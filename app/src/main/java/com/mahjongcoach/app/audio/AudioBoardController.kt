package com.mahjongcoach.app.audio

import com.mahjongcoach.app.GameState
import com.mahjongcoach.engine.SpeechParser

/**
 * Folds spoken-call events (from [BoardAudioListener] → [SpeechParser]) into the
 * board side of [GameState]. Everything here comes from PUBLIC announcements; it
 * marks tiles as "seen" (no longer drawable) and nothing about concealed hands.
 *
 * Heuristics (best-effort; the UI keeps one-tap correction):
 *   - a bare tile named  -> treated as a discard placed on the table (seen ×1)
 *   - 碰 <tile>           -> three copies are now off the wall (seen = 3)
 *   - 杠 <tile>           -> four copies off the wall (seen = 4)
 *   - 胡/和               -> hand over (caller may trigger scoring / new round)
 */
class AudioBoardController(
    private val state: GameState,
    private val onWin: () -> Unit = {},
) {
    fun apply(events: List<SpeechParser.Event>) {
        for (e in events) when (e) {
            is SpeechParser.Event.TileSpoken -> state.observeSeen(e.tile)
            is SpeechParser.Event.Pon -> e.tile?.let { state.observeSeen(it, 3, additive = false) }
            is SpeechParser.Event.Kan -> e.tile?.let { state.observeSeen(it, 4, additive = false) }
            SpeechParser.Event.Win -> onWin()
        }
    }

    /** Parse a raw transcript and apply it (used by the live mic path and tests). */
    fun applyTranscript(text: String) = apply(SpeechParser.parse(text))
}
