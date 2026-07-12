package io.github.auxen.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema — port of the desktop `auxen/db.py` tables needed on Android.
 * Timestamps are epoch millis (the desktop app uses SQLite datetime text).
 */

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["source", "source_id"], unique = true),
        Index("match_group_id"),
        Index(value = ["title", "artist"]),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String? = null,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    @ColumnInfo(name = "duration") val durationSeconds: Double? = null,
    @ColumnInfo(name = "track_number") val trackNumber: Int? = null,
    @ColumnInfo(name = "disc_number") val discNumber: Int? = null,
    val source: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "bitrate") val bitrateKbps: Int? = null,
    val format: String? = null,
    @ColumnInfo(name = "sample_rate") val sampleRateHz: Int? = null,
    @ColumnInfo(name = "bit_depth") val bitDepth: Int? = null,
    @ColumnInfo(name = "album_art_url") val albumArtUrl: String? = null,
    @ColumnInfo(name = "match_group_id") val matchGroupId: String? = null,
    val explicit: Boolean = false,
    @ColumnInfo(name = "added_at") val addedAtMillis: Long = 0,
    @ColumnInfo(name = "last_played_at") val lastPlayedAtMillis: Long? = null,
    @ColumnInfo(name = "play_count") val playCount: Int = 0,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "match_group_id") val matchGroupId: String? = null,
    @ColumnInfo(name = "added_at") val addedAtMillis: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: String? = null,
    val source: String? = null,
    @ColumnInfo(name = "tidal_playlist_id") val tidalPlaylistId: String? = null,
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlist_id", "track_id", "position"])
data class PlaylistTrackEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "track_id") val trackId: Long,
    val position: Int,
)

@Entity(tableName = "play_history", indices = [Index("track_id"), Index("played_at")])
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "played_at") val playedAtMillis: Long,
    @ColumnInfo(name = "duration_listened") val durationListenedSeconds: Double? = null,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Persisted playback queue — one row per queue slot, track as JSON. */
@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    @ColumnInfo(name = "track_json") val trackJson: String,
)

/** Recent search queries — desktop `search_history` table. */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    @ColumnInfo(name = "searched_at") val searchedAtMillis: Long,
)
