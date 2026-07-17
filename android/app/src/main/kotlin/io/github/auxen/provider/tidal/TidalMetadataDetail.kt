package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Net-new domain projections for the official-API enrichment surface (Slice 1).
 * The codebase has only the local `AlbumGroup`/`ArtistGroup` grouping structs;
 * these are the remote-backed detail types. Playable entities are projected as
 * ordinary [Track]s with `source = TIDAL` and `sourceId = <catalog id>`, so they
 * play through the existing internal-v1 pipeline unchanged — this layer is
 * metadata only.
 */

/**
 * An artist as the OFFICIAL v2 artist document describes it, and **nothing
 * more**: header art + biography + similar artists.
 *
 * **No `albums` and no `tracks`** — by decision, not by omission. Both were
 * probed/reasoned to be arbitrary first pages of the same compound document
 * (`?include=albums` returned 20 *Fixxxer* variants for artist 8405 on
 * 2026-07-16; `?include=tracks` is the identical mechanism and is not
 * popularity-ordered). The real discography is internal-v1
 * [TidalProvider.artistAlbums] and the real top tracks are internal-v1
 * [TidalProvider.artistTopTracks]. Those are fetched independently and composed
 * by the ViewModel — [TidalMetadataRepository]'s constructor is frozen and
 * cannot reach the internal-v1 provider.
 *
 * Keeping this type honest (= exactly one document) is what stops a future
 * reader from "fixing" the missing fields by re-adding the includes. If you are
 * here because a list looks empty, the bug is upstream in the ViewModel
 * composition, not in this type.
 */
data class TidalArtistDetail(
    val id: String,
    val name: String,
    val biography: String?,
    val artworkUrl: String?,
    val similarArtists: List<ArtistLink>,
)

/**
 * The `meta` an album's `items` linkage carries per resource identifier (field
 * names verbatim from the cached spec's `Albums_Items_Resource_Identifier_Meta`,
 * which marks both `required`).
 *
 * This is the ONLY source of real track numbering: `Tracks_Attributes` carries
 * no track number, and these values sit on the LINKAGE, not on the track
 * resource — which is why [albumDetailFrom] reads items via
 * [TidalDocumentView.relatedWithMeta]. Both fields stay nullable/defaulted like
 * every other DTO here; a meta-less document falls back to linkage position.
 * (`itemCursor` is the third spec field, deliberately unmodelled: paging is
 * Slice 2's, and nothing here reads it.)
 */
@Serializable
data class AlbumItemMeta(val trackNumber: Int? = null, val volumeNumber: Int? = null)

/**
 * `Credits_Attributes` (spec, verbatim): ONE contributor per resource, as
 * `{name, role}` — the spec marks both `required`, and a track's `credits`
 * relationship is a multi-linkage, so "Producer: Alice, Bob" arrives as two
 * sibling resources sharing a role, never as one resource with a name list.
 * That is precisely why [trackDetailFrom] groups by role. Both fields stay
 * nullable/defaulted anyway — the house rule for every DTO facing this
 * versioned external API.
 */
@Serializable
data class CreditsAttributes(val name: String? = null, val role: String? = null)

/**
 * `Lyrics_Attributes` (spec, verbatim, subset): plain [text] + time-synced
 * [lrcText]. The spec's other three fields (`provider`, `direction`,
 * `technicalStatus`) are deliberately unmodelled — nothing renders them, and
 * `ignoreUnknownKeys` drops them harmlessly.
 */
@Serializable
data class LyricsAttributes(val text: String? = null, val lrcText: String? = null)

/** Best-fit artwork URL for [resource]'s named artwork [relationship], or null when not included/absent. */
internal fun TidalDocumentView.artworkUrlFor(
    resource: TidalResource,
    relationship: String,
    json: Json,
    targetPx: Int,
): String? =
    related(resource, relationship).firstOrNull()
        ?.decodeAttributes<ArtworkAttributes>(json)?.files
        ?.let { pickArtworkUrl(it, targetPx) }

/**
 * Project a `tracks` resource into a playable Tidal [Track] (source = TIDAL,
 * sourceId = catalog id). This is the CANONICAL official-track-resource →
 * `model.Track` projection, owned by Slice 1. Slice 3 (Discovery) reuses it
 * (via the shared `parseIsoDurationSeconds` primitive) for the official-track →
 * Track step, so **Slice 1 must merge before Slice 3** (merge order:
 * Foundation → Slice 1 → Slice 3).
 *
 * **Not called by [artistDetailFrom]** — the artist's top tracks come from
 * internal-v1 (`TidalProvider.artistTopTracks`, which maps through the
 * provider's own `toTrack`), so within Task 3's commit this function is
 * declared but unused. Task 4 (`albumDetailFrom`) and Task 5
 * (`trackDetailFrom`) are its callers. Kotlin does not error on an unused
 * internal function and this module has no lint gate — do not "clean it up"
 * between tasks.
 */
internal fun trackFrom(
    resource: TidalResource,
    json: Json,
    artistName: String,
    albumTitle: String?,
    artworkUrl: String?,
): Track {
    val a = resource.decodeAttributes<TrackAttributes>(json)
    return Track(
        title = a?.title.orEmpty(),
        artist = artistName,
        source = Source.TIDAL,
        sourceId = resource.id,
        album = albumTitle,
        albumArtUrl = artworkUrl,
        durationSeconds = parseIsoDurationSeconds(a?.duration),
        explicit = a?.explicit ?: false,
    )
}

/**
 * Project an official-v2 `albums` resource into an [AlbumSummary] (coverArt
 * resolved only when included in the document).
 *
 * Declared here, but **not called by [artistDetailFrom]** — the artist
 * discography comes from internal-v1 [artistAlbumsFrom], which builds the same
 * [AlbumSummary]. Task 4's `albumDetailFrom` is this function's only caller
 * (for `similarAlbums`), so it lands unused-until-Task-4 within this commit.
 */
internal fun albumSummaryFrom(res: TidalResource, view: TidalDocumentView, json: Json, targetPx: Int): AlbumSummary {
    val a = res.decodeAttributes<AlbumAttributes>(json)
    return AlbumSummary(
        id = res.id,
        title = a?.title.orEmpty(),
        albumType = a?.albumType,
        releaseYear = releaseYearOf(a?.releaseDate),
        explicit = a?.explicit ?: false,
        artworkUrl = view.artworkUrlFor(res, "coverArt", json, targetPx),
    )
}

/**
 * Pure projection: compound artist [TidalDocument] → [TidalArtistDetail].
 * Returns null when there is no primary artist.
 *
 * **`biography` is always null in production, and that is expected.**
 * [TidalMetadataRepository.ARTIST_DETAIL_INCLUDE] no longer requests the
 * include, because official v2 has no biography at our tier (proven for
 * Metallica and Tommy Richman). The real bio is fetched from internal-v1 and
 * composed onto this type by the ViewModel — see [TidalProvider.artistBio]. This
 * projection still reads the field so it stays a faithful projection of whatever
 * document it is handed (and so the ViewModel's compose-in is additive rather
 * than overwriting), not because the field is expected to arrive. If you are
 * here because the bio is missing, the answer is not to re-add the include.
 *
 * `similarArtists` is **deduped by id, first occurrence winning**, in document
 * order. [TidalDocumentView.related] resolves the linkage array 1:1 with no
 * dedup, and this API demonstrably repeats a linkage entry — the on-device probe
 * of artist 8405 returned `Fixxxer (Remastered)` TWICE through this exact
 * mechanism (see [dedupRemoteAlbums]' KDoc). Duplicates render as duplicate rows,
 * and would be a hard Compose "key was already used" crash for any caller that
 * keyed on the id. The dedup is DEFENCE IN DEPTH, not a licence to re-add that
 * key: ArtistDetailScreen's similar-artist LazyRow stays deliberately unkeyed,
 * because a projection is the wrong place to bet a crash on.
 *
 * `similarArtists`' own `profileArt` is NOT flatly include-able from an artist
 * document, so those `artworkUrl`s are null — the UI shows a glyph, and tapping
 * through fetches that artist's own document (with art).
 */
fun artistDetailFrom(doc: TidalDocument, json: Json, artworkTargetPx: Int): TidalArtistDetail? {
    val view = TidalDocumentView(doc, json)
    val artist = view.primary ?: return null
    val name = artist.decodeAttributes<ArtistAttributes>(json)?.name.orEmpty()
    val artworkUrl = view.artworkUrlFor(artist, "profileArt", json, artworkTargetPx)
    return TidalArtistDetail(
        id = artist.id,
        name = name,
        biography = view.related(artist, "biography").firstOrNull()
            ?.decodeAttributes<ArtistBiographyAttributes>(json)?.text
            ?.takeIf { it.isNotBlank() },
        artworkUrl = artworkUrl,
        similarArtists = view.related(artist, "similarArtists").map {
            ArtistLink(
                id = it.id,
                name = it.decodeAttributes<ArtistAttributes>(json)?.name.orEmpty(),
                artworkUrl = view.artworkUrlFor(it, "profileArt", json, artworkTargetPx),
            )
        }.distinctBy { it.id },
    )
}

// `biographyTextFrom` — the projection for the
// `/artists/{id}/relationships/biography` fallback document — was DELETED on
// 2026-07-16 together with that endpoint and its client method. It parsed
// correctly; there was simply never anything to parse. Official v2 serves no
// biography at our tier (proven for Metallica and Tommy Richman), so the bio
// now comes from internal-v1 `artists/{id}/bio` via `artistBioFrom`
// (TidalInternalArtist.kt), which also handles the markup that endpoint returns.

/**
 * Pure projection: compound album [TidalDocument] → [TidalAlbumDetail]. Returns
 * null when there is no primary album.
 *
 * **The `items` linkage is not simply "the tracklist"**, and two spec facts
 * shape how it is read:
 *
 * 1. **It can contain videos.**
 *    `AlbumsItemsRelationshipUpdateOperation_Payload_Data.type` is
 *    `enum: ["tracks", "videos"]`, so a deluxe edition may legitimately list
 *    video items. They are filtered out. Projecting one through [trackFrom]
 *    would mint a `Track(source = TIDAL, sourceId = <video id>)` whose
 *    `auxen://tidal/<id>` resolves against the internal-v1 TRACK stream
 *    endpoint and simply fails when tapped; Auxen has no video surface, so a
 *    silently-unplayable row is strictly worse than an absent one. If a video
 *    surface ever arrives, it wants its own projection and its own list — not a
 *    `Track` that lies about what it is.
 * 2. **The track numbers live on the LINKAGE, not the resource.**
 *    `Tracks_Attributes` carries no track number; `Albums_Items_Resource_-
 *    Identifier_Meta` carries `trackNumber`/`volumeNumber` and marks both
 *    `required` — hence [TidalDocumentView.relatedWithMeta] here. Reading them
 *    is what makes a 2-volume album number 1..12 then 1..10 rather than 1..22,
 *    and what populates `discNumber` at all. Linkage position (`i + 1`) stays
 *    the fallback for a meta-less document, which is single-volume-correct.
 *
 * Linkage order is preserved as the display order — `meta` supplies the
 * numbering, never the sort.
 */
fun albumDetailFrom(doc: TidalDocument, json: Json, artworkTargetPx: Int): TidalAlbumDetail? {
    val view = TidalDocumentView(doc, json)
    val album = view.primary ?: return null
    val a = album.decodeAttributes<AlbumAttributes>(json)
    val title = a?.title.orEmpty()
    val artistRes = view.related(album, "artists").firstOrNull()
    val artistName = artistRes?.decodeAttributes<ArtistAttributes>(json)?.name.orEmpty()
    val artworkUrl = view.artworkUrlFor(album, "coverArt", json, artworkTargetPx)
    return TidalAlbumDetail(
        id = album.id,
        title = title,
        artistName = artistName,
        artistId = artistRes?.id,
        albumType = a?.albumType,
        releaseYear = releaseYearOf(a?.releaseDate),
        explicit = a?.explicit ?: false,
        artworkUrl = artworkUrl,
        tracks = view.relatedWithMeta(album, "items")
            .filter { it.resource.type == "tracks" }
            .mapIndexed { i, item ->
                val meta = item.decodeMeta<AlbumItemMeta>(json)
                trackFrom(item.resource, json, artistName = artistName, albumTitle = title, artworkUrl = artworkUrl)
                    .copy(trackNumber = meta?.trackNumber ?: (i + 1), discNumber = meta?.volumeNumber)
            },
        similarAlbums = view.related(album, "similarAlbums").map { albumSummaryFrom(it, view, json, artworkTargetPx) },
    )
}

/**
 * Pure projection: compound track [TidalDocument] → [TidalTrackDetail]. Returns
 * null when there is no primary track.
 *
 * **Credits are grouped, not listed.** `Credits_Attributes` is one `{name, role}`
 * per resource (see [CreditsAttributes]), so a track produced by two people
 * arrives as two sibling `credits` resources both roled "Producer". Rendering
 * that raw would print "Producer: Alice" then "Producer: Bob"; grouping by role
 * gives the sheet the "Producer — Alice, Bob" shape a credits list is expected to
 * have. `groupBy` yields a `LinkedHashMap`, so BOTH the role order and the names
 * within a role stay in document order — first-seen wins, no sorting. Entries
 * missing either half are dropped: a nameless role or a roleless name has nothing
 * to render.
 *
 * `similarTracks` are projected with `artworkUrl = null` for the same structural
 * reason [artistDetailFrom]'s similar artists have no art: a similar track's own
 * `albums`/`coverArt` is not flatly include-able from THIS document, and only its
 * own document carries it. [artworkTargetPx] is therefore accepted-but-unused —
 * it keeps this projection signature-symmetric with its two siblings so
 * [TidalMetadataRepository] can call all three uniformly with `ARTWORK_TARGET_PX`.
 * It is a seam, not an oversight: the day similar-track art becomes reachable it
 * is already threaded through. Do not "clean it up" — the call sites pass it by
 * name.
 *
 * **KNOWN LIMITATION — every similar track inherits the PRIMARY track's artist
 * name.** `?include=artists` resolves the artists of the primary resource only;
 * a similar track's own artists are not in this document, so its true artist is
 * unreachable here. `Track.artist` is non-null, so the projection must put
 * *something* there, and it carries the primary artist over. For a similar track
 * by a DIFFERENT artist that label is simply wrong. This is invisible in Slice 1
 * — Task 8's `TrackInfoSheet` renders title/credits/lyrics and never touches
 * [TidalTrackDetail.similarTracks] — but any future surface that renders or
 * enqueues these rows MUST fix it first, or it will show (and now-play) the wrong
 * artist. The `sourceId`s are correct, so such rows would still PLAY the right
 * audio while displaying the wrong name — the quiet failure mode. The fix is to
 * resolve each similar track's own artist (a nested `similarTracks.artists`
 * include, unverified at our tier, or one document per track), not to paper over
 * it here.
 */
fun trackDetailFrom(doc: TidalDocument, json: Json, artworkTargetPx: Int): TidalTrackDetail? {
    val view = TidalDocumentView(doc, json)
    val track = view.primary ?: return null
    val artistName = view.related(track, "artists").firstOrNull()
        ?.decodeAttributes<ArtistAttributes>(json)?.name.orEmpty()
    val credits = view.related(track, "credits")
        .mapNotNull { it.decodeAttributes<CreditsAttributes>(json) }
        .filter { it.role != null && it.name != null }
        .groupBy { it.role!! }
        .map { (role, entries) -> TrackCredit(role, entries.mapNotNull { it.name }) }
    val lyrics = view.related(track, "lyrics").firstOrNull()?.decodeAttributes<LyricsAttributes>(json)
    return TidalTrackDetail(
        id = track.id,
        title = track.decodeAttributes<TrackAttributes>(json)?.title.orEmpty(),
        credits = credits,
        lyricsText = lyrics?.text,
        lyricsSynced = lyrics?.lrcText,
        similarTracks = view.related(track, "similarTracks").map {
            trackFrom(it, json, artistName = artistName, albumTitle = null, artworkUrl = null)
        },
    )
}
