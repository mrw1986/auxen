package io.github.auxen.provider.tidal

import io.github.auxen.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The pure parts of the user's Tidal PLAYLISTS on the INTERNAL v1 API — the
 * favorite (own + followed) playlists the desktop reads via
 * `session.user.favorites.playlists()`, plus a playlist's items. Every
 * `*From(body, json)` / `*Url(...)` here is pure and unit-tested; the suspend
 * methods that call the network live on [TidalProvider] (untested, internal-v1
 * precedent). Reuses the existing [TidalV1PageTrack.toTrack] projection and
 * [parseIso8601Millis] — NO new track parser, NO new timestamp parser.
 *
 * Pull-only: this models READING playlists, never creating/editing one on Tidal.
 */

/**
 * A Tidal favorite playlist, projected narrow for the Playlists tab. Identified
 * by its [uuid] — a UUID string, NOT a numeric id (the reason imported playlists
 * key on `tidal_playlist_id`, not `source:sourceId`). Art is DEFERRED this slice
 * (PlaylistEntity has no art column), so no cover field.
 */
data class TidalPlaylist(val uuid: String, val title: String, val addedAtMillis: Long?)

/**
 * One page of a playlist's items → its playable [Track]s (non-track items like
 * videos dropped) PLUS the page's RAW item count. The raw count is carried
 * because pagination cannot key on the filtered track count: a full 50-item page
 * containing one video would project to 49 tracks and be mistaken for the last
 * page, truncating a large playlist. [TidalProvider.playlistItems] paginates on
 * [rawItemCount] and appends [tracks].
 */
data class TidalPlaylistPage(val tracks: List<Track>, val rawItemCount: Int)

// ---- internal-v1 response DTOs (every field nullable/defaulted — the tolerance
// every DTO in this package applies to a reverse-engineered endpoint) ----

@Serializable
private data class TidalV1PlaylistInfo(val uuid: String = "", val title: String = "", val numberOfTracks: Int = 0)

@Serializable
private data class TidalV1FavoritePlaylistItem(val created: String? = null, val item: TidalV1PlaylistInfo? = null)

@Serializable
private data class TidalV1FavoritePlaylistsResponse(
    val items: List<TidalV1FavoritePlaylistItem> = emptyList(),
    val totalNumberOfItems: Int = 0,
)

@Serializable
private data class TidalV1PlaylistItemWrapper(val type: String? = null, val item: TidalV1PageTrack? = null)

@Serializable
private data class TidalV1PlaylistItemsResponse(
    val items: List<TidalV1PlaylistItemWrapper> = emptyList(),
    val totalNumberOfItems: Int = 0,
)

/**
 * Pure: one `favorites/playlists` page body → [TidalPlaylist]s. A row with a
 * blank uuid (unusable — the uuid IS the import key) is dropped. Same
 * degrade-to-empty discipline as [favoriteAlbumsFrom]: a malformed body / v1
 * error envelope yields an empty list, never a crash.
 */
fun favoritePlaylistsFrom(body: String, json: Json): List<TidalPlaylist> = runCatching {
    json.decodeFromString<TidalV1FavoritePlaylistsResponse>(body).items.mapNotNull { wrapper ->
        val info = wrapper.item ?: return@mapNotNull null
        if (info.uuid.isBlank()) return@mapNotNull null
        TidalPlaylist(uuid = info.uuid, title = info.title, addedAtMillis = parseIso8601Millis(wrapper.created))
    }
}.getOrDefault(emptyList())

/**
 * Pure: one playlist `items` page body → a [TidalPlaylistPage]. Keeps only
 * `type == "track"` items (a playlist may also contain videos), projecting each
 * via the existing [TidalV1PageTrack.toTrack] (NO new track parser). Carries the
 * raw item count so the caller can detect the last page despite the filtering.
 * Degrades to an empty page on a malformed body / v1 error envelope.
 */
fun playlistTracksFrom(body: String, json: Json): TidalPlaylistPage = runCatching {
    val response = json.decodeFromString<TidalV1PlaylistItemsResponse>(body)
    val tracks = response.items.filter { it.type == "track" }.mapNotNull { it.item?.toTrack() }
    TidalPlaylistPage(tracks = tracks, rawItemCount = response.items.size)
}.getOrDefault(TidalPlaylistPage(emptyList(), 0))

// ---- request URL builders (pure) ----

/** `GET {apiBase}/users/{userId}/favorites/playlists?limit&offset&order=DATE&orderDirection=DESC&countryCode`. */
fun favoritePlaylistsUrl(apiBase: String, userId: String, countryCode: String, limit: Int, offset: Int): HttpUrl =
    "$apiBase/users/$userId/favorites/playlists".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("order", "DATE")
        .addQueryParameter("orderDirection", "DESC")
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `GET {apiBase}/playlists/{uuid}/items?limit&offset&countryCode` (a playlist's tracks, paged). */
fun playlistItemsUrl(apiBase: String, uuid: String, countryCode: String, limit: Int, offset: Int): HttpUrl =
    "$apiBase/playlists/$uuid/items".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("countryCode", countryCode)
        .build()
