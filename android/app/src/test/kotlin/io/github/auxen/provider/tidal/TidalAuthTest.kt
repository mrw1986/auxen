package io.github.auxen.provider.tidal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: AuxenApp would open the Room database at startup.
@Config(application = Application::class)
class TidalAuthTest {

    @Test
    fun credentialsConfiguredRequiresBothValues() {
        assertTrue(TidalAuth.credentialsConfigured("id", "secret"))
        assertFalse(TidalAuth.credentialsConfigured("", "secret"))
        assertFalse(TidalAuth.credentialsConfigured("id", ""))
        assertFalse(TidalAuth.credentialsConfigured("  ", "secret"))
        assertFalse(TidalAuth.credentialsConfigured("", ""))
    }

    @Test
    fun `hasSession is true once a login stores a session and false after logout`() = runTest {
        val auth = TidalAuth(ApplicationProvider.getApplicationContext(), tokenEndpoint())
        // The session store is one per process, so start from a known empty one.
        auth.logout()
        assertFalse(auth.hasSession.first())

        auth.awaitLogin(TidalAuth.DeviceAuthorization("device", "user", "", "", expiresIn = 60, interval = 0))
        assertTrue(auth.hasSession.first())

        auth.logout()
        assertFalse(auth.hasSession.first())
    }

    // Answers every call like Tidal's token endpoint approving the login, so nothing hits the network.
    private fun tokenEndpoint() = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""{"access_token":"test-access","refresh_token":"test-refresh","expires_in":3600}""".toResponseBody("application/json".toMediaType()))
            .build()
    }.build()
}
