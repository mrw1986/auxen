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
}
