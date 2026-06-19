package com.mahjongcoach.engine

/**
 * Tile model for Sichuan mahjong (血战到底).
 *
 * Sichuan uses ONLY three suits — no winds, no dragons, no flowers:
 *   - man  (万, "characters")  -> suit index 0
 *   - pin  (筒, "dots/circles") -> suit index 1
 *   - sou  (条, "bamboo")       -> suit index 2
 *
 * Each suit has ranks 1..9, four copies each => 108 tiles total.
 *
 * Internal encoding: a tile is an Int index 0..26.
 *   suit = index / 9          (0=man, 1=pin, 2=sou)
 *   rank = index % 9 + 1      (1..9)
 *
 * A "hand" is most efficiently represented as an IntArray(27) of counts,
 * which is what every algorithm in this package consumes.
 */

enum class Suit(val code: Char, val cn: String) {
    MAN('m', "万"),
    PIN('p', "筒"),
    SOU('s', "条");

    companion object {
        fun fromCode(c: Char): Suit = when (c) {
            'm' -> MAN
            'p' -> PIN
            's' -> SOU
            else -> throw IllegalArgumentException("Unknown suit code '$c' (Sichuan uses only m/p/s)")
        }
        fun fromIndex(i: Int): Suit = entries[i]
    }
}

object Tiles {
    const val SUIT_COUNT = 3
    const val RANKS = 9
    const val TILE_KINDS = SUIT_COUNT * RANKS // 27
    const val COPIES = 4

    fun index(suit: Suit, rank: Int): Int {
        require(rank in 1..9) { "rank must be 1..9, got $rank" }
        return suit.ordinal * RANKS + (rank - 1)
    }

    fun suitOf(index: Int): Suit = Suit.fromIndex(index / RANKS)
    fun rankOf(index: Int): Int = index % RANKS + 1

    fun name(index: Int): String = "${rankOf(index)}${suitOf(index).code}"
    fun cnName(index: Int): String = "${rankOf(index)}${suitOf(index).cn}"

    /** First tile index of a suit (inclusive). */
    fun suitStart(suit: Suit): Int = suit.ordinal * RANKS

    /**
     * Parse compact notation like "123m 456p 7799s" into a counts array.
     * Digits accumulate until a suit code is hit; spaces are ignored.
     * Example: "1112345678999m" is a 13-tile pure-man hand.
     */
    fun parse(notation: String): IntArray {
        val counts = IntArray(TILE_KINDS)
        val pending = ArrayList<Int>()
        for (ch in notation) {
            when {
                ch.isWhitespace() -> {}
                ch.isDigit() -> {
                    val r = ch - '0'
                    require(r in 1..9) { "rank digit must be 1..9, got $r in \"$notation\"" }
                    pending.add(r)
                }
                else -> {
                    val suit = Suit.fromCode(ch)
                    for (r in pending) counts[index(suit, r)]++
                    pending.clear()
                }
            }
        }
        require(pending.isEmpty()) { "notation \"$notation\" ended with ranks but no suit code" }
        return counts
    }

    /** Inverse of [parse]: render a counts array back to compact notation. */
    fun toNotation(counts: IntArray): String {
        val sb = StringBuilder()
        for (suit in Suit.entries) {
            var any = false
            for (rank in 1..9) {
                val c = counts[index(suit, rank)]
                repeat(c) { sb.append(rank); any = true }
            }
            if (any) sb.append(suit.code)
        }
        return sb.toString()
    }

    fun totalTiles(counts: IntArray): Int = counts.sum()
}
