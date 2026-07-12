package io.github.auxen.dsp

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * A single DataStore backing every audio effect, but with one string
 * preference key per effect (see [KEY_BASS_BOOST] … [KEY_REPLAY_GAIN]) so that
 * updating one effect never rewrites another, and a corrupt value for one
 * effect falls back to that effect's defaults without touching the rest.
 */
internal val Context.audioFxDataStore by preferencesDataStore(name = "audio_fx")

internal val KEY_BASS_BOOST = stringPreferencesKey("fx_bass_boost")
internal val KEY_BALANCE = stringPreferencesKey("fx_balance")
internal val KEY_LIMITER = stringPreferencesKey("fx_limiter")
internal val KEY_REPLAY_GAIN = stringPreferencesKey("fx_replay_gain")

/**
 * Single source of truth for the per-effect DSP settings, shared by the UI and
 * the playback service (both run in the app process). Mirrors [EqController],
 * but persists each effect independently under its own DataStore key.
 *
 * Each effect X in {bassBoost, balance, limiter, replayGain} exposes:
 *  - `val xState: StateFlow<XState>` — the current state.
 *  - `fun updateX(state)` — sets it and persists that effect's key only.
 *  - `fun attachX(apply)` — replays the current state into `apply`
 *    immediately and forwards every subsequent update to it.
 *
 * [initialize] restores all four states independently: a malformed stored JSON
 * for one effect resolves to that effect's defaults and leaves the others
 * untouched.
 */
object AudioFxController {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Per-effect state holder: owns the [MutableStateFlow], its persistence
     * key/serializer, and the list of listeners attached via `attachX`. Keeping
     * the four effects behind one small type keeps persistence and listener
     * fan-out identical (and independent) across effects.
     */
    private class FxSlot<T>(
        val key: Preferences.Key<String>,
        val serializer: KSerializer<T>,
        private val default: T,
    ) {
        val flow = MutableStateFlow(default)
        private val listeners = mutableListOf<(T) -> Unit>()

        fun attach(apply: (T) -> Unit) {
            listeners += apply
            apply(flow.value)
        }

        fun set(value: T) {
            flow.value = value
            listeners.forEach { it(value) }
        }

        /** Decode [stored]; malformed JSON leaves the current value in place. */
        fun restore(stored: String, json: Json) {
            runCatching { json.decodeFromString(serializer, stored) }
                .onSuccess { set(it) }
        }

        fun reset() {
            listeners.clear()
            flow.value = default
        }
    }

    private val bassBoost = FxSlot(KEY_BASS_BOOST, BassBoostState.serializer(), BassBoostState())
    private val balance = FxSlot(KEY_BALANCE, BalanceState.serializer(), BalanceState())
    private val limiter = FxSlot(KEY_LIMITER, LimiterState.serializer(), LimiterState())
    private val replayGain = FxSlot(KEY_REPLAY_GAIN, ReplayGainState.serializer(), ReplayGainState())

    val bassBoostState: StateFlow<BassBoostState> = bassBoost.flow
    val balanceState: StateFlow<BalanceState> = balance.flow
    val limiterState: StateFlow<LimiterState> = limiter.flow
    val replayGainState: StateFlow<ReplayGainState> = replayGain.flow

    private var appContext: Context? = null

    @Volatile
    private var initJob: Job? = null

    @Volatile
    private var persistJob: Job? = null

    fun attachBassBoost(apply: (BassBoostState) -> Unit) = bassBoost.attach(apply)
    fun attachBalance(apply: (BalanceState) -> Unit) = balance.attach(apply)
    fun attachLimiter(apply: (LimiterState) -> Unit) = limiter.attach(apply)
    fun attachReplayGain(apply: (ReplayGainState) -> Unit) = replayGain.attach(apply)

    fun updateBassBoost(state: BassBoostState) = update(bassBoost, state)
    fun updateBalance(state: BalanceState) = update(balance, state)
    fun updateLimiter(state: LimiterState) = update(limiter, state)
    fun updateReplayGain(state: ReplayGainState) = update(replayGain, state)

    private fun <T> update(slot: FxSlot<T>, state: T) {
        slot.set(state)
        val ctx = appContext ?: return
        persistJob = scope.launch {
            ctx.audioFxDataStore.edit { it[slot.key] = json.encodeToString(slot.serializer, state) }
        }
    }

    /** Load persisted state for every effect independently; safe to call more than once. */
    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        initJob = scope.launch {
            val prefs = app.audioFxDataStore.data.first()
            prefs[bassBoost.key]?.let { bassBoost.restore(it, json) }
            prefs[balance.key]?.let { balance.restore(it, json) }
            prefs[limiter.key]?.let { limiter.restore(it, json) }
            prefs[replayGain.key]?.let { replayGain.restore(it, json) }
        }
    }

    /**
     * Suspends until the DataStore restore started by [initialize] has settled,
     * so a caller can read fully-restored states instead of racing the load.
     * Returns immediately if [initialize] was never called or already finished.
     */
    internal suspend fun awaitInitialized() {
        initJob?.join()
    }

    /** Suspends until the most recent `updateX` persist has been written. */
    internal suspend fun awaitPersisted() {
        persistJob?.join()
    }

    /**
     * Test-only: reset the singleton's in-memory state, detach all listeners,
     * and forget the app context so a subsequent [initialize] re-runs. Does not
     * touch DataStore — persisted state survives, which is what the persistence
     * round-trip test relies on.
     */
    internal fun resetForTest() {
        initJob?.cancel()
        persistJob?.cancel()
        initJob = null
        persistJob = null
        appContext = null
        bassBoost.reset()
        balance.reset()
        limiter.reset()
        replayGain.reset()
    }
}
