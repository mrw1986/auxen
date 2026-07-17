package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * Shared low-level primitives every metadata slice (Enrichment/Collection/
 * Discovery) consumes: the artwork DTOs + best-fit selection, and ISO-8601
 * duration/release-date parsing. Declared ONCE here (Slice 0) so the feature
 * slices consume them and never re-declare them (a Kotlin "conflicting
 * declarations" error). Field names are verbatim from the cached spec
 * (`Artworks_Attributes` / `Artwork_File` / `Artwork_File_Meta`).
 */
@Serializable
data class ArtworkFileMeta(val width: Int? = null, val height: Int? = null)

/** One `Artwork_File`: a concrete image [href] (nullable — defensive) plus its pixel [meta]. */
@Serializable
data class ArtworkFile(val href: String? = null, val meta: ArtworkFileMeta? = null)

/** `Artworks_Attributes`: the `files` list of rendered sizes, shared by artist `profileArt`, album `coverArt`, and any track/mix art. */
@Serializable
data class ArtworkAttributes(val files: List<ArtworkFile> = emptyList(), val mediaType: String? = null)

/**
 * Best-fit artwork URL for a [targetPx] slot: the smallest file at or above
 * [targetPx] wide (avoids upscaling a tiny thumb into a big slot), or the
 * widest available if none reach it. Files without an [ArtworkFile.href] are
 * ignored; null when there is no usable file. Pure and reusable across
 * artist/album/track/mix projections.
 */
fun pickArtworkUrl(files: List<ArtworkFile>, targetPx: Int): String? {
    val usable = files.filter { it.href != null }
    if (usable.isEmpty()) return null
    val atLeast = usable.filter { (it.meta?.width ?: 0) >= targetPx }
        .minByOrNull { it.meta?.width ?: Int.MAX_VALUE }
    return (atLeast ?: usable.maxByOrNull { it.meta?.width ?: 0 })?.href
}

/**
 * ISO-8601 duration (`Tracks_Attributes.duration`, e.g. "PT2M58S") → whole
 * seconds as a Double, matching [io.github.auxen.model.Track.durationSeconds].
 * Null/unparseable → null (tolerant of a versioned external API). `java.time`
 * is available at minSdk 26.
 */
fun parseIsoDurationSeconds(iso: String?): Double? {
    if (iso.isNullOrBlank()) return null
    return runCatching { Duration.parse(iso).seconds.toDouble() }.getOrNull()
}
