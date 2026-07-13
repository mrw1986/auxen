package io.github.auxen.provider.tidal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Executes [TidalOfficialEndpoints] requests and parses the JSON:API
 * response into the DTOs from `TidalOfficialApi.kt`, surfacing a
 * structured error message on failure (Tidal official-API migration, Task
 * 1's go/no-go spike). The actual HTTP execution is deliberately NOT unit
 * tested -- same "platform/network call" precedent as [TidalAuth]'s own
 * login methods; everything feeding INTO these calls (every request's
 * shape, in `TidalOfficialEndpointsTest`) already is.
 */
class TidalOfficialClient(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getTrack(id: String, accessToken: String): Result<TrackDocument> =
        execute(TidalOfficialEndpoints.trackRequest(id, accessToken))

    suspend fun getTrackManifest(id: String, accessToken: String, formats: List<String>): Result<TrackManifestDocument> =
        execute(TidalOfficialEndpoints.trackManifestRequest(id, accessToken, formats))

    /** Requires `PARTNER` access tier -- see [TidalOfficialEndpoints.trackFileRequest]'s KDoc. */
    suspend fun getTrackFile(id: String, accessToken: String, formats: List<String>): Result<TrackFileDocument> =
        execute(TidalOfficialEndpoints.trackFileRequest(id, accessToken, formats))

    private suspend inline fun <reified T> execute(request: Request): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val message = runCatching { json.decodeFromString<JsonApiErrorDocument>(text) }
                        .getOrNull()
                        ?.errors
                        ?.firstOrNull()
                        ?.let { "${it.code ?: "HTTP ${resp.code}"}${it.detail?.let { d -> ": $d" } ?: ""}" }
                        ?: "HTTP ${resp.code}"
                    error(message)
                }
                json.decodeFromString<T>(text)
            }
        }
    }
}
