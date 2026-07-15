package io.github.auxen.provider.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC 7636 PKCE (Proof Key for Code Exchange) helpers for the official
 * Tidal API's authorization-code flow (Tidal official-API migration, Task
 * 1). No Robolectric needed -- pure JDK crypto (`MessageDigest`,
 * `SecureRandom`, `java.util.Base64`), matching [ScreensLogicTest]'s "no
 * VM/Compose runtime" convention.
 */
class PkceTest {

    @Test
    fun `challengeFor matches RFC 7636's own worked example`() {
        // RFC 7636 Appendix B -- the canonical verifier-to-S256-challenge
        // test vector, straight from the spec itself.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeFor(verifier))
    }

    @Test
    fun `generateVerifier stays within RFC 7636's 43-128 length bound using only the allowed charset`() {
        val verifier = Pkce.generateVerifier()
        assertTrue("length ${verifier.length} out of [43,128]", verifier.length in 43..128)
        assertTrue("contains disallowed characters: $verifier", verifier.matches(Regex("^[A-Za-z0-9\\-._~]+$")))
    }

    @Test
    fun `generateVerifier is different on every call`() {
        val a = Pkce.generateVerifier()
        val b = Pkce.generateVerifier()
        assertTrue("two calls returned the same verifier -- not random", a != b)
    }
}
