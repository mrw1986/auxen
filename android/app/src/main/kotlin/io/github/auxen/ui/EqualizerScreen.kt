package io.github.auxen.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.Graph
import io.github.auxen.dsp.AutoEqProfile
import io.github.auxen.dsp.EqController
import io.github.auxen.dsp.EqState
import kotlinx.coroutines.launch

/** Settings key holding the active AutoEq profile name (or `custom:<name>`). */
private const val KEY_AUTOEQ_PROFILE = "autoeq_profile"

/** Settings key holding the raw text of an imported custom profile. */
private const val KEY_AUTOEQ_CUSTOM_TEXT = "autoeq_custom_text"

/**
 * Equalizer screen: enable toggle, the desktop app's 10-band graphic EQ with
 * its presets, and a Wavelet-style AutoEq profile picker (search the bundled
 * 8,850-headphone database, plus a file-import path for custom profiles).
 */
@UnstableApi
@Composable
fun EqualizerScreen(modifier: Modifier = Modifier) {
    val state by EqController.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Graph.autoEq

    var presetMenuOpen by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    // AutoEq picker state.
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AutoEqProfile>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var activeProfile by remember { mutableStateOf<String?>(null) }

    // Parse the ~1 MB index once (off the main thread) and read back the
    // persisted active-profile name. ensureLoaded() has a ~3s cold path, so it
    // must not block composition — LaunchedEffect suspends onto the IO
    // dispatcher inside the repository.
    LaunchedEffect(Unit) {
        repo.ensureLoaded()
        loaded = true
        activeProfile = Graph.library.getSetting(KEY_AUTOEQ_PROFILE).toActiveProfileName()
    }

    // Recompute matches whenever the query changes or the index finishes
    // loading. search() is an in-memory substring filter — cheap enough for the
    // main thread — and caps at 50 hits.
    LaunchedEffect(query, loaded) {
        results = if (loaded && query.isNotBlank()) repo.search(query, limit = 50) else emptyList()
    }

    // Switching to graphic mode (touching a band or applying a preset)
    // abandons the AutoEq correction, so the marker must go too — otherwise
    // "Active: <name>" lingers next to the graphic preset label and, worse,
    // restore-on-start re-applies the old profile over the user's graphic
    // edits at next launch. autoeq_custom_text is deliberately kept: a stored
    // custom profile stays re-importable. Guarded so slider drags (which fire
    // per-frame) don't spam DB writes.
    fun clearActiveProfileMarker() {
        if (activeProfile == null) return
        activeProfile = null
        scope.launch { Graph.library.setSetting(KEY_AUTOEQ_PROFILE, "") }
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
            EqController.importAutoEq(text, displayName)
                .onSuccess {
                    importError = null
                    activeProfile = displayName
                    scope.launch {
                        Graph.library.setSetting(KEY_AUTOEQ_CUSTOM_TEXT, text)
                        Graph.library.setSetting(KEY_AUTOEQ_PROFILE, "custom:$displayName")
                    }
                }
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

        // Only the graphic EQ names a preset here; the active AutoEq profile
        // (parametric, bands == null) is shown by its own row below.
        if (state.bands != null) {
            state.presetName?.let {
                Text("Active profile: $it", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "Preamp: %.1f dB".format(state.preampDb),
            style = MaterialTheme.typography.bodySmall,
        )

        Row {
            Column {
                OutlinedButton(onClick = { presetMenuOpen = true }) { Text("Presets") }
                DropdownMenu(expanded = presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
                    EqState.PRESETS.keys.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                EqController.applyPreset(name)
                                clearActiveProfileMarker()
                                presetMenuOpen = false
                            },
                        )
                    }
                }
            }
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
                    onChange = {
                        EqController.setBand(index, it)
                        clearActiveProfileMarker()
                    },
                )
            }
        }

        HorizontalDivider()

        // ---- AutoEq headphone-correction picker (Wavelet-style) ----
        Text("Headphone correction", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search 8,850 headphone profiles") },
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
            activeProfile = activeProfile,
            results = results,
            noMatches = query.isNotBlank() && loaded && results.isEmpty(),
            onSelectProfile = { profile ->
                applyAutoEq(scope, repo, profile) { activeProfile = it }
                query = ""
            },
            onClearActive = {
                EqController.applyPreset("Flat")
                clearActiveProfileMarker()
            },
        )

        Button(onClick = { importLauncher.launch(arrayOf("text/plain")) }) {
            Text("Import custom profile…")
        }

        importError?.let {
            Text("Import failed: $it", color = MaterialTheme.colorScheme.error)
        }

        Text(
            "Powered by AutoEq (MIT) — github.com/jaakkopasanen/AutoEq",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Turns a stored `autoeq_profile` value into its display name (drops `custom:`). */
private fun String?.toActiveProfileName(): String? = when {
    isNullOrBlank() -> null
    startsWith("custom:") -> removePrefix("custom:")
    else -> this
}

/**
 * Apply a bundled AutoEq profile: read its ParametricEq body (IO), feed it
 * through [EqController.importAutoEq], and persist the name for restore-on-start.
 * runCatching keeps a corrupt/missing zip entry (profileText throws) from
 * crashing the composition scope — matching the import/restore paths.
 */
@UnstableApi
private fun applyAutoEq(
    scope: kotlinx.coroutines.CoroutineScope,
    repo: io.github.auxen.dsp.AutoEqRepository,
    profile: AutoEqProfile,
    onApplied: (String) -> Unit,
) {
    scope.launch {
        runCatching {
            val text = repo.profileText(profile)
            EqController.importAutoEq(text, profile.name).onSuccess {
                Graph.library.setSetting(KEY_AUTOEQ_PROFILE, profile.name)
                onApplied(profile.name)
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
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            items(results, key = { it.index }) { profile ->
                ProfileRow(profile) { onSelectProfile(profile) }
            }
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

/** One search-result row: profile name over a `source · rig` subtitle. */
@Composable
private fun ProfileRow(profile: AutoEqProfile, onClick: () -> Unit) {
    val subtitle = listOf(profile.source, profile.rig)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
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
