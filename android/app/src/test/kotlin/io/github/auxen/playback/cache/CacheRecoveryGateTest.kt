package io.github.auxen.playback.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheRecoveryGateTest {

    private var now = 1_000L
    private val gate = CacheRecoveryGate(cooldownMillis = 60_000, clock = { now })

    @Test
    fun `the first recovery for a track is allowed`() {
        assertTrue(gate.tryAcquire("42"))
    }

    @Test
    fun `a second recovery within the cooldown is blocked`() {
        gate.tryAcquire("42")
        now += 59_999

        assertFalse(gate.tryAcquire("42"))
    }

    @Test
    fun `a recovery after the cooldown is allowed again`() {
        gate.tryAcquire("42")
        now += 30_000
        // A blocked attempt doesn't push the cooldown back.
        assertFalse(gate.tryAcquire("42"))
        now += 30_000

        assertTrue(gate.tryAcquire("42"))
        now += 1
        assertFalse(gate.tryAcquire("42"))
    }

    @Test
    fun `each track has its own cooldown`() {
        assertTrue(gate.tryAcquire("42"))
        now += 10_000
        assertTrue(gate.tryAcquire("7"))

        assertFalse(gate.tryAcquire("42"))
        assertFalse(gate.tryAcquire("7"))
        now += 50_000
        assertTrue(gate.tryAcquire("42"))
        assertFalse(gate.tryAcquire("7"))
    }
}
