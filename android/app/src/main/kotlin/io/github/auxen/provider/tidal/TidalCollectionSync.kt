package io.github.auxen.provider.tidal

import io.github.auxen.data.LibraryRepository
import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.model.Source
import kotlinx.coroutines.flow.first

/** What one full pull changed locally: adds per favorite type + total mirror-removes across the three keyed types. */
data class CollectionPullSummary(val albums: Int, val artists: Int, val tracks: Int, val playlists: Int, val removed: Int)

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
     * Pull Tidal saved albums into `favorite_albums`, MIRRORING Tidal: ADD
     * remote-not-local favorites and REMOVE local TIDAL-source favorites gone
     * from Tidal (via [reconcileFavorites], keyed `TIDAL:<id>`). A null (failed)
     * fetch reconciles nothing — no add, no delete. Returns the applied reconcile
     * (its sizes are the adds/removes performed). LOCAL favorites are never touched.
     */
    suspend fun pullFavoriteAlbums(): FavoriteReconcile<TidalFavoriteAlbum> {
        val localKeys = library.favoriteAlbumKeys().first()
        val reconcile = reconcileFavorites(tidal.favoriteAlbums(), localKeys) { it.id }
        reconcile.toAdd.forEach { library.favoriteAlbum(favoriteAlbumEntityFor(it, it.addedAtMillis ?: clock())) }
        reconcile.staleTidalIds.forEach { library.unfavoriteAlbum(Source.TIDAL.name, it) }
        return reconcile
    }

    /** Pull Tidal followed artists into `favorite_artists`, mirroring Tidal (add + remove). Same contract as [pullFavoriteAlbums]. */
    suspend fun pullFavoriteArtists(): FavoriteReconcile<TidalFavoriteArtist> {
        val localKeys = library.favoriteArtistKeys().first()
        val reconcile = reconcileFavorites(tidal.favoriteArtists(), localKeys) { it.id }
        reconcile.toAdd.forEach { library.favoriteArtist(favoriteArtistEntityFor(it, it.addedAtMillis ?: clock())) }
        reconcile.staleTidalIds.forEach { library.unfavoriteArtist(Source.TIDAL.name, it) }
        return reconcile
    }

    /**
     * Pull Tidal favorite tracks into the EXISTING `tracks`+`favorites` tables,
     * mirroring Tidal (add + remove). Adds each fresh track at its remote
     * `created` millis (falling back to the clock when the wrapper carried none);
     * removes local TIDAL-source favorite tracks gone from Tidal via
     * [LibraryRepository.unfavoriteTrack] (drops only the favorite row). Same
     * null-safe, LOCAL-preserving contract as [pullFavoriteAlbums].
     */
    suspend fun pullFavoriteTracks(): FavoriteReconcile<TidalFavoriteTrack> {
        val localKeys = library.favoriteKeys().first()
        val reconcile = reconcileFavorites(tidal.favoriteTracks(), localKeys) { it.track.sourceId }
        reconcile.toAdd.forEach { library.favoriteTrack(it.track, it.addedAtMillis ?: clock()) }
        reconcile.staleTidalIds.forEach { library.unfavoriteTrack(Source.TIDAL.name, it) }
        return reconcile
    }

    /**
     * Pull the user's Tidal favorite playlists as local SHELLS — LIST-eager,
     * tracks-lazy. Find-or-creates a `source=TIDAL` [io.github.auxen.db.PlaylistEntity]
     * per remote playlist (idempotent by uuid) but does NOT fetch its ~tracks —
     * those are imported lazily by [importPlaylistTracks] when a playlist is first
     * opened, keeping the Collection-open pull to one cheap LIST call. Returns the
     * number of NEWLY-created shells (an already-imported playlist is a no-op, not
     * recounted); additive — never deletes a local playlist.
     */
    suspend fun pullPlaylists(): Int {
        val remote = tidal.favoritePlaylists()
        var added = 0
        remote.forEach { playlist ->
            if (library.playlistByTidalId(playlist.uuid) == null) added++
            library.importTidalPlaylistShell(playlist.uuid, playlist.title)
        }
        return added
    }

    /**
     * Fetch a Tidal playlist's items and set the local membership in Tidal order
     * (clear + re-insert). The tracks-lazy half of the import: called when a Tidal
     * playlist shell is first opened (see PlayerViewModel.loadPlaylist). Returns
     * the number of tracks imported.
     */
    suspend fun importPlaylistTracks(playlistId: Long, uuid: String): Int {
        val tracks = tidal.playlistItems(uuid)
        library.importTidalPlaylistTracks(playlistId, tracks)
        return tracks.size
    }

    /** One pull of all favorite types (playlists are LIST-eager, additive; the other three mirror Tidal). */
    suspend fun pullAll(): CollectionPullSummary {
        val albums = pullFavoriteAlbums()
        val artists = pullFavoriteArtists()
        val tracks = pullFavoriteTracks()
        val playlists = pullPlaylists()
        return CollectionPullSummary(
            albums = albums.toAdd.size,
            artists = artists.toAdd.size,
            tracks = tracks.toAdd.size,
            playlists = playlists,
            removed = albums.staleTidalIds.size + artists.staleTidalIds.size + tracks.staleTidalIds.size,
        )
    }

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
 * The outcome of one pure favorites reconcile: [toAdd] are remote favorites to
 * insert locally (the additive union), [staleTidalIds] are local TIDAL-source
 * sourceIds to unfavorite (the mirror-delete set).
 */
data class FavoriteReconcile<T>(val toAdd: List<T>, val staleTidalIds: List<String>)

/**
 * Pure two-way reconcile of a CONFIRMED [remote] favorite list against the local
 * favorite [localKeys] ("SOURCE:sourceId", mixed sources). [toAdd] reuses
 * [newRemoteFavorites] (remote favorites whose `TIDAL:<id>` key is not already
 * local). [staleTidalIds] is the mirror-delete set: local **TIDAL-source**
 * sourceIds NOT in the confirmed remote id set — LOCAL-source keys are filtered
 * out first, so a local favorite is NEVER deleted by a Tidal pull.
 *
 * **Data-loss guard:** a NULL [remote] means the fetch FAILED / is untrusted
 * (see [TidalProvider.favoriteAlbums] — it returns null on any page failure).
 * Both lists are then EMPTY, so a failed/partial fetch adds nothing and — the
 * point of this whole design — deletes nothing. Only a genuinely-empty CONFIRMED
 * fetch (`remote = []`) can yield a non-empty stale set. Idempotent: an unchanged
 * remote+local yields (empty, empty). [id] extracts a remote favorite's Tidal id.
 */
fun <T> reconcileFavorites(remote: List<T>?, localKeys: Set<String>, id: (T) -> String): FavoriteReconcile<T> {
    if (remote == null) return FavoriteReconcile(emptyList(), emptyList())
    val prefix = "${Source.TIDAL.name}:"
    val remoteIds = remote.mapTo(mutableSetOf(), id)
    val staleTidalIds = localKeys
        .filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix) }
        .filter { it !in remoteIds }
    return FavoriteReconcile(newRemoteFavorites(remote, localKeys, id), staleTidalIds)
}

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
