package com.mahjongcoach.app.vision

import android.graphics.Bitmap
import com.mahjongcoach.engine.scoring.Group

/** A revealed hand recognised from a still photo (at showdown / for scoring). */
data class RecognizedHand(
    val concealed34: IntArray,        // 34-length counts of the closed portion
    val melds: List<Group> = emptyList(),
    val kans: List<Int> = emptyList(),
)

/**
 * Recognises a FULLY REVEALED hand from a photo so it can be scored — e.g. a
 * single player's hand laid out at the win, or all four hands at showdown.
 *
 * ⚠️ This is for REVEALED tiles only (scoring time / your own / practice). It is
 * not for photographing opponents' concealed hands mid-game. Same boundary as
 * everywhere else in this app.
 *
 * v2 implementation (shares the detector/classifier with [HandRecognizer], see
 * docs/VISION.md): detect tile boxes in the still, classify each into one of 34
 * kinds, group multiple players by spatial clustering, emit counts per player.
 */
interface RevealedHandRecognizer {
    /** @return one entry per detected player hand, or empty if none found. */
    fun recognize(photo: Bitmap): List<RecognizedHand>
}

class StubRevealedHandRecognizer : RevealedHandRecognizer {
    override fun recognize(photo: Bitmap): List<RecognizedHand> = emptyList() // TODO: model
}
