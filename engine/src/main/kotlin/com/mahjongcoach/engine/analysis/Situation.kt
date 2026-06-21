package com.mahjongcoach.engine.analysis

import com.mahjongcoach.engine.Hand

/**
 * The strategic phase of the round, used to decide which analyses matter so the
 * coach doesn't compute (or explain) everything every turn.
 *
 *  - NEW_GAME — fresh 13-tile hand, no 定缺 yet → recommend the void + route.
 *  - BUILDING — far from tenpai → efficiency / value route dominate.
 *  - RACING   — 1-shanten or tenpai, no big threat → speed + wait quality.
 *  - DEFENSE  — an opponent is threatening → danger / push-fold dominate.
 */
enum class GamePhase { NEW_GAME, BUILDING, RACING, DEFENSE }

object Situation {
    /**
     * @param shanten the hand's best resulting shanten (-1 win, 0 tenpai…).
     * @param threatLevel 0 none, 1 mild, 2 strong (opponent likely tenpai/big).
     *   Filled by the danger model (Phase 2); 0 until then.
     */
    fun classify(hand: Hand, shanten: Int, threatLevel: Int = 0): GamePhase = when {
        threatLevel >= 2 -> GamePhase.DEFENSE
        hand.tileCount == 13 && hand.melds.isEmpty() && hand.voidSuit == null -> GamePhase.NEW_GAME
        shanten <= 1 -> GamePhase.RACING
        else -> GamePhase.BUILDING
    }
}
