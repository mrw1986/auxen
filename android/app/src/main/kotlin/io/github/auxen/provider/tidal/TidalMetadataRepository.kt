package io.github.auxen.provider.tidal

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

    companion object {
        val ARTIST_INCLUDE = listOf("biography", "similarArtists", "albums")
    }
}
