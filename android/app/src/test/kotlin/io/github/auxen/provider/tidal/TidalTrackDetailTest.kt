package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalTrackDetailTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val trackDoc = """
        {
          "data": {
            "id": "75413016", "type": "tracks",
            "attributes": { "title": "Sample", "isrc": "USABC1234567" },
            "relationships": {
              "artists":       { "data": [ { "id": "3456", "type": "artists" } ] },
              "credits":       { "data": [
                  { "id": "c1", "type": "credits" }, { "id": "c2", "type": "credits" }, { "id": "c3", "type": "credits" } ] },
              "lyrics":        { "data": { "id": "ly1", "type": "lyrics" } },
              "similarTracks": { "data": [ { "id": "8001", "type": "tracks" } ] }
            }
          },
          "included": [
            { "id": "3456", "type": "artists", "attributes": { "name": "Prompto" } },
            { "id": "c1", "type": "credits", "attributes": { "role": "Producer", "name": "Alice" } },
            { "id": "c2", "type": "credits", "attributes": { "role": "Producer", "name": "Bob" } },
            { "id": "c3", "type": "credits", "attributes": { "role": "Mixer", "name": "Carol" } },
            { "id": "ly1", "type": "lyrics", "attributes": { "text": "line one\nline two", "lrcText": "[00:01.00]line one" } },
            { "id": "8001", "type": "tracks", "attributes": { "title": "Similar One", "duration": "PT3M00S" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `trackDetailFrom groups credits by role, preserving order`() {
        val d = trackDetailFrom(json.decodeFromString(trackDoc), json, artworkTargetPx = 640)!!
        assertEquals("Sample", d.title)
        assertEquals(listOf("Producer", "Mixer"), d.credits.map { it.role })
        assertEquals(listOf("Alice", "Bob"), d.credits.first().names)
        assertEquals(listOf("Carol"), d.credits[1].names)
    }

    @Test
    fun `trackDetailFrom maps plain and synced lyrics`() {
        val d = trackDetailFrom(json.decodeFromString(trackDoc), json, artworkTargetPx = 640)!!
        assertEquals("line one\nline two", d.lyricsText)
        assertEquals("[00:01.00]line one", d.lyricsSynced)
    }

    @Test
    fun `trackDetailFrom maps similar tracks`() {
        val d = trackDetailFrom(json.decodeFromString(trackDoc), json, artworkTargetPx = 640)!!
        assertEquals(listOf("8001"), d.similarTracks.map { it.sourceId })
        assertEquals(listOf("Similar One"), d.similarTracks.map { it.title })
    }

    @Test
    fun `trackDetailFrom tolerates a track with no credits or lyrics`() {
        val doc = """{ "data": { "id": "1", "type": "tracks", "attributes": { "title": "Bare" } }, "included": [] }"""
        val d = trackDetailFrom(json.decodeFromString(doc), json, artworkTargetPx = 640)!!
        assertEquals("Bare", d.title)
        assertTrue(d.credits.isEmpty())
        assertNull(d.lyricsText)
        assertNull(d.lyricsSynced)
        assertTrue(d.similarTracks.isEmpty())
    }

    @Test
    fun `trackDetailFrom returns null without a primary track`() {
        assertNull(trackDetailFrom(json.decodeFromString("""{ "data": [], "included": [] }"""), json, 640))
    }
}
