# Multi-type search — find artists + albums, not just tracks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make search return **artists** and **albums** as tappable results alongside tracks, each navigating straight to its detail page. Today search is **tracks-only** (`TidalProvider.search` hits `search/tracks`), so the just-rebuilt artist page — rich bio, Fans Also Like with photos, categorised discography — is nearly **unreachable**: nothing lets the user *find* an artist.

**User decision (2026-07-17 — on device):** *"they don't show in search results, they aren't clickable."* The desktop searches Track + Album + Artist (`auxen/providers/tidal.py:197-218`, `search_all`) and renders tappable artist/album rows. This ports that.

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), OkHttp (raw), Jetpack Compose + Navigation-Compose, JUnit4 + Robolectric + Roborazzi. Manual `Graph` service-locator DI (`AuxenApp.kt:137`). minSdk 26 / compileSdk 35.

---

## Ground truth — read before writing a line

`.superpowers/sdd/search-multitype-schema.md`, captured by hitting the live API with the desktop's own session on 2026-07-17. **It is ground truth. Do not guess at shapes; do not contradict it.** Everything below is traceable to it, to real Kotlin in this repo, to `tidalapi` on this machine (`/home/mrw1986/.local/lib/python3.14/site-packages/tidalapi/`), or to the Python desktop at `auxen/`.

**Request:** `GET https://api.tidal.com/v1/search` with `query`, `types=ARTISTS,ALBUMS,TRACKS` (comma-joined, UPPERCASE — probe-verified), `limit`, `offset`, `countryCode`. The current track-only call hits `search/tracks` and answers a bare `{"items":[…]}`; **this** hits `search` and answers a per-type envelope.

**Response top-level keys:** `['artists', 'albums', 'playlists', 'tracks', 'videos', 'topHit']`. Each of `artists`/`albums`/`tracks` is `{ items: [...], totalNumberOfItems, limit, offset }`. `topHit` is `{ value: <one item>, type: 'ARTISTS'|'ALBUMS'|'TRACKS' }`.

| type block | probed item keys (the fields we care about) | maps to |
|---|---|---|
| `artists` | `id`, `name`, `picture` (uuid), `popularity`, `url`, … | `ArtistLink` via `tidalV1ArtistPictureUrl` (**480x480**) |
| `albums` | `id`, `title`, `cover` (uuid), `releaseDate`, `explicit`, `numberOfTracks`, **`artists`** (plural) | `AlbumSummary` via `toAlbumSummary` (**640x640**) + `artists[0].name` |
| `tracks` | `id`, `title`, `duration`, `explicit`, `audioQuality`, `trackNumber`, `volumeNumber`, **`artists`** (plural), `album{id,title,cover}` | `Track` via `TidalV1PageTrack.toTrack()` |

---

## Two findings that change the design — do not "simplify" past them

Both were caught by reading the probe and the sources rather than assuming. Both would have shipped as bugs.

### Finding 1 — multi-type `search` track items have NO singular `artist` key. Reusing `TidalProvider`'s `TrackDto.toTrack` would blank every track's artist.

The probed `tracks` item keys are:

```
['accessType', 'adSupportedStreamReady', 'ai', 'album', 'allowStreaming', 'artists', 'audioModes',
 'audioQuality', 'bpm', 'copyright', 'djReady', 'duration', 'editable', 'explicit', 'id', 'isrc',
 'key', 'keyScale', 'mediaMetadata', 'mixes', 'payToStream', 'peak', 'popularity',
 'premiumStreamingOnly', 'replayGain', 'spotlighted', 'stemReady', 'streamReady', 'streamStartDate',
 'title', 'trackNumber', 'upload', 'url', 'version', 'volumeNumber']
```

`artists` (plural array) is present. **There is no singular `artist`.** This is the SAME shape `pages/artist`'s `TRACK_LIST` carries — and the artist-page rebuild already met and documented it (Finding 1 there; `TidalArtistPage.kt:111-135`). `tidalapi` confirms it is expected (`tidalapi/media.py:239-245`: reads `json_obj["artists"]` unconditionally, treats the singular as optional, falls back to `artists[0]`).

`TidalProvider`'s **private** `TrackDto` reads `val artist: ArtistDto = ArtistDto()` (`TidalProvider.kt:60`), a **singular** object defaulting to `name = ""`. Mapping a multi-type-search track through `TrackDto.toTrack()` would yield **`Track.artist == ""`** on every result row — and `TrackDto`/`SearchResponse`/`toTrack()` are `private` nested inside `TidalProvider` (`TidalProvider.kt:54-67`, `:326`), so a pure function in another file **cannot reference them at all**.

**The correct reuse is `TidalV1PageTrack` + its `internal fun toTrack()`** (`TidalArtistPage.kt:125-135`, `:259-277`), which reads `artists.firstOrNull()?.name` and lives in the same package. It maps quality, album art @640, duration, explicit, numbering — exactly what a track row needs. The existing track-only `search/tracks` path is untouched; it keeps using `TrackDto` because `search/tracks` **does** carry the singular `artist` and works today.

### Finding 2 — the album's artist name is in the payload (`artists[0].name`) but `AlbumSummary` / `TidalV1Album` cannot carry it. One additive field fixes it.

The probe's `albums` items carry **`artists`** (plural) — the schema explicitly calls out `artists[0].name` as the album's artist, needed for **both** the card subtitle and the `albumRoute(title, **artist**, tidalAlbumId)` argument (brief item 4). But:

- `AlbumSummary` (`TidalMetadataDetail.kt:21-28`) has **no artist-name field** — this is exactly the gap the artist-page rebuild flagged as its *Open question 4* and deliberately left for later.
- `TidalV1Album` (`TidalInternalArtist.kt:35-45`) models only the **singular** `artist: TidalV1AlbumArtist?` (always null here — the payload has plural `artists`), not `artists`.

Two clean options existed: (a) render album cards with `artist = null` and pass `artist = ""` to the route (self-corrects in `AlbumDetailScreen` via the non-null `tidalAlbumId`, exactly as the artist-page album cards do), or (b) capture the real name. **This plan takes (b)** because the brief wants it and we already hold the data. The fix is the minimal additive one the artist-page plan itself pre-identified: **add `val artists: List<TidalV1AlbumArtist> = emptyList()` to `TidalV1Album`.**

**It is provably inert for the two existing callers.** `toAlbumSummary()` does not read `artists`; `artistAlbumsFrom` and `artistPageFrom` map albums through `toAlbumSummary()` and never touch it. Nothing else reads `TidalV1Album.artist` (singular) either — grep proof below. A defaulted field can never change the decode of a body that lacks the key, so `TidalInternalArtistTest` stays green. The search projection pairs the reused `AlbumSummary` with `artists.firstOrNull()?.name` in a small domain wrapper, `TidalAlbumResult`.

---

## The reuse map — GREP FIRST, never re-declare

Kotlin "conflicting declarations" is a **compile error** and has bitten this series repeatedly. Every type/helper the new code needs already exists and is REUSED; every genuinely new name was greped and is free.

**Reused (naming any of these again is a compile error):**

| Symbol | Where | Role in this plan |
|---|---|---|
| `TidalV1PagedList` | `TidalArtistPage.kt:67` | each search type block's `{items,…}` envelope (raw `JsonElement` items) |
| `decodeEach` | `TidalArtistPage.kt:286` (**widen `private`→`internal`**) | item-level totality: decode one at a time under `runCatching` |
| `TidalV1PageTrack` + `toTrack()` | `TidalArtistPage.kt:125`, `:259` (`internal`) | search track → `Track` (Finding 1) |
| `TidalV1Album` + `toAlbumSummary()` | `TidalInternalArtist.kt:35`, `:171` (`internal`) | search album → `AlbumSummary` (**+ additive `artists`**) |
| `TidalV1AlbumArtist` | `TidalInternalArtist.kt:32` | element type of the new `artists` field |
| `TidalV1PageArtist` | `TidalArtistPage.kt:105` | search artist item (carries `picture`, unlike `TidalV1SearchArtist`) |
| `tidalV1ArtistPictureUrl` | `TidalArtistPage.kt:207` | artist picture @ **480** |
| `tidalV1CoverUrl` | `TidalInternalArtist.kt:144` | album/track cover @ **640** (via the reused mappers) |
| `ArtistLink`, `AlbumSummary` | `TidalMetadataDetail.kt:18`, `:21` | domain result types |
| `ArtistRow`, `AlbumCard`, `AuxenTrackRow`, `SectionHeader`, `EmptyState` | `ui/components/` | result rows/rails (all have current signatures verified) |
| `artistRoute`, `albumRoute` | `MainActivity.kt:179`, `:181` | navigation (via new `SearchScreen` callbacks) |

**New (greped free — see verification in Task 1):** `TidalSearchResults`, `TidalAlbumResult`, `TidalMultiTypeSearchResponse`, `searchAllUrl`, `searchResultsFrom`, `TidalProvider.searchAll`, `PlayerViewModel.searchAlbums`, `PlayerViewModel.searchArtists`.

**Do NOT confuse with the exact-match artist search already in the tree:** `TidalV1SearchResponse` / `TidalV1ArtistsPage` / `TidalV1SearchArtist` / `artistIdFromSearch` / `artistSearchUrl` (`TidalInternalArtist.kt:126-238`) are for `searchArtistIdExact` (one artist id from a name, no picture). This plan adds a **parallel** multi-type surface; it does **not** touch or rename those.

---

## THE UI DECISION — sectioned results in one scroll, and how the source filter coexists

The screen today (`SearchScreen.kt`) has a **source** `FilterChip` row `["All","Local","Tidal"]` (`:55`, `:169-180`) and a single tracks `LazyColumn` (`:202-217`). The desktop uses **type tabs** (Tracks / Albums / Artists / Playlists).

**This plan renders TYPE SECTIONS in one vertical scroll** — an "Artists" rail, an "Albums" rail, then the "Tracks" list — reusing the exact `LazyColumn` + `item { SectionHeader }` + `item { LazyRow { … } }` + `items(tracks)` pattern the rebuilt `ArtistDetailScreen` already ships. Chosen over type tabs because:

1. **It reuses the most and adds no new state machine.** No tab index to persist, no per-tab empty states. The artist page proves the carousel-then-list pattern in this codebase.
2. **It coexists cleanly with the existing source chips** (which are *orthogonal*: source = where a **track** came from; sections = result **type**). Type tabs would fight the source chips for the same header strip.
3. **Phone-idiomatic.** Tidal/Spotify/Apple search all show horizontal artist/album shelves above a songs list. One scroll, everything discoverable — which is the whole point (the user could not *find* artists).

**How the source filter coexists — the coexistence rule (forced by the data model, not invented):** the local DB has **no artist/album entities yet** (that is Slice 2), so artists and albums are **Tidal-only, by construction**. Therefore:

- The source chip filters **only the Tracks section** (unchanged `when (typeFilter)` semantics — the same branch `ScreensLogicTest.searchTypeFilterSemantics` mirrors at `:81-85`).
- Under **"All"** and **"Tidal"**: Artists + Albums sections show (they are Tidal results).
- Under **"Local"**: Artists + Albums sections are **hidden entirely** (there are no local artists/albums to show), and the Tracks list is filtered to `Source.LOCAL` — exactly today's behaviour.

This rule is the *only* coherent one given there are no local artist/album entities; it is not a free UX choice. **The free choice — sections vs type tabs, and whether to keep the source chips at all — IS escalated below (Open question 1).** The plan builds the sectioned design; if the user prefers tabs, only Task 3 changes.

**Not logged in to Tidal:** `searchAll` returns `TidalSearchResults.EMPTY` → Artists/Albums empty (sections hidden), Tracks = local only. Graceful, no crash, no empty rails.

---

## What ships, what is deferred, what is escalated

### Shipping

| Section | Source | Ships? |
|---|---|---|
| Artists rail | `search` `artists` block → `ArtistLink` (picture @480) → `ArtistRow` in a `LazyRow` | **YES** — the headline fix: artist pages become reachable |
| Albums rail | `search` `albums` block → `AlbumSummary` + `artistName` → `AlbumCard` in a `LazyRow` | **YES** |
| Tracks list | local + `search` `tracks` block, merged by `DuplicateResolver` (unchanged) | **YES** — today's behaviour, now fed by `searchAll().tracks` |

### Deferred — with the reason, not a shrug

| Thing | Why it is not shipping |
|---|---|
| **`topHit`** (`{value, type}`) | **DEFERRED — presentation is a real mini-design, and it is optional.** The payload's `topHit.value` is an **untyped single item** whose shape depends on `topHit.type` (`ARTISTS`\|`ALBUMS`\|`TRACKS`); a "Top result" row means type-dispatching one item to one of three row renderers and deciding its visual weight above the sections. That is a UX decision (Open question 2), and every result already appears in its own section, so nothing is lost by deferring. The field is one projection away whenever a "Top result" row is designed — **not projected now** (this plan does not add a field nothing reads). |
| **`playlists`, `videos`** | **DROPPED — no surface.** Same reasons the artist-page rebuild dropped them: Auxen has no video surface, and Tidal playlists are uuid-keyed with no detail screen (`MainActivity.kt:371` parses `playlistId.toLongOrNull()`, a **local** row). A rail whose taps do nothing is worse than no rail. Not projected. |
| **Local artists/albums** | **NOT AVAILABLE — Slice 2.** The local DB has no artist/album entities. Artists/albums are Tidal-only here, and the coexistence rule above says exactly what shows under "Local" (nothing but tracks). |

### Escalated — I am not inventing these

**Open question 1 — sections vs type tabs, and the fate of the source chips.** This plan ships **type sections in one scroll**, keeping the source chips (which filter only the Tracks section). The defensible alternative is **desktop-parity type tabs** (Tracks / Albums / Artists), which would likely *replace* the source chips (a "Local" concept only applies to tracks). Both are reasonable; picking one is a product call. **The coexistence rule is not the open question — it is forced by the data.** Whether the surface is sections or tabs IS. If the user wants tabs, only Task 3's screen body changes; Tasks 1–2 are identical either way. **Recommendation: ship sections now (least disruptive, maximal reuse), revisit tabs if desired.**

**Open question 2 — `topHit` "Top result" row.** Deferred above. If wanted, it needs: which types get a Top-result row, its visual weight vs the sections, and whether it suppresses the duplicate in its own section. A genuine design, not an engineering gap. **Escalating rather than building.**

---

## Global Constraints

- **Additive, read-only internal-v1 only.** This plan adds exactly **ONE** new call to `TidalProvider`: `searchAll(query)` → `GET api.tidal.com/v1/search`. It is a catalog GET: it resolves no stream and touches no session state. The existing track-only `search()` (the `MusicProvider` interface method) is **untouched and kept** — `search/tracks` carries the singular `artist` and works today; other callers (none besides the ViewModel today) keep it as their contract.
- **Internal-v1 auth and streaming are UNTOUCHED.** `TidalAuth.kt` gets no change. `restoreSession`, `logout`, `validToken`, `get`, `search`, `getStreamInfo`, `getStreamInfoById`, `toTrack` and the existing private DTOs are **not edited**; no existing method changes behaviour or signature. Confirm with `git status` at Task 3.
- **Playback is unchanged.** Search tracks are ordinary `Track(source = Source.TIDAL, sourceId = <catalog id>)` values that play through the existing pipeline (`Graph.mediaItemFor` → `auxen://tidal/<id>` → `TrackResolver` → `getStreamInfoById`). Album cards carry `tidalAlbumId` into the existing `AlbumDetailScreen`; artist rows carry `tidalArtistId` into the existing `ArtistDetailScreen`. **No new playback or detail code.**
- **`TidalMetadataRepository`'s constructor is FROZEN and holds no `TidalProvider`.** `searchAll` belongs on the provider; the ViewModel composes local + Tidal (`AuxenApp.kt:190`, and the same precedent the artist page follows). **`searchAll` does NOT go on the repository.**
- **Never re-declare an existing type.** Reuse everything in the reuse map. The one shared-type edit — adding `artists` to `TidalV1Album` — is **additive** (a new defaulted field), not a re-declaration, and is proven inert for the two existing callers.
- **Conversely: ADD THE IMPORT.** Kotlin resolves per file. New code in an existing file naming a type it does not import is a compile error. Every step below lists its imports.
- **Never log or print `accessToken`/`refreshToken`.** `searchAll` logs nothing; PKCE is a public client and never sends `client_secret`.
- **Do not unit-test live HTTP.** Unit-test PURE logic only: the request shape (`searchAllUrl`) and the body→domain projection (`searchResultsFrom`). The suspend/network wrapper (`searchAll`), the `PlayerViewModel` composition and the Compose screen are NOT unit-tested and close on the Task 3 on-device checkpoint — established precedent.
- **Roborazzi goldens must not shift.** `AlbumCard` (`album-card-{light,dark}.png`) and `AuxenTrackRow` (`track-row-{local-explicit,tidal-favorite}-{light,dark}.png`) have goldens owned by `ComponentScreenshotTest`; `ArtistRow` has none. This plan **reuses** those components and **passes no new defaults into them** — it edits none of the three component files. **A shifted golden means a leaked default — fix the default, never re-record.**
- **Gradle lines MUST carry `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`** (the system JDK 25 breaks Gradle).
- **Git: explicit pathspecs only.** Never `git add -A` / `git add .` — another agent may be committing Kotlin concurrently.
- Every code step below shows the actual code. No `TODO`, no "add error handling", no "similar to above".

---

### Task 1: The pure parse — `TidalMultiTypeSearch.kt` (all the testable logic)

This is where nearly all the value and all the testable logic lives: the request shape, the per-type envelope, item-level totality, the two artwork sizes (480 artist / 640 album/track), and the three projections. It reuses the artist-page and internal-artist primitives wholesale.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMultiTypeSearch.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalArtistPage.kt` (widen `decodeEach` `private`→`internal`; **nothing else**)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalArtist.kt` (add one defaulted field to `TidalV1Album`; **nothing else**)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMultiTypeSearchTest.kt` (create)

**Interfaces:**
- Consumes: `TidalV1PagedList`, `decodeEach`, `TidalV1PageTrack.toTrack`, `TidalV1PageArtist`, `tidalV1ArtistPictureUrl` (`TidalArtistPage.kt`); `TidalV1Album.toAlbumSummary`, `TidalV1AlbumArtist` (`TidalInternalArtist.kt`); `AlbumSummary`, `ArtistLink` (`TidalMetadataDetail.kt`); `io.github.auxen.model.Track`.
- Produces: `fun searchAllUrl(apiBase, query, countryCode, limit = 20): HttpUrl`; `fun searchResultsFrom(body: String, json: Json): TidalSearchResults`; the domain types `TidalSearchResults` (+ `.EMPTY`), `TidalAlbumResult`; the DTO `TidalMultiTypeSearchResponse`.
- **Do NOT re-declare:** anything in the reuse map. In particular **not** `TidalV1SearchResponse`/`TidalV1ArtistsPage`/`TidalV1SearchArtist` (the exact-match search DTOs — different feature).
- **Do NOT touch:** `TidalProvider`'s private `TrackDto`/`SearchResponse`/`toTrack()` — unreachable by design (Finding 1).

- [ ] **Step 0: Prove every new name is free (no conflicting declarations)**

```bash
cd android/app/src && grep -rnE \
  "class TidalSearchResults|class TidalAlbumResult|class TidalMultiTypeSearchResponse|fun searchAll\b|fun searchResultsFrom|fun searchAllUrl|TidalMultiTypeSearch\b" \
  --include=*.kt .
```

**Expected: no hits.** If any name is taken, STOP and rename — a duplicate top-level declaration in `io.github.auxen.provider.tidal` is a compile error, not a warning.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMultiTypeSearchTest.kt`. The fixture is trimmed verbatim from the probed schema (CAKE artist 572, *Fashion Nugget* album 660646, *The Distance* track 668138) — **note the album and track items carry `artists` plural with NO singular `artist`; that is the real payload, not an omission**:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalMultiTypeSearchTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Trimmed from the live probe of `search?query=cake&types=ARTISTS,ALBUMS,TRACKS`
     * (.superpowers/sdd/search-multitype-schema.md). Deliberately includes:
     *  - `artists` with a picture AND a picture:null item (the Person-glyph fallback)
     *  - `albums` whose items carry `artists` plural (no singular `artist`) and a
     *    MALFORMED id-less album -> dropped, sibling survives
     *  - `tracks` whose items carry `artists` plural (Finding 1)
     *  - `playlists`, `videos`, `topHit` present -> ignored, parse still works
     */
    private val body = """
        {
          "artists": {
            "limit": 20, "offset": 0, "totalNumberOfItems": 111,
            "items": [
              { "id": 572, "name": "CAKE", "picture": "73f443d3-0ebd-43e0-a3ba-1d470d9b131a",
                "popularity": 78, "url": "http://www.tidal.com/artist/572" },
              { "id": 3707146, "name": "CAKE (Tribute)", "picture": null }
            ]
          },
          "albums": {
            "limit": 20, "offset": 0, "totalNumberOfItems": 92,
            "items": [
              { "id": 660646, "title": "Fashion Nugget", "cover": "12345678-90ab-cdef-1234-567890abcdef",
                "releaseDate": "1996-09-17", "explicit": false, "numberOfTracks": 14,
                "artists": [ { "id": 572, "name": "CAKE" } ] },
              { "title": "A malformed album with no id", "artists": [ { "id": 572, "name": "CAKE" } ] }
            ]
          },
          "tracks": {
            "limit": 20, "offset": 0, "totalNumberOfItems": 214,
            "items": [
              { "id": 668138, "title": "The Distance", "duration": 181, "explicit": false,
                "audioQuality": "LOSSLESS", "trackNumber": 2, "volumeNumber": 1,
                "artists": [ { "id": 572, "name": "CAKE" } ],
                "album": { "id": 660646, "title": "Fashion Nugget", "cover": "12345678-90ab-cdef-1234-567890abcdef" } }
            ]
          },
          "playlists": { "items": [] },
          "videos": { "items": [] },
          "topHit": { "value": { "id": 572, "name": "CAKE" }, "type": "ARTISTS" }
        }
    """.trimIndent()

    private fun results() = searchResultsFrom(body, json)

    // ---- 1. the request shape ----

    @Test
    fun `searchAllUrl hits search (not search-tracks) with all three types`() {
        val url = searchAllUrl(apiBase = "https://api.tidal.com/v1", query = "cake", countryCode = "US", limit = 20)
        assertEquals("api.tidal.com", url.host)
        assertEquals("/v1/search", url.encodedPath) // NOT /v1/search/tracks
        assertEquals("cake", url.queryParameter("query"))
        assertEquals("ARTISTS,ALBUMS,TRACKS", url.queryParameter("types"))
        assertEquals("20", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals(
            setOf("query", "types", "limit", "offset", "countryCode"),
            url.queryParameterNames,
        )
    }

    // ---- 2. artists -- WITH the photos, so the rail is not a wall of glyphs ----

    @Test
    fun `searchResultsFrom maps artists with 480 pictures and tolerates a null picture`() {
        // Picture @480 (the ARTIST size; 640 is the ALBUM size and does not
        // render for a picture -- tidalV1ArtistPictureUrl's KDoc). id as String,
        // ready for artistRoute(name, tidalArtistId=id).
        val artists = results().artists
        assertEquals(listOf("CAKE", "CAKE (Tribute)"), artists.map { it.name })
        assertEquals(listOf("572", "3707146"), artists.map { it.id })
        assertEquals(
            "https://resources.tidal.com/images/73f443d3/0ebd/43e0/a3ba/1d470d9b131a/480x480.jpg",
            artists[0].artworkUrl,
        )
        assertNull(artists[1].artworkUrl) // picture: null -> the Person glyph
    }

    // ---- 3. albums -- with the artist name the route needs (Finding 2) ----

    @Test
    fun `searchResultsFrom maps albums with 640 cover, year, explicit and the artist name`() {
        val albums = results().albums
        assertEquals(1, albums.size) // the id-less album is dropped
        val fashion = albums.single()
        assertEquals("660646", fashion.summary.id)
        assertEquals("Fashion Nugget", fashion.summary.title)
        assertEquals(1996, fashion.summary.releaseYear)
        assertEquals(false, fashion.summary.explicit)
        assertEquals(
            "https://resources.tidal.com/images/12345678/90ab/cdef/1234/567890abcdef/640x640.jpg",
            fashion.summary.artworkUrl,
        )
        // FINDING 2: artists[0].name, carried via the new TidalV1Album.artists
        // field. AlbumSummary has no artist field, so the wrapper holds it -- for
        // the card subtitle AND the albumRoute(title, artist, tidalAlbumId) arg.
        assertEquals("CAKE", fashion.artistName)
    }

    @Test
    fun `searchResultsFrom drops a malformed album and keeps its siblings`() {
        // The id-less album is decoded one-at-a-time under runCatching (reused
        // decodeEach), so one bad row cannot cost the whole albums section.
        assertEquals(listOf("Fashion Nugget"), results().albums.map { it.summary.title })
    }

    // ---- 4. tracks -- Finding 1: artist from `artists` plural, never blank ----

    @Test
    fun `searchResultsFrom reads the track artist from artists plural - there is no singular artist`() {
        // FINDING 1. Multi-type search track items carry `artists` and NO
        // `artist`. TidalProvider's private TrackDto reads the SINGULAR and
        // defaults to name="" -- routing this through it would blank every result
        // row's artist. TidalV1PageTrack.toTrack reads artists[0]. That is why
        // the reuse is TidalV1PageTrack, not TrackDto.
        val tracks = results().tracks
        assertEquals(listOf("The Distance"), tracks.map { it.title })
        assertEquals(listOf("CAKE"), tracks.map { it.artist })
        assertFalse(tracks.any { it.artist.isBlank() })
        assertTrue(tracks.all { it.isTidal })
        assertEquals(listOf("668138"), tracks.map { it.sourceId })
    }

    @Test
    fun `searchResultsFrom maps track album, art at 640, quality, duration and numbering`() {
        val distance = results().tracks.single()
        assertEquals("Fashion Nugget", distance.album)
        assertEquals(181.0, distance.durationSeconds!!, 0.001)
        assertEquals(2, distance.trackNumber)
        assertEquals(1, distance.discNumber)
        assertEquals("FLAC", distance.format)
        assertEquals("FLAC", distance.qualityLabel)
        assertEquals(
            "https://resources.tidal.com/images/12345678/90ab/cdef/1234/567890abcdef/640x640.jpg",
            distance.albumArtUrl,
        )
    }

    // ---- 5. TOTALITY -- never crash, never lose the other types over one ----

    @Test
    fun `searchResultsFrom ignores playlists, videos and topHit`() {
        // They are in the fixture and are not projected. This endpoint is
        // undocumented; ignoreUnknownKeys drops what we do not model.
        val r = results()
        assertEquals(2, r.artists.size)
        assertEquals(1, r.albums.size)
        assertEquals(1, r.tracks.size)
    }

    @Test
    fun `searchResultsFrom yields an empty block for a query that returns no artists`() {
        // An EMPTY type block: items: []. Albums/tracks still parse.
        val emptyArtists = """
            { "artists": { "items": [] },
              "albums": { "items": [ { "id": 660646, "title": "Fashion Nugget",
                "artists": [ { "id": 572, "name": "CAKE" } ] } ] },
              "tracks": { "items": [] } }
        """.trimIndent()
        val r = searchResultsFrom(emptyArtists, json)
        assertTrue(r.artists.isEmpty())
        assertEquals(listOf("Fashion Nugget"), r.albums.map { it.summary.title })
        assertTrue(r.tracks.isEmpty())
    }

    @Test
    fun `searchResultsFrom yields an empty block for a MISSING type key`() {
        // A body with only `tracks` -- no `artists`/`albums` keys at all. The
        // defaulted TidalV1PagedList() blocks make a missing key empty, not a throw.
        val tracksOnly = """
            { "tracks": { "items": [
                { "id": 1, "title": "Solo", "artists": [ { "id": 9, "name": "Nobody" } ] }
            ] } }
        """.trimIndent()
        val r = searchResultsFrom(tracksOnly, json)
        assertTrue(r.artists.isEmpty())
        assertTrue(r.albums.isEmpty())
        assertEquals(listOf("Solo"), r.tracks.map { it.title })
        assertEquals(listOf("Nobody"), r.tracks.map { it.artist })
    }

    @Test
    fun `searchResultsFrom returns EMPTY for malformed JSON or an error envelope`() {
        // Every one means the same thing to the caller: no Tidal results, and the
        // screen shows local tracks (or nothing) -- never a crash.
        assertEquals(TidalSearchResults.EMPTY, searchResultsFrom("not json at all", json))
        assertEquals(TidalSearchResults.EMPTY, searchResultsFrom("<html>502 Bad Gateway</html>", json))
        // A v1 error envelope decodes CLEANLY into all-default (empty) blocks.
        val err = searchResultsFrom("""{ "status": 401, "userMessage": "unauthorized" }""", json)
        assertTrue(err.artists.isEmpty() && err.albums.isEmpty() && err.tracks.isEmpty())
    }
}
```

- [ ] **Step 2: RED — run the tests and watch them fail**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalMultiTypeSearchTest*'
```

**Expected: FAIL — the file does not compile.** Every symbol it names is unresolved: `searchAllUrl`, `searchResultsFrom`, `TidalSearchResults`, `TidalAlbumResult`, and `.artists`/`.albums`/`.tracks`/`.artistName`/`.summary` accessors. This is the RED state: **`TidalMultiTypeSearch.kt` does not exist yet.** If it compiles, you are in the wrong tree.

- [ ] **Step 3: Widen `decodeEach` in `TidalArtistPage.kt`**

`decodeEach` is the item-level totality helper — decode each element under `runCatching`, drop the failures. It is `private`, so the new file cannot reach it. Widen it — a **visibility change only, no behaviour change**; its three existing callers in `artistPageFrom` are unaffected.

At `TidalArtistPage.kt:286`, change exactly this one line:

```kotlin
private inline fun <reified T> List<JsonElement>.decodeEach(json: Json): List<T> =
```

to:

```kotlin
/**
 * Decode each element independently, dropping the ones that fail. `internal`
 * rather than private because `search`'s per-type blocks reuse it for the same
 * item-level totality — one malformed row must never cost a whole type section.
 */
internal inline fun <reified T> List<JsonElement>.decodeEach(json: Json): List<T> =
```

**Do not change the body or the existing KDoc paragraph above it.**

- [ ] **Step 4: Add the `artists` field to `TidalV1Album` in `TidalInternalArtist.kt`**

`TidalV1Album` models the album item everywhere else via the singular `artist` (always null for the multi-type payload). The search album's real artist is in `artists` plural. Add it — **additive, a new defaulted field, no behaviour change** — reusing the existing `TidalV1AlbumArtist`. Grep confirms nothing reads `TidalV1Album.artist` today and `toAlbumSummary` ignores both, so the two existing callers (`artistAlbumsFrom`, `artistPageFrom`) are untouched.

At `TidalInternalArtist.kt:44`, change:

```kotlin
    val artist: TidalV1AlbumArtist? = null,
)
```

to:

```kotlin
    val artist: TidalV1AlbumArtist? = null,
    /**
     * Plural artists — the multi-type `search` album item carries this, not the
     * singular `artist` (which is null there). Read ONLY by the search projection
     * ([searchResultsFrom]) for the album's artist name; [toAlbumSummary] ignores
     * it and `AlbumSummary` has no artist field, so this is inert for the
     * discography and pages/artist callers. Defaulted, so a body without the key
     * (every `artists/{id}/albums` and `pages/artist` album) decodes unchanged.
     */
    val artists: List<TidalV1AlbumArtist> = emptyList(),
)
```

- [ ] **Step 5: Create `TidalMultiTypeSearch.kt`**

Create `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMultiTypeSearch.kt`. Imports mirror `TidalArtistPage.kt` (a sibling in the same package that decodes the same way): `json.decodeFromString<T>` needs only `kotlinx.serialization.json.Json` here, exactly as `TidalArtistPage.kt:307` and `TidalInternalArtist.kt:159` use it.

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The pure parts of `GET api.tidal.com/v1/search` with `types=ARTISTS,ALBUMS,TRACKS`
 * — the multi-type search that makes ARTISTS and ALBUMS reachable, not just tracks.
 *
 * Probed live 2026-07-17 with the desktop's own session; the captured schema is
 * `.superpowers/sdd/search-multitype-schema.md` and it is ground truth here.
 *
 * **This is additive alongside the track-only [TidalProvider.search].** That one
 * hits `search/tracks` (a bare `{"items":[…]}` whose items carry the singular
 * `artist`); this hits `search` and gets a per-type envelope whose track/album
 * items carry `artists` PLURAL and no singular `artist`. That difference is why
 * the reuse here is [TidalV1PageTrack] (reads `artists[0]`), never
 * [TidalProvider]'s private `TrackDto` (reads the singular, would blank every
 * result's artist). See the plan's Finding 1.
 *
 * **The parse is TOTAL.** A non-JSON body or a v1 error envelope yields
 * [TidalSearchResults.EMPTY] (never a throw); a missing or empty type block
 * yields an empty list for that type; and items decode ONE AT A TIME (reused
 * [decodeEach]) so one malformed row cannot cost a whole type section. A failure
 * here degrades search to local-only — never breaks it.
 */

/**
 * A search album result: the reused [AlbumSummary] plus [artistName] from the
 * item's `artists` plural.
 *
 * [AlbumSummary] has no artist field (the artist-page rebuild's Open question 4),
 * but the multi-type album item carries `artists[0].name`, needed for both the
 * card subtitle and the `albumRoute(title, artist, tidalAlbumId)` argument. The
 * name is captured via the additive [TidalV1Album.artists] field.
 */
data class TidalAlbumResult(val summary: AlbumSummary, val artistName: String?)

/**
 * A multi-type search result bundle: tracks (local merge happens in the
 * ViewModel), Tidal albums, Tidal artists. Artists/albums are Tidal-only — the
 * local DB has no artist/album entities yet (Slice 2).
 */
data class TidalSearchResults(
    val tracks: List<Track>,
    val albums: List<TidalAlbumResult>,
    val artists: List<ArtistLink>,
) {
    companion object {
        /** Not logged in, network failure, or a non-search body — no Tidal results. */
        val EMPTY = TidalSearchResults(emptyList(), emptyList(), emptyList())
    }
}

/**
 * The `search` response envelope: each requested type nested under its own key,
 * `{ "artists": {items…}, "albums": {items…}, "tracks": {items…}, … }`. Reuses
 * [TidalV1PagedList] (raw `JsonElement` items) for every block, so the items are
 * decoded per type by [searchResultsFrom]. Each block DEFAULTS to an empty
 * [TidalV1PagedList] so a missing type key is empty, not a throw; `playlists`,
 * `videos` and `topHit` are dropped by `ignoreUnknownKeys`.
 */
@Serializable
data class TidalMultiTypeSearchResponse(
    val artists: TidalV1PagedList = TidalV1PagedList(),
    val albums: TidalV1PagedList = TidalV1PagedList(),
    val tracks: TidalV1PagedList = TidalV1PagedList(),
)

/**
 * `GET {apiBase}/search?query=…&limit=…&offset=0&types=ARTISTS,ALBUMS,TRACKS&countryCode=…`.
 *
 * Same param set as the exact-match [artistSearchUrl] (the other `search` caller),
 * with all three types. `types` is `ARTISTS,ALBUMS,TRACKS` — the probe-verified
 * UPPERCASE comma-joined form (the schema notes the endpoint is case-insensitive
 * on types; this ships what was probed). Unlike `pages/artist`, `search` ACCEPTS
 * `limit`, so it is sent.
 */
fun searchAllUrl(apiBase: String, query: String, countryCode: String, limit: Int = 20): HttpUrl =
    "$apiBase/search".toHttpUrl().newBuilder()
        .addQueryParameter("query", query)
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", "0")
        .addQueryParameter("types", "ARTISTS,ALBUMS,TRACKS")
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * Pure projection: a `search` body -> [TidalSearchResults], or
 * [TidalSearchResults.EMPTY] for a non-search body (error envelope, HTML, not-JSON).
 *
 * Each type block's items decode one at a time (reused [decodeEach]): a track ->
 * [TidalV1PageTrack.toTrack] (artist from `artists[0]`, art @640), an album ->
 * [TidalV1Album.toAlbumSummary] paired with `artists[0].name`, an artist ->
 * [ArtistLink] with the picture @480 ([tidalV1ArtistPictureUrl]). All reused; no
 * new mapping is declared.
 */
fun searchResultsFrom(body: String, json: Json): TidalSearchResults {
    val response = runCatching { json.decodeFromString<TidalMultiTypeSearchResponse>(body) }.getOrNull()
        ?: return TidalSearchResults.EMPTY

    val tracks = response.tracks.items?.decodeEach<TidalV1PageTrack>(json)
        ?.map { it.toTrack() }
        .orEmpty()

    val albums = response.albums.items?.decodeEach<TidalV1Album>(json)
        ?.map { TidalAlbumResult(summary = it.toAlbumSummary(), artistName = it.artists.firstOrNull()?.name) }
        .orEmpty()

    val artists = response.artists.items?.decodeEach<TidalV1PageArtist>(json)
        ?.map { ArtistLink(id = it.id.toString(), name = it.name, artworkUrl = tidalV1ArtistPictureUrl(it.picture)) }
        .orEmpty()

    return TidalSearchResults(tracks = tracks, albums = albums, artists = artists)
}
```

- [ ] **Step 6: GREEN — run the tests**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalMultiTypeSearchTest*' --tests '*TidalInternalArtistTest*' --tests '*TidalArtistPageTest*'
```

**Expected: PASS.** `TidalMultiTypeSearchTest` green, and `TidalInternalArtistTest` + `TidalArtistPageTest` **still** green — Steps 3–4 were a visibility widen and an additive defaulted field, neither of which changes any existing behaviour. If `TidalInternalArtistTest` moved, you changed `TidalV1Album`'s decode of a body that lacks `artists` (impossible with a defaulted field) — revert and check for a typo.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMultiTypeSearch.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalArtistPage.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalArtist.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMultiTypeSearchTest.kt
git commit -m "feat(android): pure projection for multi-type internal-v1 search

GET /search with types=ARTISTS,ALBUMS,TRACKS returns a per-type envelope; this
projects it to artists + albums + tracks so artist and album pages become
reachable from search (today it is tracks-only via /search/tracks).

Reuses the artist-page/internal-artist primitives wholesale — TidalV1PagedList,
decodeEach (widened to internal), TidalV1PageTrack.toTrack, TidalV1Album.
toAlbumSummary, TidalV1PageArtist, tidalV1ArtistPictureUrl. Track artists read
from `artists` plural — multi-type search has no singular `artist` key
(Finding 1), so reusing TidalProvider's private TrackDto would blank every row.
The album's artist name comes from a new defaulted TidalV1Album.artists field
(inert for the discography/pages-artist callers).

Metadata only; streaming and auth untouched.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: The provider call + the ViewModel — three result lists

Four lines on `TidalProvider` and the search state on `PlayerViewModel`. The provider call is additive; the existing `search()` is untouched.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt` (add `searchAll`; **nothing else**)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (add `searchAlbums`/`searchArtists`; feed them from `search()`; clear them on blank)

**Interfaces:**
- Consumes: `searchAllUrl`, `searchResultsFrom`, `TidalSearchResults` (Task 1); `TidalProvider`'s private `API_BASE`/`json`/`countryCode`/`validToken()`/`get()`.
- Produces: `TidalProvider.searchAll(query: String, limit: Int = 20): TidalSearchResults`; `PlayerViewModel.searchAlbums: MutableStateFlow<List<TidalAlbumResult>>`, `PlayerViewModel.searchArtists: MutableStateFlow<List<ArtistLink>>`.
- **Does NOT touch** `TidalMetadataRepository` (frozen constructor) or the existing `search()`/`TrackDto`/`SearchResponse`.

- [ ] **Step 1: Add `searchAll` to `TidalProvider.kt`**

Add **directly after** the existing `search()` override (which ends at `TidalProvider.kt:119`), before the `getStreamInfo` KDoc. It is additive: no existing method is edited.

```kotlin
    /**
     * Multi-type search — artists + albums + tracks in one read-only catalog GET
     * on `search` (the endpoint `types=…` filters), so artist and album pages are
     * reachable from search, not just tracks. Metadata only: resolves no stream,
     * touches no session state.
     *
     * **Additive alongside [search].** [search] hits `search/tracks` (bare
     * `{"items":[…]}`, singular `artist`, kept for its interface contract); this
     * hits `search` and gets a per-type envelope whose items carry `artists`
     * plural — see [searchResultsFrom] and the plan's Finding 1.
     *
     * Returns [TidalSearchResults.EMPTY] when not logged in or on any HTTP/parse
     * failure: search then shows local tracks only (or nothing), never an error —
     * the same not-logged-in discipline as [search].
     */
    suspend fun searchAll(query: String, limit: Int = 20): TidalSearchResults {
        val token = validToken() ?: return TidalSearchResults.EMPTY
        val url = searchAllUrl(API_BASE, query, countryCode, limit)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return TidalSearchResults.EMPTY
        return searchResultsFrom(body, json)
    }
```

**No import is needed** — `searchAllUrl`, `searchResultsFrom` and `TidalSearchResults` are in the same package (`io.github.auxen.provider.tidal`).

- [ ] **Step 2: Add the two result flows to `PlayerViewModel.kt`**

Add the imports (grouped with the other `io.github.auxen.provider.tidal` imports near `PlayerViewModel.kt:31-33`):

```kotlin
import io.github.auxen.provider.tidal.ArtistLink
import io.github.auxen.provider.tidal.TidalAlbumResult
import io.github.auxen.provider.tidal.TidalSearchResults
```

Add the two flows immediately after `searchResults` (`PlayerViewModel.kt:76`):

```kotlin
    /**
     * Tidal-only album + artist results for the search screen, filled alongside
     * [searchResults] by [search]. Empty when the query is blank, when not logged
     * in to Tidal, or on any failure — the search screen hides those sections.
     * There is no local equivalent (the local DB has no artist/album entities
     * yet — Slice 2), so these are Tidal-only by construction.
     */
    val searchAlbums = MutableStateFlow<List<TidalAlbumResult>>(emptyList())
    val searchArtists = MutableStateFlow<List<ArtistLink>>(emptyList())
```

- [ ] **Step 3: Feed the flows from `search()`**

Replace the body of `search()` (`PlayerViewModel.kt:899-915`). The local merge is unchanged; the Tidal source switches from the track-only `search(query)` to `searchAll(query)`, and its albums/artists fill the two new flows under the same latest-query-wins guard:

```kotlin
    /** Search local library and Tidal (tracks + albums + artists), collapsing track duplicates. */
    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            searchInFlight = true
            val local = runCatching { Graph.local.search(query) }.getOrDefault(emptyList())
            val tidal = runCatching { Graph.tidal.searchAll(query) }.getOrDefault(TidalSearchResults.EMPTY)
            val priority = runCatching { Graph.library.sourcePriority() }
                .getOrDefault(SourcePriority.PREFER_QUALITY)
            val merged = DuplicateResolver.merge(local, tidal.tracks, priority)
            // Latest-query-wins: drop stale responses that resolve after the
            // field changed or was cleared. All three surfaces flip together so
            // the tracks list and the album/artist rails never show mismatched queries.
            if (query == searchQuery.value) {
                searchResults.value = merged
                searchAlbums.value = tidal.albums
                searchArtists.value = tidal.artists
                searchInFlight = false
            }
        }
    }
```

- [ ] **Step 4: Clear the flows on a blank query**

In `onSearchQueryChange` (`PlayerViewModel.kt:918-930`), extend the blank-query reset (`:921-925`) so the rails clear the instant the field empties:

```kotlin
        if (query.isBlank()) {
            searchResults.value = emptyList()
            searchAlbums.value = emptyList()
            searchArtists.value = emptyList()
            searchInFlight = false
            return
        }
```

- [ ] **Step 5: Build + the full Tidal suite**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*Tidal*' assembleDebug
```

**Expected: PASS; APK builds.** The provider call and the ViewModel composition are live-HTTP/stateful and not unit-tested; this is the compile-and-no-regression gate.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt
git commit -m "feat(android): TidalProvider.searchAll + album/artist search state

One additive read-only catalog GET on /search (types=ARTISTS,ALBUMS,TRACKS),
and two ViewModel flows (searchAlbums/searchArtists) filled alongside the
existing track results under the same latest-query-wins guard. Local search
stays tracks-only; artists/albums are Tidal-only and empty when logged out.

The track-only search() is untouched; auth and streaming untouched.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: The screen — Artists rail, Albums rail, Tracks list

> **Confirm the UI approach before building — see Open question 1.** This task ships **type sections in one scroll** with the source chips filtering only the Tracks section. If the user prefers desktop-parity **type tabs**, only this task changes; Tasks 1–2 are identical.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/SearchScreen.kt` (nav callbacks + the two rails)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (wire the callbacks to the existing routes)

**Interfaces:**
- Consumes: `PlayerViewModel.searchAlbums`/`searchArtists` (Task 2); `ArtistRow`, `AlbumCard`, `AuxenTrackRow`, `SectionHeader`, `EmptyState` (unchanged); `artistRoute`/`albumRoute` (`MainActivity.kt:179`, `:181`, unchanged).
- Produces: no new public API. `SearchScreen` gains two required callback params.
- **Roborazzi:** reuses `AlbumCard`/`AuxenTrackRow` with **no new defaults** and edits none of the three component files, so `ComponentScreenshotTest`'s goldens do not move. `ArtistRow` has no golden.

- [ ] **Step 1: Add the nav callbacks and the two result flows to `SearchScreen`**

Add the imports:

```kotlin
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.ArtistRow
```

Change the signature (`SearchScreen.kt:69`) — the two callbacks match `ArtistDetailScreen`'s exactly (`MainActivity.kt:356-360`):

```kotlin
fun SearchScreen(
    viewModel: PlayerViewModel,
    onOpenArtist: (String, String?) -> Unit,
    onOpenAlbum: (String, String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
```

Collect the two new flows beside the existing `results` (`SearchScreen.kt:71`):

```kotlin
    val albums by viewModel.searchAlbums.collectAsState()
    val artists by viewModel.searchArtists.collectAsState()
```

- [ ] **Step 2: Rebuild the results body as sections**

Replace the query-non-blank branch (`SearchScreen.kt:164-219` — the whole `else` block from the `Row { TYPE_FILTERS… }` through the tracks `LazyColumn`). The source chip `Row`, the spinner and the source `when` are preserved; the results become one `LazyColumn` with the two rails above the tracks:

```kotlin
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TYPE_FILTERS.forEach { filter ->
                    FilterChip(
                        selected = typeFilter == filter,
                        onClick = { typeFilter = filter },
                        label = { Text(filter) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            if (viewModel.searchInFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                )
            }
            // Source chip filters only TRACKS (source = where a track came from).
            // Artists/albums are Tidal-only (no local artist/album entities yet),
            // so "Local" hides those rails entirely — the plan's coexistence rule.
            // This `when` is mirrored by ScreensLogicTest.searchTypeFilterSemantics;
            // keep the two in sync.
            val filteredTracks = when (typeFilter) {
                "Local" -> results.filter { it.source == Source.LOCAL }
                "Tidal" -> results.filter { it.source == Source.TIDAL }
                else -> results
            }
            val showTypeSections = typeFilter != "Local"
            val hasResults = filteredTracks.isNotEmpty() ||
                (showTypeSections && (albums.isNotEmpty() || artists.isNotEmpty()))
            if (!hasResults && !viewModel.searchInFlight && !searchPending) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = stringResource(R.string.empty_search_no_results_title),
                    subtitle = stringResource(R.string.empty_search_no_results_subtitle),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Artists rail — the headline fix: tap navigates STRAIGHT to
                    // the rebuilt artist page with tidalArtistId, skipping the
                    // name-based id resolution chain.
                    if (showTypeSections && artists.isNotEmpty()) {
                        item(key = "artists-header") { SectionHeader("Artists") }
                        item(key = "artists-rail") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Deliberately UNKEYED — ArtistRow in a rail, same
                                // as ArtistDetailScreen's Fans-Also-Like row. The
                                // search list is short, static and never reordered,
                                // so the default index key is free; keying on id
                                // risks a hard crash if the API ever repeats one.
                                items(artists) { link ->
                                    ArtistRow(
                                        name = link.name,
                                        artUrl = link.artworkUrl,
                                        onClick = { onOpenArtist(link.name, link.id) },
                                        modifier = Modifier.width(200.dp),
                                    )
                                }
                            }
                        }
                    }
                    // Albums rail — tap opens AlbumDetailScreen with tidalAlbumId,
                    // whose remote tracklist is already playable.
                    if (showTypeSections && albums.isNotEmpty()) {
                        item(key = "albums-header") { SectionHeader("Albums") }
                        item(key = "albums-rail") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(albums, key = { "album:${it.summary.id}" }) { result ->
                                    AlbumCard(
                                        title = result.summary.title,
                                        artist = result.artistName,
                                        artUrl = result.summary.artworkUrl,
                                        source = Source.TIDAL,
                                        // artistName is the real album artist
                                        // (artists[0].name); AlbumDetailScreen also
                                        // re-fetches the album by tidalAlbumId, so
                                        // even an empty name self-corrects there.
                                        onClick = {
                                            onOpenAlbum(result.summary.title, result.artistName ?: "", result.summary.id)
                                        },
                                        // No local tracks to enqueue from a search
                                        // card — a tap opens the album to play.
                                        onPlay = null,
                                        // Carousel item — a fill-width card has no
                                        // width constraint inside a LazyRow.
                                        modifier = Modifier.width(150.dp),
                                    )
                                }
                            }
                        }
                    }
                    // A "Tracks" header only when a rail sits above it, so a
                    // local-only or tracks-only result never gets a lonely header.
                    if (showTypeSections && (artists.isNotEmpty() || albums.isNotEmpty()) && filteredTracks.isNotEmpty()) {
                        item(key = "tracks-header") { SectionHeader("Tracks") }
                    }
                    items(filteredTracks, key = { "${it.source}:${it.sourceId}" }) { track ->
                        AuxenTrackRow(
                            track = track,
                            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                            onPlay = { viewModel.play(track) },
                            onToggleFavorite = { viewModel.toggleFavorite(track) },
                            onLongPress = { sheetTrack = track },
                            trailing = {
                                IconButton(onClick = { viewModel.enqueue(track) }) {
                                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to queue")
                                }
                            },
                        )
                    }
                }
            }
        }
```

- [ ] **Step 3: Wire the callbacks in `MainActivity.kt`**

Replace the search composable (`MainActivity.kt:362`) — the callbacks reuse the existing local `artistRoute`/`albumRoute`, identical to how `ArtistDetailScreen` is wired (`:356-360`):

```kotlin
                    composable("search") {
                        SearchScreen(
                            viewModel,
                            onOpenArtist = { name, id -> navController.navigate(artistRoute(name, id)) },
                            onOpenAlbum = { album, artist, tidalAlbumId ->
                                navController.navigate(albumRoute(album, artist, tidalAlbumId))
                            },
                        )
                    }
```

- [ ] **Step 4: Build + the component screenshot suite**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*ComponentScreenshotTest*' --tests '*ScreensLogicTest*' assembleDebug
```

**Expected: PASS; APK builds; goldens unchanged.** `ComponentScreenshotTest` must stay green — this task edits none of `AlbumCard`/`AuxenTrackRow`/`ArtistRow` and passes them no new defaults, so nothing they render changed. **If a golden shifted, a default leaked — fix the default, do not re-record.** `ScreensLogicTest.searchTypeFilterSemantics` must stay green — Step 2 keeps its mirrored `when (typeFilter)` branch verbatim.

- [ ] **Step 5: THE ON-DEVICE GATE**

**Build the APK LOCALLY.** CI has no Tidal credentials — a CI APK cannot log in and search returns nothing on it.

1. Install; log in on the internal-v1 path (**Account → Log in to Tidal**).
2. Search **`cake`** (the probed query).
3. **Gate criteria — ALL must hold:**
   - An **"Artists"** rail shows with photos (CAKE's picture loads — a 480x480 URL is only correct if the CDN renders it; 640 would 404). Tapping an artist opens the **rebuilt artist page** (bio, Fans Also Like, categorised discography), NOT a blank screen.
   - An **"Albums"** rail shows with covers and the artist name under each. Tapping an album opens `AlbumDetailScreen` with a playable remote tracklist.
   - A **"Tracks"** list shows below, with NO blank artist names (Finding 1 — a blank name means the projection read a singular `artist` that is not there).
   - The source chips still filter tracks: **"Local"** hides both rails and shows only local tracks; **"Tidal"** keeps the rails.
4. **Logged-out check:** log out of Tidal, search again → only local tracks (or the "No results" empty state), **no crash, no empty rails**.

**If any criterion fails, STOP.** A blank artist rail with a logged-in session is the first suspect for a wrong `types` value or a not-logged-in `validToken`; a blank track artist is the Finding-1 trap.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/SearchScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt
git commit -m "feat(android): search shows tappable artists + albums, not just tracks

Search now renders an Artists rail and an Albums rail above the tracks list,
each result navigating straight to its detail page (artist -> rebuilt
pages/artist page via tidalArtistId; album -> AlbumDetailScreen via
tidalAlbumId). Source chips filter only the tracks section; artists/albums are
Tidal-only and hidden under 'Local'. Reuses ArtistRow/AlbumCard/AuxenTrackRow/
SectionHeader and the existing artistRoute/albumRoute — no golden moves, no new
routes.

This makes the just-rebuilt artist page reachable: before, nothing let the user
FIND an artist.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task summary

| Task | What | Tested by |
|---|---|---|
| 1 | Pure parse `searchResultsFrom` + `searchAllUrl`; reuse-widening (`decodeEach` internal) + additive `TidalV1Album.artists` | `TidalMultiTypeSearchTest` (RED→GREEN); `TidalInternalArtistTest`/`TidalArtistPageTest` stay green |
| 2 | `TidalProvider.searchAll` (additive) + `PlayerViewModel` album/artist state | build + full Tidal suite (no unit test on live HTTP/state) |
| 3 | `SearchScreen` rails + `MainActivity` nav wiring | `ComponentScreenshotTest`/`ScreensLogicTest` green + on-device gate |

Three tasks — under the ~5 ceiling. Tasks 1–2 are surface-agnostic; only Task 3 depends on Open question 1.
