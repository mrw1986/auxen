package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Official Tidal API (`openapi.tidal.com/v2`) authorization-code + PKCE
 * login (Tidal official-API migration, Task 1) -- additive, alongside the
 * existing device-code [TidalAuth] for the internal API; nothing internal
 * is touched or removed.
 *
 * Rolled by hand rather than `com.tidal.sdk:auth`: that SDK module pulls
 * in Dagger (+ KSP) and Retrofit, neither used anywhere else in this
 * codebase (manual [io.github.auxen.Graph] wiring, raw OkHttp everywhere
 * else including [TidalAuth] itself). This mirrors that same OkHttp +
 * kotlinx.serialization shape instead of adding a second, inconsistent
 * dependency-injection/networking stack for one login flow.
 *
 * PKCE public-client: the token exchange and refresh requests deliberately
 * never send `client_secret` -- see [buildTokenExchangeRequest]/
 * [buildRefreshRequest]. The code_verifier (see [Pkce]) is what proves
 * possession instead, which is the entire point of PKCE for a distributed
 * mobile client that cannot keep a secret confidential.
 */
object TidalOfficialAuth {
    private const val AUTHORIZE_URL = "https://login.tidal.com/authorize"
    private const val TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"

    /** Registered for the Auxen developer app; matches the loopback fallback Tidal also has on file. */
    const val REDIRECT_URI = "auxen://auth-callback"

    /** Every scope the Auxen app is registered for (developer.tidal.com), space-separated per RFC 6749. */
    const val SCOPE = "collection.read collection.write playlists.read playlists.write " +
        "recommendations.read search.read search.write user.read entitlements.read playback"

    /**
     * The `login.tidal.com/authorize` URL to open in a Custom Tab
     * (RFC 8252 -- native apps must use the system browser/Custom Tab for
     * this, never an embedded WebView the app itself controls, or the app
     * could intercept the user's Tidal credentials).
     */
    fun authorizeUrl(clientId: String, codeChallenge: String, state: String): String =
        AUTHORIZE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build()
            .toString()

    /** The `grant_type=authorization_code` POST — no `client_secret` (PKCE public-client). */
    fun buildTokenExchangeRequest(clientId: String, code: String, verifier: String): Request {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        return Request.Builder().url(TOKEN_URL).post(body).build()
    }

    /** The `grant_type=refresh_token` POST — also no `client_secret`. */
    fun buildRefreshRequest(clientId: String, refreshToken: String): Request {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        return Request.Builder().url(TOKEN_URL).post(body).build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The standard OAuth2 token endpoint response body — same shape as the internal API's own [TidalAuth.TokenResponse]. */
    @Serializable
    data class TokenResponse(
        val access_token: String,
        val refresh_token: String? = null,
        val token_type: String = "Bearer",
        val expires_in: Long = 0,
    )

    /** A resolved session, in the naming this codebase's own [TidalAuth.Session] uses. */
    data class Session(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long)

    fun parseTokenResponse(body: String): Session {
        val token = json.decodeFromString<TokenResponse>(body)
        return Session(token.access_token, token.refresh_token, token.expires_in)
    }
}
