package io.github.auxen.provider.tidal

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [validateRedirect] is the pure, network-free half of
 * [TidalOfficialSession.completeLogin] -- state-mismatch/error/missing-code
 * detection on the `auxen://auth-callback` redirect, extracted so it's
 * testable without a live token-exchange call (same "don't unit-test actual
 * network execution" precedent as [TidalAuth]'s own login methods). Needs
 * Robolectric for a working `Uri.parse`/`getQueryParameter` shadow, and (for
 * the single-flight case) a Context for [TidalOfficialSession]'s DataStore.
 *
 * Pinned to a bare [Application] rather than the real `AuxenApp`: this class
 * only ever builds its OWN [TidalOfficialSession], so booting `Graph.init`'s
 * whole object graph (Room, OkHttp, every provider) would add nothing but
 * coupling and start-up surface underneath a timing-sensitive concurrency test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TidalOfficialSessionTest {

    @Test
    fun `accepts a redirect whose state matches and extracts the code`() {
        val redirect = Uri.parse("auxen://auth-callback?code=abc123&state=xyz")
        val result = validateRedirect(redirect, expectedState = "xyz")
        assertTrue(result.isSuccess)
        assertEquals("abc123", result.getOrNull())
    }

    @Test
    fun `rejects a state mismatch as a possible CSRF redirect`() {
        val redirect = Uri.parse("auxen://auth-callback?code=abc123&state=attacker-supplied")
        val result = validateRedirect(redirect, expectedState = "xyz")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("state mismatch", ignoreCase = true))
    }

    @Test
    fun `rejects a redirect with no pending state (no login was ever started)`() {
        val redirect = Uri.parse("auxen://auth-callback?code=abc123&state=xyz")
        val result = validateRedirect(redirect, expectedState = null)
        assertTrue(result.isFailure)
    }

    @Test
    fun `surfaces a user-denied login as its own error, not a generic missing-code error`() {
        val redirect = Uri.parse("auxen://auth-callback?error=access_denied&state=xyz")
        val result = validateRedirect(redirect, expectedState = "xyz")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("access_denied"))
    }

    @Test
    fun `rejects a redirect missing the authorization code`() {
        val redirect = Uri.parse("auxen://auth-callback?state=xyz")
        val result = validateRedirect(redirect, expectedState = "xyz")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("code", ignoreCase = true))
    }

    @Test
    fun `token is fresh well before expiry`() {
        // expires at 100_000; now 10_000; 60s skew -> not yet
        assertFalse(officialTokenNeedsRefresh(expiresAtMillis = 100_000L, nowMillis = 10_000L, skewMillis = 60_000L))
    }

    @Test
    fun `token needs refresh inside the skew window`() {
        // expires at 100_000; now 50_000; skew 60_000 -> 50_000 >= 40_000 -> refresh
        assertTrue(officialTokenNeedsRefresh(expiresAtMillis = 100_000L, nowMillis = 50_000L, skewMillis = 60_000L))
    }

    @Test
    fun `token needs refresh once past expiry`() {
        assertTrue(officialTokenNeedsRefresh(expiresAtMillis = 100_000L, nowMillis = 200_000L, skewMillis = 60_000L))
    }

    @Test
    fun `legacy session with unknown expiry (0) always refreshes`() {
        assertTrue(officialTokenNeedsRefresh(expiresAtMillis = 0L, nowMillis = 1_000L, skewMillis = 60_000L))
    }

    // --- tokenAfterFailedRefresh: a transient refresh failure must not read as a logout ---

    @Test
    fun `a token still inside its life is kept when a proactive refresh fails`() {
        // Inside the 60s skew window (so a refresh was attempted) but not actually
        // expired: 30s of genuine validity remain, so keep using it rather than
        // reporting the user as logged out over one failed request.
        assertEquals(
            "tok",
            tokenAfterFailedRefresh(accessToken = "tok", expiresAtMillis = 100_000L, nowMillis = 70_000L),
        )
    }

    @Test
    fun `a genuinely expired token is not resurrected by a failed refresh`() {
        assertNull(tokenAfterFailedRefresh(accessToken = "tok", expiresAtMillis = 100_000L, nowMillis = 200_000L))
    }

    @Test
    fun `the expiry instant itself counts as expired`() {
        // Boundary: the `<` is deliberate -- at exactly expiresAtMillis the token is done.
        assertNull(tokenAfterFailedRefresh(accessToken = "tok", expiresAtMillis = 100_000L, nowMillis = 100_000L))
    }

    @Test
    fun `a legacy unknown-expiry (0) token is never resurrected`() {
        assertNull(tokenAfterFailedRefresh(accessToken = "tok", expiresAtMillis = 0L, nowMillis = 1_000L))
    }

    // --- single-flight refresh ---

    /**
     * A fake OkHttp transport: short-circuits every call with a canned token
     * body, so nothing opens a socket (no live HTTP, no MockWebServer dep).
     * Counts only `grant_type=refresh_token` POSTs -- the initial exchange that
     * seeds the session goes through the same client.
     *
     * The seeded and refreshed responses carry *different* placeholder values so
     * assertions can tell WHICH one a caller ended up holding: a caller that
     * queued on the mutex must come back with the refreshed value, proving it
     * re-read the store under the lock rather than returning the stale value it
     * read before queueing.
     */
    private class CannedTokenTransport : Interceptor {
        val refreshCalls = AtomicInteger(0)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val form = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val isRefresh = form.contains("grant_type=refresh_token")
            if (isRefresh) refreshCalls.incrementAndGet()
            // Seed short-lived (lands inside the 60s skew -> due for refresh);
            // refresh long-lived, so a caller that queued on the mutex re-reads
            // a fresh session and does NOT refresh a second time.
            val expiresIn = if (isRefresh) 3600 else 30
            val access = if (isRefresh) REFRESHED else SEEDED
            val body =
                """{"access_token":"$access","refresh_token":"$access-r","token_type":"Bearer","expires_in":$expiresIn}"""
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }

        companion object {
            /** Throwaway placeholders, not tokens -- distinct so callers are distinguishable. */
            const val SEEDED = "seeded-placeholder"
            const val REFRESHED = "refreshed-placeholder"
        }
    }

    @Test
    fun `concurrent validAccessToken callers refresh exactly once`() = runBlocking {
        val transport = CannedTokenTransport()
        val client = OkHttpClient.Builder().addInterceptor(transport).build()
        val now = 1_000_000L
        val session = TidalOfficialSession(
            ApplicationProvider.getApplicationContext<Context>(),
            client,
            clock = { now },
        )
        session.logout() // the DataStore is process-wide; start from a clean store

        // Seed a session via the real login path (the fake transport answers the
        // exchange) -- persist() is private, so this is the honest way in.
        val state = Uri.parse(session.beginLogin()).getQueryParameter("state")!!
        session.completeLogin(Uri.parse("auxen://auth-callback?code=code&state=$state")).getOrThrow()
        assertEquals("seeding must not itself refresh", 0, transport.refreshCalls.get())

        val tokens = coroutineScope {
            listOf(
                async { session.validAccessToken() },
                async { session.validAccessToken() },
            ).awaitAll()
        }

        // Tidal may rotate the refresh token, so a second concurrent refresh could
        // POST an already-consumed one and break the session. This is THE
        // single-flight proof: without the mutex both callers reach the endpoint.
        assertEquals("refresh must be single-flight", 1, transport.refreshCalls.get())
        assertEquals("both callers get a token", 2, tokens.filterNotNull().size)
        // Both must hold the REFRESHED value: the one caller that queued on the
        // mutex has to re-read the store under the lock, not hand back the stale
        // pre-lock read. (Distinct canned values make this assertion real -- with
        // one shared canned value it could not fail.)
        assertEquals(
            "both callers end on the refreshed token",
            listOf(CannedTokenTransport.REFRESHED, CannedTokenTransport.REFRESHED),
            tokens,
        )
    }
}
