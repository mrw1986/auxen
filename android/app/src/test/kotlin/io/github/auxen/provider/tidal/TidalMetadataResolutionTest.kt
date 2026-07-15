package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidalMetadataResolutionTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val trackRelDoc = """
        {
          "data": {
            "id": "75413016", "type": "tracks",
            "attributes": { "title": "Sample", "isrc": "USABC1234567" },
            "relationships": {
              "artists": { "data": [ { "id": "3456", "type": "artists" } ] },
              "albums":  { "data": [ { "id": "9876", "type": "albums" } ] }
            }
          },
          "included": []
        }
    """.trimIndent()

    @Test
    fun `trackRelationsFrom pulls the first artist id, album id and the isrc`() {
        val rel = trackRelationsFrom(json.decodeFromString(trackRelDoc), json)!!
        assertEquals("3456", rel.artistId)
        assertEquals("9876", rel.albumId)
        assertEquals("USABC1234567", rel.isrc)
    }

    @Test
    fun `trackRelationsFrom tolerates missing relationships`() {
        val doc = """{ "data": { "id": "1", "type": "tracks", "attributes": {} }, "included": [] }"""
        val rel = trackRelationsFrom(json.decodeFromString(doc), json)!!
        assertNull(rel.artistId)
        assertNull(rel.albumId)
        assertNull(rel.isrc)
    }

    @Test
    fun `trackRelationsFrom returns null when there is no primary track`() {
        val doc = """{ "data": [], "included": [] }"""
        assertNull(trackRelationsFrom(json.decodeFromString(doc), json))
    }
}
