package com.mahjongcoach.app.coach

import androidx.compose.foundation.background
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
import com.mahjongcoach.engine.Tiles

/** Translucent one-line HUD across the top — the "what to do next" summary. */
@Composable
fun AdviceBanner(state: GameState, modifier: Modifier = Modifier) {
    val advice = state.advice
    val total = state.totalTiles
    val text = when {
        total == 0 -> "Point camera at your hand · or tap ✎ to enter manually"
        advice.isWin -> "🀄 和牌 · winning hand"
        advice.voidTilesHeld > 0 -> "定缺: dump ${advice.voidTilesHeld} void tile(s) first"
        total % 3 != 2 -> "Add a drawn tile (${total} so far)"
        advice.isTenpai -> {
            val best = advice.options.firstOrNull()
            val accepts = best?.acceptance.orEmpty().sortedByDescending { it.remaining }
                .joinToString(" / ") { "${it.name}×${it.remaining}" }
            "打 ${best?.discardName.orEmpty()} · tenpai · accepts $accepts (${best?.ukeireCount ?: 0})"
        }
        else -> {
            val best = advice.options.firstOrNull()
            "打 ${best?.discardName.orEmpty()} · ${advice.shanten}-shanten · ${best?.ukeireCount ?: 0} tiles"
        }
    }
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
