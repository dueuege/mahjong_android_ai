package com.mahjongcoach.engine

/**
 * Shanten = number of tile swaps away from tenpai+1, i.e. how far the hand is
 * from completion.
 *   -1 => already a winning hand (和牌)
 *    0 => tenpai (one tile away from winning)
 *    n => n away from tenpai
 *
 * Sichuan-relevant winning shapes handled here:
 *   1. Standard: 4 melds + 1 pair. Sequences are allowed in the CONCEALED hand
 *      (you simply may not CHI from the table — but called melds are PON/KAN
 *      only, which this model already enforces).
 *   2. Seven pairs (七对): 7 distinct pairs. Only valid with a fully concealed
 *      hand (no called melds).
 *
 * 定缺 (void suit): a winning hand contains no void-suit tiles. Callers should
 * zero out the void suit before asking for shanten (see [Advisor]); void tiles
 * are dead weight and forced discards.
 */
object Shanten {

    const val WIN = -1

    /** Best (lowest) shanten across all legal Sichuan shapes. */
    fun shanten(hand: Hand): Int {
        val std = standard(hand.concealed, hand.melds.size)
        // Seven pairs requires a 13/14-tile fully concealed hand.
        if (hand.melds.isEmpty()) {
            return minOf(std, sevenPairs(hand.concealed))
        }
        return std
    }

    /**
     * Standard-form shanten for [counts], given [meldedSets] already-completed
     * melds (called PON/KAN). Pure function: does not mutate [counts].
     */
    fun standard(counts: IntArray, meldedSets: Int): Int {
        val c = counts.copyOf()
        val concealedSlots = 4 - meldedSets // meld blocks still to form from hand

        // Score = 2*completeSets + partials (+1 if a pair head is reserved).
        // shanten = 8 - score. We maximise score, hence minimise shanten.

        // Option A: no reserved pair head (e.g. tanki / not-yet-paired shapes).
        var best = 8 - (2 * meldedSets + search(c, 0, concealedSlots))

        // Option B: reserve each possible pair head, then decompose the rest.
        for (i in 0 until Tiles.TILE_KINDS) {
            if (c[i] >= 2) {
                c[i] -= 2
                val score = 2 * meldedSets + search(c, 0, concealedSlots) + 1
                best = minOf(best, 8 - score)
                c[i] += 2
            }
        }
        return best
    }

    /**
     * Maximises (2*completeSets + partialBlocks) obtainable from [c] starting at
     * tile [start], using at most [blocksLeft] blocks. A "block" is a complete
     * set (triplet/sequence, worth 2) or a partial (pair/two-tile proto-run,
     * worth 1). Leftover singles cost nothing.
     */
    private fun search(c: IntArray, start: Int, blocksLeft: Int): Int {
        if (blocksLeft == 0) return 0
        var i = start
        while (i < Tiles.TILE_KINDS && c[i] == 0) i++
        if (i >= Tiles.TILE_KINDS) return 0

        val rank = i % Tiles.RANKS
        var best = 0

        // Complete triplet (刻子).
        if (c[i] >= 3) {
            c[i] -= 3
            best = maxOf(best, 2 + search(c, i, blocksLeft - 1))
            c[i] += 3
        }
        // Complete sequence (顺子) — only within a suit, so rank <= 7.
        if (rank <= 6 && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 1]--; c[i + 2]--
            best = maxOf(best, 2 + search(c, i, blocksLeft - 1))
            c[i]++; c[i + 1]++; c[i + 2]++
        }
        // Partial pair (对子) -> aiming for a triplet.
        if (c[i] >= 2) {
            c[i] -= 2
            best = maxOf(best, 1 + search(c, i, blocksLeft - 1))
            c[i] += 2
        }
        // Partial adjacent (两面/边张) -> aiming for a sequence.
        if (rank <= 7 && c[i + 1] > 0) {
            c[i]--; c[i + 1]--
            best = maxOf(best, 1 + search(c, i, blocksLeft - 1))
            c[i]++; c[i + 1]++
        }
        // Partial gap (嵌张) -> aiming for a sequence.
        if (rank <= 6 && c[i + 2] > 0) {
            c[i]--; c[i + 2]--
            best = maxOf(best, 1 + search(c, i, blocksLeft - 1))
            c[i]++; c[i + 2]++
        }
        // Drop one copy as a leftover single (does not consume a block).
        c[i]--
        best = maxOf(best, search(c, i, blocksLeft))
        c[i]++

        return best
    }

    /**
     * Seven pairs (七对) shanten.
     *   pairs  = tile kinds with >= 2 copies
     *   kinds  = distinct tile kinds present
     *   shanten = 6 - pairs + max(0, 7 - kinds)
     *
     * The `7 - kinds` term accounts for needing seven DISTINCT pairs: a fourth
     * copy of a tile cannot form a second pair, so too few distinct kinds costs
     * extra draws.
     */
    fun sevenPairs(counts: IntArray): Int {
        var pairs = 0
        var kinds = 0
        for (i in 0 until Tiles.TILE_KINDS) {
            if (counts[i] >= 1) kinds++
            if (counts[i] >= 2) pairs++
        }
        return 6 - pairs + maxOf(0, 7 - kinds)
    }
}
