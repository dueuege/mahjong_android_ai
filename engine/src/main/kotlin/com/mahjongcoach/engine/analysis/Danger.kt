package com.mahjongcoach.engine.analysis

import com.mahjongcoach.engine.Hand
import com.mahjongcoach.engine.Tiles

/** Estimated deal-in danger of discarding [tile]: 0 = safe … 1 = risky. */
data class TileDanger(val tile: Int, val danger: Double) {
    val name: String get() = Tiles.cnName(tile)
    val label: String get() = when {
        danger < 0.25 -> "安全"
        danger < 0.55 -> "中等"
        else -> "危险"
    }
}

/**
 * Defense: per-tile deal-in danger from the **public record the app never
 * forgets** (the accumulated discard pond + walls). This is where humans lose
 * the most points and read by feel; the engine reads it systematically.
 *
 * This is an AGGREGATE model (one pooled view of all opponents) using
 * genbutsu / 现物, suji / 筋, kabe / 壁, and terminal-vs-middle priors. Per-seat
 * precision (which opponent a tile is safe against) arrives with the opponent
 * model in Phase 4; until then `seen` is the pooled discard pile.
 *
 * Pure Kotlin; depends only on tile indices.
 */
object Danger {

    /** Danger of discarding each distinct tile currently held, safest first. */
    fun rank(hand: Hand, seen: IntArray): List<TileDanger> =
        (0 until Tiles.TILE_KINDS)
            .filter { hand.concealed[it] > 0 }
            .map { TileDanger(it, dangerOf(it, hand, seen)) }
            .sortedBy { it.danger }

    /** The safest tile to discard from hand, or null if the hand is empty. */
    fun safest(hand: Hand, seen: IntArray): TileDanger? = rank(hand, seen).firstOrNull()

    /**
     * A coarse threat level from the public pile, for [Situation]: a large pond
     * means late game where opponents are likely close, so caution rises. 0 none,
     * 1 mild, 2 strong. (Per-seat riichi/meld signals refine this in Phase 4.)
     */
    fun threatLevel(seen: IntArray): Int {
        val pond = seen.sum()
        return when {
            pond >= 36 -> 2
            pond >= 20 -> 1
            else -> 0
        }
    }

    private fun dangerOf(t: Int, hand: Hand, seen: IntArray): Double {
        // 现物 (genbutsu): already discarded by someone → much safer.
        if (seen[t] > 0) return (0.10 + 0.02 * (Tiles.COPIES - seen[t])).coerceAtMost(0.2)

        val rank = Tiles.rankOf(t)
        // Terminals span no two-sided (ryanmen) wait; middles span the most.
        var d = when (rank) {
            1, 9 -> 0.30
            2, 8 -> 0.45
            3, 7 -> 0.58
            else -> 0.72            // 4,5,6
        }
        d -= sujiRelief(t, seen)
        d -= kabeRelief(t, seen, hand)
        return d.coerceIn(0.05, 1.0)
    }

    /** 筋: if a ryanmen "other end" 3 away is in the pond, this tile is safer. */
    private fun sujiRelief(t: Int, seen: IntArray): Double {
        val suit = t / Tiles.RANKS
        val rank = Tiles.rankOf(t)
        var relief = 0.0
        // e.g. 4 is suji-safer if 1 or 7 already discarded.
        if (rank - 3 >= 1 && seen[t - 3] > 0 && (t - 3) / Tiles.RANKS == suit) relief += 0.12
        if (rank + 3 <= 9 && seen[t + 3] > 0 && (t + 3) / Tiles.RANKS == suit) relief += 0.12
        return relief
    }

    /** 壁: if a connecting tile is fully visible (all 4), waits needing it vanish. */
    private fun kabeRelief(t: Int, seen: IntArray, hand: Hand): Double {
        val suit = t / Tiles.RANKS
        fun walled(idx: Int): Boolean =
            idx in 0 until Tiles.TILE_KINDS && idx / Tiles.RANKS == suit &&
                seen[idx] + hand.concealed[idx] >= Tiles.COPIES
        var relief = 0.0
        if (walled(t - 1) || walled(t + 1)) relief += 0.08
        if (walled(t - 2) || walled(t + 2)) relief += 0.05
        return relief
    }
}
