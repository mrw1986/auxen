package io.github.auxen.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.TrackActionSheet

@UnstableApi
@Composable
fun AccountScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val loginState by viewModel.tidalLogin.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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

@UnstableApi
@Composable
fun FavoritesScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val tracks by viewModel.favorites.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var sheetTrack by remember { mutableStateOf<Track?>(null) }

    if (tracks.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No favorites yet", style = MaterialTheme.typography.titleMedium)
            Text("Tap the heart on any track to add it here.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(tracks, key = { "${it.source}:${it.sourceId}" }) { track ->
                AuxenTrackRow(
                    track = track,
                    isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                    onPlay = { viewModel.play(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                    onLongPress = { sheetTrack = track },
                    trailing = {
                        IconButton(onClick = { viewModel.enqueue(track) }) {
                            Icon(Icons.Filled.PlaylistAdd, contentDescription = "Add to queue")
                        }
                    },
                )
            }
        }
    }

    sheetTrack?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
            playlists = playlists,
            onDismiss = { sheetTrack = null },
            onPlay = { viewModel.play(track) },
            onPlayNext = { viewModel.playNext(track) },
            onEnqueue = { viewModel.enqueue(track) },
            onToggleFavorite = { viewModel.toggleFavorite(track) },
            onAddToPlaylist = { viewModel.addToPlaylist(track, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAdd(track, it) },
        )
    }
}
