package io.github.auxen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.R
import io.github.auxen.data.groupAlbums
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.provider.tidal.dedupRemoteAlbums
import io.github.auxen.provider.tidal.mergeArtistTracks
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.ArtistRow
import io.github.auxen.ui.components.ArtworkImage
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import io.github.auxen.ui.components.LoadingState
import io.github.auxen.ui.components.SectionHeader
import io.github.auxen.ui.components.TrackActionSheet
import io.github.auxen.ui.theme.AuxenColors

/**
 * Artist detail — desktop ArtistDetailView: circular art header, Play All +
 * Shuffle, horizontally-scrolling Albums row, track list.
 *
 * The local MediaStore-derived grouping is the offline base and always renders
 * first; remote enrichment (biography, profile art, discography, top tracks,
 * similar artists) is overlaid on top as it lands. An artist we cannot resolve a
 * Tidal id for renders exactly as it does today.
 */
@UnstableApi
@Composable
fun ArtistDetailScreen(
    viewModel: PlayerViewModel,
    artist: String,
    tidalArtistId: String? = null,
    onOpenAlbum: (album: String, artist: String, tidalAlbumId: String?) -> Unit,
    onOpenArtist: (name: String, tidalArtistId: String?) -> Unit,
) {
    val localTracks by viewModel.localTracks.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    // Hoisted above the LazyColumn deliberately: state remembered inside an
    // `item {}` is disposed when that item scrolls out of the viewport, which
    // would silently collapse an expanded biography on scroll-back.
    var bioExpanded by remember { mutableStateOf(false) }

    // The track pool comes from the local scan ([libraryLoading]) and the
    // favorites DB read ([collectionLoading]); until both resolve an unresolved
    // artist would read as "no tracks", so show a spinner instead of the
    // EmptyState while either is still in flight (polish C2).
    val libraryLoading by viewModel.libraryLoading.collectAsState()
    val collectionLoading by viewModel.collectionLoading.collectAsState()
    val loading = libraryLoading || collectionLoading

    val pool = (localTracks + favorites).distinctBy { "${it.source}:${it.sourceId}" }
    val tracks = pool.filter { it.artist == artist || it.albumArtist == artist }
    val albums = groupAlbums(tracks)

    val detail by viewModel.artistDetail.collectAsState()
    val remoteAlbums by viewModel.artistAlbums.collectAsState()
    val topTracks by viewModel.artistTopTracks.collectAsState()
    val artistDetailLoading by viewModel.artistDetailLoading.collectAsState()
    // The seed MUST be a track whose PRIMARY artist is this screen's artist.
    // `tracks` is filtered on `it.artist == artist || it.albumArtist == artist`,
    // and trackRelations takes related(track,"artists").first() — the primary
    // artist. So seeding off an albumArtist-only match (a compilation, a
    // "Various Artists" album) would resolve a DIFFERENT artist's id and hang
    // the wrong bio, discography and top tracks on this page. `it.artist` is the
    // main-artist name for Tidal tracks (TidalProvider.toTrack maps
    // TrackDto.artist.name), so this comparison is exactly the right guard.
    val seedTidalTrackId = tracks.firstOrNull { it.isTidal && it.artist == artist }?.sourceId
    LaunchedEffect(artist, tidalArtistId, seedTidalTrackId) {
        viewModel.loadArtistDetail(artist, tidalArtistId, seedTidalTrackId)
    }
    // Tidal top tracks (popularity order) first, then local tracks not among
    // them — the desktop's merge (window.py:1409-1422). With no top tracks this
    // returns `tracks` unchanged, so the offline base is never at risk.
    val displayTracks = remember(topTracks, tracks) { mergeArtistTracks(topTracks, tracks) }
    // Local albums render first and Tidal-only albums are appended, deduped by
    // lowercase title — the desktop's union (window.py:1394-1407).
    val tidalOnlyAlbums = remember(albums, remoteAlbums) {
        dedupRemoteAlbums(
            localTitles = albums.map { it.album.lowercase() }.toSet(),
            remote = remoteAlbums,
        )
    }
    val similarArtists = detail?.similarArtists.orEmpty()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    // Remote profile art wins when present; the local album art
                    // is the offline fallback.
                    model = detail?.artworkUrl ?: tracks.firstNotNullOfOrNull { it.albumArtUrl },
                    contentDescription = null,
                    glyph = Icons.Filled.Person,
                    glyphSize = 40.dp,
                    modifier = Modifier.size(96.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(artist, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        // Counts describe what is actually on screen, not the
                        // local subset.
                        "${albums.size + tidalOnlyAlbums.size} albums • ${displayTracks.size} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(modifier = Modifier.padding(16.dp)) {
                Button(
                    // displayTracks, so a Tidal-only artist (a similar-artist
                    // tap, or one resolved by name with no local files) is fully
                    // playable through the existing internal-v1 pipeline.
                    onClick = { viewModel.playAll(displayTracks) },
                    enabled = displayTracks.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuxenColors.AmberPrimary,
                        contentColor = AuxenColors.BgDeep,
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("Play All")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { viewModel.playAll(displayTracks, shuffled = true) },
                    enabled = displayTracks.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Text("Shuffle")
                }
            }
        }
        // A null biography renders nothing at all — no header, no placeholder,
        // no error. The repository has already exhausted its fallback by the
        // time this is null, and the desktop hides the section too.
        detail?.biography?.let { biography ->
            item { SectionHeader("Biography") }
            item {
                Text(
                    biography,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (bioExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { bioExpanded = !bioExpanded }
                        .padding(horizontal = 16.dp),
                )
            }
        }
        if (albums.isNotEmpty() || tidalOnlyAlbums.isNotEmpty()) {
            item { SectionHeader("Albums") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(albums, key = { "${it.album}|${it.albumArtist}" }) { album ->
                        AlbumCard(
                            title = album.album,
                            artist = null,
                            artUrl = album.artUrl,
                            source = album.tracks.firstOrNull()?.source,
                            onClick = { onOpenAlbum(album.album, album.albumArtist, null) },
                            onPlay = { viewModel.playAll(album.tracks) },
                            // Carousel item — a fill-width card has no width
                            // constraint inside a LazyRow, so pin it here.
                            modifier = Modifier.width(150.dp),
                        )
                    }
                    items(tidalOnlyAlbums, key = { "tidal:${it.id}" }) { summary ->
                        AlbumCard(
                            title = summary.title,
                            artist = null,
                            artUrl = summary.artworkUrl,
                            source = Source.TIDAL,
                            onClick = { onOpenAlbum(summary.title, detail?.name ?: artist, summary.id) },
                            // No local tracks to enqueue — a tap opens the album,
                            // whose remote tracklist (Task 7) is playable.
                            onPlay = null,
                            modifier = Modifier.width(150.dp),
                        )
                    }
                }
            }
        }
        if (similarArtists.isNotEmpty()) {
            item { SectionHeader("Similar artists") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // ArtistLink.artworkUrl is always null here (see its KDoc),
                    // so these render with ArtistRow's Person glyph — the same
                    // fallback the header uses.
                    //
                    // Deliberately UNKEYED. `similarArtists` comes straight off
                    // the linkage array via TidalDocumentView.related, which
                    // resolves 1:1 with no dedup — and this API demonstrably
                    // repeats a linkage entry (the on-device probe of artist
                    // 8405 returned `Fixxxer (Remastered)` twice; see
                    // dedupRemoteAlbums' KDoc). Keying on `link.id` would turn
                    // such a duplicate into a hard "key was already used" crash.
                    // The list is short, static and never reordered, so the
                    // default index key is both safe and sufficient.
                    items(similarArtists) { link ->
                        ArtistRow(
                            name = link.name,
                            artUrl = link.artworkUrl,
                            onClick = { onOpenArtist(link.name, link.id) },
                            // Carousel item — ArtistRow fills its parent's width,
                            // which is unbounded inside a LazyRow.
                            modifier = Modifier.width(200.dp),
                        )
                    }
                }
            }
        }
        // displayTracks, not tracks: a Tidal-only artist has an empty local list
        // but a full merged one, and must render the list rather than "no tracks".
        if (displayTracks.isEmpty()) {
            item {
                // `artistDetailLoading` may spin the EMPTY list — never the
                // populated one (we are inside displayTracks.isEmpty(), so there
                // is no local list here to block, and `loading` is untouched).
                // Without it a Tidal-only artist (a similar-artist tap) reads
                // "no tracks" for the whole remote round-trip, since the local
                // `loading` is already false — and that round-trip is the slow
                // path, because an empty biography forces a second sequential
                // fetch. The finally in loadArtistDetail always clears this, so
                // an artist genuinely not on Tidal still settles to EmptyState
                // rather than a stuck spinner.
                if (loading || artistDetailLoading) {
                    LoadingState()
                } else {
                    EmptyState(
                        icon = Icons.Filled.MusicNote,
                        title = stringResource(R.string.empty_artist_tracks_title),
                        subtitle = stringResource(R.string.empty_artist_tracks_subtitle),
                    )
                }
            }
        } else {
            // `artists/{id}/toptracks` is popularity-ordered by contract, so the
            // label is honest; with no top tracks the list is purely local and
            // "Tracks" is the honest label there.
            item { SectionHeader(if (topTracks.isNotEmpty()) "Top tracks" else "Tracks") }
            items(displayTracks, key = { "${it.source}:${it.sourceId}" }) { track ->
                AuxenTrackRow(
                    track = track,
                    isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                    onPlay = { viewModel.play(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                    onLongPress = { sheetTrack = track },
                )
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
