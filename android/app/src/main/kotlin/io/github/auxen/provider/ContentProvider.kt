package io.github.auxen.provider

import io.github.auxen.model.Track

/**
 * Interface that every music source (local files, Tidal, ...) must implement.
 * Mirrors `auxen.providers.base.ContentProvider` from the desktop app.
 */
interface MusicProvider {
    /** Return tracks matching [query]. */
    suspend fun search(query: String, limit: Int = 20): List<Track>

    /** Return playback info (URI + container hints) for [track]. */
    suspend fun getStreamInfo(track: Track): StreamInfo
}

/**
 * Everything the player needs to open a stream.
 *
 * [uri] is either a direct media URL/content-URI, or a `data:` URI holding a
 * DASH manifest when [mimeType] is `application/dash+xml` (how Tidal delivers
 * Hi-Res / MQA-era streams).
 */
data class StreamInfo(
    val uri: String,
    val mimeType: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    /** ReplayGain tag values in dB, when the source could supply them (DSP-a Task 5). */
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
)
