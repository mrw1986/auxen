# Tidal Metadata Layer — Roadmap & Foundation Plan

**Date:** 2026-07-15
**Branch context:** `claude/android-app-availability-y6uzb0`
**Supersedes the "if only previews" branch of** `docs/plans/2026-07-13-android-tidal-official-api-migration.md` **(Task 2+).**

---

## Part 1 — Roadmap (the whole metadata layer)

### The verdict that sets the direction

Task 1 (the official-API spike) is done. The on-device streaming probe returned **PREVIEW**, not FULL, on `/trackManifests/{id}` — our registered app sits at the **`THIRD_PARTY`** access tier, which does not entitle full-length streaming through the official Open API (that needs `PARTNER`). Combined with the user's **personal-use-only** decision (which removes the distribution liability of the reverse-engineered internal API), this locks the direction:

> **Keep the internal `api.tidal.com/v1` path for search + Hi-Res streaming (it works, personal use). Layer the official `openapi.tidal.com/v2` API on top for the *sanctioned* metadata surface: enrichment, collection sync, and discovery.**

The official API is not a stub-grade option — the cached spec (`.superpowers/sdd/tidal-openapi-v2.json`, v1.10.55) exposes the full metadata surface we want:

| Need | Official endpoint(s) |
|---|---|
| Search | `GET /searchResults/{urlencoded-query}` → `artists`/`albums`/`tracks`/`playlists`/`topHits` |
| Artist enrichment | `GET /artists/{id}` + `include=biography,similarArtists,albums,tracks,profileArt,radio` |
| Album enrichment | `GET /albums/{id}` + `include=items,artists,coverArt,similarAlbums` |
| Track credits/lyrics | `GET /tracks/{id}` + `include=credits,lyrics,similarTracks,radio` |
| Collection (favorites) | `GET/POST /userCollections/{id}` + `tracks`/`albums`/`artists` relationships |
| Discovery / mixes | `GET /userRecommendations/{id}`, `/userDailyMixes`, `/userDiscoveryMixes`, `/userNewReleaseMixes` |

Everything responds as JSON:API compound documents (`data` + `included` + resource-linkage `relationships`).

### Why this is several plans, not one

This "metadata layer" spans **independent subsystems** that each ship working, testable software on their own and can be prioritized in any order. Per the writing-plans scope check, they get separate plans. Only **Slice 0 (Foundation)** is a hard prerequisite for the rest; Slices 1–3 are independent of each other.

```
                    ┌─────────────────────────────┐
                    │  Slice 0 — FOUNDATION        │  ← this document, Part 2
                    │  (session refresh, JSON:API  │
                    │   infra, repo seam, probe)   │
                    └──────────────┬──────────────┘
                                   │ (prerequisite)
          ┌────────────────────────┼────────────────────────┐
          ▼                        ▼                        ▼
  ┌───────────────┐       ┌────────────────┐       ┌────────────────┐
  │ Slice 1 —     │       │ Slice 2 —      │       │ Slice 3 —      │
  │ ENRICHMENT    │       │ COLLECTION SYNC│       │ DISCOVERY      │
  │ artist/album  │       │ Tidal favorites│       │ Home mixes /   │
  │ detail, bio,  │       │ (userCollections│      │ recommendations│
  │ similar,      │       │  r/w) ↔ local  │       │ (userRecomm-   │
  │ credits,lyrics│       │ Collection view│       │  endations)    │
  └───────────────┘       └────────────────┘       └────────────────┘
        (independent of each other; sequence by product priority)
```

**Slice 1 — Enrichment.** Wire real remote data into the currently local-only `ArtistDetailScreen` / `AlbumDetailScreen`: artist biography, similar artists, full discography, album tracklist + cover art, track credits + lyrics. First step is an explicit validation that an internal-v1 track/artist/album id is the **same catalog id** the official v2 API expects (they should be — both are Tidal catalog ids — but this must be proven on-device before building on it; ISRC/`barcodeId` matching is the fallback if not).

**Slice 2 — Collection sync.** Favorite tracks/albums/artists on Tidal via `userCollections` (r/w), reconciled with the local Collection view. `PlaylistEntity.source`/`tidalPlaylistId` and `TrackEntity.match_group_id` are latent DB hooks already present for this.

**Slice 3 — Discovery.** Tidal-powered Home sections (daily mixes, discovery mixes, new arrivals) via `userRecommendations`. Net-new surface; no existing screen to retrofit.

### Product decisions needed before detailing Slices 1–3

1. **Which slice first?** Recommendation: **Slice 1 (Enrichment)** — it turns the two emptiest screens (artist/album detail are pure local grouping today, no remote data at all) into rich ones, and it exercises the id-equivalence question that Slices 2–3 also depend on.
2. **Search: leave on internal-v1 (default) or add official search?** The migration doc says keep search on internal-v1. Official `search.read` works and is sanctioned, but internal search already merges local+Tidal well. Recommend **leave as-is** unless you want the sanctioned path.
3. **Collection sync direction** (Slice 2): read-only mirror of Tidal favorites into the app, or full two-way (favoriting in the app writes to Tidal)? Affects scope materially.

### Explicitly out of scope for the metadata layer

- **Streaming** stays on internal-v1 (`TidalProvider.getStreamInfoById`) — the official API only serves previews at our tier. Do **not** route playback through the official path.
- **The Tidal SDK `player` module** — never; it is a black-box ExoPlayer fork with no DSP hook, and our Media3 `EqRenderersFactory` pipeline is non-negotiable.

### Deferred hardening (before any wider distribution — safe to defer for personal use)

Neither blocks the metadata layer functionally; both are flagged in the Task-1 report. Track them here so they survive:

- **Persist the PKCE verifier/state pair** across process death (`TidalOfficialSession` keeps them `@Volatile` in-memory today, so an app kill mid-Custom-Tab silently fails that login). Small change; fold into Slice 1 or do standalone if login flakiness is observed.
- **Token-at-rest encryption** via the Android Keystore directly (a `KeyGenParameterSpec` AES key encrypting the token blob before DataStore) — the report's recommended approach, deliberately *not* `androidx.security.crypto` (deprecated by Google ~2024). `TidalOfficialSession.persist`/`storedSession` are the only two functions that change. App-private DataStore is already not world-readable, so this is a rooted-device / ADB-backup threat only — low priority for a personal-use build.

### Implementation order & shared contract

**Merge order:** Foundation (Slice 0 — including the new **Task 4** shared primitives) → **Slice 1 (Enrichment)** → then **Slice 2 (Collection)** and **Slice 3 (Discovery)** in either order. Slice 1 must precede Slice 3 because Slice 3 reuses Slice 1's canonical `trackFrom` projection and the shared `TRACK_RELATIONS_INCLUDE` constant.

**The id-equivalence GATE (Slice 1, Task 1) must pass on-device first.** It proves an internal-v1 track id is the same catalog id the official v2 API accepts. Slice 2's synced-favorite playback and Slice 3's `playMix` handoff both assume that equivalence, so neither is trusted until the gate is green on-device.

**Shared-once symbols** — declared exactly once; every slice consumes them, none re-declares them (re-declaration is a Kotlin "conflicting declarations" compile error):

| Symbol | Declared once in |
|---|---|
| Artwork DTOs (`ArtworkFile` / `ArtworkFileMeta` / `ArtworkAttributes`) + `pickArtworkUrl` | Foundation Task 4 — `TidalMetadataPrimitives.kt` |
| `parseIsoDurationSeconds`, `releaseYearOf` | Foundation Task 4 — `TidalMetadataPrimitives.kt` |
| `currentUserId()` (+ pure `userIdFrom`, `userRequest`, `getUser`) | Foundation Task 4 |
| `TidalMetadataRepository` constructor `(session, client, json, countryCode, locale)` | Foundation (Task 3 — final shape up front) |
| `trackFrom` (official-track-resource → `model.Track`) | Slice 1 (Enrichment) |
| `TrackAttributes` (spike DTO) | existing `TidalOfficialApi.kt` |

The artwork / duration / user-id primitives are shared infrastructure the feature slices consume: Slice 1 & Slice 3 use the artwork DTOs + `pickArtworkUrl` + `parseIsoDurationSeconds`/`releaseYearOf`; Slice 2 delegates its `resolveCollectionId()` to `currentUserId()`; Slices 2 & 3 use the reserved `locale` constructor param.

---

## Part 2 — Foundation Implementation Plan (Slice 0)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the shared plumbing every metadata slice needs — an auto-refreshing official-API session, a generic JSON:API compound-document parser, and a repository seam — and prove the whole read path end-to-end with an on-device artist-metadata probe (mirroring the Task-1 streaming probe).

**Architecture:** Additive only; nothing in the internal-v1 streaming/search path is touched. Extend the existing official-API classes (`TidalOfficialSession`/`TidalOfficialAuth`/`TidalOfficialEndpoints`/`TidalOfficialClient`) with token refresh + a reusable JSON:API layer, add a `TidalMetadataRepository` on `Graph`, and surface an artist-overview probe in the existing `TidalOfficialDebugScreen`. Follows the codebase's manual `Graph` DI + raw-OkHttp + kotlinx.serialization conventions exactly (no Retrofit/Dagger).

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), OkHttp, AndroidX DataStore (Preferences), Compose (debug screen), JUnit4 + Robolectric (unit tests). minSdk 26 / compileSdk 35 / Media3 1.5.1.

### Global Constraints

- **PKCE public client — NEVER send `client_secret`** on any official-API request. The official path has verified it sends none; keep it that way.
- **Never log or print `accessToken`/`refreshToken`.** Only human-readable status strings go to Logcat.
- **Do not unit-test live HTTP execution** (established precedent: `TidalAuth`/`TidalOfficialClient` network calls are not unit-tested). Unit-test *pure* logic only — expiry math, JSON:API parsing, request shapes, document→domain mapping. Extract pure functions from suspend/network wrappers so the logic is testable without a network.
- **New classes stay plain instantiable classes held on `Graph`** (like `TidalOfficialSession`), never JVM-wide `object`s with mutable state — so tests need no `@Before` reset and have no ordering sensitivity.
- **Internal-v1 code (`TidalAuth.kt`, `TidalProvider.kt`) is untouched.** Confirm with `git status` at the end, as Task 1 did.
- **Official client id is `BuildConfig.TIDAL_OFFICIAL_CLIENT_ID`** (public PKCE value), distinct from the internal `TIDAL_CLIENT_ID`.
- Every code step below shows the actual code. No `TODO`/"add error handling"/"similar to above" placeholders.

---

### Task 1: Official session — expiry tracking + token refresh + `validAccessToken()`

Without this, every metadata call breaks the moment the first access token expires (~1h). The internal `TidalAuth` already has the exact pattern to mirror (`expiresAtMillis`, `refresh()`, keep-old-refresh-token-if-omitted); this ports it to the official session and adds a single accessor all metadata callers use.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialAuth.kt` (Session field + `parseTokenResponse` signature)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialSession.kt` (clock, expiry persistence, `refresh()`, `validAccessToken()`, top-level `officialTokenNeedsRefresh`)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialAuthTest.kt` (update 2 existing calls)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialSessionTest.kt` (add `officialTokenNeedsRefresh` cases)

**Interfaces:**
- Produces: `TidalOfficialAuth.Session(accessToken: String, refreshToken: String?, expiresAtMillis: Long)` (renamed field); `TidalOfficialAuth.parseTokenResponse(body: String, nowMillis: Long): Session`; `TidalOfficialSession.refresh(): Session?`; `TidalOfficialSession.validAccessToken(): String?`; `officialTokenNeedsRefresh(expiresAtMillis: Long, nowMillis: Long, skewMillis: Long): Boolean`.
- Consumes: `TidalOfficialAuth.buildRefreshRequest(clientId, refreshToken)` (already exists, currently uncalled).

- [ ] **Step 1: Write the failing test for `officialTokenNeedsRefresh`**

In `TidalOfficialSessionTest.kt`, add (imports: `import io.github.auxen.provider.tidal.officialTokenNeedsRefresh` is same-package, no import needed):

```kotlin
@Test
fun `token is fresh well before expiry`() {
    // expires at 100_000; now 10_000; 60s skew -> not yet
    assertFalse(officialTokenNeedsRefresh(expiresAtMillis = 100_000L, nowMillis = 10_000L, skewMillis = 60_000L))
}

@Test
fun `token needs refresh inside the skew window`() {
    // expires at 100_000; now 50_000; skew 60_000 -> 50_000 >= 40_000 -> refresh
    assertTrue(officialTokenNeedsRefresh(expiresAtMillis = 100_000L, nowMillis = 50_000L, skewMillis = 60_000L))
}

@Test
fun `token needs refresh once past expiry`() {
    assertTrue(officialTokenNeedsRefresh(expiresAtMillis = 100_000L, nowMillis = 200_000L, skewMillis = 60_000L))
}

@Test
fun `legacy session with unknown expiry (0) always refreshes`() {
    assertTrue(officialTokenNeedsRefresh(expiresAtMillis = 0L, nowMillis = 1_000L, skewMillis = 60_000L))
}
```

Ensure `org.junit.Assert.assertTrue`/`assertFalse` are imported (the file already imports assertions for its `validateRedirect` tests).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalOfficialSessionTest*'`
Expected: FAIL — `officialTokenNeedsRefresh` unresolved reference.

- [ ] **Step 3: Add the pure decision function**

In `TidalOfficialSession.kt`, add a top-level function next to `validateRedirect` (same file, top level, `internal`):

```kotlin
/**
 * True when an access token expiring at [expiresAtMillis] should be
 * proactively refreshed at [nowMillis], treating anything within
 * [skewMillis] of expiry (or already expired, including the legacy
 * "unknown expiry = 0" case) as due. Pure so the refresh *decision* is
 * unit-testable without a live token endpoint.
 */
internal fun officialTokenNeedsRefresh(expiresAtMillis: Long, nowMillis: Long, skewMillis: Long): Boolean =
    nowMillis >= expiresAtMillis - skewMillis
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalOfficialSessionTest*'`
Expected: PASS.

- [ ] **Step 5: Change `Session` to absolute expiry + make `parseTokenResponse` clock-parameterized**

In `TidalOfficialAuth.kt`, replace the `Session` data class (L92) and `parseTokenResponse` (L94–97):

```kotlin
    /** A resolved session, in the naming this codebase's own [TidalAuth.Session] uses. */
    data class Session(val accessToken: String, val refreshToken: String?, val expiresAtMillis: Long)

    /**
     * Parse the OAuth2 token body, converting the relative `expires_in`
     * (seconds) to an absolute [Session.expiresAtMillis] using [nowMillis].
     * [nowMillis] is a parameter (not a `System.currentTimeMillis()` call)
     * so the conversion stays pure and unit-testable.
     */
    fun parseTokenResponse(body: String, nowMillis: Long): Session {
        val token = json.decodeFromString<TokenResponse>(body)
        return Session(token.access_token, token.refresh_token, nowMillis + token.expires_in * 1000L)
    }
```

- [ ] **Step 6: Update the two existing `parseTokenResponse` tests**

In `TidalOfficialAuthTest.kt`, update the calls at L103 and L112 to pass a fixed `nowMillis` and assert the absolute expiry:

```kotlin
    @Test
    fun `parseTokenResponse decodes the standard OAuth2 token body`() {
        val body = """
            {
              "access_token": "at-123",
              "refresh_token": "rt-456",
              "token_type": "Bearer",
              "expires_in": 3600
            }
        """.trimIndent()
        val token = TidalOfficialAuth.parseTokenResponse(body, nowMillis = 1_000_000L)
        assertEquals("at-123", token.accessToken)
        assertEquals("rt-456", token.refreshToken)
        assertEquals(1_000_000L + 3_600_000L, token.expiresAtMillis)
    }

    @Test
    fun `parseTokenResponse tolerates a missing refresh_token, as a refresh response may omit it`() {
        val body = """{"access_token": "at-123", "token_type": "Bearer", "expires_in": 3600}"""
        val token = TidalOfficialAuth.parseTokenResponse(body, nowMillis = 0L)
        assertEquals("at-123", token.accessToken)
        assertEquals(null, token.refreshToken)
    }
```

- [ ] **Step 7: Wire expiry persistence + refresh into `TidalOfficialSession`**

In `TidalOfficialSession.kt`: add the `clock` constructor param, persist/read `KEY_EXPIRES`, add `refresh()` + `validAccessToken()`, and use `clock()` in `completeLogin`. Replace the class body pieces:

Constructor (L50):
```kotlin
class TidalOfficialSession(
    context: Context,
    private val client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
```

`completeLogin`'s parse call (was L86):
```kotlin
                TidalOfficialAuth.parseTokenResponse(resp.body!!.string(), clock())
```

Replace `storedSession()` (L94–98) and `persist(...)` (L104–109), and add `refresh()`/`validAccessToken()`:
```kotlin
    suspend fun storedSession(): TidalOfficialAuth.Session? {
        val prefs = appContext.tidalOfficialDataStore.data.first()
        val access = prefs[KEY_ACCESS] ?: return null
        return TidalOfficialAuth.Session(access, prefs[KEY_REFRESH], prefs[KEY_EXPIRES]?.toLongOrNull() ?: 0L)
    }

    /**
     * A non-expired access token for the official API, refreshing if within
     * [REFRESH_SKEW_MS] of expiry. Returns null when there is no session or
     * the refresh fails (the caller should then prompt re-login). This is the
     * single accessor every metadata call goes through.
     */
    suspend fun validAccessToken(): String? {
        val session = storedSession() ?: return null
        if (!officialTokenNeedsRefresh(session.expiresAtMillis, clock(), REFRESH_SKEW_MS)) return session.accessToken
        return refresh()?.accessToken
    }

    /** Refresh the access token; returns null when re-login is required. Mirrors [TidalAuth.refresh]. */
    suspend fun refresh(): TidalOfficialAuth.Session? = withContext(Dispatchers.IO) {
        val refreshToken = storedSession()?.refreshToken ?: return@withContext null
        val request = TidalOfficialAuth.buildRefreshRequest(clientId, refreshToken)
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val parsed = TidalOfficialAuth.parseTokenResponse(resp.body!!.string(), clock())
            // Tidal may omit the refresh token on refresh; keep the old one.
            val merged = parsed.copy(refreshToken = parsed.refreshToken ?: refreshToken)
            persist(merged)
            merged
        }
    }

    private suspend fun persist(session: TidalOfficialAuth.Session) {
        appContext.tidalOfficialDataStore.edit {
            it[KEY_ACCESS] = session.accessToken
            session.refreshToken?.let { rt -> it[KEY_REFRESH] = rt }
            it[KEY_EXPIRES] = session.expiresAtMillis.toString()
        }
    }
```

Update the companion (L111–114) to add the expiry key + skew:
```kotlin
    companion object {
        private const val REFRESH_SKEW_MS = 60_000L
        private val KEY_ACCESS = stringPreferencesKey("access_token")
        private val KEY_REFRESH = stringPreferencesKey("refresh_token")
        private val KEY_EXPIRES = stringPreferencesKey("expires_at")
    }
```

Note `completeLogin` already calls `persist(it)` on the parsed session (now carrying `expiresAtMillis`), so login persists expiry with no further change.

- [ ] **Step 8: Run the full official-Tidal test suite + build**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalOfficial*' assembleDebug`
Expected: PASS; APK builds. (`Graph.init`'s `TidalOfficialSession(context, httpClient)` still compiles — `clock` defaults.)

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialAuth.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialSession.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialAuthTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialSessionTest.kt
git commit -m "feat(android): official Tidal session token refresh + expiry tracking

Ports TidalAuth's expiry/refresh pattern to TidalOfficialSession so
metadata calls survive token expiry. Adds validAccessToken() as the
single refresh-aware accessor. Prereq for the metadata layer.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Generic JSON:API compound-document parser

The official API returns JSON:API compound documents: a primary `data` resource (or array), an `included` array, and per-resource `relationships` linkage (`{type,id}`, single or array). This is the highest-risk parsing code, so it is built and tested in isolation from any network call. Existing `TidalOfficialApi.kt` DTOs are track-presentation-only and model no relationships — this is the general layer.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalJsonApi.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalJsonApiTest.kt`

**Interfaces:**
- Produces: `TidalResource(id, type, attributes: JsonObject?, relationships: Map<String, TidalRelationship>)`; `TidalDocument(data: JsonElement, included: List<TidalResource>)`; `TidalDocumentView(document, json)` with `primary: TidalResource?`, `primaryList: List<TidalResource>`, `related(resource, relationship): List<TidalResource>`; `inline fun <reified T> TidalResource.decodeAttributes(json): T?`.

- [ ] **Step 1: Write the failing test**

Create `TidalJsonApiTest.kt`. The fixture uses the spec's real attribute names (`Artists_Attributes.name`, `ArtistBiographies_Attributes.text`, `Albums_Attributes.title`) and JSON:API structure:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalJsonApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable data class Name(val name: String? = null)
    @Serializable data class Bio(val text: String? = null)
    @Serializable data class Title(val title: String? = null)

    private val artistDoc = """
        {
          "data": {
            "id": "100", "type": "artists",
            "attributes": { "name": "Prompto", "popularity": 0.5 },
            "relationships": {
              "biography":      { "data": { "id": "b1", "type": "artistBiographies" } },
              "similarArtists": { "data": [ { "id": "200", "type": "artists" }, { "id": "201", "type": "artists" } ] },
              "albums":         { "data": [ { "id": "300", "type": "albums" } ] }
            }
          },
          "included": [
            { "id": "b1",  "type": "artistBiographies", "attributes": { "text": "A biography.", "source": "TIDAL" } },
            { "id": "200", "type": "artists", "attributes": { "name": "Trevor Something" } },
            { "id": "201", "type": "artists", "attributes": { "name": "Com Truise" } },
            { "id": "300", "type": "albums",  "attributes": { "title": "Album One" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `primary resource is parsed with type and attributes`() {
        val doc = json.decodeFromString<TidalDocument>(artistDoc)
        val view = TidalDocumentView(doc, json)
        val artist = view.primary!!
        assertEquals("artists", artist.type)
        assertEquals("100", artist.id)
        assertEquals("Prompto", artist.decodeAttributes<Name>(json)?.name)
    }

    @Test
    fun `single-object relationship resolves to one included resource`() {
        val view = TidalDocumentView(json.decodeFromString(artistDoc), json)
        val bio = view.related(view.primary!!, "biography")
        assertEquals(1, bio.size)
        assertEquals("A biography.", bio.first().decodeAttributes<Bio>(json)?.text)
    }

    @Test
    fun `array relationship resolves to included resources in order`() {
        val view = TidalDocumentView(json.decodeFromString(artistDoc), json)
        val similar = view.related(view.primary!!, "similarArtists")
            .mapNotNull { it.decodeAttributes<Name>(json)?.name }
        assertEquals(listOf("Trevor Something", "Com Truise"), similar)
        val albums = view.related(view.primary!!, "albums")
            .mapNotNull { it.decodeAttributes<Title>(json)?.title }
        assertEquals(listOf("Album One"), albums)
    }

    @Test
    fun `missing relationship yields empty list, not a crash`() {
        val view = TidalDocumentView(json.decodeFromString(artistDoc), json)
        assertTrue(view.related(view.primary!!, "doesNotExist").isEmpty())
    }

    @Test
    fun `relationship pointing at an absent included resource is dropped`() {
        val doc = """
            { "data": { "id": "1", "type": "artists",
              "relationships": { "albums": { "data": [ { "id": "999", "type": "albums" } ] } } },
              "included": [] }
        """.trimIndent()
        val view = TidalDocumentView(json.decodeFromString(doc), json)
        assertTrue(view.related(view.primary!!, "albums").isEmpty())
    }

    @Test
    fun `array data document exposes every primary resource`() {
        val doc = """
            { "data": [ { "id": "1", "type": "artists" }, { "id": "2", "type": "artists" } ], "included": [] }
        """.trimIndent()
        val view = TidalDocumentView(json.decodeFromString(doc), json)
        assertEquals(2, view.primaryList.size)
        assertEquals(listOf("1", "2"), view.primaryList.map { it.id })
    }

    @Test
    fun `decodeAttributes returns null when attributes are absent`() {
        val doc = """{ "data": { "id": "1", "type": "artists" }, "included": [] }"""
        val view = TidalDocumentView(json.decodeFromString(doc), json)
        assertNull(view.primary!!.decodeAttributes<Name>(json))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalJsonApiTest*'`
Expected: FAIL — `TidalDocument`/`TidalResource`/`TidalDocumentView`/`decodeAttributes` unresolved.

- [ ] **Step 3: Write the implementation**

Create `TidalJsonApi.kt`:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A reusable JSON:API (`openapi.tidal.com/v2`) compound-document layer for
 * the Tidal metadata surface (enrichment/collection/discovery). Unlike the
 * track-presentation-only DTOs in `TidalOfficialApi.kt`, this models the
 * general `data` + `included` + `relationships` envelope so a single
 * `?include=...` call resolves related resources locally, with no extra
 * round-trips. Attribute bodies stay as raw [JsonObject] and are decoded
 * per resource type on demand via [decodeAttributes] — the one envelope
 * fits every resource type (artists, albums, tracks, ...).
 */

/** A JSON:API resource-linkage identifier: `{ "type": ..., "id": ... }`. */
@Serializable
data class TidalResourceId(val type: String, val id: String)

/** A relationship's `data` is either a single `{type,id}` or an array of them; kept raw and normalized by [TidalDocumentView]. */
@Serializable
data class TidalRelationship(val data: JsonElement? = null)

/** One JSON:API resource: identity, free-form [attributes], and named [relationships] (linkage only). */
@Serializable
data class TidalResource(
    val id: String,
    val type: String,
    val attributes: JsonObject? = null,
    val relationships: Map<String, TidalRelationship> = emptyMap(),
)

/** A JSON:API compound document. `data` is a single resource or an array; `included` carries `?include=`'d resources. */
@Serializable
data class TidalDocument(
    val data: JsonElement,
    val included: List<TidalResource> = emptyList(),
)

/** Decode a resource's `attributes` into a typed class, or null if absent/malformed (tolerant, like the spike DTOs). */
inline fun <reified T> TidalResource.decodeAttributes(json: Json): T? =
    attributes?.let { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }

/**
 * Read-side helper over a parsed [TidalDocument]: indexes `included` by
 * (type,id) so [related] resolves a resource's relationship to the full
 * included resources in O(1). All single-vs-array `data` normalization
 * lives here, so callers work in terms of [TidalResource]s, never raw JSON.
 */
class TidalDocumentView(document: TidalDocument, private val json: Json) {
    private val index: Map<TidalResourceId, TidalResource> =
        document.included.associateBy { TidalResourceId(it.type, it.id) }

    /** Every primary resource; one element for object `data`, many for array `data`. */
    val primaryList: List<TidalResource> = resourcesFrom(document.data)

    /** The document's primary resource (first, if `data` was an array). */
    val primary: TidalResource? get() = primaryList.firstOrNull()

    /** The included resources linked by [resource]'s named [relationship], in linkage order; absent links are dropped. */
    fun related(resource: TidalResource, relationship: String): List<TidalResource> {
        val link = resource.relationships[relationship]?.data ?: return emptyList()
        return identifiersFrom(link).mapNotNull { index[it] }
    }

    private fun resourcesFrom(el: JsonElement): List<TidalResource> = when (el) {
        is JsonArray -> el.map { json.decodeFromJsonElement<TidalResource>(it) }
        is JsonObject -> listOf(json.decodeFromJsonElement(el))
        else -> emptyList()
    }

    private fun identifiersFrom(el: JsonElement): List<TidalResourceId> = when (el) {
        is JsonArray -> el.map { json.decodeFromJsonElement<TidalResourceId>(it) }
        is JsonObject -> listOf(json.decodeFromJsonElement(el))
        else -> emptyList()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalJsonApiTest*'`
Expected: PASS (all 7).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalJsonApi.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalJsonApiTest.kt
git commit -m "feat(android): generic JSON:API compound-document parser for Tidal metadata

TidalDocument/TidalResource/TidalDocumentView resolve data+included+
relationships (single or array) so one ?include= call resolves related
resources locally. Foundation for artist/album/track enrichment.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `TidalMetadataRepository` + artist-overview + on-device probe

Ties Tasks 1–2 together into a repository seam on `Graph`, and proves the whole read path (refresh → fetch → compound-parse → domain projection) end-to-end with an artist-metadata probe in the existing debug screen — the metadata analogue of the Task-1 streaming probe. The domain projection is pure and unit-tested against a real compound fixture; the network wrapper and UI follow the untested-network / untested-VM-passthrough precedent, closing on an on-device checkpoint.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt` (add `artistRequest`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt` (add `getArtist`)
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt` (attr DTOs, `ArtistOverview`, `artistOverviewFrom`, repository)
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (`Graph.tidalMetadata` wiring)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (`tidalMetadataStatus` flow + `runTidalMetadataProbe`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/TidalOfficialDebugScreen.kt` (artist-id field + "Fetch artist metadata" button + status readout)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepositoryTest.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt` (add `artistRequest` shape)

**Interfaces:**
- Consumes: `TidalOfficialSession.validAccessToken()` (Task 1); `TidalDocument`/`TidalDocumentView`/`decodeAttributes` (Task 2).
- Produces: `ArtistOverview(id, name, biography: String?, similarArtistNames: List<String>, albumTitles: List<String>)`; `artistOverviewFrom(doc: TidalDocument, json: Json): ArtistOverview?`; `TidalMetadataRepository.artistOverview(artistId: String): Result<ArtistOverview>`; `Graph.tidalMetadata: TidalMetadataRepository`.

- [ ] **Step 1: Write the failing test for the pure projection + endpoint shape**

Create `TidalMetadataRepositoryTest.kt` (reuses the artist-doc structure, real attribute names):

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidalMetadataRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val artistDoc = """
        {
          "data": { "id": "100", "type": "artists",
            "attributes": { "name": "Prompto" },
            "relationships": {
              "biography":      { "data": { "id": "b1", "type": "artistBiographies" } },
              "similarArtists": { "data": [ { "id": "200", "type": "artists" }, { "id": "201", "type": "artists" } ] },
              "albums":         { "data": [ { "id": "300", "type": "albums" }, { "id": "301", "type": "albums" } ] }
            } },
          "included": [
            { "id": "b1",  "type": "artistBiographies", "attributes": { "text": "A biography.", "source": "TIDAL" } },
            { "id": "200", "type": "artists", "attributes": { "name": "Trevor Something" } },
            { "id": "201", "type": "artists", "attributes": { "name": "Com Truise" } },
            { "id": "300", "type": "albums",  "attributes": { "title": "Album One", "albumType": "ALBUM" } },
            { "id": "301", "type": "albums",  "attributes": { "title": "Album Two", "albumType": "EP" } }
          ]
        }
    """.trimIndent()

    @Test
    fun `artistOverviewFrom maps name, biography, similar artists and albums`() {
        val o = artistOverviewFrom(json.decodeFromString(artistDoc), json)!!
        assertEquals("100", o.id)
        assertEquals("Prompto", o.name)
        assertEquals("A biography.", o.biography)
        assertEquals(listOf("Trevor Something", "Com Truise"), o.similarArtistNames)
        assertEquals(listOf("Album One", "Album Two"), o.albumTitles)
    }

    @Test
    fun `artistOverviewFrom tolerates missing relationships`() {
        val doc = """{ "data": { "id": "5", "type": "artists", "attributes": { "name": "Solo" } }, "included": [] }"""
        val o = artistOverviewFrom(json.decodeFromString(doc), json)!!
        assertEquals("Solo", o.name)
        assertNull(o.biography)
        assertEquals(emptyList<String>(), o.similarArtistNames)
        assertEquals(emptyList<String>(), o.albumTitles)
    }

    @Test
    fun `artistOverviewFrom returns null when there is no primary resource`() {
        val doc = """{ "data": [], "included": [] }"""
        assertNull(artistOverviewFrom(json.decodeFromString(doc), json))
    }

    @Test
    fun `artistRequest carries every include and the country code`() {
        val req = TidalOfficialEndpoints.artistRequest(
            id = "100", accessToken = "tok",
            include = listOf("biography", "similarArtists", "albums"), countryCode = "US",
        )
        val url = req.url
        assertEquals(listOf("biography", "similarArtists", "albums"), url.queryParameterValues("include"))
        assertEquals("US", url.queryParameter("countryCode"))
        assertEquals("Bearer tok", req.header("Authorization"))
        assertEquals("/v2/artists/100", url.encodedPath)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalMetadataRepositoryTest*'`
Expected: FAIL — `artistOverviewFrom`, `TidalOfficialEndpoints.artistRequest` unresolved.

- [ ] **Step 3: Add the `artistRequest` endpoint builder**

In `TidalOfficialEndpoints.kt`, add:

```kotlin
    /**
     * `GET /artists/{id}` with `?include=...` (JSON:API compound document)
     * so biography/similarArtists/albums come back in one call. `countryCode`
     * is optional per the spec (only `id` is required) but supplied for
     * correct regional catalog results.
     */
    fun artistRequest(id: String, accessToken: String, include: List<String>, countryCode: String? = null): Request {
        val url = "$BASE/artists/$id".toHttpUrl().newBuilder()
            .apply { countryCode?.let { addQueryParameter("countryCode", it) } }
            .apply { include.forEach { addQueryParameter("include", it) } }
            .build()
        return authed(url, accessToken)
    }
```

- [ ] **Step 4: Add `getArtist` to the client**

In `TidalOfficialClient.kt`, add (its generic `execute<T>` already parses any `@Serializable` `T`, and `TidalDocument` is one):

```kotlin
    /** Fetch an artist as a JSON:API compound document (see [TidalOfficialEndpoints.artistRequest]). */
    suspend fun getArtist(
        id: String,
        accessToken: String,
        include: List<String>,
        countryCode: String? = null,
    ): Result<TidalDocument> = execute(TidalOfficialEndpoints.artistRequest(id, accessToken, include, countryCode))
```

- [ ] **Step 5: Create the repository, projection, and attribute DTOs**

Create `TidalMetadataRepository.kt`:

```kotlin
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
```

- [ ] **Step 6: Run the repository/endpoint tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalMetadataRepositoryTest*'`
Expected: PASS (all 4).

- [ ] **Step 7: Wire the repository onto `Graph`**

In `AuxenApp.kt`, in `object Graph`, add the field beside `tidalOfficialApi`:

```kotlin
    lateinit var tidalMetadata: TidalMetadataRepository
        private set
```

and in `init(context)`, after `tidalOfficialApi = TidalOfficialClient(httpClient)`:

```kotlin
        tidalMetadata = TidalMetadataRepository(
            session = tidalOfficialSession,
            client = tidalOfficialApi,
            json = json,
            countryCode = java.util.Locale.getDefault().country.ifBlank { "US" },
            locale = java.util.Locale.getDefault().toLanguageTag(),
        )
```

`locale` is passed here now but reserved for Slices 2/3 (collection paging / discovery `locale`); the Foundation itself does not read it. No later slice rewrites this call — Slices 2/3 only APPEND (e.g. Slice 2's `tidalCollectionSync = TidalCollectionSync(...)` line).

- [ ] **Step 8: Add the ViewModel probe entry point**

In `PlayerViewModel.kt`, mirror the existing `tidalOfficialStatus` / `runTidalOfficialStreamingProbe` wiring. Add a status flow and a probe function:

```kotlin
    private val _tidalMetadataStatus = MutableStateFlow<String?>(null)
    val tidalMetadataStatus: StateFlow<String?> = _tidalMetadataStatus.asStateFlow()

    /** Debug/probe: fetch an artist's official-API metadata overview and render it as a status string. */
    fun runTidalMetadataProbe(artistId: String) {
        viewModelScope.launch {
            _tidalMetadataStatus.value = "Fetching artist $artistId…"
            _tidalMetadataStatus.value = Graph.tidalMetadata.artistOverview(artistId.trim()).fold(
                onSuccess = { o ->
                    buildString {
                        appendLine("Artist: ${o.name} (id ${o.id})")
                        appendLine("Biography: ${o.biography?.take(160) ?: "—"}")
                        appendLine("Similar (${o.similarArtistNames.size}): ${o.similarArtistNames.take(5).joinToString()}")
                        appendLine("Albums (${o.albumTitles.size}): ${o.albumTitles.take(5).joinToString()}")
                    }
                },
                onFailure = { "Metadata probe failed: ${it.message}" },
            )
        }
    }
```

Match the file's existing import style (`MutableStateFlow`/`asStateFlow`/`viewModelScope`/`launch` are already imported for the streaming-probe wiring). No token or URL is ever put in the status string — only names/titles.

- [ ] **Step 9: Add the probe UI to the debug screen**

In `TidalOfficialDebugScreen.kt`, below the existing streaming-probe section, add an artist-id `OutlinedTextField` + a "Fetch artist metadata" `Button` calling `onRunMetadataProbe(artistId)`, and render `metadataStatus` in a `Text` (monospace, same style as the streaming status). Thread `metadataStatus: String?` and `onRunMetadataProbe: (String) -> Unit` from the screen's caller the same way `tidalOfficialStatus`/`onRunStreamingProbe` are already threaded. (Compose debug screen — not unit-tested, matching the Task-1 precedent for `TidalOfficialDebugScreen`.)

- [ ] **Step 10: Full gate + build**

Run: `cd android && ./gradlew testDebugUnitTest verifyRoborazziDebug assembleDebug`
Expected: PASS. (If the debug screen's golden shifts because the new section extends the scroll content, re-record with `./gradlew recordRoborazziDebug` and eyeball the diff — the streaming-probe section set this precedent.)

- [ ] **Step 11: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt \
        android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/TidalOfficialDebugScreen.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepositoryTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt
# plus any re-recorded golden under app/src/test/.../roborazzi/ if Step 10 changed it
git commit -m "feat(android): Tidal metadata repository + artist-overview probe

Adds TidalMetadataRepository (validAccessToken -> getArtist -> compound
projection) on Graph and an artist-metadata probe in the official debug
screen, proving the official-API read path end-to-end. Metadata only;
streaming stays on internal-v1.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 12: On-device checkpoint (the Foundation deliverable)**

Build the APK **locally** (CI has no Tidal creds): `cd android && ./gradlew assembleDebug`. Install, then Settings → Advanced → "Try official Tidal login (beta)" → log in → enter a Tidal **artist** id (the numeric id from a `tidal.com/browse/artist/<id>` URL) → "Fetch artist metadata". Confirm the readout shows the artist name, a biography snippet, similar-artist names, and album titles. Also verify token refresh by re-running the probe after the access token would have expired (or after force-stopping and reopening). This is the un-CI-testable confirmation, exactly like the Task-1 streaming probe — paste the readout back to close the loop.

---

### Task 4: Shared metadata primitives (artwork, duration, current-user id, locale)

The feature slices all need the same low-level pieces, so they are declared ONCE here as Slice-0 infrastructure — re-declaring them per slice is a Kotlin "conflicting declarations" compile error. Enrichment (Slice 1) and Discovery (Slice 3) both need best-fit artwork selection from `Artworks_Attributes.files` and ISO-8601 duration/release-date parsing; Collection (Slice 2) needs the current user's numeric id (its `userCollections` id) and Discovery needs a `locale`. This task also carries the small `/users/me` resolver and adds `currentUserId()` onto the repository whose constructor Task 3 already froze at its final shape.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataPrimitives.kt` (artwork DTOs + `pickArtworkUrl` + `parseIsoDurationSeconds` + `releaseYearOf`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt` (add `userRequest`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt` (add `getUser`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt` (add top-level `userIdFrom` + cached `currentUserId()`; constructor already final from Task 3)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataPrimitivesTest.kt` (create)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepositoryTest.kt` (add `userIdFrom` cases)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt` (add `userRequest` shape)

**Interfaces:**
- Consumes: `TidalDocument`/`TidalDocumentView` (Task 2); `TidalOfficialSession.validAccessToken()` (Task 1); the generic `TidalOfficialClient.execute<T>`.
- Produces (shared infrastructure the feature slices CONSUME, never re-declare): `@Serializable ArtworkFileMeta(width, height)`; `@Serializable ArtworkFile(href, meta)` (href nullable — defensive); `@Serializable ArtworkAttributes(files, mediaType)`; `fun pickArtworkUrl(files: List<ArtworkFile>, targetPx: Int): String?`; `fun parseIsoDurationSeconds(iso: String?): Double?`; `fun releaseYearOf(releaseDate: String?): Int?`; `fun userIdFrom(doc: TidalDocument, json: Json): String?`; `TidalOfficialEndpoints.userRequest(id, accessToken): Request`; `TidalOfficialClient.getUser(id, accessToken): Result<TidalDocument>`; `TidalMetadataRepository.currentUserId(): String?`.

- [ ] **Step 1: Write the failing tests**

Create `TidalMetadataPrimitivesTest.kt`:

```kotlin
package io.github.auxen.provider.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidalMetadataPrimitivesTest {
    private fun file(href: String?, w: Int?) = ArtworkFile(href = href, meta = ArtworkFileMeta(width = w, height = w))

    @Test
    fun `picks the smallest file at or above the target width`() {
        val files = listOf(file("a80", 80), file("a320", 320), file("a640", 640), file("a1280", 1280))
        assertEquals("a640", pickArtworkUrl(files, targetPx = 500))
        assertEquals("a640", pickArtworkUrl(files, targetPx = 640))
    }

    @Test
    fun `falls back to the largest file when none reach the target`() {
        val files = listOf(file("a80", 80), file("a160", 160))
        assertEquals("a160", pickArtworkUrl(files, targetPx = 640))
    }

    @Test
    fun `ignores files without an href`() {
        val files = listOf(file(null, 1280), file("a320", 320))
        assertEquals("a320", pickArtworkUrl(files, targetPx = 300))
    }

    @Test
    fun `returns null for empty files`() {
        assertNull(pickArtworkUrl(emptyList(), targetPx = 640))
    }

    @Test fun `parses ISO 8601 track duration to seconds`() {
        assertEquals(178.0, parseIsoDurationSeconds("PT2M58S")!!, 0.0)
        assertEquals(180.0, parseIsoDurationSeconds("PT3M")!!, 0.0)
    }

    @Test fun `duration is null for null or unparseable input`() {
        assertNull(parseIsoDurationSeconds(null))
        assertNull(parseIsoDurationSeconds("not-a-duration"))
    }

    @Test fun `takes the release year from an ISO date`() {
        assertEquals(2021, releaseYearOf("2021-05-14"))
    }

    @Test fun `release year is null for null or empty input`() {
        assertNull(releaseYearOf(null))
        assertNull(releaseYearOf(""))
    }
}
```

Add the `userIdFrom` cases to `TidalMetadataRepositoryTest.kt` (it already imports `Json`/`assertEquals`/`assertNull`):

```kotlin
    @Test
    fun `userIdFrom returns the primary user resource id`() {
        val doc = """
            { "data": { "id": "987654", "type": "users",
              "attributes": { "username": "matt", "country": "US" } } }
        """.trimIndent()
        assertEquals("987654", userIdFrom(json.decodeFromString(doc), json))
    }

    @Test
    fun `userIdFrom returns null when there is no primary user`() {
        assertNull(userIdFrom(json.decodeFromString("""{ "data": [] }"""), json))
    }
```

Add the `userRequest` shape to `TidalOfficialEndpointsTest.kt`:

```kotlin
    @Test
    fun `user request hits users me with the bearer token`() {
        val request = TidalOfficialEndpoints.userRequest(id = "me", accessToken = "tok-123")
        assertEquals("https://openapi.tidal.com/v2/users/me", request.url.toString())
        assertEquals("Bearer tok-123", request.header("Authorization"))
        assertEquals("application/vnd.api+json", request.header("Accept"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalMetadataPrimitivesTest*' --tests '*TidalMetadataRepositoryTest*' --tests '*TidalOfficialEndpointsTest*'`
Expected: FAIL — `ArtworkFile`/`pickArtworkUrl`/`parseIsoDurationSeconds`/`releaseYearOf`/`userIdFrom`/`TidalOfficialEndpoints.userRequest` unresolved.

- [ ] **Step 3: Create `TidalMetadataPrimitives.kt`**

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * Shared low-level primitives every metadata slice (Enrichment/Collection/
 * Discovery) consumes: the artwork DTOs + best-fit selection, and ISO-8601
 * duration/release-date parsing. Declared ONCE here (Slice 0) so the feature
 * slices consume them and never re-declare them (a Kotlin "conflicting
 * declarations" error). Field names are verbatim from the cached spec
 * (`Artworks_Attributes` / `Artwork_File` / `Artwork_File_Meta`).
 */
@Serializable
data class ArtworkFileMeta(val width: Int? = null, val height: Int? = null)

/** One `Artwork_File`: a concrete image [href] (nullable — defensive) plus its pixel [meta]. */
@Serializable
data class ArtworkFile(val href: String? = null, val meta: ArtworkFileMeta? = null)

/** `Artworks_Attributes`: the `files` list of rendered sizes, shared by artist `profileArt`, album `coverArt`, and any track/mix art. */
@Serializable
data class ArtworkAttributes(val files: List<ArtworkFile> = emptyList(), val mediaType: String? = null)

/**
 * Best-fit artwork URL for a [targetPx] slot: the smallest file at or above
 * [targetPx] wide (avoids upscaling a tiny thumb into a big slot), or the
 * widest available if none reach it. Files without an [ArtworkFile.href] are
 * ignored; null when there is no usable file. Pure and reusable across
 * artist/album/track/mix projections.
 */
fun pickArtworkUrl(files: List<ArtworkFile>, targetPx: Int): String? {
    val usable = files.filter { it.href != null }
    if (usable.isEmpty()) return null
    val atLeast = usable.filter { (it.meta?.width ?: 0) >= targetPx }
        .minByOrNull { it.meta?.width ?: Int.MAX_VALUE }
    return (atLeast ?: usable.maxByOrNull { it.meta?.width ?: 0 })?.href
}

/**
 * ISO-8601 duration (`Tracks_Attributes.duration`, e.g. "PT2M58S") → whole
 * seconds as a Double, matching [io.github.auxen.model.Track.durationSeconds].
 * Null/unparseable → null (tolerant of a versioned external API). `java.time`
 * is available at minSdk 26.
 */
fun parseIsoDurationSeconds(iso: String?): Double? {
    if (iso.isNullOrBlank()) return null
    return runCatching { Duration.parse(iso).seconds.toDouble() }.getOrNull()
}

/** Release year from `Albums_Attributes.releaseDate` (ISO date "YYYY-MM-DD"); null when absent/malformed. */
fun releaseYearOf(releaseDate: String?): Int? = releaseDate?.take(4)?.toIntOrNull()
```

- [ ] **Step 4: Add `userRequest` + `getUser`**

In `TidalOfficialEndpoints.kt`, add (reuses the private `authed` helper and `toHttpUrl`, already imported for `artistRequest`):

```kotlin
    /**
     * `GET /users/{id}` — pass `id = "me"` for the authenticated user's
     * resource (spec convention). THE single source of the numeric user id for
     * any user/collection-scoped call (see [TidalMetadataRepository.currentUserId]).
     */
    fun userRequest(id: String, accessToken: String): Request =
        authed("$BASE/users/$id".toHttpUrl(), accessToken)
```

In `TidalOfficialClient.kt`, add (the generic `execute<T>` parses any `@Serializable T`, and `TidalDocument` is one):

```kotlin
    /** Fetch a user as a JSON:API compound document (`id = "me"` for the current user). */
    suspend fun getUser(id: String, accessToken: String): Result<TidalDocument> =
        execute(TidalOfficialEndpoints.userRequest(id, accessToken))
```

- [ ] **Step 5: Add the pure `userIdFrom` + cached `currentUserId()` to `TidalMetadataRepository.kt`**

Add the pure projection at top level (next to `artistOverviewFrom`):

```kotlin
/** The current user's id (which is also their userCollection id), from `GET /users/me`. Pure. */
fun userIdFrom(doc: TidalDocument, json: Json): String? =
    TidalDocumentView(doc, json).primary?.id
```

Inside the class (the constructor already carries `countryCode`/`locale` from Task 3), add the cache field + resolver:

```kotlin
    // The current user's numeric id (also their userCollection id), resolved
    // once via /users/me and cached for the process lifetime (cleared implicitly
    // on process death). THE single source of the user id for any user/
    // collection-scoped call — Slice 2's resolveCollectionId() delegates here.
    @Volatile private var cachedUserId: String? = null

    /**
     * The current user's id via `GET /users/me` (JSON:API `data.id`), cached
     * in-memory. Returns null when not logged in to the official API or the id
     * cannot be resolved (the caller should surface "sign in"). This is the
     * single accessor every user/collection-scoped call goes through.
     */
    suspend fun currentUserId(): String? {
        cachedUserId?.let { return it }
        val token = session.validAccessToken() ?: return null
        val id = client.getUser("me", token)
            .mapCatching { userIdFrom(it, json) ?: error("No user id in /users/me response") }
            .getOrNull()
        if (id != null) cachedUserId = id
        return id
    }
```

- [ ] **Step 6: Run tests to verify they pass + build**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalMetadataPrimitivesTest*' --tests '*TidalMetadataRepositoryTest*' --tests '*TidalOfficialEndpointsTest*' assembleDebug`
Expected: PASS; APK builds. (`Graph.init`'s `TidalMetadataRepository(...)` already passes `locale` from Task 3, so no wiring change here.)

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataPrimitives.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataPrimitivesTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepositoryTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt
git commit -m "feat(android): shared Tidal metadata primitives (artwork, duration, user id)

Defines the artwork DTOs + pickArtworkUrl, parseIsoDurationSeconds/
releaseYearOf, and the /users/me currentUserId() resolver ONCE as Slice-0
infrastructure, so the Enrichment/Collection/Discovery slices consume them
without re-declaring (no conflicting declarations). Metadata only.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-review (writing-plans checklist)

- **Spec coverage:** Session refresh (verdict "keep official for metadata" requires a durable session) ✓ Task 1; JSON:API compound parsing (every metadata endpoint returns it) ✓ Task 2; repository seam + proof (the "layer the official API" deliverable) ✓ Task 3; shared metadata primitives (artwork DTOs + `pickArtworkUrl`, `parseIsoDurationSeconds`/`releaseYearOf`, `currentUserId()`/`userIdFrom`) defined once as Slice-0 infrastructure the feature slices consume ✓ Task 4. Slices 1–3 are roadmapped, to be detailed after the Part-1 product decisions. Deferred hardening (PKCE-persist, token encryption) explicitly listed, not silently dropped.
- **Placeholder scan:** every code step shows real code; test steps show real assertions; the one non-TDD step (Step 9 Compose UI) is explicitly justified by the untested-debug-screen precedent and closes on the Step 12 on-device checkpoint.
- **Type consistency:** `expiresAtMillis` (renamed from `expiresInSeconds`) used consistently across `TidalOfficialAuth.Session`, `parseTokenResponse`, `storedSession`, `persist`, `officialTokenNeedsRefresh`; `parseTokenResponse(body, nowMillis)`'s new arg updated at both its call sites (`TidalOfficialSession.completeLogin`, `refresh`) and both tests; `TidalDocument`/`TidalResource`/`TidalDocumentView`/`decodeAttributes`/`related` names match between Task 2 (definition) and Task 3 (use); `ArtistOverview`/`artistOverviewFrom`/`artistOverview` distinct and consistent; `ARTIST_INCLUDE` matches the endpoint's `include` param. The `TidalMetadataRepository` constructor is finalized with the reserved `locale` param in Task 3 (Slices 2/3 only APPEND, never rewrite it); Task 4's shared symbols — `pickArtworkUrl(files: List<ArtworkFile>, targetPx)`, `parseIsoDurationSeconds`/`releaseYearOf`, and `userIdFrom`/`currentUserId` — keep the exact names the feature slices reference.
```
