package com.mahjongcoach.engine

/** A called (open) meld. Sichuan allows only PON and KAN — never CHI. */
enum class MeldType { PON, KAN }

data class Meld(val type: MeldType, val tile: Int) {
    override fun toString(): String =
        "${type.name}(${Tiles.cnName(tile)})"
}

/**
 * A full Sichuan hand state from one player's seat: the concealed tiles
 * (as a counts array), any open melds called from the table, and the
 * declared void suit (定缺).
 *
 * Invariant tracked loosely (the advisor tolerates transient states such as
 * holding 14 tiles right after a draw):
 *   concealedTiles + 3 * melds.size  ∈ {13, 14}
 *
 * 定缺 rule: a winning hand may contain ZERO tiles of [voidSuit]. While any
 * void-suit tile is held you cannot win, so they are dumped first.
 */
data class Hand(
    val concealed: IntArray,          // IntArray(27) counts
    val melds: List<Meld> = emptyList(),
    val voidSuit: Suit? = null,
) {
    init {
        require(concealed.size == Tiles.TILE_KINDS) { "concealed must be length ${Tiles.TILE_KINDS}" }
    }

    val concealedCount: Int get() = concealed.sum()
    val tileCount: Int get() = concealedCount + 3 * melds.size

    /** Count of void-suit tiles currently held (these block a win). */
    fun voidTilesHeld(): Int {
        val v = voidSuit ?: return 0
        var n = 0
        val start = Tiles.suitStart(v)
        for (i in start until start + Tiles.RANKS) n += concealed[i]
        return n
    }

    fun hasVoidTiles(): Boolean = voidTilesHeld() > 0

    /** List the distinct void-suit tile indices currently held. */
    fun voidTileIndices(): List<Int> {
        val v = voidSuit ?: return emptyList()
        val start = Tiles.suitStart(v)
        return (start until start + Tiles.RANKS).filter { concealed[it] > 0 }
    }

    fun copyConcealed(): IntArray = concealed.copyOf()

    override fun toString(): String {
        val voidStr = voidSuit?.let { " 定缺${it.cn}" } ?: ""
        val meldStr = if (melds.isEmpty()) "" else " " + melds.joinToString(",")
        return Tiles.toNotation(concealed) + meldStr + voidStr
    }

    // Arrays need explicit equals/hashCode in a data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Hand) return false
        return concealed.contentEquals(other.concealed) &&
            melds == other.melds && voidSuit == other.voidSuit
    }

    override fun hashCode(): Int {
        var r = concealed.contentHashCode()
        r = 31 * r + melds.hashCode()
        r = 31 * r + (voidSuit?.hashCode() ?: 0)
        return r
    }

    companion object {
        fun of(notation: String, voidSuit: Suit? = null, melds: List<Meld> = emptyList()): Hand =
            Hand(Tiles.parse(notation), melds, voidSuit)
    }
}
