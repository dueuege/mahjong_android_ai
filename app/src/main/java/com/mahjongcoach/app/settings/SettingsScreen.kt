package com.mahjongcoach.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mahjongcoach.app.data.LlmBackend
import com.mahjongcoach.app.data.Settings
import com.mahjongcoach.app.data.SettingsStore
import kotlinx.coroutines.launch

/** Customizations: which LLM backend, API key, model, language, default ruleset. */
@Composable
fun SettingsScreen(store: SettingsStore) {
    val scope = rememberCoroutineScope()
    val s by store.settings.collectAsState(initial = Settings())
    fun update(f: (Settings) -> Settings) = scope.launch { store.update(f) }

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置 Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Section("AI assistant backend") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LlmBackend.entries.forEachIndexed { i, b ->
                    SegmentedButton(
                        selected = s.backend == b,
                        onClick = { update { it.copy(backend = b) } },
                        shape = SegmentedButtonDefaults.itemShape(i, LlmBackend.entries.size),
                        enabled = b != LlmBackend.EDGE, // not wired yet
                    ) { Text(b.label, fontSize = 12.sp) }
                }
            }
            Text(
                "The coach works fully without an assistant. The assistant adds chat + voice " +
                    "and always calls the engine for the actual numbers.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
            )
        }

        if (s.backend == LlmBackend.CLAUDE_API) {
            Section("Claude API") {
                OutlinedTextField(
                    value = s.apiKey, onValueChange = { v -> update { it.copy(apiKey = v) } },
                    label = { Text("API key (sk-ant-…)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "⚠️ Stored on-device for your personal use. Don't ship an APK with a baked-in " +
                        "key — proxy through a backend instead.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                )
                Text("Model", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Picker(Settings.MODELS, s.model) { v -> update { it.copy(model = v) } }
            }
        }

        Section("Language (voice + coaching)") {
            Picker(Settings.LANGUAGES, s.language) { v -> update { it.copy(language = v) } }
        }

        Section("Default ruleset") {
            Picker(listOf("sichuan", "chinese", "japan"), s.defaultRuleset) { v ->
                update { it.copy(defaultRuleset = v) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Picker(options: List<String>, selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { opt ->
                    FilterChip(selected = selected == opt, onClick = { onPick(opt) }, label = { Text(opt, fontSize = 12.sp) })
                }
            }
        }
    }
}
