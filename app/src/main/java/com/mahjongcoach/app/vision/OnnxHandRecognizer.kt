package com.mahjongcoach.app.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.mahjongcoach.engine.Tiles
import java.nio.FloatBuffer

/**
 * On-device YOLO-based tile detector. The model is loaded from
 * `assets/tiles.onnx` (or any name passed via [modelAsset]); when the asset is
 * missing we fail fast in [isAvailable] so the host can fall back to a stub
 * without throwing.
 *
 * The detector is assumed to be **34-class riichi-style** (man/pin/sou + 7
 * honors); we keep only the 27 m/p/s classes and remap them to engine indices.
 * If your model uses a different class order, point [classMap] at the right
 * permutation.
 *
 * Each [recognize] call runs the model synchronously on the analyzer thread
 * (CameraX gives us one frame at a time with `STRATEGY_KEEP_ONLY_LATEST`), so
 * the smoother window absorbs the latency. Inference at 640×640 fp32 is
 * roughly 70–120 ms on a mid-2024 phone — fine for live coaching.
 *
 * ⚠️ Same ethical boundary as the rest of the recognizer layer: this is for
 * the player's own hand only. Class-mask any face-down or wall tiles in
 * post-processing if the dataset includes them.
 */
class OnnxHandRecognizer(
    context: Context,
    private val onCounts: (IntArray) -> Unit,
    private val onBoxes: (List<DetectedBox>) -> Unit = {},
    private val onBitmap: (Bitmap) -> Unit = {},
    modelAsset: String = MODEL_ASSET,
    private val confidence: Float = 0.30f,
    private val nmsIou: Float = 0.45f,
    private val classMap: IntArray = MJ42_TO_SICHUAN27,
) : HandRecognizer {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val inputW: Int
    private val inputH: Int
    private val numClassesFromModel: Int

    /**
     * The class map actually used at runtime. Prefer one derived from the
     * model's embedded `names` metadata (so any Ultralytics mahjong export
     * self-describes); fall back to the [classMap] passed in.
     */
    private val activeMap: IntArray

    @Volatile private var latestBoxes: List<DetectedBox> = emptyList()
    override val lastBoxes: List<DetectedBox> get() = latestBoxes

    @Volatile private var rawCandidateCount = 0
    private var frameCounter = 0

    init {
        val bytes = context.assets.open(modelAsset).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            try {
                addNnapi()                       // hardware accel where available
            } catch (_: Throwable) { /* ok */ }
        }
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.first()
        val info = session.inputInfo[inputName]!!.info as TensorInfo
        val shape = info.shape                   // [1, 3, H, W]
        // Ultralytics exports often have DYNAMIC H/W (shape == -1). Feeding a
        // -1-sized tensor produces garbage detections, which is the classic
        // "detection is horrible" cause. Fall back to a square default.
        inputH = shape.getOrNull(2)?.toInt()?.takeIf { it > 0 } ?: DEFAULT_INPUT
        inputW = shape.getOrNull(3)?.toInt()?.takeIf { it > 0 } ?: DEFAULT_INPUT
        // Detector output is [1, 4 + nc, n] for YOLOv8/v9-style models; the
        // raw class count is `dim1 - 4`. We tolerate "+1 objectness" too.
        val outInfo = session.outputInfo.values.first().info as TensorInfo
        val nc = (outInfo.shape.getOrNull(1)?.toInt() ?: 0).minus(4).coerceAtLeast(classMap.size)
        numClassesFromModel = nc

        // Try to self-configure the class map from the model's embedded names.
        val namesRaw = runCatching { session.metadata.customMetadata["names"] }.getOrNull()
        val derived = namesRaw?.let { runCatching { mapFromNames(it) }.getOrNull() }
        activeMap = derived ?: classMap
        Log.i(
            TAG,
            "session ready: input=$inputName ${inputW}x${inputH} " +
                "(modelShape=${shape.joinToString("x")}) outShape=${outInfo.shape.joinToString("x")} " +
                "classes=$nc conf=$confidence iou=$nmsIou " +
                "classMap=${if (derived != null) "from-model-names" else "hardcoded-fallback"} " +
                "kept=${activeMap.count { it >= 0 }}",
        )
    }

    override fun recognize(image: ImageProxy): IntArray? {
        val bitmap = runCatching { image.toRgbBitmap() }.getOrNull()
        image.close()
        if (bitmap == null) return null
        onBitmap(bitmap)

        val (letterboxed, scale, padX, padY) = letterbox(bitmap, inputW, inputH)
        val tensor = letterboxed.toFloatTensor(env)
        val outputs = session.run(mapOf(inputName to tensor))
        val raw = outputs[0].value
        val boxes = postprocess(raw, scale, padX, padY, bitmap.width, bitmap.height)
        outputs.close(); tensor.close()

        latestBoxes = boxes
        onBoxes(boxes)
        val counts = countsFromBoxes(boxes)
        onCounts(counts)

        // Periodic diagnostic (every ~30 frames). One logcat capture while
        // pointing at a known hand tells us the whole story: how many raw
        // candidates cleared the confidence gate, how many survived NMS, and
        // the top tiles + scores. If rawCandidates is ~0 the model/preproc is
        // wrong; if it's high but tiles are wrong the suit map is wrong; if
        // scores are all ~0.3 the model is just unsure (domain mismatch).
        if (frameCounter++ % 30 == 0) {
            val top = boxes.sortedByDescending { it.score }.take(3)
                .joinToString(", ") { "${Tiles.cnName(it.tileIndex)}@${"%.2f".format(it.score)}" }
            Log.i(
                TAG,
                "frame#$frameCounter rawCandidates=$rawCandidateCount keptAfterNMS=${boxes.size} " +
                    "top=[$top]",
            )
        }
        return counts
    }

    fun close() = runCatching { session.close() }.let { Unit }

    // --- pre/post -----------------------------------------------------------

    private data class Letterboxed(val bitmap: Bitmap, val scale: Float, val padX: Int, val padY: Int)

    private fun letterbox(src: Bitmap, dstW: Int, dstH: Int): Letterboxed {
        val scale = minOf(dstW.toFloat() / src.width, dstH.toFloat() / src.height)
        val nw = (src.width * scale).toInt()
        val nh = (src.height * scale).toInt()
        val resized = Bitmap.createScaledBitmap(src, nw, nh, true)
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val padX = (dstW - nw) / 2
        val padY = (dstH - nh) / 2
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.argb(255, 114, 114, 114))
        canvas.drawBitmap(resized, padX.toFloat(), padY.toFloat(), null)
        return Letterboxed(out, scale, padX, padY)
    }

    private fun Bitmap.toFloatTensor(env: OrtEnvironment): OnnxTensor {
        val w = width; val h = height
        val px = IntArray(w * h)
        getPixels(px, 0, w, 0, 0, w, h)
        val buf = FloatBuffer.allocate(3 * w * h)
        // NCHW order: R-plane, then G-plane, then B-plane.
        for (c in 0 until 3) {
            val shift = (2 - c) * 8        // R = bits 16, G = 8, B = 0
            for (i in 0 until w * h) {
                val v = ((px[i] shr shift) and 0xFF) / 255f
                buf.put(c * w * h + i, v)
            }
        }
        return OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, h.toLong(), w.toLong()))
    }

    private fun postprocess(
        raw: Any,
        scale: Float, padX: Int, padY: Int,
        origW: Int, origH: Int,
    ): List<DetectedBox> {
        // YOLOv8 / v9 output shape is [1][4 + nc][n_anchors]. Cast safely.
        @Suppress("UNCHECKED_CAST")
        val arr = (raw as? Array<Array<FloatArray>>) ?: return emptyList()
        val plane = arr[0]
        val nFields = plane.size
        val nAnchors = plane[0].size
        val nc = nFields - 4
        if (nc <= 0) return emptyList()

        val raws = ArrayList<DetectedBox>(64)
        for (i in 0 until nAnchors) {
            var bestC = -1; var bestS = 0f
            for (c in 0 until nc) {
                val s = plane[4 + c][i]
                if (s > bestS) { bestS = s; bestC = c }
            }
            if (bestS < confidence) continue
            val engineIdx = if (bestC in activeMap.indices) activeMap[bestC] else -1
            if (engineIdx < 0) continue                    // honors / bonus dropped

            val cxModel = plane[0][i]
            val cyModel = plane[1][i]
            val wModel = plane[2][i]
            val hModel = plane[3][i]
            // Undo letterbox to source-pixel coords, then normalise.
            val cxSrc = (cxModel - padX) / scale
            val cySrc = (cyModel - padY) / scale
            val wSrc = wModel / scale
            val hSrc = hModel / scale
            raws.add(
                DetectedBox(
                    cx = (cxSrc / origW).coerceIn(0f, 1f),
                    cy = (cySrc / origH).coerceIn(0f, 1f),
                    w = (wSrc / origW).coerceIn(0f, 1f),
                    h = (hSrc / origH).coerceIn(0f, 1f),
                    tileIndex = engineIdx,
                    score = bestS,
                ),
            )
        }
        rawCandidateCount = raws.size
        return nms(raws, nmsIou)
    }

    private fun nms(boxes: List<DetectedBox>, iouThresh: Float): List<DetectedBox> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val kept = ArrayList<DetectedBox>()
        while (sorted.isNotEmpty()) {
            val top = sorted.removeAt(0)
            kept.add(top)
            sorted.removeAll { other -> iou(top, other) > iouThresh && other.tileIndex == top.tileIndex }
        }
        return kept
    }

    private fun iou(a: DetectedBox, b: DetectedBox): Float {
        val ax1 = a.cx - a.w / 2; val ay1 = a.cy - a.h / 2
        val ax2 = a.cx + a.w / 2; val ay2 = a.cy + a.h / 2
        val bx1 = b.cx - b.w / 2; val by1 = b.cy - b.h / 2
        val bx2 = b.cx + b.w / 2; val by2 = b.cy + b.h / 2
        val ix1 = maxOf(ax1, bx1); val iy1 = maxOf(ay1, by1)
        val ix2 = minOf(ax2, bx2); val iy2 = minOf(ay2, by2)
        val iw = (ix2 - ix1).coerceAtLeast(0f); val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        val ua = a.w * a.h + b.w * b.h - inter
        return if (ua > 0f) inter / ua else 0f
    }

    private fun countsFromBoxes(boxes: List<DetectedBox>): IntArray {
        val counts = IntArray(Tiles.TILE_KINDS)
        boxes.forEach { b ->
            if (counts[b.tileIndex] < Tiles.COPIES) counts[b.tileIndex]++
        }
        return counts
    }

    /**
     * Build the class→engine map from an Ultralytics `names` metadata string
     * like `{0: 'b', 1: 'b1', ..., 33: 'w1', ...}`. Works across labeling
     * conventions (colonel b/t/w, smilee/MJOD m/s/t) by inspecting the actual
     * prefixes:
     *
     *  - `f*` (flowers) and `z*` (honors) → always dropped.
     *  - single letters with no rank (winds/dragons) → dropped.
     *  - a `<letter><1..9>` prefix is a numbered SUIT only if its max rank is
     *    ≥ 5 — this separates real suits (1..9) from seasons (`s1..s4`).
     *  - suit identity by letter: m/w/c → man, p/t/d → pin, s/b → sou.
     *
     * Returns null if nothing parseable, so the caller falls back.
     */
    private fun mapFromNames(raw: String): IntArray? {
        val entryRe = Regex("""(\d+)\s*:\s*'([^']*)'""")
        val names = entryRe.findAll(raw).associate { m ->
            m.groupValues[1].toInt() to m.groupValues[2].trim().lowercase()
        }
        if (names.isEmpty()) return null

        // Max rank seen per prefix letter (to tell suits from seasons/flowers).
        val maxRank = HashMap<Char, Int>()
        val tileRe = Regex("""^([a-z])(\d)$""")
        names.values.forEach { n ->
            tileRe.matchEntire(n)?.let {
                val p = it.groupValues[1][0]; val r = it.groupValues[2].toInt()
                maxRank[p] = maxOf(maxRank[p] ?: 0, r)
            }
        }
        fun suitBase(p: Char): Int? = when (p) {
            'm', 'w', 'c' -> 0       // man 万
            'p', 't', 'd' -> 9       // pin 筒
            's', 'b' -> 18           // sou 条
            else -> null
        }

        val size = (names.keys.maxOrNull() ?: -1) + 1
        if (size <= 0) return null
        val map = IntArray(size) { -1 }
        var mapped = 0
        for ((idx, n) in names) {
            if (n.startsWith("f") || n.startsWith("z")) continue   // bonus / honors
            val mt = tileRe.matchEntire(n) ?: continue              // single letters skip
            val p = mt.groupValues[1][0]; val rank = mt.groupValues[2].toInt()
            if ((maxRank[p] ?: 0) < 5) continue                    // seasons etc., not a suit
            val base = suitBase(p) ?: continue
            map[idx] = base + (rank - 1)
            mapped++
        }
        return if (mapped >= 9) map else null   // need at least one full suit
    }

    companion object {
        private const val TAG = "OnnxHandRecognizer"
        const val MODEL_ASSET = "tiles.onnx"

        /** Fallback square input size when the model declares dynamic H/W. */
        private const val DEFAULT_INPUT = 640

        /** True iff the model asset is shipped in this APK. */
        fun isAvailable(context: Context): Boolean = runCatching {
            context.assets.list("")?.contains(MODEL_ASSET) == true
        }.getOrDefault(false)

        /**
         * Standard riichi label order → engine `Tiles` index, dropping honors.
         * Kept for reference / models that use this convention.
         * Classes 0..8 = 1m..9m, 9..17 = 1p..9p, 18..26 = 1s..9s,
         * 27..33 = z1..z7 (east/south/west/north/white/green/red) → DROP.
         */
        val RIICHI34_TO_SICHUAN27: IntArray =
            IntArray(34) { i -> if (i < 27) i else -1 }

        /**
         * Class map for the shipped `tiles.onnx` (colonel-aureliano YOLOv8,
         * 42 classes). Its embedded `names` dict is:
         * ```
         * 0:b  1-9:b1..b9   10:e  11-14:f1..f4  15:g  16:n  17:r  18:s
         * 19-22:s1..s4  23-31:t1..t9  32:w  33-41:w1..w9
         * ```
         * Three numbered suits (b/t/w) + 7 honors (b=white, e/s/w/n winds,
         * r=red, g=green) + flowers (f) + seasons (s). Sichuan uses only the
         * three numbered suits and no honors/bonus.
         *
         * Suit assignment is a best guess by pinyin (Wàn万=w, Tǒng筒=t,
         * Bamboo条=b): w*→man, t*→pin, b*→sou. The on-device first-detection
         * log (see [recognize]) confirms or refutes this; if a known tile
         * reads wrong, permute the three [suitBase] offsets below.
         */
        val MJ42_TO_SICHUAN27: IntArray = buildMj42Map()

        // man=0, pin=9, sou=18 are the engine-index bases per suit.
        private fun buildMj42Map(): IntArray {
            val map = IntArray(42) { -1 }
            val sou = 18; val pin = 9; val man = 0
            for (r in 0..8) {
                map[1 + r] = sou + r       // b1..b9  → sou 1..9
                map[23 + r] = pin + r      // t1..t9  → pin 1..9
                map[33 + r] = man + r      // w1..w9  → man 1..9
            }
            // index 0 (b/white), 10 (e), 11-14 (f1-4), 15 (g), 16 (n), 17 (r),
            // 18 (s), 19-22 (s1-4), 32 (w) all stay -1 (dropped).
            return map
        }
    }
}
