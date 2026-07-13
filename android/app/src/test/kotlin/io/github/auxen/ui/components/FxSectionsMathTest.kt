package io.github.auxen.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FxSectionsMathTest {
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
