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
import io.github.auxen.data.AlbumGroup
import io.github.auxen.data.groupAlbums
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.provider.tidal.dedupRemoteAlbums
import io.github.auxen.provider.tidal.mergeArtistTracks
import io.github.auxen.ui.components.AlbumActionSheet
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.ArtistRow
import io.github.auxen.ui.components.ArtworkImage
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import io.github.auxen.ui.components.LoadingState
import io.github.auxen.ui.components.SectionHeader
import io.github.auxen.ui.components.TrackActionSheet
import io.github.auxen.ui.components.TrackInfoSheet
import io.github.auxen.ui.components.albumTargetFor
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
    onOpenAlbumForTrack: (album: String, artist: String, seedTrackId: String?) -> Unit,
) {
    val localTracks by viewModel.localTracks.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val cachedIds by viewModel.cachedIds.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var infoTrack by remember { mutableStateOf<Track?>(null) }
    var albumSheet by remember { mutableStateOf<AlbumGroup?>(null) }
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

    val page by viewModel.artistPage.collectAsState()
    val artistDetailLoading by viewModel.artistDetailLoading.collectAsState()
    // The seed MUST be a track whose PRIMARY artist is this screen's artist.
    // `tracks` is filtered on `it.artist == artist || it.albumArtist == artist`,
    // and trackRelations takes related(track,"artists").first() — the primary
    // artist. So seeding off an albumArtist-only match (a compilation, a
    // "Various Artists" album) would resolve a DIFFERENT artist's id and hang
    // the wrong page on this screen. `it.artist` is the main-artist name for
    // Tidal tracks (TidalProvider.toTrack maps TrackDto.artist.name), so this
    // comparison is exactly the right guard.
    val seedTidalTrackId = tracks.firstOrNull { it.isTidal && it.artist == artist }?.sourceId
    LaunchedEffect(artist, tidalArtistId, seedTidalTrackId) {
        viewModel.loadArtistDetail(artist, tidalArtistId, seedTidalTrackId)
    }

    // Tidal top tracks (popularity order) first, then local tracks not among
    // them — the desktop's merge (window.py:1409-1422). With no page this
    // returns `tracks` unchanged, so the offline base is never at risk.
    //
    // CAPPED AT 50. pages/artist's 'Top Tracks' module reported 300 items and
    // this endpoint REFUSES a `limit` param (400), so the cap is ours to apply.
    // 50 is what the artist page shipped with (artistTopTracks(limit = 50)) and
    // what the desktop uses (get_top_tracks(limit=50)) — this is deliberately
    // not a behaviour change.
    val topTracks = remember(page) { page?.topTracks.orEmpty().take(TOP_TRACKS_DISPLAY_LIMIT) }
    val displayTracks = remember(topTracks, tracks) { mergeArtistTracks(topTracks, tracks) }

    // Every ALBUM_LIST Tidal gave us, in page order, under ITS OWN title —
    // 'Albums', 'EP & Singles', 'Live albums', 'Compilations', 'Appears On'. We
    // never match on those strings (see TidalArtistPage.kt's KDoc): a rename, a
    // new category or a localisation all just work.
    //
    // The user's LOCAL albums merge into the FIRST section, which assumes the
    // first ALBUM_LIST is the primary discography — true for both probed
    // artists. It is a POSITION assumption, deliberately chosen over a TITLE
    // match because position survives renaming and localisation. If it is ever
    // wrong the failure is cosmetic (local albums under a row headed 'EP &
    // Singles'), never data loss.
    //
    // Each section is deduped against the RAW local titles — dedupRemoteAlbums
    // normalizes both sides itself, and pre-lowercasing here would be a lossy
    // half of what it already does.
    val localTitles = remember(albums) { albums.map { it.album }.toSet() }
    val albumSections = remember(page, localTitles) {
        page?.albumSections.orEmpty().map { section ->
            section.title to dedupRemoteAlbums(localTitles = localTitles, remote = section.albums)
        }
    }
    val artistSections = page?.artistSections.orEmpty()
    val totalAlbums = albums.size + albumSections.sumOf { (_, remote) -> remote.size }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    // The page's own profile art (480x480 — the artist size; the
                    // 640 album size does not render for a picture) wins when
                    // present; the local album art is the offline fallback.
                    model = page?.artworkUrl ?: tracks.firstNotNullOfOrNull { it.albumArtUrl },
                    contentDescription = null,
                    glyph = Icons.Filled.Person,
                    glyphSize = 40.dp,
                    modifier = Modifier.size(96.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(artist, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        // Counts describe what is actually on screen, across
                        // every album section, not the local subset.
                        "$totalAlbums albums • ${displayTracks.size} tracks",
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
        // no error. This text comes from ARTIST_HEADER.bio.text, the biography
        // official v2 has none of at our tier, and it is display-ready:
        // artistPageFrom already ran formatTidalBio over the endpoint's
        // `<br>`/`[wimpLink]` markup, so no raw tag can reach this Text.
        page?.biography?.let { biography ->
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
        if (albumSections.isEmpty()) {
            // No Tidal page (not logged in, no id resolved, the call failed) —
            // the offline-safe local base, exactly as it renders today.
            if (albums.isNotEmpty()) {
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
                                onLongPress = { albumSheet = album },
                                // Carousel item — a fill-width card has no width
                                // constraint inside a LazyRow, so pin it here.
                                modifier = Modifier.width(150.dp),
                            )
                        }
                    }
                }
            }
        } else {
            albumSections.forEachIndexed { index, (sectionTitle, remoteAlbums) ->
                // The user's local albums join the FIRST section only — see the
                // position-assumption note above. Sections after it are Tidal-only.
                val localHere = if (index == 0) albums else emptyList()
                if (localHere.isEmpty() && remoteAlbums.isEmpty()) return@forEachIndexed
                // Tidal's OWN title, verbatim. Never matched on, only rendered.
                item(key = "section-header-$index") { SectionHeader(sectionTitle) }
                item(key = "section-row-$index") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(localHere, key = { "${it.album}|${it.albumArtist}" }) { album ->
                            AlbumCard(
                                title = album.album,
                                artist = null,
                                artUrl = album.artUrl,
                                source = album.tracks.firstOrNull()?.source,
                                onClick = { onOpenAlbum(album.album, album.albumArtist, null) },
                                onPlay = { viewModel.playAll(album.tracks) },
                                onLongPress = { albumSheet = album },
                                modifier = Modifier.width(150.dp),
                            )
                        }
                        items(remoteAlbums, key = { "tidal:${it.id}" }) { summary ->
                            AlbumCard(
                                title = summary.title,
                                artist = null,
                                artUrl = summary.artworkUrl,
                                source = Source.TIDAL,
                                // KNOWN LIMITATION (plan, Open question 4): the
                                // artist arg is this screen's artist, which is
                                // WRONG for an 'Appears On' album (that is a
                                // different artist's record). It self-corrects —
                                // tidalAlbumId is non-null, so AlbumDetailScreen
                                // fetches the album's own document and shows the
                                // real artist; this arg only seeds the local
                                // grouping fallback. Same behaviour the shipped
                                // tidal-only row already has.
                                onClick = { onOpenAlbum(summary.title, page?.name ?: artist, summary.id) },
                                // No local tracks to enqueue — a tap opens the
                                // album, whose remote tracklist is playable.
                                onPlay = null,
                                modifier = Modifier.width(150.dp),
                            )
                        }
                    }
                }
            }
        }
        // Every ARTIST_LIST, under its own title — 'Fans Also Like', and
        // 'Influencers' for the artists that have one. Selecting on type rather
        // than title is what makes the second row ship at all.
        artistSections.forEachIndexed { index, section ->
            item(key = "artists-header-$index") { SectionHeader(section.title) }
            item(key = "artists-row-$index") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // These now carry REAL photos (ArtistLink.artworkUrl from the
                    // item's `picture` at 480x480 — the artist size; 640 is the
                    // album size and does not render for a picture). Slice 1's
                    // similar artists were null by construction and fell back to
                    // ArtistRow's Person glyph; that glyph is still the fallback
                    // for an artist whose `picture` is null.
                    //
                    // Deliberately UNKEYED. This API demonstrably repeats a
                    // linkage entry (the probe of artist 8405 returned
                    // `Fixxxer (Remastered)` twice through the same mechanism;
                    // see dedupRemoteAlbums' KDoc), and keying on `link.id`
                    // turns any duplicate into a hard "key was already used"
                    // CRASH. The cost of the default index key here is nil (the
                    // list is short, static and never reordered); the cost of
                    // being wrong is the whole screen.
                    items(section.artists) { link ->
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
                // `loading` is already false.
                // (That round-trip is now id-resolution + ONE pages/artist call,
                // where it used to be id-resolution + four sequential reads.)
                // The finally in loadArtistDetail always clears this, so
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
                    isCached = track.isCachedIn(cachedIds),
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
            onShowInfo = { infoTrack = track },
            onGoToAlbum = albumTargetFor(track)?.let { target ->
                { onOpenAlbumForTrack(target.album, target.albumArtist, track.sourceId.takeIf { track.isTidal }) }
            },
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
            onGoToArtist = { onOpenArtist(group.albumArtist, null) },
        )
    }
}

/**
 * How many of `pages/artist`'s 'Top Tracks' the screen renders.
 *
 * The module reported 300 items and the endpoint REFUSES a `limit` param (it
 * answers 400), so the cap is the client's to apply. 50 is what this page
 * shipped with (`artistTopTracks(limit = 50)`) and what the desktop uses
 * (`get_top_tracks(limit=50)`) — deliberately not a behaviour change.
 */
private const val TOP_TRACKS_DISPLAY_LIMIT = 50
