package io.github.auxen.playback.cache

import android.app.Application
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PlaybackService clears the offline notice on a real queue change in onTimelineChanged and runs
 * the offline check in onMediaItemTransition. That only works if Media3 reports a new queue's
 * timeline first, or the clear would wipe the notice the new song's check just set.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: AuxenApp would open the Room database at startup.
@Config(application = Application::class)
class QueueChangeOrderTest {

    private lateinit var exo: ExoPlayer
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Never prepared: playlist edits dispatch their events synchronously.
        exo = ExoPlayer.Builder(context).setClock(FakeClock(/* isAutoAdvancing= */ true)).build()
    }

    @After
    fun tearDown() = exo.release()

    private fun tidal(id: String) =
        MediaItem.Builder().setMediaId("TIDAL:$id").setUri("auxen://tidal/$id").build()

    private fun recordOrder() = exo.addListener(object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) events += "timeline"
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            events += "transition ${mediaItem?.mediaId}"
        }
    })

    @Test fun `a new queue reports its timeline before its item transition`() {
        exo.setMediaItems(listOf(tidal("1"), tidal("2")))
        exo.playWhenReady = true
        recordOrder()

        exo.setMediaItems(listOf(tidal("7"), tidal("8")), 1, 0)

        assertEquals(listOf("timeline", "transition TIDAL:8"), events)
    }

    @Test fun `removing the playing song reports the timeline before the next song`() {
        exo.setMediaItems(listOf(tidal("1"), tidal("2"), tidal("3")))
        recordOrder()

        exo.removeMediaItem(0)

        assertEquals(listOf("timeline", "transition TIDAL:2"), events)
    }

    @Test fun `the notice set for a new queue survives the queue change clear`() {
        // Mirrors PlaybackService's listener: clear on a real queue change, check on item start.
        val status = OfflineStatus { 0L }
        val offline = NetworkState(online = false, unmetered = false)
        val skipper = OfflineSkipper(PlayerQueueAdapter { exo }, { null }, { offline }, status)
        var lastIds = emptyList<String>()
        exo.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
                val ids = queueMediaIds(exo)
                if (ids != lastIds) status.clear()
                lastIds = ids
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                skipper.onItemStarting()
            }
        })
        exo.playWhenReady = true

        exo.setMediaItems(listOf(tidal("7"), tidal("8")))

        assertTrue(status.nothingCachedAhead.value)
    }
}
