package io.github.auxen.provider.tidal

import io.github.auxen.data.LibraryRepository
import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.model.Source
import kotlinx.coroutines.flow.first

/** What one full pull added locally, per favorite type. */
data class CollectionPullSummary(val albums: Int, val artists: Int, val tracks: Int)

/**
 * Two-way reconcile between the user's Tidal favorites (internal-v1, via
 * [TidalProvider]) and the local favorite tables ([LibraryRepository]) — saved
 * albums and followed artists. Held on `Graph` (a plain class, not an object).
 * Pure merge logic is unit-tested; the network+DB orchestration is not
 * (internal-v1 precedent). Playback of a synced favorite stays on the
 * internal-v1 stream path — this class touches metadata + favorite-state only.
 */
class TidalCollectionSync(
    private val tidal: TidalProvider,
    private val library: LibraryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Pull Tidal saved albums into `favorite_albums` (additive union: never
     * deletes a local favorite). Returns the number of newly-added favorites.
     */
    suspend fun pullFavoriteAlbums(): Int {
        val remote = tidal.favoriteAlbums()
        val fresh = newRemoteFavorites(remote, library.favoriteAlbumKeys().first()) { it.id }
        fresh.forEach { library.favoriteAlbum(favoriteAlbumEntityFor(it, it.addedAtMillis ?: clock())) }
        return fresh.size
    }

    /** Pull Tidal followed artists into `favorite_artists` (additive union). Returns the number newly added. */
    suspend fun pullFavoriteArtists(): Int {
        val remote = tidal.favoriteArtists()
        val fresh = newRemoteFavorites(remote, library.favoriteArtistKeys().first()) { it.id }
        fresh.forEach { library.favoriteArtist(favoriteArtistEntityFor(it, it.addedAtMillis ?: clock())) }
        return fresh.size
    }

    /**
     * Pull Tidal favorite tracks into the EXISTING `tracks`+`favorites` tables
     * (additive union: never deletes a local favorite). Reuses the pure
     * [newRemoteFavorites] set-difference (keyed `TIDAL:<sourceId>`), then
     * favorites each fresh track at its remote `created` millis (falling back to
     * the clock only when the wrapper carried none). Returns the number newly
     * added; a re-pull of an already-favorited track is a no-op.
     */
    suspend fun pullFavoriteTracks(): Int {
        val remote = tidal.favoriteTracks()
        val fresh = newRemoteFavorites(remote, library.favoriteKeys().first()) { it.track.sourceId }
        fresh.forEach { library.favoriteTrack(it.track, it.addedAtMillis ?: clock()) }
        return fresh.size
    }

    /** One pull of all three favorite types. */
    suspend fun pullAll(): CollectionPullSummary = CollectionPullSummary(
        albums = pullFavoriteAlbums(),
        artists = pullFavoriteArtists(),
        tracks = pullFavoriteTracks(),
    )

    /**
     * Push an album favorite toggle to Tidal. Best-effort and optimistic — the
     * caller has already updated the local table; a failure here is not rolled
     * back. No-op for local-source ids (they are not on Tidal).
     */
    suspend fun pushAlbumFavorite(source: Source, sourceId: String, favorite: Boolean) {
        if (source != Source.TIDAL) return
        if (favorite) tidal.addFavoriteAlbum(sourceId) else tidal.removeFavoriteAlbum(sourceId)
    }

    /** Push an artist follow toggle to Tidal. Same optimistic, Tidal-only contract as [pushAlbumFavorite]. */
    suspend fun pushArtistFavorite(source: Source, sourceId: String, favorite: Boolean) {
        if (source != Source.TIDAL) return
        if (favorite) tidal.addFavoriteArtist(sourceId) else tidal.removeFavoriteArtist(sourceId)
    }

    /**
     * Push a track favorite toggle to Tidal. Same optimistic, Tidal-only contract
     * as [pushAlbumFavorite]: the caller has already written the local favorite; a
     * failure here is not rolled back, and a LOCAL-source track is a no-op (it is
     * not on Tidal), so in-app hearting of a local track stays local-only.
     */
    suspend fun pushTrackFavorite(source: Source, sourceId: String, favorite: Boolean) {
        if (source != Source.TIDAL) return
        if (favorite) tidal.addFavoriteTrack(sourceId) else tidal.removeFavoriteTrack(sourceId)
    }
}

/**
 * Remote favorites whose `TIDAL:<id>` key is not already locally favorited.
 * Pure and type-agnostic: the caller supplies the id selector and the matching
 * local key set (`favoriteAlbumKeys` or `favoriteArtistKeys`), both of which
 * emit the same `SOURCE:sourceId` shape.
 */
fun <T> newRemoteFavorites(remote: List<T>, localFavoriteKeys: Set<String>, id: (T) -> String): List<T> =
    remote.filter { "${Source.TIDAL.name}:${id(it)}" !in localFavoriteKeys }

/**
 * A [FavoriteAlbumEntity] for a pulled Tidal album favorite — cover + artist
 * populated from internal-v1. Title falls back to a stable placeholder only
 * when the favorite has none. Pure.
 */
fun favoriteAlbumEntityFor(fav: TidalFavoriteAlbum, addedAtMillis: Long): FavoriteAlbumEntity = FavoriteAlbumEntity(
    source = Source.TIDAL.name,
    sourceId = fav.id,
    title = fav.title.ifBlank { "Tidal album ${fav.id}" },
    artist = fav.artistName?.ifBlank { null },
    artUrl = fav.coverUrl,
    addedAtMillis = addedAtMillis,
)

/**
 * A [FavoriteArtistEntity] for a pulled Tidal followed artist — picture @480
 * populated from internal-v1. Name falls back only when absent. Pure.
 */
fun favoriteArtistEntityFor(fav: TidalFavoriteArtist, addedAtMillis: Long): FavoriteArtistEntity = FavoriteArtistEntity(
    source = Source.TIDAL.name,
    sourceId = fav.id,
    name = fav.name.ifBlank { "Tidal artist ${fav.id}" },
    artUrl = fav.pictureUrl,
    addedAtMillis = addedAtMillis,
)
