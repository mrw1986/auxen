package io.github.auxen.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.auxen.R
import io.github.auxen.playback.cache.CACHE_LIMIT_PRESETS
import io.github.auxen.playback.cache.CacheNetworkMode
import io.github.auxen.playback.cache.CacheSettings
import io.github.auxen.playback.cache.formatLimitLabel

/**
 * The Settings "Offline cache" card: which networks may cache upcoming Tidal songs, how much
 * storage they may use, the current usage, and a Clear button that asks first.
 */
@Composable
fun OfflineCacheSection(
    settings: CacheSettings,
    readout: String,
    onModeChange: (CacheNetworkMode) -> Unit,
    onLimitChange: (Long) -> Unit,
    onClear: () -> Unit,
) {
    var confirmOpen by rememberSaveable { mutableStateOf(false) }

    SettingsSectionCard(title = stringResource(R.string.settings_offline_cache_title)) {
        Text(stringResource(R.string.settings_cache_over_label), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            CacheNetworkMode.entries.forEach { mode ->
                CacheRadioRow(
                    label = cacheModeLabel(mode),
                    selected = settings.mode == mode,
                    onSelect = { onModeChange(mode) },
                )
            }
        }

        Text(stringResource(R.string.settings_cache_limit_label), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            CACHE_LIMIT_PRESETS.forEach { bytes ->
                CacheRadioRow(
                    label = formatLimitLabel(bytes),
                    selected = settings.limitBytes == bytes,
                    onSelect = { onLimitChange(bytes) },
                )
            }
        }

        Text(
            readout,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { confirmOpen = true }) {
            Text(stringResource(R.string.settings_cache_clear))
        }
    }

    if (confirmOpen) {
        ClearCacheDialog(
            onConfirm = { onClear(); confirmOpen = false },
            onDismiss = { confirmOpen = false },
        )
    }
}

@Composable
private fun ClearCacheDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_cache_clear_confirm_title)) },
        text = { Text(stringResource(R.string.settings_cache_clear_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_cache_clear_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cache_clear_cancel)) }
        },
    )
}

/** One radio row; the whole row is the tap target, like `SourcePriorityRow`. */
@Composable
private fun CacheRadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick = null: the Row's selectable() owns the click and the merged a11y node.
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun cacheModeLabel(mode: CacheNetworkMode): String = when (mode) {
    CacheNetworkMode.WIFI_AND_MOBILE -> stringResource(R.string.settings_cache_mode_wifi_and_mobile)
    CacheNetworkMode.WIFI_ONLY -> stringResource(R.string.settings_cache_mode_wifi_only)
    CacheNetworkMode.OFF -> stringResource(R.string.settings_cache_mode_off)
}
