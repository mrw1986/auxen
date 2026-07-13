package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable

/**
 * Minimal JSON:API DTOs for `openapi.tidal.com/v2` (Tidal official-API
 * migration, Task 1's go/no-go spike) -- just enough to fetch a track and
 * validate streaming, not a general JSON:API client. Shapes are pulled
 * directly from the cached spec (`.superpowers/sdd/tidal-openapi-v2.json`),
 * not guessed: `Tracks_Attributes`, `TrackManifests_Attributes`,
 * `TrackFiles_Attributes`, and the shared `{errors: [{code, status,
 * detail}]}` error-response shape every 4xx/5xx response in the spec uses.
 *
 * Every attribute is nullable/defaulted: `ignoreUnknownKeys = true` plus
 * "decode what's there, tolerate what's missing" is the deliberate choice
 * for a spike client talking to a real, versioned external API -- an
 * unexpected null here should degrade the spike's diagnostic output, not
 * crash the app.
 */
@Serializable
data class TrackAttributes(
    val title: String? = null,
    /** ISO 8601 duration, e.g. "PT2M58S". */
    val duration: String? = null,
    val isrc: String? = null,
    val explicit: Boolean? = null,
)

@Serializable
data class TrackResource(val id: String, val type: String, val attributes: TrackAttributes? = null)

@Serializable
data class TrackDocument(val data: TrackResource)

@Serializable
data class TrackManifestAttributes(
    /** "FULL" or "PREVIEW" -- the decisive field for the go/no-go spike. */
    val trackPresentation: String? = null,
    /** Set only when [trackPresentation] is "PREVIEW", e.g. "FULL_REQUIRES_HIGHER_ACCESS_TIER". */
    val previewReason: String? = null,
    val formats: List<String>? = null,
    /** Manifest URI -- an HTTPS URL to an HLS/DASH manifest when requested with `uriScheme=HTTPS`. */
    val uri: String? = null,
    val hash: String? = null,
)

@Serializable
data class TrackManifestResource(val id: String, val type: String, val attributes: TrackManifestAttributes? = null)

@Serializable
data class TrackManifestDocument(val data: TrackManifestResource)

/** True when [TrackManifestResource.attributes]' `trackPresentation` is "FULL" (the go/no-go signal). */
fun TrackManifestResource.isFullPresentation(): Boolean = attributes?.trackPresentation == "FULL"

@Serializable
data class TrackFileAttributes(
    val trackPresentation: String? = null,
    val format: String? = null,
    /** Direct, playable file URL -- no manifest wrapping, unlike [TrackManifestAttributes.uri]. */
    val url: String? = null,
)

@Serializable
data class TrackFileResource(val id: String, val type: String, val attributes: TrackFileAttributes? = null)

@Serializable
data class TrackFileDocument(val data: TrackFileResource)

/** True when [TrackFileResource.attributes]' `trackPresentation` is "FULL". */
fun TrackFileResource.isFullPresentation(): Boolean = attributes?.trackPresentation == "FULL"

@Serializable
data class JsonApiErrorEntry(val code: String? = null, val status: String? = null, val detail: String? = null)

@Serializable
data class JsonApiErrorDocument(val errors: List<JsonApiErrorEntry> = emptyList())
