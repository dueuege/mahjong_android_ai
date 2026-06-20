package com.mahjongcoach.app.coach

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Pin the host activity's orientation while a screen is foreground; restore the
 * previous value on dispose. We use this so Coach is locked to landscape and
 * Score / Assistant / Settings stay portrait, which makes "leaving Coach" feel
 * like a deliberate rotation back to the calmer modes.
 */
@Composable
fun LockOrientation(orientation: Int) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(orientation) {
        val previous = activity.requestedOrientation
        activity.requestedOrientation = orientation
        onDispose { activity.requestedOrientation = previous }
    }
}

object Orientations {
    const val PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    const val UNSPECIFIED = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /**
     * Free rotation driven by the physical sensor, ignoring the system
     * auto-rotate lock. Used by Score / Assistant so they keep landscape when
     * arriving from the landscape Coach, but the user can still turn the phone
     * to portrait (e.g. to type a long chat message).
     */
    const val FREE = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
}
