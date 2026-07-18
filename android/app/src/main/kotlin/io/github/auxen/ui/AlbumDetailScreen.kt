package io.github.auxen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.R
import io.github.auxen.data.groupAlbums
import io.github.auxen.data.groupTracksByDisc
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.components.ArtworkImage
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import io.github.auxen.ui.components.LoadingState
import io.github.auxen.ui.components.SourceBadge
import io.github.auxen.ui.components.TrackActionSheet
import io.github.auxen.ui.components.TrackInfoSheet
import io.github.auxen.ui.theme.AuxenColors

/**
 * Album detail — desktop AlbumDetailView: header (art, title, artist link,
 * meta, Play All + Shuffle) and the ordered track list.
 *
 * The local MediaStore-derived grouping is the offline base and always renders
 * first; remote enrichment (real title/artist/type/year, high-res cover art and
 * the COMPLETE tracklist) is overlaid on top as it lands. An album we cannot
 * resolve a Tidal id for renders exactly as it does today.
 */
@UnstableApi
@Composable
fun AlbumDetailScreen(
    viewModel: PlayerViewModel,
    album: String,
    artist: String,
    tidalAlbumId: String? = null,
    onOpenArtist: (name: String, tidalArtistId: String?) -> Unit,
) {
    val localTracks by viewModel.localTracks.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var infoTrack by remember { mutableStateOf<Track?>(null) }

    // The track pool comes from the local scan ([libraryLoading]) and the
    // favorites DB read ([collectionLoading]); until both resolve an unresolved
    // album would read as "no tracks", so show a spinner instead of the
    // EmptyState while either is still in flight (polish C2).
    val libraryLoading by viewModel.libraryLoading.collectAsState()
    val collectionLoading by viewModel.collectionLoading.collectAsState()
    val loading = libraryLoading || collectionLoading

    val pool = (localTracks + favorites).distinctBy { "${it.source}:${it.sourceId}" }
    val group = groupAlbums(pool).firstOrNull { it.album == album && it.albumArtist == artist }
    val tracks = group?.tracks ?: emptyList()

    val detail by viewModel.albumDetail.collectAsState()
    val albumDetailLoading by viewModel.albumDetailLoading.collectAsState()
    // ANY Tidal track in this group resolves the album id: trackRelations reads
    // related(track, "albums").first(), and every track here is grouped by this
    // album already. So — unlike the artist screen, whose seed must match on the
    // PRIMARY artist to avoid resolving a compilation's albumArtist — no further
    // guard is needed here.
    val seedTidalTrackId = tracks.firstOrNull { it.isTidal }?.sourceId
    LaunchedEffect(album, artist, tidalAlbumId, seedTidalTrackId) {
        viewModel.loadAlbumDetail(album, artist, tidalAlbumId, seedTidalTrackId)
    }
    // The remote tracklist is the COMPLETE album; the local group holds only the
    // tracks the user happens to own. Remote wins when it has anything, so a
    // Tidal-only album is fully playable; an empty/absent remote list falls back
    // to the local group, leaving a non-Tidal album exactly as it is today.
    val displayTracks = detail?.tracks?.takeIf { it.isNotEmpty() } ?: tracks
    // Split into "Disc N" sections only when the album spans >1 disc; a single-
    // disc album yields one null-disc section (no header) with its tracks in the
    // same order as before (polish Fix A).
    val discSections = groupTracksByDisc(displayTracks)

    // `TidalAlbumDetail.title`/`.artistName` are NON-NULL Strings (projected in
    // `TidalInternalAlbum.kt`, `artistName` via `.orEmpty()`), so a `?:` on the nullable
    // `detail` alone would NOT guard a document that resolved but carried no
    // usable text — it would blank the header that the local group had filled
    // correctly. That is not hypothetical on this API: the same "linked but
    // absent from `included`" behaviour that forced Task 3's biography fallback
    // applies to `artists` here. Hence isNotBlank(), not just null-safety.
    val displayTitle = detail?.title?.takeIf { it.isNotBlank() } ?: album
    val displayArtist = detail?.artistName?.takeIf { it.isNotBlank() } ?: artist

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ArtworkImage(
                    // Remote cover art (high-res, best-fit) wins when present;
                    // the local group's art is the offline fallback.
                    model = detail?.artworkUrl ?: group?.artUrl,
                    contentDescription = null,
                    glyphSize = 48.dp,
                    modifier = Modifier.size(140.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(displayTitle, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        displayArtist,
                        style = MaterialTheme.typography.bodyLarge,
                        // Text tint (clickable artist link) -- resolves to the contrast-safe
                        // primary (final-review fix round, Minor #2); the Play All button
                        // below is a container behind BgDeep content and correctly keeps the
                        // raw brand amber.
                        color = MaterialTheme.colorScheme.primary,
                        // The remote artist id skips the artist screen's own id
                        // resolution entirely when we have it.
                        modifier = Modifier.clickable { onOpenArtist(displayArtist, detail?.artistId) },
                    )
                    val meta = listOfNotNull(
                        detail?.albumType,
                        detail?.releaseYear?.toString() ?: group?.year?.toString(),
                        "${displayTracks.size} tracks",
                    ).joinToString(" • ")
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // A resolved remote album IS a Tidal album, including the
                    // Tidal-only case where there is no local track to read a
                    // source off at all.
                    val badgeSource = if (detail != null) Source.TIDAL else tracks.firstOrNull()?.source
                    badgeSource?.let { SourceBadge(it, Modifier.padding(top = 6.dp)) }
                }
            }
        }
        item {
            Row(modifier = Modifier.padding(16.dp)) {
                Button(
                    // displayTracks, so a Tidal-only album (opened from an
                    // enriched discography with no local files) is fully playable
                    // through the existing internal-v1 pipeline.
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
        // displayTracks, not tracks: a Tidal-only album has an empty local group
        // but a full remote tracklist, and must render the list rather than
        // "no tracks".
        if (displayTracks.isEmpty()) {
            item {
                // `albumDetailLoading` may spin the EMPTY list — never the
                // populated one (we are inside displayTracks.isEmpty(), so there
                // is no local list here to block, and `loading` is untouched).
                // Without it a Tidal-only album reads "no tracks" for the whole
                // remote round-trip, since the local `loading` is already false.
                // [PlayerViewModel.loadAlbumDetail] clears this flag on EVERY
                // exit, so the spinner always settles: the launched job's
                // `finally`, AND its early return, which is the path an album
                // genuinely not on Tidal takes — no job is launched for it, so
                // there is no `finally` to clear it and the early return must
                // (and does) do it itself. Keep those two in sync with this.
                if (loading || albumDetailLoading) {
                    LoadingState()
                } else {
                    EmptyState(
                        icon = Icons.Filled.Album,
                        title = stringResource(R.string.empty_album_tracks_title),
                        subtitle = stringResource(R.string.empty_album_tracks_subtitle),
                    )
                }
            }
        }
        discSections.forEach { section ->
            section.discNumber?.let { disc ->
                item(key = "disc-$disc") {
                    Text(
                        "Disc $disc",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
            }
            items(section.tracks, key = { "${it.source}:${it.sourceId}" }) { track ->
                AuxenTrackRow(
                    track = track,
                    trackNumber = track.trackNumber,
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
            onShowInfo = { infoTrack = track },
        )
    }

    infoTrack?.let { track ->
        val detail by viewModel.trackDetail.collectAsState()
        val detailLoading by viewModel.trackDetailLoading.collectAsState()
        LaunchedEffect(track) { viewModel.loadTrackDetail(track) }
        TrackInfoSheet(
            detail = detail,
            loading = detailLoading,
            onDismiss = { infoTrack = null; viewModel.clearTrackDetail() },
        )
    }
}
