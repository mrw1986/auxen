package io.github.auxen.provider.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Request-shape tests for [TidalOfficialEndpoints] (Tidal official-API
 * migration, Task 1) -- pure request BUILDING, no execution, matching
 * [TidalOfficialAuthTest]'s pattern. Query params matter here specifically
 * because of the access-tier finding: `/trackManifests/{id}` needs
 * `manifestType=MPEG_DASH` (not HLS -- `media3-exoplayer-hls` isn't a
 * dependency, `media3-exoplayer-dash` already is) and `uriScheme=HTTPS`
 * (not `DATA`, which would return a base64 manifest blob instead of a URL).
 */
class TidalOfficialEndpointsTest {

    @Test
    fun `track request carries the bearer token and hits the right path`() {
        val request = TidalOfficialEndpoints.trackRequest(id = "75413016", accessToken = "tok-123")
        assertEquals("https://openapi.tidal.com/v2/tracks/75413016", request.url.toString())
        assertEquals("Bearer tok-123", request.header("Authorization"))
        assertEquals("application/vnd.api+json", request.header("Accept"))
    }

    @Test
    fun `track manifest request asks for MPEG_DASH over HTTPS, not HLS or a data URI`() {
        val request = TidalOfficialEndpoints.trackManifestRequest(
            id = "75413016",
            accessToken = "tok-123",
            formats = listOf("FLAC", "FLAC_HIRES"),
        )
        val url = request.url
        assertEquals("openapi.tidal.com", url.host)
        assertEquals("/v2/trackManifests/75413016", url.encodedPath)
        assertEquals("MPEG_DASH", url.queryParameter("manifestType"))
        assertEquals("HTTPS", url.queryParameter("uriScheme"))
        assertEquals("PLAYBACK", url.queryParameter("usage"))
        assertEquals("false", url.queryParameter("adaptive"))
        assertEquals(listOf("FLAC", "FLAC_HIRES"), url.queryParameterValues("formats"))
    }

    @Test
    fun `track file request asks for playback usage in the requested formats`() {
        val request = TidalOfficialEndpoints.trackFileRequest(
            id = "75413016",
            accessToken = "tok-123",
            formats = listOf("FLAC_HIRES"),
        )
        val url = request.url
        assertEquals("/v2/trackFiles/75413016", url.encodedPath)
        assertEquals("PLAYBACK", url.queryParameter("usage"))
        assertEquals(listOf("FLAC_HIRES"), url.queryParameterValues("formats"))
        assertNull("trackFiles has no manifestType param", url.queryParameter("manifestType"))
    }
}
