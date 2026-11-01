package io.github.auxen.playback

import androidx.media3.common.PlaybackException
import io.github.auxen.provider.StreamInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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
}
