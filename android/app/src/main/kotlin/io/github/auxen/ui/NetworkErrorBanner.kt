package io.github.auxen.ui

import io.github.auxen.playback.isIoErrorCode

/**
 * Remembers which playback banner an IO error raised. Once the service handles an offline
 * failure, that banner is stale and goes. Any other banner, like a decode error or the offline
 * message, stays. Main thread only, like the banner itself.
 */
class NetworkErrorBanner {

    private var networkBanner: String? = null

    /** Call with every error banner the UI shows. */
    fun shown(message: String, errorCode: Int) {
        networkBanner = message.takeIf { isIoErrorCode(errorCode) }
    }

    /** The banner to keep once offline handling took over, given the one showing now. */
    fun afterOfflineHandled(current: String?): String? =
        if (current != null && current == networkBanner) null else current
}
