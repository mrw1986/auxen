package io.github.auxen.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {
    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("3:47", formatDuration(227.0))
        assertEquals("0:05", formatDuration(5.4))
        assertEquals("1:00", formatDuration(60.0))
    }

    @Test
    fun longDurationsKeepMinuteCount() {
        assertEquals("104:09", formatDuration(6249.0))
    }

    @Test
    fun unknownDurationsRenderPlaceholder() {
        assertEquals("–:––", formatDuration(null))
        assertEquals("–:––", formatDuration(0.0))
        assertEquals("–:––", formatDuration(-3.0))
    }
}
