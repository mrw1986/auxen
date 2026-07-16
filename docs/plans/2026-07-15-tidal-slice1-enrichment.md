# Slice 1 — Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the currently local-only `ArtistDetailScreen` and `AlbumDetailScreen` into rich, remote-backed screens (biography, similar artists, full discography, real top tracks, real album metadata + full tracklist + cover art), and add track credits + lyrics. **Album** detail, **track** detail (credits/lyrics) and the artist's **biography / similar artists / profile art** are sourced from the sanctioned official `openapi.tidal.com/v2` metadata API through the Slice-0 Foundation seam. The **artist page's three list surfaces** — discography, top tracks, and artist-id-by-name — come from additive read-only **internal-v1** calls mirroring the desktop, because official v2 provably cannot reproduce them (see *Why the artist's lists come from internal-v1* below). Streaming and search stay on the internal `api.tidal.com/v1` path, and playback never touches official v2.

**Architecture:** Additive overlay. The local MediaStore-derived grouping (`groupAlbums`/`groupArtists` over the `localTracks + favorites` pool) remains the offline-safe base of both detail screens, and always renders first. On top of it the screen resolves a Tidal catalog id (see *Artist-id resolution* below) and overlays remote metadata. An artist we cannot resolve an id for, and any album that is not Tidal-backed, shows exactly what it shows today (no remote sections). Every official call goes through the Foundation seams: `TidalOfficialSession.validAccessToken()` → `TidalOfficialClient` → the generic JSON:API layer (`TidalDocument`/`TidalDocumentView`/`decodeAttributes` in `TidalJsonApi.kt`) → a **pure** `xFrom(doc, json, …)` projection to a net-new domain type → a thin, un-unit-tested suspend wrapper on `TidalMetadataRepository` (held at `Graph.tidalMetadata`). The three internal-v1 reads follow the same discipline one layer down: a **pure** URL builder + a **pure** body→domain projection in `TidalInternalArtist.kt`, and a four-line fetch on `TidalProvider`. New domain types are net-new — the codebase has only `AlbumGroup`/`ArtistGroup` grouping structs today, no `Album`/`Artist`/`TrackDetail` domain classes.

**The resolution chain (documented, and proven on-device by Task 1's gate):**

```
Tidal-sourced Track (Track.source == TIDAL, Track.sourceId = catalog track id)
        │  GET /tracks/{sourceId}?include=artists,albums   (TidalMetadataRepository.trackRelations)
        ▼
TrackRelations(artistId, albumId, isrc)
        ├─ GET /artists/{artistId}?include=biography,similarArtists,profileArt         → TidalArtistDetail
        │       └─ when `biography` is absent from `included`:
        │          GET /artists/{artistId}/relationships/biography?include=biography   → bio text (Task 3)
        ├─ GET api.tidal.com/v1/artists/{artistId}/albums?limit=50     (INTERNAL-v1)   → discography (Task 3)
        ├─ GET api.tidal.com/v1/artists/{artistId}/toptracks?limit=50  (INTERNAL-v1)   → top tracks  (Task 3)
        ├─ GET /albums/{albumId}?include=items,artists,coverArt,similarAlbums          → TidalAlbumDetail
        └─ GET /tracks/{sourceId}?include=credits,lyrics,similarTracks,artists         → TidalTrackDetail

Artist with NO Tidal track to seed from (a purely local artist):
        GET api.tidal.com/v1/search?query={name}&types=artists&limit=20 (INTERNAL-v1)
        └─ EXACT case-insensitive name match only → artistId, else null (Task 3)
```

**id-equivalence — the gate (Task 1). RESULT: PASSED on-device, 2026-07-16, real device + real account:**

```
Track id 186460926 resolves on the official v2 API (same catalog id).
ISRC: QMKHM1900232
Artist: Metallica (id 8405)
Album id: 186460912
PASS — the internal-v1 track id is the same catalog id the official v2 API accepts.
```

So enrichment keys off the ids we already hold, and the ISRC/`barcodeId` search fallback the plan hedged on is **dead — it is not built, not designed, and must not be revived.** (`TrackRelations.isrc` remains on the merged Task-1 data class; it is now just carried, unused.) The one id-equivalence question the gate did **not** answer is the *reverse* direction the artist's internal-v1 reads need — an artist id read off the official v2 API, sent to an internal-v1 artist endpoint — which Task 3 Step 9 probes on-device before Task 6 consumes it.

**Artist-id resolution — the two-step chain (state it once; the tasks refer here).** `ArtistDetailScreen` is keyed by artist **name**, but all three internal-v1 artist reads *and* the official artist document need an **id**. Resolve it in this order and stop at the first hit:

1. **`tidalArtistId` nav arg** — already known (a similar-artist tap). No resolution needed.
2. **Preferred (precise): seed from a local Tidal track by this artist** → `sourceId` → `trackRelations` → `artistId`. Gate-verified on-device (Task 1). The seed **MUST** be `tracks.firstOrNull { it.isTidal && it.artist == artist }`. Matching on `albumArtist` too would resolve a **different** artist's id: the screen's `tracks` is filtered on `it.artist == artist || it.albumArtist == artist` (`ArtistDetailScreen.kt:79`), while `trackRelations` reads `related(track, "artists").first()` — the **primary** artist. Seeding off an albumArtist-only match (a compilation, a "Various Artists" album) would hang the wrong bio, the wrong discography and the wrong top tracks on the page. `TidalProvider.toTrack` maps `Track.artist` from `TrackDto.artist.name` (the main artist), so this comparison is exactly the right guard.
3. **Fallback (Decision 4): exact-match internal-v1 search by name** — `TidalProvider.searchArtistIdExact(artistName)`. Reached when there is no nav-arg id and no seed (a purely local artist), and also when a seed exists but `trackRelations` fails.
4. **Nothing resolves → no remote enrichment.** The page renders exactly what it renders today.

**The desktop's `artists[0]` fallback is deliberately NOT ported — this is a bug fix, not a parity gap.** `get_artist_info` (`auxen/providers/tidal.py:867-876`) loops for an exact `a_name.lower() == artist_name.lower()` and, **when nothing matches, falls back to `artists[0]`** — a fuzzy best-match that attaches the *wrong* artist's bio, discography and top tracks. The user's own desktop exhibits this today (artist images resolve for some artists and not others). Slice 1 requires an **exact case-insensitive name match**; if none, it resolves to **null** and the page shows what it shows today. A miss is strictly better than a confident wrong answer. **Do not "restore parity" by re-adding the `artists[0]` fallback** — its absence is the decision.

**Why the artist's lists come from internal-v1, not official v2 (user decisions, 2026-07-16).** Three reads, one root cause.

1. **Discography.** The on-device session probed artist `8405` (Metallica) with `?include=albums` and got back 20 *Fixxxer* variants — rough mixes, a vocal idea, `Fixxxer (Remastered)` twice — not a discography. Both `Similar (20)` and `Albums (20)` landing on exactly 20 shows `include` returns an **arbitrary first page**, not a curated list. Official v2 cannot reproduce the desktop's view by other means either: `Albums_Attributes.albumType` is `ALBUM | EP | SINGLE` with **no COMPILATION value** (verified in the cached spec), so an `albumType == "ALBUM"` filter returns albums *plus* compilations; and `/artists/{id}/relationships/albums` takes only `page[cursor]`, `countryCode`, `include` — **no filter param** — so it would need N paged calls plus a client-side heuristic and still be wrong. The desktop's discography is `tidalapi`'s `Artist.get_albums(limit=50)`, which sends `{limit, offset}` and **no `filter`** to internal-v1 `artists/{id}/albums`.

2. **Top tracks.** `?include=tracks` is the **same arbitrary-page mechanism on the same document** — it was never popularity-ordered, and the previous revision of this plan correctly refused to label it "Top tracks". There is no popularity-ordered artist-tracks endpoint on official v2 at our tier. The desktop's real top tracks are `tidalapi`'s `Artist.get_top_tracks(limit=50)` → internal-v1 `artists/{id}/toptracks`, whose docstring is explicit: *"Queries TIDAL for the artists tracks, sorted by popularity"* (`tidalapi/artist.py:179-194`). So `"tracks"` is **dropped from the artist include entirely** — an arbitrary page is dead weight once the real list is one call away — and the UI section can honestly be labelled **"Top tracks"**.

3. **Artist id by name.** `ArtistDetailScreen` is keyed by artist **name**; every one of these reads needs an **id**. When no local Tidal track exists to seed the id from, the desktop searches internal-v1 by name (`get_artist_info`, `auxen/providers/tidal.py:850-876`). This does not strain the series' rules at all: *"search stays on internal-v1"* was always the constraint — this is search, on internal-v1, where it belongs. See *Artist-id resolution* below for the one place the port deliberately **diverges** from the desktop.

All three are read-only catalog GETs, mirroring calls the desktop makes daily. Metadata-only still holds: none of them resolves a stream or touches session state.

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), OkHttp (raw), AndroidX DataStore (via Foundation), Jetpack Compose + Navigation-Compose, JUnit4 + Robolectric. manual `Graph` service-locator DI (no Retrofit, no Dagger/Hilt). minSdk 26 / compileSdk 35 / Media3 1.5.1. `java.time.Duration.parse` is available at minSdk 26.

## Global Constraints

- **Metadata only.** NEVER route playback or search through the official path. Playable tracks projected from official documents are ordinary `Track(source = Source.TIDAL, sourceId = <catalog id>)` values that play through the *existing* internal-v1 pipeline (`Graph.mediaItemFor` → `auxen://tidal/<id>` → `TrackResolver` → `TidalProvider.getStreamInfoById`). No new playback code.
- **Internal-v1 is additive-only, and never carries playback for this slice.** Precisely:
  - `TidalAuth.kt` is **untouched** — no change of any kind. Confirm with `git status` at the end (Task 8).
  - Internal-v1's **auth, streaming, and search paths are untouched**: `restoreSession`, `logout`, `validToken`, `get`, `search`, `getStreamInfo`, `getStreamInfoById`, `toTrack` and the existing DTOs are **not edited**, and no existing method changes behaviour or signature. Note `search` (the `/search/tracks` track search the player already uses) is on that untouched list — Slice 1 **adds** a sibling artist search, it does not modify this one.
  - Slice 1 makes exactly **THREE additive, read-only additions to `TidalProvider`** — all catalog GETs; none resolves a stream or touches session state; all three land in Task 3:
    1. `artistAlbums(artistId)` → `GET api.tidal.com/v1/artists/{id}/albums?limit=50&offset=0&countryCode=…` (discography)
    2. `artistTopTracks(artistId)` → `GET api.tidal.com/v1/artists/{id}/toptracks?limit=50&offset=0&countryCode=…` (popularity-ordered top tracks)
    3. `searchArtistIdExact(artistName)` → `GET api.tidal.com/v1/search?query=…&limit=20&offset=0&types=artists&countryCode=…` (artist id by exact name)
  - **The artist search does not strain this constraint at all.** *"Search stays on internal-v1"* was always the rule; this is search, on internal-v1, where it belongs. What the constraint forbids is routing search through the **official** API — and we are not.
  - **Playback is NEVER routed through the official API.** Re-confirmed on-device 2026-07-16: `Manifest (trackManifests, THIRD_PARTY tier): PREVIEW — FULL_REQUIRES_HIGHER_ACCESS_TIER`. Streaming and search stay on internal-v1; official v2 is metadata only.
  - **What official v2 still owns in Slice 1:** the artist **biography** (with the relationship-endpoint fallback), **similar artists**, **profile art**, and the whole of Tasks 4/5 (album detail, track credits, lyrics).
  - This constraint is **narrower than Slices 2 and 3's**, which still say internal-v1 is untouched absolutely. That wording is correct for those slices (they add no internal-v1 call) but is no longer a series-wide invariant — see the Task 3 revision reports.
- **Never use the Tidal SDK `player` module.** Not touched here.
- **PKCE public client — NEVER send `client_secret`** on any official request. All requests go through the Foundation's `TidalOfficialEndpoints.authed(...)`, which adds only `Authorization: Bearer` + `Accept: application/vnd.api+json`.
- **Never log or print `accessToken`/`refreshToken`.** Probe/status readouts contain only human-readable names/titles/ids and ISRCs — never a token or a full request URL.
- **Do not unit-test live HTTP execution** (established precedent). Unit-test PURE logic only: artwork best-fit selection, ISO-duration/date parsing, document→domain projection, and request shapes. The suspend/network wrappers on `TidalMetadataRepository`, the `PlayerViewModel` passthroughs, and the Compose screens are NOT unit-tested and close on on-device checkpoints, exactly like the Foundation's debug-screen probe.
- **New classes stay plain instantiable classes held on `Graph`.** `TidalMetadataRepository` already lives on `Graph.tidalMetadata` (Foundation); this slice only extends it. No JVM-wide `object` with mutable state.
- **Attribute DTO field names are copied verbatim from the cached spec** (`.superpowers/sdd/tidal-openapi-v2.json`, `Artists_Attributes` / `ArtistBiographies_Attributes` / `Albums_Attributes` / `Tracks_Attributes` / `Artworks_Attributes` / `Artwork_File` / `Artwork_File_Meta` / `Credits_Attributes` / `Lyrics_Attributes`). Every field nullable/defaulted.
- Every code step below shows the actual code. No `TODO`/"add error handling"/"similar to above" placeholders in logic steps; the Compose UI steps describe the concrete wiring and close on an on-device checkpoint (Foundation debug-screen precedent).

**Depends on Slice 0 (Foundation) being merged.** Treated as existing: `TidalOfficialSession.validAccessToken()`; `TidalJsonApi.kt` (`TidalResource`, `TidalRelationship`, `TidalDocument`, `TidalDocumentView` with `primary`/`primaryList`/`related`, and `inline fun <reified T> TidalResource.decodeAttributes(json): T?`); `TidalOfficialEndpoints.artistRequest(id, accessToken, include, countryCode)`; `TidalOfficialClient.getArtist(...)`; the `@Serializable` attribute DTOs `ArtistAttributes(name, handle, popularity)`, `ArtistBiographyAttributes(text, source)`, `AlbumAttributes(title, albumType, releaseDate, explicit)`; `artistOverviewFrom(...)` / `TidalMetadataRepository.artistOverview(...)` / `ARTIST_INCLUDE`; and `Graph.tidalMetadata: TidalMetadataRepository(session, client, json, countryCode, locale)` (constructor frozen at its final shape by the Foundation — this slice never rewrites it). The spike DTO `TrackAttributes(title, duration, isrc, explicit)` in `TidalOfficialApi.kt` is reused as-is. The **shared metadata primitives** (Foundation Task 4, `TidalMetadataPrimitives.kt`) are also treated as existing and consumed, never re-declared: `@Serializable ArtworkFileMeta(width, height)` / `@Serializable ArtworkFile(href, meta)` / `@Serializable ArtworkAttributes(files, mediaType)`, `pickArtworkUrl(files, targetPx)`, `parseIsoDurationSeconds(iso)`, `releaseYearOf(releaseDate)`.

---

### Task 1: The id-equivalence gate — resolution chain + on-device probe

**This task gates the entire slice.** It adds the `trackRelations` resolution primitive (pure projection + thin wrapper), an id-equivalence probe in the existing Foundation debug screen, and proves on-device that an internal-v1 track id resolves on the official v2 API and yields the artist/album ids the rest of the slice needs. Do not start Tasks 3–8 until Step 9 passes on-device.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt` (add `include`/`countryCode` params to `trackRequest`, backward-compatible)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt` (add `getTrackMetadata`)
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataResolution.kt` (`TrackRelations`, `trackRelationsFrom`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt` (add `trackRelations(...)` + include constants)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (add `tidalIdProbeStatus` + `runTidalIdEquivalenceProbe`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/TidalOfficialDebugScreen.kt` (add the id-equivalence probe section)
- Modify: `android/app/src/main/res/values/strings.xml` (probe strings)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataResolutionTest.kt` (create)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt` (add `trackRequest` include-shape case)

**Interfaces:**
- Consumes: `TidalOfficialSession.validAccessToken()`; `TidalDocument`/`TidalDocumentView`/`decodeAttributes`; `TrackAttributes` (spike DTO); `TidalMetadataRepository.artistOverview(...)` (Foundation, for the human-readable artist name in the readout).
- Produces: `TidalOfficialEndpoints.trackRequest(id, accessToken, include: List<String> = emptyList(), countryCode: String? = null): Request`; `TidalOfficialClient.getTrackMetadata(id, accessToken, include, countryCode): Result<TidalDocument>`; `data class TrackRelations(artistId: String?, albumId: String?, isrc: String?)`; `fun trackRelationsFrom(doc: TidalDocument, json: Json): TrackRelations?`; `TidalMetadataRepository.trackRelations(trackId: String): Result<TrackRelations>`; `PlayerViewModel.tidalIdProbeStatus: StateFlow<String?>` + `runTidalIdEquivalenceProbe(tidalTrackId: String)`.

- [ ] **Step 1: Write the failing test for `trackRelationsFrom` + the extended `trackRequest` shape**

Create `TidalMetadataResolutionTest.kt`:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidalMetadataResolutionTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val trackRelDoc = """
        {
          "data": {
            "id": "75413016", "type": "tracks",
            "attributes": { "title": "Sample", "isrc": "USABC1234567" },
            "relationships": {
              "artists": { "data": [ { "id": "3456", "type": "artists" } ] },
              "albums":  { "data": [ { "id": "9876", "type": "albums" } ] }
            }
          },
          "included": []
        }
    """.trimIndent()

    @Test
    fun `trackRelationsFrom pulls the first artist id, album id and the isrc`() {
        val rel = trackRelationsFrom(json.decodeFromString(trackRelDoc), json)!!
        assertEquals("3456", rel.artistId)
        assertEquals("9876", rel.albumId)
        assertEquals("USABC1234567", rel.isrc)
    }

    @Test
    fun `trackRelationsFrom tolerates missing relationships`() {
        val doc = """{ "data": { "id": "1", "type": "tracks", "attributes": {} }, "included": [] }"""
        val rel = trackRelationsFrom(json.decodeFromString(doc), json)!!
        assertNull(rel.artistId)
        assertNull(rel.albumId)
        assertNull(rel.isrc)
    }

    @Test
    fun `trackRelationsFrom returns null when there is no primary track`() {
        val doc = """{ "data": [], "included": [] }"""
        assertNull(trackRelationsFrom(json.decodeFromString(doc), json))
    }
}
```

In `TidalOfficialEndpointsTest.kt`, add:

```kotlin
    @Test
    fun `track request carries include params and country code when asked`() {
        val request = TidalOfficialEndpoints.trackRequest(
            id = "75413016", accessToken = "tok-123",
            include = listOf("artists", "albums"), countryCode = "US",
        )
        val url = request.url
        assertEquals("/v2/tracks/75413016", url.encodedPath)
        assertEquals(listOf("artists", "albums"), url.queryParameterValues("include"))
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals("Bearer tok-123", request.header("Authorization"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalMetadataResolutionTest*' --tests '*TidalOfficialEndpointsTest*'`
Expected: FAIL — `trackRelationsFrom`/`TrackRelations` unresolved; the new endpoint test fails to compile (`trackRequest` has no `include` param).

- [ ] **Step 3: Extend `trackRequest` (backward-compatible) in `TidalOfficialEndpoints.kt`**

Replace the existing `trackRequest` (L23–24) with the overload-free, default-arg version. The existing zero-include call sites (`getTrack`, `TidalOfficialEndpointsTest`) keep compiling and still produce `.../v2/tracks/{id}` with no query string:

```kotlin
    /**
     * `GET /tracks/{id}`. With no [include]/[countryCode] this is the bare
     * track fetch the streaming spike uses (unchanged URL). With `?include=`
     * it is a JSON:API compound document for the metadata layer (credits/
     * lyrics/artists/albums). [countryCode] is optional per the spec.
     */
    fun trackRequest(
        id: String,
        accessToken: String,
        include: List<String> = emptyList(),
        countryCode: String? = null,
    ): Request {
        val url = "$BASE/tracks/$id".toHttpUrl().newBuilder()
            .apply { countryCode?.let { addQueryParameter("countryCode", it) } }
            .apply { include.forEach { addQueryParameter("include", it) } }
            .build()
        return authed(url, accessToken)
    }
```

- [ ] **Step 4: Add `getTrackMetadata` to `TidalOfficialClient.kt`**

Add beside `getTrack` (the generic `execute<T>` already parses any `@Serializable` `T`, and `TidalDocument` is one):

```kotlin
    /** Fetch a track as a JSON:API compound document (credits/lyrics/artists/albums). */
    suspend fun getTrackMetadata(
        id: String,
        accessToken: String,
        include: List<String>,
        countryCode: String? = null,
    ): Result<TidalDocument> = execute(TidalOfficialEndpoints.trackRequest(id, accessToken, include, countryCode))
```

- [ ] **Step 5: Create `TidalMetadataResolution.kt`**

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json

/**
 * The ids needed to fetch an artist/album detail document, resolved from a
 * single Tidal-sourced track via `GET /tracks/{id}?include=artists,albums`.
 * [isrc] is carried for the documented ISRC/barcode fallback (see the plan's
 * Architecture) — unused while direct id-equivalence holds.
 */
data class TrackRelations(val artistId: String?, val albumId: String?, val isrc: String?)

/**
 * Pure projection from a compound track [TidalDocument] to [TrackRelations].
 * Returns null when the document has no primary track resource. Extracted from
 * [TidalMetadataRepository.trackRelations] so the mapping is unit-testable with
 * no network.
 */
fun trackRelationsFrom(doc: TidalDocument, json: Json): TrackRelations? {
    val view = TidalDocumentView(doc, json)
    val track = view.primary ?: return null
    return TrackRelations(
        artistId = view.related(track, "artists").firstOrNull()?.id,
        albumId = view.related(track, "albums").firstOrNull()?.id,
        isrc = track.decodeAttributes<TrackAttributes>(json)?.isrc,
    )
}
```

- [ ] **Step 6: Add `trackRelations` + include constants to `TidalMetadataRepository.kt`**

Add the method inside the class (beside `artistOverview`), and extend the companion. Keep the Foundation's `ARTIST_INCLUDE`/`artistOverview` intact:

```kotlin
    /** Resolve the artist + album ids (and isrc) for a Tidal track — the detail-fetch entry point. */
    suspend fun trackRelations(trackId: String): Result<TrackRelations> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))
        return client.getTrackMetadata(trackId, token, TRACK_RELATIONS_INCLUDE, countryCode).mapCatching { doc ->
            trackRelationsFrom(doc, json) ?: error("Track $trackId not found on the official API")
        }
    }
```

In the companion (alongside `ARTIST_INCLUDE`):

```kotlin
        val TRACK_RELATIONS_INCLUDE = listOf("artists", "albums")
```

- [ ] **Step 7: Run the logic tests to verify they pass**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalMetadataResolutionTest*' --tests '*TidalOfficialEndpointsTest*'`
Expected: PASS (all resolution cases + both endpoint cases, including the unchanged bare-track shape).

- [ ] **Step 8: Add the probe to `PlayerViewModel.kt` and the debug screen**

In `PlayerViewModel.kt`, add near the Foundation's `tidalMetadataStatus` wiring (`MutableStateFlow`/`asStateFlow`/`viewModelScope`/`launch` already imported):

```kotlin
    private val _tidalIdProbeStatus = MutableStateFlow<String?>(null)
    val tidalIdProbeStatus: StateFlow<String?> = _tidalIdProbeStatus.asStateFlow()

    /**
     * Slice-1 gate: prove an internal-v1 track id is the SAME catalog id the
     * official v2 API accepts. Resolves the track's artist/album ids on the
     * official API, then fetches the artist name to make the match
     * human-verifiable. Only names/ids/ISRC in the readout — never a token.
     */
    fun runTidalIdEquivalenceProbe(tidalTrackId: String) {
        viewModelScope.launch {
            val id = tidalTrackId.trim()
            _tidalIdProbeStatus.value = "Resolving track $id on the official v2 API…"
            val repo = Graph.tidalMetadata
            val relations = repo.trackRelations(id)
            val rel = relations.getOrNull()
            if (rel == null) {
                _tidalIdProbeStatus.value =
                    "FAIL: the official API rejected track id $id — ${relations.exceptionOrNull()?.message}. " +
                        "id-equivalence does NOT hold; fall back to ISRC/barcodeId matching."
                return@launch
            }
            val artistName = rel.artistId?.let { repo.artistOverview(it).getOrNull()?.name }
            _tidalIdProbeStatus.value = buildString {
                appendLine("Track id $id resolves on the official v2 API (same catalog id).")
                appendLine("ISRC: ${rel.isrc ?: "—"}")
                appendLine("Artist: ${artistName ?: "—"} (id ${rel.artistId ?: "—"})")
                appendLine("Album id: ${rel.albumId ?: "—"}")
                appendLine(
                    if (rel.artistId != null && rel.albumId != null) {
                        "PASS — the internal-v1 track id is the same catalog id the official v2 API accepts."
                    } else {
                        "PARTIAL — track resolved but a relationship was missing; inspect the ids above."
                    },
                )
            }
        }
    }
```

In `strings.xml`, add beside the `tidal_official_*` keys:

```xml
    <string name="tidal_official_id_probe_label">Tidal track id (internal-v1) for id check</string>
    <string name="tidal_official_id_probe_button">Check id equivalence (official v2)</string>
```

In `TidalOfficialDebugScreen.kt`, below the existing streaming-probe block (before the trailing `status?.let { … }`), add a second section that reads the new flow and calls the new probe (same pattern as the streaming section — the screen already receives `viewModel` directly):

```kotlin
        HorizontalDivider()

        var idProbeTrackId by remember { mutableStateOf("") }
        val idProbeStatus by viewModel.tidalIdProbeStatus.collectAsState()
        OutlinedTextField(
            value = idProbeTrackId,
            onValueChange = { idProbeTrackId = it },
            label = { Text(stringResource(R.string.tidal_official_id_probe_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.runTidalIdEquivalenceProbe(idProbeTrackId) },
            enabled = idProbeTrackId.isNotBlank(),
        ) {
            Text(stringResource(R.string.tidal_official_id_probe_button))
        }
        idProbeStatus?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
```

- [ ] **Step 9: Build + full official-Tidal suite, then the ON-DEVICE GATE**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalMetadata*' --tests '*TidalOfficial*' assembleDebug`
Expected: PASS; APK builds.

Then build the APK **locally** (CI has no Tidal creds) and confirm the gate on-device:
1. Install, log in on the internal-v1 path (Account → Log in to Tidal) and also via Settings → Advanced → "Try official Tidal login (beta)".
2. Play a Tidal track through the normal app (internal-v1); note the numeric id — it is `Track.sourceId`, visible as the tail of the `SOURCE:sourceId` media id in Logcat, or reuse a known `tidal.com/browse/track/<id>` id.
3. In the debug screen, paste that id into "Tidal track id (internal-v1) for id check" → "Check id equivalence".
4. **Gate criterion:** the readout says `PASS`, shows the correct artist name for that track, and non-null artist + album ids. Paste the readout back to close the loop.

If it shows `FAIL`, STOP — id-equivalence does not hold; the plan's ISRC/barcode fallback must be designed before Tasks 3–8.

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataResolution.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/TidalOfficialDebugScreen.kt \
        android/app/src/main/res/values/strings.xml \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataResolutionTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt
git commit -m "feat(android): Tidal id-equivalence gate + track relations resolution

Proves an internal-v1 track id is the same catalog id the official v2 API
accepts, and adds trackRelations (track id -> artist id + album id + isrc)
as the resolution entry point for artist/album enrichment. Metadata only;
streaming stays on internal-v1.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 2: Projection primitives — provided by the Foundation (consume, do not re-declare)

The artwork DTOs (`ArtworkFileMeta`/`ArtworkFile`/`ArtworkAttributes`), the best-fit `pickArtworkUrl`, and the `parseIsoDurationSeconds`/`releaseYearOf` parsers were **promoted into the Slice-0 Foundation** (Foundation Task 4, `provider/tidal/TidalMetadataPrimitives.kt`) because Slice 3 (Discovery) needs the same primitives — declaring them in both slices would be a Kotlin "conflicting declarations" compile error. So this slice has **no code to write here**: it CONSUMES the Foundation's symbols directly (same `io.github.auxen.provider.tidal` package, no import needed), and their behaviour is already unit-tested in the Foundation (`TidalMetadataPrimitivesTest.kt`).

Treated as existing from the Foundation (Task 4):
- `@Serializable ArtworkFileMeta(width, height)`, `@Serializable ArtworkFile(href, meta)` (href nullable — defensive), `@Serializable ArtworkAttributes(files, mediaType)`
- `fun pickArtworkUrl(files: List<ArtworkFile>, targetPx: Int): String?`
- `fun parseIsoDurationSeconds(iso: String?): Double?`
- `fun releaseYearOf(releaseDate: String?): Int?`

Tasks 3–5 below call these directly: `artworkUrlFor` calls `pickArtworkUrl`; `trackFrom` calls `parseIsoDurationSeconds`; `albumSummaryFrom` calls `releaseYearOf` — and so does Task 3's internal-v1 `artistAlbumsFrom`, since v1 renders `releaseDate` in the same "YYYY-MM-DD" shape as v2's `Albums_Attributes.releaseDate`. (The v1 discography does NOT use `pickArtworkUrl`: v1 has no `Artwork_File` list, only a `cover` uuid, so Task 3 builds that URL with its own `tidalV1CoverUrl`.) **No `TidalArtwork.kt` and no `TidalMetadataPrimitives.kt` are created in this slice** (the Foundation owns them). There is no commit for this task — it is a dependency note; the primitives ship with the Foundation, which must merge first.

---

### Task 3: Artist detail — official projection (bio + fallback, similar, art) + the three internal-v1 artist reads

**Read the Architecture section's evidence blocks and *Artist-id resolution* before starting.** They are why this task looks different from Tasks 4–5: the on-device probe falsified `?include=albums` as a discography source and showed the biography does not arrive in `included`; `?include=tracks` is the same arbitrary-page mechanism. This task is built against that evidence.

Four pieces, one commit:
1. **Official v2 artist document** → `TidalArtistDetail` (biography, similar artists, profile art). Both `albums` **and** `tracks` are **dropped from the include** — official's arbitrary first page is dead weight now that the real discography and the real top tracks are each one internal-v1 call away.
2. **Biography fallback.** Use the included bio when present; when `related(artist, "biography")` is empty, fetch `GET /artists/{id}/relationships/biography?include=biography`. Either cause (Tidal omitting the resource from `included`, or a genuinely bio-less artist) ends at the same place, and a bio-less artist renders cleanly as "no bio" — never an error.
3. **The three additive read-only internal-v1 reads** (Global Constraints): `artistAlbums` (discography, mirroring `tidalapi`'s `get_albums(limit=50)`), `artistTopTracks` (mirroring `get_top_tracks(limit=50)`), `searchArtistIdExact` (mirroring the desktop's search-by-name **minus its `artists[0]` fallback**).
4. **The two pure merges** that mirror the desktop: `dedupRemoteAlbums` (local ∪ Tidal discography) and `mergeArtistTracks` (Tidal top tracks first, unique local tracks after).

**The repository constructor is FROZEN**, so `TidalMetadataRepository` cannot reach `TidalProvider` and **must not try**. The official artist document and the internal-v1 reads are fetched *independently* and composed by the ViewModel (Task 6), which already reaches `Graph` directly. That is why `TidalArtistDetail` has **no `albums` and no `tracks` field** — it is exactly the official artist document, nothing more.

**Note on file naming:** the previous revision specced a `TidalArtistAlbums.kt`. It was never built (only Task 1 is merged, at `0d10ed5`), and there are now **three** internal-v1 artist reads rather than one, so it is specced here as **`TidalInternalArtist.kt`** — one coherent home for the pure parts of all three. There is no rename to perform; just create the file under this name.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalArtist.kt` (the pure parts of all three internal-v1 artist reads: DTOs + URL builders + projections)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt` (**the three additive read-only internal-v1 methods**: `artistAlbums`, `artistTopTracks`, `searchArtistIdExact`)
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataDetail.kt` (domain types + shared projection helpers + `artistDetailFrom` + `dedupRemoteAlbums` + `mergeArtistTracks`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt` (add `artistBiographyRequest`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt` (add `getArtistBiography`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt` (add `artistDetail(...)` + `ARTIST_DETAIL_INCLUDE` + `BIOGRAPHY_INCLUDE` + `ARTWORK_TARGET_PX`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (extend the existing Task-1 probe to exercise all three new reads)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalArtistTest.kt` (create)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalArtistDetailTest.kt` (create)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt` (add the biography-relationship shape case)

**Interfaces:**
- Consumes: `TidalDocument`/`TidalDocumentView`/`decodeAttributes` (Foundation, `TidalJsonApi.kt`); `ArtistAttributes`/`ArtistBiographyAttributes`/`AlbumAttributes` (Foundation, `TidalMetadataRepository.kt`); `TrackAttributes` (spike DTO, `TidalOfficialApi.kt`); `pickArtworkUrl`/`ArtworkAttributes`/`parseIsoDurationSeconds`/`releaseYearOf` (Foundation Task 4, `TidalMetadataPrimitives.kt`); `TidalOfficialClient.getArtist`; `TidalProvider`'s **private** `validToken()`/`get(...)`/`json`/`countryCode`/`API_BASE` **and its private nested `SearchResponse`/`TrackDto`/`toTrack()`** (all reachable from inside the class only — which is exactly why the pure parts live in a separate file).
- Produces:
  - `TidalMetadataDetail.kt`: `data class ArtistLink(id, name, artworkUrl)`; `data class AlbumSummary(id, title, albumType, releaseYear, explicit, artworkUrl)`; `data class TidalArtistDetail(id, name, biography, artworkUrl, similarArtists)`; `fun artistDetailFrom(doc, json, artworkTargetPx): TidalArtistDetail?`; `fun biographyTextFrom(doc, json): String?`; `fun dedupRemoteAlbums(localTitles, remote): List<AlbumSummary>`; `fun mergeArtistTracks(topTracks, localTracks): List<Track>`; the shared internal helpers `TidalDocumentView.artworkUrlFor(...)`, `trackFrom(...)`, `albumSummaryFrom(...)`.
  - `TidalInternalArtist.kt`: `fun artistAlbumsUrl(apiBase, artistId, countryCode, limit): HttpUrl`; `fun artistAlbumsFrom(body, json): List<AlbumSummary>`; `fun tidalV1CoverUrl(cover): String?`; `fun artistTopTracksUrl(apiBase, artistId, countryCode, limit): HttpUrl`; `fun artistSearchUrl(apiBase, query, countryCode, limit): HttpUrl`; `fun artistIdFromSearch(body, json, artistName): String?`; the DTOs `TidalV1AlbumArtist`/`TidalV1Album`/`TidalV1AlbumsResponse`/`TidalV1SearchArtist`/`TidalV1ArtistsPage`/`TidalV1SearchResponse`.
  - `TidalProvider.kt`: `suspend fun artistAlbums(artistId: String, limit: Int = 50): List<AlbumSummary>`; `suspend fun artistTopTracks(artistId: String, limit: Int = 50): List<Track>`; `suspend fun searchArtistIdExact(artistName: String, limit: Int = 5): String?`.
  - Official: `TidalOfficialEndpoints.artistBiographyRequest(id, accessToken, include, countryCode): Request`; `TidalOfficialClient.getArtistBiography(id, accessToken, include, countryCode): Result<TidalDocument>`; `TidalMetadataRepository.artistDetail(artistId): Result<TidalArtistDetail>`.
- **Do NOT re-declare** `SearchResponse`, `TrackDto` or a v1 cover-URL builder inside `TidalProvider` — `artistTopTracks` **reuses** the existing private `SearchResponse`/`TrackDto`/`toTrack()`, because `artists/{id}/toptracks` returns the *same* `{"items":[<track>]}` envelope that `/search/tracks` does (verified: `tidalapi/request.py:59-81` `map_request` → `map_json` → `json_obj.get("items")`). A duplicate declaration is a Kotlin compile error and has already bitten this series.
- **Do NOT declare:** `nextCursorFrom` / `TidalDocument.links` / any cursor paging — Slice 1 needs none (`limit=50`, one call), and Slice 2 Task 1 owns those symbols. Re-declaring them is a Kotlin "conflicting declarations" compile error.

- [ ] **Step 1: Write the failing tests — official artist projection + bio fallback + dedup**

Create `TidalArtistDetailTest.kt`. The fixture uses the real relationship names (`profileArt`, `biography`, `similarArtists`) and real attribute names. **`albums` and `tracks` are deliberately absent** — we no longer include either, and the projection must not read them:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalArtistDetailTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val artistDoc = """
        {
          "data": {
            "id": "3456", "type": "artists",
            "attributes": { "name": "Prompto", "popularity": 0.7 },
            "relationships": {
              "profileArt":     { "data": { "id": "art1", "type": "artworks" } },
              "biography":      { "data": { "id": "b1", "type": "artistBiographies" } },
              "similarArtists": { "data": [ { "id": "200", "type": "artists" }, { "id": "201", "type": "artists" } ] }
            }
          },
          "included": [
            { "id": "art1", "type": "artworks", "attributes": {
                "files": [
                  { "href": "https://img/80.jpg",  "meta": { "width": 80,  "height": 80 } },
                  { "href": "https://img/640.jpg", "meta": { "width": 640, "height": 640 } }
                ] } },
            { "id": "b1",  "type": "artistBiographies", "attributes": { "text": "A biography.", "source": "TIDAL" } },
            { "id": "200", "type": "artists", "attributes": { "name": "Trevor Something" } },
            { "id": "201", "type": "artists", "attributes": { "name": "Com Truise" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `artistDetailFrom maps name, biography and best-fit profile art`() {
        val d = artistDetailFrom(json.decodeFromString(artistDoc), json, artworkTargetPx = 640)!!
        assertEquals("3456", d.id)
        assertEquals("Prompto", d.name)
        assertEquals("A biography.", d.biography)
        assertEquals("https://img/640.jpg", d.artworkUrl)
    }

    @Test
    fun `artistDetailFrom maps similar artists`() {
        val d = artistDetailFrom(json.decodeFromString(artistDoc), json, artworkTargetPx = 640)!!
        assertEquals(listOf("Trevor Something", "Com Truise"), d.similarArtists.map { it.name })
        assertEquals(listOf("200", "201"), d.similarArtists.map { it.id })
        // similarArtists' own profileArt is NOT flatly include-able from an artist doc.
        assertTrue(d.similarArtists.all { it.artworkUrl == null })
    }

    @Test
    fun `artistDetailFrom tolerates an artist with no relationships`() {
        val doc = """{ "data": { "id": "5", "type": "artists", "attributes": { "name": "Solo" } }, "included": [] }"""
        val d = artistDetailFrom(json.decodeFromString(doc), json, artworkTargetPx = 640)!!
        assertEquals("Solo", d.name)
        assertNull(d.biography)
        assertNull(d.artworkUrl)
        assertTrue(d.similarArtists.isEmpty())
    }

    @Test
    fun `artistDetailFrom returns null when there is no primary artist`() {
        assertNull(artistDetailFrom(json.decodeFromString("""{ "data": [], "included": [] }"""), json, 640))
    }

    // ---- the biography fallback (falsified assumption A, on-device 2026-07-16) ----

    @Test
    fun `artistDetailFrom leaves biography null when the resource is linked but not included`() {
        // EXACTLY what the real API returned for artist 8405: the relationship
        // linkage is there, the artistBiographies resource is not in `included`.
        // The projection must NOT invent one — the repository fetches the
        // fallback when it sees this null.
        val doc = """
            { "data": { "id": "8405", "type": "artists",
                "attributes": { "name": "Metallica" },
                "relationships": { "biography": { "data": { "id": "b9", "type": "artistBiographies" } } } },
              "included": [] }
        """.trimIndent()
        assertNull(artistDetailFrom(json.decodeFromString(doc), json, 640)!!.biography)
    }

    @Test
    fun `biographyTextFrom reads the bio out of a relationship document`() {
        // GET /artists/{id}/relationships/biography?include=biography — `data` is
        // a bare Resource_Identifier, so the text only exists in `included`.
        val doc = """
            { "data": { "id": "b9", "type": "artistBiographies" },
              "included": [
                { "id": "b9", "type": "artistBiographies",
                  "attributes": { "text": "Formed in Los Angeles in 1981.", "source": "TIDAL", "editable": false } }
              ],
              "links": { "self": "/artists/8405/relationships/biography" } }
        """.trimIndent()
        assertEquals("Formed in Los Angeles in 1981.", biographyTextFrom(json.decodeFromString(doc), json))
    }

    @Test
    fun `biographyTextFrom returns null for an empty or blank relationship document`() {
        // A genuinely bio-less artist: to-one linkage resolves to null. Must read
        // as "no bio", never as an error.
        val empty = """{ "data": null, "included": [], "links": { "self": "/x" } }"""
        assertNull(biographyTextFrom(json.decodeFromString(empty), json))

        val blank = """
            { "data": { "id": "b9", "type": "artistBiographies" },
              "included": [ { "id": "b9", "type": "artistBiographies", "attributes": { "text": "   " } } ],
              "links": { "self": "/x" } }
        """.trimIndent()
        assertNull(biographyTextFrom(json.decodeFromString(blank), json))
    }

    // ---- local ∪ Tidal discography dedup (mirrors desktop window.py:1394-1407) ----

    @Test
    fun `dedupRemoteAlbums drops remote albums whose lowercase title is already local`() {
        val remote = listOf(
            albumSummary("1", "Album One"),
            albumSummary("2", "Ride the Lightning"),
        )
        val kept = dedupRemoteAlbums(localTitles = setOf("album one"), remote = remote)
        assertEquals(listOf("Ride the Lightning"), kept.map { it.title })
    }

    @Test
    fun `dedupRemoteAlbums drops duplicates within the remote list itself, keeping the first`() {
        // The real API returned `Fixxxer (Remastered)` TWICE for artist 8405.
        // Desktop adds to seen_names inside the loop, so the second is dropped.
        val remote = listOf(
            albumSummary("1", "Fixxxer (Remastered)"),
            albumSummary("2", "Fixxxer (Remastered)"),
            albumSummary("3", "FIXXXER (REMASTERED)"),
        )
        assertEquals(listOf("1"), dedupRemoteAlbums(emptySet(), remote).map { it.id })
    }

    @Test
    fun `dedupRemoteAlbums is case-insensitive and preserves remote order`() {
        val remote = listOf(albumSummary("1", "Kill 'Em All"), albumSummary("2", "Reload"))
        assertEquals(
            listOf("Reload"),
            dedupRemoteAlbums(localTitles = setOf("kill 'em all"), remote = remote).map { it.title },
        )
    }

    // ---- top tracks ∪ local tracks (mirrors desktop window.py:1409-1422) ----

    @Test
    fun `mergeArtistTracks puts Tidal top tracks first, then local tracks not already in them`() {
        // Desktop: `tracks = list(tidal_top) + local_extras`. Popularity order
        // leads; the user's own extra files follow.
        val top = listOf(tidalTrack("100", "Enter Sandman"), tidalTrack("200", "One"))
        val local = listOf(tidalTrack("200", "One"), localTrack("f1", "Garage B-Side"))
        val merged = mergeArtistTracks(top, local)
        assertEquals(listOf("Enter Sandman", "One", "Garage B-Side"), merged.map { it.title })
    }

    @Test
    fun `mergeArtistTracks keeps the local list untouched when there are no top tracks`() {
        // Desktop guards with `if tidal_top:` — no top tracks means no merge at
        // all, NOT an empty track list. This is the not-logged-in / unknown-artist
        // / call-failed path, and it must leave the offline base exactly as it is.
        val local = listOf(localTrack("f1", "Only Local"))
        assertEquals(local, mergeArtistTracks(emptyList(), local))
    }

    @Test
    fun `mergeArtistTracks never drops a LOCAL track that shares an id with a Tidal top track`() {
        // DELIBERATE DIVERGENCE from desktop (window.py:1413-1421), which compares
        // `source_id` across sources: a MediaStore id and a Tidal catalog id are
        // both numeric strings, so a bare id compare can silently delete the
        // user's own file. Track identity in this codebase is ALWAYS namespaced by
        // source (ArtistDetailScreen.kt:78/166 key on "${source}:${sourceId}"), so
        // the dedup is scoped to Tidal tracks only.
        val top = listOf(tidalTrack("100", "Enter Sandman"))
        val local = listOf(localTrack("100", "My Own Recording"))
        assertEquals(
            listOf("Enter Sandman", "My Own Recording"),
            mergeArtistTracks(top, local).map { it.title },
        )
    }

    @Test
    fun `mergeArtistTracks yields keys unique by source and id even if Tidal repeats a track`() {
        // The merged list is rendered by a LazyColumn keyed on
        // "${source}:${sourceId}" (ArtistDetailScreen.kt:166). A duplicate key is
        // a hard Compose crash, and `topTracks` does NOT come through the pool's
        // `distinctBy` (ArtistDetailScreen.kt:78) — so the merge must guarantee it.
        val top = listOf(tidalTrack("100", "Enter Sandman"), tidalTrack("100", "Enter Sandman"))
        val merged = mergeArtistTracks(top, emptyList())
        assertEquals(1, merged.size)
        assertEquals(merged.size, merged.distinctBy { "${it.source}:${it.sourceId}" }.size)
    }

    private fun albumSummary(id: String, title: String) =
        AlbumSummary(id = id, title = title, albumType = "ALBUM", releaseYear = 1984, explicit = false, artworkUrl = null)

    private fun tidalTrack(id: String, title: String) =
        Track(title = title, artist = "Metallica", source = Source.TIDAL, sourceId = id)

    private fun localTrack(id: String, title: String) =
        Track(title = title, artist = "Metallica", source = Source.LOCAL, sourceId = id)
}
```

Create `TidalInternalArtistTest.kt` — the three internal-v1 artist reads (request shapes + projections). **The `filter`-absence assertion and the two exact-match assertions are the point of this file**, not niceties:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalInternalArtistTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- 1. the discography: artists/{id}/albums ----

    @Test
    fun `artistAlbumsUrl mirrors tidalapi get_albums - limit, offset, countryCode and NO filter`() {
        val url = artistAlbumsUrl(
            apiBase = "https://api.tidal.com/v1",
            artistId = "8405",
            countryCode = "US",
            limit = 50,
        )
        assertEquals("api.tidal.com", url.host)
        assertEquals("/v1/artists/8405/albums", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
        // THE POINT: tidalapi's Artist.get_albums sends NO filter — that absence
        // selects the endpoint's default view (the real discography). Its
        // siblings send filter=EPSANDSINGLES / filter=COMPILATIONS. If a filter
        // ever appears here, the discography silently becomes the wrong list.
        assertNull(url.queryParameter("filter"))
    }

    @Test
    fun `artistAlbumsFrom projects the v1 items envelope into AlbumSummary cards`() {
        val body = """
            {
              "limit": 50, "offset": 0, "totalNumberOfItems": 2,
              "items": [
                { "id": 186460912, "title": "Hardwired…To Self-Destruct", "cover": "aaaa-bbbb-cccc",
                  "type": "ALBUM", "releaseDate": "2016-11-18", "explicit": true, "numberOfTracks": 12,
                  "artist": { "id": 8405, "name": "Metallica" } },
                { "id": 77680105, "title": "Ride the Lightning", "cover": null,
                  "type": "ALBUM", "releaseDate": "1984-07-27", "numberOfTracks": 8 }
              ]
            }
        """.trimIndent()
        val albums = artistAlbumsFrom(body, json)
        assertEquals(listOf("186460912", "77680105"), albums.map { it.id })
        assertEquals(listOf("Hardwired…To Self-Destruct", "Ride the Lightning"), albums.map { it.title })
        assertEquals(listOf("ALBUM", "ALBUM"), albums.map { it.albumType })
        assertEquals(listOf(2016, 1984), albums.map { it.releaseYear })
        assertEquals(listOf(true, false), albums.map { it.explicit })
        assertEquals("https://resources.tidal.com/images/aaaa/bbbb/cccc/640x640.jpg", albums.first().artworkUrl)
        assertNull(albums[1].artworkUrl)
    }

    @Test
    fun `artistAlbumsFrom keeps COMPILATION - the type v2 cannot express`() {
        // Albums_Attributes.albumType is ALBUM|EP|SINGLE with no COMPILATION,
        // which is precisely why the discography is sourced from v1.
        val body = """{ "items": [ { "id": 1, "title": "Garage Inc.", "type": "COMPILATION" } ] }"""
        assertEquals("COMPILATION", artistAlbumsFrom(body, json).single().albumType)
    }

    @Test
    fun `artistAlbumsFrom returns empty for a malformed or error body`() {
        // A missing discography must never take down the rest of the artist page.
        assertTrue(artistAlbumsFrom("""{ "status": 404, "userMessage": "not found" }""", json).isEmpty())
        assertTrue(artistAlbumsFrom("not json at all", json).isEmpty())
    }

    @Test
    fun `tidalV1CoverUrl converts a cover uuid to the CDN path, tolerating null and blank`() {
        assertEquals(
            "https://resources.tidal.com/images/1e01/cdcc/9d3c/640x640.jpg",
            tidalV1CoverUrl("1e01-cdcc-9d3c"),
        )
        assertNull(tidalV1CoverUrl(null))
        assertNull(tidalV1CoverUrl(""))
    }

    // ---- 2. the top tracks: artists/{id}/toptracks ----

    @Test
    fun `artistTopTracksUrl mirrors tidalapi get_top_tracks - limit, offset, countryCode`() {
        val url = artistTopTracksUrl(
            apiBase = "https://api.tidal.com/v1",
            artistId = "8405",
            countryCode = "US",
            limit = 50,
        )
        assertEquals("api.tidal.com", url.host)
        // `toptracks`, one word, lowercase — tidalapi/artist.py:190.
        assertEquals("/v1/artists/8405/toptracks", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
        // get_top_tracks sends {limit, offset} only — no filter, no mode.
        assertNull(url.queryParameter("filter"))
    }

    // ---- 3. artist id by exact name: search?types=artists ----

    @Test
    fun `artistSearchUrl mirrors the desktop's proven session-search call`() {
        val url = artistSearchUrl(
            apiBase = "https://api.tidal.com/v1",
            query = "Trevor Something",
            countryCode = "US",
            limit = 5,
        )
        assertEquals("api.tidal.com", url.host)
        // `GET search` — the multi-type endpoint tidalapi's session.search uses
        // (session.py:807). NOT `/search/artists`: nobody has verified such a
        // sibling exists, and the provider's existing track search hits
        // `/search/tracks`, a differently-shaped endpoint.
        assertEquals("/v1/search", url.encodedPath)
        assertEquals("Trevor Something", url.queryParameter("query"))
        // LOWERCASE "artists". tidalapi builds this via convert_type(Artist,
        // "type"), whose Case enum aliases pascal/scream/lower onto ONE member
        // (session.py:187-190), so the `case == Case.scream` branch always fires
        // and always lowercases. Verified by executing it: it emits "artists".
        // This exact string is what the desktop sends daily; "ARTISTS" is not.
        assertEquals("artists", url.queryParameter("types"))
        assertEquals("5", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
    }

    private val artistSearchBody = """
        {
          "artists": {
            "limit": 5, "offset": 0, "totalNumberOfItems": 3,
            "items": [
              { "id": 111, "name": "Trevor Something Does Not Exist", "picture": "aaaa-bbbb-cccc" },
              { "id": 222, "name": "Trevor Something",                "picture": "dddd-eeee-ffff" },
              { "id": 333, "name": "Trevor Something Jr",             "picture": null }
            ]
          },
          "albums":    { "items": [] },
          "tracks":    { "items": [] },
          "videos":    { "items": [] },
          "playlists": { "items": [] }
        }
    """.trimIndent()

    @Test
    fun `artistIdFromSearch takes the EXACT name match, not the top-ranked result`() {
        // The exact match is 2nd; a "best match" heuristic would take 111 and hang
        // a different artist's bio, discography and top tracks on the page.
        assertEquals("222", artistIdFromSearch(artistSearchBody, json, "Trevor Something"))
    }

    @Test
    fun `artistIdFromSearch matches case-insensitively`() {
        assertEquals("222", artistIdFromSearch(artistSearchBody, json, "trevor something"))
        assertEquals("222", artistIdFromSearch(artistSearchBody, json, "TREVOR SOMETHING"))
    }

    @Test
    fun `artistIdFromSearch returns NULL on a near miss - it must never fall back to artists zero`() {
        // THE POINT OF THIS FILE. The desktop does `best = artists[0]` here
        // (providers/tidal.py:874-876) and thereby attaches the WRONG artist.
        // We resolve null and the page renders exactly as it does today.
        // If this assertion ever flips to expecting "111", the bug is back.
        assertNull(artistIdFromSearch(artistSearchBody, json, "Trevor Someth"))
        assertNull(artistIdFromSearch(artistSearchBody, json, "Trevor"))
        assertNull(artistIdFromSearch(artistSearchBody, json, "Com Truise"))
    }

    @Test
    fun `artistIdFromSearch returns null for empty results, a malformed body or an error envelope`() {
        assertNull(artistIdFromSearch("""{ "artists": { "items": [] } }""", json, "Anybody"))
        assertNull(artistIdFromSearch("""{ "status": 401, "userMessage": "unauthorized" }""", json, "Anybody"))
        assertNull(artistIdFromSearch("not json at all", json, "Anybody"))
    }
}
```

Add to `TidalOfficialEndpointsTest.kt`:

```kotlin
    @Test
    fun `artist biography relationship request carries the include and country code`() {
        val request = TidalOfficialEndpoints.artistBiographyRequest(
            id = "8405", accessToken = "tok-123",
            include = listOf("biography"), countryCode = "US",
        )
        val url = request.url
        assertEquals("/v2/artists/8405/relationships/biography", url.encodedPath)
        // Without ?include=biography the response carries only a {type,id}
        // identifier and no bio text — the include is load-bearing, not cosmetic.
        assertEquals(listOf("biography"), url.queryParameterValues("include"))
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals("Bearer tok-123", request.header("Authorization"))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalArtistDetailTest*' --tests '*TidalInternalArtistTest*' --tests '*TidalOfficialEndpointsTest*'`

Expected: **FAIL — compilation errors, not assertion failures.** Every symbol under test is net-new (swept: zero hits repo-wide), and neither `TidalInternalArtist.kt` nor `TidalMetadataDetail.kt` exists yet, so Kotlin cannot resolve:
- `TidalArtistDetailTest` → `artistDetailFrom`, `biographyTextFrom`, `dedupRemoteAlbums`, `mergeArtistTracks`, `TidalArtistDetail`, `AlbumSummary`, `ArtistLink`
- `TidalInternalArtistTest` → `artistAlbumsUrl`, `artistAlbumsFrom`, `tidalV1CoverUrl`, `artistTopTracksUrl`, `artistSearchUrl`, `artistIdFromSearch`
- `TidalOfficialEndpointsTest` → `TidalOfficialEndpoints.artistBiographyRequest`

The compiler reports `Unresolved reference` for each. That is the honest RED for a task whose files do not exist — do not mistake it for a broken build.

- [ ] **Step 3: Create `TidalInternalArtist.kt` — the three internal-v1 artist reads (pure parts)**

Everything testable about the internal-v1 calls lives here, so `TidalProvider`'s own additions (Step 4) stay four-line fetches. `AlbumSummary` is declared in `TidalMetadataDetail.kt` (Step 6, same package — no import needed); the two files land in the same commit.

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The pure parts of the artist reads that live on the INTERNAL v1 API
 * (`api.tidal.com/v1`) — the three deliberate internal-v1 additions in this
 * slice (see Global Constraints): the **discography**, the **top tracks**, and
 * **artist id by exact name**.
 *
 * Why not official v2: probed on-device 2026-07-16, `GET /artists/8405?include=albums`
 * returned 20 *Fixxxer* variants (rough mixes, a vocal idea, `Fixxxer (Remastered)`
 * twice) — an arbitrary first page, not a discography. `?include=tracks` is the same
 * mechanism on the same document, so it is not a top-tracks list either. And v2
 * cannot express the desktop's discography anyway: `Albums_Attributes.albumType` is
 * `ALBUM | EP | SINGLE` with no COMPILATION, and `/artists/{id}/relationships/albums`
 * has no filter param.
 *
 * These builders are pure — they take [apiBase] rather than reading
 * `TidalProvider.API_BASE` (which is in a *private* companion) so every request
 * SHAPE is unit-testable with no live call, exactly like `TidalOfficialEndpoints`.
 *
 * Field names are verbatim from what `tidalapi.album.Album.parse` reads
 * (`tidalapi/album.py:114-145`): `id`, `title`, `cover`, `type`, `releaseDate`,
 * `explicit`, `numberOfTracks`. Every field but `id` is nullable/defaulted —
 * same tolerance as every other DTO in this package.
 */
@Serializable
data class TidalV1AlbumArtist(val id: Long? = null, val name: String = "")

@Serializable
data class TidalV1Album(
    val id: Long,
    val title: String = "",
    val cover: String? = null,
    /** v1's album type — `ALBUM` | `EP` | `SINGLE` | `COMPILATION`. Note COMPILATION, which official v2 cannot express. */
    val type: String? = null,
    val releaseDate: String? = null,
    val explicit: Boolean? = null,
    val numberOfTracks: Int? = null,
    val artist: TidalV1AlbumArtist? = null,
)

/** The v1 paged-collection envelope: `{ "limit", "offset", "totalNumberOfItems", "items": [...] }`. */
@Serializable
data class TidalV1AlbumsResponse(val items: List<TidalV1Album> = emptyList())

/**
 * `GET {apiBase}/artists/{artistId}/albums?limit=…&offset=0&countryCode=…`.
 *
 * Mirrors `tidalapi`'s `Artist.get_albums(limit=50)`, which sends ONLY
 * `{limit, offset}` (`tidalapi/artist.py:131-137`) on top of the `countryCode`
 * its `basic_request` attaches to every v1 call (`tidalapi/request.py:76`) —
 * which is also what this provider's own `search` already sends.
 *
 * **The ABSENCE of `filter` is the whole point**: it selects the endpoint's
 * default view, the real discography. `filter=EPSANDSINGLES` and
 * `filter=COMPILATIONS` are its siblings (`get_ep_singles` / `get_other`,
 * `tidalapi/artist.py:150-177`) and must NOT be sent here.
 * `TidalInternalArtistTest` asserts the absence.
 */
fun artistAlbumsUrl(apiBase: String, artistId: String, countryCode: String, limit: Int = 50): HttpUrl =
    "$apiBase/artists/$artistId/albums".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", "0")
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * `GET {apiBase}/artists/{artistId}/toptracks?limit=…&offset=0&countryCode=…`
 * — the artist's tracks **sorted by popularity**.
 *
 * Mirrors `tidalapi`'s `Artist.get_top_tracks(limit=50)`, which sends exactly
 * `{limit, offset}` to `artists/{id}/toptracks` (`tidalapi/artist.py:179-194`).
 * Its docstring is the guarantee we are buying: *"Queries TIDAL for the artists
 * tracks, sorted by popularity."* The desktop calls it at
 * `auxen/providers/tidal.py:906` and renders the result as the artist's top
 * tracks; this is the same request.
 *
 * This exists because official v2's `?include=tracks` is an arbitrary first
 * page in popularity-agnostic order — the same trap `?include=albums` was
 * proven to be. Do not "simplify" the artist page back onto the include.
 */
fun artistTopTracksUrl(apiBase: String, artistId: String, countryCode: String, limit: Int = 50): HttpUrl =
    "$apiBase/artists/$artistId/toptracks".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", "0")
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * `GET {apiBase}/search?query=…&limit=…&offset=0&types=artists&countryCode=…`
 * — resolve an artist id from a name, for artists we hold no Tidal track for.
 *
 * Mirrors the call the desktop makes daily: `session.search(artist_name,
 * models=[tidalapi.Artist], limit=5)` (`auxen/providers/tidal.py:860-862`),
 * which issues **`GET search`** with `{query, limit, offset, types}`
 * (`tidalapi/session.py:800-807`) plus the `countryCode` that `basic_request`
 * attaches to every v1 call (`tidalapi/request.py:76`).
 *
 * **`types` is the lowercase plural `artists`.** tidalapi derives it from
 * `convert_type(Artist, "type")`, and its `Case` enum assigns `pascal`/`scream`/
 * `lower` all the same value (`id`), which makes them **aliases for one member**
 * (`tidalapi/session.py:187-190`) — so the `case == Case.scream` branch always
 * matches and always lowercases. Executed to confirm: it emits `"artists"`.
 * That is the proven string; `"ARTISTS"` is not, and nobody has verified the
 * endpoint accepts it.
 *
 * **`GET search`, not `/search/artists`.** The provider's existing track search
 * hits `/search/tracks`, which answers a bare `{"items":[...]}`. The multi-type
 * `search` endpoint answers a per-type envelope instead (see
 * [artistIdFromSearch]). An artist sibling of `/search/tracks` may or may not
 * exist — nobody has checked, so we send the request that is known to work.
 *
 * **[limit] is 20, deliberately NOT the desktop's 5.** The desktop can afford 5
 * because it falls back to `artists[0]` regardless of ranking — the limit never
 * has to contain the right answer. Dropping that fallback makes the limit
 * load-bearing: an exact match ranked below the cut resolves **null** and the
 * page renders un-enriched. 20 costs one marginally larger response and makes
 * that miss far less likely. The residual trade-off stands and is the one this
 * decision prefers: a miss, never a wrong artist.
 */
fun artistSearchUrl(apiBase: String, query: String, countryCode: String, limit: Int = 20): HttpUrl =
    "$apiBase/search".toHttpUrl().newBuilder()
        .addQueryParameter("query", query)
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", "0")
        .addQueryParameter("types", "artists")
        .addQueryParameter("countryCode", countryCode)
        .build()

/**
 * v1 cover uuid → CDN URL, the `resources.tidal.com/images/{uuid, dashes → slashes}/640x640.jpg`
 * pattern the desktop uses (`_album_to_dict`, `auxen/providers/tidal.py:354-361`).
 *
 * Deliberately duplicated rather than refactored out of [TidalProvider]'s
 * `toTrack`, which builds the same URL inline: this slice adds to the
 * internal-v1 provider but **changes no existing method of it** (Global
 * Constraints). Fold the two together in a later slice that is allowed to.
 */
fun tidalV1CoverUrl(cover: String?): String? =
    cover?.takeIf { it.isNotBlank() }
        ?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg" }

/**
 * Pure projection: an `artists/{id}/albums` v1 response body → the same
 * [AlbumSummary] cards the official-v2 projections produce, so the artist
 * discography row is ONE uniform type regardless of which API filled it.
 *
 * A malformed body — a v1 error envelope, an HTML error page, anything — yields
 * an empty list rather than throwing: a missing discography must never take
 * down the rest of the artist page (the desktop swallows the same failure at
 * `providers/tidal.py:899-900`).
 */
fun artistAlbumsFrom(body: String, json: Json): List<AlbumSummary> =
    runCatching { json.decodeFromString<TidalV1AlbumsResponse>(body) }.getOrNull()
        ?.items
        ?.map { it.toAlbumSummary() }
        .orEmpty()

private fun TidalV1Album.toAlbumSummary() = AlbumSummary(
    id = id.toString(),
    title = title,
    albumType = type,
    // v1 releaseDate is "YYYY-MM-DD", the same shape releaseYearOf already parses
    // for v2's Albums_Attributes.releaseDate. (The desktop leaves the year null
    // on Tidal album cards; v1 gives us the real one, and the Android AlbumCard
    // does not render a year either way — so this is strictly better and inert.)
    releaseYear = releaseYearOf(releaseDate),
    explicit = explicit ?: false,
    artworkUrl = tidalV1CoverUrl(cover),
)

/**
 * A search hit's artist. Fields verbatim from what `tidalapi`'s `parse_artist`
 * reads (`tidalapi/artist.py:83-98`): `id`, `name`, `picture`. We need only the
 * first two — `picture` is deliberately not projected, because the artist's
 * profile art comes from the official v2 document once the id is resolved.
 */
@Serializable
data class TidalV1SearchArtist(val id: Long, val name: String = "")

/** One per-type page inside the `search` response: `{ "items": [...], "limit", "offset", "totalNumberOfItems" }`. */
@Serializable
data class TidalV1ArtistsPage(val items: List<TidalV1SearchArtist> = emptyList())

/**
 * The `GET search` response envelope. Unlike `/search/tracks`' bare
 * `{"items":[...]}`, this endpoint nests each requested type under its own key:
 * `{"artists": {"items": [...]}, "albums": {...}, "tracks": {...}, …}` —
 * exactly how `tidalapi` reads it (`json_obj["artists"]` →
 * `map_json(...)["items"]`, `session.py:807-813` + `request.py:217`).
 *
 * Only `artists` is declared; `Json { ignoreUnknownKeys = true }` drops the
 * rest, and the default keeps a missing key from throwing.
 */
@Serializable
data class TidalV1SearchResponse(val artists: TidalV1ArtistsPage = TidalV1ArtistsPage())

/**
 * Pure projection: a `search?types=artists` body + the name we searched for →
 * that artist's catalog id, or **null**.
 *
 * **EXACT case-insensitive name match ONLY. There is deliberately no
 * best-match fallback, and adding one would be a regression.** The desktop does
 * `best = artists[0]` when nothing matches exactly
 * (`auxen/providers/tidal.py:874-876`), which silently attaches a *different*
 * artist's biography, discography and top tracks to the page — the user's own
 * desktop exhibits this today (artist images resolve for some artists and not
 * others). We are **fixing** that here, not porting it. A null means the artist
 * page renders exactly what it renders today, which is strictly better than a
 * confident wrong answer.
 *
 * `lowercase()` on both sides mirrors Python's `a_name.lower() ==
 * artist_name.lower()` and is `Locale.ROOT`, keeping the match independent of
 * device locale — the same reasoning as [dedupRemoteAlbums].
 *
 * A malformed body (a v1 error envelope, an HTML error page, anything) yields
 * null rather than throwing: a failed id resolution must degrade the artist
 * page, never break it.
 */
fun artistIdFromSearch(body: String, json: Json, artistName: String): String? {
    val wanted = artistName.lowercase()
    return runCatching { json.decodeFromString<TidalV1SearchResponse>(body) }.getOrNull()
        ?.artists?.items
        ?.firstOrNull { it.name.lowercase() == wanted }
        ?.id?.toString()
}
```

- [ ] **Step 4: Add the THREE additive internal-v1 methods to `TidalProvider.kt`**

This is the **only** edit to `TidalProvider.kt` in the entire slice. It ADDS three methods and changes nothing else — no existing method, no DTO, no signature, no session state. Add them in the `MusicProvider` section, immediately after `getStreamInfoById` and before the `// Internals` banner.

**No new imports are needed**, and this has been checked rather than assumed:
- `AlbumSummary`/`artistAlbumsUrl`/`artistAlbumsFrom`/`artistTopTracksUrl`/`artistSearchUrl`/`artistIdFromSearch` are same-package (`io.github.auxen.provider.tidal`).
- `json`/`validToken`/`get`/`countryCode`/`API_BASE` and the private nested `SearchResponse`/`TrackDto`/`toTrack()` are the class's own members.
- `Track` is already imported (`TidalProvider.kt:5`); `kotlinx.serialization.decodeFromString` is already imported (line 13).
- `HttpUrl` is never *named* here — the builders return it and we call `.toString()` — so no `okhttp3.HttpUrl` import is required.

```kotlin
    /**
     * The artist's discography — a read-only catalog GET, mirroring the
     * desktop's `best.get_albums(limit=50)` (`auxen/providers/tidal.py:896`).
     * Metadata only: it resolves no stream and touches no session state.
     *
     * This lives on internal-v1 because official v2 provably cannot reproduce
     * this view (see [artistAlbumsUrl]'s KDoc and the plan's Architecture).
     * [artistId] is a Tidal catalog artist id — the same id official v2 uses.
     *
     * Returns an empty list when not logged in or when the call/parse fails,
     * matching [search]'s not-logged-in behaviour and the desktop's swallow at
     * `providers/tidal.py:899-900`: a missing discography degrades the artist
     * page, it must never break it.
     */
    suspend fun artistAlbums(artistId: String, limit: Int = 50): List<AlbumSummary> {
        val token = validToken() ?: return emptyList()
        val url = artistAlbumsUrl(API_BASE, artistId, countryCode, limit)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return emptyList()
        return artistAlbumsFrom(body, json)
    }

    /**
     * The artist's tracks **sorted by popularity** — a read-only catalog GET,
     * mirroring the desktop's `best.get_top_tracks(limit=50)`
     * (`auxen/providers/tidal.py:906`). Metadata only: it resolves no stream and
     * touches no session state.
     *
     * `artists/{id}/toptracks` answers the SAME `{"items":[<track>]}` envelope
     * `/search/tracks` does (`tidalapi/request.py:59-81`: `map_request` →
     * `map_json` → `json_obj.get("items")`), so this deliberately **reuses** the
     * existing private [SearchResponse]/[TrackDto]/[toTrack] rather than
     * declaring a parallel set — a duplicate DTO would be a conflicting
     * declaration, and re-mapping the same fields twice would let the two drift.
     * ([SearchResponse] is named for its first caller; it is really just this
     * package's v1 track-collection envelope. Renaming it would EDIT an existing
     * declaration, which the Global Constraints forbid.)
     *
     * [artistId] is a Tidal catalog artist id — the same id official v2 uses.
     * Returns an empty list when not logged in or when the call/parse fails; the
     * artist page then falls back to the local track list, exactly as the desktop
     * does when its own call throws (`providers/tidal.py:908-909`).
     */
    suspend fun artistTopTracks(artistId: String, limit: Int = 50): List<Track> {
        val token = validToken() ?: return emptyList()
        val url = artistTopTracksUrl(API_BASE, artistId, countryCode, limit)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return emptyList()
        return runCatching {
            json.decodeFromString<SearchResponse>(body).items.map { it.toTrack() }
        }.getOrDefault(emptyList())
    }

    /**
     * Resolve an artist's catalog id from their NAME, for artists we hold no
     * Tidal track for — a read-only catalog GET on the internal-v1 **search**
     * path, where search has always belonged (see Global Constraints).
     *
     * Mirrors the desktop's `session.search(artist_name, models=[Artist],
     * limit=5)` (`auxen/providers/tidal.py:860-862`) — **minus its `artists[0]`
     * fallback**, which is a bug we are deliberately not porting. [artistIdFromSearch]
     * requires an exact case-insensitive name match and otherwise returns null;
     * see its KDoc and the plan's *Artist-id resolution*.
     *
     * [limit] is 20 rather than the desktop's 5 precisely because that fallback
     * is gone — see [artistSearchUrl]'s KDoc.
     *
     * Returns null when not logged in, when the call/parse fails, or when no
     * exact match exists. Every one of those means the same thing to the caller:
     * no remote enrichment, page renders as today.
     */
    suspend fun searchArtistIdExact(artistName: String, limit: Int = 20): String? {
        if (artistName.isBlank()) return null
        val token = validToken() ?: return null
        val url = artistSearchUrl(API_BASE, artistName, countryCode, limit)
        val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return null
        return artistIdFromSearch(body, json, artistName)
    }
```

- [ ] **Step 5: Add the biography-relationship endpoint + client method**

In `TidalOfficialEndpoints.kt`, add after `artistRequest` (`toHttpUrl`/`Request`/`authed`/`BASE` are all already in the file):

```kotlin
    /**
     * `GET /artists/{id}/relationships/biography` — the biography fallback.
     *
     * Probed on-device 2026-07-16: `GET /artists/8405?include=biography` came
     * back with an EMPTY biography, so the bio does not reliably arrive in the
     * artist document's `included`. This endpoint is the direct fetch. Per the
     * spec its params are `id`, `countryCode`, `include` (example: `biography`),
     * and it answers `Artists_Single_Relationship_Data_Document` — `data` is a
     * bare `{type,id}` identifier, so **`?include=biography` is load-bearing**:
     * without it the response carries no bio text at all.
     */
    fun artistBiographyRequest(
        id: String,
        accessToken: String,
        include: List<String> = emptyList(),
        countryCode: String? = null,
    ): Request {
        val url = "$BASE/artists/$id/relationships/biography".toHttpUrl().newBuilder()
            .apply { countryCode?.let { addQueryParameter("countryCode", it) } }
            .apply { include.forEach { addQueryParameter("include", it) } }
            .build()
        return authed(url, accessToken)
    }
```

In `TidalOfficialClient.kt`, add beside `getArtist`:

```kotlin
    /** Fetch an artist's biography relationship document (see [TidalOfficialEndpoints.artistBiographyRequest]). */
    suspend fun getArtistBiography(
        id: String,
        accessToken: String,
        include: List<String>,
        countryCode: String? = null,
    ): Result<TidalDocument> = execute(TidalOfficialEndpoints.artistBiographyRequest(id, accessToken, include, countryCode))
```

- [ ] **Step 6: Create `TidalMetadataDetail.kt` with the domain types + shared helpers + `artistDetailFrom`**

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import io.github.auxen.model.Track
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
```

- [ ] **Step 7: Add `artistDetail` (with the bio fallback) + constants to `TidalMetadataRepository.kt`**

Method, beside `artistOverview`. **Do not touch the constructor** — it is frozen, and this method deliberately does not reach `TidalProvider` for the discography (the ViewModel composes that in Task 6):

```kotlin
    /**
     * The official-v2 artist document: biography, similar artists, profile art.
     * **Not the discography and not the top tracks** — those are internal-v1
     * [TidalProvider.artistAlbums] / [TidalProvider.artistTopTracks], composed
     * alongside this by the ViewModel (this class's constructor is frozen and
     * holds no internal-v1 provider).
     *
     * When the artist document carries no biography, ONE extra fetch of
     * `/artists/{id}/relationships/biography?include=biography` fills it. That
     * covers both known causes at once — Tidal omitting the resource from
     * `included` (observed for artist 8405 on 2026-07-16) and an artist who
     * genuinely has no bio — without needing to tell them apart.
     *
     * The fallback is TOTAL: `getOrNull()` swallows a 404, a network error, and
     * a `data`-less relationship document alike, all landing on `biography =
     * null` → the UI hides the section. A bio-less artist must render cleanly,
     * never as an error, and the rest of the detail must survive a bio failure.
     * This mirrors the desktop, which wraps `get_bio()` in a bare `except`
     * ("404s are expected for lesser-known artists", `providers/tidal.py:912-915`).
     */
    suspend fun artistDetail(artistId: String): Result<TidalArtistDetail> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))
        val detail = client.getArtist(artistId, token, ARTIST_DETAIL_INCLUDE, countryCode).mapCatching { doc ->
            artistDetailFrom(doc, json, ARTWORK_TARGET_PX) ?: error("Artist $artistId not found")
        }
        val projected = detail.getOrNull() ?: return detail
        if (projected.biography != null) return detail
        val bio = client.getArtistBiography(artistId, token, BIOGRAPHY_INCLUDE, countryCode)
            .getOrNull()
            ?.let { biographyTextFrom(it, json) }
        return Result.success(projected.copy(biography = bio))
    }
```

Companion additions:

```kotlin
        // "albums" AND "tracks" are deliberately ABSENT. Probed on-device
        // 2026-07-16, ?include=albums returns an arbitrary first page (20
        // Fixxxer variants for artist 8405), not a discography; ?include=tracks
        // is the same mechanism on the same document, so it is not a
        // popularity-ordered top-tracks list either. Both come from internal-v1
        // instead: TidalProvider.artistAlbums / TidalProvider.artistTopTracks.
        // Re-adding either include here re-introduces the bug AND costs a
        // bigger response for data nothing reads — don't.
        //
        // NOTE: this is Slice 1's own constant. The Foundation's ARTIST_INCLUDE
        // (= biography, similarArtists, albums) is a DIFFERENT, merged constant
        // used by artistOverview() and the Task-1 probe readout. Leave it alone.
        val ARTIST_DETAIL_INCLUDE = listOf("biography", "similarArtists", "profileArt")
        /** The relationship endpoint's own include — without it the document has a `{type,id}` and no bio text. */
        val BIOGRAPHY_INCLUDE = listOf("biography")
        const val ARTWORK_TARGET_PX = 640
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalArtistDetailTest*' --tests '*TidalInternalArtistTest*' --tests '*TidalOfficialEndpointsTest*'`

Expected: PASS — all cases in both new files, plus the unchanged existing endpoint cases.

- [ ] **Step 9: Extend the Task-1 probe to exercise all three internal-v1 reads, then run it ON-DEVICE**

**Why this step exists.** Task 1's gate proved *internal-v1 track id → official v2 track endpoint*. Task 3 relies on the **reverse** direction, which the gate did not test: the artist id comes back from official v2 (`trackRelations`), and Step 4 sends it to *internal-v1* artist endpoints. Both are Tidal catalog ids and the probe's `Artist: Metallica (id 8405)` is consistent with them matching — but "consistent with" is not "proven", and the same shrug is exactly what let `?include=albums` reach a plan as a discography. None of the three new endpoints has ever been called from this app. This is ~30 lines against an already-built debug section, it fails loudly (empty list / null) rather than silently, and it gives the first real look at whether the v1 default views are what we think they are.

It probes four things:
1. the official-v2 artist id is accepted by **internal-v1** (`artistAlbums`);
2. `toptracks` returns a list that **reads like popularity order** (eyeball it);
3. an **exact-match** artist search resolves the artist by name;
4. a **deliberate near-miss** resolves to **nothing** rather than the wrong artist — the assertion that Decision 4's whole value rests on.

In `PlayerViewModel.kt`, extend the existing `runTidalIdEquivalenceProbe` (it already resolves `rel.artistId` and holds `repo`). Replace its final `_tidalIdProbeStatus.value = buildString { … }` block with:

```kotlin
            // Slice 1 Task 3: prove the artist id official-v2 just handed us is
            // ALSO accepted by internal-v1, which is where the discography, the
            // top tracks and the by-name id resolution all come from (official
            // v2's ?include= returns an arbitrary page). Names/titles/ids only —
            // never a token, never a full request URL.
            val artistId = rel.artistId
            val discography = artistId?.let { Graph.tidal.artistAlbums(it) }.orEmpty()
            val top = artistId?.let { Graph.tidal.artistTopTracks(it) }.orEmpty()
            // Decision 4, both directions: the real name must resolve, and a
            // near-miss must resolve to NOTHING rather than to a nearby artist.
            val exactHit = artistName?.let { Graph.tidal.searchArtistIdExact(it) }
            val nearMissName = artistName?.let { "$it Zzz" }
            val nearMissHit = nearMissName?.let { Graph.tidal.searchArtistIdExact(it) }
            _tidalIdProbeStatus.value = buildString {
                appendLine("Track id $id resolves on the official v2 API (same catalog id).")
                appendLine("ISRC: ${rel.isrc ?: "—"}")
                appendLine("Artist: ${artistName ?: "—"} (id ${artistId ?: "—"})")
                appendLine("Album id: ${rel.albumId ?: "—"}")
                appendLine(
                    if (artistId != null && rel.albumId != null) {
                        "PASS — the internal-v1 track id is the same catalog id the official v2 API accepts."
                    } else {
                        "PARTIAL — track resolved but a relationship was missing; inspect the ids above."
                    },
                )
                appendLine()
                appendLine("Discography (internal-v1 artists/$artistId/albums?limit=50): ${discography.size}")
                discography.take(8).forEach { appendLine("  • ${it.title} (${it.albumType ?: "—"}, ${it.releaseYear ?: "—"})") }
                appendLine(
                    if (discography.isEmpty()) {
                        "FAIL — internal-v1 returned no albums for artist id $artistId. Either the " +
                            "official-v2 artist id is NOT an internal-v1 artist id, or you are not logged in " +
                            "on the internal-v1 path (Account → Log in to Tidal). Task 3's discography " +
                            "depends on this — do not build Task 6 until it passes."
                    } else {
                        "PASS — the official-v2 artist id is accepted by internal-v1."
                    },
                )
                appendLine()
                appendLine("Top tracks (internal-v1 artists/$artistId/toptracks?limit=50): ${top.size}")
                top.take(8).forEach { appendLine("  • ${it.title}") }
                appendLine(
                    if (top.isEmpty()) {
                        "FAIL — internal-v1 returned no top tracks for artist id $artistId. Task 3's " +
                            "top-tracks section depends on this — do not build Task 6 until it passes."
                    } else {
                        "PASS — toptracks answered. EYEBALL THE ORDER: it must read like the artist's " +
                            "best-known songs, not an arbitrary page of remixes/B-sides."
                    },
                )
                appendLine()
                appendLine("Exact-match search for \"${artistName ?: "—"}\": id ${exactHit ?: "null"}")
                appendLine("Near-miss search for \"${nearMissName ?: "—"}\": id ${nearMissHit ?: "null"}")
                appendLine(
                    when {
                        exactHit == null ->
                            "FAIL — the exact name did not resolve. Decision 4's fallback would never fire; " +
                                "check the internal-v1 login and the `types=artists` param."
                        nearMissHit != null ->
                            "FAIL — a NEAR-MISS resolved to id $nearMissHit. artistIdFromSearch is matching " +
                                "fuzzily; it must require an exact case-insensitive name match. This is the " +
                                "desktop bug we are here to NOT ship."
                        exactHit != artistId ->
                            "PARTIAL — the exact name resolved to $exactHit but the track's artist id is " +
                                "$artistId. Two artists share this name; inspect before relying on the fallback."
                        else -> "PASS — exact name resolves to the same artist id; a near-miss resolves to nothing."
                    },
                )
            }
```

`Graph` is already imported in this file (the probe uses `Graph.tidalMetadata`); `Graph.tidal` is the existing `TidalProvider`, and it is public (`AuxenApp.kt`). No new imports, no new strings, no debug-screen change — the section built in Task 1 renders this readout as-is.

**Then run it on-device** (build the APK **locally** — CI has no Tidal creds, so a CI APK's Tidal login fails):

```
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android assembleDebug
```

1. Install; log in on **both** paths (Account → Log in to Tidal **and** Settings → Advanced → "Try official Tidal login (beta)"). The internal-v1 login is what all three new reads need — without it they come back empty for a reason that has nothing to do with ids.
2. Debug screen → paste a known Tidal track id (e.g. `186460926`, the gate's Metallica track) → "Check id equivalence".
3. **Gate criteria — all four lines must say PASS:**
   - `PASS — the internal-v1 track id is the same catalog id…` (the original)
   - `PASS — the official-v2 artist id is accepted by internal-v1.`
   - `PASS — toptracks answered.…`
   - `PASS — exact name resolves to the same artist id; a near-miss resolves to nothing.`
4. **Eyeball the discography list.** For Metallica it must read like a discography (`Hardwired…To Self-Destruct`, `Ride the Lightning`, `Master of Puppets`, …) — **not** 20 *Fixxxer* variants. If it comes back looking like the official-v2 page did, the `filter`-free v1 default view is not what the desktop gets either, and Task 6 must not be built on it: stop and report.
5. **Eyeball the top-tracks order.** For Metallica it must read like the popular songs (`Enter Sandman`, `Nothing Else Matters`, `Master of Puppets`, `One`, …). If it reads like an arbitrary page of remixes and B-sides, then `toptracks` is not delivering popularity order for us and the section must not be labelled "Top tracks" in Task 6 — stop and report.

A `PARTIAL` on the search line is not automatically a failure — it means two artists share the name, which is exactly the ambiguity the exact-match rule exists for. Note it and continue; it only matters for artists with no local Tidal track.

Paste the readout back to close the loop.

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalInternalArtist.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataDetail.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalInternalArtistTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalArtistDetailTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt
git commit -m "feat(android): Tidal artist detail — official bio/similar/art + internal-v1 lists

artistDetailFrom projects a compound /artists/{id} document into
TidalArtistDetail (biography, similar artists, profile art). When the bio
is absent from 'included' — which the on-device probe showed is the norm —
artistDetail() falls back to /artists/{id}/relationships/biography, and a
bio-less artist reads as 'no bio', never an error.

The artist's three list surfaces come from additive read-only internal-v1
calls, because official v2's ?include= returns an arbitrary first page
rather than a curated list (proven for albums; tracks is the same
mechanism): artists/{id}/albums (discography, no filter, mirroring
tidalapi get_albums), artists/{id}/toptracks (real popularity order,
mirroring get_top_tracks), and search?types=artists (artist id by name).
Both official includes are dropped as dead weight.

The by-name search deliberately requires an EXACT case-insensitive match
and drops the desktop's artists[0] fallback, which attaches the wrong
artist's data — a bug fix, not a parity gap. Merges mirror window.py:
albums local-first + dedup by title, tracks top-first + unique local after.

Metadata only; streaming stays untouched and search stays on internal-v1.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 4: Album detail projection — real metadata, full tracklist, cover art, similar albums

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt` (add `albumRequest`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt` (add `getAlbum`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataDetail.kt` (add `TidalAlbumDetail` + `albumDetailFrom`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt` (add `albumDetail(...)` + `ALBUM_DETAIL_INCLUDE`)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalAlbumDetailTest.kt` (create)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt` (add `albumRequest` shape)

**Interfaces:**
- Produces: `TidalOfficialEndpoints.albumRequest(id, accessToken, include = emptyList(), countryCode = null): Request`; `TidalOfficialClient.getAlbum(id, accessToken, include, countryCode): Result<TidalDocument>`; `data class TidalAlbumDetail(id, title, artistName, artistId, albumType, releaseYear, explicit, artworkUrl, tracks, similarAlbums)`; `fun albumDetailFrom(doc, json, artworkTargetPx): TidalAlbumDetail?`; `TidalMetadataRepository.albumDetail(albumId): Result<TidalAlbumDetail>`.

- [ ] **Step 1: Write the failing tests**

Create `TidalAlbumDetailTest.kt`. `items` = the tracklist (type `tracks`), in linkage order:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalAlbumDetailTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val albumDoc = """
        {
          "data": {
            "id": "9876", "type": "albums",
            "attributes": { "title": "The Album", "albumType": "ALBUM", "releaseDate": "2020-07-10", "explicit": true },
            "relationships": {
              "coverArt":      { "data": { "id": "cov1", "type": "artworks" } },
              "artists":       { "data": [ { "id": "3456", "type": "artists" } ] },
              "items":         { "data": [ { "id": "t1", "type": "tracks" }, { "id": "t2", "type": "tracks" } ] },
              "similarAlbums": { "data": [ { "id": "9999", "type": "albums" } ] }
            }
          },
          "included": [
            { "id": "cov1", "type": "artworks", "attributes": {
                "files": [ { "href": "https://cov/1280.jpg", "meta": { "width": 1280, "height": 1280 } } ] } },
            { "id": "3456", "type": "artists", "attributes": { "name": "Prompto" } },
            { "id": "t1", "type": "tracks", "attributes": { "title": "Opener", "duration": "PT4M00S", "explicit": true } },
            { "id": "t2", "type": "tracks", "attributes": { "title": "Closer", "duration": "PT2M30S", "explicit": false } },
            { "id": "9999", "type": "albums", "attributes": { "title": "Neighbor Album", "albumType": "ALBUM", "releaseDate": "2018-01-01" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `albumDetailFrom maps metadata, artist and best-fit cover art`() {
        val d = albumDetailFrom(json.decodeFromString(albumDoc), json, artworkTargetPx = 640)!!
        assertEquals("9876", d.id)
        assertEquals("The Album", d.title)
        assertEquals("Prompto", d.artistName)
        assertEquals("3456", d.artistId)
        assertEquals("ALBUM", d.albumType)
        assertEquals(2020, d.releaseYear)
        assertTrue(d.explicit)
        assertEquals("https://cov/1280.jpg", d.artworkUrl)
    }

    @Test
    fun `albumDetailFrom builds the ordered playable tracklist with album context`() {
        val d = albumDetailFrom(json.decodeFromString(albumDoc), json, artworkTargetPx = 640)!!
        assertEquals(listOf("Opener", "Closer"), d.tracks.map { it.title })
        assertEquals(listOf(1, 2), d.tracks.map { it.trackNumber })
        d.tracks.forEach {
            assertEquals(Source.TIDAL, it.source)
            assertEquals("The Album", it.album)
            assertEquals("Prompto", it.artist)
            assertEquals("https://cov/1280.jpg", it.albumArtUrl)
        }
        assertEquals(240.0, d.tracks.first().durationSeconds!!, 0.0)
    }

    @Test
    fun `albumDetailFrom maps similar albums`() {
        val d = albumDetailFrom(json.decodeFromString(albumDoc), json, artworkTargetPx = 640)!!
        assertEquals(listOf("Neighbor Album"), d.similarAlbums.map { it.title })
        assertEquals(2018, d.similarAlbums.single().releaseYear)
    }

    @Test
    fun `albumDetailFrom returns null without a primary album`() {
        assertNull(albumDetailFrom(json.decodeFromString("""{ "data": [], "included": [] }"""), json, 640))
    }
}
```

Add to `TidalOfficialEndpointsTest.kt`:

```kotlin
    @Test
    fun `album request carries include params and the country code`() {
        val request = TidalOfficialEndpoints.albumRequest(
            id = "9876", accessToken = "tok-123",
            include = listOf("items", "artists", "coverArt", "similarAlbums"), countryCode = "GB",
        )
        val url = request.url
        assertEquals("/v2/albums/9876", url.encodedPath)
        assertEquals(listOf("items", "artists", "coverArt", "similarAlbums"), url.queryParameterValues("include"))
        assertEquals("GB", url.queryParameter("countryCode"))
        assertEquals("Bearer tok-123", request.header("Authorization"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalAlbumDetailTest*' --tests '*TidalOfficialEndpointsTest*'`
Expected: FAIL — `albumDetailFrom`/`TidalAlbumDetail`/`albumRequest` unresolved.

- [ ] **Step 3: Add `albumRequest` to `TidalOfficialEndpoints.kt`**

```kotlin
    /** `GET /albums/{id}` with `?include=...` (items/artists/coverArt/similarAlbums). `countryCode` optional per spec. */
    fun albumRequest(
        id: String,
        accessToken: String,
        include: List<String> = emptyList(),
        countryCode: String? = null,
    ): Request {
        val url = "$BASE/albums/$id".toHttpUrl().newBuilder()
            .apply { countryCode?.let { addQueryParameter("countryCode", it) } }
            .apply { include.forEach { addQueryParameter("include", it) } }
            .build()
        return authed(url, accessToken)
    }
```

- [ ] **Step 4: Add `getAlbum` to `TidalOfficialClient.kt`**

```kotlin
    /** Fetch an album as a JSON:API compound document (tracklist/artists/coverArt/similarAlbums). */
    suspend fun getAlbum(
        id: String,
        accessToken: String,
        include: List<String>,
        countryCode: String? = null,
    ): Result<TidalDocument> = execute(TidalOfficialEndpoints.albumRequest(id, accessToken, include, countryCode))
```

- [ ] **Step 5: Add `TidalAlbumDetail` + `albumDetailFrom` to `TidalMetadataDetail.kt`**

```kotlin
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
 * Pure projection: compound album [TidalDocument] → [TidalAlbumDetail]. The
 * `items` relationship IS the tracklist; linkage order is the track order, so
 * each item is numbered by position (Tracks attributes carry no track number).
 * Returns null when there is no primary album.
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
        tracks = view.related(album, "items").mapIndexed { i, res ->
            trackFrom(res, json, artistName = artistName, albumTitle = title, artworkUrl = artworkUrl)
                .copy(trackNumber = i + 1)
        },
        similarAlbums = view.related(album, "similarAlbums").map { albumSummaryFrom(it, view, json, artworkTargetPx) },
    )
}
```

- [ ] **Step 6: Add `albumDetail` + constant to `TidalMetadataRepository.kt`**

```kotlin
    /** Full album detail: metadata, ordered tracklist, cover art, similar albums. */
    suspend fun albumDetail(albumId: String): Result<TidalAlbumDetail> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))
        return client.getAlbum(albumId, token, ALBUM_DETAIL_INCLUDE, countryCode).mapCatching { doc ->
            albumDetailFrom(doc, json, ARTWORK_TARGET_PX) ?: error("Album $albumId not found")
        }
    }
```

Companion addition:

```kotlin
        val ALBUM_DETAIL_INCLUDE = listOf("items", "artists", "coverArt", "similarAlbums")
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalAlbumDetailTest*' --tests '*TidalOfficialEndpointsTest*'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataDetail.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalAlbumDetailTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt
git commit -m "feat(android): Tidal album detail projection (metadata, tracklist, cover art, similar)

albumDetailFrom projects a compound /albums/{id} document into
TidalAlbumDetail; items become an ordered list of playable TIDAL Tracks
with album context. Repository albumDetail() wraps the fetch.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 5: Track credits + lyrics projection

Grep-confirmed spec fields: `Credits_Attributes` = `{ name, role }` (one resource per contributor); `Lyrics_Attributes` = `{ text, lrcText, provider, direction, technicalStatus }` (plain `text` + synced `lrcText`).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataDetail.kt` (add `CreditsAttributes`, `LyricsAttributes`, `TrackCredit`, `TidalTrackDetail`, `trackDetailFrom`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt` (add `trackDetail(...)` + `TRACK_DETAIL_INCLUDE`)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalTrackDetailTest.kt` (create)

**Interfaces:**
- Produces: `@Serializable CreditsAttributes(name, role)`; `@Serializable LyricsAttributes(text, lrcText)`; `data class TrackCredit(role, names)`; `data class TidalTrackDetail(id, title, credits, lyricsText, lyricsSynced, similarTracks)`; `fun trackDetailFrom(doc, json, artworkTargetPx): TidalTrackDetail?`; `TidalMetadataRepository.trackDetail(trackId): Result<TidalTrackDetail>` (uses `getTrackMetadata` from Task 1).

- [ ] **Step 1: Write the failing test**

Create `TidalTrackDetailTest.kt`:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalTrackDetailTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val trackDoc = """
        {
          "data": {
            "id": "75413016", "type": "tracks",
            "attributes": { "title": "Sample", "isrc": "USABC1234567" },
            "relationships": {
              "artists":       { "data": [ { "id": "3456", "type": "artists" } ] },
              "credits":       { "data": [
                  { "id": "c1", "type": "credits" }, { "id": "c2", "type": "credits" }, { "id": "c3", "type": "credits" } ] },
              "lyrics":        { "data": { "id": "ly1", "type": "lyrics" } },
              "similarTracks": { "data": [ { "id": "8001", "type": "tracks" } ] }
            }
          },
          "included": [
            { "id": "3456", "type": "artists", "attributes": { "name": "Prompto" } },
            { "id": "c1", "type": "credits", "attributes": { "role": "Producer", "name": "Alice" } },
            { "id": "c2", "type": "credits", "attributes": { "role": "Producer", "name": "Bob" } },
            { "id": "c3", "type": "credits", "attributes": { "role": "Mixer", "name": "Carol" } },
            { "id": "ly1", "type": "lyrics", "attributes": { "text": "line one\nline two", "lrcText": "[00:01.00]line one" } },
            { "id": "8001", "type": "tracks", "attributes": { "title": "Similar One", "duration": "PT3M00S" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `trackDetailFrom groups credits by role, preserving order`() {
        val d = trackDetailFrom(json.decodeFromString(trackDoc), json, artworkTargetPx = 640)!!
        assertEquals("Sample", d.title)
        assertEquals(listOf("Producer", "Mixer"), d.credits.map { it.role })
        assertEquals(listOf("Alice", "Bob"), d.credits.first().names)
        assertEquals(listOf("Carol"), d.credits[1].names)
    }

    @Test
    fun `trackDetailFrom maps plain and synced lyrics`() {
        val d = trackDetailFrom(json.decodeFromString(trackDoc), json, artworkTargetPx = 640)!!
        assertEquals("line one\nline two", d.lyricsText)
        assertEquals("[00:01.00]line one", d.lyricsSynced)
    }

    @Test
    fun `trackDetailFrom maps similar tracks`() {
        val d = trackDetailFrom(json.decodeFromString(trackDoc), json, artworkTargetPx = 640)!!
        assertEquals(listOf("8001"), d.similarTracks.map { it.sourceId })
        assertEquals(listOf("Similar One"), d.similarTracks.map { it.title })
    }

    @Test
    fun `trackDetailFrom tolerates a track with no credits or lyrics`() {
        val doc = """{ "data": { "id": "1", "type": "tracks", "attributes": { "title": "Bare" } }, "included": [] }"""
        val d = trackDetailFrom(json.decodeFromString(doc), json, artworkTargetPx = 640)!!
        assertEquals("Bare", d.title)
        assertTrue(d.credits.isEmpty())
        assertNull(d.lyricsText)
        assertNull(d.lyricsSynced)
        assertTrue(d.similarTracks.isEmpty())
    }

    @Test
    fun `trackDetailFrom returns null without a primary track`() {
        assertNull(trackDetailFrom(json.decodeFromString("""{ "data": [], "included": [] }"""), json, 640))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalTrackDetailTest*'`
Expected: FAIL — `trackDetailFrom`/`TidalTrackDetail`/`TrackCredit`/`CreditsAttributes`/`LyricsAttributes` unresolved.

- [ ] **Step 3: Add the DTOs, domain types and `trackDetailFrom` to `TidalMetadataDetail.kt`**

```kotlin
import kotlinx.serialization.Serializable

/** `Credits_Attributes` (spec, verbatim): one contributor {name, role} per resource. */
@Serializable
data class CreditsAttributes(val name: String? = null, val role: String? = null)

/** `Lyrics_Attributes` (spec, verbatim, subset): plain [text] + time-synced [lrcText]. */
@Serializable
data class LyricsAttributes(val text: String? = null, val lrcText: String? = null)

/** Contributors sharing one role (e.g. "Producer" -> [Alice, Bob]). */
data class TrackCredit(val role: String, val names: List<String>)

/** Track credits + lyrics (and similar tracks) for the credits/lyrics sheet. */
data class TidalTrackDetail(
    val id: String,
    val title: String,
    val credits: List<TrackCredit>,
    val lyricsText: String?,
    val lyricsSynced: String?,
    val similarTracks: List<Track>,
)

/**
 * Pure projection: compound track [TidalDocument] → [TidalTrackDetail].
 * Credits (one {name, role} per resource) are grouped by role, preserving
 * first-seen order of both roles and names. Returns null without a primary track.
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
```

(The `import kotlinx.serialization.Serializable` line goes with the file's other imports; add it if Tasks 3–4 did not already.)

- [ ] **Step 4: Add `trackDetail` + constant to `TidalMetadataRepository.kt`**

```kotlin
    /** Track credits + lyrics (+ similar tracks) as a compound document projection. */
    suspend fun trackDetail(trackId: String): Result<TidalTrackDetail> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))
        return client.getTrackMetadata(trackId, token, TRACK_DETAIL_INCLUDE, countryCode).mapCatching { doc ->
            trackDetailFrom(doc, json, ARTWORK_TARGET_PX) ?: error("Track $trackId not found")
        }
    }
```

Companion addition:

```kotlin
        val TRACK_DETAIL_INCLUDE = listOf("credits", "lyrics", "similarTracks", "artists")
```

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TidalTrackDetailTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataDetail.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalTrackDetailTest.kt
git commit -m "feat(android): Tidal track credits + lyrics projection

trackDetailFrom groups Credits by role and surfaces plain + synced Lyrics.
Repository trackDetail() wraps validAccessToken -> getTrackMetadata ->
projection.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 6: Wire artist enrichment into the UI (ViewModel passthroughs + navigation + ArtistDetailScreen overlay)

Compose + ViewModel-passthrough + Navigation. Per the Foundation precedent, the ViewModel passthroughs and Compose wiring are NOT unit-tested; this task closes on an on-device checkpoint (Step 5). All projection/repository logic it calls is already tested (Tasks 2–5).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (artist/album enrichment state + `loadArtistDetail`/`loadAlbumDetail`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (optional `tidalArtistId`/`tidalAlbumId` nav args; unified open-album/open-artist lambdas)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt` (overlay biography, remote art, remote discography, artist tracks, similar artists)

**Interfaces:**
- Consumes: `TidalMetadataRepository.trackRelations`, `.artistDetail`, `.albumDetail` (Tasks 1/3/4); `TidalProvider.artistAlbums`, `.artistTopTracks`, `.searchArtistIdExact` + `dedupRemoteAlbums` + `mergeArtistTracks` (Task 3).
- Produces: `PlayerViewModel.artistDetail: StateFlow<TidalArtistDetail?>`, `.artistAlbums: StateFlow<List<AlbumSummary>>`, `.artistTopTracks: StateFlow<List<Track>>`, `.artistDetailLoading: StateFlow<Boolean>`, `fun loadArtistDetail(artistName, tidalArtistId, seedTidalTrackId)`; `PlayerViewModel.albumDetail: StateFlow<TidalAlbumDetail?>`, `.albumDetailLoading`, `fun loadAlbumDetail(album, artist, tidalAlbumId, seedTidalTrackId)` (album load is defined here, consumed by Task 7).

**How the artist id is resolved (read this before Step 1).** The full chain — including why the seed must match on `it.artist` and not `albumArtist`, and why there is deliberately no `artists[0]` fallback — is stated once in the Architecture section under ***Artist-id resolution***. Read it there; this task only wires it. In short: nav-arg id → seed track via `trackRelations` → exact-match internal-v1 name search → give up and render as today.

**What changed from the previous revision:** an artist with no nav-arg id and no Tidal seed track no longer gets *nothing*. It now gets **one** internal-v1 search by name, and if — and only if — an artist matches **exactly** (case-insensitively), the page is enriched like any other. This is the desktop's behaviour minus its `artists[0]` fallback. So the architecture's *"a local-only artist shows exactly what it shows today"* now holds precisely when **no exact Tidal match exists**; a local-only artist who *does* exist on Tidal by that exact name gets a bio, a discography and top tracks. (Albums are unaffected: there is no album-search fallback, so a non-Tidal album still shows exactly what it shows today.)

**Meanwhile:** local content renders immediately — it is the offline base, never gated on the network. Remote sections appear as they land. The existing `loading` (`libraryLoading || collectionLoading`) is untouched; `artistDetailLoading` must never block the local list.

- [ ] **Step 1: Add the enrichment state + load functions to `PlayerViewModel.kt`**

Add (imports `TidalArtistDetail`/`TidalAlbumDetail`/`AlbumSummary` from `io.github.auxen.provider.tidal`):

```kotlin
    // ---- Slice 1: official-API detail enrichment (overlays local grouping) ----
    val artistDetail = MutableStateFlow<io.github.auxen.provider.tidal.TidalArtistDetail?>(null)

    /**
     * The artist discography, from INTERNAL-v1 (`artists/{id}/albums`) — a
     * separate flow from [artistDetail] because it is a separate API and a
     * separate call. [TidalMetadataRepository]'s constructor is frozen and holds
     * no internal-v1 provider, so this composition happens here, not there.
     * Empty means "no discography" (not logged in to internal-v1, unknown
     * artist, or the call failed) — the row simply falls back to local albums.
     */
    val artistAlbums = MutableStateFlow<List<io.github.auxen.provider.tidal.AlbumSummary>>(emptyList())

    /**
     * The artist's top tracks in POPULARITY order, from INTERNAL-v1
     * (`artists/{id}/toptracks`) — same reasoning as [artistAlbums]: separate
     * API, separate call, composed here because the repository cannot reach the
     * internal-v1 provider. Empty means "no top tracks", and the screen then
     * shows the plain local track list exactly as it does today (`mergeArtistTracks`
     * returns the local list untouched on an empty top-tracks list).
     */
    val artistTopTracks = MutableStateFlow<List<Track>>(emptyList())
    val artistDetailLoading = MutableStateFlow(false)
    private var artistDetailKey: String? = null

    val albumDetail = MutableStateFlow<io.github.auxen.provider.tidal.TidalAlbumDetail?>(null)
    val albumDetailLoading = MutableStateFlow(false)
    private var albumDetailKey: String? = null

    /**
     * Load remote artist enrichment. Resolves the artist id through the chain in
     * the plan's *Artist-id resolution*, then fans out to the four reads. Keyed
     * so a recomposition/re-navigation to the same artist does not refetch.
     *
     * The id chain, in order — first hit wins:
     *  1. [tidalArtistId] — already known (a similar-artist tap).
     *  2. [seedTidalTrackId] → `trackRelations` → artistId (gate-proven, precise).
     *  3. `searchArtistIdExact(artistName)` — internal-v1, EXACT name match only.
     *  4. null → no remote enrichment; the screen renders exactly as today.
     *
     * Step 3 also catches the case where a seed exists but `trackRelations`
     * fails, which is why it is an elvis fallthrough rather than an else-branch.
     *
     * There is deliberately **no early return** for "no id and no seed": that
     * artist is exactly who step 3 exists for. `searchArtistIdExact` is one
     * request, only on artist-page open, and the key guard stops it repeating.
     *
     * The official artist document and the two internal-v1 lists are fetched off
     * ONE resolved id and each is allowed to fail on its own: a missing
     * discography must not cost us the biography, and vice versa.
     */
    fun loadArtistDetail(artistName: String, tidalArtistId: String?, seedTidalTrackId: String?) {
        val key = "$artistName|$tidalArtistId|$seedTidalTrackId"
        if (key == artistDetailKey) return
        artistDetailKey = key
        artistDetail.value = null
        artistAlbums.value = emptyList()
        artistTopTracks.value = emptyList()
        viewModelScope.launch {
            artistDetailLoading.value = true
            try {
                val artistId = tidalArtistId
                    ?: seedTidalTrackId?.let { Graph.tidalMetadata.trackRelations(it).getOrNull()?.artistId }
                    ?: Graph.tidal.searchArtistIdExact(artistName)
                    ?: return@launch
                Graph.tidalMetadata.artistDetail(artistId).onSuccess { artistDetail.value = it }
                // Internal-v1, per the artist-lists decision. Neither throws
                // (both return empty on any failure), so neither can take down
                // the official detail resolved just above, nor each other.
                artistAlbums.value = Graph.tidal.artistAlbums(artistId)
                artistTopTracks.value = Graph.tidal.artistTopTracks(artistId)
            } finally {
                artistDetailLoading.value = false
            }
        }
    }

    /** Load remote album enrichment. Symmetric to [loadArtistDetail]; consumed by AlbumDetailScreen (Task 7). */
    fun loadAlbumDetail(album: String, artist: String, tidalAlbumId: String?, seedTidalTrackId: String?) {
        val key = "$album|$artist|$tidalAlbumId|$seedTidalTrackId"
        if (key == albumDetailKey) return
        albumDetailKey = key
        albumDetail.value = null
        if (tidalAlbumId == null && seedTidalTrackId == null) return
        viewModelScope.launch {
            albumDetailLoading.value = true
            try {
                val albumId = tidalAlbumId
                    ?: Graph.tidalMetadata.trackRelations(seedTidalTrackId!!).getOrNull()?.albumId
                    ?: return@launch
                Graph.tidalMetadata.albumDetail(albumId).onSuccess { albumDetail.value = it }
            } finally {
                albumDetailLoading.value = false
            }
        }
    }
```

- [ ] **Step 2: Add the optional id nav args in `MainActivity.kt`**

Concretely (mirrors the existing `composable(...)` blocks at L327–343):

- Add `import androidx.navigation.navArgument`.
- Add the two routes to `topBarTitle` pattern matching so the labels still resolve: change the `when` keys to `"album/{album}/{artist}?tidalAlbumId={tidalAlbumId}"` → "Album" and `"artist/{artist}?tidalArtistId={tidalArtistId}"` → "Artist" (the route string a `composable` registers is what `currentRoute` reports).
- Register a small route-builder helper at the top of `MainScreen`:

```kotlin
    fun artistRoute(name: String, tidalArtistId: String? = null): String =
        "artist/${Uri.encode(name)}" + (tidalArtistId?.let { "?tidalArtistId=$it" } ?: "")
    fun albumRoute(album: String, artist: String, tidalAlbumId: String? = null): String =
        "album/${Uri.encode(album)}/${Uri.encode(artist)}" + (tidalAlbumId?.let { "?tidalAlbumId=$it" } ?: "")
```

- Replace the `artist` and `album` composables:

```kotlin
                    composable(
                        "artist/{artist}?tidalArtistId={tidalArtistId}",
                        arguments = listOf(navArgument("tidalArtistId") { nullable = true; defaultValue = null }),
                    ) { backStack ->
                        ArtistDetailScreen(
                            viewModel,
                            artist = backStack.arguments?.getString("artist").orEmpty(),
                            tidalArtistId = backStack.arguments?.getString("tidalArtistId"),
                            onOpenAlbum = { album, artist, tidalAlbumId ->
                                navController.navigate(albumRoute(album, artist, tidalAlbumId))
                            },
                            onOpenArtist = { name, id -> navController.navigate(artistRoute(name, id)) },
                        )
                    }
                    composable(
                        "album/{album}/{artist}?tidalAlbumId={tidalAlbumId}",
                        arguments = listOf(navArgument("tidalAlbumId") { nullable = true; defaultValue = null }),
                    ) { backStack ->
                        AlbumDetailScreen(
                            viewModel,
                            album = backStack.arguments?.getString("album").orEmpty(),
                            artist = backStack.arguments?.getString("artist").orEmpty(),
                            tidalAlbumId = backStack.arguments?.getString("tidalAlbumId"),
                            onOpenArtist = { name, id -> navController.navigate(artistRoute(name, id)) },
                        )
                    }
```

- Update the other call sites that navigate to album/artist (Library, Collection composables, and the `onOpenArtist` inside the album composable) to use `albumRoute(...)`/`artistRoute(...)` with a null id — behavior unchanged for local navigation. The `LibraryScreen`/`CollectionScreen` composables keep their existing `onOpenAlbum: (AlbumGroup) -> Unit`/`onOpenArtist: (String) -> Unit` signatures; only the lambda bodies change to call the route helpers.

- [ ] **Step 3: Overlay remote data in `ArtistDetailScreen.kt`**

Change the signature and add the enrichment overlay. Keep the existing local grouping (`pool`/`tracks`/`albums`) exactly as-is — it is the offline base. Concrete wiring:

- New params: replace `onOpenAlbum: (AlbumGroup) -> Unit` with `onOpenAlbum: (album: String, artist: String, tidalAlbumId: String?) -> Unit`, add `tidalArtistId: String? = null`, add `onOpenArtist: (name: String, tidalArtistId: String?) -> Unit`.
- Collect + trigger the load:

```kotlin
    val detail by viewModel.artistDetail.collectAsState()
    val remoteAlbums by viewModel.artistAlbums.collectAsState()
    val topTracks by viewModel.artistTopTracks.collectAsState()
    // The seed MUST be a track whose PRIMARY artist is this screen's artist.
    // `tracks` is filtered on `it.artist == artist || it.albumArtist == artist`,
    // and trackRelations takes related(track,"artists").first() — the primary
    // artist. So seeding off an albumArtist-only match (a compilation, a
    // "Various Artists" album) would resolve a DIFFERENT artist's id and hang
    // the wrong bio, discography and top tracks on this page. `it.artist` is the
    // main-artist name for Tidal tracks (TidalProvider.toTrack maps
    // TrackDto.artist.name), so this comparison is exactly the right guard.
    val seedTidalTrackId = tracks.firstOrNull { it.isTidal && it.artist == artist }?.sourceId
    LaunchedEffect(artist, tidalArtistId, seedTidalTrackId) {
        viewModel.loadArtistDetail(artist, tidalArtistId, seedTidalTrackId)
    }
    // Tidal top tracks (popularity order) first, then local tracks not among
    // them — the desktop's merge (window.py:1409-1422). With no top tracks this
    // returns `tracks` unchanged, so the offline base is never at risk.
    val displayTracks = remember(topTracks, tracks) { mergeArtistTracks(topTracks, tracks) }
```

- Header art model becomes `detail?.artworkUrl ?: tracks.firstNotNullOfOrNull { it.albumArtUrl }` (remote profile art wins when present).
- After the Play All / Shuffle row, add a **Biography** section when `detail?.biography` is non-null: a `SectionHeader("Biography")` + an expandable `Text` (collapsed `maxLines = 4`, `overflow = TextOverflow.Ellipsis`, a `remember { mutableStateOf(false) }` toggled by `Modifier.clickable`, `MaterialTheme.typography.bodyMedium`). This mirrors the desktop artist-bio surface. A null biography renders **nothing** — no header, no placeholder, no error (the desktop hides the section); Task 3's repository has already exhausted the fallback by the time it is null.
- **Albums** section — local ∪ Tidal, mirroring desktop `window.py:1394-1407`. Do **not** prefer one over the other; the desktop shows local albums first and appends Tidal albums it doesn't already have:

```kotlin
    // `albums` is the existing local grouping — unchanged, still the offline base.
    val tidalOnlyAlbums = remember(albums, remoteAlbums) {
        dedupRemoteAlbums(
            localTitles = albums.map { it.album.lowercase() }.toSet(),
            remote = remoteAlbums,
        )
    }
```

Render the section when `albums.isNotEmpty() || tidalOnlyAlbums.isNotEmpty()`, as **two `items(...)` blocks in the existing single `LazyRow`** — locals first (unchanged: existing `AlbumCard` wiring, `onPlay = { viewModel.playAll(album.tracks) }`, `onClick = { onOpenAlbum(album.album, album.albumArtist, null) }`), then:

```kotlin
                    items(tidalOnlyAlbums, key = { "tidal:${it.id}" }) { summary ->
                        AlbumCard(
                            title = summary.title,
                            artist = null,
                            artUrl = summary.artworkUrl,
                            source = Source.TIDAL,
                            onClick = { onOpenAlbum(summary.title, detail?.name ?: artist, summary.id) },
                            // No local tracks to enqueue — a tap opens the album,
                            // whose remote tracklist (Task 7) is playable.
                            onPlay = null,
                            modifier = Modifier.width(150.dp),
                        )
                    }
```

**Imports for this file** — `ArtistDetailScreen.kt` is in package `io.github.auxen.ui`, so every `provider.tidal` symbol it names needs an explicit import (Kotlin resolves per-file; a missing import has bitten this series). Add: `io.github.auxen.model.Source`, `io.github.auxen.provider.tidal.dedupRemoteAlbums`, `io.github.auxen.provider.tidal.mergeArtistTracks`. Already present and NOT to be re-added: `androidx.compose.runtime.remember` (line 32), `androidx.compose.runtime.collectAsState` (29), `io.github.auxen.model.Track` (43), `io.github.auxen.ui.components.AlbumCard` (44). `LaunchedEffect` is not currently imported — add `androidx.compose.runtime.LaunchedEffect`.

(Two `items` blocks in one `LazyRow` keep the desktop's ordering without needing a union type for the two album shapes.)
- **Similar artists** section (new, when `detail?.similarArtists` non-empty): a `LazyRow` of `ArtistRow`-style circular cards or a simple `AlbumCard`-like column; each `onClick = { onOpenArtist(link.name, link.id) }`. `ArtistLink.artworkUrl` is always null here (Task 3's KDoc says why) — these render with the `Icons.Filled.Person` glyph the header already uses.
- **Tracks** section: render `displayTracks` — the merge above — instead of the bare local `tracks`, through the **existing** `AuxenTrackRow` wiring (favorite state, `onPlay = { viewModel.play(it) }`, `onLongPress = { sheetTrack = it }`), keeping the existing `key = { "${it.source}:${it.sourceId}" }` (which `mergeArtistTracks` guarantees is unique). Two consequences to wire:
  - **Header label:** `SectionHeader(if (topTracks.isNotEmpty()) "Top tracks" else "Tracks")`. `artists/{id}/toptracks` is popularity-ordered by contract (`tidalapi`: *"sorted by popularity"*) and was eyeballed on-device in Task 3 Step 9, so the label is now honest. With no top tracks the list is purely local and "Tracks" is the honest label there.
  - **`EmptyState` guard** moves from `tracks.isEmpty()` to `displayTracks.isEmpty()` — a Tidal-only artist (reached by a similar-artist tap, or resolved by the name search, with no local files) has an empty local `tracks` but a full `displayTracks`, and must render the list rather than "no tracks".
- **Play All / Shuffle** operate on `displayTracks` (`enabled = displayTracks.isNotEmpty()`), so a Tidal-only artist is fully playable. Every entry is an ordinary `Track(source = TIDAL, sourceId = <catalog id>)` and plays through the existing internal-v1 pipeline — no new playback code.
- **Header counts:** `"${albums.size + tidalOnlyAlbums.size} albums • ${displayTracks.size} tracks"`, so the counts describe what is actually on screen rather than the local subset.

- [ ] **Step 4: Build**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest assembleDebug`
Expected: PASS; APK builds. (No new unit tests here — logic is covered by Tasks 2–5; this step is a compile+existing-suite gate. If a Roborazzi golden for a detail/component screen shifts, re-record with `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android recordRoborazziDebug` and eyeball the diff.)

- [ ] **Step 5: On-device checkpoint (artist enrichment)**

Build the APK **locally** (CI has no Tidal creds), install, log in on **both** Tidal paths — the discography/top-tracks/search need the internal-v1 login, the rest needs the official one. Open an artist that has Tidal tracks in your library/favorites (Library → Artists, or via a Tidal track's artist). Confirm:
- circular **profile artwork** loads;
- a **Biography** section appears and expands. This is the falsified assumption's payoff: if the bio is missing here, Task 3's fallback is not firing — check that `ARTIST_DETAIL_INCLUDE` still contains `biography` AND that `artistDetail` actually calls `getArtistBiography` when the projection returns null.
- a **Similar artists** row appears; tapping one opens that artist remote-only via `tidalArtistId` (no local tracks needed);
- the **Albums** row shows local albums first, then Tidal-only albums, **with no title duplicated across the two** (the dedup) — and it reads like a discography, not 20 variants of one song;
- the track section is headed **"Top tracks"** and leads with the artist's best-known songs in popularity order, with any local-only tracks appended after. **Your own local tracks must still all be there** — if a local track vanished, `mergeArtistTracks` is dropping across sources; re-read its divergence #1.
- tapping a Tidal-only album opens album detail (Task 7).

Then exercise **Decision 4** — the by-name fallback — with two artists that have **no Tidal tracks in your library**:
1. **An artist who exists on Tidal under exactly that name** (e.g. a ripped CD by a well-known band). Confirm the page **now enriches**: bio, similar artists, discography, top tracks — with no local Tidal track to seed from. Confirm the artist is genuinely the right one; a wrong bio here means the exact match is not exact.
2. **An artist who does not exist on Tidal** (a bootleg, a local recording, a misspelling). Confirm the page renders **exactly as before**: no biography, no similar-artists row, no Tidal albums, header still "Tracks", no spinner stuck. One search request fires and resolves to nothing — that is the design, not a bug. **A wrong artist's bio appearing here is a FAIL**: it means the `artists[0]` fallback got re-introduced somewhere.

Paste a screenshot/observations to close the loop.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt
# plus any re-recorded golden under app/src/test/.../roborazzi/ if Step 4 changed it
git commit -m "feat(android): remote-backed artist detail (bio, similar, discography, top tracks)

ArtistDetailScreen overlays remote artist metadata on the local grouping.
The artist id resolves through a chain: tidalArtistId nav arg (similar-
artist drill-in) -> a Tidal track by that artist seeding trackRelations ->
an exact-match internal-v1 name search. An artist with no exact Tidal match
renders exactly as today.

Albums merge local-first and dedup by lowercase title; tracks lead with
Tidal top tracks in popularity order and append unique local tracks after
— both mirroring the desktop's window.py merge.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 7: Wire album enrichment into AlbumDetailScreen

Consumes `loadAlbumDetail`/`albumDetail` added in Task 6. Compose; closes on-device.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/AlbumDetailScreen.kt`

**Interfaces:**
- Consumes: `PlayerViewModel.albumDetail`, `.loadAlbumDetail`; `onOpenArtist: (name, tidalArtistId) -> Unit`, `tidalAlbumId: String?` (from Task 6 nav).

- [ ] **Step 1: Overlay remote data in `AlbumDetailScreen.kt`**

Keep the existing local `group`/`tracks` computation as the offline base. Concrete wiring:

- New params: add `tidalAlbumId: String? = null`; change `onOpenArtist: (String) -> Unit` to `onOpenArtist: (name: String, tidalArtistId: String?) -> Unit`.
- Collect + trigger:

```kotlin
    val detail by viewModel.albumDetail.collectAsState()
    val seedTidalTrackId = tracks.firstOrNull { it.isTidal }?.sourceId
    LaunchedEffect(album, artist, tidalAlbumId, seedTidalTrackId) {
        viewModel.loadAlbumDetail(album, artist, tidalAlbumId, seedTidalTrackId)
    }
    val displayTracks = detail?.tracks?.takeIf { it.isNotEmpty() } ?: tracks
```

- Header: art model `detail?.artworkUrl ?: group?.artUrl`; title `detail?.title ?: album`; artist link text `detail?.artistName ?: artist` with `Modifier.clickable { onOpenArtist(detail?.artistName ?: artist, detail?.artistId) }`; meta line built from `listOfNotNull(detail?.albumType, detail?.releaseYear?.toString() ?: group?.year?.toString(), "${displayTracks.size} tracks")`; keep the `SourceBadge` (use `Source.TIDAL` when `detail != null`, else the first local track's source).
- Play All / Shuffle operate on `displayTracks` (`enabled = displayTracks.isNotEmpty()`), so a Tidal-only album (no local tracks) is fully playable from the remote tracklist.
- Track list: `items(displayTracks, key = { "${it.source}:${it.sourceId}" })` through the existing `AuxenTrackRow` wiring (favorite state, `onPlay = { viewModel.play(it) }`, `onLongPress = { sheetTrack = it }`).
- Optional **Similar albums** section when `detail?.similarAlbums` is non-empty: a `LazyRow` of `AlbumCard`s, each `onClick` navigating via the album route with `summary.id` — this needs an `onOpenAlbum: (album, artist, tidalAlbumId) -> Unit` param threaded from MainActivity's album composable (add it symmetrically to Task 6's artist composable). If you keep Task 7 minimal, defer the similar-albums row and note it; the tracklist + metadata + cover art are the core deliverable.
- Keep the `LoadingState` while `libraryLoading || collectionLoading` (unchanged), and additionally show it while `albumDetailLoading` is true AND both local and remote track lists are still empty.

- [ ] **Step 2: Build**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest assembleDebug`
Expected: PASS; APK builds.

- [ ] **Step 3: On-device checkpoint (album enrichment)**

Locally-built APK: open a Tidal-backed album (e.g. from the enriched artist discography in Task 6, which passes `tidalAlbumId`, or from a Tidal track's album). Confirm high-res cover art, real title/artist/type/year, and the **full** tracklist (not just the tracks you happen to own locally), all playable. Tapping the artist link opens the artist. Open a local-only album and confirm unchanged behavior. Paste observations.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/AlbumDetailScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt
git commit -m "feat(android): remote-backed album detail (metadata, full tracklist, cover art)

AlbumDetailScreen overlays the official-API album document — real cover
art, metadata, and the complete playable tracklist — over the local group,
falling back to local when the album isn't Tidal-backed.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 8: Track credits + lyrics sheet, wired from the track action sheet

Surface `TidalTrackDetail` (Task 5) from the existing long-press `TrackActionSheet`, for Tidal tracks. Compose; closes on-device and confirms internal-v1 is untouched (slice close-out).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (`trackDetail` state + `loadTrackDetail`/`clearTrackDetail`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/TrackActionSheet.kt` (a "Credits & lyrics" action for Tidal tracks → `onShowInfo` callback)
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/TrackInfoSheet.kt` (credits + lyrics bottom sheet)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt` and `.../AlbumDetailScreen.kt` (host the info sheet)

**Interfaces:**
- Consumes: `TidalMetadataRepository.trackDetail` (Task 5).
- Produces: `PlayerViewModel.trackDetail: StateFlow<TidalTrackDetail?>`, `.trackDetailLoading`, `fun loadTrackDetail(track: Track)`, `fun clearTrackDetail()`; `TrackActionSheet(..., onShowInfo: (() -> Unit)? = null)`; `@Composable TrackInfoSheet(detail, loading, onDismiss)`.

- [ ] **Step 1: Add the ViewModel passthrough in `PlayerViewModel.kt`**

```kotlin
    val trackDetail = MutableStateFlow<io.github.auxen.provider.tidal.TidalTrackDetail?>(null)
    val trackDetailLoading = MutableStateFlow(false)

    /** Load credits + lyrics for a Tidal track (its sourceId IS the catalog id — no resolution needed). */
    fun loadTrackDetail(track: Track) {
        trackDetail.value = null
        if (!track.isTidal) return
        viewModelScope.launch {
            trackDetailLoading.value = true
            try {
                Graph.tidalMetadata.trackDetail(track.sourceId).onSuccess { trackDetail.value = it }
            } finally {
                trackDetailLoading.value = false
            }
        }
    }

    fun clearTrackDetail() { trackDetail.value = null }
```

- [ ] **Step 2: Add the "Credits & lyrics" action to `TrackActionSheet.kt`**

- Add a nullable `onShowInfo: (() -> Unit)? = null` param to both `TrackActionSheet` and `TrackActionSheetContent`.
- In the root actions block (the `if (!showPlaylists) { … }` branch), after "Add to playlist", add — only for Tidal tracks and only when a handler is present:

```kotlin
            if (track.source == Source.TIDAL && onShowInfo != null) {
                SheetAction("Credits & lyrics", { Icon(Icons.Filled.Info, null) }) { onShowInfo() }
            }
```

(Add `import androidx.compose.material.icons.filled.Info`.) In `TrackActionSheet`, thread it as `onShowInfo = onShowInfo?.let { { it(); onDismiss() } }` so opening the info sheet dismisses the action sheet first.

- [ ] **Step 3: Create `TrackInfoSheet.kt`**

A `ModalBottomSheet` (matching `TrackActionSheet`'s pattern) with a stateless `TrackInfoSheetContent` `Column` for testability parity. It shows: title, a `LoadingState` while `loading` and the detail is null, each `TrackCredit` as a role header + comma-joined names, and lyrics (`detail.lyricsText`, or a note when only `lyricsSynced` exists, or "No lyrics available"). Essential structure:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackInfoSheet(
    detail: io.github.auxen.provider.tidal.TidalTrackDetail?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {
            Text(detail?.title ?: "Track info", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (detail == null) {
                if (loading) LoadingState() else Text("No details available.")
            } else {
                if (detail.credits.isNotEmpty()) {
                    Text("Credits", style = MaterialTheme.typography.titleSmall)
                    detail.credits.forEach { c ->
                        Text(c.role, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(c.names.joinToString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Lyrics", style = MaterialTheme.typography.titleSmall)
                Text(
                    detail.lyricsText ?: (if (detail.lyricsSynced != null) "Synced lyrics only." else "No lyrics available."),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
```

- [ ] **Step 4: Host the info sheet in ArtistDetailScreen + AlbumDetailScreen**

In each screen, add `var infoTrack by remember { mutableStateOf<Track?>(null) }`, and in the `TrackActionSheet(...)` call pass `onShowInfo = { infoTrack = track }`. After the action sheet block, add:

```kotlin
    infoTrack?.let { track ->
        val detail by viewModel.trackDetail.collectAsState()
        val detailLoading by viewModel.trackDetailLoading.collectAsState()
        LaunchedEffect(track) { viewModel.loadTrackDetail(track) }
        TrackInfoSheet(
            detail = detail,
            loading = detailLoading,
            onDismiss = { infoTrack = null; viewModel.clearTrackDetail() },
        )
    }
```

- [ ] **Step 5: Build + full suite + goldens**

Run: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest verifyRoborazziDebug assembleDebug`
Expected: PASS. If the `TrackActionSheet` component golden (`TrackActionSheetUiTest`) shifts because the new Tidal-only action row renders in a Tidal fixture, re-record with `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android recordRoborazziDebug` and eyeball the diff; a LOCAL fixture must be unaffected (the row is gated on `track.source == TIDAL`).

- [ ] **Step 6: On-device checkpoint + internal-v1 untouched confirmation (slice close-out)**

Locally-built APK: long-press a Tidal track (in album/artist detail) → "Credits & lyrics" → confirm the sheet shows grouped credits and lyrics. Long-press a LOCAL track → confirm the "Credits & lyrics" action is absent.

Then confirm internal-v1 stayed within the narrowed constraint. **This check changed with the artist-lists decisions** — Task 3 adds `artistAlbums`, `artistTopTracks` and `searchArtistIdExact` to `TidalProvider.kt`, so "no diff at all" is no longer the criterion, and a bare `git status` would prove nothing anyway (Task 3's work is committed by now, so it reports clean either way). Diff against **`0d10ed5`, the commit Task 1 landed on** — the slice's base for Tasks 3–8:

```bash
# 1. TidalAuth.kt — untouched, entirely. Expect NO output.
git diff --stat 0d10ed5..HEAD -- android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalAuth.kt

# 2. TidalProvider.kt — exactly THREE additive methods, nothing else.
git diff 0d10ed5..HEAD -- android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt

# 3. The criterion that actually bites: ZERO deleted lines in TidalProvider.kt.
#    Prints only the count; anything but 0 fails the constraint.
git diff --numstat 0d10ed5..HEAD -- android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt
```

Expected:
1. prints nothing.
2. prints **purely additive** hunks adding `suspend fun artistAlbums(...)`, `suspend fun artistTopTracks(...)` and `suspend fun searchArtistIdExact(...)` — every changed line prefixed `+`.
3. `--numstat` prints `<added> 0 <path>` — the **second column must be `0`**. A non-zero deletion count means an existing internal-v1 method (auth, streaming, `search`, `toTrack`, a DTO) was modified, which violates the Global Constraint: revert it and re-do the addition additively. The most likely offender is someone "tidying" the reused `SearchResponse`/`TrackDto` that `artistTopTracks` shares with `search` — reuse it in place; do not rename or restructure it.

Paste observations + all three command outputs to close the slice.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/TrackActionSheet.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/TrackInfoSheet.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/AlbumDetailScreen.kt
# plus any re-recorded golden under app/src/test/.../roborazzi/ if Step 5 changed it
git commit -m "feat(android): track credits + lyrics sheet for Tidal tracks

Adds a 'Credits & lyrics' action (Tidal tracks only) to the track action
sheet, opening a TrackInfoSheet backed by the official-API trackDetail
projection. Internal-v1 path untouched.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

## Self-review (writing-plans checklist)

**Revised 2026-07-16 (revision #2)**, folding in two further user decisions on top of the same day's first revision: the artist page takes its **top tracks** from internal-v1 `artists/{id}/toptracks`, and a local-only artist is enriched via an **exact-match** internal-v1 name search. The first revision's basis stands: the Task-1 gate's on-device results falsified two assumptions (`?include=biography` does not deliver the bio; `?include=albums` is not a discography) and the discography moved to internal-v1. Tasks 1, 2, 4, 5, 7 are logically unchanged; Task 3 grew the two new reads, Task 6 consumes them, and Task 8's close-out criterion was corrected from "one additive method" to "three, zero deletions".

**Spec coverage:**
- **id-equivalence gate** (the slice's stated critical first task) ✓ Task 1: `trackRelations` (`GET /tracks/{id}?include=artists,albums`) + on-device probe reusing the Foundation debug screen. **PASSED on-device 2026-07-16** (readout in Architecture) — so the ISRC/`barcodeId` fallback is dead and is not built. The reverse direction (an official-v2 artist id sent to internal-v1) is probed in Task 3 Step 9, alongside all three new reads.
- **Artist detail** — biography (+ fallback), similar artists, profile art via `GET /artists/{id}?include=biography,similarArtists,profileArt`, with `GET /artists/{id}/relationships/biography?include=biography` when the bio is absent from `included` ✓ Task 3 (`artistDetailFrom` + `biographyTextFrom`) + Task 6 (UI). Spec schemas used: `Artists_Attributes.name`, `ArtistBiographies_Attributes.text/source`, `Artworks_Attributes.files`; the bio fallback's `Artists_Single_Relationship_Data_Document` (`data` = a bare `Resource_Identifier`, hence the mandatory `include`). **Neither `albums` nor `tracks` is included** — both are arbitrary first pages.
- **Artist discography** — internal-v1 `GET api.tidal.com/v1/artists/{id}/albums?limit=50&offset=0&countryCode=…`, **no `filter`**, mirroring `tidalapi`'s `Artist.get_albums(limit=50)` (`tidalapi/artist.py:131-137`) as the desktop calls it (`auxen/providers/tidal.py:896`) ✓ Task 3 (`artistAlbumsUrl`/`artistAlbumsFrom`/`TidalProvider.artistAlbums`) + Task 6 (UI). Official v2 cannot reproduce it: `albumType` has no COMPILATION and `/artists/{id}/relationships/albums` has no filter param (both verified in the cached spec).
- **Artist top tracks** — internal-v1 `GET api.tidal.com/v1/artists/{id}/toptracks?limit=50&offset=0&countryCode=…`, mirroring `tidalapi`'s `Artist.get_top_tracks(limit=50)` (`tidalapi/artist.py:179-194`, *"sorted by popularity"*) as the desktop calls it (`auxen/providers/tidal.py:906`) ✓ Task 3 (`artistTopTracksUrl` + `TidalProvider.artistTopTracks`, **reusing** the provider's existing `SearchResponse`/`TrackDto`/`toTrack` since the envelope is the identical `{"items":[…]}`) + Task 6 (UI, labelled "Top tracks"). Official v2 has no popularity-ordered artist-tracks endpoint at our tier.
- **Artist id by name** — internal-v1 `GET api.tidal.com/v1/search?query=…&limit=20&offset=0&types=artists&countryCode=…`, mirroring `session.search(name, models=[Artist], limit=5)` (`tidalapi/session.py:800-807`) as the desktop calls it (`auxen/providers/tidal.py:860-862`) ✓ Task 3 (`artistSearchUrl` + `artistIdFromSearch` + `TidalProvider.searchArtistIdExact`) + Task 6 (the chain's step 3). `types=artists` is **lowercase** — verified by executing tidalapi's `convert_type`, whose `Case` enum aliases `pascal`/`scream`/`lower` onto one member so the lowercase branch always fires. **The desktop's `artists[0]` fallback is deliberately not ported** (`providers/tidal.py:874-876`): exact case-insensitive match or null. Unit-tested in both directions, including a near-miss that must resolve to null.
- **Album detail** — metadata, full tracklist (`items`), cover art, similar albums via `GET /albums/{id}?include=items,artists,coverArt,similarAlbums` ✓ Task 4 (`albumDetailFrom`) + Task 7 (UI). Uses `Albums_Attributes.title/albumType/releaseDate/explicit`.
- **Track credits + lyrics** via `GET /tracks/{id}?include=credits,lyrics,similarTracks,artists` ✓ Task 5 (`trackDetailFrom`, `Credits_Attributes.name/role`, `Lyrics_Attributes.text/lrcText`) + Task 8 (context-menu action + `TrackInfoSheet`).
- **Reusable artwork helper** (album+artist+track) ✓ **Foundation Task 4** (`pickArtworkUrl` over `Artwork_File.href` + `Artwork_File_Meta.width/height`) — promoted to Slice-0 infrastructure so Slice 3 shares it without re-declaring; used by the official projections here via `artworkUrlFor`. The internal-v1 discography instead builds `resources.tidal.com/…/640x640.jpg` from the v1 `cover` uuid (`tidalV1CoverUrl`), which is a different, v1-only representation.
- **Merge strategy** (remote overlays local grouping when an id resolves; an artist with no exact Tidal match and no album that is not Tidal-backed shows no remote data) ✓ Tasks 6–8. Two pure merges, both mirroring the desktop and both unit-tested ✓ Task 3:
  - `dedupRemoteAlbums` — local-first + dedup-by-lowercase-title, mirroring `auxen/window.py:1394-1407` exactly, including the duplicate-within-remote case the probe actually hit (`Fixxxer (Remastered)` twice).
  - `mergeArtistTracks` — Tidal top tracks first, unique local tracks after, mirroring `auxen/window.py:1409-1422` including its `if tidal_top:` guard. Two documented divergences: the id compare is **scoped to Tidal tracks** (the desktop compares `source_id` across sources, which can delete a local file on a numeric collision; this codebase namespaces identity as `"${source}:${sourceId}"` everywhere — `ArtistDetailScreen.kt:78`/`:166`), and the result is `distinctBy` that same key because a duplicate key is a hard Compose crash and `topTracks` does not come through the pool's `distinctBy`.
- Streaming remains on internal-v1 and **search remains on internal-v1** — the new artist search is *on* internal-v1, which is where the constraint always put search; what is forbidden is routing search through the official API, and nothing does. Playback never touches official v2 (PREVIEW-only, re-confirmed on-device); `TidalAuth.kt` untouched and `TidalProvider.kt` additive-only — three new methods, **zero deleted lines**, asserted by `git diff --numstat` against `0d10ed5` in Task 8 Step 6; no Tidal SDK player module.

**No cursor paging in this slice.** `limit=50` in one call covers the discography, so `nextCursorFrom`/`TidalDocument.links` are NOT pulled forward from Slice 2 Task 1, which owns them. Declaring them here would be a Kotlin "conflicting declarations" compile error once Slice 2 lands.

**Placeholder scan:** Every logic step (Tasks 1–5) shows real code and real test assertions with real spec field names and real fixtures — including the two fixtures taken from what the API actually returned on 2026-07-16 (a `biography` linkage with an empty `included`; `Fixxxer (Remastered)` twice). The Compose/ViewModel-passthrough/Navigation steps (Tasks 6–8) give the real new state/flows, the real `LaunchedEffect`/collect wiring, the real nav-route registrations, and the essential composable structure, then close on on-device checkpoints — matching the Foundation plan's treatment of `TidalOfficialDebugScreen` (untested Compose, on-device proof). No `TODO`/"add error handling"/"similar to above" left in any logic step.

**Provenance of the internal-v1 request shapes** (nothing here is inferred from an endpoint's name):
| Request | Mirrors | Verified against |
| --- | --- | --- |
| `artists/{id}/albums?limit=50&offset=0&countryCode` | `Artist.get_albums(limit=50)` | `tidalapi/artist.py:131-137` (params, **no filter**; siblings at `:150-177` send `filter=EPSANDSINGLES`/`COMPILATIONS`), `request.py:76` (`countryCode`), desktop call at `providers/tidal.py:896` |
| `artists/{id}/toptracks?limit=50&offset=0&countryCode` | `Artist.get_top_tracks(limit=50)` | `tidalapi/artist.py:179-194` (path, params, *"sorted by popularity"*), envelope via `request.py:59-81` → `map_json` → `json_obj.get("items")` (`request.py:217`), desktop call at `providers/tidal.py:906` |
| `search?query&limit=20&offset=0&types=artists&countryCode` | `session.search(name, models=[Artist], limit=5)` | `tidalapi/session.py:800-807` (`GET search`, params), `:810` (response nests under `json_obj["artists"]`, then `["items"]`), `:187-190` + `:318-340` (`Case` aliasing ⇒ `types` is lowercase `artists` — **executed to confirm**), desktop call at `providers/tidal.py:860-862` |

`sessionId` is omitted from all three even though `basic_request` attaches it (`request.py:75`): this provider's existing `search` omits it and works (`TidalProvider.kt:110-119`), and consistency with the file's own working precedent beats consistency with tidalapi's.

**Type-consistency check:** `TrackRelations`/`trackRelationsFrom`/`trackRelations` (Task 1) consistent; the Foundation-owned `pickArtworkUrl(files: List<ArtworkFile>, targetPx)` / `ArtworkAttributes.files` are consumed consistently by `artworkUrlFor(...)` across Tasks 3–5 (no re-declaration here); `parseIsoDurationSeconds`/`releaseYearOf` produce `Double?`/`Int?` matching `Track.durationSeconds: Double?` and `AlbumSummary.releaseYear: Int?`, and `releaseYearOf` is reused for v1's `releaseDate` because both APIs render it "YYYY-MM-DD"; **`AlbumSummary` is produced by BOTH `albumSummaryFrom` (official v2, Task 4's `similarAlbums`) and `artistAlbumsFrom` (internal v1, Task 3's discography)** — one card type, two sources, which is what lets Task 6 render them in one `LazyRow`; **`model.Track` is likewise produced by BOTH `trackFrom` (official v2, Tasks 4–5) and the provider's own `toTrack` (internal v1, Task 3's top tracks)** — one track type, two sources, both `Track(source = Source.TIDAL, sourceId = <catalog id>)`, which is what lets `mergeArtistTracks` merge them with local tracks and lets every one of them play through the existing internal-v1 pipeline unchanged; `TidalArtistDetail`/`TidalAlbumDetail`/`TidalTrackDetail` names match between their projection files, the repository methods (`artistDetail`/`albumDetail`/`trackDetail`), the `PlayerViewModel` flows, and the screens; `TidalArtistDetail` has **no `albums` and no `tracks` field** and `ARTIST_DETAIL_INCLUDE` has neither entry — consistent with both lists arriving via `PlayerViewModel.artistAlbums`/`.artistTopTracks` from internal-v1, and with `TidalMetadataRepository`'s frozen constructor (which holds no `TidalProvider` and therefore cannot compose them itself); Slice 1's `ARTIST_DETAIL_INCLUDE` is distinct from the Foundation's merged `ARTIST_INCLUDE` (`biography`/`similarArtists`/`albums`, used by `artistOverview`), which this slice does not touch; include constants (`ARTIST_DETAIL_INCLUDE`/`BIOGRAPHY_INCLUDE`/`ALBUM_DETAIL_INCLUDE`/`TRACK_DETAIL_INCLUDE`/`TRACK_RELATIONS_INCLUDE`) each match their endpoint's `include` param and the relationship names read back by the projection (`profileArt`/`biography`/`similarArtists`; `items`/`artists`/`coverArt`/`similarAlbums`; `credits`/`lyrics`/`similarTracks`/`artists`); `trackRequest`'s new default args keep the existing `getTrack`/`TidalOfficialEndpointsTest` call sites compiling and producing the unchanged bare-track URL; `getTrackMetadata`/`getAlbum`/`getArtistBiography` mirror the Foundation's `getArtist` signature (`Result<TidalDocument>`).

**Import check** (per-file resolution; a missing import has bitten this series): `TidalProvider`'s three new methods need **no new imports** — `AlbumSummary`/`artistAlbumsUrl`/`artistAlbumsFrom`/`artistTopTracksUrl`/`artistSearchUrl`/`artistIdFromSearch` are same-package, `json`/`validToken`/`get`/`countryCode`/`API_BASE`/`SearchResponse`/`TrackDto`/`toTrack` are its own members, `Track` is imported at `TidalProvider.kt:5` and `kotlinx.serialization.decodeFromString` at `:13`, and `HttpUrl` is never named (the builders' return value is only `.toString()`-ed). `TidalInternalArtist.kt` imports `kotlinx.serialization.Serializable`, `kotlinx.serialization.json.Json`, `okhttp3.HttpUrl` and `okhttp3.HttpUrl.Companion.toHttpUrl` for its return types and builders — a `Json` receiver needs no `kotlinx.serialization.decodeFromString` import (cf. `TidalJsonApiTest`). `PlayerViewModel.kt` already imports `Graph` (`:22`) and `Track` (`:29`), so `artistTopTracks: MutableStateFlow<List<Track>>` and `Graph.tidal` resolve as-is (`Graph.tidal: TidalProvider` is public, `AuxenApp.kt:144`). `ArtistDetailScreen.kt` must ADD `io.github.auxen.provider.tidal.mergeArtistTracks` alongside `dedupRemoteAlbums`, plus `androidx.compose.runtime.LaunchedEffect` and `io.github.auxen.model.Source`; `remember`/`collectAsState`/`Track`/`AlbumCard` are already imported there.

**Name-collision sweep** (the "never re-declare" rule), run repo-wide over `*.kt` for this revision's new symbols: `mergeArtistTracks`, `artistTopTracksUrl`, `artistSearchUrl`, `artistIdFromSearch`, `TidalInternalArtist`, `TidalV1SearchResponse`, `TidalV1SearchArtist`, `TidalV1ArtistsPage`, `searchArtistIdExact`, `artistTopTracks`, `topTracks` — **zero hits each**, all net-new. The one intentional *re-use* (not re-declaration) is `TidalProvider`'s private `SearchResponse`/`TrackDto`/`toTrack`, consumed in place by `artistTopTracks`.
