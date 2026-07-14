package io.github.auxen.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import io.github.auxen.model.Source
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
 * screen: a blurred album-art backdrop, the sharp art card, marquee
 * title/artist, seek, transport with 3-state repeat, favorite heart, and
 * source/quality badges.
 *
 * This is the thin stateful wrapper: it reads [viewModel]/[SleepTimerController]
 * and owns the sleep-timer sheet, then delegates the whole visual to the
 * ViewModel-free [NowPlayingContent] (same split as `QueueContent`/
 * `SettingsContent`, which lets `NowPlayingScreenshotTest` render it without a
 * live `MediaController`).
 */
@UnstableApi
@Composable
fun NowPlayingScreen(viewModel: PlayerViewModel, onBack: () -> Unit, onOpenQueue: () -> Unit) {
    val metadata = viewModel.nowPlaying
    val track = viewModel.currentTrack
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    val sleepTimerState by SleepTimerController.state.collectAsState()

    val favoriteKey = track?.let { "${it.source.name}:${it.sourceId}" }

    NowPlayingContent(
        title = metadata?.title?.toString() ?: "Nothing playing",
        artist = metadata?.artist?.toString() ?: "",
        artworkModel = metadata?.artworkUri,
        source = track?.source,
        qualityLabel = track?.qualityLabel,
        isFavorite = favoriteKey != null && favoriteKey in favoriteKeys,
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = viewModel.isPlaying,
        shuffleEnabled = viewModel.shuffleEnabled,
        repeatMode = viewModel.repeatMode,
        sleepTimerArmed = sleepTimerState.isArmed,
        onBack = onBack,
        onOpenQueue = onOpenQueue,
        onOpenSleepTimer = { showSleepTimerSheet = true },
        onToggleFavorite = { track?.let { viewModel.toggleFavorite(it) } },
        onTogglePlayPause = { viewModel.togglePlayPause() },
        onSkipPrevious = { viewModel.skipPrevious() },
        onSkipNext = { viewModel.skipNext() },
        onToggleShuffle = { viewModel.toggleShuffle() },
        onCycleRepeat = { viewModel.cycleRepeat() },
        onSeek = { viewModel.seekTo(it) },
    )

    if (showSleepTimerSheet) {
        SleepTimerSheet(onDismiss = { showSleepTimerSheet = false })
    }
}

/**
 * Stateless now-playing surface. Layout is responsive via [BoxWithConstraints]:
 *
 *  - **Tall phones** stack top bar → art → controls in a `Column`. The art
 *    sits in a `weight(1f)` box so it takes only the space *left over* after
 *    the (unweighted, intrinsic-height) transport block is laid out — it
 *    shrinks to fit rather than pushing the controls off-screen. This is the
 *    fix for the reported "just image + timebar" bug, which came from an
 *    always-`fillMaxWidth().aspectRatio(1f)` art in a non-scrollable column.
 *  - **Wide / near-square screens** (tablets, landscape) put the art beside
 *    the controls in a two-pane `Row`, which uses the extra width well and
 *    keeps every control comfortably on-screen.
 *
 * In both modes the transport row is guaranteed visible without scrolling.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowPlayingContent(
    title: String,
    artist: String,
    artworkModel: Any?,
    source: Source?,
    qualityLabel: String?,
    isFavorite: Boolean,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    sleepTimerArmed: Boolean,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Near-square or wider than ~600dp: art and controls share the width
        // side-by-side rather than stacking (the stacked square would waste the
        // horizontal room and crowd the vertical).
        val twoPane = maxWidth >= 600.dp && maxWidth >= maxHeight * 0.9f

        NowPlayingBackdrop(artworkModel, Modifier.matchParentSize())

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NowPlayingTopBar(
                onBack = onBack,
                onOpenQueue = onOpenQueue,
                onOpenSleepTimer = onOpenSleepTimer,
                sleepTimerArmed = sleepTimerArmed,
            )

            if (twoPane) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArtCard(
                        artworkModel = artworkModel,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 16.dp),
                    )
                    Spacer(Modifier.width(32.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TrackMetadata(title, artist, source, qualityLabel, isFavorite, onToggleFavorite)
                        Spacer(Modifier.height(20.dp))
                        SeekBar(positionMs, durationMs, onSeek)
                        Spacer(Modifier.height(12.dp))
                        TransportBar(isPlaying, shuffleEnabled, repeatMode, onTogglePlayPause, onSkipPrevious, onSkipNext, onToggleShuffle, onCycleRepeat)
                    }
                }
            } else {
                // weight(1f): the art claims only the space the intrinsic-height
                // metadata/seek/transport block below leaves free, so it can
                // never push those off the bottom of a short screen.
                AlbumArtCard(
                    artworkModel = artworkModel,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                )
                TrackMetadata(title, artist, source, qualityLabel, isFavorite, onToggleFavorite)
                Spacer(Modifier.height(16.dp))
                SeekBar(positionMs, durationMs, onSeek)
                Spacer(Modifier.height(12.dp))
                TransportBar(isPlaying, shuffleEnabled, repeatMode, onTogglePlayPause, onSkipPrevious, onSkipNext, onToggleShuffle, onCycleRepeat)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/**
 * Blurred, dimmed album-art fill behind a theme-aware scrim — the "premium
 * now-playing" backdrop. The scrim is a vertical gradient over the theme
 * [MaterialTheme.colorScheme.background] so title/badge text keeps its
 * contrast in both light and dark. The art layer is skipped entirely when
 * [artworkModel] is null (nothing to blur), leaving just the flat background.
 */
@Composable
private fun NowPlayingBackdrop(artworkModel: Any?, modifier: Modifier) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier.background(background)) {
        if (artworkModel != null) {
            AsyncImage(
                model = artworkModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(48.dp).alpha(0.5f),
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(background.copy(alpha = 0.55f), background.copy(alpha = 0.92f)),
                ),
            ),
        )
    }
}

@Composable
private fun NowPlayingTopBar(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    sleepTimerArmed: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.weight(1f))
        // Queue (Desktop-Parity Screens, sub-batch A, Task 2): lives here
        // rather than on MiniPlayerBar, which is already an "ultra-narrow
        // tier" per its own KDoc with no spare room for a third icon
        // alongside play/next.
        IconButton(onClick = onOpenQueue) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = stringResource(R.string.queue_title))
        }
        // Moved here from the transport row (final review round, Important
        // #5): a 6th icon in that row's SpaceEvenly arrangement pushed
        // Play/Pause off-center. Top bar is the natural home for a screen-
        // level action that isn't part of transport control proper.
        IconButton(onClick = onOpenSleepTimer) {
            Icon(
                Icons.Filled.Bedtime,
                contentDescription = "Sleep timer",
                // Tinted while armed, matching the shuffle/repeat convention.
                // isArmed (not endElapsedRealtime != null): also true during the
                // pendingTrackEnd phase, which has no countdown timestamp of its
                // own but is still an active pending pause.
                tint = if (sleepTimerArmed) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
    }
}

/**
 * The sharp album-art card, centered and always square. Its own
 * [BoxWithConstraints] fits the square to whichever of the available box's
 * dimensions is smaller, so it never overflows whether the box is portrait
 * (phone, stacked) or landscape/tall (tablet pane). A raised [Surface] gives
 * it a lifted, premium feel and a deterministic `surfaceVariant` fill while
 * art loads (or in null-art goldens).
 */
@Composable
private fun AlbumArtCard(artworkModel: Any?, modifier: Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // Landscape box -> the height is the limiting dimension; otherwise the
        // width is. Fill the limiting one and let aspectRatio derive the other.
        val squared = if (maxHeight < maxWidth) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 18.dp,
            modifier = squared.aspectRatio(1f),
        ) {
            AsyncImage(
                model = artworkModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackMetadata(
    title: String,
    artist: String,
    source: Source?,
    qualityLabel: String?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                // titleMedium (DM Sans) at 22sp — pinned verbatim by
                // ComponentScreenshotTest's typography-details golden (proves
                // the title is sans-serif DM Sans, not Fraunces).
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.basicMarquee(),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (source != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) AuxenColors.FavoriteRed else LocalContentColor.current,
                )
            }
        }
    }
    if (source != null) {
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            SourceBadge(source)
            if (qualityLabel != null) {
                Spacer(Modifier.width(6.dp))
                QualityBadge(qualityLabel)
            }
        }
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    val max = durationMs.toFloat().coerceAtLeast(1f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = (dragPositionMs ?: positionMs).toFloat().coerceIn(0f, max),
            onValueChange = { dragPositionMs = it.toLong() },
            onValueChangeFinished = {
                dragPositionMs?.let(onSeek)
                dragPositionMs = null
            },
            valueRange = 0f..max,
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
    }
}

@Composable
private fun TransportBar(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                Icons.Filled.Shuffle,
                contentDescription = "Shuffle",
                // Icon tint -- resolves to the contrast-safe primary (final-review fix
                // round, Minor #2); the play button below is a container behind BgDeep
                // content and correctly keeps the raw brand amber.
                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        IconButton(onClick = onSkipPrevious) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(38.dp))
        }
        // Clearly-primary transport control: larger amber container than the
        // ghost skip/shuffle/repeat buttons around it.
        IconButton(
            onClick = onTogglePlayPause,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = AuxenColors.AmberPrimary,
                contentColor = AuxenColors.BgDeep,
            ),
            modifier = Modifier.size(72.dp),
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = onSkipNext) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(38.dp))
        }
        IconButton(onClick = onCycleRepeat) {
            Icon(
                if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                contentDescription = "Repeat",
                // Icon tint -- resolves to the contrast-safe primary (final-review fix round, Minor #2).
                tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
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
