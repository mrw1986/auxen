package io.github.auxen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.auxen.R
import io.github.auxen.dsp.BalanceState
import io.github.auxen.dsp.BassBoostState
import io.github.auxen.dsp.LimiterState
import io.github.auxen.dsp.ReplayGainState
import io.github.auxen.dsp.ReverbState
import io.github.auxen.dsp.VirtualizerState
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Per-effect sections for [io.github.auxen.ui.EqualizerScreen]'s DSP suite:
 * one expandable card per effect, each with its OWN enable switch bound to
 * that effect's `enabled` field -- deliberately independent, per the
 * standing requirement that every effect is individually toggleable with no
 * coupling between sections (docs/plans/2026-07-13-android-dsp-b-ui.md, Task
 * 3). Callers own the live [io.github.auxen.dsp.AudioFxController] state and
 * pass it in, matching the stateless-content-composable pattern already used
 * by [TrackActionSheetContent] and `AutoEqPickerResults`.
 */

/**
 * An expandable card with an independent enable [Switch] and an independent
 * expand/collapse chevron. Neither control affects the other: disabling a
 * section doesn't collapse it, and collapsing a section doesn't disable it.
 */
@Composable
fun FxSectionCard(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    // No visible Text of its own for TalkBack to merge (unlike a
                    // Button) -- with up to seven of these on one screen, an
                    // unlabeled switch is indistinguishable from any other.
                    modifier = Modifier.semantics { contentDescription = title },
                )
                IconButton(onClick = { onExpandedChange(!expanded) }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) {
                            stringResource(R.string.fx_section_collapse, title)
                        } else {
                            stringResource(R.string.fx_section_expand, title)
                        },
                    )
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Live drag position plus a debounced write-through -- the desktop app's
 * established 50ms convention for continuous controls (project memory:
 * Collection's `_refresh_pending_id` debounce), ported here so a fast drag
 * doesn't launch a fresh `AudioFxController.updateX` (and its own chained
 * persist coroutine) every single frame. [value] tracks every drag frame so
 * a caller-rendered readout stays live; only [onDrag]'s effect on the
 * eventual [onCommit] call is delayed.
 */
private class DebouncedSliderState(val value: Float, val onDrag: (Float) -> Unit)

@Composable
private fun rememberDebouncedSlider(
    committedValue: Float,
    debounceMillis: Long = 50L,
    onCommit: (Float) -> Unit,
): DebouncedSliderState {
    val scope = rememberCoroutineScope()
    // Re-seeds from the upstream value only when IT changes (e.g. after a
    // commit round-trips through AudioFxController) -- not on every
    // recomposition, so mid-drag frames keep tracking the local drag instead
    // of snapping back to a stale committed value.
    var localValue by remember(committedValue) { mutableStateOf(committedValue) }
    var pendingJob by remember { mutableStateOf<Job?>(null) }
    val onDrag: (Float) -> Unit = { newValue ->
        localValue = newValue
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(debounceMillis)
            onCommit(newValue)
        }
    }
    return DebouncedSliderState(localValue, onDrag)
}

/** A label + monospace value readout above a debounced [Slider]. */
@Composable
private fun LabeledSlider(
    label: String,
    committedValue: Double,
    valueRange: ClosedFloatingPointRange<Float>,
    formatValue: (Double) -> String,
    onCommit: (Double) -> Unit,
) {
    val slider = rememberDebouncedSlider(committedValue.toFloat()) { onCommit(it.toDouble()) }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                formatValue(slider.value.toDouble()),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = slider.value,
            onValueChange = slider.onDrag,
            valueRange = valueRange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * Limiter: threshold -12..0 dB (default -1), release 40..500 ms (default
 * 120); knee stays fixed at [LimiterState]'s default 6 dB (advanced-free UI,
 * per Task 3, item 4) -- no slider for it. Subtitle deliberately does NOT
 * claim "<0.5 dB at threshold": the true reduction at threshold is
 * `kneeDb/8` dB (0.75 dB at the default 6 dB knee) -- a copy mistake caught
 * and corrected during DSP-a review (docs/plans/2026-07-13-android-dsp-b-ui.md
 * line 9).
 */
@Composable
fun LimiterSection(
    state: LimiterState,
    onStateChange: (LimiterState) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FxSectionCard(
        title = stringResource(R.string.fx_limiter_title),
        subtitle = stringResource(R.string.fx_limiter_subtitle),
        enabled = state.enabled,
        onEnabledChange = { onStateChange(state.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        LabeledSlider(
            label = stringResource(R.string.fx_limiter_threshold_label),
            committedValue = state.thresholdDb,
            valueRange = -12f..0f,
            formatValue = { "%.1f dB".format(it) },
            onCommit = { onStateChange(state.copy(thresholdDb = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.fx_limiter_release_label),
            committedValue = state.releaseMs,
            valueRange = 40f..500f,
            formatValue = { "%.0f ms".format(it) },
            onCommit = { onStateChange(state.copy(releaseMs = it)) },
        )
    }
}

/**
 * `PresetReverb.PRESET_*` display labels, index-aligned with the platform
 * constants (`NONE`=0 .. `PLATE`=6) -- see [ReverbState.preset]'s KDoc.
 */
private val REVERB_PRESET_LABEL_RES = listOf(
    R.string.fx_reverb_preset_none,
    R.string.fx_reverb_preset_small_room,
    R.string.fx_reverb_preset_medium_room,
    R.string.fx_reverb_preset_large_room,
    R.string.fx_reverb_preset_medium_hall,
    R.string.fx_reverb_preset_large_hall,
    R.string.fx_reverb_preset_plate,
)

/** Reverb: preset dropdown over the seven `PresetReverb` constants -- Task 3, item 5. */
@Composable
fun ReverbSection(
    state: ReverbState,
    onStateChange: (ReverbState) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    FxSectionCard(
        title = stringResource(R.string.fx_reverb_title),
        subtitle = stringResource(R.string.fx_reverb_subtitle),
        enabled = state.enabled,
        onEnabledChange = { onStateChange(state.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        Column {
            OutlinedButton(onClick = { menuOpen = true }) {
                Text(stringResource(REVERB_PRESET_LABEL_RES[state.preset.coerceIn(0, 6)]))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                REVERB_PRESET_LABEL_RES.forEachIndexed { index, labelRes ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        onClick = {
                            onStateChange(state.copy(preset = index))
                            menuOpen = false
                        },
                    )
                }
            }
        }
    }
}

/** One side of a [io.github.auxen.dsp.BalanceState.balance] readout, rounded to the nearest percent. */
internal sealed interface BalanceReadout {
    data object Center : BalanceReadout
    data class Left(val percent: Int) : BalanceReadout
    data class Right(val percent: Int) : BalanceReadout
}

/**
 * Rounds [balance] (-1..+1) to the nearest percent and classifies it. Rounds
 * BEFORE classifying, so a value that rounds to 0% (e.g. 0.004f) reports
 * [BalanceReadout.Center] rather than a spurious `Left(0)`/`Right(0)`.
 */
internal fun balanceReadout(balance: Float): BalanceReadout {
    val percent = round(abs(balance) * 100).toInt()
    return when {
        percent == 0 -> BalanceReadout.Center
        balance < 0f -> BalanceReadout.Left(percent)
        else -> BalanceReadout.Right(percent)
    }
}

/**
 * Snaps [value] to dead-center when within [thresholdFraction] of 0, so a
 * user dragging near the middle lands exactly on 0 instead of a stray
 * fractional value. The boundary is exclusive: a value exactly AT the
 * threshold is left untouched.
 */
internal fun snapBalanceToCenter(value: Float, thresholdFraction: Float = 0.05f): Float =
    if (abs(value) < thresholdFraction) 0f else value

/**
 * Bass boost: frequency 40..160 Hz (default 80), gain 0..12 dB (default 6) --
 * per docs/plans/2026-07-13-android-dsp-b-ui.md, Task 3, item 2.
 */
@Composable
fun BassBoostSection(
    state: BassBoostState,
    onStateChange: (BassBoostState) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FxSectionCard(
        title = stringResource(R.string.fx_bass_boost_title),
        subtitle = stringResource(R.string.fx_bass_boost_subtitle),
        enabled = state.enabled,
        onEnabledChange = { onStateChange(state.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        LabeledSlider(
            label = stringResource(R.string.fx_bass_boost_freq_label),
            committedValue = state.freqHz,
            valueRange = 40f..160f,
            formatValue = { "%.0f Hz".format(it) },
            onCommit = { onStateChange(state.copy(freqHz = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.fx_bass_boost_gain_label),
            committedValue = state.gainDb,
            valueRange = 0f..12f,
            formatValue = { "%.1f dB".format(it) },
            onCommit = { onStateChange(state.copy(gainDb = it)) },
        )
    }
}

/**
 * Balance: single slider -1..+1 with center snap, L/R side labels, and a
 * live monospace percent readout -- per Task 3, item 3. The readout is
 * driven by the SAME live drag value as the slider thumb (via
 * [rememberDebouncedSlider]'s [DebouncedSliderState.value]), so it updates
 * every frame during a drag rather than waiting for the debounced commit.
 */
@Composable
fun BalanceSection(
    state: BalanceState,
    onStateChange: (BalanceState) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FxSectionCard(
        title = stringResource(R.string.fx_balance_title),
        subtitle = stringResource(R.string.fx_balance_subtitle),
        enabled = state.enabled,
        onEnabledChange = { onStateChange(state.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        val slider = rememberDebouncedSlider(state.balance) { onStateChange(state.copy(balance = it)) }
        val readout = balanceReadout(slider.value)
        Text(
            text = when (readout) {
                is BalanceReadout.Center -> stringResource(R.string.fx_balance_center)
                is BalanceReadout.Left -> stringResource(R.string.fx_balance_percent_left, readout.percent)
                is BalanceReadout.Right -> stringResource(R.string.fx_balance_percent_right, readout.percent)
            },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Distinct from the section's own title/switch label (both are
            // separate accessible nodes on the same screen -- reusing "Balance"
            // for both made onNodeWithContentDescription("Balance") ambiguous,
            // caught by FxSectionsAccessibilityTest).
            val sliderA11yLabel = stringResource(R.string.fx_balance_slider_a11y_label)
            Text(stringResource(R.string.fx_balance_left), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = slider.value,
                onValueChange = { slider.onDrag(snapBalanceToCenter(it)) },
                valueRange = -1f..1f,
                modifier = Modifier.weight(1f).semantics { contentDescription = sliderA11yLabel },
            )
            Text(stringResource(R.string.fx_balance_right), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Virtualizer: strength 0..100% UI, mapped to the platform's 0..1000 range -- Task 3, item 6. */
@Composable
fun VirtualizerSection(
    state: VirtualizerState,
    onStateChange: (VirtualizerState) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FxSectionCard(
        title = stringResource(R.string.fx_virtualizer_title),
        subtitle = stringResource(R.string.fx_virtualizer_subtitle),
        enabled = state.enabled,
        onEnabledChange = { onStateChange(state.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        LabeledSlider(
            label = stringResource(R.string.fx_virtualizer_strength_label),
            committedValue = state.strength / 10.0,
            valueRange = 0f..100f,
            formatValue = { "${it.roundToInt()}%" },
            onCommit = { onStateChange(state.copy(strength = (it * 10).roundToInt())) },
        )
    }
}

/**
 * Volume normalization (ReplayGain): Track/Album mode, preamp -12..+12 dB,
 * fallback gain -12..0 dB -- Task 3, item 7. User-facing name is "Volume
 * normalization"; "ReplayGain" stays in the subtitle for the initiated.
 */
@Composable
fun VolumeNormalizationSection(
    state: ReplayGainState,
    onStateChange: (ReplayGainState) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FxSectionCard(
        title = stringResource(R.string.fx_volume_normalization_title),
        subtitle = stringResource(R.string.fx_volume_normalization_subtitle),
        enabled = state.enabled,
        onEnabledChange = { onStateChange(state.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !state.albumMode,
                onClick = { onStateChange(state.copy(albumMode = false)) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.fx_volume_normalization_mode_track)) }
            SegmentedButton(
                selected = state.albumMode,
                onClick = { onStateChange(state.copy(albumMode = true)) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.fx_volume_normalization_mode_album)) }
        }
        LabeledSlider(
            label = stringResource(R.string.fx_volume_normalization_preamp_label),
            committedValue = state.preampDb,
            valueRange = -12f..12f,
            formatValue = { "%+.1f dB".format(it) },
            onCommit = { onStateChange(state.copy(preampDb = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.fx_volume_normalization_fallback_label),
            committedValue = state.fallbackDb,
            valueRange = -12f..0f,
            formatValue = { "%.1f dB".format(it) },
            onCommit = { onStateChange(state.copy(fallbackDb = it)) },
        )
    }
}
