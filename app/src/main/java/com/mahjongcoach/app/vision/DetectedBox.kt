package com.mahjongcoach.app.vision

/**
 * One detection from a tile recognizer. Coordinates are normalised to the
 * source frame (0..1 in both dims) so the overlay can position labels without
 * knowing the analyzer's resolution.
 *
 * [tileIndex] is already in engine-space (0..26 per `engine.Tiles`); honors /
 * bonus tiles are dropped in the recognizer's class mask before producing
 * boxes, so the overlay never has to think about them.
 */
data class DetectedBox(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val tileIndex: Int,
    val score: Float,
)
