package io.github.auxen.provider.tidal

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Pure request builders for the three official-API calls the go/no-go
 * spike needs (Tidal official-API migration, Task 1). Separated from
 * execution/parsing (in [TidalOfficialClient]) so the request SHAPE --
 * path, query params, headers -- is unit-testable without a live call,
 * matching [TidalOfficialAuth]'s `buildTokenExchangeRequest` pattern.
 */
object TidalOfficialEndpoints {
    const val BASE = "https://openapi.tidal.com/v2"

    private fun authed(url: okhttp3.HttpUrl, accessToken: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/vnd.api+json")
            .build()

    /**
     * `GET /tracks/{id}`. With no [include]/[countryCode] this is the bare
     * track fetch the streaming spike uses (unchanged URL). With `?include=`
     * it is a JSON:API compound document for the metadata layer (credits/
     * lyrics/artists/albums). [countryCode] is optional per the spec.
     */
    fun trackRequest(
        id: String,
        accessToken: String,
        include: List<String> = emptyList(),
        countryCode: String? = null,
    ): Request {
        val url = "$BASE/tracks/$id".toHttpUrl().newBuilder()
            .apply { countryCode?.let { addQueryParameter("countryCode", it) } }
            .apply { include.forEach { addQueryParameter("include", it) } }
            .build()
        return authed(url, accessToken)
    }

    /**
     * `manifestType=MPEG_DASH` (not `HLS` -- no `media3-exoplayer-hls`
     * dependency) and `uriScheme=HTTPS` (not `DATA` -- an HTTPS URL our
     * Media3 `DashMediaSource` can consume directly, not a base64 blob
     * needing local decoding). `adaptive=false`: the spike wants one fixed
     * format to definitively test, not ABR switching.
     */
    fun trackManifestRequest(id: String, accessToken: String, formats: List<String>): Request {
        val url = "$BASE/trackManifests/$id".toHttpUrl().newBuilder()
            .addQueryParameter("manifestType", "MPEG_DASH")
            .addQueryParameter("uriScheme", "HTTPS")
            .addQueryParameter("usage", "PLAYBACK")
            .addQueryParameter("adaptive", "false")
            .apply { formats.forEach { addQueryParameter("formats", it) } }
            .build()
        return authed(url, accessToken)
    }

    /** Requires `PARTNER` access tier per the spec's `x-path-item-properties` -- see the Task 1 report for what a 403 here means. */
    fun trackFileRequest(id: String, accessToken: String, formats: List<String>): Request {
        val url = "$BASE/trackFiles/$id".toHttpUrl().newBuilder()
            .addQueryParameter("usage", "PLAYBACK")
            .apply { formats.forEach { addQueryParameter("formats", it) } }
            .build()
        return authed(url, accessToken)
    }

    /**
     * `GET /artists/{id}` with `?include=...` (JSON:API compound document)
     * so biography/similarArtists/albums come back in one call. `countryCode`
     * is optional per the spec (only `id` is required) but supplied for
     * correct regional catalog results.
     */
    fun artistRequest(id: String, accessToken: String, include: List<String>, countryCode: String? = null): Request {
        val url = "$BASE/artists/$id".toHttpUrl().newBuilder()
            .apply { countryCode?.let { addQueryParameter("countryCode", it) } }
            .apply { include.forEach { addQueryParameter("include", it) } }
            .build()
        return authed(url, accessToken)
    }

    /**
     * `GET /artists/{id}/relationships/biography` — the biography fallback.
     *
     * Probed on-device 2026-07-16: `GET /artists/8405?include=biography` came
     * back with an EMPTY biography, so the bio does not reliably arrive in the
     * artist document's `included`. This endpoint is the direct fetch. Per the
     * spec its params are `id`, `countryCode`, `include` (example: `biography`),
     * and it answers `Artists_Single_Relationship_Data_Document` — `data` is a
     * bare `{type,id}` identifier, so **`?include=biography` is load-bearing**:
     * without it the response carries no bio text at all.
     */
    fun artistBiographyRequest(
        id: String,
        accessToken: String,
        include: List<String> = emptyList(),
        countryCode: String? = null,
    ): Request {
        val url = "$BASE/artists/$id/relationships/biography".toHttpUrl().newBuilder()
            .apply { countryCode?.let { addQueryParameter("countryCode", it) } }
            .apply { include.forEach { addQueryParameter("include", it) } }
            .build()
        return authed(url, accessToken)
    }

    /**
     * `GET /users/{id}` — pass `id = "me"` for the authenticated user's
     * resource (spec convention). THE single source of the numeric user id for
     * any user/collection-scoped call (see [TidalMetadataRepository.currentUserId]).
     */
    fun userRequest(id: String, accessToken: String): Request =
        authed("$BASE/users/$id".toHttpUrl(), accessToken)
}
