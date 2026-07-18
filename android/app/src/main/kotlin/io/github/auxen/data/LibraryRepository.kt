package io.github.auxen.data

import io.github.auxen.db.AuxenDatabase
import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.db.FavoriteEntity
import io.github.auxen.db.PlayHistoryEntity
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.db.SearchHistoryEntity
import io.github.auxen.db.SettingEntity
import io.github.auxen.db.toEntity
import io.github.auxen.db.toTrack
import io.github.auxen.model.Source
import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Library persistence facade — the Android analog of the desktop
 * `auxen.db.Database` surface the UI consumes (favorites, play counts,
 * settings). Backed by Room; injectable clock for tests.
 */
class LibraryRepository(
    private val db: AuxenDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Insert or refresh a track row; returns its stable row id. */
    suspend fun upsert(track: Track): Long = db.trackDao().upsert(track.toEntity(), clock())

    fun favorites(): Flow<List<Track>> =
        db.favoriteDao().favorites().map { list -> list.map { it.toTrack() } }

    /** Favorite identity keys ("SOURCE:sourceId") for fast UI lookups. */
    fun favoriteKeys(): Flow<Set<String>> =
        db.favoriteDao().favoriteKeys().map { it.toSet() }

    suspend fun setFavorite(track: Track, favorite: Boolean) {
        val id = upsert(track)
        if (favorite) {
            db.favoriteDao().insert(FavoriteEntity(id, track.matchGroupId, clock()))
        } else {
            db.favoriteDao().delete(id)
        }
    }

    /**
     * Favorite [track] at an EXPLICIT [addedAtMillis] — the remote Tidal `created`
     * timestamp for a pulled favorite — so the Tracks tab (ORDER BY added_at DESC)
     * preserves Tidal's own favorite order. Upserts the track first (preserving
     * its id/added_at/play_count on conflict), then inserts the favorite row.
     * Idempotent: a re-pull REPLACEs the favorite row (FavoriteDao.insert is
     * OnConflictStrategy.REPLACE). The in-app heart path stays on [setFavorite],
     * which stamps the wall clock (correct for a just-now action).
     */
    suspend fun favoriteTrack(track: Track, addedAtMillis: Long) {
        val id = upsert(track)
        db.favoriteDao().insert(FavoriteEntity(id, track.matchGroupId, addedAtMillis))
    }

    /**
     * Record a play for a mediaId of the form "SOURCE:sourceId". Unknown
     * tracks and malformed ids are ignored — playback must never crash on
     * bookkeeping.
     */
    suspend fun recordPlay(mediaId: String) {
        val parts = mediaId.split(':', limit = 2)
        if (parts.size != 2) return
        val entity = db.trackDao().bySourceId(parts[0], parts[1]) ?: return
        val now = clock()
        db.trackDao().recordPlay(entity.id, now)
        db.playHistoryDao().insert(PlayHistoryEntity(trackId = entity.id, playedAtMillis = now))
    }

    /** Most recently played tracks (desktop get_recently_played). */
    suspend fun recentlyPlayed(limit: Int = 20): List<Track> =
        db.playHistoryDao().recentlyPlayed(limit).map { it.toTrack() }

    fun playlists(): Flow<List<PlaylistEntity>> = db.playlistDao().playlists()

    /** Create a playlist with the desktop default amber color; returns its id. */
    suspend fun createPlaylist(name: String, color: String = "#d4a039"): Long =
        db.playlistDao().insert(PlaylistEntity(name = name, color = color))

    suspend fun addTrackToPlaylist(track: Track, playlistId: Long) {
        val trackId = upsert(track)
        db.playlistDao().appendTrack(playlistId, trackId)
    }

    /** Ordered tracks in a playlist (desktop get_playlist_tracks). */
    suspend fun playlistTracks(playlistId: Long): List<Track> =
        db.playlistDao().tracksIn(playlistId).map { it.toTrack() }

    suspend fun renamePlaylist(id: Long, name: String) = db.playlistDao().rename(id, name)

    suspend fun recolorPlaylist(id: Long, color: String) = db.playlistDao().recolor(id, color)

    suspend fun deletePlaylist(id: Long) = db.playlistDao().deleteWithTracks(id)

    /** The playlist row by id (with source + tidal_playlist_id), or null — the lazy-fetch guard reads it. */
    suspend fun playlist(id: Long): PlaylistEntity? = db.playlistDao().byId(id)

    /** An imported Tidal playlist by its Tidal [uuid], or null. */
    suspend fun playlistByTidalId(uuid: String): PlaylistEntity? = db.playlistDao().playlistByTidalId(uuid)

    /**
     * Find-or-create the LOCAL shell for a Tidal playlist, keyed by its Tidal
     * [uuid]. Idempotent in CODE (there is no unique index on tidal_playlist_id,
     * so uniqueness is enforced by finding first): a re-import of the same uuid
     * returns the SAME playlist id and never duplicates. Create-only — an
     * existing shell is returned untouched (a local rename is NOT clobbered by a
     * re-sync; the tradeoff: a Tidal-side rename does not propagate without
     * delete+resync). Stamps source = TIDAL + tidalPlaylistId = uuid, with the
     * default amber color so the tab's color-dot renders (playlist art is
     * deferred this slice). Creates only the shell — the tracks are imported
     * lazily by [importTidalPlaylistTracks] when the playlist is first opened.
     */
    suspend fun importTidalPlaylistShell(uuid: String, name: String): Long {
        db.playlistDao().playlistByTidalId(uuid)?.let { return it.id }
        return db.playlistDao().insert(
            PlaylistEntity(name = name, color = "#d4a039", source = Source.TIDAL.name, tidalPlaylistId = uuid),
        )
    }

    /**
     * Replace a playlist's membership with [tracks] in the given order — the
     * import of a Tidal playlist's items in Tidal order (a re-import mirrors
     * Tidal exactly). Upserts each track into `tracks` first, then rewrites the
     * ordering in one transaction (clear + re-insert by index) via the existing
     * [PlaylistDao.reorder].
     */
    suspend fun importTidalPlaylistTracks(playlistId: Long, tracks: List<Track>) {
        val trackIds = tracks.map { upsert(it) }
        db.playlistDao().reorder(playlistId, trackIds)
    }

    suspend fun removeFromPlaylist(playlistId: Long, track: Track) {
        val entity = db.trackDao().bySourceId(track.source.name, track.sourceId) ?: return
        db.playlistDao().removeTrack(playlistId, entity.id)
    }

    /** Move one entry and rewrite positions — desktop reorder_playlist_track. */
    suspend fun movePlaylistTrack(playlistId: Long, fromIndex: Int, toIndex: Int) {
        val entities = db.playlistDao().tracksIn(playlistId)
        if (fromIndex !in entities.indices || toIndex !in entities.indices) return
        val ids = entities.map { it.id }.toMutableList()
        val moved = ids.removeAt(fromIndex)
        ids.add(toIndex, moved)
        db.playlistDao().reorder(playlistId, ids)
    }

    suspend fun sourcePriority(): SourcePriority =
        db.settingsDao().get(KEY_SOURCE_PRIORITY)
            ?.let { stored -> SourcePriority.entries.firstOrNull { it.name == stored } }
            ?: SourcePriority.PREFER_QUALITY

    suspend fun setSourcePriority(priority: SourcePriority) =
        db.settingsDao().put(SettingEntity(KEY_SOURCE_PRIORITY, priority.name))

    /** Read a raw settings value by key (desktop `get_setting`); null if unset. */
    suspend fun getSetting(key: String): String? = db.settingsDao().get(key)

    /** Persist a raw settings value by key (desktop `set_setting`). */
    suspend fun setSetting(key: String, value: String) =
        db.settingsDao().put(SettingEntity(key, value))

    /** Recent search queries, newest first — desktop get_search_history. */
    fun searchHistory(limit: Int = 10): Flow<List<String>> =
        db.searchHistoryDao().recent(limit).map { list -> list.map { it.query } }

    suspend fun addSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        db.searchHistoryDao().put(SearchHistoryEntity(trimmed, clock()))
    }

    suspend fun deleteSearchHistoryItem(query: String) = db.searchHistoryDao().delete(query)

    suspend fun clearSearchHistory() = db.searchHistoryDao().clear()

    /** Favorited albums, newest favorite first (the Collection Albums tab's remote half). */
    fun favoriteAlbums(): Flow<List<FavoriteAlbumEntity>> = db.favoriteAlbumDao().all()

    /** "SOURCE:sourceId" keys of favorited albums — the album analogue of [favoriteKeys]. */
    fun favoriteAlbumKeys(): Flow<Set<String>> = db.favoriteAlbumDao().keys().map { it.toSet() }

    /** Insert-or-refresh a favorite album row. Idempotent on (source, source_id). */
    suspend fun favoriteAlbum(album: FavoriteAlbumEntity): Long = db.favoriteAlbumDao().upsert(album)

    suspend fun unfavoriteAlbum(source: String, sourceId: String) = db.favoriteAlbumDao().delete(source, sourceId)

    /** Favorited (followed) artists, newest favorite first. */
    fun favoriteArtists(): Flow<List<FavoriteArtistEntity>> = db.favoriteArtistDao().all()

    /** "SOURCE:sourceId" keys of favorited artists — the artist analogue of [favoriteKeys]. */
    fun favoriteArtistKeys(): Flow<Set<String>> = db.favoriteArtistDao().keys().map { it.toSet() }

    /** Insert-or-refresh a favorite artist row. Idempotent on (source, source_id). */
    suspend fun favoriteArtist(artist: FavoriteArtistEntity): Long = db.favoriteArtistDao().upsert(artist)

    suspend fun unfavoriteArtist(source: String, sourceId: String) = db.favoriteArtistDao().delete(source, sourceId)

    private companion object {
        const val KEY_SOURCE_PRIORITY = "source_priority"
    }
}
