package com.mahjongcoach.app.vision

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Live tile detector backed by [RoboflowInfer]. Highest-priority recognizer
 * when a Roboflow API key is configured — the practical answer to the bundled
 * on-device model being domain-mismatched.
 *
 * Throttling, snap, busy reporting, and frame capture mirror
 * [LlmHandRecognizer] so the Coach overlay state reuses across both.
 */
class RoboflowHandRecognizer(
    private val apiKey: String,
    private val modelId: String,
    private val onCounts: (IntArray) -> Unit,
    private val onBoxes: (List<DetectedBox>) -> Unit = {},
    private val onBitmap: (Bitmap) -> Unit = {},
    private val onBusy: (Boolean) -> Unit = {},
    private val confidence: Float = 0.30f,
    private val minIntervalMs: Long = 3_000L,
) : HandRecognizer {

    private val scope = CoroutineScope(SupervisorJob())
    private val inFlight = AtomicBoolean(false)
    private val lastFiredAt = AtomicLong(0L)
    @Volatile private var snapPending = false
    @Volatile private var latestBoxes: List<DetectedBox> = emptyList()

    override val lastBoxes: List<DetectedBox> get() = latestBoxes

    /** Force the next [recognize] call to skip the throttle window. */
    fun requestSnap() { snapPending = true }

    override fun recognize(image: ImageProxy): IntArray? {
        val now = System.currentTimeMillis()
        val cooled = minIntervalMs != Long.MAX_VALUE && now - lastFiredAt.get() >= minIntervalMs
        val allowed = snapPending || cooled
        if (!allowed || !inFlight.compareAndSet(false, true)) {
            image.close(); return null
        }
        snapPending = false
        lastFiredAt.set(now)

        val bitmap = runCatching { image.toRgbBitmap() }.getOrNull()
        image.close()
        if (bitmap == null) { inFlight.set(false); return null }
        onBitmap(bitmap)

        onBusy(true)
        scope.launch {
            val result = runCatching {
                RoboflowInfer.infer(apiKey, modelId, bitmap, confidence)
            }.getOrElse {
                Log.w(TAG, "infer failed: ${it.message}")
                null
            }
            inFlight.set(false)
            onBusy(false)
            if (result != null) {
                if (result.boxes.isNotEmpty()) {
                    latestBoxes = result.boxes
                    onBoxes(result.boxes)
                }
                onCounts(result.counts)
            }
        }
        return null
    }

    fun close() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private companion object {
        const val TAG = "RoboflowHandRecognizer"
    }
}
