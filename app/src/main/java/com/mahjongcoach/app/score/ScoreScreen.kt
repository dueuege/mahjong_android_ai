package com.mahjongcoach.app.score

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mahjongcoach.engine.scoring.RiichiContext
import com.mahjongcoach.engine.scoring.Ruleset
import com.mahjongcoach.engine.scoring.ScoreService
import com.mahjongcoach.engine.scoring.T34

/**
 * Points calculator for a REVEALED hand. Today it scores a hand you type in
 * (riichi notation, e.g. "234m567m345p678p55s", honors as z1..z7); the photo
 * path ([com.mahjongcoach.app.vision.RevealedHandRecognizer]) will fill the same
 * fields automatically once the model is wired.
 */
@Composable
fun ScoreScreen() {
    var ruleset by remember { mutableStateOf(Ruleset.SICHUAN) }
    var hand by remember { mutableStateOf("") }
    var winTile by remember { mutableStateOf("") }
    var tsumo by remember { mutableStateOf(false) }
    var dealer by remember { mutableStateOf(false) }
    var riichi by remember { mutableStateOf(false) }

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

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("点数计算 Score", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        SingleChoiceSegmentedButtonRow {
            listOf(Ruleset.SICHUAN to "四川", Ruleset.JAPANESE to "日本").forEachIndexed { i, (r, label) ->
                SegmentedButton(
                    selected = ruleset == r, onClick = { ruleset = r },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToggleChip("自摸", tsumo) { tsumo = it }
                ToggleChip("庄家", dealer) { dealer = it }
                ToggleChip("立直", riichi) { riichi = it }
            }
            Text("Dora / aka / ippatsu etc. — TODO in the full context editor.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }

        HorizontalDivider()
        Card(Modifier.fillMaxWidth()) {
            Text(result, Modifier.padding(12.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Text("📷 Photo scoring drops into these same fields once the tile model ships.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(selected = on, onClick = { onChange(!on) }, label = { Text(label) },
        modifier = Modifier.padding(end = 6.dp))
}
