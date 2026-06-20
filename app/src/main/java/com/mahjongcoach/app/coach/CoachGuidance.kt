package com.mahjongcoach.app.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mahjongcoach.app.GameState
import com.mahjongcoach.app.assistant.toPromptBlock
import com.mahjongcoach.app.data.LlmBackend
import com.mahjongcoach.app.data.Settings
import com.mahjongcoach.app.llm.ChatTurn
import com.mahjongcoach.app.llm.Role
import kotlinx.coroutines.launch

/**
 * "AI 指导" affordance for the Coach screen. Sends the current detected hand
 * (as a [GameState.toPromptBlock] `[STATE]` block) plus a Sichuan coaching
 * prompt to the configured text LLM, and shows the reply in a dismissible
 * card. The engine still owns the exact numbers — this is the prose layer
 * that turns "打 5条, tenpai" into table-side advice on what to do next.
 *
 * Works with any text-capable backend (no vision needed — tiles already came
 * from the Roboflow detector), so the user's text-only endpoint is fine here.
 */
@Composable
fun CoachGuidanceButton(
    state: GameState,
    settings: Settings,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun ask() {
        if (busy) return
        if (settings.backend == LlmBackend.OFF) {
            error = "先在设置里启用 AI 助手 (Settings → backend)。"
            return
        }
        if (state.totalTiles == 0) {
            error = "先拍一张手牌 (tap 📸)。"
            return
        }
        busy = true; error = null; result = null
        val client = settings.buildClient()
        val prompt = buildString {
            append(state.toPromptBlock())
            append("\n\n")
            append(
                "这是四川麻将（血战到底）。根据上面的手牌，给出下一步指导：" +
                    "如果还没定缺，建议定哪一门并说明原因；" +
                    "然后说明现在该打哪张、为什么、以及大致还差几张听牌。" +
                    "用中文，简洁，先说结论。",
            )
        }
        scope.launch {
            val reply = runCatching { client.reply(listOf(ChatTurn(Role.USER, prompt))) }
                .getOrElse { "出错: ${it.message}" }
            result = reply
            busy = false
        }
    }

    // The trigger button.
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        shape = CircleShape,
        modifier = modifier.clickable { ask() },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text("🧠", fontSize = 14.sp)
            }
            Text("AI 指导", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }

    error?.let { msg ->
        // Transient inline error shown just below the button position; cleared on next ask.
        GuidanceDialog(title = "提示", body = msg, onClose = { error = null })
    }
    result?.let { text ->
        GuidanceDialog(title = "AI 指导", body = text, onClose = { result = null })
    }
}

@Composable
private fun GuidanceDialog(title: String, body: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            color = Color(0xF2202020),
            contentColor = Color.White,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                Modifier
                    .widthIn(max = 460.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Surface(
                        color = Color.White.copy(alpha = 0.15f),
                        shape = CircleShape,
                        modifier = Modifier.clickable { onClose() },
                    ) {
                        Text("✕", Modifier.padding(horizontal = 9.dp, vertical = 3.dp), fontSize = 13.sp)
                    }
                }
                Text(
                    body,
                    Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}
