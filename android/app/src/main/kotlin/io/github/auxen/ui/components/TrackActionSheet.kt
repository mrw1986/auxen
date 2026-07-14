package io.github.auxen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.rememberModalBottomSheetState
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        TrackActionSheetContent(
            track = track,
            isFavorite = isFavorite,
            playlists = playlists,
            showPlaylists = showPlaylists,
            onPlay = { onPlay(); onDismiss() },
            onPlayNext = { onPlayNext(); onDismiss() },
            onEnqueue = { onEnqueue(); onDismiss() },
            onToggleFavorite = { onToggleFavorite(); onDismiss() },
            onAddToPlaylist = { id -> onAddToPlaylist(id); onDismiss() },
            onShowPlaylists = { showPlaylists = true },
            onNewPlaylist = { showNameDialog = true },
        )
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

/**
 * Stateless body of [TrackActionSheet] — the sheet's scrollable action list
 * (track header + either the root actions or the playlist-picker page).
 *
 * Extracted from [TrackActionSheet] so screenshot goldens can render it
 * directly: the Material 3 [ModalBottomSheet] wrapper hosts its content in a
 * *separate* window whose animated entrance never settles under Robolectric,
 * so `onRoot().captureRoboImage(...)` can't capture it deterministically. This
 * plain [Column] renders in the main composition and captures reproducibly.
 * [TrackActionSheet] threads its dismiss/toggle callbacks into the [onPlay]…
 * lambdas here, so behavior is identical to the inlined version.
 */
@Composable
internal fun TrackActionSheetContent(
    track: Track,
    isFavorite: Boolean,
    playlists: List<PlaylistEntity>,
    showPlaylists: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onShowPlaylists: () -> Unit,
    onNewPlaylist: () -> Unit,
) {
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
            SheetAction("Play", { Icon(Icons.Filled.PlayArrow, null) }) { onPlay() }
            SheetAction("Play next", { Icon(Icons.Filled.SkipNext, null) }) { onPlayNext() }
            SheetAction("Add to queue", { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }) { onEnqueue() }
            SheetAction(
                if (isFavorite) "Remove from favorites" else "Add to favorites",
                {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        null,
                        tint = if (isFavorite) AuxenColors.FavoriteRed else MaterialTheme.colorScheme.onSurface,
                    )
                },
            ) { onToggleFavorite() }
            SheetAction("Add to playlist", { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }) { onShowPlaylists() }
        } else {
            SheetAction("New playlist…", { Icon(Icons.Filled.Add, null) }) { onNewPlaylist() }
            playlists.forEach { playlist ->
                SheetAction(
                    playlist.name,
                    {
                        // Parse once per color (not every recomposition); fall back
                        // to the brand token, never a drifting literal. The 1dp
                        // outline keeps pale user colors visible on the white
                        // light-theme surface (polish P1, Fix 3).
                        val dotColor = remember(playlist.color) {
                            playlist.color
                                ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                                ?: AuxenColors.AmberPrimary
                        }
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(dotColor, CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                    },
                ) { onAddToPlaylist(playlist.id) }
            }
        }
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
