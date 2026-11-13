package io.github.auxen.dsp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
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

internal val Context.bitPerfectDataStore by preferencesDataStore(name = "bit_perfect")

/**
 * Single source of truth for the Bit-Perfect / Direct playback toggle, shared
 * by the UI and the playback service (both run in the app process). Mirrors
 * [EqController]'s DataStore-backed singleton shape — one boolean preference,
 * default `false`.
 *
 * ## What the flag actually does
 * Bit-Perfect ON delivers untouched float/hi-res audio straight to the
 * `AudioTrack` and, as a direct consequence in Media3 1.5.1, auto-bypasses the
 * WHOLE in-process DSP chain: `DefaultAudioSink`'s float-output path skips
 * `setAudioProcessors` entirely, so EQ/AutoEq/bass/balance/limiter/ReplayGain
 * never run (see `EqRenderersFactory.buildAudioSink`'s KDoc — that same
 * behaviour was the "DSP does nothing" bug when float was on unintentionally;
 * here we turn it into a deliberate purist/direct mode). OFF (the default) is
 * the integer path where the DSP chain runs and output is 16-bit — the current,
 * shipping behaviour.
 *
 * ## Why this is NOT an effect controller
 * Unlike [EqController]/[io.github.auxen.dsp.AudioFxController], this drives no
 * `AudioProcessor` of its own. `setEnableFloatOutput` is fixed when the audio
 * sink is constructed, so a live flip cannot be pushed into the running sink —
 * `PlaybackService` instead OBSERVES [enabled] and, on a change, rebuilds its
 * ExoPlayer with a renderers factory constructed for the new flag. The UI
 * (`PlayerViewModel`) exposes the same [enabled] flow and calls [setEnabled].
 */
object BitPerfectController {
    internal val KEY_ENABLED = booleanPreferencesKey("bit_perfect_enabled")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private var appContext: Context? = null

    @Volatile
    private var initJob: Job? = null

    /** The in-flight DataStore write, if any — joined by [awaitPersisted] in tests. */
    @Volatile
    private var persistJob: Job? = null

    /** Load the persisted flag; safe to call more than once. */
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        initJob = scope.launch {
            val stored = context.applicationContext.bitPerfectDataStore.data.first()[KEY_ENABLED]
                ?: return@launch
            _enabled.value = stored
        }
    }

    /**
     * Suspends until the DataStore restore started by [initialize] has settled,
     * so a caller can read a fully-restored [enabled] instead of racing the load.
     * Returns immediately if [initialize] was never called or already finished.
     */
    internal suspend fun awaitInitialized() {
        initJob?.join()
    }

    /** Set the flag and persist it. Idempotent writes are fine (DataStore dedups). */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        val ctx = appContext ?: return
        val previous = persistJob
        persistJob = scope.launch {
            previous?.join()
            ctx.bitPerfectDataStore.edit { it[KEY_ENABLED] = _enabled.value }
        }
    }

    /** Suspends until the most recent [setEnabled] persist has been written. */
    internal suspend fun awaitPersisted() {
        persistJob?.join()
    }

    /**
     * Test-only: reset the singleton's in-memory state and forget the app
     * context so a subsequent [initialize] re-runs. Does not touch DataStore.
     * Mirrors [EqController.resetForTest].
     */
    internal fun resetForTest() {
        // cancelAndJoin (not cancel) so an IO-dispatched initJob from a prior
        // test can't write _enabled after this reset on an unlucky JUnit
        // ordering (CI JDK-17 isolation) — same reasoning as EqController.
        runBlocking {
            initJob?.cancelAndJoin()
            persistJob?.cancelAndJoin()
        }
        initJob = null
        persistJob = null
        appContext = null
        _enabled.value = false
    }
}
