package io.github.auxen.provider.tidal

import android.net.Uri
import org.junit.Assert.assertEquals
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
}
