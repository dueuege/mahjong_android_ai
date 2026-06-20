package com.mahjongcoach.app.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mahjongcoach.engine.Tiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Append-only log of recognizer corrections: when the user opens the edit
 * sheet and tweaks the hand counts, we save the recognizer's last frame
 * alongside both the detected counts and the human-corrected counts.
 *
 * This is the seed for an "improve itself" loop — the corrections can later be
 * fed back as training samples. For now we just write JSONL + JPG to the
 * private files dir; export and retraining are future work.
 *
 * Storage layout: `context.filesDir/corrections/`
 *   - `corrections.jsonl`  — one JSON line per correction, see [Entry] schema
 *   - `frames/<id>.jpg`    — the frame the recognizer was looking at
 *
 * Empty corrections (no diff between detected and corrected) are skipped so
 * the file doesn't bloat with idempotent edits.
 */
class CorrectionLog(private val context: Context) {

    private val root: File by lazy {
        File(context.filesDir, "corrections").also { it.mkdirs() }
    }
    private val framesDir: File by lazy {
        File(root, "frames").also { it.mkdirs() }
    }
    private val jsonl: File by lazy { File(root, "corrections.jsonl") }

    /**
     * Log [corrected] versus the recognizer's [detected] view. [frame] is the
     * last bitmap the recognizer ran on; we encode it to JPEG so disk usage
     * stays modest (typically < 300 KB per shot). Returns the id assigned, or
     * null if nothing was logged (no diff, or write failed).
     */
    fun log(detected: IntArray, corrected: IntArray, frame: Bitmap?): String? {
        if (!detected.diffs(corrected)) return null
        val id = System.currentTimeMillis().toString(36)
        runCatching {
            frame?.let {
                FileOutputStream(File(framesDir, "$id.jpg")).use { out ->
                    it.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
            }
            val line = JSONObject().apply {
                put("id", id)
                put("ts", System.currentTimeMillis())
                put("detected", detected.toJsonArray())
                put("corrected", corrected.toJsonArray())
                put("detectedNotation", detected.toCompact())
                put("correctedNotation", corrected.toCompact())
                put("framePath", if (frame != null) "frames/$id.jpg" else JSONObject.NULL)
            }.toString()
            jsonl.appendText(line + "\n")
        }.onFailure { Log.w(TAG, "log() failed for $id", it); return null }
        return id
    }

    fun count(): Int = runCatching {
        if (!jsonl.exists()) 0 else jsonl.useLines { it.count() }
    }.getOrDefault(0)

    /** Total bytes on disk under the corrections directory (jsonl + frames). */
    fun sizeBytes(): Long = runCatching {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /** Wipe every correction. Used by the Settings "Clear" affordance. */
    fun clear(): Boolean = runCatching {
        framesDir.listFiles()?.forEach { it.delete() }
        if (jsonl.exists()) jsonl.delete()
        true
    }.getOrDefault(false)

    private fun IntArray.diffs(other: IntArray): Boolean {
        if (size != other.size) return true
        for (i in indices) if (this[i] != other[i]) return true
        return false
    }

    private fun IntArray.toJsonArray(): JSONArray {
        val a = JSONArray()
        for (v in this) a.put(v)
        return a
    }

    private fun IntArray.toCompact(): String {
        val sb = StringBuilder()
        for (suit in 0..2) {
            val digits = StringBuilder()
            for (rank in 0..8) {
                val tile = suit * 9 + rank
                if (tile >= size) break
                repeat(this[tile]) { digits.append(rank + 1) }
            }
            if (digits.isNotEmpty()) {
                sb.append(digits).append(when (suit) { 0 -> "m"; 1 -> "p"; else -> "s" })
            }
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "CorrectionLog"
        /** All 27 entries zero — sentinel for "recognizer had nothing." */
        val EMPTY: IntArray get() = IntArray(Tiles.TILE_KINDS)
    }
}
