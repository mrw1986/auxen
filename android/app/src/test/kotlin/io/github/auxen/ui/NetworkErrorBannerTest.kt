package io.github.auxen.ui

import androidx.media3.common.PlaybackException
import io.github.auxen.playback.cache.OFFLINE_NOTHING_CACHED_MESSAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkErrorBannerTest {

    private val banner = NetworkErrorBanner()
    private val networkError = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED\nExoPlaybackException ← UnknownHostException"
    private val decodeError = "ERROR_CODE_DECODING_FAILED\nExoPlaybackException ← IllegalStateException"

    @Test fun `a network error banner goes once offline handling takes over`() {
        banner.shown(networkError, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        assertNull(banner.afterOfflineHandled(networkError))
    }

    @Test fun `a non-network error banner stays`() {
        banner.shown(decodeError, PlaybackException.ERROR_CODE_DECODING_FAILED)

        assertEquals(decodeError, banner.afterOfflineHandled(decodeError))
    }

    @Test fun `a banner that replaced the network error stays`() {
        banner.shown(networkError, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(OFFLINE_NOTHING_CACHED_MESSAGE, banner.afterOfflineHandled(OFFLINE_NOTHING_CACHED_MESSAGE))

        banner.shown(decodeError, PlaybackException.ERROR_CODE_DECODING_FAILED)
        assertEquals(decodeError, banner.afterOfflineHandled(decodeError))
    }

    @Test fun `no banner stays none`() {
        assertNull(banner.afterOfflineHandled(null))
    }
}
