package io.github.auxen.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Artist long-press action sheet — the mobile substitute for the desktop
 * ArtistContextMenu's playback section. Renders [artistActions] (Task 6) over
 * the artist's in-hand `ArtistGroup.tracks`. No playlist entry (the desktop
 * artist menu has none) and no "Go to artist" (this is opened FROM an artist row).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistActionSheet(
    artistName: String,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ArtistActionSheetContent(
            artistName = artistName,
            onPlay = { onPlay(); onDismiss() },
            onShuffle = { onShuffle(); onDismiss() },
            onPlayNext = { onPlayNext(); onDismiss() },
            onEnqueue = { onEnqueue(); onDismiss() },
        )
    }
}

/** Stateless body — split out for the same Robolectric reason as
 *  [TrackActionSheetContent]. */
@Composable
internal fun ArtistActionSheetContent(
    artistName: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            artistName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        artistActions().forEach { entry ->
            when (entry.id) {
                ArtistActionId.PLAY ->
                    SheetAction(entry.label, { Icon(Icons.Filled.PlayArrow, null) }) { onPlay() }
                ArtistActionId.SHUFFLE ->
                    SheetAction(entry.label, { Icon(Icons.Filled.Shuffle, null) }) { onShuffle() }
                ArtistActionId.PLAY_NEXT ->
                    SheetAction(entry.label, { Icon(Icons.Filled.SkipNext, null) }) { onPlayNext() }
                ArtistActionId.ENQUEUE ->
                    SheetAction(entry.label, { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }) { onEnqueue() }
            }
        }
    }
}
