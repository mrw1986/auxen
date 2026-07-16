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

/** A minimal artist reference (similar-artist chips, etc.). */
data class ArtistLink(val id: String, val name: String, val artworkUrl: String?)

/** A discography entry / similar-album card. */
data class AlbumSummary(
    val id: String,
    val title: String,
    val albumType: String?,
    val releaseYear: Int?,
    val explicit: Boolean,
    val artworkUrl: String?,
)

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
 * `biography` is null both when the artist has none AND when Tidal simply did
 * not put the resource in `included` (observed on-device for artist 8405, whose
 * `biography` linkage was present but unresolved). The projection cannot tell
 * those apart and does not try: it reports null, and
 * [TidalMetadataRepository.artistDetail] fetches the relationship endpoint on
 * null. Both causes converge on the same fix, so neither needs identifying.
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
        },
    )
}

/**
 * Pure projection for the biography FALLBACK document
 * (`GET /artists/{id}/relationships/biography?include=biography`).
 *
 * Reads `included` directly rather than going through [TidalDocumentView]: this
 * document's `data` is a bare `Resource_Identifier` (`{type,id}`) with no
 * attributes and no relationships, so `primary`/`related` have nothing to give.
 * The text only ever exists in `included`, put there by `?include=biography`.
 *
 * Null — never an error — when the artist has no biography (`data: null`,
 * empty `included`) or the text is blank. The caller treats null as "no bio"
 * and hides the section, matching the desktop, which swallows the identical
 * 404 (`providers/tidal.py:912-915`: "404s are expected for lesser-known artists").
 */
fun biographyTextFrom(doc: TidalDocument, json: Json): String? =
    doc.included.firstOrNull { it.type == "artistBiographies" }
        ?.decodeAttributes<ArtistBiographyAttributes>(json)
        ?.text
        ?.takeIf { it.isNotBlank() }

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
 * The Tidal-only half of the artist discography: every [remote] album whose
 * lowercase title is not already in [localTitles].
 *
 * Mirrors the desktop's merge at `auxen/window.py:1394-1407` EXACTLY. There,
 * `seen_names` is seeded from the local albums' lowercase titles and each Tidal
 * album is appended only if its `title.lower()` is absent — and crucially the
 * title is added to `seen_names` INSIDE the loop, so a duplicate *within* the
 * Tidal list is dropped too. That is not hypothetical: the on-device probe of
 * artist 8405 returned `Fixxxer (Remastered)` twice. `seen.add(...)` returns
 * false once present, giving both behaviours in one pass.
 *
 * Returns only the remote-only albums, in remote order; the caller renders its
 * local albums first and then these, reproducing the desktop's list order
 * without this pure function needing to know the UI's local album type.
 * `lowercase()` (not the deprecated `toLowerCase()`) is Locale.ROOT — matching
 * Python's `str.lower()` and keeping the dedup independent of device locale.
 */
fun dedupRemoteAlbums(localTitles: Set<String>, remote: List<AlbumSummary>): List<AlbumSummary> {
    val seen = localTitles.toMutableSet()
    return remote.filter { seen.add(it.title.lowercase()) }
}

/**
 * The artist's track list: **Tidal top tracks first (popularity order), then any
 * local tracks not already among them.**
 *
 * Mirrors the desktop's merge at `auxen/window.py:1409-1422`:
 * ```python
 * tidal_top = info.get("tracks", [])
 * if tidal_top:
 *     tidal_ids = {t.source_id for t in tidal_top if t.source_id}
 *     local_extras = [t for t in tracks if t.source_id not in tidal_ids]
 *     tracks = list(tidal_top) + local_extras
 * ```
 * — including the `if tidal_top:` guard: **no top tracks means no merge at all**,
 * not an empty list. [topTracks] is empty whenever internal-v1 is not logged in,
 * the artist is unknown, or the call failed, and in every one of those cases the
 * local list must survive untouched.
 *
 * **Two deliberate divergences from the Python, both traceable to this codebase's
 * own conventions:**
 *
 * 1. **The id compare is scoped to Tidal tracks.** The desktop compares
 *    `source_id` across sources; a local MediaStore id and a Tidal catalog id are
 *    both numeric strings, so a bare compare can silently delete the user's own
 *    file when the numbers happen to collide. Track identity here is ALWAYS
 *    namespaced by source (`"${source}:${sourceId}"`, `ArtistDetailScreen.kt:78`
 *    and `:166`), so `it.isTidal &&` is the faithful port of the *intent*.
 * 2. **The result is distinct by `source:sourceId`.** The merged list is rendered
 *    by a `LazyColumn` keyed on exactly that (`ArtistDetailScreen.kt:166`), and a
 *    duplicate key is a hard Compose crash. The local pool is already
 *    `distinctBy`-ed at `ArtistDetailScreen.kt:78`, but [topTracks] arrives
 *    straight from the network and has no such guarantee — so the merge owns it.
 */
fun mergeArtistTracks(topTracks: List<Track>, localTracks: List<Track>): List<Track> {
    if (topTracks.isEmpty()) return localTracks
    val tidalIds = topTracks.filter { it.isTidal }.mapTo(mutableSetOf()) { it.sourceId }
    val extras = localTracks.filterNot { it.isTidal && it.sourceId in tidalIds }
    return (topTracks + extras).distinctBy { "${it.source}:${it.sourceId}" }
}
