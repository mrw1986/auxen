package io.github.auxen.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionActionsTest {

    private fun albumIds(entries: List<AlbumActionEntry>) = entries.map { it.id }
    private fun artistIds(entries: List<ArtistActionEntry>) = entries.map { it.id }

    // ---- albumActions ----

    @Test
    fun albumBaseSetAndOrderWithoutNav() {
        // No handler for go-to-artist -> the five always-on playback/organize
        // entries, in render order.
        assertEquals(
            listOf(
                AlbumActionId.PLAY,
                AlbumActionId.SHUFFLE,
                AlbumActionId.PLAY_NEXT,
                AlbumActionId.ENQUEUE,
                AlbumActionId.ADD_TO_PLAYLIST,
            ),
            albumIds(albumActions(albumArtist = "Kavinsky", canGoToArtist = false)),
        )
    }

    @Test
    fun albumGoToArtistNeedsBothTheHandlerAndAnArtistName() {
        // Handler + a real album artist -> shown, last (nav section trails the rest).
        assertEquals(
            AlbumActionId.GO_TO_ARTIST,
            albumIds(albumActions("Kavinsky", canGoToArtist = true)).last(),
        )
        // Handler present but the album has no artist -> hidden (nothing to go to).
        assertEquals(false, AlbumActionId.GO_TO_ARTIST in albumIds(albumActions(null, canGoToArtist = true)))
        assertEquals(false, AlbumActionId.GO_TO_ARTIST in albumIds(albumActions("   ", canGoToArtist = true)))
        // Artist present but the host offers no handler -> hidden (no dead action).
        assertEquals(false, AlbumActionId.GO_TO_ARTIST in albumIds(albumActions("Kavinsky", canGoToArtist = false)))
    }

    @Test
    fun albumLabelsMatchTheDesktopWording() {
        val e = albumActions("Kavinsky", canGoToArtist = true).associate { it.id to it.label }
        assertEquals("Play album", e[AlbumActionId.PLAY])
        assertEquals("Shuffle album", e[AlbumActionId.SHUFFLE])
        assertEquals("Play album next", e[AlbumActionId.PLAY_NEXT])
        assertEquals("Add album to queue", e[AlbumActionId.ENQUEUE])
        assertEquals("Add album to playlist", e[AlbumActionId.ADD_TO_PLAYLIST])
        assertEquals("Go to artist", e[AlbumActionId.GO_TO_ARTIST])
    }

    // ---- artistActions ----

    @Test
    fun artistFixedSetAndOrder() {
        // No conditionality: the card exists only for a non-empty ArtistGroup, so
        // all four playback entries always show, in this order.
        assertEquals(
            listOf(
                ArtistActionId.PLAY,
                ArtistActionId.SHUFFLE,
                ArtistActionId.PLAY_NEXT,
                ArtistActionId.ENQUEUE,
            ),
            artistIds(artistActions()),
        )
    }

    @Test
    fun artistLabels() {
        val e = artistActions().associate { it.id to it.label }
        assertEquals("Play", e[ArtistActionId.PLAY])
        assertEquals("Shuffle", e[ArtistActionId.SHUFFLE])
        assertEquals("Play next", e[ArtistActionId.PLAY_NEXT])
        assertEquals("Add to queue", e[ArtistActionId.ENQUEUE])
    }
}
