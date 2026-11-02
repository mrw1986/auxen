package io.github.auxen.playback.cache

import io.github.auxen.db.CachedTrackEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.provider.StreamInfo
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.StateFlow

/** How a queued Tidal track's media id starts: `TIDAL:<id>`. */
internal val TIDAL_MEDIA_ID_PREFIX = "${Source.TIDAL.name}:"

/** The bare Tidal track id in a `TIDAL:<id>` media id, or null for anything else. */
internal fun tidalTrackIdOf(mediaId: String): String? =
    mediaId.takeIf { it.startsWith(TIDAL_MEDIA_ID_PREFIX) }
        ?.removePrefix(TIDAL_MEDIA_ID_PREFIX)
        ?.takeIf { it.isNotEmpty() }

/** The scheme of a queued Tidal item still on its stable, unresolved URI. */
internal const val STABLE_URI_SCHEME = "auxen"

/** A Tidal track's stable URI. TidalUriResolver maps it to a cached file or a fresh stream URL. */
internal fun stableTidalUri(trackId: String) = "$STABLE_URI_SCHEME://tidal/$trackId"

/** How a cached Tidal track is stored on disk. */
enum class CacheKind { PROGRESSIVE, DASH }

/** An upcoming Tidal track that could be cached, with its size guessed up front. */
data class CacheCandidate(val trackId: String, val track: Track, val estimatedBytes: Long)

/**
 * The cache index at one moment, keyed by Tidal track id. [generation] goes up on every clear,
 * so a reader can tell a clear from songs being evicted.
 */
data class CacheSnapshot(
    val entries: Map<String, CachedTrackEntity> = emptyMap(),
    val generation: Long = 0L,
) {
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

/**
 * A download stopped because [trackId] won't fit the room it was given. [sizeBytes] is the best
 * known size: the Content-Length, an estimate from the stream's real format, or what was written
 * so far scaled up to the whole song.
 */
class SongTooLargeException(val trackId: String, val sizeBytes: Long) :
    IOException("Track $trackId is about $sizeBytes bytes, more than its cache budget")

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

    /** Records that [trackId] played just now, so eviction keeps it longer. No-op if not cached. */
    suspend fun markPlayed(trackId: String)

    /**
     * Returns once the index has been read from disk, or that read failed. Until then
     * [snapshot] is empty, so planning against it would re-fetch songs already cached.
     */
    suspend fun awaitLoaded()
}
