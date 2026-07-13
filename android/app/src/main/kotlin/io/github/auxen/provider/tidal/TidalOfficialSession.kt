package io.github.auxen.provider.tidal

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.auxen.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private val Context.tidalOfficialDataStore by preferencesDataStore(name = "tidal_official_session")

/**
 * Validates a `auxen://auth-callback` redirect [Uri] against the
 * [expectedState] captured at [TidalOfficialSession.beginLogin], returning
 * the authorization code on success. Pure and network-free -- extracted out
 * of [TidalOfficialSession.completeLogin] specifically so this decision
 * logic is unit-testable without a live token-exchange call (Tidal
 * official-API migration, Task 1).
 */
internal fun validateRedirect(redirect: Uri, expectedState: String?): Result<String> {
    redirect.getQueryParameter("error")?.let { return Result.failure(IllegalStateException("Tidal login denied: $it")) }
    val returnedState = redirect.getQueryParameter("state")
    if (expectedState == null || expectedState != returnedState) {
        return Result.failure(IllegalStateException("OAuth state mismatch on Tidal redirect — discarding (possible CSRF)"))
    }
    val code = redirect.getQueryParameter("code")
        ?: return Result.failure(IllegalStateException("Tidal redirect had no authorization code"))
    return Result.success(code)
}

/**
 * Orchestrates the official Tidal API's PKCE login end-to-end (Tidal
 * official-API migration, Task 1): builds the authorize URL for the caller
 * to open in a Custom Tab, validates + exchanges the redirect for tokens,
 * and persists the session. Entirely independent of [TidalAuth]'s
 * internal-API session -- its own DataStore, nothing shared.
 *
 * **Temporary storage note:** tokens are stored in plain DataStore here,
 * matching [TidalAuth]'s own existing internal-API storage exactly -- NOT
 * yet the encrypted storage the task brief calls for
 * (`androidx.security.crypto`'s `EncryptedSharedPreferences` is a separate,
 * still-pending confirmation; see the Task 1 report). This class's public
 * API won't need to change when that lands, only [persist]/[storedSession]'s
 * internals.
 */
class TidalOfficialSession(context: Context, private val client: OkHttpClient) {
    private val appContext = context.applicationContext
    private val clientId: String get() = BuildConfig.TIDAL_CLIENT_ID

    // In-memory only for the duration of one login attempt -- NOT persisted
    // across process death mid-flow. Acceptable for a go/no-go spike (the
    // browser round-trip is typically well under a minute); a hardened
    // Task 2+ implementation should persist this pair too, since Android
    // can kill a backgrounded app while the Custom Tab is in front.
    @Volatile private var pendingVerifier: String? = null
    @Volatile private var pendingState: String? = null

    /** Step 1: generates a fresh PKCE pair, returns the URL to open in a Custom Tab. */
    fun beginLogin(): String {
        val verifier = Pkce.generateVerifier()
        val state = Pkce.generateVerifier() // same high-entropy generator, reused for the CSRF state token
        pendingVerifier = verifier
        pendingState = state
        return TidalOfficialAuth.authorizeUrl(clientId, Pkce.challengeFor(verifier), state)
    }

    /**
     * Step 2: call from `onNewIntent` with the full redirect URI. Validates
     * it via [validateRedirect] before ever making a network call.
     */
    suspend fun completeLogin(redirect: Uri): Result<TidalOfficialAuth.Session> = withContext(Dispatchers.IO) {
        val verifier = pendingVerifier
        val result = runCatching {
            checkNotNull(verifier) { "No login in progress — call beginLogin() first" }
            val code = validateRedirect(redirect, pendingState).getOrThrow()
            val request = TidalOfficialAuth.buildTokenExchangeRequest(clientId, code, verifier)
            client.newCall(request).execute().use { resp ->
                check(resp.isSuccessful) { "Token exchange failed: HTTP ${resp.code}" }
                TidalOfficialAuth.parseTokenResponse(resp.body!!.string())
            }
        }
        pendingVerifier = null
        pendingState = null
        result.onSuccess { persist(it) }
    }

    suspend fun storedSession(): TidalOfficialAuth.Session? {
        val prefs = appContext.tidalOfficialDataStore.data.first()
        val access = prefs[KEY_ACCESS] ?: return null
        return TidalOfficialAuth.Session(access, prefs[KEY_REFRESH], 0L)
    }

    suspend fun logout() {
        appContext.tidalOfficialDataStore.edit { it.clear() }
    }

    private suspend fun persist(session: TidalOfficialAuth.Session) {
        appContext.tidalOfficialDataStore.edit {
            it[KEY_ACCESS] = session.accessToken
            session.refreshToken?.let { rt -> it[KEY_REFRESH] = rt }
        }
    }

    companion object {
        private val KEY_ACCESS = stringPreferencesKey("access_token")
        private val KEY_REFRESH = stringPreferencesKey("refresh_token")
    }
}
