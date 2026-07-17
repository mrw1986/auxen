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

    /**
     * The artist's discography — a read-only catalog GET, mirroring the
     * desktop's `best.get_albums(limit=50)` (`auxen/providers/tidal.py:896`).
     * Metadata only: it resolves no stream and touches no session state.
     *
     * This lives on internal-v1 because official v2 provably cannot reproduce
     * this view (see [artistAlbumsUrl]'s KDoc and the plan's Architecture).
     * [artistId] is a Tidal catalog artist id — the same id official v2 uses.
     *
     * Returns an empty list when not logged in or when the call/parse fails,
     * matching [search]'s not-logged-in behaviour and the desktop's swallow at
     * `providers/tidal.py:899-900`: a missing discography degrades the artist
     * page, it must never break it.
     */
    suspend fun artistAlbums(artistId: String, limit: Int = 50): List<AlbumSummary> {
        val token = validToken() ?: return emptyList()
        val url = artistAlbumsUrl(API_BASE, artistId, countryCode, limit)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return emptyList()
        return artistAlbumsFrom(body, json)
    }

    /**
     * The artist's tracks **sorted by popularity** — a read-only catalog GET,
     * mirroring the desktop's `best.get_top_tracks(limit=50)`
     * (`auxen/providers/tidal.py:906`). Metadata only: it resolves no stream and
     * touches no session state.
     *
     * `artists/{id}/toptracks` answers the SAME `{"items":[<track>]}` envelope
     * `/search/tracks` does (`tidalapi/request.py:59-81`: `map_request` →
     * `map_json` → `json_obj.get("items")`), so this deliberately **reuses** the
     * existing private [SearchResponse]/[TrackDto]/[toTrack] rather than
     * declaring a parallel set — a duplicate DTO would be a conflicting
     * declaration, and re-mapping the same fields twice would let the two drift.
     * ([SearchResponse] is named for its first caller; it is really just this
     * package's v1 track-collection envelope. Renaming it would EDIT an existing
     * declaration, which the Global Constraints forbid.)
     *
     * [artistId] is a Tidal catalog artist id — the same id official v2 uses.
     * Returns an empty list when not logged in or when the call/parse fails; the
     * artist page then falls back to the local track list, exactly as the desktop
     * does when its own call throws (`providers/tidal.py:908-909`).
     */
    suspend fun artistTopTracks(artistId: String, limit: Int = 50): List<Track> {
        val token = validToken() ?: return emptyList()
        val url = artistTopTracksUrl(API_BASE, artistId, countryCode, limit)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return emptyList()
        return runCatching {
            json.decodeFromString<SearchResponse>(body).items.map { it.toTrack() }
        }.getOrDefault(emptyList())
    }

    /**
     * Resolve an artist's catalog id from their NAME, for artists we hold no
     * Tidal track for — a read-only catalog GET on the internal-v1 **search**
     * path, where search has always belonged (see Global Constraints).
     *
     * Mirrors the desktop's `session.search(artist_name, models=[Artist],
     * limit=5)` (`auxen/providers/tidal.py:860-862`) — **minus its `artists[0]`
     * fallback**, which is a bug we are deliberately not porting. [artistIdFromSearch]
     * requires an exact case-insensitive name match and otherwise returns null;
     * see its KDoc and the plan's *Artist-id resolution*.
     *
     * [limit] is 20 rather than the desktop's 5 precisely because that fallback
     * is gone — see [artistSearchUrl]'s KDoc.
     *
     * Returns null when not logged in, when the call/parse fails, or when no
     * exact match exists. Every one of those means the same thing to the caller:
     * no remote enrichment, page renders as today.
     */
    suspend fun searchArtistIdExact(artistName: String, limit: Int = 20): String? {
        if (artistName.isBlank()) return null
        val token = validToken() ?: return null
        val url = artistSearchUrl(API_BASE, artistName, countryCode, limit)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return null
        return artistIdFromSearch(body, json, artistName)
    }

    /**
     * The artist's biography as DISPLAY-READY text — a read-only catalog GET,
     * mirroring the desktop's `best.get_bio()` (`auxen/providers/tidal.py:911-916`).
     * Metadata only: it resolves no stream and touches no session state.
     *
     * This lives on internal-v1 because **official v2 has no biography data at
     * our tier** — proven twice on-device, for Metallica and for Tommy Richman;
     * see [artistBioUrl]'s KDoc. The official artist document no longer even
     * requests the include.
     *
     * Returns null when not logged in, on a 404, or on any call/parse failure —
     * and a **404 is the EXPECTED case for a lesser-known artist**, not an
     * error. [get] throws on a non-2xx, so `runCatching` is what turns that 404
     * into "no bio"; the screen then hides the section, exactly as the desktop's
     * bare `except` does ("404s are expected for lesser-known artists"). A
     * bio-less artist must render cleanly, and a bio failure must never cost the
     * page its discography or top tracks.
     */
    suspend fun artistBio(artistId: String): String? {
        val token = validToken() ?: return null
        val url = artistBioUrl(API_BASE, artistId, countryCode)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return null
        return artistBioFrom(body, json)
    }

    /**
     * The artist's ENTIRE Tidal page in one read-only catalog GET — biography,
     * top tracks, the discography **already categorised by Tidal**, and Fans
     * Also Like **with photos**. Metadata only: it resolves no stream and
     * touches no session state.
     *
     * This is the artist screen's single remote source. It replaces a four-call
     * fan-out ([artistBio] + [artistAlbums] + [artistTopTracks] + the official-v2
     * `artistDetail`) that on-device produced no biography, placeholder avatars,
     * and 20 *"Fixxxer"* rough mixes where a discography should be — because
     * `artists/{id}/albums` returns 43 albums FLATTENED and discards the
     * curation this endpoint keeps.
     *
     * **The request carries no `limit`, and must not** — this endpoint answers
     * **400** when one is present (probed 2026-07-16). [get] adds no params, and
     * [artistPageUrl] sends exactly the four the probe proved.
     *
     * [artistId] is a Tidal catalog artist id — the same id official v2 uses,
     * proven on-device by the id-equivalence gate.
     *
     * Returns **null** when not logged in, on any HTTP failure, or when the body
     * is not a page ([artistPageFrom]'s contract). All of those mean one thing to
     * the caller: no remote enrichment, and the artist screen renders exactly
     * what it renders today off its local base. A missing artist page must
     * degrade the screen, never break it — the same discipline as [artistAlbums]
     * and the desktop's swallow at `providers/tidal.py:899-900`.
     */
    suspend fun artistPage(artistId: String): TidalArtistPage? {
        val token = validToken() ?: return null
        val url = artistPageUrl(API_BASE, artistId, countryCode)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return null
        return artistPageFrom(body, json, artistId)
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
