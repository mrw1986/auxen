package io.github.auxen.ui.components

import io.github.auxen.dsp.ReverbState
import org.junit.Assert.assertEquals
import org.junit.Test

class FxSectionsMathTest {
    // -- reverbStateForEnableToggle: platform effects fix (user-confirmed
    // device report, 2026-07-13) -- enabling reverb while its preset is
    // still PRESET_NONE (0) silently produces zero effect, since the
    // preset dropdown is a separate control from the enable switch. --

    @Test
    fun `reverbStateForEnableToggle picks a real preset when enabling from PRESET_NONE`() {
        val result = reverbStateForEnableToggle(ReverbState(preset = 0), enabling = true)
        assertEquals(true, result.enabled)
        assertEquals(1, result.preset) // PRESET_SMALLROOM
    }

    @Test
    fun `reverbStateForEnableToggle leaves an already-real preset untouched when enabling`() {
        val result = reverbStateForEnableToggle(ReverbState(preset = 4), enabling = true)
        assertEquals(true, result.enabled)
        assertEquals(4, result.preset)
    }

    @Test
    fun `reverbStateForEnableToggle disabling never touches the preset`() {
        val result = reverbStateForEnableToggle(ReverbState(preset = 0, enabled = true), enabling = false)
        assertEquals(false, result.enabled)
        assertEquals(0, result.preset) // disabling with PRESET_NONE stays PRESET_NONE -- no effect to pick
    }

    @Test
    fun `reverbStateForEnableToggle disabling with a real preset leaves it in place for next time`() {
        val result = reverbStateForEnableToggle(ReverbState(preset = 3, enabled = true), enabling = false)
        assertEquals(false, result.enabled)
        assertEquals(3, result.preset)
    }
    @Test
    fun `snapBalanceToCenter snaps values within the threshold`() {
        assertEquals(0f, snapBalanceToCenter(0.03f))
        assertEquals(0f, snapBalanceToCenter(-0.03f))
        assertEquals(0f, snapBalanceToCenter(0f))
    }

    @Test
    fun `snapBalanceToCenter leaves values outside the threshold untouched`() {
        assertEquals(0.5f, snapBalanceToCenter(0.5f))
        assertEquals(-1f, snapBalanceToCenter(-1f))
        assertEquals(0.05f, snapBalanceToCenter(0.05f)) // boundary is exclusive
    }

    @Test
    fun `balanceReadout reports Center at and near zero`() {
        assertEquals(BalanceReadout.Center, balanceReadout(0f))
        // Rounds to 0% -> Center, not Left(0)/Right(0).
        assertEquals(BalanceReadout.Center, balanceReadout(0.004f))
        assertEquals(BalanceReadout.Center, balanceReadout(-0.004f))
    }

    @Test
    fun `balanceReadout reports Left for negative values`() {
        assertEquals(BalanceReadout.Left(40), balanceReadout(-0.4f))
        assertEquals(BalanceReadout.Left(100), balanceReadout(-1f))
    }

    @Test
    fun `balanceReadout reports Right for positive values`() {
        assertEquals(BalanceReadout.Right(25), balanceReadout(0.25f))
        assertEquals(BalanceReadout.Right(100), balanceReadout(1f))
    }

    @Test
    fun `balanceReadout rounds to the nearest percent`() {
        assertEquals(BalanceReadout.Right(33), balanceReadout(0.333f))
        assertEquals(BalanceReadout.Left(33), balanceReadout(-0.334f))
    }
}
