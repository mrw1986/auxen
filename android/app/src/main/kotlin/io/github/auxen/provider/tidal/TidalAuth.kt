package io.github.auxen.provider.tidal

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.auxen.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

private val Context.tidalDataStore by preferencesDataStore(name = "tidal_session")

/**
 * Tidal OAuth2 device-code flow — the same flow the desktop app drives
 * through tidalapi (`login_oauth()`): show the user a link.tidal.com URL,
 * poll the token endpoint until they approve, persist the tokens.
 *
 * Client credentials come from BuildConfig (see app/build.gradle.kts); they
 * are intentionally not checked into the repository.
 */
class TidalAuth(context: Context, private val client: OkHttpClient) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresIn: Int,
        val interval: Int = 2,
    )

    @Serializable
    data class TokenResponse(
        val access_token: String,
        val refresh_token: String? = null,
        val token_type: String = "Bearer",
        val expires_in: Long = 0,
    )

    @Serializable
    private data class TokenError(val error: String? = null, val sub_status: Int? = null)

    /** Persisted session tokens. */
    data class Session(val accessToken: String, val refreshToken: String?, val expiresAtMillis: Long)

    suspend fun storedSession(): Session? {
        val prefs = appContext.tidalDataStore.data.first()
        val access = prefs[KEY_ACCESS] ?: return null
        return Session(access, prefs[KEY_REFRESH], prefs[KEY_EXPIRES]?.toLongOrNull() ?: 0L)
    }

    /**
     * Step 1: request a device code. The returned
     * [DeviceAuthorization.verificationUriComplete] (prefixed with https://)
     * is what the UI shows / opens for the user.
     */
    suspend fun requestDeviceAuthorization(): DeviceAuthorization = withContext(Dispatchers.IO) {
        check(credentialsConfigured(BuildConfig.TIDAL_CLIENT_ID, BuildConfig.TIDAL_CLIENT_SECRET)) {
            "Tidal credentials are not configured in this build — add " +
                "auxen.tidalClientId/auxen.tidalClientSecret to ~/.gradle/gradle.properties and rebuild"
        }
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.TIDAL_CLIENT_ID)
            .add("scope", SCOPE)
            .build()
        val request = Request.Builder().url("$AUTH_BASE/device_authorization").post(body).build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "Device authorization failed: HTTP ${resp.code}" }
            json.decodeFromString<DeviceAuthorization>(resp.body!!.string())
        }
    }

    /**
     * Step 2: poll the token endpoint until the user approves (or the code
     * expires). Blocks the calling coroutine; cancel it to abort login.
     */
    suspend fun awaitLogin(auth: DeviceAuthorization): Session = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + auth.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(auth.interval * 1000L)
            val body = FormBody.Builder()
                .add("client_id", BuildConfig.TIDAL_CLIENT_ID)
                .add("client_secret", BuildConfig.TIDAL_CLIENT_SECRET)
                .add("device_code", auth.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("scope", SCOPE)
                .build()
            val request = Request.Builder().url("$AUTH_BASE/token").post(body).build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val token = json.decodeFromString<TokenResponse>(text)
                    return@withContext persist(token)
                }
                val error = runCatching { json.decodeFromString<TokenError>(text) }.getOrNull()
                if (error?.error != "authorization_pending") {
                    error("Tidal login failed: ${error?.error ?: "HTTP ${resp.code}"}")
                }
            }
        }
        error("Tidal login timed out — device code expired")
    }

    /** Refresh the access token; returns null when re-login is required. */
    suspend fun refresh(): Session? = withContext(Dispatchers.IO) {
        val refreshToken = storedSession()?.refreshToken ?: return@withContext null
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.TIDAL_CLIENT_ID)
            .add("client_secret", BuildConfig.TIDAL_CLIENT_SECRET)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder().url("$AUTH_BASE/token").post(body).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val token = json.decodeFromString<TokenResponse>(resp.body!!.string())
            // Tidal may omit the refresh token on refresh; keep the old one.
            persist(token.copy(refresh_token = token.refresh_token ?: refreshToken))
        }
    }

    suspend fun logout() {
        appContext.tidalDataStore.edit { it.clear() }
    }

    private suspend fun persist(token: TokenResponse): Session {
        val session = Session(
            accessToken = token.access_token,
            refreshToken = token.refresh_token,
            expiresAtMillis = System.currentTimeMillis() + token.expires_in * 1000L,
        )
        appContext.tidalDataStore.edit {
            it[KEY_ACCESS] = session.accessToken
            session.refreshToken?.let { rt -> it[KEY_REFRESH] = rt }
            it[KEY_EXPIRES] = session.expiresAtMillis.toString()
        }
        return session
    }

    internal companion object {
        const val AUTH_BASE = "https://auth.tidal.com/v1/oauth2"
        const val SCOPE = "r_usr w_usr w_sub"
        val KEY_ACCESS = stringPreferencesKey("access_token")
        val KEY_REFRESH = stringPreferencesKey("refresh_token")
        val KEY_EXPIRES = stringPreferencesKey("expires_at")

        /** True when both build-time Tidal credentials are present. */
        fun credentialsConfigured(clientId: String, clientSecret: String): Boolean =
            clientId.isNotBlank() && clientSecret.isNotBlank()
    }
}
