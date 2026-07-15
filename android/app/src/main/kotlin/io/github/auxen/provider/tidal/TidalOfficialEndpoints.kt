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

    fun trackRequest(id: String, accessToken: String): Request =
        authed("$BASE/tracks/$id".toHttpUrl(), accessToken)

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
}
