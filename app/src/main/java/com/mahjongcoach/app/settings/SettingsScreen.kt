package com.mahjongcoach.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mahjongcoach.app.ui.Spacing
import com.mahjongcoach.app.ui.editableScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.mahjongcoach.app.data.CorrectionLog
import com.mahjongcoach.app.data.LlmBackend
import com.mahjongcoach.app.data.SecretsLoader
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
        Modifier.fillMaxSize().editableScreen().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.section),
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
                        // Equal weight so all segments are the same width
                        // regardless of label length (fixes the ragged sizing).
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            b.label, fontSize = 11.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
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

        if (s.backend == LlmBackend.OPENAI_COMPAT) {
            Section("OpenAI-compatible endpoint") {
                OutlinedTextField(
                    value = s.baseUrl,
                    onValueChange = { v -> update { it.copy(baseUrl = v) } },
                    label = { Text("Base URL (e.g. https://api.openai.com/v1)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.apiKey, onValueChange = { v -> update { it.copy(apiKey = v) } },
                    label = { Text("API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.model, onValueChange = { v -> update { it.copy(model = v) } },
                    label = { Text("Model (e.g. gpt-4o, deepseek-chat, qwen2.5)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.extraHeadersJson,
                    onValueChange = { v -> update { it.copy(extraHeadersJson = v) } },
                    label = { Text("Extra headers JSON (optional)") },
                    placeholder = { Text("""{"HTTP-Referer":"…","X-Title":"Mahjong Coach"}""") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                Text(
                    "Works with any OpenAI-compatible /chat/completions endpoint (OpenRouter, " +
                        "Together, vLLM, llama.cpp server, etc). Pick a tool-capable model — the " +
                        "engine is called via OpenAI function tools.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Section("Config JSON (paste / copy)") {
            JsonImportExport(s) { next -> update { _ -> next } }
            SecretsReloadRow { next -> update { _ -> next } }
        }

        Section("Roboflow tile detector (hosted, optional)") {
            OutlinedTextField(
                value = s.roboflowApiKey,
                onValueChange = { v -> update { it.copy(roboflowApiKey = v) } },
                label = { Text("Roboflow API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.roboflowModelId,
                onValueChange = { v -> update { it.copy(roboflowModelId = v) } },
                label = { Text("Model id (e.g. mahjong-baq4s/83)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Highest-priority recognizer when a key is set: each frame is POSTed to " +
                    "serverless.roboflow.com and parsed into tile boxes. Throttled to ~3s " +
                    "between calls. Free tier ≈ 3000 inferences/month; pay-as-you-go ≈ " +
                    "\$0.001/call.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
            )
        }

        Section("Coach (live mode)") {
            SwitchRow(
                title = "Always-on detection (3s)",
                subtitle = "Default OFF: the recognizer only fires when you tap the shutter " +
                    "button (📸). Turn ON for continuous detection every ~3 seconds — uses more " +
                    "battery and (for hosted backends) API quota.",
                checked = s.coachAlwaysOn,
                onChange = { v -> update { it.copy(coachAlwaysOn = v) } },
            )
            SwitchRow(
                title = "Auto-engage mic",
                subtitle = "Listen to the table on Coach entry (after permission). The mic badge " +
                    "still toggles in the overlay.",
                checked = s.coachAudioAuto,
                onChange = { v -> update { it.copy(coachAudioAuto = v) } },
            )
            SwitchRow(
                title = "Use LLM vision",
                subtitle = "Route hand detection through the configured assistant backend. Needs a " +
                    "vision-capable model. Throttled to ~3s per call to protect API spend.",
                checked = s.useLlmVision,
                onChange = { v -> update { it.copy(useLlmVision = v) } },
            )
        }

        Section("Screen orientation") {
            Picker(
                options = listOf("auto", "portrait", "landscape"),
                selected = s.orientationLock,
            ) { v -> update { it.copy(orientationLock = v) } }
            Text(
                "auto = follow the phone. Lock to portrait or landscape if you prefer a fixed view.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
            )
        }

        Section("Language (voice + coaching)") {
            Picker(Settings.LANGUAGES, s.language) { v -> update { it.copy(language = v) } }
        }

        Section("Default ruleset") {
            Picker(listOf("sichuan", "chinese", "japan"), s.defaultRuleset) { v ->
                update { it.copy(defaultRuleset = v) }
            }
        }

        Section("Recognizer corrections") {
            CorrectionLogRow()
        }
    }
}

/**
 * Surfaces the correction-log feedback loop in Settings. Shows how many
 * sheet-edit corrections we've captured + total disk use, with a Clear button
 * that wipes the log directory.
 */
@Composable
private fun CorrectionLogRow() {
    val ctx = LocalContext.current
    val log = remember { CorrectionLog(ctx.applicationContext) }
    // refreshKey forces a re-read after a successful Clear.
    var refreshKey by remember { mutableStateOf(0) }
    val count = remember(refreshKey) { log.count() }
    val bytes = remember(refreshKey) { log.sizeBytes() }
    Text(
        "$count correction(s) logged — ${"%.1f".format(bytes / 1024f)} KB on disk.",
        fontSize = 12.sp,
    )
    Text(
        "Each open of the edit sheet that changes the hand counts gets saved " +
            "with the source frame (if any). Future retraining will fold these " +
            "back into the detector.",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
    )
    if (count > 0) {
        OutlinedButton(onClick = { if (log.clear()) refreshKey++ }) {
            Text("Clear log", fontSize = 12.sp)
        }
    }
}

@Composable
private fun JsonImportExport(current: Settings, onApply: (Settings) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var draft by remember { mutableStateOf(current.toJson()) }
    var touched by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError

    // Sync draft from settings until the user starts editing.
    LaunchedEffect(current) { if (!touched) draft = current.toJson() }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it; touched = true; status = null },
        label = { Text("Config JSON") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 6,
        textStyle = MaterialTheme.typography.bodySmall,
    )
    status?.let { (msg, isErr) ->
        Text(
            msg, fontSize = 11.sp,
            color = if (isErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            Settings.fromJson(draft, base = current)
                .onSuccess { next ->
                    onApply(next)
                    touched = false
                    status = "Applied." to false
                }
                .onFailure { e -> status = (e.message ?: "Invalid JSON") to true }
        }) { Text("Apply") }
        OutlinedButton(onClick = {
            draft = current.toJson(); touched = false; status = null
        }) { Text("Reset") }
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(draft))
            status = "Copied to clipboard." to false
        }) { Text("Copy") }
    }
    Text(
        "Paste a full config to switch backend, endpoint, key, model, and headers in one shot. " +
            "Keys: backend (off|claude|openai|edge), apiKey, baseUrl, model, headers, language, " +
            "defaultRuleset.",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * Debug-build affordance: load `assets/secrets.json` (packaged via
 * `app/src/debug/assets/secrets.json`) and apply it on top of current settings.
 * Hidden when the asset isn't present, e.g. in release builds or when the dev
 * hasn't filled in their local copy.
 */
@Composable
private fun SecretsReloadRow(onApply: (Settings) -> Unit) {
    val ctx = LocalContext.current
    val present = remember { SecretsLoader.isPresent(ctx.applicationContext) }
    if (!present) return
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val loaded = SecretsLoader.load(ctx.applicationContext)
            if (loaded != null) {
                onApply(loaded)
                status = "Loaded secrets.json." to false
            } else {
                status = "secrets.json present but unparseable — check logcat." to true
            }
        }) { Text("Reload secrets.json") }
    }
    status?.let { (msg, isErr) ->
        Text(
            msg, fontSize = 11.sp,
            color = if (isErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Picker(options: List<String>, selected: String, onPick: (String) -> Unit) {
    // FlowRow wraps whole chips to the next line as needed, so a long option
    // (e.g. "claude-haiku-4-5") never gets squeezed into a chunked column and
    // wrapped mid-word. The label itself is single-line.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { opt ->
            FilterChip(
                selected = selected == opt,
                onClick = { onPick(opt) },
                label = { Text(opt, fontSize = 12.sp, maxLines = 1, softWrap = false) },
            )
        }
    }
}
