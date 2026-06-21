package com.mahjongcoach.engine.analysis

import com.mahjongcoach.engine.Hand
import com.mahjongcoach.engine.Shanten
import com.mahjongcoach.engine.Tiles
import kotlin.random.Random

/** Monte-Carlo win-rate estimate for your own hand. */
data class WinEstimate(val winProb: Double, val avgDrawsToWin: Double?, val sims: Int, val drawsLeft: Int)

/**
 * Estimates your probability of completing the hand before the wall runs out,
 * by simulating draws from the **unseen** wall and advancing greedily by
 * acceptance. This is the number humans approximate worst — it ties efficiency,
 * value (via where you steer) and the live tile counts into one figure that
 * drives push/fold and route choices.
 *
 * Pure Kotlin, bounded for on-device use (default 400 sims). Run off the main
 * thread — it's CPU work. Aggregate wall (opponents not modelled yet); treats
 * 定缺 tiles as dead draws.
 */
object WinRate {

    fun estimate(
        hand: Hand,
        seen: IntArray = IntArray(Tiles.TILE_KINDS),
        drawsLeft: Int = defaultDrawsLeft(seen),
        sims: Int = 400,
        rng: Random = Random(0x9E3779B9),
    ): WinEstimate {
        // Start from a 13-tile concealed hand (discard the best tile if 14).
        val start = startHand13(hand)
        val voidStart = hand.voidSuit?.let { Tiles.suitStart(it) } ?: -1

        // Unseen wall counts (copies not in our hand or the public pile, minus
        // meld tiles). 定缺-suit tiles are excluded — drawing them never helps.
        val wall = IntArray(Tiles.TILE_KINDS)
        var wallTotal = 0
        for (t in 0 until Tiles.TILE_KINDS) {
            if (voidStart >= 0 && t in voidStart until voidStart + Tiles.RANKS) continue
            val used = start[t] + seen[t] + meldCopies(hand, t)
            val avail = (Tiles.COPIES - used).coerceAtLeast(0)
            wall[t] = avail; wallTotal += avail
        }
        if (wallTotal == 0) return WinEstimate(0.0, null, 0, drawsLeft)

        var wins = 0
        var drawSum = 0
        repeat(sims) {
            val h = start.copyOf()
            val w = wall.copyOf()
            var wt = wallTotal
            var cur = Shanten.shanten(Hand(h, hand.melds, hand.voidSuit))
            var won = false
            for (d in 1..drawsLeft) {
                if (wt <= 0) break
                val tile = drawFrom(w, wt, rng); w[tile]--; wt--
                h[tile]++
                val s14 = Shanten.shanten(Hand(h, hand.melds, hand.voidSuit))
                if (s14 == Shanten.WIN) { won = true; drawSum += d; break }
                if (s14 < cur) {
                    discardToShanten(h, hand, s14)   // keep the useful draw
                    cur = s14
                } else {
                    h[tile]--                        // tsumogiri the useless draw
                }
            }
            if (won) wins++
        }
        val p = wins.toDouble() / sims
        val avg = if (wins > 0) drawSum.toDouble() / wins else null
        return WinEstimate(p, avg, sims, drawsLeft)
    }

    /** Rough draws remaining for you from the pooled pond (108 tiles, 4 players). */
    fun defaultDrawsLeft(seen: IntArray): Int =
        ((108 - 52 - seen.sum()) / 4).coerceIn(1, 18)

    /**
     * A cheap analytic win-probability proxy (no simulation) for the fast
     * deterministic path (e.g. the push/fold gate). Monotone in ukeire (more
     * acceptance ⇒ higher), in shanten (closer ⇒ higher), and in draws left.
     * Not as accurate as [estimate]; use that for display.
     */
    fun quickProxy(shanten: Int, ukeire: Int, drawsLeft: Int): Double {
        if (shanten <= Shanten.WIN) return 1.0
        val perDraw = (ukeire / 60.0).coerceIn(0.02, 0.85)
        // Chance to catch at least one useful tile over the remaining draws…
        var p = 1.0 - Math.pow(1.0 - perDraw, drawsLeft.toDouble())
        // …discounted for each extra step still needed beyond tenpai.
        p *= Math.pow(0.45, shanten.coerceAtLeast(0).toDouble())
        return p.coerceIn(0.0, 1.0)
    }

    private fun startHand13(hand: Hand): IntArray {
        val h = hand.concealed.copyOf()
        if (hand.concealedCount % 3 == 2) {
            // 14 tiles: drop the discard that yields the lowest shanten.
            var bestTile = -1; var bestShanten = Int.MAX_VALUE
            for (t in 0 until Tiles.TILE_KINDS) {
                if (h[t] == 0) continue
                h[t]--
                val s = Shanten.shanten(Hand(h, hand.melds, hand.voidSuit))
                h[t]++
                if (s < bestShanten) { bestShanten = s; bestTile = t }
            }
            if (bestTile >= 0) h[bestTile]--
        }
        return h
    }

    /** From a 14-tile [h], remove the tile that reaches [target] shanten. */
    private fun discardToShanten(h: IntArray, hand: Hand, target: Int) {
        for (t in 0 until Tiles.TILE_KINDS) {
            if (h[t] == 0) continue
            h[t]--
            if (Shanten.shanten(Hand(h, hand.melds, hand.voidSuit)) == target) return
            h[t]++
        }
        // Fallback: drop any tile (shouldn't happen).
        for (t in 0 until Tiles.TILE_KINDS) if (h[t] > 0) { h[t]--; return }
    }

    private fun drawFrom(w: IntArray, total: Int, rng: Random): Int {
        var k = rng.nextInt(total)
        for (t in 0 until Tiles.TILE_KINDS) {
            k -= w[t]
            if (k < 0) return t
        }
        for (t in Tiles.TILE_KINDS - 1 downTo 0) if (w[t] > 0) return t
        return 0
    }

    private fun meldCopies(hand: Hand, tile: Int): Int {
        var n = 0
        for (m in hand.melds) if (m.tile == tile) n += if (m.type.name.startsWith("K")) 4 else 3
        return n
    }
}
