package io.github.auxen.playback.cache

import androidx.media3.common.PlaybackException
import io.github.auxen.playback.cache.AutoResumeEvent.ItemChanged
import io.github.auxen.playback.cache.AutoResumeEvent.NetworkBack
import io.github.auxen.playback.cache.AutoResumeEvent.OfflinePaused
import io.github.auxen.playback.cache.AutoResumeEvent.QueueChanged
import io.github.auxen.playback.cache.AutoResumeEvent.SleepTimerFired
import io.github.auxen.playback.cache.AutoResumeEvent.UserPlay
import io.github.auxen.playback.cache.AutoResumeState.Idle
import io.github.auxen.playback.cache.AutoResumeState.Waiting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoResumeTest {

    // Paused at 0 on song 3, "TIDAL:7".
    private val waiting = reduceAutoResume(Idle, OfflinePaused(atMillis = 0, index = 3, mediaId = "TIDAL:7")).state

    @Test fun `an offline pause starts waiting on the stopped song`() {
        assertEquals(Waiting(pausedAtMillis = 0, index = 3, mediaId = "TIDAL:7"), waiting)
    }

    @Test fun `network back within 30 minutes resumes on the stopped song`() {
        assertEquals(AutoResumeStep(Idle, resumeIndex = 3), reduceAutoResume(waiting, NetworkBack(600_000)))
    }

    @Test fun `network back at exactly 30 minutes still resumes`() {
        assertEquals(AutoResumeStep(Idle, resumeIndex = 3), reduceAutoResume(waiting, NetworkBack(1_800_000)))
    }

    @Test fun `network back after 30 minutes stays paused`() {
        assertEquals(AutoResumeStep(Idle), reduceAutoResume(waiting, NetworkBack(1_800_001)))
    }

    @Test fun `user play cancels`() {
        assertEquals(AutoResumeStep(Idle), reduceAutoResume(waiting, UserPlay))
    }

    @Test fun `queue change cancels`() {
        assertEquals(AutoResumeStep(Idle), reduceAutoResume(waiting, QueueChanged))
    }

    @Test fun `moving to a different song cancels`() {
        assertEquals(AutoResumeStep(Idle), reduceAutoResume(waiting, ItemChanged("TIDAL:8")))
    }

    @Test fun `a swap of the same song does not cancel`() {
        assertEquals(AutoResumeStep(waiting), reduceAutoResume(waiting, ItemChanged("TIDAL:7")))
    }

    @Test fun `a sleep timer firing cancels`() {
        assertEquals(AutoResumeStep(Idle), reduceAutoResume(waiting, SleepTimerFired))
        assertEquals(AutoResumeStep(Idle), reduceAutoResume(Idle, SleepTimerFired))
    }

    @Test fun `network back while idle does nothing`() {
        assertEquals(AutoResumeStep(Idle), reduceAutoResume(Idle, NetworkBack(600_000)))
    }

    @Test fun `a second offline pause restarts the clock`() {
        val again = reduceAutoResume(waiting, OfflinePaused(atMillis = 1_000_000, index = 5, mediaId = "TIDAL:9")).state

        assertEquals(Waiting(pausedAtMillis = 1_000_000, index = 5, mediaId = "TIDAL:9"), again)
        // 45 minutes after the first pause, but only 28 after the second.
        assertEquals(AutoResumeStep(Idle, resumeIndex = 5), reduceAutoResume(again, NetworkBack(2_700_000)))
    }

    @Test fun `controller calls onResume once and stops waiting`() {
        val resumes = mutableListOf<Pair<Int, String>>()
        val controller = AutoResumeController(clock = { 0 }) { index, mediaId -> resumes += index to mediaId }

        controller.on(OfflinePaused(atMillis = 0, index = 3, mediaId = "TIDAL:7"))
        assertTrue(controller.isWaiting)

        controller.on(NetworkBack(600_000))
        controller.on(NetworkBack(600_001))

        assertEquals(listOf(3 to "TIDAL:7"), resumes)
        assertFalse(controller.isWaiting)
    }

    @Test fun `the controller is idle before resume runs, so the play it starts cancels nothing`() {
        lateinit var controller: AutoResumeController
        var waitingDuringResume: Boolean? = null
        controller = AutoResumeController(clock = { 0 }) { _, _ ->
            waitingDuringResume = controller.isWaiting
            controller.on(UserPlay)
        }

        controller.on(OfflinePaused(atMillis = 0, index = 3, mediaId = "TIDAL:7"))
        controller.on(NetworkBack(1_000))

        assertEquals(false, waitingDuringResume)
        assertFalse(controller.isWaiting)
    }

    @Test fun `waiting ends once the 30 minutes run out with no network`() {
        var now = 100L
        val controller = AutoResumeController(clock = { now }) { _, _ -> }

        controller.onOfflineAction(OfflineAction.PausedNothingAhead(index = 3, mediaId = "TIDAL:7"))
        now += AUTO_RESUME_WINDOW_MILLIS
        assertTrue(controller.isWaiting)

        now += 1
        assertFalse(controller.isWaiting)
    }

    @Test fun `only the nothing-cached-ahead pause starts waiting`() {
        val controller = AutoResumeController(clock = { 0 }) { _, _ -> }

        controller.onOfflineAction(OfflineAction.None)
        controller.onOfflineAction(OfflineAction.Skipped(to = 4))
        controller.onOfflineAction(OfflineAction.ResumedFromCache(atMs = 5_000))
        assertFalse(controller.isWaiting)

        controller.onOfflineAction(OfflineAction.PausedNothingAhead(index = 3, mediaId = "TIDAL:7"))
        assertTrue(controller.isWaiting)
    }

    @Test fun `a reconnect resumes the offline pause, stamped on the controller clock`() {
        var now = 0L
        val resumes = mutableListOf<Pair<Int, String>>()
        val controller = AutoResumeController(clock = { now }) { index, mediaId -> resumes += index to mediaId }
        controller.onNetwork(online = true)

        now = 1_000
        assertFalse(controller.onNetwork(online = false))
        controller.onOfflineAction(OfflineAction.PausedNothingAhead(index = 3, mediaId = "TIDAL:7"))
        now = 1_000 + AUTO_RESUME_WINDOW_MILLIS

        assertTrue(controller.onNetwork(online = true))
        assertEquals(listOf(3 to "TIDAL:7"), resumes)
    }

    @Test fun `a reconnect past the window still counts as one but resumes nothing`() {
        var now = 0L
        val resumes = mutableListOf<Pair<Int, String>>()
        val controller = AutoResumeController(clock = { now }) { index, mediaId -> resumes += index to mediaId }
        controller.onNetwork(online = false)
        controller.onOfflineAction(OfflineAction.PausedNothingAhead(index = 3, mediaId = "TIDAL:7"))

        now = AUTO_RESUME_WINDOW_MILLIS + 1
        assertTrue(controller.onNetwork(online = true))
        assertEquals(emptyList<Pair<Int, String>>(), resumes)
    }

    @Test fun `only an offline to online change is a reconnect`() {
        val resumes = mutableListOf<Pair<Int, String>>()
        val controller = AutoResumeController(clock = { 0 }) { index, mediaId -> resumes += index to mediaId }
        controller.onOfflineAction(OfflineAction.PausedNothingAhead(index = 3, mediaId = "TIDAL:7"))

        // The first state is where the collector starts, not a change.
        assertFalse(controller.onNetwork(online = true))
        // A metered to unmetered change emits online again.
        assertFalse(controller.onNetwork(online = true))
        assertEquals(emptyList<Pair<Int, String>>(), resumes)
        assertTrue(controller.isWaiting)
    }

    @Test fun `a resume plays the stopped song from the start`() {
        val queue = RecordingQueue("TIDAL:1", "TIDAL:2", "TIDAL:3")

        resumeOfflinePause(queue, index = 1, mediaId = "TIDAL:2")

        assertEquals(listOf("seekTo(1, 0)", "prepareAndPlay"), queue.calls)
    }

    @Test fun `a resume does nothing when another song is at its index`() {
        val queue = RecordingQueue("TIDAL:1", "TIDAL:5", "TIDAL:3")

        resumeOfflinePause(queue, index = 1, mediaId = "TIDAL:2")

        assertEquals(emptyList<String>(), queue.calls)
    }

    @Test fun `a resume does nothing when the queue no longer reaches its index`() {
        val queue = RecordingQueue("TIDAL:1")

        resumeOfflinePause(queue, index = 1, mediaId = "TIDAL:2")

        assertEquals(emptyList<String>(), queue.calls)
    }
}

/** A queue of [ids] that reads an index outside it as "", like [PlayerQueueAdapter], and records commands. */
private class RecordingQueue(vararg ids: String) : PlayerQueue {
    private val mediaIds = ids.toList()
    val calls = mutableListOf<String>()

    override val currentIndex = 0
    override val positionMs = 0L
    override val playWhenReady = false
    override val playerError: PlaybackException? = null

    override fun mediaIdAt(index: Int) = mediaIds.getOrNull(index).orEmpty()

    override fun uriAt(index: Int): String? = null

    override fun playOrderFrom(index: Int): List<Int> = emptyList()

    override fun replaceUri(index: Int, uri: String, mimeType: String?) {
        calls += "replaceUri($index)"
    }

    override fun seekTo(index: Int, positionMs: Long) {
        calls += "seekTo($index, $positionMs)"
    }

    override fun prepareAndPlay() {
        calls += "prepareAndPlay"
    }

    override fun pause() {
        calls += "pause"
    }
}
