# Plan — on-device ONNX tile recognizer (Option A)

> **Status 2026-06-20:** model downloaded + bundled, code wired, class map
> corrected for the real 42-class model, APK builds + installs (140 MB).
> NOT yet confirmed against real tiles — device was locked during every test
> attempt. See "Model selection & safety" and "Class mapping — ACTUAL" below.

Pick-up plan for the next session. The Coach UI, recognizer interface, and
`RecognitionSmoother` are all in place; the only thing missing is a real CV
backend. This wires one in by vendoring an existing YOLO ONNX file and running
it through ONNX Runtime Mobile.

## Model selection & safety (2026-06-20)

Candidates compared (from the earlier web search):

| Model | Weights shipped? | Classes | Notes |
|---|---|---|---|
| **colonel-aureliano/Embedded-Mahjong-Bot** | **yes** (.onnx) | 42 | YOLOv8, the only drop-in. **Selected.** |
| smilee3998/mahjong_detection | no (train yourself) | ~34-42 | YOLOv11 pipeline only |
| lissa2077/Mahjong-Detection | no (weights excluded) | ? | YOLOv3/v4-tiny |
| Roboflow Universe (several) | API/download | 34-42 | hosted; ToS license |

**Selected: colonel-aureliano** — it's the only one with a runnable weight
file, so it's the fastest path to "detection works today."

**Safety check on the downloaded `tiles.onnx`:**
- Header: valid ONNX protobuf, `producer = pytorch 2.0.1`.
- Graph: textbook **YOLOv8** (`model.0..model.22`, DFL head, Conv/Sigmoid/
  Mul/Concat/Split/Softmax/Reshape). **No custom operators**, no embedded
  scripts — ONNX Runtime only executes this fixed op graph, so there's no
  code-execution surface. Standard, inert NN weights.
- Size: 66 MB fp32 (the `-fp32` in the filename is accurate). Input `images`,
  resolution read dynamically from the model (`-800-` ⇒ likely 800×800, so
  expect ~200-500 ms/inference fp32 on a mid phone — fine behind the 7-frame
  smoother, but int8-quantize before shipping).
- License: **still undeclared upstream** → personal/debug build only. The
  binary is git-ignored; for distribution, retrain on MJOD-2136 (Apache 2.0).

Verdict: safe to load and run for a personal build; do not redistribute the
weights.

## Goal

Replace `StubHandRecognizer` (which returns `null`) with `OnnxHandRecognizer`
that:

1. Loads an ONNX tile-detection model from `app/src/main/assets/tiles.onnx`.
2. For each `ImageProxy`, runs detection + classification, NMS-filters the
   boxes, masks out non-Sichuan classes (honors / bonus), maps the rest to the
   engine's 27-tile index space, and submits the counts to
   `RecognitionSmoother`.
3. Pushes the smoother's stable output to `state.setHandCounts(...)`.

The `HandRecognizer` interface and `CoachScreen`'s wiring stay unchanged — only
the concrete implementation and its model asset are new.

## Source weights

**Primary candidate:** `colonel-aureliano/Embedded-Mahjong-Bot` ships
`tile_classifier/onnx/weights/best-v4-800-half-fp32.onnx` — a YOLOv9-based
detector trained on a Roboflow tile dataset. License is **not declared**, so
treat this as personal-build-only and surface that in `OnnxHandRecognizer`'s
KDoc. If we end up wanting to distribute, swap to the clean-license path
(Option B in the previous summary: re-train on MJOD-2136, Apache 2.0).

Repo: <https://github.com/colonel-aureliano/Embedded-Mahjong-Bot>
File: `tile_classifier/onnx/weights/best-v4-800-half-fp32.onnx`

Step 1 of the next session is to download that file and drop it at
`app/src/main/assets/tiles.onnx`. We should NOT commit the binary unless we've
clarified license — instead, add `app/src/main/assets/tiles.onnx` to
`.gitignore` next to the secrets pattern and document the manual fetch in
`docs/VISION.md`.

## Class mapping — ACTUAL (decoded 2026-06-20)

The vendored model is **not** riichi-34. Reading its embedded `names` dict
(Ultralytics writes it into the ONNX metadata, recovered via a raw string
scan of `tiles.onnx`) shows **42 classes**:

```
0:b   1-9:b1..b9   10:e  11-14:f1..f4  15:g  16:n  17:r  18:s
19-22:s1..s4   23-31:t1..t9   32:w   33-41:w1..w9
```

Three numbered suits `b* / t* / w*`, seven honors (`b`=white/board,
`e/s/w/n`=winds, `r`=red 中, `g`=green 發), flowers `f1-4`, seasons `s1-4`.

Sichuan needs only the three numbered suits, no honors/bonus. The map in
`OnnxHandRecognizer.MJ42_TO_SICHUAN27` (and the `buildMj42Map()` that
constructs it) does:

```
b1..b9 (idx 1..9)   → sou (engine 18..26)
t1..t9 (idx 23..31) → pin (engine  9..17)
w1..w9 (idx 33..41) → man (engine  0..8)
all else            → -1 (dropped)
```

**The suit assignment (which of b/t/w is man/pin/sou) is a best guess by
pinyin** — Wàn万=w, Tǒng筒=t, Bamboo条=b. This has NOT been confirmed against
real tiles yet (device was locked during testing). The first-detection
diagnostic `Log.i(OnnxHandRecognizer, "first detection: …")` prints the top
box's tile name on the first frame — point the camera at a known tile (say a
clear 5万) and if logcat shows a different tile, permute the three base
offsets in `buildMj42Map()`. `engine.Tiles.cnName(i)` is the sanity check.

## Android dependency

```kotlin
// app/build.gradle.kts
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
```

(Pin a recent stable version — 1.18+ has good ARM throughput. Confirm size
impact; the AAR is ~10 MB.)

## File-level plan

### `app/src/main/java/com/mahjongcoach/app/vision/OnnxHandRecognizer.kt`

New file, implements `HandRecognizer`. Lifecycle owned by `CoachScreen` (it
calls `close()` when leaving the tab so the OrtSession releases native
memory).

```kotlin
class OnnxHandRecognizer(
    context: Context,
    private val onCounts: (IntArray) -> Unit,
    modelAsset: String = "tiles.onnx",
    private val confidence: Float = 0.30f,
    private val nmsIou: Float = 0.45f,
) : HandRecognizer {
    private val env = OrtEnvironment.getEnvironment()
    private val session = run {
        val bytes = context.assets.open(modelAsset).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            // setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // addNnapi() / addXnnpack() if available — guard with try/catch.
        }
        env.createSession(bytes, opts)
    }
    private val inputName = session.inputNames.first()
    private val inputW: Int; private val inputH: Int
    init {
        val shape = session.inputInfo[inputName]!!.info as TensorInfo
        inputH = shape.shape[2].toInt(); inputW = shape.shape[3].toInt()
    }

    override fun recognize(image: ImageProxy): IntArray? {
        val bitmap = image.toBitmap() ?: run { image.close(); return null }
        image.close()
        val tensor = bitmap.letterboxTo(inputW, inputH).toFloatTensor(env)
        val out = session.run(mapOf(inputName to tensor))
        val boxes = postprocessYolo(out, inputW, inputH, confidence, nmsIou)
        val counts = boxesToCounts(boxes)   // 27-length; honors masked
        onCounts(counts)                    // host smoother will pick this up
        return null   // async push pattern, same as LlmHandRecognizer
    }

    fun close() { session.close() }
}
```

Helpers (all private in the same file):

- `Bitmap.letterboxTo(w, h)` — preserves aspect, pads with gray.
- `Bitmap.toFloatTensor(env)` — NCHW, RGB, normalised to [0,1]. Reuse a
  `FloatBuffer` across calls to avoid GC pressure.
- `postprocessYolo(OrtResult, …)` — YOLOv8/v9 output is typically
  `[1, 4+nc, n_boxes]` (cx, cy, w, h, then per-class scores). NMS per class
  with the IOU threshold.
- `boxesToCounts(boxes)`:
  ```kotlin
  val counts = IntArray(Tiles.TILE_KINDS)
  boxes.forEach { b ->
      val engineIdx = SICHUAN_FROM_DETECTOR[b.cls] ?: return@forEach
      if (counts[engineIdx] < Tiles.COPIES) counts[engineIdx]++
  }
  return counts
  ```

Reuse the YUV→Bitmap converter already in `LlmHandRecognizer.kt` — extract it
to `vision/ImageProxyBitmap.kt` so both adapters share it.

### `app/src/main/java/com/mahjongcoach/app/coach/CoachScreen.kt`

Swap the `else` branch of the recognizer-pick to use ONNX when a model is
shipped. Simplest gate: try to construct `OnnxHandRecognizer`; fall back to
`StubHandRecognizer` if the asset is missing. That keeps clean-installs
working without a model and avoids a new Settings flag.

```kotlin
val recognizer: HandRecognizer = remember(settings.useLlmVision, settings.backend) {
    when {
        settings.useLlmVision -> LlmHandRecognizer(client = settings.buildClient(), onCounts = ::push)
        OnnxHandRecognizer.isAvailable(context) -> OnnxHandRecognizer(context, onCounts = ::push)
        else -> StubHandRecognizer()
    }
}
```

`push` is `{ counts -> val stable = smoother.submit(counts); if (smoother.isStable()) state.setHandCounts(stable) }` — lift it out of the existing
`LlmHandRecognizer` block so both adapters share it.

Add `DisposableEffect` cleanup for `OnnxHandRecognizer.close()` (already there
for `LlmHandRecognizer`).

### `app/build.gradle.kts`

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
```

Also add to `android.packaging.resources.excludes` if ORT ships any
META-INF/* duplicates (it usually does):
```
"META-INF/native-image/**",
"META-INF/ASL2.0",
```

### `.gitignore`

```
# CV model weights baked into assets. License-dependent; fetch separately,
# see docs/VISION.md / docs/ONNX_VISION.md.
app/src/main/assets/tiles.onnx
```

### `docs/VISION.md`

Append a "Manual model fetch" section:
1. Grab `best-v4-800-half-fp32.onnx` from
   `colonel-aureliano/Embedded-Mahjong-Bot`.
2. Copy to `app/src/main/assets/tiles.onnx`.
3. Note the license caveat (personal build only until upstream clarifies).

## Verification

1. Build: `gradlew :app:assembleDebug` — should still build without the model
   asset (Stub fallback path).
2. Drop `tiles.onnx` into `app/src/main/assets/`; rebuild.
3. Install on device `58079ba3`; launch Coach. Confirm in logcat that
   `OnnxHandRecognizer` constructed successfully (no
   `OrtException` lines).
4. Point the camera at a Sichuan hand. The bottom DetectedHandStrip should
   populate with the detected tiles within ~1 s (7-frame smoother window at
   ~30 fps).
5. Add an honor (white dragon / east wind) into the frame. Confirm it's
   silently dropped — strip stays the same. That's the class mask working.
6. AdviceBanner should update to reflect engine output on the detected hand.

If detection is jittery: bump `confidence` to 0.40 and `RecognitionSmoother`
window to 11. If the strip lags behind: drop the smoother window to 5.

## Out of scope for this PR

- A clean-license model. If detection works, follow up with the MJOD-2136
  retrain path (separate ticket).
- Bounding-box overlay on the preview (the user picked Variant C, but we
  agreed to start with the fallback strip until a real detector lands —
  now we have one, so this becomes the natural next step: add
  `FloatingTileLabels` that takes the raw boxes and renders text labels at
  their centres in preview coordinates).
- Quantisation. The vendored model is fp32; if latency is bad, export an
  int8 variant via Ultralytics `model.export(format='onnx', int8=True)`.

## Session-limit notes (carrying state forward)

- Both commits this session are pushed to local `main` only (`f52b6b4`,
  `be1d23e`). Not pushed to `origin/main` yet.
- Device `58079ba3` had MIUI permission dialog blocking on first launch;
  `pm grant` + restart works around it.
- `LocalLifecycleOwner` deprecation warning in `CameraPreview.kt` is
  cosmetic — moving to `androidx.lifecycle.compose` requires adding
  `lifecycle-runtime-compose` dep; can do during the ONNX pass.
- Untracked working-tree noise to clean before next commit: `screenshot1.png`,
  `shot.png`, `ui.xml` (test artifacts from this session).
