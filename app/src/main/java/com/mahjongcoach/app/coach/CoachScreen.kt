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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mahjongcoach.app.GameState
import android.graphics.Bitmap
import com.mahjongcoach.app.audio.AudioBoardController
import com.mahjongcoach.app.audio.BoardAudioListener
import com.mahjongcoach.app.data.CorrectionLog
import com.mahjongcoach.app.data.Settings
import com.mahjongcoach.app.data.SettingsStore
import com.mahjongcoach.app.vision.DetectedBox
import com.mahjongcoach.app.vision.HandRecognizer
import com.mahjongcoach.app.vision.LlmHandRecognizer
import com.mahjongcoach.app.vision.RoboflowHandRecognizer
import com.mahjongcoach.app.vision.StubHandRecognizer
import com.mahjongcoach.engine.vision.RecognitionSmoother

/**
 * Camera-first Coach. Full-bleed CameraX preview, translucent AdviceBanner at
 * the top, DetectedHandStrip near the bottom, mic/live badges bottom-left, and a
 * single edit FAB bottom-right that opens the modal sheet. Tab bar is hidden by
 * the caller while this screen is foreground; the sheet's "Go to" footer is the
 * only nav route off the screen.
 *
 * Lifecycle:
 *  - Locks orientation to landscape on enter; restores on dispose.
 *  - Binds the camera in `CameraPreview` (which auto-unbinds on dispose).
 *  - Starts the audio listener if [Settings.coachAudioAuto] AND the user has
 *    granted RECORD_AUDIO; stops it on dispose or when the user mutes.
 *  - Picks `StubHandRecognizer` or `LlmHandRecognizer` based on Settings; both
 *    flow through the engine's [RecognitionSmoother] before pushing counts.
 */
@Composable
fun CoachScreen(
    state: GameState,
    store: SettingsStore,
    onGoToTab: (Int) -> Unit,
) {
    LockOrientation(Orientations.LANDSCAPE)

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
    val smoother = remember { RecognitionSmoother(window = 7) }
    var boxes by remember { mutableStateOf<List<DetectedBox>>(emptyList()) }

    // Correction log + last-detected snapshot. When the user opens the edit
    // sheet and the hand they leave with differs from what the recognizer
    // last saw, we save the pair as a training sample.
    val correctionLog = remember { CorrectionLog(context.applicationContext) }
    var lastFrame by remember { mutableStateOf<Bitmap?>(null) }
    var lastDetectedCounts by remember { mutableStateOf(IntArray(0)) }
    var sheetOpenSnapshot by remember { mutableStateOf<IntArray?>(null) }
    var visionBusy by remember { mutableStateOf(false) }

    val pushCounts: (IntArray) -> Unit = { counts ->
        lastDetectedCounts = counts
        val stable = smoother.submit(counts)
        if (smoother.isStable()) state.setHandCounts(stable)
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

        // Top: one-line advice HUD.
        AdviceBanner(
            state = state,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, start = 16.dp, end = 16.dp),
        )

        // Top-right: nav pills so other tabs are reachable without first
        // opening the edit sheet. Same target as the sheet footer chips.
        TopRightNav(
            onGoTo = onGoToTab,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp),
        )

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

        // Bottom-right: AI guidance + edit FAB + (when useful) snap button.
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoachGuidanceButton(state = state, settings = settings)
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
