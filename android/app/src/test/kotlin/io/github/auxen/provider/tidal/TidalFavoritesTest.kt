package io.github.auxen.provider.tidal

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import io.github.auxen.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val tracksPage = """
        {
          "items": [
            { "created": "2023-05-01T12:00:00.000+0000",
              "item": { "id": 12345, "title": "Nightcall", "duration": 258.0,
                        "trackNumber": 1, "volumeNumber": 1, "explicit": false,
                        "audioQuality": "LOSSLESS",
                        "artists": [ { "id": 11, "name": "Kavinsky" } ],
                        "album": { "id": 75413016, "title": "OutRun", "cover": "aa-bb-cc" } } },
            { "created": null,
              "item": { "id": 222, "title": "No Album", "artists": [], "album": null } }
          ],
          "totalNumberOfItems": 2
        }
    """.trimIndent()

    @Test
    fun `favoriteTracksFrom projects playable tracks with art, artist, quality and addedAt`() {
        val tracks = favoriteTracksFrom(tracksPage, json)
        assertEquals(2, tracks.size)
        val first = tracks[0].track
        assertEquals("12345", first.sourceId)
        assertEquals("Nightcall", first.title)
        assertEquals("Kavinsky", first.artist) // artists[0].name — there is NO singular `artist` key
        assertEquals("OutRun", first.album)
        assertEquals(Source.TIDAL, first.source)
        assertEquals("FLAC", first.format) // LOSSLESS -> FLAC via toTrack's quality map
        assertEquals("https://resources.tidal.com/images/aa/bb/cc/640x640.jpg", first.albumArtUrl)
        assertEquals(1682942400000L, tracks[0].addedAtMillis) // 2023-05-01T12:00:00Z
    }

    @Test
    fun `favoriteTracksFrom tolerates a missing album, empty artists and null created`() {
        val second = favoriteTracksFrom(tracksPage, json)[1]
        assertEquals("222", second.track.sourceId)
        assertEquals("", second.track.artist) // empty artists -> artists.firstOrNull()?.name.orEmpty()
        assertNull(second.track.album) // null album -> album?.title
        assertNull(second.track.albumArtUrl) // null album -> tidalV1CoverUrl(null)
        assertNull(second.addedAtMillis) // null created
    }

    @Test
    fun `favoriteTracksFrom returns empty list on garbage or an error envelope`() {
        assertEquals(emptyList<TidalFavoriteTrack>(), favoriteTracksFrom("not json", json))
        assertEquals(emptyList<TidalFavoriteTrack>(), favoriteTracksFrom("{\"status\":404}", json))
    }

    @Test
    fun `favorite tracks read URL is paged and country-scoped, most-recent-first`() {
        val url = favoriteTracksUrl("https://api.tidal.com/v1", "42", "US", limit = 50, offset = 100)
        assertEquals("/v1/users/42/favorites/tracks", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("100", url.queryParameter("offset"))
        assertEquals("DATE", url.queryParameter("order"))
        assertEquals("DESC", url.queryParameter("orderDirection"))
        assertEquals("US", url.queryParameter("countryCode"))
    }

    @Test
    fun `favorite track write URL and delete URL target the tracks relationship`() {
        val add = favoriteTracksWriteUrl("https://api.tidal.com/v1", "42", "US")
        assertEquals("/v1/users/42/favorites/tracks", add.encodedPath)
        assertEquals("US", add.queryParameter("countryCode"))

        val del = favoriteTrackDeleteUrl("https://api.tidal.com/v1", "42", "12345", "US")
        assertEquals("/v1/users/42/favorites/tracks/12345", del.encodedPath)
        assertEquals("US", del.queryParameter("countryCode"))
    }

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

    @Test
    fun `strict page parsers decode a genuine page (incl a genuine EMPTY) to a trusted non-null list`() {
        // A real page decodes to the same projection the lenient parser produces...
        assertEquals(2, favoriteAlbumsPageOrNull(albumsPage, json)!!.items.size)
        assertEquals(2, favoriteArtistsPageOrNull(artistsPage, json)!!.items.size)
        assertEquals(2, favoriteTracksPageOrNull(tracksPage, json)!!.items.size)
        // ...and a GENUINE empty page (items:[] WITH totalNumberOfItems) is a
        // TRUSTED empty [] — non-null — so the to-zero mirror still works.
        val emptyPage = """{"items":[],"totalNumberOfItems":0}"""
        assertEquals(emptyList<TidalFavoriteAlbum>(), favoriteAlbumsPageOrNull(emptyPage, json)!!.items)
        assertEquals(emptyList<TidalFavoriteArtist>(), favoriteArtistsPageOrNull(emptyPage, json)!!.items)
        assertEquals(emptyList<TidalFavoriteTrack>(), favoriteTracksPageOrNull(emptyPage, json)!!.items)
    }

    @Test
    fun `strict page parsers return NULL on a non-favorites 200 body (captive portal, error envelope, drift)`() {
        // THE captive-portal wipe guard. Each of these is an HTTP-200 body that is
        // NOT a favorites page; a null result makes the read return null so the
        // mirror deletes nothing (vs the lenient parser's trusted []).
        val html = "<html><body>Sign in to WiFi</body></html>"          // captive portal
        val errorEnvelope = """{"status":404,"userMessage":"not found"}""" // no totalNumberOfItems
        val drift = """{"tracks":[{"item":{"id":1}}],"totalCount":1}"""     // items dropped/renamed, no totalNumberOfItems
        for (bad in listOf(html, errorEnvelope, drift, "not json")) {
            assertNull(favoriteAlbumsPageOrNull(bad, json))
            assertNull(favoriteArtistsPageOrNull(bad, json))
            assertNull(favoriteTracksPageOrNull(bad, json))
        }
    }

    @Test
    fun `strict page parsers surface the envelope's own totalCount alongside the projected items`() {
        assertEquals(2, favoriteAlbumsPageOrNull(albumsPage, json)!!.totalCount)
        assertEquals(2, favoriteArtistsPageOrNull(artistsPage, json)!!.totalCount)
        assertEquals(2, favoriteTracksPageOrNull(tracksPage, json)!!.totalCount)
    }

    @Test
    fun `M1 cross-check- a well-formed page whose items all fail to project is untrusted, a genuine empty is trusted`() {
        // A well-formed envelope whose items ALL fail to project (every wrapper's
        // `item` is null -- a schema drift) still decodes -- the strict parser
        // trusts the ENVELOPE, not the per-item shape -- to an EMPTY items list
        // carrying the server's own non-zero total.
        val driftedNonEmptyTotal =
            """{"items":[{"created":"2023-01-01T00:00:00Z","item":null}],"totalNumberOfItems":5}"""
        val drifted = favoriteAlbumsPageOrNull(driftedNonEmptyTotal, json)!!
        assertTrue(drifted.items.isEmpty())
        assertEquals(5, drifted.totalCount)
        // The read-level M1 guard turns that mismatch into null (untrusted) --
        // this is what stops a schema-drifted "successful" fetch from mirroring
        // as a mass-delete.
        assertNull(drifted.items.orNullIfDriftedEmpty(drifted.totalCount))

        // A GENUINE empty collection reports totalNumberOfItems == 0, so the
        // guard is false-null-safe: the trusted empty list flows through
        // unchanged, and the to-zero mirror still works.
        val genuineEmpty = favoriteAlbumsPageOrNull("""{"items":[],"totalNumberOfItems":0}""", json)!!
        assertEquals(emptyList<TidalFavoriteAlbum>(), genuineEmpty.items.orNullIfDriftedEmpty(genuineEmpty.totalCount))
    }

    @Test
    fun `C1- strict page parsers report the RAW wrapper count separately from the (possibly shorter) projected items`() {
        // A page with one UNAVAILABLE favorite (its wrapper's `item` is null --
        // the favorite still exists, Tidal just can't resolve it right now) still
        // carries a FULL raw wrapper count. Pagination MUST key off rawCount, not
        // items.size, or an unavailable favorite on a page boundary makes a full
        // page look short and truncates the fetch early (C1: a partial fetch then
        // reads as "everything after this point is gone" and gets mass-deleted).
        val pageWithOneUnavailable = """
            {"items":[
              { "created": "2023-01-01T00:00:00Z",
                "item": { "id": 1, "title": "A", "artists": [] } },
              { "created": "2023-01-01T00:00:00Z", "item": null }
            ],"totalNumberOfItems":10}
        """.trimIndent()
        val page = favoriteAlbumsPageOrNull(pageWithOneUnavailable, json)!!
        assertEquals(1, page.items.size) // one wrapper failed to project
        assertEquals(2, page.rawCount) // but BOTH wrappers were on the wire
    }

    // ---- paginateFavorites: the read loop that drives every favorites pull.
    // Pure (network injected as `fetchPage`). The internal-v1 favorites endpoints
    // return SHORT NON-FINAL pages (they drop un-returnable items but count them
    // in the total), so pagination ends on an EMPTY page, not a short one, and
    // completeness (the DELETE gate) is "reached the empty page", not
    // "rawSeen == total". These pin that model + the data-loss guards.

    @Test
    fun `short non-final pages page through to the empty page (regression- Tidal drops items mid-collection)`() = runBlocking {
        // THE bug: the favorites/tracks endpoint returned [47, 47, 21, 0] for a
        // 124-total collection (probed live). Breaking on a short page (rawCount <
        // pageSize) truncated the read to the first 47, which the delete gate then
        // read as incomplete -> every Tidal-side removal was silently suppressed.
        // A short page must NOT end the read; only the empty page does.
        val pages = mapOf(
            0 to FavoritesPage((1..47).toList(), totalCount = 124, rawCount = 47),
            50 to FavoritesPage((48..94).toList(), totalCount = 124, rawCount = 47),
            100 to FavoritesPage((95..115).toList(), totalCount = 124, rawCount = 21),
            150 to FavoritesPage(emptyList(), totalCount = 124, rawCount = 0),
        )
        val fetch = paginateFavorites(pageSize = 50, maxItems = 5000) { offset -> pages.getValue(offset) }
        assertEquals(115, fetch.items?.size) // all three partial pages, NOT truncated at 47
        assertTrue(fetch.fullyProjected)     // empty page reached, all projected -> deletes TRUSTED
    }

    @Test
    fun `a null-item wrapper still pages on but suppresses deletes (C1)`() = runBlocking {
        // A wrapper whose item is null (unavailable, id unrecoverable) drops in
        // projection: rawCount(50) > items(49). The read still pages to the empty
        // page, but the lossless check (rawSeen == items) fails, so deletes are
        // suppressed this cycle rather than risk deleting the unavailable favorite.
        val pages = mapOf(
            0 to FavoritesPage((1..49).toList(), totalCount = 60, rawCount = 50),
            50 to FavoritesPage((50..58).toList(), totalCount = 60, rawCount = 9),
            100 to FavoritesPage(emptyList(), totalCount = 60, rawCount = 0),
        )
        val fetch = paginateFavorites(pageSize = 50, maxItems = 5000) { offset -> pages.getValue(offset) }
        assertEquals(58, fetch.items?.size)
        assertFalse(fetch.fullyProjected)   // rawSeen 59 != items 58 -> deletes suppressed
    }

    @Test
    fun `a complete read that pages to an empty page is trusted for deletes`() = runBlocking {
        val pages = mapOf(
            0 to FavoritesPage((1..50).toList(), totalCount = 80, rawCount = 50),
            50 to FavoritesPage((51..80).toList(), totalCount = 80, rawCount = 30),
            100 to FavoritesPage(emptyList(), totalCount = 80, rawCount = 0),
        )
        val fetch = paginateFavorites(pageSize = 50, maxItems = 5000) { offset -> pages.getValue(offset) }
        assertEquals(80, fetch.items?.size)
        assertTrue(fetch.fullyProjected)
    }

    @Test
    fun `hitting the max cap without an empty page suppresses deletes`() = runBlocking {
        // Full pages all the way to the cap, never an empty page -> incomplete, so
        // deletes are suppressed rather than wiping the un-fetched tail of a
        // >maxItems collection. Adds still flow.
        val fetch = paginateFavorites(pageSize = 50, maxItems = 100) { offset ->
            FavoritesPage((offset + 1..offset + 50).toList(), totalCount = 300, rawCount = 50)
        }
        assertEquals(100, fetch.items?.size)
        assertFalse(fetch.fullyProjected)
    }

    @Test
    fun `an understated total still pages to the empty page, no early stop`() = runBlocking {
        // The server understates the total to 40, but really returns ~90 items.
        // Because we page to the EMPTY page (not to offset >= total), we collect
        // them all and only then trust -- an understated total cannot truncate.
        val pages = mapOf(
            0 to FavoritesPage((1..50).toList(), totalCount = 40, rawCount = 50),
            50 to FavoritesPage((51..90).toList(), totalCount = 40, rawCount = 40),
            100 to FavoritesPage(emptyList(), totalCount = 40, rawCount = 0),
        )
        val fetch = paginateFavorites(pageSize = 50, maxItems = 5000) { offset -> pages.getValue(offset) }
        assertEquals(90, fetch.items?.size)
        assertTrue(fetch.fullyProjected)
    }

    @Test
    fun `a genuine empty collection is trusted (mirror may delete all)`() = runBlocking {
        // total 0, page 0 empty: a real to-zero, distinct from a failed fetch.
        val fetch = paginateFavorites<Int>(pageSize = 50, maxItems = 5000) { _ ->
            FavoritesPage(emptyList(), totalCount = 0, rawCount = 0)
        }
        assertEquals(0, fetch.items?.size)
        assertTrue(fetch.fullyProjected)
    }

    @Test
    fun `M1- a drifted empty (page 0 empty but total says items exist) is untrusted`() = runBlocking {
        val fetch = paginateFavorites<Int>(pageSize = 50, maxItems = 5000) { _ ->
            FavoritesPage(emptyList(), totalCount = 100, rawCount = 0)
        }
        assertNull(fetch.items)           // empty but total 100 -> null (no reconcile)
        assertFalse(fetch.fullyProjected)
    }

    @Test
    fun `a first-page fetch failure yields an untrusted null read`() = runBlocking {
        val fetch = paginateFavorites<Int>(pageSize = 50, maxItems = 5000) { _ -> null }
        assertNull(fetch.items)
        assertFalse(fetch.fullyProjected)
    }

    @Test
    fun `a later-page fetch failure aborts the whole read to untrusted`() = runBlocking {
        // A mid-pagination failure must abort to null (no reconcile), never
        // silently truncate to what was gathered and trust it for deletes.
        val fetch = paginateFavorites(pageSize = 50, maxItems = 5000) { offset ->
            if (offset == 0) FavoritesPage((1..50).toList(), totalCount = 200, rawCount = 50) else null
        }
        assertNull(fetch.items)
        assertFalse(fetch.fullyProjected)
    }

    @Test
    fun `M1- a negative total with empty items is untrusted, not a trusted empty`() = runBlocking {
        // A crafted/garbled body could report a negative total; an empty items
        // list with total != 0 must be untrusted (null), never a genuine-empty
        // that the mirror would act on by deleting every favorite.
        val fetch = paginateFavorites<Int>(pageSize = 50, maxItems = 5000) { _ ->
            FavoritesPage(emptyList(), totalCount = -1, rawCount = 0)
        }
        assertNull(fetch.items)
        assertFalse(fetch.fullyProjected)
    }
}
