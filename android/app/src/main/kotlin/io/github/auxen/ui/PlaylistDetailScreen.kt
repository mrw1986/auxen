package io.github.auxen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.R
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import io.github.auxen.ui.theme.AuxenColors

/** Desktop PLAYLIST_COLORS — the 8-swatch picker palette. */
val PLAYLIST_COLORS = listOf(
    "#d4a039", "#00c4cc", "#7cb87a", "#9b59b6",
    "#e74c3c", "#3498db", "#e67e22", "#1abc9c",
)

private fun parseColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#d4a039")) }
        .getOrDefault(AuxenColors.AmberPrimary)

/**
 * Playlist detail — desktop PlaylistView: color-dot header, Play All/Shuffle,
 * rename/recolor/delete, and per-row remove + move up/down reordering.
 */
@UnstableApi
@Composable
fun PlaylistDetailScreen(
    viewModel: PlayerViewModel,
    playlistId: Long,
    onBack: () -> Unit,
) {
    val tracks by viewModel.playlistTracks.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    var showRename by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    LaunchedEffect(playlistId) { viewModel.loadPlaylist(playlistId) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Box(Modifier.size(16.dp).background(parseColor(playlist?.color), CircleShape))
            Text(
                playlist?.name ?: "Playlist",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            IconButton(onClick = { renameText = playlist?.name.orEmpty(); showRename = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename")
            }
            IconButton(onClick = { showColors = true }) {
                Icon(Icons.Filled.Palette, contentDescription = "Change color")
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete playlist", tint = MaterialTheme.colorScheme.error)
            }
        }
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(
                onClick = { viewModel.playAll(tracks) },
                enabled = tracks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AuxenColors.AmberPrimary,
                    contentColor = AuxenColors.BgDeep,
                ),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("Play All")
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { viewModel.playAll(tracks, shuffled = true) }, enabled = tracks.isNotEmpty()) {
                Icon(Icons.Filled.Shuffle, contentDescription = null)
                Text("Shuffle")
            }
        }
        if (tracks.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.QueueMusic,
                title = stringResource(R.string.empty_playlist_title),
                subtitle = stringResource(R.string.empty_playlist_subtitle),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(tracks, key = { i, t -> "$i|${t.source}:${t.sourceId}" }) { index, track ->
                    AuxenTrackRow(
                        track = track,
                        isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                        onPlay = { viewModel.play(track) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        trailing = {
                            IconButton(
                                onClick = { viewModel.movePlaylistTrack(playlistId, index, index - 1) },
                                enabled = index > 0,
                            ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up") }
                            IconButton(
                                onClick = { viewModel.movePlaylistTrack(playlistId, index, index + 1) },
                                enabled = index < tracks.size - 1,
                            ) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down") }
                            IconButton(onClick = { viewModel.removeFromPlaylist(playlistId, track) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove from playlist")
                            }
                        },
                    )
                }
            }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) viewModel.renamePlaylist(playlistId, renameText.trim())
                        showRename = false
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
    if (showColors) {
        AlertDialog(
            onDismissRequest = { showColors = false },
            title = { Text("Playlist color") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PLAYLIST_COLORS.forEach { hex ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(parseColor(hex), CircleShape)
                                .clickable {
                                    viewModel.recolorPlaylist(playlistId, hex)
                                    showColors = false
                                },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showColors = false }) { Text("Cancel") } },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete playlist?") },
            text = { Text("\"${playlist?.name.orEmpty()}\" will be deleted. Tracks stay in your library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(playlistId)
                        showDeleteConfirm = false
                        onBack()
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}
