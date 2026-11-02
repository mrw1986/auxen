package io.github.auxen.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * True when [error]'s cause chain contains a [TidalDashStreamException] — the
 * signal a Tidal track resolved to a DASH manifest instead of a direct stream —
 * or a [CachedDashStreamException], its twin for a song cached as a DASH folder.
 *
 * [PlaybackService.onPlayerError] AUTO-RECOVERS this by swapping in the resolved
 * DASH media item and re-preparing, so the track goes on to play. The same
 * [androidx.media3.common.PlaybackException] also reaches the UI's controller
 * listener, which must NOT surface it as a failure — otherwise a Hi-Res track
 * that plays fine still flashes a scary "ERROR_CODE_IO_UNSPECIFIED" banner during
 * the swap. Pure and framework-free so it is unit-testable; walks `cause` links
 * (the DASH exception is nested under Media3's ExoPlaybackException/source error).
 *
 * A GENUINE DASH-playback failure (e.g. the swapped MPD source itself 401s) throws
 * a DIFFERENT exception, not [TidalDashStreamException], so real errors still
 * surface — only the recoverable resolve-time signal is filtered here.
 */
fun isRecoverableDashError(error: Throwable): Boolean =
    causeChain(error).any { it is TidalDashStreamException || it is CachedDashStreamException }

/**
 * True when a Tidal item failed because a cached file is gone: it vanished between
 * resolve and open, or a DASH folder lost a segment. A Tidal network stream never
 * raises FILE_NOT_FOUND, so this can only be the cache. [PlaybackService] evicts the
 * entry and falls back to the network, so the UI shouldn't show it as a failure.
 */
fun isRecoverableCacheError(errorCode: Int, mediaId: String?): Boolean =
    errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND && mediaId?.startsWith("TIDAL:") == true

/**
 * True when a load failed because a file under [cacheRootUri] is gone, like a cached DASH
 * folder's manifest or segment. Retrying the same file can't help, so the load error policy
 * skips straight to [PlaybackService]'s missing-file recovery. A cached progressive song loads
 * through its `auxen` URI, which a retry re-resolves to the network, so it isn't matched here.
 */
@UnstableApi
fun isMissingCacheFile(error: Throwable, uri: String, cacheRootUri: String): Boolean =
    uri.startsWith(cacheRootUri) &&
        causeChain(error).any { it is DataSourceException && it.reason == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND }

private val NETWORK_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
)

/**
 * True when [error] means the device couldn't reach the server: a DNS, connect or timeout
 * failure anywhere in the cause chain, or Media3's connection failed/timed out code. An HTTP
 * status anywhere in the chain means the server did answer, so that's never a network failure.
 */
fun isNetworkFailure(error: Throwable): Boolean {
    val chain = causeChain(error).toList()
    if (chain.any { it is HttpDataSource.InvalidResponseCodeException }) return false
    return chain.any {
        it is UnknownHostException || it is ConnectException ||
            it is SocketTimeoutException || it is NoRouteToHostException ||
            (it is PlaybackException && it.errorCode in NETWORK_ERROR_CODES)
    }
}

/**
 * A network failure with no validated network. A validated network can still fail a request,
 * and then the normal error path (and its banner) applies, since a retry might work.
 */
fun isOfflineFailure(error: Throwable, online: Boolean): Boolean = !online && isNetworkFailure(error)

/** Media3's IO error codes, the 2000 range. They survive the trip to a controller's listener. */
fun isIoErrorCode(errorCode: Int): Boolean = errorCode in 2000..2999

/**
 * True when the UI shouldn't show [error]: [PlaybackService] is already swapping in a DASH copy,
 * or it just handled an offline failure by resuming from cache, skipping or pausing. Only an IO
 * error can be that failure, so a decode or other error right after a skip still shows.
 */
fun shouldSuppressPlaybackBanner(error: PlaybackException, offlineHandledRecently: Boolean): Boolean =
    isRecoverableDashError(error) || (offlineHandledRecently && isIoErrorCode(error.errorCode))

private fun causeChain(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }
