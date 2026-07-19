package io.github.auxen.playback.browse

import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Needs Robolectric only for a working `android.os.Bundle` shadow (used by
 * [io.github.auxen.Graph.mediaItemFor], which every playable-leaf case here
 * goes through) -- same reason as [io.github.auxen.GraphMapperTest]. No
 * `Graph.init()` call: none of the paths exercised touch Graph's `lateinit`
 * fields.
 */
@RunWith(RobolectricTestRunner::class)
class AuxenBrowseTreeTest {
    private val tree = AuxenBrowseTree()

    private fun track(id: String, title: String) = Track(
        title = title, artist = "A", source = Source.TIDAL, sourceId = id,
    )

    @Test fun `root has the five categories in order`() {
        val ids = tree.rootChildren().map { it.mediaId }
        assertEquals(
            listOf(
                AuxenBrowseIds.RECENT, AuxenBrowseIds.PLAYLISTS, AuxenBrowseIds.ALBUMS,
                AuxenBrowseIds.ARTISTS, AuxenBrowseIds.FAVORITES,
            ),
            ids,
        )
    }

    @Test fun `root children are browsable folders, not playable`() {
        tree.rootChildren().forEach {
            assertTrue(it.mediaMetadata.isBrowsable == true)
            assertFalse(it.mediaMetadata.isPlayable == true)
        }
    }

    @Test fun `album category maps each favorite album to a browsable node`() {
        val albums = listOf(
            FavoriteAlbumEntity(source = "TIDAL", sourceId = "111", title = "Album One", addedAtMillis = 1),
            FavoriteAlbumEntity(source = "TIDAL", sourceId = "222", title = "Album Two", addedAtMillis = 2),
        )
        val nodes = tree.albumCategory(albums)
        assertEquals(listOf(AuxenBrowseIds.album("111"), AuxenBrowseIds.album("222")), nodes.map { it.mediaId })
        assertEquals("Album One", nodes[0].mediaMetadata.title)
        assertTrue(nodes[0].mediaMetadata.isBrowsable == true)
    }

    @Test fun `artist category maps each followed artist`() {
        val artists = listOf(
            FavoriteArtistEntity(source = "TIDAL", sourceId = "9", name = "Artist Nine", addedAtMillis = 1),
        )
        val nodes = tree.artistCategory(artists)
        assertEquals(AuxenBrowseIds.artist("9"), nodes.single().mediaId)
        assertEquals("Artist Nine", nodes.single().mediaMetadata.title)
    }

    @Test fun `playlist category maps each playlist by local id`() {
        val pls = listOf(PlaylistEntity(id = 5, name = "Roadtrip"))
        val nodes = tree.playlistCategory(pls)
        assertEquals(AuxenBrowseIds.playlist(5), nodes.single().mediaId)
        assertEquals("Roadtrip", nodes.single().mediaMetadata.title)
    }

    @Test fun `trackList produces playable leaves with SOURCE colon id media ids`() {
        val nodes = tree.trackList(listOf(track("42", "Song")))
        assertEquals("TIDAL:42", nodes.single().mediaId)
        assertTrue(nodes.single().mediaMetadata.isPlayable == true)
    }

    @Test fun `albumDetail prepends a Play all node then the tracks`() {
        val nodes = tree.albumDetail("111", listOf(track("1", "T1"), track("2", "T2")))
        assertEquals(AuxenBrowseIds.playAllAlbum("111"), nodes.first().mediaId)
        assertTrue(nodes.first().mediaMetadata.isPlayable == true)
        assertEquals(listOf("TIDAL:1", "TIDAL:2"), nodes.drop(1).map { it.mediaId })
    }

    @Test fun `artistDetail prepends a Play all node then the tracks`() {
        val nodes = tree.artistDetail("9", listOf(track("1", "T1")))
        assertEquals(AuxenBrowseIds.playAllArtist("9"), nodes.first().mediaId)
        assertEquals(listOf("TIDAL:1"), nodes.drop(1).map { it.mediaId })
    }

    @Test fun `empty category yields a single non-playable placeholder`() {
        val nodes = tree.albumCategory(emptyList())
        assertEquals(1, nodes.size)
        assertFalse(nodes.single().mediaMetadata.isPlayable == true)
        assertFalse(nodes.single().mediaMetadata.isBrowsable == true)
    }

    @Test fun `errorNode is non-playable and non-browsable`() {
        val n = tree.errorNode()
        assertFalse(n.mediaMetadata.isPlayable == true)
        assertFalse(n.mediaMetadata.isBrowsable == true)
    }

    @Test fun `item builds folders and play-all but not bare track ids`() {
        assertEquals(AuxenBrowseIds.ALBUMS, tree.item(AuxenBrowseIds.ALBUMS)?.mediaId)
        assertEquals(AuxenBrowseIds.playAllAlbum("111"), tree.item(AuxenBrowseIds.playAllAlbum("111"))?.mediaId)
        assertNull(tree.item("TIDAL:42"))
    }

    @Test fun `id parse round-trips`() {
        assertEquals(BrowseTarget.Playlist(7), AuxenBrowseIds.parse(AuxenBrowseIds.playlist(7)))
        assertEquals(BrowseTarget.Album("x"), AuxenBrowseIds.parse(AuxenBrowseIds.album("x")))
        assertEquals(BrowseTarget.PlayAllArtist("z"), AuxenBrowseIds.parse(AuxenBrowseIds.playAllArtist("z")))
        assertTrue(AuxenBrowseIds.parse("garbage") is BrowseTarget.Unknown)
    }
}
