package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidalMetadataRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val artistDoc = """
        {
          "data": { "id": "100", "type": "artists",
            "attributes": { "name": "Prompto" },
            "relationships": {
              "biography":      { "data": { "id": "b1", "type": "artistBiographies" } },
              "similarArtists": { "data": [ { "id": "200", "type": "artists" }, { "id": "201", "type": "artists" } ] },
              "albums":         { "data": [ { "id": "300", "type": "albums" }, { "id": "301", "type": "albums" } ] }
            } },
          "included": [
            { "id": "b1",  "type": "artistBiographies", "attributes": { "text": "A biography.", "source": "TIDAL" } },
            { "id": "200", "type": "artists", "attributes": { "name": "Trevor Something" } },
            { "id": "201", "type": "artists", "attributes": { "name": "Com Truise" } },
            { "id": "300", "type": "albums",  "attributes": { "title": "Album One", "albumType": "ALBUM" } },
            { "id": "301", "type": "albums",  "attributes": { "title": "Album Two", "albumType": "EP" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `artistOverviewFrom maps name, biography, similar artists and albums`() {
        val o = artistOverviewFrom(json.decodeFromString(artistDoc), json)!!
        assertEquals("100", o.id)
        assertEquals("Prompto", o.name)
        assertEquals("A biography.", o.biography)
        assertEquals(listOf("Trevor Something", "Com Truise"), o.similarArtistNames)
        assertEquals(listOf("Album One", "Album Two"), o.albumTitles)
    }

    @Test
    fun `artistOverviewFrom tolerates missing relationships`() {
        val doc = """{ "data": { "id": "5", "type": "artists", "attributes": { "name": "Solo" } }, "included": [] }"""
        val o = artistOverviewFrom(json.decodeFromString(doc), json)!!
        assertEquals("Solo", o.name)
        assertNull(o.biography)
        assertEquals(emptyList<String>(), o.similarArtistNames)
        assertEquals(emptyList<String>(), o.albumTitles)
    }

    @Test
    fun `artistOverviewFrom returns null when there is no primary resource`() {
        val doc = """{ "data": [], "included": [] }"""
        assertNull(artistOverviewFrom(json.decodeFromString(doc), json))
    }

    @Test
    fun `artistRequest carries every include and the country code`() {
        val req = TidalOfficialEndpoints.artistRequest(
            id = "100", accessToken = "tok",
            include = listOf("biography", "similarArtists", "albums"), countryCode = "US",
        )
        val url = req.url
        assertEquals(listOf("biography", "similarArtists", "albums"), url.queryParameterValues("include"))
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals("Bearer tok", req.header("Authorization"))
        assertEquals("/v2/artists/100", url.encodedPath)
    }
}
