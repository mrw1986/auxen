package io.github.auxen.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import io.github.auxen.playback.SleepTimerController
import io.github.auxen.playback.SleepTimerState
import io.github.auxen.playback.remainingMillis
import io.github.auxen.ui.components.QualityBadge
import io.github.auxen.ui.components.SourceBadge
import io.github.auxen.ui.components.formatDuration
import io.github.auxen.ui.theme.AuxenColors
import kotlinx.coroutines.delay

/**
 * Full-screen player — the desktop now-playing bar expanded to a mobile
 * screen: large art, marquee title/artist, seek, transport with 3-state
 * repeat, favorite heart, and source/quality badges.
 */
@OptIn(ExperimentalFoundationApi::class)
@UnstableApi
@Composable
fun NowPlayingScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val metadata = viewModel.nowPlaying
    val track = viewModel.currentTrack
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    val sleepTimerState by SleepTimerController.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Spacer(Modifier.height(16.dp))
        AsyncImage(
            model = metadata?.artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    metadata?.title?.toString() ?: "Nothing playing",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.basicMarquee(),
                )
                Text(
                    metadata?.artist?.toString() ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (track != null) {
                val key = "${track.source.name}:${track.sourceId}"
                IconButton(onClick = { viewModel.toggleFavorite(track) }) {
                    Icon(
                        if (key in favoriteKeys) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (key in favoriteKeys) AuxenColors.FavoriteRed else LocalContentColor.current,
                    )
                }
            }
        }
        if (track != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                SourceBadge(track.source)
                Spacer(Modifier.width(6.dp))
                QualityBadge(track.qualityLabel)
            }
        }
        Spacer(Modifier.height(16.dp))
        Slider(
            value = (dragPositionMs ?: positionMs).toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
            onValueChange = { dragPositionMs = it.toLong() },
            onValueChangeFinished = {
                dragPositionMs?.let { viewModel.seekTo(it) }
                dragPositionMs = null
            },
            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = AuxenColors.AmberPrimary,
                activeTrackColor = AuxenColors.AmberPrimary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDuration((dragPositionMs ?: positionMs) / 1000.0),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                formatDuration(durationMs / 1000.0),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.toggleShuffle() }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = "Shuffle",
                    // Icon tint -- resolves to the contrast-safe primary (final-review fix
                    // round, Minor #2); the play button below is a container behind BgDeep
                    // content and correctly keeps the raw brand amber.
                    tint = if (viewModel.shuffleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            IconButton(onClick = { viewModel.skipPrevious() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick = { viewModel.togglePlayPause() },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = AuxenColors.AmberPrimary,
                    contentColor = AuxenColors.BgDeep,
                ),
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    if (viewModel.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (viewModel.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { viewModel.skipNext() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { viewModel.cycleRepeat() }) {
                Icon(
                    if (viewModel.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repeat",
                    // Icon tint -- resolves to the contrast-safe primary (final-review fix round, Minor #2).
                    tint = if (viewModel.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            IconButton(onClick = { showSleepTimerSheet = true }) {
                Icon(
                    Icons.Filled.Bedtime,
                    contentDescription = "Sleep timer",
                    // Tinted while armed, matching the shuffle/repeat convention above.
                    tint = if (sleepTimerState.endElapsedRealtime != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current
                    },
                )
            }
        }
    }

    if (showSleepTimerSheet) {
        SleepTimerSheet(onDismiss = { showSleepTimerSheet = false })
    }
}

/**
 * Sleep timer picker/status sheet. [SleepTimerSheetContent] is extracted
 * (same "content composable, not the ModalBottomSheet" reasoning as
 * [io.github.auxen.ui.components.TrackActionSheet]) so it can be rendered
 * and captured/tested directly -- the Material 3 [ModalBottomSheet] hosts
 * its content in a separate window whose animated entrance never settles
 * under Robolectric.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by SleepTimerController.state.collectAsState()
    var finishTrackChecked by remember { mutableStateOf(false) }
    var remainingText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.endElapsedRealtime, state.finishTrack) {
        while (true) {
            val remaining = state.remainingMillis() ?: break
            remainingText = formatRemainingTime(remaining.coerceAtLeast(0))
            if (remaining <= 0) break
            delay(1_000)
        }
        remainingText = null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SleepTimerSheetContent(
            state = state,
            remainingText = remainingText,
            finishTrackChecked = finishTrackChecked,
            onFinishTrackCheckedChange = { finishTrackChecked = it },
            onSelectDuration = { minutes -> SleepTimerController.start(minutes, finishTrackChecked) },
            onCancelTimer = { SleepTimerController.cancel() },
        )
    }
}

/** Preset durations offered by [SleepTimerSheetContent], in minutes. */
private val SLEEP_TIMER_PRESET_MINUTES = listOf(15, 30, 45, 60, 90)

@Composable
internal fun SleepTimerSheetContent(
    state: SleepTimerState,
    remainingText: String?,
    finishTrackChecked: Boolean,
    onFinishTrackCheckedChange: (Boolean) -> Unit,
    onSelectDuration: (Int) -> Unit,
    onCancelTimer: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
        Text("Sleep timer", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        if (state.endElapsedRealtime != null) {
            Text(
                if (state.finishTrack) "Pausing after this track" else "Pausing in ${remainingText ?: "--:--"}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onCancelTimer) { Text("Cancel timer") }
        } else {
            Text(
                "Pause playback after",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SLEEP_TIMER_PRESET_MINUTES.forEach { minutes ->
                    OutlinedButton(onClick = { onSelectDuration(minutes) }) { Text("${minutes}m") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Finish last track", modifier = Modifier.weight(1f))
                Switch(checked = finishTrackChecked, onCheckedChange = onFinishTrackCheckedChange)
            }
        }
    }
}

/** `M:SS` (unpadded minutes, since presets run up to 90) -- e.g. "14:32", "3:07". */
private fun formatRemainingTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
