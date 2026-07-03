package io.github.auxen.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqParserTest {

    private val sennheiserHd650 = """
        Preamp: -6.4 dB
        Filter 1: ON LSC Fc 105 Hz Gain 5.8 dB Q 0.70
        Filter 2: ON PK Fc 154 Hz Gain -2.2 dB Q 0.53
        Filter 3: ON PK Fc 1897 Hz Gain -1.5 dB Q 2.65
        Filter 4: ON PK Fc 3229 Hz Gain 1.9 dB Q 2.20
        Filter 5: ON HSC Fc 10000 Hz Gain 3.6 dB Q 0.70
        Filter 6: OFF PK Fc 5000 Hz Gain 2.0 dB Q 1.00
    """.trimIndent()

    @Test
    fun `parses preamp and enabled filters`() {
        val state = AutoEqParser.parse(sennheiserHd650, "HD 650")
        assertEquals(-6.4, state.preampDb, 1e-9)
        assertEquals(5, state.filters.size) // OFF filter skipped
        assertEquals("HD 650", state.presetName)
        assertTrue(state.enabled)
    }

    @Test
    fun `maps filter types`() {
        val state = AutoEqParser.parse(sennheiserHd650)
        assertEquals(FilterType.LOW_SHELF, state.filters[0].type)
        assertEquals(FilterType.PEAKING, state.filters[1].type)
        assertEquals(FilterType.HIGH_SHELF, state.filters[4].type)
        assertEquals(105.0, state.filters[0].freqHz, 1e-9)
        assertEquals(-2.2, state.filters[1].gainDb, 1e-9)
        assertEquals(2.65, state.filters[2].q, 1e-9)
    }

    @Test
    fun `rejects non-profile text`() {
        assertThrows(IllegalArgumentException::class.java) {
            AutoEqParser.parse("this is not a profile")
        }
    }
}
