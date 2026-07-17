package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import io.github.auxen.model.Track
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

    // ---- local ∪ Tidal discography dedup (mirrors desktop window.py:1394-1407) ----

    @Test
    fun `dedupRemoteAlbums drops remote albums whose lowercase title is already local`() {
        val remote = listOf(
            albumSummary("1", "Album One"),
            albumSummary("2", "Ride the Lightning"),
        )
        val kept = dedupRemoteAlbums(localTitles = setOf("album one"), remote = remote)
        assertEquals(listOf("Ride the Lightning"), kept.map { it.title })
    }

    @Test
    fun `dedupRemoteAlbums drops duplicates within the remote list itself, keeping the first`() {
        // The real API returned `Fixxxer (Remastered)` TWICE for artist 8405.
        // Desktop adds to seen_names inside the loop, so the second is dropped.
        val remote = listOf(
            albumSummary("1", "Fixxxer (Remastered)"),
            albumSummary("2", "Fixxxer (Remastered)"),
            albumSummary("3", "FIXXXER (REMASTERED)"),
        )
        assertEquals(listOf("1"), dedupRemoteAlbums(emptySet(), remote).map { it.id })
    }

    @Test
    fun `dedupRemoteAlbums is case-insensitive and preserves remote order`() {
        val remote = listOf(albumSummary("1", "Kill 'Em All"), albumSummary("2", "Reload"))
        assertEquals(
            listOf("Reload"),
            dedupRemoteAlbums(localTitles = setOf("kill 'em all"), remote = remote).map { it.title },
        )
    }

    // ---- top tracks ∪ local tracks (mirrors desktop window.py:1409-1422) ----

    @Test
    fun `mergeArtistTracks puts Tidal top tracks first, then local tracks not already in them`() {
        // Desktop: `tracks = list(tidal_top) + local_extras`. Popularity order
        // leads; the user's own extra files follow.
        val top = listOf(tidalTrack("100", "Enter Sandman"), tidalTrack("200", "One"))
        val local = listOf(tidalTrack("200", "One"), localTrack("f1", "Garage B-Side"))
        val merged = mergeArtistTracks(top, local)
        assertEquals(listOf("Enter Sandman", "One", "Garage B-Side"), merged.map { it.title })
    }

    @Test
    fun `mergeArtistTracks keeps the local list untouched when there are no top tracks`() {
        // Desktop guards with `if tidal_top:` — no top tracks means no merge at
        // all, NOT an empty track list. This is the not-logged-in / unknown-artist
        // / call-failed path, and it must leave the offline base exactly as it is.
        val local = listOf(localTrack("f1", "Only Local"))
        assertEquals(local, mergeArtistTracks(emptyList(), local))
    }

    @Test
    fun `mergeArtistTracks never drops a LOCAL track that shares an id with a Tidal top track`() {
        // DELIBERATE DIVERGENCE from desktop (window.py:1413-1421), which compares
        // `source_id` across sources: a MediaStore id and a Tidal catalog id are
        // both numeric strings, so a bare id compare can silently delete the
        // user's own file. Track identity in this codebase is ALWAYS namespaced by
        // source (ArtistDetailScreen.kt:78/166 key on "${source}:${sourceId}"), so
        // the dedup is scoped to Tidal tracks only.
        val top = listOf(tidalTrack("100", "Enter Sandman"))
        val local = listOf(localTrack("100", "My Own Recording"))
        assertEquals(
            listOf("Enter Sandman", "My Own Recording"),
            mergeArtistTracks(top, local).map { it.title },
        )
    }

    @Test
    fun `mergeArtistTracks yields keys unique by source and id even if Tidal repeats a track`() {
        // The merged list is rendered by a LazyColumn keyed on
        // "${source}:${sourceId}" (ArtistDetailScreen.kt:166). A duplicate key is
        // a hard Compose crash, and `topTracks` does NOT come through the pool's
        // `distinctBy` (ArtistDetailScreen.kt:78) — so the merge must guarantee it.
        val top = listOf(tidalTrack("100", "Enter Sandman"), tidalTrack("100", "Enter Sandman"))
        val merged = mergeArtistTracks(top, emptyList())
        assertEquals(1, merged.size)
        assertEquals(merged.size, merged.distinctBy { "${it.source}:${it.sourceId}" }.size)
    }

    private fun albumSummary(id: String, title: String) =
        AlbumSummary(id = id, title = title, albumType = "ALBUM", releaseYear = 1984, explicit = false, artworkUrl = null)

    private fun tidalTrack(id: String, title: String) =
        Track(title = title, artist = "Metallica", source = Source.TIDAL, sourceId = id)

    private fun localTrack(id: String, title: String) =
        Track(title = title, artist = "Metallica", source = Source.LOCAL, sourceId = id)
}
