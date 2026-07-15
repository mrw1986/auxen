package io.github.auxen.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqStateTest {

    @Test
    fun `fromBands builds one peaking filter per band`() {
        val state = EqState.fromBands(EqState.PRESETS.getValue("Rock"))
        assertEquals(EqState.NUM_BANDS, state.filters.size)
        assertTrue(state.filters.all { it.type == FilterType.PEAKING })
        assertEquals(EqState.BAND_FREQUENCIES, state.filters.map { it.freqHz })
    }

    @Test
    fun `auto preamp offsets the largest boost`() {
        val state = EqState.fromBands(EqState.PRESETS.getValue("Bass Boost"))
        assertEquals(-6.0, state.preampDb, 1e-9)
    }

    @Test
    fun `flat preset needs no preamp`() {
        val state = EqState.fromBands(EqState.PRESETS.getValue("Flat"))
        assertEquals(0.0, state.preampDb, 1e-9)
    }

    @Test
    fun `band gains are clamped to plus-minus 12 dB`() {
        val state = EqState.fromBands(listOf(99.0, -99.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(12.0, state.filters[0].gainDb, 1e-9)
        assertEquals(-12.0, state.filters[1].gainDb, 1e-9)
    }

    @Test
    fun `presets match the desktop app`() {
        // Same ten presets as auxen/equalizer.py, same order.
        assertEquals(
            listOf(
                "Flat", "Bass Boost", "Treble Boost", "Vocal", "Rock",
                "Pop", "Jazz", "Classical", "Electronic", "Hip-Hop",
            ),
            EqState.PRESETS.keys.toList(),
        )
    }
}
