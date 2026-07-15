package io.github.auxen.dsp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal val Context.eqDataStore by preferencesDataStore(name = "equalizer")

/**
 * Single source of truth for EQ settings, shared by the UI and the playback
 * service (both run in the app process). Persists state as JSON in
 * DataStore, mirroring the desktop app's to_dict/from_dict persistence.
 */
@UnstableApi
object EqController {
    internal val KEY_STATE = stringPreferencesKey("eq_state")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state

    private var processor: ParametricEqProcessor? = null
    private var appContext: Context? = null

    @Volatile
    private var initJob: Job? = null

    /** Called once from the playback service when it creates its audio sink. */
    fun attachProcessor(p: ParametricEqProcessor) {
        processor = p
        p.updateState(_state.value)
    }

    /** Load persisted state; safe to call more than once. */
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        initJob = scope.launch {
            val stored = context.applicationContext.eqDataStore.data.first()[KEY_STATE] ?: return@launch
            runCatching { json.decodeFromString<EqState>(stored) }
                .onSuccess { setState(it, persist = false) }
        }
    }

    /**
     * Suspends until the DataStore restore started by [initialize] has settled,
     * so a caller can read a fully-restored [state] instead of racing the load.
     * Returns immediately if [initialize] was never called or already finished.
     * Used by restore-on-start to decide whether the full [EqState] (filters,
     * preamp, and the user's enabled flag) was already restored from DataStore.
     */
    internal suspend fun awaitInitialized() {
        initJob?.join()
    }

    fun setState(newState: EqState, persist: Boolean = true) {
        _state.value = newState
        processor?.updateState(newState)
        if (persist) {
            val ctx = appContext ?: return
            scope.launch {
                ctx.eqDataStore.edit { it[KEY_STATE] = json.encodeToString(EqState.serializer(), newState) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = setState(_state.value.copy(enabled = enabled))

    /** Set one graphic-EQ band (0..9); rebuilds the filter chain from bands. */
    fun setBand(index: Int, gainDb: Double) {
        val bands = (_state.value.bands ?: List(EqState.NUM_BANDS) { 0.0 }).toMutableList()
        bands[index] = gainDb.coerceIn(EqState.MIN_GAIN_DB, EqState.MAX_GAIN_DB)
        setState(EqState.fromBands(bands, enabled = _state.value.enabled, presetName = null))
    }

    fun applyPreset(name: String) {
        val gains = EqState.PRESETS[name] ?: return
        setState(EqState.fromBands(gains, enabled = true, presetName = name))
    }

    /**
     * Test-only: reset the singleton's in-memory state, detach the
     * processor, and forget the app context so a subsequent [initialize]
     * re-runs. Does not touch DataStore. Mirrors [AutoEqController.resetForTest]
     * -- added so tests that seed [EqController]'s state directly (e.g. the
     * AutoEq migration tests, which mutate it as their legacy source) can
     * fully isolate it between runs instead of relying on a partial manual
     * reset (`setState(EqState(), persist = false)`, which leaves
     * [appContext]/[initJob] dangling and doesn't guarantee order-independence).
     */
    internal fun resetForTest() {
        // See AutoEqController.resetForTest: cancelAndJoin (not cancel) so an
        // IO-dispatched initJob from a prior test can't write _state.value
        // after this reset on an unlucky JUnit ordering (CI JDK-17 isolation).
        runBlocking { initJob?.cancelAndJoin() }
        initJob = null
        appContext = null
        processor = null
        _state.value = EqState()
    }
}
