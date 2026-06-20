package com.mahjongcoach.app.data

import android.content.Context
import android.util.Log
import java.io.FileNotFoundException
import org.json.JSONObject

/**
 * Reads a debug-only `assets/secrets.json` and merges it into [Settings]. The
 * file lives under `app/src/debug/assets/secrets.json` (git-ignored, see
 * `secrets.json.example` for the schema) so it ships in the debug APK only —
 * release builds just have nothing at the path and this is a no-op.
 *
 * Same JSON schema as the Settings "Config JSON" field, plus an optional
 * `_comment` key that's stripped (handy for inline notes in the file).
 */
object SecretsLoader {
    const val ASSET_PATH = "secrets.json"
    private const val TAG = "SecretsLoader"

    /** True if a `secrets.json` is packaged in this APK. */
    fun isPresent(context: Context): Boolean = runCatching {
        context.assets.list("")?.contains(ASSET_PATH) == true
    }.getOrDefault(false)

    /**
     * Load the file and merge it on top of [base]. Returns null if the asset
     * is missing or unparseable; logs the reason to logcat.
     */
    fun load(context: Context, base: Settings = Settings()): Settings? {
        val raw = try {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            return null
        } catch (e: Exception) {
            Log.w(TAG, "failed to read $ASSET_PATH", e)
            return null
        }
        val cleaned = runCatching {
            // Strip the optional _comment key so it doesn't pollute persisted state.
            val o = JSONObject(raw)
            o.remove("_comment")
            o.toString()
        }.getOrDefault(raw)
        return Settings.fromJson(cleaned, base = base)
            .onFailure { Log.w(TAG, "secrets.json parse error: ${it.message}") }
            .getOrNull()
    }
}
