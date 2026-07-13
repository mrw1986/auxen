package io.github.auxen.dsp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal val Context.autoEqDataStore by preferencesDataStore(name = "autoeq")

/**
 * Single source of truth for the headphone-correction (AutoEq) stage,
 * mirroring [EqController]'s object shape exactly but persisted, applied,
 * and toggled completely independently of it -- its own DataStore, its own
 * [ParametricEqProcessor], its own enable flag. What used to be one shared
 * EQ stage is now two: this one holds a searched/imported AutoEq profile
 * (no manual bands); [EqController] is now purely the 10-band graphic EQ
 * (AutoEq split, Task 1 -- user, 2026-07-13: "I don't like mixing the eq
 * and autoeq").
 *
 * Reuses [EqState] as its state type: an AutoEq profile already parses to
 * one via [AutoEqParser.parse] (`bands` stays null, `presetName` = the
 * profile name) -- no new state shape needed.
 */
@UnstableApi
object AutoEqController {
    internal val KEY_STATE = stringPreferencesKey("autoeq_state")
    internal val KEY_MIGRATED = booleanPreferencesKey("autoeq_migrated")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state

    private var processor: ParametricEqProcessor? = null
    private var appContext: Context? = null

    @Volatile
    private var initJob: Job? = null

    /** The in-flight DataStore write for the current state, if any. */
    @Volatile
    private var persistJob: Job? = null

    /** Called once from the playback service when it creates its audio sink. */
    fun attachProcessor(p: ParametricEqProcessor) {
        processor = p
        p.updateState(_state.value)
    }

    /**
     * Load persisted state; safe to call more than once. Also runs the
     * one-time legacy migration (see [migrateFromLegacyIfNeeded]) after
     * restoring its own state, since a pre-split install has its AutoEq
     * profile sitting in [EqController]'s OLD combined state, not here.
     */
    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        initJob = scope.launch {
            val prefs = app.autoEqDataStore.data.first()
            prefs[KEY_STATE]?.let { stored ->
                runCatching { json.decodeFromString<EqState>(stored) }
                    .onSuccess { setState(it, persist = false) }
            }
            migrateFromLegacyIfNeeded(app, alreadyMigrated = prefs[KEY_MIGRATED] ?: false)
        }
    }

    /**
     * One-time migration for installs that imported an AutoEq profile
     * before this split existed: the old, single [EqController] merged
     * graphic EQ and AutoEq into one `eq_state` key, so a profile shows up
     * there as `bands == null && filters.isNotEmpty()` -- graphic-EQ states
     * always carry `bands` (see [EqState.fromBands]), so that shape is
     * unambiguously "this was an AutoEq import, not a hand-tuned graphic
     * curve," never a false positive. Guarded by [KEY_MIGRATED] so it only
     * ever runs once per install, regardless of how many times
     * [initialize] is called across process restarts.
     *
     * Reads [EqController]'s state only after [EqController.awaitInitialized]
     * -- without this, a cold start could race this migration against
     * EqController's own DataStore restore and see stale (default) state,
     * wrongly concluding there was nothing to migrate.
     */
    private suspend fun migrateFromLegacyIfNeeded(app: Context, alreadyMigrated: Boolean) {
        if (alreadyMigrated) return
        EqController.awaitInitialized()
        val legacy = EqController.state.value
        if (legacy.bands == null && legacy.filters.isNotEmpty()) {
            // Crash-safety: both payload writes must be DURABLE before
            // KEY_MIGRATED is set, or a process kill in the gap permanently
            // loses the profile (guard true, autoeq_state never written) or
            // double-applies it (guard true, legacy eq_state never reset).
            // setState(..., persist = true) is the wrong tool here -- it
            // launches a fire-and-forget write on a separate coroutine that
            // this function doesn't wait for, so it can (and, on a loaded
            // device, will) race the marker write below. Instead: write the
            // legacy reset directly and suspend until it lands, then fold
            // the AutoEq payload and the marker into ONE DataStore#edit
            // transaction, so the marker can never be committed without the
            // payload it's meant to guard.
            app.eqDataStore.edit { it[EqController.KEY_STATE] = json.encodeToString(EqState.serializer(), EqState()) }
            EqController.setState(EqState(), persist = false) // disk already written above; sync in-memory only
            _state.value = legacy
            processor?.updateState(legacy)
            app.autoEqDataStore.edit {
                it[KEY_STATE] = json.encodeToString(EqState.serializer(), legacy)
                it[KEY_MIGRATED] = true
            }
            return
        }
        // Graphic-shaped (bands != null) or genuinely empty legacy state:
        // nothing to migrate -- EqController is left exactly as it was.
        app.autoEqDataStore.edit { it[KEY_MIGRATED] = true }
    }

    /**
     * Suspends until the DataStore restore (and the one-time migration)
     * started by [initialize] has settled.
     */
    internal suspend fun awaitInitialized() {
        initJob?.join()
    }

    /** Suspends until the most recent [setState] persist has been written. */
    internal suspend fun awaitPersisted() {
        persistJob?.join()
    }

    fun setState(newState: EqState, persist: Boolean = true) {
        _state.value = newState
        processor?.updateState(newState)
        if (persist) {
            val ctx = appContext ?: return
            persistJob = scope.launch {
                ctx.autoEqDataStore.edit { it[KEY_STATE] = json.encodeToString(EqState.serializer(), newState) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = setState(_state.value.copy(enabled = enabled))

    /** Drops the active profile: empties filters, disables, clears the name. */
    fun clear() = setState(EqState())

    /**
     * Import an AutoEq ParametricEq profile (the Wavelet-style feature;
     * moved here verbatim from [EqController] -- AutoEq split, Task 1).
     * `runCatching` around the parse keeps a rejected profile (Q<=0, Fc<=0
     * -- see [AutoEqParser.parse]'s KDoc) from ever reaching [setState],
     * so a bad import can't even partially touch DataStore (carries the
     * DSP-a review's Important #1 guarantee forward to the new home).
     */
    fun importAutoEq(text: String, profileName: String?): Result<EqState> =
        runCatching { AutoEqParser.parse(text, profileName) }.onSuccess { setState(it) }

    /**
     * Test-only: reset the singleton's in-memory state, detach the
     * processor, and forget the app context so a subsequent [initialize]
     * re-runs (including the migration). Does not touch DataStore.
     */
    internal fun resetForTest() {
        initJob?.cancel()
        initJob = null
        persistJob?.cancel()
        persistJob = null
        appContext = null
        processor = null
        _state.value = EqState()
    }
}
