package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The pure parts of the artist reads that live on the INTERNAL v1 API
 * (`api.tidal.com/v1`) — the four deliberate internal-v1 additions in this
 * slice (see Global Constraints): the **discography**, the **top tracks**,
 * **artist id by exact name**, and the **biography**.
 *
 * Why not official v2: probed on-device 2026-07-16, `GET /artists/8405?include=albums`
 * returned 20 *Fixxxer* variants (rough mixes, a vocal idea, `Fixxxer (Remastered)`
 * twice) — an arbitrary first page, not a discography. `?include=tracks` is the same
 * mechanism on the same document, so it is not a top-tracks list either. And v2
 * cannot express the desktop's discography anyway: `Albums_Attributes.albumType` is
 * `ALBUM | EP | SINGLE` with no COMPILATION, and `/artists/{id}/relationships/albums`
 * has no filter param.
 *
 * These builders are pure — they take [apiBase] rather than reading
 * `TidalProvider.API_BASE` (which is in a *private* companion) so every request
 * SHAPE is unit-testable with no live call, exactly like `TidalOfficialEndpoints`.
 *
 * Field names are verbatim from what `tidalapi.album.Album.parse` reads
 * (`tidalapi/album.py:114-145`): `id`, `title`, `cover`, `type`, `releaseDate`,
 * `explicit`, `numberOfTracks`. Every field but `id` is nullable/defaulted —
 * same tolerance as every other DTO in this package.
 */
@Serializable
data class TidalV1AlbumArtist(val id: Long? = null, val name: String = "")

@Serializable
data class TidalV1Album(
    val id: Long,
    val title: String = "",
    val cover: String? = null,
    /** v1's album type — `ALBUM` | `EP` | `SINGLE` | `COMPILATION`. Note COMPILATION, which official v2 cannot express. */
    val type: String? = null,
    val releaseDate: String? = null,
    val explicit: Boolean? = null,
    val numberOfTracks: Int? = null,
    val artist: TidalV1AlbumArtist? = null,
)

/** The v1 paged-collection envelope: `{ "limit", "offset", "totalNumberOfItems", "items": [...] }`. */
@Serializable
data class TidalV1AlbumsResponse(val items: List<TidalV1Album> = emptyList())

/**
 * `GET {apiBase}/artists/{artistId}/albums?limit=…&offset=0&countryCode=…`.
 *
 * Mirrors `tidalapi`'s `Artist.get_albums(limit=50)`, which sends ONLY
 * `{limit, offset}` (`tidalapi/artist.py:131-137`) on top of the `countryCode`
 * its `basic_request` attaches to every v1 call (`tidalapi/request.py:76`) —
 * which is also what this provider's own `search` already sends.
 *
 * **The ABSENCE of `filter` is the whole point**: it selects the endpoint's
 * default view, the real discography. `filter=EPSANDSINGLES` and
 * `filter=COMPILATIONS` are its siblings (`get_ep_singles` / `get_other`,
 * `tidalapi/artist.py:150-177`) and must NOT be sent here.
 * `TidalInternalArtistTest` asserts the absence.
 */
fun artistAlbumsUrl(apiBase: String, artistId: String, countryCode: String, limit: Int = 50): HttpUrl =
    "$apiBase/artists/$artistId/albums".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", "0")
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * `GET {apiBase}/artists/{artistId}/toptracks?limit=…&offset=0&countryCode=…`
 * — the artist's tracks **sorted by popularity**.
 *
 * Mirrors `tidalapi`'s `Artist.get_top_tracks(limit=50)`, which sends exactly
 * `{limit, offset}` to `artists/{id}/toptracks` (`tidalapi/artist.py:179-194`).
 * Its docstring is the guarantee we are buying: *"Queries TIDAL for the artists
 * tracks, sorted by popularity."* The desktop calls it at
 * `auxen/providers/tidal.py:906` and renders the result as the artist's top
 * tracks; this is the same request.
 *
 * This exists because official v2's `?include=tracks` is an arbitrary first
 * page in popularity-agnostic order — the same trap `?include=albums` was
 * proven to be. Do not "simplify" the artist page back onto the include.
 */
fun artistTopTracksUrl(apiBase: String, artistId: String, countryCode: String, limit: Int = 50): HttpUrl =
    "$apiBase/artists/$artistId/toptracks".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", "0")
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * `GET {apiBase}/search?query=…&limit=…&offset=0&types=artists&countryCode=…`
 * — resolve an artist id from a name, for artists we hold no Tidal track for.
 *
 * Mirrors the call the desktop makes daily: `session.search(artist_name,
 * models=[tidalapi.Artist], limit=5)` (`auxen/providers/tidal.py:860-862`),
 * which issues **`GET search`** with `{query, limit, offset, types}`
 * (`tidalapi/session.py:800-807`) plus the `countryCode` that `basic_request`
 * attaches to every v1 call (`tidalapi/request.py:76`).
 *
 * **`types` is the lowercase plural `artists`.** tidalapi derives it from
 * `convert_type(Artist, "type")`, and its `Case` enum assigns `pascal`/`scream`/
 * `lower` all the same value (`id`), which makes them **aliases for one member**
 * (`tidalapi/session.py:187-190`) — so the `case == Case.scream` branch always
 * matches and always lowercases. Executed to confirm: it emits `"artists"`.
 * That is the proven string; `"ARTISTS"` is not, and nobody has verified the
 * endpoint accepts it.
 *
 * **`GET search`, not `/search/artists`.** The provider's existing track search
 * hits `/search/tracks`, which answers a bare `{"items":[...]}`. The multi-type
 * `search` endpoint answers a per-type envelope instead (see
 * [artistIdFromSearch]). An artist sibling of `/search/tracks` may or may not
 * exist — nobody has checked, so we send the request that is known to work.
 *
 * **[limit] is 20, deliberately NOT the desktop's 5.** The desktop can afford 5
 * because it falls back to `artists[0]` regardless of ranking — the limit never
 * has to contain the right answer. Dropping that fallback makes the limit
 * load-bearing: an exact match ranked below the cut resolves **null** and the
 * page renders un-enriched. 20 costs one marginally larger response and makes
 * that miss far less likely. The residual trade-off stands and is the one this
 * decision prefers: a miss, never a wrong artist.
 */
fun artistSearchUrl(apiBase: String, query: String, countryCode: String, limit: Int = 20): HttpUrl =
    "$apiBase/search".toHttpUrl().newBuilder()
        .addQueryParameter("query", query)
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", "0")
        .addQueryParameter("types", "artists")
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * v1 cover uuid → CDN URL, the `resources.tidal.com/images/{uuid, dashes → slashes}/640x640.jpg`
 * pattern the desktop uses (`_album_to_dict`, `auxen/providers/tidal.py:354-361`).
 *
 * Deliberately duplicated rather than refactored out of [TidalProvider]'s
 * `toTrack`, which builds the same URL inline: this slice adds to the
 * internal-v1 provider but **changes no existing method of it** (Global
 * Constraints). Fold the two together in a later slice that is allowed to.
 */
fun tidalV1CoverUrl(cover: String?): String? =
    cover?.takeIf { it.isNotBlank() }
        ?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg" }

/**
 * Pure projection: an `artists/{id}/albums` v1 response body → the same
 * [AlbumSummary] cards the official-v2 projections produce, so the artist
 * discography row is ONE uniform type regardless of which API filled it.
 *
 * A malformed body — a v1 error envelope, an HTML error page, anything — yields
 * an empty list rather than throwing: a missing discography must never take
 * down the rest of the artist page (the desktop swallows the same failure at
 * `providers/tidal.py:899-900`).
 */
fun artistAlbumsFrom(body: String, json: Json): List<AlbumSummary> =
    runCatching { json.decodeFromString<TidalV1AlbumsResponse>(body) }.getOrNull()
        ?.items
        ?.map { it.toAlbumSummary() }
        .orEmpty()

/**
 * v1 album item -> [AlbumSummary]. `internal` rather than private because
 * `pages/artist`'s `ALBUM_LIST` items are the SAME v1 album shape and
 * [artistPageFrom] maps them through here — one mapper, so the two cannot drift.
 * (Those items carry no `type` key, so `albumType` is null for them; nothing on
 * the artist page renders it, and the module title carries what `type` cannot.)
 */
internal fun TidalV1Album.toAlbumSummary() = AlbumSummary(
    id = id.toString(),
    title = title,
    albumType = type,
    // v1 releaseDate is "YYYY-MM-DD", the same shape releaseYearOf already parses
    // for v2's Albums_Attributes.releaseDate. (The desktop leaves the year null
    // on Tidal album cards; v1 gives us the real one, and the Android AlbumCard
    // does not render a year either way — so this is strictly better and inert.)
    releaseYear = releaseYearOf(releaseDate),
    explicit = explicit ?: false,
    artworkUrl = tidalV1CoverUrl(cover),
)

/**
 * A search hit's artist. Fields verbatim from what `tidalapi`'s `parse_artist`
 * reads (`tidalapi/artist.py:83-98`): `id`, `name`, `picture`. We need only the
 * first two — `picture` is deliberately not projected, because the artist's
 * profile art comes from the official v2 document once the id is resolved.
 */
@Serializable
data class TidalV1SearchArtist(val id: Long, val name: String = "")

/** One per-type page inside the `search` response: `{ "items": [...], "limit", "offset", "totalNumberOfItems" }`. */
@Serializable
data class TidalV1ArtistsPage(val items: List<TidalV1SearchArtist> = emptyList())

/**
 * The `GET search` response envelope. Unlike `/search/tracks`' bare
 * `{"items":[...]}`, this endpoint nests each requested type under its own key:
 * `{"artists": {"items": [...]}, "albums": {...}, "tracks": {...}, …}` —
 * exactly how `tidalapi` reads it (`json_obj["artists"]` →
 * `map_json(...)["items"]`, `session.py:807-813` + `request.py:217`).
 *
 * Only `artists` is declared; `Json { ignoreUnknownKeys = true }` drops the
 * rest, and the default keeps a missing key from throwing.
 */
@Serializable
data class TidalV1SearchResponse(val artists: TidalV1ArtistsPage = TidalV1ArtistsPage())

/**
 * Pure projection: a `search?types=artists` body + the name we searched for →
 * that artist's catalog id, or **null**.
 *
 * **EXACT case-insensitive name match ONLY. There is deliberately no
 * best-match fallback, and adding one would be a regression.** The desktop does
 * `best = artists[0]` when nothing matches exactly
 * (`auxen/providers/tidal.py:874-876`), which silently attaches a *different*
 * artist's biography, discography and top tracks to the page — the user's own
 * desktop exhibits this today (artist images resolve for some artists and not
 * others). We are **fixing** that here, not porting it. A null means the artist
 * page renders exactly what it renders today, which is strictly better than a
 * confident wrong answer.
 *
 * `lowercase()` on both sides mirrors Python's `a_name.lower() ==
 * artist_name.lower()` and is `Locale.ROOT`, keeping the match independent of
 * device locale — the same reasoning as [dedupRemoteAlbums].
 *
 * A malformed body (a v1 error envelope, an HTML error page, anything) yields
 * null rather than throwing: a failed id resolution must degrade the artist
 * page, never break it.
 */
fun artistIdFromSearch(body: String, json: Json, artistName: String): String? {
    val wanted = artistName.lowercase()
    return runCatching { json.decodeFromString<TidalV1SearchResponse>(body) }.getOrNull()
        ?.artists?.items
        ?.firstOrNull { it.name.lowercase() == wanted }
        ?.id?.toString()
}

/**
 * `GET {apiBase}/artists/{artistId}/bio?countryCode=…` — the artist biography.
 *
 * **This is on internal-v1 because official v2 has no biography at our tier.**
 * Not a guess: `GET /artists/8405?include=biography` came back with the linkage
 * present and the `artistBiographies` resource absent from `included`, and the
 * dedicated `/artists/{id}/relationships/biography?include=biography` fallback
 * then returned nothing either — for Metallica, and again on-device for Tommy
 * Richman. Two real artists, one of them among the most famous acts alive. That
 * fallback (and its whole client/endpoint chain) was DELETED when this landed;
 * do not resurrect it.
 *
 * Mirrors `tidalapi`'s `Artist.get_bio()`, which is
 * `request("GET", f"artists/{self.id}/bio").json()["text"]`
 * (`tidalapi/artist.py:212-219`) — a plain v1 GET with **no params of its own**,
 * so the only query parameter is the `countryCode` that `basic_request` attaches
 * to every v1 call (`tidalapi/request.py:75-76`). The desktop calls it at
 * `auxen/providers/tidal.py:911-916` and renders rich markup out of the result.
 */
fun artistBioUrl(apiBase: String, artistId: String, countryCode: String): HttpUrl =
    "$apiBase/artists/$artistId/bio".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/** The v1 bio envelope: `{ "source", "lastUpdated", "text" }` — `get_bio` reads only `text`. */
@Serializable
data class TidalV1Bio(val text: String? = null)

private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val WIMP_LINK = Regex(
    """\[wimpLink[^\]]*](.*?)\[/wimpLink]""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val WIMP_LINK_RESIDUAL = Regex("""\[/?wimpLink[^\]]*]""", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("""<[^>]+>""")
private val BLANK_LINES = Regex("""\n{3,}""")

/**
 * Tidal bio markup → display text, mirroring the desktop's `_format_bio`
 * (`auxen/views/artist_detail.py:1282-1325`). The endpoint returns markup, not
 * prose — that the desktop bothers to parse it is the proof the payload is real,
 * rich content.
 *
 * The rules, in order:
 *  1. `<br>` / `<br/>` / `<br />` → newline. **Case-insensitively**, which the
 *     desktop's regex is not: there a `<BR>` falls through to the generic tag
 *     strip and silently loses the line break. Same intent, one less bug.
 *  2. `[wimpLink artistId="123"]Name[/wimpLink]` → `Name`.
 *  3. Any UNPAIRED `[wimpLink …]`/`[/wimpLink]` → dropped. Rule 2 cannot match a
 *     half-tag, and the tag syntax is square-bracketed so rule 4 will not catch
 *     it either — without this sweep a malformed bio prints the raw tag.
 *  4. Every remaining HTML tag → dropped.
 *  5. Runs of 3+ newlines → one blank line; trim.
 *
 * **Deliberate divergences from the desktop, both forced by the target:**
 * - **Plain text, not bold.** The desktop wraps wimpLink names in Pango `<b>`
 *   and preserves `<b>/<i>/<u>/…`; this returns a plain `String` for a Compose
 *   `Text`. Emphasis is lost, never information. Bold would mean an
 *   `AnnotatedString`, which would drag `androidx.compose.ui.text` into this
 *   provider package — a layering inversion for italics.
 * - **No entity escaping.** The desktop's `&` → `&amp;` step exists ONLY because
 *   Pango parses its input as markup. Compose `Text` renders a literal string,
 *   so porting that step would print `&amp;` on screen where the artist wrote
 *   `&`. Its absence here is the correct port, not an omission.
 */
fun formatTidalBio(raw: String): String =
    raw.replace(BR_TAG, "\n")
        .replace(WIMP_LINK) { it.groupValues[1] }
        .replace(WIMP_LINK_RESIDUAL, "")
        .replace(HTML_TAG, "")
        .replace(BLANK_LINES, "\n\n")
        .trim()

/**
 * Pure projection: an `artists/{id}/bio` body → display-ready biography text, or
 * **null**.
 *
 * Parsing and formatting are fused deliberately: [formatTidalBio] is not optional
 * decoration, it is what stops raw `[wimpLink]`/HTML markup reaching the screen.
 * A caller handed the raw `text` field could forget to call it; a caller handed
 * this cannot.
 *
 * Null — never an error — for a malformed body, a v1 error envelope, a missing
 * `text`, or markup that renders to nothing. Every one of those means the same
 * thing to the UI: hide the section. The desktop swallows the identical failure
 * in a bare `except` ("404s are expected for lesser-known artists",
 * `providers/tidal.py:911-916`).
 */
fun artistBioFrom(body: String, json: Json): String? =
    runCatching { json.decodeFromString<TidalV1Bio>(body) }.getOrNull()
        ?.text
        ?.let { formatTidalBio(it) }
        ?.takeIf { it.isNotBlank() }
