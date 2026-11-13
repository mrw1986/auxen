package io.github.auxen.provider.tidal

import io.github.auxen.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The pure parts of album detail on the INTERNAL v1 API — `GET albums/{id}`
 * (metadata) + `GET albums/{id}/items` (tracklist) — plus the seed-track
 * `GET tracks/{id}` read that resolves an artist/album id off one Tidal track.
 *
 * This replaced the official-v2 album path (a JSON:API compound document via
 * `TidalMetadataRepository.albumDetail`). Internal-v1 is strictly better here:
 * the `items` entries carry `trackNumber` AND `volumeNumber` NATIVELY, so a
 * multi-volume album numbers 1..n per disc without the official path's trick of
 * reconstructing volume numbers from `Albums_Items_Resource_Identifier_Meta`
 * linkage. Field names are verbatim from `tidalapi/album.py` (`Album.parse` /
 * `Album.items`) and from what this package already models.
 *
 * These builders are pure — they take [apiBase] rather than reading
 * `TidalProvider.API_BASE` (a private companion) so every request SHAPE is
 * unit-testable with no live call, exactly like the other internal-v1 files.
 */

/** Full album detail: header art + metadata + ordered tracklist + similar albums. */
data class TidalAlbumDetail(
    val id: String,
    val title: String,
    val artistName: String,
    val artistId: String?,
    val albumType: String?,
    val releaseYear: Int?,
    val explicit: Boolean,
    val artworkUrl: String?,
    val tracks: List<Track>,
    val similarAlbums: List<AlbumSummary>,
)

/**
 * `albums/{id}` metadata. Every field but `id` is nullable/defaulted — the same
 * tolerance every DTO in this package applies to a reverse-engineered endpoint.
 * `type` is v1's album type (`ALBUM`|`EP`|`SINGLE`|`COMPILATION`); the album
 * screen renders it when present and simply omits it when absent.
 */
@Serializable
data class TidalV1AlbumMeta(
    val id: Long,
    val title: String = "",
    val cover: String? = null,
    val releaseDate: String? = null,
    val explicit: Boolean? = null,
    val numberOfTracks: Int? = null,
    val type: String? = null,
    val artist: TidalV1AlbumArtist? = null,
    val artists: List<TidalV1AlbumArtist> = emptyList(),
)

/**
 * One `albums/{id}/items` entry: `{ "type": "track"|"video", "item": {<track>} }`
 * — the wrapper shape `tidalapi`'s `Album.items()` reads (`item["item"]`,
 * `item["type"]`). The inner track is the SAME v1 track shape [TidalV1PageTrack]
 * models (it carries `artists` plural and the native `trackNumber`/`volumeNumber`),
 * so it is reused rather than re-declared. A `video` item, or one whose `item`
 * failed to decode, is dropped by [albumDetailFromV1].
 */
@Serializable
data class TidalV1AlbumItem(val type: String? = null, val item: TidalV1PageTrack? = null)

/** The `albums/{id}/items` envelope: `{ items, totalNumberOfItems, limit, offset }` — the last two unmodelled. */
@Serializable
data class TidalV1AlbumItemsResponse(
    val items: List<TidalV1AlbumItem> = emptyList(),
    val totalNumberOfItems: Int = 0,
)

/** `GET {apiBase}/albums/{albumId}?countryCode=…`. */
fun albumMetadataUrl(apiBase: String, albumId: String, countryCode: String): HttpUrl =
    "$apiBase/albums/$albumId".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `GET {apiBase}/albums/{albumId}/items?limit=…&offset=…&countryCode=…`. */
fun albumItemsUrl(apiBase: String, albumId: String, countryCode: String, limit: Int, offset: Int): HttpUrl =
    "$apiBase/albums/$albumId/items".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * `GET {apiBase}/tracks/{trackId}?countryCode=…` — the bare v1 track read, used
 * ONLY to resolve a seed track's artist/album ids (the internal-v1 replacement
 * for official `trackRelations`). It returns the same v1 track object search and
 * toptracks return, so [TidalV1PageTrack] decodes it.
 */
fun trackRelationsUrl(apiBase: String, trackId: String, countryCode: String): HttpUrl =
    "$apiBase/tracks/$trackId".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * Pure: a `tracks/{id}` body → the artist/album ids a seed track resolves to.
 *
 * `artistId` reads `artists[0].id` — this payload has no singular `artist` key
 * (see [TidalV1PageTrack]'s KDoc). `isrc` is left null: it backed an official-v2
 * ISRC fallback that is unused while direct id-equivalence holds (gate-proven).
 * Null for a v1 error envelope or non-JSON — a failed id resolution must degrade
 * the screen, never break it.
 */
fun trackRelationsFromV1(body: String, json: Json): TrackRelations? =
    runCatching { json.decodeFromString<TidalV1PageTrack>(body) }.getOrNull()?.let {
        TrackRelations(
            artistId = it.artists.firstOrNull()?.id?.toString(),
            albumId = it.album?.id?.toString(),
            isrc = null,
        )
    }

/**
 * Pure: `albums/{id}` metadata + every `albums/{id}/items` page body →
 * [TidalAlbumDetail], or **null** for a non-album/error body.
 *
 * **Wrapped in `runCatching` at birth** so any malformed field degrades to null
 * → the album screen keeps its local group, never crashes (the hardening
 * discipline, applied here from the start rather than retrofitted).
 *
 * Videos are dropped: a video id routed to the internal-v1 TRACK stream endpoint
 * fails, and Auxen has no video surface, so a silently-unplayable row is strictly
 * worse than an absent one. Each track is stamped with the album title and its
 * 640 cover, so a row whose inline `album` is absent still shows the right art —
 * the same thing the official `albumDetailFrom` did by passing them into `trackFrom`.
 * `similarAlbums` is empty: internal `albums/{id}` carries none, and the album
 * screen never rendered them.
 */
fun albumDetailFromV1(metaBody: String, itemBodies: List<String>, json: Json): TidalAlbumDetail? = runCatching {
    val meta = json.decodeFromString<TidalV1AlbumMeta>(metaBody)
    val artworkUrl = tidalV1CoverUrl(meta.cover)
    val albumArtist = meta.artist ?: meta.artists.firstOrNull()
    val tracks = itemBodies.flatMap { body ->
        runCatching { json.decodeFromString<TidalV1AlbumItemsResponse>(body).items }.getOrDefault(emptyList())
            .filterNot { it.type == "video" }
            .mapNotNull { it.item }
            .map { it.toTrack().copy(album = meta.title, albumArtUrl = artworkUrl) }
    }
    TidalAlbumDetail(
        id = meta.id.toString(),
        title = meta.title,
        artistName = albumArtist?.name.orEmpty(),
        artistId = albumArtist?.id?.toString(),
        albumType = meta.type,
        releaseYear = releaseYearOf(meta.releaseDate),
        explicit = meta.explicit ?: false,
        artworkUrl = artworkUrl,
        tracks = tracks,
        similarAlbums = emptyList(),
    )
}.getOrNull()
