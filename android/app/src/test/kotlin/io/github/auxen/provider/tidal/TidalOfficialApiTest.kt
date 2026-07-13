package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON:API envelope parsing for the minimal official-API client (Tidal
 * official-API migration, Task 1). No Robolectric needed -- pure
 * kotlinx.serialization over string fixtures, matching [PkceTest]'s "no
 * VM/Compose runtime" convention. Fixtures are hand-built to match the
 * cached spec's exact schemas (`.superpowers/sdd/tidal-openapi-v2.json`:
 * `Tracks_Attributes`, `TrackManifests_Attributes`,
 * `TrackFiles_Attributes`, and the 403 error body shape), not guessed.
 */
class TidalOfficialApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses a track document`() {
        val body = """
            {
              "data": {
                "id": "75413016",
                "type": "tracks",
                "attributes": {
                  "title": "Kill Jay Z",
                  "duration": "PT2M58S",
                  "isrc": "QMJMT1701229",
                  "explicit": false
                }
              },
              "links": { "self": "/tracks/75413016" }
            }
        """.trimIndent()
        val doc = json.decodeFromString<TrackDocument>(body)
        assertEquals("75413016", doc.data.id)
        assertEquals("tracks", doc.data.type)
        assertEquals("Kill Jay Z", doc.data.attributes?.title)
        assertEquals("PT2M58S", doc.data.attributes?.duration)
        assertEquals(false, doc.data.attributes?.explicit)
    }

    @Test
    fun `parses a FULL-presentation FLAC_HIRES track manifest`() {
        val body = """
            {
              "data": {
                "id": "75413016",
                "type": "trackManifests",
                "attributes": {
                  "trackPresentation": "FULL",
                  "formats": ["FLAC_HIRES"],
                  "uri": "https://example.tidal.com/manifest.mpd",
                  "hash": "abc123"
                }
              },
              "links": { "self": "/trackManifests/75413016" }
            }
        """.trimIndent()
        val doc = json.decodeFromString<TrackManifestDocument>(body)
        assertEquals("FULL", doc.data.attributes?.trackPresentation)
        assertEquals(listOf("FLAC_HIRES"), doc.data.attributes?.formats)
        assertEquals("https://example.tidal.com/manifest.mpd", doc.data.attributes?.uri)
        assertNull("a FULL presentation must not carry a previewReason", doc.data.attributes?.previewReason)
        assertTrue(doc.data.isFullPresentation())
    }

    @Test
    fun `parses a PREVIEW-presentation track manifest gated by access tier`() {
        val body = """
            {
              "data": {
                "id": "75413016",
                "type": "trackManifests",
                "attributes": {
                  "trackPresentation": "PREVIEW",
                  "previewReason": "FULL_REQUIRES_HIGHER_ACCESS_TIER",
                  "formats": ["AACLC"],
                  "uri": "https://example.tidal.com/preview.mpd"
                }
              },
              "links": { "self": "/trackManifests/75413016" }
            }
        """.trimIndent()
        val doc = json.decodeFromString<TrackManifestDocument>(body)
        assertEquals("PREVIEW", doc.data.attributes?.trackPresentation)
        assertEquals("FULL_REQUIRES_HIGHER_ACCESS_TIER", doc.data.attributes?.previewReason)
        assertTrue("PREVIEW must not report as full", !doc.data.isFullPresentation())
    }

    @Test
    fun `parses a track file document`() {
        val body = """
            {
              "data": {
                "id": "75413016",
                "type": "trackFiles",
                "attributes": {
                  "trackPresentation": "FULL",
                  "format": "FLAC_HIRES",
                  "url": "https://example.tidal.com/track.flac"
                }
              },
              "links": { "self": "/trackFiles/75413016" }
            }
        """.trimIndent()
        val doc = json.decodeFromString<TrackFileDocument>(body)
        assertEquals("FLAC_HIRES", doc.data.attributes?.format)
        assertEquals("https://example.tidal.com/track.flac", doc.data.attributes?.url)
        assertTrue(doc.data.isFullPresentation())
    }

    @Test
    fun `parses a 403 access-tier error body`() {
        // The exact shape TrackFilesReadById403ResponseBody/
        // TrackManifestsReadById403ResponseBody both use.
        val body = """
            {
              "errors": [
                {
                  "code": "CLIENT_NOT_ENTITLED",
                  "status": "403",
                  "detail": "Cannot fulfill this request because required prerequisites are missing"
                }
              ]
            }
        """.trimIndent()
        val doc = json.decodeFromString<JsonApiErrorDocument>(body)
        assertEquals(1, doc.errors.size)
        assertEquals("CLIENT_NOT_ENTITLED", doc.errors.first().code)
        assertEquals("403", doc.errors.first().status)
    }
}
