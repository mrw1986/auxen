package io.github.auxen.provider.tidal

import io.github.auxen.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The pure parts of track credits + lyrics on the INTERNAL v1 API —
 * `GET tracks/{id}/credits` + `GET tracks/{id}/lyrics` — replacing the official-v2
 * compound-document `trackDetail`. Metadata only; the sheet they feed resolves no
 * stream. Field names are verbatim from the probed schema
 * (`.superpowers/sdd/official-v2-retirement-schema.md`) and from `tidalapi`'s
 * `media.py` (credits/lyrics).
 */

/** Contributors sharing one role (e.g. "Producer" -> [Alice, Bob]). */
data class TrackCredit(val role: String, val names: List<String>)

/** Track credits + lyrics (and similar tracks) for the credits/lyrics sheet. */
data class TidalTrackDetail(
    val id: String,
    val title: String,
    val credits: List<TrackCredit>,
    val lyricsText: String?,
    val lyricsSynced: String?,
    val similarTracks: List<Track>,
)

/** One contributor inside a credits role group. `id` is often absent. */
@Serializable
data class TidalV1Contributor(val name: String = "", val id: Long? = null)

/** One `tracks/{id}/credits` role group: `{ "type": "<role>", "contributors": [...] }`. */
@Serializable
data class TidalV1CreditGroup(val type: String = "", val contributors: List<TidalV1Contributor> = emptyList())

/**
 * `tracks/{id}/lyrics`, the subset the sheet renders: plain [lyrics] +
 * time-synced [subtitles]. `lyricsProvider`/`isRightToLeft`/`trackId`/… are
 * dropped by `ignoreUnknownKeys` — nothing renders them.
 */
@Serializable
data class TidalV1Lyrics(val lyrics: String? = null, val subtitles: String? = null)

/** `GET {apiBase}/tracks/{trackId}/credits?countryCode=…&includeContributors=true`. */
fun trackCreditsUrl(apiBase: String, trackId: String, countryCode: String): HttpUrl =
    "$apiBase/tracks/$trackId/credits".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .addQueryParameter("includeContributors", "true")
        .build()

/** `GET {apiBase}/tracks/{trackId}/lyrics?countryCode=…`. */
fun trackLyricsUrl(apiBase: String, trackId: String, countryCode: String): HttpUrl =
    "$apiBase/tracks/$trackId/lyrics".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * Pure: a `tracks/{id}/credits` body (a JSON ARRAY of role groups) → [TrackCredit]
 * list, one entry per role, names in payload order.
 *
 * Empty for anything that is not that array — a v1 error envelope, an object, a
 * 404 body, non-JSON — so a track with no credits degrades to "no credits",
 * never a throw. A group with a blank role or no usable names is dropped: there
 * is nothing to render for it.
 */
fun trackCreditsFrom(body: String, json: Json): List<TrackCredit> =
    runCatching { json.decodeFromString<List<TidalV1CreditGroup>>(body) }.getOrNull()
        ?.mapNotNull { group ->
            val names = group.contributors.map { it.name }.filter { it.isNotBlank() }
            if (group.type.isBlank() || names.isEmpty()) null else TrackCredit(group.type, names)
        }
        .orEmpty()

/**
 * Pure: a `tracks/{id}/lyrics` body → [TidalV1Lyrics], or **null** when there is
 * nothing to show (both fields absent/blank) or the body is not lyrics.
 *
 * A **404 is EXPECTED** for a track without lyrics (instrumentals) and never
 * reaches here — the provider's `get` throws on the non-2xx and `runCatching`
 * turns that into a null body upstream, so this sees only a real 200 body. Null
 * from here means the sheet shows "No lyrics available." — a clean state, not an error.
 */
fun trackLyricsFrom(body: String, json: Json): TidalV1Lyrics? =
    runCatching { json.decodeFromString<TidalV1Lyrics>(body) }.getOrNull()
        ?.takeIf { !it.lyrics.isNullOrBlank() || !it.subtitles.isNullOrBlank() }
