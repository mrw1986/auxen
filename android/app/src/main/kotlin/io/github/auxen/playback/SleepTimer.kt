package io.github.auxen.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sleep timer state. [endElapsedRealtime] is `null` when no timer is armed
 * (or once its countdown has expired -- see [pendingTrackEnd]); otherwise
 * the `SystemClock.elapsedRealtime()` timestamp playback should pause at.
 * [finishTrack] chooses HOW that pause happens: `false` pauses immediately
 * at expiry; `true` lets the currently-playing track finish and pauses at
 * the next track transition instead (see [PlaybackService]'s
 * `pendingSleepTimerPause` field).
 *
 * [pendingTrackEnd] is a THIRD phase, distinct from "counting down"
 * ([endElapsedRealtime] non-null) and "unarmed" (everything at defaults):
 * the countdown reached zero with [finishTrack] armed, and the service is
 * now waiting for the CURRENT track to finish before it actually pauses.
 * Without this phase, the watcher's only way to represent "still waiting to
 * pause" was `PlaybackService`'s own internal `pendingSleepTimerPause`
 * flag, invisible to the UI -- reopening the sheet during that window
 * showed the unarmed preset picker, as if nothing were pending, with no way
 * to see or cancel the pause about to happen (final review round,
 * Important #3).
 *
 * Uses `elapsedRealtime`, not wall-clock time, so a timer isn't thrown off
 * by a wall-clock adjustment (DST, NTP sync, manual clock change, ...)
 * mid-countdown -- matches `SystemClock.elapsedRealtime()`'s own monotonic,
 * boot-relative guarantee (unaffected by wall-clock changes, only paused
 * during deep sleep, which is the correct behavior for a countdown the user
 * expects to run in real elapsed time regardless of what the wall clock does).
 */
data class SleepTimerState(
    val endElapsedRealtime: Long? = null,
    val finishTrack: Boolean = false,
    val pendingTrackEnd: Boolean = false,
)

/**
 * True whenever a pause is either counting down or pending -- the sheet and
 * the Now Playing icon tint both key off this rather than
 * `endElapsedRealtime != null` alone, since [SleepTimerState.pendingTrackEnd]
 * has no countdown timestamp of its own but is still very much "armed" from
 * the user's point of view.
 */
val SleepTimerState.isArmed: Boolean
    get() = endElapsedRealtime != null || pendingTrackEnd

/**
 * Milliseconds remaining until this state's timer expires, per [clock];
 * `null` if unarmed OR in the [SleepTimerState.pendingTrackEnd] phase (there
 * is no countdown left to report there). Can go negative once expired --
 * clamping (if wanted) is the caller's job, not this pure function's;
 * display code coerces to 0, expiry-detection code just checks `<= 0`.
 */
fun SleepTimerState.remainingMillis(clock: () -> Long = SystemClock::elapsedRealtime): Long? =
    endElapsedRealtime?.let { it - clock() }

/**
 * Sleep timer -- same object/StateFlow idiom as
 * [io.github.auxen.dsp.EqController], but with NO persistence: a timer that
 * survived an app restart would be a user-hostile surprise (silence
 * starting at some forgotten future moment), not a convenience, so [state]
 * always starts at [SleepTimerState] defaults and there is no DataStore key
 * at all.
 */
object SleepTimerController {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state

    /** Test-only clock override; production always uses the real monotonic clock. */
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    /** Arms the timer for [minutes] minutes from now. Replaces any previous timer. */
    fun start(minutes: Int, finishTrack: Boolean) {
        _state.value = SleepTimerState(
            endElapsedRealtime = clock() + minutes * 60_000L,
            finishTrack = finishTrack,
        )
    }

    /**
     * Transitions from "counting down with finishTrack" to "waiting for the
     * current track to end" -- called by the watcher at expiry instead of
     * [cancel] when [SleepTimerState.finishTrack] is armed, so the sheet can
     * keep showing an active, cancelable state instead of silently
     * reverting to unarmed while a pause is still pending at the service
     * level. No-ops if already unarmed (e.g. a stray call racing a
     * concurrent [cancel]).
     */
    fun markPendingTrackEnd() {
        val current = _state.value
        if (current.endElapsedRealtime == null) return
        _state.value = current.copy(endElapsedRealtime = null, pendingTrackEnd = true)
    }

    /** Disarms the timer, if one is armed. */
    fun cancel() {
        _state.value = SleepTimerState()
    }

    /** Test-only: resets state and the clock override to their real-world defaults. */
    internal fun resetForTest() {
        _state.value = SleepTimerState()
        clock = SystemClock::elapsedRealtime
    }
}
