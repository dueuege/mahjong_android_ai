package com.mahjongcoach.engine.scoring

enum class Ruleset { SICHUAN, JAPANESE }

/**
 * Bridges a RECOGNISED revealed hand (from a photo, or manual entry) to the
 * scoring engines. This is the backend for the "calculate the points" feature.
 *
 * Inputs are public revealed tiles at showdown; for Japanese the player also
 * supplies the situational context (win tile, tsumo/ron, winds, dora) that a
 * photo cannot show.
 */
object ScoreService {

    fun scoreSichuan(concealed34: IntArray, melds: List<Group> = emptyList(), kans: List<Int> = emptyList()): SichuanResult {
        val full = concealed34.copyOf()
        for (m in melds) for (t in m.tiles) full[t]++
        return SichuanScore.score(full, kans)
    }

    fun scoreJapanese(concealed34: IntArray, melds: List<Group>, ctx: RiichiContext): RiichiResult =
        RiichiScore.score(concealed34, melds, ctx)

    /** One-line human-readable summary, used by the photo-scoring screen. */
    fun describe(
        ruleset: Ruleset,
        concealed34: IntArray,
        melds: List<Group> = emptyList(),
        kans: List<Int> = emptyList(),
        ctx: RiichiContext? = null,
    ): String = when (ruleset) {
        Ruleset.SICHUAN -> scoreSichuan(concealed34, melds, kans).toString()
        Ruleset.JAPANESE -> {
            requireNotNull(ctx) { "Japanese scoring needs win-tile/tsumo/wind/dora context" }
            scoreJapanese(concealed34, melds, ctx).toString()
        }
    }
}
