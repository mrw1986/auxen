package io.github.auxen.model

import kotlinx.serialization.Serializable

/** Where a track originates from. Mirrors `auxen.models.Source` in the desktop app. */
enum class Source { LOCAL, TIDAL }

/** User preference for which source to play when duplicates exist. */
enum class SourcePriority { PREFER_LOCAL, PREFER_TIDAL, PREFER_QUALITY, ALWAYS_ASK }

private val LOSSLESS_FORMATS = setOf("FLAC", "WAV", "ALAC", "HI-RES")
private val LOSSY_HQ_FORMATS = setOf("AAC", "OGG", "OPUS")

/**
 * A single music track, either local or from Tidal.
 *
 * Mirrors the desktop `auxen.models.Track` dataclass, including the
 * quality-scoring rules used for duplicate resolution.
 */
@Serializable
data class Track(
    val title: String,
    val artist: String,
    val source: Source,
    val sourceId: String,

    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val durationSeconds: Double? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val bitrateKbps: Int? = null,
    val format: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val albumArtUrl: String? = null,
    val matchGroupId: String? = null,
    val explicit: Boolean = false,
) {
    val isLocal: Boolean get() = source == Source.LOCAL
    val isTidal: Boolean get() = source == Source.TIDAL

    /**
     * Integer quality score for comparison; higher is better.
     *
     *  1000 — Hi-Res FLAC (24-bit, 96 kHz+)
     *   500 — FLAC / WAV / ALAC 16-bit (or unknown depth)
     *   300 — AAC / OGG / OPUS 320 kbps
     *   250 — MP3 320 kbps
     *   200 — AAC / OGG / OPUS < 320 kbps
     *   100 — MP3 < 320 kbps (or unknown bitrate)
     *     0 — Unknown / unsupported format
     */
    val qualityScore: Int
        get() {
            val fmt = format?.uppercase() ?: ""
            return when {
                fmt in LOSSLESS_FORMATS ->
                    if ((bitDepth ?: 0) >= 24 && (sampleRateHz ?: 0) >= 96_000) 1000 else 500
                fmt in LOSSY_HQ_FORMATS -> if ((bitrateKbps ?: 0) >= 320) 300 else 200
                fmt == "MP3" -> if ((bitrateKbps ?: 0) >= 320) 250 else 100
                else -> 0
            }
        }

    /** Human-readable quality label, e.g. "Hi-Res", "FLAC", "MP3". */
    val qualityLabel: String
        get() {
            val fmt = format?.uppercase() ?: ""
            return when {
                fmt in LOSSLESS_FORMATS ->
                    if ((bitDepth ?: 0) >= 24 && (sampleRateHz ?: 0) >= 96_000) "Hi-Res" else fmt
                fmt in LOSSY_HQ_FORMATS -> fmt
                fmt == "MP3" -> "MP3"
                else -> "Unknown"
            }
        }
}
