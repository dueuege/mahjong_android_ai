package com.mahjongcoach.engine.scoring

/**
 * Sichuan 血战到底 scoring. Sichuan uses a multiplicative 倍数 system; exact
 * values are house-dependent, so this encodes a common default and is easy to
 * retune. Honors never appear (suits only).
 *
 * Implemented patterns (multiplier):
 *   平胡 ×1 · 碰碰胡 ×2 · 清一色 ×4 · 清碰(清+碰) ×8
 *   七对 ×4 · 龙七对 ×8 · 清七对 ×16 · 清龙七对 ×32
 *   金钩钓 (all-melds tanki) flagged · 根 (each four-of-a-kind) doubles ×2
 *
 * Scoring is for a REVEALED winning hand (showdown / your own / a practice
 * photo) — never for reading a live concealed hand.
 */
data class SichuanResult(
    val win: Boolean,
    val multiplier: Int,
    val patterns: List<String>,
    val gen: Int,                 // number of 根 (four-of-a-kind)
) {
    override fun toString(): String =
        if (!win) "not a winning hand"
        else "×$multiplier  [${patterns.joinToString(" + ")}]" + if (gen > 0) "  (根×$gen)" else ""
}

object SichuanScore {

    /**
     * @param full  34-length counts of the complete revealed hand (Sichuan uses
     *              indices 0..26). Kan tiles appear as 4 copies here.
     * @param kans  tiles declared as kan (杠) — counted as melded sets and as 根.
     * @param allMeldsCalled true if every set was called from the table (for 金钩钓).
     */
    fun score(full: IntArray, kans: List<Int> = emptyList(), allMeldsCalled: Boolean = false): SichuanResult {
        require(full.size == T34.KINDS)
        if ((27..33).any { full[it] > 0 }) return SichuanResult(false, 0, listOf("honors not allowed"), 0)

        val gen = (0 until 27).count { full[it] == 4 }
        val suitsUsed = (0..2).count { s -> (s * 9 until s * 9 + 9).any { full[it] > 0 } }
        val pure = suitsUsed == 1

        // --- Seven-pairs family (七对) — detect directly to allow 龙 (quads). ---
        if (kans.isEmpty() && full.sum() == 14 && full.all { it % 2 == 0 } &&
            (0 until 27).sumOf { full[it] / 2 } == 7
        ) {
            val quads = (0 until 27).count { full[it] == 4 }
            var mult = 4 * (if (pure) 4 else 1)
            repeat(quads) { mult *= 2 }
            val names = buildList {
                add(if (pure) "清七对" else "七对")
                if (quads == 1) { set(0, if (pure) "清龙七对" else "龙七对") }
                if (quads >= 2) { set(0, if (pure) "清双龙七对" else "双龙七对") }
            }
            return SichuanResult(true, mult, names, quads)
        }

        // --- Standard 4 sets + pair (sequences allowed). ---
        val concealed = full.copyOf()
        val kanGroups = kans.map { Group(GroupType.KAN, it, open = false) }
        kans.forEach { concealed[it] -= 4 }
        val parses = WinDecomposer.decompose(concealed, kanGroups).filter { !it.chiitoitsu }
        if (parses.isEmpty()) return SichuanResult(false, 0, emptyList(), gen)

        // Pick the best-scoring interpretation.
        var best: SichuanResult? = null
        for (parse in parses) {
            val allTriplets = parse.sets.all { it.type == GroupType.TRIPLET || it.type == GroupType.KAN }
            val patterns = ArrayList<String>()
            var mult = 1
            when {
                allTriplets && pure -> { mult = 8; patterns.add("清碰") }
                allTriplets -> { mult = 2; patterns.add("碰碰胡") }
                pure -> { mult = 4; patterns.add("清一色") }
                else -> { mult = 1; patterns.add("平胡") }
            }
            if (allTriplets && allMeldsCalled) patterns.add("金钩钓")
            repeat(gen) { mult *= 2 }
            val r = SichuanResult(true, mult, patterns, gen)
            if (best == null || r.multiplier > best.multiplier) best = r
        }
        return best!!
    }

    /** Convenience for Sichuan notation, e.g. "11223344556677m". */
    fun scoreNotation(notation: String, kans: List<Int> = emptyList()): SichuanResult =
        score(T34.parse(notation), kans)
}
