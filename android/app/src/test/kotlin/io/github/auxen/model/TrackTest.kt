package io.github.auxen.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {

    private fun track(source: Source, id: String) = Track(title = "Song", artist = "Artist", source = source, sourceId = id)

    @Test fun `a Tidal track whose id is cached is cached`() {
        assertTrue(track(Source.TIDAL, "42").isCachedIn(setOf("7", "42")))
    }

    @Test fun `a Tidal track whose id isn't cached is not cached`() {
        assertFalse(track(Source.TIDAL, "42").isCachedIn(setOf("7")))
    }

    @Test fun `a local track is never cached, even when its id matches`() {
        assertFalse(track(Source.LOCAL, "42").isCachedIn(setOf("42")))
    }
}
