package io.github.auxen.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource.FileDataSourceException
import androidx.media3.datasource.HttpDataSource
import io.github.auxen.provider.StreamInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// Robolectric because PlaybackException and DataSpec need SystemClock and Uri.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecoverableErrorsTest {

    private fun dash() = TidalDashStreamException(
        trackId = "338196336",
        streamInfo = StreamInfo(uri = "data:application/dash+xml;base64,PMFk"),
    )

    @Test fun `a bare DASH exception is recoverable`() {
        assertTrue(isRecoverableDashError(dash()))
    }

    @Test fun `a DASH exception nested under a source error is recoverable`() {
        val wrapped = IOException("Source error", RuntimeException("inner", dash()))
        assertTrue(isRecoverableDashError(wrapped))
    }

    @Test fun `an unrelated IO error is NOT recoverable`() {
        assertFalse(isRecoverableDashError(IOException("Response code: 403")))
    }

    @Test fun `an error with no cause chain is NOT recoverable`() {
        assertFalse(isRecoverableDashError(IllegalStateException("boom")))
    }

    @Test fun `a cached DASH swap signal is recoverable`() {
        val cached = CachedDashStreamException("338196336", "file:///cache/338196336/manifest.mpd")
        assertTrue(isRecoverableDashError(IOException("Source error", cached)))
    }

    @Test fun `a missing file on a Tidal item is a recoverable cache error`() {
        assertTrue(isRecoverableCacheError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, "TIDAL:42"))
    }

    @Test fun `a missing file on a local item is NOT a cache error`() {
        assertFalse(isRecoverableCacheError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, "LOCAL:42"))
    }

    @Test fun `another IO error on a Tidal item is NOT a cache error`() {
        assertFalse(isRecoverableCacheError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "TIDAL:42"))
    }

    @Test fun `a missing file with no media id is NOT a cache error`() {
        assertFalse(isRecoverableCacheError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, null))
    }

    private val cacheRoot = "file:///data/no_backup/track_cache/"

    private fun fileNotFound() =
        FileDataSourceException(FileNotFoundException("gone"), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

    @Test fun `a missing file under the cache root is a missing cache file`() {
        assertTrue(isMissingCacheFile(fileNotFound(), "${cacheRoot}42/segment-3.mp4", cacheRoot))
    }

    @Test fun `a missing file nested under a source error is a missing cache file`() {
        val wrapped = IOException("Source error", fileNotFound())
        assertTrue(isMissingCacheFile(wrapped, "${cacheRoot}42/manifest.mpd", cacheRoot))
    }

    @Test fun `a missing local library file is NOT a missing cache file`() {
        assertFalse(isMissingCacheFile(fileNotFound(), "file:///storage/emulated/0/Music/song.flac", cacheRoot))
        assertFalse(isMissingCacheFile(fileNotFound(), "auxen://tidal/42", cacheRoot))
    }

    @Test fun `another error under the cache root is NOT a missing cache file`() {
        val denied = FileDataSourceException(SecurityException("no"), PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        assertFalse(isMissingCacheFile(denied, "${cacheRoot}42/segment-3.mp4", cacheRoot))
    }

    // ---- offline ----

    private fun sourceError(cause: Throwable, code: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED) =
        PlaybackException("Source error", cause, code)

    private fun forbidden(cause: IOException? = null) = HttpDataSource.InvalidResponseCodeException(
        403, "Forbidden", cause, emptyMap(), DataSpec(Uri.parse("https://example.invalid/track")), ByteArray(0),
    )

    @Test fun `UnknownHostException nested in a source error is a network failure`() {
        val error = sourceError(IOException("resolve", UnknownHostException("unresolved")))
        assertTrue(isNetworkFailure(error))
    }

    @Test fun `SocketTimeoutException is a network failure`() {
        assertTrue(isNetworkFailure(sourceError(SocketTimeoutException("timeout"))))
    }

    @Test fun `ConnectException and NoRouteToHostException are network failures`() {
        assertTrue(isNetworkFailure(sourceError(ConnectException("refused"))))
        assertTrue(isNetworkFailure(IOException("wrapped", NoRouteToHostException("no route"))))
    }

    @Test fun `a connection failed or timed out code is a network failure without a known cause`() {
        val failed = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        val timedOut = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        assertTrue(isNetworkFailure(sourceError(IOException("opaque"), failed)))
        assertTrue(isNetworkFailure(sourceError(IOException("opaque"), timedOut)))
    }

    @Test fun `an InvalidResponseCodeException is not a network failure`() {
        assertFalse(isNetworkFailure(sourceError(forbidden(), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        // An HTTP status means the server answered, whatever else the chain or code says.
        val mixed = IOException("wrapped", forbidden(cause = UnknownHostException("x")))
        assertFalse(isNetworkFailure(sourceError(mixed, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
    }

    @Test fun `other errors are not network failures`() {
        assertFalse(isNetworkFailure(sourceError(IOException("disk"))))
        assertFalse(isNetworkFailure(fileNotFound()))
        assertFalse(isNetworkFailure(IllegalStateException("boom")))
    }

    @Test fun `offline failure needs no validated network`() {
        val error = sourceError(UnknownHostException("unresolved"))
        assertFalse(isOfflineFailure(error, online = true))
        assertTrue(isOfflineFailure(error, online = false))
        assertFalse(isOfflineFailure(sourceError(forbidden()), online = false))
    }

    @Test fun `banner is suppressed for DASH swaps and an IO error right after offline handling`() {
        val io = sourceError(IOException("Response code: 500"))
        // A DASH swap is hidden whatever its code.
        val dashSwap = sourceError(dash(), PlaybackException.ERROR_CODE_UNSPECIFIED)
        assertTrue(shouldSuppressPlaybackBanner(dashSwap, offlineHandledRecently = false))
        assertTrue(shouldSuppressPlaybackBanner(io, offlineHandledRecently = true))
        assertFalse(shouldSuppressPlaybackBanner(io, offlineHandledRecently = false))
    }

    @Test fun `IO error codes are the 2000 range`() {
        assertFalse(isIoErrorCode(1999))
        assertTrue(isIoErrorCode(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertTrue(isIoErrorCode(2999))
        assertFalse(isIoErrorCode(3000))
    }

    @Test fun `a non-network error right after offline handling still shows`() {
        val decode = sourceError(IllegalStateException("decoder"), PlaybackException.ERROR_CODE_DECODING_FAILED)
        val parse = sourceError(IOException("bad box"), PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        assertFalse(shouldSuppressPlaybackBanner(decode, offlineHandledRecently = true))
        assertFalse(shouldSuppressPlaybackBanner(parse, offlineHandledRecently = true))
    }
}
