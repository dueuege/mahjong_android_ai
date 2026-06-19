package com.mahjongcoach.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mahjongcoach.app.llm.ClaudeClient
import com.mahjongcoach.app.llm.DisabledLlm
import com.mahjongcoach.app.llm.EdgeLlmClient
import com.mahjongcoach.app.llm.LlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class LlmBackend(val label: String) {
    OFF("Off"),
    CLAUDE_API("Claude API"),
    EDGE("On-device (soon)"),
}

/** Persisted user preferences. */
data class Settings(
    val backend: LlmBackend = LlmBackend.OFF,
    val apiKey: String = "",
    val model: String = "claude-opus-4-8",
    val language: String = "zh-CN",      // ASR / coaching language
    val defaultRuleset: String = "sichuan",
) {
    /** Build the configured assistant backend. The coach itself needs none of this. */
    fun buildClient(): LlmClient = when (backend) {
        LlmBackend.OFF -> DisabledLlm
        LlmBackend.EDGE -> EdgeLlmClient()
        LlmBackend.CLAUDE_API -> ClaudeClient(apiKey = apiKey, model = model)
    }

    companion object {
        /** Models offered in the picker (default first). */
        val MODELS = listOf("claude-opus-4-8", "claude-sonnet-4-6", "claude-haiku-4-5")
        val LANGUAGES = listOf("zh-CN", "ja-JP", "en-US")
    }
}

private val Context.dataStore by preferencesDataStore(name = "mahjong_settings")

/** Thin DataStore wrapper exposing [Settings] as a Flow plus suspend setters. */
class SettingsStore(private val context: Context) {
    private object Keys {
        val backend = stringPreferencesKey("llm_backend")
        val apiKey = stringPreferencesKey("api_key")
        val model = stringPreferencesKey("model")
        val language = stringPreferencesKey("language")
        val ruleset = stringPreferencesKey("ruleset")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            backend = p[Keys.backend]?.let { runCatching { LlmBackend.valueOf(it) }.getOrNull() } ?: LlmBackend.OFF,
            apiKey = p[Keys.apiKey].orEmpty(),
            model = p[Keys.model] ?: "claude-opus-4-8",
            language = p[Keys.language] ?: "zh-CN",
            defaultRuleset = p[Keys.ruleset] ?: "sichuan",
        )
    }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val current = Settings(
                backend = p[Keys.backend]?.let { runCatching { LlmBackend.valueOf(it) }.getOrNull() } ?: LlmBackend.OFF,
                apiKey = p[Keys.apiKey].orEmpty(),
                model = p[Keys.model] ?: "claude-opus-4-8",
                language = p[Keys.language] ?: "zh-CN",
                defaultRuleset = p[Keys.ruleset] ?: "sichuan",
            )
            val next = transform(current)
            p[Keys.backend] = next.backend.name
            p[Keys.apiKey] = next.apiKey
            p[Keys.model] = next.model
            p[Keys.language] = next.language
            p[Keys.ruleset] = next.defaultRuleset
        }
    }
}
