package io.github.auxen.playback.cache

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: AuxenApp would open the Room database at startup.
@Config(application = Application::class)
class QueueCacheSyncTest {

    private val root = "file:///data/no_backup/track_cache/"
    private val networkDash = "data:application/dash+xml;base64,PE1QRD4="

    private lateinit var exo: ExoPlayer
    private lateinit var player: CountingPlayer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Never prepared: the queue edits and seeks run against ExoPlayer's real playlist state.
        exo = ExoPlayer.Builder(context).setClock(FakeClock(/* isAutoAdvancing= */ true)).build()
        player = CountingPlayer(exo)
    }

    @After
    fun tearDown() = exo.release()

    @Test
    fun `queueItemUpdate restores a DASH-swapped copy once cached as progressive`() {
        val item = tidal("42", networkDash, MimeTypes.APPLICATION_MPD)

        val updated = queueItemUpdate(item, progressive("42"), root)!!

        assertEquals("auxen://tidal/42", updated.uriString())
        assertNull(updated.localConfiguration!!.mimeType)
        assertEquals("TIDAL:42", updated.mediaId)
        assertEquals("Song 42", updated.mediaMetadata.title)
    }

    @Test
    fun `queueItemUpdate points a cached DASH track at its local manifest`() {
        val updated = queueItemUpdate(tidal("42"), dash("42"), root)!!

        assertEquals(dash("42").uri, updated.uriString())
        assertEquals(MimeTypes.APPLICATION_MPD, updated.localConfiguration!!.mimeType)
        assertEquals("TIDAL:42", updated.mediaId)
    }

    @Test
    fun `queueItemUpdate leaves an already-updated item alone`() {
        assertNull(queueItemUpdate(tidal("42"), progressive("42"), root))
        assertNull(queueItemUpdate(tidal("42", dash("42").uri, MimeTypes.APPLICATION_MPD), dash("42"), root))
    }

    @Test
    fun `queueItemUpdate leaves uncached network copies alone`() {
        assertNull(queueItemUpdate(tidal("42"), null, root))
        assertNull(queueItemUpdate(tidal("42", networkDash, MimeTypes.APPLICATION_MPD), null, root))
        assertNull(queueItemUpdate(local("7"), progressive("7"), root))
    }

    @Test
    fun `queueItemUpdate restores a local item whose cache entry is gone`() {
        val item = tidal("42", dash("42").uri, MimeTypes.APPLICATION_MPD)

        val updated = queueItemUpdate(item, null, root)!!

        assertEquals("auxen://tidal/42", updated.uriString())
        assertNull(updated.localConfiguration!!.mimeType)
    }

    @Test
    fun `reconcile updates every non-current copy of a track queued twice`() {
        player.setMediaItems(listOf(tidal("42"), tidal("1"), tidal("42"), tidal("3"), tidal("42")), 2, 0)

        reconcileQueueWithCache(player, cache("42" to dash("42")), root)

        assertEquals(dash("42").uri, player.uriAt(0))
        assertEquals("auxen://tidal/1", player.uriAt(1))
        assertEquals("auxen://tidal/42", player.uriAt(2))
        assertEquals("auxen://tidal/3", player.uriAt(3))
        assertEquals(dash("42").uri, player.uriAt(4))
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(2, player.edits)
    }

    @Test
    fun `reconcile never moves the playing item onto the cache`() {
        player.setMediaItems(listOf(tidal("42"), tidal("9", networkDash, MimeTypes.APPLICATION_MPD)), 0, 30_000)

        reconcileQueueWithCache(player, cache("42" to dash("42")), root)
        assertEquals("auxen://tidal/42", player.uriAt(0))

        player.seekTo(1, 30_000)
        reconcileQueueWithCache(player, cache("9" to progressive("9")), root)
        assertEquals(networkDash, player.uriAt(1))

        assertEquals(0, player.edits)
        assertEquals(30_000, player.currentPosition)
    }

    @Test
    fun `reconcile restores a playing item whose file is gone and keeps its position`() {
        val stale = tidal("42", dash("42").uri, MimeTypes.APPLICATION_MPD)
        player.setMediaItems(listOf(tidal("1"), stale, tidal("3")), 1, 42_000)
        player.playWhenReady = true

        reconcileQueueWithCache(player, cache(), root)

        assertEquals("auxen://tidal/42", player.uriAt(1))
        assertNull(player.getMediaItemAt(1).localConfiguration!!.mimeType)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(42_000, player.currentPosition)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `reconcile run twice makes no second edit`() {
        val playing = tidal("5", dash("5").uri, MimeTypes.APPLICATION_MPD)
        val gone = tidal("6", dash("6").uri, MimeTypes.APPLICATION_MPD)
        player.setMediaItems(listOf(tidal("42"), playing, gone, tidal("7", networkDash, MimeTypes.APPLICATION_MPD)), 1, 5_000)
        val lookup = cache("42" to dash("42"), "7" to progressive("7"))
        reconcileQueueWithCache(player, lookup, root)
        val first = player.uris()
        assertEquals(4, player.edits)

        reconcileQueueWithCache(player, lookup, root)

        assertEquals(4, player.edits)
        assertEquals(first, player.uris())
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(5_000, player.currentPosition)
    }

    @Test
    fun `reconcile settles when its own timeline change runs it again`() {
        val lookup = cache("42" to dash("42"))
        player.setMediaItems(listOf(tidal("42"), tidal("1"), tidal("42")), 1, 7_000)
        shadowOf(Looper.getMainLooper()).idle()
        // The service reconciles on every PLAYLIST_CHANGED, and ExoPlayer delivers that inside replaceMediaItem.
        var reruns = 0
        exo.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
                reruns++
                reconcileQueueWithCache(player, lookup, root)
            }
        })

        reconcileQueueWithCache(player, lookup, root)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("timeline reruns $reruns", reruns > 0)
        assertEquals(listOf(dash("42").uri, "auxen://tidal/1", dash("42").uri), player.uris())
        assertEquals(2, player.edits)
        assertEquals(7_000, player.currentPosition)
    }

    @Test
    fun `only real queue edits change the queue's media ids`() {
        // Mirrors PlaybackService.lastQueueIds: one entry per PLAYLIST_CHANGED, true when the ids changed.
        val changes = mutableListOf<Boolean>()
        var last = emptyList<String>()
        exo.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
                val ids = queueMediaIds(exo)
                changes += ids != last
                last = ids
            }
        })
        val looper = shadowOf(Looper.getMainLooper())

        player.setMediaItems(listOf(tidal("42"), local("7"), tidal("1"), tidal("6", dash("6").uri, MimeTypes.APPLICATION_MPD)), 2, 0)
        looper.idle()
        reconcileQueueWithCache(player, cache("42" to dash("42")), root) // two swaps: 42 onto the cache, 6 off it
        looper.idle()
        replacePlayingItem(player, tidal("1", networkDash, MimeTypes.APPLICATION_MPD)) // a DASH swap of the playing song
        looper.idle()
        val swapsOnly = changes.toList()
        player.addMediaItem(tidal("9"))
        looper.idle()
        player.moveMediaItem(4, 0)
        looper.idle()
        player.removeMediaItem(0)
        looper.idle()

        assertEquals(listOf(true, false, false, false), swapsOnly)
        assertEquals(listOf(true, false, false, false, true, true, true), changes)
        assertEquals(listOf("TIDAL:42", "LOCAL:7", "TIDAL:1", "TIDAL:6"), last)
    }

    @Test
    fun `swapping the playing item's DASH copy keeps its position`() {
        player.setMediaItems(listOf(tidal("1"), tidal("42"), tidal("3")), 1, 42_000)
        player.playWhenReady = true

        assertTrue(swapDashCopies(player, "42", networkDash))

        assertEquals(networkDash, player.uriAt(1))
        assertEquals(MimeTypes.APPLICATION_MPD, player.getMediaItemAt(1).localConfiguration!!.mimeType)
        assertEquals("Song 42", player.getMediaItemAt(1).mediaMetadata.title)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(42_000, player.currentPosition)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `swapDashCopies only touches unresolved copies of that track`() {
        val queue = listOf(
            tidal("42"),
            tidal("42", networkDash, MimeTypes.APPLICATION_MPD),
            tidal("1"),
            tidal("42", dash("42").uri, MimeTypes.APPLICATION_MPD),
            tidal("420"),
            local("42"),
            tidal("42"),
        )
        player.setMediaItems(queue, 2, 9_000)

        assertTrue(swapDashCopies(player, "42", networkDash))

        assertEquals(
            listOf(
                networkDash,
                networkDash,
                "auxen://tidal/1",
                dash("42").uri,
                "auxen://tidal/420",
                "content://media/external/audio/media/42",
                networkDash,
            ),
            player.uris(),
        )
        assertEquals(2, player.edits)
        assertFalse(swapDashCopies(player, "42", networkDash))
        assertFalse(swapDashCopies(player, "9", networkDash))
        assertEquals(2, player.edits)
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(9_000, player.currentPosition)
    }

    @Test
    fun `a DASH signal is stale once the playing copy has been swapped`() {
        player.setMediaItems(listOf(tidal("1"), tidal("42"), local("7")), 1, 0)
        assertFalse(isPlayingResolvedCopy(player, "42"))

        swapDashCopies(player, "42", dash("42").uri)

        assertTrue(isPlayingResolvedCopy(player, "42"))
        assertFalse(isPlayingResolvedCopy(player, "1"))
        player.seekTo(2, 0)
        assertFalse(isPlayingResolvedCopy(player, "7"))
    }

    private fun tidal(id: String, uri: String = "auxen://tidal/$id", mimeType: String? = null) =
        MediaItem.Builder()
            .setMediaId("TIDAL:$id")
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Song $id").build())
            .build()

    private fun local(id: String) =
        MediaItem.Builder().setMediaId("LOCAL:$id").setUri("content://media/external/audio/media/$id").build()

    private fun progressive(id: String) = CachedPlayback(CacheKind.PROGRESSIVE, "$root$id/audio")

    private fun dash(id: String) = CachedPlayback(CacheKind.DASH, "$root$id/manifest.mpd")

    private fun cache(vararg entries: Pair<String, CachedPlayback>): (String) -> CachedPlayback? = entries.toMap()::get

    private fun MediaItem.uriString() = localConfiguration!!.uri.toString()

    private fun Player.uriAt(index: Int) = getMediaItemAt(index).uriString()

    private fun Player.uris() = (0 until mediaItemCount).map { uriAt(it) }

    /** Counts queue edits while passing them through to the real player. */
    private class CountingPlayer(player: Player) : ForwardingPlayer(player) {
        var edits = 0
            private set

        override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
            edits++
            super.replaceMediaItem(index, mediaItem)
        }

        override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) {
            edits++
            super.replaceMediaItems(fromIndex, toIndex, mediaItems)
        }
    }
}
