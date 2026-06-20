package com.mahjongcoach.app.score

import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mahjongcoach.app.ui.Spacing
import com.mahjongcoach.app.ui.editableScreen
import com.mahjongcoach.app.data.LlmBackend
import com.mahjongcoach.app.data.Settings
import com.mahjongcoach.app.data.SettingsStore
import com.mahjongcoach.app.vision.RoboflowInfer
import com.mahjongcoach.engine.scoring.RiichiContext
import com.mahjongcoach.engine.scoring.Ruleset
import com.mahjongcoach.engine.scoring.ScoreService
import com.mahjongcoach.engine.scoring.T34
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Points calculator for a REVEALED hand. Type a compact notation
 * (`234m567m345p678p55s`, honors `z1..z7`) or pick a photo and let the
 * configured LLM vision read the tiles in. The engine scores either ruleset.
 */
@Composable
fun ScoreScreen(store: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsState(initial = Settings())

    var ruleset by remember { mutableStateOf(Ruleset.SICHUAN) }
    var hand by remember { mutableStateOf("") }
    var winTile by remember { mutableStateOf("") }
    var tsumo by remember { mutableStateOf(false) }
    var dealer by remember { mutableStateOf(false) }
    var riichi by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var photoStatus by remember { mutableStateOf<String?>(null) }

    val hasRoboflow = settings.roboflowApiKey.isNotBlank()

    fun runPhoto(uri: Uri?) {
        if (uri == null) return
        if (!hasRoboflow && settings.backend == LlmBackend.OFF) {
            photoStatus = "Set a Roboflow key (or an LLM vision backend) in Settings first."
            return
        }
        photoBusy = true
        photoStatus = "Reading tiles…"
        scope.launch {
            val parsed: IntArray? = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openStream(uri) ?: error("cannot open image")
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("not a decodable image")
                    if (hasRoboflow) {
                        // Prefer the trained Roboflow detector — same path the
                        // live Coach uses, so what you see there matches here.
                        RoboflowInfer.infer(
                            settings.roboflowApiKey, settings.roboflowModelId, bitmap,
                        ).counts
                    } else {
                        settings.buildClient().recognizeHand(bitmap)
                    }
                }
            }.getOrElse {
                photoBusy = false; photoStatus = "Error: ${it.message}"; return@launch
            }
            photoBusy = false
            val source = if (hasRoboflow) "Roboflow" else "LLM"
            if (parsed == null || parsed.sum() == 0) {
                photoStatus = "$source read no tiles. Try a clearer, straight-on shot of the full hand."
            } else {
                hand = countsToNotation(parsed)
                photoStatus = "$source recognized ${parsed.sum()} tile(s) — review and edit before scoring."
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> runPhoto(uri) }

    // System-camera capture writes the JPEG into the app's cache and returns a
    // FileProvider-wrapped Uri. We keep the pending Uri here so the result
    // callback knows which file to read.
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (success && uri != null) runPhoto(uri)
        else if (!success) photoStatus = "Camera capture canceled."
    }

    val result: String = remember(ruleset, hand, winTile, tsumo, dealer, riichi) {
        runCatching {
            val counts = T34.parse(hand)
            when (ruleset) {
                Ruleset.SICHUAN -> ScoreService.describe(Ruleset.SICHUAN, counts)
                Ruleset.JAPANESE -> {
                    val wt = T34.parse(winTile).indexOfFirst { it > 0 }
                    require(wt >= 0) { "enter the winning tile" }
                    ScoreService.describe(
                        Ruleset.JAPANESE, counts,
                        ctx = RiichiContext(
                            winTile = wt, tsumo = tsumo, riichi = riichi,
                            seatWind = if (dealer) T34.EAST else T34.SOUTH, roundWind = T34.EAST,
                        ),
                    )
                }
            }
        }.getOrElse { "—  (${it.message})" }
    }

    // Input controls and the result are reusable content blocks so portrait
    // (one column) and landscape (two panes) share the exact same widgets.
    val controls: @Composable ColumnScope.() -> Unit = {
        Text("点数计算 Score", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        SingleChoiceSegmentedButtonRow {
            listOf(Ruleset.SICHUAN to "四川", Ruleset.JAPANESE to "日本").forEachIndexed { i, (r, label) ->
                SegmentedButton(
                    selected = ruleset == r, onClick = { ruleset = r },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }

        // From photo affordance — drives the same `hand` field.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !photoBusy, modifier = Modifier.weight(1f),
            ) { Text("🖼 From gallery", fontSize = 12.sp) }
            OutlinedButton(
                onClick = {
                    val uri = createCaptureUri(context)
                    if (uri == null) {
                        photoStatus = "Couldn't create a temp file for the camera."
                    } else {
                        pendingCaptureUri = uri
                        cameraLauncher.launch(uri)
                    }
                },
                enabled = !photoBusy, modifier = Modifier.weight(1f),
            ) { Text("📷 Take photo", fontSize = 12.sp) }
        }
        photoStatus?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }

        OutlinedTextField(
            value = hand, onValueChange = { hand = it },
            label = { Text("Revealed hand (e.g. 234m567m345p678p55s)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )

        if (ruleset == Ruleset.JAPANESE) {
            OutlinedTextField(
                value = winTile, onValueChange = { winTile = it },
                label = { Text("Winning tile (e.g. 4m, 5z)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip("自摸", tsumo) { tsumo = it }
                ToggleChip("庄家", dealer) { dealer = it }
                ToggleChip("立直", riichi) { riichi = it }
            }
            Text(
                "Dora / aka / ippatsu — TODO in the full context editor.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    val resultPane: @Composable ColumnScope.() -> Unit = {
        Card(Modifier.fillMaxWidth()) {
            Text(result, Modifier.padding(12.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Text(
            if (hasRoboflow)
                "Photo reading uses your Roboflow detector — same as the Coach tab. " +
                    "Sichuan = m/p/s only; Japanese honors (z*) need manual entry."
            else
                "Photo reading uses the configured LLM vision backend. Set a Roboflow key " +
                    "in Settings for better tile reading. Honors (z*) need manual entry.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
        )
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscape) {
        // Two panes: controls on the left, result on the right — uses the
        // wide screen instead of one tall scrolling column.
        Row(
            Modifier.fillMaxSize().editableScreen(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.section),
            ) { controls() }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.section),
            ) {
                Spacer(Modifier.height(40.dp))   // align with title baseline
                resultPane()
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().editableScreen().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.section),
        ) {
            controls()
            HorizontalDivider()
            resultPane()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(
    horizontalArrangement: Arrangement.Horizontal,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = horizontalArrangement,
        content = { content() },
    )
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on, onClick = { onChange(!on) }, label = { Text(label) },
        modifier = Modifier.padding(end = 6.dp),
    )
}

/** Read all bytes from a content URI; null if unreadable. */
private fun ContentResolver.openStream(uri: Uri): ByteArray? = runCatching {
    openInputStream(uri)?.use { it.readBytes() }
}.getOrNull()

/**
 * Allocate a unique cache-path JPEG and wrap it with the app's FileProvider
 * authority so the system camera app can write into it. The cache directory
 * is automatically pruned by the OS, so old captures don't pile up.
 */
private fun createCaptureUri(context: Context): Uri? = runCatching {
    val dir = File(context.cacheDir, "captures").also { it.mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()

/**
 * Engine-space 27-length counts back to compact notation
 * ("123m456p77s"). Empty suits are skipped.
 */
private fun countsToNotation(counts: IntArray): String {
    val suits = listOf("m", "p", "s")
    val sb = StringBuilder()
    for (suit in 0..2) {
        val digits = StringBuilder()
        for (rank in 0..8) {
            val tile = suit * 9 + rank
            if (tile >= counts.size) break
            repeat(counts[tile]) { digits.append(rank + 1) }
        }
        if (digits.isNotEmpty()) sb.append(digits).append(suits[suit])
    }
    return sb.toString()
}
