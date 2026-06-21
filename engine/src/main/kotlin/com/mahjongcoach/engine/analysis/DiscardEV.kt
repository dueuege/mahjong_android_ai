package com.mahjongcoach.engine.analysis

import com.mahjongcoach.engine.Acceptance
import com.mahjongcoach.engine.Advisor
import com.mahjongcoach.engine.DiscardOption
import com.mahjongcoach.engine.Hand
import com.mahjongcoach.engine.MeldType
import com.mahjongcoach.engine.Tiles
import com.mahjongcoach.engine.scoring.SichuanScore
import com.mahjongcoach.engine.scoring.T34

/** One discard ranked by expected value (speed × estimated hand value). */
data class EvOption(
    val discard: Int,
    val resultingShanten: Int,
    val ukeire: Int,
    val valueX: Double,            // estimated win multiplier (番/倍)
    val ev: Double,                // speed × value — the number to maximise
    val acceptance: List<Acceptance>,
) {
    val discardName: String get() = Tiles.cnName(discard)
}

/**
 * Expected-value discard ranking — the first "superhuman" analysis. Where the
 * `Advisor` ranks by raw tile acceptance (ukeire) alone, this weights each
 * discard by the **value** of the win it's heading toward (清一色 ×4 beats a
 * faster 平胡 ×1) and its **speed**. The result is the play a strong player
 * reaches by feel and a calculator reaches exactly.
 *
 * Pure Kotlin; reuses `Advisor.recommendDiscard` for shanten/ukeire and
 * `scoring/SichuanScore` for value. Exact for closed hands; meld tiles are
 * folded into the scored hand (approximate for open hands).
 */
object DiscardEV {

    fun rank(hand: Hand, seen: IntArray = IntArray(Tiles.TILE_KINDS)): List<EvOption> {
        val advisor = Advisor(seen)
        val advice = advisor.recommendDiscard(hand)
        return advice.options.map { opt ->
            val post = hand.concealed.copyOf().also { it[opt.discard]-- }
            val value = estimateValue(post, opt, hand)
            // Speed: tiles that advance, discounted by how far from tenpai.
            val speed = opt.ukeireCount.toDouble() / (opt.resultingShanten + 1)
            EvOption(opt.discard, opt.resultingShanten, opt.ukeireCount, value, speed * value, opt.acceptance)
        }.sortedByDescending { it.ev }
    }

    /** Best (highest-EV) discard, or null for a non-14-tile hand. */
    fun best(hand: Hand, seen: IntArray = IntArray(Tiles.TILE_KINDS)): EvOption? =
        rank(hand, seen).firstOrNull()

    private fun estimateValue(post: IntArray, opt: DiscardOption, hand: Hand): Double {
        // Tenpai: exact — average the completed-hand multiplier over the waits,
        // weighted by how many of each winning tile remain.
        if (opt.resultingShanten == 0 && opt.acceptance.isNotEmpty()) {
            var weighted = 0.0
            var total = 0
            val kans = hand.melds.filter { it.type == MeldType.KAN }.map { it.tile }
            for (acc in opt.acceptance) {
                val full = full34(post, acc.tile, hand)
                val r = SichuanScore.score(full, kans)
                if (r.win) { weighted += r.multiplier.toDouble() * acc.remaining; total += acc.remaining }
            }
            if (total > 0) return weighted / total
        }
        return routeValue(post)
    }

    /** Heuristic value for a not-yet-tenpai hand from the route it's heading. */
    private fun routeValue(post: IntArray): Double {
        val perSuit = IntArray(3)
        for (i in 0 until Tiles.TILE_KINDS) perSuit[i / 9] += post[i]
        val total = perSuit.sum().coerceAtLeast(1)
        val topFrac = (perSuit.maxOrNull() ?: 0).toDouble() / total
        val pairs = (0 until Tiles.TILE_KINDS).count { post[it] >= 2 }
        var v = 1.0
        when {
            topFrac >= 0.85 -> v = maxOf(v, 3.0)   // strongly heading 清一色
            topFrac >= 0.65 -> v = maxOf(v, 1.8)
        }
        when {
            pairs >= 4 -> v = maxOf(v, 2.0)        // heading 碰碰胡 / 七对
            pairs >= 3 -> v = maxOf(v, 1.4)
        }
        return v
    }

    /** 34-length counts for the completed hand = post-discard + win tile + melds. */
    private fun full34(post: IntArray, winTile: Int, hand: Hand): IntArray {
        val full = IntArray(T34.KINDS)
        for (i in 0 until Tiles.TILE_KINDS) full[i] = post[i]
        full[winTile]++
        for (m in hand.melds) full[m.tile] += if (m.type == MeldType.KAN) 4 else 3
        return full
    }
}
