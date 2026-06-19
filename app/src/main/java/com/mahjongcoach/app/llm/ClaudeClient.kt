package com.mahjongcoach.app.llm

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.mahjongcoach.engine.Assistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
}
