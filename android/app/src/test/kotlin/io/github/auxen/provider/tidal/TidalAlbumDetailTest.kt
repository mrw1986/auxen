package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalAlbumDetailTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val albumDoc = """
        {
          "data": {
            "id": "9876", "type": "albums",
            "attributes": { "title": "The Album", "albumType": "ALBUM", "releaseDate": "2020-07-10", "explicit": true },
            "relationships": {
              "coverArt":      { "data": { "id": "cov1", "type": "artworks" } },
              "artists":       { "data": [ { "id": "3456", "type": "artists" } ] },
              "items":         { "data": [ { "id": "t1", "type": "tracks" }, { "id": "t2", "type": "tracks" } ] },
              "similarAlbums": { "data": [ { "id": "9999", "type": "albums" } ] }
            }
          },
          "included": [
            { "id": "cov1", "type": "artworks", "attributes": {
                "files": [ { "href": "https://cov/1280.jpg", "meta": { "width": 1280, "height": 1280 } } ] } },
            { "id": "3456", "type": "artists", "attributes": { "name": "Prompto" } },
            { "id": "t1", "type": "tracks", "attributes": { "title": "Opener", "duration": "PT4M00S", "explicit": true } },
            { "id": "t2", "type": "tracks", "attributes": { "title": "Closer", "duration": "PT2M30S", "explicit": false } },
            { "id": "9999", "type": "albums", "attributes": { "title": "Neighbor Album", "albumType": "ALBUM", "releaseDate": "2018-01-01" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `albumDetailFrom maps metadata, artist and best-fit cover art`() {
        val d = albumDetailFrom(json.decodeFromString(albumDoc), json, artworkTargetPx = 640)!!
        assertEquals("9876", d.id)
        assertEquals("The Album", d.title)
        assertEquals("Prompto", d.artistName)
        assertEquals("3456", d.artistId)
        assertEquals("ALBUM", d.albumType)
        assertEquals(2020, d.releaseYear)
        assertTrue(d.explicit)
        assertEquals("https://cov/1280.jpg", d.artworkUrl)
    }

    @Test
    fun `albumDetailFrom builds the ordered playable tracklist with album context`() {
        val d = albumDetailFrom(json.decodeFromString(albumDoc), json, artworkTargetPx = 640)!!
        assertEquals(listOf("Opener", "Closer"), d.tracks.map { it.title })
        assertEquals(listOf(1, 2), d.tracks.map { it.trackNumber })
        d.tracks.forEach {
            assertEquals(Source.TIDAL, it.source)
            assertEquals("The Album", it.album)
            assertEquals("Prompto", it.artist)
            assertEquals("https://cov/1280.jpg", it.albumArtUrl)
        }
        assertEquals(240.0, d.tracks.first().durationSeconds!!, 0.0)
    }

    @Test
    fun `albumDetailFrom maps similar albums`() {
        val d = albumDetailFrom(json.decodeFromString(albumDoc), json, artworkTargetPx = 640)!!
        assertEquals(listOf("Neighbor Album"), d.similarAlbums.map { it.title })
        assertEquals(2018, d.similarAlbums.single().releaseYear)
    }

    @Test
    fun `albumDetailFrom returns null without a primary album`() {
        assertNull(albumDetailFrom(json.decodeFromString("""{ "data": [], "included": [] }"""), json, 640))
    }
}
