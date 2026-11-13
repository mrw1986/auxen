package io.github.auxen.playback.browse

import androidx.media3.session.MediaConstants
import io.github.auxen.Graph
import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test fun `cached tidal leaf carries the downloaded status extra`() {
        val cached = setOf("42")
        val leaves = listOf(
            tree.trackList(listOf(track("42", "Song")), cachedIds = cached).single(),
            tree.albumDetail("111", listOf(track("42", "Song")), cachedIds = cached)[1],
            tree.artistDetail("9", listOf(track("42", "Song")), cachedIds = cached)[1],
        )
        leaves.forEach { leaf ->
            assertEquals("TIDAL:42", leaf.mediaId)
            assertEquals(
                MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED,
                leaf.mediaMetadata.extras?.getLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS),
            )
        }
    }

    @Test fun `uncached leaf has no download status`() {
        val nodes = tree.trackList(
            listOf(
                track("42", "Cached"),
                track("43", "Not cached"),
                // The cache holds bare Tidal ids. A MediaStore id can be the same number,
                // so a local song must not borrow a Tidal song's badge.
                Track(title = "Local", artist = "A", source = Source.LOCAL, sourceId = "42"),
            ),
            cachedIds = setOf("42"),
        )
        val uncached = nodes.drop(1)
        assertEquals(listOf("TIDAL:43", "LOCAL:42"), uncached.map { it.mediaId })
        uncached.forEach { leaf ->
            assertFalse(leaf.mediaMetadata.extras!!.containsKey(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS))
        }
    }

    @Test fun `download status keeps the existing track extras`() {
        val leaf = tree.trackList(listOf(track("42", "Song")), cachedIds = setOf("42")).single()
        val extras = leaf.mediaMetadata.extras!!
        assertEquals(track("42", "Song"), Graph.trackFor(leaf))
        assertNotNull(extras.getString(Graph.QUEUE_UID_KEY))
        assertTrue(leaf.mediaMetadata.isPlayable == true)
    }

    @Test fun `id parse round-trips`() {
        assertEquals(BrowseTarget.Playlist(7), AuxenBrowseIds.parse(AuxenBrowseIds.playlist(7)))
        assertEquals(BrowseTarget.Album("x"), AuxenBrowseIds.parse(AuxenBrowseIds.album("x")))
        assertEquals(BrowseTarget.PlayAllArtist("z"), AuxenBrowseIds.parse(AuxenBrowseIds.playAllArtist("z")))
        assertTrue(AuxenBrowseIds.parse("garbage") is BrowseTarget.Unknown)
    }
}
