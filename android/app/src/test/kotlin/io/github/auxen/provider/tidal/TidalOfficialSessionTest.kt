package io.github.auxen.provider.tidal

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [validateRedirect] is the pure, network-free half of
 * [TidalOfficialSession.completeLogin] -- state-mismatch/error/missing-code
 * detection on the `auxen://auth-callback` redirect, extracted so it's
 * testable without a live token-exchange call (same "don't unit-test actual
 * network execution" precedent as [TidalAuth]'s own login methods). Needs
 * Robolectric only for a working `Uri.parse`/`getQueryParameter` shadow.
 */
@RunWith(RobolectricTestRunner::class)
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
}
