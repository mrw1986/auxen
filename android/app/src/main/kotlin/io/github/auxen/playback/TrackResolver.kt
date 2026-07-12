package io.github.auxen.playback

import io.github.auxen.provider.StreamInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Tidal track ids to fresh [StreamInfo] with a short TTL cache.
 * Tidal stream URLs are short-lived, so nothing here is persisted; the
 * cache only avoids duplicate API calls during one listening session.
 */
class TrackResolver(
    private val fetch: suspend (tidalTrackId: String) -> StreamInfo,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {

    private data class Entry(val info: StreamInfo, val resolvedAtMillis: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun resolve(tidalTrackId: String): StreamInfo {
        cache[tidalTrackId]?.let { entry ->
            if (clock() - entry.resolvedAtMillis < ttlMillis) return entry.info
        }
        val info = fetch(tidalTrackId)
        cache[tidalTrackId] = Entry(info, clock())
        return info
    }

    /** Drop a cached entry, e.g. after the CDN answered 401/403/410. */
    fun invalidate(tidalTrackId: String) {
        cache.remove(tidalTrackId)
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 20L * 60 * 1000
    }
}
