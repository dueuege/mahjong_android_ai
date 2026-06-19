package com.mahjongcoach.engine.scoring

/**
 * 34-kind tile space used for SCORING (Japanese riichi needs honors; Sichuan is
 * the 0..26 subset). Kept separate from the Sichuan efficiency engine's 27-kind
 * `Tiles` so neither complicates the other — the first 27 indices are identical,
 * so a Sichuan hand maps in directly.
 *
 *   0..8   man 1-9   (m, 万)
 *   9..17  pin 1-9   (p, 筒)
 *   18..26 sou 1-9   (s, 条/索)
 *   27..30 winds     East, South, West, North      (z1..z4)
 *   31..33 dragons   White(haku), Green(hatsu), Red(chun)  (z5..z7)
 */
object T34 {
    const val KINDS = 34
    const val COPIES = 4

    const val EAST = 27; const val SOUTH = 28; const val WEST = 29; const val NORTH = 30
    const val HAKU = 31; const val HATSU = 32; const val CHUN = 33

    fun isHonor(i: Int): Boolean = i >= 27
    fun isWind(i: Int): Boolean = i in 27..30
    fun isDragon(i: Int): Boolean = i in 31..33
    fun suitOf(i: Int): Int = if (i < 27) i / 9 else -1        // 0=m,1=p,2=s, -1=honor
    fun rankOf(i: Int): Int = if (i < 27) i % 9 + 1 else 0     // 1..9, 0 for honors
    fun isTerminal(i: Int): Boolean = i < 27 && (rankOf(i) == 1 || rankOf(i) == 9)
    fun isTerminalOrHonor(i: Int): Boolean = isHonor(i) || isTerminal(i)
    /** Can a run start here? Numbers 1..7 within a suit. */
    fun canStartRun(i: Int): Boolean = i < 27 && rankOf(i) <= 7

    private val honorNames = arrayOf("東", "南", "西", "北", "白", "發", "中")

    fun name(i: Int): String = when {
        i < 27 -> "${rankOf(i)}${"mps"[suitOf(i)]}"
        else -> honorNames[i - 27]
    }

    /**
     * Parse riichi notation: digits then a suit letter, honors as `z` with
     * 1..7 = E,S,W,N,白,發,中.  e.g. "123m 99p 1112z 5z".
     */
    fun parse(notation: String): IntArray {
        val counts = IntArray(KINDS)
        val pending = ArrayList<Int>()
        for (ch in notation) {
            when {
                ch.isWhitespace() -> {}
                ch.isDigit() -> pending.add(ch - '0')
                else -> {
                    for (r in pending) counts[indexFor(ch, r)]++
                    pending.clear()
                }
            }
        }
        require(pending.isEmpty()) { "notation \"$notation\" ended without a suit letter" }
        return counts
    }

    private fun indexFor(suit: Char, rank: Int): Int = when (suit) {
        'm' -> { require(rank in 1..9); rank - 1 }
        'p' -> { require(rank in 1..9); 9 + rank - 1 }
        's' -> { require(rank in 1..9); 18 + rank - 1 }
        'z' -> { require(rank in 1..7) { "honor z$rank out of range" }; 27 + rank - 1 }
        else -> throw IllegalArgumentException("bad suit '$suit'")
    }

    fun fromSichuan(counts27: IntArray): IntArray {
        require(counts27.size == 27)
        return IntArray(KINDS).also { counts27.copyInto(it) }
    }
}
