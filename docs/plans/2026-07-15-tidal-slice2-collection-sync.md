# Slice 2 — Collection Sync (Two-Way) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mirror the user's Tidal favorites (tracks/albums/artists) into the app via the sanctioned official `openapi.tidal.com/v2` `userCollections` API, and push local favorite toggles back to Tidal — reconciled with the existing local favorites (`LibraryRepository` + Room), keyed by `source:sourceId`. Album and artist favorites get their own narrow local tables (`favorite_albums`/`favorite_artists`, DB `version = 3`) so a Tidal-favorited album/artist is **visible in Collection even with no local tracks for it**. Streaming of a favorited Tidal track still goes through the internal-v1 `TidalProvider` path, untouched.

**Architecture:** Additive, built entirely on the Slice 0 Foundation seams (`TidalOfficialSession.validAccessToken()`, the `TidalJsonApi` compound-document layer, `TidalOfficialEndpoints`/`TidalOfficialClient`, and `Graph.tidalMetadata: TidalMetadataRepository`). Four concerns, cleanly separated:

- **Official-API access (metadata only)** — new pure request builders on `TidalOfficialEndpoints`, thin execute wrappers on `TidalOfficialClient` (including a new body-tolerant `executeUnit` for writes), and new suspend methods + pure projections on `TidalMetadataRepository`. All parsing/mapping/request-shape logic is pure and unit-tested; HTTP execution is never unit-tested (Slice 0 precedent).
- **The collection-id discovery problem** (the trickiest part) — the `userCollections/{id}` id is the current user's **user id**, obtained via `GET /users/{id}` with the special `id = "me"` (spec: *"Use `me` for the authenticated user's resource"*). That resolver (`userRequest`/`getUser`/pure `userIdFrom` + the cached `TidalMetadataRepository.currentUserId()`) now lives in the **Slice-0 Foundation (Task 4)** — shared with Slice 3, declared once. This slice's `resolveCollectionId()` is a one-line delegate to `currentUserId()` and is used as the collection id for every collection read/write. `user.read` scope is already requested (`TidalOfficialAuth.SCOPE`).
- **The local surface for album/artist favorites** — two narrow Room tables (`favorite_albums`, `favorite_artists`) + `MIGRATION_2_3`, each holding the favorite record plus the minimal display metadata the favorites endpoint actually returns. Deliberately *not* a catalog mirror (see the locked decision below).
- **Local reconcile + push** — a new `TidalCollectionSync` (held on `Graph`) composes `TidalMetadataRepository` (official API) with `LibraryRepository` (local DB). Its merge logic is a **pure** set-difference function (unit-tested), reused unchanged across tracks/albums/artists; its network+DB orchestration is not. Push is wired into `PlayerViewModel.toggleFavorite` (tracks) and the Collection Albums/Artists tabs (albums/artists); a manual "Sync favorites from Tidal" pull is surfaced in the existing `TidalOfficialDebugScreen`, closing the slice on an on-device checkpoint (mirroring the Slice 0 probe).

**Design decisions (locked for this slice):**

- **Dedup / conflict key = `source:sourceId`** (e.g. `TIDAL:75413016`), the same identity `LibraryRepository.favoriteKeys()` already emits. A Tidal favorite (`TIDAL:<id>`) and a local file of the same recording are *distinct* keys unless linked by `TrackEntity.match_group_id` — that match-group linking is a latent DB hook (`TrackEntity.match_group_id`, `PlaylistEntity.source`/`tidalPlaylistId`) noted but **out of scope** to compute here.
- **Pull is an additive union (non-destructive).** Remote favorites missing locally are added; local favorites missing remotely are **not** deleted by a pull (they are pushed instead). This keeps sync idempotent and never destroys a local-only favorite. A destructive "mirror-delete" mode is future work (needs tombstones).
- **Push is optimistic and best-effort.** `toggleFavorite` updates the local DB immediately (unchanged), then fires the official write for **Tidal-source items only** (a local file is not on Tidal). A failed write never rolls back the local toggle; the next full pull re-reconciles the union. No offline write-queue in this slice (documented limitation).
- **Sync triggers:** (1) push on every `toggleFavorite` of a Tidal track and every album/artist favorite toggle in Collection; (2) manual pull button (debug screen this slice; Collection UI later). Auto-pull-on-login is a one-line addition noted in Task 6 but intentionally left to the product wiring (avoids surprising network + placeholder inserts on every launch).
- **Full-metadata caveat:** the official favorites endpoints return a track's `title`/`isrc`/`duration` but **not** its artist name (artist is a `tracks` relationship the favorites endpoint cannot `include`). So a favorited Tidal track the app has never seen locally is inserted as a **minimal placeholder** (`title` only) that **self-heals** the moment internal-v1 search/play upserts the same `(TIDAL, id)` row with full metadata.
- **DECIDED 2026-07-15 (user) — album/artist favorites get narrow local tables, not a catalog mirror.** The earlier open question ("album/artist favorites have no local surface") is **resolved: add the schema.** Tracks-only was rejected; the requirement is that favoriting a Tidal album/artist round-trips *and* is visible in the app. The shape is deliberately **narrow**: `favorite_albums` / `favorite_artists` hold only the favorite record plus the minimal display metadata (`source`, `source_id`, `title`/`name`, `art_url`, `added_at`), with a unique index on `(source, source_id)` mirroring `TrackEntity`'s precedent. This is *not* a full albums/artists catalog — the need is precisely "show a Tidal-favorited album/artist in Collection even when the user has no local tracks for it, and round-trip its favorite state", which a favorite table satisfies (YAGNI). It also matches the **desktop Collection semantics the Android port tracks**: Collection Albums = *Tidal saved albums (`favorites.albums()`) ∪ local albums with favorited tracks*; Artists likewise. The revised Collection tab logic is that same union (Task 7).
- **⚠️ SCOPE BOUNDARY on that decision — this slice delivers three of its four halves; the *add*-side trigger is NOT in Slice 2.** Stated plainly here so nobody discovers it at the Task 8 checkpoint. Delivered: (1) **pull** for tracks/albums/artists, so Tidal-favorited albums/artists become **visible** in Collection with no local tracks (Tasks 6–7 — this is the decision's core); (2) **remove** round-trip — unfavorite from a Collection album card / artist row → local delete + `pushAlbumFavorite(…, favorite = false)` (Task 7); (3) the **add** write path itself, symmetric and tested (`addFavorite` request/payload, Task 4; `push*(favorite = true)`, Task 6). **Not delivered: any UI that can *trigger* an add.** Favoriting an album on Tidal needs its **Tidal album id**, and no screen has one — `AlbumDetailScreen(viewModel, album: String, artist: String, onOpenArtist: (String) -> Unit)` and `ArtistDetailScreen(viewModel, artist: String, onOpenAlbum: (AlbumGroup) -> Unit)` identify their subject **by name only** (verified in `ui/AlbumDetailScreen.kt` / `ui/ArtistDetailScreen.kt`), and a locally-grouped album carries no album id (`TrackEntity` has no `album_id` column — verified in `db/Entities.kt`). Every Collection entry that *does* have a Tidal id is already favorited, so the add path has no reachable caller. Closing this means resolving the id at the point of action via the Foundation's `trackRelations(trackId)` → `TrackRelations.albumId/artistId` (exists, built for exactly this) **plus** a genuine UX decision about an inherently async heart (resolve on screen open or on tap? what does the heart render while resolving?). That is design, not mechanical wiring, so it is **flagged for the user, not invented** — see open question 3 in the self-review. If the user wants it inside Slice 2 it becomes **Task 9**, and it deserves a brainstorm first.
- **Album/artist favorite rows are title-only placeholders too, and for a spec-confirmed reason.** `GET /userCollections/{id}/relationships/{albums|artists}` documents its `include` as *"Available options: albums"* / *"Available options: artists"* — **only the relationship itself**, no nested `albums.coverArt` or `albums.artists`. And per the cached spec, `Albums_Attributes` carries `title`/`albumType`/`releaseDate`/`explicit` but **no artist name** (`artists` is a relationship), while cover/profile art are the `coverArt`/`profileArt` relationships. So a pulled favorite album/artist has **title/name + addedAt only**; `art_url` is a nullable self-heal hook that a later enrichment slice fills, and a remote-only album card renders with no artist and no art. This exactly mirrors the track-placeholder story above rather than inventing a second mechanism.

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`; `encodeDefaults` stays default-`false` so null `meta` is omitted), OkHttp 4.12 (+ transitive okio, used in tests to read request bodies), AndroidX DataStore, Room 2.7.1 (`exportSchema = false`), Compose (debug screen + Collection), JUnit4 + Robolectric 4.14.1. `java.time` for ISO-8601 `addedAt` parsing (minSdk 26). minSdk 26 / compileSdk 35 / Media3 1.5.1.

## Global Constraints

- **Every gradle command must carry `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`.** The system default JDK is 25 and breaks this project's Gradle with a cryptic `java.lang.IllegalArgumentException: 25.0.3`. Every `Run:` line below already carries the prefix — do not drop it. Never pipe gradle through `tail`/`head` and then read the exit status (that is the pipe's status, not gradle's); read the real output for `BUILD SUCCESSFUL`.
- **PKCE public client — NEVER send `client_secret`** on any official-API request. All collection reads/writes go through `authed(url, accessToken)` / the write builder, which set only `Authorization: Bearer` + `Accept: application/vnd.api+json`.
- **Never log or print `accessToken`/`refreshToken`.** Only human-readable names/titles/counts go to Logcat or any status string.
- **Do not unit-test live HTTP execution** (Slice 0 precedent). Unit-test **pure** logic only: JSON:API parsing, document→domain projection, write-payload encoding, request shapes, the pure merge/reconcile set-logic, and the pure Collection tab union. Extract pure functions from every suspend/network wrapper so the logic is testable with no network.
- **Writes never execute in tests** — assert the request **shape** (method / path / headers / serialized body) and the payload **encoding**; the suspend write wrappers themselves are untested (network).
- **New classes stay plain instantiable classes held on `Graph`** (`TidalCollectionSync`), never JVM-wide `object`s with mutable state — so tests need no `@Before` reset. (`TidalOfficialEndpoints` stays a stateless pure-builder `object`, matching Slice 0; the only value it holds is an immutable `MediaType` constant.)
- **Internal-v1 code (`TidalAuth.kt`, `TidalProvider.kt`) is untouched.** A favorited Tidal track still streams via `TidalProvider.getStreamInfoById(sourceId)`. Confirm with `git status` at the end.
- **Streaming/search stay on internal-v1.** This slice is metadata + favorite-state only. Never route playback or search through the official path.
- **Build on Slice 0, don't re-invent.** Reuse `TidalDocument`/`TidalResource`/`TidalDocumentView`/`decodeAttributes`, `validAccessToken()`, the generic `execute<T>`, and `Graph.tidalMetadata`. Task 1 makes two small **backward-compatible additive** extensions to the Slice 0 parser (optional `links` on `TidalDocument`, optional `meta` on `TidalResource`) — Slice 0's existing tests still pass unchanged.
- **Never re-declare an existing type — a duplicate declaration is a Kotlin "conflicting declarations" COMPILE ERROR.** `TrackAttributes` (`TidalOfficialApi.kt`), `AlbumAttributes`/`ArtistAttributes` (`TidalMetadataRepository.kt`), `ArtworkFile`/`ArtworkAttributes`/`pickArtworkUrl`/`parseIsoDurationSeconds`/`releaseYearOf` (`TidalMetadataPrimitives.kt`), `TrackRelations`/`trackRelationsFrom` (`TidalMetadataResolution.kt`), `AlbumGroup`/`ArtistGroup`/`groupAlbums`/`groupArtists` (`data/LibraryGrouping.kt`) all already exist and are **reused, never re-declared**. Likewise the `/users/me` resolver (`userRequest`/`getUser`/`userIdFrom`/`currentUserId`) is Foundation-owned and delegated to. This exact bug class already bit an earlier pass of this work.
- **When new code in an EXISTING file names a type that file does not already import, ADD THE IMPORT — this is the mirror image of the re-declaration bug above, and it bites just as hard.** Do not assume a type is in scope because it is used elsewhere in the module; Kotlin resolves per-file. The concrete instance in this slice: `PlayerViewModel.kt` imports `io.github.auxen.model.QueueEntry`/`SourcePriority`/`Track` but **NOT `Source`** (today's `toggleFavorite` only touches the `String` `track.source.name`), so Tasks 6 and 7 — which both name `Source.TIDAL` — must add `import io.github.auxen.model.Source` (once, in whichever task lands first). `CollectionScreen.kt` **already** imports `Source`; the new files `TidalCollectionSync.kt` and `data/CollectionMerge.kt` carry it in their own import blocks below. Before running a task's build gate, re-read each new symbol in a modified file and confirm it resolves.
- **New assignments in `Graph.init(context)` go after ALL of their dependencies.** Every `Graph` field is a `lateinit var`, so a wrong-order assignment **compiles** and then throws `UninitializedPropertyAccessException` on app launch — no unit test or `assembleDebug` in this plan catches it (nothing here calls `Graph.init()`), so it would only surface at the Task 8 on-device checkpoint as a total startup failure. `TidalCollectionSync` needs `tidalMetadata` AND `library`; `library` is assigned two statements later than `tidalMetadata` (after `db`). See Task 6, Step 5.
- **`TidalMetadataRepository`'s constructor is FROZEN** at its Foundation shape `(session, client, json, countryCode, locale)`. Later slices only **append methods**; never rewrite the signature or its `Graph` construction.
- **The Room DB goes `version = 2` → `version = 3` exactly once,** via the additive `MIGRATION_2_3` registered in `AuxenDatabase.build()`. `exportSchema` stays `false`; the migration test (Task 5) is designed around that (no schema JSON, no `room-testing` dependency).
- **Official client id is `BuildConfig.TIDAL_OFFICIAL_CLIENT_ID`** (public PKCE value), distinct from the internal `TIDAL_CLIENT_ID`.
- **Git: explicit pathspecs only.** Never `git add -A` / `git add .` / `git commit -a` — the working tree carries unrelated untracked files (`.playwright-mcp/`, other `docs/plans/*`) that must never be committed.
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

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalJsonApiPagingTest*'`
Expected: FAIL — compilation error: `TidalLinks`, `TidalDocument.links`, `TidalResource.meta`, and `nextCursorFrom` are unresolved references (none of them exist yet).

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

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalJsonApiPagingTest*' --tests '*TidalJsonApiTest*'`
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

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalOfficialEndpointsTest*' assembleDebug`
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

Per the cached spec, each of these endpoints documents `include` as *"Available options: `<the relationship itself>`"* — so `?include={relationship}` is the **only** legal include here. There is no `albums.coverArt` / `albums.artists` nesting, which is exactly why favorites (of every type) come back title-only. `CollectionFavorite` is therefore **one type for all three relationships** — `title` carries the track title, the album title, or the artist name.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpoints.kt` (add `collectionRelationshipPageRequest`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalOfficialClient.kt` (add `getCollectionRelationshipPage`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollection.kt` (add `CollectionFavorite`, `CollectionItemMeta`, `parseIso8601Millis`, `collectionFavoritesFrom`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalMetadataRepository.kt` (add `favoriteItems` + `favoriteTracks`/`favoriteAlbums`/`favoriteArtists`)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionTest.kt` (create — first pure-projection cases)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalOfficialEndpointsTest.kt` (add page-request shape)

**Interfaces:**
- Consumes: `TidalDocument`/`TidalDocumentView`/`decodeAttributes` (Slice 0/Task 1); the canonical `TrackAttributes` (existing `TidalOfficialApi.kt` — `title`/`isrc`/`duration`/`explicit`), `AlbumAttributes`/`ArtistAttributes` (Slice 0's `TidalMetadataRepository.kt`); `resolveCollectionId()`, `nextCursorFrom` (Tasks 1–2).
- Produces: `CollectionFavorite(id, type, addedAtMillis, title, isrc)`; `CollectionItemMeta(addedAt)`; `fun parseIso8601Millis(String?): Long?`; `fun collectionFavoritesFrom(doc, json, type): List<CollectionFavorite>`; `TidalOfficialEndpoints.collectionRelationshipPageRequest(...)`; `TidalOfficialClient.getCollectionRelationshipPage(...)`; `TidalMetadataRepository.favoriteItems(relationship): Result<List<CollectionFavorite>>` + `favoriteTracks/Albums/Artists()`. (`TrackAttributes`/`AlbumAttributes`/`ArtistAttributes` are REUSED, not re-declared.)

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

(Task 4 adds further `@Test` methods to this class, inserted before the closing brace.)

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

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalCollectionTest*' --tests '*TidalOfficialEndpointsTest*'`
Expected: FAIL — compilation error: `collectionFavoritesFrom`, `parseIso8601Millis`, `CollectionFavorite`, and `TidalOfficialEndpoints.collectionRelationshipPageRequest` are unresolved references.

- [ ] **Step 3: Add the page-request endpoint + client method**

In `TidalOfficialEndpoints.kt`, add:

```kotlin
    /**
     * `GET /userCollections/{id}/relationships/{relationship}` with
     * `?include={relationship}` so the favorite identifiers (with
     * `meta.addedAt`) come back alongside the full track/album/artist
     * resources in one call. Per the spec, the relationship itself is the
     * ONLY legal `include` value on these endpoints (no nested
     * `albums.coverArt`/`albums.artists`). Cursor-paginated via
     * `page[cursor]`; `locale` is optional (spec defaults to en-US).
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
 * userCollection relationship page. ONE type serves all three relationships:
 * [title] is the track title, the album title, or the artist name (null when
 * the full resource wasn't in `included`); [isrc] is populated for tracks only
 * (a latent hook for future local-file matching by ISRC). [addedAtMillis] is
 * null when Tidal omitted/we couldn't parse it.
 *
 * There is deliberately no artist-name or artwork field: the favorites
 * endpoints only allow `?include={the relationship itself}`, and per the spec
 * `Albums_Attributes` has no artist name while cover/profile art are the
 * `coverArt`/`profileArt` relationships. Favorites of every type are
 * title-only; richer metadata self-heals later (see Task 6).
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

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalCollectionTest*' --tests '*TidalOfficialEndpointsTest*' assembleDebug`
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

    @Test
    fun `collectionWritePayload carries the resource type for artists`() {
        val body = json.encodeToString(collectionWritePayload(listOf("7"), "artists"))
        assertEquals("""{"data":[{"type":"artists","id":"7"}]}""", body)
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

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalCollectionTest*' --tests '*TidalOfficialEndpointsTest*'`
Expected: FAIL — compilation error: `collectionWritePayload` and `TidalOfficialEndpoints.collectionRelationshipWriteRequest` are unresolved references.

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

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalCollectionTest*' --tests '*TidalOfficialEndpointsTest*' assembleDebug`
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

### Task 5: Local schema — `favorite_albums` / `favorite_artists` + `MIGRATION_2_3`

The local surface for the **user decision of 2026-07-15**. Two narrow favorite tables (not a catalog mirror) so a Tidal-favorited album/artist can be shown in Collection with no local tracks for it, and its favorite state can round-trip. Both mirror `TrackEntity`'s identity precedent exactly: an autogenerated row id plus a **unique index on `(source, source_id)`**, so `source:sourceId` stays the one dedup key across the whole app. The migration is additive (`CREATE TABLE IF NOT EXISTS` + `CREATE UNIQUE INDEX IF NOT EXISTS`, no existing data touched), following the `MIGRATION_1_2` precedent.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/Entities.kt` (add `FavoriteAlbumEntity`, `FavoriteArtistEntity`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/Daos.kt` (add `FavoriteAlbumDao`, `FavoriteArtistDao`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/AuxenDatabase.kt` (register both entities + DAOs, `version = 3`, `DB_NAME`, `MIGRATION_2_3`, `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`)
- Test: `android/app/src/test/kotlin/io/github/auxen/db/FavoriteCollectionDaoTest.kt` (create)
- Test: `android/app/src/test/kotlin/io/github/auxen/db/MigrationTest.kt` (create)

**Interfaces:**
- Consumes: nothing new (pure Room; `TrackEntity`'s `(source, source_id)` unique-index precedent).
- Produces: `FavoriteAlbumEntity(id, source, sourceId, title, artUrl, addedAtMillis)`; `FavoriteArtistEntity(id, source, sourceId, name, artUrl, addedAtMillis)`; `FavoriteAlbumDao`/`FavoriteArtistDao` each with `upsert`/`bySourceId`/`delete`/`all()`/`keys()`; `AuxenDatabase.favoriteAlbumDao()`/`favoriteArtistDao()`; `AuxenDatabase.DB_NAME`; `AuxenDatabase.MIGRATION_2_3`.
- Consumed by: `LibraryRepository` (Task 6), `mergeCollectionAlbums`/`mergeCollectionArtists` (Task 7).

**On testing the migration honestly.** The module sets `exportSchema = false` and does **not** depend on `androidx.room:room-testing`; there is also no `androidTest` source set. So `MigrationTestHelper` is **not** an option — it builds the "before" database from an exported `<version>.json` that does not exist and cannot be regenerated retroactively. Rather than spec a test that cannot run, `MigrationTest` drives Room's **own** open-time schema validation: create the current schema, rewind the file to v2, then reopen through the production `AuxenDatabase.build()`. Room detects `user_version 2 < 3`, runs the registered `MIGRATION_2_3`, and then calls its generated `validateMigration`, which compares the post-migration SQLite schema (columns, affinities, nullability, primary keys, indices) against the schema Room derived from the `@Entity` classes at compile time — throwing `IllegalStateException("Migration didn't properly handle: ...")` on any mismatch. That is a real migration test with **no schema JSON and no new dependency**, and it additionally proves the migration is actually *registered* (an unregistered one throws "A migration from 2 to 3 was required but not found").

- [ ] **Step 1: Write the failing tests**

Create `FavoriteCollectionDaoTest.kt` (in-memory Room, mirroring `SearchHistoryDaoTest`):

```kotlin
package io.github.auxen.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The narrow favorite-album/favorite-artist tables added for the 2026-07-15
 * decision: a Tidal-favorited album/artist must be visible in Collection even
 * with no local tracks for it. Identity is (source, source_id) — the same key
 * LibraryRepository.favoriteKeys() already emits for tracks.
 */
@RunWith(RobolectricTestRunner::class)
class FavoriteCollectionDaoTest {

    private lateinit var db: AuxenDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AuxenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    // ---------------- favorite albums ----------------

    @Test
    fun albumUpsertInsertsThenRefreshesPreservingRowId() = runBlocking {
        val dao = db.favoriteAlbumDao()
        val id = dao.upsert(
            FavoriteAlbumEntity(source = "TIDAL", sourceId = "9", title = "Album Nine", addedAtMillis = 100),
        )
        assertTrue(id > 0)

        // Same (source, source_id) → same row, refreshed metadata, no duplicate.
        val id2 = dao.upsert(
            FavoriteAlbumEntity(
                source = "TIDAL", sourceId = "9", title = "Album Nine (Deluxe)",
                artUrl = "https://art/9.jpg", addedAtMillis = 200,
            ),
        )
        assertEquals(id, id2)
        val row = dao.bySourceId("TIDAL", "9")!!
        assertEquals("Album Nine (Deluxe)", row.title)
        assertEquals("https://art/9.jpg", row.artUrl)
        assertEquals(200L, row.addedAtMillis)
        assertEquals(1, dao.all().first().size)
    }

    @Test
    fun albumKeysEmitSourceColonSourceIdAndDeleteRemoves() = runBlocking {
        val dao = db.favoriteAlbumDao()
        dao.upsert(FavoriteAlbumEntity(source = "TIDAL", sourceId = "9", title = "Nine", addedAtMillis = 100))
        dao.upsert(FavoriteAlbumEntity(source = "LOCAL", sourceId = "abc", title = "Local One", addedAtMillis = 50))

        assertEquals(setOf("TIDAL:9", "LOCAL:abc"), dao.keys().first().toSet())

        dao.delete("TIDAL", "9")
        assertEquals(listOf("LOCAL:abc"), dao.keys().first())
        assertNull(dao.bySourceId("TIDAL", "9"))
    }

    @Test
    fun albumsAreOrderedNewestFavoriteFirst() = runBlocking {
        val dao = db.favoriteAlbumDao()
        dao.upsert(FavoriteAlbumEntity(source = "TIDAL", sourceId = "1", title = "Older", addedAtMillis = 100))
        dao.upsert(FavoriteAlbumEntity(source = "TIDAL", sourceId = "2", title = "Newer", addedAtMillis = 300))
        assertEquals(listOf("Newer", "Older"), dao.all().first().map { it.title })
    }

    @Test
    fun sameSourceIdUnderDifferentSourcesAreDistinctRows() = runBlocking {
        val dao = db.favoriteAlbumDao()
        dao.upsert(FavoriteAlbumEntity(source = "TIDAL", sourceId = "9", title = "Tidal Nine", addedAtMillis = 1))
        dao.upsert(FavoriteAlbumEntity(source = "LOCAL", sourceId = "9", title = "Local Nine", addedAtMillis = 2))
        assertEquals(2, dao.all().first().size)
    }

    // ---------------- favorite artists ----------------

    @Test
    fun artistUpsertKeysAndDeleteRoundTrip() = runBlocking {
        val dao = db.favoriteArtistDao()
        val id = dao.upsert(FavoriteArtistEntity(source = "TIDAL", sourceId = "7", name = "Prompto", addedAtMillis = 10))
        val id2 = dao.upsert(
            FavoriteArtistEntity(
                source = "TIDAL", sourceId = "7", name = "Prompto",
                artUrl = "https://art/7.jpg", addedAtMillis = 20,
            ),
        )
        assertEquals(id, id2)
        assertEquals("https://art/7.jpg", dao.bySourceId("TIDAL", "7")!!.artUrl)
        assertEquals(listOf("TIDAL:7"), dao.keys().first())

        dao.delete("TIDAL", "7")
        assertTrue(dao.all().first().isEmpty())
    }
}
```

Create `MigrationTest.kt`:

```kotlin
package io.github.auxen.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MIGRATION_2_3 (adds favorite_albums / favorite_artists), exercised through
 * Room's OWN open-time schema validation.
 *
 * WHY NOT MigrationTestHelper: the module sets `exportSchema = false`, so there
 * is no `schemas/<version>.json` for the helper to build the "before" database
 * from (and none can be produced retroactively for v2), and
 * `androidx.room:room-testing` is not a dependency. So the v2 database is
 * manufactured directly instead:
 *
 *   1. Let Room create the CURRENT (v3) schema in a real file-backed database.
 *   2. Drop the two v3-only tables and stamp `user_version = 2`. This IS a
 *      faithful v2 database precisely BECAUSE MIGRATION_2_3 is additive:
 *      v2 == v3 minus favorite_albums/favorite_artists, and every other table
 *      is identical between the two versions.
 *   3. Reopen through the PRODUCTION [AuxenDatabase.build] path. Room sees
 *      user_version 2 < 3, runs the registered MIGRATION_2_3, then calls its
 *      generated `validateMigration`, which compares the post-migration SQLite
 *      schema (columns, affinities, nullability, primary keys, indices) with
 *      the schema Room derived from the @Entity classes at compile time. A
 *      wrong column name/type, a missing NOT NULL, or a missing unique index
 *      throws IllegalStateException("Migration didn't properly handle: ...").
 *
 * This also proves MIGRATION_2_3 is REGISTERED in build(): an unregistered
 * migration throws "A migration from 2 to 3 was required but not found".
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(AuxenDatabase.DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(AuxenDatabase.DB_NAME)
    }

    @Test
    fun migration2to3AddsFavoriteCollectionTablesAndPreservesExistingData() = runBlocking {
        // 1. Create the current schema on disk and seed a row that must survive.
        val seeded = AuxenDatabase.build(context)
        val trackId = seeded.trackDao().upsert(
            Track(title = "Keeper", artist = "Artist", source = Source.LOCAL, sourceId = "keep-1").toEntity(),
            nowMillis = 1_000,
        )
        seeded.favoriteDao().insert(FavoriteEntity(trackId = trackId, matchGroupId = null, addedAtMillis = 1_000))
        seeded.close()

        // 2. Rewind the file to v2 (additive migration ⇒ v2 == v3 minus the new tables).
        val path = context.getDatabasePath(AuxenDatabase.DB_NAME).absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE IF EXISTS `favorite_albums`")
            raw.execSQL("DROP TABLE IF EXISTS `favorite_artists`")
            raw.version = 2
        }

        // 3. Reopen through the production builder: Room runs MIGRATION_2_3 and
        //    validates the result against the compiled @Entity schema. If the
        //    migration DDL and the entities disagree, this line throws.
        val migrated = AuxenDatabase.build(context)

        // The additive migration left the v2 data alone.
        assertEquals(listOf("LOCAL:keep-1"), migrated.favoriteDao().favoriteKeys().first())

        // The new tables exist and satisfy their entity contract post-migration.
        migrated.favoriteAlbumDao().upsert(
            FavoriteAlbumEntity(source = "TIDAL", sourceId = "9", title = "Album Nine", addedAtMillis = 2_000),
        )
        migrated.favoriteArtistDao().upsert(
            FavoriteArtistEntity(source = "TIDAL", sourceId = "7", name = "Prompto", addedAtMillis = 3_000),
        )
        assertEquals(listOf("TIDAL:9"), migrated.favoriteAlbumDao().keys().first())
        assertEquals(listOf("TIDAL:7"), migrated.favoriteArtistDao().keys().first())
        migrated.close()
    }

    @Test
    fun reopeningAnAlreadyMigratedDatabaseRunsNoMigrationAndKeepsData() = runBlocking {
        // Opening a v3 database twice must not attempt the migration again and
        // must not fail Room's identity check.
        val first = AuxenDatabase.build(context)
        first.favoriteAlbumDao().upsert(
            FavoriteAlbumEntity(source = "TIDAL", sourceId = "5", title = "Five", addedAtMillis = 1),
        )
        first.close()

        val second = AuxenDatabase.build(context)
        assertEquals(listOf("TIDAL:5"), second.favoriteAlbumDao().keys().first())
        second.close()
    }
}
```

Note on `close()`: `AuxenDatabase` extends `androidx.room.RoomDatabase`, which does **not** implement `Closeable` in Room 2.7.1 — so `use { }` does NOT apply to it and every database is closed explicitly above. `SQLiteDatabase` *does* (via `SQLiteClosable implements Closeable`), which is why the raw-SQLite block is the one place `use { }` is used.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*FavoriteCollectionDaoTest*' --tests '*MigrationTest*'`
Expected: FAIL — compilation error: `FavoriteAlbumEntity`, `FavoriteArtistEntity`, `AuxenDatabase.favoriteAlbumDao()`, `AuxenDatabase.favoriteArtistDao()` and `AuxenDatabase.DB_NAME` are unresolved references (none exist yet; the DB is still `version = 2` with eight entities).

- [ ] **Step 3: Add the entities**

In `Entities.kt`, append (both mirror `TrackEntity`: autogenerated row id + a unique `(source, source_id)` index, so `source:sourceId` remains the single dedup key):

```kotlin
/**
 * A favorited album — the local surface for Tidal saved albums (user decision
 * 2026-07-15). Deliberately NOT a catalog mirror: only the favorite record
 * plus the minimal display metadata Collection needs.
 *
 * [artUrl] is nullable and stays null on a pull: the official favorites
 * endpoint only allows `?include=albums`, and per the spec an album's cover is
 * the `coverArt` RELATIONSHIP (and `Albums_Attributes` carries no artist name
 * either). It is a self-heal hook for a later enrichment slice.
 */
@Entity(
    tableName = "favorite_albums",
    indices = [Index(value = ["source", "source_id"], unique = true)],
)
data class FavoriteAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String,
    @ColumnInfo(name = "art_url") val artUrl: String? = null,
    @ColumnInfo(name = "added_at") val addedAtMillis: Long,
)

/**
 * A favorited (followed) artist — the local surface for Tidal followed artists
 * (user decision 2026-07-15). [artUrl] is null on a pull for the same reason as
 * [FavoriteAlbumEntity.artUrl] (`profileArt` is a relationship, and the
 * favorites endpoint only allows `?include=artists`).
 */
@Entity(
    tableName = "favorite_artists",
    indices = [Index(value = ["source", "source_id"], unique = true)],
)
data class FavoriteArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val name: String,
    @ColumnInfo(name = "art_url") val artUrl: String? = null,
    @ColumnInfo(name = "added_at") val addedAtMillis: Long,
)
```

- [ ] **Step 4: Add the DAOs**

In `Daos.kt`, append (the `upsert` mirrors `TrackDao.upsert`'s insert-or-refresh-preserving-row-id semantics; `keys()` mirrors `FavoriteDao.favoriteKeys()`'s `source || ':' || source_id` projection so the pure `newRemoteFavorites` set-difference works unchanged for albums/artists in Task 6):

```kotlin
@Dao
interface FavoriteAlbumDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(album: FavoriteAlbumEntity): Long

    @Update
    suspend fun update(album: FavoriteAlbumEntity)

    @Query("SELECT * FROM favorite_albums WHERE source = :source AND source_id = :sourceId")
    suspend fun bySourceId(source: String, sourceId: String): FavoriteAlbumEntity?

    /** Insert-or-refresh keyed by (source, source_id); the row id is preserved. Idempotent. */
    @Transaction
    suspend fun upsert(album: FavoriteAlbumEntity): Long {
        val existing = bySourceId(album.source, album.sourceId)
            ?: return insertIgnore(album.copy(id = 0))
        update(album.copy(id = existing.id))
        return existing.id
    }

    @Query("DELETE FROM favorite_albums WHERE source = :source AND source_id = :sourceId")
    suspend fun delete(source: String, sourceId: String)

    @Query("SELECT * FROM favorite_albums ORDER BY added_at DESC")
    fun all(): Flow<List<FavoriteAlbumEntity>>

    /** "SOURCE:sourceId" identity keys — the same shape as FavoriteDao.favoriteKeys(). */
    @Query("SELECT source || ':' || source_id FROM favorite_albums")
    fun keys(): Flow<List<String>>
}

@Dao
interface FavoriteArtistDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(artist: FavoriteArtistEntity): Long

    @Update
    suspend fun update(artist: FavoriteArtistEntity)

    @Query("SELECT * FROM favorite_artists WHERE source = :source AND source_id = :sourceId")
    suspend fun bySourceId(source: String, sourceId: String): FavoriteArtistEntity?

    /** Insert-or-refresh keyed by (source, source_id); the row id is preserved. Idempotent. */
    @Transaction
    suspend fun upsert(artist: FavoriteArtistEntity): Long {
        val existing = bySourceId(artist.source, artist.sourceId)
            ?: return insertIgnore(artist.copy(id = 0))
        update(artist.copy(id = existing.id))
        return existing.id
    }

    @Query("DELETE FROM favorite_artists WHERE source = :source AND source_id = :sourceId")
    suspend fun delete(source: String, sourceId: String)

    @Query("SELECT * FROM favorite_artists ORDER BY added_at DESC")
    fun all(): Flow<List<FavoriteArtistEntity>>

    /** "SOURCE:sourceId" identity keys — the same shape as FavoriteDao.favoriteKeys(). */
    @Query("SELECT source || ':' || source_id FROM favorite_artists")
    fun keys(): Flow<List<String>>
}
```

- [ ] **Step 5: Register the entities, bump to `version = 3`, add `MIGRATION_2_3`**

Rewrite `AuxenDatabase.kt` to (the DDL below is exactly what Room generates for the two entities — `CREATE TABLE IF NOT EXISTS`, backtick-quoted, `INTEGER PRIMARY KEY AUTOINCREMENT` for the autogenerated `Long` id, `TEXT NOT NULL` for non-null Strings, `TEXT` for the nullable `art_url`, `INTEGER NOT NULL` for the non-null `added_at`, and Room's default index name `index_<table>_<col>_<col>`):

```kotlin
@Database(
    entities = [
        TrackEntity::class,
        FavoriteEntity::class,
        FavoriteAlbumEntity::class,
        FavoriteArtistEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        PlayHistoryEntity::class,
        SettingEntity::class,
        QueueItemEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AuxenDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun favoriteAlbumDao(): FavoriteAlbumDao
    abstract fun favoriteArtistDao(): FavoriteArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun settingsDao(): SettingsDao
    abstract fun queueDao(): QueueDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        /** On-disk database name — shared with MigrationTest so the two can never drift. */
        const val DB_NAME = "auxen.db"

        /** v1 -> v2: adds the search_history table (additive, no data touched). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_history` (" +
                        "`query` TEXT NOT NULL, " +
                        "`searched_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`query`))",
                )
            }
        }

        /**
         * v2 -> v3: adds the favorite_albums + favorite_artists tables (additive,
         * no data touched) — the local surface for Tidal saved albums / followed
         * artists (user decision 2026-07-15). Each mirrors TrackEntity's identity:
         * an autogenerated row id plus a UNIQUE (source, source_id) index.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorite_albums` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "`source` TEXT NOT NULL, " +
                        "`source_id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`art_url` TEXT, " +
                        "`added_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_favorite_albums_source_source_id` " +
                        "ON `favorite_albums` (`source`, `source_id`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorite_artists` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "`source` TEXT NOT NULL, " +
                        "`source_id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`art_url` TEXT, " +
                        "`added_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_favorite_artists_source_source_id` " +
                        "ON `favorite_artists` (`source`, `source_id`)",
                )
            }
        }

        fun build(context: Context): AuxenDatabase =
            Room.databaseBuilder(context, AuxenDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
```

- [ ] **Step 6: Run tests + commit**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*FavoriteCollectionDaoTest*' --tests '*MigrationTest*' --tests '*DaoTest*' --tests '*SearchHistoryDaoTest*' --tests '*LibraryRepositoryTest*' assembleDebug`
Expected: PASS; APK builds. The pre-existing DB tests are re-run deliberately — they build the schema from the same `@Entity` set, so they catch a botched entity change immediately.

If `MigrationTest` fails with `IllegalStateException: Migration didn't properly handle: favorite_albums(...)`, the message prints Room's **expected** vs **found** column/index sets — diff them and fix the DDL in `MIGRATION_2_3` to match the entity, never the other way round.

```bash
git add android/app/src/main/kotlin/io/github/auxen/db/Entities.kt \
        android/app/src/main/kotlin/io/github/auxen/db/Daos.kt \
        android/app/src/main/kotlin/io/github/auxen/db/AuxenDatabase.kt \
        android/app/src/test/kotlin/io/github/auxen/db/FavoriteCollectionDaoTest.kt \
        android/app/src/test/kotlin/io/github/auxen/db/MigrationTest.kt
git commit -m "feat(android): favorite_albums/favorite_artists tables + MIGRATION_2_3

Adds the local surface for Tidal saved albums / followed artists (user
decision 2026-07-15): two narrow favorite tables (not a catalog mirror),
each mirroring TrackEntity's unique (source, source_id) identity, plus an
additive v2->v3 migration. MigrationTest drives Room's own open-time schema
validation (exportSchema=false rules out MigrationTestHelper).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 6: Reconcile with local favorites — `TidalCollectionSync` + push wiring

Tie the official read/write layer to the local DB, for **all three** favorite types. `TidalCollectionSync` (on `Graph`) computes the additive-union delta with **one pure `newRemoteFavorites` set-difference reused across tracks/albums/artists** (unit-tested), marks known local tracks favorite and inserts self-healing placeholders for unknown ones, upserts album/artist favorite rows, and pushes local toggles to Tidal (Tidal-source only, optimistic) through **one pure-shaped private `push`** helper. `PlayerViewModel.toggleFavorite` fires the track push; a manual pull is exposed for the debug screen.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt` (add `favoriteFromRemote` + the favorite-album/artist accessors)
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt` (pure `newRemoteFavorites`/`placeholderTrackFor`/`favoriteAlbumEntityFor`/`favoriteArtistEntityFor` + suspend `pullFavoriteTracks`/`pullFavoriteAlbums`/`pullFavoriteArtists`/`pullAllFavorites`/`pushFavorite`/`pushAlbumFavorite`/`pushArtistFavorite`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (`Graph.tidalCollectionSync` wiring)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (push in `toggleFavorite` + `syncFavoritesFromTidal` + status flow)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionSyncTest.kt` (create)

**Interfaces:**
- Consumes: `TidalMetadataRepository.favoriteTracks()`/`favoriteAlbums()`/`favoriteArtists()`/`addFavorite`/`removeFavorite` (Tasks 3–4); `CollectionFavorite` (Task 3); `FavoriteAlbumEntity`/`FavoriteArtistEntity` + their DAOs (Task 5); `LibraryRepository.favoriteKeys()`/`upsert`; `Track`/`Source`.
- Produces: `LibraryRepository.favoriteFromRemote(placeholder, addedAtMillis)`, `.favoriteAlbums()`, `.favoriteAlbumKeys()`, `.favoriteAlbum(entity)`, `.unfavoriteAlbum(source, sourceId)`, `.favoriteArtists()`, `.favoriteArtistKeys()`, `.favoriteArtist(entity)`, `.unfavoriteArtist(source, sourceId)`; `fun newRemoteFavorites(remote, localFavoriteKeys): List<CollectionFavorite>`; `fun placeholderTrackFor(fav): Track`; `fun favoriteAlbumEntityFor(fav, addedAtMillis): FavoriteAlbumEntity`; `fun favoriteArtistEntityFor(fav, addedAtMillis): FavoriteArtistEntity`; `data class CollectionPullSummary(tracks, albums, artists)`; `TidalCollectionSync(metadata, library, clock)` with `pullFavoriteTracks/Albums/Artists(): Result<Int>`, `pullAllFavorites(): Result<CollectionPullSummary>`, `pushFavorite(track, favorite)`, `pushAlbumFavorite(source, sourceId, favorite)`, `pushArtistFavorite(source, sourceId, favorite)`; `Graph.tidalCollectionSync`; `PlayerViewModel.syncFavoritesFromTidal()` + `tidalCollectionStatus: StateFlow<String?>`.

- [ ] **Step 1: Write the failing test for the pure merge logic**

Create `TidalCollectionSyncTest.kt`:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure merge/reconcile logic for two-way collection sync. The network+DB
 * orchestration (the pull/push suspend wrappers) is untested (Slice 0 network
 * precedent); only the additive-union delta and the placeholder/entity
 * projections are unit-tested here. Note that ONE newRemoteFavorites serves
 * all three favorite types — the album/artist cases below prove it.
 */
class TidalCollectionSyncTest {

    private fun fav(id: String, title: String? = null, addedAt: Long? = null, type: String = "tracks") =
        CollectionFavorite(id = id, type = type, addedAtMillis = addedAt, title = title)

    // ---------------- additive-union delta (shared by all three types) ----------------

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
    fun `newRemoteFavorites is reused unchanged for albums and artists`() {
        val albums = listOf(fav("9", type = "albums"), fav("10", type = "albums"))
        assertEquals(listOf("10"), newRemoteFavorites(albums, setOf("TIDAL:9")).map { it.id })

        val artists = listOf(fav("7", type = "artists"), fav("8", type = "artists"))
        assertEquals(listOf("7"), newRemoteFavorites(artists, setOf("TIDAL:8")).map { it.id })
    }

    // ---------------- track placeholder ----------------

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

    // ---------------- album / artist favorite rows ----------------

    @Test
    fun `favoriteAlbumEntityFor projects a Tidal album favorite row`() {
        val row = favoriteAlbumEntityFor(fav("9", title = "Album Nine", type = "albums"), addedAtMillis = 1_000)
        assertEquals(Source.TIDAL.name, row.source)
        assertEquals("9", row.sourceId)
        assertEquals("Album Nine", row.title)
        assertEquals(1_000L, row.addedAtMillis)
        // The favorites endpoint cannot include coverArt, so there is no art to store yet.
        assertNull(row.artUrl)
    }

    @Test
    fun `favoriteAlbumEntityFor falls back to a stable title when the favorite has none`() {
        assertEquals("Tidal album 12", favoriteAlbumEntityFor(fav("12", type = "albums"), 0).title)
        assertEquals("Tidal album 13", favoriteAlbumEntityFor(fav("13", title = "  ", type = "albums"), 0).title)
    }

    @Test
    fun `favoriteArtistEntityFor projects a Tidal artist favorite row`() {
        val row = favoriteArtistEntityFor(fav("7", title = "Prompto", type = "artists"), addedAtMillis = 2_000)
        assertEquals(Source.TIDAL.name, row.source)
        assertEquals("7", row.sourceId)
        assertEquals("Prompto", row.name)
        assertEquals(2_000L, row.addedAtMillis)
        assertNull(row.artUrl)
    }

    @Test
    fun `favoriteArtistEntityFor falls back to a stable name when the favorite has none`() {
        assertEquals("Tidal artist 21", favoriteArtistEntityFor(fav("21", type = "artists"), 0).name)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalCollectionSyncTest*'`
Expected: FAIL — compilation error: `newRemoteFavorites`, `placeholderTrackFor`, `favoriteAlbumEntityFor`, `favoriteArtistEntityFor` are unresolved references (`TidalCollectionSync.kt` does not exist yet).

- [ ] **Step 3: Add the repository accessors**

In `LibraryRepository.kt`, add the imports `io.github.auxen.db.FavoriteAlbumEntity` and `io.github.auxen.db.FavoriteArtistEntity`, then add (DB access stays encapsulated in the repository; every write is idempotent):

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

    /** Favorited albums, newest favorite first (Tidal saved albums + local favorites). */
    fun favoriteAlbums(): Flow<List<FavoriteAlbumEntity>> = db.favoriteAlbumDao().all()

    /** "SOURCE:sourceId" keys of favorited albums — the album analogue of [favoriteKeys]. */
    fun favoriteAlbumKeys(): Flow<Set<String>> = db.favoriteAlbumDao().keys().map { it.toSet() }

    /**
     * Insert-or-refresh a favorite album row. [FavoriteAlbumEntity.addedAtMillis]
     * is the favorite's timestamp — Tidal's `addedAt` on a pull, the caller's
     * clock on a local toggle. Idempotent on (source, source_id).
     */
    suspend fun favoriteAlbum(album: FavoriteAlbumEntity): Long = db.favoriteAlbumDao().upsert(album)

    suspend fun unfavoriteAlbum(source: String, sourceId: String) = db.favoriteAlbumDao().delete(source, sourceId)

    /** Favorited (followed) artists, newest favorite first. */
    fun favoriteArtists(): Flow<List<FavoriteArtistEntity>> = db.favoriteArtistDao().all()

    /** "SOURCE:sourceId" keys of favorited artists — the artist analogue of [favoriteKeys]. */
    fun favoriteArtistKeys(): Flow<Set<String>> = db.favoriteArtistDao().keys().map { it.toSet() }

    /** Insert-or-refresh a favorite artist row. Idempotent on (source, source_id). */
    suspend fun favoriteArtist(artist: FavoriteArtistEntity): Long = db.favoriteArtistDao().upsert(artist)

    suspend fun unfavoriteArtist(source: String, sourceId: String) = db.favoriteArtistDao().delete(source, sourceId)
```

- [ ] **Step 4: Create `TidalCollectionSync`**

Create `TidalCollectionSync.kt`:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.data.LibraryRepository
import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.coroutines.flow.first

/** What one full pull added locally, per favorite type. */
data class CollectionPullSummary(val tracks: Int, val albums: Int, val artists: Int)

/**
 * Two-way reconcile between the user's Tidal favorites (official
 * `userCollections` API, via [TidalMetadataRepository]) and the local
 * favorites ([LibraryRepository]) — tracks, albums and artists. Held on
 * `Graph` (a plain class, not an object). Pure merge logic is unit-tested; the
 * network+DB orchestration is not (Slice 0 precedent). Playback of a synced
 * favorite stays on the internal-v1 [TidalProvider] — this class touches
 * metadata + favorite-state only.
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

    /** Pull Tidal saved albums into `favorite_albums` (additive union). Returns the number newly added. */
    suspend fun pullFavoriteAlbums(): Result<Int> = runCatching {
        val remote = metadata.favoriteAlbums().getOrThrow()
        val fresh = newRemoteFavorites(remote, library.favoriteAlbumKeys().first())
        fresh.forEach { library.favoriteAlbum(favoriteAlbumEntityFor(it, it.addedAtMillis ?: clock())) }
        fresh.size
    }

    /** Pull Tidal followed artists into `favorite_artists` (additive union). Returns the number newly added. */
    suspend fun pullFavoriteArtists(): Result<Int> = runCatching {
        val remote = metadata.favoriteArtists().getOrThrow()
        val fresh = newRemoteFavorites(remote, library.favoriteArtistKeys().first())
        fresh.forEach { library.favoriteArtist(favoriteArtistEntityFor(it, it.addedAtMillis ?: clock())) }
        fresh.size
    }

    /** One pull of all three favorite types. Fails fast on the first failing type. */
    suspend fun pullAllFavorites(): Result<CollectionPullSummary> = runCatching {
        CollectionPullSummary(
            tracks = pullFavoriteTracks().getOrThrow(),
            albums = pullFavoriteAlbums().getOrThrow(),
            artists = pullFavoriteArtists().getOrThrow(),
        )
    }

    /**
     * Push a local track favorite toggle to Tidal. Best-effort and optimistic —
     * the caller has already updated the local DB; a failure here is not rolled
     * back. No-op for local-source tracks (they are not on Tidal).
     */
    suspend fun pushFavorite(track: Track, favorite: Boolean): Result<Unit> =
        push("tracks", track.source, track.sourceId, favorite)

    /** Push an album favorite toggle to Tidal. Same optimistic, Tidal-only contract as [pushFavorite]. */
    suspend fun pushAlbumFavorite(source: Source, sourceId: String, favorite: Boolean): Result<Unit> =
        push("albums", source, sourceId, favorite)

    /** Push an artist follow toggle to Tidal. Same optimistic, Tidal-only contract as [pushFavorite]. */
    suspend fun pushArtistFavorite(source: Source, sourceId: String, favorite: Boolean): Result<Unit> =
        push("artists", source, sourceId, favorite)

    /** The single push path for every relationship — a local-source item is never on Tidal, so it no-ops. */
    private suspend fun push(relationship: String, source: Source, sourceId: String, favorite: Boolean): Result<Unit> {
        if (source != Source.TIDAL) return Result.success(Unit)
        return if (favorite) metadata.addFavorite(relationship, sourceId)
        else metadata.removeFavorite(relationship, sourceId)
    }
}

/**
 * Remote favorites whose `TIDAL:<id>` key is not already locally favorited.
 * Pure, and type-agnostic: the caller supplies the matching local key set
 * (`favoriteKeys`, `favoriteAlbumKeys` or `favoriteArtistKeys`), all of which
 * emit the same `SOURCE:sourceId` shape.
 */
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

/**
 * A [FavoriteAlbumEntity] for a pulled Tidal album favorite. `artUrl` is null:
 * the favorites endpoint only allows `?include=albums` and an album's cover is
 * the `coverArt` relationship, so there is no art URL to store — it is a
 * self-heal hook for a later enrichment slice. Pure.
 */
fun favoriteAlbumEntityFor(fav: CollectionFavorite, addedAtMillis: Long): FavoriteAlbumEntity = FavoriteAlbumEntity(
    source = Source.TIDAL.name,
    sourceId = fav.id,
    title = fav.title?.ifBlank { null } ?: "Tidal album ${fav.id}",
    artUrl = null,
    addedAtMillis = addedAtMillis,
)

/**
 * A [FavoriteArtistEntity] for a pulled Tidal followed artist. `artUrl` is null
 * for the same reason as [favoriteAlbumEntityFor] (`profileArt` is a
 * relationship the favorites endpoint cannot include). Pure.
 */
fun favoriteArtistEntityFor(fav: CollectionFavorite, addedAtMillis: Long): FavoriteArtistEntity = FavoriteArtistEntity(
    source = Source.TIDAL.name,
    sourceId = fav.id,
    name = fav.title?.ifBlank { null } ?: "Tidal artist ${fav.id}",
    artUrl = null,
    addedAtMillis = addedAtMillis,
)
```

- [ ] **Step 5: Wire `Graph.tidalCollectionSync` + the ViewModel**

In `AuxenApp.kt`, in `object Graph`, add the field beside `tidalMetadata` (Slice 0):

```kotlin
    lateinit var tidalCollectionSync: TidalCollectionSync
        private set
```

and add the import `import io.github.auxen.provider.tidal.TidalCollectionSync`.

**Placement in `init(context)` is load-bearing — read this before you type it.** `TidalCollectionSync` takes BOTH `tidalMetadata` and `library`, and both are `lateinit var`s on `Graph`. In the current `init(context)` they are assigned two statements apart, with `db` in between:

```kotlin
        tidalMetadata = TidalMetadataRepository(   // assigned first
            …
        )
        db = AuxenDatabase.build(context)
        library = LibraryRepository(db)            // …but `library` only lands HERE
```

So the new line goes **immediately after `library = LibraryRepository(db)`**, NOT after the `tidalMetadata = …` line:

```kotlin
        db = AuxenDatabase.build(context)
        library = LibraryRepository(db)
        tidalCollectionSync = TidalCollectionSync(metadata = tidalMetadata, library = library)   // ADD THIS LINE HERE
        queueStore = QueueStateStore(db)
```

Putting it directly after `tidalMetadata = …` **compiles cleanly and then throws at runtime on every single app launch**: `kotlin.UninitializedPropertyAccessException: lateinit property library has not been initialized`. Nothing in this task's gates would catch that — Step 6 runs `assembleDebug` (compilation) and unit tests (pure functions); neither one calls `Graph.init()`. It would first surface at Task 8's on-device checkpoint as "the app won't start at all". Order the assignment after both of its dependencies.

**Append-only:** the `TidalMetadataRepository(...)` construction already has its FINAL shape from the Foundation (constructor `(session, client, json, countryCode, locale)`), so this slice only ADDS the `tidalCollectionSync = …` line — it never rewrites the `tidalMetadata = TidalMetadataRepository(...)` call, the repository constructor signature, or the relative order of any existing assignment.

In `PlayerViewModel.kt`, add the import:

```kotlin
import io.github.auxen.model.Source
```

**This import is REQUIRED and is easy to miss.** `PlayerViewModel.kt` today imports `io.github.auxen.model.QueueEntry`, `io.github.auxen.model.SourcePriority` and `io.github.auxen.model.Track` — but **not `Source`**, because the current `toggleFavorite` only ever touches `track.source.name` (a `String`) and never names the `Source` type. The revised body below compares against `Source.TIDAL`, so without this import the file fails to compile with `unresolved reference: Source`. (It sorts between `QueueEntry` and `SourcePriority`.) Task 7 Step 5 adds more `Source.TIDAL` references to this same file — this one import covers both tasks; do not add it twice.

Then extend `toggleFavorite` to push the toggle (optimistic; local DB already updated), and add a manual pull + status flow. Replace `toggleFavorite`:

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

    /** Manual pull of Tidal favorites (tracks + albums + artists) into the local DB; renders a human status string (no tokens). */
    fun syncFavoritesFromTidal() {
        viewModelScope.launch {
            _tidalCollectionStatus.value = "Syncing favorites from Tidal…"
            _tidalCollectionStatus.value = Graph.tidalCollectionSync.pullAllFavorites().fold(
                onSuccess = { "Synced: +${it.tracks} track(s), +${it.albums} album(s), +${it.artists} artist(s)." },
                onFailure = { "Favorites sync failed: ${it.message}" },
            )
        }
    }
```

(Optional future auto-trigger: call `syncFavoritesFromTidal()` from the official `completeLogin` success path — deliberately left to product wiring.)

- [ ] **Step 6: Run tests + build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalCollectionSyncTest*' --tests '*LibraryRepositoryTest*' assembleDebug`
Expected: PASS; APK builds (`Graph` wiring compiles; `TidalCollectionSync` defaults `clock`).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt \
        android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionSyncTest.kt
git commit -m "feat(android): reconcile Tidal favorites (tracks/albums/artists) + push toggles

TidalCollectionSync pulls all three favorite types (additive union; one pure
newRemoteFavorites set-difference reused across types; self-healing track
placeholders; narrow album/artist favorite rows) and pushes local toggles to
Tidal (optimistic, Tidal-only) through one push helper. Wires push into
toggleFavorite + a manual syncFavoritesFromTidal.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 7: Collection Albums/Artists tabs = local-grouped ∪ remote-favorited

The payoff of the 2026-07-15 decision, and the desktop's exact Collection semantics: **Albums = Tidal saved albums ∪ local albums with favorited tracks**; **Artists** likewise. Today `CollectionScreen.kt` derives both tabs purely by grouping favorited *tracks* (`groupAlbums(filtered)` / `groupArtists(filtered)` from `data/LibraryGrouping.kt`), so a Tidal-favorited album with no favorited tracks is invisible. Add a **pure** union in `data/CollectionMerge.kt` (unit-tested), feed it the new favorite tables, and render it. `groupAlbums`/`groupArtists`/`AlbumGroup`/`ArtistGroup` are REUSED, not re-declared.

Dedup follows the desktop rule (**exact name match**, trimmed + case-insensitive — not fuzzy): a remote favorite whose title/name matches a local group is dropped in favour of the local group, which is strictly richer (it has an artist, art, and playable tracks). Remote-only entries carry no artist and no art — see the locked decision — so they render as a title + Tidal badge and their card has no Play affordance (there are no local tracks to play).

**Known caveat (accepted, faithfully ported):** because the key is **title-only**, two genuinely different albums that share a title by different artists (e.g. a local *Origins* by one artist and a Tidal-saved *Origins* by another) **false-merge** — the remote one silently vanishes behind the local group. Title-only is forced here, not chosen: a pulled favorite album has **no artist name** to key on (`Albums_Attributes` has no artist; `artists` is a relationship the favorites endpoint cannot `include` — see the locked decision), so `(title, artist)` is not computable on the remote side in this slice. Desktop has the identical limitation and the user accepted it there. Worth knowing that Android exercises it against a *smaller* local library, which makes a collision *less* likely, not more. The real fix is keying on `source:sourceId` once an enrichment slice can resolve a local group's Tidal album id — the same id resolution open question 3 needs — at which point this rule should be revisited rather than extended.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/data/CollectionMerge.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumCard.kt` (optional `onToggleFavorite`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistRow.kt` (optional `onToggleFavorite`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (`favoriteAlbums`/`favoriteArtists` StateFlows + `toggleFavoriteAlbum`/`toggleFavoriteArtist`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt` (tabs 1 + 2 render the union)
- Test: `android/app/src/test/kotlin/io/github/auxen/data/CollectionMergeTest.kt` (create)

**Interfaces:**
- Consumes: `AlbumGroup`/`ArtistGroup`/`groupAlbums`/`groupArtists` (`data/LibraryGrouping.kt`, REUSED); `FavoriteAlbumEntity`/`FavoriteArtistEntity` (Task 5); `LibraryRepository.favoriteAlbums()`/`favoriteArtists()` (Task 6); `TidalCollectionSync.pushAlbumFavorite`/`pushArtistFavorite` (Task 6); `Source`/`Track`.
- Produces: `CollectionAlbum(title, artist, artUrl, source, tidalId, tracks)` with `key`/`isRemoteFavorite`/`toAlbumGroup()`; `CollectionArtist(name, artUrl, source, tidalId, trackCount)` with `key`/`isRemoteFavorite`; `fun mergeCollectionAlbums(local, remote): List<CollectionAlbum>`; `fun mergeCollectionArtists(local, remote): List<CollectionArtist>`; `PlayerViewModel.favoriteAlbums`/`favoriteArtists`/`toggleFavoriteAlbum`/`toggleFavoriteArtist`; `AlbumCard(onToggleFavorite = …)`; `ArtistRow(onToggleFavorite = …)`.

- [ ] **Step 1: Write the failing test**

Create `CollectionMergeTest.kt`:

```kotlin
package io.github.auxen.data

import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Collection Albums/Artists union — desktop semantics: Tidal saved albums
 * ∪ local albums with favorited tracks (Artists likewise). Pure; no Room, no
 * Compose, no network.
 */
class CollectionMergeTest {

    private fun tidalTrack(id: String, album: String, artist: String) = Track(
        title = "Song $id",
        artist = artist,
        source = Source.TIDAL,
        sourceId = id,
        album = album,
        albumArtist = artist,
        albumArtUrl = "https://art/$album.jpg",
    )

    private fun savedAlbum(id: String, title: String, addedAt: Long = 0) =
        FavoriteAlbumEntity(source = "TIDAL", sourceId = id, title = title, addedAtMillis = addedAt)

    private fun followedArtist(id: String, name: String, addedAt: Long = 0) =
        FavoriteArtistEntity(source = "TIDAL", sourceId = id, name = name, addedAtMillis = addedAt)

    // ---------------- albums ----------------

    @Test
    fun `local groups pass through with their tracks and art`() {
        val local = groupAlbums(listOf(tidalTrack("1", "OutRun", "Kavinsky")))
        val merged = mergeCollectionAlbums(local, emptyList())
        assertEquals(1, merged.size)
        assertEquals("OutRun", merged[0].title)
        assertEquals("Kavinsky", merged[0].artist)
        assertEquals("https://art/OutRun.jpg", merged[0].artUrl)
        assertEquals(Source.TIDAL, merged[0].source)
        assertEquals(1, merged[0].tracks.size)
        assertNull(merged[0].tidalId)
        assertFalse(merged[0].isRemoteFavorite)
    }

    @Test
    fun `a saved album with no local tracks still surfaces (the whole point)`() {
        val merged = mergeCollectionAlbums(emptyList(), listOf(savedAlbum("9", "Album Nine")))
        assertEquals(listOf("Album Nine"), merged.map { it.title })
        assertEquals("9", merged[0].tidalId)
        assertTrue(merged[0].isRemoteFavorite)
        assertEquals(Source.TIDAL, merged[0].source)
        // The favorites endpoint returns no artist and no cover art.
        assertNull(merged[0].artist)
        assertNull(merged[0].artUrl)
        assertTrue(merged[0].tracks.isEmpty())
    }

    @Test
    fun `a saved album that already has a local group is deduped in favour of the local group`() {
        val local = groupAlbums(listOf(tidalTrack("1", "OutRun", "Kavinsky")))
        val merged = mergeCollectionAlbums(local, listOf(savedAlbum("9", "OutRun")))
        assertEquals(1, merged.size)
        // The local group wins: it has the artist, the art and playable tracks.
        assertEquals("Kavinsky", merged[0].artist)
        assertEquals(1, merged[0].tracks.size)
    }

    @Test
    fun `dedup is exact-name but tolerant of case and surrounding whitespace`() {
        val local = groupAlbums(listOf(tidalTrack("1", "OutRun", "Kavinsky")))
        assertEquals(1, mergeCollectionAlbums(local, listOf(savedAlbum("9", "  outrun "))).size)
        // A genuinely different title is NOT deduped (clean/explicit variants stay visible).
        assertEquals(2, mergeCollectionAlbums(local, listOf(savedAlbum("9", "OutRun (Deluxe)"))).size)
    }

    @Test
    fun `local and remote entries have distinct stable keys`() {
        val local = groupAlbums(listOf(tidalTrack("1", "OutRun", "Kavinsky")))
        val merged = mergeCollectionAlbums(local, listOf(savedAlbum("9", "Nine"), savedAlbum("10", "Nine Too")))
        assertEquals(merged.size, merged.map { it.key }.distinct().size)
    }

    @Test
    fun `toAlbumGroup carries the fields album navigation keys on`() {
        val group = mergeCollectionAlbums(emptyList(), listOf(savedAlbum("9", "Album Nine")))
            .single()
            .toAlbumGroup()
        assertEquals("Album Nine", group.album)
        assertEquals("", group.albumArtist)
        assertTrue(group.tracks.isEmpty())
    }

    @Test
    fun `a local-source favorite album row projects Source LOCAL and no tidal id`() {
        val row = FavoriteAlbumEntity(source = "LOCAL", sourceId = "abc", title = "Home Tapes", addedAtMillis = 1)
        val merged = mergeCollectionAlbums(emptyList(), listOf(row)).single()
        assertEquals(Source.LOCAL, merged.source)
        assertNull(merged.tidalId)
    }

    // ---------------- artists ----------------

    @Test
    fun `followed artists union with locally-grouped artists and dedupe by name`() {
        val local = groupArtists(listOf(tidalTrack("1", "OutRun", "Kavinsky")))
        val merged = mergeCollectionArtists(
            local,
            listOf(followedArtist("7", "Prompto"), followedArtist("8", "kavinsky")),
        )
        assertEquals(listOf("Kavinsky", "Prompto"), merged.map { it.name })
        assertEquals(1, merged[0].trackCount)
        assertNull(merged[0].tidalId)
        assertEquals("7", merged[1].tidalId)
        assertEquals(0, merged[1].trackCount)
        assertTrue(merged[1].isRemoteFavorite)
    }

    @Test
    fun `a followed artist with no local tracks still surfaces`() {
        val merged = mergeCollectionArtists(emptyList(), listOf(followedArtist("7", "Prompto")))
        assertEquals(listOf("Prompto"), merged.map { it.name })
        assertNull(merged[0].artUrl)
        assertEquals(Source.TIDAL, merged[0].source)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*CollectionMergeTest*'`
Expected: FAIL — compilation error: `mergeCollectionAlbums`, `mergeCollectionArtists`, `CollectionAlbum`, `CollectionArtist` are unresolved references (`data/CollectionMerge.kt` does not exist yet).

- [ ] **Step 3: Create the pure union**

Create `CollectionMerge.kt`:

```kotlin
package io.github.auxen.data

import io.github.auxen.db.FavoriteAlbumEntity
import io.github.auxen.db.FavoriteArtistEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track

/**
 * The Collection Albums/Artists union — the Android port of the desktop
 * CollectionView's semantics: Albums = Tidal saved albums ∪ local albums with
 * favorited tracks; Artists likewise. Pure and unit-tested; grouping itself is
 * reused from [groupAlbums]/[groupArtists] (LibraryGrouping.kt), never
 * re-implemented here.
 */

/**
 * One Collection Albums-tab entry. Either a locally-grouped album (favorited
 * tracks — has [artist]/[artUrl]/[tracks]) or a Tidal saved album with no local
 * tracks (has [tidalId]; no artist and no art, because the favorites endpoint
 * only allows `?include=albums` and an album's artist/cover are relationships).
 */
data class CollectionAlbum(
    val title: String,
    val artist: String?,
    val artUrl: String?,
    val source: Source?,
    val tidalId: String?,
    val tracks: List<Track>,
) {
    /** True when this row exists because the user favorited the ALBUM on Tidal (vs. because its tracks are favorited). */
    val isRemoteFavorite: Boolean get() = tidalId != null

    /** Stable, unique LazyGrid key: remote favorites by Tidal id, local groups by (title, artist). */
    val key: String get() = if (tidalId != null) "tidal:$tidalId" else "local:$title|${artist.orEmpty()}"

    /** Project back to an [AlbumGroup] for the existing `album/{album}/{artist}` navigation route. */
    fun toAlbumGroup(): AlbumGroup = AlbumGroup(
        album = title,
        albumArtist = artist.orEmpty(),
        artUrl = artUrl,
        year = null,
        tracks = tracks,
    )
}

/** One Collection Artists-tab entry — a locally-grouped artist, or a Tidal followed artist with no local tracks. */
data class CollectionArtist(
    val name: String,
    val artUrl: String?,
    val source: Source?,
    val tidalId: String?,
    val trackCount: Int,
) {
    val isRemoteFavorite: Boolean get() = tidalId != null

    val key: String get() = if (tidalId != null) "tidal:$tidalId" else "local:$name"
}

/**
 * Albums tab = [local] groups ∪ [remote] saved albums, local first. A remote
 * favorite whose title matches a local group's album title is dropped: the
 * local group is strictly richer (artist, art, playable tracks). Dedup is
 * EXACT-name (trimmed, case-insensitive) — never fuzzy, so clean/explicit and
 * deluxe variants deliberately stay visible as separate entries.
 */
fun mergeCollectionAlbums(local: List<AlbumGroup>, remote: List<FavoriteAlbumEntity>): List<CollectionAlbum> {
    val localTitles = local.mapTo(mutableSetOf()) { it.album.normalizedName() }
    val localEntries = local.map { group ->
        CollectionAlbum(
            title = group.album,
            artist = group.albumArtist,
            artUrl = group.artUrl,
            source = group.tracks.firstOrNull()?.source,
            tidalId = null,
            tracks = group.tracks,
        )
    }
    val remoteOnly = remote
        .filter { it.title.normalizedName() !in localTitles }
        .map { row ->
            CollectionAlbum(
                title = row.title,
                artist = null,
                artUrl = row.artUrl,
                source = sourceOf(row.source),
                tidalId = row.sourceId.takeIf { row.source == Source.TIDAL.name },
                tracks = emptyList(),
            )
        }
    return localEntries + remoteOnly
}

/** Artists tab = [local] groups ∪ [remote] followed artists, local first. Same exact-name dedup as [mergeCollectionAlbums]. */
fun mergeCollectionArtists(local: List<ArtistGroup>, remote: List<FavoriteArtistEntity>): List<CollectionArtist> {
    val localNames = local.mapTo(mutableSetOf()) { it.artist.normalizedName() }
    val localEntries = local.map { group ->
        CollectionArtist(
            name = group.artist,
            artUrl = group.artUrl,
            source = group.tracks.firstOrNull()?.source,
            tidalId = null,
            trackCount = group.tracks.size,
        )
    }
    val remoteOnly = remote
        .filter { it.name.normalizedName() !in localNames }
        .map { row ->
            CollectionArtist(
                name = row.name,
                artUrl = row.artUrl,
                source = sourceOf(row.source),
                tidalId = row.sourceId.takeIf { row.source == Source.TIDAL.name },
                trackCount = 0,
            )
        }
    return localEntries + remoteOnly
}

/** The dedup key: exact name, tolerant only of case and surrounding whitespace. */
private fun String.normalizedName(): String = trim().lowercase()

/** Tolerant [Source] lookup — mirrors LibraryRepository.sourcePriority()'s `entries.firstOrNull` pattern; null on an unknown value. */
private fun sourceOf(name: String): Source? = Source.entries.firstOrNull { it.name == name }
```

- [ ] **Step 4: Add the optional favorite affordance to `AlbumCard` / `ArtistRow`**

Both params are **defaulted to null**, so every existing call site (Library, Home, ArtistDetail, and the `ComponentScreenshotTest` goldens) compiles and renders unchanged. The heart only ever appears on a remote favorite, which is by definition already favorited, so it always renders filled and always means "remove from collection" — no `isFavorite` param is needed.

In `AlbumCard.kt`, add `import androidx.compose.material.icons.filled.Favorite`, add the parameter at the end of the signature:

```kotlin
    onToggleFavorite: (() -> Unit)? = null,
```

and inside the art `Box`, after the existing `if (onPlay != null) { … }` block (TopStart is the source badge, BottomEnd is the play button, so the heart takes TopEnd):

```kotlin
            if (onToggleFavorite != null) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = "Remove $title from collection",
                        tint = AuxenColors.AmberPrimary,
                    )
                }
            }
```

In `ArtistRow.kt`, add `import androidx.compose.material.icons.filled.Favorite`, `import androidx.compose.material3.Icon` and `import androidx.compose.material3.IconButton`, add the parameter at the end of the signature:

```kotlin
    onToggleFavorite: (() -> Unit)? = null,
```

and after the `Column(modifier = Modifier.weight(1f)) { … }` block (the weight already pushes the trailing control to the end):

```kotlin
        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Unfollow $name",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
```

- [ ] **Step 5: Add the ViewModel flows + toggles**

In `PlayerViewModel.kt`, add `import io.github.auxen.data.CollectionAlbum`, `import io.github.auxen.data.CollectionArtist`, `import io.github.auxen.db.FavoriteAlbumEntity`, `import io.github.auxen.db.FavoriteArtistEntity`, and — **if Task 6 Step 5 has not already added it** — `import io.github.auxen.model.Source` (the toggles below name `Source.TIDAL`; stock `PlayerViewModel.kt` imports `SourcePriority` and `Track` from that package but *not* `Source`, so omitting it is an `unresolved reference: Source` compile error). Then add beside the existing `favorites`/`favoriteKeys` flows:

```kotlin
    /** Favorited albums (Tidal saved albums + local), newest first — the Collection Albums tab's remote half. */
    val favoriteAlbums: StateFlow<List<FavoriteAlbumEntity>> = Graph.library.favoriteAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Favorited (followed) artists, newest first — the Collection Artists tab's remote half. */
    val favoriteArtists: StateFlow<List<FavoriteArtistEntity>> = Graph.library.favoriteArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

and near `toggleFavorite`:

```kotlin
    /**
     * Remove a Collection album entry from the collection. Only a remote
     * favorite row can be toggled — a locally-grouped album is on the tab
     * because its TRACKS are favorited, and carries no album id to write with
     * (see the slice's documented limitation). Optimistic, exactly like
     * [toggleFavorite]: the local row goes first, then a best-effort push.
     */
    fun toggleFavoriteAlbum(album: CollectionAlbum) {
        val tidalId = album.tidalId ?: return
        viewModelScope.launch {
            runCatching { Graph.library.unfavoriteAlbum(Source.TIDAL.name, tidalId) }
            runCatching { Graph.tidalCollectionSync.pushAlbumFavorite(Source.TIDAL, tidalId, favorite = false) }
        }
    }

    /** Unfollow a Collection artist entry. Same contract as [toggleFavoriteAlbum]. */
    fun toggleFavoriteArtist(artist: CollectionArtist) {
        val tidalId = artist.tidalId ?: return
        viewModelScope.launch {
            runCatching { Graph.library.unfavoriteArtist(Source.TIDAL.name, tidalId) }
            runCatching { Graph.tidalCollectionSync.pushArtistFavorite(Source.TIDAL, tidalId, favorite = false) }
        }
    }
```

- [ ] **Step 6: Render the union in `CollectionScreen`**

In `CollectionScreen.kt`, add the imports `io.github.auxen.data.mergeCollectionAlbums`, `io.github.auxen.data.mergeCollectionArtists`, and collect the two new flows beside the existing `favorites`/`playlists`:

```kotlin
    val favoriteAlbumRows by viewModel.favoriteAlbums.collectAsState()
    val favoriteArtistRows by viewModel.favoriteArtists.collectAsState()
```

Add a source-filter predicate for the favorite rows next to the existing `matches(track)` (the All/Tidal/Local chips must apply to the remote half too):

```kotlin
    fun matchesSource(source: String): Boolean = when (filter) {
        "tidal" -> source == Source.TIDAL.name
        "local" -> source == Source.LOCAL.name
        else -> true
    }
```

Replace the `1 ->` (Albums) branch body:

```kotlin
            1 -> {
                val albums = mergeCollectionAlbums(
                    local = groupAlbums(filtered),
                    remote = favoriteAlbumRows.filter { matchesSource(it.source) },
                )
                if (loading && albums.isEmpty()) {
                    LoadingState()
                } else if (albums.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Album,
                        title = stringResource(R.string.empty_collection_albums_title),
                        subtitle = stringResource(R.string.empty_collection_albums_subtitle),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    ) {
                        items(albums, key = { it.key }) { album ->
                            AlbumCard(
                                title = album.title,
                                artist = album.artist,
                                artUrl = album.artUrl,
                                source = album.source,
                                onClick = { onOpenAlbum(album.toAlbumGroup()) },
                                // A remote-only saved album has no local tracks to play.
                                onPlay = if (album.tracks.isNotEmpty()) {
                                    { viewModel.playAll(album.tracks) }
                                } else {
                                    null
                                },
                                onToggleFavorite = if (album.isRemoteFavorite) {
                                    { viewModel.toggleFavoriteAlbum(album) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
```

Replace the `2 ->` (Artists) branch body:

```kotlin
            2 -> {
                val artists = mergeCollectionArtists(
                    local = groupArtists(filtered),
                    remote = favoriteArtistRows.filter { matchesSource(it.source) },
                )
                if (loading && artists.isEmpty()) {
                    LoadingState()
                } else if (artists.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Person,
                        title = stringResource(R.string.empty_collection_artists_title),
                        subtitle = stringResource(R.string.empty_collection_artists_subtitle),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(artists, key = { it.key }) { artist ->
                            ArtistRow(
                                name = artist.name,
                                subtitle = if (artist.isRemoteFavorite && artist.trackCount == 0) {
                                    "Followed on Tidal"
                                } else {
                                    "${artist.trackCount} favorited"
                                },
                                artUrl = artist.artUrl,
                                onClick = { onOpenArtist(artist.name) },
                                onToggleFavorite = if (artist.isRemoteFavorite) {
                                    { viewModel.toggleFavoriteArtist(artist) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
```

The `AlbumGroup`/`groupAlbums`/`groupArtists` imports already present in the file stay — `groupAlbums`/`groupArtists` are still called (they produce the *local* half), and `AlbumGroup` is still the `onOpenAlbum` parameter type.

**Known limitation to record, not to fix here:** tapping a remote-only saved album opens `AlbumDetailScreen`, which resolves its track list by name from the local pool and will therefore show its existing empty state (there are no local tracks). Fetching a saved album's track list from Tidal needs an album→items read plus internal-v1 id mapping — that is enrichment work, out of scope for this slice.

- [ ] **Step 7: Run tests + build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*CollectionMergeTest*' --tests '*LibraryGroupingTest*' verifyRoborazziDebug assembleDebug`
Expected: PASS; APK builds. `verifyRoborazziDebug` must still pass **unchanged**: `onToggleFavorite` defaults to null, so `ComponentScreenshotTest`'s `AlbumCard` goldens render exactly as before. If a golden does shift, that means a default leaked — fix the default rather than re-recording.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/data/CollectionMerge.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumCard.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistRow.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt \
        android/app/src/test/kotlin/io/github/auxen/data/CollectionMergeTest.kt
git commit -m "feat(android): Collection Albums/Artists = local-grouped + Tidal saved

Surfaces Tidal saved albums / followed artists in Collection even with no
local tracks (desktop CollectionView semantics), via a pure
mergeCollectionAlbums/Artists union over the favorite tables, with exact-name
dedup favouring the richer local group. Remote entries get an unfavorite
affordance (optional, defaulted params keep every other AlbumCard/ArtistRow
call site and its golden unchanged).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

---

### Task 8: Debug-screen sync button + on-device checkpoint (the slice deliverable)

Surface a manual "Sync favorites from Tidal" button + status readout in the existing `TidalOfficialDebugScreen` (mirroring the Slice 0 artist-metadata probe), then prove the whole two-way path on device. Track push is already wired into `toggleFavorite` and album/artist unfavorite into the Collection tabs, so both are exercised from real screens. The Compose debug wiring is not unit-tested (Slice 0 `TidalOfficialDebugScreen` precedent) and closes on the on-device checkpoint.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/TidalOfficialDebugScreen.kt` ("Sync favorites" button + status `Text`)
- (No new test — Compose debug UI, untested by precedent.)

**Interfaces:**
- Consumes: `PlayerViewModel.syncFavoritesFromTidal()` + `tidalCollectionStatus` (Task 6).

- [ ] **Step 1: Add the sync button + status to the debug screen**

`TidalOfficialDebugScreen` takes only `(viewModel, modifier)` and collects each status flow **off the viewModel directly** (e.g. `viewModel.tidalMetadataStatus.collectAsState()`) — nothing is threaded from a caller, so no call site changes. Follow that same pattern. Append, below the existing id-equivalence-probe section:

```kotlin
        HorizontalDivider()

        // Slice-2 collection sync: pull the user's Tidal favorites (tracks +
        // albums + artists) into the local DB. Push is already wired into the
        // real screens (track hearts, Collection album/artist entries), so this
        // button only needs to cover the pull half.
        val collectionStatus by viewModel.tidalCollectionStatus.collectAsState()
        Text("Collection sync", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { viewModel.syncFavoritesFromTidal() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sync favorites from Tidal")
        }
        collectionStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
```

(All imports used above — `HorizontalDivider`, `Button`, `Text`, `MaterialTheme`, `Modifier`, `fillMaxWidth`, `padding`, `dp`, `FontFamily`, `collectAsState`, `getValue` — are already in the file.)

- [ ] **Step 2: Full gate + build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest verifyRoborazziDebug assembleDebug`
Expected: PASS — the whole suite, the screenshot gate, and the APK. This is the same triple CI runs (`.github/workflows/android.yml`).

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/TidalOfficialDebugScreen.kt
git commit -m "feat(android): debug-screen Tidal favorites-sync button + status

Surfaces syncFavoritesFromTidal() in the official debug screen (mirrors the
Slice 0 probe), pulling tracks + albums + artists. Push is already wired into
toggleFavorite and the Collection album/artist entries. Closes Slice 2 on the
on-device checkpoint.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GxTvd8LGQvdx633hqWVEci"
```

- [ ] **Step 4: On-device checkpoint (the Slice 2 deliverable)**

Build the APK **locally** (CI has no Tidal creds): `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew assembleDebug`. Install **over an existing v2 install** (do NOT uninstall first — that is the only way to exercise `MIGRATION_2_3` on real data), then:

1. **App launches at all + migration.** The app must launch and Collection must still list your existing favorites. Two distinct failure modes hide behind "it won't start", and no earlier gate in this plan catches either (`assembleDebug` only compiles; the unit tests never call `Graph.init()`), so read the stack trace rather than assuming migration:
   - `kotlin.UninitializedPropertyAccessException: lateinit property library has not been initialized` in `Graph.init` ⇒ the `tidalCollectionSync = …` line was placed **before** `library = LibraryRepository(db)`. Move it after. See Task 6, Step 5.
   - `IllegalStateException` on first DB access ⇒ a real `MIGRATION_2_3` failure (Task 5's `MigrationTest` should have caught it first).
2. **Login + collection-id resolution.** Settings → Advanced → "Try official Tidal login (beta)" → log in. (`user.read`/`collection.read`/`collection.write` are already in `TidalOfficialAuth.SCOPE`.)
3. **Pull.** Tap "Sync favorites from Tidal". Confirm the status reads "Synced: +N track(s), +N album(s), +N artist(s)."
4. **Tracks surface.** Collection → Tracks shows your Tidal favorites (tracks you'd already played show full metadata; never-seen ones show title only until played — the self-heal).
5. **Albums/Artists surface (the decision under test).** Collection → **Albums** shows your Tidal saved albums *including ones you have no local tracks for* (title + Tidal badge, no artist/art — expected). Collection → **Artists** shows your followed artists, remote-only ones subtitled "Followed on Tidal".
6. **Filter chips.** Switch All → Tidal → Local and confirm remote-only entries appear under All/Tidal and vanish under Local.
7. **Play a synced favorite track** and confirm it streams (this validates the id-equivalence assumption: the official favorites track id == the internal-v1 stream id used by `TidalProvider.getStreamInfoById`).
8. **Push add (tracks).** On a Tidal track not yet favorited, tap the heart. In the official Tidal app (or re-run pull after clearing), confirm it now appears in your Tidal favorites.
9. **Push remove (tracks).** Unheart it; confirm it's removed on Tidal.
10. **Push remove (albums/artists).** Tap the heart on a remote-only saved album card and on a followed artist row. Each disappears from the tab; confirm in the official Tidal app that the album is no longer saved / the artist no longer followed.
11. **Idempotency.** Re-run the pull; confirm "+0 track(s), +0 album(s), +0 artist(s)" and no duplicates. Toggle-favorite a track that's already favorited on Tidal; confirm no error (409 DUPLICATE mapped to success).

Paste the status readout + the Collection Albums/Artists tab result back to close the loop. **Verify no token/refresh-token ever appears in Logcat** during the flow.

---

## Self-review (writing-plans checklist)

- **Spec coverage:** READ — `/users/{id}` with `id=me` (spec: *"Use `me`…"*) → user id (resolved by the **Foundation's** `currentUserId()`; Task 2 just delegates via `resolveCollectionId()`) → `/userCollections/{id}/relationships/{tracks|albums|artists}?include={rel}`, cursor-paginated via `links.next`+`page[cursor]`, primary identifiers carry `meta.addedAt` (Tasks 1–3). The `include` values are spec-verified: each endpoint documents *"Available options: `<the relationship itself>`"*, so no nested `albums.coverArt`/`albums.artists` — which is exactly why every favorite type is title-only and why `favorite_*.art_url` starts null. WRITE — POST/DELETE `/userCollections/{id}/relationships/{rel}` with body `{"data":[{"type","id"[,"meta.addedAt"]}]}` (schemas `UserCollections*RelationshipAdd/RemoveOperation_Payload`), 2xx-no-body success, 409 `DUPLICATE_ITEMS_IN_COLLECTION` treated idempotent (Task 4). SCHEMA — narrow `favorite_albums`/`favorite_artists` + additive `MIGRATION_2_3`, `version = 3` (Task 5). RECONCILE — additive-union merge keyed on `source:sourceId` for all three types, self-healing placeholders, optimistic push, latent hooks (`match_group_id`, `PlaylistEntity.source/tidalPlaylistId`) noted (Task 6). SURFACE — Collection Albums/Artists = local-grouped ∪ remote-favorited (Task 7). Field names verbatim from spec: `Tracks_Attributes.{title,isrc,duration,explicit}` (the canonical `TrackAttributes` from `TidalOfficialApi.kt`, REUSED not re-declared), `Albums_Attributes.title`, `Artists_Attributes.name`, `*_Resource_Identifier_Meta.addedAt`, `Users_Resource_Object.id`.
- **Placeholder scan:** every code step shows real code; every test step shows real assertions (fixtures use real spec field names + JSON:API structure). The only non-TDD steps are Task 7's Compose rendering and Task 8's Compose debug wiring, both explicitly justified by the Slice 0 `TidalOfficialDebugScreen` precedent (Task 7's *logic* — the union — is fully unit-tested as a pure function) and closed by Task 8's on-device checkpoint. No `TODO`/"similar to above" left.
- **Type consistency:** `CollectionFavorite(id,type,addedAtMillis,title,isrc)` produced by `collectionFavoritesFrom` (Task 3), consumed by `newRemoteFavorites`/`placeholderTrackFor`/`favoriteAlbumEntityFor`/`favoriteArtistEntityFor` (Task 6) — fields match, and it is ONE type across all three relationships (`title` = track title / album title / artist name). `TidalDocument.links`/`TidalResource.meta` added in Task 1, read by `collectionFavoritesFrom` (`ident.meta`) and `favoriteItems` (`doc.links?.next` → `nextCursorFrom`) — names align. Write path: `collectionWritePayload` → `CollectionWritePayload{data:[CollectionWriteItem{type,id,meta}]}` serialized (encodeDefaults=false ⇒ null meta omitted) → `collectionRelationshipWriteRequest(body)` → `writeCollectionRelationship` → `executeUnit` (`Result<Unit>`), consistent across Task 4. `resolveCollectionId()` (Task 2, a delegate to the Foundation's cached `currentUserId()`) consumed by both read (`favoriteItems`) and write (`writeFavorite`). Schema path: `FavoriteAlbumEntity`/`FavoriteArtistEntity` (Task 5) produced by `favoriteAlbumEntityFor`/`favoriteArtistEntityFor` (Task 6), persisted via `LibraryRepository.favoriteAlbum`/`favoriteArtist` (Task 6), read back via `favoriteAlbums()`/`favoriteArtists()` and consumed by `mergeCollectionAlbums`/`mergeCollectionArtists` (Task 7) — fields match. `Source.TIDAL.name` used identically in `favoriteKeys`/`FavoriteAlbumDao.keys()`/`FavoriteArtistDao.keys()` (all `source || ':' || source_id`), `newRemoteFavorites`, `placeholderTrackFor`, and the merge. `TidalCollectionSync(metadata, library, clock)` matches its `Graph` construction; `pullAllFavorites`/`pushFavorite`/`pushAlbumFavorite`/`pushArtistFavorite` signatures match the ViewModel call sites.
- **No re-declarations (the compile-error trap):** verified against the tree — `TrackAttributes`, `AlbumAttributes`, `ArtistAttributes`, `ArtworkFile`/`pickArtworkUrl`/`parseIsoDurationSeconds`/`releaseYearOf`, `TrackRelations`/`trackRelationsFrom`, `userIdFrom`/`currentUserId`, `AlbumGroup`/`ArtistGroup`/`groupAlbums`/`groupArtists` are all consumed, never re-declared. Every NEW name introduced here (`TidalLinks`, `nextCursorFrom`, `CollectionFavorite`, `CollectionItemMeta`, `parseIso8601Millis`, `collectionFavoritesFrom`, `CollectionWrite*`, `collectionWritePayload`, `FavoriteAlbumEntity`, `FavoriteArtistEntity`, `FavoriteAlbumDao`, `FavoriteArtistDao`, `MIGRATION_2_3`, `DB_NAME`, `TidalCollectionSync`, `CollectionPullSummary`, `newRemoteFavorites`, `placeholderTrackFor`, `favoriteAlbumEntityFor`, `favoriteArtistEntityFor`, `CollectionAlbum`, `CollectionArtist`, `mergeCollectionAlbums`, `mergeCollectionArtists`) has zero existing declarations in `app/src`. `TidalMetadataRepository`'s constructor is never touched — only methods are appended.
- **Every symbol new code names actually resolves (the other half of the compile-error trap):** re-declaration is only one way a symbol reference breaks the build — the mirror image is naming an EXISTING type in an EXISTING file that does not import it, which no amount of "the type exists" checking catches, because Kotlin resolves per-file. Swept every modified file for symbols its new code names: **`PlayerViewModel.kt` needs `import io.github.auxen.model.Source` added** (it imports `QueueEntry`/`SourcePriority`/`Track` from that package but not `Source`, because today's `toggleFavorite` only touches the `String` `track.source.name`) — called out in Task 6 Step 5 and Task 6/7's shared note; `CollectionScreen.kt` **already** imports `Source` (line 53) and `AlbumGroup`/`groupAlbums`/`groupArtists`, needing only `mergeCollectionAlbums`/`mergeCollectionArtists`; `AuxenApp.kt` already imports `Source`/`Track` and needs only `TidalCollectionSync`; `LibraryRepository.kt` needs the two new entity imports; the new files (`TidalCollectionSync.kt`, `data/CollectionMerge.kt`) carry complete import blocks in their code steps above.
- **`Graph.init(context)` assignment order is checked, not assumed:** `tidalCollectionSync` is specced to land **after `library = LibraryRepository(db)`** (which is two statements after `tidalMetadata = …`, past `db = AuxenDatabase.build(context)`), because it needs both. Every `Graph` field is `lateinit`, so the wrong order compiles and throws `UninitializedPropertyAccessException` at launch — and **no gate in this plan would catch it** (nothing here invokes `Graph.init()`; `assembleDebug` only compiles), so it is pinned by explicit instruction in Task 6 Step 5 and by a Global Constraint instead.
- **The migration test can actually run:** `exportSchema = false` + no `androidx.room:room-testing` + no `androidTest` source set ⇒ `MigrationTestHelper` is impossible and is NOT specced. Task 5's `MigrationTest` instead manufactures the v2 file (create v3 → drop the two new tables → `user_version = 2`, valid precisely because the migration is additive) and reopens via the production `AuxenDatabase.build()`, letting Room's own generated `validateMigration` be the assertion. Runs on the existing Robolectric + `androidx.test.core` deps, like `DaoTest`/`SearchHistoryDaoTest`.
- **Boundaries honored:** internal-v1 (`TidalAuth.kt`/`TidalProvider.kt`) untouched; streaming/search stay internal-v1; no `client_secret`; no token logging; writes/executions never unit-tested; new `TidalCollectionSync` is a plain `Graph`-held class; every gradle line carries `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`; every `git add` uses explicit pathspecs. `git status` at the end should show only the files listed across Tasks 1–8.
- **Open questions (on-device / product):**
  1. Confirm `userCollectionId == userId` (the resolve-via-`/users/me` assumption) returns 200 on device.
  2. Confirm the official favorites track id == the internal-v1 stream id (shared with Slice 1's id-equivalence probe) so synced favorites play.
  3. **The add-side of album/artist push has no UI surface yet — deliberately, and this is the one gap against "full two-way parity".** (Also stated up front as the **SCOPE BOUNDARY** bullet in "Design decisions", so a reader meets it before Task 1 rather than here.) The write layer is symmetric and tested (`addFavorite`/`removeFavorite` for any relationship, Task 4; `pushAlbumFavorite`/`pushArtistFavorite(favorite = true|false)`, Task 6), and the *remove* side is wired (Task 7). But no screen can currently offer "save this album to Tidal" for an album Tidal does not already have: `AlbumDetailScreen`/`ArtistDetailScreen` are keyed by **name**, not by a Tidal album/artist id, and a locally-grouped album carries no album id. Closing it means resolving the id at the point of action via the Foundation's `trackRelations(trackId)` → `TrackRelations.albumId/artistId` (which exists and was built for exactly this) and then deciding the UX for an inherently async heart (resolve on screen open? on tap? what does the heart show meanwhile?). That is a real UX decision, not a mechanical wiring step, so it is flagged here for the user rather than invented — it is the natural first task of the next slice.
  4. Destructive "mirror-delete" pull and an offline write-queue are deferred (documented limitations).
  5. Tapping a remote-only saved album lands on `AlbumDetailScreen`'s empty state (no local tracks to list). Fetching a saved album's tracks from Tidal is enrichment work, out of scope here.
