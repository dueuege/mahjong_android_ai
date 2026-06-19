package com.mahjongcoach.engine.scoring

enum class GroupType { SEQUENCE, TRIPLET, KAN, PAIR }

/**
 * One group in a parsed winning hand.
 * @param tile lowest tile of a sequence, or the repeated tile otherwise.
 * @param open true if formed by calling from the table (affects yaku/fu).
 */
data class Group(
    val type: GroupType,
    val tile: Int,
    val open: Boolean = false,
) {
    val tiles: List<Int>
        get() = when (type) {
            GroupType.SEQUENCE -> listOf(tile, tile + 1, tile + 2)
            GroupType.PAIR -> listOf(tile, tile)
            GroupType.TRIPLET -> listOf(tile, tile, tile)
            GroupType.KAN -> listOf(tile, tile, tile, tile)
        }
    val isConcealed: Boolean get() = !open
}

/** A full interpretation of a winning hand: 4 sets + 1 pair (or 7 pairs). */
data class Parse(val groups: List<Group>, val chiitoitsu: Boolean = false) {
    val pair: Group get() = groups.first { it.type == GroupType.PAIR }
    val sets: List<Group> get() = groups.filter { it.type != GroupType.PAIR }
}

/**
 * Enumerates every valid decomposition of a complete hand into groups, so the
 * scorer can pick the highest-scoring interpretation (the rule when a hand parses
 * multiple ways). Works on the shared 34-kind space, so it serves both rulesets.
 */
object WinDecomposer {

    /**
     * @param concealed  34-length counts of the concealed portion (includes the
     *                    winning tile).
     * @param calledMelds groups already melded from the table (open), each a
     *                    complete set; they occupy meld slots.
     * @return all standard parses, plus a chiitoitsu parse if applicable. Empty
     *         if the hand is not complete.
     */
    fun decompose(concealed: IntArray, calledMelds: List<Group> = emptyList()): List<Parse> {
        val parses = ArrayList<Parse>()
        val needSets = 4 - calledMelds.size

        // Standard: choose a pair, then split the rest into exactly needSets sets.
        val c = concealed.copyOf()
        for (p in 0 until T34.KINDS) {
            if (c[p] >= 2) {
                c[p] -= 2
                splitSetsRec(c, 0, needSets, ArrayList()) { sets ->
                    parses.add(Parse(calledMelds + sets + Group(GroupType.PAIR, p)))
                }
                c[p] += 2
            }
        }

        // Seven pairs — only a fully concealed 14-tile hand with 7 distinct pairs.
        if (calledMelds.isEmpty()) {
            val pairTiles = (0 until T34.KINDS).filter { concealed[it] == 2 }
            if (pairTiles.size == 7) {
                parses.add(Parse(pairTiles.map { Group(GroupType.PAIR, it) }, chiitoitsu = true))
            }
        }
        return parses
    }

    private fun splitSetsRec(
        c: IntArray,
        start: Int,
        remaining: Int,
        acc: ArrayList<Group>,
        emit: (List<Group>) -> Unit,
    ) {
        if (remaining == 0) {
            if (c.all { it == 0 }) emit(ArrayList(acc)) // every tile consumed
            return
        }
        var i = start
        while (i < T34.KINDS && c[i] == 0) i++
        if (i >= T34.KINDS) return

        // Triplet at i.
        if (c[i] >= 3) {
            c[i] -= 3
            acc.add(Group(GroupType.TRIPLET, i))
            splitSetsRec(c, i, remaining - 1, acc, emit)
            acc.removeAt(acc.lastIndex)
            c[i] += 3
        }
        // Sequence starting at i (numbers only, within a suit).
        if (T34.canStartRun(i) && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 1]--; c[i + 2]--
            acc.add(Group(GroupType.SEQUENCE, i))
            splitSetsRec(c, i, remaining - 1, acc, emit)
            acc.removeAt(acc.lastIndex)
            c[i]++; c[i + 1]++; c[i + 2]++
        }
    }

    /** Kokushi musou (thirteen orphans) — a special non-grouped yakuman shape. */
    fun isKokushi(concealed: IntArray): Boolean {
        val terminals = listOf(0, 8, 9, 17, 18, 26) + (27..33)
        if (concealed.withIndex().any { (i, n) -> n > 0 && i !in terminals }) return false
        if (terminals.any { concealed[it] == 0 }) return false
        return concealed.sum() == 14 && terminals.count { concealed[it] == 2 } == 1
    }
}
