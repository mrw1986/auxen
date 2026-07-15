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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import io.github.auxen.R
import io.github.auxen.data.groupAlbums
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.EmptyState
import io.github.auxen.ui.components.SourceBadge
import io.github.auxen.ui.components.TrackActionSheet
import io.github.auxen.ui.theme.AuxenColors

/**
 * Album detail — desktop AlbumDetailView: header (art, title, artist link,
 * meta, Play All + Shuffle) and the ordered track list.
 */
@UnstableApi
@Composable
fun AlbumDetailScreen(
    viewModel: PlayerViewModel,
    album: String,
    artist: String,
    onOpenArtist: (String) -> Unit,
) {
    val localTracks by viewModel.localTracks.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var sheetTrack by remember { mutableStateOf<Track?>(null) }

    val pool = (localTracks + favorites).distinctBy { "${it.source}:${it.sourceId}" }
    val group = groupAlbums(pool).firstOrNull { it.album == album && it.albumArtist == artist }
    val tracks = group?.tracks ?: emptyList()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                AsyncImage(
                    model = group?.artUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(140.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(album, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        artist,
                        style = MaterialTheme.typography.bodyLarge,
                        // Text tint (clickable artist link) -- resolves to the contrast-safe
                        // primary (final-review fix round, Minor #2); the Play All button
                        // below is a container behind BgDeep content and correctly keeps the
                        // raw brand amber.
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenArtist(artist) },
                    )
                    val meta = listOfNotNull(
                        group?.year?.toString(),
                        "${tracks.size} tracks",
                    ).joinToString(" • ")
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    tracks.firstOrNull()?.let { SourceBadge(it.source, Modifier.padding(top = 6.dp)) }
                }
            }
        }
        item {
            Row(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = { viewModel.playAll(tracks) },
                    enabled = tracks.isNotEmpty(),
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
                    onClick = { viewModel.playAll(tracks, shuffled = true) },
                    enabled = tracks.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Text("Shuffle")
                }
            }
        }
        if (tracks.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Album,
                    title = stringResource(R.string.empty_album_tracks_title),
                    subtitle = stringResource(R.string.empty_album_tracks_subtitle),
                )
            }
        }
        items(tracks, key = { "${it.source}:${it.sourceId}" }) { track ->
            AuxenTrackRow(
                track = track,
                isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                onPlay = { viewModel.play(track) },
                onToggleFavorite = { viewModel.toggleFavorite(track) },
                onLongPress = { sheetTrack = track },
            )
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
