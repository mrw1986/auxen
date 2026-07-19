package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import io.github.auxen.model.Track
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

/**
 * The pure parts of the user's Tidal favorites on the INTERNAL v1 API — the
 * saved-albums / followed-artists collection the desktop reads via
 * `session.user.favorites.albums()/artists()`. Every `*From(body, json)` and
 * `*Url(...)` here is pure and unit-tested; the suspend methods that call the
 * network live on [TidalProvider] (untested, internal-v1 precedent).
 *
 * Internal-v1 is richer than the retired official path: a favorite album
 * carries its cover + artist + track count, a favorite artist its name +
 * picture — so a pulled favorite renders fully (no title-only placeholder).
 * Field names are verbatim from the probed schema and `tidalapi/user.py`.
 */

/** A Tidal saved album, projected narrow for Collection (cover + title + artist). */
data class TidalFavoriteAlbum(
    val id: String,
    val title: String,
    val artistName: String?,
    val coverUrl: String?,
    val addedAtMillis: Long?,
)

/** A Tidal followed artist, projected narrow for Collection (picture + name). */
data class TidalFavoriteArtist(
    val id: String,
    val name: String,
    val pictureUrl: String?,
    val addedAtMillis: Long?,
)

/**
 * A Tidal favorite TRACK, projected to a fully-playable [Track] (via
 * [TidalV1PageTrack.toTrack]) paired with its remote `created` millis. Unlike
 * the album/artist favorites — which back narrow local tables and so get bespoke
 * persistence DTOs — a favorite track reuses the EXISTING `tracks`+`favorites`
 * tables, so the projection IS a [Track]; [addedAtMillis] rides alongside so the
 * pull can favorite it at Tidal's own timestamp (the Tracks tab orders by
 * added_at DESC). This is a transient parse result, not a stored row.
 */
data class TidalFavoriteTrack(val track: Track, val addedAtMillis: Long?)

/**
 * One strictly-decoded favorites page: [items] the projected rows, [totalCount]
 * the envelope's OWN `totalNumberOfItems` — carried alongside so the read loop
 * ([TidalProvider.favoriteAlbums] and its siblings) can apply the M1
 * cross-check ([orNullIfDriftedEmpty]) to the FIRST page's total against the
 * fully-accumulated result — and [rawCount], the number of wrapper entries
 * actually on the wire BEFORE projection (`response.items.size`). [rawCount]
 * can exceed `items.size`: an UNAVAILABLE favorite (its wrapper's `item` is
 * `null`) is still counted on the wire but drops out of the projection. The
 * read loop's pagination MUST key off [rawCount] (C1) — keying off
 * `items.size` makes a full page with one unavailable favorite look SHORT,
 * ending pagination early and truncating the fetch, which then reads every
 * un-fetched favorite as gone and deletes it on mirror.
 */
data class FavoritesPage<T>(val items: List<T>, val totalCount: Int, val rawCount: Int)

/**
 * The outcome of one favorites READ ([TidalProvider.favoriteAlbums] and its
 * siblings): [items] is `null` on ANY failure (not logged in, a page fetch
 * failed, a page didn't strictly decode, or the M1 cross-check fired) or a
 * complete, possibly-empty list on success. [fullyProjected] is only
 * meaningful when [items] is non-null and is the flag the mirror gates DELETES
 * on: `true` only when the read is BOTH COMPLETE (it paged to the server's EMPTY
 * page rather than bailing at the [maxItems] cap — so a capped read on a huge
 * collection can't delete the un-fetched tail) and LOSSLESS (every RETURNED
 * wrapper projected — `rawSeen == items.size` — C1); `false` when a wrapper
 * failed to project (an unavailable favorite, [FavoritesPage]'s `item == null`
 * case, whose id is UNRECOVERABLE) OR the read stopped at the cap. NOTE: the
 * endpoint returns SHORT non-final pages (dropping un-returnable items), so
 * completeness is the empty page, NOT `rawSeen == totalNumberOfItems`. See
 * [paginateFavorites], which computes it. The mirror pulls
 * ([TidalCollectionSync]'s `gatedOnFullProjection`) then let adds proceed
 * regardless (a resolved remote favorite is always safe to add), but skip ALL
 * deletes that cycle unless the read is fully trusted — rather than risk
 * deleting an item that's merely temporarily unavailable or simply not
 * fetched yet.
 */
data class FavoritesFetch<T>(val items: List<T>?, val fullyProjected: Boolean)

// ---- internal-v1 response DTOs (every field nullable/defaulted, the tolerance
// every DTO in this package applies to a reverse-engineered endpoint) ----

@Serializable
private data class TidalV1FavoriteAlbum(
    val id: Long,
    val title: String = "",
    val cover: String? = null,
    val artist: TidalV1AlbumArtist? = null,
    val artists: List<TidalV1AlbumArtist> = emptyList(),
)

@Serializable
private data class TidalV1FavoriteAlbumItem(val created: String? = null, val item: TidalV1FavoriteAlbum? = null)

@Serializable
private data class TidalV1FavoriteAlbumsResponse(
    val items: List<TidalV1FavoriteAlbumItem> = emptyList(),
    val totalNumberOfItems: Int,
)

@Serializable
private data class TidalV1FavoriteArtist(val id: Long, val name: String = "", val picture: String? = null)

@Serializable
private data class TidalV1FavoriteArtistItem(val created: String? = null, val item: TidalV1FavoriteArtist? = null)

@Serializable
private data class TidalV1FavoriteArtistsResponse(
    val items: List<TidalV1FavoriteArtistItem> = emptyList(),
    val totalNumberOfItems: Int,
)

@Serializable
private data class TidalV1FavoriteTrackItem(val created: String? = null, val item: TidalV1PageTrack? = null)

@Serializable
private data class TidalV1FavoriteTracksResponse(
    val items: List<TidalV1FavoriteTrackItem> = emptyList(),
    val totalNumberOfItems: Int,
)

/**
 * A formatter for the internal-v1 `created` timestamp, whose offset is the bare
 * numeric `+0000` form (NO colon) — e.g. `2026-07-03T12:17:29.572+0000`, the
 * exact shape probed live. Neither `OffsetDateTime.parse` (needs `+00:00`/`Z`)
 * nor `Instant.parse` (needs `Z`) accepts it, so this fallback does.
 */
private val TIDAL_CREATED_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
    .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    .appendOffset("+HHMM", "Z")
    .toFormatter()

/**
 * Parse an ISO-8601 date-time (`created`) to epoch millis. Tolerant of the three
 * forms seen on the wire: `+00:00`/`+01:00` (colon offset), `Z` (UTC), and — the
 * form the internal-v1 favorites endpoint actually returns — `+0000` (bare
 * numeric offset, no colon). Null on absence/parse failure.
 */
fun parseIso8601Millis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value, TIDAL_CREATED_FORMAT).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

/**
 * M1 cross-check (data-loss guard): applied to a completed favorites read's
 * accumulated items against the FIRST page's [firstPageTotalCount]. Closes the
 * one wipe path [favoriteAlbumsPageOrNull] et al cannot: a WELL-FORMED envelope
 * whose items all fail to PROJECT (e.g. every wrapper's `item` is null — a
 * schema drift on the nested item shape) still strictly decodes, so the read
 * would otherwise trust an empty `[]` and the mirror would delete every local
 * TIDAL favorite. Returns `null` (untrusted — the mirror reconciles nothing)
 * when the accumulated result is empty but the server's own total says it
 * should not be; returns the list unchanged otherwise.
 *
 * Provably false-null-safe: a GENUINE empty collection reports
 * `totalNumberOfItems == 0`, so `0 != 0` is false and a trusted `[]` still flows
 * through — the to-zero mirror still works. The check is `!= 0` (not `> 0`) so a
 * NONSENSICAL total — a negative from a crafted/garbled body — is treated as
 * drift (untrusted null), never as a genuine empty that would delete-all. A
 * legitimate trailing empty page
 * (pagination's natural end) only occurs once earlier pages had items, so the
 * accumulated list is already non-empty by then regardless of that page's own
 * total.
 */
fun <T> List<T>.orNullIfDriftedEmpty(firstPageTotalCount: Int): List<T>? =
    if (isEmpty() && firstPageTotalCount != 0) null else this

/**
 * The pure favorites read loop (data-loss-critical, so unit-tested independent
 * of the network). [fetchPage] returns the strictly-decoded [FavoritesPage] for
 * an offset, or `null` on ANY failure — a page fetch that failed or a body that
 * didn't strictly decode — which aborts the whole read to an untrusted
 * [FavoritesFetch] (`items == null`, so the mirror reconciles nothing).
 *
 * ⚠️ The internal-v1 favorites endpoints return SHORT NON-FINAL pages: the
 * server DROPS un-returnable entries (videos, region-locked) from a page's
 * `items` while still counting them in `totalNumberOfItems`, so a page can carry
 * FEWER than [pageSize] items even though more remain (probed live 2026-07-19:
 * tracks total 124 paged as [47, 47, 21, 0]; artists 110 as [49, 50, 10, 0]).
 * Pagination therefore advances by the OFFSET WINDOW (`offset += pageSize`) and
 * ends only on an EMPTY page (`rawCount == 0`, i.e. offset past the collection)
 * or the [maxItems] cap — NEVER on a short page. Breaking on a short page (the
 * earlier design) truncated the read to the first partial page, which the delete
 * gate below then read as "incomplete" and so silently stopped mirroring every
 * removal. `rawSeen` (summed raw wrapper count) is legitimately LESS than
 * `totalNumberOfItems` here (the dropped items), so it is NOT a completeness
 * yardstick — the empty page is.
 *
 * [FavoritesFetch.fullyProjected] — the flag the mirror gates DELETES on — is
 * `true` only when the read is BOTH complete and lossless:
 * - COMPLETE: the server ended the list with an EMPTY page (`reachedEnd`), so we
 *   paged the whole offset range. Bailing at the [maxItems] cap leaves it false,
 *   suppressing deletes rather than wiping an un-fetched tail on a >[maxItems]
 *   collection. Ending on the empty page (not on `offset >= total`) also makes
 *   this robust to an UNDERSTATED total — we keep paging until the server itself
 *   returns empty. (A collection large enough to hit [maxItems] before an empty
 *   page is conservatively treated as incomplete — the safe direction.)
 * - LOSSLESS (C1): `rawSeen == items.size` — every RETURNED wrapper projected. A
 *   null-item wrapper (an unavailable favorite whose id is unrecoverable) makes
 *   this false, suppressing deletes so a merely-temporarily-unavailable favorite
 *   is never deleted. (This endpoint DROPS rather than nulls, so in practice
 *   rawCount == items.size; the guard is defence-in-depth.)
 * Adds always proceed (a resolved remote favorite is safe to add regardless);
 * only deletes wait for a fully-trusted read.
 *
 * Residuals (MINOR): (a) a genuine mid-collection EMPTY page from a server hiccup
 * would look like the end and truncate the read — an empty page is structurally
 * indistinguishable from a true end; the M1 cross-check ([orNullIfDriftedEmpty])
 * still catches the worst case (page 0 empty while the total is non-zero). (b) a
 * favorite that transitions to un-returnable (region-lock) BETWEEN syncs — was
 * fetched+stored before, now dropped from the response — reconciles as removed;
 * inherent to any mirror that can't tell "gone" from "temporarily hidden".
 */
suspend fun <T> paginateFavorites(
    pageSize: Int,
    maxItems: Int,
    fetchPage: suspend (offset: Int) -> FavoritesPage<T>?,
): FavoritesFetch<T> {
    val all = mutableListOf<T>()
    var offset = 0
    var firstPageTotalCount = 0
    var rawSeen = 0
    var reachedEnd = false
    while (offset < maxItems) {
        val page = fetchPage(offset) ?: return FavoritesFetch(null, fullyProjected = false)
        if (offset == 0) firstPageTotalCount = page.totalCount
        rawSeen += page.rawCount
        all += page.items
        // END on an EMPTY page only. A short (< pageSize) page is NORMAL here —
        // the server drops un-returnable items mid-collection — so breaking on a
        // short page would truncate the read and (via the delete gate) stop
        // mirroring removals. The empty page means offset is past the collection.
        if (page.rawCount == 0) { reachedEnd = true; break }
        offset += pageSize
    }
    val trusted = all.orNullIfDriftedEmpty(firstPageTotalCount)
        ?: return FavoritesFetch(null, fullyProjected = false)
    // COMPLETE only when the server ended the list with an empty page (we paged
    // the whole offset range); bailing at [maxItems] leaves it false and
    // suppresses deletes. We do NOT require rawSeen >= firstPageTotalCount — this
    // endpoint returns fewer items than the total (dropped un-returnable entries),
    // so that check would never pass and would perma-suppress every removal.
    return FavoritesFetch(trusted, fullyProjected = reachedEnd && rawSeen == trusted.size)
}

/** Pure projection of a decoded albums page — the tested body shared by the lenient and strict parsers. */
private fun TidalV1FavoriteAlbumsResponse.toFavoriteAlbums(): List<TidalFavoriteAlbum> = items.mapNotNull { wrapper ->
    val album = wrapper.item ?: return@mapNotNull null
    val artist = album.artist ?: album.artists.firstOrNull()
    TidalFavoriteAlbum(
        id = album.id.toString(),
        title = album.title,
        artistName = artist?.name?.ifBlank { null },
        coverUrl = tidalV1CoverUrl(album.cover),
        addedAtMillis = parseIso8601Millis(wrapper.created),
    )
}

/**
 * LENIENT: one `favorites/albums` page body -> [TidalFavoriteAlbum]s, degrading a
 * malformed body / v1 error envelope to EMPTY (never crashes). Retained as the
 * tested projection surface; the mirror-pull reads use [favoriteAlbumsPageOrNull].
 */
fun favoriteAlbumsFrom(body: String, json: Json): List<TidalFavoriteAlbum> = runCatching {
    json.decodeFromString<TidalV1FavoriteAlbumsResponse>(body).toFavoriteAlbums()
}.getOrDefault(emptyList())

/**
 * STRICT (mirror-safe): the same projection, but `null` on ANY decode failure —
 * an HTML captive-portal page, a v1 error envelope, or drift that drops/renames
 * `items` (now that `totalNumberOfItems` is REQUIRED, all three fail to decode).
 * A GENUINE page — including a genuine empty `{"items":[],"totalNumberOfItems":0}`
 * — decodes to a TRUSTED [FavoritesPage], its `totalCount` carrying the
 * envelope's OWN `totalNumberOfItems` alongside the projected items. The
 * favorites reads gate on `null` here so an ambiguous empty from a bad 200
 * becomes `null` (mirror deletes nothing), never a trusted `[]` that would wipe
 * the collection; `totalCount` feeds the read-level M1 cross-check
 * ([orNullIfDriftedEmpty]) for the remaining case — a well-formed envelope
 * whose items all fail to PROJECT (not decode).
 */
fun favoriteAlbumsPageOrNull(body: String, json: Json): FavoritesPage<TidalFavoriteAlbum>? = runCatching {
    val decoded = json.decodeFromString<TidalV1FavoriteAlbumsResponse>(body)
    FavoritesPage(decoded.toFavoriteAlbums(), decoded.totalNumberOfItems, decoded.items.size)
}.getOrNull()

/** Pure projection of a decoded artists page — the tested body shared by the lenient and strict parsers. */
private fun TidalV1FavoriteArtistsResponse.toFavoriteArtists(): List<TidalFavoriteArtist> = items.mapNotNull { wrapper ->
    val artist = wrapper.item ?: return@mapNotNull null
    TidalFavoriteArtist(
        id = artist.id.toString(),
        name = artist.name,
        pictureUrl = tidalV1ArtistPictureUrl(artist.picture),
        addedAtMillis = parseIso8601Millis(wrapper.created),
    )
}

/**
 * LENIENT: one `favorites/artists` page body -> [TidalFavoriteArtist]s, degrading a
 * malformed body / v1 error envelope to EMPTY (never crashes). Retained as the
 * tested projection surface; the mirror-pull reads use [favoriteArtistsPageOrNull].
 */
fun favoriteArtistsFrom(body: String, json: Json): List<TidalFavoriteArtist> = runCatching {
    json.decodeFromString<TidalV1FavoriteArtistsResponse>(body).toFavoriteArtists()
}.getOrDefault(emptyList())

/**
 * STRICT (mirror-safe): the same projection, but `null` on ANY decode failure —
 * see [favoriteAlbumsPageOrNull] for the full rationale (captive portal / error
 * envelope / drift all fail now that `totalNumberOfItems` is REQUIRED, while a
 * genuine — including genuine-empty — page decodes to a trusted list).
 */
fun favoriteArtistsPageOrNull(body: String, json: Json): FavoritesPage<TidalFavoriteArtist>? = runCatching {
    val decoded = json.decodeFromString<TidalV1FavoriteArtistsResponse>(body)
    FavoritesPage(decoded.toFavoriteArtists(), decoded.totalNumberOfItems, decoded.items.size)
}.getOrNull()

/** Pure projection of a decoded tracks page — the tested body shared by the lenient and strict parsers. */
private fun TidalV1FavoriteTracksResponse.toFavoriteTracks(): List<TidalFavoriteTrack> = items.mapNotNull { wrapper ->
    val track = wrapper.item?.toTrack() ?: return@mapNotNull null
    TidalFavoriteTrack(track = track, addedAtMillis = parseIso8601Millis(wrapper.created))
}

/**
 * LENIENT: one `favorites/tracks` page body -> [TidalFavoriteTrack]s, reusing the
 * existing [TidalV1PageTrack.toTrack] projection (artists[0].name, album cover
 * @640, quality, source = TIDAL) — NO new track parser. Degrades a malformed
 * body / v1 error envelope to EMPTY (never crashes). Retained as the tested
 * projection surface; the mirror-pull reads use [favoriteTracksPageOrNull].
 */
fun favoriteTracksFrom(body: String, json: Json): List<TidalFavoriteTrack> = runCatching {
    json.decodeFromString<TidalV1FavoriteTracksResponse>(body).toFavoriteTracks()
}.getOrDefault(emptyList())

/**
 * STRICT (mirror-safe): the same projection, but `null` on ANY decode failure —
 * see [favoriteAlbumsPageOrNull] for the full rationale (captive portal / error
 * envelope / drift all fail now that `totalNumberOfItems` is REQUIRED, while a
 * genuine — including genuine-empty — page decodes to a trusted list).
 */
fun favoriteTracksPageOrNull(body: String, json: Json): FavoritesPage<TidalFavoriteTrack>? = runCatching {
    val decoded = json.decodeFromString<TidalV1FavoriteTracksResponse>(body)
    FavoritesPage(decoded.toFavoriteTracks(), decoded.totalNumberOfItems, decoded.items.size)
}.getOrNull()

// ---- request URL builders (pure; `order=DATE&orderDirection=DESC` gives
// most-recent-first, matching the probed schema) ----

/** `GET {apiBase}/users/{userId}/favorites/albums?limit&offset&order=DATE&orderDirection=DESC&countryCode`. */
fun favoriteAlbumsUrl(apiBase: String, userId: String, countryCode: String, limit: Int, offset: Int): HttpUrl =
    "$apiBase/users/$userId/favorites/albums".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("order", "DATE")
        .addQueryParameter("orderDirection", "DESC")
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `GET {apiBase}/users/{userId}/favorites/artists?limit&offset&order=DATE&orderDirection=DESC&countryCode`. */
fun favoriteArtistsUrl(apiBase: String, userId: String, countryCode: String, limit: Int, offset: Int): HttpUrl =
    "$apiBase/users/$userId/favorites/artists".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("order", "DATE")
        .addQueryParameter("orderDirection", "DESC")
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `POST {apiBase}/users/{userId}/favorites/albums?countryCode` (form body `albumId=<id>`). */
fun favoriteAlbumsWriteUrl(apiBase: String, userId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/albums".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `POST {apiBase}/users/{userId}/favorites/artists?countryCode` (form body `artistId=<id>`). */
fun favoriteArtistsWriteUrl(apiBase: String, userId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/artists".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `DELETE {apiBase}/users/{userId}/favorites/albums/{albumId}?countryCode`. */
fun favoriteAlbumDeleteUrl(apiBase: String, userId: String, albumId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/albums/$albumId".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `DELETE {apiBase}/users/{userId}/favorites/artists/{artistId}?countryCode`. */
fun favoriteArtistDeleteUrl(apiBase: String, userId: String, artistId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/artists/$artistId".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `GET {apiBase}/users/{userId}/favorites/tracks?limit&offset&order=DATE&orderDirection=DESC&countryCode`. */
fun favoriteTracksUrl(apiBase: String, userId: String, countryCode: String, limit: Int, offset: Int): HttpUrl =
    "$apiBase/users/$userId/favorites/tracks".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("order", "DATE")
        .addQueryParameter("orderDirection", "DESC")
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `POST {apiBase}/users/{userId}/favorites/tracks?countryCode` (form body `trackId=<id>`). */
fun favoriteTracksWriteUrl(apiBase: String, userId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/tracks".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `DELETE {apiBase}/users/{userId}/favorites/tracks/{trackId}?countryCode`. */
fun favoriteTrackDeleteUrl(apiBase: String, userId: String, trackId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/tracks/$trackId".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()
