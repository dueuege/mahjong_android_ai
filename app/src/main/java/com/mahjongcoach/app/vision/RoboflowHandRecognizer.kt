package com.mahjongcoach.app.vision

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.camera.core.ImageProxy
import com.mahjongcoach.engine.Tiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hosted-API tile detector backed by a Roboflow serverless model. Picked over
 * the on-device ONNX path when an API key is configured; meant as the
 * "actually-trained on diverse table photos" answer to the bundled stock
 * model being domain-mismatched.
 *
 * Wire format (Roboflow REST):
 *   POST https://serverless.roboflow.com/{modelId}?api_key={key}
 *   Content-Type: application/x-www-form-urlencoded
 *   body: base64-encoded JPEG bytes (raw, no field name)
 *
 * Response (object detection):
 *   {
 *     "predictions": [
 *        {"x":..,"y":..,"width":..,"height":..,
 *         "confidence":..,"class":"5m","class_id":..},
 *        ...
 *     ],
 *     "image": {"width": W, "height": H}
 *   }
 *   x/y are CENTER coords in source-image pixels; width/height in pixels.
 *
 * Throttling, busy reporting, and frame capture are identical to
 * [LlmHandRecognizer] so the same Coach overlay state works for both.
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
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val inFlight = AtomicBoolean(false)
    private val lastFiredAt = AtomicLong(0L)
    @Volatile private var snapPending = false
    @Volatile private var latestBoxes: List<DetectedBox> = emptyList()
    private var frameCounter = 0

    override val lastBoxes: List<DetectedBox> get() = latestBoxes

    /** Force the next [recognize] call to skip the throttle window. */
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
        onBitmap(bitmap)

        onBusy(true)
        scope.launch {
            val (counts, boxes) = runCatching { infer(bitmap) }
                .getOrElse {
                    Log.w(TAG, "infer failed: ${it.message}")
                    null to emptyList()
                }
            inFlight.set(false)
            onBusy(false)
            if (boxes.isNotEmpty()) {
                latestBoxes = boxes
                onBoxes(boxes)
            }
            if (counts != null) onCounts(counts)
        }
        return null
    }

    fun close() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private suspend fun infer(bitmap: Bitmap): Pair<IntArray?, List<DetectedBox>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val jpeg = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray()
            }
            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            val url = "https://serverless.roboflow.com/${modelId.trimStart('/')}?api_key=$apiKey"
            val req = Request.Builder().url(url)
                .post(b64.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200)}")
                parse(body, bitmap.width, bitmap.height)
            }
        }

    private fun parse(body: String, frameW: Int, frameH: Int): Pair<IntArray?, List<DetectedBox>> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("predictions") ?: return null to emptyList()
        // Roboflow returns its own image coordinate space ("image" field). If
        // it's present and differs from our bitmap, normalise via it; otherwise
        // assume the response is in source-pixel coords.
        val imgObj = root.optJSONObject("image")
        val refW = (imgObj?.optInt("width") ?: frameW).takeIf { it > 0 } ?: frameW
        val refH = (imgObj?.optInt("height") ?: frameH).takeIf { it > 0 } ?: frameH
        val out = ArrayList<DetectedBox>(arr.length())
        var seenClasses = HashMap<String, Int>()    // diagnostic — class -> seen count
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val conf = p.optDouble("confidence", 0.0).toFloat()
            if (conf < confidence) continue
            val klass = p.optString("class")
            seenClasses[klass] = (seenClasses[klass] ?: 0) + 1
            val tileIdx = engineIndexFor(klass) ?: continue
            val cxAbs = p.optDouble("x", 0.0).toFloat()
            val cyAbs = p.optDouble("y", 0.0).toFloat()
            val wAbs = p.optDouble("width", 0.0).toFloat()
            val hAbs = p.optDouble("height", 0.0).toFloat()
            out.add(
                DetectedBox(
                    cx = (cxAbs / refW).coerceIn(0f, 1f),
                    cy = (cyAbs / refH).coerceIn(0f, 1f),
                    w = (wAbs / refW).coerceIn(0f, 1f),
                    h = (hAbs / refH).coerceIn(0f, 1f),
                    tileIndex = tileIdx, score = conf,
                ),
            )
        }
        if (frameCounter++ % 5 == 0) {
            Log.i(
                TAG,
                "infer #$frameCounter: raw=${arr.length()} kept=${out.size} " +
                    "classes=$seenClasses image=${refW}x${refH}",
            )
        }
        return countsFromBoxes(out) to out
    }

    /**
     * Parse a Roboflow class name (e.g. "5m", "5_man", "p9", "wan5", "z1") into
     * the engine's 0..26 tile index. Drops honors (z*), flowers (f*), seasons.
     * Robust to whatever naming the dataset used, since Roboflow models on the
     * Universe don't share a single convention.
     */
    private fun engineIndexFor(klass: String): Int? {
        val s = klass.trim().lowercase()
        if (s.isEmpty()) return null
        if (s.startsWith("z") || s.startsWith("f") || s.startsWith("h")) return null  // honors/flowers
        // Find digit 1..9 + suit letter (any order).
        val rank = Regex("""[1-9]""").find(s)?.value?.toIntOrNull() ?: return null
        val suitChar = Regex("""[a-z]""").findAll(s)
            .map { it.value[0] }.firstOrNull { it.toString().matches(Regex("""[bcdmpstw]""")) }
            ?: return null
        val base = when (suitChar) {
            'm', 'w', 'c' -> 0       // man 万
            'p', 't', 'd' -> 9       // pin 筒
            's', 'b' -> 18           // sou 条
            else -> return null
        }
        // Guard "season" style ranks (s1..s4 means season here only if no clear
        // suit context). With Roboflow class strings we just trust the letter.
        if (rank !in 1..9) return null
        return base + (rank - 1)
    }

    private fun countsFromBoxes(boxes: List<DetectedBox>): IntArray {
        val counts = IntArray(Tiles.TILE_KINDS)
        boxes.forEach { b ->
            if (counts[b.tileIndex] < Tiles.COPIES) counts[b.tileIndex]++
        }
        return counts
    }

    private companion object {
        const val TAG = "RoboflowHandRecognizer"
    }
}
