package com.mahjongcoach.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mahjongcoach.engine.Advice
import com.mahjongcoach.engine.Advisor
import com.mahjongcoach.engine.Hand
import com.mahjongcoach.engine.Meld
import com.mahjongcoach.engine.MeldType
import com.mahjongcoach.engine.Suit
import com.mahjongcoach.engine.Tiles

/**
 * Single source of truth for one game, observable by Compose.
 *
 * All inputs are PUBLIC information:
 *   - your own concealed hand   (manual taps now; camera later -> [setHandCounts])
 *   - your called melds (碰/杠)   ([callPon], [callKan])
 *   - your declared void suit    (定缺)
 *   - tiles seen on the table    ([observeSeen]; audio + board video)
 *
 * It never models opponents' concealed hands. The advisor only reasons about
 * YOUR hand plus how many copies of each tile remain unseen.
 */
class GameState {

    var hand by mutableStateOf(IntArray(Tiles.TILE_KINDS)); private set
    var seen by mutableStateOf(IntArray(Tiles.TILE_KINDS)); private set
    var melds by mutableStateOf<List<Meld>>(emptyList()); private set
    var voidSuit by mutableStateOf<Suit?>(null)

    /** Recomputed advice for the current state. */
    val advice: Advice
        get() = Advisor(seen).recommendDiscard(Hand(hand.copyOf(), melds, voidSuit))

    /** The current state as an engine [Hand] (snapshot copy). */
    fun toHand(): Hand = Hand(hand.copyOf(), melds, voidSuit)

    /** Snapshot of the public seen-pile (for off-main analysis). */
    fun seenCopy(): IntArray = seen.copyOf()

    /** Superhuman analysis: EV-ranked call + a structured report for the LLM. */
    val analysis: com.mahjongcoach.engine.analysis.AnalysisReport
        get() = com.mahjongcoach.engine.analysis.CoachAnalysis.analyze(toHand(), seen)

    /** Concealed tiles + 3 per meld; should be 13 or 14 for valid advice. */
    val totalTiles: Int get() = hand.sum() + melds.size * 3

    /** Engine's 定缺 recommendation (lowest-count suit), or null if hand empty. */
    val voidRecommendation: Suit?
        get() = Advisor(seen).recommendVoidSuit(Hand(hand.copyOf(), melds, voidSuit))

    /** Per-suit totals [man, pin, sou] for UI display. */
    val suitCounts: IntArray
        get() = Advisor(seen).suitCounts(Hand(hand.copyOf(), melds, voidSuit))

    /**
     * Looks like the start of a round: a full 13-tile concealed hand, no called
     * melds, and no void declared yet. The cue to recommend 定缺.
     */
    val isNewGame: Boolean
        get() = melds.isEmpty() && voidSuit == null && hand.sum() == 13

    /** A plausible Sichuan hand: total 13 or 14, and no tile held more than 4. */
    val isReasonableHand: Boolean
        get() = totalTiles in 13..14 && hand.all { it <= Tiles.COPIES }

    /**
     * If the current hand looks like a misread, a short Chinese description of
     * what's wrong (for the coach to flag), else null. Used to keep advising
     * even on a bad detection while pointing out the likely error.
     */
    val handIssue: String?
        get() {
            val over = (0 until Tiles.TILE_KINDS).firstOrNull { hand[it] > Tiles.COPIES }
            if (over != null) return "${Tiles.cnName(over)} 超过 4 枚，可能误识"
            return when {
                totalTiles == 0 -> null
                totalTiles < 13 -> "只识别到 $totalTiles 张（手牌应 13/14 张），可能漏识"
                totalTiles > 14 -> "识别到 $totalTiles 张（手牌应 13/14 张），可能多识"
                else -> null
            }
        }

    // ---- your concealed hand ----
    fun addTile(tile: Int) {
        if (totalTiles >= 14) return
        if (countInWorld(tile) >= Tiles.COPIES) return
        hand = hand.copyOf().also { it[tile]++ }
    }

    fun removeTile(tile: Int) {
        if (hand[tile] <= 0) return
        hand = hand.copyOf().also { it[tile]-- }
    }

    fun clearHand() { hand = IntArray(Tiles.TILE_KINDS) }
    fun setHandCounts(counts: IntArray) { hand = counts.copyOf() }

    /** Start a fresh round: clear hand, seen pile, melds and the declared void. */
    fun resetRound() {
        hand = IntArray(Tiles.TILE_KINDS)
        seen = IntArray(Tiles.TILE_KINDS)
        melds = emptyList()
        voidSuit = null
    }

    // ---- called melds ----
    /** Call 碰: 2 from hand + 1 taken from a discard. */
    fun callPon(tile: Int) {
        if (hand[tile] < 2 || melds.size >= 4) return
        hand = hand.copyOf().also { it[tile] -= 2 }
        melds = melds + Meld(MeldType.PON, tile)
        observeSeen(tile, 3, additive = false) // all 3 are now off the wall
    }

    /** Call 杠. [concealed] = 暗杠 (4 from hand) vs 明杠 (3 from hand + 1 discard). */
    fun callKan(tile: Int, concealed: Boolean) {
        val need = if (concealed) 4 else 3
        if (hand[tile] < need || melds.size >= 4) return
        hand = hand.copyOf().also { it[tile] -= need }
        melds = melds + Meld(MeldType.KAN, tile)
        observeSeen(tile, 4, additive = false)
    }

    fun removeMeld(index: Int) {
        val m = melds.getOrNull(index) ?: return
        val back = if (m.type == MeldType.KAN) 3 else 2 // return the from-hand portion
        hand = hand.copyOf().also { it[m.tile] = (it[m.tile] + back).coerceAtMost(Tiles.COPIES) }
        melds = melds.toMutableList().also { it.removeAt(index) }
        seen = seen.copyOf().also { it[m.tile] = 0 } // recompute conservatively
    }

    // ---- table knowledge (discards / opponents' melds) ----
    /**
     * Accumulate a detected board/discard frame into the seen pile. Unlike
     * [setHandCounts] (which replaces the hand), this is additive but capped per
     * tile: a fresh detection of the same pond shouldn't keep stacking, so we
     * take the running max per tile rather than summing every frame. This is the
     * round's growing memory of public tiles, which the engine uses for odds.
     */
    fun addSeenCounts(counts: IntArray) {
        seen = seen.copyOf().also {
            for (t in it.indices) {
                it[t] = maxOf(it[t], counts.getOrElse(t) { 0 }).coerceAtMost(Tiles.COPIES)
            }
        }
    }

    /** Mark [n] copies of [tile] as seen. additive=true adds; false sets at least n. */
    fun observeSeen(tile: Int, n: Int = 1, additive: Boolean = true) {
        seen = seen.copyOf().also {
            it[tile] = if (additive) (it[tile] + n) else maxOf(it[tile], n)
            it[tile] = it[tile].coerceAtMost(Tiles.COPIES)
        }
    }

    fun unsee(tile: Int) {
        if (seen[tile] <= 0) return
        seen = seen.copyOf().also { it[tile]-- }
    }

    fun resetSeen() { seen = IntArray(Tiles.TILE_KINDS) }

    /** Copies of [tile] accounted for (hand + melds + seen), capped sanity. */
    private fun countInWorld(tile: Int): Int {
        val inMelds = melds.count { it.tile == tile } * 3
        return hand[tile] + inMelds
    }
}
