package io.github.auxen.playback.cache

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.os.SystemClock

/** How long after a "nothing cached ahead" pause a returning network still restarts playback. */
const val AUTO_RESUME_WINDOW_MILLIS = 1_800_000L

/** Whether playback restarts on its own when the network comes back. */
sealed interface AutoResumeState {
    data object Idle : AutoResumeState

    /** Paused offline at [pausedAtMillis] on [mediaId], queued at [index]. */
    data class Waiting(val pausedAtMillis: Long, val index: Int, val mediaId: String) : AutoResumeState
}

/** What the auto-resume reacts to. Times are on [SystemClock.elapsedRealtime]. */
sealed interface AutoResumeEvent {
    /** The offline skipper paused on [mediaId] at [index] with nothing cached ahead. */
    data class OfflinePaused(val atMillis: Long, val index: Int, val mediaId: String) : AutoResumeEvent

    /** A validated network came back after an outage. */
    data class NetworkBack(val atMillis: Long) : AutoResumeEvent

    /** Playback was asked to start. */
    data object UserPlay : AutoResumeEvent

    /** The queue's media ids changed. A cache or DASH swap keeps them, so it isn't one. */
    data object QueueChanged : AutoResumeEvent

    /** A song became current. Fed on every transition, so a swap of the same song comes through too. */
    data class ItemChanged(val mediaId: String) : AutoResumeEvent

    /** The sleep timer ran out. It beats the auto-resume, so a timer spent during an outage stays spent. */
    data object SleepTimerFired : AutoResumeEvent

    /**
     * An external output such as the car or headphones went away. Playing stops when that
     * happens, so a resume mustn't start the phone speaker instead.
     */
    data object OutputLost : AutoResumeEvent
}

/** The next state, and the index to play when this step resumes. */
data class AutoResumeStep(val state: AutoResumeState, val resumeIndex: Int? = null)

/**
 * Whether an [AudioDeviceInfo] type is an output outside the phone: Bluetooth, USB, wired, HDMI
 * or line out. The constants inline at compile time, so the API 31 ones are safe on older phones.
 */
@SuppressLint("InlinedApi")
fun isExternalOutput(type: Int): Boolean = type in EXTERNAL_OUTPUT_TYPES

@SuppressLint("InlinedApi")
private val EXTERNAL_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
)

/**
 * Only a network back within [AUTO_RESUME_WINDOW_MILLIS] of the pause resumes. A play, a queue
 * change, a different song, the sleep timer running out or a lost output cancels.
 */
fun reduceAutoResume(state: AutoResumeState, event: AutoResumeEvent): AutoResumeStep = when (event) {
    is AutoResumeEvent.OfflinePaused ->
        AutoResumeStep(AutoResumeState.Waiting(event.atMillis, event.index, event.mediaId))
    is AutoResumeEvent.NetworkBack ->
        if (state is AutoResumeState.Waiting && state.inWindowAt(event.atMillis)) {
            AutoResumeStep(AutoResumeState.Idle, resumeIndex = state.index)
        } else {
            AutoResumeStep(AutoResumeState.Idle)
        }
    is AutoResumeEvent.ItemChanged ->
        if (state is AutoResumeState.Waiting && state.mediaId == event.mediaId) {
            AutoResumeStep(state)
        } else {
            AutoResumeStep(AutoResumeState.Idle)
        }
    AutoResumeEvent.UserPlay,
    AutoResumeEvent.QueueChanged,
    AutoResumeEvent.SleepTimerFired,
    AutoResumeEvent.OutputLost,
    -> AutoResumeStep(AutoResumeState.Idle)
}

private fun AutoResumeState.Waiting.inWindowAt(nowMillis: Long) =
    nowMillis - pausedAtMillis <= AUTO_RESUME_WINDOW_MILLIS

/**
 * Holds the auto-resume state for the service and stamps its events on [clock]. The state is
 * already Idle when [onResume] runs, so the play it starts can't cancel anything. Main thread only.
 */
class AutoResumeController(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val onResume: (index: Int, mediaId: String) -> Unit,
) {
    private var state: AutoResumeState = AutoResumeState.Idle

    // Null until the first network state, which is where the collector starts, not a change.
    private var online: Boolean? = null

    /** True while a returning network would still resume. False once the window runs out. */
    val isWaiting: Boolean
        get() = (state as? AutoResumeState.Waiting)?.inWindowAt(clock()) ?: false

    fun on(event: AutoResumeEvent) {
        val waiting = state as? AutoResumeState.Waiting
        val step = reduceAutoResume(state, event)
        state = step.state
        if (step.resumeIndex != null && waiting != null) onResume(step.resumeIndex, waiting.mediaId)
    }

    /** Starts waiting when [action] is the offline skipper's "nothing cached ahead" pause. */
    fun onOfflineAction(action: OfflineAction) {
        if (action is OfflineAction.PausedNothingAhead) {
            on(AutoResumeEvent.OfflinePaused(clock(), action.index, action.mediaId))
        }
    }

    /**
     * Feed every network state. Returns true on an offline to online change, after resuming if
     * that was in time. A metered/unmetered change while online isn't one.
     */
    fun onNetwork(online: Boolean): Boolean {
        val reconnected = this.online == false && online
        this.online = online
        if (reconnected) on(AutoResumeEvent.NetworkBack(clock()))
        return reconnected
    }
}

/**
 * Plays [index] from where it stopped, unless the queue moved under the pause and [mediaId] isn't
 * there any more. [PlayerQueueAdapter] reads an index past the end as "", so that never matches.
 * A transition pause sits at 0 already, an error pause resumes where it failed, and a seek made
 * during the wait holds. A position from another song never carries over.
 */
fun resumeOfflinePause(queue: PlayerQueue, index: Int, mediaId: String) {
    if (queue.mediaIdAt(index) != mediaId) return
    queue.seekTo(index, if (queue.currentIndex == index) queue.positionMs else 0)
    queue.prepareAndPlay()
}
