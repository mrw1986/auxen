package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure internal-v1 favorites parsing + request-URL shapes. No live HTTP — the
 * TidalProvider suspend methods that call these are untested (internal-v1
 * precedent), only the pure projection + URL builders are.
 *
 * Fixtures use the real internal-v1 favorites wrapper shape probed live
 * (`.superpowers/sdd/slice2-favorites-schema.md`): `{ items:[{ created, item }],
 * totalNumberOfItems }`, album item `{id,title,cover,numberOfTracks,releaseDate,
 * explicit,artists[]}`, artist item `{id,name,picture}`.
 */
class TidalFavoritesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val albumsPage = """
        {
          "items": [
            { "created": "2023-05-01T12:00:00.000+0000",
              "item": { "id": 75413016, "title": "OutRun", "cover": "aa-bb-cc",
                        "numberOfTracks": 13, "releaseDate": "2013-02-25", "explicit": false,
                        "artists": [ { "id": 11, "name": "Kavinsky" } ] } },
            { "created": "2023-06-01T12:00:00Z",
              "item": { "id": 99, "title": "No Cover", "cover": null,
                        "artists": [] } }
          ],
          "totalNumberOfItems": 2
        }
    """.trimIndent()

    private val artistsPage = """
        {
          "items": [
            { "created": "2023-05-01T12:00:00Z",
              "item": { "id": 7, "name": "Prompto", "picture": "dd-ee-ff" } },
            { "created": null,
              "item": { "id": 8, "name": "No Picture", "picture": null } }
          ],
          "totalNumberOfItems": 2
        }
    """.trimIndent()

    @Test
    fun `favoriteAlbumsFrom projects id title artist cover and addedAt`() {
        val albums = favoriteAlbumsFrom(albumsPage, json)
        assertEquals(2, albums.size)
        assertEquals("75413016", albums[0].id)
        assertEquals("OutRun", albums[0].title)
        assertEquals("Kavinsky", albums[0].artistName)
        assertEquals("https://resources.tidal.com/images/aa/bb/cc/640x640.jpg", albums[0].coverUrl)
        assertEquals(1682942400000L, albums[0].addedAtMillis) // 2023-05-01T12:00:00Z
    }

    @Test
    fun `favoriteAlbumsFrom tolerates a missing cover and empty artists`() {
        val second = favoriteAlbumsFrom(albumsPage, json)[1]
        assertEquals("99", second.id)
        assertNull(second.coverUrl)
        assertNull(second.artistName)
    }

    @Test
    fun `favoriteArtistsFrom projects id name picture and addedAt`() {
        val artists = favoriteArtistsFrom(artistsPage, json)
        assertEquals(2, artists.size)
        assertEquals("7", artists[0].id)
        assertEquals("Prompto", artists[0].name)
        assertEquals("https://resources.tidal.com/images/dd/ee/ff/480x480.jpg", artists[0].pictureUrl)
        assertNull(artists[1].pictureUrl)
        assertNull(artists[1].addedAtMillis)
    }

    @Test
    fun `favorite parsers return empty list on garbage or an error envelope`() {
        assertEquals(emptyList<TidalFavoriteAlbum>(), favoriteAlbumsFrom("{\"status\":404}", json))
        assertEquals(emptyList<TidalFavoriteArtist>(), favoriteArtistsFrom("not json", json))
    }

    @Test
    fun `parseIso8601Millis handles Z, colon and bare-numeric offsets, null on garbage`() {
        assertEquals(0L, parseIso8601Millis("1970-01-01T00:00:00Z"))
        assertEquals(0L, parseIso8601Millis("1970-01-01T01:00:00+01:00"))
        // The form the internal-v1 favorites endpoint actually returns (probed
        // live, e.g. 2026-07-03T12:17:29.572+0000): a bare numeric offset with NO
        // colon. Regression guard for the parser fallback that the first albums
        // fixture also depends on.
        assertEquals(1682942400000L, parseIso8601Millis("2023-05-01T12:00:00.000+0000"))
        assertNull(parseIso8601Millis("not-a-date"))
        assertNull(parseIso8601Millis(null))
    }

    @Test
    fun `read URL is paged and country-scoped, ordered most-recent-first`() {
        val url = favoriteAlbumsUrl("https://api.tidal.com/v1", "42", "US", limit = 50, offset = 100)
        assertEquals("/v1/users/42/favorites/albums", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("100", url.queryParameter("offset"))
        assertEquals("DATE", url.queryParameter("order"))
        assertEquals("DESC", url.queryParameter("orderDirection"))
        assertEquals("US", url.queryParameter("countryCode"))
    }

    @Test
    fun `write URL and delete URL target the collection relationship`() {
        val add = favoriteArtistsWriteUrl("https://api.tidal.com/v1", "42", "US")
        assertEquals("/v1/users/42/favorites/artists", add.encodedPath)
        assertEquals("US", add.queryParameter("countryCode"))

        val del = favoriteAlbumDeleteUrl("https://api.tidal.com/v1", "42", "99", "US")
        assertEquals("/v1/users/42/favorites/albums/99", del.encodedPath)
        assertEquals("US", del.queryParameter("countryCode"))
    }
}
