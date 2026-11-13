package io.github.auxen.playback.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueChangeTrackerTest {

    private val tracker = QueueChangeTracker()

    @Test fun `the first queue is a change and the same ids again are not`() {
        assertTrue(tracker.changed(listOf("TIDAL:1", "TIDAL:2")))

        // A cache or DASH swap fires PLAYLIST_CHANGED with the same ids.
        assertFalse(tracker.changed(listOf("TIDAL:1", "TIDAL:2")))
    }

    @Test fun `added, moved and removed songs are changes`() {
        tracker.changed(listOf("TIDAL:1", "TIDAL:2"))

        assertTrue(tracker.changed(listOf("TIDAL:1", "TIDAL:2", "LOCAL:3")))
        assertTrue(tracker.changed(listOf("LOCAL:3", "TIDAL:1", "TIDAL:2")))
        assertTrue(tracker.changed(listOf("TIDAL:1", "TIDAL:2")))
        assertEquals(listOf("TIDAL:1", "TIDAL:2"), tracker.lastKeys)
    }

    @Test fun `an empty queue at the start is not a change`() {
        assertFalse(tracker.changed(emptyList()))
    }

    @Test fun `seeding sets the ids without counting a change`() {
        tracker.seed(listOf("TIDAL:7"))

        assertEquals(listOf("TIDAL:7"), tracker.lastKeys)
        assertFalse(tracker.changed(listOf("TIDAL:7")))
    }
}
