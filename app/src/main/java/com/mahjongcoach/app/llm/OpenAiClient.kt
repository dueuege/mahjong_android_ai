package com.mahjongcoach.app.llm

import com.mahjongcoach.engine.Assistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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
