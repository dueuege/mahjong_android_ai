package com.mahjongcoach.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mahjongcoach.app.llm.ClaudeClient
import com.mahjongcoach.app.llm.DisabledLlm
import com.mahjongcoach.app.llm.EdgeLlmClient
import com.mahjongcoach.app.llm.LlmClient
import com.mahjongcoach.app.llm.OpenAiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

enum class LlmBackend(val label: String) {
    OFF("Off"),
    CLAUDE_API("Claude"),
    OPENAI_COMPAT("OpenAI"),
    EDGE("Edge"),
}

/** Persisted user preferences. */
data class Settings(
    val backend: LlmBackend = LlmBackend.OFF,
    val apiKey: String = "",
    val baseUrl: String = "",            // OpenAI-compat only; blank ⇒ official endpoint
    val model: String = "claude-opus-4-8",
    val extraHeadersJson: String = "",   // JSON object of extra HTTP headers; "" or "{}" = none
    val language: String = "zh-CN",
    val defaultRuleset: String = "sichuan",
    // Coach (live mode) — see coach/CoachScreen.kt
    val useLlmVision: Boolean = false,     // route hand recognition through the configured LlmClient
    val coachAlwaysOn: Boolean = false,    // false (default) = snap mode; true = continuous detection
    val coachIntervalSec: Int = 30,        // always-on detection interval; one of INTERVAL_OPTIONS
    val coachAudioAuto: Boolean = true,    // auto-engage mic on Coach entry once permission granted
    val coachAutoGuide: Boolean = true,    // auto-ask the LLM for guidance after each new hand
    // Roboflow serverless tile detector — takes priority over both on-device
    // ONNX and LLM vision when [roboflowApiKey] is set.
    val roboflowApiKey: String = "",
    val roboflowModelId: String = "mahjong-baq4s/83",
    // "auto" (follow phone), "portrait", or "landscape". Applied app-wide.
    val orientationLock: String = "auto",
) {
    /** Build the configured assistant backend. The coach itself needs none of this. */
    fun buildClient(): LlmClient = when (backend) {
        LlmBackend.OFF -> DisabledLlm
        LlmBackend.EDGE -> EdgeLlmClient()
        LlmBackend.CLAUDE_API -> ClaudeClient(apiKey = apiKey, model = model)
        LlmBackend.OPENAI_COMPAT -> OpenAiClient(
            baseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" },
            apiKey = apiKey,
            model = model,
            extraHeaders = parseHeaders(extraHeadersJson),
        )
    }

    /** Render this config as a JSON blob the user can copy out and share/paste back. */
    fun toJson(): String {
        val o = JSONObject()
        o.put("backend", backendKey(backend))
        if (apiKey.isNotBlank()) o.put("apiKey", apiKey)
        if (baseUrl.isNotBlank()) o.put("baseUrl", baseUrl)
        o.put("model", model)
        val headers = runCatching { JSONObject(extraHeadersJson) }.getOrNull()
        if (headers != null && headers.length() > 0) o.put("headers", headers)
        o.put("language", language)
        o.put("defaultRuleset", defaultRuleset)
        // Only emit Coach flags when non-default — keeps the JSON tidy.
        val defaults = Settings()
        if (useLlmVision != defaults.useLlmVision) o.put("useLlmVision", useLlmVision)
        if (coachAlwaysOn != defaults.coachAlwaysOn) o.put("coachAlwaysOn", coachAlwaysOn)
        if (coachIntervalSec != defaults.coachIntervalSec) o.put("coachIntervalSec", coachIntervalSec)
        if (coachAudioAuto != defaults.coachAudioAuto) o.put("coachAudioAuto", coachAudioAuto)
        if (coachAutoGuide != defaults.coachAutoGuide) o.put("coachAutoGuide", coachAutoGuide)
        if (roboflowApiKey.isNotBlank()) o.put("roboflowApiKey", roboflowApiKey)
        if (roboflowModelId != defaults.roboflowModelId) o.put("roboflowModelId", roboflowModelId)
        if (orientationLock != defaults.orientationLock) o.put("orientationLock", orientationLock)
        return o.toString(2)
    }

    companion object {
        /** Models offered in the Claude picker (default first). */
        val MODELS = listOf("claude-opus-4-8", "claude-sonnet-4-6", "claude-haiku-4-5")
        val LANGUAGES = listOf("zh-CN", "ja-JP", "en-US")

        /** Always-on detection interval choices, in seconds (1s … 10min). */
        val INTERVAL_OPTIONS = listOf(1, 3, 10, 30, 60, 180, 600)

        /** Human label for an interval in seconds. */
        fun intervalLabel(sec: Int): String = when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m"
            else -> "${sec / 3600}h"
        }

        /**
         * Parse a config JSON blob. Accepted keys (camelCase or snake_case):
         *  - backend: "off" | "claude" | "openai" | "edge"
         *  - apiKey / api_key
         *  - baseUrl / base_url           (OpenAI-compat only)
         *  - model
         *  - headers: { "Header-Name": "value", ... }
         *  - language
         *  - defaultRuleset / ruleset
         *
         * Missing keys keep the current value (caller can pass [base] for that);
         * by default they fall back to the data-class defaults.
         */
        fun fromJson(src: String, base: Settings = Settings()): Result<Settings> = runCatching {
            val o = JSONObject(src)
            val backend = o.opt("backend")?.toString()
                ?.let { parseBackend(it) } ?: base.backend
            val headersJson = o.optJSONObject("headers")?.toString() ?: base.extraHeadersJson
            Settings(
                backend = backend,
                apiKey = o.optStringOr("apiKey", o.optStringOr("api_key", base.apiKey)),
                baseUrl = o.optStringOr("baseUrl", o.optStringOr("base_url", base.baseUrl)),
                model = o.optStringOr("model", base.model),
                extraHeadersJson = headersJson,
                language = o.optStringOr("language", base.language),
                defaultRuleset = o.optStringOr(
                    "defaultRuleset",
                    o.optStringOr("ruleset", base.defaultRuleset),
                ),
                useLlmVision = o.optBoolOr("useLlmVision", base.useLlmVision),
                coachAlwaysOn = o.optBoolOr("coachAlwaysOn", base.coachAlwaysOn),
                coachIntervalSec = if (o.has("coachIntervalSec") && !o.isNull("coachIntervalSec"))
                    o.optInt("coachIntervalSec", base.coachIntervalSec) else base.coachIntervalSec,
                coachAudioAuto = o.optBoolOr("coachAudioAuto", base.coachAudioAuto),
                coachAutoGuide = o.optBoolOr("coachAutoGuide", base.coachAutoGuide),
                roboflowApiKey = o.optStringOr("roboflowApiKey", base.roboflowApiKey),
                roboflowModelId = o.optStringOr("roboflowModelId", base.roboflowModelId),
                orientationLock = o.optStringOr("orientationLock", base.orientationLock),
            )
        }

        private fun backendKey(b: LlmBackend) = when (b) {
            LlmBackend.OFF -> "off"
            LlmBackend.CLAUDE_API -> "claude"
            LlmBackend.OPENAI_COMPAT -> "openai"
            LlmBackend.EDGE -> "edge"
        }

        private fun parseBackend(s: String): LlmBackend = when (s.trim().lowercase()) {
            "off", "none", "" -> LlmBackend.OFF
            "claude", "anthropic", "claude_api", "claude-api" -> LlmBackend.CLAUDE_API
            "openai", "openai_compat", "openai-compat", "openai-compatible" -> LlmBackend.OPENAI_COMPAT
            "edge", "on-device", "local" -> LlmBackend.EDGE
            else -> error("unknown backend: $s")
        }

        private fun JSONObject.optStringOr(key: String, fallback: String): String =
            if (has(key) && !isNull(key)) optString(key, fallback) else fallback

        private fun JSONObject.optBoolOr(key: String, fallback: Boolean): Boolean =
            if (has(key) && !isNull(key)) optBoolean(key, fallback) else fallback

        internal fun parseHeaders(json: String): Map<String, String> {
            if (json.isBlank()) return emptyMap()
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
            val out = mutableMapOf<String, String>()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = o.optString(k)
            }
            return out
        }
    }
}

private val Context.dataStore by preferencesDataStore(name = "mahjong_settings")

/** Thin DataStore wrapper exposing [Settings] as a Flow plus suspend setters. */
class SettingsStore(private val context: Context) {
    private object Keys {
        val backend = stringPreferencesKey("llm_backend")
        val apiKey = stringPreferencesKey("api_key")
        val baseUrl = stringPreferencesKey("base_url")
        val model = stringPreferencesKey("model")
        val headers = stringPreferencesKey("extra_headers")
        val language = stringPreferencesKey("language")
        val ruleset = stringPreferencesKey("ruleset")
        val useLlmVision = booleanPreferencesKey("use_llm_vision")
        val coachAlwaysOn = booleanPreferencesKey("coach_always_on")
        val coachIntervalSec = intPreferencesKey("coach_interval_sec")
        val coachAudioAuto = booleanPreferencesKey("coach_audio_auto")
        val coachAutoGuide = booleanPreferencesKey("coach_auto_guide")
        val roboflowApiKey = stringPreferencesKey("roboflow_api_key")
        val roboflowModelId = stringPreferencesKey("roboflow_model_id")
        val orientationLock = stringPreferencesKey("orientation_lock")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p -> read(p) }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val next = transform(read(p))
            p[Keys.backend] = next.backend.name
            p[Keys.apiKey] = next.apiKey
            p[Keys.baseUrl] = next.baseUrl
            p[Keys.model] = next.model
            p[Keys.headers] = next.extraHeadersJson
            p[Keys.language] = next.language
            p[Keys.ruleset] = next.defaultRuleset
            p[Keys.useLlmVision] = next.useLlmVision
            p[Keys.coachAlwaysOn] = next.coachAlwaysOn
            p[Keys.coachIntervalSec] = next.coachIntervalSec
            p[Keys.coachAudioAuto] = next.coachAudioAuto
            p[Keys.coachAutoGuide] = next.coachAutoGuide
            p[Keys.roboflowApiKey] = next.roboflowApiKey
            p[Keys.roboflowModelId] = next.roboflowModelId
            p[Keys.orientationLock] = next.orientationLock
        }
    }

    private fun read(p: androidx.datastore.preferences.core.Preferences): Settings = Settings(
        backend = p[Keys.backend]?.let { runCatching { LlmBackend.valueOf(it) }.getOrNull() }
            ?: LlmBackend.OFF,
        apiKey = p[Keys.apiKey].orEmpty(),
        baseUrl = p[Keys.baseUrl].orEmpty(),
        model = p[Keys.model] ?: "claude-opus-4-8",
        extraHeadersJson = p[Keys.headers].orEmpty(),
        language = p[Keys.language] ?: "zh-CN",
        defaultRuleset = p[Keys.ruleset] ?: "sichuan",
        useLlmVision = p[Keys.useLlmVision] ?: false,
        coachAlwaysOn = p[Keys.coachAlwaysOn] ?: false,
        coachIntervalSec = p[Keys.coachIntervalSec] ?: 30,
        coachAudioAuto = p[Keys.coachAudioAuto] ?: true,
        coachAutoGuide = p[Keys.coachAutoGuide] ?: true,
        roboflowApiKey = p[Keys.roboflowApiKey].orEmpty(),
        roboflowModelId = p[Keys.roboflowModelId] ?: "mahjong-baq4s/83",
        orientationLock = p[Keys.orientationLock] ?: "auto",
    )
}
