package io.github.auxen.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource.FileDataSourceException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
// A plain Application: AuxenApp would open the Room database at startup.
@Config(application = Application::class)
class AuxenLoadErrorPolicyTest {

    private val root = "file:///data/no_backup/track_cache/"
    private val policy = AuxenLoadErrorPolicy(root)

    @Test
    fun `a missing cached file is not retried`() {
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info("${root}42/segment-3.mp4", fileNotFound())))
    }

    @Test
    fun `a missing cached file is found through the load's data spec`() {
        val info = info("${root}42/manifest.mpd", fileNotFound(), openedUri = Uri.EMPTY)

        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info))
    }

    @Test
    fun `a missing local library file keeps the default retry`() {
        val info = info("file:///storage/emulated/0/Music/song.flac", fileNotFound())

        assertEquals(1_000, policy.getRetryDelayMsFor(info))
    }

    @Test
    fun `a DASH swap signal is not retried`() {
        val swap = IOException("Source error", CachedDashStreamException("42", "${root}42/manifest.mpd"))

        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info("auxen://tidal/42", swap)))
    }

    private fun fileNotFound() =
        FileDataSourceException(FileNotFoundException("gone"), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

    // A second failure: Media3's default waits (errorCount - 1) seconds before retrying.
    private fun info(uri: String, error: IOException, openedUri: Uri = Uri.parse(uri)) =
        LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(1, DataSpec(Uri.parse(uri)), openedUri, emptyMap(), 0, 0, 0),
            MediaLoadData(C.DATA_TYPE_MEDIA),
            error,
            2,
        )
}
