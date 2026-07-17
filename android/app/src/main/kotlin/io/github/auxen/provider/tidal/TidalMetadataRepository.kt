package io.github.auxen.provider.tidal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed attribute bodies for the metadata resources (field names verbatim
 * from the cached spec's `Artists_Attributes` / `ArtistBiographies_Attributes`
 * / `Albums_Attributes`). Every field is nullable — tolerate a versioned
 * external API omitting anything, exactly like the spike's track DTOs.
 */
@Serializable
data class ArtistAttributes(val name: String? = null, val handle: String? = null, val popularity: Double? = null)

@Serializable
data class ArtistBiographyAttributes(val text: String? = null, val source: String? = null)

@Serializable
data class AlbumAttributes(
    val title: String? = null,
    val albumType: String? = null,
    val releaseDate: String? = null,
    val explicit: Boolean? = null,
)

/** A text-only projection of an artist for the Foundation probe; artwork/track-list land in Slice 1 (Enrichment). */
data class ArtistOverview(
    val id: String,
    val name: String,
    val biography: String?,
    val similarArtistNames: List<String>,
    val albumTitles: List<String>,
)

/**
 * Pure projection from a compound artist [TidalDocument] to [ArtistOverview]
 * — extracted from [TidalMetadataRepository.artistOverview] so the mapping is
 * unit-testable without a live call. Returns null if the document has no
 * primary artist resource.
 */
fun artistOverviewFrom(doc: TidalDocument, json: Json): ArtistOverview? {
    val view = TidalDocumentView(doc, json)
    val artist = view.primary ?: return null
    return ArtistOverview(
        id = artist.id,
        name = artist.decodeAttributes<ArtistAttributes>(json)?.name.orEmpty(),
        biography = view.related(artist, "biography").firstOrNull()
            ?.decodeAttributes<ArtistBiographyAttributes>(json)?.text,
        similarArtistNames = view.related(artist, "similarArtists")
            .mapNotNull { it.decodeAttributes<ArtistAttributes>(json)?.name },
        albumTitles = view.related(artist, "albums")
            .mapNotNull { it.decodeAttributes<AlbumAttributes>(json)?.title },
    )
}

/** The current user's id (which is also their userCollection id), from `GET /users/me`. Pure. */
fun userIdFrom(doc: TidalDocument, json: Json): String? =
    TidalDocumentView(doc, json).primary?.id

/**
 * The seam every official-API metadata feature (enrichment/collection/
 * discovery) goes through. Wraps token refresh ([TidalOfficialSession.
 * validAccessToken]) + fetch ([TidalOfficialClient]) + compound-document
 * projection. Streaming stays entirely on the internal-v1 path — this class
 * is metadata only.
 *
 * The constructor is frozen at its FINAL shape here (Slice 0): `countryCode`
 * is used by the Foundation; `locale` is unused by the Foundation itself but
 * reserved for Slices 2/3 (collection paging / discovery `locale`). Later
 * slices only APPEND methods to this class and never rewrite this signature.
 */
class TidalMetadataRepository(
    private val session: TidalOfficialSession,
    private val client: TidalOfficialClient,
    private val json: Json,
    private val countryCode: String? = null,
    private val locale: String? = null,
) {
    /** Artist name + biography + similar artists + album titles in one call. */
    suspend fun artistOverview(artistId: String): Result<ArtistOverview> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))
        return client.getArtist(artistId, token, ARTIST_INCLUDE, countryCode).mapCatching { doc ->
            artistOverviewFrom(doc, json) ?: error("Artist $artistId not found")
        }
    }

    /**
     * The official-v2 artist document: similar artists + profile art. **ONE
     * request, no fallback.**
     *
     * **Not the biography, not the discography, not the top tracks** — all three
     * are internal-v1 ([TidalProvider.artistBio] / [TidalProvider.artistAlbums] /
     * [TidalProvider.artistTopTracks]), composed alongside this by the ViewModel
     * (this class's constructor is frozen and holds no internal-v1 provider).
     *
     * **The biography used to be here and is gone on purpose.** Official v2 has
     * no biography data at our tier: `?include=biography` returned the linkage
     * with the resource absent from `included`, and the dedicated
     * `/artists/{id}/relationships/biography?include=biography` fallback then
     * returned nothing either — for artist 8405 (Metallica) and again on-device
     * for Tommy Richman. That fallback was a second SEQUENTIAL round-trip on
     * every bio-less artist, i.e. on every artist, which is what made this page
     * slow; deleting it makes the official half of the artist page a single
     * request. Do not re-add `"biography"` to [ARTIST_DETAIL_INCLUDE] — it costs
     * a bigger response for a field that never arrives.
     */
    suspend fun artistDetail(artistId: String): Result<TidalArtistDetail> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))
        return client.getArtist(artistId, token, ARTIST_DETAIL_INCLUDE, countryCode).mapCatching { doc ->
            artistDetailFrom(doc, json, ARTWORK_TARGET_PX) ?: error("Artist $artistId not found")
        }
    }

    /** Full album detail: metadata, ordered tracklist, cover art, similar albums. */
    suspend fun albumDetail(albumId: String): Result<TidalAlbumDetail> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))
        return client.getAlbum(albumId, token, ALBUM_DETAIL_INCLUDE, countryCode).mapCatching { doc ->
            albumDetailFrom(doc, json, ARTWORK_TARGET_PX) ?: error("Album $albumId not found")
        }
    }

    /** Track credits + lyrics (+ similar tracks) as a compound document projection. */
    suspend fun trackDetail(trackId: String): Result<TidalTrackDetail> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))
        return client.getTrackMetadata(trackId, token, TRACK_DETAIL_INCLUDE, countryCode).mapCatching { doc ->
            trackDetailFrom(doc, json, ARTWORK_TARGET_PX) ?: error("Track $trackId not found")
        }
    }

    /** Resolve the artist + album ids (and isrc) for a Tidal track — the detail-fetch entry point. */
    suspend fun trackRelations(trackId: String): Result<TrackRelations> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))
        return client.getTrackMetadata(trackId, token, TRACK_RELATIONS_INCLUDE, countryCode).mapCatching { doc ->
            trackRelationsFrom(doc, json) ?: error("Track $trackId not found on the official API")
        }
    }

    // The current user's numeric id (also their userCollection id), resolved
    // once via /users/me and cached for the process lifetime (cleared implicitly
    // on process death). THE single source of the user id for any user/
    // collection-scoped call — Slice 2's resolveCollectionId() delegates here.
    @Volatile private var cachedUserId: String? = null
    private val userIdMutex = Mutex()

    /**
     * The current user's id via `GET /users/me` (JSON:API `data.id`), cached
     * in-memory. Returns null when not logged in to the official API or the id
     * cannot be resolved (the caller should surface "sign in"). This is the
     * single accessor every user/collection-scoped call goes through.
     *
     * Double-checked under [userIdMutex] so concurrent first-callers resolve it
     * once rather than each hitting `/users/me`; [cachedUserId] stays `@Volatile`
     * because the fast path reads it outside the lock.
     */
    suspend fun currentUserId(): String? {
        cachedUserId?.let { return it }
        return userIdMutex.withLock {
            // Re-check under the lock: a caller ahead of us may already have resolved it.
            cachedUserId?.let { return@withLock it }
            val token = session.validAccessToken() ?: return@withLock null
            val id = client.getUser("me", token)
                .mapCatching { userIdFrom(it, json) ?: error("No user id in /users/me response") }
                .getOrNull()
            if (id != null) cachedUserId = id
            id
        }
    }

    companion object {
        val ARTIST_INCLUDE = listOf("biography", "similarArtists", "albums")
        val TRACK_RELATIONS_INCLUDE = listOf("artists", "albums")

        // "albums" AND "tracks" are deliberately ABSENT. Probed on-device
        // 2026-07-16, ?include=albums returns an arbitrary first page (20
        // Fixxxer variants for artist 8405), not a discography; ?include=tracks
        // is the same mechanism on the same document, so it is not a
        // popularity-ordered top-tracks list either. Both come from internal-v1
        // instead: TidalProvider.artistAlbums / TidalProvider.artistTopTracks.
        // Re-adding either include here re-introduces the bug AND costs a
        // bigger response for data nothing reads — don't.
        //
        // "biography" is deliberately ABSENT for the same reason: official v2
        // has none at our tier. Probed on-device 2026-07-16, the include's
        // resource never lands in `included`, and the dedicated relationship
        // endpoint answered empty for Metallica AND for Tommy Richman. The bio
        // now comes from internal-v1 (TidalProvider.artistBio). Re-adding it here
        // buys a bigger response and a field that is always null — don't.
        //
        // NOTE: this is Slice 1's own constant. The Foundation's ARTIST_INCLUDE
        // (= biography, similarArtists, albums) is a DIFFERENT, merged constant
        // used by artistOverview() and the Task-1 probe readout. It keeps its
        // "biography" precisely so that probe keeps REPORTING the emptiness that
        // motivated this split. Leave it alone.
        val ARTIST_DETAIL_INCLUDE = listOf("similarArtists", "profileArt")

        // The four relationships the album page renders, all real per the spec's
        // Albums_Relationships. Unlike the artist's `albums`/`tracks`, `items` IS
        // the album's authoritative tracklist (a bounded, ordered list), so the
        // include is the right mechanism here.
        val ALBUM_DETAIL_INCLUDE = listOf("items", "artists", "coverArt", "similarAlbums")

        // All four are real Tracks_Relationships per the spec. "artists" earns its
        // place only by naming the PRIMARY track's artist, which trackDetailFrom
        // then carries onto the similar-track rows — see that function's KDoc for
        // why those rows' own artists are unreachable from this document.
        // TidalTrackDetail has no artist field of its own: the sheet already knows
        // the track it was opened from.
        val TRACK_DETAIL_INCLUDE = listOf("credits", "lyrics", "similarTracks", "artists")

        const val ARTWORK_TARGET_PX = 640
    }
}
