package io.github.auxen.provider.tidal

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [TidalOfficialAuth]'s pure, network-free pieces (Tidal official-API
 * migration, Task 1): the authorize-URL builder and the token-exchange
 * request SHAPE (an OkHttp [Request] this test inspects without ever
 * executing it). No Robolectric needed. Live login/refresh (real HTTP,
 * Custom Tab UI) is exercised on-device, same as [TidalAuth]'s equivalent
 * network calls.
 */
class TidalOfficialAuthTest {

    private fun Request.formParams(): Map<String, String> {
        val buffer = Buffer()
        body!!.writeTo(buffer)
        return buffer.readUtf8().split("&").associate { pair ->
            val (k, v) = pair.split("=", limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }
    }

    @Test
    fun `authorizeUrl targets login-tidal-com with PKCE S256 and every configured scope`() {
        val url = TidalOfficialAuth.authorizeUrl(
            clientId = "test-client-id",
            codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            state = "csrf-state-123",
        )
        val parsed = url.toHttpUrl()
        assertEquals("login.tidal.com", parsed.host)
        assertEquals("/authorize", parsed.encodedPath)
        assertEquals("code", parsed.queryParameter("response_type"))
        assertEquals("test-client-id", parsed.queryParameter("client_id"))
        assertEquals(TidalOfficialAuth.REDIRECT_URI, parsed.queryParameter("redirect_uri"))
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", parsed.queryParameter("code_challenge"))
        assertEquals("S256", parsed.queryParameter("code_challenge_method"))
        assertEquals("csrf-state-123", parsed.queryParameter("state"))
        assertEquals(TidalOfficialAuth.SCOPE, parsed.queryParameter("scope"))
    }

    @Test
    fun `authorizeUrl carries every scope the Auxen app is registered for`() {
        val scopes = TidalOfficialAuth.SCOPE.split(" ").toSet()
        assertEquals(
            setOf(
                "collection.read", "collection.write", "playlists.read", "playlists.write",
                "recommendations.read", "search.read", "search.write", "user.read",
                "entitlements.read", "playback",
            ),
            scopes,
        )
    }

    @Test
    fun `token exchange request is a PKCE public-client POST -- no client_secret in the body`() {
        val request = TidalOfficialAuth.buildTokenExchangeRequest(
            clientId = "test-client-id",
            code = "auth-code-abc",
            verifier = "the-original-verifier",
        )
        assertEquals("https://auth.tidal.com/v1/oauth2/token", request.url.toString())
        assertEquals("POST", request.method)

        val params = request.formParams()
        assertEquals("authorization_code", params["grant_type"])
        assertEquals("auth-code-abc", params["code"])
        assertEquals("the-original-verifier", params["code_verifier"])
        assertEquals("test-client-id", params["client_id"])
        assertEquals(TidalOfficialAuth.REDIRECT_URI, params["redirect_uri"])
        // The whole point of PKCE for a public (mobile) client: the code
        // verifier proves possession instead of a shared secret, so no
        // client_secret should ever be sent here (security review flag).
        assertFalse("client_secret must not appear in a PKCE public-client token exchange", params.containsKey("client_secret"))
    }

    @Test
    fun `refresh request is also secret-free`() {
        val request = TidalOfficialAuth.buildRefreshRequest(clientId = "test-client-id", refreshToken = "rt-xyz")
        val params = request.formParams()
        assertEquals("refresh_token", params["grant_type"])
        assertEquals("rt-xyz", params["refresh_token"])
        assertEquals("test-client-id", params["client_id"])
        assertFalse(params.containsKey("client_secret"))
    }

    @Test
    fun `parseTokenResponse decodes the standard OAuth2 token body`() {
        val body = """
            {
              "access_token": "at-123",
              "refresh_token": "rt-456",
              "token_type": "Bearer",
              "expires_in": 3600
            }
        """.trimIndent()
        val token = TidalOfficialAuth.parseTokenResponse(body)
        assertEquals("at-123", token.accessToken)
        assertEquals("rt-456", token.refreshToken)
        assertEquals(3600L, token.expiresInSeconds)
    }

    @Test
    fun `parseTokenResponse tolerates a missing refresh_token, as a refresh response may omit it`() {
        val body = """{"access_token": "at-123", "token_type": "Bearer", "expires_in": 3600}"""
        val token = TidalOfficialAuth.parseTokenResponse(body)
        assertEquals("at-123", token.accessToken)
        assertEquals(null, token.refreshToken)
    }
}
