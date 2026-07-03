package io.github.auxen.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.dsp.EqController
import io.github.auxen.dsp.EqState

/**
 * Equalizer screen: enable toggle, the desktop app's 10-band graphic EQ with
 * its presets, and AutoEq profile import (Wavelet-style headphone correction).
 */
@UnstableApi
@Composable
fun EqualizerScreen(modifier: Modifier = Modifier) {
    val state by EqController.state.collectAsState()
    val context = LocalContext.current
    var presetMenuOpen by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text == null) {
            importError = "Could not read file"
        } else {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".txt")
            EqController.importAutoEq(text, name)
                .onSuccess { importError = null }
                .onFailure { importError = it.message }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Equalizer", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = { EqController.setEnabled(it) })
        }

        state.presetName?.let {
            Text("Active profile: $it", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Preamp: %.1f dB".format(state.preampDb),
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                OutlinedButton(onClick = { presetMenuOpen = true }) { Text("Presets") }
                DropdownMenu(expanded = presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
                    EqState.PRESETS.keys.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                EqController.applyPreset(name)
                                presetMenuOpen = false
                            },
                        )
                    }
                }
            }
            Button(onClick = { importLauncher.launch(arrayOf("text/plain")) }) {
                Text("Import AutoEq profile")
            }
        }

        importError?.let {
            Text("Import failed: $it", color = MaterialTheme.colorScheme.error)
        }

        // 10-band graphic EQ. When a parametric AutoEq profile is active the
        // bands list is null and the sliders show flat until touched (which
        // switches back to graphic mode).
        val bands = state.bands ?: List(EqState.NUM_BANDS) { 0.0 }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            bands.forEachIndexed { index, gain ->
                BandSlider(
                    label = EqState.BAND_LABELS[index],
                    gainDb = gain,
                    onChange = { EqController.setBand(index, it) },
                )
            }
        }

        Text(
            "AutoEq profiles for ~5,000 headphones: github.com/jaakkopasanen/AutoEq " +
                "(use the ParametricEq .txt export). Correction runs inside the player's " +
                "float pipeline — no system EQ involved.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BandSlider(label: String, gainDb: Double, onChange: (Double) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%.0f".format(gainDb), style = MaterialTheme.typography.labelSmall)
        VerticalSlider(
            value = gainDb.toFloat(),
            onChange = { onChange(it.toDouble()) },
            valueRange = EqState.MIN_GAIN_DB.toFloat()..EqState.MAX_GAIN_DB.toFloat(),
            modifier = Modifier.height(180.dp).width(40.dp),
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** A [Slider] rotated to run bottom-to-top. */
@Composable
private fun VerticalSlider(
    value: Float,
    onChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = valueRange,
        modifier = modifier
            .graphicsLayer { rotationZ = 270f }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    androidx.compose.ui.unit.Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth,
                    ),
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(
                        x = -(placeable.width - placeable.height) / 2,
                        y = -(placeable.height - placeable.width) / 2,
                    )
                }
            },
    )
}
