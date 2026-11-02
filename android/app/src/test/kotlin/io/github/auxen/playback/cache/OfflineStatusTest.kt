package io.github.auxen.playback.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineStatusTest {

    private var now = 10_000L
    private val status = OfflineStatus { now }

    @Test fun `handledRecently is true within 5 s and false after`() {
        assertFalse(status.handledRecently())

        status.markHandled()
        now += 4_999
        assertTrue(status.handledRecently())

        now += 1
        assertFalse(status.handledRecently())
    }

    @Test fun `a custom window is honored`() {
        status.markHandled()
        now += 1_500

        assertTrue(status.handledRecently(windowMillis = 2_000))
        assertFalse(status.handledRecently(windowMillis = 1_000))
    }

    @Test fun `clear hides the message`() {
        assertFalse(status.nothingCachedAhead.value)

        status.showNothingCachedAhead()
        assertTrue(status.nothingCachedAhead.value)

        status.clear()
        assertFalse(status.nothingCachedAhead.value)
    }

    @Test fun `each handled mark bumps the handled count`() {
        assertEquals(0L, status.handledCount.value)

        status.markHandled()
        status.markHandled()

        assertEquals(2L, status.handledCount.value)
    }

    @Test fun `clear leaves the handled mark alone`() {
        status.markHandled()
        status.clear()

        assertTrue(status.handledRecently())
    }
}
