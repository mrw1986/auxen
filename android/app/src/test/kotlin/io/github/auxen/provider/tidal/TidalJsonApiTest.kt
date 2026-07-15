package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalJsonApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable data class Name(val name: String? = null)
    @Serializable data class Bio(val text: String? = null)
    @Serializable data class Title(val title: String? = null)

    private val artistDoc = """
        {
          "data": {
            "id": "100", "type": "artists",
            "attributes": { "name": "Prompto", "popularity": 0.5 },
            "relationships": {
              "biography":      { "data": { "id": "b1", "type": "artistBiographies" } },
              "similarArtists": { "data": [ { "id": "200", "type": "artists" }, { "id": "201", "type": "artists" } ] },
              "albums":         { "data": [ { "id": "300", "type": "albums" } ] }
            }
          },
          "included": [
            { "id": "b1",  "type": "artistBiographies", "attributes": { "text": "A biography.", "source": "TIDAL" } },
            { "id": "200", "type": "artists", "attributes": { "name": "Trevor Something" } },
            { "id": "201", "type": "artists", "attributes": { "name": "Com Truise" } },
            { "id": "300", "type": "albums",  "attributes": { "title": "Album One" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `primary resource is parsed with type and attributes`() {
        val doc = json.decodeFromString<TidalDocument>(artistDoc)
        val view = TidalDocumentView(doc, json)
        val artist = view.primary!!
        assertEquals("artists", artist.type)
        assertEquals("100", artist.id)
        assertEquals("Prompto", artist.decodeAttributes<Name>(json)?.name)
    }

    @Test
    fun `single-object relationship resolves to one included resource`() {
        val view = TidalDocumentView(json.decodeFromString(artistDoc), json)
        val bio = view.related(view.primary!!, "biography")
        assertEquals(1, bio.size)
        assertEquals("A biography.", bio.first().decodeAttributes<Bio>(json)?.text)
    }

    @Test
    fun `array relationship resolves to included resources in order`() {
        val view = TidalDocumentView(json.decodeFromString(artistDoc), json)
        val similar = view.related(view.primary!!, "similarArtists")
            .mapNotNull { it.decodeAttributes<Name>(json)?.name }
        assertEquals(listOf("Trevor Something", "Com Truise"), similar)
        val albums = view.related(view.primary!!, "albums")
            .mapNotNull { it.decodeAttributes<Title>(json)?.title }
        assertEquals(listOf("Album One"), albums)
    }

    @Test
    fun `missing relationship yields empty list, not a crash`() {
        val view = TidalDocumentView(json.decodeFromString(artistDoc), json)
        assertTrue(view.related(view.primary!!, "doesNotExist").isEmpty())
    }

    @Test
    fun `relationship pointing at an absent included resource is dropped`() {
        val doc = """
            { "data": { "id": "1", "type": "artists",
              "relationships": { "albums": { "data": [ { "id": "999", "type": "albums" } ] } } },
              "included": [] }
        """.trimIndent()
        val view = TidalDocumentView(json.decodeFromString(doc), json)
        assertTrue(view.related(view.primary!!, "albums").isEmpty())
    }

    @Test
    fun `array data document exposes every primary resource`() {
        val doc = """
            { "data": [ { "id": "1", "type": "artists" }, { "id": "2", "type": "artists" } ], "included": [] }
        """.trimIndent()
        val view = TidalDocumentView(json.decodeFromString(doc), json)
        assertEquals(2, view.primaryList.size)
        assertEquals(listOf("1", "2"), view.primaryList.map { it.id })
    }

    @Test
    fun `decodeAttributes returns null when attributes are absent`() {
        val doc = """{ "data": { "id": "1", "type": "artists" }, "included": [] }"""
        val view = TidalDocumentView(json.decodeFromString(doc), json)
        assertNull(view.primary!!.decodeAttributes<Name>(json))
    }
}
