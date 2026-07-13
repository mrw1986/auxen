package io.github.auxen.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.auxen.R
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AuxenTrackRow
import kotlin.math.roundToInt

/**
 * Queue screen (Desktop-Parity Screens, sub-batch A, Task 2): pinned
 * now-playing summary + the full live queue, reorderable via a long-press
 * drag handle, tap-to-jump, remove, and clear. Desktop reference
 * (`auxen/views/queue_panel.py`) excludes the current-index track from its
 * scrollable list entirely (shown only in the pinned section); this
 * deliberately does NOT do that -- the current track stays IN the list
 * (highlighted, drag handle hidden) so every row's index is always a real,
 * direct queue index. Excluding it would mean every jump/remove/move call
 * from the list needs to translate a "position among the OTHERS" back to a
 * real queue index, which is exactly the kind of index-math a reorderable
 * list is already fragile enough without.
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
    queue: List<Track>,
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
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(stringResource(R.string.queue_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.queue_empty_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        if (playingIndex in queue.indices) {
            Text(
                stringResource(R.string.queue_now_playing_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            AuxenTrackRow(
                track = queue[playingIndex],
                isFavorite = favoriteKeys.contains(queue[playingIndex].favoriteKey()),
                isPlaying = true,
                onPlay = { onJumpTo(playingIndex) },
                onToggleFavorite = { onToggleFavorite(queue[playingIndex]) },
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
 * The reorderable body: a plain [LazyColumn] with a manual long-press drag
 * on each row's trailing handle. No third-party reorderable-list dependency
 * -- checked the classpath first (none present) -- since [targetDragIndex]'s
 * fixed-row-height assumption (every row is the same [AuxenTrackRow], same
 * content shape) keeps this simple enough not to need one.
 *
 * `visualOrder` is a LOCAL copy used only to preview the reorder while
 * dragging; it re-syncs to [queue] via the `remember(queue)` key whenever
 * [queue] changes for any other reason (track advance, an external
 * remove), so a completed drag can never leave a stale view behind.
 */
@Composable
private fun ReorderableQueueList(
    queue: List<Track>,
    playingIndex: Int,
    favoriteKeys: Set<String>,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onToggleFavorite: (Track) -> Unit,
) {
    var visualOrder by remember(queue) { mutableStateOf(queue) }
    var itemHeightPx by remember { mutableFloatStateOf(0f) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    fun endDrag(commit: Boolean) {
        val start = dragStartIndex
        val end = draggedIndex
        if (commit && start != null && end != null && start != end) onMove(start, end)
        if (!commit) visualOrder = queue
        dragStartIndex = null
        draggedIndex = null
        dragOffsetPx = 0f
    }

    LazyColumn {
        // Positional key: there's no animateItemPlacement here, and Track has
        // no per-occurrence id, so a track-identity key (the previous
        // `track.favoriteKey()`) bought nothing and only crashed -- a queue
        // with the same track twice (a playlist dup, or "play next" on the
        // same song twice; the queue is raw MediaController items, no dedup)
        // produced duplicate keys, which Compose treats as a hard error
        // (final review round, Critical: reproduced, IllegalArgumentException;
        // regression test in QueueScreenUiTest).
        itemsIndexed(visualOrder, key = { index, _ -> index }) { index, track ->
            // Reread on every recomposition of THIS slot, not just once --
            // see the pointerInput(Unit) note below for why this matters.
            val currentIndex by rememberUpdatedState(index)
            AuxenTrackRow(
                track = track,
                isFavorite = favoriteKeys.contains(track.favoriteKey()),
                isPlaying = index == playingIndex,
                onPlay = { onJumpTo(index) },
                onToggleFavorite = { onToggleFavorite(track) },
                modifier = Modifier.onGloballyPositioned {
                    if (itemHeightPx == 0f) itemHeightPx = it.size.height.toFloat()
                },
                trailing = {
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.queue_remove_a11y, track.title))
                    }
                    // The playing row can't be dragged -- reordering "what's
                    // currently playing" out from under itself is confusing
                    // UX every major queue implementation avoids the same way.
                    if (index != playingIndex) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = stringResource(R.string.queue_drag_handle_a11y, track.title),
                            // Unit, NOT visualOrder: onDrag reassigns visualOrder
                            // on every row-cross (below), so keying on it made
                            // Compose cancel-and-restart this pointerInput's
                            // gesture-detection coroutine mid-drag -- the
                            // restarted detectDragGesturesAfterLongPress then
                            // waits for a fresh awaitFirstDown() that never
                            // comes until the finger lifts, so a drag died
                            // after moving at most one position (final review
                            // round, Important: analysis-based, confirm
                            // multi-position drag on-device). A stable key
                            // keeps ONE coroutine alive for the whole gesture;
                            // currentIndex (rememberUpdatedState) is what lets
                            // onDragStart still pick up this slot's LATEST
                            // index for the NEXT gesture, since a
                            // never-restarting coroutine would otherwise
                            // freeze it at this row's first-ever composition.
                            modifier = Modifier.pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragStartIndex = currentIndex
                                        draggedIndex = currentIndex
                                        dragOffsetPx = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val current = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                        dragOffsetPx += dragAmount.y
                                        val target = targetDragIndex(visualOrder.size, current, dragOffsetPx, itemHeightPx)
                                        if (target != current) {
                                            visualOrder = visualOrder.toMutableList()
                                                .apply { add(target, removeAt(current)) }
                                            dragOffsetPx -= (target - current) * itemHeightPx
                                            draggedIndex = target
                                        }
                                    },
                                    onDragEnd = { endDrag(commit = true) },
                                    onDragCancel = { endDrag(commit = false) },
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}

/** "SOURCE:sourceId" identity key, matching every other favorites lookup in this app. */
private fun Track.favoriteKey() = "${source.name}:$sourceId"

/**
 * Given the dragged item's current index among [itemCount] uniform-height
 * rows and how far its pointer has moved since the drag started
 * ([dragOffsetPx], downward positive, reset to the remainder after each
 * shift -- see the call site), returns the index it should now occupy.
 * Rounds to the nearest row (not floor/ceil) so a shift triggers once the
 * drag crosses the HALFWAY point of the next row, not the whole row --
 * matches how every mainstream reorderable list feels to drag.
 */
internal fun targetDragIndex(itemCount: Int, draggedIndex: Int, dragOffsetPx: Float, itemHeightPx: Float): Int {
    if (itemCount == 0 || itemHeightPx <= 0f) return draggedIndex
    val shift = (dragOffsetPx / itemHeightPx).roundToInt()
    return (draggedIndex + shift).coerceIn(0, itemCount - 1)
}
