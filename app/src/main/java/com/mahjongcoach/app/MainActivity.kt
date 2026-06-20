package com.mahjongcoach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mahjongcoach.app.assistant.AssistantScreen
import com.mahjongcoach.app.coach.CoachScreen
import com.mahjongcoach.app.coach.LockOrientation
import com.mahjongcoach.app.coach.Orientations
import com.mahjongcoach.app.data.LlmBackend
import com.mahjongcoach.app.data.SecretsLoader
import com.mahjongcoach.app.data.SettingsStore
import com.mahjongcoach.app.score.ScoreScreen
import com.mahjongcoach.app.settings.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

private const val TAB_COACH = 0
private const val TAB_SCORE = 1
private const val TAB_ASSISTANT = 2
private const val TAB_SETTINGS = 3

@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val gameState = remember { GameState() }

    // Debug-only: if assets/secrets.json is packaged AND the user hasn't picked
    // a backend yet, prefill Settings from it. Manual changes from the UI win
    // afterwards (we only fire when backend == OFF).
    LaunchedEffect(Unit) {
        scope.launch {
            val current = store.settings.first()
            if (current.backend == LlmBackend.OFF) {
                SecretsLoader.load(context.applicationContext, base = current)
                    ?.let { next -> store.update { next } }
            }
        }
    }

    var tab by remember { mutableStateOf(TAB_COACH) }
    val tabs = listOf("教练 Coach", "算点 Score", "助手 Assistant", "设置 Settings")

    // Coach owns the full screen (landscape, hidden chrome). Everything else uses
    // the tab bar in portrait — see LockOrientation calls below.
    if (tab == TAB_COACH) {
        CoachScreen(state = gameState, store = store, onGoToTab = { tab = it })
    } else {
        LockOrientation(Orientations.PORTRAIT)
        Column(Modifier.fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                TAB_SCORE -> ScoreScreen(store)
                TAB_ASSISTANT -> AssistantScreen(store)
                TAB_SETTINGS -> SettingsScreen(store)
            }
        }
    }
}
