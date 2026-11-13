package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalMultiTypeSearchTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Trimmed from the live probe of `search?query=cake&types=ARTISTS,ALBUMS,TRACKS`
     * (.superpowers/sdd/search-multitype-schema.md). Deliberately includes:
     *  - `artists` with a picture AND a picture:null item (the Person-glyph fallback)
     *  - `albums` whose items carry `artists` plural (no singular `artist`) and a
     *    MALFORMED id-less album -> dropped, sibling survives
     *  - `tracks` whose items carry `artists` plural (Finding 1)
     *  - `playlists`, `videos`, `topHit` present -> ignored, parse still works
     */
    private val body = """
        {
          "artists": {
            "limit": 20, "offset": 0, "totalNumberOfItems": 111,
            "items": [
              { "id": 572, "name": "CAKE", "picture": "73f443d3-0ebd-43e0-a3ba-1d470d9b131a",
                "popularity": 78, "url": "http://www.tidal.com/artist/572" },
              { "id": 3707146, "name": "CAKE (Tribute)", "picture": null }
            ]
          },
          "albums": {
            "limit": 20, "offset": 0, "totalNumberOfItems": 92,
            "items": [
              { "id": 660646, "title": "Fashion Nugget", "cover": "12345678-90ab-cdef-1234-567890abcdef",
                "releaseDate": "1996-09-17", "explicit": false, "numberOfTracks": 14,
                "artists": [ { "id": 572, "name": "CAKE" } ] },
              { "title": "A malformed album with no id", "artists": [ { "id": 572, "name": "CAKE" } ] }
            ]
          },
          "tracks": {
            "limit": 20, "offset": 0, "totalNumberOfItems": 214,
            "items": [
              { "id": 668138, "title": "The Distance", "duration": 181, "explicit": false,
                "audioQuality": "LOSSLESS", "trackNumber": 2, "volumeNumber": 1,
                "artists": [ { "id": 572, "name": "CAKE" } ],
                "album": { "id": 660646, "title": "Fashion Nugget", "cover": "12345678-90ab-cdef-1234-567890abcdef" } }
            ]
          },
          "playlists": { "items": [] },
          "videos": { "items": [] },
          "topHit": { "value": { "id": 572, "name": "CAKE" }, "type": "ARTISTS" }
        }
    """.trimIndent()

    private fun results() = searchResultsFrom(body, json)

    // ---- 1. the request shape ----

    @Test
    fun `searchAllUrl hits search (not search-tracks) with all three types`() {
        val url = searchAllUrl(apiBase = "https://api.tidal.com/v1", query = "cake", countryCode = "US", limit = 20)
        assertEquals("api.tidal.com", url.host)
        assertEquals("/v1/search", url.encodedPath) // NOT /v1/search/tracks
        assertEquals("cake", url.queryParameter("query"))
        assertEquals("ARTISTS,ALBUMS,TRACKS", url.queryParameter("types"))
        assertEquals("20", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals(
            setOf("query", "types", "limit", "offset", "countryCode"),
            url.queryParameterNames,
        )
    }

    // ---- 2. artists -- WITH the photos, so the rail is not a wall of glyphs ----

    @Test
    fun `searchResultsFrom maps artists with 480 pictures and tolerates a null picture`() {
        // Picture @480 (the ARTIST size; 640 is the ALBUM size and does not
        // render for a picture -- tidalV1ArtistPictureUrl's KDoc). id as String,
        // ready for artistRoute(name, tidalArtistId=id).
        val artists = results().artists
        assertEquals(listOf("CAKE", "CAKE (Tribute)"), artists.map { it.name })
        assertEquals(listOf("572", "3707146"), artists.map { it.id })
        assertEquals(
            "https://resources.tidal.com/images/73f443d3/0ebd/43e0/a3ba/1d470d9b131a/480x480.jpg",
            artists[0].artworkUrl,
        )
        assertNull(artists[1].artworkUrl) // picture: null -> the Person glyph
    }

    // ---- 3. albums -- with the artist name the route needs (Finding 2) ----

    @Test
    fun `searchResultsFrom maps albums with 640 cover, year, explicit and the artist name`() {
        val albums = results().albums
        assertEquals(1, albums.size) // the id-less album is dropped
        val fashion = albums.single()
        assertEquals("660646", fashion.summary.id)
        assertEquals("Fashion Nugget", fashion.summary.title)
        assertEquals(1996, fashion.summary.releaseYear)
        assertEquals(false, fashion.summary.explicit)
        assertEquals(
            "https://resources.tidal.com/images/12345678/90ab/cdef/1234/567890abcdef/640x640.jpg",
            fashion.summary.artworkUrl,
        )
        // FINDING 2: artists[0].name, carried via the new TidalV1Album.artists
        // field. AlbumSummary has no artist field, so the wrapper holds it -- for
        // the card subtitle AND the albumRoute(title, artist, tidalAlbumId) arg.
        assertEquals("CAKE", fashion.artistName)
    }

    @Test
    fun `searchResultsFrom drops a malformed album and keeps its siblings`() {
        // The id-less album is decoded one-at-a-time under runCatching (reused
        // decodeEach), so one bad row cannot cost the whole albums section.
        assertEquals(listOf("Fashion Nugget"), results().albums.map { it.summary.title })
    }

    // ---- 4. tracks -- Finding 1: artist from `artists` plural, never blank ----

    @Test
    fun `searchResultsFrom reads the track artist from artists plural - there is no singular artist`() {
        // FINDING 1. Multi-type search track items carry `artists` and NO
        // `artist`. TidalProvider's private TrackDto reads the SINGULAR and
        // defaults to name="" -- routing this through it would blank every result
        // row's artist. TidalV1PageTrack.toTrack reads artists[0]. That is why
        // the reuse is TidalV1PageTrack, not TrackDto.
        val tracks = results().tracks
        assertEquals(listOf("The Distance"), tracks.map { it.title })
        assertEquals(listOf("CAKE"), tracks.map { it.artist })
        assertFalse(tracks.any { it.artist.isBlank() })
        assertTrue(tracks.all { it.isTidal })
        assertEquals(listOf("668138"), tracks.map { it.sourceId })
    }

    @Test
    fun `searchResultsFrom maps track album, art at 640, quality, duration and numbering`() {
        val distance = results().tracks.single()
        assertEquals("Fashion Nugget", distance.album)
        assertEquals(181.0, distance.durationSeconds!!, 0.001)
        assertEquals(2, distance.trackNumber)
        assertEquals(1, distance.discNumber)
        assertEquals("FLAC", distance.format)
        assertEquals("FLAC", distance.qualityLabel)
        assertEquals(
            "https://resources.tidal.com/images/12345678/90ab/cdef/1234/567890abcdef/640x640.jpg",
            distance.albumArtUrl,
        )
    }

    // ---- 5. TOTALITY -- never crash, never lose the other types over one ----

    @Test
    fun `searchResultsFrom ignores playlists, videos and topHit`() {
        // They are in the fixture and are not projected. This endpoint is
        // undocumented; ignoreUnknownKeys drops what we do not model.
        val r = results()
        assertEquals(2, r.artists.size)
        assertEquals(1, r.albums.size)
        assertEquals(1, r.tracks.size)
    }

    @Test
    fun `searchResultsFrom yields an empty block for a query that returns no artists`() {
        // An EMPTY type block: items: []. Albums/tracks still parse.
        val emptyArtists = """
            { "artists": { "items": [] },
              "albums": { "items": [ { "id": 660646, "title": "Fashion Nugget",
                "artists": [ { "id": 572, "name": "CAKE" } ] } ] },
              "tracks": { "items": [] } }
        """.trimIndent()
        val r = searchResultsFrom(emptyArtists, json)
        assertTrue(r.artists.isEmpty())
        assertEquals(listOf("Fashion Nugget"), r.albums.map { it.summary.title })
        assertTrue(r.tracks.isEmpty())
    }

    @Test
    fun `searchResultsFrom yields an empty block for a MISSING type key`() {
        // A body with only `tracks` -- no `artists`/`albums` keys at all. The
        // defaulted TidalV1PagedList() blocks make a missing key empty, not a throw.
        val tracksOnly = """
            { "tracks": { "items": [
                { "id": 1, "title": "Solo", "artists": [ { "id": 9, "name": "Nobody" } ] }
            ] } }
        """.trimIndent()
        val r = searchResultsFrom(tracksOnly, json)
        assertTrue(r.artists.isEmpty())
        assertTrue(r.albums.isEmpty())
        assertEquals(listOf("Solo"), r.tracks.map { it.title })
        assertEquals(listOf("Nobody"), r.tracks.map { it.artist })
    }

    @Test
    fun `searchResultsFrom returns EMPTY for malformed JSON or an error envelope`() {
        // Every one means the same thing to the caller: no Tidal results, and the
        // screen shows local tracks (or nothing) -- never a crash.
        assertEquals(TidalSearchResults.EMPTY, searchResultsFrom("not json at all", json))
        assertEquals(TidalSearchResults.EMPTY, searchResultsFrom("<html>502 Bad Gateway</html>", json))
        // A v1 error envelope decodes CLEANLY into all-default (empty) blocks.
        val err = searchResultsFrom("""{ "status": 401, "userMessage": "unauthorized" }""", json)
        assertTrue(err.artists.isEmpty() && err.albums.isEmpty() && err.tracks.isEmpty())
    }
}
