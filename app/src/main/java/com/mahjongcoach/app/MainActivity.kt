package com.mahjongcoach.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.mahjongcoach.app.assistant.AssistantScreen
import com.mahjongcoach.app.audio.AudioBoardController
import com.mahjongcoach.app.audio.BoardAudioListener
import com.mahjongcoach.app.data.SettingsStore
import com.mahjongcoach.app.score.ScoreScreen
import com.mahjongcoach.app.settings.SettingsScreen
import com.mahjongcoach.engine.MeldType
import com.mahjongcoach.engine.Shanten
import com.mahjongcoach.engine.Suit
import com.mahjongcoach.engine.Tiles

/** What a tap on the tile keyboard does. */
private enum class InputMode(val label: String) {
    HAND("手牌"), SEEN("看到"), PON("碰"), KAN("杠")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("教练 Coach", "算点 Score", "助手 Assistant", "设置 Settings")
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> CoachScreen()
            1 -> ScoreScreen()
            2 -> AssistantScreen(store)
            else -> SettingsScreen(store)
        }
    }
}

@Composable
fun CoachScreen(state: GameState = remember { GameState() }) {
    var mode by remember { mutableStateOf(InputMode.HAND) }

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("四川麻将 Coach", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        VoidSuitSelector(state.voidSuit) { state.voidSuit = it }

        Text("Your hand (${state.totalTiles} tiles)", fontWeight = FontWeight.SemiBold)
        HandRow(state)
        MeldsRow(state)

        HorizontalDivider()
        ModeSelector(mode) { mode = it }
        TileKeyboard { tile -> onTile(state, mode, tile) }
        MicButton(state)
        SeenRow(state)

        HorizontalDivider()
        AdvicePanel(state)
    }
}

private fun onTile(state: GameState, mode: InputMode, tile: Int) = when (mode) {
    InputMode.HAND -> state.addTile(tile)
    InputMode.SEEN -> state.observeSeen(tile)
    InputMode.PON -> state.callPon(tile)
    InputMode.KAN -> state.callKan(tile, concealed = state.hand[tile] >= 4)
}

@Composable
private fun ModeSelector(mode: InputMode, onPick: (InputMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        InputMode.entries.forEachIndexed { i, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onPick(m) },
                shape = SegmentedButtonDefaults.itemShape(i, InputMode.entries.size),
            ) { Text(m.label) }
        }
    }
    Text(
        when (mode) {
            InputMode.HAND -> "Tap tiles to add to your hand."
            InputMode.SEEN -> "Tap tiles you've seen discarded / melded on the table."
            InputMode.PON -> "Tap the tile you're calling 碰 on (needs 2 in hand)."
            InputMode.KAN -> "Tap the tile you're calling 杠 on."
        },
        fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * Toggles the live mic. Friends' spoken calls -> SpeechParser -> board state.
 * Listens to the table audio only; no other-hand inspection.
 */
@Composable
private fun MicButton(state: GameState) {
    val context = LocalContext.current
    val controller = remember { AudioBoardController(state) }
    var listener by remember { mutableStateOf<BoardAudioListener?>(null) }
    val listening = listener != null

    fun start() {
        val l = BoardAudioListener(context, onEvents = { controller.apply(it) })
        l.start(); listener = l
    }
    fun stop() { listener?.stop(); listener?.destroy(); listener = null }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) start() }

    DisposableEffect(Unit) { onDispose { listener?.destroy() } }

    FilledTonalButton(onClick = {
        if (listening) stop()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) start() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }, modifier = Modifier.fillMaxWidth()) {
        Text(if (listening) "🎙 Listening to table… (tap to stop)" else "🎙 Listen to table")
    }
}

@Composable
private fun VoidSuitSelector(current: Suit?, onPick: (Suit?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("定缺:")
        Suit.entries.forEach { suit ->
            FilterChip(
                selected = current == suit,
                onClick = { onPick(if (current == suit) null else suit) },
                label = { Text(suit.cn) },
            )
        }
    }
}

@Composable
private fun HandRow(state: GameState) {
    val tiles = (0 until Tiles.TILE_KINDS).flatMap { t -> List(state.hand[t]) { t } }
    if (tiles.isEmpty()) {
        Text("— empty — pick 手牌 mode and tap tiles —", color = MaterialTheme.colorScheme.outline)
        return
    }
    Wrap(tiles) { tile -> AssistChip(onClick = { state.removeTile(tile) }, label = { Text(Tiles.cnName(tile)) }) }
}

@Composable
private fun MeldsRow(state: GameState) {
    if (state.melds.isEmpty()) return
    Wrap(state.melds.indices.toList()) { i ->
        val m = state.melds[i]
        val tag = if (m.type == MeldType.KAN) "杠" else "碰"
        InputChip(selected = true, onClick = { state.removeMeld(i) },
            label = { Text("$tag ${Tiles.cnName(m.tile)}") })
    }
}

@Composable
private fun SeenRow(state: GameState) {
    val seen = (0 until Tiles.TILE_KINDS).filter { state.seen[it] > 0 }
    if (seen.isEmpty()) return
    Text("Seen on table:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Wrap(seen) { t ->
        AssistChip(onClick = { state.unsee(t) }, label = { Text("${Tiles.cnName(t)}×${state.seen[t]}") })
    }
}

@Composable
private fun TileKeyboard(onTap: (Int) -> Unit) {
    Suit.entries.forEach { suit ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            (1..9).forEach { rank ->
                OutlinedButton(
                    onClick = { onTap(Tiles.index(suit, rank)) },
                    contentPadding = PaddingValues(2.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("$rank${suit.cn}", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun AdvicePanel(state: GameState) {
    val advice = state.advice
    val header = when {
        advice.isWin -> "🀄 和牌! Winning hand"
        advice.voidTilesHeld > 0 -> "定缺: dump ${advice.voidTilesHeld} void tile(s) first"
        advice.isTenpai -> "听牌 — one tile from winning"
        state.totalTiles % 3 == 2 -> "向听 (shanten): ${advice.shanten}"
        else -> "Add a drawn tile for discard advice (now ${state.totalTiles})"
    }
    Text(header, fontSize = 18.sp, fontWeight = FontWeight.Bold)

    if (state.totalTiles % 3 == 2) {
        advice.options.take(5).forEachIndexed { i, opt ->
            val label = when (opt.resultingShanten) {
                Shanten.WIN -> "WIN"; 0 -> "tenpai"; else -> "${opt.resultingShanten}-shanten"
            }
            val accepts = opt.acceptance.sortedByDescending { it.remaining }
                .joinToString(" ") { "${it.name}×${it.remaining}" }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        "${i + 1}. 打 ${opt.discardName} → $label  (${opt.ukeireCount} tiles)" +
                            if (opt.forcedByVoid) "  [定缺]" else "",
                        fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (accepts.isNotEmpty()) Text("进张: $accepts", fontSize = 12.sp)
                }
            }
        }
    }
}

/** Minimal wrap layout to avoid pulling in extra deps for the skeleton. */
@Composable
private fun <T> Wrap(items: List<T>, item: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { item(it) } }
        }
    }
}
