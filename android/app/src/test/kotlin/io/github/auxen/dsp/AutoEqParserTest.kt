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

    @Test
    fun `rejects a Q 0 line rather than silently importing a NaN filter`() {
        // Final-review fix round, Important #1: Q=0 produces NaN biquad
        // coefficients in ParametricEqProcessor (see ParametricEqProcessorTest);
        // catching it here, at parse time, means importAutoEq's existing
        // runCatching wrapper turns the whole import into a failed Result
        // before EqController.setState/persist is ever called -- the bad
        // profile never reaches DataStore, not even partially.
        assertThrows(IllegalArgumentException::class.java) {
            AutoEqParser.parse("Filter 1: ON PK Fc 105 Hz Gain -2.4 dB Q 0")
        }
    }

    @Test
    fun `rejects a Fc 0 line rather than silently importing a degenerate filter`() {
        assertThrows(IllegalArgumentException::class.java) {
            AutoEqParser.parse("Filter 1: ON PK Fc 0 Hz Gain -2.4 dB Q 0.70")
        }
    }

    @Test
    fun `rejects a shelf line with an omitted Q that resolves to zero -- guards the default too`() {
        // Sanity check that the validation runs AFTER the DEFAULT_SHELF_Q
        // fallback is applied, not before -- an omitted Q always resolves to
        // the (positive) shelf default today, so this profile is actually
        // valid; asserts it parses cleanly rather than false-rejecting.
        val state = AutoEqParser.parse("Filter 1: ON LSC Fc 105 Hz Gain 2.0 dB")
        assertTrue(state.filters.single().q > 0.0)
    }
}
