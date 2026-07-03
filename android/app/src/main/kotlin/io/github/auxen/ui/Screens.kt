package io.github.auxen.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import io.github.auxen.model.Source
import io.github.auxen.model.Track

@UnstableApi
@Composable
fun LibraryScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val tracks by viewModel.localTracks.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadLibrary() }

    if (tracks.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No local music found", style = MaterialTheme.typography.titleMedium)
            Text(
                "Grant the audio permission or add music to your device.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(tracks, key = { "${it.source}:${it.sourceId}" }) { track ->
                TrackRow(track, onPlay = { viewModel.play(track) }, onEnqueue = { viewModel.enqueue(track) })
            }
        }
    }
}

@UnstableApi
@Composable
fun SearchScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            label = { Text("Search local + Tidal") },
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = { viewModel.search(query) }) { Text("Go") }
            },
        )
        if (viewModel.searchInFlight) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results, key = { "${it.source}:${it.sourceId}" }) { track ->
                TrackRow(track, onPlay = { viewModel.play(track) }, onEnqueue = { viewModel.enqueue(track) })
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, onPlay: () -> Unit, onEnqueue: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.albumArtUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track.artist, track.album).joinToString(" — "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        AssistChip(
            onClick = {},
            label = { Text(if (track.source == Source.TIDAL) track.qualityLabel else "Local") },
        )
        IconButton(onClick = onEnqueue) {
            Icon(Icons.Filled.PlaylistAdd, contentDescription = "Add to queue")
        }
    }
}

@UnstableApi
@Composable
fun NowPlayingBar(viewModel: PlayerViewModel) {
    val metadata = viewModel.nowPlaying ?: return
    val title = metadata.title?.toString() ?: return

    Surface(tonalElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(model = metadata.artworkUri, contentDescription = null, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                metadata.artist?.let {
                    Text(it.toString(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { viewModel.togglePlayPause() }) {
                Icon(
                    if (viewModel.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (viewModel.isPlaying) "Pause" else "Play",
                )
            }
        }
    }
}

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
