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

    @Test
    fun `artist request carries every include and the country code`() {
        val request = TidalOfficialEndpoints.artistRequest(
            id = "100",
            accessToken = "tok-123",
            include = listOf("biography", "similarArtists", "albums"),
            countryCode = "US",
        )
        val url = request.url
        assertEquals("/v2/artists/100", url.encodedPath)
        assertEquals(listOf("biography", "similarArtists", "albums"), url.queryParameterValues("include"))
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals("Bearer tok-123", request.header("Authorization"))
        assertEquals("application/vnd.api+json", request.header("Accept"))
    }

    @Test
    fun `user request hits users me with the bearer token`() {
        val request = TidalOfficialEndpoints.userRequest(id = "me", accessToken = "tok-123")
        assertEquals("https://openapi.tidal.com/v2/users/me", request.url.toString())
        assertEquals("Bearer tok-123", request.header("Authorization"))
        assertEquals("application/vnd.api+json", request.header("Accept"))
    }

    @Test
    fun `track request carries include params and country code when asked`() {
        val request = TidalOfficialEndpoints.trackRequest(
            id = "75413016", accessToken = "tok-123",
            include = listOf("artists", "albums"), countryCode = "US",
        )
        val url = request.url
        assertEquals("/v2/tracks/75413016", url.encodedPath)
        assertEquals(listOf("artists", "albums"), url.queryParameterValues("include"))
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals("Bearer tok-123", request.header("Authorization"))
    }

    @Test
    fun `artist biography relationship request carries the include and country code`() {
        val request = TidalOfficialEndpoints.artistBiographyRequest(
            id = "8405", accessToken = "tok-123",
            include = listOf("biography"), countryCode = "US",
        )
        val url = request.url
        assertEquals("/v2/artists/8405/relationships/biography", url.encodedPath)
        // Without ?include=biography the response carries only a {type,id}
        // identifier and no bio text — the include is load-bearing, not cosmetic.
        assertEquals(listOf("biography"), url.queryParameterValues("include"))
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals("Bearer tok-123", request.header("Authorization"))
    }

    @Test
    fun `album request carries include params and the country code`() {
        val request = TidalOfficialEndpoints.albumRequest(
            id = "9876", accessToken = "tok-123",
            include = listOf("items", "artists", "coverArt", "similarAlbums"), countryCode = "GB",
        )
        val url = request.url
        assertEquals("/v2/albums/9876", url.encodedPath)
        assertEquals(listOf("items", "artists", "coverArt", "similarAlbums"), url.queryParameterValues("include"))
        assertEquals("GB", url.queryParameter("countryCode"))
        assertEquals("Bearer tok-123", request.header("Authorization"))
    }

    @Test
    fun `artist request omits the country code when not supplied`() {
        val request = TidalOfficialEndpoints.artistRequest(
            id = "100",
            accessToken = "tok-123",
            include = listOf("albums"),
        )
        val url = request.url
        assertEquals("/v2/artists/100", url.encodedPath)
        assertNull("countryCode is optional per the spec", url.queryParameter("countryCode"))
        assertEquals(listOf("albums"), url.queryParameterValues("include"))
    }
}
