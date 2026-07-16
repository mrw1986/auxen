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

    /**
     * A 2-volume deluxe edition carrying the spec's real linkage meta
     * (`Albums_Items_Resource_Identifier_Meta`), plus a video item — which
     * `AlbumsItemsRelationshipUpdateOperation_Payload_Data.type`'s
     * `enum: ["tracks","videos"]` permits in this very relationship.
     */
    private val deluxeDoc = """
        {
          "data": {
            "id": "5150", "type": "albums",
            "attributes": { "title": "Deluxe", "albumType": "ALBUM", "releaseDate": "2021-01-01", "explicit": false },
            "relationships": {
              "items": { "data": [
                { "id": "t1", "type": "tracks", "meta": { "trackNumber": 1, "volumeNumber": 1 } },
                { "id": "t2", "type": "tracks", "meta": { "trackNumber": 2, "volumeNumber": 1 } },
                { "id": "v1", "type": "videos", "meta": { "trackNumber": 3, "volumeNumber": 1 } },
                { "id": "t3", "type": "tracks", "meta": { "trackNumber": 1, "volumeNumber": 2 } }
              ] }
            }
          },
          "included": [
            { "id": "t1", "type": "tracks", "attributes": { "title": "Vol1 Opener", "duration": "PT4M00S" } },
            { "id": "t2", "type": "tracks", "attributes": { "title": "Vol1 Closer", "duration": "PT3M00S" } },
            { "id": "v1", "type": "videos", "attributes": { "title": "Behind The Scenes", "duration": "PT5M00S" } },
            { "id": "t3", "type": "tracks", "attributes": { "title": "Vol2 Opener", "duration": "PT2M30S" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `albumDetailFrom numbers a multi-volume tracklist from linkage meta, not position`() {
        val d = albumDetailFrom(json.decodeFromString(deluxeDoc), json, artworkTargetPx = 640)!!
        // Positional numbering would give 1, 2, 3 — the volume-2 opener must restart at 1.
        assertEquals(listOf(1, 2, 1), d.tracks.map { it.trackNumber })
        assertEquals(listOf(1, 1, 2), d.tracks.map { it.discNumber })
    }

    @Test
    fun `albumDetailFrom drops video items from the playable tracklist`() {
        val d = albumDetailFrom(json.decodeFromString(deluxeDoc), json, artworkTargetPx = 640)!!
        assertEquals(listOf("Vol1 Opener", "Vol1 Closer", "Vol2 Opener"), d.tracks.map { it.title })
        assertTrue(d.tracks.none { it.sourceId == "v1" })
        d.tracks.forEach { assertEquals(Source.TIDAL, it.source) }
    }

    @Test
    fun `albumDetailFrom falls back to positional numbering when the linkage carries no meta`() {
        val d = albumDetailFrom(json.decodeFromString(albumDoc), json, artworkTargetPx = 640)!!
        assertEquals(listOf(1, 2), d.tracks.map { it.trackNumber })
        d.tracks.forEach { assertNull(it.discNumber) }
    }
}
