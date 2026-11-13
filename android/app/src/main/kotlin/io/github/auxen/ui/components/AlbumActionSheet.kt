package io.github.auxen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import io.github.auxen.ui.theme.AuxenColors

/**
 * Album long-press action sheet — the mobile substitute for the desktop
 * AlbumContextMenu. Renders [albumActions] (Task 6) and reuses [SheetAction] +
 * the same playlist-picker + New-playlist dialog flow as [TrackActionSheet].
 *
 * [onGoToArtist] is optional: a host that cannot navigate to an artist omits it
 * and [albumActions] drops the "Go to artist" row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumActionSheet(
    albumTitle: String,
    albumArtist: String?,
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onGoToArtist: (() -> Unit)? = null,
) {
    var showPlaylists by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AlbumActionSheetContent(
            albumTitle = albumTitle,
            albumArtist = albumArtist,
            playlists = playlists,
            showPlaylists = showPlaylists,
            onPlay = { onPlay(); onDismiss() },
            onShuffle = { onShuffle(); onDismiss() },
            onPlayNext = { onPlayNext(); onDismiss() },
            onEnqueue = { onEnqueue(); onDismiss() },
            onAddToPlaylist = { id -> onAddToPlaylist(id); onDismiss() },
            onShowPlaylists = { showPlaylists = true },
            onNewPlaylist = { showNameDialog = true },
            // Dismiss FIRST, then navigate: an open ModalBottomSheet contending
            // with a screen transition is the same hazard TrackActionSheet documents.
            onGoToArtist = onGoToArtist?.let { { it(); onDismiss() } },
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

/** Stateless body — split out for the same Robolectric reason as
 *  [TrackActionSheetContent] (the [ModalBottomSheet] window never settles). */
@Composable
internal fun AlbumActionSheetContent(
    albumTitle: String,
    albumArtist: String?,
    playlists: List<PlaylistEntity>,
    showPlaylists: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onShowPlaylists: () -> Unit,
    onNewPlaylist: () -> Unit,
    onGoToArtist: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            albumTitle,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        if (!albumArtist.isNullOrBlank()) {
            Text(
                albumArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (!showPlaylists) {
            albumActions(albumArtist = albumArtist, canGoToArtist = onGoToArtist != null).forEach { entry ->
                when (entry.id) {
                    AlbumActionId.PLAY ->
                        SheetAction(entry.label, { Icon(Icons.Filled.PlayArrow, null) }) { onPlay() }
                    AlbumActionId.SHUFFLE ->
                        SheetAction(entry.label, { Icon(Icons.Filled.Shuffle, null) }) { onShuffle() }
                    AlbumActionId.PLAY_NEXT ->
                        SheetAction(entry.label, { Icon(Icons.Filled.SkipNext, null) }) { onPlayNext() }
                    AlbumActionId.ENQUEUE ->
                        SheetAction(entry.label, { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }) { onEnqueue() }
                    AlbumActionId.ADD_TO_PLAYLIST ->
                        SheetAction(entry.label, { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }) { onShowPlaylists() }
                    AlbumActionId.GO_TO_ARTIST ->
                        SheetAction(entry.label, { Icon(Icons.Filled.Person, null) }) { onGoToArtist?.invoke() }
                }
            }
        } else {
            SheetAction("New playlist…", { Icon(Icons.Filled.Add, null) }) { onNewPlaylist() }
            playlists.forEach { playlist ->
                SheetAction(
                    playlist.name,
                    {
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
