package com.mahjongcoach.app.coach

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
import androidx.compose.runtime.mutableStateListOf
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
 * Round-scoped coaching memory. Holds the running LLM conversation for ONE
 * round so the model remembers the hand's evolution, your prior moves, and the
 * advice it already gave — the "memory system for the LLM". Each [ask] prepends
 * the current full game state (hand + seen pond + 定缺 + melds via
 * [GameState.toPromptBlock]) so the model always grounds on the latest board.
 *
 * Reset on 新局 ([reset]) to start a fresh round's memory.
 */
@Stable
class RoundCoach {
    val history = mutableStateListOf<ChatTurn>()
    var result by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    /** Pick the strategy guide-book by ruleset (japan → riichi, else Sichuan). */
    private fun systemCoach(settings: Settings): String {
        val guide = if (settings.defaultRuleset.startsWith("j", ignoreCase = true))
            RiichiStrategy.GUIDE else SichuanStrategy.GUIDE
        return guide + "\n\n" +
            "你是实时教练，记住本局之前的状态和你给过的建议。每次根据最新 [STATE]" +
            "（hand=我的手牌, seen=牌池已见, void=定缺, melds=已碰杠）给出下一步指导。"
    }

    suspend fun ask(state: GameState, settings: Settings, userNote: String) {
        if (busy) return
        if (settings.backend == LlmBackend.OFF) {
            error = "先在设置里启用 AI 助手 (Settings → backend)。"; return
        }
        if (state.totalTiles == 0) {
            error = "先拍一张手牌 (tap 📸)。"; return
        }
        busy = true; error = null
        val userText = "${state.toPromptBlock()}\n\n$userNote"
        history.add(ChatTurn(Role.USER, userText))
        // Prepend the coaching system instruction as the first turn each call
        // (cheap, and keeps the persona stable across the round's history).
        val convo = buildList {
            add(ChatTurn(Role.USER, systemCoach(settings)))
            add(ChatTurn(Role.ASSISTANT, "明白，我会记住本局并实时指导。"))
            addAll(history)
        }
        val reply = runCatching { settings.buildClient().reply(convo) }
            .getOrElse { "出错: ${it.message}" }
        history.add(ChatTurn(Role.ASSISTANT, reply))
        result = reply
        busy = false
    }

    fun reset() { history.clear(); result = null; error = null }
    fun dismiss() { result = null; error = null }
}

/** Trigger button for on-demand guidance; shares [coach] memory with auto-guide. */
@Composable
fun CoachGuidanceButton(
    state: GameState,
    settings: Settings,
    coach: RoundCoach,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        shape = CircleShape,
        modifier = modifier.clickable {
            scope.launch { coach.ask(state, settings, "现在该怎么打？") }
        },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (coach.busy) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text("🧠", fontSize = 14.sp)
            }
            Text("AI 指导", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }

    coach.error?.let { msg -> GuidanceDialog("提示", msg, onClose = { coach.dismiss() }) }
    coach.result?.let { text -> GuidanceDialog("AI 指导", text, onClose = { coach.dismiss() }) }
}

@Composable
private fun GuidanceDialog(title: String, body: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(color = Color(0xF2202020), contentColor = Color.White, shape = RoundedCornerShape(14.dp)) {
            Column(
                Modifier.widthIn(max = 460.dp).padding(16.dp),
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
                    Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
        }
    }
}
