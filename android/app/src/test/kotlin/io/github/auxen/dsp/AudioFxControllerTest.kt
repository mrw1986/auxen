package io.github.auxen.dsp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioFxControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runBlocking {
        // AudioFxController is a process-wide `object`; wipe both its in-memory
        // state and the shared DataStore file so each test starts from a clean
        // slate regardless of JUnit method ordering.
        AudioFxController.resetForTest()
        context.audioFxDataStore.edit { it.clear() }
        Unit
    }

    @Test
    fun `state flows expose defaults before initialize`() {
        assertEquals(BassBoostState(), AudioFxController.bassBoostState.value)
        assertEquals(BalanceState(), AudioFxController.balanceState.value)
        assertEquals(LimiterState(), AudioFxController.limiterState.value)
        assertEquals(ReplayGainState(), AudioFxController.replayGainState.value)
        assertEquals(ReverbState(), AudioFxController.reverbState.value)
        assertEquals(VirtualizerState(), AudioFxController.virtualizerState.value)
    }

    @Test
    fun `updating one effect persists only that effect across a fresh initialize`() = runBlocking {
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()

        val boosted = BassBoostState(enabled = true, freqHz = 120.0, gainDb = 9.0)
        AudioFxController.updateBassBoost(boosted)
        AudioFxController.awaitPersisted()

        // Drop all in-memory state, then reload purely from DataStore.
        AudioFxController.resetForTest()
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()

        assertEquals(boosted, AudioFxController.bassBoostState.value)
        assertEquals(BalanceState(), AudioFxController.balanceState.value)
        assertEquals(LimiterState(), AudioFxController.limiterState.value)
        assertEquals(ReplayGainState(), AudioFxController.replayGainState.value)
    }

    @Test
    fun `a corrupt key falls back to that effect's defaults without disturbing the others`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val boosted = BassBoostState(enabled = true, freqHz = 150.0, gainDb = 3.0)
        context.audioFxDataStore.edit {
            it[KEY_BASS_BOOST] = json.encodeToString(BassBoostState.serializer(), boosted)
            it[KEY_LIMITER] = "{ this is not valid json at all"
        }

        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()

        // Limiter's stored value is garbage -> falls back to its own defaults.
        assertEquals(LimiterState(), AudioFxController.limiterState.value)
        // Bass boost's stored value is intact and still loads.
        assertEquals(boosted, AudioFxController.bassBoostState.value)
    }

    @Test
    fun `attachBalance replays current state and receives later updates`() = runBlocking {
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()
        AudioFxController.updateBalance(BalanceState(enabled = true, balance = 0.5f))

        val received = mutableListOf<BalanceState>()
        AudioFxController.attachBalance { received.add(it) }

        // Immediate replay of the current state on attach.
        assertEquals(BalanceState(enabled = true, balance = 0.5f), received.last())

        AudioFxController.updateBalance(BalanceState(enabled = true, balance = -0.5f))
        assertEquals(BalanceState(enabled = true, balance = -0.5f), received.last())
    }

    @Test
    fun `a second attachBalance replaces the first, not adds to it`() = runBlocking {
        // Final-review fix round, Important #3: attachX used to append to a
        // list forever -- a service restart calling attachX again with a
        // fresh processor left the OLD processor from the previous service
        // lifecycle still receiving updates alongside the new one. The
        // single-applier redesign makes "replace" the only possible outcome.
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()

        val firstReceived = mutableListOf<BalanceState>()
        val secondReceived = mutableListOf<BalanceState>()
        AudioFxController.attachBalance { firstReceived.add(it) }
        AudioFxController.attachBalance { secondReceived.add(it) }

        AudioFxController.updateBalance(BalanceState(enabled = true, balance = 0.75f))

        // The second (current) applier gets replay + the update.
        assertEquals(BalanceState(enabled = true, balance = 0.75f), secondReceived.last())
        // The first (orphaned) applier received only its own initial replay
        // -- never the update, proving it was detached, not just "also called".
        assertEquals(1, firstReceived.size)
    }

    @Test
    fun `attach from within a listener during set does not throw`() = runBlocking {
        // Final-review fix round, item 8a: regression coverage for the
        // original 04da76e re-entrancy fix, adapted to the new single-
        // applier design. The original hazard (CopyOnWriteArrayList
        // protecting a `listeners.forEach` iteration from a concurrent
        // `attachX` mutating that same list mid-iteration) is now
        // structurally impossible -- `set()` invokes a single field, not an
        // iteration -- but this pins that a listener re-attaching (a
        // DIFFERENT listener) from within its own invocation still
        // completes cleanly and the new listener is live immediately.
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()

        var reentrantReceived: BalanceState? = null
        AudioFxController.attachBalance { state ->
            if (state.balance != 0f) {
                AudioFxController.attachBalance { inner -> reentrantReceived = inner }
            }
        }

        AudioFxController.updateBalance(BalanceState(enabled = true, balance = 0.25f))
        assertEquals(BalanceState(enabled = true, balance = 0.25f), reentrantReceived)
    }

    @Test
    fun `back-to-back updates of two different effects both persist before awaitPersisted returns`() = runBlocking {
        // Final-review fix round, item 8b: regression coverage for the
        // other half of 04da76e's fix (per-slot persistJob + awaitPersisted
        // joining all four) -- still correct after the item 6 persist-
        // chaining change (chaining is PER-SLOT, so two different effects'
        // writes remain fully independent of each other).
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()

        val boostedBass = BassBoostState(enabled = true, freqHz = 100.0, gainDb = 4.0)
        val boostedBalance = BalanceState(enabled = true, balance = -0.75f)
        AudioFxController.updateBassBoost(boostedBass)
        AudioFxController.updateBalance(boostedBalance)
        AudioFxController.awaitPersisted()

        AudioFxController.resetForTest()
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()

        assertEquals(boostedBass, AudioFxController.bassBoostState.value)
        assertEquals(boostedBalance, AudioFxController.balanceState.value)
    }

    @Test
    fun `updating reverb and virtualizer persists independently across a fresh initialize`() = runBlocking {
        // DSP-b Task 1: controller slot round-trip for the two new platform-
        // effect states, mirroring the existing bass-boost round-trip test.
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()

        val reverbOn = ReverbState(enabled = true, preset = 4) // MEDIUMHALL
        val virtualizerOn = VirtualizerState(enabled = true, strength = 750)
        AudioFxController.updateReverb(reverbOn)
        AudioFxController.updateVirtualizer(virtualizerOn)
        AudioFxController.awaitPersisted()

        AudioFxController.resetForTest()
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()

        assertEquals(reverbOn, AudioFxController.reverbState.value)
        assertEquals(virtualizerOn, AudioFxController.virtualizerState.value)
        // Untouched effects still restore to their own defaults.
        assertEquals(BassBoostState(), AudioFxController.bassBoostState.value)
    }

    @Test
    fun `attachReverb replays current state and receives later updates`() = runBlocking {
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()
        AudioFxController.updateReverb(ReverbState(enabled = true, preset = 2))

        val received = mutableListOf<ReverbState>()
        AudioFxController.attachReverb { received.add(it) }
        assertEquals(ReverbState(enabled = true, preset = 2), received.last())

        AudioFxController.updateReverb(ReverbState(enabled = true, preset = 6))
        assertEquals(ReverbState(enabled = true, preset = 6), received.last())
    }

    @Test
    fun `rapid same-slot updates persist the final value, not a stale earlier one`() = runBlocking {
        // Basic correctness check for the item 6 persist-chaining fix: two
        // updates to the SAME effect in quick succession must not let an
        // earlier queued write clobber the later one once both land.
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()

        AudioFxController.updateBassBoost(BassBoostState(enabled = true, freqHz = 60.0, gainDb = 2.0))
        val final = BassBoostState(enabled = true, freqHz = 200.0, gainDb = 8.0)
        AudioFxController.updateBassBoost(final)
        AudioFxController.awaitPersisted()

        AudioFxController.resetForTest()
        AudioFxController.initialize(context)
        AudioFxController.awaitInitialized()

        assertEquals(final, AudioFxController.bassBoostState.value)
    }
}
