package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalInternalTrackDetailTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- request shapes ----

    @Test
    fun `trackCreditsUrl asks for contributors and passes countryCode`() {
        val url = trackCreditsUrl("https://api.tidal.com/v1", "186460926", "US")
        assertEquals("/v1/tracks/186460926/credits", url.encodedPath)
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals("true", url.queryParameter("includeContributors"))
    }

    @Test
    fun `trackLyricsUrl passes only countryCode`() {
        val url = trackLyricsUrl("https://api.tidal.com/v1", "186460926", "US")
        assertEquals("/v1/tracks/186460926/lyrics", url.encodedPath)
        assertEquals(setOf("countryCode"), url.queryParameterNames)
    }

    // ---- credits: role-grouped, contributor id optional ----

    @Test
    fun `trackCreditsFrom groups contributors by role, dropping blank roles and empty groups`() {
        val body = """
            [ { "type": "Producer", "contributors": [ { "name": "Bob Rock", "id": 123 }, { "name": "James Hetfield" } ] },
              { "type": "Composer", "contributors": [ { "name": "James Hetfield" } ] },
              { "type": "", "contributors": [ { "name": "Nobody" } ] },
              { "type": "Mixer", "contributors": [] } ]
        """.trimIndent()
        val credits = trackCreditsFrom(body, json)
        assertEquals(listOf("Producer", "Composer"), credits.map { it.role })
        assertEquals(listOf("Bob Rock", "James Hetfield"), credits[0].names)
        assertEquals(listOf("James Hetfield"), credits[1].names)
    }

    @Test
    fun `trackCreditsFrom returns empty for a 404 body, an object, or non-JSON`() {
        // The provider swallows the non-2xx into a null body; a stray object or a
        // v1 error envelope must also degrade to "no credits", never throw.
        assertTrue(trackCreditsFrom("""{ "status": 404 }""", json).isEmpty())
        assertTrue(trackCreditsFrom("not json", json).isEmpty())
        assertTrue(trackCreditsFrom("[]", json).isEmpty())
    }

    // ---- lyrics: plain + optional synced; 404 handled upstream ----

    @Test
    fun `trackLyricsFrom reads plain lyrics and synced subtitles`() {
        val body = """
            { "lyrics": "Say your prayers little one", "subtitles": "[00:12.00]Say your prayers little one",
              "lyricsProvider": "MUSIXMATCH", "isRightToLeft": false, "trackId": 186460926 }
        """.trimIndent()
        val lyrics = trackLyricsFrom(body, json)!!
        assertEquals("Say your prayers little one", lyrics.lyrics)
        assertEquals("[00:12.00]Say your prayers little one", lyrics.subtitles)
    }

    @Test
    fun `trackLyricsFrom returns null when both fields are absent, blank, or the body is bad`() {
        assertNull(trackLyricsFrom("""{ "lyrics": null, "subtitles": null }""", json))
        assertNull(trackLyricsFrom("""{ "lyrics": "   ", "subtitles": "" }""", json))
        assertNull(trackLyricsFrom("not json", json))
    }

    @Test
    fun `trackLyricsFrom keeps synced-only lyrics so the sheet can report them`() {
        val lyrics = trackLyricsFrom("""{ "lyrics": null, "subtitles": "[00:01.00]La" }""", json)!!
        assertNull(lyrics.lyrics)
        assertEquals("[00:01.00]La", lyrics.subtitles)
    }
}
