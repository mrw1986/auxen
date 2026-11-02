package io.github.auxen.provider.tidal

import android.util.Base64
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.provider.MusicProvider
import io.github.auxen.provider.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
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
    private val bootstrapped = MutableStateFlow(false)
    private var userId: String? = null

    /** Whether a session is bootstrapped. Observable, so caching stops the moment the user logs out. */
    val loggedIn: StateFlow<Boolean> = bootstrapped.asStateFlow()

    val isLoggedIn: Boolean get() = bootstrapped.value

    /** The signed-in user's Tidal id (the favorites collection owner), or null before a successful [restoreSession]. */
    val currentUserId: String? get() = userId

    // ------------------------------------------------------------------
    // API response models (subset of the fields tidalapi consumes)
    // ------------------------------------------------------------------

    @Serializable
    private data class ApiSession(val countryCode: String = "US", val userId: Long? = null)

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
            val api = json.decodeFromString<ApiSession>(body)
            countryCode = api.countryCode
            userId = api.userId?.toString()
            bootstrapped.value = true
        }.isSuccess
    }

    suspend fun logout() = sessionMutex.withLock {
        auth.logout()
        bootstrapped.value = false
        userId = null
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
     * Multi-type search — artists + albums + tracks in one read-only catalog GET
     * on `search` (the endpoint `types=…` filters), so artist and album pages are
     * reachable from search, not just tracks. Metadata only: resolves no stream,
     * touches no session state.
     *
     * **Additive alongside [search].** [search] hits `search/tracks` (bare
     * `{"items":[…]}`, singular `artist`, kept for its interface contract); this
     * hits `search` and gets a per-type envelope whose items carry `artists`
     * plural — see [searchResultsFrom] and the plan's Finding 1.
     *
     * Returns [TidalSearchResults.EMPTY] when not logged in or on any HTTP/parse
     * failure: search then shows local tracks only (or nothing), never an error —
     * the same not-logged-in discipline as [search].
     */
    suspend fun searchAll(query: String, limit: Int = 20): TidalSearchResults {
        val token = validToken() ?: return TidalSearchResults.EMPTY
        val url = searchAllUrl(API_BASE, query, countryCode, limit)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return TidalSearchResults.EMPTY
        return searchResultsFrom(body, json)
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
     * Resolve a Tidal track's artist + album ids from ONE internal-v1 read
     * (`GET tracks/{id}`). This is the internal-v1 replacement for the official-v2
     * `trackRelations`, used as the seed step of BOTH the artist-id chain
     * ([io.github.auxen.ui.PlayerViewModel.loadArtistDetail]) and the album-id
     * chain ([io.github.auxen.ui.PlayerViewModel.loadAlbumDetail]). Metadata only:
     * resolves no stream, touches no session state.
     *
     * Returns null when not logged in or on any HTTP/parse failure — the caller
     * then falls through to its next id source or renders un-enriched, exactly as
     * with the official read it replaces.
     */
    suspend fun trackRelations(trackId: String): TrackRelations? {
        val token = validToken() ?: return null
        val url = trackRelationsUrl(API_BASE, trackId, countryCode)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return null
        return trackRelationsFromV1(body, json)
    }

    /**
     * Full album detail — `GET albums/{id}` + a paged sweep of
     * `GET albums/{id}/items` — projected to [TidalAlbumDetail]. A read-only
     * catalog GET: resolves no stream, touches no session state. The tracklist is
     * the COMPLETE album (paged), with native `trackNumber`/`volumeNumber`; videos
     * are filtered out.
     *
     * Returns null when not logged in, when the metadata read fails, or when the
     * body is not an album ([albumDetailFromV1]'s contract) — the album screen
     * then falls back to its local group, exactly as it does today for a
     * non-Tidal album.
     */
    suspend fun albumDetail(albumId: String): TidalAlbumDetail? {
        val token = validToken() ?: return null
        val metaBody = runCatching { get(albumMetadataUrl(API_BASE, albumId, countryCode).toString(), token) }
            .getOrNull() ?: return null
        val itemBodies = mutableListOf<String>()
        var offset = 0
        while (offset < ALBUM_ITEMS_MAX) {
            val body = runCatching { get(albumItemsUrl(API_BASE, albumId, countryCode, ALBUM_ITEMS_PAGE, offset).toString(), token) }
                .getOrNull() ?: break
            itemBodies += body
            val total = runCatching { json.decodeFromString<TidalV1AlbumItemsResponse>(body) }.getOrNull()
            offset += ALBUM_ITEMS_PAGE
            if (total == null || total.items.isEmpty() || offset >= total.totalNumberOfItems) break
        }
        return albumDetailFromV1(metaBody, itemBodies, json)
    }

    /**
     * Credits + lyrics for a Tidal track, assembled into the [TidalTrackDetail]
     * the info sheet renders — two internal-v1 reads (`tracks/{id}/credits` and
     * `tracks/{id}/lyrics`) in place of the official-v2 compound document. Metadata
     * only: resolves no stream.
     *
     * Each read fails independently and gracefully: no credits → empty list, no
     * lyrics (a 404 is EXPECTED for instrumentals) → null text. Returns null ONLY
     * when not logged in — the sheet then shows "No details available." The title
     * comes from [track] because neither endpoint carries it. `similarTracks` is
     * empty: internal-v1 credits/lyrics carry none, and the sheet never rendered them.
     */
    suspend fun trackDetail(track: Track): TidalTrackDetail? {
        val token = validToken() ?: return null
        val credits = runCatching { get(trackCreditsUrl(API_BASE, track.sourceId, countryCode).toString(), token) }
            .getOrNull()?.let { trackCreditsFrom(it, json) }.orEmpty()
        val lyrics = runCatching { get(trackLyricsUrl(API_BASE, track.sourceId, countryCode).toString(), token) }
            .getOrNull()?.let { trackLyricsFrom(it, json) }
        return TidalTrackDetail(
            id = track.sourceId,
            title = track.title,
            credits = credits,
            lyricsText = lyrics?.lyrics,
            lyricsSynced = lyrics?.subtitles,
            similarTracks = emptyList(),
        )
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

    /**
     * The user's Tidal saved albums, paginated — the internal-v1 port of the
     * desktop's `session.user.favorites.albums()` (`auxen/providers/tidal.py`
     * `get_favorite_albums`). Metadata only: resolves no stream. Returns
     * [FavoritesFetch.items] **null** when not logged in, the user id is
     * unresolved, any page's fetch fails, OR any page's body does not strictly
     * decode as a favorites envelope (an HTML captive-portal page / v1 error
     * envelope is never mistaken for an empty collection); a complete
     * (possibly empty) list only on full success — the mirror in
     * [TidalCollectionSync] relies on this to distinguish a genuine empty from
     * a failure. The M1 cross-check ([orNullIfDriftedEmpty]) additionally
     * guards a well-formed-but-drifted envelope whose items all fail to
     * project.
     *
     * Pagination keys off [FavoritesPage.rawCount] (the RAW wrapper count),
     * NOT `items.size` (C1): a page with an unavailable favorite (its
     * wrapper's `item` is `null`) still has a FULL raw count, so keying off
     * the projected size would make that page look short and end the fetch
     * early. [FavoritesFetch.fullyProjected] is `false` when any wrapper
     * across the whole read failed to project — see [FavoritesFetch]'s KDoc
     * for how the mirror uses it.
     */
    suspend fun favoriteAlbums(): FavoritesFetch<TidalFavoriteAlbum> {
        val token = validToken() ?: return FavoritesFetch(null, fullyProjected = false)
        val uid = userId ?: return FavoritesFetch(null, fullyProjected = false)
        return paginateFavorites(FAVORITES_PAGE, FAVORITES_MAX) { offset ->
            val url = favoriteAlbumsUrl(API_BASE, uid, countryCode, FAVORITES_PAGE, offset)
            val body = runCatching { get(url.toString(), token) }.getOrNull()
                ?: return@paginateFavorites null
            favoriteAlbumsPageOrNull(body, json)
        }
    }

    /**
     * The user's Tidal followed artists, paginated — the internal-v1 port of
     * the desktop's `session.user.favorites.artists()`
     * (`get_followed_artists`). Same null-on-any-failure / M1-guarded /
     * raw-count-paginated (C1) contract as [favoriteAlbums].
     */
    suspend fun favoriteArtists(): FavoritesFetch<TidalFavoriteArtist> {
        val token = validToken() ?: return FavoritesFetch(null, fullyProjected = false)
        val uid = userId ?: return FavoritesFetch(null, fullyProjected = false)
        return paginateFavorites(FAVORITES_PAGE, FAVORITES_MAX) { offset ->
            val url = favoriteArtistsUrl(API_BASE, uid, countryCode, FAVORITES_PAGE, offset)
            val body = runCatching { get(url.toString(), token) }.getOrNull()
                ?: return@paginateFavorites null
            favoriteArtistsPageOrNull(body, json)
        }
    }

    /** Save [albumId] to the user's Tidal collection. Best-effort — false on any failure. */
    suspend fun addFavoriteAlbum(albumId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteAlbumsWriteUrl(API_BASE, uid, countryCode)
        val form = FormBody.Builder().add("albumId", albumId).build()
        return runCatching { post(url.toString(), form, token) }.isSuccess
    }

    /** Remove [albumId] from the user's Tidal collection. Best-effort. */
    suspend fun removeFavoriteAlbum(albumId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteAlbumDeleteUrl(API_BASE, uid, albumId, countryCode)
        return runCatching { delete(url.toString(), token) }.isSuccess
    }

    /** Follow [artistId] in the user's Tidal collection. Best-effort. */
    suspend fun addFavoriteArtist(artistId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteArtistsWriteUrl(API_BASE, uid, countryCode)
        val form = FormBody.Builder().add("artistId", artistId).build()
        return runCatching { post(url.toString(), form, token) }.isSuccess
    }

    /** Unfollow [artistId] in the user's Tidal collection. Best-effort. */
    suspend fun removeFavoriteArtist(artistId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteArtistDeleteUrl(API_BASE, uid, artistId, countryCode)
        return runCatching { delete(url.toString(), token) }.isSuccess
    }

    /**
     * The user's Tidal favorite tracks, paginated — the internal-v1 port of the
     * desktop's `session.user.favorites.tracks()`. Each is a fully-playable
     * [Track] (via [TidalV1PageTrack.toTrack]) paired with its remote `created`
     * millis. Metadata only: resolves no stream. Same null-on-any-failure /
     * M1-guarded / raw-count-paginated (C1) contract as [favoriteAlbums] (a
     * missing collection degrades the Tracks tab, never breaks it).
     */
    suspend fun favoriteTracks(): FavoritesFetch<TidalFavoriteTrack> {
        val token = validToken() ?: return FavoritesFetch(null, fullyProjected = false)
        val uid = userId ?: return FavoritesFetch(null, fullyProjected = false)
        return paginateFavorites(FAVORITES_PAGE, FAVORITES_MAX) { offset ->
            val url = favoriteTracksUrl(API_BASE, uid, countryCode, FAVORITES_PAGE, offset)
            val body = runCatching { get(url.toString(), token) }.getOrNull()
                ?: return@paginateFavorites null
            favoriteTracksPageOrNull(body, json)
        }
    }

    /** Save [trackId] to the user's Tidal favorite tracks. Best-effort — false on any failure. */
    suspend fun addFavoriteTrack(trackId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteTracksWriteUrl(API_BASE, uid, countryCode)
        val form = FormBody.Builder().add("trackId", trackId).build()
        return runCatching { post(url.toString(), form, token) }.isSuccess
    }

    /** Remove [trackId] from the user's Tidal favorite tracks. Best-effort. */
    suspend fun removeFavoriteTrack(trackId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteTrackDeleteUrl(API_BASE, uid, trackId, countryCode)
        return runCatching { delete(url.toString(), token) }.isSuccess
    }

    /**
     * The user's Tidal favorite PLAYLISTS (own + followed), paginated — the
     * internal-v1 port of `session.user.favorites.playlists()`. LIST only: each
     * carries its uuid + title, NOT its tracks (those are fetched lazily via
     * [playlistItems] when a playlist is opened). Same not-logged-in / degrade
     * contract as [favoriteTracks] (a missing collection degrades the Playlists
     * tab, never breaks it).
     */
    suspend fun favoritePlaylists(): List<TidalPlaylist> {
        val token = validToken() ?: return emptyList()
        val uid = userId ?: return emptyList()
        val all = mutableListOf<TidalPlaylist>()
        var offset = 0
        while (offset < FAVORITES_MAX) {
            val url = favoritePlaylistsUrl(API_BASE, uid, countryCode, FAVORITES_PAGE, offset)
            val body = runCatching { get(url.toString(), token) }.getOrNull() ?: break
            val page = favoritePlaylistsFrom(body, json)
            all += page
            if (page.size < FAVORITES_PAGE) break
            offset += FAVORITES_PAGE
        }
        return all
    }

    /**
     * One Tidal playlist's tracks by its [uuid], paginated — the internal-v1
     * `playlists/{uuid}/items`. Non-track items (e.g. videos) are dropped by
     * [playlistTracksFrom]; pagination advances on the RAW page item count (via
     * [TidalPlaylistPage.rawItemCount]) so filtering never truncates a large
     * playlist early. Metadata only: resolves no stream. Empty when not logged in
     * / any page fails.
     */
    suspend fun playlistItems(uuid: String): List<Track> {
        val token = validToken() ?: return emptyList()
        val all = mutableListOf<Track>()
        var offset = 0
        while (offset < FAVORITES_MAX) {
            val url = playlistItemsUrl(API_BASE, uuid, countryCode, FAVORITES_PAGE, offset)
            val body = runCatching { get(url.toString(), token) }.getOrNull() ?: break
            val page = playlistTracksFrom(body, json)
            all += page.tracks
            if (page.rawItemCount < FAVORITES_PAGE) break
            offset += FAVORITES_PAGE
        }
        return all
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

    private suspend fun post(url: String, form: FormBody, token: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").post(form).build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "Tidal API error: HTTP ${resp.code} for $url" }
            resp.body?.string().orEmpty()
        }
    }

    private suspend fun delete(url: String, token: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").delete().build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "Tidal API error: HTTP ${resp.code} for $url" }
            resp.body?.string().orEmpty()
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
        // albums/{id}/items paging: 100 covers all but box sets in one call; the
        // MAX cap bounds a pathological loop (a huge box set truncates gracefully
        // rather than looping unboundedly — no album on Tidal reaches 1000 tracks).
        const val ALBUM_ITEMS_PAGE = 100
        const val ALBUM_ITEMS_MAX = 1000
        // favorites paging: 50 matches the desktop page size; MAX bounds a
        // pathological loop (114 albums / 110 artists paginate to an empty page
        // in ~4 calls) AND is the completeness backstop — a collection that exits
        // at MAX rather than reaching the server's empty page reads as incomplete
        // (`paginateFavorites`'s `reachedEnd` is false), so deletes are suppressed
        // rather than wiping the un-fetched tail. MUST stay > 0: that `while
        // (offset < maxItems)` loop runs at least once only because of it; a MAX
        // of 0 would skip the loop, leaving `reachedEnd` false and `all` empty —
        // the false read still can't trust a delete (no empty page seen), but
        // keep MAX a real page budget.
        const val FAVORITES_PAGE = 50
        const val FAVORITES_MAX = 5000
    }
}
