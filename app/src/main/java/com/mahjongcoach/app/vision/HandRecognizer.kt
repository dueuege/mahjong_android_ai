package com.mahjongcoach.app.vision

import androidx.camera.core.ImageProxy

/**
 * Reads YOUR OWN hand from the camera and returns the detected tiles as a
 * 27-length counts array (see engine `Tiles`).
 *
 * ⚠️ ETHICAL BOUNDARY (enforced by design): this points at your own hand and the
 * public board only. There is deliberately no API here for capturing opponents'
 * concealed tiles or the wall. Don't add one.
 *
 * v2 implementation plan (see docs/ROADMAP.md):
 *   1. Object detection (YOLO-style, TFLite or MediaPipe) to find tile bounding
 *      boxes in the frame — your hand is the easiest target (fixed-ish angle,
 *      lighting, ~13–14 tiles in a row).
 *   2. Per-box classification into one of 27 Sichuan tile kinds.
 *   3. Temporal smoothing across frames (majority vote) to kill flicker.
 *   4. Hand off the stabilised counts to GameState.setHandCounts(...).
 *
 * Bootstrapping data: open riichi/Chinese tile datasets exist; Sichuan uses a
 * 27-kind subset (no honors), so they transfer well after relabeling.
 */
interface HandRecognizer {
    /** @return detected tile counts, or null if the frame was unusable. */
    fun recognize(image: ImageProxy): IntArray?
}

/** Placeholder until the TFLite model is wired up. */
class StubHandRecognizer : HandRecognizer {
    override fun recognize(image: ImageProxy): IntArray? {
        image.close()
        return null // TODO: run on-device model; see class doc.
    }
}
