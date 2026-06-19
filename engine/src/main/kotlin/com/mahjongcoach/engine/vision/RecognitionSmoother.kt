package com.mahjongcoach.engine.vision

/**
 * Temporal smoothing for camera tile recognition. A per-frame detector is noisy
 * (a tile flickers, a hand half-occluded for one frame); feeding raw frames to
 * the advisor would make the advice jump around. This keeps a sliding window of
 * recent frames and reports the per-tile MEDIAN count, which rejects one-off
 * misreads while staying responsive.
 *
 * Pure logic (no Android), so it's unit-tested on the JVM. Works for either tile
 * space (27 Sichuan / 34 with honors) — it just smooths whatever length it's fed.
 */
class RecognitionSmoother(private val window: Int = 7) {
    init { require(window >= 1) }

    private val frames = ArrayDeque<IntArray>()

    /** Add a frame's detected counts; returns the current smoothed estimate. */
    fun submit(frame: IntArray): IntArray {
        frames.addLast(frame.copyOf())
        while (frames.size > window) frames.removeFirst()
        return stable()
    }

    /** Smoothed counts = per-tile median over the window. */
    fun stable(): IntArray {
        val n = frames.firstOrNull()?.size ?: return IntArray(0)
        val out = IntArray(n)
        val mid = frames.size / 2
        for (i in 0 until n) {
            val sorted = frames.map { it[i] }.sorted()
            out[i] = sorted[mid]
        }
        return out
    }

    /** True once the window is full enough to trust (≥ ceil(window/2) frames). */
    fun isStable(): Boolean = frames.size >= (window + 1) / 2

    fun reset() = frames.clear()
}
