package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalArtistPageTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Trimmed from the live probe of artist 8405 (.superpowers/sdd/pages-artist-schema.md).
     * Deliberately includes:
     *  - an UNKNOWN module type (`SOCIAL`) -> must be ignored, never crash
     *  - a module with an EMPTY pagedList (`ALBUM_LIST` 'Compilations') -> absent section
     *  - a MALFORMED item (the id-less album in 'Live albums') -> dropped, sibling survives
     *  - `artists` plural with NO singular `artist`, and NO `type` on albums (Finding 1)
     */
    private val pageBody = """
        {
          "selfLink": null, "id": "artist", "title": "Metallica",
          "rows": [
            { "modules": [ {
                "type": "ARTIST_HEADER", "title": "", "width": 12,
                "artist": { "id": 8405, "name": "Metallica", "picture": "0a85112a-d1ec-49be-a618-8df9081e9e0c" },
                "bio": { "source": "TiVo", "text": "Metallica were easily the best.<br><br>See [wimpLink artistId=\"721\"]System Of A Down[/wimpLink]." },
                "artistMix": { "id": "0008a92f59f8b301581f70ca9ee481" },
                "mixes": { "ARTIST_MIX": "0008a92f59f8b301581f70ca9ee481" }
              } ] },
            { "modules": [ {
                "type": "TRACK_LIST", "title": "Top Tracks",
                "pagedList": { "limit": 10, "offset": 0, "totalNumberOfItems": 300, "items": [
                  { "id": 166790188, "title": "Enter Sandman", "duration": 332, "explicit": false,
                    "audioQuality": "LOSSLESS", "trackNumber": 1, "volumeNumber": 1,
                    "artists": [ { "id": 8405, "name": "Metallica", "type": "MAIN", "picture": "0a85112a-d1ec-49be-a618-8df9081e9e0c" } ],
                    "album": { "id": 166790187, "title": "Metallica", "cover": "d0747f21-26ff-447c-8d04-0d8f3635e205" } },
                  { "id": 166790200, "title": "One", "duration": 447, "explicit": true,
                    "audioQuality": "HI_RES_LOSSLESS",
                    "artists": [ { "id": 8405, "name": "Metallica" } ],
                    "album": { "id": 1, "title": "…And Justice for All", "cover": null } }
                ] }
              } ] },
            { "modules": [ {
                "type": "ALBUM_LIST", "title": "Albums",
                "pagedList": { "totalNumberOfItems": 16, "items": [
                  { "id": 288984589, "title": "72 Seasons", "cover": "7e907ce5-5c38-4738-8a21-95a8d362ddb4",
                    "releaseDate": "2023-04-14", "explicit": false, "numberOfTracks": 12,
                    "artists": [ { "id": 8405, "name": "Metallica" } ] }
                ] }
              } ] },
            { "modules": [ {
                "type": "ALBUM_LIST", "title": "EP & Singles",
                "pagedList": { "totalNumberOfItems": 47, "items": [
                  { "id": 531884999, "title": "Fixer (\"Fixxxer\" July '97 Rough Mix)", "cover": "1f5511c5-92a2-4448-bb40-f519210729ee",
                    "releaseDate": "1997-07-01", "explicit": false }
                ] }
              } ] },
            { "modules": [ { "type": "ALBUM_LIST", "title": "Compilations",
                "pagedList": { "totalNumberOfItems": 0, "items": [] } } ] },
            { "modules": [ {
                "type": "ALBUM_LIST", "title": "Live albums",
                "pagedList": { "totalNumberOfItems": 7, "items": [
                  { "title": "A malformed album with no id" },
                  { "id": 186461110, "title": "S&M2 (Live)", "cover": "3eb66f06-3a0d-462c-aa2b-6fa93f95a448",
                    "releaseDate": "2020-08-28", "explicit": false }
                ] }
              } ] },
            { "modules": [ { "type": "SOCIAL", "title": "Social", "somethingWeHaveNeverSeen": { "a": 1 } } ] },
            { "modules": [ {
                "type": "ARTIST_LIST", "title": "Fans Also Like",
                "pagedList": { "totalNumberOfItems": 15, "items": [
                  { "id": 721, "name": "System Of A Down", "picture": "b45f6499-0636-4874-9e41-796be9a8c2b8" },
                  { "id": 14670, "name": "Slayer", "picture": null }
                ] }
              } ] },
            { "modules": [ {
                "type": "ARTIST_LIST", "title": "Influencers",
                "pagedList": { "totalNumberOfItems": 8, "items": [
                  { "id": 15637, "name": "Diamond Head", "picture": "cccccccc-dddd-eeee-ffff-000000000000" }
                ] }
              } ] }
          ]
        }
    """.trimIndent()

    private fun page() = artistPageFrom(pageBody, json, artistId = "8405")!!

    // ---- 1. the request shape ----

    @Test
    fun `artistPageUrl sends only artistId, deviceType, locale and countryCode`() {
        val url = artistPageUrl(apiBase = "https://api.tidal.com/v1", artistId = "8405", countryCode = "US")
        assertEquals("api.tidal.com", url.host)
        assertEquals("/v1/pages/artist", url.encodedPath)
        assertEquals("8405", url.queryParameter("artistId"))
        assertEquals("BROWSER", url.queryParameter("deviceType"))
        assertEquals("en_US", url.queryParameter("locale"))
        assertEquals("US", url.queryParameter("countryCode"))
        // Exactly these four and nothing else — see the `limit` test below.
        assertEquals(setOf("artistId", "deviceType", "locale", "countryCode"), url.queryParameterNames)
    }

    @Test
    fun `artistPageUrl sends NO limit - the endpoint answers 400 when it is present`() {
        // THE POINT OF THIS TEST. tidalapi's request.request auto-injects
        // limit=1000 and pages/artist answers 400 (probed 2026-07-16). Our
        // TidalProvider.get() adds no params of its own, so the ONLY way a limit
        // reaches this endpoint is someone adding it here. Do not.
        val url = artistPageUrl("https://api.tidal.com/v1", "8405", "US")
        assertNull(url.queryParameter("limit"))
        assertNull(url.queryParameter("offset"))
    }

    // ---- 2. the header: the biography official v2 has not got ----

    @Test
    fun `artistPageFrom reads the biography out of ARTIST_HEADER and formats the markup away`() {
        // The headline fix. Official v2 returns null here at our tier (proven
        // on-device for Metallica AND Tommy Richman); ARTIST_HEADER.bio.text
        // carries it. formatTidalBio turns <br> into newlines and unwraps
        // [wimpLink] so no raw tag reaches the screen.
        assertEquals(
            "Metallica were easily the best.\n\nSee System Of A Down.",
            page().biography,
        )
    }

    @Test
    fun `artistPageFrom maps the header id and name`() {
        assertEquals("8405", page().id)
        assertEquals("Metallica", page().name)
    }

    @Test
    fun `artistPageFrom builds the artist picture at 480x480, NOT the album 640`() {
        // Finding 2. tidalapi/artist.py:283-293 -- an artist picture's valid
        // resolutions are 160/320/480/750 and image() RAISES for anything else;
        // 640 is an ALBUM size (tidalapi/album.py:243-256). The desktop builds
        // artist images at 480 (auxen/providers/tidal.py:883, :1017). Using
        // tidalV1CoverUrl (640) here would 404 every photo -- which is exactly
        // the "artist images work for some but not all" bug we are fixing.
        assertEquals(
            "https://resources.tidal.com/images/0a85112a/d1ec/49be/a618/8df9081e9e0c/480x480.jpg",
            page().artworkUrl,
        )
    }

    @Test
    fun `tidalV1ArtistPictureUrl tolerates null and blank`() {
        assertNull(tidalV1ArtistPictureUrl(null))
        assertNull(tidalV1ArtistPictureUrl(""))
        assertNull(tidalV1ArtistPictureUrl("   "))
    }

    // ---- 3. Fans Also Like -- WITH the photos the user asked for ----

    @Test
    fun `artistPageFrom carries a picture on every ARTIST_LIST item that has one`() {
        // The user's ask: "the tidal app itself shows the pics. can we make sure
        // we have the same info available?" Slice 1's similar artists were null
        // BY CONSTRUCTION -- a similar artist's profileArt is not flatly
        // include-able from an official artist document. Here it is inline.
        val fans = page().artistSections.first { it.title == "Fans Also Like" }
        assertEquals(listOf("System Of A Down", "Slayer"), fans.artists.map { it.name })
        assertEquals(listOf("721", "14670"), fans.artists.map { it.id })
        assertEquals(
            "https://resources.tidal.com/images/b45f6499/0636/4874/9e41/796be9a8c2b8/480x480.jpg",
            fans.artists[0].artworkUrl,
        )
        assertNull(fans.artists[1].artworkUrl) // picture: null -> the Person glyph
    }

    @Test
    fun `artistPageFrom keeps EVERY ARTIST_LIST under its own title, not just Fans Also Like`() {
        // Artist 3634161 has a SECOND ARTIST_LIST titled 'Influencers'. Selecting
        // on type (never on title) means it ships for free. A title-matching
        // design would have silently dropped it.
        assertEquals(listOf("Fans Also Like", "Influencers"), page().artistSections.map { it.title })
        assertEquals(listOf("Diamond Head"), page().artistSections[1].artists.map { it.name })
    }

    // ---- 4. the categorised discography -- the whole point ----

    @Test
    fun `artistPageFrom keeps Tidal's own album categories, titles and page order`() {
        // artists/{id}/albums returns 43 albums FLATTENED, which is why the
        // device showed 20 Fixxxer rough mixes as a discography. pages/artist
        // already did the curation. 'Compilations' is absent because its
        // pagedList is empty -- an empty carousel under a lonely header is worse
        // than no section.
        assertEquals(listOf("Albums", "EP & Singles", "Live albums"), page().albumSections.map { it.title })
        assertEquals(listOf("72 Seasons"), page().albumSections[0].albums.map { it.title })
        assertEquals(listOf("Fixer (\"Fixxxer\" July '97 Rough Mix)"), page().albumSections[1].albums.map { it.title })
        assertEquals(listOf("S&M2 (Live)"), page().albumSections[2].albums.map { it.title })
    }

    @Test
    fun `artistPageFrom maps album cover art at 640 and the release year`() {
        val first = page().albumSections[0].albums.single()
        assertEquals("288984589", first.id)
        assertEquals(2023, first.releaseYear)
        assertEquals(false, first.explicit)
        assertEquals(
            "https://resources.tidal.com/images/7e907ce5/5c38/4738/8a21/95a8d362ddb4/640x640.jpg",
            first.artworkUrl,
        )
    }

    @Test
    fun `artistPageFrom leaves albumType null because ALBUM_LIST items carry no type key`() {
        // Probed: ALBUM_LIST item keys have no `type`. Inert -- nothing on the
        // artist page renders albumType -- and arguably an UPGRADE: a live album
        // and a studio album are BOTH type=="ALBUM", but Tidal files them under
        // 'Live albums' and 'Albums'. The module title carries what type cannot.
        assertTrue(page().albumSections.flatMap { it.albums }.all { it.albumType == null })
    }

    // ---- 5. top tracks -- Finding 1 ----

    @Test
    fun `artistPageFrom reads the track artist from artists plural - there is no singular artist key`() {
        // FINDING 1. pages/artist track items carry `artists` and NO `artist`
        // (probed; tidalapi/media.py:239-245 confirms the singular is optional).
        // TidalProvider's private TrackDto reads the SINGULAR and defaults to
        // name="" -- routing this payload through it would blank every artist
        // name on the page. Read artists[0].
        val top = page().topTracks
        assertEquals(listOf("Enter Sandman", "One"), top.map { it.title })
        assertEquals(listOf("Metallica", "Metallica"), top.map { it.artist })
        assertTrue(top.all { it.isTidal })
        assertEquals(listOf("166790188", "166790200"), top.map { it.sourceId })
    }

    @Test
    fun `artistPageFrom maps track album, art, duration, explicit and numbering`() {
        val sandman = page().topTracks.first()
        assertEquals("Metallica", sandman.album)
        assertEquals(332.0, sandman.durationSeconds!!, 0.001)
        assertEquals(false, sandman.explicit)
        assertEquals(1, sandman.trackNumber)
        assertEquals(1, sandman.discNumber)
        assertEquals(
            "https://resources.tidal.com/images/d0747f21/26ff/447c/8d04/0d8f3635e205/640x640.jpg",
            sandman.albumArtUrl,
        )
        assertEquals(true, page().topTracks[1].explicit)
        assertNull(page().topTracks[1].albumArtUrl) // cover: null
    }

    @Test
    fun `artistPageFrom maps audioQuality so the row's quality badge matches the rest of the app`() {
        // Track.qualityLabel returns "Unknown" when format is null (Track.kt:69-79)
        // and AuxenTrackRow renders QualityBadge for every TIDAL row
        // (AuxenTrackRow.kt:118-121). Skipping this mapping would be a VISIBLE
        // regression against the shipped page.
        val (sandman, one) = page().topTracks
        assertEquals("FLAC", sandman.format)
        assertEquals(16, sandman.bitDepth)
        assertEquals("FLAC", sandman.qualityLabel)
        assertEquals("Hi-Res", one.format)
        assertEquals(24, one.bitDepth)
        assertEquals(96_000, one.sampleRateHz)
        assertEquals("Hi-Res", one.qualityLabel)
    }

    @Test
    fun `tidalV1QualityOf mirrors TidalProvider toTrack exactly`() {
        // The duplication this asserts is deliberate and precedented
        // (tidalV1CoverUrl's KDoc, TidalInternalArtist.kt:138-143). These four
        // rows ARE the sync check: TidalProvider.kt:296-303 is the original, and
        // it is private, nested and has never had a test. If the two ever drift,
        // this fails first.
        assertEquals(TidalV1Quality("Hi-Res", 96_000, 24, 4608), tidalV1QualityOf("HI_RES_LOSSLESS"))
        assertEquals(TidalV1Quality("FLAC", 44_100, 16, 1411), tidalV1QualityOf("LOSSLESS"))
        assertEquals(TidalV1Quality("AAC", 44_100, 16, 320), tidalV1QualityOf("HIGH"))
        assertEquals(TidalV1Quality("AAC", 44_100, null, 96), tidalV1QualityOf("LOW"))
        assertEquals(TidalV1Quality(null, null, null, null), tidalV1QualityOf(null))
        assertEquals(TidalV1Quality(null, null, null, null), tidalV1QualityOf(""))
    }

    // ---- 6. TOTALITY -- the parse must never crash and never lose a section ----

    @Test
    fun `artistPageFrom ignores an unknown module type`() {
        // SOCIAL is in the fixture and is not projected. This endpoint is
        // undocumented and reverse-engineered; Tidal owes us nothing and may add
        // a module type any day. An unknown type must be invisible, never fatal.
        assertNotNull(page())
        assertEquals(3, page().albumSections.size)
        assertEquals(2, page().artistSections.size)
    }

    @Test
    fun `artistPageFrom renders a missing module as an ABSENT section, never an error`() {
        val headerOnly = """
            { "rows": [ { "modules": [ {
                "type": "ARTIST_HEADER", "title": "",
                "artist": { "id": 5, "name": "Solo" } } ] } ] }
        """.trimIndent()
        val p = artistPageFrom(headerOnly, json, artistId = "5")!!
        assertEquals("Solo", p.name)
        assertNull(p.biography)
        assertNull(p.artworkUrl)
        assertTrue(p.topTracks.isEmpty())
        assertTrue(p.albumSections.isEmpty())
        assertTrue(p.artistSections.isEmpty())
    }

    @Test
    fun `artistPageFrom drops a section whose pagedList is empty`() {
        // 'Compilations' in the fixture has items: []. No header, no empty rail.
        assertTrue(page().albumSections.none { it.title == "Compilations" })
    }

    @Test
    fun `artistPageFrom drops ONE malformed item and keeps its siblings`() {
        // 'Live albums' in the fixture leads with an id-less album. Items are
        // decoded one at a time under runCatching precisely so a single bad row
        // cannot cost the user a whole section.
        val live = page().albumSections.first { it.title == "Live albums" }
        assertEquals(listOf("S&M2 (Live)"), live.albums.map { it.title })
    }

    @Test
    fun `artistPageFrom survives an explicit null items and still returns the rest of the page`() {
        // A default only applies when the KEY IS ABSENT; an explicit null in a
        // NON-nullable field throws. The page decodes in ONE pass, so a single
        // `"items": null` in a section we do not even render would otherwise cost
        // the whole page — biography and all. `items`/`modules`/`rows` are
        // nullable for exactly this. One null costs one section, never the page.
        val nullItems = """
            { "rows": [
                { "modules": [ {
                    "type": "ARTIST_HEADER", "title": "",
                    "artist": { "id": 8405, "name": "Metallica" },
                    "bio": { "text": "Still here." } } ] },
                { "modules": [ { "type": "ALBUM_LIST", "title": "Albums", "pagedList": { "items": null } } ] },
                { "modules": null },
                { "modules": [ {
                    "type": "ARTIST_LIST", "title": "Fans Also Like",
                    "pagedList": { "items": [ { "id": 721, "name": "System Of A Down" } ] } } ] }
              ] }
        """.trimIndent()
        val p = artistPageFrom(nullItems, json, artistId = "8405")!!
        assertEquals("Still here.", p.biography)
        assertTrue(p.albumSections.isEmpty())
        assertEquals(listOf("System Of A Down"), p.artistSections.single().artists.map { it.name })
    }

    @Test
    fun `artistPageFrom returns null for an error envelope, malformed JSON or a module-less body`() {
        // Every one of these means the same thing to the caller: no enrichment,
        // the page renders exactly as it does today. Never an error, never a crash.
        assertNull(artistPageFrom("""{ "status": 400, "userMessage": "limit is not allowed" }""", json, "8405"))
        assertNull(artistPageFrom("not json at all", json, "8405"))
        assertNull(artistPageFrom("""<html><body>502 Bad Gateway</body></html>""", json, "8405"))
        assertNull(artistPageFrom("""{ "rows": [] }""", json, "8405"))
        assertNull(artistPageFrom("""{ "rows": [ { "modules": [] } ] }""", json, "8405"))
    }

    @Test
    fun `artistPageFrom falls back to the requested id when the header carries no artist`() {
        val noHeaderArtist = """
            { "rows": [ { "modules": [ {
                "type": "ARTIST_LIST", "title": "Fans Also Like",
                "pagedList": { "items": [ { "id": 721, "name": "System Of A Down" } ] } } ] } ] }
        """.trimIndent()
        val p = artistPageFrom(noHeaderArtist, json, artistId = "8405")!!
        assertEquals("8405", p.id)
        assertEquals("", p.name)
    }

    @Test
    fun `artistPageFrom leaves the biography null when the bio is absent or renders to nothing`() {
        // A bio-less artist must read as "no bio" and hide the section -- never
        // as an error. The desktop swallows the identical case in a bare except
        // ("404s are expected for lesser-known artists", providers/tidal.py:911-916).
        val blank = """
            { "rows": [ { "modules": [ {
                "type": "ARTIST_HEADER", "title": "",
                "artist": { "id": 5, "name": "Solo" },
                "bio": { "source": "TiVo", "text": "<br>  <br>" } } ] } ] }
        """.trimIndent()
        assertNull(artistPageFrom(blank, json, "5")!!.biography)
    }
}
