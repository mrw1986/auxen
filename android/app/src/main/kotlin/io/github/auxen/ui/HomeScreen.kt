package io.github.auxen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.data.groupAlbums
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.SectionHeader
import io.github.auxen.ui.theme.AuxenColors
import java.util.Calendar

/** Time-of-day greeting — desktop HomePage header. */
internal fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * Home — desktop HomePage: greeting, All/Tidal/Local filter chips (persisted),
 * stat cards, Recently Added carousel, Recently Played list.
 */
@UnstableApi
@Composable
fun HomeScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val localTracks by viewModel.localTracks.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val filter by viewModel.homeFilter.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.loadLibrary()
        viewModel.refreshHome()
    }

    fun matches(track: Track): Boolean = when (filter) {
        "tidal" -> track.source == Source.TIDAL
        "local" -> track.source == Source.LOCAL
        else -> true
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("all" to "All", "tidal" to "Tidal", "local" to "Local").forEach { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { viewModel.setHomeFilter(value) },
                        label = { Text(label) },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard("Local Tracks", localTracks.size.toString(), Modifier.weight(1f))
                StatCard("Favorites", favoriteKeys.size.toString(), Modifier.weight(1f))
                StatCard("Recently Played", recentlyPlayed.size.toString(), Modifier.weight(1f))
            }
        }
        val addedAlbums = groupAlbums(recentlyAdded.filter(::matches))
        if (addedAlbums.isNotEmpty()) {
            item { SectionHeader("Recently Added") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(addedAlbums, key = { "${it.album}|${it.albumArtist}" }) { album ->
                        AlbumCard(
                            title = album.album,
                            artist = album.albumArtist,
                            artUrl = album.artUrl,
                            source = Source.LOCAL,
                            onClick = { viewModel.playAll(album.tracks) },
                            onPlay = { viewModel.playAll(album.tracks) },
                        )
                    }
                }
            }
        }
        val played = recentlyPlayed.filter(::matches)
        if (played.isNotEmpty()) {
            item { SectionHeader("Recently Played") }
            items(played, key = { "${it.source}:${it.sourceId}" }) { track ->
                AuxenTrackRow(
                    track = track,
                    isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                    onPlay = { viewModel.play(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                )
            }
        } else if (addedAlbums.isEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Play something from your Library or Search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Amber-accented stat card — desktop stat-card row. */
@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = AuxenColors.AmberPrimary)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
