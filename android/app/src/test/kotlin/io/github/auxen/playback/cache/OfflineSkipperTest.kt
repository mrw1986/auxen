package io.github.auxen.playback.cache

import android.app.Application
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException

// Robolectric only because PlaybackException stamps itself with SystemClock.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OfflineSkipperTest {

    private val offline = NetworkState(online = false, unmetered = false)
    private val online = NetworkState(online = true, unmetered = true)
    private val networkDash = "data:application/dash+xml;base64,PE1QRD4="

    private var now = 0L
    private val status = OfflineStatus { now }
    private var network = offline
    private val cache = mutableMapOf<String, CachedPlayback>()

    private fun skipper(queue: FakePlayerQueue) = OfflineSkipper(queue, { cache[it] }, { network }, status)

    private fun cacheProgressive(id: String) {
        cache[id] = CachedPlayback(CacheKind.PROGRESSIVE, "file:///cache/$id/track.flac")
    }

    private fun cacheDash(id: String): String {
        val manifest = "file:///cache/$id/manifest.mpd"
        cache[id] = CachedPlayback(CacheKind.DASH, manifest)
        return manifest
    }

    private fun networkError() =
        PlaybackException("Source error", IOException("resolve", UnknownHostException("unresolved")), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    private fun forbidden(): PlaybackException {
        val http = HttpDataSource.InvalidResponseCodeException(
            403, "Forbidden", null, emptyMap(), DataSpec(Uri.parse("https://example.invalid/track")), ByteArray(0),
        )
        return PlaybackException("Source error", http, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }

    // ---- item start ----

    @Test fun `online does nothing on item start`() {
        network = online
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2")
        cacheProgressive("2")

        assertEquals(OfflineAction.None, skipper(queue).onItemStarting())
        assertTrue(queue.calls.isEmpty())
    }

    @Test fun `offline item start on a cached song does nothing`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2")
        cacheProgressive("1")

        assertEquals(OfflineAction.None, skipper(queue).onItemStarting())
        assertTrue(queue.calls.isEmpty())
    }

    @Test fun `offline item start on an uncached song skips to the next cached one`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2", "TIDAL:3")
        cacheProgressive("3")

        assertEquals(OfflineAction.Skipped(2), skipper(queue).onItemStarting())
        assertEquals(listOf("seekTo(2, 0)", "prepareAndPlay"), queue.calls)
    }

    @Test fun `local tracks count as playable offline`() {
        assertTrue(isPlayableOffline("LOCAL:9") { null })
        assertFalse(isPlayableOffline("TIDAL:9") { null })
        assertFalse(isPlayableOffline("") { null })

        val queue = FakePlayerQueue("TIDAL:1", "LOCAL:9")
        assertEquals(OfflineAction.Skipped(1), skipper(queue).onItemStarting())
    }

    @Test fun `skipped songs stay in the queue`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2", "TIDAL:3")
        cacheProgressive("3")

        skipper(queue).onItemStarting()

        assertEquals(listOf("TIDAL:1", "TIDAL:2", "TIDAL:3"), queue.mediaIds)
        assertTrue(queue.calls.none { it.startsWith("remove") })
    }

    @Test fun `nothing ahead pauses and shows the message`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2")

        assertEquals(OfflineAction.PausedNothingAhead(0, "TIDAL:1"), skipper(queue).onItemStarting())
        assertEquals(listOf("pause"), queue.calls)
        assertTrue(status.nothingCachedAhead.value)
    }

    @Test fun `skip does not wrap past the end without repeat`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2", "TIDAL:3", current = 1)
        cacheProgressive("1")

        assertEquals(OfflineAction.PausedNothingAhead(1, "TIDAL:2"), skipper(queue).onItemStarting())
        assertEquals(listOf("pause"), queue.calls)
    }

    @Test fun `skip wraps to the start with repeat all`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2", "TIDAL:3", current = 1).apply { repeatAll = true }
        cacheProgressive("1")

        assertEquals(OfflineAction.Skipped(0), skipper(queue).onItemStarting())
    }

    @Test fun `paused item start does nothing`() {
        // The startup restore and paused queue edits fire item transitions too.
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2").apply { playWhenReady = false }
        cacheProgressive("2")

        assertEquals(OfflineAction.None, skipper(queue).onItemStarting())
        assertTrue(queue.calls.isEmpty())
        assertFalse(status.nothingCachedAhead.value)
    }

    // ---- errors ----

    @Test fun `a network failure on a cached progressive song resumes at the same position`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2", position = 41_000)
        cacheProgressive("1")

        assertEquals(OfflineAction.ResumedFromCache(41_000), skipper(queue).onError(networkError()))
        assertEquals(listOf("seekTo(0, 41000)", "prepareAndPlay"), queue.calls)
    }

    @Test fun `a network failure on a cached DASH song swaps to the local manifest first`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2", position = 41_000).apply { uris[0] = networkDash }
        val manifest = cacheDash("1")

        assertEquals(OfflineAction.ResumedFromCache(41_000), skipper(queue).onError(networkError()))
        assertEquals(
            listOf("replaceUri(0, $manifest, ${MimeTypes.APPLICATION_MPD})", "seekTo(0, 41000)", "prepareAndPlay"),
            queue.calls,
        )
    }

    @Test fun `a cached DASH song already on its local manifest is not swapped again`() {
        val queue = FakePlayerQueue("TIDAL:1", position = 41_000)
        queue.uris[0] = cacheDash("1")

        skipper(queue).onError(networkError())

        assertEquals(listOf("seekTo(0, 41000)", "prepareAndPlay"), queue.calls)
    }

    @Test fun `a cached progressive song on a network DASH copy goes back to its stable uri first`() {
        // Re-preparing the network manifest would only fail again.
        val queue = FakePlayerQueue("TIDAL:1", position = 41_000).apply { uris[0] = networkDash }
        cacheProgressive("1")

        skipper(queue).onError(networkError())

        assertEquals(listOf("replaceUri(0, auxen://tidal/1, null)", "seekTo(0, 41000)", "prepareAndPlay"), queue.calls)
    }

    @Test fun `a network failure on an uncached song skips to the next cached one`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2", "TIDAL:3", position = 41_000)
        cacheProgressive("3")

        assertEquals(OfflineAction.Skipped(2), skipper(queue).onError(networkError()))
        assertEquals(listOf("seekTo(2, 0)", "prepareAndPlay"), queue.calls)
    }

    @Test fun `a network failure with nothing cached ahead pauses and shows the message`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2")

        assertEquals(OfflineAction.PausedNothingAhead(0, "TIDAL:1"), skipper(queue).onError(networkError()))
        assertEquals(listOf("pause"), queue.calls)
        assertTrue(status.nothingCachedAhead.value)
    }

    @Test fun `a network failure while online is not handled`() {
        network = online
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2")
        cacheProgressive("1")

        assertEquals(OfflineAction.None, skipper(queue).onError(networkError()))
        assertTrue(queue.calls.isEmpty())
        assertFalse(status.handledRecently())
    }

    @Test fun `an HTTP 403 is not an offline failure`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2")
        cacheProgressive("2")

        assertEquals(OfflineAction.None, skipper(queue).onError(forbidden()))
        assertTrue(queue.calls.isEmpty())
    }

    @Test fun `a network failure while paused starts nothing but quiets the banner`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2").apply { playWhenReady = false }
        cacheProgressive("2")

        assertEquals(OfflineAction.None, skipper(queue).onError(networkError()))
        assertTrue(queue.calls.isEmpty())
        assertTrue(status.handledRecently())
    }

    // ---- network changes ----

    @Test fun `going offline while holding a network error applies the error path`() {
        network = online
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2", position = 41_000).apply { playerError = networkError() }
        cacheProgressive("1")
        val skipper = skipper(queue)

        // The validated network outlived the radio, so the error wasn't handled when it came in.
        assertEquals(OfflineAction.None, skipper.onError(queue.playerError!!))
        network = offline

        assertEquals(OfflineAction.ResumedFromCache(41_000), skipper.onNetworkChanged(offline))
        assertEquals(listOf("seekTo(0, 41000)", "prepareAndPlay"), queue.calls)
    }

    @Test fun `a network change without a held error does nothing`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2")
        cacheProgressive("2")

        assertEquals(OfflineAction.None, skipper(queue).onNetworkChanged(offline))
        assertTrue(queue.calls.isEmpty())
    }

    @Test fun `coming back online with a held error does nothing`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2").apply { playerError = networkError() }
        cacheProgressive("2")

        assertEquals(OfflineAction.None, skipper(queue).onNetworkChanged(online))
        assertTrue(queue.calls.isEmpty())
    }

    // ---- status ----

    @Test fun `handled actions mark the status`() {
        val queue = FakePlayerQueue("TIDAL:1", "TIDAL:2", "TIDAL:3")
        val skipper = skipper(queue)
        network = online
        skipper.onItemStarting()
        assertFalse(status.handledRecently())

        network = offline
        cacheProgressive("3")
        skipper.onItemStarting()
        assertTrue(status.handledRecently())

        now += 10_000
        assertFalse(status.handledRecently())
        skipper.onError(networkError())
        assertTrue(status.handledRecently())
    }

    @Test fun `findSkipTarget takes the first playable index in order`() {
        assertEquals(4, findSkipTarget(listOf(2, 4, 1)) { it != 2 })
        assertEquals(null, findSkipTarget(listOf(2, 3)) { false })
        assertEquals(null, findSkipTarget(emptyList()) { true })
    }
}

/** A queue of [mediaIds] on their stable URIs, played in order, that records what's done to it. */
private class FakePlayerQueue(
    vararg ids: String,
    current: Int = 0,
    position: Long = 0,
) : PlayerQueue {
    val mediaIds = ids.toList()
    val uris: MutableList<String?> = ids.map { id -> tidalTrackIdOf(id)?.let { "auxen://tidal/$it" } }.toMutableList()
    val calls = mutableListOf<String>()
    var repeatAll = false

    override var currentIndex = current
    override var positionMs = position
    override var playWhenReady = true
    override var playerError: PlaybackException? = null

    override fun mediaIdAt(index: Int) = mediaIds[index]

    override fun uriAt(index: Int) = uris[index]

    override fun playOrderFrom(index: Int): List<Int> {
        val ahead = (index until mediaIds.size).toList()
        return if (repeatAll) ahead + (0 until index) else ahead
    }

    override fun replaceUri(index: Int, uri: String, mimeType: String?) {
        calls += "replaceUri($index, $uri, $mimeType)"
        uris[index] = uri
    }

    override fun seekTo(index: Int, positionMs: Long) {
        calls += "seekTo($index, $positionMs)"
        currentIndex = index
        this.positionMs = positionMs
    }

    override fun prepareAndPlay() {
        calls += "prepareAndPlay"
        playerError = null
        playWhenReady = true
    }

    override fun pause() {
        calls += "pause"
        playWhenReady = false
    }
}
