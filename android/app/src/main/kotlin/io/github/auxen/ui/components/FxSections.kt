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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import io.github.auxen.dsp.AudioFxController
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
 * pass it in for RENDERING (readouts, switch/slider positions), matching the
 * stateless-content-composable pattern already used by
 * [TrackActionSheetContent] and `AutoEqPickerResults`.
 *
 * Every commit lambda (a slider's `onCommit`, a switch's `onEnabledChange`,
 * a dropdown/segmented-button selection) merges its field against
 * `AudioFxController.xState.value` read FRESH at the moment it fires, not
 * the `state` parameter closed over whenever this composable last
 * recomposed. Two controls in the SAME section can commit independently and
 * close together (e.g. dragging a slider while also flipping the enable
 * switch) -- if the slower one's `state.copy(...)` used a snapshot from
 * before the faster one's write landed, it would silently revert that
 * write. Reading the controller directly at commit time makes this
 * impossible regardless of recomposition timing (final review round,
 * Important #2).
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
    // DisposableEffect(Unit) below only ever RUNS its body once (the key
    // never changes), so the onDispose lambda it registers is permanently
    // the one closed over at first composition -- a plain captured
    // `onCommit` parameter would stay frozen at whatever was passed in on
    // THAT first call, even though every later recomposition passes a fresh
    // onCommit closure. rememberUpdatedState keeps a stable holder whose
    // `.value` is refreshed every recomposition (via an internal
    // SideEffect), so the flush below always invokes the CURRENT onCommit,
    // not composition #1's (final review round, Important #2).
    val currentOnCommit by rememberUpdatedState(onCommit)
    // Re-seeds from the upstream value only when IT changes (e.g. after a
    // commit round-trips through AudioFxController) -- not on every
    // recomposition, so mid-drag frames keep tracking the local drag instead
    // of snapping back to a stale committed value.
    var localValue by remember(committedValue) { mutableStateOf(committedValue) }
    var pendingJob by remember { mutableStateOf<Job?>(null) }
    // True from the moment a drag frame arrives until its commit actually
    // executes (either the debounce firing normally, or the dispose-time
    // flush below) -- tracked separately from pendingJob's own Job.isActive
    // so the flush doesn't depend on ordering between this composable's
    // rememberCoroutineScope() teardown and its DisposableEffect (both fire
    // during the same "leave composition" pass; relying on Job.isActive
    // there would silently break if that order ever flips).
    var hasUncommittedChange by remember { mutableStateOf(false) }
    val onDrag: (Float) -> Unit = { newValue ->
        localValue = newValue
        hasUncommittedChange = true
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(debounceMillis)
            currentOnCommit(newValue)
            hasUncommittedChange = false
        }
    }
    // Flush-on-dispose: if the section hosting this slider leaves
    // composition (collapsed, screen navigated away) before the 50ms
    // debounce settles, rememberCoroutineScope()'s scope cancellation would
    // otherwise silently drop the last drag frame's commit -- reproduced and
    // fixed per the DSP-b Task 3/4 review's disposal-race finding. Reads
    // localValue live inside onDispose (not a captured parameter), so the
    // flush always commits whatever the LATEST drag position was, not a
    // stale value from whenever this effect was first composed.
    DisposableEffect(Unit) {
        onDispose {
            if (hasUncommittedChange) {
                pendingJob?.cancel()
                currentOnCommit(localValue)
                hasUncommittedChange = false
            }
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
        onEnabledChange = { onStateChange(AudioFxController.limiterState.value.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        LabeledSlider(
            label = stringResource(R.string.fx_limiter_threshold_label),
            committedValue = state.thresholdDb,
            valueRange = -12f..0f,
            formatValue = { "%.1f dB".format(it) },
            onCommit = { onStateChange(AudioFxController.limiterState.value.copy(thresholdDb = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.fx_limiter_release_label),
            committedValue = state.releaseMs,
            valueRange = 40f..500f,
            formatValue = { "%.0f ms".format(it) },
            onCommit = { onStateChange(AudioFxController.limiterState.value.copy(releaseMs = it)) },
        )
    }
}

/**
 * Applied at the reverb section's enable toggle. Enabling reverb while its
 * preset is still `PRESET_NONE` (0) would otherwise silently produce zero
 * effect -- "reverb on" reading as "reverberation preset none" -- since the
 * preset dropdown is a completely separate control from the enable switch.
 * Picks a real preset (`PRESET_SMALLROOM`, 1) instead, but ONLY when
 * enabling from `PRESET_NONE`; an already-chosen preset, or a disable, is
 * left untouched (platform effects fix -- user-confirmed device report,
 * 2026-07-13, the strongest and cheapest of the two root causes).
 */
internal fun reverbStateForEnableToggle(current: ReverbState, enabling: Boolean): ReverbState {
    val preset = if (enabling && current.preset == 0) 1 else current.preset
    return current.copy(enabled = enabling, preset = preset)
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
        onEnabledChange = { onStateChange(reverbStateForEnableToggle(AudioFxController.reverbState.value, it)) },
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
                            onStateChange(AudioFxController.reverbState.value.copy(preset = index))
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
        onEnabledChange = { onStateChange(AudioFxController.bassBoostState.value.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        LabeledSlider(
            label = stringResource(R.string.fx_bass_boost_freq_label),
            committedValue = state.freqHz,
            valueRange = 40f..160f,
            formatValue = { "%.0f Hz".format(it) },
            onCommit = { onStateChange(AudioFxController.bassBoostState.value.copy(freqHz = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.fx_bass_boost_gain_label),
            committedValue = state.gainDb,
            valueRange = 0f..12f,
            formatValue = { "%.1f dB".format(it) },
            onCommit = { onStateChange(AudioFxController.bassBoostState.value.copy(gainDb = it)) },
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
        onEnabledChange = { onStateChange(AudioFxController.balanceState.value.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        val slider = rememberDebouncedSlider(state.balance) {
            onStateChange(AudioFxController.balanceState.value.copy(balance = it))
        }
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
        onEnabledChange = { onStateChange(AudioFxController.virtualizerState.value.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        LabeledSlider(
            label = stringResource(R.string.fx_virtualizer_strength_label),
            committedValue = state.strength / 10.0,
            valueRange = 0f..100f,
            formatValue = { "${it.roundToInt()}%" },
            onCommit = {
                onStateChange(AudioFxController.virtualizerState.value.copy(strength = (it * 10).roundToInt()))
            },
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
        onEnabledChange = { onStateChange(AudioFxController.replayGainState.value.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !state.albumMode,
                onClick = { onStateChange(AudioFxController.replayGainState.value.copy(albumMode = false)) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.fx_volume_normalization_mode_track)) }
            SegmentedButton(
                selected = state.albumMode,
                onClick = { onStateChange(AudioFxController.replayGainState.value.copy(albumMode = true)) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.fx_volume_normalization_mode_album)) }
        }
        LabeledSlider(
            label = stringResource(R.string.fx_volume_normalization_preamp_label),
            committedValue = state.preampDb,
            valueRange = -12f..12f,
            formatValue = { "%+.1f dB".format(it) },
            onCommit = { onStateChange(AudioFxController.replayGainState.value.copy(preampDb = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.fx_volume_normalization_fallback_label),
            committedValue = state.fallbackDb,
            valueRange = -12f..0f,
            formatValue = { "%.1f dB".format(it) },
            onCommit = { onStateChange(AudioFxController.replayGainState.value.copy(fallbackDb = it)) },
        )
    }
}
