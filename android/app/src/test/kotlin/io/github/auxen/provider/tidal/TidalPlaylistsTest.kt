package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure internal-v1 playlist parsing + request-URL shapes. No live HTTP — the
 * TidalProvider suspend methods that call these are untested (internal-v1
 * precedent); only the pure projection + URL builders are.
 *
 * Fixtures use the probed wrappers: LIST `{ items:[{ created, item:{ uuid,
 * title, numberOfTracks } }], totalNumberOfItems }`; ITEMS `{ items:[{ type,
 * item:{<v1 track>} }], totalNumberOfItems }` where a `type != "track"` item
 * (a video) must be dropped.
 */
class TidalPlaylistsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val playlistsPage = """
        {
          "items": [
            { "created": "2023-05-01T12:00:00.000+0000",
              "item": { "uuid": "30631867-b9d8-4bb1-a525-44019daf4b5b",
                        "title": "2024", "numberOfTracks": 100,
                        "image": "aa-bb-cc", "squareImage": "dd-ee-ff" } },
            { "created": null,
              "item": { "uuid": "", "title": "Unusable (blank uuid)" } }
          ],
          "totalNumberOfItems": 2
        }
    """.trimIndent()

    private val itemsPage = """
        {
          "items": [
            { "type": "track",
              "item": { "id": 12345, "title": "Nightcall", "duration": 258.0,
                        "trackNumber": 1, "volumeNumber": 1, "explicit": false,
                        "audioQuality": "LOSSLESS",
                        "artists": [ { "id": 11, "name": "Kavinsky" } ],
                        "album": { "id": 75413016, "title": "OutRun", "cover": "aa-bb-cc" } } },
            { "type": "video",
              "item": { "id": 999, "title": "Some Music Video" } },
            { "type": "track",
              "item": { "id": 222, "title": "No Album", "artists": [], "album": null } }
          ],
          "totalNumberOfItems": 3
        }
    """.trimIndent()

    @Test
    fun `favoritePlaylistsFrom projects uuid, title and addedAt, dropping blank-uuid rows`() {
        val playlists = favoritePlaylistsFrom(playlistsPage, json)
        assertEquals(1, playlists.size) // the blank-uuid row is dropped
        assertEquals("30631867-b9d8-4bb1-a525-44019daf4b5b", playlists[0].uuid)
        assertEquals("2024", playlists[0].title)
        assertEquals(1682942400000L, playlists[0].addedAtMillis) // 2023-05-01T12:00:00Z
    }

    @Test
    fun `favoritePlaylistsFrom returns empty list on garbage or an error envelope`() {
        assertEquals(emptyList<TidalPlaylist>(), favoritePlaylistsFrom("not json", json))
        assertEquals(emptyList<TidalPlaylist>(), favoritePlaylistsFrom("{\"status\":404}", json))
    }

    @Test
    fun `playlistTracksFrom keeps track items, drops videos, and reports the raw item count`() {
        val page = playlistTracksFrom(itemsPage, json)
        assertEquals(3, page.rawItemCount) // RAW items, before filtering — drives pagination
        assertEquals(2, page.tracks.size)  // the video item is dropped
        val first = page.tracks[0]
        assertEquals("12345", first.sourceId)
        assertEquals("Nightcall", first.title)
        assertEquals("Kavinsky", first.artist) // artists[0].name — there is NO singular `artist` key
        assertEquals("OutRun", first.album)
        assertEquals(Source.TIDAL, first.source)
        assertEquals("FLAC", first.format) // LOSSLESS -> FLAC via toTrack's quality map
        assertEquals("https://resources.tidal.com/images/aa/bb/cc/640x640.jpg", first.albumArtUrl)
    }

    @Test
    fun `playlistTracksFrom tolerates a missing album and empty artists`() {
        val second = playlistTracksFrom(itemsPage, json).tracks[1]
        assertEquals("222", second.sourceId)
        assertEquals("", second.artist) // empty artists -> artists.firstOrNull()?.name.orEmpty()
        assertNull(second.album) // null album -> album?.title
        assertNull(second.albumArtUrl) // null album -> tidalV1CoverUrl(null)
    }

    @Test
    fun `playlistTracksFrom returns an empty page on garbage`() {
        val page = playlistTracksFrom("not json", json)
        assertTrue(page.tracks.isEmpty())
        assertEquals(0, page.rawItemCount)
    }

    @Test
    fun `favorite playlists read URL is paged and country-scoped, most-recent-first`() {
        val url = favoritePlaylistsUrl("https://api.tidal.com/v1", "42", "US", limit = 50, offset = 100)
        assertEquals("/v1/users/42/favorites/playlists", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("100", url.queryParameter("offset"))
        assertEquals("DATE", url.queryParameter("order"))
        assertEquals("DESC", url.queryParameter("orderDirection"))
        assertEquals("US", url.queryParameter("countryCode"))
    }

    @Test
    fun `playlist items URL targets the playlist and is paged and country-scoped`() {
        val url = playlistItemsUrl(
            "https://api.tidal.com/v1", "30631867-b9d8-4bb1-a525-44019daf4b5b", "US", limit = 50, offset = 50,
        )
        assertEquals("/v1/playlists/30631867-b9d8-4bb1-a525-44019daf4b5b/items", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("50", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
    }
}
