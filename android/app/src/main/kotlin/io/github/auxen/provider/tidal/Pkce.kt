package io.github.auxen.provider.tidal

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * RFC 7636 PKCE (Proof Key for Code Exchange) helpers for
 * [TidalOfficialAuth]'s authorization-code flow -- the official Tidal API's
 * login (Tidal official-API migration, Task 1). The internal API's
 * [TidalAuth] uses a device-code flow with no PKCE involved, so this is
 * genuinely new to this codebase, not a duplicate of anything existing.
 */
object Pkce {
    /** 32 random bytes -> a 43-character base64url string, the minimum RFC 7636 §4.1 allows (and typical practice). */
    private const val VERIFIER_BYTES = 32

    /** A fresh, high-entropy code_verifier (RFC 7636 §4.1). */
    fun generateVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(VERIFIER_BYTES)
        random.nextBytes(bytes)
        return base64UrlNoPadding(bytes)
    }

    /** The S256 code_challenge for [verifier] (RFC 7636 §4.2): BASE64URL(SHA256(verifier)). */
    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64UrlNoPadding(digest)
    }

    private fun base64UrlNoPadding(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
