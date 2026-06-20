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
    private val classMap: IntArray = RIICHI34_TO_SICHUAN27,
) : HandRecognizer {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val inputW: Int
    private val inputH: Int
    private val numClassesFromModel: Int

    @Volatile private var latestBoxes: List<DetectedBox> = emptyList()
    override val lastBoxes: List<DetectedBox> get() = latestBoxes

    @Volatile private var firstDetectionLogged = false

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
        inputH = shape[2].toInt()
        inputW = shape[3].toInt()
        // Detector output is [1, 4 + nc, n] for YOLOv8/v9-style models; the
        // raw class count is `dim1 - 4`. We tolerate "+1 objectness" too.
        val outInfo = session.outputInfo.values.first().info as TensorInfo
        val nc = (outInfo.shape[1].toInt() - 4).coerceAtLeast(34)
        numClassesFromModel = nc
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

        // One-shot diagnostic: if the very first detection looks unreasonable,
        // the class-map almost certainly mismatches the model's actual label
        // order. The log line shows the top box's (rawClass → engineTile)
        // mapping so a misorder is obvious in logcat.
        if (!firstDetectionLogged && boxes.isNotEmpty()) {
            firstDetectionLogged = true
            val top = boxes.maxByOrNull { it.score }!!
            Log.i(
                TAG,
                "first detection: tileIndex=${top.tileIndex} (${Tiles.cnName(top.tileIndex)}) " +
                    "score=${"%.2f".format(top.score)} — if this name doesn't match the tile you " +
                    "pointed at, your classMap is misordered. See OnnxHandRecognizer.RIICHI34_TO_SICHUAN27.",
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
            val engineIdx = if (bestC in classMap.indices) classMap[bestC] else -1
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

    companion object {
        private const val TAG = "OnnxHandRecognizer"
        const val MODEL_ASSET = "tiles.onnx"

        /** True iff the model asset is shipped in this APK. */
        fun isAvailable(context: Context): Boolean = runCatching {
            context.assets.list("")?.contains(MODEL_ASSET) == true
        }.getOrDefault(false)

        /**
         * Standard riichi label order → engine `Tiles` index, dropping honors.
         * Classes 0..8 = 1m..9m, 9..17 = 1p..9p, 18..26 = 1s..9s,
         * 27..33 = z1..z7 (east/south/west/north/white/green/red) → DROP.
         */
        val RIICHI34_TO_SICHUAN27: IntArray =
            IntArray(34) { i -> if (i < 27) i else -1 }
    }
}
