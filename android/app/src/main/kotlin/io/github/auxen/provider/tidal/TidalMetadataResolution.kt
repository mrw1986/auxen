package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The ids needed to fetch an artist/album detail document, resolved from a
 * single Tidal-sourced track via `GET /tracks/{id}?include=artists,albums`.
 * [isrc] is carried for the documented ISRC/barcode fallback (see the plan's
 * Architecture) — unused while direct id-equivalence holds.
 */
data class TrackRelations(val artistId: String?, val albumId: String?, val isrc: String?)

/**
 * Pure projection from a compound track [TidalDocument] to [TrackRelations].
 * Returns null when the document has no primary track resource. Extracted from
 * [TidalMetadataRepository.trackRelations] so the mapping is unit-testable with
 * no network.
 *
 * Artist/album ids are read from the relationship LINKAGE (`relationships.<rel>.
 * data`), not via [TidalDocumentView.related] — the id is present in the linkage
 * whether or not the target resource was echoed back into `included`, and the
 * gate only needs the ids (the human-readable name is a separate artist fetch).
 */
fun trackRelationsFrom(doc: TidalDocument, json: Json): TrackRelations? {
    val view = TidalDocumentView(doc, json)
    val track = view.primary ?: return null
    return TrackRelations(
        artistId = track.firstRelatedId("artists", json),
        albumId = track.firstRelatedId("albums", json),
        isrc = track.decodeAttributes<TrackAttributes>(json)?.isrc,
    )
}

/** The first linkage id for [relationship], normalizing the single-object-vs-array `data` shape. */
private fun TidalResource.firstRelatedId(relationship: String, json: Json): String? {
    val first = when (val data = relationships[relationship]?.data) {
        is JsonArray -> data.firstOrNull()
        is JsonObject -> data
        else -> null
    } ?: return null
    return runCatching { json.decodeFromJsonElement<TidalResourceId>(first) }.getOrNull()?.id
}
