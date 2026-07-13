package io.github.auxen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.auxen.BuildConfig
import io.github.auxen.R
import io.github.auxen.model.SourcePriority
import io.github.auxen.ui.components.BrandBlock
import io.github.auxen.ui.theme.ThemeMode

/**
 * Settings screen (Desktop-Parity Screens, sub-batch A, Task 1): the two
 * genuinely-missing preferences from the desktop app's settings dialog
 * (`auxen/views/settings.py`) that have no Tidal-API dependency -- theme
 * mode and source priority -- plus an About group. Subscription/audio-
 * quality settings are deferred to sub-batch B.
 *
 * Thin VM-collecting shell over [SettingsContent], which does the actual
 * rendering from plain params -- same "stateless content composable" split
 * as `AutoEqPickerResults`/`TrackActionSheetContent`, so goldens can render
 * it without a live [PlayerViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val themeMode by viewModel.themeMode.collectAsState()
    val sourcePriority by viewModel.sourcePriority.collectAsState()
    SettingsContent(
        themeMode = themeMode,
        onThemeModeChange = viewModel::setThemeMode,
        sourcePriority = sourcePriority,
        onSourcePriorityChange = viewModel::setSourcePriority,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    sourcePriority: SourcePriority,
    onSourcePriorityChange: (SourcePriority) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SettingsSectionCard(title = stringResource(R.string.settings_appearance_title)) {
            Text(stringResource(R.string.settings_theme_mode_label), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_theme_mode_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    ) {
                        Text(themeModeLabel(mode))
                    }
                }
            }
        }

        SettingsSectionCard(title = stringResource(R.string.settings_playback_title)) {
            Text(stringResource(R.string.settings_source_priority_label), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_source_priority_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup()) {
                SourcePriority.entries.forEach { priority ->
                    SourcePriorityRow(
                        priority = priority,
                        selected = sourcePriority == priority,
                        onSelect = { onSourcePriorityChange(priority) },
                    )
                }
            }
        }

        SettingsSectionCard(title = stringResource(R.string.settings_about_title)) {
            BrandBlock()
            Text(stringResource(R.string.settings_about_blurb), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A titled [Card] grouping related settings — the "lighter" sibling of [io.github.auxen.ui.components.FxSectionCard] with no switch/expand affordance, since every group here is always visible. */
@Composable
private fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
}

@Composable
private fun sourcePriorityLabel(priority: SourcePriority): String = when (priority) {
    SourcePriority.PREFER_LOCAL -> stringResource(R.string.settings_source_priority_prefer_local)
    SourcePriority.PREFER_TIDAL -> stringResource(R.string.settings_source_priority_prefer_tidal)
    SourcePriority.PREFER_QUALITY -> stringResource(R.string.settings_source_priority_prefer_quality)
    SourcePriority.ALWAYS_ASK -> stringResource(R.string.settings_source_priority_always_ask)
}

@Composable
private fun sourcePriorityDescription(priority: SourcePriority): String = when (priority) {
    SourcePriority.PREFER_LOCAL -> stringResource(R.string.settings_source_priority_prefer_local_desc)
    SourcePriority.PREFER_TIDAL -> stringResource(R.string.settings_source_priority_prefer_tidal_desc)
    SourcePriority.PREFER_QUALITY -> stringResource(R.string.settings_source_priority_prefer_quality_desc)
    SourcePriority.ALWAYS_ASK -> stringResource(R.string.settings_source_priority_always_ask_desc)
}

/** One radio row: title + one-line explanation, the whole row is the tap target. */
@Composable
private fun SourcePriorityRow(priority: SourcePriority, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick = null: the enclosing Row's selectable() owns the click and
        // the merged a11y node (standard Compose radio-group pattern) --
        // a non-null onClick here would register a second, redundant target.
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(sourcePriorityLabel(priority), style = MaterialTheme.typography.bodyLarge)
            Text(
                sourcePriorityDescription(priority),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
