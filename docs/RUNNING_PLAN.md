# Running plan — make the trainer actually smart

Five chunks, ordered by user value. Each chunk is self-contained and ships
its own commit, so if a session ends mid-plan the next one picks up cleanly
from `git log` + this file.

Working machine has the Android toolchain (see CLAUDE.md), device
`58079ba3` connected, OpenAI-compat LLM configured via `secrets.json`.

## Phase 1 — On-device tile detector (ONNX)

Make the live preview actually detect tiles. The detailed pickup plan was
written last session in `docs/ONNX_VISION.md`; this phase executes it plus
two extras:

- **AR overlay (variant C, finally real):** `FloatingTileLabels` composable
  that takes the raw boxes from the recognizer and renders class-name chips
  at each box's centre over the preview, replacing today's
  `DetectedHandStrip` when boxes are available. The strip stays as the
  fallback for stub / LLM-vision modes.
- **Box plumbing:** widen `HandRecognizer` to optionally surface boxes —
  a parallel `recognizeBoxes(image): List<DetectedBox>?` so the strip-fallback
  path doesn't break. `DetectedBox(rect: RectF, classIdx: Int, score: Float)`.

### Files
- `app/build.gradle.kts` — add `com.microsoft.onnxruntime:onnxruntime-android`
- `app/src/main/java/com/mahjongcoach/app/vision/OnnxHandRecognizer.kt` (new)
- `app/src/main/java/com/mahjongcoach/app/vision/ImageProxyBitmap.kt` (new — lift the YUV→Bitmap from `LlmHandRecognizer.kt`)
- `app/src/main/java/com/mahjongcoach/app/vision/DetectedBox.kt` (new)
- `app/src/main/java/com/mahjongcoach/app/vision/HandRecognizer.kt` — add optional `recognizeBoxes`
- `app/src/main/java/com/mahjongcoach/app/coach/CoachScreen.kt` — wire ONNX when asset present; pipe boxes through to the overlay
- `app/src/main/java/com/mahjongcoach/app/coach/CoachOverlays.kt` — add `FloatingTileLabels`
- `.gitignore` — ignore `app/src/main/assets/tiles.onnx`
- `docs/VISION.md` — manual model-fetch note

### Verification
1. Build clean (without the asset) — Stub fallback path; preview shows, no
   detections.
2. Drop `tiles.onnx` into assets; rebuild + install. Logcat confirms
   `OrtSession` construction.
3. Push an internet sample image to `/sdcard/Pictures/`, open it in the
   Score tab "from photo" affordance (added in Phase 2), confirm classes
   roughly match.
4. Live test: phone over a real hand; floating chips appear over each tile.
5. Add an honor tile → silently dropped (class mask check).

## Phase 2 — Score from photo (gallery + camera)

The Score tab today is engine-only — user types a hand. Add a "from photo"
affordance: pick from gallery OR snap with camera, run through
`LlmClient.recognizeHand`, populate the hand string, compute the score.

This exercises the LLM-vision path end-to-end against real photos and
gives a high-leverage feature for free.

### Files
- `app/src/main/AndroidManifest.xml` — `READ_MEDIA_IMAGES` (Android 13+) for gallery on newer devices
- `app/src/main/java/com/mahjongcoach/app/score/ScoreScreen.kt` — add "from photo" row with two buttons (gallery picker via `ActivityResultContracts.PickVisualMedia`, camera snap reusing CameraX `ImageCapture`)
- `app/src/main/java/com/mahjongcoach/app/score/PhotoScorePanel.kt` (new) — bottom card that shows the picked image thumbnail, the recognized hand string, the score breakdown, and a "fix tiles" affordance
- `app/src/main/java/com/mahjongcoach/app/llm/LlmClient.kt` — already has `recognizeHand`; reuse as-is

### Verification
1. Pick a sample mahjong hand image from gallery → recognized hand
   string appears within ~3s with a vision-capable model configured.
2. Tap "fix tiles" → opens the existing edit sheet pre-loaded with the
   recognized counts; tweak; tap "score now" → engine output renders.
3. Tap "snap" → CameraX one-shot capture → same pipeline.
4. Bad image (LLM returns `{"hand":""}`) → friendly status, not a crash.

## Phase 3 — Assistant strategy presets

Replace today's empty Assistant tab placeholder text with a row of preset
chips that pre-fill the prompt. Each chip injects current `GameState` as
context, so the model sees the hand without the user having to type it.

### Presets (initial set; trivial to extend)
1. **"Best discard now"** — runs `recommend_discard` tool on current hand.
2. **"Pick void suit (定缺)"** — explain which suit to declare given the hand distribution.
3. **"Should I call 碰?"** — given the seen-pile, danger of opening the hand.
4. **"Defensive read"** — danger estimate from public discards.
5. **"Score this hand"** — runs `score_hand` tool on current hand as revealed.

The current `GameState` (hand + seen + melds + void suit) goes into the
user message as a structured prefix so the model has facts to ground on.

### Files
- `app/src/main/java/com/mahjongcoach/app/assistant/Presets.kt` (new)
- `app/src/main/java/com/mahjongcoach/app/assistant/AssistantScreen.kt` — add `LazyRow` of preset chips above the input
- `app/src/main/java/com/mahjongcoach/app/MainActivity.kt` — pass `GameState` into `AssistantScreen` so presets can read it
- `engine/src/main/kotlin/com/mahjongcoach/engine/Assistant.kt` — extend `SYSTEM_PROMPT` with a one-paragraph note: "When the user message includes a `[STATE]…[/STATE]` block, treat that as the current hand and call tools accordingly."

### Verification
On device with a configured LLM endpoint:
1. Manually enter a 14-tile hand on Coach. Switch to Assistant.
2. Tap "Best discard now" — chat shows pre-filled prompt with `[STATE]` block; assistant response includes the engine's discard (tool call routed correctly).
3. Tap "Pick void suit" — model explains in plain language; no tool call expected (engine has no 定缺-suggestion tool yet — followup ticket).
4. Tap "Score this hand" (with 14-tile hand) → `score_hand` tool invoked.

## Phase 4 — UI alignment + polish pass

Audit every screen for the alignment issues the user flagged. Known
suspects:
- **Settings tab:** OpenAI-compat section's labels overflow on narrow phones; the JSON textarea collapses to single-line on rotation; "Coach (live mode)" SwitchRows don't truncate long subtitles cleanly.
- **Coach overlays:** AdviceBanner sometimes overlaps the system status bar in landscape on devices with a notch; FAB row competes with the gesture nav bar.
- **Assistant:** input row doesn't respect IME insets (keyboard covers Send).
- **Score:** input field too wide on tablet form-factor, no card backing.
- **Sheet:** scroll-clip when meld + seen rows both populated.

### Strategy
Rather than 5 ad-hoc fixes, introduce one small "layout primitive" file
with consistent spacing/insets and refactor each screen to use it. Keeps
the diff readable.

### Files
- `app/src/main/java/com/mahjongcoach/app/ui/Layout.kt` (new) — `screenPadding()` (status + nav bar insets via WindowInsets), `cardCorners`, `sectionGap` constants.
- All 4 screens + Coach overlays get a once-over.

### Verification
- Each screen rendered correctly in portrait + landscape (where applicable).
- IME doesn't cover input rows.
- Status bar / nav bar overlap eliminated by `windowInsetsPadding`.
- Screenshot pass committed under `docs/screenshots/` for future regression.

## Phase 5 — Trainer feedback loop

When the user opens the edit sheet and changes a recognized count
(stub or detector misread), log the original frame + corrected counts
to an internal directory. This is the seed for an "improve itself"
phase later — fold the corrections into a fine-tuning set.

### Files
- `app/src/main/java/com/mahjongcoach/app/data/CorrectionLog.kt` (new) — writes JSONL into `context.filesDir/corrections/`
- `app/src/main/java/com/mahjongcoach/app/coach/CoachScreen.kt` — capture the last frame the recognizer saw; on sheet close, if hand differs from the recognizer's last output, log the pair.

### Verification
- Sheet edit produces a `corrections/*.jsonl` line + `corrections/*.jpg` next to it.
- Lines are valid JSON (`jq` smoke).

---

## Out of scope (next-next session)

- Re-training the detector on MJOD-2136 with a clean 27-class head.
- Edge LLM (`EdgeLlmClient`) actual implementation.
- Continuous ASR (current `BoardAudioListener` is single-utterance).
- Score-context editor UI (dora indicators / ippatsu / haitei).

## Session-by-session checkpoints

Commit after each phase. If a session runs out, the next one reads
`git log --oneline -10` + this file and resumes at the next unchecked
phase.

- [ ] Phase 1 — ONNX detector
- [ ] Phase 2 — Score from photo
- [ ] Phase 3 — Assistant presets
- [ ] Phase 4 — UI alignment
- [ ] Phase 5 — Correction log
