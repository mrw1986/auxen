package io.github.auxen.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SleepTimerControllerTest {

    @Before
    fun setUp() {
        SleepTimerController.resetForTest()
    }

    @After
    fun tearDown() {
        SleepTimerController.resetForTest()
    }

    @Test
    fun `state starts unarmed`() {
        assertEquals(SleepTimerState(), SleepTimerController.state.value)
    }

    @Test
    fun `start arms the timer minutes-from-now per the injected clock`() {
        SleepTimerController.clock = { 100_000L }
        SleepTimerController.start(minutes = 15, finishTrack = false)
        val state = SleepTimerController.state.value
        assertEquals(100_000L + 15 * 60_000L, state.endElapsedRealtime)
        assertEquals(false, state.finishTrack)
    }

    @Test
    fun `start with finishTrack true is reflected in state`() {
        SleepTimerController.clock = { 0L }
        SleepTimerController.start(minutes = 30, finishTrack = true)
        assertTrue(SleepTimerController.state.value.finishTrack)
    }

    @Test
    fun `cancel resets to unarmed defaults`() {
        SleepTimerController.clock = { 0L }
        SleepTimerController.start(minutes = 15, finishTrack = true)
        SleepTimerController.cancel()
        assertEquals(SleepTimerState(), SleepTimerController.state.value)
    }

    @Test
    fun `starting again while armed replaces the previous timer, not stacks`() {
        SleepTimerController.clock = { 0L }
        SleepTimerController.start(minutes = 15, finishTrack = false)
        SleepTimerController.start(minutes = 45, finishTrack = true)
        val state = SleepTimerController.state.value
        assertEquals(45 * 60_000L, state.endElapsedRealtime)
        assertTrue(state.finishTrack)
    }

    // -- remainingMillis extension: countdown math, fully clock-injected,
    // no SystemClock/Date.now anywhere in this test class --

    @Test
    fun `remainingMillis is null when unarmed`() {
        assertNull(SleepTimerState().remainingMillis { 0L })
    }

    @Test
    fun `remainingMillis computes time left from the injected clock`() {
        val state = SleepTimerState(endElapsedRealtime = 100_000L)
        assertEquals(40_000L, state.remainingMillis { 60_000L })
    }

    @Test
    fun `remainingMillis goes negative past expiry rather than clamping`() {
        // Clamping (if wanted) is the CALLER's job -- display code coerces to
        // 0 -- the pure math function reports the true (possibly negative)
        // delta so callers can distinguish "just expired" from "long overdue".
        val state = SleepTimerState(endElapsedRealtime = 100_000L)
        assertEquals(-5_000L, state.remainingMillis { 105_000L })
    }
}
