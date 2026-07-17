package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidalInternalAlbumTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- request shapes ----

    @Test
    fun `albumMetadataUrl sends only countryCode`() {
        val url = albumMetadataUrl("https://api.tidal.com/v1", "166790187", "US")
        assertEquals("/v1/albums/166790187", url.encodedPath)
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals(setOf("countryCode"), url.queryParameterNames)
    }

    @Test
    fun `albumItemsUrl carries limit, offset and countryCode`() {
        val url = albumItemsUrl("https://api.tidal.com/v1", "166790187", "US", limit = 100, offset = 100)
        assertEquals("/v1/albums/166790187/items", url.encodedPath)
        assertEquals("100", url.queryParameter("limit"))
        assertEquals("100", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
    }

    @Test
    fun `trackRelationsUrl is the bare track read plus countryCode`() {
        val url = trackRelationsUrl("https://api.tidal.com/v1", "186460926", "US")
        assertEquals("/v1/tracks/186460926", url.encodedPath)
        assertEquals(setOf("countryCode"), url.queryParameterNames)
    }

    // ---- trackRelationsFromV1: artist + album id off ONE track read ----

    @Test
    fun `trackRelationsFromV1 reads artistId from artists plural and albumId from album`() {
        val body = """
            { "id": 186460926, "title": "Enter Sandman",
              "artists": [ { "id": 8405, "name": "Metallica" } ],
              "album": { "id": 166790187, "title": "Metallica", "cover": null } }
        """.trimIndent()
        val rel = trackRelationsFromV1(body, json)!!
        assertEquals("8405", rel.artistId)
        assertEquals("166790187", rel.albumId)
        assertNull(rel.isrc) // isrc unused while direct id-equivalence holds
    }

    @Test
    fun `trackRelationsFromV1 returns null for an error envelope or non-JSON`() {
        assertNull(trackRelationsFromV1("""{ "status": 404, "userMessage": "not found" }""", json))
        assertNull(trackRelationsFromV1("not json", json))
    }

    // ---- albumDetailFromV1: the whole projection ----

    private val metaBody = """
        { "id": 166790187, "title": "Metallica",
          "cover": "d0747f21-26ff-447c-8d04-0d8f3635e205",
          "releaseDate": "1991-08-12", "explicit": false, "numberOfTracks": 3,
          "type": "ALBUM",
          "artist": { "id": 8405, "name": "Metallica" },
          "artists": [ { "id": 8405, "name": "Metallica" } ] }
    """.trimIndent()

    // A two-volume tracklist with a VIDEO interleaved. The video must be dropped;
    // the native volumeNumber must survive so disc 1 t1 and disc 2 t1 are distinct.
    private val itemsBody = """
        { "totalNumberOfItems": 3, "items": [
          { "type": "track", "item": {
              "id": 166790188, "title": "Enter Sandman", "duration": 332, "explicit": false,
              "audioQuality": "LOSSLESS", "trackNumber": 1, "volumeNumber": 1,
              "artists": [ { "id": 8405, "name": "Metallica" } ] } },
          { "type": "video", "item": {
              "id": 999999, "title": "A behind-the-scenes video", "duration": 120,
              "trackNumber": 2, "volumeNumber": 1,
              "artists": [ { "id": 8405, "name": "Metallica" } ] } },
          { "type": "track", "item": {
              "id": 166790200, "title": "Nothing Else Matters", "duration": 388, "explicit": false,
              "audioQuality": "HI_RES_LOSSLESS", "trackNumber": 1, "volumeNumber": 2,
              "artists": [ { "id": 8405, "name": "Metallica" } ] } }
        ] }
    """.trimIndent()

    private fun detail() = albumDetailFromV1(metaBody, listOf(itemsBody), json)!!

    @Test
    fun `albumDetailFromV1 maps album metadata`() {
        val d = detail()
        assertEquals("166790187", d.id)
        assertEquals("Metallica", d.title)
        assertEquals("Metallica", d.artistName)
        assertEquals("8405", d.artistId)
        assertEquals("ALBUM", d.albumType)
        assertEquals(1991, d.releaseYear)
        assertEquals(false, d.explicit)
        assertEquals(
            "https://resources.tidal.com/images/d0747f21/26ff/447c/8d04/0d8f3635e205/640x640.jpg",
            d.artworkUrl,
        )
        assertEquals(emptyList<AlbumSummary>(), d.similarAlbums)
    }

    @Test
    fun `albumDetailFromV1 drops video items and keeps native track and volume numbers`() {
        val tracks = detail().tracks
        assertEquals(listOf("Enter Sandman", "Nothing Else Matters"), tracks.map { it.title })
        assertEquals(listOf("166790188", "166790200"), tracks.map { it.sourceId })
        // Native numbering: BOTH are trackNumber 1 but on different volumes.
        assertEquals(listOf(1, 1), tracks.map { it.trackNumber })
        assertEquals(listOf(1, 2), tracks.map { it.discNumber })
    }

    @Test
    fun `albumDetailFromV1 reads artist from artists plural, quality, duration and stamps the album cover`() {
        val (sandman, matters) = detail().tracks
        assertEquals("Metallica", sandman.artist)
        assertEquals("Metallica", sandman.album) // stamped from the album title
        assertEquals(332.0, sandman.durationSeconds!!, 0.001)
        assertEquals("FLAC", sandman.format)
        assertEquals("Hi-Res", matters.format)
        // Each row carries the album's 640 cover even when the item has no inline album.
        assertEquals(
            "https://resources.tidal.com/images/d0747f21/26ff/447c/8d04/0d8f3635e205/640x640.jpg",
            sandman.albumArtUrl,
        )
        assertEquals(true, sandman.source == io.github.auxen.model.Source.TIDAL)
    }

    @Test
    fun `albumDetailFromV1 concatenates every items page in order`() {
        val page2 = """
            { "totalNumberOfItems": 3, "items": [
              { "type": "track", "item": { "id": 3, "title": "The God That Failed",
                  "duration": 308, "trackNumber": 2, "volumeNumber": 2,
                  "artists": [ { "id": 8405, "name": "Metallica" } ] } }
            ] }
        """.trimIndent()
        val d = albumDetailFromV1(metaBody, listOf(itemsBody, page2), json)!!
        assertEquals(listOf("Enter Sandman", "Nothing Else Matters", "The God That Failed"), d.tracks.map { it.title })
    }

    @Test
    fun `albumDetailFromV1 returns null for a non-album body`() {
        assertNull(albumDetailFromV1("""{ "status": 404 }""", listOf(), json))
        assertNull(albumDetailFromV1("not json", listOf(itemsBody), json))
    }

    @Test
    fun `albumDetailFromV1 tolerates a malformed items page and keeps the album`() {
        // One bad page must not cost the album its metadata or its good pages.
        val d = albumDetailFromV1(metaBody, listOf("not json at all", itemsBody), json)!!
        assertEquals("Metallica", d.title)
        assertEquals(listOf("Enter Sandman", "Nothing Else Matters"), d.tracks.map { it.title })
    }
}
