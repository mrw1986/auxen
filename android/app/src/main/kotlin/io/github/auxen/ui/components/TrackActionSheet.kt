package io.github.auxen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.model.Track
import io.github.auxen.ui.theme.AuxenColors

/**
 * Long-press action sheet — the mobile substitute for the desktop
 * TrackContextMenu (Play / Play Next / Add to Queue / Favorite /
 * Add to Playlist / New Playlist).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionSheet(
    track: Track,
    isFavorite: Boolean,
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    var showPlaylists by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (!showPlaylists) {
                SheetAction("Play", { Icon(Icons.Filled.PlayArrow, null) }) { onPlay(); onDismiss() }
                SheetAction("Play next", { Icon(Icons.Filled.SkipNext, null) }) { onPlayNext(); onDismiss() }
                SheetAction("Add to queue", { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }) { onEnqueue(); onDismiss() }
                SheetAction(
                    if (isFavorite) "Remove from favorites" else "Add to favorites",
                    {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            null,
                            tint = if (isFavorite) AuxenColors.FavoriteRed else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                ) { onToggleFavorite(); onDismiss() }
                SheetAction("Add to playlist", { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }) { showPlaylists = true }
            } else {
                SheetAction("New playlist…", { Icon(Icons.Filled.Add, null) }) { showNameDialog = true }
                playlists.forEach { playlist ->
                    SheetAction(
                        playlist.name,
                        {
                            Box(
                                Modifier.size(12.dp).background(
                                    runCatching { Color(android.graphics.Color.parseColor(playlist.color ?: "#d4a039")) }
                                        .getOrDefault(AuxenColors.AmberPrimary),
                                    CircleShape,
                                ),
                            )
                        },
                    ) { onAddToPlaylist(playlist.id); onDismiss() }
                }
            }
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onCreatePlaylist(newName.trim())
                            showNameDialog = false
                            onDismiss()
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetAction(label: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = leading,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).clickable(onClick = onClick),
    )
}
