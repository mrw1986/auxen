package io.github.auxen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.data.AlbumGroup
import io.github.auxen.data.groupAlbums
import io.github.auxen.data.groupArtists
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.TrackActionSheet
import io.github.auxen.ui.theme.AuxenColors

private val COLLECTION_TABS = listOf("Tracks", "Albums", "Artists", "Playlists")

/**
 * Collection — desktop CollectionView: favorited content in Tracks/Albums/
 * Artists tabs plus Playlists, with the All/Tidal/Local source filter.
 */
@UnstableApi
@Composable
fun CollectionScreen(
    viewModel: PlayerViewModel,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAlbum: (AlbumGroup) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val favorites by viewModel.favorites.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val filter by viewModel.collectionFilter.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheetTrack by remember { mutableStateOf<Track?>(null) }

    fun matches(track: Track): Boolean = when (filter) {
        "tidal" -> track.source == Source.TIDAL
        "local" -> track.source == Source.LOCAL
        else -> true
    }
    val filtered = favorites.filter(::matches)

    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            COLLECTION_TABS.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = tab == index,
                    onClick = { tab = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = COLLECTION_TABS.size),
                ) { Text(label) }
            }
        }
        if (tab != 3) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("all" to "All", "tidal" to "Tidal", "local" to "Local").forEach { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { viewModel.setCollectionFilter(value) },
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

        when (tab) {
            0 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (filtered.isEmpty()) {
                    item { EmptyCollectionHint() }
                }
                items(filtered, key = { "${it.source}:${it.sourceId}" }) { track ->
                    AuxenTrackRow(
                        track = track,
                        isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                        onPlay = { viewModel.play(track) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        onLongPress = { sheetTrack = track },
                        trailing = {
                            IconButton(onClick = { viewModel.enqueue(track) }) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to queue")
                            }
                        },
                    )
                }
            }
            1 -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                items(groupAlbums(filtered), key = { "${it.album}|${it.albumArtist}" }) { album ->
                    AlbumCard(
                        title = album.album,
                        artist = album.albumArtist,
                        artUrl = album.artUrl,
                        source = album.tracks.firstOrNull()?.source,
                        onClick = { onOpenAlbum(album) },
                        onPlay = { viewModel.playAll(album.tracks) },
                    )
                }
            }
            2 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(groupArtists(filtered), key = { it.artist }) { artist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenArtist(artist.artist) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(artist.artist, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${artist.tracks.size} favorited",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (playlists.isEmpty()) {
                    item { EmptyCollectionHint(message = "No playlists yet — long-press any track to add one.") }
                }
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPlaylist(playlist.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Parse once per color (not every recomposition); fall back
                        // to the brand token, never a drifting literal. The 1dp
                        // outline keeps pale user colors visible on the white
                        // light-theme surface (polish P1, Fix 3).
                        val dotColor = remember(playlist.color) {
                            playlist.color
                                ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                                ?: AuxenColors.AmberPrimary
                        }
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(dotColor, CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                    }
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
        )
    }
}

@Composable
private fun EmptyCollectionHint(message: String = "Tap the heart on any track to add it here.") {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
