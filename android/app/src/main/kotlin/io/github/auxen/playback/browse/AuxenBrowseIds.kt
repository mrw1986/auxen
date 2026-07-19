package io.github.auxen.playback.browse

/** Stable, parseable ids for Auxen's Android Auto browse folders. Track leaves keep
 *  their "SOURCE:sourceId" ids (built by Graph.mediaItemFor) and are NOT in this scheme. */
object AuxenBrowseIds {
    const val ROOT = "auxen://browse/root"
    const val RECENT = "auxen://browse/recent"
    const val PLAYLISTS = "auxen://browse/playlists"
    const val ALBUMS = "auxen://browse/albums"
    const val ARTISTS = "auxen://browse/artists"
    const val FAVORITES = "auxen://browse/favorites"

    fun playlist(id: Long) = "auxen://browse/playlist/$id"
    fun album(tidalId: String) = "auxen://browse/album/$tidalId"
    fun artist(tidalId: String) = "auxen://browse/artist/$tidalId"
    fun playAllAlbum(tidalId: String) = "auxen://browse/playall/album/$tidalId"
    fun playAllArtist(tidalId: String) = "auxen://browse/playall/artist/$tidalId"

    fun parse(mediaId: String): BrowseTarget = when {
        mediaId == ROOT -> BrowseTarget.Root
        mediaId == RECENT -> BrowseTarget.Recent
        mediaId == PLAYLISTS -> BrowseTarget.Playlists
        mediaId == ALBUMS -> BrowseTarget.Albums
        mediaId == ARTISTS -> BrowseTarget.Artists
        mediaId == FAVORITES -> BrowseTarget.Favorites
        mediaId.startsWith("auxen://browse/playall/album/") ->
            BrowseTarget.PlayAllAlbum(mediaId.removePrefix("auxen://browse/playall/album/"))
        mediaId.startsWith("auxen://browse/playall/artist/") ->
            BrowseTarget.PlayAllArtist(mediaId.removePrefix("auxen://browse/playall/artist/"))
        mediaId.startsWith("auxen://browse/playlist/") ->
            mediaId.removePrefix("auxen://browse/playlist/").toLongOrNull()
                ?.let { BrowseTarget.Playlist(it) } ?: BrowseTarget.Unknown(mediaId)
        mediaId.startsWith("auxen://browse/album/") ->
            BrowseTarget.Album(mediaId.removePrefix("auxen://browse/album/"))
        mediaId.startsWith("auxen://browse/artist/") ->
            BrowseTarget.Artist(mediaId.removePrefix("auxen://browse/artist/"))
        else -> BrowseTarget.Unknown(mediaId)
    }
}

sealed interface BrowseTarget {
    data object Root : BrowseTarget
    data object Recent : BrowseTarget
    data object Playlists : BrowseTarget
    data object Albums : BrowseTarget
    data object Artists : BrowseTarget
    data object Favorites : BrowseTarget
    data class Playlist(val id: Long) : BrowseTarget
    data class Album(val tidalId: String) : BrowseTarget
    data class Artist(val tidalId: String) : BrowseTarget
    data class PlayAllAlbum(val tidalId: String) : BrowseTarget
    data class PlayAllArtist(val tidalId: String) : BrowseTarget
    data class Unknown(val raw: String) : BrowseTarget
}
