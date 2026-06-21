package com.mahjongcoach.app.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.mahjongcoach.app.GameState
import com.mahjongcoach.app.vision.DetectedBox
import com.mahjongcoach.engine.Shanten
import com.mahjongcoach.engine.Suit
import com.mahjongcoach.engine.Tiles

/**
 * Translucent guidance HUD across the top. Line 1 = game phase / 定缺 prompt
 * (tap a suit chip to declare it). Line 2 = the engine's discard advice. Kept
 * deterministic and instant; the LLM "AI 指导" button adds prose on demand.
 */
@Composable
fun AdviceBanner(state: GameState, modifier: Modifier = Modifier) {
    val advice = state.advice
    val total = state.totalTiles

    // Whether we have table knowledge: if the discard pond is empty, remaining
    // counts are an upper bound (opponents may hold them) → mark uncertain "?".
    val uncertain = state.seen.sum() == 0
    val best = advice.options.firstOrNull()

    val adviceLine = when {
        total == 0 -> "对准手牌拍照 (📸) · 或点 ✎ 手动输入"
        advice.isWin -> "🀄 和牌 · winning hand!"
        advice.voidTilesHeld > 0 -> "定缺: 先打掉 ${advice.voidTilesHeld} 张缺张"
        total % 3 != 2 -> "已识别 $total 张 · 摸牌后给出打牌建议"
        advice.isTenpai -> "打 ${best?.discardName.orEmpty()} · 听牌 (${best?.ukeireCount ?: 0} 张可胡)"
        else -> "打 ${best?.discardName.orEmpty()} · ${advice.shanten} 向听 · ${best?.ukeireCount ?: 0} 张进张"
    }

    // Detailed wait/acceptance line: each tile with how many are (likely) left.
    val waits: String? = best?.acceptance
        ?.takeIf { it.isNotEmpty() && total % 3 == 2 }
        ?.sortedByDescending { it.remaining }
        ?.joinToString("  ") { a ->
            val q = if (uncertain) "?" else ""
            "${a.name}(${a.remaining}$q)"
        }
    val waitsLabel = if (advice.isTenpai) "可胡:" else "进张:"

    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            PhaseLine(state)
            Text(adviceLine, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            waits?.let {
                Text(
                    "$waitsLabel $it",
                    fontSize = 11.sp,
                    color = if (advice.isTenpai) Color(0xFF9FE1CB) else Color(0xFFC8C4B8),
                )
            }
        }
    }
}

/** Line 1 — new-game / 定缺 prompt with tappable suit chips, or the declared void. */
@Composable
private fun PhaseLine(state: GameState) {
    val declared = state.voidSuit
    when {
        declared != null -> {
            Text(
                "定缺 ${declared.cn} ✓ · 已定缺",
                fontSize = 12.sp, color = Color(0xFFFAC775), fontWeight = FontWeight.Medium,
            )
        }
        state.isNewGame -> {
            val rec = state.voidRecommendation
            val counts = state.suitCounts   // [man, pin, sou]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("🆕 新局 · 定缺:", fontSize = 12.sp, color = Color.White)
                Suit.entries.forEachIndexed { i, suit ->
                    val isRec = suit == rec
                    Surface(
                        color = if (isRec) Color(0xFFFAC775) else Color.White.copy(alpha = 0.22f),
                        contentColor = if (isRec) Color(0xFF412402) else Color.White,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { state.voidSuit = suit },
                    ) {
                        Text(
                            "${suit.cn}${counts.getOrElse(i) { 0 }}",
                            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            fontSize = 11.sp,
                            fontWeight = if (isRec) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
                Text("(建议最少)", fontSize = 10.sp, color = Color(0xFFC8C4B8))
            }
        }
        else -> { /* mid-game, no phase line */ }
    }
}

/**
 * Bottom-center chip strip showing the engine's current view of the hand. This
 * is the "fallback" path used whenever the recognizer can only give us counts,
 * not bounding boxes — today: stub mode, LLM-vision snap mode. When a real
 * detector lands with boxes, this gets replaced by per-tile floating labels.
 */
@Composable
fun DetectedHandStrip(state: GameState, modifier: Modifier = Modifier) {
    val tiles = (0 until Tiles.TILE_KINDS).flatMap { t -> List(state.hand[t]) { t } }
    if (tiles.isEmpty()) return
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tiles.forEach { tile ->
            Surface(
                color = Color.White.copy(alpha = 0.92f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    Tiles.cnName(tile),
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 12.sp, color = Color(0xFF222222),
                )
            }
        }
    }
}

/** Small "● live" / "● mic" pills bottom-left of the preview. */
@Composable
fun LiveBadge(label: String, on: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        shape = CircleShape,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(if (on) Color(0xFF22C55E) else Color(0xFFE24B4A), CircleShape),
            )
            Text(label, fontSize = 11.sp)
        }
    }
}

/**
 * Top-right nav row — three small dark pills for Score / Assistant / Settings.
 * Makes the other tabs reachable directly from the AR HUD without forcing the
 * user to open the edit sheet first. Tapping a pill triggers the same
 * rotate-back-to-portrait + switch-tab flow as the sheet footer.
 */
@Composable
fun TopRightNav(onGoTo: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NavPill("算点", onClick = { onGoTo(1) })
        NavPill("助手", onClick = { onGoTo(2) })
        NavPill("设置", onClick = { onGoTo(3) })
    }
}

@Composable
private fun NavPill(label: String, onClick: () -> Unit) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 11.sp, fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Variant-C AR overlay. Each [DetectedBox] gets a small label chip at its
 * box-centre (normalised 0..1 → preview pixels). When the detector returns no
 * boxes (stub mode, or before the model warms up) this renders nothing; the
 * [DetectedHandStrip] still shows the counts so the user has feedback.
 */
@Composable
fun FloatingTileLabels(boxes: List<DetectedBox>, modifier: Modifier = Modifier) {
    if (boxes.isEmpty()) return
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    Box(modifier = modifier.onSizeChanged { size = it }) {
        if (size.width == 0 || size.height == 0) return@Box
        boxes.forEach { box ->
            // Defensive: a miswired classMap could surface an out-of-range
            // tileIndex; skip rather than letting Tiles.cnName throw.
            if (box.tileIndex !in 0 until Tiles.TILE_KINDS) return@forEach
            val xPx = (box.cx * size.width).toInt()
            val yPx = (box.cy * size.height).toInt()
            val offsetX = with(density) { (xPx - 18.dp.roundToPx() / 2).toDp() }
            val offsetY = with(density) { (yPx - 12.dp.roundToPx() / 2).toDp() }
            Surface(
                modifier = Modifier.padding(start = offsetX, top = offsetY),
                color = Color.White.copy(alpha = 0.92f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    Tiles.cnName(box.tileIndex),
                    Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    fontSize = 10.sp, color = Color(0xFF222222),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** Single-tap edit FAB. */
@Composable
fun EditFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = Color.White.copy(alpha = 0.94f),
        contentColor = Color(0xFF222222),
    ) {
        Text("✎", fontSize = 18.sp)
    }
}
