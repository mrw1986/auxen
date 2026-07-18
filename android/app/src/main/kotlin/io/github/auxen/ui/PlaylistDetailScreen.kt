package io.github.auxen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.R
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import io.github.auxen.ui.theme.AuxenColors
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Desktop PLAYLIST_COLORS — the 8-swatch picker palette. */
val PLAYLIST_COLORS = listOf(
    "#d4a039", "#00c4cc", "#7cb87a", "#9b59b6",
    "#e74c3c", "#3498db", "#e67e22", "#1abc9c",
)

/**
 * A playlist row's drag-stable identity: its ORIGINAL position in the loaded
 * playlist, assigned once and carried with the row as the optimistic order
 * mutates during a drag — the role QueueEntry.id plays in QueueScreen. A
 * playlist can repeat a track id (its PK is (playlist_id, track_id, position)),
 * so "source:sourceId" is NOT a unique row key and a bare current index is not
 * stable under reorder; the original position is both.
 */
private data class ReorderablePlaylistRow(val id: Int, val track: Track)

/**
 * Parse a stored `#RRGGBB` playlist color, falling back to the brand amber token
 * (never a drifting literal) when null/malformed. Wrap call sites in
 * `remember(color)` so it isn't re-parsed on every recomposition.
 */
private fun parseColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }
        .getOrDefault(AuxenColors.AmberPrimary)

/**
 * Playlist detail — desktop PlaylistView: color-dot header, Play All/Shuffle,
 * rename/recolor/delete, and drag-to-reorder tracks (long-press the drag handle)
 * with a per-row overflow menu for Remove. Reorder mirrors QueueScreen's
 * ReorderableQueueList (optimistic order during the drag, one movePlaylistTrack
 * on drop); the playlist list is unfiltered, so reorderable indices ARE playlist
 * positions and map straight to movePlaylistTrack — no index translation needed.
 */
@OptIn(ExperimentalLayoutApi::class)
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
            // Parse once per color (not every recomposition); the 1dp outline keeps
            // pale user colors visible on the light-theme surface.
            val dotColor = remember(playlist?.color) { parseColor(playlist?.color) }
            Box(
                Modifier
                    .size(16.dp)
                    .background(dotColor, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
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
            // Drag-to-reorder, mirroring QueueScreen.ReorderableQueueList: `order`
            // is an optimistic local copy the reorder callback mutates in place for
            // smooth animation; it re-syncs to the real list whenever that changes
            // for any OTHER reason — but never mid-drag, so a completed drag can't
            // be clobbered before it commits. The controller is never touched until
            // the drag ends; on drop, one net movePlaylistTrack reproduces the
            // dragged order. Rows carry a stable per-occurrence key (original
            // position) so a repeated track id still has a unique, drag-stable
            // identity. movePlaylistTrack is position-based (remove-at-from,
            // insert-at-to, then rewrite positions), so from/to map straight from
            // the reorderable indices — no queueMoveTarget-style translation, since
            // this list is unfiltered (unlike the Queue, which pins the playing row).
            val rows = remember(tracks) { tracks.mapIndexed { i, t -> ReorderablePlaylistRow(i, t) } }
            var order by remember { mutableStateOf(rows) }
            var draggingId by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(rows) { if (draggingId == null) order = rows }

            val haptics = LocalHapticFeedback.current
            val lazyListState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
                order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }

            LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
                items(order, key = { it.id }) { row ->
                    ReorderableItem(reorderState, key = row.id) { _ ->
                        val track = row.track
                        var menuOpen by remember { mutableStateOf(false) }
                        AuxenTrackRow(
                            track = track,
                            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                            onPlay = { viewModel.play(track) },
                            onToggleFavorite = { viewModel.toggleFavorite(track) },
                            trailing = {
                                // Remove relocated from the old inline ✕ into a ⋮
                                // overflow, freeing the row for the drag handle.
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Remove from playlist") },
                                            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                viewModel.removeFromPlaylist(playlistId, track)
                                            },
                                        )
                                    }
                                }
                                // Long-press drag handle. longPressDraggableHandle is
                                // the enclosing ReorderableItem scope's member (no
                                // import). On drop, from = the row's ORIGINAL position
                                // (its index in `rows`, unchanged during the drag),
                                // to = its index in the dragged `order`; one
                                // movePlaylistTrack reproduces the order.
                                Icon(
                                    Icons.Filled.DragHandle,
                                    contentDescription = "Reorder ${track.title}",
                                    modifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = { draggingId = row.id },
                                        onDragStopped = {
                                            val id = draggingId
                                            draggingId = null
                                            if (id != null) {
                                                val from = rows.indexOfFirst { it.id == id }
                                                val to = order.indexOfFirst { it.id == id }
                                                if (from >= 0 && to >= 0 && from != to) {
                                                    viewModel.movePlaylistTrack(playlistId, from, to)
                                                }
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                    }
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
                val currentColor = playlist?.color
                // FlowRow so all 8 swatches reflow onto multiple lines and stay
                // reachable inside the ~264dp AlertDialog content slot (a single
                // non-wrapping Row clipped the last swatches off-dialog).
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PLAYLIST_COLORS.forEach { hex ->
                        val selected = hex.equals(currentColor, ignoreCase = true)
                        // 48dp hit target for accessibility; clip BEFORE clickable
                        // so the ripple is circular.
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    viewModel.recolorPlaylist(playlistId, hex)
                                    showColors = false
                                }
                                .semantics {
                                    contentDescription = if (selected) "$hex, selected" else hex
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(parseColor(hex))
                                    .then(
                                        if (selected) {
                                            Modifier.border(
                                                2.dp,
                                                MaterialTheme.colorScheme.onSurface,
                                                CircleShape,
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                    )
                                }
                            }
                        }
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
