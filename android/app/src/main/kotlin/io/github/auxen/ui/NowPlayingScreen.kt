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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import io.github.auxen.ui.components.QualityBadge
import io.github.auxen.ui.components.SourceBadge
import io.github.auxen.ui.components.formatDuration
import io.github.auxen.ui.theme.AuxenColors

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
                    style = MaterialTheme.typography.headlineSmall,
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
            Text(formatDuration((dragPositionMs ?: positionMs) / 1000.0), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(durationMs / 1000.0), style = MaterialTheme.typography.bodySmall)
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
                    tint = if (viewModel.shuffleEnabled) AuxenColors.AmberPrimary else LocalContentColor.current,
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
                    tint = if (viewModel.repeatMode != Player.REPEAT_MODE_OFF) AuxenColors.AmberPrimary else LocalContentColor.current,
                )
            }
        }
    }
}
