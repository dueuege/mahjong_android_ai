package com.mahjongcoach.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared layout knobs. Keep one source of truth so every screen feels
 * consistent and the IME / status / nav bar handling lives in one place.
 *
 *  - [Spacing.screenEdge] is the standard outer padding for scrollable lists.
 *  - [Spacing.section] is the vertical gap between distinct sections.
 *  - [Modifier.screenPadding] adds system-bar insets + screen-edge padding so
 *    scrollable screens stop drawing under the status bar / nav bar / IME.
 *  - [Modifier.editableScreen] additionally pads for the IME so the input row
 *    isn't covered when the keyboard comes up.
 */
object Spacing {
    val screenEdge = 12.dp
    val section = 14.dp
    val tight = 6.dp
}

@Composable
fun Modifier.screenPadding(): Modifier =
    this
        .windowInsetsPadding(WindowInsets.systemBars)
        .padding(Spacing.screenEdge)

@Composable
fun Modifier.editableScreen(): Modifier =
    this
        .imePadding()
        .windowInsetsPadding(WindowInsets.systemBars)
        .padding(Spacing.screenEdge)
