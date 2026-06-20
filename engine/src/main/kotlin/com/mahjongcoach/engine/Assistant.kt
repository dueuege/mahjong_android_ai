package com.mahjongcoach.engine

import com.mahjongcoach.engine.scoring.RiichiContext
import com.mahjongcoach.engine.scoring.Ruleset
import com.mahjongcoach.engine.scoring.ScoreService
import com.mahjongcoach.engine.scoring.T34

/**
 * The bridge between a natural-language LLM and the deterministic engine.
 *
 * The LLM is only a front-end: it must call these tools for ANY shanten / ukeire
 * / discard / score claim — it never computes mahjong math itself. That keeps the
 * engine (which is exact and unit-tested) the single source of truth, and means
 * the coach still works without an LLM at all.
 *
 * Pure Kotlin, no Android, no network — fully unit-testable. The Android-side
 * Claude client just turns tool-call JSON into [dispatch] calls.
 */
object Assistant {

    /** System prompt for the LLM front-end. Kept here so it's versioned with the tools. */
    val SYSTEM_PROMPT: String = """
        You are a Sichuan/Chinese/Japanese mahjong coach used openly at the table.
        You only ever reason about the player's OWN hand and PUBLIC information
        (discards, melds, declared void suit). You never claim to know opponents'
        concealed tiles.

        You MUST call a tool for every concrete claim about shanten, tile
        acceptance (ukeire), the best discard, or scoring — never compute or guess
        these yourself. The tools wrap an exact engine; trust their output.

        Hand notation: digits then a suit letter. m=万, p=筒, s=条 (Sichuan has only
        these three). Japanese honors: z1..z7 = 東南西北白發中. Example: "123m456p77s".

        If a user message contains a `[STATE]…[/STATE]` block, treat its
        fields (`hand=`, `seen=`, `void=`, `melds=`) as the authoritative
        current state and pass them straight through to the tools. Don't
        ask the user to repeat what's already in the block.

        Be concise. Lead with the recommended action, then a one-line why. Don't
        ask permission for read-only analysis — just answer.
    """.trimIndent()

    /** A tool the LLM may call. [inputSchemaJson] is a JSON-Schema object string. */
    data class ToolSpec(val name: String, val description: String, val inputSchemaJson: String)

    const val TOOL_ADVISE = "recommend_discard"
    const val TOOL_SCORE = "score_hand"
    const val TOOL_PICK_VOID = "pick_void_suit"

    val tools: List<ToolSpec> = listOf(
        ToolSpec(
            TOOL_ADVISE,
            "Compute shanten, the best discard, and tile acceptance (ukeire) for the " +
                "player's own Sichuan hand. Respects 定缺 (void suit).",
            """
            {"type":"object","properties":{
              "hand":{"type":"string","description":"own hand, e.g. 123m456m789m1199p5s (13 or 14 tiles)"},
              "void_suit":{"type":"string","description":"declared void suit: m, p, or s (optional)"},
              "seen":{"type":"string","description":"tiles already seen on the table, same notation (optional)"}
            },"required":["hand"]}
            """.trimIndent(),
        ),
        ToolSpec(
            TOOL_PICK_VOID,
            "Recommend the Sichuan 定缺 (void suit) for the start of a round: the suit " +
                "with the fewest tiles in the player's own hand. Returns per-suit counts " +
                "and the recommendation.",
            """
            {"type":"object","properties":{
              "hand":{"type":"string","description":"player's own hand, e.g. 12349m23p5689s (13 tiles)"}
            },"required":["hand"]}
            """.trimIndent(),
        ),
        ToolSpec(
            TOOL_SCORE,
            "Score a REVEALED winning hand. ruleset=sichuan returns 番/倍; " +
                "ruleset=japan returns han/fu and points (needs win_tile).",
            """
            {"type":"object","properties":{
              "ruleset":{"type":"string","enum":["sichuan","japan"]},
              "hand":{"type":"string","description":"the full revealed hand"},
              "win_tile":{"type":"string","description":"winning tile, e.g. 4m (japan only)"},
              "tsumo":{"type":"boolean"},"dealer":{"type":"boolean"},"riichi":{"type":"boolean"}
            },"required":["ruleset","hand"]}
            """.trimIndent(),
        ),
    )

    /** Execute a tool call. [args] is the LLM's tool input flattened to strings. */
    fun dispatch(name: String, args: Map<String, String?>): String = try {
        when (name) {
            TOOL_ADVISE -> advise(args)
            TOOL_SCORE -> score(args)
            TOOL_PICK_VOID -> pickVoid(args)
            else -> "error: unknown tool \"$name\""
        }
    } catch (e: Exception) {
        "error: ${e.message}"
    }

    private fun pickVoid(args: Map<String, String?>): String {
        val handStr = args["hand"]?.takeIf { it.isNotBlank() } ?: return "error: missing hand"
        val counts = Tiles.parse(handStr)
        // Per-suit totals.
        val perSuit = IntArray(3)
        for (i in counts.indices) perSuit[i / 9] += counts[i]
        val suitNames = listOf("m (万)", "p (筒)", "s (条)")
        val pick = perSuit.withIndex().minByOrNull { it.value }!!.index
        val pickName = listOf("m", "p", "s")[pick]
        val tied = perSuit.count { it == perSuit[pick] } > 1
        val summary = buildString {
            append("counts: ")
            perSuit.forEachIndexed { i, c ->
                if (i > 0) append(", ")
                append("${suitNames[i]}=$c")
            }
        }
        return buildString {
            append("Recommended 定缺: $pickName\n")
            append(summary)
            if (tied) append("\n(tied — any of the lowest-count suits is reasonable; prefer the one with the fewest pairs / terminals retained.)")
        }
    }

    private fun advise(args: Map<String, String?>): String {
        val handStr = args["hand"]?.takeIf { it.isNotBlank() } ?: return "error: missing hand"
        val void = args["void_suit"]?.let { parseSuit(it) }
        val seen = args["seen"]?.takeIf { it.isNotBlank() }?.let { Tiles.parse(it) }
            ?: IntArray(Tiles.TILE_KINDS)
        val hand = Hand.of(handStr, voidSuit = void)
        val advisor = Advisor(seen)

        if (hand.tileCount % 3 != 2) {
            val s = advisor.shanten(hand)
            val label = if (s == Shanten.WIN) "winning hand" else if (s == 0) "tenpai (听牌)" else "$s-shanten (向听)"
            return "$hand → $label."
        }
        val advice = advisor.recommendDiscard(hand)
        val sb = StringBuilder()
        sb.append(hand).append("\n")
        when {
            advice.isWin -> sb.append("和牌! winning hand.")
            advice.voidTilesHeld > 0 -> sb.append("定缺: discard the ${advice.voidTilesHeld} void-suit tile(s) first.")
            advice.isTenpai -> sb.append("听牌 (tenpai).")
            else -> sb.append("向听 (shanten): ${advice.shanten}.")
        }
        advice.options.take(3).forEachIndexed { i, opt ->
            val label = when (opt.resultingShanten) {
                Shanten.WIN -> "win"; 0 -> "tenpai"; else -> "${opt.resultingShanten}-shanten"
            }
            val accepts = opt.acceptance.sortedByDescending { it.remaining }
                .joinToString(" ") { "${it.name}×${it.remaining}" }
            sb.append("\n${i + 1}. discard ${opt.discardName} → $label (${opt.ukeireCount} tiles)")
            if (accepts.isNotEmpty()) sb.append("  accepts: $accepts")
        }
        return sb.toString()
    }

    private fun score(args: Map<String, String?>): String {
        val hand = args["hand"]?.takeIf { it.isNotBlank() } ?: return "error: missing hand"
        val counts = T34.parse(hand)
        val japan = args["ruleset"]?.startsWith("j", ignoreCase = true) == true
        return if (!japan) {
            ScoreService.describe(Ruleset.SICHUAN, counts)
        } else {
            val wt = args["win_tile"]?.let { T34.parse(it).indexOfFirst { c -> c > 0 } } ?: -1
            if (wt < 0) return "error: japan scoring needs win_tile (e.g. 4m)"
            ScoreService.describe(
                Ruleset.JAPANESE, counts,
                ctx = RiichiContext(
                    winTile = wt,
                    tsumo = args["tsumo"].toBool(),
                    riichi = args["riichi"].toBool(),
                    seatWind = if (args["dealer"].toBool()) T34.EAST else T34.SOUTH,
                    roundWind = T34.EAST,
                ),
            )
        }
    }

    private fun parseSuit(s: String): Suit? = when (s.trim().firstOrNull()) {
        'm', '万', '萬' -> Suit.MAN
        'p', '筒', '饼' -> Suit.PIN
        's', '条', '索' -> Suit.SOU
        else -> null
    }

    private fun String?.toBool(): Boolean = this?.lowercase() in setOf("true", "1", "yes", "y")
}
