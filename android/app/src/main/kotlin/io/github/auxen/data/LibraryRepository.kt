package io.github.auxen.data

import io.github.auxen.db.AuxenDatabase
import io.github.auxen.db.FavoriteEntity
import io.github.auxen.db.PlayHistoryEntity
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.db.SearchHistoryEntity
import io.github.auxen.db.SettingEntity
import io.github.auxen.db.toEntity
import io.github.auxen.db.toTrack
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

    suspend fun sourcePriority(): SourcePriority =
        db.settingsDao().get(KEY_SOURCE_PRIORITY)
            ?.let { stored -> SourcePriority.entries.firstOrNull { it.name == stored } }
            ?: SourcePriority.PREFER_QUALITY

    suspend fun setSourcePriority(priority: SourcePriority) =
        db.settingsDao().put(SettingEntity(KEY_SOURCE_PRIORITY, priority.name))

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

    private companion object {
        const val KEY_SOURCE_PRIORITY = "source_priority"
    }
}
