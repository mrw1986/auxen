package io.github.auxen.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.ui.PlayerViewModel

/**
 * Collapsed player bar — the desktop now-playing bar's ultra-narrow tier
 * (art + marquee title/artist + play + next) with a hairline progress
 * indicator. Tap anywhere to open the full Now Playing screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@UnstableApi
@Composable
fun MiniPlayerBar(viewModel: PlayerViewModel, onOpen: () -> Unit) {
    val metadata = viewModel.nowPlaying ?: return
    val title = metadata.title?.toString() ?: return
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()

    Surface(tonalElevation = 4.dp) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onOpen).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    model = metadata.artworkUri,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.basicMarquee(),
                    )
                    metadata.artist?.let {
                        Text(
                            it.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = { viewModel.togglePlayPause() },
                    colors = IconButtonDefaults.iconButtonColors(
                        // Theme-aware amber: primary is the brand gold in dark
                        // (== AmberPrimary) and the contrast-safe Amber600 in light,
                        // matching the progress indicator below so the bar's two
                        // amber accents stay one color; onPrimary is BgDeep in both
                        // themes (polish P1, Fix 1).
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        if (viewModel.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (viewModel.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = { viewModel.skipNext() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }
            if (durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                    // Foreground accent line — theme-aware primary (Amber600 in
                    // light, AmberPrimary in dark), matching the play button above.
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    drawStopIndicator = {},
                )
            }
        }
    }
}
