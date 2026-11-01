package io.github.auxen.playback.cache

import io.github.auxen.db.CachedTrackEntity
import io.github.auxen.model.Track

/** How a cached Tidal track is stored on disk. */
enum class CacheKind { PROGRESSIVE, DASH }

/** An upcoming Tidal track that could be cached, with its size guessed up front. */
data class CacheCandidate(val trackId: String, val track: Track, val estimatedBytes: Long)

/** The cache index at one moment, keyed by Tidal track id. */
data class CacheSnapshot(val entries: Map<String, CachedTrackEntity> = emptyMap()) {
    val ids: Set<String> get() = entries.keys
    val totalBytes: Long get() = entries.values.sumOf { it.sizeBytes }
    val count: Int get() = entries.size
}
