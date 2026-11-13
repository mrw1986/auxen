package io.github.auxen.playback

import io.github.auxen.db.AuxenDatabase
import io.github.auxen.db.QueueItemEntity
import io.github.auxen.db.SettingEntity
import io.github.auxen.model.Track
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the playback queue across process death — the Android analog of
 * the desktop app's queue persistence. Tracks are stored as JSON per queue
 * slot so restoration doesn't depend on the library tables.
 */
class QueueStateStore(private val db: AuxenDatabase) {

    data class SavedQueue(val tracks: List<Track>, val index: Int, val positionMs: Long)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(tracks: List<Track>, index: Int, positionMs: Long) {
        db.queueDao().replaceAll(
            tracks.mapIndexed { i, track -> QueueItemEntity(i, json.encodeToString(track)) },
        )
        db.settingsDao().put(SettingEntity(KEY_INDEX, index.toString()))
        db.settingsDao().put(SettingEntity(KEY_POSITION, positionMs.toString()))
    }

    /** Null when nothing (valid) is saved. Corrupted rows are skipped. */
    suspend fun load(): SavedQueue? {
        val items = db.queueDao().all()
        val tracks = items.mapNotNull { item ->
            runCatching { json.decodeFromString<Track>(item.trackJson) }.getOrNull()
        }
        if (tracks.isEmpty()) return null
        val index = (db.settingsDao().get(KEY_INDEX)?.toIntOrNull() ?: 0)
            .coerceIn(0, tracks.size - 1)
        val positionMs = (db.settingsDao().get(KEY_POSITION)?.toLongOrNull() ?: 0)
            .coerceAtLeast(0)
        return SavedQueue(tracks, index, positionMs)
    }

    private companion object {
        const val KEY_INDEX = "queue_index"
        const val KEY_POSITION = "queue_position_ms"
    }
}
