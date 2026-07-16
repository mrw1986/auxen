package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalInternalArtistTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- 1. the discography: artists/{id}/albums ----

    @Test
    fun `artistAlbumsUrl mirrors tidalapi get_albums - limit, offset, countryCode and NO filter`() {
        val url = artistAlbumsUrl(
            apiBase = "https://api.tidal.com/v1",
            artistId = "8405",
            countryCode = "US",
            limit = 50,
        )
        assertEquals("api.tidal.com", url.host)
        assertEquals("/v1/artists/8405/albums", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
        // THE POINT: tidalapi's Artist.get_albums sends NO filter — that absence
        // selects the endpoint's default view (the real discography). Its
        // siblings send filter=EPSANDSINGLES / filter=COMPILATIONS. If a filter
        // ever appears here, the discography silently becomes the wrong list.
        assertNull(url.queryParameter("filter"))
    }

    @Test
    fun `artistAlbumsFrom projects the v1 items envelope into AlbumSummary cards`() {
        val body = """
            {
              "limit": 50, "offset": 0, "totalNumberOfItems": 2,
              "items": [
                { "id": 186460912, "title": "Hardwired…To Self-Destruct", "cover": "aaaa-bbbb-cccc",
                  "type": "ALBUM", "releaseDate": "2016-11-18", "explicit": true, "numberOfTracks": 12,
                  "artist": { "id": 8405, "name": "Metallica" } },
                { "id": 77680105, "title": "Ride the Lightning", "cover": null,
                  "type": "ALBUM", "releaseDate": "1984-07-27", "numberOfTracks": 8 }
              ]
            }
        """.trimIndent()
        val albums = artistAlbumsFrom(body, json)
        assertEquals(listOf("186460912", "77680105"), albums.map { it.id })
        assertEquals(listOf("Hardwired…To Self-Destruct", "Ride the Lightning"), albums.map { it.title })
        assertEquals(listOf("ALBUM", "ALBUM"), albums.map { it.albumType })
        assertEquals(listOf(2016, 1984), albums.map { it.releaseYear })
        assertEquals(listOf(true, false), albums.map { it.explicit })
        assertEquals("https://resources.tidal.com/images/aaaa/bbbb/cccc/640x640.jpg", albums.first().artworkUrl)
        assertNull(albums[1].artworkUrl)
    }

    @Test
    fun `artistAlbumsFrom keeps COMPILATION - the type v2 cannot express`() {
        // Albums_Attributes.albumType is ALBUM|EP|SINGLE with no COMPILATION,
        // which is precisely why the discography is sourced from v1.
        val body = """{ "items": [ { "id": 1, "title": "Garage Inc.", "type": "COMPILATION" } ] }"""
        assertEquals("COMPILATION", artistAlbumsFrom(body, json).single().albumType)
    }

    @Test
    fun `artistAlbumsFrom returns empty for a malformed or error body`() {
        // A missing discography must never take down the rest of the artist page.
        assertTrue(artistAlbumsFrom("""{ "status": 404, "userMessage": "not found" }""", json).isEmpty())
        assertTrue(artistAlbumsFrom("not json at all", json).isEmpty())
    }

    @Test
    fun `tidalV1CoverUrl converts a cover uuid to the CDN path, tolerating null and blank`() {
        assertEquals(
            "https://resources.tidal.com/images/1e01/cdcc/9d3c/640x640.jpg",
            tidalV1CoverUrl("1e01-cdcc-9d3c"),
        )
        assertNull(tidalV1CoverUrl(null))
        assertNull(tidalV1CoverUrl(""))
    }

    // ---- 2. the top tracks: artists/{id}/toptracks ----

    @Test
    fun `artistTopTracksUrl mirrors tidalapi get_top_tracks - limit, offset, countryCode`() {
        val url = artistTopTracksUrl(
            apiBase = "https://api.tidal.com/v1",
            artistId = "8405",
            countryCode = "US",
            limit = 50,
        )
        assertEquals("api.tidal.com", url.host)
        // `toptracks`, one word, lowercase — tidalapi/artist.py:190.
        assertEquals("/v1/artists/8405/toptracks", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
        // get_top_tracks sends {limit, offset} only — no filter, no mode.
        assertNull(url.queryParameter("filter"))
    }

    // ---- 3. artist id by exact name: search?types=artists ----

    @Test
    fun `artistSearchUrl mirrors the desktop's proven session-search call`() {
        val url = artistSearchUrl(
            apiBase = "https://api.tidal.com/v1",
            query = "Trevor Something",
            countryCode = "US",
            limit = 5,
        )
        assertEquals("api.tidal.com", url.host)
        // `GET search` — the multi-type endpoint tidalapi's session.search uses
        // (session.py:807). NOT `/search/artists`: nobody has verified such a
        // sibling exists, and the provider's existing track search hits
        // `/search/tracks`, a differently-shaped endpoint.
        assertEquals("/v1/search", url.encodedPath)
        assertEquals("Trevor Something", url.queryParameter("query"))
        // LOWERCASE "artists". tidalapi builds this via convert_type(Artist,
        // "type"), whose Case enum aliases pascal/scream/lower onto ONE member
        // (session.py:187-190), so the `case == Case.scream` branch always fires
        // and always lowercases. Verified by executing it: it emits "artists".
        // This exact string is what the desktop sends daily; "ARTISTS" is not.
        assertEquals("artists", url.queryParameter("types"))
        assertEquals("5", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
    }

    private val artistSearchBody = """
        {
          "artists": {
            "limit": 5, "offset": 0, "totalNumberOfItems": 3,
            "items": [
              { "id": 111, "name": "Trevor Something Does Not Exist", "picture": "aaaa-bbbb-cccc" },
              { "id": 222, "name": "Trevor Something",                "picture": "dddd-eeee-ffff" },
              { "id": 333, "name": "Trevor Something Jr",             "picture": null }
            ]
          },
          "albums":    { "items": [] },
          "tracks":    { "items": [] },
          "videos":    { "items": [] },
          "playlists": { "items": [] }
        }
    """.trimIndent()

    @Test
    fun `artistIdFromSearch takes the EXACT name match, not the top-ranked result`() {
        // The exact match is 2nd; a "best match" heuristic would take 111 and hang
        // a different artist's bio, discography and top tracks on the page.
        assertEquals("222", artistIdFromSearch(artistSearchBody, json, "Trevor Something"))
    }

    @Test
    fun `artistIdFromSearch matches case-insensitively`() {
        assertEquals("222", artistIdFromSearch(artistSearchBody, json, "trevor something"))
        assertEquals("222", artistIdFromSearch(artistSearchBody, json, "TREVOR SOMETHING"))
    }

    @Test
    fun `artistIdFromSearch returns NULL on a near miss - it must never fall back to artists zero`() {
        // THE POINT OF THIS FILE. The desktop does `best = artists[0]` here
        // (providers/tidal.py:874-876) and thereby attaches the WRONG artist.
        // We resolve null and the page renders exactly as it does today.
        // If this assertion ever flips to expecting "111", the bug is back.
        assertNull(artistIdFromSearch(artistSearchBody, json, "Trevor Someth"))
        assertNull(artistIdFromSearch(artistSearchBody, json, "Trevor"))
        assertNull(artistIdFromSearch(artistSearchBody, json, "Com Truise"))
    }

    @Test
    fun `artistIdFromSearch returns null for empty results, a malformed body or an error envelope`() {
        assertNull(artistIdFromSearch("""{ "artists": { "items": [] } }""", json, "Anybody"))
        assertNull(artistIdFromSearch("""{ "status": 401, "userMessage": "unauthorized" }""", json, "Anybody"))
        assertNull(artistIdFromSearch("not json at all", json, "Anybody"))
    }
}
