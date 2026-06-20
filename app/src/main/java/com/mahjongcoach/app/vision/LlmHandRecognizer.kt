package com.mahjongcoach.app.vision

import androidx.camera.core.ImageProxy
import com.mahjongcoach.app.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

        val bitmap = runCatching { image.toRgbBitmap() }.getOrNull()
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
