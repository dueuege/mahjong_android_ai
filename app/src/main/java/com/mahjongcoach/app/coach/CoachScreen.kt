package com.mahjongcoach.app.coach

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mahjongcoach.app.GameState
import android.graphics.Bitmap
import com.mahjongcoach.app.audio.AudioBoardController
import com.mahjongcoach.app.audio.BoardAudioListener
import com.mahjongcoach.app.data.CorrectionLog
import com.mahjongcoach.app.data.LlmBackend
import com.mahjongcoach.app.data.Settings
import com.mahjongcoach.app.data.SettingsStore
import com.mahjongcoach.app.vision.DetectedBox
import com.mahjongcoach.app.vision.HandRecognizer
import com.mahjongcoach.app.vision.LlmHandRecognizer
import com.mahjongcoach.app.vision.RoboflowHandRecognizer
import com.mahjongcoach.app.vision.StubHandRecognizer

/** What a camera detection represents. */
enum class CaptureMode(val cn: String) { HAND("手牌"), BOARD("牌池") }

/**
 * Camera-first Coach. Full-bleed CameraX preview, translucent AdviceBanner at
 * the top, DetectedHandStrip near the bottom, mic/live badges bottom-left, and a
 * single edit FAB bottom-right that opens the modal sheet. Tab bar is hidden by
 * the caller while this screen is foreground; the sheet's "Go to" footer is the
 * only nav route off the screen.
 *
 * Lifecycle:
 *  - Follows the app-wide orientation setting (see App()).
 *  - Binds the camera in `CameraPreview` (which auto-unbinds on dispose).
 *  - Starts the audio listener if [Settings.coachAudioAuto] AND the user has
 *    granted RECORD_AUDIO; stops it on dispose or when the user mutes.
 *  - Picks the Roboflow / LLM / stub recognizer based on Settings; each
 *    detection is a full-hand inference pushed straight to GameState.
 */
@Composable
fun CoachScreen(
    state: GameState,
    store: SettingsStore,
    onGoToTab: (Int) -> Unit,
) {
    // Orientation is set app-wide in App() from the Settings preference.
    val context = LocalContext.current
    val settings by store.settings.collectAsState(initial = Settings())

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    var micOn by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: cameraGranted
        micGranted = result[Manifest.permission.RECORD_AUDIO] ?: micGranted
    }

    // Ask for permissions on first entry. Mic is optional — only request when
    // the user opted into auto-mic in Settings.
    LaunchedEffect(Unit) {
        val need = buildList {
            if (!cameraGranted) add(Manifest.permission.CAMERA)
            if (!micGranted && settings.coachAudioAuto) add(Manifest.permission.RECORD_AUDIO)
        }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    // Audio listener — wired to GameState through the existing controller.
    val audioController = remember { AudioBoardController(state) }
    var audioListener by remember { mutableStateOf<BoardAudioListener?>(null) }
    fun startMic() {
        if (audioListener != null || !micGranted) return
        audioListener = BoardAudioListener(
            context, languageTag = settings.language,
        ) { events -> audioController.apply(events) }.also { it.start() }
        micOn = true
    }
    fun stopMic() {
        audioListener?.let { it.stop(); it.destroy() }
        audioListener = null
        micOn = false
    }
    LaunchedEffect(micGranted, settings.coachAudioAuto) {
        if (settings.coachAudioAuto && micGranted && audioListener == null) startMic()
    }
    DisposableEffect(Unit) { onDispose { stopMic() } }

    // Recognizer pipeline. Re-create whenever settings flip. Roboflow (hosted)
    // takes priority when a key is set; LLM vision is the toggleable fallback;
    // stub is the last-resort placeholder (UI works, no detection).
    var boxes by remember { mutableStateOf<List<DetectedBox>>(emptyList()) }

    // Correction log + last-detected snapshot. When the user opens the edit
    // sheet and the hand they leave with differs from what the recognizer
    // last saw, we save the pair as a training sample.
    val correctionLog = remember { CorrectionLog(context.applicationContext) }
    var lastFrame by remember { mutableStateOf<Bitmap?>(null) }
    var lastDetectedCounts by remember { mutableStateOf(IntArray(0)) }
    var sheetOpenSnapshot by remember { mutableStateOf<IntArray?>(null) }
    var visionBusy by remember { mutableStateOf(false) }

    // What a detection means: your own hand (replaces) vs the table's discard
    // pond (accumulates into the round's seen pile — the public-tile memory).
    var captureMode by remember { mutableStateOf(CaptureMode.HAND) }

    // Round-scoped LLM coaching memory (persists across snaps; reset on 新局).
    val roundCoach = remember { RoundCoach() }

    val pushCounts: (IntArray) -> Unit = { counts ->
        // Each detection is one deliberate, full-hand inference (snap, or a
        // 3s-apart hosted call), so trust it directly — no cross-frame median
        // smoothing, which would blend hands from different turns and (worse)
        // never populate the hand from a single snap.
        lastDetectedCounts = counts
        if (counts.sum() > 0) {
            when (captureMode) {
                CaptureMode.HAND -> state.setHandCounts(counts)
                CaptureMode.BOARD -> state.addSeenCounts(counts)   // accumulate discards
            }
        }
    }
    // Snap mode is the default: each recognizer's throttle is set to "never"
    // (Long.MAX_VALUE) so only a shutter tap (requestSnap) fires an inference.
    // Always-on flips the throttle to ~3s for continuous detection.
    val intervalMs = if (settings.coachAlwaysOn) 3_000L else Long.MAX_VALUE
    val recognizer: HandRecognizer = remember(
        settings.useLlmVision, settings.backend,
        settings.roboflowApiKey, settings.roboflowModelId,
        intervalMs,
    ) {
        val capture: (Bitmap) -> Unit = { bmp -> lastFrame = bmp }
        when {
            // Hosted Roboflow takes priority when a key is set — typically the
            // user's best-quality option.
            settings.roboflowApiKey.isNotBlank() -> RoboflowHandRecognizer(
                apiKey = settings.roboflowApiKey,
                modelId = settings.roboflowModelId,
                onCounts = pushCounts,
                onBoxes = { boxes = it },
                onBitmap = capture,
                onBusy = { visionBusy = it },
                minIntervalMs = intervalMs,
            )
            settings.useLlmVision -> LlmHandRecognizer(
                client = settings.buildClient(),
                onCounts = pushCounts,
                onBitmap = capture,
                onBusy = { visionBusy = it },
                minIntervalMs = intervalMs,
            )
            else -> StubHandRecognizer()
        }
    }

    // Dispose recognizer-owned resources when leaving the screen / switching backends.
    DisposableEffect(recognizer) {
        onDispose {
            (recognizer as? LlmHandRecognizer)?.close()
            (recognizer as? RoboflowHandRecognizer)?.close()
        }
    }

    // Auto-guide: when the hand updates to a full 13/14 tiles and the content
    // actually changed, ask the round coach for fresh advice (once per distinct
    // hand). Gated by the setting + an active backend. The persistent
    // RoundCoach history gives the LLM memory of the round.
    var lastCoachedSig by remember { mutableStateOf("") }
    LaunchedEffect(state.hand, settings.coachAutoGuide, settings.backend) {
        if (!settings.coachAutoGuide || settings.backend == LlmBackend.OFF) return@LaunchedEffect
        val sig = state.hand.joinToString(",")
        if (state.totalTiles in 13..14 && sig != lastCoachedSig) {
            lastCoachedSig = sig
            roundCoach.ask(state, settings, "我的手牌更新了，下一步怎么打？")
        }
    }

    // The snap button is meaningful whenever a real recognizer is active
    // (stub does nothing on snap). Live = continuous mode is on.
    val snapEnabled = recognizer !is StubHandRecognizer
    val live = settings.coachAlwaysOn && cameraGranted

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Apply system-bar inset padding once at the root so every overlay
            // child clears the status bar / nav bar / notch automatically.
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        if (cameraGranted) {
            // Always hand each frame to the recognizer — its throttle (set
            // from coachAlwaysOn via intervalMs) decides whether to actually
            // run inference or close the proxy. Snap requests bypass it.
            CameraPreview(
                onFrame = { proxy -> recognizer.recognize(proxy) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CameraDeniedState(
                onEdit = { showSheet = true },
                onAskAgain = { permLauncher.launch(arrayOf(Manifest.permission.CAMERA)) },
            )
        }

        // Floating per-tile labels — only renders when the detector returns boxes.
        FloatingTileLabels(
            boxes = boxes,
            modifier = Modifier.fillMaxSize(),
        )

        // Top: a control row (reset left, nav right) with the advice banner
        // below it. Stacking these avoids the three-way horizontal collision
        // that happened in portrait when they all sat on the same top band.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 10.dp, start = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                    modifier = Modifier.clickableOnce {
                        state.resetRound()
                        boxes = emptyList()
                        roundCoach.reset()
                    },
                ) {
                    Text(
                        "🔄 新局",
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    )
                }
                TopRightNav(onGoTo = onGoToTab)
            }
            AdviceBanner(state = state)
        }

        // Bottom strip: chips of detected hand.
        DetectedHandStrip(
            state = state,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp, start = 24.dp, end = 24.dp),
        )

        // Bottom-left: live + mic badges.
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveBadge(label = if (live) "live" else "preview", on = live)
            val pondSize = state.seen.sum()
            if (pondSize > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    shape = androidx.compose.foundation.shape.CircleShape,
                ) {
                    Text(
                        "牌池 $pondSize",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 11.sp,
                    )
                }
            }
            if (micGranted) {
                Surface(
                    color = Color.Transparent,
                    modifier = Modifier,
                ) {
                    Box(
                        Modifier
                            .background(
                                Color.Black.copy(alpha = 0.55f),
                                androidx.compose.foundation.shape.CircleShape,
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            if (micOn) "🎤 on" else "🎤 off",
                            color = Color.White, fontSize = 11.sp,
                            modifier = Modifier.clickableOnce {
                                if (micOn) stopMic() else startMic()
                            },
                        )
                    }
                }
            }
        }

        // Bottom-right: capture-mode toggle stacked above the action row.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // Capture mode: snapping your hand vs the table's discard pond.
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              CaptureMode.entries.forEach { m ->
                  val on = captureMode == m
                  Surface(
                      color = if (on) Color(0xFFFAC775) else Color.Black.copy(alpha = 0.55f),
                      contentColor = if (on) Color(0xFF412402) else Color.White,
                      shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                      modifier = Modifier.clickableOnce { captureMode = m },
                  ) {
                      Text(
                          m.cn,
                          Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                          fontSize = 12.sp,
                          fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                      )
                  }
              }
          }
          Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CoachGuidanceButton(state = state, settings = settings, coach = roundCoach)
            if (snapEnabled) {
                Surface(
                    color = Color.White.copy(alpha = 0.94f),
                    contentColor = Color(0xFF222222),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (visionBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF222222),
                            )
                        } else {
                            Text(
                                "📸",
                                fontSize = 18.sp,
                                modifier = Modifier.clickableOnce {
                                    (recognizer as? LlmHandRecognizer)?.requestSnap()
                                    (recognizer as? RoboflowHandRecognizer)?.requestSnap()
                                },
                            )
                        }
                    }
                }
            }
            EditFab(onClick = { showSheet = true })
          }
        }
    }

    // When the sheet opens we snapshot what the recognizer last saw; when it
    // closes we compare against the user's hand and log a correction if they
    // differ. This is the feedback loop for "improve itself" later.
    LaunchedEffect(showSheet) {
        if (showSheet) {
            sheetOpenSnapshot = lastDetectedCounts.copyOf()
        } else {
            sheetOpenSnapshot?.let { detected ->
                correctionLog.log(detected = detected, corrected = state.hand, frame = lastFrame)
            }
            sheetOpenSnapshot = null
        }
    }

    EditHandSheet(
        state = state,
        visible = showSheet,
        onDismiss = { showSheet = false },
        onGoTo = { idx ->
            showSheet = false
            onGoToTab(idx)
        },
    )
}

@Composable
private fun CameraDeniedState(onEdit: () -> Unit, onAskAgain: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Grant camera access to enable live coach.",
            color = Color.White, fontSize = 14.sp,
        )
        Text(
            "You can still tap ✎ to enter your hand manually.",
            color = Color(0xFFC8C4B8), fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                color = Color.White.copy(alpha = 0.94f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            ) {
                Text(
                    "Ask again",
                    Modifier.clickableOnce(onAskAgain).padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color(0xFF222222), fontSize = 13.sp,
                )
            }
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            ) {
                Text(
                    "✎ Edit hand",
                    Modifier.clickableOnce(onEdit).padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White, fontSize = 13.sp,
                )
            }
        }
    }
}

/** Non-rippled click modifier so we can stick tap handlers on plain Text. */
@Composable
private fun Modifier.clickableOnce(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = src, indication = null, onClick = onClick)
}
