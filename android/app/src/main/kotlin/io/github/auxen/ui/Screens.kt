package io.github.auxen.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.ui.components.BrandBlock

@UnstableApi
@Composable
fun AccountScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val loginState by viewModel.tidalLogin.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandBlock()
        Text("Tidal", style = MaterialTheme.typography.headlineSmall)
        when (val state = loginState) {
            is TidalLoginState.LoggedOut -> {
                Text("Connect your Tidal account to stream in lossless and Hi-Res quality.")
                Button(onClick = { viewModel.startTidalLogin() }) { Text("Log in to Tidal") }
            }
            is TidalLoginState.AwaitingApproval -> {
                Text("Approve this device in your browser:")
                Text(state.verificationUrl, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.verificationUrl)))
                }) { Text("Open browser") }
            }
            is TidalLoginState.LoggedIn -> {
                Text("Logged in ✓", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { viewModel.tidalLogout() }) { Text("Log out") }
            }
            is TidalLoginState.Error -> {
                Text("Login failed: ${state.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = { viewModel.startTidalLogin() }) { Text("Try again") }
            }
        }
    }
}
