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
    }
}
