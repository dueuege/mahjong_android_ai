package com.mahjongcoach.app.assistant

import com.mahjongcoach.app.GameState
import com.mahjongcoach.engine.Suit
import com.mahjongcoach.engine.Tiles

/**
 * One-tap chips that pre-fill the assistant prompt with current [GameState] as
 * a `[STATE]…[/STATE]` block. The engine's SYSTEM_PROMPT teaches the model to
 * treat that block as authoritative current state.
 *
 * Each preset's [query] is the user-facing instruction that follows the state
 * block, e.g. "What should I discard?" — keep these phrased like things a
 * casual player would actually say, not API jargon.
 */
data class Preset(
    val label: String,
    val query: String,
    /** When true, the chip is disabled if the hand is empty. */
    val requiresHand: Boolean = true,
)

object Presets {
    val ALL: List<Preset> = listOf(
        Preset(
            label = "Best discard now",
            query = "What's the best discard from this hand and why? Use the tool.",
        ),
        Preset(
            label = "Pick void suit (定缺)",
            query = "Which suit should I declare 定缺 for this hand? Explain the trade-off.",
        ),
        Preset(
            label = "Should I 碰?",
            query = "Given the seen-pile and current hand, is it worth opening the hand with a 碰 here? What would I lose?",
        ),
        Preset(
            label = "Defensive read",
            query = "From the public discards in the seen-pile, what's dangerous to discard? Suggest 1-2 safe tiles.",
        ),
        Preset(
            label = "Score this hand",
            query = "Treat this hand as a revealed Sichuan winning hand and score it. Use the tool.",
            requiresHand = true,
        ),
    )
}

/**
 * Render [state] as a compact `[STATE]` block the LLM can parse from inside a
 * user message. Honors the same notation everywhere else in the app uses.
 */
fun GameState.toPromptBlock(): String {
    val handStr = countsToCompact(hand)
    val seenStr = countsToCompact(seen)
    val voidStr = voidSuit?.let { suitCode(it) }.orEmpty()
    val meldsStr = melds.joinToString(",") { m ->
        val tag = if (m.type.name.startsWith("K")) "kan" else "pon"
        "$tag:${Tiles.cnName(m.tile)}"
    }
    return buildString {
        append("[STATE]\n")
        append("hand=$handStr\n")
        if (seenStr.isNotBlank()) append("seen=$seenStr\n")
        if (voidStr.isNotBlank()) append("void=$voidStr\n")
        if (meldsStr.isNotBlank()) append("melds=$meldsStr\n")
        append("[/STATE]")
    }
}

private fun countsToCompact(counts: IntArray): String {
    val sb = StringBuilder()
    for (suit in 0..2) {
        val digits = StringBuilder()
        for (rank in 0..8) {
            val tile = suit * 9 + rank
            if (tile >= counts.size) break
            repeat(counts[tile]) { digits.append(rank + 1) }
        }
        if (digits.isNotEmpty()) {
            sb.append(digits)
            sb.append(when (suit) { 0 -> "m"; 1 -> "p"; else -> "s" })
        }
    }
    return sb.toString()
}

private fun suitCode(suit: Suit) = when (suit) {
    Suit.MAN -> "m"; Suit.PIN -> "p"; Suit.SOU -> "s"
}
