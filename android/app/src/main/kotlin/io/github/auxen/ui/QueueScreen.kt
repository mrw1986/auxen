package io.github.auxen.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.auxen.R
import io.github.auxen.model.QueueEntry
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Queue screen: a pinned now-playing summary + the scrollable "up next" list,
 * reorderable via a long-press drag handle (animated + haptic, powered by
 * `sh.calvin.reorderable`), tap-to-jump, remove, and clear.
 *
 * Unlike the earlier in-house version, the now-playing track is shown ONLY in
 * the pinned header — it is excluded from the scrollable list (matching the
 * desktop `queue_panel.py`), so it never appears twice. Because the list is
 * therefore filtered, every jump/remove/move from a list row translates the
 * row's stable [QueueEntry.id] back to a real controller index before calling
 * the ViewModel (whose `jumpTo`/`removeFromQueue`/`moveInQueue` take real
 * indices). Per-occurrence ids make that translation exact even when the same
 * track appears more than once.
 *
 * Thin VM-collecting shell over [QueueContent], same split as
 * `SettingsContent`/`AutoEqPickerResults`.
 */
@Composable
fun QueueScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val queue by viewModel.queue.collectAsState()
    val playingIndex by viewModel.queueIndex.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    QueueContent(
        queue = queue,
        playingIndex = playingIndex,
        favoriteKeys = favoriteKeys,
        onJumpTo = viewModel::jumpTo,
        onRemove = viewModel::removeFromQueue,
        onMove = viewModel::moveInQueue,
        onToggleFavorite = viewModel::toggleFavorite,
        onClear = viewModel::clearQueue,
        modifier = modifier,
    )
}

@Composable
internal fun QueueContent(
    queue: List<QueueEntry>,
    playingIndex: Int,
    favoriteKeys: Set<String>,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.headlineSmall)
                if (queue.isNotEmpty()) {
                    Text(
                        stringResource(R.string.queue_track_count, queue.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (queue.isNotEmpty()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.queue_clear)) }
            }
        }

        if (queue.isEmpty()) {
            // Centered shared EmptyState (was a bare 24dp-inset left-aligned
            // Column that didn't line up with the 16dp header) — polish P2, Fix 22.
            EmptyState(
                icon = Icons.Filled.QueueMusic,
                title = stringResource(R.string.queue_empty_title),
                subtitle = stringResource(R.string.queue_empty_message),
            )
            return@Column
        }

        val playingEntry = queue.getOrNull(playingIndex)
        if (playingEntry != null) {
            Text(
                stringResource(R.string.queue_now_playing_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            AuxenTrackRow(
                track = playingEntry.track,
                isFavorite = favoriteKeys.contains(playingEntry.track.favoriteKey()),
                isPlaying = true,
                onPlay = { onJumpTo(playingIndex) },
                onToggleFavorite = { onToggleFavorite(playingEntry.track) },
            )
            HorizontalDivider()
        }

        ReorderableQueueList(
            queue = queue,
            playingIndex = playingIndex,
            favoriteKeys = favoriteKeys,
            onJumpTo = onJumpTo,
            onRemove = onRemove,
            onMove = onMove,
            onToggleFavorite = onToggleFavorite,
        )
    }
}

/**
 * The reorderable "up next" body: a [LazyColumn] of every queue entry EXCEPT
 * the pinned playing one, wired to `sh.calvin.reorderable` for animated
 * long-press drag-reorder.
 *
 * [upNextOrder] is a LOCAL, optimistic copy of the filtered queue that the
 * reorder callback mutates in place for smooth animation while dragging; it
 * re-syncs to the real (filtered) queue whenever that changes for any other
 * reason (track advance, external remove, our own committed move) — but never
 * mid-drag, so a completed drag can't be clobbered before it commits. The
 * controller is never mutated until the drag ends: on drop, [queueMoveTarget]
 * translates the final local order into the single real-index
 * `moveMediaItem` that reproduces it.
 */
@Composable
private fun ReorderableQueueList(
    queue: List<QueueEntry>,
    playingIndex: Int,
    favoriteKeys: Set<String>,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onToggleFavorite: (Track) -> Unit,
) {
    val upNext = remember(queue, playingIndex) {
        queue.filterIndexed { index, _ -> index != playingIndex }
    }
    var upNextOrder by remember { mutableStateOf(upNext) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    // Re-sync the optimistic order from the real queue, but not while a drag is
    // in flight (that would yank rows out from under the finger).
    LaunchedEffect(upNext) { if (draggingId == null) upNextOrder = upNext }

    val haptics = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        upNextOrder = upNextOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LazyColumn(state = lazyListState) {
        items(upNextOrder, key = { it.id }) { entry ->
            ReorderableItem(reorderState, key = entry.id) { _ ->
                val track = entry.track
                // Real controller index of this row, resolved from the full
                // (unchanged) queue by the entry's unique id — exact even for
                // duplicate tracks.
                val realIndex = queue.indexOfFirst { it.id == entry.id }
                AuxenTrackRow(
                    track = track,
                    isFavorite = favoriteKeys.contains(track.favoriteKey()),
                    isPlaying = false,
                    onPlay = { if (realIndex >= 0) onJumpTo(realIndex) },
                    onToggleFavorite = { onToggleFavorite(track) },
                    trailing = {
                        IconButton(onClick = { if (realIndex >= 0) onRemove(realIndex) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.queue_remove_a11y, track.title),
                            )
                        }
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = stringResource(R.string.queue_drag_handle_a11y, track.title),
                            modifier = Modifier.longPressDraggableHandle(
                                onDragStarted = { draggingId = entry.id },
                                onDragStopped = {
                                    val id = draggingId
                                    draggingId = null
                                    if (id != null) {
                                        queueMoveTarget(queue, upNextOrder, id)
                                            ?.let { (from, target) -> onMove(from, target) }
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

/** "SOURCE:sourceId" identity key, matching every other favorites lookup in this app. */
private fun Track.favoriteKey() = "${source.name}:$sourceId"

/**
 * Translate a completed drag in the FILTERED (playing-excluded) up-next list
 * back to the single real-queue move that reproduces it.
 *
 * The Queue screen pins the playing track and drags only the *other* entries,
 * so the reorderable list is a filtered view while
 * [PlayerViewModel.moveInQueue] needs real controller indices. [queue] is the
 * full, unchanged snapshot (playing item still in place); [reorderedUpNext] is
 * the up-next list in its final dragged order; [draggedId] is the moved
 * entry's [QueueEntry.id]. Entries are matched by id, so a duplicate track
 * resolves to the exact occurrence dragged — never a same-titled sibling.
 *
 * Returns `(fromRealIndex, toRealIndex)` for `moveMediaItem`, or null when the
 * drag was a no-op. `toRealIndex` is the index the dragged item must land at:
 * the position, in the queue-minus-dragged-item, of whatever now follows it
 * in [reorderedUpNext] (or the end, if it is now last). `moveMediaItem(from,
 * to)` — remove at `from`, insert at `to` — then yields exactly the desired
 * relative order of the non-playing items, wherever the (untouched) playing
 * item ends up floating.
 */
internal fun queueMoveTarget(
    queue: List<QueueEntry>,
    reorderedUpNext: List<QueueEntry>,
    draggedId: String,
): Pair<Int, Int>? {
    val fromReal = queue.indexOfFirst { it.id == draggedId }
    if (fromReal < 0) return null
    val position = reorderedUpNext.indexOfFirst { it.id == draggedId }
    if (position < 0) return null
    val remaining = queue.filter { it.id != draggedId }
    val next = reorderedUpNext.getOrNull(position + 1)
    val toReal = if (next == null) remaining.size else remaining.indexOfFirst { it.id == next.id }
    if (toReal < 0) return null
    return if (toReal == fromReal) null else fromReal to toReal
}
