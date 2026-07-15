package io.github.auxen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.R
import io.github.auxen.data.AlbumGroup
import io.github.auxen.data.LibrarySort
import io.github.auxen.data.groupAlbums
import io.github.auxen.data.groupArtists
import io.github.auxen.data.sortAlbums
import io.github.auxen.data.sortArtists
import io.github.auxen.data.sortTracks
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.ArtistRow
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import io.github.auxen.ui.components.LoadingState
import io.github.auxen.ui.components.TrackActionSheet

private val TAB_LABELS = listOf("Albums", "Artists", "Tracks")

private fun sortOptionsFor(tab: Int): List<LibrarySort> = when (tab) {
    0 -> listOf(LibrarySort.RECENTLY_ADDED, LibrarySort.NAME, LibrarySort.ARTIST)
    1 -> listOf(LibrarySort.NAME, LibrarySort.TRACK_COUNT, LibrarySort.RECENTLY_ADDED)
    else -> listOf(LibrarySort.RECENTLY_ADDED, LibrarySort.NAME, LibrarySort.ARTIST)
}

private fun sortLabel(sort: LibrarySort): String = when (sort) {
    LibrarySort.RECENTLY_ADDED -> "Recently Added"
    LibrarySort.NAME -> "Name"
    LibrarySort.ARTIST -> "Artist"
    LibrarySort.TRACK_COUNT -> "Track Count"
}

/**
 * Library — desktop LibraryView: Albums/Artists/Tracks tabs, per-tab sort +
 * direction (persisted), album grid, artist list, track list.
 */
@UnstableApi
@Composable
fun LibraryScreen(
    viewModel: PlayerViewModel,
    onOpenAlbum: (AlbumGroup) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracks by viewModel.localTracks.collectAsState()
    val loading by viewModel.libraryLoading.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val tab by viewModel.libraryTab.collectAsState()
    val sort by viewModel.librarySort.collectAsState()
    val ascending by viewModel.librarySortAscending.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(Unit) { viewModel.loadLibrary() }

    // Group + sort the active tab's data ONCE, reused for both the header count
    // (Fix 11 — derived from the active tab, not always "N tracks") and the body
    // (Fix 4 — an EmptyState when that list is empty, never a blank void).
    val albums = if (tab == 0) sortAlbums(groupAlbums(tracks), sort, ascending) else emptyList()
    val artists = if (tab == 1) sortArtists(groupArtists(tracks), sort, ascending) else emptyList()
    val sortedTracks = if (tab != 0 && tab != 1) sortTracks(tracks, sort, ascending) else emptyList()
    val countLabel = when (tab) {
        0 -> "${albums.size} albums"
        1 -> "${artists.size} artists"
        else -> "${sortedTracks.size} tracks"
    }

    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TAB_LABELS.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = tab == index,
                    onClick = { viewModel.setLibraryTab(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TAB_LABELS.size),
                ) { Text(label) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                countLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { sortMenuOpen = true }) { Text(sortLabel(sort)) }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                sortOptionsFor(tab).forEach { option ->
                    DropdownMenuItem(
                        text = { Text(sortLabel(option)) },
                        // Fix 35 — mark the applied sort so it's visible at a glance.
                        trailingIcon = {
                            if (option == sort) {
                                Icon(Icons.Filled.Check, contentDescription = "Selected")
                            }
                        },
                        onClick = {
                            viewModel.setLibrarySort(option)
                            sortMenuOpen = false
                        },
                    )
                }
            }
            IconButton(onClick = { viewModel.toggleLibrarySortDirection() }) {
                Icon(
                    if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    contentDescription = if (ascending) "Ascending" else "Descending",
                )
            }
        }

        // Gate on [loading]: a spinner while the local scan runs (and there's
        // nothing yet to show), the EmptyState only once loaded-and-empty, so
        // the empty state never flashes before [loadLibrary] resolves. Guarded
        // by `list.isEmpty()` so a reload with data already present keeps the
        // content on screen instead of flashing a spinner over it.
        when (tab) {
            0 -> if (loading && albums.isEmpty()) {
                LoadingState()
            } else if (albums.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Album,
                    title = stringResource(R.string.empty_library_albums_title),
                    subtitle = stringResource(R.string.empty_library_albums_subtitle),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                ) {
                    items(albums, key = { "${it.album}|${it.albumArtist}" }) { album ->
                        AlbumCard(
                            title = album.album,
                            artist = album.albumArtist,
                            artUrl = album.artUrl,
                            source = null,
                            onClick = { onOpenAlbum(album) },
                            onPlay = { viewModel.playAll(album.tracks) },
                        )
                    }
                }
            }
            1 -> if (loading && artists.isEmpty()) {
                LoadingState()
            } else if (artists.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Person,
                    title = stringResource(R.string.empty_library_artists_title),
                    subtitle = stringResource(R.string.empty_library_artists_subtitle),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(artists, key = { it.artist }) { artist ->
                        ArtistRow(
                            name = artist.artist,
                            subtitle = "${artist.tracks.size} tracks",
                            artUrl = artist.artUrl,
                            onClick = { onOpenArtist(artist.artist) },
                        )
                    }
                }
            }
            else -> if (loading && sortedTracks.isEmpty()) {
                LoadingState()
            } else if (sortedTracks.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.MusicNote,
                    title = stringResource(R.string.empty_library_tracks_title),
                    subtitle = stringResource(R.string.empty_library_tracks_subtitle),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sortedTracks, key = { "${it.source}:${it.sourceId}" }) { track ->
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
