package io.github.auxen.provider.local

import io.github.auxen.model.Track

/**
 * The embedded-tag values a [LocalTagReader] MediaMetadataRetriever pass
 * extracts for a local track — the fields the MediaStore projection in
 * [LocalProvider] leaves null. All nullable: a tag may be absent, and
 * [sampleRateHz]/[bitDepth] are ALSO null below API 31, where
 * MediaMetadataRetriever cannot report them (the KEY constants are 31+).
 */
data class TrackTags(
    val genre: String? = null,
    val discNumber: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
)

/**
 * Parse a disc-number tag, often the `"disc/total"` form (`"1/2"`) — the tail
 * is dropped. A bare `"3"` yields 3; blank/zero/garbage yields null. Pure.
 */
fun parseDiscNumber(raw: String?): Int? =
    raw?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

/**
 * Merge [tags] into this track, FILLING ONLY fields the MediaStore read left
 * null. MediaStore stays authoritative for identity-ish fields (title/artist/
 * album/year/trackNumber), which album grouping and dedup key on, so tag
 * enrichment never shifts a track between groups. Concretely: genre, disc
 * number, sample rate, bit depth, and (only when absent) bitrate are filled
 * from [tags]; everything else is unchanged. Pure — the unit-tested core.
 *
 * qualityLabel/qualityScore recompute for free off the filled sampleRateHz/
 * bitDepth, so a 24-bit/96kHz FLAC becomes "Hi-Res" once enriched (API 31+).
 */
fun Track.mergedWith(tags: TrackTags): Track = copy(
    genre = genre ?: tags.genre,
    discNumber = discNumber ?: tags.discNumber,
    sampleRateHz = sampleRateHz ?: tags.sampleRateHz,
    bitDepth = bitDepth ?: tags.bitDepth,
    bitrateKbps = bitrateKbps ?: tags.bitrateKbps,
)
