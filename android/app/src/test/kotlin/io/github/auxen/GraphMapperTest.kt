package io.github.auxen

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [Graph.trackFor] is the shared mapper Desktop-Parity Screens' Task 2
 * (Queue screen) needed -- the inverse of [Graph.mediaItemFor], extracted
 * so `PlaybackService.currentTracks` and `PlayerViewModel`'s new live
 * `queue` flow (plus its two pre-existing `nowPlaying`/`currentTrack`
 * extras-decode call sites) share one implementation instead of four
 * copies of the same `extras?.getString(TRACK_EXTRA_KEY)?.let { decode }`
 * snippet. Needs Robolectric only for a working `android.os.Bundle`
 * shadow (`MediaMetadata.Builder().setExtras(...)`) -- no `Graph.init()`
 * call, since `json`/`mediaItemFor`/`trackFor` don't touch any of
 * [Graph]'s `lateinit` fields.
 */
@RunWith(RobolectricTestRunner::class)
class GraphMapperTest {

    private val localTrack = Track(
        title = "Everlong",
        artist = "Foo Fighters",
        source = Source.LOCAL,
        sourceId = "42",
        album = "The Colour and the Shape",
    )

    private val tidalTrack = Track(
        title = "Nightcall",
        artist = "Kavinsky",
        source = Source.TIDAL,
        sourceId = "t-1",
        album = "OutRun",
    )

    @Test
    fun `trackFor round-trips a MediaItem built by mediaItemFor`() {
        val item = Graph.mediaItemFor(localTrack)
        assertEquals(localTrack, Graph.trackFor(item))
    }

    @Test
    fun `trackFor round-trips a Tidal track too`() {
        val item = Graph.mediaItemFor(tidalTrack)
        assertEquals(tidalTrack, Graph.trackFor(item))
    }

    @Test
    fun `trackFor returns null when extras are absent`() {
        val item = androidx.media3.common.MediaItem.Builder().setMediaId("bare").build()
        assertNull(Graph.trackFor(item))
    }
}
