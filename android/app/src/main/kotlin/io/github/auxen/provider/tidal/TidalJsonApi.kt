package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A reusable JSON:API (`openapi.tidal.com/v2`) compound-document layer for
 * the Tidal metadata surface (enrichment/collection/discovery). Unlike the
 * track-presentation-only DTOs in `TidalOfficialApi.kt`, this models the
 * general `data` + `included` + `relationships` envelope so a single
 * `?include=...` call resolves related resources locally, with no extra
 * round-trips. Attribute bodies stay as raw [JsonObject] and are decoded
 * per resource type on demand via [decodeAttributes] — the one envelope
 * fits every resource type (artists, albums, tracks, ...).
 */

/** A JSON:API resource-linkage identifier: `{ "type": ..., "id": ... }`. */
@Serializable
data class TidalResourceId(val type: String, val id: String)

/** A relationship's `data` is either a single `{type,id}` or an array of them; kept raw and normalized by [TidalDocumentView]. */
@Serializable
data class TidalRelationship(val data: JsonElement? = null)

/** One JSON:API resource: identity, free-form [attributes], and named [relationships] (linkage only). */
@Serializable
data class TidalResource(
    val id: String,
    val type: String,
    val attributes: JsonObject? = null,
    val relationships: Map<String, TidalRelationship> = emptyMap(),
)

/** A JSON:API compound document. `data` is a single resource or an array; `included` carries `?include=`'d resources. */
@Serializable
data class TidalDocument(
    val data: JsonElement,
    val included: List<TidalResource> = emptyList(),
)

/** Decode a resource's `attributes` into a typed class, or null if absent/malformed (tolerant, like the spike DTOs). */
inline fun <reified T> TidalResource.decodeAttributes(json: Json): T? =
    attributes?.let { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }

/**
 * Read-side helper over a parsed [TidalDocument]: indexes `included` by
 * (type,id) so [related] resolves a resource's relationship to the full
 * included resources in O(1). All single-vs-array `data` normalization
 * lives here, so callers work in terms of [TidalResource]s, never raw JSON.
 */
class TidalDocumentView(document: TidalDocument, private val json: Json) {
    private val index: Map<TidalResourceId, TidalResource> =
        document.included.associateBy { TidalResourceId(it.type, it.id) }

    /** Every primary resource; one element for object `data`, many for array `data`. */
    val primaryList: List<TidalResource> = resourcesFrom(document.data)

    /** The document's primary resource (first, if `data` was an array). */
    val primary: TidalResource? get() = primaryList.firstOrNull()

    /** The included resources linked by [resource]'s named [relationship], in linkage order; absent links are dropped. */
    fun related(resource: TidalResource, relationship: String): List<TidalResource> {
        val link = resource.relationships[relationship]?.data ?: return emptyList()
        return identifiersFrom(link).mapNotNull { index[it] }
    }

    private fun resourcesFrom(el: JsonElement): List<TidalResource> = when (el) {
        is JsonArray -> el.map { json.decodeFromJsonElement<TidalResource>(it) }
        is JsonObject -> listOf(json.decodeFromJsonElement(el))
        else -> emptyList()
    }

    private fun identifiersFrom(el: JsonElement): List<TidalResourceId> = when (el) {
        is JsonArray -> el.map { json.decodeFromJsonElement<TidalResourceId>(it) }
        is JsonObject -> listOf(json.decodeFromJsonElement(el))
        else -> emptyList()
    }
}
