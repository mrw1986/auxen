package io.github.auxen.provider.tidal

import android.util.Base64
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.provider.MusicProvider
import io.github.auxen.provider.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Tidal music source — Kotlin port of `auxen.providers.tidal.TidalProvider`,
 * talking to the same v1 API tidalapi uses.
 *
 * Only the surface the player needs so far: session bootstrap, track search,
 * and stream resolution (`playbackinfopostpaywall`). The rest of the desktop
 * provider (favorites, mixes, home page, lyrics, ...) ports over the same way
 * and is tracked in docs/android.md.
 */
class TidalProvider(
    private val auth: TidalAuth,
    private val client: OkHttpClient,
) : MusicProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val sessionMutex = Mutex()
    private var countryCode: String = "US"
    private var bootstrapped = false

    val isLoggedIn: Boolean get() = bootstrapped

    // ------------------------------------------------------------------
    // API response models (subset of the fields tidalapi consumes)
    // ------------------------------------------------------------------

    @Serializable
    private data class ApiSession(val countryCode: String = "US")

    @Serializable
    private data class ArtistDto(val name: String = "")

    @Serializable
    private data class AlbumDto(val title: String = "", val cover: String? = null)

    @Serializable
    private data class TrackDto(
        val id: Long,
        val title: String,
        val duration: Double = 0.0,
        val explicit: Boolean = false,
        val audioQuality: String? = null,
        val artist: ArtistDto = ArtistDto(),
        val album: AlbumDto = AlbumDto(),
        val trackNumber: Int? = null,
        val volumeNumber: Int? = null,
    )

    @Serializable
    private data class SearchResponse(val items: List<TrackDto> = emptyList())

    @Serializable
    private data class PlaybackInfo(
        val manifestMimeType: String,
        val manifest: String,
        val audioQuality: String? = null,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        val trackReplayGain: Double? = null,
        val albumReplayGain: Double? = null,
    )

    /** The JSON inside a `application/vnd.tidal.bts` manifest. */
    @Serializable
    private data class BtsManifest(val mimeType: String? = null, val urls: List<String> = emptyList())

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    /** Verify stored credentials against `GET /sessions`; refreshes when stale. */
    suspend fun restoreSession(): Boolean = sessionMutex.withLock {
        var session = auth.storedSession() ?: return false
        if (session.expiresAtMillis < System.currentTimeMillis() + 60_000) {
            session = auth.refresh() ?: return false
        }
        return runCatching {
            val body = get("$API_BASE/sessions", session.accessToken)
            countryCode = json.decodeFromString<ApiSession>(body).countryCode
            bootstrapped = true
        }.isSuccess
    }

    suspend fun logout() = sessionMutex.withLock {
        auth.logout()
        bootstrapped = false
    }

    // ------------------------------------------------------------------
    // MusicProvider
    // ------------------------------------------------------------------

    override suspend fun search(query: String, limit: Int): List<Track> {
        val token = validToken() ?: return emptyList()
        val url = "$API_BASE/search/tracks".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("countryCode", countryCode)
            .build()
        val body = get(url.toString(), token)
        return json.decodeFromString<SearchResponse>(body).items.map { it.toTrack() }
    }

    /**
     * Resolve a playable stream. Requests Hi-Res lossless; Tidal answers with
     * either a BTS manifest (direct FLAC/AAC URLs) or a DASH MPD, which is
     * passed to ExoPlayer as a data: URI (media3-exoplayer-dash handles it).
     */
    override suspend fun getStreamInfo(track: Track): StreamInfo = getStreamInfoById(track.sourceId)

    /**
     * Resolve a playable stream by Tidal track id. Requests Hi-Res lossless;
     * Tidal answers with either a BTS manifest (direct FLAC/AAC URLs) or a
     * DASH MPD, which is passed to ExoPlayer as a data: URI.
     */
    suspend fun getStreamInfoById(trackId: String): StreamInfo {
        val token = validToken() ?: error("Not logged in to Tidal")
        val url = "$API_BASE/tracks/$trackId/playbackinfopostpaywall".toHttpUrl()
            .newBuilder()
            .addQueryParameter("audioquality", "HI_RES_LOSSLESS")
            .addQueryParameter("playbackmode", "STREAM")
            .addQueryParameter("assetpresentation", "FULL")
            .addQueryParameter("countryCode", countryCode)
            .build()
        val info = json.decodeFromString<PlaybackInfo>(get(url.toString(), token))

        return when {
            info.manifestMimeType.contains("vnd.tidal.bts") -> {
                val decoded = String(Base64.decode(info.manifest, Base64.DEFAULT))
                val bts = json.decodeFromString<BtsManifest>(decoded)
                StreamInfo(
                    uri = bts.urls.firstOrNull() ?: error("Empty BTS manifest for track $trackId"),
                    mimeType = bts.mimeType,
                    sampleRateHz = info.sampleRate,
                    bitDepth = info.bitDepth,
                    trackGainDb = info.trackReplayGain,
                    albumGainDb = info.albumReplayGain,
                )
            }
            info.manifestMimeType.contains("dash+xml") -> StreamInfo(
                uri = "data:application/dash+xml;base64,${info.manifest}",
                mimeType = "application/dash+xml",
                sampleRateHz = info.sampleRate,
                bitDepth = info.bitDepth,
                trackGainDb = info.trackReplayGain,
                albumGainDb = info.albumReplayGain,
            )
            else -> error("Unsupported Tidal manifest type: ${info.manifestMimeType}")
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun validToken(): String? {
        var session = auth.storedSession() ?: return null
        if (session.expiresAtMillis < System.currentTimeMillis() + 60_000) {
            session = sessionMutex.withLock { auth.refresh() } ?: return null
        }
        return session.accessToken
    }

    private suspend fun get(url: String, token: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "Tidal API error: HTTP ${resp.code} for $url" }
            resp.body!!.string()
        }
    }

    /** Mirrors `_tidal_track_to_model` in the desktop provider. */
    private fun TrackDto.toTrack(): Track {
        val artUrl = album.cover?.let {
            "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg"
        }
        val quality = audioQuality.orEmpty()
        val (fmt, sampleRate, bitDepth, bitrate) = when {
            quality.contains("HI_RES") -> QualityInfo("Hi-Res", 96_000, 24, 4608)
            quality.contains("LOSSLESS") -> QualityInfo("FLAC", 44_100, 16, 1411)
            quality.contains("HIGH") -> QualityInfo("AAC", 44_100, 16, 320)
            quality.isNotEmpty() -> QualityInfo("AAC", 44_100, null, 96)
            else -> QualityInfo(null, null, null, null)
        }
        return Track(
            title = title,
            artist = artist.name,
            album = album.title,
            source = Source.TIDAL,
            sourceId = id.toString(),
            durationSeconds = duration,
            trackNumber = trackNumber,
            discNumber = volumeNumber,
            format = fmt,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrate,
            albumArtUrl = artUrl,
            explicit = explicit,
        )
    }

    private data class QualityInfo(val fmt: String?, val sampleRate: Int?, val bitDepth: Int?, val bitrate: Int?)

    private companion object {
        const val API_BASE = "https://api.tidal.com/v1"
    }
}
