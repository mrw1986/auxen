package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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
    val totalNumberOfItems: Int = 0,
)

@Serializable
private data class TidalV1FavoriteArtist(val id: Long, val name: String = "", val picture: String? = null)

@Serializable
private data class TidalV1FavoriteArtistItem(val created: String? = null, val item: TidalV1FavoriteArtist? = null)

@Serializable
private data class TidalV1FavoriteArtistsResponse(
    val items: List<TidalV1FavoriteArtistItem> = emptyList(),
    val totalNumberOfItems: Int = 0,
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
 * Pure: one `favorites/albums` page body → [TidalFavoriteAlbum]s. Reuses
 * [tidalV1CoverUrl] (640 — it IS an album cover). Wrapped in `runCatching` so a
 * malformed body / v1 error envelope degrades to empty, never crashes the pull.
 */
fun favoriteAlbumsFrom(body: String, json: Json): List<TidalFavoriteAlbum> = runCatching {
    json.decodeFromString<TidalV1FavoriteAlbumsResponse>(body).items.mapNotNull { wrapper ->
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
}.getOrDefault(emptyList())

/**
 * Pure: one `favorites/artists` page body → [TidalFavoriteArtist]s. Reuses
 * [tidalV1ArtistPictureUrl] (480 — an artist picture is NOT served at 640, so
 * the cover builder must not be used here; see its KDoc). Same degrade-to-empty
 * discipline as [favoriteAlbumsFrom].
 */
fun favoriteArtistsFrom(body: String, json: Json): List<TidalFavoriteArtist> = runCatching {
    json.decodeFromString<TidalV1FavoriteArtistsResponse>(body).items.mapNotNull { wrapper ->
        val artist = wrapper.item ?: return@mapNotNull null
        TidalFavoriteArtist(
            id = artist.id.toString(),
            name = artist.name,
            pictureUrl = tidalV1ArtistPictureUrl(artist.picture),
            addedAtMillis = parseIso8601Millis(wrapper.created),
        )
    }
}.getOrDefault(emptyList())

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
