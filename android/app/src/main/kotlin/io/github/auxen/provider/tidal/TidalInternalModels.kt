package io.github.auxen.provider.tidal

import io.github.auxen.model.Track

/**
 * The internal-v1 domain types + pure merge/parse helpers that OUTLIVED the
 * official-v2 stack. Every symbol here was relocated (unchanged) out of a
 * deleted `TidalMetadata*` file because surviving internal-v1 code still uses it:
 *  - [ArtistLink] / [AlbumSummary] — the artist page, multi-type search, the
 *    discography row and the search screen all render them;
 *  - [releaseYearOf] — [TidalV1Album.toAlbumSummary] parses v1 release dates with it;
 *  - [normalizeTitleForDedup] / [dedupRemoteAlbums] / [mergeArtistTracks] — the
 *    artist screen's local/remote album + track merge;
 *  - [TrackRelations] — [trackRelationsFromV1] projects a seed track's ids into it.
 *
 * They live in one file so the relocation is a single, reviewable move; the
 * package is unchanged, so no caller's import moved.
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

/** Release year from `Albums_Attributes.releaseDate` (ISO date "YYYY-MM-DD"); null when absent/malformed. */
fun releaseYearOf(releaseDate: String?): Int? = releaseDate?.take(4)?.toIntOrNull()

/**
 * The ids needed to fetch an artist/album detail document, resolved from a
 * single Tidal-sourced track via `GET /tracks/{id}?include=artists,albums`.
 * [isrc] is carried for the documented ISRC/barcode fallback (see the plan's
 * Architecture) — unused while direct id-equivalence holds.
 */
data class TrackRelations(val artistId: String?, val albumId: String?, val isrc: String?)

private val TITLE_PUNCT = Regex("""[-–—….]+""")
private val TITLE_WHITESPACE = Regex("""\s+""")

/**
 * A title folded to its dedup identity: lowercase, with the dash family
 * (`-` `–` `—`), ellipses (`…`) and dots folded to spaces, whitespace collapsed,
 * trimmed.
 *
 * **This is DELIBERATELY MORE AGGRESSIVE THAN THE DESKTOP, which compares raw
 * lowercase titles** (`window.py:1394-1407`). The user saw the desktop's rule
 * fail on device and asked Android to beat it (2026-07-16). Do not "restore
 * parity" by reverting to `title.lowercase()`.
 *
 * **What it must merge** — Tidal ships one release under several stylings:
 * `Hardwired...To Self Destruct` / `Hardwired…To Self-Destruct` /
 * `Hardwired... To Self-Destruct` all fold to `hardwired to self destruct`. So do
 * `St. Anger`/`St Anger` and `...And Justice for All`/`And Justice for All`.
 *
 * **What it must NOT merge, and why the rule stops where it does:**
 * - **Parentheticals are untouched.** `72 Seasons` and `72 Seasons (Atmos Mix)`
 *   are different releases, and clean/explicit variants both showing is a
 *   deliberate product decision. Stripping `(...)` would eat both.
 * - **A dash folds to a SPACE, never to nothing.** `Re-Load` -> `re load` stays
 *   distinct from `Reload`; folding to nothing would merge them. Those two may
 *   well be the same album, but "may well be" is not good enough to delete a row
 *   — a missed merge is a cosmetic duplicate, a wrong merge silently hides a
 *   release the user owns.
 * - **An all-punctuation title falls back to its raw lowercase form.** `...`
 *   folds to `""`, and two such titles would then collide and eat each other.
 *
 * Validated against Metallica's real discography (the source of both reported
 * cases): every merge it produces is a genuine styling variant of one release,
 * and every distinct album survives.
 *
 * `lowercase()` is Locale.ROOT, keeping the dedup independent of device locale.
 */
fun normalizeTitleForDedup(title: String): String {
    val folded = title.lowercase()
        .replace(TITLE_PUNCT, " ")
        .replace(TITLE_WHITESPACE, " ")
        .trim()
    return folded.ifBlank { title.lowercase().trim() }
}

/**
 * The Tidal-only half of the artist discography: every [remote] album whose
 * title is not already among [localTitles].
 *
 * Follows the desktop's merge at `auxen/window.py:1394-1407` in SHAPE — seed the
 * seen-set from the local titles, append a Tidal album only if unseen, and add
 * inside the loop so a duplicate *within* the Tidal list is dropped too (the
 * on-device probe of artist 8405 returned `Fixxxer (Remastered)` twice).
 * `seen.add(...)` returns false once present, giving both behaviours in one pass.
 *
 * **It deliberately DIVERGES from the desktop in its comparison**: identity is
 * [normalizeTitleForDedup], not a raw `lowercase()`. The desktop's exact match
 * kept both `Hardwired...To Self Destruct` and `Hardwired...To Self-Destruct` —
 * one hyphen apart — and the user asked Android to do better (2026-07-16). See
 * that function's KDoc for exactly how far the folding goes and why it stops
 * there. Do not revert this to `title.lowercase()` in the name of parity.
 *
 * [localTitles] takes **RAW** titles and normalizes them here. Normalizing only
 * the remote side would be a silent no-op bug — a local `Hardwired...To
 * Self-Destruct` would never match the folded remote and the duplicate would
 * survive the fix. Passing already-lowercased titles is harmless: the fold is
 * idempotent under lowercase.
 *
 * Returns only the remote-only albums, in remote order; the caller renders its
 * local albums first and then these, reproducing the desktop's list order
 * without this pure function needing to know the UI's local album type.
 */
fun dedupRemoteAlbums(localTitles: Set<String>, remote: List<AlbumSummary>): List<AlbumSummary> {
    val seen = localTitles.mapTo(mutableSetOf()) { normalizeTitleForDedup(it) }
    return remote.filter { seen.add(normalizeTitleForDedup(it.title)) }
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
 * **Three deliberate divergences from the Python, all traceable to this
 * codebase's own conventions or to on-device evidence:**
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
 * 3. **The TIDAL list is additionally deduped by [normalizeTitleForDedup],
 *    first-wins.** DELIBERATELY BEATING THE DESKTOP (user-asked, 2026-07-16): on
 *    device the top tracks showed `Enter Sandman` TWICE — the original and a
 *    remaster, two distinct catalog ids, so divergence 2's id dedup sailed right
 *    past it. Top tracks arrive in popularity order, so the first occurrence is
 *    the most popular and is the one to keep. Do not "restore parity" by removing
 *    this.
 *
 * **Divergence 3 is scoped to [topTracks] and must stay that way.** A local track
 * sharing a title with a Tidal top track is NOT a duplicate to delete: it is the
 * user's own file, offline-playable and genuinely distinct, and dropping it would
 * quietly erase their library from their own artist page. The reported bug was
 * two TIDAL ids; that is all this fixes.
 *
 * **[tidalIds] is built from the ORIGINAL [topTracks], not the title-deduped
 * list**, and that ordering is load-bearing. The remaster dropped from display
 * was still *in* the top list, so a local Tidal favorite carrying its id must
 * still be filtered out of the extras — otherwise dedup 3 would hand it straight
 * back as an "extra" and the duplicate row would return by the back door.
 */
fun mergeArtistTracks(topTracks: List<Track>, localTracks: List<Track>): List<Track> {
    if (topTracks.isEmpty()) return localTracks
    val tidalIds = topTracks.filter { it.isTidal }.mapTo(mutableSetOf()) { it.sourceId }
    val extras = localTracks.filterNot { it.isTidal && it.sourceId in tidalIds }
    val top = topTracks.distinctBy { normalizeTitleForDedup(it.title) }
    return (top + extras).distinctBy { "${it.source}:${it.sourceId}" }
}
