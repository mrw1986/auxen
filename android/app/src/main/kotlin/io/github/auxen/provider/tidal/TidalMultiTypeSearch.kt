package io.github.auxen.provider.tidal

import io.github.auxen.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The pure parts of `GET api.tidal.com/v1/search` with `types=ARTISTS,ALBUMS,TRACKS`
 * — the multi-type search that makes ARTISTS and ALBUMS reachable, not just tracks.
 *
 * Probed live 2026-07-17 with the desktop's own session; the captured schema is
 * `.superpowers/sdd/search-multitype-schema.md` and it is ground truth here.
 *
 * **This is additive alongside the track-only [TidalProvider.search].** That one
 * hits `search/tracks` (a bare `{"items":[…]}` whose items carry the singular
 * `artist`); this hits `search` and gets a per-type envelope whose track/album
 * items carry `artists` PLURAL and no singular `artist`. That difference is why
 * the reuse here is [TidalV1PageTrack] (reads `artists[0]`), never
 * [TidalProvider]'s private `TrackDto` (reads the singular, would blank every
 * result's artist). See the plan's Finding 1.
 *
 * **The parse is TOTAL.** A non-JSON body or a v1 error envelope yields
 * [TidalSearchResults.EMPTY] (never a throw); a missing or empty type block
 * yields an empty list for that type; and items decode ONE AT A TIME (reused
 * [decodeEach]) so one malformed row cannot cost a whole type section. A failure
 * here degrades search to local-only — never breaks it.
 */

/**
 * A search album result: the reused [AlbumSummary] plus [artistName] from the
 * item's `artists` plural.
 *
 * [AlbumSummary] has no artist field (the artist-page rebuild's Open question 4),
 * but the multi-type album item carries `artists[0].name`, needed for both the
 * card subtitle and the `albumRoute(title, artist, tidalAlbumId)` argument. The
 * name is captured via the additive [TidalV1Album.artists] field.
 */
data class TidalAlbumResult(val summary: AlbumSummary, val artistName: String?)

/**
 * A multi-type search result bundle: tracks (local merge happens in the
 * ViewModel), Tidal albums, Tidal artists. Artists/albums are Tidal-only — the
 * local DB has no artist/album entities yet (Slice 2).
 */
data class TidalSearchResults(
    val tracks: List<Track>,
    val albums: List<TidalAlbumResult>,
    val artists: List<ArtistLink>,
) {
    companion object {
        /** Not logged in, network failure, or a non-search body — no Tidal results. */
        val EMPTY = TidalSearchResults(emptyList(), emptyList(), emptyList())
    }
}

/**
 * The `search` response envelope: each requested type nested under its own key,
 * `{ "artists": {items…}, "albums": {items…}, "tracks": {items…}, … }`. Reuses
 * [TidalV1PagedList] (raw `JsonElement` items) for every block, so the items are
 * decoded per type by [searchResultsFrom]. Each block DEFAULTS to an empty
 * [TidalV1PagedList] so a missing type key is empty, not a throw; `playlists`,
 * `videos` and `topHit` are dropped by `ignoreUnknownKeys`.
 */
@Serializable
data class TidalMultiTypeSearchResponse(
    val artists: TidalV1PagedList = TidalV1PagedList(),
    val albums: TidalV1PagedList = TidalV1PagedList(),
    val tracks: TidalV1PagedList = TidalV1PagedList(),
)

/**
 * `GET {apiBase}/search?query=…&limit=…&offset=0&types=ARTISTS,ALBUMS,TRACKS&countryCode=…`.
 *
 * Same param set as the exact-match [artistSearchUrl] (the other `search` caller),
 * with all three types. `types` is `ARTISTS,ALBUMS,TRACKS` — the probe-verified
 * UPPERCASE comma-joined form (the schema notes the endpoint is case-insensitive
 * on types; this ships what was probed). Unlike `pages/artist`, `search` ACCEPTS
 * `limit`, so it is sent.
 */
fun searchAllUrl(apiBase: String, query: String, countryCode: String, limit: Int = 20): HttpUrl =
    "$apiBase/search".toHttpUrl().newBuilder()
        .addQueryParameter("query", query)
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", "0")
        .addQueryParameter("types", "ARTISTS,ALBUMS,TRACKS")
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * Pure projection: a `search` body -> [TidalSearchResults], or
 * [TidalSearchResults.EMPTY] for a non-search body (error envelope, HTML, not-JSON).
 *
 * Each type block's items decode one at a time (reused [decodeEach]): a track ->
 * [TidalV1PageTrack.toTrack] (artist from `artists[0]`, art @640), an album ->
 * [TidalV1Album.toAlbumSummary] paired with `artists[0].name`, an artist ->
 * [ArtistLink] with the picture @480 ([tidalV1ArtistPictureUrl]). All reused; no
 * new mapping is declared.
 */
fun searchResultsFrom(body: String, json: Json): TidalSearchResults {
    val response = runCatching { json.decodeFromString<TidalMultiTypeSearchResponse>(body) }.getOrNull()
        ?: return TidalSearchResults.EMPTY

    val tracks = response.tracks.items?.decodeEach<TidalV1PageTrack>(json)
        ?.map { it.toTrack() }
        .orEmpty()

    val albums = response.albums.items?.decodeEach<TidalV1Album>(json)
        ?.map { TidalAlbumResult(summary = it.toAlbumSummary(), artistName = it.artists.firstOrNull()?.name) }
        .orEmpty()

    val artists = response.artists.items?.decodeEach<TidalV1PageArtist>(json)
        ?.map { ArtistLink(id = it.id.toString(), name = it.name, artworkUrl = tidalV1ArtistPictureUrl(it.picture)) }
        .orEmpty()

    return TidalSearchResults(tracks = tracks, albums = albums, artists = artists)
}
