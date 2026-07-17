package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The pure parts of `GET api.tidal.com/v1/pages/artist` — ONE internal-v1 read
 * that returns the ENTIRE Tidal artist page: the biography, the top tracks,
 * the discography **already categorised by Tidal**, and Fans Also Like **with
 * photos**.
 *
 * Probed live 2026-07-16 with the desktop's own session; the captured schema is
 * `.superpowers/sdd/pages-artist-schema.md` and it is ground truth for
 * everything in this file.
 *
 * **Why this replaced a four-call fan-out.** `artists/{id}/albums?limit=50`
 * returns 43 albums FLATTENED — which is why the user's device showed 20
 * *"Fixxxer"* rough mixes as a discography and an `S&M2 (Live)` duplicate.
 * `pages/artist` already categorises them: 'Albums' = 16 real studio albums, the
 * Fixxxer rough mixes under 'EP & Singles', `S&M2 (Live)` under 'Live albums',
 * `Garage, Inc.` under 'Compilations'. Tidal did the curation; the flat endpoint
 * just discards it. It also carries the biography official v2 has not got at our
 * tier, and the similar-artist photos that are not include-able from an official
 * artist document.
 *
 * **THE DESIGN RULE: modules are selected by `type` ONLY, never by `title`.**
 * Titles here are human-readable and locale-dependent ('Albums', 'EP & Singles',
 * 'Fans Also Like'). We send `locale=en_US` so they are stable *for us*, but this
 * is an undocumented reverse-engineered endpoint and Tidal owes us nothing. So we
 * never ask "is this module titled 'Albums'?" — we take EVERY `ALBUM_LIST` and
 * render it under whatever title Tidal gave it. Tidal renaming a category, adding
 * a sixth one, or localising the lot all just work. Artist 3634161's second
 * `ARTIST_LIST` — 'Influencers' — ships for free because of this; a
 * title-matching design would have silently dropped it. **Do not add a title
 * match here.**
 *
 * **The parse is TOTAL, at three levels.** The envelope tolerates anything (every
 * field nullable/defaulted; a non-page body yields null). An unknown module type
 * is never looked at; a missing module is an absent section; an empty `pagedList`
 * is an absent section. And items are decoded ONE AT A TIME under `runCatching`,
 * so one malformed row cannot cost the user a whole section. A failure here must
 * always degrade the artist page to its offline-safe local base — never break it.
 */

/**
 * The v1 paged-collection envelope inside a list module. Items stay raw and are
 * decoded per module type by [artistPageFrom].
 *
 * **[items] is nullable, not just defaulted, and the difference matters.** A
 * default only applies when the KEY IS ABSENT; an explicit `"items": null` makes
 * a non-nullable field THROW — and because the page decodes in one pass, that
 * would lose the ENTIRE page over one null, not one section. Nullable + `.orEmpty()`
 * is what keeps the envelope total. Same reasoning for [TidalV1PageRow.modules]
 * and [TidalV1ArtistPageResponse.rows].
 *
 * The probe shows `pagedList` also carries `limit`/`offset`/`totalNumberOfItems`.
 * They are deliberately unmodelled: nothing reads them, `ignoreUnknownKeys` drops
 * them, and an unmodelled field is one fewer way to throw.
 */
@Serializable
data class TidalV1PagedList(val items: List<JsonElement>? = null)

/** `ARTIST_HEADER.bio` — `{source, text}`. [text] is the biography, and it carries markup. */
@Serializable
data class TidalV1PageBio(val text: String? = null, val source: String? = null)

/**
 * One module. [type] is the ONLY selector (see the file KDoc); [title] is DATA,
 * rendered verbatim. [pagedList] is null on non-list modules (`ARTIST_HEADER`,
 * `SOCIAL`); [artist]/[bio] are populated on `ARTIST_HEADER` only. Everything is
 * nullable — an unknown module must decode cleanly and be ignored, never throw.
 */
@Serializable
data class TidalV1PageModule(
    val type: String? = null,
    val title: String? = null,
    val pagedList: TidalV1PagedList? = null,
    val artist: TidalV1PageArtist? = null,
    val bio: TidalV1PageBio? = null,
)

@Serializable
data class TidalV1PageRow(val modules: List<TidalV1PageModule>? = null)

/**
 * `{selfLink, id, title, rows}`. Only `rows` is modelled; `ignoreUnknownKeys`
 * drops the rest. Nullable for the reason in [TidalV1PagedList]'s KDoc.
 */
@Serializable
data class TidalV1ArtistPageResponse(val rows: List<TidalV1PageRow>? = null)

/**
 * An artist as this page renders one: `ARTIST_HEADER.artist`, an `ARTIST_LIST`
 * item, or an entry in a track's `artists`. Fields verbatim from the probe
 * (`id`, `name`, `picture`; `ARTIST_LIST` items add `artistRoles`/`artistTypes`/
 * `mixes`/`selectedAlbumCoverFallback`, which nothing here reads).
 */
@Serializable
data class TidalV1PageArtist(val id: Long, val name: String = "", val picture: String? = null)

/** A track item's `album`: `{id, title, cover, vibrantColor, videoCover, ...}` — we read three. */
@Serializable
data class TidalV1PageTrackAlbum(val id: Long? = null, val title: String = "", val cover: String? = null)

/**
 * A `TRACK_LIST` item.
 *
 * **[artists] is plural and there is NO singular `artist` field — that is the
 * real payload, not an omission here.** The probed item keys carry `artists` and
 * no `artist`, and `tidalapi` expects exactly that (`tidalapi/media.py:239-245`:
 * it reads `json_obj["artists"]` unconditionally and treats the singular as
 * optional, falling back to `artists[0]`). [TidalProvider]'s own private
 * `TrackDto` reads the SINGULAR and defaults it to `name = ""`, so routing this
 * payload through it would blank every artist name on the page. It is
 * unreachable from this file anyway — it is `private` and nested — which is
 * exactly why this DTO exists. **Do not "unify" the two without fixing that.**
 */
@Serializable
data class TidalV1PageTrack(
    val id: Long,
    val title: String = "",
    val duration: Double = 0.0,
    val explicit: Boolean = false,
    val audioQuality: String? = null,
    val trackNumber: Int? = null,
    val volumeNumber: Int? = null,
    val artists: List<TidalV1PageArtist> = emptyList(),
    val album: TidalV1PageTrackAlbum? = null,
)

/** One titled row of albums, exactly as Tidal grouped and titled it ('Albums', 'EP & Singles', 'Live albums', ...). */
data class TidalArtistAlbumSection(val title: String, val albums: List<AlbumSummary>)

/** One titled row of artists, exactly as Tidal titled it ('Fans Also Like', 'Influencers', ...). */
data class TidalArtistLinkSection(val title: String, val artists: List<ArtistLink>)

/**
 * The Tidal artist page, projected — the artist screen's SINGLE remote source.
 *
 * [albumSections] and [artistSections] are in page order and carry Tidal's own
 * titles; the screen renders each under its own heading and never inspects the
 * strings. [topTracks] are ordinary Tidal [Track]s that play through the
 * existing internal-v1 pipeline unchanged — this layer is metadata only.
 *
 * **Not here, deliberately:** videos (Auxen has no video surface, and a video id
 * routed to the track stream endpoint fails — the same reason `albumDetailFrom`
 * filters them out), playlists (Tidal playlists are uuid-keyed and there is no
 * screen to open one), the artist mix id (no mix surface), and a fan count
 * (**this payload has none** — `fans`/`followers`/`numberOfFollowers` were probed
 * for and are absent; the artist's `popularity` is 0-100 and is NOT the Tidal
 * app's "78K fans"). Do not add a field nothing reads.
 */
data class TidalArtistPage(
    val id: String,
    val name: String,
    val biography: String?,
    val artworkUrl: String?,
    val topTracks: List<Track>,
    val albumSections: List<TidalArtistAlbumSection>,
    val artistSections: List<TidalArtistLinkSection>,
)

/**
 * `GET {apiBase}/pages/artist?artistId=…&deviceType=BROWSER&locale=…&countryCode=…`.
 *
 * **These four params and NOTHING else. Sending `limit` returns 400** — probed
 * live 2026-07-16. That is why a naive call through `tidalapi` fails: its
 * `request.request` auto-injects `limit=1000`. [TidalProvider.get] sends no
 * params of its own, so the only way a limit can reach this endpoint is someone
 * adding it here. `TidalArtistPageTest` asserts its absence.
 *
 * [locale] defaults to the probe-verified `en_US`. Because module titles are DATA
 * and never selectors (see the file KDoc), passing the device locale would give
 * localised headings for free — but that is unverified against this endpoint, so
 * we ship what was probed.
 */
fun artistPageUrl(apiBase: String, artistId: String, countryCode: String, locale: String = "en_US"): HttpUrl =
    "$apiBase/pages/artist".toHttpUrl().newBuilder()
        .addQueryParameter("artistId", artistId)
        .addQueryParameter("deviceType", "BROWSER")
        .addQueryParameter("locale", locale)
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * Artist `picture` uuid -> CDN URL at **480x480**.
 *
 * **NOT [tidalV1CoverUrl], and the difference is load-bearing.** The CDN serves
 * two different size sets:
 *  - **artists**: 160 / 320 / **480** / 750 — `tidalapi/artist.py:283-293`, whose
 *    `image()` RAISES `ValueError` for anything else. **640 is not one of them.**
 *  - **albums**: 80 / 160 / 320 / **640** / 1280 — `tidalapi/album.py:243-256`.
 *
 * The desktop agrees and builds artist images at 480 in both places it does so
 * (`auxen/providers/tidal.py:883` and `:1017`), while album covers use 640
 * (`:597`, `:1502`). Reusing the 640 cover builder for a picture would request a
 * size Tidal does not render — reproducing the exact
 * *"artist images work for some but not all"* bug this rebuild exists to fix.
 * **Do not fold these two builders together.**
 */
fun tidalV1ArtistPictureUrl(picture: String?): String? =
    picture?.takeIf { it.isNotBlank() }
        ?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/480x480.jpg" }

/** The four quality facts a v1 `audioQuality` string implies. */
internal data class TidalV1Quality(
    val format: String?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val bitrateKbps: Int?,
)

/**
 * v1 `audioQuality` -> the quality fields a [Track] carries.
 *
 * **Deliberately duplicated from [TidalProvider]'s `toTrack` (TidalProvider.kt:296-303)
 * rather than refactored out of it** — the exact precedent [tidalV1CoverUrl] set
 * and documented for the same reason (`TidalInternalArtist.kt:138-143`): this
 * plan ADDS to the internal-v1 provider but **changes no existing method of it**,
 * and `toTrack` is on the live search path. `toTrack`'s copy is private, nested
 * and has never had a test; this one is covered by
 * `TidalArtistPageTest.tidalV1QualityOf mirrors TidalProvider toTrack exactly`,
 * which is the sync check between them. Fold the two together in a later change
 * that is allowed to touch `toTrack`.
 *
 * Skipping this mapping is not an option: [Track.qualityLabel] returns "Unknown"
 * when `format` is null (`Track.kt:69-79`) and `AuxenTrackRow` renders a
 * `QualityBadge` on every Tidal row (`AuxenTrackRow.kt:118-121`), so a null
 * format is a visible regression against the shipped page.
 */
internal fun tidalV1QualityOf(audioQuality: String?): TidalV1Quality {
    val quality = audioQuality.orEmpty()
    return when {
        quality.contains("HI_RES") -> TidalV1Quality("Hi-Res", 96_000, 24, 4608)
        quality.contains("LOSSLESS") -> TidalV1Quality("FLAC", 44_100, 16, 1411)
        quality.contains("HIGH") -> TidalV1Quality("AAC", 44_100, 16, 320)
        quality.isNotEmpty() -> TidalV1Quality("AAC", 44_100, null, 96)
        else -> TidalV1Quality(null, null, null, null)
    }
}

/**
 * A page track item -> a playable Tidal [Track] (`source = TIDAL`,
 * `sourceId = <catalog id>`), so it plays through the EXISTING internal-v1
 * pipeline (`Graph.mediaItemFor` -> `auxen://tidal/<id>` -> `TrackResolver` ->
 * `TidalProvider.getStreamInfoById`). No new playback code.
 *
 * `artist` comes from `artists.firstOrNull()` — see [TidalV1PageTrack]'s KDoc;
 * there is no singular `artist` key in this payload. The album cover uses the
 * **640** builder ([tidalV1CoverUrl]) because it IS an album cover; only the
 * artist picture uses 480.
 */
internal fun TidalV1PageTrack.toTrack(): Track {
    val quality = tidalV1QualityOf(audioQuality)
    return Track(
        title = title,
        artist = artists.firstOrNull()?.name.orEmpty(),
        album = album?.title,
        source = Source.TIDAL,
        sourceId = id.toString(),
        durationSeconds = duration,
        trackNumber = trackNumber,
        discNumber = volumeNumber,
        format = quality.format,
        sampleRateHz = quality.sampleRateHz,
        bitDepth = quality.bitDepth,
        bitrateKbps = quality.bitrateKbps,
        albumArtUrl = tidalV1CoverUrl(album?.cover),
        explicit = explicit,
    )
}

/**
 * Decode each element independently, dropping the ones that fail.
 *
 * **This is the item-level half of the totality guarantee.** Decoding the whole
 * array in one call would let a single malformed row throw and take an entire
 * section with it; here one bad row is dropped and its siblings survive.
 */
/**
 * Decode each element independently, dropping the ones that fail. `internal`
 * rather than private because `search`'s per-type blocks reuse it for the same
 * item-level totality — one malformed row must never cost a whole type section.
 */
internal inline fun <reified T> List<JsonElement>.decodeEach(json: Json): List<T> =
    mapNotNull { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }

/**
 * Pure projection: a `pages/artist` body -> [TidalArtistPage], or **null**.
 *
 * Null — never an exception — for a v1 error envelope, an HTML error page,
 * not-JSON, or a body with no modules at all. Every one of those means the same
 * thing to the caller: no remote enrichment, and the artist screen renders
 * exactly what it renders today off its local base.
 *
 * [artistId] is the id we ASKED for, used only as the fallback when the header
 * carries no artist object, so the projection always reports a usable id.
 *
 * **Selection is by `type` only** — see the file KDoc. Every `ALBUM_LIST` and
 * every `ARTIST_LIST` becomes a section carrying its own verbatim title, in page
 * order; the first `TRACK_LIST` is the top tracks. A section whose items are all
 * gone (empty `pagedList`, or every item malformed) is dropped rather than
 * rendered as a header over an empty rail.
 */
fun artistPageFrom(body: String, json: Json, artistId: String): TidalArtistPage? = runCatching {
    val page = json.decodeFromString<TidalV1ArtistPageResponse>(body)
    val modules = page.rows.orEmpty().flatMap { it.modules.orEmpty() }
    // No modules at all is not a page. This is the case that catches a v1 error
    // envelope: `{"status":400,...}` decodes CLEANLY into rows=[] because every
    // field is defaulted, so it must be rejected here.
    if (modules.isEmpty()) return@runCatching null

    val header = modules.firstOrNull { it.type == "ARTIST_HEADER" }

    val topTracks = modules.firstOrNull { it.type == "TRACK_LIST" }
        ?.pagedList?.items?.decodeEach<TidalV1PageTrack>(json)
        ?.map { it.toTrack() }
        .orEmpty()

    val albumSections = modules.filter { it.type == "ALBUM_LIST" }.mapNotNull { module ->
        val albums = module.pagedList?.items?.decodeEach<TidalV1Album>(json)
            ?.map { it.toAlbumSummary() }
            .orEmpty()
        if (albums.isEmpty()) null else TidalArtistAlbumSection(module.title.orEmpty(), albums)
    }

    val artistSections = modules.filter { it.type == "ARTIST_LIST" }.mapNotNull { module ->
        val artists = module.pagedList?.items?.decodeEach<TidalV1PageArtist>(json)
            ?.map { ArtistLink(id = it.id.toString(), name = it.name, artworkUrl = tidalV1ArtistPictureUrl(it.picture)) }
            .orEmpty()
        if (artists.isEmpty()) null else TidalArtistLinkSection(module.title.orEmpty(), artists)
    }

    TidalArtistPage(
        id = header?.artist?.id?.toString() ?: artistId,
        name = header?.artist?.name.orEmpty(),
        // ARTIST_HEADER.bio.text is the biography official v2 has not got at our
        // tier. formatTidalBio is REUSED (not re-declared): this is the same
        // service's markup as `artists/{id}/bio`, so the same [wimpLink]/<br>/HTML
        // must be assumed, and the formatter is idempotent on clean prose. Blank
        // after formatting reads as "no bio" and hides the section.
        biography = header?.bio?.text?.let { formatTidalBio(it) }?.takeIf { it.isNotBlank() },
        // 480, not 640 — see tidalV1ArtistPictureUrl's KDoc.
        artworkUrl = tidalV1ArtistPictureUrl(header?.artist?.picture),
        topTracks = topTracks,
        albumSections = albumSections,
        artistSections = artistSections,
    )
}.getOrNull()
