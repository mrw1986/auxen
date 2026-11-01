package io.github.auxen.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.FileDataSource.FileDataSourceException
import io.github.auxen.provider.StreamInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
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
}
