package io.github.auxen.playback.cache

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

const val OFFLINE_NOTHING_CACHED_MESSAGE = "Offline, nothing cached ahead"

/**
 * What the offline skipper last did, shared between the service and the UI in one process.
 * [nothingCachedAhead] drives the banner. [handledRecently] lets the UI drop the player error
 * that arrives right after the service already dealt with it. [handledCount] goes up on every
 * handled failure, so the UI can also drop a network error banner it showed before that.
 */
class OfflineStatus(private val clock: () -> Long = SystemClock::elapsedRealtime) {

    private val nothingAhead = MutableStateFlow(false)
    val nothingCachedAhead: StateFlow<Boolean> = nothingAhead.asStateFlow()

    private val handled = MutableStateFlow(0L)
    val handledCount: StateFlow<Long> = handled.asStateFlow()

    // Set on the main thread by the service, read on the main thread by the VM.
    @Volatile private var handledAt: Long? = null

    fun showNothingCachedAhead() {
        nothingAhead.value = true
    }

    fun clear() {
        nothingAhead.value = false
    }

    fun markHandled() {
        handledAt = clock()
        handled.update { it + 1 }
    }

    fun handledRecently(windowMillis: Long = 5_000): Boolean {
        val at = handledAt ?: return false
        return clock() - at < windowMillis
    }
}
