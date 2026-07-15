package io.github.auxen.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fix round (review of commit 6426194), Minor: apply-site clamping for the
 * two platform-effect parameters -- persisted [io.github.auxen.dsp.ReverbState]/
 * [io.github.auxen.dsp.VirtualizerState] values could hold anything (a stale
 * DataStore entry from a future app version with a wider range, manual
 * tampering, ...), and the platform APIs (`PresetReverb`/`Virtualizer`) don't
 * themselves validate out-of-range values the way this app would want.
 * Mirrors the DSP-a apply-site validation precedent. Extracted as pure
 * top-level functions specifically so they're unit-testable without any
 * `PlaybackService`/Robolectric/platform-effect-object involvement.
 */
class PlatformEffectClampTest {

    @Test
    fun `reverb preset within range passes through unchanged`() {
        assertEquals(4.toShort(), clampReverbPreset(4))
    }

    @Test
    fun `reverb preset below zero clamps to zero`() {
        assertEquals(0.toShort(), clampReverbPreset(-5))
    }

    @Test
    fun `reverb preset above six clamps to six`() {
        assertEquals(6.toShort(), clampReverbPreset(99))
    }

    @Test
    fun `virtualizer strength within range passes through unchanged`() {
        assertEquals(750.toShort(), clampVirtualizerStrength(750))
    }

    @Test
    fun `virtualizer strength below zero clamps to zero`() {
        assertEquals(0.toShort(), clampVirtualizerStrength(-100))
    }

    @Test
    fun `virtualizer strength above 1000 clamps to 1000`() {
        assertEquals(1000.toShort(), clampVirtualizerStrength(50_000))
    }
}
