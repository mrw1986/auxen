# Retire official Tidal v2 entirely — migrate album detail + credits + lyrics to internal-v1, then delete the stack

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retire the official `openapi.tidal.com/v2` API **entirely** (user decision, 2026-07-17). Official v2 is PREVIEW-only at our access tier — it cannot stream — and **every** feature it serves has an internal-v1 equivalent the desktop already uses. The artist page was already rebuilt on internal-v1 `pages/artist`. This plan moves the **last three** official-v2 features — **album detail**, **track credits**, **track lyrics** — onto internal-v1, relocates the shared symbols that internal-v1 code still needs out of the doomed files, then **deletes the whole official-v2 stack**, which also removes the experimental Tidal login from Settings (the user asked for that gone).

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), OkHttp (raw), Jetpack Compose + Navigation-Compose, JUnit4 + Robolectric + Roborazzi. Manual `Graph` service-locator DI (`AuxenApp.kt:137`). minSdk 26 / compileSdk 35. System JDK 25 breaks Gradle — every gradle line below carries `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`.

---

## Ground truth — read before writing a line

`.superpowers/sdd/official-v2-retirement-schema.md`, probed live 2026-07-17 against the desktop's own session. **It is ground truth. Do not guess at shapes.** The three internal-v1 replacements:

- **Credits:** `GET api.tidal.com/v1/tracks/{id}/credits` (params: `countryCode`; optional `includeContributors`) → a JSON **LIST** `[{ "type": "<role>", "contributors": [{ "name": str, "id": int? }] }]`. Role-grouped. Contributor `id` may be absent. Desktop: `auxen/providers/tidal.py:1702-1704`. Empty/404 → "no credits", degrade gracefully.
- **Lyrics:** `GET api.tidal.com/v1/tracks/{id}/lyrics` (params: `countryCode`) → `{ "lyrics": str, "subtitles": str (synced/LRC), "lyricsProvider", "isRightToLeft", "trackId", ... }`. `lyrics` is plain `\n`-separated text; `subtitles` is time-synced. **404 is EXPECTED** for tracks without lyrics (instrumentals) → "no lyrics", **NOT an error**. Desktop: `tidal.py:1731`; tidalapi `media.py:447`.
- **Album detail:** `GET albums/{id}` (metadata) + `GET albums/{id}/items?limit&offset` (tracklist). **Items carry `trackNumber` AND `volumeNumber` NATIVELY** — strictly better than the official-v2 path, which reconstructed volume numbers from JSON:API linkage `meta`. Cover 640 via the existing `tidalV1CoverUrl`.

One shape this plan needs is **not** in that schema and is called out honestly below (§ *Open questions*): the seed-track → artist/album id resolution, today served by official `trackRelations`, moves to internal-v1 `GET tracks/{id}` → `{artists[0].id, album.id}`. It is grounded on `tidalapi/media.py` and on DTOs this repo already ships, and the on-device gate (Task 4) exercises it before any deletion.

---

## ⚠️ THE SHARED-SYMBOL TRAP — the survivor sweep (done for real, 2026-07-17)

Some symbols live in the doomed official-v2 files but are used by **surviving internal-v1 code**. Deleting a file without first relocating its still-used symbols **breaks the build**. Every top-level declaration in each of the 10 doomed files was grepped across `android/app/src` (main **and** test) with word boundaries. A "survivor caller" is any reference **outside the deletion set** (the 10 files + `Pkce.kt`/`TidalStreamingProbe.kt`/`TidalPlaybackProbe.kt`/`TidalOfficialDebugScreen.kt` + the official halves of `PlayerViewModel`/`MainActivity`/`SettingsScreen`/`AuxenApp` + all official-v2 tests).

Grep run:
```
cd android/app/src && for sym in <every symbol below>; do grep -rln "\b$sym\b" --include=*.kt .; done
```

| Doomed file | Symbol | Survivor callers found | Fate |
|---|---|---|---|
| **TidalMetadataDetail.kt** | `ArtistLink` | `TidalArtistPage.kt`, `TidalMultiTypeSearch.kt`, `ArtistDetailScreen.kt`, `PlayerViewModel.kt` | **RELOCATE → `TidalInternalModels.kt`** |
| | `AlbumSummary` | `TidalArtistPage.kt`, `TidalInternalArtist.kt`, `TidalMultiTypeSearch.kt`, `TidalProvider.kt` (+`TidalInternalArtistTest`, `TidalMultiTypeSearchTest`) | **RELOCATE → `TidalInternalModels.kt`** |
| | `TidalAlbumDetail` | `AlbumDetailScreen.kt`, `PlayerViewModel.kt` | **RELOCATE → `TidalInternalAlbum.kt`** |
| | `TrackCredit` | `TrackInfoSheet.kt` (+`TrackInfoSheetUiTest`) | **RELOCATE → `TidalInternalTrackDetail.kt`** |
| | `TidalTrackDetail` | `TrackInfoSheet.kt`, `PlayerViewModel.kt` (+`TrackInfoSheetUiTest`) | **RELOCATE → `TidalInternalTrackDetail.kt`** |
| | `normalizeTitleForDedup` | `TidalArtistDetailTest` (+ internal use by dedup/merge) | **RELOCATE → `TidalInternalModels.kt`** |
| | `dedupRemoteAlbums` | `ArtistDetailScreen.kt` (+`TidalInternalArtist.kt` KDoc, +`TidalArtistDetailTest`) | **RELOCATE → `TidalInternalModels.kt`** |
| | `mergeArtistTracks` | `ArtistDetailScreen.kt` (+`TidalArtistDetailTest`) | **RELOCATE → `TidalInternalModels.kt`** |
| | `TidalArtistDetail`, `AlbumItemMeta`, `CreditsAttributes`, `LyricsAttributes`, `artworkUrlFor`, `trackFrom`, `albumSummaryFrom`, `artistDetailFrom`, `albumDetailFrom`, `trackDetailFrom` | none outside the deletion set (only doomed files, doomed tests, and prose KDoc mentions) | **DIE with the file** |
| **TidalMetadataPrimitives.kt** | `releaseYearOf` | `TidalInternalArtist.kt` (`toAlbumSummary`) | **RELOCATE → `TidalInternalModels.kt`** |
| | `ArtworkFileMeta`, `ArtworkFile`, `ArtworkAttributes`, `pickArtworkUrl`, `parseIsoDurationSeconds` | none (only `TidalMetadataDetail.kt` + `TidalMetadataPrimitivesTest`, all doomed) | **DIE with the file** |
| **TidalMetadataResolution.kt** | `TrackRelations` | (new internal-v1 `trackRelationsFromV1` + `TidalProvider.trackRelations`, added in Task 1) | **RELOCATE → `TidalInternalModels.kt`** |
| | `trackRelationsFrom` | none outside the deletion set (`TidalMetadataRepository` + `TidalMetadataResolutionTest`) | **DIE with the file** |
| **TidalJsonApi.kt** | `TidalResourceId`, `TidalRelationship`, `TidalRelated`, `TidalResource`, `TidalDocument`, `decodeAttributes`, `decodeMeta`, `TidalDocumentView` | none (only doomed files + `TidalJsonApiTest`) | **DIE with the file** |
| **TidalMetadataRepository.kt** | `ArtistAttributes`, `ArtistBiographyAttributes`, `AlbumAttributes`, `ArtistOverview`, `artistOverviewFrom`, `userIdFrom`, `TidalMetadataRepository` | only `AuxenApp` (graph wiring) + `PlayerViewModel` probes — **all in the deletion set** | **DIE with the file** |
| **TidalOfficialApi.kt** | `TrackAttributes`, `TrackResource`, `TrackDocument`, `TrackManifest*`, `TrackFile*`, `isFullPresentation`, `JsonApiError*` | none outside the deletion set | **DIE with the file** |
| **TidalOfficialAuth / Client / Endpoints / Session.kt** | every top-level (`TidalOfficialAuth`, `TidalOfficialClient`, `TidalOfficialEndpoints`, `TidalOfficialSession`, `validateRedirect`, `officialTokenNeedsRefresh`, `tokenAfterFailedRefresh`) | only `AuxenApp`, `TidalStreamingProbe`, `Pkce`, official tests — **all in the deletion set** | **DIE with the file** |

**Ten survivor symbols** must be relocated before the deletion: `ArtistLink`, `AlbumSummary`, `TidalAlbumDetail`, `TrackCredit`, `TidalTrackDetail`, `normalizeTitleForDedup`, `dedupRemoteAlbums`, `mergeArtistTracks`, `releaseYearOf`, `TrackRelations`.

**The load-bearing fact that makes relocation cheap:** every one of these lives in package `io.github.auxen.provider.tidal`, and so do all their surviving-provider callers; the UI callers import them by their **fully-qualified package name** (`io.github.auxen.provider.tidal.AlbumSummary`, …). **Moving a top-level declaration to another file in the same package changes no import in any caller.** Relocation is a pure cut-and-paste of whole declarations; **no signature changes, no import edits**. The brief's verified example (`releaseYearOf`, used by `TidalInternalArtist.kt`) is one row of the table above; the sweep found nine more.

Two **non-survivors** the brief flagged to confirm — confirmed dead by the sweep and dying with their files: `parseIsoDurationSeconds` and `pickArtworkUrl` have **no** caller outside `TidalMetadataDetail.kt` (internal-v1 durations are integer seconds; internal-v1 covers use `tidalV1CoverUrl`). And `formatTidalBio` is **not** a relocation concern — it is DEFINED in the surviving `TidalInternalArtist.kt` and used by the surviving `artistPageFrom`; it survives naturally.

---

## Decisions this plan makes (so a future reader knows they were made, not missed)

**1. Album domain type — KEEP `TidalAlbumDetail`, relocated, reshaped only by omission.** `AlbumDetailScreen.kt` and `PlayerViewModel.kt` already consume `TidalAlbumDetail` (`{id, title, artistName, artistId, albumType, releaseYear, explicit, artworkUrl, tracks, similarAlbums}`). Because relocation is same-package (zero import churn) and the type's shape is unchanged, **UI churn is nil**. The internal-v1 parse fills every field from `albums/{id}` + `/items` and passes `similarAlbums = emptyList()` — internal `albums/{id}` carries no similar-albums list, and **`AlbumDetailScreen` never rendered `similarAlbums` anyway** (grep: the screen reads `title/artistName/artworkUrl/albumType/releaseYear/artistId/tracks`, never `similarAlbums`). The field stays as a harmless vestige rather than a signature change that would ripple through the two consumers.

**2. Track-info domain type — KEEP `TidalTrackDetail` + `TrackCredit`, relocated.** Same reasoning. `TrackInfoSheet` renders `title/credits/lyricsText/lyricsSynced` and never touches `similarTracks`; the internal-v1 assembly passes `similarTracks = emptyList()`. Keeping the exact shape means `TrackInfoSheetUiTest` (which constructs `TidalTrackDetail(..., similarTracks = emptyList())` by hand, `TrackInfoSheetUiTest.kt:61-68`) needs **no change**.

**3. Synced lyrics — NO new UX decision needed.** `TrackInfoSheet` already handles `subtitles`: it renders plain `lyricsText`, and when only synced lyrics exist shows *"Synced lyrics only."* (`TrackInfoSheet.kt:96-103`). This plan maps `lyrics → lyricsText` and `subtitles → lyricsSynced` and the **existing** sheet logic is preserved verbatim. Auxen has no synced-lyrics renderer and this plan does not add one.

**4. Album richer page (`pages/album` review / "more by artist" / similar) — DEFERRED, not built.** `AlbumDetailScreen` renders a header + tracklist and nothing else; `albums/{id}` + `/items` fills exactly that. The `pages/album` modules the schema mentions have no surface on this screen. Not projected. (See § *Open questions*.)

**Where the three features are consumed today:**
- **Album detail:** `AlbumDetailScreen.kt` reads `viewModel.albumDetail` (a `MutableStateFlow<TidalAlbumDetail?>`), filled by `PlayerViewModel.loadAlbumDetail` (`PlayerViewModel.kt:605-639`), which calls `Graph.tidalMetadata.trackRelations(seed).albumId` then `Graph.tidalMetadata.albumDetail(albumId)`.
- **Credits + lyrics:** `AlbumDetailScreen.kt:238-247` opens `TrackInfoSheet` bound to `viewModel.trackDetail` (`MutableStateFlow<TidalTrackDetail?>`), filled by `PlayerViewModel.loadTrackDetail` (`PlayerViewModel.kt:670-685`), which calls `Graph.tidalMetadata.trackDetail(track.sourceId)`.
- **Also officially wired today:** `PlayerViewModel.loadArtistDetail` step 2 resolves the artist id via `Graph.tidalMetadata.trackRelations(seed).artistId` (`PlayerViewModel.kt:586`). Retiring official v2 **entirely** means this too moves to internal-v1 — handled in Task 1, since album detail needs the same `tracks/{id}` read.

---

## The exact deletion set (Task 5)

**Whole files deleted (13 main + 13 test):**
- Main: `TidalJsonApi.kt`, `TidalMetadataDetail.kt`, `TidalMetadataPrimitives.kt`, `TidalMetadataRepository.kt`, `TidalMetadataResolution.kt`, `TidalOfficialApi.kt`, `TidalOfficialAuth.kt`, `TidalOfficialClient.kt`, `TidalOfficialEndpoints.kt`, `TidalOfficialSession.kt`, **plus** `Pkce.kt`, `TidalStreamingProbe.kt`, `TidalPlaybackProbe.kt` (PKCE + the official streaming/playback probes — used only by official login/streaming, confirmed by grep), and the UI file `ui/TidalOfficialDebugScreen.kt`.
- Test: `TidalJsonApiTest.kt`, `TidalMetadataPrimitivesTest.kt`, `TidalMetadataRepositoryTest.kt`, `TidalMetadataResolutionTest.kt`, `TidalOfficialApiTest.kt`, `TidalOfficialAuthTest.kt`, `TidalOfficialEndpointsTest.kt`, `TidalOfficialSessionTest.kt`, `PkceTest.kt`, `TidalStreamingProbeTest.kt`, `TidalAlbumDetailTest.kt` (official `albumDetailFrom`), `TidalTrackDetailTest.kt` (official `trackDetailFrom`), `TidalArtistDetailTest.kt` (official `artistDetailFrom`; its **survivor** dedup/merge cases are moved to `TidalInternalModelsTest.kt` in Task 3 first).

**In-file edits (Task 5):** `AuxenApp.kt` (3 Graph members + init + imports), `PlayerViewModel.kt` (the whole official/probe surface), `MainActivity.kt` (nav route + `onNewIntent`/redirect + `OVERLAY_ROUTES`/`topBarTitle`), `SettingsScreen.kt` (the Advanced card + `onOpenTidalOfficialDebug` param), `AndroidManifest.xml` (the `auth-callback` intent-filter), `app/build.gradle.kts` (`TIDAL_OFFICIAL_CLIENT_ID` buildConfigField + its property line), `res/values/strings.xml` (14 dead strings), and re-record the two `settings-screen-{light,dark}.png` goldens (the deliberate UI removal).

**`TidalMetadataRepository`'s "frozen constructor" constraint DIES here** — the class is deleted, so the entire reason internal-v1 composition had to happen in the ViewModel evaporates. This plan does not exploit that (the internal-v1 reads stay on `TidalProvider`, where they already are), but say so: the constraint is gone.

---

## Global Constraints

- **Additive, read-only internal-v1 only.** New `TidalProvider` methods (`trackRelations`, `albumDetail`, `trackDetail`) are catalog GETs: they resolve no stream and touch no session state. **`TidalAuth.kt` gets no change.** `restoreSession`/`logout`/`validToken`/`get`/`search`/`getStreamInfo`/`getStreamInfoById`/`toTrack` and the existing private DTOs are **not edited**; no existing method changes behaviour or signature. Confirm with `git status` at Task 4.
- **Playback is unchanged.** Album tracks are ordinary `Track(source = Source.TIDAL, sourceId = <catalog id>)` values that play through the *existing* pipeline (`Graph.mediaItemFor` → `auxen://tidal/<id>` → `TrackResolver` → `TidalProvider.getStreamInfoById`). **Videos are filtered out of album tracklists** (a video id on the track stream endpoint fails — the same rule the artist page and the old album path already applied).
- **Never re-declare an existing type.** Kotlin "conflicting declarations" is a compile error. The new internal-v1 parses are named **distinctly** from their official counterparts precisely so both coexist while the official ones await deletion: `albumDetailFromV1` (not `albumDetailFrom`), `trackRelationsFromV1` (not `trackRelationsFrom`), `trackCreditsFrom`/`trackLyricsFrom` (new). Reuse — never redeclare — `AlbumSummary`, `ArtistLink`, `TidalAlbumDetail`, `TrackCredit`, `TidalTrackDetail`, `TrackRelations`, `TidalV1Album`, `TidalV1AlbumArtist`, `TidalV1PageTrack`, `TidalV1PageArtist`, `TidalV1PageTrackAlbum`, `tidalV1CoverUrl`, `releaseYearOf`, `decodeEach`, `TidalV1PageTrack.toTrack`.
- **Conversely: ADD THE IMPORT.** Kotlin resolves per file. New code in an existing file naming an unimported type is a compile error. Every step lists its imports. (Relocations within the package need none — see the sweep note.)
- **Do not unit-test live HTTP.** Unit-test PURE logic only: request shape (`*Url`) and body→domain projection (`*From*`). The suspend/network wrappers on `TidalProvider`, the `PlayerViewModel` composition and the Compose screens are NOT unit-tested and close on the Task 4 on-device checkpoint — established precedent.
- **Every new parse is TOTAL and born wrapped.** `albumDetailFromV1`, `trackCreditsFrom`, `trackLyricsFrom`, `trackRelationsFromV1` each wrap their whole body in `runCatching { … }.getOrNull()` (or `.orEmpty()`), so any malformed field degrades to null/empty and the screen keeps its local base — never a crash. This IS the hardening discipline the brief asks for, applied at birth to the new code; the existing `artistPageFrom` gets the same wrap retrofitted in Task 3.
- **Gradle lines MUST carry `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`** (system JDK 25 breaks Gradle).
- **Git: explicit pathspecs only.** Never `git add -A` / `git add .` — another agent may be committing Kotlin concurrently. **Never commit** `.playwright-mcp/`, `android/.kotlin/`, `docs/plans/2026-07-13-*.md`.
- **Never log or print `accessToken`/`refreshToken`.** Internal-v1 auth + streaming untouched; additive reads only.
- **Sequencing is a correctness property.** Migrations (Tasks 1–2) and the relocate (Task 3) all land, build-green, **before** the deletion (Task 5); the on-device gate (Task 4) sits between them and deletion. **The build is green at every commit. Deletion is the LAST task.**

---

### Task 1: Album detail → internal-v1 (and the seed-track id resolution both screens share)

The album screen consumes `TidalAlbumDetail` from official v2. This task produces the identical type from `albums/{id}` + `albums/{id}/items`, and — because retiring official v2 is total — replaces the official `trackRelations` that both the album and artist id chains lean on with an internal-v1 `tracks/{id}` read.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalAlbum.kt`
- Create: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalAlbumTest.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt` (add `trackRelations` + `albumDetail`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (`loadAlbumDetail` body + `loadArtistDetail` step 2)

**Interfaces:**
- Consumes: `TidalAlbumDetail`, `TrackRelations` (from `TidalMetadataDetail.kt`/`TidalMetadataResolution.kt` — still present, same package, relocated in Task 3); `TidalV1AlbumArtist`, `tidalV1CoverUrl`, `releaseYearOf` (survivors); `TidalV1PageTrack`, `TidalV1PageArtist`, `TidalV1PageTrackAlbum`, `TidalV1PageTrack.toTrack` (`TidalArtistPage.kt`); `io.github.auxen.model.Track`.
- Produces: `albumMetadataUrl`, `albumItemsUrl`, `trackRelationsUrl` (`HttpUrl`); `albumDetailFromV1(metaBody, itemBodies, json): TidalAlbumDetail?`; `trackRelationsFromV1(body, json): TrackRelations?`; DTOs `TidalV1AlbumMeta`, `TidalV1AlbumItem`, `TidalV1AlbumItemsResponse`; `TidalProvider.trackRelations(trackId)`, `TidalProvider.albumDetail(albumId)`.
- **Do NOT re-declare:** `TidalAlbumDetail`, `TrackRelations`, `TidalV1AlbumArtist`, `TidalV1PageTrack`, `tidalV1CoverUrl`, `releaseYearOf`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalAlbumTest.kt`. Fixtures are trimmed from the probed schema — note the multi-volume tracklist (`volumeNumber` 1 then 2) and the interleaved **video** item, both there to prove the native numbering and the video filter:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidalInternalAlbumTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- request shapes ----

    @Test
    fun `albumMetadataUrl sends only countryCode`() {
        val url = albumMetadataUrl("https://api.tidal.com/v1", "166790187", "US")
        assertEquals("/v1/albums/166790187", url.encodedPath)
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals(setOf("countryCode"), url.queryParameterNames)
    }

    @Test
    fun `albumItemsUrl carries limit, offset and countryCode`() {
        val url = albumItemsUrl("https://api.tidal.com/v1", "166790187", "US", limit = 100, offset = 100)
        assertEquals("/v1/albums/166790187/items", url.encodedPath)
        assertEquals("100", url.queryParameter("limit"))
        assertEquals("100", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
    }

    @Test
    fun `trackRelationsUrl is the bare track read plus countryCode`() {
        val url = trackRelationsUrl("https://api.tidal.com/v1", "186460926", "US")
        assertEquals("/v1/tracks/186460926", url.encodedPath)
        assertEquals(setOf("countryCode"), url.queryParameterNames)
    }

    // ---- trackRelationsFromV1: artist + album id off ONE track read ----

    @Test
    fun `trackRelationsFromV1 reads artistId from artists plural and albumId from album`() {
        val body = """
            { "id": 186460926, "title": "Enter Sandman",
              "artists": [ { "id": 8405, "name": "Metallica" } ],
              "album": { "id": 166790187, "title": "Metallica", "cover": null } }
        """.trimIndent()
        val rel = trackRelationsFromV1(body, json)!!
        assertEquals("8405", rel.artistId)
        assertEquals("166790187", rel.albumId)
        assertNull(rel.isrc) // isrc unused while direct id-equivalence holds
    }

    @Test
    fun `trackRelationsFromV1 returns null for an error envelope or non-JSON`() {
        assertNull(trackRelationsFromV1("""{ "status": 404, "userMessage": "not found" }""", json))
        assertNull(trackRelationsFromV1("not json", json))
    }

    // ---- albumDetailFromV1: the whole projection ----

    private val metaBody = """
        { "id": 166790187, "title": "Metallica",
          "cover": "d0747f21-26ff-447c-8d04-0d8f3635e205",
          "releaseDate": "1991-08-12", "explicit": false, "numberOfTracks": 3,
          "type": "ALBUM",
          "artist": { "id": 8405, "name": "Metallica" },
          "artists": [ { "id": 8405, "name": "Metallica" } ] }
    """.trimIndent()

    // A two-volume tracklist with a VIDEO interleaved. The video must be dropped;
    // the native volumeNumber must survive so disc 1 t1 and disc 2 t1 are distinct.
    private val itemsBody = """
        { "totalNumberOfItems": 3, "items": [
          { "type": "track", "item": {
              "id": 166790188, "title": "Enter Sandman", "duration": 332, "explicit": false,
              "audioQuality": "LOSSLESS", "trackNumber": 1, "volumeNumber": 1,
              "artists": [ { "id": 8405, "name": "Metallica" } ] } },
          { "type": "video", "item": {
              "id": 999999, "title": "A behind-the-scenes video", "duration": 120,
              "trackNumber": 2, "volumeNumber": 1,
              "artists": [ { "id": 8405, "name": "Metallica" } ] } },
          { "type": "track", "item": {
              "id": 166790200, "title": "Nothing Else Matters", "duration": 388, "explicit": false,
              "audioQuality": "HI_RES_LOSSLESS", "trackNumber": 1, "volumeNumber": 2,
              "artists": [ { "id": 8405, "name": "Metallica" } ] } }
        ] }
    """.trimIndent()

    private fun detail() = albumDetailFromV1(metaBody, listOf(itemsBody), json)!!

    @Test
    fun `albumDetailFromV1 maps album metadata`() {
        val d = detail()
        assertEquals("166790187", d.id)
        assertEquals("Metallica", d.title)
        assertEquals("Metallica", d.artistName)
        assertEquals("8405", d.artistId)
        assertEquals("ALBUM", d.albumType)
        assertEquals(1991, d.releaseYear)
        assertEquals(false, d.explicit)
        assertEquals(
            "https://resources.tidal.com/images/d0747f21/26ff/447c/8d04/0d8f3635e205/640x640.jpg",
            d.artworkUrl,
        )
        assertEquals(emptyList<AlbumSummary>(), d.similarAlbums)
    }

    @Test
    fun `albumDetailFromV1 drops video items and keeps native track and volume numbers`() {
        val tracks = detail().tracks
        assertEquals(listOf("Enter Sandman", "Nothing Else Matters"), tracks.map { it.title })
        assertEquals(listOf("166790188", "166790200"), tracks.map { it.sourceId })
        // Native numbering: BOTH are trackNumber 1 but on different volumes.
        assertEquals(listOf(1, 1), tracks.map { it.trackNumber })
        assertEquals(listOf(1, 2), tracks.map { it.discNumber })
    }

    @Test
    fun `albumDetailFromV1 reads artist from artists plural, quality, duration and stamps the album cover`() {
        val (sandman, matters) = detail().tracks
        assertEquals("Metallica", sandman.artist)
        assertEquals("Metallica", sandman.album) // stamped from the album title
        assertEquals(332.0, sandman.durationSeconds!!, 0.001)
        assertEquals("FLAC", sandman.format)
        assertEquals("Hi-Res", matters.format)
        // Each row carries the album's 640 cover even when the item has no inline album.
        assertEquals(
            "https://resources.tidal.com/images/d0747f21/26ff/447c/8d04/0d8f3635e205/640x640.jpg",
            sandman.albumArtUrl,
        )
        assertEquals(true, sandman.source == io.github.auxen.model.Source.TIDAL)
    }

    @Test
    fun `albumDetailFromV1 concatenates every items page in order`() {
        val page2 = """
            { "totalNumberOfItems": 3, "items": [
              { "type": "track", "item": { "id": 3, "title": "The God That Failed",
                  "duration": 308, "trackNumber": 2, "volumeNumber": 2,
                  "artists": [ { "id": 8405, "name": "Metallica" } ] } }
            ] }
        """.trimIndent()
        val d = albumDetailFromV1(metaBody, listOf(itemsBody, page2), json)!!
        assertEquals(listOf("Enter Sandman", "Nothing Else Matters", "The God That Failed"), d.tracks.map { it.title })
    }

    @Test
    fun `albumDetailFromV1 returns null for a non-album body`() {
        assertNull(albumDetailFromV1("""{ "status": 404 }""", listOf(), json))
        assertNull(albumDetailFromV1("not json", listOf(itemsBody), json))
    }

    @Test
    fun `albumDetailFromV1 tolerates a malformed items page and keeps the album`() {
        // One bad page must not cost the album its metadata or its good pages.
        val d = albumDetailFromV1(metaBody, listOf("not json at all", itemsBody), json)!!
        assertEquals("Metallica", d.title)
        assertEquals(listOf("Enter Sandman", "Nothing Else Matters"), d.tracks.map { it.title })
    }
}
```

- [ ] **Step 2: RED — run the tests and watch them fail**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalInternalAlbumTest*'
```

**Expected: FAIL — the file does not compile.** `albumMetadataUrl`, `albumItemsUrl`, `trackRelationsUrl`, `albumDetailFromV1`, `trackRelationsFromV1` are unresolved: `TidalInternalAlbum.kt` does not exist yet. If it compiles, you are in the wrong tree.

- [ ] **Step 3: Create `TidalInternalAlbum.kt`**

```kotlin
package io.github.auxen.provider.tidal

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
```

- [ ] **Step 4: Add `trackRelations` + `albumDetail` to `TidalProvider.kt`**

After `searchArtistIdExact` (`TidalProvider.kt:266`), and using the private `API_BASE`/`countryCode`/`get`/`validToken` already in the class, add:

```kotlin
    /**
     * Resolve a Tidal track's artist + album ids from ONE internal-v1 read
     * (`GET tracks/{id}`). This is the internal-v1 replacement for the official-v2
     * `trackRelations`, used as the seed step of BOTH the artist-id chain
     * ([io.github.auxen.ui.PlayerViewModel.loadArtistDetail]) and the album-id
     * chain ([io.github.auxen.ui.PlayerViewModel.loadAlbumDetail]). Metadata only:
     * resolves no stream, touches no session state.
     *
     * Returns null when not logged in or on any HTTP/parse failure — the caller
     * then falls through to its next id source or renders un-enriched, exactly as
     * with the official read it replaces.
     */
    suspend fun trackRelations(trackId: String): TrackRelations? {
        val token = validToken() ?: return null
        val url = trackRelationsUrl(API_BASE, trackId, countryCode)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return null
        return trackRelationsFromV1(body, json)
    }

    /**
     * Full album detail — `GET albums/{id}` + a paged sweep of
     * `GET albums/{id}/items` — projected to [TidalAlbumDetail]. A read-only
     * catalog GET: resolves no stream, touches no session state. The tracklist is
     * the COMPLETE album (paged), with native `trackNumber`/`volumeNumber`; videos
     * are filtered out.
     *
     * Returns null when not logged in, when the metadata read fails, or when the
     * body is not an album ([albumDetailFromV1]'s contract) — the album screen
     * then falls back to its local group, exactly as it does today for a
     * non-Tidal album.
     */
    suspend fun albumDetail(albumId: String): TidalAlbumDetail? {
        val token = validToken() ?: return null
        val metaBody = runCatching { get(albumMetadataUrl(API_BASE, albumId, countryCode).toString(), token) }
            .getOrNull() ?: return null
        val itemBodies = mutableListOf<String>()
        var offset = 0
        while (offset < ALBUM_ITEMS_MAX) {
            val body = runCatching { get(albumItemsUrl(API_BASE, albumId, countryCode, ALBUM_ITEMS_PAGE, offset).toString(), token) }
                .getOrNull() ?: break
            itemBodies += body
            val total = runCatching { json.decodeFromString<TidalV1AlbumItemsResponse>(body) }.getOrNull()
            offset += ALBUM_ITEMS_PAGE
            if (total == null || total.items.isEmpty() || offset >= total.totalNumberOfItems) break
        }
        return albumDetailFromV1(metaBody, itemBodies, json)
    }
```

And add the two page constants to the existing private companion (`TidalProvider.kt:380-382`):

```kotlin
    private companion object {
        const val API_BASE = "https://api.tidal.com/v1"
        // albums/{id}/items paging: 100 covers all but box sets in one call; the
        // MAX cap bounds a pathological loop (a huge box set truncates gracefully
        // rather than looping unboundedly — no album on Tidal reaches 1000 tracks).
        const val ALBUM_ITEMS_PAGE = 100
        const val ALBUM_ITEMS_MAX = 1000
    }
```

- [ ] **Step 5: Rewire `PlayerViewModel.loadAlbumDetail` and `loadArtistDetail` step 2 onto internal-v1**

In `loadAlbumDetail` (`PlayerViewModel.kt:626-638`), replace the launched body so the album id and the detail both come from `Graph.tidal` (keep the whole `key`/`cancel`/early-return machinery above it, and the `finally`, exactly as they are):

```kotlin
        albumDetailJob = viewModelScope.launch {
            albumDetailLoading.value = true
            try {
                val albumId = tidalAlbumId
                    ?: Graph.tidal.trackRelations(seedTidalTrackId!!)?.albumId
                    ?: return@launch
                // ONE internal-v1 read (paged internally); it returns null on any
                // failure, so there is nothing to catch — the try/finally exists
                // solely to clear the spinner on the early return above.
                val detail = Graph.tidal.albumDetail(albumId)
                if (albumDetailKey == key && detail != null) albumDetail.value = detail
            } finally {
                if (albumDetailKey == key) albumDetailLoading.value = false
            }
        }
```

In `loadArtistDetail`'s id chain (`PlayerViewModel.kt:585-588`), replace the official `trackRelations` step-2 line with the internal-v1 one — **this line only**, the rest of the chain is unchanged:

```kotlin
                val artistId = tidalArtistId
                    ?: seedTidalTrackId?.let { Graph.tidal.trackRelations(it)?.artistId }
                    ?: Graph.tidal.searchArtistIdExact(artistName)
                    ?: return@launch
```

**Do not touch** `loadTrackDetail` here (Task 2), and **do not touch** the two probe callers of `Graph.tidalMetadata.trackRelations` (`runTidalIdEquivalenceProbe`); those stay until Task 5 deletes them wholesale. After this step, `Graph.tidalMetadata` has exactly two remaining callers, both probes — Task 4's `git grep` confirms it.

- [ ] **Step 6: Build + the full Tidal suite**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*Tidal*' assembleDebug
```

**Expected: PASS; APK builds.** The new unit tests pass; nothing regresses. The album screen now enriches from internal-v1; the official `albumDetail`/`trackRelations` still compile (they are deleted in Task 5).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalAlbum.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalAlbumTest.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt
git commit -m "feat(android): album detail + seed-track id resolution on internal-v1

albums/{id} + albums/{id}/items replaces the official-v2 compound-document
album path. Internal-v1 is strictly better: items carry trackNumber AND
volumeNumber natively, so multi-volume albums number per disc without the
official path's linkage-meta reconstruction. Videos are filtered out.

One internal-v1 tracks/{id} read (trackRelations) replaces the official
trackRelations for both the album-id and artist-id seed chains, so official
v2 no longer resolves any id.

Additive, read-only internal-v1; auth and streaming untouched. Official
albumDetail/trackRelations still compile and are deleted in the retirement
commit.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Track credits + lyrics → internal-v1 (the info sheet)

Credits and lyrics feed the **same** `TrackInfoSheet` through the **same** `TidalTrackDetail` and the **same** `loadTrackDetail`, so they migrate together. The official `trackDetail` compound-document fetch is replaced by two internal-v1 reads assembled with the track already in hand.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalTrackDetail.kt`
- Create: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalTrackDetailTest.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt` (add `trackDetail`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (`loadTrackDetail` body + KDoc)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/TrackInfoSheet.kt` (KDoc only)

**Interfaces:**
- Consumes: `TrackCredit`, `TidalTrackDetail` (from `TidalMetadataDetail.kt`, relocated in Task 3); `io.github.auxen.model.Track`.
- Produces: `trackCreditsUrl`, `trackLyricsUrl` (`HttpUrl`); `trackCreditsFrom(body, json): List<TrackCredit>`; `trackLyricsFrom(body, json): TidalV1Lyrics?`; DTOs `TidalV1Contributor`, `TidalV1CreditGroup`, `TidalV1Lyrics`; `TidalProvider.trackDetail(track): TidalTrackDetail?`.
- **Do NOT re-declare:** `TrackCredit`, `TidalTrackDetail`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalTrackDetailTest.kt`. The credits fixture is a JSON **array** of role groups (verbatim shape from the schema), deliberately including a blank role and an empty contributor list to prove both are dropped:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalInternalTrackDetailTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- request shapes ----

    @Test
    fun `trackCreditsUrl asks for contributors and passes countryCode`() {
        val url = trackCreditsUrl("https://api.tidal.com/v1", "186460926", "US")
        assertEquals("/v1/tracks/186460926/credits", url.encodedPath)
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals("true", url.queryParameter("includeContributors"))
    }

    @Test
    fun `trackLyricsUrl passes only countryCode`() {
        val url = trackLyricsUrl("https://api.tidal.com/v1", "186460926", "US")
        assertEquals("/v1/tracks/186460926/lyrics", url.encodedPath)
        assertEquals(setOf("countryCode"), url.queryParameterNames)
    }

    // ---- credits: role-grouped, contributor id optional ----

    @Test
    fun `trackCreditsFrom groups contributors by role, dropping blank roles and empty groups`() {
        val body = """
            [ { "type": "Producer", "contributors": [ { "name": "Bob Rock", "id": 123 }, { "name": "James Hetfield" } ] },
              { "type": "Composer", "contributors": [ { "name": "James Hetfield" } ] },
              { "type": "", "contributors": [ { "name": "Nobody" } ] },
              { "type": "Mixer", "contributors": [] } ]
        """.trimIndent()
        val credits = trackCreditsFrom(body, json)
        assertEquals(listOf("Producer", "Composer"), credits.map { it.role })
        assertEquals(listOf("Bob Rock", "James Hetfield"), credits[0].names)
        assertEquals(listOf("James Hetfield"), credits[1].names)
    }

    @Test
    fun `trackCreditsFrom returns empty for a 404 body, an object, or non-JSON`() {
        // The provider swallows the non-2xx into a null body; a stray object or a
        // v1 error envelope must also degrade to "no credits", never throw.
        assertTrue(trackCreditsFrom("""{ "status": 404 }""", json).isEmpty())
        assertTrue(trackCreditsFrom("not json", json).isEmpty())
        assertTrue(trackCreditsFrom("[]", json).isEmpty())
    }

    // ---- lyrics: plain + optional synced; 404 handled upstream ----

    @Test
    fun `trackLyricsFrom reads plain lyrics and synced subtitles`() {
        val body = """
            { "lyrics": "Say your prayers little one", "subtitles": "[00:12.00]Say your prayers little one",
              "lyricsProvider": "MUSIXMATCH", "isRightToLeft": false, "trackId": 186460926 }
        """.trimIndent()
        val lyrics = trackLyricsFrom(body, json)!!
        assertEquals("Say your prayers little one", lyrics.lyrics)
        assertEquals("[00:12.00]Say your prayers little one", lyrics.subtitles)
    }

    @Test
    fun `trackLyricsFrom returns null when both fields are absent, blank, or the body is bad`() {
        assertNull(trackLyricsFrom("""{ "lyrics": null, "subtitles": null }""", json))
        assertNull(trackLyricsFrom("""{ "lyrics": "   ", "subtitles": "" }""", json))
        assertNull(trackLyricsFrom("not json", json))
    }

    @Test
    fun `trackLyricsFrom keeps synced-only lyrics so the sheet can report them`() {
        val lyrics = trackLyricsFrom("""{ "lyrics": null, "subtitles": "[00:01.00]La" }""", json)!!
        assertNull(lyrics.lyrics)
        assertEquals("[00:01.00]La", lyrics.subtitles)
    }
}
```

- [ ] **Step 2: RED**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalInternalTrackDetailTest*'
```

**Expected: FAIL — the file does not compile** (`trackCreditsUrl`, `trackLyricsUrl`, `trackCreditsFrom`, `trackLyricsFrom` unresolved).

- [ ] **Step 3: Create `TidalInternalTrackDetail.kt`**

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The pure parts of track credits + lyrics on the INTERNAL v1 API —
 * `GET tracks/{id}/credits` + `GET tracks/{id}/lyrics` — replacing the official-v2
 * compound-document `trackDetail`. Metadata only; the sheet they feed resolves no
 * stream. Field names are verbatim from the probed schema
 * (`.superpowers/sdd/official-v2-retirement-schema.md`) and from `tidalapi`'s
 * `media.py` (credits/lyrics).
 */

/** One contributor inside a credits role group. `id` is often absent. */
@Serializable
data class TidalV1Contributor(val name: String = "", val id: Long? = null)

/** One `tracks/{id}/credits` role group: `{ "type": "<role>", "contributors": [...] }`. */
@Serializable
data class TidalV1CreditGroup(val type: String = "", val contributors: List<TidalV1Contributor> = emptyList())

/**
 * `tracks/{id}/lyrics`, the subset the sheet renders: plain [lyrics] +
 * time-synced [subtitles]. `lyricsProvider`/`isRightToLeft`/`trackId`/… are
 * dropped by `ignoreUnknownKeys` — nothing renders them.
 */
@Serializable
data class TidalV1Lyrics(val lyrics: String? = null, val subtitles: String? = null)

/** `GET {apiBase}/tracks/{trackId}/credits?countryCode=…&includeContributors=true`. */
fun trackCreditsUrl(apiBase: String, trackId: String, countryCode: String): HttpUrl =
    "$apiBase/tracks/$trackId/credits".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .addQueryParameter("includeContributors", "true")
        .build()

/** `GET {apiBase}/tracks/{trackId}/lyrics?countryCode=…`. */
fun trackLyricsUrl(apiBase: String, trackId: String, countryCode: String): HttpUrl =
    "$apiBase/tracks/$trackId/lyrics".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * Pure: a `tracks/{id}/credits` body (a JSON ARRAY of role groups) → [TrackCredit]
 * list, one entry per role, names in payload order.
 *
 * Empty for anything that is not that array — a v1 error envelope, an object, a
 * 404 body, non-JSON — so a track with no credits degrades to "no credits",
 * never a throw. A group with a blank role or no usable names is dropped: there
 * is nothing to render for it.
 */
fun trackCreditsFrom(body: String, json: Json): List<TrackCredit> =
    runCatching { json.decodeFromString<List<TidalV1CreditGroup>>(body) }.getOrNull()
        ?.mapNotNull { group ->
            val names = group.contributors.map { it.name }.filter { it.isNotBlank() }
            if (group.type.isBlank() || names.isEmpty()) null else TrackCredit(group.type, names)
        }
        .orEmpty()

/**
 * Pure: a `tracks/{id}/lyrics` body → [TidalV1Lyrics], or **null** when there is
 * nothing to show (both fields absent/blank) or the body is not lyrics.
 *
 * A **404 is EXPECTED** for a track without lyrics (instrumentals) and never
 * reaches here — the provider's `get` throws on the non-2xx and `runCatching`
 * turns that into a null body upstream, so this sees only a real 200 body. Null
 * from here means the sheet shows "No lyrics available." — a clean state, not an error.
 */
fun trackLyricsFrom(body: String, json: Json): TidalV1Lyrics? =
    runCatching { json.decodeFromString<TidalV1Lyrics>(body) }.getOrNull()
        ?.takeIf { !it.lyrics.isNullOrBlank() || !it.subtitles.isNullOrBlank() }
```

- [ ] **Step 4: Add `trackDetail` to `TidalProvider.kt`**

After the new `albumDetail` (Task 1), add — it takes the [Track] because credits/lyrics do not carry the title the sheet header needs, and the caller already holds it:

```kotlin
    /**
     * Credits + lyrics for a Tidal track, assembled into the [TidalTrackDetail]
     * the info sheet renders — two internal-v1 reads (`tracks/{id}/credits` and
     * `tracks/{id}/lyrics`) in place of the official-v2 compound document. Metadata
     * only: resolves no stream.
     *
     * Each read fails independently and gracefully: no credits → empty list, no
     * lyrics (a 404 is EXPECTED for instrumentals) → null text. Returns null ONLY
     * when not logged in — the sheet then shows "No details available." The title
     * comes from [track] because neither endpoint carries it. `similarTracks` is
     * empty: internal-v1 credits/lyrics carry none, and the sheet never rendered them.
     */
    suspend fun trackDetail(track: Track): TidalTrackDetail? {
        val token = validToken() ?: return null
        val credits = runCatching { get(trackCreditsUrl(API_BASE, track.sourceId, countryCode).toString(), token) }
            .getOrNull()?.let { trackCreditsFrom(it, json) }.orEmpty()
        val lyrics = runCatching { get(trackLyricsUrl(API_BASE, track.sourceId, countryCode).toString(), token) }
            .getOrNull()?.let { trackLyricsFrom(it, json) }
        return TidalTrackDetail(
            id = track.sourceId,
            title = track.title,
            credits = credits,
            lyricsText = lyrics?.lyrics,
            lyricsSynced = lyrics?.subtitles,
            similarTracks = emptyList(),
        )
    }
```

`Track` is already imported in `TidalProvider.kt` (`io.github.auxen.model.Track`, line 5).

- [ ] **Step 5: Rewire `PlayerViewModel.loadTrackDetail`**

Replace the launched body of `loadTrackDetail` (`PlayerViewModel.kt:675-684`) — keep the `token`/`cancel`/`finally` machinery, only the fetch changes:

```kotlin
        trackDetailJob = viewModelScope.launch {
            trackDetailLoading.value = true
            try {
                val detail = Graph.tidal.trackDetail(track)
                if (trackDetailToken == token && detail != null) trackDetail.value = detail
            } finally {
                if (trackDetailToken == token) trackDetailLoading.value = false
            }
        }
```

Update the KDoc paragraph that names the official read (`PlayerViewModel.kt:656-662`) — the cancellation semantics now match the artist/album loaders (the internal-v1 reads wrap in `runCatching`, which swallows `CancellationException`, so the **token guard** is the real stale-write mechanism). Replace the paragraph beginning *"If A lands last… Cancelling A helps (unlike the internal-v1 reads, `Graph.tidalMetadata.trackDetail` lets a `CancellationException` propagate…"* with:

```
     * If A lands last, its write would clobber B's — and since the sheet reads its
     * header from [TidalTrackDetail.title], the user would sit on a sheet showing
     * A's title, credits and lyrics with no way to tell it is stale. `cancel()`
     * cannot settle this alone: `Graph.tidal.trackDetail` wraps its reads in
     * `runCatching`, which swallows the `CancellationException` cancellation
     * delivers, so a cancelled load resumes with a value in hand. The token guard
     * is what actually settles both — the same mechanism [loadArtistDetail] documents.
```

- [ ] **Step 6: Update `TrackInfoSheet.kt` KDoc (no behaviour change)**

At `TrackInfoSheet.kt:20-27`, the class KDoc says the sheet is *"Backed by the OFFICIAL metadata API's `trackDetail` projection"*. Replace that sentence with:

```kotlin
 * Credits + lyrics for a Tidal track, opened from [TrackActionSheet]'s
 * "Credits & lyrics" action. Backed by the internal-v1 `tracks/{id}/credits`
 * and `tracks/{id}/lyrics` reads (assembled by `TidalProvider.trackDetail`) —
 * metadata only; nothing here resolves a stream.
```

The rendering body is unchanged — the synced-lyrics branch (`TrackInfoSheet.kt:96-103`) already handles `lyricsSynced`.

- [ ] **Step 7: Build + full Tidal suite + the sheet UI test**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*Tidal*' --tests '*TrackInfoSheet*' assembleDebug
```

**Expected: PASS.** `TrackInfoSheetUiTest` is unchanged and still passes — it constructs `TidalTrackDetail`/`TrackCredit` by hand and never called the official parse.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalTrackDetail.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalTrackDetailTest.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/TrackInfoSheet.kt
git commit -m "feat(android): track credits + lyrics on internal-v1

tracks/{id}/credits + tracks/{id}/lyrics replace the official-v2 compound
trackDetail. Credits are role-grouped from the array shape; lyrics map plain
text + synced subtitles into the existing sheet (a 404 is the expected
no-lyrics case for instrumentals, degraded to a clean state). The sheet and
its UI test are unchanged — same TidalTrackDetail shape, now filled from
internal-v1 with the title carried from the opened track.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Relocate the ten shared survivors + harden `artistPageFrom`

Every doomed-file symbol with a surviving caller (the sweep table) is moved into a surviving same-package file — pure cut-and-paste, no signature or import changes — so the deletion in Task 5 cannot break the build. The existing `artistPageFrom` gets the totality wrap Codex and a Claude reviewer flagged.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalModels.kt`
- Create: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalModelsTest.kt`
- Modify: `.../provider/tidal/TidalMetadataDetail.kt` (CUT the relocated symbols)
- Modify: `.../provider/tidal/TidalMetadataPrimitives.kt` (CUT `releaseYearOf`)
- Modify: `.../provider/tidal/TidalMetadataResolution.kt` (CUT `TrackRelations`)
- Modify: `.../provider/tidal/TidalInternalAlbum.kt` (PASTE `TidalAlbumDetail`)
- Modify: `.../provider/tidal/TidalInternalTrackDetail.kt` (PASTE `TrackCredit`, `TidalTrackDetail`)
- Modify: `.../provider/tidal/TidalArtistPage.kt` (harden `artistPageFrom`)
- Modify: `.../test/.../TidalArtistDetailTest.kt` (CUT the survivor dedup/merge/normalize cases) and `.../TidalMetadataPrimitivesTest.kt` (CUT the `releaseYearOf` cases) — their remaining doomed cases stay until Task 5 deletes both files whole.

**Interfaces:** no new public API. Relocation is identity-preserving.

- [ ] **Step 1: Create `TidalInternalModels.kt` with the seven relocated declarations**

CUT these from their current homes and PASTE them **verbatim** (bodies and KDoc unchanged) into a new `TidalInternalModels.kt`:
- from `TidalMetadataDetail.kt`: `ArtistLink` (`:18`), `AlbumSummary` (`:21-28`), `normalizeTitleForDedup` + its two private regexes `TITLE_PUNCT`/`TITLE_WHITESPACE` (`:365-407`), `dedupRemoteAlbums` (`:436-439`), `mergeArtistTracks` (`:492-498`);
- from `TidalMetadataPrimitives.kt`: `releaseYearOf` (`:52`);
- from `TidalMetadataResolution.kt`: `TrackRelations` (`:14`).

```kotlin
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

// <paste ArtistLink, AlbumSummary, releaseYearOf, TITLE_PUNCT, TITLE_WHITESPACE,
//  normalizeTitleForDedup, dedupRemoteAlbums, mergeArtistTracks, TrackRelations
//  here, verbatim from the sources above. `Track` is the only import they need
//  (AlbumSummary/ArtistLink/releaseYearOf/TrackRelations need none of their own).>
```

**After the cut, delete the now-orphaned imports** in the three source files if they become unused (e.g. `TidalMetadataResolution.kt` still uses `Json`/`JsonArray`/`JsonObject` for `trackRelationsFrom`, so those stay; check each file compiles). Leave everything else in those three files exactly as it is — they are deleted whole in Task 5, but must stay green until then.

- [ ] **Step 2: Relocate the three domain types into their new owners**

- CUT `TidalAlbumDetail` (`TidalMetadataDetail.kt:73-85`, with its KDoc) → PASTE into `TidalInternalAlbum.kt` (it is that file's product now). It needs no new import (`Track` is already imported there via the DTOs — add `import io.github.auxen.model.Track` if the file does not already have it; `TidalV1PageTrack.toTrack()` returns `Track`, so it does).
- CUT `TrackCredit` (`TidalMetadataDetail.kt:109`) and `TidalTrackDetail` (`TidalMetadataDetail.kt:112-119`), both with KDoc → PASTE into `TidalInternalTrackDetail.kt`. `TidalTrackDetail` references `Track` (its `similarTracks`), so add `import io.github.auxen.model.Track` to that file.

The official `albumDetailFrom`/`trackDetailFrom` (still in `TidalMetadataDetail.kt` until Task 5) keep compiling against these relocated types — same package.

- [ ] **Step 3: Harden `artistPageFrom` — wrap the whole projection in `runCatching`**

`artistPageFrom` (`TidalArtistPage.kt:311-355`) decodes the envelope under `runCatching`, but the post-decode mapping (`decodeEach`, `toAlbumSummary`, `toTrack`, `formatTidalBio`, the `TidalArtistPage(...)` build) runs OUTSIDE it, so a future throwing mapper would break totality. Wrap the body from the `modules` line through the return in one `runCatching { … }.getOrNull()`:

```kotlin
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
        biography = header?.bio?.text?.let { formatTidalBio(it) }?.takeIf { it.isNotBlank() },
        artworkUrl = tidalV1ArtistPictureUrl(header?.artist?.picture),
        topTracks = topTracks,
        albumSections = albumSections,
        artistSections = artistSections,
    )
}.getOrNull()
```

The existing `TidalArtistPageTest` totality cases (unknown module, null items, error envelope, malformed item) all still pass — the wrap only makes the guarantee airtight against a throwing mapper. **Run them to prove it (Step 6).**

- [ ] **Step 4: Move the survivor tests into `TidalInternalModelsTest.kt`**

Create `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalModelsTest.kt` and **move** (cut) into it, verbatim:
- from `TidalMetadataPrimitivesTest.kt`: every `releaseYearOf` test case;
- from `TidalArtistDetailTest.kt`: every `dedupRemoteAlbums`, `normalizeTitleForDedup` and `mergeArtistTracks` test case, plus the `albumSummary`/`tidalTrack`/`localTrack` helpers they use.

Give it the standard header:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class TidalInternalModelsTest {
    // <the moved releaseYearOf / dedupRemoteAlbums / normalizeTitleForDedup /
    //  mergeArtistTracks cases + helpers, verbatim>
}
```

Leave the **doomed** cases where they are: `TidalMetadataPrimitivesTest.kt` keeps its `pickArtworkUrl`/`parseIsoDurationSeconds`/`ArtworkFile` cases, `TidalArtistDetailTest.kt` keeps its `artistDetailFrom`/`ARTIST_DETAIL_INCLUDE` cases (they still exercise live-until-Task-5 code and both files are deleted whole in Task 5).

- [ ] **Step 5: Build + the full suite + goldens**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest assembleDebug
```

**Expected: PASS, full suite + every Roborazzi golden.** Relocation is behaviour-preserving, so nothing moves. **A shifted golden here means a leaked change — fix it, do not re-record** (the one deliberate re-record is Task 5's settings screen).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalModels.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalAlbum.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalTrackDetail.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataDetail.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataPrimitives.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataResolution.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalArtistPage.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalModelsTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalArtistDetailTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataPrimitivesTest.kt
git commit -m "refactor(android): relocate internal-v1 survivors out of the official-v2 files

The ten symbols internal-v1 code still uses — ArtistLink, AlbumSummary,
TidalAlbumDetail, TrackCredit, TidalTrackDetail, releaseYearOf,
normalizeTitleForDedup, dedupRemoteAlbums, mergeArtistTracks, TrackRelations —
move (unchanged) into TidalInternalModels/Album/TrackDetail.kt so the official-v2
deletion cannot break the build. Same package, so no caller's import moved. The
survivor unit tests move with them.

Also folds in the reviewer-flagged totality fix: artistPageFrom's post-decode
mapping now sits inside runCatching, so a throwing mapper degrades to null
instead of breaking the artist page.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: On-device checkpoint (GATE for Task 5)

No code. The three migrations are proven on the internal-v1 path **before** anything is deleted — Task 5 removes the official fallback, so while it is still in the tree this whole migration is one revert away.

- [ ] **Step 1: Confirm nothing forbidden moved, and `tidalMetadata` is probe-only**

```bash
git status --short
git diff --stat -- android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalAuth.kt
git grep -n "Graph.tidalMetadata" -- android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt
```

**Expected:** `TidalAuth.kt` shows ZERO changes. `Graph.tidalMetadata` now appears **only** in `runTidalMetadataProbe` and `runTidalIdEquivalenceProbe` (the two probes) — every product path is off official v2.

- [ ] **Step 2: Build the APK LOCALLY and run the checkpoint**

CI has no Tidal credentials — a CI APK cannot log in. Build locally, install, log in on the internal-v1 path (**Account → Log in to Tidal**). Official login is **not** used.

| # | Path | What must be true |
|---|---|---|
| 1 | Open a **Tidal album** (from search or an artist's discography — carries `tidalAlbumId`) | Header shows real title/artist/type/year + high-res cover; the **complete** tracklist renders in order; a **multi-volume** album numbers per disc; **no video rows**; **Play All plays**. |
| 2 | Open a **local album that has a favorited Tidal track** (no `tidalAlbumId` — the seed path) | It enriches: the Tidal cover/tracklist overlay the local group. **This is the proof the internal-v1 `tracks/{id}` seed resolution works** (the one shape not in the probed schema). |
| 3 | Long-press a Tidal track → **Credits & lyrics** | Credits render grouped by role; lyrics render as prose. |
| 4 | Open **Credits & lyrics** on an **instrumental** (no lyrics) | Shows **"No lyrics available."** — a clean state, not a spinner or an error (the expected 404). |
| 5 | Regression: open an **artist page** | Unchanged — biography, categorised discography, Fans Also Like with photos (this already ran on internal-v1; confirm the step-2 id swap did not regress the seed resolution). |
| 6 | Open a **non-Tidal (local-only) album** and go **offline** | Local content only, no spinner stuck, no crash. |

**Gate criterion: rows 1–5 all pass and row 6 does not regress.** Paste the result back to close the loop.

**If any FAILS, STOP. Do not proceed to Task 5.** For row 1/2 a `null` tracklist first suspects the `albums/{id}/items` wrapper shape or the `tracks/{id}` seed shape; for row 3 an empty credits list suspects the `includeContributors` param.

---

### Task 5: Delete the official-v2 stack (the LAST task, gated on Task 4)

**Do not start until Task 4's gate has passed.** This is the one destructive task, and it is gated deliberately: everything it removes is what the three screens would fall back to if internal-v1 misbehaved in a way Task 4 caught. After Tasks 1–3 no product code references any official-v2 symbol; a clean build here **is** the proof.

**Files — whole-file deletions (per the deletion set above):** the 10 official-v2 mains + `Pkce.kt` + `TidalStreamingProbe.kt` + `TidalPlaybackProbe.kt` + `ui/TidalOfficialDebugScreen.kt`, and the 13 official-v2 test files.

**Files — in-file edits:** `AuxenApp.kt`, `PlayerViewModel.kt`, `MainActivity.kt`, `SettingsScreen.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `res/values/strings.xml`, and re-record `settings-screen-{light,dark}.png`.

- [ ] **Step 1: Delete the whole-file set**

```bash
git rm android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalJsonApi.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataDetail.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataPrimitives.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataResolution.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialApi.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialAuth.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialSession.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/Pkce.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalStreamingProbe.kt \
       android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalPlaybackProbe.kt \
       android/app/src/main/kotlin/io/github/auxen/ui/TidalOfficialDebugScreen.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalJsonApiTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataPrimitivesTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepositoryTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataResolutionTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialApiTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialAuthTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialSessionTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/PkceTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalStreamingProbeTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalAlbumDetailTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalTrackDetailTest.kt \
       android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalArtistDetailTest.kt
```

- [ ] **Step 2: Gut the official Graph wiring in `AuxenApp.kt`**

Remove the three imports (`TidalMetadataRepository`, `TidalOfficialClient`, `TidalOfficialSession`, lines 27-29), the three `lateinit` Graph members with their KDoc (`tidalOfficialSession`/`tidalOfficialApi`/`tidalMetadata`, lines 146-153), and their three constructions in `init` (lines 188-196). Leave `tidal`/`tidalAuth`/`resolver`/everything else untouched.

- [ ] **Step 3: Excise the official/probe surface from `PlayerViewModel.kt`**

Delete, in one pass:
- Imports `android.net.Uri` (line 5) and `io.github.auxen.provider.tidal.formatTidalStreamingProbeResult` / `probePlayback` / `probeStreaming` (lines 34-36). **Keep** `android.util.Log` (still used at the playback-error log, line 861).
- The streaming spike surface: `tidalOfficialStatus` + its KDoc (`:223-228`), `tidalOfficialProbePlayer` (`:230-231`), `beginTidalOfficialLogin` (`:233-234`), `completeTidalOfficialLogin` (`:236-245`), `runTidalOfficialStreamingProbe` (`:247-279`).
- The metadata probe: `_tidalMetadataStatus`/`tidalMetadataStatus` + KDoc (`:281-287`), `runTidalMetadataProbe` (`:289-305`).
- The id-equivalence probe: `_tidalIdProbeStatus`/`tidalIdProbeStatus` (`:307-308`), `runTidalIdEquivalenceProbe` (`:310-400`).
- The artist-page probe: `_tidalArtistPageProbeStatus`/`tidalArtistPageProbeStatus` (`:402-403`), `runTidalArtistPageProbe` (`:405-495`).
- In `onCleared` (`:1109-1112`), remove the `tidalOfficialProbePlayer?.release()` line; keep `override fun onCleared()`/`super.onCleared()`.

**Keep** `artistPage`/`albumDetail`/`trackDetail` flows, `loadArtistDetail`/`loadAlbumDetail`/`loadTrackDetail` (now internal-v1), and everything non-official.

- [ ] **Step 4: Remove the debug route + redirect handler from `MainActivity.kt`**

- `OVERLAY_ROUTES` (`:74`): drop `"tidal-official-debug"`.
- `topBarTitle` (`:98`): drop the `"tidal-official-debug" -> "Tidal Debug"` arm.
- `onCreate` (`:130`): drop `handleTidalOfficialRedirect(intent)`.
- Delete `override fun onNewIntent` (`:133-141`) and `handleTidalOfficialRedirect` (`:143-148`) entirely — their only job was the PKCE redirect. (Remove the now-unused `Intent` import only if nothing else uses it — verify; `onCreate(savedInstanceState: Bundle?)` and permission code likely keep other imports, so check before removing `Intent`.)
- The `settings` composable (`:387-392`): drop the `onOpenTidalOfficialDebug = { … }` argument so it reads `SettingsScreen(viewModel)`.
- Delete the `composable("tidal-official-debug") { TidalOfficialDebugScreen(viewModel) }` route (`:393`).

- [ ] **Step 5: Remove the Advanced entry from `SettingsScreen.kt`**

- `SettingsScreen` (`:54`): drop the `onOpenTidalOfficialDebug: () -> Unit` param, and drop the `onOpenTidalOfficialDebug = onOpenTidalOfficialDebug` arg in its `SettingsContent(...)` call (`:65`).
- `SettingsContent` (`:79`): drop the `onOpenTidalOfficialDebug: () -> Unit = {}` param.
- Delete the whole Advanced `SettingsSectionCard` block (`:143-156`) — the comment, the section card, its subtitle Text and the `TextButton`.

`ComponentScreenshotTest.kt`'s `SettingsPreview` already calls `SettingsContent` **without** `onOpenTidalOfficialDebug` (`:1058-1065`), so it needs no edit — but its rendered output loses the Advanced card, so the goldens are re-recorded in Step 9.

- [ ] **Step 6: Remove the auth-callback intent-filter from `AndroidManifest.xml`**

Delete the second `<intent-filter>` and its comment (`:37-45`) — the `auxen://auth-callback` VIEW filter. **Leave `android:launchMode="singleTask"`** (`:30`): it is harmless without the redirect and reverting it is an unrelated task-affinity change this plan should not make. (Flagged in § *Open questions* as an optional follow-up.)

- [ ] **Step 7: Stop referencing the official client id in `app/build.gradle.kts`**

Delete the `tidalOfficialClientId` property read (`:34`) and its `buildConfigField("String", "TIDAL_OFFICIAL_CLIENT_ID", …)` line (`:37`), and trim the `auxen.tidalOfficialClientId` paragraph from the credentials comment (`:26-31`). **Leave the credential in `gradle.properties`/`local.properties`** — just stop compiling it in. Keep `TIDAL_CLIENT_ID`/`TIDAL_CLIENT_SECRET` (internal-v1 login) untouched.

- [ ] **Step 8: Delete the 14 dead strings from `res/values/strings.xml`**

Remove these entries (all confirmed by grep to have exactly one non-`strings.xml` reference, in the just-deleted debug screen / settings entry): `settings_advanced_title`, `settings_advanced_subtitle`, `settings_tidal_official_link`, `tidal_official_title`, `tidal_official_description`, `tidal_official_login_button`, `tidal_official_track_id_label`, `tidal_official_probe_button`, `tidal_official_artist_id_label`, `tidal_official_metadata_button`, `tidal_official_id_probe_label`, `tidal_official_id_probe_button`, `tidal_artist_page_probe_label`, `tidal_artist_page_probe_button` (the contiguous block at `:179-193`).

- [ ] **Step 9: Verify — the compiler is the proof the deletion was safe**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest assembleDebug
```

**Expected: PASS on compilation, ONE golden failure — `settings-screen-{light,dark}`.** That failure is the DELIBERATE removal of the Advanced card, not a leaked default. Confirm the diff shows only the Advanced section gone, then re-record and re-run:

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android recordRoborazziDebug --tests '*ComponentScreenshotTest*'
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest assembleDebug
```

**Now expected: PASS, full suite + goldens.** A clean compile is the proof that nothing outside the deletion set referenced any official-v2 symbol.

- [ ] **Step 10: Prove no official-v2 symbol survives anywhere**

```bash
git grep -nE "TidalOfficial|tidalMetadata|TidalMetadataRepository|TidalJsonApi|TidalDocument|artistDetailFrom|trackDetailFrom|albumDetailFrom\b|TIDAL_OFFICIAL_CLIENT_ID|auth-callback|Pkce|probeStreaming|probePlayback" -- android/app/src ':!*TidalInternal*'
```

**Expected: NO hits in product code.** (Prose/KDoc mentions of `albumDetailFrom` in `TidalArtistPage.kt:152` and `TidalOfficialEndpoints` in `TidalInternalArtist.kt:24` are plain-text names, not code references — optionally tidy them, they do not affect the build.) If any real reference remains, restore it and re-check the sweep — do not chase the error.

- [ ] **Step 11: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/SettingsScreen.kt \
        android/app/src/main/AndroidManifest.xml \
        android/app/build.gradle.kts \
        android/app/src/main/res/values/strings.xml \
        android/app/src/test/screenshots/settings-screen-light.png \
        android/app/src/test/screenshots/settings-screen-dark.png
git commit -m "refactor(android): retire the official Tidal v2 stack entirely

Album detail, credits and lyrics now run on internal-v1 (prior commits), so
official v2 — PREVIEW-only at our tier and unable to stream — has no remaining
job. Deleted: the JSON:API layer, the official auth/session/client/endpoints,
the metadata repository + primitives + resolution, the PKCE + streaming/playback
probes, the debug screen and its Settings entry and nav route, the auth-callback
intent-filter, the TIDAL_OFFICIAL_CLIENT_ID buildConfig field, and the dead
strings. The frozen-constructor constraint on TidalMetadataRepository dies with
the class.

Internal-v1 survivors were relocated first (prior commit); a clean build is the
proof nothing outside the deletion set referenced official v2. The settings
golden is re-recorded for the deliberate removal of the Advanced card.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Scope

**Five tasks** — under the brief's ceiling of six. The brief's six items map on as: album (Task 1), credits + lyrics **merged** (Task 2, since they share the sheet, the domain type and the loader), relocate (Task 3), delete (Task 5). The hardening (brief item 6) is **folded in**, not a separate task: the three new parses are born wrapped in `runCatching`, and the existing `artistPageFrom` wrap is a step of Task 3. The on-device gate (Task 4) is inserted where the brief's *Sequencing* section requires it — between the migrations and the deletion.

**Task 1 and Task 5 are the heavy ones.** Task 1 carries the shared seed-track id resolution because both album and artist chains need it; Task 5 is broad by nature (it is the retirement) but almost entirely mechanical, and every line of it is gated on the Task 4 checkpoint.

---

## Open questions — flagged, not invented

1. **Seed-track id resolution shape (`GET tracks/{id}` → `{artists[0].id, album.id}`).** This is the ONE shape not in the probed retirement schema. It is firmly grounded — `tidalapi/media.py`'s `Track.parse` reads exactly `artists` + `album` from this endpoint, the desktop's `get_track` uses it, and this repo already models the fields (`TidalV1PageTrack`, `AlbumDto`, `TidalV1PageTrackAlbum`). It is also **gated**: Task 4 row 2 (enrich a local album via a seed Tidal track) and row 5 (the artist seed path) both fail on-device if it is wrong, before any deletion. **Recommendation:** treat it as grounded and let the gate confirm; if a reviewer wants belt-and-braces, a 30-second probe of `GET tracks/{id}?countryCode=US` against the desktop session settles it. I did not invent an alternative resolution path.

2. **`albums/{id}/items` item shape — wrapper vs bare.** The schema notes `[{type:"track", item:{…}} | track…]`. I committed to the `{type, item}` wrapper form (what `tidalapi`'s `Album.items()` reads) and filter `type == "video"`. If the live endpoint ever returns bare tracks, the tracklist would come back empty — which Task 4 row 1 catches. Flagging the ambiguity rather than modelling both shapes speculatively.

3. **`pages/album` richer sections (review, "more by artist", similar albums).** The schema mentions them; `AlbumDetailScreen` has no surface for any of them and this plan does not add one. **Deferred, not built** — same posture as the artist page's deferred Videos/Playlists sections. A future album-page enrichment is its own plan.

4. **`android:launchMode="singleTask"` on `MainActivity`.** Added for the PKCE redirect; harmless without it. I left it rather than change task-affinity behaviour as a side effect of this deletion. Reverting it to the default is an optional, separate follow-up.

## Out of scope — noted as follow-ups, deliberately not done here

- **Slice 2 (collection sync)** plans on official-v2 `userCollections` writes. It must re-plan onto internal-v1 favorites (`favorites.add_artist()` / `favorites.albums()` — the desktop uses them). **Separate plan revision.** This plan only removes the read stack it would have shared.
- **Now-dead internal-v1 leftovers from the (unfinished) artist-page-rebuild Task 6.** Deleting the official-v2 debug probes leaves `TidalProvider.artistBio`/`artistBioUrl`/`artistBioFrom`/`TidalV1Bio` and `artistAlbums`/`artistTopTracks` with no live caller (their only callers were the probes and the pre-rebuild artist page). They are **internal-v1, not official-v2**, they compile clean (Kotlin does not error on unused public/suspend members), and their deletion is the artist-page-rebuild plan's Task 6 — **out of scope here** to avoid overlapping that plan. **Note the overlap:** this plan supersedes artist-page-rebuild Task 6's *official-v2* deletions (`artistDetail`/`artistDetailFrom`/`TidalArtistDetail`/`ARTIST_DETAIL_INCLUDE`) — those are gone after Task 5, so that plan's Task 6 reduces to the internal-v1 `artistBio`/`artistAlbums`/`artistTopTracks` cleanup only.
