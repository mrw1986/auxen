package io.github.auxen.ui

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import io.github.auxen.R

/**
 * The go/no-go streaming spike's entry point (Tidal official-API
 * migration, Task 1): official-API PKCE login (Custom Tab, RFC 8252) and
 * the streaming probe, with the visible status readout the whole spike
 * exists to produce. Reachable only via Settings → Advanced → "Try
 * official Tidal login (beta)" -- deliberately not surfaced anywhere a
 * user would stumble into it, since it's additive and doesn't affect the
 * existing, working Tidal login on Account.
 */
@UnstableApi
@Composable
fun TidalOfficialDebugScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val status by viewModel.tidalOfficialStatus.collectAsState()
    val context = LocalContext.current
    var trackId by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.tidal_official_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.tidal_official_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = {
            val url = viewModel.beginTidalOfficialLogin()
            CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
        }) {
            Text(stringResource(R.string.tidal_official_login_button))
        }

        HorizontalDivider()

        OutlinedTextField(
            value = trackId,
            onValueChange = { trackId = it },
            label = { Text(stringResource(R.string.tidal_official_track_id_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.runTidalOfficialStreamingProbe(trackId.trim()) },
            enabled = trackId.isNotBlank(),
        ) {
            Text(stringResource(R.string.tidal_official_probe_button))
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
