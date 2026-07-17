package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalArtistDetailTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val artistDoc = """
        {
          "data": {
            "id": "3456", "type": "artists",
            "attributes": { "name": "Prompto", "popularity": 0.7 },
            "relationships": {
              "profileArt":     { "data": { "id": "art1", "type": "artworks" } },
              "biography":      { "data": { "id": "b1", "type": "artistBiographies" } },
              "similarArtists": { "data": [ { "id": "200", "type": "artists" }, { "id": "201", "type": "artists" } ] }
            }
          },
          "included": [
            { "id": "art1", "type": "artworks", "attributes": {
                "files": [
                  { "href": "https://img/80.jpg",  "meta": { "width": 80,  "height": 80 } },
                  { "href": "https://img/640.jpg", "meta": { "width": 640, "height": 640 } }
                ] } },
            { "id": "b1",  "type": "artistBiographies", "attributes": { "text": "A biography.", "source": "TIDAL" } },
            { "id": "200", "type": "artists", "attributes": { "name": "Trevor Something" } },
            { "id": "201", "type": "artists", "attributes": { "name": "Com Truise" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `artistDetailFrom maps name, biography and best-fit profile art`() {
        val d = artistDetailFrom(json.decodeFromString(artistDoc), json, artworkTargetPx = 640)!!
        assertEquals("3456", d.id)
        assertEquals("Prompto", d.name)
        assertEquals("A biography.", d.biography)
        assertEquals("https://img/640.jpg", d.artworkUrl)
    }

    @Test
    fun `artistDetailFrom maps similar artists`() {
        val d = artistDetailFrom(json.decodeFromString(artistDoc), json, artworkTargetPx = 640)!!
        assertEquals(listOf("Trevor Something", "Com Truise"), d.similarArtists.map { it.name })
        assertEquals(listOf("200", "201"), d.similarArtists.map { it.id })
        // similarArtists' own profileArt is NOT flatly include-able from an artist doc.
        assertTrue(d.similarArtists.all { it.artworkUrl == null })
    }

    @Test
    fun `artistDetailFrom dedups similarArtists by id, keeping the first and the order`() {
        // NOT hypothetical: this compound-document mechanism demonstrably repeats
        // a linkage entry — the on-device probe of artist 8405 returned
        // `Fixxxer (Remastered)` TWICE through the very same `related()` path
        // (see dedupRemoteAlbums' KDoc). `related()` resolves linkage 1:1 with no
        // dedup, so a repeated entry yields duplicate ids. Rendering them is a
        // visible bug, and keying a LazyRow on link.id would make it a hard
        // "key was already used" crash.
        val doc = """
            { "data": { "id": "8405", "type": "artists",
                "attributes": { "name": "Metallica" },
                "relationships": { "similarArtists": { "data": [
                    { "id": "200", "type": "artists" },
                    { "id": "200", "type": "artists" },
                    { "id": "201", "type": "artists" }
                ] } } },
              "included": [
                { "id": "200", "type": "artists", "attributes": { "name": "Megadeth" } },
                { "id": "201", "type": "artists", "attributes": { "name": "Slayer" } }
              ] }
        """.trimIndent()
        val similar = artistDetailFrom(json.decodeFromString(doc), json, 640)!!.similarArtists
        assertEquals(listOf("200", "201"), similar.map { it.id })
        assertEquals(listOf("Megadeth", "Slayer"), similar.map { it.name })
    }

    @Test
    fun `artistDetailFrom tolerates an artist with no relationships`() {
        val doc = """{ "data": { "id": "5", "type": "artists", "attributes": { "name": "Solo" } }, "included": [] }"""
        val d = artistDetailFrom(json.decodeFromString(doc), json, artworkTargetPx = 640)!!
        assertEquals("Solo", d.name)
        assertNull(d.biography)
        assertNull(d.artworkUrl)
        assertTrue(d.similarArtists.isEmpty())
    }

    @Test
    fun `artistDetailFrom returns null when there is no primary artist`() {
        assertNull(artistDetailFrom(json.decodeFromString("""{ "data": [], "included": [] }"""), json, 640))
    }

    // ---- the biography is NOT official-v2's to give (on-device 2026-07-16) ----
    //
    // The two `biographyTextFrom` tests that lived here were deleted with the
    // function: the `/artists/{id}/relationships/biography` fallback answered
    // empty for Metallica AND for Tommy Richman, so official v2 has no bio at our
    // tier. The bio is now internal-v1 — its tests are `artistBioFrom` /
    // `formatTidalBio` in TidalInternalArtistTest.

    @Test
    fun `artistDetailFrom leaves biography null when the resource is linked but not included`() {
        // EXACTLY what the real API returned for artist 8405: the relationship
        // linkage is there, the artistBiographies resource is not in `included`.
        // The projection must NOT invent one. In production this null is now the
        // ONLY outcome — ARTIST_DETAIL_INCLUDE no longer asks for the bio at all
        // — and the ViewModel composes the internal-v1 bio in on top.
        val doc = """
            { "data": { "id": "8405", "type": "artists",
                "attributes": { "name": "Metallica" },
                "relationships": { "biography": { "data": { "id": "b9", "type": "artistBiographies" } } } },
              "included": [] }
        """.trimIndent()
        assertNull(artistDetailFrom(json.decodeFromString(doc), json, 640)!!.biography)
    }

    @Test
    fun `ARTIST_DETAIL_INCLUDE does not ask official v2 for a biography it has not got`() {
        // Pins the deletion. Re-adding "biography" here buys a bigger response and
        // a field that is always null (proven for Metallica and Tommy Richman).
        assertEquals(
            listOf("similarArtists", "profileArt"),
            TidalMetadataRepository.ARTIST_DETAIL_INCLUDE,
        )
    }

}
