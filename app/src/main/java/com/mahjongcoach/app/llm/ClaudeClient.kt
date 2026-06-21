package com.mahjongcoach.app.llm

import android.graphics.Bitmap
import android.util.Base64
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
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
import java.util.function.Supplier

/**
 * Claude-backed assistant. Uses the official Anthropic Java SDK's beta tool
 * runner: it calls the model, and whenever the model requests a tool the runner
 * invokes the matching [Supplier] below, which forwards to the exact engine via
 * [Assistant.dispatch]. The model only orchestrates and explains — the numbers
 * come from the engine.
 *
 * Default model is claude-opus-4-8 (configurable in Settings). Thinking is left
 * off by default for table-side latency; the engine already does the reasoning.
 *
 * ⚠️ SECURITY: this calls the API directly with the user's key. That's fine for a
 * personal build (key entered in Settings), but DO NOT distribute an APK with an
 * embedded key — proxy through a small backend instead. See docs/LLM.md.
 */
class ClaudeClient(
    private val apiKey: String,
    private val model: String = "claude-opus-4-8",
    private val maxTokens: Long = 2048,
) : LlmClient {

    override val label: String get() = "Claude API · $model"
    override val available: Boolean get() = apiKey.isNotBlank()

    private val client by lazy { AnthropicOkHttpClient.builder().apiKey(apiKey).build() }

    override suspend fun reply(history: List<ChatTurn>): String = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(Assistant.SYSTEM_PROMPT)
            .addTool(RecommendDiscardTool::class.java)
            .addTool(ScoreHandTool::class.java)
            .addTool(PickVoidSuitTool::class.java)
            .addTool(CoachAnalysisTool::class.java)
            .apply {
                history.forEach { turn ->
                    when (turn.role) {
                        Role.USER -> addUserMessage(turn.text)
                        Role.ASSISTANT -> addAssistantMessage(turn.text)
                    }
                }
            }
            .build()

        // The runner drives the API ↔ tool loop to completion; collect assistant text.
        val out = StringBuilder()
        for (message: BetaMessage in client.beta().messages().toolRunner(params)) {
            message.content().forEach { block ->
                block.text().ifPresent { out.append(it.text()) }
            }
        }
        out.toString().trim().ifEmpty { "(no response)" }
    }

    // --- Tools: the runner fills the public fields from the model's JSON, then
    //     calls get(); we forward to the engine. ---

    @JsonClassDescription(
        "Compute shanten, the best discard, and tile acceptance (ukeire) for the " +
            "player's own Sichuan hand. Respects the declared void suit (定缺).",
    )
    class RecommendDiscardTool : Supplier<String> {
        @JvmField
        @JsonPropertyDescription("the player's own hand, e.g. 123m456m789m1199p5s (13 or 14 tiles)")
        var hand: String = ""

        @JvmField
        @JsonPropertyDescription("declared void suit: m, p, or s (optional)")
        var void_suit: String? = null

        @JvmField
        @JsonPropertyDescription("tiles already seen on the table, same notation (optional)")
        var seen: String? = null

        override fun get(): String = Assistant.dispatch(
            Assistant.TOOL_ADVISE,
            mapOf("hand" to hand, "void_suit" to void_suit, "seen" to seen),
        )
    }

    @JsonClassDescription(
        "Full coach analysis of the player's own Sichuan hand: game phase + an " +
            "EV-ranked discard table (resulting shanten, ukeire, estimated hand " +
            "value 倍数, EV = speed×value), or the 定缺 recommendation for a fresh " +
            "hand. Use for 'what should I do' — it weights value, not just count.",
    )
    class CoachAnalysisTool : Supplier<String> {
        @JvmField @JsonPropertyDescription("own hand, e.g. 123m456m789m1199p5s (13 or 14 tiles)")
        var hand: String = ""

        @JvmField @JsonPropertyDescription("declared void suit: m, p, or s (optional)")
        var void_suit: String? = null

        @JvmField @JsonPropertyDescription("tiles seen on the table, same notation (optional)")
        var seen: String? = null

        override fun get(): String = Assistant.dispatch(
            Assistant.TOOL_ANALYZE,
            mapOf("hand" to hand, "void_suit" to void_suit, "seen" to seen),
        )
    }

    @JsonClassDescription(
        "Recommend the Sichuan 定缺 (void suit) for the start of a round: the suit " +
            "with the fewest tiles in the player's own hand. Returns per-suit counts " +
            "and the recommendation.",
    )
    class PickVoidSuitTool : Supplier<String> {
        @JvmField
        @JsonPropertyDescription("player's own hand in engine notation, e.g. 12349m23p5689s")
        var hand: String = ""

        override fun get(): String = Assistant.dispatch(
            Assistant.TOOL_PICK_VOID,
            mapOf("hand" to hand),
        )
    }

    @JsonClassDescription(
        "Score a REVEALED winning hand. ruleset=sichuan returns 番/倍; ruleset=japan " +
            "returns han/fu and points (needs win_tile).",
    )
    class ScoreHandTool : Supplier<String> {
        @JvmField @JsonPropertyDescription("sichuan or japan")
        var ruleset: String = "sichuan"

        @JvmField @JsonPropertyDescription("the full revealed hand")
        var hand: String = ""

        @JvmField @JsonPropertyDescription("winning tile, e.g. 4m (japan only)")
        var win_tile: String? = null

        @JvmField var tsumo: Boolean = false
        @JvmField var dealer: Boolean = false
        @JvmField var riichi: Boolean = false

        override fun get(): String = Assistant.dispatch(
            Assistant.TOOL_SCORE,
            mapOf(
                "ruleset" to ruleset, "hand" to hand, "win_tile" to win_tile,
                "tsumo" to tsumo.toString(), "dealer" to dealer.toString(), "riichi" to riichi.toString(),
            ),
        )
    }

    /**
     * Vision path. Bypasses the Java SDK and POSTs directly to
     * `api.anthropic.com/v1/messages` with an image content block — keeps us off
     * specific SDK class names that change between SDK versions, and shares the
     * same wire shape as a plain curl example. Returns a 27-length counts array
     * or null on any failure (no vision, low confidence, parse error).
     */
    override suspend fun recognizeHand(bitmap: Bitmap): IntArray? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray()
        }
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val systemPrompt =
            "You read mahjong tiles from a photo of the player's OWN hand. Reply with " +
                "a strict JSON object {\"hand\": \"...\"} where the value is a hand string " +
                "in engine notation (digits then suit letter; m=万, p=筒, s=条). Sichuan " +
                "has only these three suits, 13 or 14 tiles total. Example: " +
                "\"123m456m789m1199p5s\". If unreadable or empty, reply {\"hand\": \"\"}. " +
                "No prose, no markdown."

        val content = JSONArray()
            .put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", b64),
                    ),
            )
            .put(JSONObject().put("type", "text").put("text", "Read this hand."))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 256)
            .put("system", systemPrompt)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", content),
                ),
            )

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()
        val text = runCatching {
            visionHttp.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@runCatching null
                val arr = JSONObject(s).optJSONArray("content") ?: return@runCatching null
                buildString {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("type") == "text") append(o.optString("text"))
                    }
                }
            }
        }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return@withContext null

        val hand = runCatching {
            JSONObject(text).optString("hand")
        }.getOrNull() ?: run {
            val first = text.indexOf('{'); val last = text.lastIndexOf('}')
            if (first >= 0 && last > first)
                runCatching { JSONObject(text.substring(first, last + 1)).optString("hand") }.getOrNull()
            else null
        }
        if (hand.isNullOrBlank()) return@withContext null
        runCatching { Tiles.parse(hand) }.getOrNull()
    }

    private val visionHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
