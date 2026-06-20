package com.mahjongcoach.app.coach

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mahjongcoach.app.GameState
import com.mahjongcoach.engine.MeldType
import com.mahjongcoach.engine.Suit
import com.mahjongcoach.engine.Tiles
import kotlinx.coroutines.launch

/** What a tap on the tile keyboard does. */
enum class InputMode(val label: String) {
    HAND("手牌"), SEEN("看到"), PON("碰"), KAN("杠")
}

/**
 * Bottom sheet that lifts the original Coach's manual-input affordances on top
 * of the live preview. Hand chips + mode selector + tile keyboard + void chips +
 * a "go to" footer for jumping to the other tabs without ever showing the tab bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditHandSheet(
    state: GameState,
    visible: Boolean,
    onDismiss: () -> Unit,
    onGoTo: (Int) -> Unit,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(InputMode.HAND) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HandChipsRow(state)
            VoidSuitRow(state)
            ModeSelector(mode) { mode = it }
            TileKeyboard { tile -> onTile(state, mode, tile) }
            HelperText(mode)
            MeldsAndSeen(state)
            HorizontalDivider()
            NavFooter(onGoTo = { idx ->
                scope.launch { sheetState.hide() }
                onGoTo(idx)
            })
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun onTile(state: GameState, mode: InputMode, tile: Int) = when (mode) {
    InputMode.HAND -> state.addTile(tile)
    InputMode.SEEN -> state.observeSeen(tile)
    InputMode.PON -> state.callPon(tile)
    InputMode.KAN -> state.callKan(tile, concealed = state.hand[tile] >= 4)
}

@Composable
private fun HandChipsRow(state: GameState) {
    val tiles = (0 until Tiles.TILE_KINDS).flatMap { t -> List(state.hand[t]) { t } }
    Text("手牌 (${tiles.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    if (tiles.isEmpty()) {
        Text("— empty — pick 手牌 mode and tap tiles —",
            color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
        return
    }
    Wrap(tiles) { tile ->
        AssistChip(onClick = { state.removeTile(tile) }, label = { Text(Tiles.cnName(tile)) })
    }
}

@Composable
private fun VoidSuitRow(state: GameState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("定缺:", fontSize = 12.sp)
        Suit.entries.forEach { suit ->
            FilterChip(
                selected = state.voidSuit == suit,
                onClick = { state.voidSuit = if (state.voidSuit == suit) null else suit },
                label = { Text(suit.cn) },
            )
        }
    }
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
}

@Composable
private fun HelperText(mode: InputMode) {
    Text(
        when (mode) {
            InputMode.HAND -> "Tap tiles to add to your hand."
            InputMode.SEEN -> "Tap tiles you've seen discarded / melded."
            InputMode.PON -> "Tap the tile you're calling 碰 on (needs 2 in hand)."
            InputMode.KAN -> "Tap the tile you're calling 杠 on."
        },
        fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
    )
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
private fun MeldsAndSeen(state: GameState) {
    if (state.melds.isNotEmpty()) {
        Text("Melds:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Wrap(state.melds.indices.toList()) { i ->
            val m = state.melds[i]
            val tag = if (m.type == MeldType.KAN) "杠" else "碰"
            InputChip(
                selected = true,
                onClick = { state.removeMeld(i) },
                label = { Text("$tag ${Tiles.cnName(m.tile)}") },
            )
        }
    }
    val seen = (0 until Tiles.TILE_KINDS).filter { state.seen[it] > 0 }
    if (seen.isNotEmpty()) {
        Text("Seen on table:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Wrap(seen) { t ->
            AssistChip(
                onClick = { state.unsee(t) },
                label = { Text("${Tiles.cnName(t)}×${state.seen[t]}") },
            )
        }
    }
}

@Composable
private fun NavFooter(onGoTo: (Int) -> Unit) {
    // Tab indices match MainActivity.App() order: 0 Coach · 1 Score · 2 Assistant · 3 Settings
    Text("Go to:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(onClick = { onGoTo(1) }, label = { Text("算点 Score") })
        AssistChip(onClick = { onGoTo(2) }, label = { Text("助手 Assistant") })
        AssistChip(onClick = { onGoTo(3) }, label = { Text("设置 Settings") })
    }
}

@Composable
private fun <T> Wrap(items: List<T>, item: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { item(it) } }
        }
    }
}
