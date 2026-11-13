package io.github.auxen.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Bit-Perfect -> float-output mapping the audio sink is built from
 * ([bitPerfectEnablesFloatOutput], the seam `EqRenderersFactory.buildAudioSink`
 * calls). Pure top-level function, no Robolectric/Media3 sink involvement --
 * same "extract the decision, test it directly" pattern as
 * [PlatformEffectClampTest] and [ProcessorChainOrderTest].
 *
 * The invariant this guards is load-bearing: `false` (the default) MUST select
 * the INTEGER path so the DSP chain runs -- float-on silently skips the whole
 * chain in Media3 1.5.1, which was the "DSP does nothing" bug. A refactor that
 * accidentally inverted this mapping would regress every DSP effect for every
 * default user, and this test would catch it.
 */
class BitPerfectFloatOutputTest {

    @Test
    fun `bit-perfect off (default) selects integer output so the DSP chain runs`() {
        assertFalse(bitPerfectEnablesFloatOutput(false))
    }

    @Test
    fun `bit-perfect on selects float output (untouched hi-res, DSP auto-bypassed)`() {
        assertTrue(bitPerfectEnablesFloatOutput(true))
    }
}
