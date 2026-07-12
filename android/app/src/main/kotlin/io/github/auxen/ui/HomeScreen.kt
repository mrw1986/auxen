package io.github.auxen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.SectionHeader
import java.util.Calendar

/** Time-of-day greeting — desktop HomePage header. */
internal fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * Home — M3a foundation version: greeting + Recently Played. The full
 * desktop-parity Home (filter pills, stat cards, Recently Added carousel,
 * Tidal sections) lands in milestone 3b.
 */
@UnstableApi
@Composable
fun HomeScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshRecentlyPlayed() }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
        if (recentlyPlayed.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Nothing played yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Play something from your Library or Search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item { SectionHeader("Recently Played") }
            items(recentlyPlayed, key = { "${it.source}:${it.sourceId}" }) { track ->
                AuxenTrackRow(
                    track = track,
                    isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                    onPlay = { viewModel.play(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                )
            }
        }
    }
}
