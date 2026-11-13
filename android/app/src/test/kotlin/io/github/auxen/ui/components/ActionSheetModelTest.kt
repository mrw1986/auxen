package io.github.auxen.ui.components

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun track(
    title: String = "Everlong",
    artist: String = "Foo Fighters",
    album: String? = "The Colour and the Shape",
    albumArtist: String? = null,
    source: Source = Source.LOCAL,
    sourceId: String = "x",
) = Track(
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    source = source,
    sourceId = sourceId,
)

class ActionSheetModelTest {

    private fun ids(entries: List<ActionEntry>) = entries.map { it.id }
    private fun labelOf(entries: List<ActionEntry>, id: ActionId) =
        entries.firstOrNull { it.id == id }?.label

    // ---- albumTargetFor: must reproduce groupAlbums' key EXACTLY ----

    @Test
    fun albumTargetPrefersAlbumArtistOverArtist() {
        // groupAlbums keys on (album, albumArtist ?: artist)
        // (LibraryGrouping.kt:66-70). A compilation track whose artist differs
        // from its albumArtist must resolve to the ALBUM ARTIST or
        // AlbumDetailScreen's lookup (:85) finds no group.
        val t = track(artist = "Nina Simone", albumArtist = "Various Artists")
        assertEquals(
            AlbumTarget("The Colour and the Shape", "Various Artists"),
            albumTargetFor(t),
        )
    }

    @Test
    fun albumTargetFallsBackToArtistWhenAlbumArtistNullOrBlank() {
        assertEquals("Foo Fighters", albumTargetFor(track(albumArtist = null))?.albumArtist)
        assertEquals("Foo Fighters", albumTargetFor(track(albumArtist = "   "))?.albumArtist)
    }

    @Test
    fun albumTargetIsNullWithoutAnAlbum() {
        // Blank and null both -> null. groupAlbums files these under
        // "Unknown Album"; navigating there is useless, so the entry hides.
        assertNull(albumTargetFor(track(album = null)))
        assertNull(albumTargetFor(track(album = "  ")))
    }

    @Test
    fun albumTargetIsNullWhenNoArtistNameAtAll() {
        assertNull(albumTargetFor(track(artist = "", albumArtist = null)))
    }

    // ---- trackActions ----

    @Test
    fun trackActionsBaseSetAndOrder() {
        val entries = trackActions(
            track = track(),
            isFavorite = false,
            canGoToAlbum = false,
            canGoToArtist = false,
            canShowInfo = false,
        )
        // Exactly the five always-on entries, in the order the sheet renders
        // today. This order IS the action-sheet golden; changing it moves it.
        assertEquals(
            listOf(
                ActionId.PLAY,
                ActionId.PLAY_NEXT,
                ActionId.ENQUEUE,
                ActionId.TOGGLE_FAVORITE,
                ActionId.ADD_TO_PLAYLIST,
            ),
            ids(entries),
        )
    }

    @Test
    fun trackFavoriteLabelFlips() {
        val fav = trackActions(track(), true, false, false, false)
        val notFav = trackActions(track(), false, false, false, false)
        assertEquals("Remove from favorites", labelOf(fav, ActionId.TOGGLE_FAVORITE))
        assertEquals("Add to favorites", labelOf(notFav, ActionId.TOGGLE_FAVORITE))
    }

    @Test
    fun goToAlbumNeedsBothTheHandlerAndAnAlbum() {
        assertEquals(
            true,
            ActionId.GO_TO_ALBUM in ids(trackActions(track(), false, canGoToAlbum = true, canGoToArtist = false, canShowInfo = false)),
        )
        // Handler present but NO album -> hidden (nothing to navigate to).
        assertEquals(
            false,
            ActionId.GO_TO_ALBUM in ids(trackActions(track(album = null), false, canGoToAlbum = true, canGoToArtist = false, canShowInfo = false)),
        )
        // Album present but host offers no handler -> hidden (no dead action).
        assertEquals(
            false,
            ActionId.GO_TO_ALBUM in ids(trackActions(track(), false, canGoToAlbum = false, canGoToArtist = false, canShowInfo = false)),
        )
    }

    @Test
    fun goToArtistNeedsBothTheHandlerAndAnArtistName() {
        assertEquals(
            true,
            ActionId.GO_TO_ARTIST in ids(trackActions(track(), false, false, canGoToArtist = true, canShowInfo = false)),
        )
        assertEquals(
            false,
            ActionId.GO_TO_ARTIST in ids(trackActions(track(artist = "  "), false, false, canGoToArtist = true, canShowInfo = false)),
        )
        assertEquals(
            false,
            ActionId.GO_TO_ARTIST in ids(trackActions(track(), false, false, canGoToArtist = false, canShowInfo = false)),
        )
    }

    @Test
    fun trackInfoOfferedForEitherSourceWhenHostCanShowIt() {
        // D1: the info sheet now carries a Details section drawn from the Track
        // itself, so it is useful for a LOCAL track too — not Tidal-only.
        assertEquals(true, ActionId.SHOW_INFO in ids(trackActions(track(source = Source.TIDAL), false, false, false, canShowInfo = true)))
        assertEquals(true, ActionId.SHOW_INFO in ids(trackActions(track(source = Source.LOCAL), false, false, false, canShowInfo = true)))
        // No handler -> no dead row (also what keeps the action-sheet golden stable).
        assertEquals(false, ActionId.SHOW_INFO in ids(trackActions(track(source = Source.TIDAL), false, false, false, canShowInfo = false)))
    }

    @Test
    fun trackNavEntriesSitBetweenPlaylistAndInfo() {
        val entries = trackActions(
            track = track(source = Source.TIDAL),
            isFavorite = true,
            canGoToAlbum = true,
            canGoToArtist = true,
            canShowInfo = true,
        )
        assertEquals(
            listOf(
                ActionId.PLAY,
                ActionId.PLAY_NEXT,
                ActionId.ENQUEUE,
                ActionId.TOGGLE_FAVORITE,
                ActionId.ADD_TO_PLAYLIST,
                ActionId.GO_TO_ALBUM,
                ActionId.GO_TO_ARTIST,
                ActionId.SHOW_INFO,
            ),
            ids(entries),
        )
    }
}
