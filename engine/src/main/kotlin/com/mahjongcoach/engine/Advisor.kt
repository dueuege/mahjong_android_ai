package com.mahjongcoach.engine

/** A tile the hand can usefully draw, plus how many copies remain unseen. */
data class Acceptance(val tile: Int, val remaining: Int) {
    val name: String get() = Tiles.cnName(tile)
}

/**
 * Result of evaluating ONE candidate discard from a 14-tile hand:
 * the resulting shanten, the tiles that then advance the hand (ukeire), and the
 * total number of such tiles remaining unseen (the headline number to maximise).
 */
data class DiscardOption(
    val discard: Int,
    val resultingShanten: Int,
    val acceptance: List<Acceptance>,
    val forcedByVoid: Boolean = false,
) {
    val ukeireCount: Int get() = acceptance.sumOf { it.remaining }
    val discardName: String get() = Tiles.cnName(discard)
}

/** Top-level advice for the current hand. */
data class Advice(
    val shanten: Int,
    val isWin: Boolean,
    val isTenpai: Boolean,
    val voidTilesHeld: Int,
    val options: List<DiscardOption>, // best first
) {
    val best: DiscardOption? get() = options.firstOrNull()
}

/**
 * The decision brain. Pure, deterministic, side-effect free — this is the part
 * that must be provably correct, because every UI/voice/vision layer ultimately
 * just feeds tiles into here and renders [Advice].
 *
 * "seen" lets callers subtract tiles already visible on the table (your own
 * discards, opponents' discards heard via audio, called melds, the board video)
 * so the remaining-copies count for each acceptance reflects reality. Pass an
 * empty/zero array to ignore table knowledge.
 */
class Advisor(private val seen: IntArray = IntArray(Tiles.TILE_KINDS)) {

    init {
        require(seen.size == Tiles.TILE_KINDS) { "seen must be length ${Tiles.TILE_KINDS}" }
    }

    /** Shanten of a 13-tile (post-discard) hand. */
    fun shanten(hand: Hand): Int = Shanten.shanten(effectiveHand(hand))

    /**
     * Recommend a discard from a hand that currently holds a drawn tile
     * (concealed = 14 - 3*melds tiles). Returns ranked [DiscardOption]s.
     *
     * Ranking: lowest resulting shanten first, then highest ukeire count.
     * 定缺: if any void-suit tiles are held, those become the only candidates —
     * you must clear the void suit before the hand can ever win.
     */
    fun recommendDiscard(hand: Hand): Advice {
        val curShanten = Shanten.shanten(effectiveHand(hand))
        val voidHeld = hand.voidTilesHeld()

        val candidates: List<Int> = if (voidHeld > 0) {
            hand.voidTileIndices()
        } else {
            (0 until Tiles.TILE_KINDS).filter { hand.concealed[it] > 0 }
        }

        val options = candidates.map { tile ->
            val after = hand.concealed.copyOf()
            after[tile]--
            val afterHand = hand.copy(concealed = after)
            val effShanten = Shanten.shanten(effectiveHand(afterHand))
            val accept = acceptance(afterHand, effShanten)
            DiscardOption(
                discard = tile,
                resultingShanten = effShanten,
                acceptance = accept,
                forcedByVoid = voidHeld > 0,
            )
        }.sortedWith(
            compareBy<DiscardOption> { it.resultingShanten }
                .thenByDescending { it.ukeireCount }
                .thenBy { it.discard },
        )

        return Advice(
            shanten = curShanten,
            isWin = curShanten == Shanten.WIN,
            isTenpai = curShanten == 0,
            voidTilesHeld = voidHeld,
            options = options,
        )
    }

    /**
     * Tiles that, if drawn into this (post-discard) hand, lower its shanten by 1.
     * Void-suit tiles are never acceptances (they cannot be part of a win).
     */
    fun acceptance(hand: Hand, currentShanten: Int = shanten(hand)): List<Acceptance> {
        if (currentShanten == Shanten.WIN) return emptyList()
        val result = ArrayList<Acceptance>()
        val voidStart = hand.voidSuit?.let { Tiles.suitStart(it) } ?: -1

        for (tile in 0 until Tiles.TILE_KINDS) {
            if (voidStart >= 0 && tile in voidStart until voidStart + Tiles.RANKS) continue
            if (hand.concealed[tile] >= Tiles.COPIES) continue // all 4 already held

            val probe = hand.concealed.copyOf()
            probe[tile]++
            val s = Shanten.shanten(hand.copy(concealed = probe))
            if (s < currentShanten) {
                val remaining = Tiles.COPIES - hand.concealed[tile] - seen[tile]
                if (remaining > 0) result.add(Acceptance(tile, remaining))
            }
        }
        return result
    }

    /**
     * The hand the algorithms should reason over: void-suit tiles removed, since
     * a winning hand contains none. The remaining structure is what we optimise.
     */
    private fun effectiveHand(hand: Hand): Hand {
        val v = hand.voidSuit ?: return hand
        if (!hand.hasVoidTiles()) return hand
        val c = hand.concealed.copyOf()
        val start = Tiles.suitStart(v)
        for (i in start until start + Tiles.RANKS) c[i] = 0
        return hand.copy(concealed = c)
    }
}
