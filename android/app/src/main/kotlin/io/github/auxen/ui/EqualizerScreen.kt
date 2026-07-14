package io.github.auxen.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.Graph
import io.github.auxen.R
import io.github.auxen.dsp.AudioFxController
import io.github.auxen.dsp.AutoEqController
import io.github.auxen.dsp.AutoEqProfile
import io.github.auxen.dsp.EqController
import io.github.auxen.dsp.EqState
import io.github.auxen.ui.components.BalanceSection
import io.github.auxen.ui.components.BassBoostSection
import io.github.auxen.ui.components.FxSectionCard
import io.github.auxen.ui.components.LimiterSection
import io.github.auxen.ui.components.ReverbSection
import io.github.auxen.ui.components.VirtualizerSection
import io.github.auxen.ui.components.VolumeNormalizationSection
import kotlinx.coroutines.launch

/** Settings key holding the active AutoEq profile name (or `custom:<name>`) -- cold-start restore only, see [io.github.auxen.AuxenApp]. */
private const val KEY_AUTOEQ_PROFILE = "autoeq_profile"

/** Settings key holding the raw text of an imported custom profile. */
private const val KEY_AUTOEQ_CUSTOM_TEXT = "autoeq_custom_text"

/**
 * Equalizer screen: the DSP suite's home, one expandable [FxSectionCard] per
 * effect, each independently toggleable and expandable (DSP-b Task 3, "no
 * master coupling"). FIRST is "Tune for your headphones" -- the AutoEq
 * profile picker (search the bundled 8,850-headphone database, plus a
 * file-import path for custom profiles), wired to
 * [io.github.auxen.dsp.AutoEqController]. SECOND is "Equalizer" -- the
 * desktop app's 10-band graphic EQ with its presets, wired to
 * [EqController] (AutoEq split, Task 2: these were one combined section
 * until DSP-a/b; splitting them means importing a headphone profile no
 * longer wipes manual graphic EQ edits, and each has its own switch). Both
 * stay inline here rather than extracted composables, since -- unlike the
 * six sections below -- each carries context-dependent complexity
 * (`rememberLauncherForActivityResult`, `Graph.autoEq`, IME/
 * BringIntoViewRequester wiring for AutoEq; nothing reusable for either).
 * The remaining six sections (bass boost, balance, limiter, reverb,
 * virtualizer, volume normalization) are rendered from
 * `io.github.auxen.ui.components.FxSections`, each wired to its own
 * independent [io.github.auxen.dsp.AudioFxController] state flow.
 */
@OptIn(ExperimentalFoundationApi::class)
@UnstableApi
@Composable
fun EqualizerScreen(modifier: Modifier = Modifier) {
    val eqState by EqController.state.collectAsState()
    val autoEqState by AutoEqController.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Graph.autoEq

    var presetMenuOpen by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    // Per-effect section expand/collapse -- independent of each section's OWN
    // enable switch (docs/plans/2026-07-13-android-dsp-b-ui.md, Task 3: "no
    // master coupling"). AutoEq and Equalizer both start expanded (the two
    // primary EQ stages); the newer effects start collapsed.
    // rememberSaveable (not plain remember): survives navigating away from
    // this screen and back, not just recomposition -- a user who expanded
    // Limiter to check a setting shouldn't have it collapse again just from
    // switching tabs (final review round, Minor #11).
    var autoEqExpanded by rememberSaveable { mutableStateOf(true) }
    var eqExpanded by rememberSaveable { mutableStateOf(true) }
    var bassBoostExpanded by rememberSaveable { mutableStateOf(false) }
    var balanceExpanded by rememberSaveable { mutableStateOf(false) }
    var limiterExpanded by rememberSaveable { mutableStateOf(false) }
    var reverbExpanded by rememberSaveable { mutableStateOf(false) }
    var virtualizerExpanded by rememberSaveable { mutableStateOf(false) }
    var volumeNormalizationExpanded by rememberSaveable { mutableStateOf(false) }

    val bassBoostState by AudioFxController.bassBoostState.collectAsState()
    val balanceState by AudioFxController.balanceState.collectAsState()
    val limiterState by AudioFxController.limiterState.collectAsState()
    val reverbState by AudioFxController.reverbState.collectAsState()
    val virtualizerState by AudioFxController.virtualizerState.collectAsState()
    val replayGainState by AudioFxController.replayGainState.collectAsState()

    // AutoEq picker search state. The active-profile display no longer needs
    // its own tracked/synchronized local state (autoEqState.presetName from
    // AutoEqController IS the active profile, always -- unlike before the
    // split, where the same eq_state also held the GRAPHIC preset, so the UI
    // had to track a separate marker and explicitly clear it whenever a
    // graphic action might have clobbered it). Graphic actions (band drags,
    // presets) don't touch AutoEqController at all now, so that
    // synchronization -- and its per-drag-frame spam guard -- is gone.
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AutoEqProfile>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // Keeps the search field + its results scrolled above the IME — see the
    // bringIntoViewRequester() usage on the picker section below.
    val searchSectionBringIntoView = remember { BringIntoViewRequester() }
    var searchFieldFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repo.ensureLoaded()
        loaded = true
    }

    // Recompute matches whenever the query changes or the index finishes
    // loading. search() is an in-memory substring filter — cheap enough for the
    // main thread — and caps at 50 hits.
    LaunchedEffect(query, loaded) {
        results = if (loaded && query.isNotBlank()) repo.search(query, limit = 50) else emptyList()
    }

    // Re-scroll the search section into view once results land while the
    // field is still focused — covers the case where the field itself was
    // already visible above the IME but the results below it were not.
    LaunchedEffect(results, searchFieldFocused) {
        if (searchFieldFocused && results.isNotEmpty()) {
            searchSectionBringIntoView.bringIntoView()
        }
    }

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
            val displayName = name?.takeIf { it.isNotBlank() } ?: "Custom profile"
            AutoEqController.importAutoEq(text, displayName)
                .onSuccess {
                    importError = null
                    // Cold-start restore marker only (io.github.auxen.AuxenApp) --
                    // the live "Active: X" display reads autoEqState.presetName
                    // directly, always in sync, no marker needed for that.
                    scope.launch {
                        Graph.library.setSetting(KEY_AUTOEQ_CUSTOM_TEXT, text)
                        Graph.library.setSetting(KEY_AUTOEQ_PROFILE, "custom:$displayName")
                    }
                }
                .onFailure { importError = it.message }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // "Tune for your headphones" section: the AutoEq picker, wired to its
        // own controller and switch (AutoEq split, Task 2, item 1). FIRST in
        // the list -- correction is meant to be applied before the graphic EQ
        // below it (both stringResource entries added alongside this split).
        FxSectionCard(
            title = stringResource(R.string.autoeq_section_title),
            subtitle = stringResource(R.string.autoeq_section_subtitle),
            enabled = autoEqState.enabled,
            onEnabledChange = { AutoEqController.setEnabled(it) },
            expanded = autoEqExpanded,
            onExpandedChange = { autoEqExpanded = it },
        ) {
            Text(
                "Corrections for 8,850 headphones, tuned to a neutral reference.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier.bringIntoViewRequester(searchSectionBringIntoView),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            searchFieldFocused = focusState.isFocused
                            if (focusState.isFocused) {
                                scope.launch { searchSectionBringIntoView.bringIntoView() }
                            }
                        },
                    label = { Text("Find your headphone model") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                )

                AutoEqPickerResults(
                    activeProfile = autoEqState.presetName,
                    results = results,
                    noMatches = query.isNotBlank() && loaded && results.isEmpty(),
                    onSelectProfile = { profile ->
                        applyAutoEq(scope, repo, profile)
                        query = ""
                    },
                    onClearActive = {
                        AutoEqController.clear()
                        scope.launch { Graph.library.setSetting(KEY_AUTOEQ_PROFILE, "") }
                    },
                )
            }

            // Secondary action (outlined), not a filled amber CTA -- importing a
            // custom profile is the exception, the bundled database is the path.
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/plain")) }) {
                Text("Import custom profile…")
            }

            importError?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null)
                        Text("Import failed: $it", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Text(
                "Powered by AutoEq (MIT) — github.com/jaakkopasanen/AutoEq",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // "Equalizer" section: the 10-band graphic EQ + presets, wired to its
        // own controller and switch. No picker content -- AutoEq split, Task
        // 2, item 2.
        FxSectionCard(
            title = stringResource(R.string.fx_equalizer_title),
            subtitle = null,
            enabled = eqState.enabled,
            onEnabledChange = { EqController.setEnabled(it) },
            expanded = eqExpanded,
            onExpandedChange = { eqExpanded = it },
        ) {
            eqState.presetName?.let {
                Text("Active profile: $it", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Preamp: %.1f dB".format(eqState.preampDb),
                style = MaterialTheme.typography.bodySmall,
            )

            Row {
                Column {
                    // Reads as a menu trigger, not a plain button: a "Presets"
                    // label with a trailing dropdown caret at a stable min width.
                    OutlinedButton(
                        onClick = { presetMenuOpen = true },
                        modifier = Modifier.widthIn(min = 180.dp),
                    ) {
                        Text("Presets", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
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
            }

            // 10-band graphic EQ. Each band takes an equal weighted slice of the
            // row width so all ten fit on a phone and distribute evenly on a
            // tablet -- no hidden horizontal scroll, no fixed per-band width.
            val bands = eqState.bands ?: List(EqState.NUM_BANDS) { 0.0 }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                bands.forEachIndexed { index, gain ->
                    BandSlider(
                        label = EqState.BAND_LABELS[index],
                        gainDb = gain,
                        onChange = { EqController.setBand(index, it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        BassBoostSection(
            state = bassBoostState,
            onStateChange = { AudioFxController.updateBassBoost(it) },
            expanded = bassBoostExpanded,
            onExpandedChange = { bassBoostExpanded = it },
        )

        BalanceSection(
            state = balanceState,
            onStateChange = { AudioFxController.updateBalance(it) },
            expanded = balanceExpanded,
            onExpandedChange = { balanceExpanded = it },
        )

        LimiterSection(
            state = limiterState,
            onStateChange = { AudioFxController.updateLimiter(it) },
            expanded = limiterExpanded,
            onExpandedChange = { limiterExpanded = it },
        )

        ReverbSection(
            state = reverbState,
            onStateChange = { AudioFxController.updateReverb(it) },
            expanded = reverbExpanded,
            onExpandedChange = { reverbExpanded = it },
        )

        VirtualizerSection(
            state = virtualizerState,
            onStateChange = { AudioFxController.updateVirtualizer(it) },
            expanded = virtualizerExpanded,
            onExpandedChange = { virtualizerExpanded = it },
        )

        VolumeNormalizationSection(
            state = replayGainState,
            onStateChange = { AudioFxController.updateReplayGain(it) },
            expanded = volumeNormalizationExpanded,
            onExpandedChange = { volumeNormalizationExpanded = it },
        )
    }
}

/**
 * Apply a bundled AutoEq profile: read its ParametricEq body (IO), feed it
 * through [AutoEqController.importAutoEq], and persist the name for restore-on-start
 * (cold-start marker only -- the live "Active: X" display reads
 * `AutoEqController.state.value.presetName` directly, always in sync).
 * runCatching keeps a corrupt/missing zip entry (profileText throws) from
 * crashing the composition scope — matching the import/restore paths.
 */
@UnstableApi
private fun applyAutoEq(
    scope: kotlinx.coroutines.CoroutineScope,
    repo: io.github.auxen.dsp.AutoEqRepository,
    profile: AutoEqProfile,
) {
    scope.launch {
        runCatching {
            val text = repo.profileText(profile)
            AutoEqController.importAutoEq(text, profile.name).onSuccess {
                Graph.library.setSetting(KEY_AUTOEQ_PROFILE, profile.name)
            }
        }
    }
}

/**
 * Stateless body of the AutoEq picker's active-profile row and search
 * results list — the part of [EqualizerScreen]'s picker section with no
 * dependency on a live [io.github.auxen.dsp.AutoEqRepository] or [Graph]
 * settings I/O.
 *
 * Extracted from [EqualizerScreen] (same pattern as
 * [io.github.auxen.ui.components.TrackActionSheetContent]) so
 * `ComponentScreenshotTest` can golden it with a FAKE result list rather
 * than exercising the bundled 8,850-profile asset, which would make the
 * goldens depend on database contents.
 *
 * [noMatches] is computed by the caller (`query.isNotBlank() && loaded &&
 * results.isEmpty()`) rather than inferred here, since an empty [results]
 * list is also the steady state before the user has typed anything.
 */
@Composable
internal fun AutoEqPickerResults(
    activeProfile: String?,
    results: List<AutoEqProfile>,
    noMatches: Boolean,
    onSelectProfile: (AutoEqProfile) -> Unit,
    onClearActive: () -> Unit,
) {
    // A single wrapping Column: emits ONE vertical stack rather than sibling
    // nodes, so a single-slot host (Material's Surface Box in the screenshot
    // preview) lays the active row and results out stacked instead of
    // overlapping. Plain rows -- NOT a LazyColumn: this renders inside
    // EqualizerScreen's page-level verticalScroll, where a nested same-axis
    // LazyColumn needed a fixed height cap (heightIn(max = 240.dp)) that showed
    // only ~4-5 matches and fought the outer scroll. Emitting rows straight
    // into the page scroll lets the whole result set scroll with it; the
    // 50-match cap + hint below bound the length.
    Column(modifier = Modifier.fillMaxWidth()) {
        activeProfile?.let { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Active: $name",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearActive) {
                    Text("Clear")
                }
            }
        }

        if (noMatches) {
            Text(
                "No matching profiles",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (results.isNotEmpty()) {
            results.forEach { profile ->
                ProfileRow(profile) { onSelectProfile(profile) }
            }
            if (results.size >= 50) {
                Text(
                    "Showing the first 50 matches — refine your search.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One search-result row: profile name over a `source · rig` subtitle. */
@Composable
private fun ProfileRow(profile: AutoEqProfile, onClick: () -> Unit) {
    val subtitle = listOf(profile.source, profile.rig)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Subtitle-less rows are ~35dp otherwise -- below the 48dp minimum
            // touch target. heightIn floors the tappable area; Center keeps the
            // single line vertically centered within it.
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(profile.name, style = MaterialTheme.typography.bodyLarge)
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One vertical gain slider for the graphic EQ's 10-band row. `internal` (not
 * `private`) so `ComponentScreenshotTest` can golden the "Equalizer" section
 * with its real band sliders rather than a hand-drawn approximation — same
 * reasoning as [AutoEqPickerResults]'s extraction.
 */
@Composable
internal fun BandSlider(
    label: String,
    gainDb: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%.0f".format(gainDb), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        VerticalSlider(
            value = gainDb.toFloat(),
            onChange = { onChange(it.toDouble()) },
            valueRange = EqState.MIN_GAIN_DB.toFloat()..EqState.MAX_GAIN_DB.toFloat(),
            // Fills its (weighted) cell; the widthIn floor only bites when width
            // is unbounded -- e.g. a horizontally scrolling preview -- keeping
            // the thumb a sane size instead of collapsing to zero there.
            modifier = Modifier.height(180.dp).widthIn(min = 40.dp).fillMaxWidth(),
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
