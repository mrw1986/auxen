package io.github.auxen.playback.browse

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import io.github.auxen.Graph
import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.model.Track

/**
 * Pure builder of the Android Auto browse tree: repo snapshots + browse ids ->
 * Media3 MediaItem nodes. No Android framework calls beyond MediaItem/MediaMetadata
 * construction, no network, no DB — callers pass fetched data in. Track leaves reuse
 * Graph.mediaItemFor, so any leaf this returns is playable through the existing
 * ResolvingDataSource. Folder nodes carry isBrowsable=true/isPlayable=false.
 */
@UnstableApi
class AuxenBrowseTree {

    fun root(): MediaItem = folder(AuxenBrowseIds.ROOT, "Auxen", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun rootChildren(): List<MediaItem> = listOf(
        folder(AuxenBrowseIds.RECENT, "Recently played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        folder(AuxenBrowseIds.PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        folder(AuxenBrowseIds.ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        folder(AuxenBrowseIds.ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        folder(AuxenBrowseIds.FAVORITES, "Favorite tracks", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
    )

    fun playlistCategory(playlists: List<PlaylistEntity>): List<MediaItem> =
        playlists.map { folder(AuxenBrowseIds.playlist(it.id), it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
            .ifEmpty { listOf(emptyNode("No playlists yet")) }

    fun albumCategory(albums: List<FavoriteAlbumEntity>): List<MediaItem> =
        albums.map {
            folder(AuxenBrowseIds.album(it.sourceId), it.title, MediaMetadata.MEDIA_TYPE_ALBUM, it.artUrl, it.artist)
        }.ifEmpty { listOf(emptyNode("No saved albums yet")) }

    fun artistCategory(artists: List<FavoriteArtistEntity>): List<MediaItem> =
        artists.map {
            folder(AuxenBrowseIds.artist(it.sourceId), it.name, MediaMetadata.MEDIA_TYPE_ARTIST, it.artUrl)
        }.ifEmpty { listOf(emptyNode("No followed artists yet")) }

    /** [cachedIds] are the bare Tidal ids with a full copy on disk; those leaves get Auto's "downloaded" badge. */
    fun trackList(tracks: List<Track>, cachedIds: Set<String> = emptySet()): List<MediaItem> =
        tracks.map { playableTrack(it, cachedIds) }.ifEmpty { listOf(emptyNode("Nothing here yet")) }

    fun albumDetail(tidalId: String, tracks: List<Track>, cachedIds: Set<String> = emptySet()): List<MediaItem> =
        if (tracks.isEmpty()) listOf(emptyNode("No tracks"))
        else listOf(playAll(AuxenBrowseIds.playAllAlbum(tidalId))) + tracks.map { playableTrack(it, cachedIds) }

    fun artistDetail(tidalId: String, tracks: List<Track>, cachedIds: Set<String> = emptySet()): List<MediaItem> =
        if (tracks.isEmpty()) listOf(emptyNode("No tracks"))
        else listOf(playAll(AuxenBrowseIds.playAllArtist(tidalId))) + tracks.map { playableTrack(it, cachedIds) }

    /**
     * [Graph.mediaItemFor] doesn't stamp `isPlayable` on its [MediaMetadata] (none of
     * its other call sites -- queue/player -- need that flag), but Android Auto's
     * browse contract does: a leaf without it won't render as tappable. Rebuild on
     * top of the shared mapper rather than touching it, so this stays local to the
     * browse tree instead of changing behavior for every other consumer.
     *
     * A cached Tidal leaf also gets the standard download-status extra, on a copy
     * of the shared mapper's extras so the track JSON and queue uid stay. The Tidal
     * gate matters: cache ids are bare Tidal ids, and a MediaStore id can be the same number.
     */
    private fun playableTrack(track: Track, cachedIds: Set<String>): MediaItem {
        val base = Graph.mediaItemFor(track)
        val metadata = base.mediaMetadata.buildUpon().setIsPlayable(true)
        if (track.isCachedIn(cachedIds)) {
            val extras = Bundle().apply {
                base.mediaMetadata.extras?.let { putAll(it) }
                putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
            }
            metadata.setExtras(extras)
        }
        return base.buildUpon().setMediaMetadata(metadata.build()).build()
    }

    fun emptyNode(text: String): MediaItem = inert(mediaId = "auxen://browse/empty", title = text)

    fun errorNode(): MediaItem = inert(mediaId = "auxen://browse/error", title = "Couldn't load — check connection")

    /** Single-node builder for onGetItem: folders + play-all only. Track items are
     *  served from the queue caches, so a bare "SOURCE:sourceId" returns null here. */
    fun item(mediaId: String): MediaItem? = when (val t = AuxenBrowseIds.parse(mediaId)) {
        BrowseTarget.Root -> root()
        BrowseTarget.Recent -> folder(AuxenBrowseIds.RECENT, "Recently played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        BrowseTarget.Playlists -> folder(AuxenBrowseIds.PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        BrowseTarget.Albums -> folder(AuxenBrowseIds.ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        BrowseTarget.Artists -> folder(AuxenBrowseIds.ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
        BrowseTarget.Favorites -> folder(AuxenBrowseIds.FAVORITES, "Favorite tracks", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        is BrowseTarget.PlayAllAlbum -> playAll(AuxenBrowseIds.playAllAlbum(t.tidalId))
        is BrowseTarget.PlayAllArtist -> playAll(AuxenBrowseIds.playAllArtist(t.tidalId))
        else -> null
    }

    private fun folder(
        id: String, title: String, mediaType: Int, artUrl: String? = null, subtitle: String? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artUrl?.let(android.net.Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build(),
        )
        .build()

    private fun playAll(id: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("▶ Play all")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    private fun inert(mediaId: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()
}
