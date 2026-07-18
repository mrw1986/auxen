package io.github.auxen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.R
import io.github.auxen.data.AlbumGroup
import io.github.auxen.data.groupAlbums
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AlbumActionSheet
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import io.github.auxen.ui.components.LoadingState
import io.github.auxen.ui.components.SectionHeader
import io.github.auxen.ui.components.TrackActionSheet
import io.github.auxen.ui.components.TrackInfoSheet
import io.github.auxen.ui.components.albumTargetFor
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
fun HomeScreen(
    viewModel: PlayerViewModel,
    onOpenAlbumForTrack: (album: String, artist: String, seedTrackId: String?) -> Unit,
    onOpenArtistByName: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val localTracks by viewModel.localTracks.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val filter by viewModel.homeFilter.collectAsState()
    val loading by viewModel.homeLoading.collectAsState()
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var infoTrack by remember { mutableStateOf<Track?>(null) }
    var albumSheet by remember { mutableStateOf<AlbumGroup?>(null) }
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
                        colors = FilterChipDefaults.filterChipColors(
                            // Theme-aware accent: colorScheme.primary is the brand
                            // amber in dark (== AmberPrimary) but the darker,
                            // contrast-safe Amber600 in light; onPrimary is BgDeep
                            // in both themes (polish P1, Fix 1).
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
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
                            // D3: open the album like every other grid; Play stays
                            // on the card's play button below.
                            onClick = {
                                onOpenAlbumForTrack(
                                    album.album,
                                    album.albumArtist,
                                    album.tracks.firstOrNull { it.isTidal }?.sourceId,
                                )
                            },
                            onPlay = { viewModel.playAll(album.tracks) },
                            onLongPress = { albumSheet = album },
                            // Carousel item — a fill-width card has no width
                            // constraint inside a LazyRow, so pin it here.
                            modifier = Modifier.width(150.dp),
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
                    onLongPress = { sheetTrack = track },
                )
            }
        } else if (addedAlbums.isEmpty()) {
            item {
                // Both sections empty: a spinner while the initial Home load
                // runs, the EmptyState only once it has finished — so the empty
                // state never flashes before [refreshHome] resolves.
                if (loading) {
                    LoadingState()
                } else {
                    EmptyState(
                        icon = Icons.Filled.MusicNote,
                        title = stringResource(R.string.empty_home_title),
                        subtitle = stringResource(R.string.empty_home_subtitle),
                    )
                }
            }
        }
    }

    sheetTrack?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
            playlists = playlists,
            onDismiss = { sheetTrack = null },
            onPlay = { viewModel.play(track) },
            onPlayNext = { viewModel.playNext(track) },
            onEnqueue = { viewModel.enqueue(track) },
            onToggleFavorite = { viewModel.toggleFavorite(track) },
            onAddToPlaylist = { viewModel.addToPlaylist(track, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAdd(track, it) },
            onShowInfo = { infoTrack = track },
            onGoToAlbum = albumTargetFor(track)?.let { target ->
                { onOpenAlbumForTrack(target.album, target.albumArtist, track.sourceId.takeIf { track.isTidal }) }
            },
            onGoToArtist = { onOpenArtistByName(track.artist) },
        )
    }

    infoTrack?.let { track ->
        val detail by viewModel.trackDetail.collectAsState()
        val detailLoading by viewModel.trackDetailLoading.collectAsState()
        val enriched by viewModel.enrichedInfoTrack.collectAsState()
        LaunchedEffect(track) { viewModel.loadTrackDetail(track) }
        TrackInfoSheet(
            // The enriched copy for THIS track (guarded against a stale value
            // from a previous open); falls back to the un-enriched track until
            // MediaMetadataRetriever lands.
            track = enriched?.takeIf { it.source == track.source && it.sourceId == track.sourceId } ?: track,
            detail = detail,
            loading = detailLoading,
            onDismiss = { infoTrack = null; viewModel.clearTrackDetail() },
        )
    }

    albumSheet?.let { group ->
        AlbumActionSheet(
            albumTitle = group.album,
            albumArtist = group.albumArtist,
            playlists = playlists,
            onDismiss = { albumSheet = null },
            onPlay = { viewModel.playAll(group.tracks) },
            onShuffle = { viewModel.playAll(group.tracks, shuffled = true) },
            onPlayNext = { viewModel.playNextAll(group.tracks) },
            onEnqueue = { viewModel.enqueueAll(group.tracks) },
            onAddToPlaylist = { viewModel.addTracksToPlaylist(group.tracks, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAddAll(group.tracks, it) },
            onGoToArtist = { onOpenArtistByName(group.albumArtist) },
        )
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
        // Text tint on surfaceVariant -- resolves to the contrast-safe primary
        // (final-review fix round, Minor #2).
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
