package io.github.auxen.playback.cache

import io.github.auxen.db.CachedTrackEntity
import io.github.auxen.model.Track
import io.github.auxen.provider.StreamInfo
import java.io.File
import kotlinx.coroutines.flow.StateFlow

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

/** Where to play a cached track from. [uri] is `file://` plus the entry file's absolute path. */
data class CachedPlayback(val kind: CacheKind, val uri: String)

/**
 * What a finished download left in its staging folder. [entryFile] is relative to that
 * folder; [streamInfo] is the stream it came from, kept for its gains and quality.
 */
data class DownloadResult(
    val kind: CacheKind,
    val entryFile: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val streamInfo: StreamInfo,
) {
    // streamInfo.uri carries a signed URL or a manifest with segment tokens, so it stays out of logs.
    override fun toString(): String =
        "DownloadResult(kind=$kind, entryFile=$entryFile, sizeBytes=$sizeBytes, mimeType=$mimeType)"
}

/** The cache as the downloader and scheduler write to it. */
interface CacheIndex {
    val snapshot: StateFlow<CacheSnapshot>

    /** A new, empty folder for one download of [trackId], a bare Tidal id (anything else throws). */
    fun staging(trackId: String): File

    /** Moves a finished [staging] folder into the cache. False (staging deleted) if it can't. */
    suspend fun commit(trackId: String, staging: File, result: DownloadResult, track: Track): Boolean

    /** Deletes an abandoned staging folder. */
    suspend fun discard(staging: File)

    suspend fun evict(trackIds: Collection<String>)
}
