package com.mahjongcoach.app.vision

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.mahjongcoach.engine.Tiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Result of one Roboflow inference. */
data class RoboflowResult(
    /** 27-length engine tile counts (man/pin/sou only; honors dropped). */
    val counts: IntArray,
    /** Normalised detection boxes for overlay rendering. */
    val boxes: List<DetectedBox>,
    /** Raw class-name → count, for diagnostics / unknown-class detection. */
    val rawClasses: Map<String, Int>,
) {
    val tileCount: Int get() = counts.sum()
}

/**
 * Stateless Roboflow serverless inference. Shared by the live
 * [RoboflowHandRecognizer] (camera frames) and the Score tab (gallery / camera
 * photo) so there's one wire format + class-parser to maintain.
 *
 * Wire format (Roboflow REST object detection):
 *   POST https://serverless.roboflow.com/{modelId}?api_key={key}
 *   Content-Type: application/x-www-form-urlencoded
 *   body: base64 JPEG (raw)
 *   ← {"predictions":[{x,y,width,height,confidence,class,class_id}],
 *      "image":{"width":W,"height":H}}   (x/y are CENTER px in image space)
 */
object RoboflowInfer {
    private const val TAG = "RoboflowInfer"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    private val urlEnc = "application/x-www-form-urlencoded".toMediaType()

    /** Run inference. Throws on HTTP / parse error so callers can show it. */
    suspend fun infer(
        apiKey: String,
        modelId: String,
        bitmap: Bitmap,
        confidence: Float = 0.30f,
    ): RoboflowResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Roboflow API key not set" }
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray()
        }
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val url = "https://serverless.roboflow.com/${modelId.trim().trimStart('/')}?api_key=$apiKey"
        val req = Request.Builder().url(url)
            .post(b64.toRequestBody(urlEnc))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200)}")
            parse(body, bitmap.width, bitmap.height, confidence)
        }
    }

    private fun parse(body: String, frameW: Int, frameH: Int, confidence: Float): RoboflowResult {
        val root = JSONObject(body)
        val arr = root.optJSONArray("predictions")
            ?: return RoboflowResult(IntArray(Tiles.TILE_KINDS), emptyList(), emptyMap())
        val imgObj = root.optJSONObject("image")
        val refW = (imgObj?.optInt("width") ?: frameW).takeIf { it > 0 } ?: frameW
        val refH = (imgObj?.optInt("height") ?: frameH).takeIf { it > 0 } ?: frameH
        val out = ArrayList<DetectedBox>(arr.length())
        val seen = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val conf = p.optDouble("confidence", 0.0).toFloat()
            if (conf < confidence) continue
            val klass = p.optString("class")
            seen[klass] = (seen[klass] ?: 0) + 1
            val tileIdx = engineIndexFor(klass) ?: continue
            out.add(
                DetectedBox(
                    cx = (p.optDouble("x", 0.0).toFloat() / refW).coerceIn(0f, 1f),
                    cy = (p.optDouble("y", 0.0).toFloat() / refH).coerceIn(0f, 1f),
                    w = (p.optDouble("width", 0.0).toFloat() / refW).coerceIn(0f, 1f),
                    h = (p.optDouble("height", 0.0).toFloat() / refH).coerceIn(0f, 1f),
                    tileIndex = tileIdx, score = conf,
                ),
            )
        }
        Log.i(TAG, "raw=${arr.length()} kept=${out.size} classes=$seen image=${refW}x${refH}")
        return RoboflowResult(countsFromBoxes(out), out, seen)
    }

    private fun countsFromBoxes(boxes: List<DetectedBox>): IntArray {
        val counts = IntArray(Tiles.TILE_KINDS)
        boxes.forEach { b -> if (counts[b.tileIndex] < Tiles.COPIES) counts[b.tileIndex]++ }
        return counts
    }

    /**
     * Parse a Roboflow class name (e.g. "5m", "5_man", "p9", "wan5", "z1") into
     * the engine's 0..26 tile index. Drops honors (z*), flowers (f*), seasons.
     * Robust to whatever naming the dataset used.
     */
    fun engineIndexFor(klass: String): Int? {
        val s = klass.trim().lowercase()
        if (s.isEmpty()) return null
        if (s.startsWith("z") || s.startsWith("f") || s.startsWith("h")) return null
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
        return base + (rank - 1)
    }
}
