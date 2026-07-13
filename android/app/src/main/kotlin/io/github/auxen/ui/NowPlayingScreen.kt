package io.github.auxen.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import io.github.auxen.R
import io.github.auxen.playback.SleepTimerController
import io.github.auxen.playback.SleepTimerState
import io.github.auxen.playback.isArmed
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.weight(1f))
            // Moved here from the transport row below (final review round,
            // Important #5): a 6th icon in that row's SpaceEvenly
            // arrangement pushed Play/Pause off-center. Top bar is the
            // natural home for a screen-level action that isn't part of
            // transport control proper.
            IconButton(onClick = { showSleepTimerSheet = true }) {
                Icon(
                    Icons.Filled.Bedtime,
                    contentDescription = "Sleep timer",
                    // Tinted while armed, matching the shuffle/repeat convention below.
                    // isArmed (not endElapsedRealtime != null): also true during the
                    // pendingTrackEnd phase, which has no countdown timestamp of its
                    // own but is still an active pending pause.
                    tint = if (sleepTimerState.isArmed) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
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
    var remainingSeconds by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(state.endElapsedRealtime, state.finishTrack) {
        while (true) {
            val remaining = state.remainingMillis() ?: break
            remainingSeconds = remaining.coerceAtLeast(0) / 1000.0
            if (remaining <= 0) break
            delay(1_000)
        }
        remainingSeconds = null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SleepTimerSheetContent(
            state = state,
            remainingSeconds = remainingSeconds,
            finishTrackChecked = finishTrackChecked,
            onFinishTrackCheckedChange = { finishTrackChecked = it },
            onSelectDuration = { minutes -> SleepTimerController.start(minutes, finishTrackChecked) },
            onCancelTimer = { SleepTimerController.cancel() },
        )
    }
}

/** Preset durations offered by [SleepTimerSheetContent], in minutes. */
private val SLEEP_TIMER_PRESET_MINUTES = listOf(15, 30, 45, 60, 90)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepTimerSheetContent(
    state: SleepTimerState,
    remainingSeconds: Double?,
    finishTrackChecked: Boolean,
    onFinishTrackCheckedChange: (Boolean) -> Unit,
    onSelectDuration: (Int) -> Unit,
    onCancelTimer: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.sleep_timer_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        when {
            // Expired with finishTrack, waiting for the current track to
            // end -- no countdown left to show (final review round,
            // Important #3).
            state.pendingTrackEnd -> {
                Text(stringResource(R.string.sleep_timer_pausing_after_track), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onCancelTimer) { Text(stringResource(R.string.sleep_timer_cancel)) }
            }
            // Counting down -- finishTrack armed or not, both get a LIVE
            // countdown now (previously finishTrack showed a static
            // "Pausing after this track" the whole time it counted down,
            // final review round, Important #3a).
            state.endElapsedRealtime != null -> {
                val timeText = formatDuration(remainingSeconds)
                val templateRes = if (state.finishTrack) {
                    R.string.sleep_timer_pausing_in_will_finish
                } else {
                    R.string.sleep_timer_pausing_in
                }
                val template = stringResource(templateRes, timeText)
                Text(countdownAnnotatedString(template, timeText), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onCancelTimer) { Text(stringResource(R.string.sleep_timer_cancel)) }
            }
            else -> {
                Text(
                    stringResource(R.string.sleep_timer_pause_after),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // FlowRow (not Row): a plain fillMaxWidth() Row squeezes
                // later children toward zero width once the five buttons'
                // combined size exceeds the available space, rather than
                // letting them overflow visibly -- confirmed by direct
                // measurement at a narrow width (60m/90m both collapsed to
                // literal DpRect(0,0,0,0)); comfortably fits at this
                // screen's default device width, but phones narrower than
                // that, a larger system font scale, or longer localized
                // preset labels can all shrink the available space the same
                // way. FlowRow wraps overflow to a new line instead of
                // squeezing, so every button keeps its full intrinsic size
                // regardless of how little width is available (final review
                // round, merge-blocking #1).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SLEEP_TIMER_PRESET_MINUTES.forEach { minutes ->
                        OutlinedButton(onClick = { onSelectDuration(minutes) }) {
                            Text(stringResource(R.string.sleep_timer_preset_minutes, minutes))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sleep_timer_finish_last_track), modifier = Modifier.weight(1f))
                    Switch(checked = finishTrackChecked, onCheckedChange = onFinishTrackCheckedChange)
                }
            }
        }
    }
}

/**
 * Builds the "Pausing in M:SS[…]" countdown line with just the M:SS portion
 * in monospace, matching this screen's convention for time-value text
 * elsewhere (the seek row's position/duration) -- final review round, Minor
 * #8. Locates [timeText] inside the already-substituted [template] rather
 * than requiring separate prefix/suffix string resources, so the sentence
 * stays one natural, fully-translatable string resource.
 */
internal fun countdownAnnotatedString(template: String, timeText: String): AnnotatedString {
    val start = template.indexOf(timeText)
    return buildAnnotatedString {
        append(template)
        if (start >= 0) {
            addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, start + timeText.length)
        }
    }
}
