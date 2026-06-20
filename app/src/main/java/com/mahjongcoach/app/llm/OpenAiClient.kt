package com.mahjongcoach.app.llm

import android.graphics.Bitmap
import android.util.Base64
import com.mahjongcoach.engine.Assistant
import com.mahjongcoach.engine.Tiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible chat-completions client. Hits any endpoint that speaks the
 * `POST {baseUrl}/chat/completions` shape with `tools` + `tool_calls` — OpenAI,
 * OpenRouter, Together, vLLM, llama.cpp's OpenAI server, etc.
 *
 * Tool calling is required: the model is constrained (via [Assistant.SYSTEM_PROMPT])
 * to call the engine for any shanten/score/discard claim, so we forward each tool
 * call to [Assistant.dispatch] and loop until the model returns plain text. If the
 * chosen model doesn't support tool calling, the loop will just yield the model's
 * first textual answer — which may hallucinate; pick a tool-capable model.
 *
 * Auth defaults to `Authorization: Bearer {apiKey}`; pass an `Authorization` entry
 * in [extraHeaders] to override (useful for endpoints that want a different scheme).
 */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : LlmClient {

    override val label: String get() = "OpenAI-compat · $model"
    override val available: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
    private val jsonMt = "application/json; charset=utf-8".toMediaType()

    override suspend fun reply(history: List<ChatTurn>): String = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", Assistant.SYSTEM_PROMPT))
        history.forEach { turn ->
            val role = if (turn.role == Role.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", turn.text))
        }

        val tools = JSONArray()
        Assistant.tools.forEach { t ->
            tools.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", JSONObject(t.inputSchemaJson)),
                    ),
            )
        }

        repeat(MAX_TOOL_HOPS) {
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")

            val resp = postChat(body).getOrElse { e ->
                return@withContext "(error: ${e.message ?: "request failed"})"
            }
            val choice = resp.optJSONArray("choices")?.optJSONObject(0)
                ?: return@withContext "(error: no choices in response)"
            val msg = choice.optJSONObject("message")
                ?: return@withContext "(error: no message in choice)"
            val toolCalls = msg.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                messages.put(msg) // preserve assistant tool-call message verbatim
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.getJSONObject(i)
                    val id = tc.optString("id")
                    val fn = tc.optJSONObject("function") ?: continue
                    val name = fn.optString("name")
                    val args = parseArgs(fn.optString("arguments", "{}"))
                    val result = Assistant.dispatch(name, args)
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", id)
                            .put("content", result),
                    )
                }
            } else {
                return@withContext msg.optString("content", "")
                    .trim().ifEmpty { "(no response)" }
            }
        }
        "(error: tool loop exceeded $MAX_TOOL_HOPS hops)"
    }

    private fun postChat(body: JSONObject): Result<JSONObject> = runCatching {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val builder = Request.Builder().url(url)
            .post(body.toString().toRequestBody(jsonMt))
        val lower = extraHeaders.keys.map { it.lowercase() }
        if ("authorization" !in lower) builder.addHeader("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> builder.addHeader(k, v) }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            JSONObject(text)
        }
    }

    /**
     * Ask the configured vision-capable model to read tile counts from a still
     * frame. One-shot call (no tool loop): we ask for a strict JSON object
     * `{"hand":"123m456m789m..."}` and convert that with the engine's parser.
     * Returns null if the model can't read the frame or doesn't speak images.
     */
    override suspend fun recognizeHand(bitmap: Bitmap): IntArray? = withContext(Dispatchers.IO) {
        if (!available) return@withContext null
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray()
        }
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val systemPrompt =
            "You read mahjong tiles from a photo of the player's OWN hand. " +
                "Reply with a strict JSON object {\"hand\": \"...\"} where the value " +
                "is a hand string in the engine's notation: digits then a suit letter, " +
                "m=万, p=筒, s=条. Sichuan has only these three suits, 13 or 14 tiles " +
                "total. Example: \"123m456m789m1199p5s\". If the image is unreadable or " +
                "the hand is empty, reply with {\"hand\": \"\"}. No prose, no markdown."

        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Read this hand."))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl)),
            )
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", content)),
            )

        val resp = postChat(body).getOrElse { return@withContext null }
        val text = resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")?.trim().orEmpty()
        if (text.isEmpty()) return@withContext null

        val hand = extractHandString(text) ?: return@withContext null
        if (hand.isBlank()) return@withContext null
        runCatching { Tiles.parse(hand) }.getOrNull()
    }

    /** Pull the `hand` field out of either a clean JSON reply or one wrapped in prose / code fences. */
    private fun extractHandString(text: String): String? {
        runCatching {
            val o = JSONObject(text)
            return o.optString("hand")
        }
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (first >= 0 && last > first) {
            runCatching {
                val o = JSONObject(text.substring(first, last + 1))
                return o.optString("hand")
            }
        }
        return null
    }

    private fun parseArgs(json: String): Map<String, String?> {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, String?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.opt(k)
            out[k] = if (v == null || v === JSONObject.NULL) null else v.toString()
        }
        return out
    }

    private companion object {
        const val MAX_TOOL_HOPS = 8
    }
}
