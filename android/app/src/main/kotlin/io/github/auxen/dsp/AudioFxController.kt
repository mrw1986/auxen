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
internal val KEY_REVERB = stringPreferencesKey("fx_reverb")
internal val KEY_VIRTUALIZER = stringPreferencesKey("fx_virtualizer")

/**
 * Single source of truth for the per-effect DSP settings, shared by the UI and
 * the playback service (both run in the app process). Mirrors [EqController],
 * but persists each effect independently under its own DataStore key.
 *
 * Each effect X in {bassBoost, balance, limiter, replayGain, reverb,
 * virtualizer} exposes:
 *  - `val xState: StateFlow<XState>` — the current state.
 *  - `fun updateX(state)` — sets it and persists that effect's key only.
 *  - `fun attachX(apply)` — replays the current state into `apply`
 *    immediately and forwards every subsequent update to it. REPLACES any
 *    previously attached applier for that effect (not additive): there is
 *    exactly one production `attachX` call site per effect (in
 *    `PlaybackService.onCreate`), and a service restart calling `attachX`
 *    again with a fresh processor instance must not also keep driving the
 *    old, now-orphaned one — mirrors [EqController.attachProcessor]'s own
 *    single-processor-field design (final-review fix round, Important #3).
 *
 * [initialize] restores all six states independently: a malformed stored JSON
 * for one effect resolves to that effect's defaults and leaves the others
 * untouched.
 *
 * [reverb] and [virtualizer] are platform effects (`android.media.audiofx`)
 * applied to the sink's audio session, not part of the in-process DSP chain
 * the other four processors belong to (see [ParametricEqProcessor]'s KDoc)
 * -- this controller still owns their state/persistence/dispatch
 * identically, `PlaybackService` just applies them differently (DSP-b Task 1).
 */
object AudioFxController {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Per-effect state holder: owns the [MutableStateFlow], its persistence
     * key/serializer, and the one applier attached via `attachX`. Keeping the
     * four effects behind one small type keeps persistence and dispatch
     * identical (and independent) across effects.
     */
    private class FxSlot<T>(
        val key: Preferences.Key<String>,
        val serializer: KSerializer<T>,
        private val default: T,
    ) {
        val flow = MutableStateFlow(default)

        /**
         * The one active listener attached via `attachX` -- see the class
         * KDoc's "REPLACES" note. `@Volatile` alone is enough here: unlike
         * the old list-based design, there is no collection to iterate,
         * so a concurrent `attach` during `set`'s dispatch can never throw
         * ConcurrentModificationException -- it's a single field write.
         */
        @Volatile
        private var applier: ((T) -> Unit)? = null

        /** The in-flight DataStore write for THIS effect's key, if any. */
        @Volatile
        var persistJob: Job? = null

        fun attach(apply: (T) -> Unit) {
            applier = apply
            val snapshot = flow.value
            apply(snapshot)
            // Close the replay-vs-concurrent-set race: if flow.value changed
            // between registering the applier and this replay call (a
            // concurrent updateX() landed in that narrow window), re-apply
            // with the fresher value so the new listener never misses it
            // (final-review fix round, Minor #7).
            val latest = flow.value
            if (latest != snapshot) apply(latest)
        }

        fun set(value: T) {
            flow.value = value
            applier?.invoke(value)
        }

        /** Decode [stored]; malformed JSON leaves the current value in place. */
        fun restore(stored: String, json: Json) {
            runCatching { json.decodeFromString(serializer, stored) }
                .onSuccess { set(it) }
        }

        fun reset() {
            persistJob?.cancel()
            persistJob = null
            applier = null
            flow.value = default
        }
    }

    private val bassBoost = FxSlot(KEY_BASS_BOOST, BassBoostState.serializer(), BassBoostState())
    private val balance = FxSlot(KEY_BALANCE, BalanceState.serializer(), BalanceState())
    private val limiter = FxSlot(KEY_LIMITER, LimiterState.serializer(), LimiterState())
    private val replayGain = FxSlot(KEY_REPLAY_GAIN, ReplayGainState.serializer(), ReplayGainState())
    private val reverb = FxSlot(KEY_REVERB, ReverbState.serializer(), ReverbState())
    private val virtualizer = FxSlot(KEY_VIRTUALIZER, VirtualizerState.serializer(), VirtualizerState())

    val bassBoostState: StateFlow<BassBoostState> = bassBoost.flow
    val balanceState: StateFlow<BalanceState> = balance.flow
    val limiterState: StateFlow<LimiterState> = limiter.flow
    val replayGainState: StateFlow<ReplayGainState> = replayGain.flow
    val reverbState: StateFlow<ReverbState> = reverb.flow
    val virtualizerState: StateFlow<VirtualizerState> = virtualizer.flow

    private var appContext: Context? = null

    @Volatile
    private var initJob: Job? = null

    fun attachBassBoost(apply: (BassBoostState) -> Unit) = bassBoost.attach(apply)
    fun attachBalance(apply: (BalanceState) -> Unit) = balance.attach(apply)
    fun attachLimiter(apply: (LimiterState) -> Unit) = limiter.attach(apply)
    fun attachReplayGain(apply: (ReplayGainState) -> Unit) = replayGain.attach(apply)
    fun attachReverb(apply: (ReverbState) -> Unit) = reverb.attach(apply)
    fun attachVirtualizer(apply: (VirtualizerState) -> Unit) = virtualizer.attach(apply)

    fun updateBassBoost(state: BassBoostState) = update(bassBoost, state)
    fun updateBalance(state: BalanceState) = update(balance, state)
    fun updateLimiter(state: LimiterState) = update(limiter, state)
    fun updateReplayGain(state: ReplayGainState) = update(replayGain, state)
    fun updateReverb(state: ReverbState) = update(reverb, state)
    fun updateVirtualizer(state: VirtualizerState) = update(virtualizer, state)

    private fun <T> update(slot: FxSlot<T>, state: T) {
        slot.set(state)
        val ctx = appContext ?: return
        // Chain onto the previous in-flight write for THIS slot rather than
        // launching independently: two independently-launched coroutines
        // give no guarantee the DataStore edit that started SECOND also
        // COMPLETES second, so a slow-then-fast pair of updates could
        // persist the stale one last. Reading slot.flow.value fresh at
        // execution time (not the `state` parameter captured at call time)
        // rather than the value each launch captured means several updates
        // queued up behind one slow write collapse into a single final
        // write of whatever's current, instead of each earlier queued job
        // stubbornly re-writing its own now-stale value (final-review fix
        // round, Minor #6).
        val previous = slot.persistJob
        slot.persistJob = scope.launch {
            previous?.join()
            val current = slot.flow.value
            ctx.audioFxDataStore.edit { it[slot.key] = json.encodeToString(slot.serializer, current) }
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
            prefs[reverb.key]?.let { reverb.restore(it, json) }
            prefs[virtualizer.key]?.let { virtualizer.restore(it, json) }
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

    /**
     * Suspends until every effect's most recent `updateX` persist has been
     * written. Each slot tracks its own in-flight write, so back-to-back
     * updates of two different effects both land before this returns.
     */
    internal suspend fun awaitPersisted() {
        bassBoost.persistJob?.join()
        balance.persistJob?.join()
        limiter.persistJob?.join()
        replayGain.persistJob?.join()
        reverb.persistJob?.join()
        virtualizer.persistJob?.join()
    }

    /**
     * Test-only: reset the singleton's in-memory state, detach all listeners,
     * and forget the app context so a subsequent [initialize] re-runs. Does not
     * touch DataStore — persisted state survives, which is what the persistence
     * round-trip test relies on.
     */
    internal fun resetForTest() {
        initJob?.cancel()
        initJob = null
        appContext = null
        bassBoost.reset()
        balance.reset()
        limiter.reset()
        replayGain.reset()
        reverb.reset()
        virtualizer.reset()
    }
}
