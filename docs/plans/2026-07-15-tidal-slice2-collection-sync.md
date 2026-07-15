# Slice 2 — Collection Sync (Two-Way) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mirror the user's Tidal favorites (tracks/albums/artists) into the app via the sanctioned official `openapi.tidal.com/v2` `userCollections` API, and push local favorite toggles back to Tidal — reconciled with the existing local favorites (`LibraryRepository` + Room), keyed by `source:sourceId`. Streaming of a favorited Tidal track still goes through the internal-v1 `TidalProvider` path, untouched.

**Architecture:** Additive, built entirely on the Slice 0 Foundation seams (`TidalOfficialSession.validAccessToken()`, the `TidalJsonApi` compound-document layer, `TidalOfficialEndpoints`/`TidalOfficialClient`, and `Graph.tidalMetadata: TidalMetadataRepository`). Three concerns, cleanly separated:

- **Official-API access (metadata only)** — new pure request builders on `TidalOfficialEndpoints`, thin execute wrappers on `TidalOfficialClient` (including a new body-tolerant `executeUnit` for writes), and new suspend methods + pure projections on `TidalMetadataRepository`. All parsing/mapping/request-shape logic is pure and unit-tested; HTTP execution is never unit-tested (Slice 0 precedent).
- **The collection-id discovery problem** (the trickiest part) — the `userCollections/{id}` id is the current user's **user id**, obtained via `GET /users/{id}` with the special `id = "me"` (spec: *"Use `me` for the authenticated user's resource"*). That resolver (`userRequest`/`getUser`/pure `userIdFrom` + the cached `TidalMetadataRepository.currentUserId()`) now lives in the **Slice-0 Foundation (Task 4)** — shared with Slice 3, declared once. This slice's `resolveCollectionId()` is a one-line delegate to `currentUserId()` and is used as the collection id for every collection read/write. `user.read` scope is already requested (`TidalOfficialAuth.SCOPE`).
- **Local reconcile + push** — a new `TidalCollectionSync` (held on `Graph`) composes `TidalMetadataRepository` (official API) with `LibraryRepository` (local DB). Its merge logic is a **pure** set-difference function (unit-tested); its network+DB orchestration is not. Push is wired into `PlayerViewModel.toggleFavorite`; a manual "Sync favorites from Tidal" pull is surfaced in the existing `TidalOfficialDebugScreen`, closing the slice on an on-device checkpoint (mirroring the Slice 0 probe).

**Design decisions (locked for this slice):**

- **Dedup / conflict key = `source:sourceId`** (e.g. `TIDAL:75413016`), the same identity `LibraryRepository.favoriteKeys()` already emits. A Tidal favorite (`TIDAL:<id>`) and a local file of the same recording are *distinct* keys unless linked by `TrackEntity.match_group_id` — that match-group linking is a latent DB hook (`TrackEntity.match_group_id`, `PlaylistEntity.source`/`tidalPlaylistId`) noted but **out of scope** to compute here.
- **Pull is an additive union (non-destructive).** Remote favorites missing locally are added; local favorites missing remotely are **not** deleted by a pull (they are pushed instead). This keeps sync idempotent and never destroys a local-only favorite. A destructive "mirror-delete" mode is future work (needs tombstones).
- **Push is optimistic and best-effort.** `toggleFavorite` updates the local DB immediately (unchanged), then fires the official write for **Tidal-source tracks only** (a local file is not on Tidal). A failed write never rolls back the local toggle; the next full pull re-reconciles the union. No offline write-queue in this slice (documented limitation).
- **Sync triggers:** (1) push on every `toggleFavorite` of a Tidal track; (2) manual pull button (debug screen this slice; Collection UI later). Auto-pull-on-login is a one-line addition noted in Task 5 but intentionally left to the product wiring (avoids surprising network + placeholder inserts on every launch).
- **Full-metadata caveat:** the official favorites endpoints return a track's `title`/`isrc`/`duration` but **not** its artist name (artist is a `tracks` relationship the favorites endpoint cannot `include`). So a favorited Tidal track the app has never seen locally is inserted as a **minimal placeholder** (`title` only) that **self-heals** the moment internal-v1 search/play upserts the same `(TIDAL, id)` row with full metadata. - **OPEN QUESTION — album/artist favorites have no local surface (needs a product decision).** Album and artist favorites get the full official-API read/write layer + projection (request shapes + parsing, unit-tested), but there is **no local-DB reconcile** for them: the current schema has **no album or artist tables** — the Collection view derives its Albums/Artists tabs by *grouping favorited tracks*. Consequence: favoriting a Tidal **album** or **artist** via the official write succeeds on Tidal but **will not surface anywhere in the app UI** without schema work (album/artist tables + reconcile). So this slice ships album/artist favorite *plumbing* but wires only **track** favorites end-to-end. Whether to add the schema (to make album/artist favorites visible) is a product decision, flagged here and in Open Questions, not silently resolved.

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`; `encodeDefaults` stays default-`false` so null `meta` is omitted), OkHttp 4.12 (+ transitive okio, used in tests to read request bodies), AndroidX DataStore, Room, Compose (debug screen), JUnit4 + Robolectric. `java.time` for ISO-8601 `addedAt` parsing (minSdk 26). minSdk 26 / compileSdk 35 / Media3 1.5.1.

## Global Constraints

- **PKCE public client — NEVER send `client_secret`** on any official-API request. All collection reads/writes go through `authed(url, accessToken)` / the write builder, which set only `Authorization: Bearer` + `Accept: application/vnd.api+json`.
- **Never log or print `accessToken`/`refreshToken`.** Only human-readable names/titles/counts go to Logcat or any status string.
- **Do not unit-test live HTTP execution** (Slice 0 precedent). Unit-test **pure** logic only: JSON:API parsing, document→domain projection, write-payload encoding, request shapes, and the pure merge/reconcile set-logic. Extract pure functions from every suspend/network wrapper so the logic is testable with no network.
- **Writes never execute in tests** — assert the request **shape** (method / path / headers / serialized body) and the payload **encoding**; the suspend write wrappers themselves are untested (network).
- **New classes stay plain instantiable classes held on `Graph`** (`TidalCollectionSync`), never JVM-wide `object`s with mutable state — so tests need no `@Before` reset. (`TidalOfficialEndpoints` stays a stateless pure-builder `object`, matching Slice 0; the only value it holds is an immutable `MediaType` constant.)
- **Internal-v1 code (`TidalAuth.kt`, `TidalProvider.kt`) is untouched.** A favorited Tidal track still streams via `TidalProvider.getStreamInfoById(sourceId)`. Confirm with `git status` at the end.
- **Streaming/search stay on internal-v1.** This slice is metadata + favorite-state only. Never route playback or search through the official path.
- **Build on Slice 0, don't re-invent.** Reuse `TidalDocument`/`TidalResource`/`TidalDocumentView`/`decodeAttributes`, `validAccessToken()`, the generic `execute<T>`, and `Graph.tidalMetadata`. Task 1 makes two small **backward-compatible additive** extensions to the Slice 0 parser (optional `links` on `TidalDocument`, optional `meta` on `TidalResource`) — Slice 0's existing tests still pass unchanged.
- **Official client id is `BuildConfig.TIDAL_OFFICIAL_CLIENT_ID`** (public PKCE value), distinct from the internal `TIDAL_CLIENT_ID`.
- Every code step below shows the actual code. No `TODO`/"add error handling"/"similar to above" placeholders.

---

### Task 1: Extend the Foundation JSON:API layer for paged relationship documents

Collection relationship reads are **cursor-paginated** (`links.next` carries `page[cursor]=…`) and their primary `data` is an array of **resource identifiers** that carry `meta.addedAt` (when the favorite was added). Slice 0's `TidalDocument`/`TidalResource` model neither `links` nor per-resource `meta`. Add both as optional, defaulted, backward-compatible fields, plus a pure cursor extractor. This is the parser's job, so it lives in `TidalJsonApi.kt` and is tested in isolation.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalJsonApi.kt` (add `TidalLinks`, `TidalDocument.links`, `TidalResource.meta`, top-level `nextCursorFrom`)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalJsonApiPagingTest.kt` (create)

**Interfaces:**
- Consumes: `TidalDocument`, `TidalResource`, `TidalDocumentView` (Slice 0).
- Produces: `TidalLinks(self: String?, next: String?)`; `TidalDocument.links: TidalLinks?`; `TidalResource.meta: JsonObject?`; `fun nextCursorFrom(next: String?): String?`.

- [ ] **Step 1: Write the failing test**

Create `TidalJsonApiPagingTest.kt`:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Slice 2 additive extensions to the Slice 0 JSON:API layer: cursor `links`,
 * per-resource-identifier `meta` (carrying `addedAt` on favorites), and the
 * pure `nextCursorFrom` cursor extractor. Pure parsing only — no network.
 */
class TidalJsonApiPagingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val pagedDoc = """
        {
          "data": [
            { "type": "tracks", "id": "111", "meta": { "addedAt": "1970-01-01T00:00:01Z" } },
            { "type": "tracks", "id": "222", "meta": { "addedAt": "1970-01-01T00:00:02Z" } }
          ],
          "included": [
            { "type": "tracks", "id": "111", "attributes": { "title": "Song One" } }
          ],
          "links": {
            "self": "/userCollections/5/relationships/tracks",
            "next": "/userCollections/5/relationships/tracks?page[cursor]=page2"
          }
        }
    """.trimIndent()

    @Test
    fun `primary identifiers carry their meta object`() {
        val doc = json.decodeFromString<TidalDocument>(pagedDoc)
        val view = TidalDocumentView(doc, json)
        assertEquals(listOf("111", "222"), view.primaryList.map { it.id })
        val addedAt = view.primaryList.first().meta?.get("addedAt")
        assertEquals(JsonPrimitive("1970-01-01T00:00:01Z"), addedAt)
    }

    @Test
    fun `links next is parsed off the document`() {
        val doc = json.decodeFromString<TidalDocument>(pagedDoc)
        assertEquals(
            "/userCollections/5/relationships/tracks?page[cursor]=page2",
            doc.links?.next,
        )
    }

    @Test
    fun `nextCursorFrom extracts the page cursor value`() {
        assertEquals(
            "page2",
            nextCursorFrom("/userCollections/5/relationships/tracks?page[cursor]=page2"),
        )
    }

    @Test
    fun `nextCursorFrom finds the cursor among other query params`() {
        assertEquals("nextpage", nextCursorFrom("/x?locale=en-US&page[cursor]=nextpage"))
    }

    @Test
    fun `nextCursorFrom url-decodes a percent-encoded bracket cursor`() {
        assertEquals("enc oded", nextCursorFrom("/x?page%5Bcursor%5D=enc%20oded"))
    }

    @Test
    fun `nextCursorFrom returns null when there is no cursor or no link`() {
        assertNull(nextCursorFrom("/x?foo=bar"))
        assertNull(nextCursorFrom(null))
        assertNull(nextCursorFrom(""))
    }

    @Test
    fun `documents without links or meta still parse (slice 0 compatibility)`() {
        val doc = json.decodeFromString<TidalDocument>(
            """{ "data": { "id": "1", "type": "artists" }, "included": [] }""",
        )
        assertNull(doc.links)
        val view = TidalDocumentView(doc, json)
        assertNull(view.primary!!.meta)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalJsonApiPagingTest*'`
Expected: FAIL — `TidalLinks`, `TidalDocument.links`, `TidalResource.meta`, and `nextCursorFrom` unresolved.

- [ ] **Step 3: Implement the additive extensions**

In `TidalJsonApi.kt`, add `import java.net.URLDecoder` at the top, add the `TidalLinks` class, add the `meta` field to `TidalResource`, add the `links` field to `TidalDocument`, and add the top-level `nextCursorFrom`:

```kotlin
/** JSON:API top-level `links` — only the cursor-pagination fields we consume. */
@Serializable
data class TidalLinks(val self: String? = null, val next: String? = null)
```

Add `meta` to `TidalResource` (a resource-identifier object may carry `meta`, e.g. a favorite's `addedAt`; kept raw and decoded on demand):

```kotlin
@Serializable
data class TidalResource(
    val id: String,
    val type: String,
    val attributes: JsonObject? = null,
    val relationships: Map<String, TidalRelationship> = emptyMap(),
    val meta: JsonObject? = null,
)
```

Add `links` to `TidalDocument`:

```kotlin
@Serializable
data class TidalDocument(
    val data: JsonElement,
    val included: List<TidalResource> = emptyList(),
    val links: TidalLinks? = null,
)
```

Add the pure cursor extractor (top level, next to `decodeAttributes`):

```kotlin
/**
 * Extract the `page[cursor]` value from a JSON:API `links.next` path
 * (e.g. `/x/relationships/tracks?page[cursor]=zyx`), URL-decoding it, or
 * null when [next] is absent/blank or carries no cursor. Pure so
 * cursor-pagination is testable without a live relationship page.
 */
fun nextCursorFrom(next: String?): String? {
    if (next.isNullOrBlank()) return null
    val query = next.substringAfter('?', "")
    val raw = query.split('&')
        .firstOrNull { it.startsWith("page[cursor]=") || it.startsWith("page%5Bcursor%5D=") }
        ?.substringAfter('=')
        ?: return null
    return URLDecoder.decode(raw, "UTF-8").ifBlank { null }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalJsonApiPagingTest*' --tests '*TidalJsonApiTest*'`
Expected: PASS — the new paging tests pass and Slice 0's `TidalJsonApiTest` still passes (additive fields default to null, no regression).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalJsonApi.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalJsonApiPagingTest.kt
git commit -m "feat(android): JSON:API cursor links + resource meta for collection paging

Adds optional TidalLinks/TidalDocument.links, TidalResource.meta, and a
pure nextCursorFrom() so paginated userCollections relationship documents
(data identifiers carry meta.addedAt; links.next carries the cursor) parse
on the Slice 0 layer. Backward compatible; Slice 0 tests unchanged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 2: Resolve the collection id — delegate to the Foundation's `currentUserId()`

`userCollections/{id}` needs the **user's id** as the collection id. The `/users/me` resolver — `TidalOfficialEndpoints.userRequest`, `TidalOfficialClient.getUser`, the pure `userIdFrom`, and the cached `TidalMetadataRepository.currentUserId()` — now lives in the **Slice-0 Foundation (Task 4)**, because Slice 3 (Discovery) also resolves `me`-scoped resources and a second copy would be a Kotlin "conflicting declarations" compile error. So this slice does NOT re-add any of them: `resolveCollectionId()` is a one-line delegate to `currentUserId()` (userCollection id == user id). `user.read` scope is already requested (`TidalOfficialAuth.SCOPE`).

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollection.kt` (file scaffold + shared KDoc; DTOs/projections/payload builders land in Tasks 3–4)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt` (add `resolveCollectionId()` delegating to the Foundation's `currentUserId()`)

**Interfaces:**
- Consumes: `TidalMetadataRepository.currentUserId()` (Foundation Task 4), which itself wraps `userRequest`/`getUser`/pure `userIdFrom` + the `cachedUserId` cache.
- Produces: `TidalMetadataRepository.resolveCollectionId(): String?`.

The `/users/me` request shape and the pure `userIdFrom` projection are unit-tested in the **Foundation** (`TidalOfficialEndpointsTest` / `TidalMetadataRepositoryTest`), not here — so this task adds no new pure logic to test, and `resolveCollectionId()` (a thin network delegate) follows the Slice-0 untested-network precedent. `TidalCollectionTest.kt` is created in Task 3 with its first pure-projection cases.

- [ ] **Step 1: Create the `TidalCollection.kt` scaffold**

Create `TidalCollection.kt` with just the package + shared KDoc (its DTOs, projections, and write-payload builders are added in Tasks 3–4):

```kotlin
package io.github.auxen.provider.tidal

/**
 * Pure projections, DTOs, and payload builders for the Slice 2 collection
 * (favorites) layer over the official `userCollections` API. Everything here
 * is pure and unit-tested; the suspend wrappers that call the network live on
 * [TidalMetadataRepository]. Metadata only — a favorited Tidal track still
 * streams via the internal-v1 [TidalProvider]. The `/users/me` id resolver
 * lives in the Foundation ([TidalMetadataRepository.currentUserId]); this file
 * does NOT re-declare it.
 */
```

- [ ] **Step 2: Add `resolveCollectionId()` delegating to `currentUserId()`**

In `TidalMetadataRepository.kt`, add the resolver (the Foundation's `currentUserId()` already fetches `GET /users/me` and caches the id in `cachedUserId`, so no separate cache is needed here):

```kotlin
    /**
     * The current user's userCollection id (== their user id), delegating to
     * the Foundation's cached [currentUserId]. Returns null when not logged in
     * to the official API or the id cannot be resolved (caller should surface
     * "sign in to sync"). This is the seam every collection read/write uses.
     */
    suspend fun resolveCollectionId(): String? = currentUserId()
```

- [ ] **Step 3: Build**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalOfficialEndpointsTest*' assembleDebug`
Expected: PASS; APK builds. (No new pure logic in this task — the `/users/me` resolver is Foundation-owned and Foundation-tested; `TidalCollectionTest` is created in Task 3.)

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollection.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt
git commit -m "feat(android): resolve Tidal userCollection id via the Foundation currentUserId()

resolveCollectionId() delegates to the Foundation's cached currentUserId()
(userCollection id == user id, fetched with the spec's id=me convention).
The /users/me resolver + userIdFrom live in the Foundation (Slice 0) so
Slice 3 shares them without a duplicate declaration.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 3: Collection READ — fetch favorites (tracks/albums/artists), paginated

Read the user's favorites from `GET /userCollections/{id}/relationships/{tracks|albums|artists}?include={rel}`: the primary `data` is favorite identifiers (`{type,id,meta.addedAt}`) and `included` carries the full resources (title/isrc/name). Project each page purely into `CollectionFavorite`, then aggregate across cursor pages in a suspend wrapper.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt` (add `collectionRelationshipPageRequest`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt` (add `getCollectionRelationshipPage`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollection.kt` (add `CollectionFavorite`, `CollectionItemMeta`, `parseIso8601Millis`, `collectionFavoritesFrom`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt` (add `favoriteItems` + `favoriteTracks`/`favoriteAlbums`/`favoriteArtists`)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionTest.kt` (create — first pure-projection cases)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt` (add page-request shape)

**Interfaces:**
- Consumes: `TidalDocument`/`TidalDocumentView`/`decodeAttributes` (Slice 0/Task 1); the canonical `TrackAttributes` (existing `TidalOfficialApi.kt` — `title`/`isrc`/`duration`/`explicit`), `AlbumAttributes`/`ArtistAttributes` (Slice 0's `TidalMetadataRepository.kt`); `resolveCollectionId()`, `nextCursorFrom` (Tasks 1–2).
- Produces: `CollectionFavorite(id, type, addedAtMillis, title, isrc)`; `CollectionItemMeta(addedAt)`; `fun parseIso8601Millis(String?): Long?`; `fun collectionFavoritesFrom(doc, json, type): List<CollectionFavorite>`; `TidalOfficialEndpoints.collectionRelationshipPageRequest(...)`; `TidalOfficialClient.getCollectionRelationshipPage(...)`; `TidalMetadataRepository.favoriteItems(relationship): Result<List<CollectionFavorite>>` + `favoriteTracks/Albums/Artists()`. (`TrackAttributes` is REUSED from `TidalOfficialApi.kt`, not re-declared.)

- [ ] **Step 1: Write the failing test**

Create `TidalCollectionTest.kt` (its class grows across Tasks 3–4):

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure projection + request-shape tests for the Slice 2 collection layer
 * (favorite-item projection, addedAt parsing, write-payload encoding). No live
 * HTTP — the suspend wrappers on TidalMetadataRepository are untested (Slice 0
 * network precedent). The `/users/me` resolver + `userIdFrom` are Foundation-
 * owned and Foundation-tested, so they are not re-tested here.
 */
class TidalCollectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val favTracksDoc = """
        {
          "data": [
            { "type": "tracks", "id": "111", "meta": { "addedAt": "1970-01-01T00:00:01Z" } },
            { "type": "tracks", "id": "222", "meta": { "addedAt": "1970-01-01T00:00:02Z" } }
          ],
          "included": [
            { "type": "tracks", "id": "111",
              "attributes": { "title": "Song One", "isrc": "USABC1234567", "duration": "PT3M20S", "explicit": false } },
            { "type": "tracks", "id": "222",
              "attributes": { "title": "Song Two", "isrc": "USABC7654321", "duration": "PT2M10S", "explicit": true } }
          ]
        }
    """.trimIndent()

    @Test
    fun `collectionFavoritesFrom joins track identifiers with included attributes and addedAt`() {
        val favs = collectionFavoritesFrom(json.decodeFromString(favTracksDoc), json, "tracks")
        assertEquals(2, favs.size)
        assertEquals("111", favs[0].id)
        assertEquals("tracks", favs[0].type)
        assertEquals("Song One", favs[0].title)
        assertEquals("USABC1234567", favs[0].isrc)
        assertEquals(1000L, favs[0].addedAtMillis)
        assertEquals(2000L, favs[1].addedAtMillis)
    }

    @Test
    fun `collectionFavoritesFrom keeps a favorite even when its full resource is absent`() {
        val doc = """
            { "data": [ { "type": "tracks", "id": "333", "meta": { "addedAt": "1970-01-01T00:00:03Z" } } ],
              "included": [] }
        """.trimIndent()
        val favs = collectionFavoritesFrom(json.decodeFromString(doc), json, "tracks")
        assertEquals(1, favs.size)
        assertEquals("333", favs[0].id)
        assertNull(favs[0].title)
        assertEquals(3000L, favs[0].addedAtMillis)
    }

    @Test
    fun `collectionFavoritesFrom projects albums and artists titles by type`() {
        val albumsDoc = """
            { "data": [ { "type": "albums", "id": "9", "meta": { "addedAt": "1970-01-01T00:00:00Z" } } ],
              "included": [ { "type": "albums", "id": "9", "attributes": { "title": "Album Nine", "albumType": "ALBUM" } } ] }
        """.trimIndent()
        assertEquals("Album Nine", collectionFavoritesFrom(json.decodeFromString(albumsDoc), json, "albums").single().title)

        val artistsDoc = """
            { "data": [ { "type": "artists", "id": "7", "meta": { "addedAt": "1970-01-01T00:00:00Z" } } ],
              "included": [ { "type": "artists", "id": "7", "attributes": { "name": "Prompto", "popularity": 0.5 } } ] }
        """.trimIndent()
        assertEquals("Prompto", collectionFavoritesFrom(json.decodeFromString(artistsDoc), json, "artists").single().title)
    }

    @Test
    fun `parseIso8601Millis handles Z and explicit offsets, null on garbage`() {
        assertEquals(1000L, parseIso8601Millis("1970-01-01T00:00:01Z"))
        assertEquals(0L, parseIso8601Millis("1970-01-01T01:00:00+01:00"))
        assertNull(parseIso8601Millis("not-a-date"))
        assertNull(parseIso8601Millis(null))
    }
}
```

(Tasks 3–4 add further `@Test` methods to this class, inserted before the closing brace.)

In `TidalOfficialEndpointsTest.kt`, add:

```kotlin
    @Test
    fun `collection page request includes the relationship and carries the cursor`() {
        val request = TidalOfficialEndpoints.collectionRelationshipPageRequest(
            collectionId = "5", relationship = "tracks", accessToken = "tok", cursor = "page2",
        )
        val url = request.url
        assertEquals("/v2/userCollections/5/relationships/tracks", url.encodedPath)
        assertEquals("tracks", url.queryParameter("include"))
        assertEquals("page2", url.queryParameter("page[cursor]"))
        assertEquals("Bearer tok", request.header("Authorization"))
    }

    @Test
    fun `collection page request omits the cursor on the first page`() {
        val request = TidalOfficialEndpoints.collectionRelationshipPageRequest(
            collectionId = "5", relationship = "albums", accessToken = "tok",
        )
        assertEquals("albums", request.url.queryParameter("include"))
        assertNull(request.url.queryParameter("page[cursor]"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalCollectionTest*' --tests '*TidalOfficialEndpointsTest*'`
Expected: FAIL — `collectionFavoritesFrom`, `parseIso8601Millis`, `CollectionFavorite`, `TidalOfficialEndpoints.collectionRelationshipPageRequest` unresolved.

- [ ] **Step 3: Add the page-request endpoint + client method**

In `TidalOfficialEndpoints.kt`, add:

```kotlin
    /**
     * `GET /userCollections/{id}/relationships/{relationship}` with
     * `?include={relationship}` so the favorite identifiers (with
     * `meta.addedAt`) come back alongside the full track/album/artist
     * resources in one call. Cursor-paginated via `page[cursor]`; `locale`
     * is optional (spec defaults to en-US).
     */
    fun collectionRelationshipPageRequest(
        collectionId: String,
        relationship: String,
        accessToken: String,
        cursor: String? = null,
        locale: String? = null,
    ): Request {
        val url = "$BASE/userCollections/$collectionId/relationships/$relationship".toHttpUrl().newBuilder()
            .addQueryParameter("include", relationship)
            .apply { cursor?.let { addQueryParameter("page[cursor]", it) } }
            .apply { locale?.let { addQueryParameter("locale", it) } }
            .build()
        return authed(url, accessToken)
    }
```

In `TidalOfficialClient.kt`, add:

```kotlin
    /** One page of a userCollection relationship (favorites) as a JSON:API document. */
    suspend fun getCollectionRelationshipPage(
        collectionId: String,
        relationship: String,
        accessToken: String,
        cursor: String? = null,
        locale: String? = null,
    ): Result<TidalDocument> =
        execute(TidalOfficialEndpoints.collectionRelationshipPageRequest(collectionId, relationship, accessToken, cursor, locale))
```

- [ ] **Step 4: Add the DTOs + projection to `TidalCollection.kt`**

In `TidalCollection.kt`, add the imports and code. `TrackAttributes` is the canonical spike DTO from `TidalOfficialApi.kt` (`title`/`isrc`/`duration`/`explicit`), REUSED here (same package, no import needed) — do NOT re-declare it (that would be a "conflicting declarations" error). This slice only reads `title` and `isrc` off it. `AlbumAttributes`/`ArtistAttributes` are reused from Slice 0's `TidalMetadataRepository.kt`, same package:

```kotlin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.OffsetDateTime
```

```kotlin
/** The `meta` on a favorite resource identifier (`UserCollections_*_Resource_Identifier_Meta.addedAt`). */
@Serializable
data class CollectionItemMeta(val addedAt: String? = null)

/**
 * A single Tidal favorite (track/album/artist), projected from a
 * userCollection relationship page. [title] is the track/album title or
 * artist name (null when the full resource wasn't in `included`); [isrc] is
 * populated for tracks only (a latent hook for future local-file matching by
 * ISRC). [addedAtMillis] is null when Tidal omitted/we couldn't parse it.
 */
data class CollectionFavorite(
    val id: String,
    val type: String,
    val addedAtMillis: Long?,
    val title: String?,
    val isrc: String? = null,
)

/** Parse an ISO-8601 date-time (`addedAt`) to epoch millis, or null on absence/parse failure. */
fun parseIso8601Millis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * Pure projection of one favorites relationship page to [CollectionFavorite]s
 * for the given [type] (`tracks`/`albums`/`artists`). Joins each primary
 * identifier (id + `meta.addedAt`) with its full `included` resource (by
 * type+id) for the title/name, dropping identifiers of a different type.
 * Extracted from the network wrapper so it is unit-testable with no call.
 */
fun collectionFavoritesFrom(doc: TidalDocument, json: Json, type: String): List<CollectionFavorite> {
    val fullByKey = doc.included.associateBy { it.type to it.id }
    val view = TidalDocumentView(doc, json)
    return view.primaryList
        .filter { it.type == type }
        .map { ident ->
            val full = fullByKey[ident.type to ident.id]
            val addedAt = ident.meta
                ?.let { runCatching { json.decodeFromJsonElement<CollectionItemMeta>(it) }.getOrNull() }
                ?.addedAt
            val trackAttrs = if (type == "tracks") full?.decodeAttributes<TrackAttributes>(json) else null
            val title = when (type) {
                "tracks" -> trackAttrs?.title
                "albums" -> full?.decodeAttributes<AlbumAttributes>(json)?.title
                "artists" -> full?.decodeAttributes<ArtistAttributes>(json)?.name
                else -> null
            }
            CollectionFavorite(
                id = ident.id,
                type = type,
                addedAtMillis = parseIso8601Millis(addedAt),
                title = title,
                isrc = trackAttrs?.isrc,
            )
        }
}
```

- [ ] **Step 5: Add the paginating suspend wrappers to the repository**

In `TidalMetadataRepository.kt`, add:

```kotlin
    /**
     * Every favorite of [relationship] (`tracks`/`albums`/`artists`) from the
     * user's Tidal collection, following the cursor across all pages. Fails
     * (Result.failure) when not logged in or the collection id can't resolve.
     */
    suspend fun favoriteItems(relationship: String): Result<List<CollectionFavorite>> = runCatching {
        val token = session.validAccessToken()
            ?: error("Not logged in to the official Tidal API")
        val collectionId = resolveCollectionId()
            ?: error("Could not resolve the Tidal collection id")
        val all = mutableListOf<CollectionFavorite>()
        var cursor: String? = null
        do {
            val doc = client.getCollectionRelationshipPage(collectionId, relationship, token, cursor)
                .getOrThrow()
            all += collectionFavoritesFrom(doc, json, relationship)
            cursor = nextCursorFrom(doc.links?.next)
        } while (cursor != null)
        all
    }

    suspend fun favoriteTracks(): Result<List<CollectionFavorite>> = favoriteItems("tracks")
    suspend fun favoriteAlbums(): Result<List<CollectionFavorite>> = favoriteItems("albums")
    suspend fun favoriteArtists(): Result<List<CollectionFavorite>> = favoriteItems("artists")
```

- [ ] **Step 6: Run tests + commit**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalCollectionTest*' --tests '*TidalOfficialEndpointsTest*' assembleDebug`
Expected: PASS; APK builds.

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollection.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt
git commit -m "feat(android): read Tidal favorites (tracks/albums/artists), paginated

Adds collectionRelationshipPageRequest/getCollectionRelationshipPage + a
pure collectionFavoritesFrom projection (joins favorite identifiers'
meta.addedAt with included title/isrc/name) + cursor-following favoriteItems
wrappers. Read path for the two-way collection sync.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 4: Collection WRITE — favorite / unfavorite (the app's first official write)

Favoriting POSTs (and unfavoriting DELETEs) to `/userCollections/{id}/relationships/{rel}` with a JSON:API body `{"data":[{"type","id"[,"meta":{"addedAt"}]}]}`. This is the first WRITE the app makes to the official API. Unit-test the **payload encoding** and the **request shape** (method / path / content-type / body bytes); never execute. The client gets a body-tolerant `executeUnit` (writes return 2xx with no body).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt` (add `collectionRelationshipWriteRequest` + a `VND_API` media-type constant)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt` (add `executeUnit` + `writeCollectionRelationship`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollection.kt` (add write DTOs + `collectionWritePayload`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt` (add `addFavorite`/`removeFavorite`)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionTest.kt` (add payload-encoding tests)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt` (add write-request shape incl. body bytes)

**Interfaces:**
- Consumes: `JsonApiErrorDocument` (existing, `TidalOfficialApi.kt`); `resolveCollectionId()` (Task 2).
- Produces: `CollectionWriteItem(type,id,meta)`, `CollectionWriteMeta(addedAt)`, `CollectionWritePayload(data)`; `fun collectionWritePayload(ids, type, addedAtIso?): CollectionWritePayload`; `TidalOfficialEndpoints.collectionRelationshipWriteRequest(...)`; `TidalOfficialClient.writeCollectionRelationship(...): Result<Unit>`; `TidalMetadataRepository.addFavorite/removeFavorite(relationship, id): Result<Unit>`.

- [ ] **Step 1: Write the failing test**

In `TidalCollectionTest.kt`, add (`encodeDefaults` is default-`false`, so a null `meta` is omitted — that's asserted here):

```kotlin
    @Test
    fun `collectionWritePayload encodes an add body without meta by default`() {
        val body = json.encodeToString(collectionWritePayload(listOf("111", "222"), "tracks"))
        assertEquals("""{"data":[{"type":"tracks","id":"111"},{"type":"tracks","id":"222"}]}""", body)
    }

    @Test
    fun `collectionWritePayload includes meta addedAt when supplied`() {
        val body = json.encodeToString(collectionWritePayload(listOf("111"), "tracks", "1970-01-01T00:00:01Z"))
        assertEquals("""{"data":[{"type":"tracks","id":"111","meta":{"addedAt":"1970-01-01T00:00:01Z"}}]}""", body)
    }

    @Test
    fun `collectionWritePayload carries the resource type for albums`() {
        val body = json.encodeToString(collectionWritePayload(listOf("9"), "albums"))
        assertEquals("""{"data":[{"type":"albums","id":"9"}]}""", body)
    }
```

Add `import kotlinx.serialization.encodeToString` to the test file.

In `TidalOfficialEndpointsTest.kt`, add (reads the request body via okio to prove the exact bytes + content type):

```kotlin
    @Test
    fun `collection write request posts the json api body with the right content type`() {
        val body = """{"data":[{"type":"tracks","id":"111"}]}"""
        val request = TidalOfficialEndpoints.collectionRelationshipWriteRequest(
            collectionId = "5", relationship = "tracks", method = "POST", body = body, accessToken = "tok",
        )
        assertEquals("POST", request.method)
        assertEquals("/v2/userCollections/5/relationships/tracks", request.url.encodedPath)
        assertEquals("Bearer tok", request.header("Authorization"))
        assertEquals("application/vnd.api+json", request.header("Accept"))
        assertEquals("application/vnd.api+json", request.body!!.contentType().toString())
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        assertEquals(body, buffer.readUtf8())
    }

    @Test
    fun `collection write request supports delete with a body`() {
        val request = TidalOfficialEndpoints.collectionRelationshipWriteRequest(
            collectionId = "5", relationship = "artists", method = "DELETE",
            body = """{"data":[{"type":"artists","id":"7"}]}""", accessToken = "tok",
        )
        assertEquals("DELETE", request.method)
        assertEquals("/v2/userCollections/5/relationships/artists", request.url.encodedPath)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalCollectionTest*' --tests '*TidalOfficialEndpointsTest*'`
Expected: FAIL — `collectionWritePayload` and `TidalOfficialEndpoints.collectionRelationshipWriteRequest` unresolved.

- [ ] **Step 3: Add the write-request builder**

In `TidalOfficialEndpoints.kt`, add the imports and a private media-type constant near `BASE`, then the builder:

```kotlin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
```

```kotlin
    // JSON:API media type for write bodies. Built from a ByteArray body (not a
    // String) so OkHttp does NOT append "; charset=utf-8" — a strict JSON:API
    // server can 415 on the charset parameter.
    private val VND_API_JSON = "application/vnd.api+json".toMediaType()

    /**
     * POST (favorite) or DELETE (unfavorite) against a userCollection
     * to-many relationship (`tracks`/`albums`/`artists`). [body] is the
     * pre-serialized JSON:API payload (`{"data":[{"type","id"[,"meta"]}]}`).
     * Only `Authorization` + `Accept` headers — never `client_secret`.
     */
    fun collectionRelationshipWriteRequest(
        collectionId: String,
        relationship: String,
        method: String,
        body: String,
        accessToken: String,
    ): Request =
        Request.Builder()
            .url("$BASE/userCollections/$collectionId/relationships/$relationship".toHttpUrl())
            .method(method, body.toByteArray().toRequestBody(VND_API_JSON))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/vnd.api+json")
            .build()
```

- [ ] **Step 4: Add the write DTOs + payload builder + client + repository methods**

In `TidalCollection.kt`, add the write DTOs and pure builder:

```kotlin
/** One item in a collection write body. `meta` (addedAt) is omitted on removes and on adds that don't set it. */
@Serializable
data class CollectionWriteItem(val type: String, val id: String, val meta: CollectionWriteMeta? = null)

@Serializable
data class CollectionWriteMeta(val addedAt: String)

@Serializable
data class CollectionWritePayload(val data: List<CollectionWriteItem>)

/**
 * Build the JSON:API write body for [ids] of [type] (`tracks`/`albums`/
 * `artists`). [addedAtIso] (adds only) is attached as `meta.addedAt` when
 * present. Pure so the exact wire body is unit-testable.
 */
fun collectionWritePayload(ids: List<String>, type: String, addedAtIso: String? = null): CollectionWritePayload =
    CollectionWritePayload(
        ids.map { CollectionWriteItem(type = type, id = it, meta = addedAtIso?.let(::CollectionWriteMeta)) },
    )
```

In `TidalOfficialClient.kt`, add a body-tolerant executor + the write method (reuses the existing `JsonApiErrorDocument` error parsing; a successful write is a 2xx with no JSON body, so it must NOT be decoded):

```kotlin
    /** Execute a write whose success is a 2xx with no (or an ignorable) body. */
    private suspend fun executeUnit(request: Request): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    val message = runCatching { json.decodeFromString<JsonApiErrorDocument>(text) }
                        .getOrNull()
                        ?.errors
                        ?.firstOrNull()
                        ?.let { "${it.code ?: "HTTP ${resp.code}"}${it.detail?.let { d -> ": $d" } ?: ""}" }
                        ?: "HTTP ${resp.code}"
                    error(message)
                }
            }
        }
    }

    /** POST/DELETE a userCollection relationship (favorite/unfavorite). */
    suspend fun writeCollectionRelationship(
        collectionId: String,
        relationship: String,
        method: String,
        body: String,
        accessToken: String,
    ): Result<Unit> =
        executeUnit(TidalOfficialEndpoints.collectionRelationshipWriteRequest(collectionId, relationship, method, body, accessToken))
```

In `TidalMetadataRepository.kt`, add `import kotlinx.serialization.encodeToString` and the favorite write methods (idempotent no-ops mapped to success):

```kotlin
    /** Favorite [id] (`tracks`/`albums`/`artists`) in the user's Tidal collection. */
    suspend fun addFavorite(relationship: String, id: String): Result<Unit> = writeFavorite(relationship, id, "POST")

    /** Unfavorite [id] (`tracks`/`albums`/`artists`) from the user's Tidal collection. */
    suspend fun removeFavorite(relationship: String, id: String): Result<Unit> = writeFavorite(relationship, id, "DELETE")

    private suspend fun writeFavorite(relationship: String, id: String, method: String): Result<Unit> {
        val token = session.validAccessToken()
            ?: return Result.failure(IllegalStateException("Not logged in to the official Tidal API"))
        val collectionId = resolveCollectionId()
            ?: return Result.failure(IllegalStateException("Could not resolve the Tidal collection id"))
        val body = json.encodeToString(collectionWritePayload(listOf(id), relationship))
        return client.writeCollectionRelationship(collectionId, relationship, method, body, token)
            .recoverCatching { e ->
                // Idempotent no-ops → the desired end-state already holds:
                // adding an already-favorited item (409
                // DUPLICATE_ITEMS_IN_COLLECTION) or removing an absent one.
                if (isIdempotentNoOp(method, e.message)) Unit else throw e
            }
    }

    private fun isIdempotentNoOp(method: String, message: String?): Boolean {
        val m = message ?: return false
        return when (method) {
            "POST" -> m.contains("DUPLICATE_ITEMS_IN_COLLECTION")
            "DELETE" -> m.contains("404") || m.contains("NOT_FOUND")
            else -> false
        }
    }
```

- [ ] **Step 5: Run tests + commit**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalCollectionTest*' --tests '*TidalOfficialEndpointsTest*' assembleDebug`
Expected: PASS; APK builds.

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollection.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt
git commit -m "feat(android): write Tidal favorites (POST/DELETE userCollections)

Adds the first official-API write: collectionRelationshipWriteRequest (exact
vnd.api+json body, no charset), a body-tolerant executeUnit, and
addFavorite/removeFavorite mapping idempotent 409/404 no-ops to success.
Payload encoding + request shape unit-tested; execution never is.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 5: Reconcile with local favorites — `TidalCollectionSync` + push wiring

Tie the official read/write layer to the local DB. `TidalCollectionSync` (on `Graph`) computes the additive-union delta (pure, unit-tested), marks known local tracks favorite and inserts self-healing placeholders for unknown ones, and pushes local toggles to Tidal (Tidal tracks only, optimistic). `PlayerViewModel.toggleFavorite` fires the push; a manual pull is exposed for the debug screen.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt` (add `favoriteFromRemote`)
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt` (pure `newRemoteFavorites`/`placeholderTrackFor` + suspend `pullFavoriteTracks`/`pushFavorite`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (`Graph.tidalCollectionSync` wiring)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (push in `toggleFavorite` + `syncFavoritesFromTidal` + status flow)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionSyncTest.kt` (create)

**Interfaces:**
- Consumes: `TidalMetadataRepository.favoriteTracks()`/`addFavorite`/`removeFavorite` (Tasks 3–4); `LibraryRepository.favoriteKeys()`/`upsert`; `CollectionFavorite` (Task 3); `Track`/`Source`.
- Produces: `LibraryRepository.favoriteFromRemote(placeholder: Track, addedAtMillis: Long)`; `fun newRemoteFavorites(remote, localFavoriteKeys): List<CollectionFavorite>`; `fun placeholderTrackFor(fav): Track`; `TidalCollectionSync(metadata, library, clock).pullFavoriteTracks(): Result<Int>` + `.pushFavorite(track, favorite): Result<Unit>`; `Graph.tidalCollectionSync`; `PlayerViewModel.syncFavoritesFromTidal()` + `tidalCollectionStatus: StateFlow<String?>`.

- [ ] **Step 1: Write the failing test for the pure merge logic**

Create `TidalCollectionSyncTest.kt`:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure merge/reconcile logic for two-way collection sync. The network+DB
 * orchestration (pullFavoriteTracks/pushFavorite) is untested (Slice 0
 * network precedent); only the additive-union delta and the placeholder
 * projection are unit-tested here.
 */
class TidalCollectionSyncTest {

    private fun fav(id: String, title: String? = null, addedAt: Long? = null) =
        CollectionFavorite(id = id, type = "tracks", addedAtMillis = addedAt, title = title)

    @Test
    fun `newRemoteFavorites keeps only remote favorites not already local (additive union)`() {
        val remote = listOf(fav("111"), fav("222"), fav("333"))
        val localKeys = setOf("TIDAL:111", "LOCAL:999")
        assertEquals(listOf("222", "333"), newRemoteFavorites(remote, localKeys).map { it.id })
    }

    @Test
    fun `newRemoteFavorites returns all when nothing is local`() {
        val remote = listOf(fav("111"), fav("222"))
        assertEquals(listOf("111", "222"), newRemoteFavorites(remote, emptySet()).map { it.id })
    }

    @Test
    fun `newRemoteFavorites returns empty when every remote favorite is already local`() {
        val remote = listOf(fav("111"), fav("222"))
        assertEquals(emptyList<String>(), newRemoteFavorites(remote, setOf("TIDAL:111", "TIDAL:222")).map { it.id })
    }

    @Test
    fun `placeholderTrackFor uses the favorite title and marks it a Tidal track`() {
        val t = placeholderTrackFor(fav("333", title = "Ghost Song"))
        assertEquals("Ghost Song", t.title)
        assertEquals(Source.TIDAL, t.source)
        assertEquals("333", t.sourceId)
        assertEquals("", t.artist)
    }

    @Test
    fun `placeholderTrackFor falls back to a stable title when the favorite has none`() {
        assertEquals("Tidal favorite 444", placeholderTrackFor(fav("444")).title)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalCollectionSyncTest*'`
Expected: FAIL — `newRemoteFavorites`, `placeholderTrackFor`, `TidalCollectionSync` unresolved.

- [ ] **Step 3: Add `favoriteFromRemote` to `LibraryRepository`**

In `LibraryRepository.kt`, add (keeps DB access encapsulated in the repository; idempotent — re-favoriting REPLACEs the row):

```kotlin
    /**
     * Mark a Tidal favorite pulled from the official collection. If the track
     * is already known locally (played/searched via internal-v1) its metadata
     * is kept and only the favorite is added; otherwise [placeholder] (title
     * only) is inserted and self-heals on the next internal-v1 upsert of the
     * same (source, source_id). Idempotent.
     */
    suspend fun favoriteFromRemote(placeholder: Track, addedAtMillis: Long) {
        val existing = db.trackDao().bySourceId(placeholder.source.name, placeholder.sourceId)
        val trackId = existing?.id ?: upsert(placeholder)
        db.favoriteDao().insert(
            FavoriteEntity(trackId, existing?.matchGroupId ?: placeholder.matchGroupId, addedAtMillis),
        )
    }
```

- [ ] **Step 4: Create `TidalCollectionSync`**

Create `TidalCollectionSync.kt`:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.data.LibraryRepository
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.coroutines.flow.first

/**
 * Two-way reconcile between the user's Tidal favorites (official
 * `userCollections` API, via [TidalMetadataRepository]) and the local
 * favorites ([LibraryRepository]). Held on `Graph` (a plain class, not an
 * object). Pure merge logic is unit-tested; the network+DB orchestration is
 * not (Slice 0 precedent). Playback of a synced favorite stays on the
 * internal-v1 [TidalProvider] — this class touches metadata + favorite-state
 * only.
 */
class TidalCollectionSync(
    private val metadata: TidalMetadataRepository,
    private val library: LibraryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Pull Tidal favorite tracks into the local DB (additive union: never
     * deletes a local favorite). Returns the number of newly-added favorites.
     */
    suspend fun pullFavoriteTracks(): Result<Int> = runCatching {
        val remote = metadata.favoriteTracks().getOrThrow()
        val fresh = newRemoteFavorites(remote, library.favoriteKeys().first())
        fresh.forEach { library.favoriteFromRemote(placeholderTrackFor(it), it.addedAtMillis ?: clock()) }
        fresh.size
    }

    /**
     * Push a local favorite toggle to Tidal. Best-effort and optimistic — the
     * caller has already updated the local DB; a failure here is not rolled
     * back. No-op for local-source tracks (they are not on Tidal).
     */
    suspend fun pushFavorite(track: Track, favorite: Boolean): Result<Unit> {
        if (track.source != Source.TIDAL) return Result.success(Unit)
        return if (favorite) metadata.addFavorite("tracks", track.sourceId)
        else metadata.removeFavorite("tracks", track.sourceId)
    }
}

/** Remote favorites whose `TIDAL:<id>` key is not already locally favorited. Pure. */
fun newRemoteFavorites(remote: List<CollectionFavorite>, localFavoriteKeys: Set<String>): List<CollectionFavorite> =
    remote.filter { "${Source.TIDAL.name}:${it.id}" !in localFavoriteKeys }

/**
 * A minimal [Track] for a Tidal favorite not yet known locally. Title-only
 * (the favorites API doesn't return a track's artist); self-heals when
 * internal-v1 later upserts the same (TIDAL, id) with full metadata. Pure.
 */
fun placeholderTrackFor(fav: CollectionFavorite): Track = Track(
    title = fav.title?.ifBlank { null } ?: "Tidal favorite ${fav.id}",
    artist = "",
    source = Source.TIDAL,
    sourceId = fav.id,
)
```

- [ ] **Step 5: Wire `Graph.tidalCollectionSync` + the ViewModel**

In `AuxenApp.kt`, in `object Graph`, add the field beside `tidalMetadata` (Slice 0):

```kotlin
    lateinit var tidalCollectionSync: TidalCollectionSync
        private set
```

and add the import `import io.github.auxen.provider.tidal.TidalCollectionSync`, and in `init(context)`, after the Slice 0 `tidalMetadata = …` line:

```kotlin
        tidalCollectionSync = TidalCollectionSync(metadata = tidalMetadata, library = library)
```

**Append-only:** the `TidalMetadataRepository(...)` construction already has its FINAL shape from the Foundation (constructor `(session, client, json, countryCode, locale)`), so this slice only ADDS the `tidalCollectionSync = …` line after it — it never rewrites the `tidalMetadata = TidalMetadataRepository(...)` call or the repository constructor signature.

In `PlayerViewModel.kt`, extend `toggleFavorite` to push the toggle (optimistic; local DB already updated), and add a manual pull + status flow. Replace `toggleFavorite`:

```kotlin
    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            val key = "${track.source.name}:${track.sourceId}"
            val makeFavorite = key !in favoriteKeys.value
            runCatching { Graph.library.setFavorite(track, makeFavorite) }
            // Optimistic best-effort push to the official Tidal collection
            // (Tidal tracks only). A failed write never rolls back the local
            // toggle; the next pull re-reconciles the union.
            if (track.source == Source.TIDAL) {
                runCatching { Graph.tidalCollectionSync.pushFavorite(track, makeFavorite) }
            }
        }
    }
```

and add near the other status flows (imports `MutableStateFlow`/`asStateFlow`/`StateFlow`/`viewModelScope`/`launch` already present):

```kotlin
    private val _tidalCollectionStatus = MutableStateFlow<String?>(null)
    val tidalCollectionStatus: StateFlow<String?> = _tidalCollectionStatus.asStateFlow()

    /** Manual pull of Tidal favorites into the local DB; renders a human status string (no tokens). */
    fun syncFavoritesFromTidal() {
        viewModelScope.launch {
            _tidalCollectionStatus.value = "Syncing favorites from Tidal…"
            _tidalCollectionStatus.value = Graph.tidalCollectionSync.pullFavoriteTracks().fold(
                onSuccess = { "Synced: added $it new favorite track(s) from Tidal." },
                onFailure = { "Favorites sync failed: ${it.message}" },
            )
        }
    }
```

(Optional future auto-trigger: call `syncFavoritesFromTidal()` from the official `completeLogin` success path — deliberately left to product wiring.)

- [ ] **Step 6: Run tests + build**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TidalCollectionSyncTest*' assembleDebug`
Expected: PASS; APK builds (`Graph` wiring compiles; `TidalCollectionSync` defaults `clock`).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt \
        android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionSyncTest.kt
git commit -m "feat(android): reconcile Tidal favorites with local DB + push toggles

TidalCollectionSync pulls Tidal favorite tracks (additive union; self-healing
placeholders for unknown tracks) and pushes local favorite toggles to Tidal
(optimistic, Tidal-only). Wires push into toggleFavorite + a manual
syncFavoritesFromTidal. Pure merge logic unit-tested.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 6: Debug-screen sync button + on-device checkpoint (the slice deliverable)

Surface a manual "Sync favorites from Tidal" button + status readout in the existing `TidalOfficialDebugScreen` (mirroring the Slice 0 artist-metadata probe), then prove the whole two-way path on device. Push is already wired into `toggleFavorite`, so it is exercised from the real Collection screen. The Compose debug wiring is not unit-tested (Slice 0 `TidalOfficialDebugScreen` precedent) and closes on the on-device checkpoint.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/TidalOfficialDebugScreen.kt` ("Sync favorites" button + status `Text`)
- (No new test — Compose debug UI, untested by precedent.)

**Interfaces:**
- Consumes: `PlayerViewModel.syncFavoritesFromTidal()` + `tidalCollectionStatus` (Task 5).

- [ ] **Step 1: Add the sync button + status to the debug screen**

In `TidalOfficialDebugScreen.kt`, below the existing artist-metadata probe section, add a "Sync favorites from Tidal" `Button` calling `onSyncFavorites()` and render `collectionStatus` in a monospace `Text` (same style as the metadata/streaming status readouts). Thread `collectionStatus: String?` and `onSyncFavorites: () -> Unit` from the screen's caller exactly the way `metadataStatus`/`onRunMetadataProbe` are already threaded (collect `viewModel.tidalCollectionStatus` at the call site and pass `viewModel::syncFavoritesFromTidal`). Concretely, mirror the metadata-probe block:

```kotlin
        Spacer(Modifier.height(24.dp))
        Text("Collection sync", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onSyncFavorites, modifier = Modifier.fillMaxWidth()) {
            Text("Sync favorites from Tidal")
        }
        collectionStatus?.let {
            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
```

(Match the file's existing imports/section spacing; the `Composable`'s new params are `collectionStatus: String? = null` and `onSyncFavorites: () -> Unit = {}`, defaulted so any preview/other caller still compiles.)

- [ ] **Step 2: Full gate + build**

Run: `cd android && ./gradlew testDebugUnitTest verifyRoborazziDebug assembleDebug`
Expected: PASS. (If the debug screen's golden shifts because the new section extends the scroll content, re-record with `./gradlew recordRoborazziDebug` and eyeball the diff — the Slice 0 metadata-probe section set this precedent.)

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/TidalOfficialDebugScreen.kt
# plus any re-recorded golden under app/src/test/.../roborazzi/ if the gate changed it
git commit -m "feat(android): debug-screen Tidal favorites-sync button + status

Surfaces syncFavoritesFromTidal() in the official debug screen (mirrors the
Slice 0 probe). Push is already wired into toggleFavorite, exercised from the
real Collection screen. Closes Slice 2 on the on-device checkpoint.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

- [ ] **Step 4: On-device checkpoint (the Slice 2 deliverable)**

Build the APK **locally** (CI has no Tidal creds): `cd android && ./gradlew assembleDebug`. Install, then:

1. **Login + collection-id resolution.** Settings → Advanced → "Try official Tidal login (beta)" → log in. (`user.read`/`collection.read`/`collection.write` are already in `TidalOfficialAuth.SCOPE`.)
2. **Pull.** Tap "Sync favorites from Tidal". Confirm the status reads "Synced: added N new favorite track(s) from Tidal." Open **Collection → Tracks** and confirm your Tidal favorites now appear (tracks you'd already played show full metadata; never-seen ones show title only until played — the self-heal).
3. **Play a synced favorite** and confirm it streams (this validates the id-equivalence assumption: the official favorites track id == the internal-v1 stream id used by `TidalProvider.getStreamInfoById`).
4. **Push add.** On a Tidal track not yet favorited, tap the heart. In the official Tidal app (or re-run pull after clearing), confirm it now appears in your Tidal favorites.
5. **Push remove.** Unheart it; confirm it's removed on Tidal.
6. **Idempotency.** Re-run the pull; confirm "added 0 new" and no duplicates. Toggle-favorite a track that's already favorited on Tidal; confirm no error (409 DUPLICATE mapped to success).

Paste the status readout + the Collection-tab result back to close the loop. **Verify no token/refresh-token ever appears in Logcat** during the flow.

---

## Self-review (writing-plans checklist)

- **Spec coverage:** READ — `/users/{id}` with `id=me` (spec: *"Use `me`…"*) → user id (resolved by the **Foundation's** `currentUserId()`; Task 2 just delegates via `resolveCollectionId()`) → `/userCollections/{id}/relationships/{tracks|albums|artists}?include={rel}`, cursor-paginated via `links.next`+`page[cursor]`, primary identifiers carry `meta.addedAt` (Tasks 1–3). WRITE — POST/DELETE `/userCollections/{id}/relationships/{rel}` with body `{"data":[{"type","id"[,"meta.addedAt"]}]}` (schemas `UserCollections*RelationshipAdd/RemoveOperation_Payload`), 2xx-no-body success, 409 `DUPLICATE_ITEMS_IN_COLLECTION` treated idempotent (Task 4). RECONCILE — additive-union merge keyed on `source:sourceId`, self-healing placeholders, optimistic push, latent hooks (`match_group_id`, `PlaylistEntity.source/tidalPlaylistId`) noted (Task 5). Field names verbatim from spec: `Tracks_Attributes.{title,isrc,duration,explicit}` (the canonical `TrackAttributes` from `TidalOfficialApi.kt`, REUSED not re-declared), `Albums_Attributes.title`, `Artists_Attributes.name`, `*_Resource_Identifier_Meta.addedAt`, `Users_Resource_Object.id`.
- **Placeholder scan:** every code step shows real code; every test step shows real assertions (fixtures use real spec field names + JSON:API structure). The only non-TDD step is Task 6's Compose debug wiring, explicitly justified by the Slice 0 `TidalOfficialDebugScreen` precedent and closed by the Step-4 on-device checkpoint. No `TODO`/"similar to above" left.
- **Type consistency:** `CollectionFavorite(id,type,addedAtMillis,title,isrc)` produced by `collectionFavoritesFrom` (Task 3), consumed by `newRemoteFavorites`/`placeholderTrackFor` (Task 5) — fields match. `TidalDocument.links`/`TidalResource.meta` added in Task 1, read by `collectionFavoritesFrom` (`ident.meta`) and `favoriteItems` (`doc.links?.next` → `nextCursorFrom`) — names align. Write path: `collectionWritePayload` → `CollectionWritePayload{data:[CollectionWriteItem{type,id,meta}]}` serialized (encodeDefaults=false ⇒ null meta omitted) → `collectionRelationshipWriteRequest(body)` → `writeCollectionRelationship` → `executeUnit` (`Result<Unit>`), consistent across Task 4. `resolveCollectionId()` (Task 2, a delegate to the Foundation's cached `currentUserId()`) consumed by both read (`favoriteItems`) and write (`writeFavorite`); `TrackAttributes` is the single canonical DTO from `TidalOfficialApi.kt` (no Slice-2 re-declaration). `Source.TIDAL.name` used identically in `favoriteKeys` (existing), `newRemoteFavorites`, and `placeholderTrackFor`. `TidalCollectionSync(metadata, library, clock)` matches its `Graph` construction; `pushFavorite`/`pullFavoriteTracks` signatures match the ViewModel call sites.
- **Boundaries honored:** internal-v1 (`TidalAuth.kt`/`TidalProvider.kt`) untouched; streaming/search stay internal-v1; no `client_secret`; no token logging; writes/executions never unit-tested; new `TidalCollectionSync` is a plain `Graph`-held class. `git status` at the end should show only the files listed across Tasks 1–6.
- **Open questions (on-device / product):** (1) confirm `userCollectionId == userId` (the resolve-via-`/users/me` assumption) returns 200 on device; (2) confirm official favorites track id == internal-v1 stream id (shared with Slice 1's id-equivalence probe) so synced favorites play; (3) **album/artist favorites have full official read/write + projection but NO local-DB reconcile** — the schema has no album/artist tables (Collection derives those tabs by grouping favorited *tracks*), so favoriting a Tidal album/artist via the official write succeeds on Tidal yet **never surfaces in the app UI** without schema work (album/artist tables + reconcile). This slice therefore wires only **track** favorites end-to-end; whether to add the schema is a **product decision**, flagged not silently resolved; (4) destructive "mirror-delete" pull and an offline write-queue are deferred (documented limitations).
