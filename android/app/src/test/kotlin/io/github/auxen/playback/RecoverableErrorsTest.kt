package io.github.auxen.playback

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
}
