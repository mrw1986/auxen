package io.github.auxen.playback.cache

import android.app.Application
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: AuxenApp would open the Room database at startup.
@Config(application = Application::class)
class PlayerQueueAdapterTest {

    private lateinit var exo: ExoPlayer
    private var current: Player? = null
    private val queue = PlayerQueueAdapter { current }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Never prepared: the queue reads and edits run against ExoPlayer's real playlist state.
        exo = ExoPlayer.Builder(context).setClock(FakeClock(/* isAutoAdvancing= */ true)).build()
        current = exo
    }

    @After
    fun tearDown() = exo.release()

    private fun tidal(id: String) =
        MediaItem.Builder().setMediaId("TIDAL:$id").setUri("auxen://tidal/$id").build()

    private fun load(vararg ids: String, start: Int = 0) {
        exo.setMediaItems(ids.map(::tidal), start, 0)
    }

    @Test fun `play order runs to the end without repeat`() {
        load("1", "2", "3", start = 1)

        assertEquals(listOf(1, 2), queue.playOrderFrom(1))
    }

    @Test fun `play order wraps with repeat all`() {
        load("1", "2", "3", start = 1)
        exo.repeatMode = Player.REPEAT_MODE_ALL

        assertEquals(listOf(1, 2, 0), queue.playOrderFrom(1))
    }

    @Test fun `repeat one counts as off for the play order`() {
        load("1", "2", "3", start = 1)
        exo.repeatMode = Player.REPEAT_MODE_ONE

        assertEquals(listOf(1, 2), queue.playOrderFrom(1))
        assertEquals(2, nextIndexForCache(exo, 1))
    }

    @Test fun `replaceUri keeps the media id and sets the mime type`() {
        load("1", "2")

        queue.replaceUri(1, "file:///cache/2/manifest.mpd", MimeTypes.APPLICATION_MPD)

        val item = exo.getMediaItemAt(1)
        assertEquals("TIDAL:2", item.mediaId)
        assertEquals("file:///cache/2/manifest.mpd", queue.uriAt(1))
        assertEquals(MimeTypes.APPLICATION_MPD, item.localConfiguration!!.mimeType)
    }

    @Test fun `seekTo moves the current item`() {
        load("1", "2", "3")

        queue.seekTo(2, 0)

        assertEquals(2, queue.currentIndex)
        assertEquals("TIDAL:3", queue.mediaIdAt(queue.currentIndex))
    }

    @Test fun `an empty queue or no player reads as nothing`() {
        assertEquals(-1, queue.currentIndex)
        assertEquals(emptyList<Int>(), queue.playOrderFrom(0))

        load("1")
        current = null
        assertEquals(-1, queue.currentIndex)
        assertEquals("", queue.mediaIdAt(0))
        assertNull(queue.uriAt(0))
        assertNull(queue.playerError)
        assertFalse(queue.playWhenReady)
        queue.pause() // no player, no crash
    }

    @Test fun `out of range indexes read as nothing`() {
        load("1")

        assertEquals("", queue.mediaIdAt(5))
        assertNull(queue.uriAt(-1))
        assertEquals(emptyList<Int>(), queue.playOrderFrom(3))
    }
}
