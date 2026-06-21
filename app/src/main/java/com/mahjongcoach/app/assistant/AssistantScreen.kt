package com.mahjongcoach.app.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mahjongcoach.app.GameState
import com.mahjongcoach.app.audio.SpeechToText
import com.mahjongcoach.app.data.LlmBackend
import com.mahjongcoach.app.data.Settings
import com.mahjongcoach.app.data.SettingsStore
import com.mahjongcoach.app.llm.ChatTurn
import com.mahjongcoach.app.llm.Role
import com.mahjongcoach.app.ui.Spacing
import com.mahjongcoach.app.ui.editableScreen
import kotlinx.coroutines.launch

/**
 * Conversational coach. Type a question, or tap the mic for one utterance →
 * transcribed → sent straight to the LLM. The model answers by calling the
 * engine tools, so any numbers it gives are the engine's, not invented.
 */
@Composable
fun AssistantScreen(
    store: SettingsStore,
    gameState: GameState? = null,
    roundCoach: com.mahjongcoach.app.coach.RoundCoach? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsState(initial = Settings())

    val history = remember { mutableStateListOf<ChatTurn>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val historyScroll = rememberScrollState()

    // Auto-scroll the transcript to the bottom whenever a new turn arrives, so
    // the user always sees the freshest reply without manually scrolling.
    LaunchedEffect(history.size, busy) {
        historyScroll.animateScrollTo(historyScroll.maxValue)
    }

    val stt = remember(settings.language) { SpeechToText(context, settings.language) }
    DisposableEffect(stt) { onDispose { stt.destroy() } }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        history.add(ChatTurn(Role.USER, text))
        input = ""
        busy = true
        status = null
        val client = settings.buildClient()
        scope.launch {
            val reply = runCatching { client.reply(history.toList()) }
                .getOrElse { "Error: ${it.message}" }
            history.add(ChatTurn(Role.ASSISTANT, reply))
            busy = false
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice(stt, onStatus = { status = it }, onText = { send(it) })
        else status = "Microphone permission denied."
    }

    Column(Modifier.fillMaxSize().editableScreen(), verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
        Text("AI 助手 Assistant", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Backend: ${settings.backend.label}" +
                if (settings.backend == LlmBackend.OFF) " — enable it in the Settings tab" else "",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
        )

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(historyScroll),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Coach log: the prompts the live coach sent the AI + the guidance it
            // got back this round (shared RoundCoach memory). Read-only.
            if (roundCoach != null && roundCoach.history.isNotEmpty()) {
                Text(
                    "教练记录 Coach log (本局)",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                roundCoach.history.forEach { turn -> Bubble(turn) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            if (history.isEmpty()) {
                Text(
                    "Ask things like: \"我手里 123万 456万 789万 11筒 99筒 5条，定缺条，打哪张?\" " +
                        "or \"score 11223344556677p in sichuan\".",
                    color = MaterialTheme.colorScheme.outline, fontSize = 13.sp,
                )
            }
            history.forEach { turn -> Bubble(turn) }
            if (busy) Text("…thinking", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }

        if (gameState != null && settings.backend != LlmBackend.OFF) {
            val handCount = gameState.hand.sum()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Presets.ALL) { preset ->
                    val enabled = !busy && (!preset.requiresHand || handCount > 0)
                    AssistChip(
                        onClick = {
                            val block = gameState.toPromptBlock()
                            send("$block\n\n${preset.query}")
                        },
                        enabled = enabled,
                        label = { Text(preset.label, fontSize = 11.sp) },
                    )
                }
            }
            if (handCount == 0) {
                Text(
                    "Tip: enter a hand on the Coach tab so the presets have something to reason about.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), placeholder = { Text("Ask the coach…") },
                maxLines = 3,
            )
            FilledTonalButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) startVoice(stt, onStatus = { status = it }, onText = { send(it) })
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = !busy,
            ) { Text("🎤") }
            Button(onClick = { send(input) }, enabled = !busy && input.isNotBlank()) { Text("Send") }
        }
    }
}

private fun startVoice(stt: SpeechToText, onStatus: (String?) -> Unit, onText: (String) -> Unit) {
    onStatus("Listening…")
    stt.listenOnce(
        onResult = { onStatus(null); onText(it) },
        onError = { onStatus(it) },
        onListening = { onStatus("Listening…") },
    )
}

@Composable
private fun Bubble(turn: ChatTurn) {
    val isUser = turn.role == Role.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Text(turn.text, Modifier.padding(10.dp), fontSize = 14.sp)
        }
    }
}
