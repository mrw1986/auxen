package io.github.auxen.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE source = :source AND source_id = :sourceId")
    suspend fun bySourceId(source: String, sourceId: String): TrackEntity?

    @Query("UPDATE tracks SET play_count = play_count + 1, last_played_at = :playedAtMillis WHERE id = :id")
    suspend fun recordPlay(id: Long, playedAtMillis: Long)

    @Query("UPDATE tracks SET match_group_id = :groupId WHERE id IN (:ids)")
    suspend fun setMatchGroup(ids: List<Long>, groupId: String)

    /**
     * Insert-or-update keyed by (source, source_id). Metadata is refreshed;
     * row id, added_at and play stats are preserved. Mirrors the desktop
     * `Database.insert_track` upsert semantics.
     */
    @Transaction
    suspend fun upsert(track: TrackEntity, nowMillis: Long): Long {
        val existing = bySourceId(track.source, track.sourceId)
            ?: return insertIgnore(track.copy(id = 0, addedAtMillis = nowMillis))
        update(
            track.copy(
                id = existing.id,
                addedAtMillis = existing.addedAtMillis,
                lastPlayedAtMillis = existing.lastPlayedAtMillis,
                playCount = existing.playCount,
            ),
        )
        return existing.id
    }
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE track_id = :trackId")
    suspend fun delete(trackId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE track_id = :trackId)")
    suspend fun isFavorite(trackId: Long): Boolean

    @Query(
        "SELECT t.* FROM tracks t JOIN favorites f ON f.track_id = t.id " +
            "ORDER BY f.added_at DESC",
    )
    fun favorites(): Flow<List<TrackEntity>>

    @Query("SELECT t.source || ':' || t.source_id FROM tracks t JOIN favorites f ON f.track_id = t.id")
    fun favoriteKeys(): Flow<List<String>>
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlists ORDER BY name")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE playlists SET color = :color WHERE id = :id")
    suspend fun recolor(id: Long, color: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun clearTracks(playlistId: Long)

    @Transaction
    suspend fun deleteWithTracks(id: Long) {
        clearTracks(id)
        delete(id)
    }

    @Insert
    suspend fun insertTrack(entry: PlaylistTrackEntity)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId)")
    suspend fun containsTrack(playlistId: Long, trackId: Long): Boolean

    /** Append if absent — desktop add_track_to_playlist no-ops on duplicates. */
    @Transaction
    suspend fun appendTrack(playlistId: Long, trackId: Long) {
        if (containsTrack(playlistId, trackId)) return
        insertTrack(PlaylistTrackEntity(playlistId, trackId, nextPosition(playlistId)))
    }

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun removeTrack(playlistId: Long, trackId: Long)

    @Query(
        "SELECT t.* FROM tracks t JOIN playlist_tracks pt ON pt.track_id = t.id " +
            "WHERE pt.playlist_id = :playlistId ORDER BY pt.position",
    )
    suspend fun tracksIn(playlistId: Long): List<TrackEntity>

    /** Rewrite the playlist's ordering in one transaction. */
    @Transaction
    suspend fun reorder(playlistId: Long, orderedTrackIds: List<Long>) {
        clearTracks(playlistId)
        orderedTrackIds.forEachIndexed { index, trackId ->
            insertTrack(PlaylistTrackEntity(playlistId, trackId, index))
        }
    }
}

@Dao
interface PlayHistoryDao {
    @Insert
    suspend fun insert(entry: PlayHistoryEntity)

    @Query(
        "SELECT t.* FROM tracks t JOIN play_history h ON h.track_id = t.id " +
            "GROUP BY t.id ORDER BY MAX(h.played_at) DESC LIMIT :limit",
    )
    suspend fun recentlyPlayed(limit: Int): List<TrackEntity>
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?
}

@Dao
interface QueueDao {
    @Query("DELETE FROM queue_items")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<QueueItemEntity>)

    @Transaction
    suspend fun replaceAll(items: List<QueueItemEntity>) {
        clear()
        insertAll(items)
    }

    @Query("SELECT * FROM queue_items ORDER BY position")
    suspend fun all(): List<QueueItemEntity>
}

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY searched_at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}
