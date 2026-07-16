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

/**
 * A JSON:API resource-linkage identifier: `{ "type": ..., "id": ... }`.
 *
 * Linkage `meta` is deliberately NOT a field here. This type doubles as
 * [TidalDocumentView]'s index key, and the index is built from `included`
 * resources (which carry no meta); adding `meta` would make data-class equality
 * compare it, so a meta-carrying linkage would stop matching its own indexed
 * resource and every `related` lookup would silently return nothing.
 * [TidalRelated] carries meta instead.
 */
@Serializable
data class TidalResourceId(val type: String, val id: String)

/** A relationship's `data` is either a single `{type,id}` or an array of them; kept raw and normalized by [TidalDocumentView]. */
@Serializable
data class TidalRelationship(val data: JsonElement? = null)

/**
 * One resolved relationship entry: the included [resource] plus the raw linkage
 * [meta] JSON:API permits alongside `{type,id}`.
 *
 * Some truths live on the LINKAGE rather than on the resource — an album's
 * `items` carries `meta.trackNumber`/`meta.volumeNumber` there, and nowhere
 * else. [meta] stays a raw [JsonObject] decoded per relationship on demand via
 * [decodeMeta], mirroring how [TidalResource.attributes] is handled: one
 * envelope, many shapes.
 */
data class TidalRelated(val resource: TidalResource, val meta: JsonObject? = null)

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

/** Decode a linkage's `meta` into a typed class, or null if absent/malformed (tolerant, mirroring [decodeAttributes]). */
inline fun <reified T> TidalRelated.decodeMeta(json: Json): T? =
    meta?.let { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }

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
    fun related(resource: TidalResource, relationship: String): List<TidalResource> =
        relatedWithMeta(resource, relationship).map { it.resource }

    /**
     * [related], but keeping each linkage's `meta` — for the relationships whose
     * truth lives on the linkage rather than the resource (an album's `items`
     * and its `meta.trackNumber`/`meta.volumeNumber` being the motivating case).
     * Same resolution and same order as [related], which delegates here, so the
     * two can never drift apart.
     */
    fun relatedWithMeta(resource: TidalResource, relationship: String): List<TidalRelated> {
        val link = resource.relationships[relationship]?.data ?: return emptyList()
        return linkageFrom(link).mapNotNull { (id, meta) -> index[id]?.let { TidalRelated(it, meta) } }
    }

    private fun resourcesFrom(el: JsonElement): List<TidalResource> = when (el) {
        is JsonArray -> el.map { json.decodeFromJsonElement<TidalResource>(it) }
        is JsonObject -> listOf(json.decodeFromJsonElement(el))
        else -> emptyList()
    }

    private fun linkageFrom(el: JsonElement): List<Pair<TidalResourceId, JsonObject?>> = when (el) {
        is JsonArray -> el.map { identifierOf(it) }
        is JsonObject -> listOf(identifierOf(el))
        else -> emptyList()
    }

    /** `{type,id}` for the index lookup, plus the sibling `meta` object the identifier itself does not model. */
    private fun identifierOf(el: JsonElement): Pair<TidalResourceId, JsonObject?> {
        val id = json.decodeFromJsonElement<TidalResourceId>(el)
        return id to ((el as? JsonObject)?.get("meta") as? JsonObject)
    }
}
