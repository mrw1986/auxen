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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.R
import io.github.auxen.data.AlbumGroup
import io.github.auxen.data.ArtistGroup
import io.github.auxen.data.groupAlbums
import io.github.auxen.data.groupArtists
import io.github.auxen.data.mergeCollectionAlbums
import io.github.auxen.data.mergeCollectionArtists
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AlbumActionSheet
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.ArtistActionSheet
import io.github.auxen.ui.components.ArtistRow
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import io.github.auxen.ui.components.LoadingState
import io.github.auxen.ui.components.TrackActionSheet
import io.github.auxen.ui.components.TrackInfoSheet
import io.github.auxen.ui.components.albumTargetFor
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
    onOpenAlbumForTrack: (album: String, artist: String, seedTrackId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val favorites by viewModel.favorites.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val favoriteAlbumRows by viewModel.favoriteAlbums.collectAsState()
    val favoriteArtistRows by viewModel.favoriteArtists.collectAsState()
    val syncStatus by viewModel.tidalCollectionStatus.collectAsState()
    val filter by viewModel.collectionFilter.collectAsState()
    val loading by viewModel.collectionLoading.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var infoTrack by remember { mutableStateOf<Track?>(null) }
    var albumSheet by remember { mutableStateOf<AlbumGroup?>(null) }
    var artistSheet by remember { mutableStateOf<ArtistGroup?>(null) }

    // Auto-pull the user's Tidal favorites (albums/artists/tracks/playlists) on
    // Collection open. The ViewModel throttles it (at most once per THROTTLE_MS,
    // shared with the app-resume trigger) and its isLoggedIn guard makes it a
    // no-op when signed out, so this is not a launch-time network surprise.
    LaunchedEffect(Unit) { viewModel.syncFavoritesFromTidal() }

    fun matches(track: Track): Boolean = when (filter) {
        "tidal" -> track.source == Source.TIDAL
        "local" -> track.source == Source.LOCAL
        else -> true
    }
    // The All/Tidal/Local chips must apply to the remote favorite rows too.
    fun matchesSource(source: String): Boolean = when (filter) {
        "tidal" -> source == Source.TIDAL.name
        "local" -> source == Source.LOCAL.name
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
                    // Drop the leading selection-check icon: with four equal
                    // segments its ~26dp eats the active tab's label width and
                    // clips "Playlists"/"Artists" at ~320-360dp. Removing it lets
                    // the active tab render at the same width the inactive tabs
                    // already fit their full labels in.
                    icon = {},
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
                IconButton(onClick = { viewModel.syncFavoritesFromTidal(force = true) }) {
                    Icon(Icons.Filled.Sync, contentDescription = "Sync favorites from Tidal")
                }
            }
        }
        // Transient sync feedback — plain human text (counts/names only, never a
        // token), also the on-device idempotency checkpoint's readout.
        syncStatus?.let { status ->
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Every tab renders the same centered EmptyState when its (filtered) list
        // is empty — the Albums grid and Artists list previously had no empty
        // state at all, so an empty or filtered-empty tab is now explained
        // uniformly across all four (polish P2, Fix 12). While [loading] (the
        // favorites/playlists DB reads still resolving) each tab shows a spinner
        // instead, so the EmptyState never flashes before the data arrives
        // (polish C2).
        when (tab) {
            0 -> if (loading && filtered.isEmpty()) {
                LoadingState()
            } else if (filtered.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.FavoriteBorder,
                    title = stringResource(R.string.empty_collection_tracks_title),
                    subtitle = stringResource(R.string.empty_collection_tracks_subtitle),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
            }
            1 -> {
                val albums = mergeCollectionAlbums(
                    local = groupAlbums(filtered),
                    remote = favoriteAlbumRows.filter { matchesSource(it.source) },
                )
                if (loading && albums.isEmpty()) {
                    LoadingState()
                } else if (albums.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Album,
                        title = stringResource(R.string.empty_collection_albums_title),
                        subtitle = stringResource(R.string.empty_collection_albums_subtitle),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    ) {
                        items(albums, key = { it.key }) { album ->
                            AlbumCard(
                                title = album.title,
                                artist = album.artist,
                                artUrl = album.artUrl,
                                source = album.source,
                                onClick = { onOpenAlbum(album.toAlbumGroup()) },
                                // A remote-only saved album has no local tracks to play.
                                onPlay = if (album.tracks.isNotEmpty()) {
                                    { viewModel.playAll(album.tracks) }
                                } else {
                                    null
                                },
                                // PRESERVE the existing album long-press action sheet (Play /
                                // Shuffle / Play Next / Enqueue / Add-to-Playlist), added in the
                                // 2026-07-18 context-menu work. The pre-rewrite code wired
                                // `onLongPress = { albumSheet = album }`; this rewrite MUST keep it
                                // or it silently drops the menu and orphans `albumSheet` +
                                // `AlbumActionSheet`. A remote-only saved album (no local tracks)
                                // has nothing to feed the sheet, so gate it.
                                onLongPress = album.toAlbumGroup().takeIf { it.tracks.isNotEmpty() }
                                    ?.let { grp -> { albumSheet = grp } },
                                onToggleFavorite = if (album.isRemoteFavorite) {
                                    { viewModel.toggleFavoriteAlbum(album) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
            2 -> {
                val artists = mergeCollectionArtists(
                    local = groupArtists(filtered),
                    remote = favoriteArtistRows.filter { matchesSource(it.source) },
                )
                if (loading && artists.isEmpty()) {
                    LoadingState()
                } else if (artists.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Person,
                        title = stringResource(R.string.empty_collection_artists_title),
                        subtitle = stringResource(R.string.empty_collection_artists_subtitle),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(artists, key = { it.key }) { artist ->
                            ArtistRow(
                                name = artist.name,
                                subtitle = if (artist.isRemoteFavorite && artist.trackCount == 0) {
                                    "Followed on Tidal"
                                } else {
                                    "${artist.trackCount} favorited"
                                },
                                artUrl = artist.artUrl,
                                onClick = { onOpenArtist(artist.name) },
                                // PRESERVE the existing artist long-press action sheet (Play /
                                // Shuffle / Play Next / Enqueue), added in the 2026-07-18
                                // context-menu work. The pre-rewrite code wired
                                // `onLongPress = { artistSheet = artist }`; keep it or it silently
                                // drops the menu and orphans `artistSheet` + `ArtistActionSheet`.
                                // The sheet needs a real track pool, which only a local group has —
                                // so wire it off `localGroup` (null for a follow-only remote artist,
                                // which correctly gets no menu).
                                onLongPress = artist.localGroup?.let { grp -> { artistSheet = grp } },
                                onToggleFavorite = if (artist.isRemoteFavorite) {
                                    { viewModel.toggleFavoriteArtist(artist) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
            else -> if (loading && playlists.isEmpty()) {
                LoadingState()
            } else if (playlists.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.QueueMusic,
                    title = stringResource(R.string.empty_collection_playlists_title),
                    subtitle = stringResource(R.string.empty_collection_playlists_subtitle),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
            onGoToArtist = { onOpenArtist(track.artist) },
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
            onGoToArtist = { onOpenArtist(group.albumArtist) },
        )
    }
    artistSheet?.let { group ->
        ArtistActionSheet(
            artistName = group.artist,
            onDismiss = { artistSheet = null },
            onPlay = { viewModel.playAll(group.tracks) },
            onShuffle = { viewModel.playAll(group.tracks, shuffled = true) },
            onPlayNext = { viewModel.playNextAll(group.tracks) },
            onEnqueue = { viewModel.enqueueAll(group.tracks) },
        )
    }
}
