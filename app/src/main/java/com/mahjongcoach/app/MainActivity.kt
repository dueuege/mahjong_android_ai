package com.mahjongcoach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // Chinese primary + English caption, stacked, so four tabs fit portrait
    // width without the last label ("设置 Settings") truncating.
    val tabs = listOf(
        "教练" to "Coach", "算点" to "Score", "助手" to "Assistant", "设置" to "Settings",
    )

    // Coach owns the full screen (landscape, hidden chrome). Everything else uses
    // the tab bar in portrait — see LockOrientation calls below.
    if (tab == TAB_COACH) {
        CoachScreen(state = gameState, store = store, onGoToTab = { tab = it })
    } else {
        LockOrientation(Orientations.PORTRAIT)
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, (cn, en) ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(cn, fontSize = 14.sp, maxLines = 1)
                                Text(
                                    en, fontSize = 10.sp, maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }
            when (tab) {
                TAB_SCORE -> ScoreScreen(store)
                TAB_ASSISTANT -> AssistantScreen(store, gameState)
                TAB_SETTINGS -> SettingsScreen(store)
            }
        }
    }
}
