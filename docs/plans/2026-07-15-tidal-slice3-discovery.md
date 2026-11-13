# Tidal Slice 3 — Discovery (Home mixes) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Tidal-powered recommendation sections — My Mixes, Discovery mixes, New-Arrival mixes — to the Home screen, below the existing local sections. Each mix renders as a titled carousel of tracks with artwork; tapping a track plays the mix through the **internal-v1** pipeline (the official API supplies metadata only). The whole surface degrades to nothing (no rows, no error) when logged out of the official API or on any fetch error, so the existing local-only Home never regresses.

**Architecture:** Additive, on top of the Slice-0 Foundation. New pure code lives in `provider/tidal/TidalDiscovery.kt` (domain types + attribute DTOs + document→domain projections). New request builders extend `TidalOfficialEndpoints`; new fetch methods extend `TidalOfficialClient`; a new `discovery()` suspend method extends `TidalMetadataRepository` (held at `Graph.tidalMetadata`). The Home surface is a new set of sections in `HomeScreen.kt` driven by a new `tidalDiscovery` flow + `refreshDiscovery()` / `playMix()` on `PlayerViewModel`. Every parsing/mapping function is a pure function unit-tested against a real compound-document fixture; the network orchestration and the Compose sections follow the Foundation's untested-network / untested-Compose precedent and close on an on-device checkpoint. Streaming and search stay entirely on internal-v1 (`TidalProvider`) — untouched.

**Dependencies & merge order.** This slice consumes the Foundation's shared primitives (`ArtworkAttributes`/`ArtworkFile`/`ArtworkFileMeta` + `pickArtworkUrl`, `parseIsoDurationSeconds`, the `locale` constructor param) and **Slice 1's** `TRACK_RELATIONS_INCLUDE` companion constant + `trackFrom` conventions — none re-declared here. So the merge order is **Foundation → Slice 1 → Slice 3**, and the Slice-1 id-equivalence gate must be green on-device before `playMix` is trusted.

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), OkHttp, kotlinx.coroutines (`coroutineScope`/`async`/`awaitAll`), AndroidX DataStore (via the Foundation session), Compose (Home), JUnit4 (pure-logic tests; no Robolectric needed — the projections are Android-free). minSdk 26 / compileSdk 35 / Media3 1.5.1.

## Global Constraints

- **Metadata only.** The official `openapi.tidal.com/v2` API is used solely to *discover* mixes and *describe* their tracks. Playback and search stay on internal-v1 (`TidalProvider`). NEVER route playback or search through the official path; NEVER use the Tidal SDK player module.
- **Playback handoff = id equivalence.** A mix item's `trackId` is the Tidal **catalog id**. Playback works by projecting the item to `Track(source = TIDAL, sourceId = trackId)` and calling the existing `Graph.mediaItemFor` → `auxen://tidal/<id>` → `TrackResolver.getStreamInfoById` path. **This assumes the official catalog id equals the internal-v1 track id — the equivalence Slice 1 (Enrichment) proves on-device.** If Slice 1 finds they differ, this slice must resolve items by ISRC (`TrackAttributes.isrc` is already carried) before playback; that is an explicit dependency, tracked in Open Questions.
- **PKCE public client — NEVER send `client_secret`** on any official request. Requests are built only through `TidalOfficialEndpoints`, which adds only `Authorization: Bearer <token>` + `Accept: application/vnd.api+json`.
- **Never log or print `accessToken`/`refreshToken`.** Only human-readable names/titles (mix titles, track titles, artist names) may appear in logs or UI status.
- **Every official call goes through `TidalOfficialSession.validAccessToken()`** (the Foundation's refresh-aware accessor). When it returns null, `discovery()` fails fast with a "not logged in" result and the UI shows nothing.
- **Do not unit-test live HTTP execution.** Unit-test pure logic only: request shapes, JSON:API projections, hydration stitching, the item→Track handoff. Network orchestration (`discovery()`), the VM passthrough, and Compose are verified by the Step on-device checkpoint (Task 5).
- **New classes stay plain instantiable classes on `Graph`.** No JVM-wide `object`s with mutable state. Pure functions are top-level and stateless.
- **Internal-v1 code (`TidalAuth.kt`, `TidalProvider.kt`) is untouched.** Confirm with `git status` at the end.
- **Bounded fan-out.** Mixes are capped per category and items per mix are capped, so one Home load makes a bounded number of calls (recommendations + N mix calls + 2 batched hydration calls). Hydration failures degrade to title-only rows, they never fail the feed.
- Every code step below shows the actual code. No `TODO` / "add error handling" / "similar to above" placeholders.

### Spec facts this slice is built on (verified against `.superpowers/sdd/tidal-openapi-v2.json`, v1.10.55)

- `GET /userRecommendations/{id}` — `id` is `me` for the caller. Query params: `include` (options: `myMixes`, `discoveryMixes`, `newArrivalMixes`, `offlineMixes`) and `locale` (BCP-47). `UserRecommendations_Attributes` is empty; the payload is its **relationships**.
- `GET /userDailyMixes/{id}`, `GET /userDiscoveryMixes/{id}`, `GET /userNewReleaseMixes/{id}` — each has an `items` relationship and an empty `*_Attributes` object. Query params: `include` (option: `items`) and `locale`. **Mixes carry no title in the spec** → titles are derived (`mixFallbackTitle`), with a defensive read of a live `title`/`subTitle` if the API sends one.
- `GET /tracks` — batch endpoint. Query params: `filter[id]` (array; repeated `filter[id]=...`), `include` (options include `artists`, `albums`), `countryCode` (ISO-3166 alpha-2). `Tracks_Attributes`: `title`, `duration` (ISO-8601, e.g. `PT2M58S`), `isrc`, `explicit`. Tracks have **no** artwork relationship — cover art comes from the track's album.
- `GET /albums` — batch endpoint. `filter[id]` (array), `include` (options include `coverArt`), `countryCode`. `Albums_Attributes.title`. `coverArt` → `artworks` resources; `Artworks_Attributes.files` is `[{ href, meta:{ width, height } }]`.
- Real JSON:API resource `type` strings (from the spec's `Included` discriminator mapping): `userRecommendations`, `userDailyMixes`, `userDiscoveryMixes`, `userNewReleaseMixes`, `tracks`, `albums`, `artists`, `artworks`.

---

### Task 1: Discovery request builders + client fetch methods

Adds the four request builders this slice needs to `TidalOfficialEndpoints` (recommendations, one mix, batched tracks, batched albums) and the matching `Result<TidalDocument>` fetch methods to `TidalOfficialClient`. Request SHAPE is unit-tested (no execution), exactly like the existing `trackManifestRequest` tests; the client methods are thin passthroughs over the Foundation's generic `execute<T>` and are not unit-tested (network precedent).

**Item vs batch — don't wire the wrong one.** These are the **batch** endpoints (`tracksRequest`/`albumsRequest` → `GET /tracks?filter[id]=…` / `GET /albums?filter[id]=…`, plural, repeated-id array, `getTracks`/`getAlbums`). Slice 1 owns the **single-item** endpoints (`trackRequest`/`albumRequest` → `GET /tracks/{id}` / `GET /albums/{id}`, path id, `getTrackMetadata`/`getAlbum`). Hydration here uses the batch pair; single-item detail lives in Slice 1. They coexist without collision.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt`
- Test (create): `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalDiscoveryEndpointsTest.kt`

**Interfaces:**
- Consumes: `TidalOfficialEndpoints.authed(url, accessToken)` (private helper, already present); `TidalOfficialEndpoints.BASE`; `TidalDocument` (Foundation Task 2); `TidalOfficialClient.execute<T>` (Foundation, private generic).
- Produces: `recommendationsRequest(accessToken, include, locale?)`, `mixRequest(mixType, id, accessToken, include, locale?)`, `tracksRequest(ids, accessToken, include, countryCode?)`, `albumsRequest(ids, accessToken, include, countryCode?)` on `TidalOfficialEndpoints`; `getRecommendations(...)`, `getMix(...)`, `getTracks(...)`, `getAlbums(...)` returning `Result<TidalDocument>` on `TidalOfficialClient`.

- [ ] **Step 1: Write the failing request-shape test**

Create `TidalDiscoveryEndpointsTest.kt`:

```kotlin
package io.github.auxen.provider.tidal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Request-shape tests for the Slice-3 (Discovery) builders on
 * [TidalOfficialEndpoints]: pure request BUILDING, no execution, matching
 * TidalOfficialEndpointsTest. Query params matter here specifically: the
 * recommendations/mix endpoints take `locale` (BCP-47) while the batched
 * `/tracks` and `/albums` endpoints take `countryCode` (ISO-3166 alpha-2),
 * and `filter[id]` is a repeated array parameter.
 */
class TidalDiscoveryEndpointsTest {

    @Test
    fun `recommendationsRequest targets me with include and locale`() {
        val req = TidalOfficialEndpoints.recommendationsRequest(
            accessToken = "tok",
            include = listOf("myMixes", "discoveryMixes", "newArrivalMixes"),
            locale = "en-US",
        )
        assertEquals("/v2/userRecommendations/me", req.url.encodedPath)
        assertEquals(listOf("myMixes", "discoveryMixes", "newArrivalMixes"), req.url.queryParameterValues("include"))
        assertEquals("en-US", req.url.queryParameter("locale"))
        assertEquals("Bearer tok", req.header("Authorization"))
        assertEquals("application/vnd.api+json", req.header("Accept"))
    }

    @Test
    fun `mixRequest builds the mix-type path with items include and locale`() {
        val req = TidalOfficialEndpoints.mixRequest(
            mixType = "userDailyMixes", id = "42", accessToken = "tok",
            include = listOf("items"), locale = "nb-NO",
        )
        assertEquals("/v2/userDailyMixes/42", req.url.encodedPath)
        assertEquals(listOf("items"), req.url.queryParameterValues("include"))
        assertEquals("nb-NO", req.url.queryParameter("locale"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mixRequest rejects an unknown mix type`() {
        TidalOfficialEndpoints.mixRequest("tracks", "1", "tok", listOf("items"), null)
    }

    @Test
    fun `tracksRequest repeats filter id and carries include and country code`() {
        val req = TidalOfficialEndpoints.tracksRequest(
            ids = listOf("1", "2"), accessToken = "tok",
            include = listOf("artists", "albums"), countryCode = "US",
        )
        assertEquals("/v2/tracks", req.url.encodedPath)
        assertEquals(listOf("1", "2"), req.url.queryParameterValues("filter[id]"))
        assertEquals(listOf("artists", "albums"), req.url.queryParameterValues("include"))
        assertEquals("US", req.url.queryParameter("countryCode"))
    }

    @Test
    fun `albumsRequest repeats filter id and includes coverArt`() {
        val req = TidalOfficialEndpoints.albumsRequest(
            ids = listOf("300"), accessToken = "tok",
            include = listOf("coverArt"), countryCode = "US",
        )
        assertEquals("/v2/albums", req.url.encodedPath)
        assertEquals(listOf("300"), req.url.queryParameterValues("filter[id]"))
        assertEquals(listOf("coverArt"), req.url.queryParameterValues("include"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalDiscoveryEndpointsTest*'`
Expected: FAIL — `recommendationsRequest` / `mixRequest` / `tracksRequest` / `albumsRequest` unresolved reference.

- [ ] **Step 3: Add the four request builders**

In `TidalOfficialEndpoints.kt`, add these inside the `object TidalOfficialEndpoints` body (after the existing spike builders). `MIX_TYPES` guards the `mixRequest` path segment against anything other than the three real mix resource types:

```kotlin
    /** The three mix resource types whose id-endpoints expose an `items` relationship (spec `Included` discriminator). */
    private val MIX_TYPES = setOf("userDailyMixes", "userDiscoveryMixes", "userNewReleaseMixes")

    /**
     * `GET /userRecommendations/me` with `?include=...&locale=...` — the
     * discovery entry point. `me` resolves the authenticated caller (spec:
     * "Use `me` for the authenticated user's resource"). `locale` is BCP-47
     * (e.g. `en-US`) and optional; `include` selects the mix buckets
     * (`myMixes`/`discoveryMixes`/`newArrivalMixes`).
     */
    fun recommendationsRequest(accessToken: String, include: List<String>, locale: String? = null): okhttp3.Request {
        val url = "$BASE/userRecommendations/me".toHttpUrl().newBuilder()
            .apply { locale?.let { addQueryParameter("locale", it) } }
            .apply { include.forEach { addQueryParameter("include", it) } }
            .build()
        return authed(url, accessToken)
    }

    /**
     * `GET /{mixType}/{id}` with `?include=items&locale=...` — one mix's track
     * items. [mixType] is the resource-type path segment and MUST be one of
     * [MIX_TYPES] (guards against path injection / a stray relationship type).
     */
    fun mixRequest(
        mixType: String,
        id: String,
        accessToken: String,
        include: List<String>,
        locale: String? = null,
    ): okhttp3.Request {
        require(mixType in MIX_TYPES) { "Unknown mix type: $mixType" }
        val url = "$BASE/$mixType/$id".toHttpUrl().newBuilder()
            .apply { locale?.let { addQueryParameter("locale", it) } }
            .apply { include.forEach { addQueryParameter("include", it) } }
            .build()
        return authed(url, accessToken)
    }

    /**
     * `GET /tracks?filter[id]=..&include=..&countryCode=..` — batch-hydrate mix
     * items with artist + album. `filter[id]` is a repeated array param.
     * `countryCode` is ISO-3166 alpha-2 (regional catalog correctness).
     */
    fun tracksRequest(
        ids: List<String>,
        accessToken: String,
        include: List<String>,
        countryCode: String? = null,
    ): okhttp3.Request {
        val url = "$BASE/tracks".toHttpUrl().newBuilder()
            .apply { countryCode?.let { addQueryParameter("countryCode", it) } }
            .apply { include.forEach { addQueryParameter("include", it) } }
            .apply { ids.forEach { addQueryParameter("filter[id]", it) } }
            .build()
        return authed(url, accessToken)
    }

    /** `GET /albums?filter[id]=..&include=coverArt&countryCode=..` — batch cover art for hydrated albums. */
    fun albumsRequest(
        ids: List<String>,
        accessToken: String,
        include: List<String>,
        countryCode: String? = null,
    ): okhttp3.Request {
        val url = "$BASE/albums".toHttpUrl().newBuilder()
            .apply { countryCode?.let { addQueryParameter("countryCode", it) } }
            .apply { include.forEach { addQueryParameter("include", it) } }
            .apply { ids.forEach { addQueryParameter("filter[id]", it) } }
            .build()
        return authed(url, accessToken)
    }
```

(`toHttpUrl` and `Request` are already imported in the file; the fully-qualified `okhttp3.Request` return type also matches the file's imports — either form compiles. Keep `Request` if the import is present.)

- [ ] **Step 4: Add the four client fetch methods**

In `TidalOfficialClient.kt`, add inside the class (after `getTrackFile`). Each returns a generic compound document parsed by the existing private `execute<T>`:

```kotlin
    /** Fetch the user's recommendation buckets as a JSON:API compound document (see [TidalOfficialEndpoints.recommendationsRequest]). */
    suspend fun getRecommendations(accessToken: String, include: List<String>, locale: String? = null): Result<TidalDocument> =
        execute(TidalOfficialEndpoints.recommendationsRequest(accessToken, include, locale))

    /** Fetch one mix's items as a compound document (see [TidalOfficialEndpoints.mixRequest]). */
    suspend fun getMix(
        mixType: String,
        id: String,
        accessToken: String,
        include: List<String>,
        locale: String? = null,
    ): Result<TidalDocument> = execute(TidalOfficialEndpoints.mixRequest(mixType, id, accessToken, include, locale))

    /** Batch-fetch tracks by id with related resources (see [TidalOfficialEndpoints.tracksRequest]). */
    suspend fun getTracks(
        ids: List<String>,
        accessToken: String,
        include: List<String>,
        countryCode: String? = null,
    ): Result<TidalDocument> = execute(TidalOfficialEndpoints.tracksRequest(ids, accessToken, include, countryCode))

    /** Batch-fetch albums by id with cover art (see [TidalOfficialEndpoints.albumsRequest]). */
    suspend fun getAlbums(
        ids: List<String>,
        accessToken: String,
        include: List<String>,
        countryCode: String? = null,
    ): Result<TidalDocument> = execute(TidalOfficialEndpoints.albumsRequest(ids, accessToken, include, countryCode))
```

- [ ] **Step 5: Run test to verify it passes + compile the client**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalDiscoveryEndpointsTest*'`
Expected: PASS (all 5). The new client methods compile against `TidalDocument` (Foundation) and the private `execute<T>`.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalDiscoveryEndpointsTest.kt
git commit -m "feat(android): Tidal discovery request builders + client fetch methods

Adds recommendations/mix/batched-tracks/batched-albums request builders
(recommendations+mix take locale; tracks+albums take countryCode) and
their Result<TidalDocument> client methods. Metadata only; feeds the
Home mixes surface.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 2: Discovery domain model + recommendations/mix projections

Creates `TidalDiscovery.kt` with the discovery domain types, the one defensive DTO the spec's empty mix schema forces (`MixAttributes`), and the two pure projections: `mixRefsFrom` (recommendations → mix references grouped by category) and `mixFrom` (one mix document → a `TidalMix` with title-only items). Reuses `TrackAttributes` (spike DTO), the Foundation's `ArtistAttributes`/`AlbumAttributes`, and the Foundation's artwork DTOs + `pickArtworkUrl` + `parseIsoDurationSeconds` (`TidalMetadataPrimitives.kt`) — no duplicate DTOs or helpers.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalDiscovery.kt`
- Test (create): `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalDiscoveryProjectionTest.kt`

**Interfaces:**
- Consumes: `TidalDocument`, `TidalDocumentView`, `TidalResource.decodeAttributes` (Foundation Task 2); `TrackAttributes` (`TidalOfficialApi.kt`: `title`/`duration`/`isrc`/`explicit`); `parseIsoDurationSeconds` (Foundation Task 4 — `mixFrom` uses it for item durations).
- Produces: `TidalMixCategory`, `TidalMixRef(id, type, category)`, `TidalMixItem(trackId, title, artist, album, artworkUrl, explicit, durationSeconds)`, `TidalMix(id, category, title, items)`, `MixAttributes(title, subTitle)`; `mixRefsFrom(doc, json): List<TidalMixRef>`; `mixFrom(doc, json, category, fallbackTitle): TidalMix?`; `mixFallbackTitle(category, ordinal): String`. (Artwork DTOs are NOT produced here — they come from the Foundation.)

- [ ] **Step 1: Write the failing projection test**

Create `TidalDiscoveryProjectionTest.kt`. The recommendations fixture keeps `included` populated (the `?include=` response carries the mix resources, which `mixRefsFrom` resolves via the Foundation's `related`). The mix fixture uses the real `tracks` item type and a real ISO-8601 `duration`:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalDiscoveryProjectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val recsDoc = """
        {
          "data": { "id": "me", "type": "userRecommendations",
            "relationships": {
              "myMixes":        { "data": [ { "id": "d1", "type": "userDailyMixes" }, { "id": "d2", "type": "userDailyMixes" } ] },
              "discoveryMixes": { "data": [ { "id": "x1", "type": "userDiscoveryMixes" } ] },
              "newArrivalMixes":{ "data": [ { "id": "n1", "type": "userNewReleaseMixes" } ] }
            } },
          "included": [
            { "id": "d1", "type": "userDailyMixes" },
            { "id": "d2", "type": "userDailyMixes" },
            { "id": "x1", "type": "userDiscoveryMixes" },
            { "id": "n1", "type": "userNewReleaseMixes" }
          ]
        }
    """.trimIndent()

    @Test
    fun `mixRefsFrom groups mix references by category in section order`() {
        val refs = mixRefsFrom(json.decodeFromString(recsDoc), json)
        assertEquals(
            listOf(
                TidalMixRef("d1", "userDailyMixes", TidalMixCategory.MY_MIX),
                TidalMixRef("d2", "userDailyMixes", TidalMixCategory.MY_MIX),
                TidalMixRef("x1", "userDiscoveryMixes", TidalMixCategory.DISCOVERY),
                TidalMixRef("n1", "userNewReleaseMixes", TidalMixCategory.NEW_ARRIVAL),
            ),
            refs,
        )
    }

    @Test
    fun `mixRefsFrom yields nothing when there is no primary recommendations resource`() {
        assertTrue(mixRefsFrom(json.decodeFromString("""{ "data": [], "included": [] }"""), json).isEmpty())
    }

    private val mixDoc = """
        {
          "data": { "id": "d1", "type": "userDailyMixes",
            "relationships": { "items": { "data": [ { "id": "t1", "type": "tracks" }, { "id": "t2", "type": "tracks" } ] } } },
          "included": [
            { "id": "t1", "type": "tracks", "attributes": { "title": "Song One", "duration": "PT2M58S", "explicit": true } },
            { "id": "t2", "type": "tracks", "attributes": { "title": "Song Two", "duration": "PT3M10S", "explicit": false } }
          ]
        }
    """.trimIndent()

    @Test
    fun `mixFrom projects items with title, explicit and duration seconds; artist and art empty until hydration`() {
        val mix = mixFrom(json.decodeFromString(mixDoc), json, TidalMixCategory.MY_MIX, "My Mix 1")!!
        assertEquals("d1", mix.id)
        assertEquals("My Mix 1", mix.title)
        assertEquals(TidalMixCategory.MY_MIX, mix.category)
        assertEquals(2, mix.items.size)
        val first = mix.items[0]
        assertEquals("t1", first.trackId)
        assertEquals("Song One", first.title)
        assertTrue(first.explicit)
        assertEquals(178.0, first.durationSeconds!!, 0.001)   // PT2M58S = 178s
        assertEquals("", first.artist)
        assertNull(first.album)
        assertNull(first.artworkUrl)
    }

    @Test
    fun `mixFrom prefers a live mix title over the fallback when present`() {
        val doc = """
            { "data": { "id": "d9", "type": "userDailyMixes",
                "attributes": { "title": "Your Monday Mix" },
                "relationships": { "items": { "data": [] } } }, "included": [] }
        """.trimIndent()
        val mix = mixFrom(json.decodeFromString(doc), json, TidalMixCategory.MY_MIX, "My Mix 1")!!
        assertEquals("Your Monday Mix", mix.title)
    }

    @Test
    fun `mixFrom returns null when there is no primary mix resource`() {
        assertNull(mixFrom(json.decodeFromString("""{ "data": [], "included": [] }"""), json, TidalMixCategory.MY_MIX, "x"))
    }

    @Test
    fun `mixFallbackTitle names each category with its ordinal`() {
        assertEquals("My Mix 2", mixFallbackTitle(TidalMixCategory.MY_MIX, 2))
        assertEquals("Discovery Mix 1", mixFallbackTitle(TidalMixCategory.DISCOVERY, 1))
        assertEquals("New Arrivals 3", mixFallbackTitle(TidalMixCategory.NEW_ARRIVAL, 3))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalDiscoveryProjectionTest*'`
Expected: FAIL — `mixRefsFrom` / `mixFrom` / `TidalMix*` / `mixFallbackTitle` unresolved reference.

- [ ] **Step 3: Create `TidalDiscovery.kt` (domain + DTOs + these two projections)**

Create `TidalDiscovery.kt`:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Slice 3 (Discovery): the Tidal Home mixes surface — My Mixes, Discovery
 * mixes, New-Arrival mixes — as titled rows of tracks. Metadata comes from
 * the official `openapi.tidal.com/v2` API (JSON:API compound documents,
 * parsed by the Foundation's [TidalDocument]/[TidalDocumentView]); playback
 * is a plain [tidalMixItemToTrack] handoff onto the internal-v1 pipeline.
 *
 * All parsing/mapping here is PURE and unit-tested against fixtures; the
 * network orchestration lives in [TidalMetadataRepository.discovery].
 */

// ---- attribute DTOs (field names verbatim from the cached spec) ---------

/**
 * The mix resource attribute bodies (`UserDailyMixes_Attributes` etc.) are an
 * empty object in the spec (v1.10.55) — mixes have no title there, so titles
 * are derived by [mixFallbackTitle]. These fields are DEFENSIVE: if a live
 * response ever carries a title, [mixFrom] uses it in preference to the
 * fallback (tolerant, like every DTO in this layer).
 */
@Serializable
data class MixAttributes(val title: String? = null, val subTitle: String? = null)

// Artwork DTOs (`ArtworkAttributes` / `ArtworkFile` / `ArtworkFileMeta`) + the
// best-fit `pickArtworkUrl` come from the Foundation (`TidalMetadataPrimitives.kt`,
// Slice 0 Task 4) — consumed here, NEVER re-declared (a second copy is a Kotlin
// "conflicting declarations" error). Note the Foundation's `ArtworkFile.href` is
// nullable (defensive); album cover-art hydration uses `pickArtworkUrl` (see Task 3).
//
// Track item titles/duration/explicit reuse `TrackAttributes` (TidalOfficialApi.kt:
// title/duration/isrc/explicit). Artist name reuses `ArtistAttributes` (name) and
// album title reuses `AlbumAttributes` (title), both from Slice 0's
// TidalMetadataRepository.kt — same package, no re-declaration.

// ---- domain model -------------------------------------------------------

/** Which recommendation bucket a mix came from — drives the Home section grouping and fallback title. */
enum class TidalMixCategory { MY_MIX, DISCOVERY, NEW_ARRIVAL }

/**
 * A mix reference discovered from `/userRecommendations`: the mix [id], its
 * resource [type] (which is also the items-endpoint path segment, e.g.
 * `userDailyMixes`), and the [category] bucket it was listed under.
 */
data class TidalMixRef(val id: String, val type: String, val category: TidalMixCategory)

/**
 * One playable mix item. [trackId] is the Tidal catalog id — the SAME id the
 * internal-v1 path streams by (Slice-1 id-equivalence) — so playback is a
 * plain [tidalMixItemToTrack] handoff. [artist]/[album]/[artworkUrl] start
 * empty/null from [mixFrom] and are filled by [applyHydration].
 */
data class TidalMixItem(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val explicit: Boolean,
    val durationSeconds: Double?,
)

/** A titled mix row for the Home screen. */
data class TidalMix(
    val id: String,
    val category: TidalMixCategory,
    val title: String,
    val items: List<TidalMixItem>,
)

// ---- pure projections ---------------------------------------------------

/** Section order + the recommendations relationship name that feeds each category. */
private val RECS_CATEGORY_BY_RELATIONSHIP = linkedMapOf(
    "myMixes" to TidalMixCategory.MY_MIX,
    "discoveryMixes" to TidalMixCategory.DISCOVERY,
    "newArrivalMixes" to TidalMixCategory.NEW_ARRIVAL,
)

/**
 * Extract mix references from a `/userRecommendations?include=myMixes,
 * discoveryMixes,newArrivalMixes` compound document. Order is My Mixes, then
 * Discovery, then New Arrivals, preserving linkage order within each bucket.
 * Uses the Foundation's [TidalDocumentView.related], which resolves each
 * relationship's linkage against `included` — so the mix resources must be
 * present in `included` (they are, because `include=` requested them).
 */
fun mixRefsFrom(doc: TidalDocument, json: Json): List<TidalMixRef> {
    val view = TidalDocumentView(doc, json)
    val recs = view.primary ?: return emptyList()
    return RECS_CATEGORY_BY_RELATIONSHIP.flatMap { (relationship, category) ->
        view.related(recs, relationship).map { TidalMixRef(it.id, it.type, category) }
    }
}

/**
 * Project one mix compound document (fetched with `?include=items`) into a
 * [TidalMix]. Items are the mix's `tracks` in linkage order, carrying
 * title/explicit/duration only — artist/album/artwork are filled later by
 * [applyHydration]. [fallbackTitle] is used unless the mix carries its own
 * (the spec's mix attributes are empty; see [MixAttributes]). Returns null
 * when the document has no primary mix resource.
 */
fun mixFrom(doc: TidalDocument, json: Json, category: TidalMixCategory, fallbackTitle: String): TidalMix? {
    val view = TidalDocumentView(doc, json)
    val mix = view.primary ?: return null
    val title = mix.decodeAttributes<MixAttributes>(json)?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val items = view.related(mix, "items")
        .filter { it.type == "tracks" }
        .mapNotNull { res ->
            val attrs = res.decodeAttributes<TrackAttributes>(json)
            val itemTitle = attrs?.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TidalMixItem(
                trackId = res.id,
                title = itemTitle,
                artist = "",
                album = null,
                artworkUrl = null,
                explicit = attrs.explicit ?: false,
                // Shared Foundation primitive (same package) — not a re-decoded inline parse.
                durationSeconds = parseIsoDurationSeconds(attrs.duration),
            )
        }
    return TidalMix(id = mix.id, category = category, title = title, items = items)
}

/** Human-readable fallback title for a mix that carries none (spec mix attributes are empty). */
fun mixFallbackTitle(category: TidalMixCategory, ordinal: Int): String = when (category) {
    TidalMixCategory.MY_MIX -> "My Mix $ordinal"
    TidalMixCategory.DISCOVERY -> "Discovery Mix $ordinal"
    TidalMixCategory.NEW_ARRIVAL -> "New Arrivals $ordinal"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalDiscoveryProjectionTest*'`
Expected: PASS (all 6).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalDiscovery.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalDiscoveryProjectionTest.kt
git commit -m "feat(android): Tidal discovery domain model + recommendations/mix projections

Adds TidalMix/TidalMixItem/TidalMixRef and the pure mixRefsFrom (recs ->
grouped mix refs) and mixFrom (mix doc -> titled track row) projections.
Mixes have no title in the official spec, so titles are derived with a
defensive read of a live title if present.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 3: Hydration projections + playback handoff

Adds the pure functions that turn the two batched hydration documents into lookup maps, stitch them back into the mixes, and hand a mix item off to the internal-v1 playback path. Tracks carry no artwork in the official API, so cover art is resolved at the album level: `trackHydrationFrom` yields each track's artist + album id/title, `albumArtworkFrom` yields each album id's best-fit cover href, and `applyHydration` folds both in. `tidalMixItemToTrack` is the handoff that Task 5's `playMix` uses.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalDiscovery.kt` (append)
- Test (create): `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalDiscoveryHydrationTest.kt`

**Interfaces:**
- Consumes: `TidalDocument`, `TidalDocumentView`, `decodeAttributes`; `ArtistAttributes` (name), `AlbumAttributes` (title) from Foundation; the artwork DTOs `ArtworkAttributes`/`ArtworkFile`/`ArtworkFileMeta` + `pickArtworkUrl` (Foundation Task 4); `TidalMix`/`TidalMixItem` (Task 2); `io.github.auxen.model.Track`/`Source`.
- Produces: `TrackHydration(artist, albumId, album)`; `trackHydrationFrom(doc, json): Map<String, TrackHydration>`; `albumArtworkFrom(doc, json, targetWidth): Map<String, String>` (uses the Foundation's `pickArtworkUrl` for the best-fit choice — no local `bestArtworkFile`); `applyHydration(mixes, hydration, artByAlbum): List<TidalMix>`; `tidalMixItemToTrack(item): Track`.

- [ ] **Step 1: Write the failing hydration + handoff test**

Create `TidalDiscoveryHydrationTest.kt`:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalDiscoveryHydrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val tracksDoc = """
        {
          "data": [
            { "id": "t1", "type": "tracks", "attributes": { "title": "Song One" },
              "relationships": {
                "artists": { "data": [ { "id": "a1", "type": "artists" } ] },
                "albums":  { "data": [ { "id": "al1", "type": "albums" } ] } } },
            { "id": "t2", "type": "tracks", "attributes": { "title": "Song Two" },
              "relationships": {
                "artists": { "data": [ { "id": "a2", "type": "artists" } ] },
                "albums":  { "data": [ { "id": "al2", "type": "albums" } ] } } }
          ],
          "included": [
            { "id": "a1", "type": "artists", "attributes": { "name": "Prompto" } },
            { "id": "a2", "type": "artists", "attributes": { "name": "Com Truise" } },
            { "id": "al1", "type": "albums", "attributes": { "title": "Album One" } },
            { "id": "al2", "type": "albums", "attributes": { "title": "Album Two" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `trackHydrationFrom maps each track to its primary artist and album`() {
        val h = trackHydrationFrom(json.decodeFromString(tracksDoc), json)
        assertEquals("Prompto", h["t1"]!!.artist)
        assertEquals("al1", h["t1"]!!.albumId)
        assertEquals("Album One", h["t1"]!!.album)
        assertEquals("Com Truise", h["t2"]!!.artist)
        assertEquals("al2", h["t2"]!!.albumId)
    }

    private val albumsDoc = """
        {
          "data": [
            { "id": "al1", "type": "albums",
              "relationships": { "coverArt": { "data": [ { "id": "art1", "type": "artworks" } ] } } }
          ],
          "included": [
            { "id": "art1", "type": "artworks", "attributes": { "files": [
                { "href": "https://resources.tidal.com/images/a/b/320x320.jpg",   "meta": { "width": 320,  "height": 320 } },
                { "href": "https://resources.tidal.com/images/a/b/640x640.jpg",   "meta": { "width": 640,  "height": 640 } },
                { "href": "https://resources.tidal.com/images/a/b/1280x1280.jpg", "meta": { "width": 1280, "height": 1280 } }
            ] } }
          ]
        }
    """.trimIndent()

    @Test
    fun `albumArtworkFrom picks the smallest cover at least the target width`() {
        val art = albumArtworkFrom(json.decodeFromString(albumsDoc), json, targetWidth = 640)
        assertEquals("https://resources.tidal.com/images/a/b/640x640.jpg", art["al1"])
    }

    // Best-fit selection itself (smallest at/above target, else widest) is the
    // Foundation's `pickArtworkUrl`, already unit-tested in TidalMetadataPrimitivesTest;
    // here we only assert the album-level stitch through `albumArtworkFrom` above.

    @Test
    fun `applyHydration fills artist, album and artwork, leaving misses title-only`() {
        val mix = TidalMix(
            "d1", TidalMixCategory.MY_MIX, "My Mix 1",
            listOf(
                TidalMixItem("t1", "Song One", "", null, null, false, null),
                TidalMixItem("t9", "Orphan", "", null, null, true, null), // no hydration
            ),
        )
        val hydrated = applyHydration(
            listOf(mix),
            hydration = mapOf("t1" to TrackHydration("Prompto", "al1", "Album One")),
            artByAlbum = mapOf("al1" to "https://cdn/640.jpg"),
        ).first()
        assertEquals("Prompto", hydrated.items[0].artist)
        assertEquals("Album One", hydrated.items[0].album)
        assertEquals("https://cdn/640.jpg", hydrated.items[0].artworkUrl)
        assertEquals("", hydrated.items[1].artist)   // untouched
        assertNull(hydrated.items[1].artworkUrl)
    }

    @Test
    fun `tidalMixItemToTrack hands off to the internal-v1 path by catalog id`() {
        val item = TidalMixItem("75413016", "Song One", "Prompto", "Album One", "https://cdn/640.jpg", true, 178.0)
        val track = tidalMixItemToTrack(item)
        assertEquals(Source.TIDAL, track.source)
        assertEquals("75413016", track.sourceId)   // == internal-v1 track id (Slice-1 equivalence)
        assertEquals("Song One", track.title)
        assertEquals("Prompto", track.artist)
        assertEquals("Album One", track.album)
        assertEquals("https://cdn/640.jpg", track.albumArtUrl)
        assertEquals(178.0, track.durationSeconds!!, 0.001)
        assertTrue(track.explicit)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalDiscoveryHydrationTest*'`
Expected: FAIL — `trackHydrationFrom` / `albumArtworkFrom` / `applyHydration` / `tidalMixItemToTrack` / `TrackHydration` unresolved reference.

- [ ] **Step 3: Append the hydration + handoff functions to `TidalDiscovery.kt`**

Add at the end of `TidalDiscovery.kt`:

```kotlin
// ---- hydration (tracks carry no artwork; cover art is album-level) -------

/** Per-track hydration harvested from a batched `/tracks?include=artists,albums` document. */
data class TrackHydration(val artist: String, val albumId: String?, val album: String?)

/**
 * Map each `tracks` primary resource to its first artist name + first album
 * id/title, from a batched `/tracks?include=artists,albums` compound document.
 * Missing artist/album degrade to `""`/null rather than dropping the track.
 */
fun trackHydrationFrom(doc: TidalDocument, json: Json): Map<String, TrackHydration> {
    val view = TidalDocumentView(doc, json)
    return view.primaryList
        .filter { it.type == "tracks" }
        .associate { track ->
            val artist = view.related(track, "artists").firstOrNull()
                ?.decodeAttributes<ArtistAttributes>(json)?.name.orEmpty()
            val album = view.related(track, "albums").firstOrNull()
            track.id to TrackHydration(
                artist = artist,
                albumId = album?.id,
                album = album?.decodeAttributes<AlbumAttributes>(json)?.title,
            )
        }
}

/**
 * Map each `albums` primary resource to its best-fit cover-art href, from a
 * batched `/albums?include=coverArt` compound document. Albums with no cover
 * art are omitted (the item just stays art-less). Best-fit selection is the
 * Foundation's [pickArtworkUrl] (smallest at/above [targetWidth], else widest)
 * — no local copy.
 */
fun albumArtworkFrom(doc: TidalDocument, json: Json, targetWidth: Int = 640): Map<String, String> {
    val view = TidalDocumentView(doc, json)
    return view.primaryList
        .filter { it.type == "albums" }
        .mapNotNull { album ->
            val files = view.related(album, "coverArt").firstOrNull()
                ?.decodeAttributes<ArtworkAttributes>(json)?.files ?: return@mapNotNull null
            pickArtworkUrl(files, targetWidth)?.let { album.id to it }
        }
        .toMap()
}

/**
 * Fold the two batched hydration maps back into each mix's items (pure). A
 * track with no hydration keeps its title-only item — the graceful-
 * degradation path when a hydration call fails or an id is missing.
 */
fun applyHydration(
    mixes: List<TidalMix>,
    hydration: Map<String, TrackHydration>,
    artByAlbum: Map<String, String>,
): List<TidalMix> = mixes.map { mix ->
    mix.copy(
        items = mix.items.map { item ->
            val h = hydration[item.trackId]
            item.copy(
                artist = h?.artist?.takeIf { it.isNotBlank() } ?: item.artist,
                album = h?.album ?: item.album,
                artworkUrl = h?.albumId?.let { artByAlbum[it] } ?: item.artworkUrl,
            )
        },
    )
}

// ---- playback handoff ---------------------------------------------------

/**
 * The playback handoff: a mix item -> a Tidal [Track] the EXISTING internal-v1
 * pipeline streams. `Graph.mediaItemFor` turns this into `auxen://tidal/<id>`,
 * which `TrackResolver` resolves at open() via `tidal.getStreamInfoById(id)`.
 * The official API supplied only the metadata; playback never touches it. This
 * relies on the official catalog id equalling the internal-v1 track id
 * (Slice-1 id-equivalence).
 *
 * This maps the ALREADY-hydrated [TidalMixItem] (an intermediate view type),
 * not a raw track resource — so it does not re-decode `TrackAttributes`. The
 * official-track-resource decode happens once, in [mixFrom], via the shared
 * `parseIsoDurationSeconds` primitive — the same primitive Slice 1's canonical
 * `trackFrom` uses. So Slice 3 DEPENDS on Slice 1 (the `TRACK_RELATIONS_INCLUDE`
 * companion constant + the `trackFrom` projection conventions) and on the
 * Foundation primitives: merge order is Foundation → Slice 1 → Slice 3.
 */
fun tidalMixItemToTrack(item: TidalMixItem): Track = Track(
    title = item.title,
    artist = item.artist,
    source = Source.TIDAL,
    sourceId = item.trackId,
    album = item.album,
    albumArtUrl = item.artworkUrl,
    durationSeconds = item.durationSeconds,
    explicit = item.explicit,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalDiscoveryHydrationTest*'`
Expected: PASS (all 6).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalDiscovery.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalDiscoveryHydrationTest.kt
git commit -m "feat(android): Tidal discovery hydration projections + playback handoff

Tracks carry no artwork in the official API, so cover art is resolved at
the album level: trackHydrationFrom (artist+album), albumArtworkFrom
(best-fit cover) and a pure applyHydration stitch, all degrading to
title-only on miss. tidalMixItemToTrack hands a mix item to the internal-v1
playback path by catalog id.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 4: `TidalMetadataRepository.discovery()` orchestration

Extends the Foundation's `TidalMetadataRepository` with the `discovery()` suspend method that ties Tasks 1–3 together: `validAccessToken` → recommendations → bounded per-mix item fetches (concurrent) → two batched hydration calls → pure `applyHydration`. The `locale` constructor param **already exists** — the Foundation froze the constructor at `(session, client, json, countryCode, locale)` and already passes `locale` from `Graph` (Foundation Task 3) — so this task only READS it; it does NOT touch the constructor or `AuxenApp.kt`. The recommendations/mix endpoints take `locale`; the batched `/tracks`/`/albums` reuse the existing `countryCode`. Per the network-precedent, `discovery()` itself is not unit-tested — its pure pieces already are, and it is exercised by Task 5's on-device checkpoint.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt`

**Interfaces:**
- Consumes: `TidalOfficialSession.validAccessToken()`; the `locale` constructor param (Foundation) + the `TRACK_RELATIONS_INCLUDE` companion constant (Slice 1); `TidalOfficialClient.getRecommendations/getMix/getTracks/getAlbums` (Task 1); `mixRefsFrom`/`mixFrom`/`mixFallbackTitle`/`applyHydration`/`trackHydrationFrom`/`albumArtworkFrom` (Tasks 2–3).
- Produces: `TidalMetadataRepository.discovery(): Result<List<TidalMix>>` (+ the discovery `include`/cap constants on the companion).

- [ ] **Step 1: Add discovery constants + `discovery()` (the `locale` constructor param already exists)**

In `TidalMetadataRepository.kt`, add the coroutine imports at the top:

```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
```

**Do NOT re-declare the constructor.** The Foundation already froze it at `(session, client, json, countryCode, locale)` (Foundation Task 3) precisely so Discovery does not rewrite it — a second constructor would be a "conflicting declarations" error. Discovery just READS `locale` in `discovery()` below.

Add the `discovery()` method and its `boundedRefs` helper inside the class (after the existing `artistOverview`):

```kotlin
    /**
     * The Home discovery feed: My Mixes + Discovery + New Arrivals, each a
     * titled row of tracks with artwork. Metadata only — a tapped track plays
     * through the internal-v1 pipeline via [tidalMixItemToTrack].
     *
     * Bounded fan-out: 1 recommendations call + up to
     * [MAX_MIXES_PER_CATEGORY]x3 concurrent mix calls + 2 batched hydration
     * calls. Hydration failures degrade to title-only rows (never fail the
     * feed). Returns an empty list (success) when there is nothing to show;
     * fails only when not logged in or the recommendations call fails.
     */
    suspend fun discovery(): Result<List<TidalMix>> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))

        val recDoc = client.getRecommendations(token, RECOMMENDATIONS_INCLUDE, locale)
            .getOrElse { return Result.failure(it) }
        val refs = boundedRefs(mixRefsFrom(recDoc, json))
        if (refs.isEmpty()) return Result.success(emptyList())

        // Fetch each mix's items concurrently; drop mixes that fail or are empty.
        val mixes = coroutineScope {
            refs.map { (ref, title) ->
                async {
                    client.getMix(ref.type, ref.id, token, MIX_INCLUDE, locale)
                        .getOrNull()
                        ?.let { mixFrom(it, json, ref.category, title) }
                        ?.let { mix -> mix.copy(items = mix.items.take(MAX_ITEMS_PER_MIX)) }
                }
            }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
        }
        if (mixes.isEmpty()) return Result.success(emptyList())

        // Batched hydration: artist + album per track, then cover art per album.
        val trackIds = mixes.flatMap { it.items }.map { it.trackId }.distinct().take(MAX_HYDRATE_IDS)
        val hydration = client.getTracks(trackIds, token, TRACK_RELATIONS_INCLUDE, countryCode)
            .map { trackHydrationFrom(it, json) }
            .getOrDefault(emptyMap())
        val albumIds = hydration.values.mapNotNull { it.albumId }.distinct().take(MAX_HYDRATE_IDS)
        val artByAlbum = if (albumIds.isEmpty()) {
            emptyMap()
        } else {
            client.getAlbums(albumIds, token, ALBUM_INCLUDE, countryCode)
                .map { albumArtworkFrom(it, json) }
                .getOrDefault(emptyMap())
        }

        return Result.success(applyHydration(mixes, hydration, artByAlbum))
    }

    /** Cap mixes per category and pair each with its ordinal fallback title. */
    private fun boundedRefs(refs: List<TidalMixRef>): List<Pair<TidalMixRef, String>> =
        refs.groupBy { it.category }.flatMap { (category, list) ->
            list.take(MAX_MIXES_PER_CATEGORY).mapIndexed { index, ref ->
                ref to mixFallbackTitle(category, index + 1)
            }
        }
```

Add the discovery constants to the existing `companion object` (alongside `ARTIST_INCLUDE`):

```kotlin
        val RECOMMENDATIONS_INCLUDE = listOf("myMixes", "discoveryMixes", "newArrivalMixes")
        val MIX_INCLUDE = listOf("items")
        val ALBUM_INCLUDE = listOf("coverArt")
        const val MAX_MIXES_PER_CATEGORY = 6
        const val MAX_ITEMS_PER_MIX = 20
        const val MAX_HYDRATE_IDS = 120
```

Track hydration reuses **Slice 1's** `TRACK_RELATIONS_INCLUDE = listOf("artists", "albums")` on this same `TidalMetadataRepository` companion (see `discovery()` above). Discovery does **not** declare a second, identical track-include constant — that copy would just duplicate `TRACK_RELATIONS_INCLUDE`. This is why **Slice 1 must merge before Slice 3** (merge order: Foundation → Slice 1 → Slice 3).

- [ ] **Step 2: `locale` is already wired by the Foundation — no `AuxenApp.kt` change**

The Foundation already constructs `Graph.tidalMetadata = TidalMetadataRepository(..., locale = java.util.Locale.getDefault().toLanguageTag())` (Foundation Task 3), so Discovery needs **no** `AuxenApp.kt` edit here — `discovery()` reads that `locale` directly. (If a `"und"`/blank language tag ever proves problematic in practice, harden it in the Foundation's single wiring line — not here — to keep one source of truth.)

- [ ] **Step 3: Compile + run the full unit suite (no new test; orchestration is network)**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: PASS; APK builds. No new unit test — `discovery()` is untested network orchestration (its pure pieces are covered by Tasks 1–3), verified on-device in Task 5.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt
git commit -m "feat(android): TidalMetadataRepository.discovery() orchestration

Ties the discovery pieces together: validAccessToken -> recommendations ->
bounded concurrent per-mix item fetches -> two batched hydration calls ->
pure applyHydration. Reads the Foundation's locale (recs/mix endpoints take
locale; tracks/albums reuse countryCode). Degrades to an empty feed when
logged out or on error.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 5: Home mixes surface + playback wiring + on-device checkpoint

Surfaces the feed: a `tidalDiscovery` flow + `refreshDiscovery()` (called from Home's existing `LaunchedEffect`, degrading to empty) and a `playMix()` handoff on `PlayerViewModel`, and the mix carousels in `HomeScreen` rendered below the existing local sections. The VM passthrough and Compose are not unit-tested (Foundation precedent); the slice closes on an on-device checkpoint that confirms real mixes render, art loads, playback works via internal-v1, and logged-out Home is unchanged.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt`

**Interfaces:**
- Consumes: `Graph.tidalMetadata.discovery()` (Task 4); `tidalMixItemToTrack` (Task 3); `Graph.mediaItemFor` / `MediaController` (existing); `AlbumCard` / `SectionHeader` (existing components).
- Produces: `PlayerViewModel.tidalDiscovery: MutableStateFlow<List<TidalMix>>`; `PlayerViewModel.refreshDiscovery()`; `PlayerViewModel.playMix(mix, startTrackId)`; new Home mix carousels.

- [ ] **Step 1: Add the VM flow + `refreshDiscovery()` + `playMix()`**

In `PlayerViewModel.kt`, add the imports (next to the other `io.github.auxen.provider.tidal` imports):

```kotlin
import io.github.auxen.provider.tidal.TidalMix
import io.github.auxen.provider.tidal.tidalMixItemToTrack
```

Add the flow next to the other Home flows (e.g. after `tidalOfficialStatus`), matching the file's public-`MutableStateFlow` style:

```kotlin
    /**
     * Tidal Home mixes (My Mixes / Discovery / New Arrivals). Empty when
     * logged out of the official API or on any fetch error, so the local-only
     * Home never regresses. Populated by [refreshDiscovery].
     */
    val tidalDiscovery = MutableStateFlow<List<TidalMix>>(emptyList())
```

Add the loader + the play handoff (place `refreshDiscovery` near `refreshHome`, and `playMix` near `playAll`):

```kotlin
    /** Load the Tidal Home mixes. Any failure (incl. logged out) degrades to an empty feed — no error surfaced. */
    fun refreshDiscovery() {
        viewModelScope.launch {
            tidalDiscovery.value = Graph.tidalMetadata.discovery().getOrDefault(emptyList())
        }
    }

    /**
     * Play a Tidal [mix] starting at [startTrackId]. Every item streams through
     * the INTERNAL-v1 pipeline ([tidalMixItemToTrack] -> Graph.mediaItemFor ->
     * auxen://tidal/<id>); the official API only supplied the metadata.
     */
    fun playMix(mix: TidalMix, startTrackId: String) {
        viewModelScope.launch {
            val c = controller ?: return@launch
            val tracks = mix.items.map { tidalMixItemToTrack(it) }
            if (tracks.isEmpty()) return@launch
            runCatching { tracks.forEach { Graph.library.upsert(it) } }
            val items = tracks.mapNotNull { runCatching { Graph.mediaItemFor(it) }.getOrNull() }
            if (items.isEmpty()) return@launch
            val startIndex = mix.items.indexOfFirst { it.trackId == startTrackId }.coerceAtLeast(0)
            // Static queue order is the source of truth (desktop semantics), like playAll.
            c.shuffleModeEnabled = false
            c.setMediaItems(items, startIndex, 0L)
            c.prepare()
            c.play()
        }
    }
```

- [ ] **Step 2: Render the mix carousels in `HomeScreen`**

In `HomeScreen.kt`:

1. Collect the flow with the other `collectAsState()` calls at the top of `HomeScreen`:

```kotlin
    val discovery by viewModel.tidalDiscovery.collectAsState()
```

2. Trigger the load in the existing `LaunchedEffect(Unit)` block, after `viewModel.refreshHome()`:

```kotlin
        viewModel.refreshDiscovery()
```

3. Inside the `LazyColumn`, immediately AFTER the "Recently Played" block (the `if (played.isNotEmpty()) { ... }`) and BEFORE the empty-state `else if`, add the mix sections. Each mix mirrors the existing "Recently Added" carousel (a `SectionHeader` + a `LazyRow` of `AlbumCard`s), and a mix item's tap plays the mix from that track:

```kotlin
        discovery.forEach { mix ->
            item(key = "mix-header-${mix.id}") { SectionHeader(mix.title) }
            item(key = "mix-row-${mix.id}") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(mix.items, key = { "${mix.id}:${it.trackId}" }) { mixItem ->
                        AlbumCard(
                            title = mixItem.title,
                            artist = mixItem.artist.ifBlank { null },
                            artUrl = mixItem.artworkUrl,
                            source = Source.TIDAL,
                            onClick = { viewModel.playMix(mix, mixItem.trackId) },
                            onPlay = { viewModel.playMix(mix, mixItem.trackId) },
                            // Bounded width required for a card inside a LazyRow (see AlbumCard KDoc).
                            modifier = Modifier.width(150.dp),
                        )
                    }
                }
            }
        }
```

4. Update the empty-state guard so a logged-out/empty local Home still shows the empty state, but a Home with only Tidal mixes does not. Change the existing `} else if (addedAlbums.isEmpty()) {` to also require `discovery.isEmpty()`:

```kotlin
        } else if (addedAlbums.isEmpty() && discovery.isEmpty()) {
```

(No new imports needed: `LazyRow`, `items`, `PaddingValues`, `Arrangement`, `width`, `dp`, `AlbumCard`, `SectionHeader`, and `Source` are all already imported by `HomeScreen.kt`. `TidalMix`'s members are accessed via type inference from the collected flow, so no explicit import is required.)

- [ ] **Step 3: Build + run the full gate**

Run: `cd android && ./gradlew testDebugUnitTest verifyRoborazziDebug assembleDebug`
Expected: PASS. The Home golden is unchanged: screenshot/unit contexts have no official-API session, so `refreshDiscovery` resolves to an empty feed (no network in the logged-out path — `validAccessToken()` returns null immediately) and no mix sections render. If a golden nonetheless shifts, re-record with `./gradlew recordRoborazziDebug` and eyeball the diff (only expected if the harness injects a logged-in session).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt
# plus any re-recorded Home golden under app/src/test/.../roborazzi/ if Step 3 changed it
git commit -m "feat(android): Home Tidal mix carousels + internal-v1 playback handoff

Adds tidalDiscovery flow + refreshDiscovery() (degrades to empty when
logged out) and playMix() to PlayerViewModel, and renders My Mixes /
Discovery / New Arrivals carousels below the local Home sections. Tapping
a mix track plays the mix through the internal-v1 pipeline. Logged-out
Home is unchanged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

- [ ] **Step 5: On-device checkpoint (the Slice-3 deliverable)**

Build the APK **locally** (CI has no Tidal creds): `cd android && ./gradlew assembleDebug`. Install, then:

1. **Logged-out regression:** open Home WITHOUT logging into the official API. Confirm it looks exactly as before — greeting, filter chips, stat cards, Recently Added, Recently Played, and the empty state when local is empty. No mix rows, no error, no jank.
2. **Log in:** Settings → Advanced → "Try official Tidal login (beta)" → complete PKCE login.
3. **Mixes render:** return to Home. Confirm titled mix carousels appear below Recently Played — "My Mix 1..N", "Discovery Mix ...", "New Arrivals ..." — each a horizontal row of track cards with **cover art** and artist subtitles. (If a title reads "My Mix 1" rather than a Tidal-branded name, that is expected — the official spec's mix attributes are empty; confirm the Open Questions item about live titles.)
4. **Playback handoff:** tap a mix track. Confirm it starts playing via the normal player (internal-v1 stream), the queue is the mix in order starting at the tapped track, and Now Playing shows the correct title/artist/art. **This is the id-equivalence confirmation** — if playback fails with a resolve error, the official catalog id ≠ internal-v1 id and the Slice-1 dependency (ISRC fallback) is triggered; report it.
5. **Degradation:** toggle airplane mode and reopen Home — confirm the local sections still render and the mix rows simply disappear (no crash, no error banner).
6. `git status` — confirm `TidalAuth.kt` and `TidalProvider.kt` are untouched.

Paste the observed mix titles + one successful playback back to close the loop, exactly like the Foundation's artist-metadata probe.

---

## Self-review (writing-plans checklist)

- **Spec coverage:** Discovery entry point `/userRecommendations/me?include=myMixes,discoveryMixes,newArrivalMixes` ✓ (Task 1 builder, Task 2 `mixRefsFrom`). Per-mix items `/userDailyMixes|userDiscoveryMixes|userNewReleaseMixes/{id}?include=items` ✓ (Task 1 `mixRequest` with type guard, Task 2 `mixFrom`). The spec's **empty mix attributes** are handled by derived titles + a defensive live-title read ✓. Tracks-have-no-artwork is handled by album-level cover art via batched `/tracks?include=artists,albums` + `/albums?include=coverArt` with `filter[id]` ✓ (Task 1 builders, Task 3 `trackHydrationFrom`/`albumArtworkFrom`, best-fit choice via the Foundation's `pickArtworkUrl`). Real resource-type strings (`tracks`/`albums`/`artists`/`artworks`/`userDailyMixes`…) and real attribute fields (`title`/`duration`/`explicit`, `Artists.name`, `Albums.title`, `Artworks.files[].href`/`meta.width|height`) are used verbatim ✓. `locale` vs `countryCode` split matches the spec per-endpoint ✓. `offlineMixes` is intentionally out of scope (offline-download bucket, not a discovery surface) — noted here so it is not silently dropped.
- **Placeholder scan:** every code step shows real code; every test step shows real assertions with concrete fixtures. The only non-TDD steps — Task 4 `discovery()` (network orchestration) and Task 5 VM passthrough + Compose — are explicitly justified by the Foundation's untested-network / untested-Compose precedent and close on the Step-5 on-device checkpoint.
- **Type consistency:** `TidalMix`/`TidalMixItem`/`TidalMixRef`/`TidalMixCategory`/`TrackHydration` names match between definition (Tasks 2–3) and use (Tasks 4–5). `mixRefsFrom`/`mixFrom`/`mixFallbackTitle`/`trackHydrationFrom`/`albumArtworkFrom`/`applyHydration`/`tidalMixItemToTrack` signatures are identical across their tests, `TidalDiscovery.kt`, and `discovery()`. `MixAttributes` is the ONLY new DTO in this slice; the artwork DTOs (`ArtworkAttributes`/`ArtworkFile`/`ArtworkFileMeta`) + `pickArtworkUrl` + `parseIsoDurationSeconds` come from the **Foundation** (Task 4), and `TrackAttributes` (spike), `ArtistAttributes`/`AlbumAttributes` (Foundation) are reused — none re-declared (no duplicate-class / conflicting-declarations error). Track hydration reuses Slice 1's `TRACK_RELATIONS_INCLUDE`; Discovery's own `include` constants (`RECOMMENDATIONS_INCLUDE`/`MIX_INCLUDE`/`ALBUM_INCLUDE`) match the query params their builders emit. `TidalMixItem` field order (`trackId,title,artist,album,artworkUrl,explicit,durationSeconds`) is consistent between the data-class definition and every positional test constructor.

## Open Questions (verify on-device; none block building the slice)

1. **Id equivalence (Slice-1 dependency).** Playback assumes the official catalog `trackId` == the internal-v1 track id. Confirmed by Task 5 Step 4. If they differ, resolve mix items by ISRC (`TrackAttributes.isrc`, already available) before `Graph.mediaItemFor` — a follow-up localized to `playMix`/`tidalMixItemToTrack`.
2. **Recommendations `included`.** `mixRefsFrom` resolves mix refs via `TidalDocumentView.related`, which requires the mix resources to be in the response's `included` array. Standard JSON:API `include=` places them there; if a live response returns linkage-only, switch `mixRefsFrom` to read `recs.relationships[name].data` linkage directly (single/array normalization identical to `TidalDocumentView.identifiersFrom`). Confirmed by Task 5 Step 3 (mixes render).
3. **`myMixes` → `userDailyMixes` mapping.** The slice drives the items endpoint from each mix ref's own `type` string (robust — no guessed relationship→type mapping), so this is informational, but Task 5 Step 3 confirms My Mixes fetch from `userDailyMixes`.
4. **Live mix titles.** The spec's mix attributes are empty, so titles are derived. If Tidal live returns a `title`/`subTitle`, `mixFrom` already prefers it; note in the checkpoint whether real titles appear (would let us drop or downrank the `mixFallbackTitle` naming).
5. **Mix item type.** Daily/Discovery mixes are assumed to hold `tracks` (the slice filters `it.type == "tracks"`). If New-Arrival mixes hold `albums`, those items are currently skipped (empty row → mix dropped); handling album items is a small follow-up (project to an album card + `playAll` of the album). Confirmed by Task 5 Step 3 (whether New Arrivals render).
