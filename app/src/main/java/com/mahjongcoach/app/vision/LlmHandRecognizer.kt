package com.mahjongcoach.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.mahjongcoach.app.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Routes per-frame hand recognition through the configured [LlmClient]. The
 * `ImageAnalysis` analyzer fires far faster than any LLM can respond, so this
 * adapter throttles aggressively:
 *
 *  - In **always-on** mode the analyzer can call [recognize] every frame, but at
 *    most one in-flight request is allowed and we won't fire another within
 *    [minIntervalMs] of the previous one.
 *  - In **snap** mode the host sets `coolDown = false` once per tap; the next
 *    [recognize] call is allowed to proceed regardless of [minIntervalMs].
 *
 * The recognizer is intentionally fire-and-forget: it returns null immediately
 * so the synchronous analyzer pipeline doesn't block, and pushes the result
 * to [onCounts] when (if) the model replies. The Coach screen feeds [onCounts]
 * into the [com.mahjongcoach.engine.vision.RecognitionSmoother] that already
 * exists in the engine.
 */
class LlmHandRecognizer(
    private val client: LlmClient,
    private val onCounts: (IntArray) -> Unit,
    private val minIntervalMs: Long = 3_000L,
) : HandRecognizer {

    private val scope = CoroutineScope(SupervisorJob())
    private val inFlight = AtomicBoolean(false)
    private val lastFiredAt = AtomicLong(0L)
    @Volatile private var snapPending = false

    /** Force the next [recognize] call to ignore the throttle window. */
    fun requestSnap() { snapPending = true }

    override fun recognize(image: ImageProxy): IntArray? {
        val now = System.currentTimeMillis()
        val cooled = now - lastFiredAt.get() >= minIntervalMs
        val allowed = snapPending || cooled
        if (!allowed || !inFlight.compareAndSet(false, true)) {
            image.close(); return null
        }
        snapPending = false
        lastFiredAt.set(now)

        val bitmap = runCatching { image.toBitmap() }.getOrNull()
        image.close()
        if (bitmap == null) { inFlight.set(false); return null }

        scope.launch {
            val counts = runCatching { client.recognizeHand(bitmap) }.getOrNull()
            inFlight.set(false)
            if (counts != null) onCounts(counts)
        }
        return null // never returns sync — the result arrives via onCounts.
    }

    fun close() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
}

/**
 * Decode an `ImageProxy` (YUV_420_888 from CameraX `ImageAnalysis`) into an
 * upright JPEG-friendly bitmap. Handles the device rotation hint the analyzer
 * leaves on the proxy so the model sees the hand the way the user holds it.
 */
private fun ImageProxy.toBitmap(): Bitmap? {
    val yPlane = planes[0].buffer
    val uPlane = planes[1].buffer
    val vPlane = planes[2].buffer
    val ySize = yPlane.remaining()
    val uSize = uPlane.remaining()
    val vSize = vPlane.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yPlane.get(nv21, 0, ySize)
    vPlane.get(nv21, ySize, vSize)
    uPlane.get(nv21, ySize + vSize, uSize)

    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val jpeg = ByteArrayOutputStream().use { out ->
        yuv.compressToJpeg(Rect(0, 0, width, height), 80, out); out.toByteArray()
    }
    val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return raw
    val m = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
}
