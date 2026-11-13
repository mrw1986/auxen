package io.github.auxen.data

import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Collection Albums/Artists union — desktop semantics: Tidal saved albums ∪
 * local albums with favorited tracks (Artists likewise). Pure; no Room, no
 * Compose, no network.
 */
class CollectionMergeTest {

    private fun tidalTrack(id: String, album: String, artist: String) = Track(
        title = "Song $id",
        artist = artist,
        source = Source.TIDAL,
        sourceId = id,
        album = album,
        albumArtist = artist,
        albumArtUrl = "https://art/$album.jpg",
    )

    private fun savedAlbum(id: String, title: String, artist: String? = "Saved Artist", art: String? = "https://art/$id.jpg") =
        FavoriteAlbumEntity(source = "TIDAL", sourceId = id, title = title, artist = artist, artUrl = art, addedAtMillis = 0)

    private fun followedArtist(id: String, name: String, art: String? = "https://pic/$id.jpg") =
        FavoriteArtistEntity(source = "TIDAL", sourceId = id, name = name, artUrl = art, addedAtMillis = 0)

    // ---------------- albums ----------------

    @Test
    fun `local groups pass through with their tracks and art`() {
        val local = groupAlbums(listOf(tidalTrack("1", "OutRun", "Kavinsky")))
        val merged = mergeCollectionAlbums(local, emptyList())
        assertEquals(1, merged.size)
        assertEquals("OutRun", merged[0].title)
        assertEquals("Kavinsky", merged[0].artist)
        assertEquals("https://art/OutRun.jpg", merged[0].artUrl)
        assertEquals(Source.TIDAL, merged[0].source)
        assertEquals(1, merged[0].tracks.size)
        assertNull(merged[0].tidalId)
        assertFalse(merged[0].isRemoteFavorite)
    }

    @Test
    fun `a saved album with no local tracks still surfaces, fully rendered`() {
        val merged = mergeCollectionAlbums(emptyList(), listOf(savedAlbum("9", "Album Nine")))
        assertEquals(listOf("Album Nine"), merged.map { it.title })
        assertEquals("9", merged[0].tidalId)
        assertTrue(merged[0].isRemoteFavorite)
        assertEquals(Source.TIDAL, merged[0].source)
        // Internal-v1 carries the artist + cover, so a remote-only card renders fully.
        assertEquals("Saved Artist", merged[0].artist)
        assertEquals("https://art/9.jpg", merged[0].artUrl)
        assertTrue(merged[0].tracks.isEmpty())
    }

    @Test
    fun `a saved album that already has a local group is deduped in favour of the local group`() {
        val local = groupAlbums(listOf(tidalTrack("1", "OutRun", "Kavinsky")))
        val merged = mergeCollectionAlbums(local, listOf(savedAlbum("9", "OutRun")))
        assertEquals(1, merged.size)
        assertEquals("Kavinsky", merged[0].artist)
        assertEquals(1, merged[0].tracks.size)
    }

    @Test
    fun `dedup is exact-name but tolerant of case and surrounding whitespace`() {
        val local = groupAlbums(listOf(tidalTrack("1", "OutRun", "Kavinsky")))
        assertEquals(1, mergeCollectionAlbums(local, listOf(savedAlbum("9", "  outrun "))).size)
        // A genuinely different title is NOT deduped (clean/explicit variants stay visible).
        assertEquals(2, mergeCollectionAlbums(local, listOf(savedAlbum("9", "OutRun (Deluxe)"))).size)
    }

    @Test
    fun `local and remote entries have distinct stable keys`() {
        val local = groupAlbums(listOf(tidalTrack("1", "OutRun", "Kavinsky")))
        val merged = mergeCollectionAlbums(local, listOf(savedAlbum("9", "Nine"), savedAlbum("10", "Nine Too")))
        assertEquals(merged.size, merged.map { it.key }.distinct().size)
    }

    @Test
    fun `toAlbumGroup carries the fields album navigation keys on`() {
        val group = mergeCollectionAlbums(emptyList(), listOf(savedAlbum("9", "Album Nine")))
            .single()
            .toAlbumGroup()
        assertEquals("Album Nine", group.album)
        assertEquals("Saved Artist", group.albumArtist)
        assertTrue(group.tracks.isEmpty())
    }

    @Test
    fun `a local-source favorite album row projects Source LOCAL and no tidal id`() {
        val row = FavoriteAlbumEntity(source = "LOCAL", sourceId = "abc", title = "Home Tapes", addedAtMillis = 1)
        val merged = mergeCollectionAlbums(emptyList(), listOf(row)).single()
        assertEquals(Source.LOCAL, merged.source)
        assertNull(merged.tidalId)
    }

    // ---------------- artists ----------------

    @Test
    fun `followed artists union with locally-grouped artists and dedupe by name`() {
        val local = groupArtists(listOf(tidalTrack("1", "OutRun", "Kavinsky")))
        val merged = mergeCollectionArtists(
            local,
            listOf(followedArtist("7", "Prompto"), followedArtist("8", "kavinsky")),
        )
        assertEquals(listOf("Kavinsky", "Prompto"), merged.map { it.name })
        assertEquals(1, merged[0].trackCount)
        assertNull(merged[0].tidalId)
        assertEquals("7", merged[1].tidalId)
        assertEquals(0, merged[1].trackCount)
        assertTrue(merged[1].isRemoteFavorite)
    }

    @Test
    fun `a followed artist with no local tracks still surfaces with its picture`() {
        val merged = mergeCollectionArtists(emptyList(), listOf(followedArtist("7", "Prompto")))
        assertEquals(listOf("Prompto"), merged.map { it.name })
        assertEquals("https://pic/7.jpg", merged[0].artUrl)
        assertEquals(Source.TIDAL, merged[0].source)
    }
}
