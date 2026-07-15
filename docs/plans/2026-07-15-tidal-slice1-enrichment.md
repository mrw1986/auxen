# Slice 1 — Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the currently local-only `ArtistDetailScreen` and `AlbumDetailScreen` into rich, remote-backed screens (biography, similar artists, full discography, top tracks, real album metadata + full tracklist + cover art), and add track credits + lyrics — all sourced from the sanctioned official `openapi.tidal.com/v2` metadata API through the Slice-0 Foundation seam. Streaming and search stay untouched on the internal `api.tidal.com/v1` path.

**Architecture:** Additive overlay. The local MediaStore-derived grouping (`groupAlbums`/`groupArtists` over the `localTracks + favorites` pool) remains the offline-safe base of both detail screens. When the pool contains a Tidal-backed track (`Track.source == TIDAL`), the screen resolves a Tidal catalog id and overlays official metadata on top of the local grouping; a local-only artist/album shows exactly what it shows today (no remote sections). Every official call goes through the Foundation seams: `TidalOfficialSession.validAccessToken()` → `TidalOfficialClient` → the generic JSON:API layer (`TidalDocument`/`TidalDocumentView`/`decodeAttributes` in `TidalJsonApi.kt`) → a **pure** `xFrom(doc, json, …)` projection to a net-new domain type → a thin, un-unit-tested suspend wrapper on `TidalMetadataRepository` (held at `Graph.tidalMetadata`). New domain types are net-new — the codebase has only `AlbumGroup`/`ArtistGroup` grouping structs today, no `Album`/`Artist`/`TrackDetail` domain classes.

**The resolution chain (documented, and proven on-device by Task 1's gate):**

```
Tidal-sourced Track (Track.source == TIDAL, Track.sourceId = catalog track id)
        │  GET /tracks/{sourceId}?include=artists,albums   (TidalMetadataRepository.trackRelations)
        ▼
TrackRelations(artistId, albumId, isrc)
        ├─ GET /artists/{artistId}?include=biography,similarArtists,albums,tracks,profileArt  → TidalArtistDetail
        ├─ GET /albums/{albumId}?include=items,artists,coverArt,similarAlbums                 → TidalAlbumDetail
        └─ GET /tracks/{sourceId}?include=credits,lyrics,similarTracks,artists                → TidalTrackDetail
```

**id-equivalence — the gate (Task 1).** Everything above assumes an internal-v1 track/artist/album id **is** the same Tidal catalog id the official v2 API expects. Both are Tidal catalog ids, so they should match, but this is proven on-device before any enrichment screen is built. **Documented contingency if they ever diverge:** the internal track carries an `isrc` (from `Tracks_Attributes.isrc`) and albums carry `barcodeId` (from `Albums_Attributes.barcodeId`); a future fallback would resolve the official id by ISRC/barcode via `GET /searchResults/{query}` rather than by direct id. This fallback is **not built** in Slice 1 — it is the recovery path if Task 1's gate fails.

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), OkHttp (raw), AndroidX DataStore (via Foundation), Jetpack Compose + Navigation-Compose, JUnit4 + Robolectric. manual `Graph` service-locator DI (no Retrofit, no Dagger/Hilt). minSdk 26 / compileSdk 35 / Media3 1.5.1. `java.time.Duration.parse` is available at minSdk 26.

## Global Constraints

- **Metadata only.** NEVER route playback or search through the official path. Playable tracks projected from official documents are ordinary `Track(source = Source.TIDAL, sourceId = <catalog id>)` values that play through the *existing* internal-v1 pipeline (`Graph.mediaItemFor` → `auxen://tidal/<id>` → `TrackResolver` → `TidalProvider.getStreamInfoById`). No new playback code.
- **Internal-v1 code (`TidalAuth.kt`, `TidalProvider.kt`) is untouched.** Confirm with `git status` at the end (Task 8).
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

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalMetadataResolutionTest*' --tests '*TidalOfficialEndpointsTest*'`
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

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalMetadataResolutionTest*' --tests '*TidalOfficialEndpointsTest*'`
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

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalMetadata*' --tests '*TidalOfficial*' assembleDebug`
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

Tasks 3–5 below call these directly: `artworkUrlFor` calls `pickArtworkUrl`; `trackFrom` calls `parseIsoDurationSeconds`; `albumSummaryFrom` calls `releaseYearOf`. **No `TidalArtwork.kt` and no `TidalMetadataPrimitives.kt` are created in this slice** (the Foundation owns them). There is no commit for this task — it is a dependency note; the primitives ship with the Foundation, which must merge first.

---

### Task 3: Artist detail projection — biography, similar artists, discography, top tracks, profile art

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataDetail.kt` (domain types + shared projection helpers + `artistDetailFrom`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt` (add `artistDetail(...)` + `ARTIST_DETAIL_INCLUDE` + `ARTWORK_TARGET_PX`)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalArtistDetailTest.kt` (create)

**Interfaces:**
- Consumes: `TidalDocument`/`TidalDocumentView`/`decodeAttributes`; `ArtistAttributes`/`ArtistBiographyAttributes`/`AlbumAttributes` (Foundation); `TrackAttributes` (spike DTO); `pickArtworkUrl`/`ArtworkAttributes`/`parseIsoDurationSeconds`/`releaseYearOf` (Foundation Task 4); `TidalOfficialClient.getArtist`.
- Produces: `data class ArtistLink(id, name, artworkUrl)`; `data class AlbumSummary(id, title, albumType, releaseYear, explicit, artworkUrl)`; `data class TidalArtistDetail(id, name, biography, artworkUrl, albums, topTracks, similarArtists)`; `fun artistDetailFrom(doc, json, artworkTargetPx): TidalArtistDetail?`; the shared private helpers `TidalDocumentView.artworkUrlFor(...)`, `trackFrom(...)`, `albumSummaryFrom(...)`; `TidalMetadataRepository.artistDetail(artistId): Result<TidalArtistDetail>`.

- [ ] **Step 1: Write the failing test**

Create `TidalArtistDetailTest.kt`. Fixture uses real relationship names (`profileArt`, `biography`, `similarArtists`, `albums`, `tracks`) and attribute names:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
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
              "similarArtists": { "data": [ { "id": "200", "type": "artists" }, { "id": "201", "type": "artists" } ] },
              "albums":         { "data": [ { "id": "300", "type": "albums" }, { "id": "301", "type": "albums" } ] },
              "tracks":         { "data": [ { "id": "9001", "type": "tracks" } ] }
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
            { "id": "201", "type": "artists", "attributes": { "name": "Com Truise" } },
            { "id": "300", "type": "albums",  "attributes": { "title": "Album One", "albumType": "ALBUM", "releaseDate": "2019-03-01", "explicit": true } },
            { "id": "301", "type": "albums",  "attributes": { "title": "Album Two", "albumType": "EP", "releaseDate": "2021-11-05", "explicit": false } },
            { "id": "9001", "type": "tracks", "attributes": { "title": "Hit Single", "duration": "PT3M20S", "explicit": true } }
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
    fun `artistDetailFrom maps similar artists and the discography with parsed years`() {
        val d = artistDetailFrom(json.decodeFromString(artistDoc), json, artworkTargetPx = 640)!!
        assertEquals(listOf("Trevor Something", "Com Truise"), d.similarArtists.map { it.name })
        assertEquals(listOf("Album One", "Album Two"), d.albums.map { it.title })
        assertEquals(listOf(2019, 2021), d.albums.map { it.releaseYear })
        assertEquals(listOf("ALBUM", "EP"), d.albums.map { it.albumType })
        assertEquals(listOf(true, false), d.albums.map { it.explicit })
        // Nested album coverArt is NOT include-able flatly from an artist doc.
        assertTrue(d.albums.all { it.artworkUrl == null })
    }

    @Test
    fun `artistDetailFrom builds playable Tidal top tracks`() {
        val d = artistDetailFrom(json.decodeFromString(artistDoc), json, artworkTargetPx = 640)!!
        val top = d.topTracks.single()
        assertEquals(Source.TIDAL, top.source)
        assertEquals("9001", top.sourceId)
        assertEquals("Hit Single", top.title)
        assertEquals("Prompto", top.artist)
        assertEquals(200.0, top.durationSeconds!!, 0.0)
        assertTrue(top.explicit)
    }

    @Test
    fun `artistDetailFrom tolerates an artist with no relationships`() {
        val doc = """{ "data": { "id": "5", "type": "artists", "attributes": { "name": "Solo" } }, "included": [] }"""
        val d = artistDetailFrom(json.decodeFromString(doc), json, artworkTargetPx = 640)!!
        assertEquals("Solo", d.name)
        assertNull(d.biography)
        assertNull(d.artworkUrl)
        assertTrue(d.albums.isEmpty())
        assertTrue(d.topTracks.isEmpty())
        assertTrue(d.similarArtists.isEmpty())
    }

    @Test
    fun `artistDetailFrom returns null when there is no primary artist`() {
        assertNull(artistDetailFrom(json.decodeFromString("""{ "data": [], "included": [] }"""), json, 640))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalArtistDetailTest*'`
Expected: FAIL — `artistDetailFrom`/`TidalArtistDetail`/`AlbumSummary`/`ArtistLink` unresolved.

- [ ] **Step 3: Create `TidalMetadataDetail.kt` with the domain types + shared helpers + `artistDetailFrom`**

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

/** Full artist detail: header art + biography + discography + top tracks + similar artists. */
data class TidalArtistDetail(
    val id: String,
    val name: String,
    val biography: String?,
    val artworkUrl: String?,
    val albums: List<AlbumSummary>,
    val topTracks: List<Track>,
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

/** Project an `albums` resource into an [AlbumSummary] (coverArt resolved only when included in the document). */
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
 * Returns null when there is no primary artist. Note: `similarArtists` art and
 * discography `coverArt` are NOT flatly include-able from an artist document,
 * so those `artworkUrl`s are null here — the UI overlays local art / a glyph,
 * and a card tap fetches the full album document (with cover art).
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
            ?.decodeAttributes<ArtistBiographyAttributes>(json)?.text,
        artworkUrl = artworkUrl,
        albums = view.related(artist, "albums").map { albumSummaryFrom(it, view, json, artworkTargetPx) },
        topTracks = view.related(artist, "tracks").map {
            trackFrom(it, json, artistName = name, albumTitle = null, artworkUrl = artworkUrl)
        },
        similarArtists = view.related(artist, "similarArtists").map {
            ArtistLink(
                id = it.id,
                name = it.decodeAttributes<ArtistAttributes>(json)?.name.orEmpty(),
                artworkUrl = view.artworkUrlFor(it, "profileArt", json, artworkTargetPx),
            )
        },
    )
}
```

- [ ] **Step 4: Add `artistDetail` + constants to `TidalMetadataRepository.kt`**

Method (beside `artistOverview`):

```kotlin
    /** Full artist detail: biography, similar artists, discography, top tracks, profile art. */
    suspend fun artistDetail(artistId: String): Result<TidalArtistDetail> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))
        return client.getArtist(artistId, token, ARTIST_DETAIL_INCLUDE, countryCode).mapCatching { doc ->
            artistDetailFrom(doc, json, ARTWORK_TARGET_PX) ?: error("Artist $artistId not found")
        }
    }
```

Companion additions:

```kotlin
        val ARTIST_DETAIL_INCLUDE = listOf("biography", "similarArtists", "albums", "tracks", "profileArt")
        const val ARTWORK_TARGET_PX = 640
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalArtistDetailTest*'`
Expected: PASS (all cases).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataDetail.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalArtistDetailTest.kt
git commit -m "feat(android): Tidal artist detail projection (bio, similar, discography, top tracks)

artistDetailFrom projects a compound /artists/{id} document into
TidalArtistDetail; top tracks project to playable TIDAL Tracks. Repository
artistDetail() wraps validAccessToken -> getArtist -> projection.

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

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalAlbumDetailTest*' --tests '*TidalOfficialEndpointsTest*'`
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

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalAlbumDetailTest*' --tests '*TidalOfficialEndpointsTest*'`
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

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalTrackDetailTest*'`
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

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalTrackDetailTest*'`
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
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt` (overlay biography, remote art, remote discography, top tracks, similar artists)

**Interfaces:**
- Consumes: `TidalMetadataRepository.trackRelations`, `.artistDetail`, `.albumDetail` (Tasks 1/3/4).
- Produces: `PlayerViewModel.artistDetail: StateFlow<TidalArtistDetail?>`, `.artistDetailLoading: StateFlow<Boolean>`, `fun loadArtistDetail(artistName, tidalArtistId, seedTidalTrackId)`; `PlayerViewModel.albumDetail: StateFlow<TidalAlbumDetail?>`, `.albumDetailLoading`, `fun loadAlbumDetail(album, artist, tidalAlbumId, seedTidalTrackId)` (album load is defined here, consumed by Task 7).

- [ ] **Step 1: Add the enrichment state + load functions to `PlayerViewModel.kt`**

Add (imports `TidalArtistDetail`/`TidalAlbumDetail` from `io.github.auxen.provider.tidal`):

```kotlin
    // ---- Slice 1: official-API detail enrichment (overlays local grouping) ----
    val artistDetail = MutableStateFlow<io.github.auxen.provider.tidal.TidalArtistDetail?>(null)
    val artistDetailLoading = MutableStateFlow(false)
    private var artistDetailKey: String? = null

    val albumDetail = MutableStateFlow<io.github.auxen.provider.tidal.TidalAlbumDetail?>(null)
    val albumDetailLoading = MutableStateFlow(false)
    private var albumDetailKey: String? = null

    /**
     * Load remote artist enrichment. If [tidalArtistId] is known (a similar-artist
     * tap) fetch it directly; else resolve the artist id from a representative
     * Tidal track ([seedTidalTrackId]) in the screen's pool. A local-only artist
     * (no id, no seed) clears the overlay — the screen stays local. Keyed so a
     * recomposition/re-navigation to the same artist does not refetch.
     */
    fun loadArtistDetail(artistName: String, tidalArtistId: String?, seedTidalTrackId: String?) {
        val key = "$artistName|$tidalArtistId|$seedTidalTrackId"
        if (key == artistDetailKey) return
        artistDetailKey = key
        artistDetail.value = null
        if (tidalArtistId == null && seedTidalTrackId == null) return
        viewModelScope.launch {
            artistDetailLoading.value = true
            try {
                val artistId = tidalArtistId
                    ?: Graph.tidalMetadata.trackRelations(seedTidalTrackId!!).getOrNull()?.artistId
                    ?: return@launch
                Graph.tidalMetadata.artistDetail(artistId).onSuccess { artistDetail.value = it }
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
    val seedTidalTrackId = tracks.firstOrNull { it.isTidal }?.sourceId
    LaunchedEffect(artist, tidalArtistId, seedTidalTrackId) {
        viewModel.loadArtistDetail(artist, tidalArtistId, seedTidalTrackId)
    }
```

- Header art model becomes `detail?.artworkUrl ?: tracks.firstNotNullOfOrNull { it.albumArtUrl }` (remote profile art wins when present).
- After the Play All / Shuffle row, add a **Biography** section when `detail?.biography` is non-null: a `SectionHeader("Biography")` + an expandable `Text` (collapsed `maxLines = 4`, `overflow = TextOverflow.Ellipsis`, a `remember { mutableStateOf(false) }` toggled by `Modifier.clickable`, `MaterialTheme.typography.bodyMedium`). This mirrors the desktop artist-bio surface.
- **Albums** section: prefer the remote discography when present. `val remoteAlbums = detail?.albums.orEmpty()`. If non-empty, render an `AlbumCard` per `AlbumSummary` in the existing `LazyRow` (`Modifier.width(150.dp)`), `source = Source.TIDAL`, `onClick = { onOpenAlbum(summary.title, detail!!.name, summary.id) }`, `onPlay = null` (no local tracks to enqueue). Otherwise fall back to the existing local `albums` (`AlbumGroup`) row, whose `onClick` calls `onOpenAlbum(album.album, album.albumArtist, null)`.
- **Similar artists** section (new, when `detail?.similarArtists` non-empty): a `LazyRow` of `ArtistRow`-style circular cards or a simple `AlbumCard`-like column; each `onClick = { onOpenArtist(link.name, link.id) }`.
- **Tracks** section: keep the existing local `tracks` list as the primary track list (it is what the user has locally + favorited). Optionally, when the local `tracks` list is empty but `detail?.topTracks` is non-empty (a Tidal-only artist reached via a similar-artist tap with no local tracks), render `detail!!.topTracks` through `AuxenTrackRow` with `onPlay = { viewModel.play(it) }` so the screen is never empty for a valid remote artist. Guard the `EmptyState` accordingly.

- [ ] **Step 4: Build**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: PASS; APK builds. (No new unit tests here — logic is covered by Tasks 2–5; this step is a compile+existing-suite gate. If a Roborazzi golden for a detail/component screen shifts, re-record with `./gradlew recordRoborazziDebug` and eyeball the diff.)

- [ ] **Step 5: On-device checkpoint (artist enrichment)**

Build the APK **locally**, install, log in on both Tidal paths. Open an artist that has Tidal tracks in your library/favorites (from Library → Artists, or by opening a Tidal track's artist). Confirm: circular profile artwork loads, a **Biography** section appears and expands, a **Similar artists** row appears and tapping one opens that artist (remote-only, via `tidalArtistId`), the **Albums** row shows the remote discography and tapping an album opens album detail. Open a **local-only** artist and confirm it looks exactly as before (no remote sections, no spinner stuck). Paste a screenshot/observations to close the loop.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt
# plus any re-recorded golden under app/src/test/.../roborazzi/ if Step 4 changed it
git commit -m "feat(android): remote-backed artist detail (bio, similar, discography, top tracks)

ArtistDetailScreen overlays official-API artist metadata on the local
grouping when a Tidal track seeds a catalog id; optional tidalArtistId nav
arg enables similar-artist drill-in. Local-only artists unchanged.

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

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
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

Run: `cd android && ./gradlew testDebugUnitTest verifyRoborazziDebug assembleDebug`
Expected: PASS. If the `TrackActionSheet` component golden (`TrackActionSheetUiTest`) shifts because the new Tidal-only action row renders in a Tidal fixture, re-record with `./gradlew recordRoborazziDebug` and eyeball the diff; a LOCAL fixture must be unaffected (the row is gated on `track.source == TIDAL`).

- [ ] **Step 6: On-device checkpoint + internal-v1 untouched confirmation (slice close-out)**

Locally-built APK: long-press a Tidal track (in album/artist detail) → "Credits & lyrics" → confirm the sheet shows grouped credits and lyrics. Long-press a LOCAL track → confirm the "Credits & lyrics" action is absent. Then confirm the internal-v1 path was never touched:

```bash
git status --short android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalAuth.kt \
                   android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt
```

Expected: no output (both unchanged across the whole slice). Paste observations + this confirmation to close the slice.

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

**Spec coverage:**
- **id-equivalence gate** (the slice's stated critical first task) ✓ Task 1: `trackRelations` (`GET /tracks/{id}?include=artists,albums`) + on-device probe reusing the Foundation debug screen; ISRC/`barcodeId` fallback documented in Architecture as the contingency if the gate fails; resolution chain (Tidal Track → artist id + album id) documented and exercised.
- **Artist detail** — biography, similar artists, discography, top tracks, profile art via `GET /artists/{id}?include=biography,similarArtists,albums,tracks,profileArt` ✓ Task 3 (`artistDetailFrom`) + Task 6 (UI). Spec schemas used: `Artists_Attributes.name`, `ArtistBiographies_Attributes.text/source`, `Albums_Attributes.title/albumType/releaseDate/explicit`, `Artworks_Attributes.files`, `Tracks_Attributes.title/duration/explicit`.
- **Album detail** — metadata, full tracklist (`items`), cover art, similar albums via `GET /albums/{id}?include=items,artists,coverArt,similarAlbums` ✓ Task 4 (`albumDetailFrom`) + Task 7 (UI).
- **Track credits + lyrics** via `GET /tracks/{id}?include=credits,lyrics,similarTracks,artists` ✓ Task 5 (`trackDetailFrom`, `Credits_Attributes.name/role`, `Lyrics_Attributes.text/lrcText`) + Task 8 (context-menu action + `TrackInfoSheet`).
- **Reusable artwork helper** (album+artist+track) ✓ **Foundation Task 4** (`pickArtworkUrl` over `Artwork_File.href` + `Artwork_File_Meta.width/height`) — promoted to Slice-0 infrastructure so Slice 3 shares it without re-declaring; used by all three projections here via `artworkUrlFor`.
- **Merge strategy** (remote overlays local grouping when Tidal-backed; local-only shows no remote data) ✓ Tasks 6–8.
- Streaming/search remain on internal-v1; `TidalAuth.kt`/`TidalProvider.kt` untouched (asserted in Task 8 Step 6); no Tidal SDK player module.

**Placeholder scan:** Every logic step (Tasks 1–5) shows real code and real test assertions with real spec field names and real fixtures. The Compose/ViewModel-passthrough/Navigation steps (Tasks 6–8) give the real new state/flows, the real `LaunchedEffect`/collect wiring, the real nav-route registrations, and the essential composable structure, then close on on-device checkpoints — matching the Foundation plan's treatment of `TidalOfficialDebugScreen` (untested Compose, on-device proof). No `TODO`/"add error handling"/"similar to above" left in any logic step.

**Type-consistency check:** `TrackRelations`/`trackRelationsFrom`/`trackRelations` (Task 1) consistent; the Foundation-owned `pickArtworkUrl(files: List<ArtworkFile>, targetPx)` / `ArtworkAttributes.files` are consumed consistently by `artworkUrlFor(...)` across Tasks 3–5 (no re-declaration here); `parseIsoDurationSeconds`/`releaseYearOf` produce `Double?`/`Int?` matching `Track.durationSeconds: Double?` and `AlbumSummary.releaseYear: Int?`; the shared `trackFrom(...)` returns `io.github.auxen.model.Track(source = Source.TIDAL, sourceId = <catalog id>)` in all three projections, so every projected track plays through the existing internal-v1 pipeline unchanged; `TidalArtistDetail`/`TidalAlbumDetail`/`TidalTrackDetail` names match between their projection files, the repository methods (`artistDetail`/`albumDetail`/`trackDetail`), the `PlayerViewModel` flows, and the screens; include constants (`ARTIST_DETAIL_INCLUDE`/`ALBUM_DETAIL_INCLUDE`/`TRACK_DETAIL_INCLUDE`/`TRACK_RELATIONS_INCLUDE`) each match their endpoint's `include` param and the relationship names read back by the projection (`profileArt`/`biography`/`similarArtists`/`albums`/`tracks`; `items`/`artists`/`coverArt`/`similarAlbums`; `credits`/`lyrics`/`similarTracks`/`artists`); `trackRequest`'s new default args keep the existing `getTrack`/`TidalOfficialEndpointsTest` call sites compiling and producing the unchanged bare-track URL; `getTrackMetadata`/`getAlbum` mirror the Foundation's `getArtist` signature (`Result<TidalDocument>`).
