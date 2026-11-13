# Slice 3 — Tidal Favorite Tracks Sync (Two-Way) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pull the user's **Tidal favorite tracks** (probed live: **125**) into the local library so they appear — with art, and playable — in the Collection **Tracks** tab, and make hearting a Tidal track in-app **round-trip to Tidal** (two-way), matching the album/artist hearts shipped in Slice 2. Today the Tracks tab renders ONLY tracks favorited *in the app* (local `favorites` rows), so a user with 125 Tidal favorites sees an empty (or partial) Tracks tab. This slice closes that gap on the **internal-v1** API the rest of the app already streams and searches through (`api.tidal.com/v1`).

**This is the direct sibling of Slice 2** (`docs/plans/2026-07-15-tidal-slice2-collection-sync.md`, shipped). Read that plan for house style. Slice 3 is **materially smaller** than Slice 2 because it reuses existing infrastructure instead of adding new:

- **No new Room table, no migration.** Favorited tracks live in the **existing** `tracks` table + `favorites` join. The Tracks tab already reads `LibraryRepository.favorites()` (`data/LibraryRepository.kt:31-32`) → `FavoriteDao.favorites()` (`db/Daos.kt:63-67`: `SELECT t.* FROM tracks t JOIN favorites f ON f.track_id = t.id ORDER BY f.added_at DESC`). So a pulled favorite track just needs to be `upsert`ed into `tracks` and get a `favorites` row — then it renders and plays automatically. The DB stays at `version = 3`; Slice 3 touches no `@Entity`, no `AuxenDatabase`, no migration.
- **No new track parser.** `TidalV1PageTrack.toTrack()` (`provider/tidal/TidalArtistPage.kt:259-277`) already maps a v1 track JSON object → a fully-playable `Track` (id→sourceId, `artists[0].name`→artist [there is NO singular `artist` key — see the DTO's KDoc], `album.title`→album, `album.cover`→albumArtUrl via `tidalV1CoverUrl` @640, duration, trackNumber, `volumeNumber`→discNumber, explicit, `audioQuality`→format/quality via `tidalV1QualityOf`, `source` hardcoded `TIDAL`). It is already reused by album-detail and multi-type search. Slice 3 reuses it once more.
- **No playback change.** A `Track(source = TIDAL, sourceId = <v1 id>)` plays via the existing `auxen://tidal/<id>` → `TidalUriResolver` → `TrackResolver.resolve` → `TidalProvider.getStreamInfoById` path. Playability is free.
- **No `Graph` wiring change.** `Graph.tidalCollectionSync` was wired by Slice 2 and is already consumed by `PlayerViewModel` (`ui/PlayerViewModel.kt:555`, `:572`). Slice 3 only adds methods to the existing `TidalCollectionSync`.

**Architecture:** Additive, mirroring the Slice 2 favorites layer exactly:

- **Pure favorites parse + request URLs (unit-tested core)** — extend the existing `TidalFavorites.kt` with a `favoriteTracksFrom` projection (reusing `TidalV1PageTrack.toTrack` + `parseIso8601Millis`) and the read/write/delete URL builders for `users/{id}/favorites/tracks`. No network in the unit tests (the internal-v1 precedent: every `*From(body, json)` / `*Url(...)` is pure and fixture-tested).
- **`TidalProvider` favorite-track methods** — a paginated read (`favoriteTracks()`) and the two writes (`addFavoriteTrack`/`removeFavoriteTrack`), mirroring the shipped `favoriteAlbums()`/`addFavoriteAlbum`/`removeFavoriteAlbum` (`TidalProvider.kt:419-472`), reusing the private `get`/`post`/`delete` helpers and `FAVORITES_PAGE`/`FAVORITES_MAX`.
- **Pull + two-way push** — extend `TidalCollectionSync` with `pullFavoriteTracks()` (fetch, diff via the existing pure `newRemoteFavorites`, write each fresh favorite into the local library) and `pushTrackFavorite()` (Tidal-only, optimistic). `CollectionPullSummary` gains a `tracks` count; `pullAll()` folds tracks in. The push is wired into the **existing** `PlayerViewModel.toggleFavorite(track)` so hearting a Tidal track round-trips, while local tracks stay local-only.

**Design decisions (locked for this slice):**

- **DECIDED — pulled favorite tracks preserve the remote Tidal `created` timestamp as `added_at`.** The Tracks-tab query orders by `favorites.added_at DESC`, so a pull that stamped the wall clock would collapse all 125 favorites to "just now" and lose Tidal's favorite order. Instead, `favoriteTracksFrom` returns a small holder **`TidalFavoriteTrack(track: Track, addedAtMillis: Long?)`** carrying the parsed `Track` alongside its `created` millis, and the pull favorites each track at that timestamp (falling back to the clock only when the wrapper carried no `created`). This is a **deliberate deviation from the brief's Task-1 note** ("`favoriteTracksFrom` returns `Track`, not a bespoke DTO"): a bare `Track` cannot carry the ordering key, and preserving Tidal's order is the stated user-facing goal. The holder is not a persistence DTO (unlike Slice 2's `TidalFavoriteAlbum`/`TidalFavoriteArtist`, which back narrow tables) — it is a transient parse result whose `Track` goes straight into the existing `tracks` table. A tiny `LibraryRepository.favoriteTrack(track, addedAtMillis)` threads the explicit timestamp (the in-app heart path keeps using `setFavorite`, which stamps the wall clock — correct for a just-now action).
- **DECIDED — two-way, wired into the shared `toggleFavorite(track)`, strictly Tidal-gated.** For parity with the album/artist hearts, hearting/unhearting a track pushes to Tidal. The push runs through `pushTrackFavorite(track.source, track.sourceId, favorite)`, which **no-ops for any non-TIDAL source** (the same self-gating contract as `pushAlbumFavorite`/`pushArtistFavorite`). So a LOCAL track's heart never touches Tidal — local-track hearting stays local-only. The local write (`setFavorite`) is unchanged and runs first; the push is best-effort and never rolls the local state back (a failed write re-reconciles on the next pull).
- **Pull is an additive union (non-destructive).** A remote favorite whose `TIDAL:<id>` key is not already in `favoriteKeys()` is inserted; a local favorite is **never** deleted by a pull. Idempotent: a re-pull of an already-present favorite is skipped (`newRemoteFavorites` filters it out), so a track favorited in-app keeps its original `added_at`. A destructive "mirror-delete" mode is future work (needs tombstones) — same posture as Slice 2.
- **Guarding concurrent pulls is unchanged.** Slice 2's `syncFavoritesFromTidal` is guarded once-per-process (`collectionSynced`), sequential, and only runs when `Graph.tidal.isLoggedIn`. Folding tracks into `pullAll()` inherits that guard; no new concurrency.

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), OkHttp 4.12 (`FormBody` for the write body — already imported by `TidalProvider`), Room 2.7.1 (existing `tracks`/`favorites` tables, no schema change), Compose (Collection — unchanged), JUnit4 + Robolectric 4.14.1. `java.time` for the ISO-8601 `created` timestamp (via the existing `parseIso8601Millis`). minSdk 26 / compileSdk 35 / Media3 1.5.1.

## Global Constraints

- **Every gradle command must carry `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`.** The system default JDK breaks this project's Gradle with a cryptic `java.lang.IllegalArgumentException: 25.0.3`. Every `Run:` line below already carries the prefix — do not drop it. Never pipe gradle through `tail`/`head` and read the pipe's exit status; read the real output for `BUILD SUCCESSFUL`.
- **Never log or print `accessToken`/`refreshToken`.** Only human-readable titles/counts go to Logcat or any status string. The provider's `Authorization: Bearer $token` header is built inline in `get`/`post`/`delete` and never logged.
- **Do not unit-test live HTTP execution** (internal-v1 precedent — every `TidalProvider` suspend method is untested; its pure `*From`/`*Url` helpers are). Unit-test **pure** logic only: the favorite-tracks JSON parse and the request-URL shapes (Task 1). The one non-pure test added (Task 3, `LibraryRepositoryTest.favoriteTrack…`) is a Robolectric **in-memory Room** test — not live HTTP — matching the codebase's existing DB-test discipline (`FavoriteCollectionDaoTest`, `LibraryRepositoryTest`). The pull/push suspend orchestration stays untested (network precedent).
- **Writes never execute in tests** — assert the request **shape** (URL path/query) and the form field the payload carries (`trackId`); the suspend write wrappers themselves are untested (network).
- **Reuse, never re-declare — a duplicate declaration is a Kotlin "conflicting declarations" COMPILE ERROR.** `TidalV1PageTrack` + `TidalV1PageTrack.toTrack()` (`TidalArtistPage.kt`), `tidalV1CoverUrl` (`TidalInternalArtist.kt`, reached transitively through `toTrack`), `parseIso8601Millis` (`TidalFavorites.kt`), `newRemoteFavorites` (`TidalCollectionSync.kt`), `FavoriteDao.favorites()`/`favoriteKeys()` (`Daos.kt`), `FavoriteEntity` (`Entities.kt`), `LibraryRepository.setFavorite`/`upsert`/`favoriteKeys` (`LibraryRepository.kt`), `FAVORITES_PAGE`/`FAVORITES_MAX` (`TidalProvider.kt`), `Source`/`Track` (`model`) all already exist and are **reused, never re-declared**. Every NEW name introduced here (`TidalFavoriteTrack`, `favoriteTracksFrom`, `favoriteTracksUrl`, `favoriteTracksWriteUrl`, `favoriteTrackDeleteUrl`, `favoriteTracks`, `addFavoriteTrack`, `removeFavoriteTrack`, `favoriteTrack`, `pullFavoriteTracks`, `pushTrackFavorite`) was grep-verified to have **zero** existing declarations in `app/src`.
- **When new code in an EXISTING file names a type that file does not import, ADD THE IMPORT — Kotlin resolves per-file.** The one instance in this slice: `TidalFavorites.kt` does **not** import `io.github.auxen.model.Track` today (its existing DTOs are self-contained), so Task 1 — which declares `TidalFavoriteTrack(val track: Track, …)` — must add `import io.github.auxen.model.Track`. `TidalProvider.kt` **already** imports `Track` and `FormBody`; `LibraryRepository.kt` **already** imports `FavoriteEntity`; `PlayerViewModel.kt` **already** imports `Source` (Slice 2 added it). No other new imports in production files.
- **`CollectionPullSummary` is a data class with three required fields after this slice** (`albums`, `artists`, `tracks`). Its only construction site is `pullAll()` (`TidalCollectionSync.kt:45-46`) and its only read site is the status string in `PlayerViewModel.syncFavoritesFromTidal` (`PlayerViewModel.kt:555-558`) — both updated in Task 3. No test constructs it (grep-verified). Adding the field therefore breaks no existing test.
- **Roborazzi goldens are unaffected.** This slice adds **no** Compose component and **no** new parameter to any component. The Tracks tab already renders `AuxenTrackRow` (which has goldens) off `viewModel.favorites`; a pulled favorite track flows through the unchanged row. The only UI-adjacent change is the status **string** in `syncFavoritesFromTidal`. No `*UiTest`/`ComponentScreenshotTest` golden re-records.
- **Internal-v1 auth (`TidalAuth.kt`) and streaming are untouched.** A favorited Tidal track still streams via `TidalProvider.getStreamInfoById(sourceId)`. `TidalProvider` is *extended* (three methods), never rewritten. Confirm with `git status`/`git diff` at the end.
- **Git: explicit pathspecs only.** Never `git add -A` / `git add .` / `git commit -a` — the working tree carries unrelated untracked files (`.playwright-mcp/`, other `docs/plans/*`) that must never be committed.
- Every code step below shows the actual code. No `TODO`/"add error handling"/"similar to above" placeholders.

---

### Task 1: Pure favorite-tracks parse + request URLs (`TidalFavorites.kt`)

The unit-tested core. `GET users/{userId}/favorites/tracks` returns the same paginated wrapper Slice 2's albums/artists used — `{ "items": [ { "created", "item": {<track>} } ], "totalNumberOfItems": N }` (probed live: total = 125) — where each `item` is a full v1 track object (`id`/`title`/`duration`/`trackNumber`/`volumeNumber`/`artists[]`/`album{id,title,cover}`/`explicit`/`audioQuality`), exactly the shape `TidalV1PageTrack` already models (`ignoreUnknownKeys` drops the extras like `isrc` and the singular `artist`). Project each page **purely** to `TidalFavoriteTrack(track, addedAtMillis)`, reusing `TidalV1PageTrack.toTrack()` (no new track parser) and `parseIso8601Millis` (`created`). Writes target `.../favorites/tracks` (POST form `trackId`) and `.../favorites/tracks/{id}` (DELETE) — the tidalapi shapes (`tidalapi/user.py` `Favorites.add_track`/`remove_track`), so their URL builders live here too and are shape-tested.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalFavorites.kt`
- Modify: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalFavoritesTest.kt`

**Interfaces:**
- Consumes: `TidalV1PageTrack` + `TidalV1PageTrack.toTrack()` (`TidalArtistPage.kt`, same package — REUSED, `internal` and reachable in-module), `parseIso8601Millis` (`TidalFavorites.kt`, same file — REUSED), `tidalV1CoverUrl` (reached transitively through `toTrack`). All REUSED, never re-declared.
- Produces: `data class TidalFavoriteTrack(val track: Track, val addedAtMillis: Long?)`; `fun favoriteTracksFrom(String, Json): List<TidalFavoriteTrack>`; `fun favoriteTracksUrl(apiBase, userId, countryCode, limit, offset): HttpUrl`; `fun favoriteTracksWriteUrl(apiBase, userId, countryCode): HttpUrl`; `fun favoriteTrackDeleteUrl(apiBase, userId, trackId, countryCode): HttpUrl`.

- [ ] **Step 1: Write the failing tests**

Add to `TidalFavoritesTest.kt` — first the import (below the existing `import kotlinx.serialization.json.Json`):

```kotlin
import io.github.auxen.model.Source
```

then, inside the `TidalFavoritesTest` class (after the existing `artistsPage` fixture), a tracks fixture and its tests:

```kotlin
    private val tracksPage = """
        {
          "items": [
            { "created": "2023-05-01T12:00:00.000+0000",
              "item": { "id": 12345, "title": "Nightcall", "duration": 258.0,
                        "trackNumber": 1, "volumeNumber": 1, "explicit": false,
                        "audioQuality": "LOSSLESS",
                        "artists": [ { "id": 11, "name": "Kavinsky" } ],
                        "album": { "id": 75413016, "title": "OutRun", "cover": "aa-bb-cc" } } },
            { "created": null,
              "item": { "id": 222, "title": "No Album", "artists": [], "album": null } }
          ],
          "totalNumberOfItems": 2
        }
    """.trimIndent()

    @Test
    fun `favoriteTracksFrom projects playable tracks with art, artist, quality and addedAt`() {
        val tracks = favoriteTracksFrom(tracksPage, json)
        assertEquals(2, tracks.size)
        val first = tracks[0].track
        assertEquals("12345", first.sourceId)
        assertEquals("Nightcall", first.title)
        assertEquals("Kavinsky", first.artist) // artists[0].name — there is NO singular `artist` key
        assertEquals("OutRun", first.album)
        assertEquals(Source.TIDAL, first.source)
        assertEquals("FLAC", first.format) // LOSSLESS -> FLAC via toTrack's quality map
        assertEquals("https://resources.tidal.com/images/aa/bb/cc/640x640.jpg", first.albumArtUrl)
        assertEquals(1682942400000L, tracks[0].addedAtMillis) // 2023-05-01T12:00:00Z
    }

    @Test
    fun `favoriteTracksFrom tolerates a missing album, empty artists and null created`() {
        val second = favoriteTracksFrom(tracksPage, json)[1]
        assertEquals("222", second.track.sourceId)
        assertEquals("", second.track.artist) // empty artists -> artists.firstOrNull()?.name.orEmpty()
        assertNull(second.track.album) // null album -> album?.title
        assertNull(second.track.albumArtUrl) // null album -> tidalV1CoverUrl(null)
        assertNull(second.addedAtMillis) // null created
    }

    @Test
    fun `favoriteTracksFrom returns empty list on garbage or an error envelope`() {
        assertEquals(emptyList<TidalFavoriteTrack>(), favoriteTracksFrom("not json", json))
        assertEquals(emptyList<TidalFavoriteTrack>(), favoriteTracksFrom("{\"status\":404}", json))
    }

    @Test
    fun `favorite tracks read URL is paged and country-scoped, most-recent-first`() {
        val url = favoriteTracksUrl("https://api.tidal.com/v1", "42", "US", limit = 50, offset = 100)
        assertEquals("/v1/users/42/favorites/tracks", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("100", url.queryParameter("offset"))
        assertEquals("DATE", url.queryParameter("order"))
        assertEquals("DESC", url.queryParameter("orderDirection"))
        assertEquals("US", url.queryParameter("countryCode"))
    }

    @Test
    fun `favorite track write URL and delete URL target the tracks relationship`() {
        val add = favoriteTracksWriteUrl("https://api.tidal.com/v1", "42", "US")
        assertEquals("/v1/users/42/favorites/tracks", add.encodedPath)
        assertEquals("US", add.queryParameter("countryCode"))

        val del = favoriteTrackDeleteUrl("https://api.tidal.com/v1", "42", "12345", "US")
        assertEquals("/v1/users/42/favorites/tracks/12345", del.encodedPath)
        assertEquals("US", del.queryParameter("countryCode"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalFavoritesTest*'`
Expected: FAIL — the whole `TidalFavoritesTest` class fails to **compile**: `favoriteTracksFrom`, `favoriteTracksUrl`, `favoriteTracksWriteUrl`, `favoriteTrackDeleteUrl`, and `TidalFavoriteTrack` are unresolved references (they don't exist in `TidalFavorites.kt` yet). A compile-error RED is expected here because the new tests share the existing green class file.

- [ ] **Step 3: Implement the additions in `TidalFavorites.kt`**

Add the import (in the existing import block, after `import kotlinx.serialization.json.Json`):

```kotlin
import io.github.auxen.model.Track
```

Add the holder right after the existing `TidalFavoriteArtist` data class:

```kotlin
/**
 * A Tidal favorite TRACK, projected to a fully-playable [Track] (via
 * [TidalV1PageTrack.toTrack]) paired with its remote `created` millis. Unlike
 * the album/artist favorites — which back narrow local tables and so get bespoke
 * persistence DTOs — a favorite track reuses the EXISTING `tracks`+`favorites`
 * tables, so the projection IS a [Track]; [addedAtMillis] rides alongside so the
 * pull can favorite it at Tidal's own timestamp (the Tracks tab orders by
 * added_at DESC). This is a transient parse result, not a stored row.
 */
data class TidalFavoriteTrack(val track: Track, val addedAtMillis: Long?)
```

Add the response DTOs beside the existing private `TidalV1FavoriteArtistsResponse` (reusing `TidalV1PageTrack` verbatim as the `item` — it already models every field `toTrack` reads):

```kotlin
@Serializable
private data class TidalV1FavoriteTrackItem(val created: String? = null, val item: TidalV1PageTrack? = null)

@Serializable
private data class TidalV1FavoriteTracksResponse(
    val items: List<TidalV1FavoriteTrackItem> = emptyList(),
    val totalNumberOfItems: Int = 0,
)
```

Add the parse right after `favoriteArtistsFrom`:

```kotlin
/**
 * Pure: one `favorites/tracks` page body → [TidalFavoriteTrack]s. Reuses the
 * existing [TidalV1PageTrack.toTrack] projection (artists[0].name, album cover
 * @640, quality, source = TIDAL) — NO new track parser — and pairs each with its
 * remote `created` millis. Same degrade-to-empty discipline as
 * [favoriteAlbumsFrom]: a malformed body / v1 error envelope yields an empty
 * list, never a crash.
 */
fun favoriteTracksFrom(body: String, json: Json): List<TidalFavoriteTrack> = runCatching {
    json.decodeFromString<TidalV1FavoriteTracksResponse>(body).items.mapNotNull { wrapper ->
        val track = wrapper.item?.toTrack() ?: return@mapNotNull null
        TidalFavoriteTrack(track = track, addedAtMillis = parseIso8601Millis(wrapper.created))
    }
}.getOrDefault(emptyList())
```

Add the URL builders at the end of the file (after `favoriteArtistDeleteUrl`):

```kotlin
/** `GET {apiBase}/users/{userId}/favorites/tracks?limit&offset&order=DATE&orderDirection=DESC&countryCode`. */
fun favoriteTracksUrl(apiBase: String, userId: String, countryCode: String, limit: Int, offset: Int): HttpUrl =
    "$apiBase/users/$userId/favorites/tracks".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("order", "DATE")
        .addQueryParameter("orderDirection", "DESC")
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `POST {apiBase}/users/{userId}/favorites/tracks?countryCode` (form body `trackId=<id>`). */
fun favoriteTracksWriteUrl(apiBase: String, userId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/tracks".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `DELETE {apiBase}/users/{userId}/favorites/tracks/{trackId}?countryCode`. */
fun favoriteTrackDeleteUrl(apiBase: String, userId: String, trackId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/tracks/$trackId".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalFavoritesTest*'`
Expected: PASS (the pre-existing album/artist tests keep passing; the five new track tests pass).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalFavorites.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalFavoritesTest.kt
git commit -m "feat(android): pure Tidal favorite-tracks parse + request URLs

Extends TidalFavorites.kt with favoriteTracksFrom — projecting the internal-v1
favorites/tracks wrapper to TidalFavoriteTrack(track, addedAtMillis) by reusing
the existing TidalV1PageTrack.toTrack projection and parseIso8601Millis (no new
track parser) — and the read/write/delete URL builders for
users/{id}/favorites/tracks. Pure and unit-tested against the probed wrapper
shape; no network.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 2: `TidalProvider` — read + write favorite tracks

Wire the pure layer to the network. Mirrors the shipped `favoriteAlbums()`/`addFavoriteAlbum`/`removeFavoriteAlbum` (`TidalProvider.kt:419-472`) exactly: the paginated read reuses `FAVORITES_PAGE`/`FAVORITES_MAX` and the private `get`; the two writes reuse the private `post`/`delete` and the `userId` captured at session bootstrap (`restoreSession()`), with the POST form field `trackId` (the exact field tidalapi sends — `tidalapi/user.py` `Favorites.add_track` uses `data={"trackId": …}`).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt`

**Interfaces:**
- Consumes: `favoriteTracksUrl`/`favoriteTracksWriteUrl`/`favoriteTrackDeleteUrl`/`favoriteTracksFrom` (Task 1); the existing private `validToken()`/`get()`/`post()`/`delete()`, `userId`, `countryCode`, `json`, `API_BASE`, `FAVORITES_PAGE`, `FAVORITES_MAX`. No new imports (`FormBody` and `Track` are already imported).
- Produces: `favoriteTracks(): List<TidalFavoriteTrack>`; `addFavoriteTrack(String): Boolean`; `removeFavoriteTrack(String): Boolean`. (Task 1's parse/URL tests cover the pure surface these call; the network methods are untested by precedent — this task's gate is `assembleDebug` + Task 1 green.)

- [ ] **Step 1: Add the paginated read + the two writes**

In `TidalProvider.kt`, immediately after `removeFavoriteArtist` (`:483-489`) and before the `// Internals` region, add:

```kotlin
    /**
     * The user's Tidal favorite tracks, paginated — the internal-v1 port of the
     * desktop's `session.user.favorites.tracks()`. Each is a fully-playable
     * [Track] (via [TidalV1PageTrack.toTrack]) paired with its remote `created`
     * millis. Metadata only: resolves no stream. Same not-logged-in / degrade
     * contract as [favoriteAlbums] (a missing collection degrades the Tracks tab,
     * never breaks it).
     */
    suspend fun favoriteTracks(): List<TidalFavoriteTrack> {
        val token = validToken() ?: return emptyList()
        val uid = userId ?: return emptyList()
        val all = mutableListOf<TidalFavoriteTrack>()
        var offset = 0
        while (offset < FAVORITES_MAX) {
            val url = favoriteTracksUrl(API_BASE, uid, countryCode, FAVORITES_PAGE, offset)
            val body = runCatching { get(url.toString(), token) }.getOrNull() ?: break
            val page = favoriteTracksFrom(body, json)
            all += page
            if (page.size < FAVORITES_PAGE) break
            offset += FAVORITES_PAGE
        }
        return all
    }

    /** Save [trackId] to the user's Tidal favorite tracks. Best-effort — false on any failure. */
    suspend fun addFavoriteTrack(trackId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteTracksWriteUrl(API_BASE, uid, countryCode)
        val form = FormBody.Builder().add("trackId", trackId).build()
        return runCatching { post(url.toString(), form, token) }.isSuccess
    }

    /** Remove [trackId] from the user's Tidal favorite tracks. Best-effort. */
    suspend fun removeFavoriteTrack(trackId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteTrackDeleteUrl(API_BASE, uid, trackId, countryCode)
        return runCatching { delete(url.toString(), token) }.isSuccess
    }
```

- [ ] **Step 2: Build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalFavoritesTest*' --tests '*TidalProvider*' assembleDebug`
Expected: PASS; APK builds. (No new pure logic in this task — the read/writes are network; Task 1's tests cover the pure surface they call.)

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt
git commit -m "feat(android): TidalProvider reads/writes Tidal favorite tracks

Adds paginated favoriteTracks() (reusing FAVORITES_PAGE/MAX and the userId
captured at /sessions bootstrap) and addFavoriteTrack()/removeFavoriteTrack()
(POST form trackId, DELETE by id) over internal-v1 users/{id}/favorites/tracks,
mirroring the shipped album/artist favorites and reusing the private
get/post/delete helpers. Auth and streaming untouched; tokens never logged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 3: Pull + two-way push — `TidalCollectionSync`, `LibraryRepository`, `PlayerViewModel`

Tie the favorite-track read/write layer to the local DB and the UI. `pullFavoriteTracks()` fetches, diffs via the **existing pure** `newRemoteFavorites`, and writes each fresh favorite into the `tracks`+`favorites` tables at its remote `added_at` through a thin `LibraryRepository.favoriteTrack(track, addedAtMillis)`. `CollectionPullSummary` gains a `tracks` count; `pullAll()` folds tracks in; the status string reports them. `pushTrackFavorite()` (Tidal-only, optimistic) is wired into the **existing** `PlayerViewModel.toggleFavorite(track)` so a Tidal heart round-trips.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt` (add `favoriteTrack`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt` (`CollectionPullSummary.tracks`, `pullFavoriteTracks`, `pullAll`, `pushTrackFavorite`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (`toggleFavorite` push wiring, status string)
- Modify: `android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt` (add the added_at-ordering test)

**Interfaces:**
- Consumes: `TidalProvider.favoriteTracks()`/`addFavoriteTrack`/`removeFavoriteTrack` (Task 2); `TidalFavoriteTrack` (Task 1); `newRemoteFavorites` (`TidalCollectionSync.kt`, REUSED); `LibraryRepository.favoriteKeys()`/`upsert`/`FavoriteDao.insert` + `FavoriteEntity` (REUSED); `Source`, `Graph.tidalCollectionSync` (already wired).
- Produces: `LibraryRepository.favoriteTrack(track, addedAtMillis)`; `TidalCollectionSync.pullFavoriteTracks(): Int`; `TidalCollectionSync.pushTrackFavorite(source, sourceId, favorite)`; `CollectionPullSummary(albums, artists, tracks)`; the two-way `PlayerViewModel.toggleFavorite`.

- [ ] **Step 1: Write the failing test for the new repository logic**

Add to `LibraryRepositoryTest.kt` (inside the class, after `setFavoriteUpsertsAndToggles`):

```kotlin
    @Test
    fun favoriteTrackOrdersByRemoteAddedAtNotThePullClock() = runBlocking {
        // A pulled Tidal favorite is favorited at its remote `created` millis, not
        // the pull clock (`now` = 1000). Two favorites pulled under the SAME clock
        // but different remote added_at must come back in added_at DESC order
        // (the Tracks-tab query), proving favoriteTrack threads the explicit
        // timestamp instead of stamping clock() like setFavorite does.
        repo.favoriteTrack(tidalTrack, addedAtMillis = 100)
        repo.favoriteTrack(tidalTrack.copy(sourceId = "100", title = "Walk"), addedAtMillis = 300)
        assertEquals(setOf("TIDAL:99", "TIDAL:100"), repo.favoriteKeys().first())
        assertEquals(listOf("Walk", "Everlong"), repo.favorites().first().map { it.title })
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*LibraryRepositoryTest*'`
Expected: FAIL — the `LibraryRepositoryTest` class fails to **compile**: `repo.favoriteTrack(...)` is an unresolved reference (`LibraryRepository.favoriteTrack` does not exist yet).

- [ ] **Step 3: Add `LibraryRepository.favoriteTrack`**

In `LibraryRepository.kt`, add immediately after `setFavorite` (`:38-45`) — `FavoriteEntity` is already imported (`:6`):

```kotlin
    /**
     * Favorite [track] at an EXPLICIT [addedAtMillis] — the remote Tidal `created`
     * timestamp for a pulled favorite — so the Tracks tab (ORDER BY added_at DESC)
     * preserves Tidal's own favorite order. Upserts the track first (preserving
     * its id/added_at/play_count on conflict), then inserts the favorite row.
     * Idempotent: a re-pull REPLACEs the favorite row (FavoriteDao.insert is
     * OnConflictStrategy.REPLACE). The in-app heart path stays on [setFavorite],
     * which stamps the wall clock (correct for a just-now action).
     */
    suspend fun favoriteTrack(track: Track, addedAtMillis: Long) {
        val id = upsert(track)
        db.favoriteDao().insert(FavoriteEntity(id, track.matchGroupId, addedAtMillis))
    }
```

- [ ] **Step 4: Extend `TidalCollectionSync` — pull tracks, fold into the summary, push**

In `TidalCollectionSync.kt`, change the summary (`:10`) to carry a `tracks` count:

```kotlin
/** What one full pull added locally, per favorite type. */
data class CollectionPullSummary(val albums: Int, val artists: Int, val tracks: Int)
```

Add `pullFavoriteTracks()` after `pullFavoriteArtists()` (`:37-42`):

```kotlin
    /**
     * Pull Tidal favorite tracks into the EXISTING `tracks`+`favorites` tables
     * (additive union: never deletes a local favorite). Reuses the pure
     * [newRemoteFavorites] set-difference (keyed `TIDAL:<sourceId>`), then
     * favorites each fresh track at its remote `created` millis (falling back to
     * the clock only when the wrapper carried none). Returns the number newly
     * added; a re-pull of an already-favorited track is a no-op.
     */
    suspend fun pullFavoriteTracks(): Int {
        val remote = tidal.favoriteTracks()
        val fresh = newRemoteFavorites(remote, library.favoriteKeys().first()) { it.track.sourceId }
        fresh.forEach { library.favoriteTrack(it.track, it.addedAtMillis ?: clock()) }
        return fresh.size
    }
```

Change `pullAll()` (`:44-46`) to fold tracks in:

```kotlin
    /** One pull of all three favorite types. */
    suspend fun pullAll(): CollectionPullSummary = CollectionPullSummary(
        albums = pullFavoriteAlbums(),
        artists = pullFavoriteArtists(),
        tracks = pullFavoriteTracks(),
    )
```

Add `pushTrackFavorite()` after `pushArtistFavorite()` (`:58-62`):

```kotlin
    /**
     * Push a track favorite toggle to Tidal. Same optimistic, Tidal-only contract
     * as [pushAlbumFavorite]: the caller has already written the local favorite; a
     * failure here is not rolled back, and a LOCAL-source track is a no-op (it is
     * not on Tidal), so in-app hearting of a local track stays local-only.
     */
    suspend fun pushTrackFavorite(source: Source, sourceId: String, favorite: Boolean) {
        if (source != Source.TIDAL) return
        if (favorite) tidal.addFavoriteTrack(sourceId) else tidal.removeFavoriteTrack(sourceId)
    }
```

- [ ] **Step 5: Wire the two-way push + update the status string in `PlayerViewModel`**

In `PlayerViewModel.kt`, replace `toggleFavorite` (`:536-541`) — `Source` is already imported (`:30`):

```kotlin
    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            val key = "${track.source.name}:${track.sourceId}"
            val favorite = key !in favoriteKeys.value
            runCatching { Graph.library.setFavorite(track, favorite) }
            // Two-way: a TIDAL track's heart round-trips to the user's Tidal
            // favorites (best-effort, optimistic). pushTrackFavorite no-ops for a
            // non-TIDAL source, so local-track hearting stays local-only.
            runCatching { Graph.tidalCollectionSync.pushTrackFavorite(track.source, track.sourceId, favorite) }
        }
    }
```

and, in `syncFavoritesFromTidal` (`:555-558`), extend the success status string to report tracks:

```kotlin
            _tidalCollectionStatus.value = runCatching { Graph.tidalCollectionSync.pullAll() }.fold(
                onSuccess = { "Synced +${it.albums} album(s), +${it.artists} artist(s), +${it.tracks} track(s) from Tidal." },
                onFailure = { collectionSynced = false; "Favorites sync failed: ${it.message}" },
            )
```

- [ ] **Step 6: Run tests + build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*LibraryRepositoryTest*' --tests '*TidalCollectionSyncTest*' --tests '*TidalFavoritesTest*' assembleDebug`
Expected: PASS; APK builds. `TidalCollectionSyncTest` (the pure `newRemoteFavorites`/`favoriteAlbumEntityFor`/`favoriteArtistEntityFor` tests) keeps passing — it constructs no `CollectionPullSummary`, so the new `tracks` field does not touch it. `LibraryRepositoryTest`'s new added_at-ordering test passes.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt
git commit -m "feat(android): pull Tidal favorite tracks + two-way heart round-trip

TidalCollectionSync.pullFavoriteTracks pulls Tidal favorite tracks into the
existing tracks+favorites tables (additive union; reuses newRemoteFavorites;
favorites each at its remote created millis so the Tracks tab preserves Tidal's
order via new LibraryRepository.favoriteTrack). CollectionPullSummary gains a
tracks count, folded into pullAll and the sync status string. Hearting a TIDAL
track in PlayerViewModel.toggleFavorite now round-trips via pushTrackFavorite
(Tidal-only; local tracks stay local-only).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 4: On-device checkpoint (gates completion)

No production code. Build a local APK (CI runners have no Tidal client id, so on-device Tidal testing **must** use a locally-built APK), install on a signed-in device, and verify the feature end-to-end. Completion of this slice is gated on this checkpoint passing.

- [ ] **Step 1: Build a labelled local APK**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew assembleDebug`
Then copy the APK out of `build/outputs` (which is unsafe to hand out while other agents build) to a stable name carrying the commit SHA, and verify it with `aapt2 dump badging` before install (confirm `package: name='io.github.auxen'` and the expected `versionName`). Install on a device already signed in to Tidal.

- [ ] **Step 2: Verify the pull**

Open Collection. The auto-pull-on-open fires (guarded, once per process, only when logged in). Confirm:
  - the sync status reads **"Synced +N album(s), +N artist(s), +N track(s) from Tidal."** with a non-zero track count (~125 on the probed account);
  - the **Tracks** tab now lists the Tidal favorite tracks, each **with album art**;
  - the **Tidal** collection filter shows them and the **Local** filter does not (they carry `source = TIDAL`);
  - tapping one **plays** (it streams via the unchanged `getStreamInfoById` path).

- [ ] **Step 3: Verify the two-way push**

  - In-app, **unheart** one Tidal favorite track. Confirm it disappears from the local Tracks tab, and — in the official Tidal app (after a refresh) — that track is **removed from the user's favorites**.
  - In-app, **heart** a Tidal track that was not previously favorited (e.g. from Search or an album). Confirm it appears in the official Tidal app's favorites.
  - Heart/unheart a **LOCAL** track and confirm nothing is sent to Tidal (it stays local-only).

- [ ] **Step 4: Verify idempotency + no secret leakage**

  - Tap the manual **Sync from Tidal** affordance (force = true). Confirm a re-sync reports **+0 track(s)** (additive union: no duplicates, original `added_at` preserved).
  - Scan Logcat during the whole session and confirm **no `accessToken`/`refreshToken`** appears anywhere.

- [ ] **Step 5: Final tree check**

Run `git status` and `git diff --stat` and confirm only the six files across Tasks 1–3 changed (`TidalFavorites.kt` + its test, `TidalProvider.kt`, `TidalCollectionSync.kt`, `LibraryRepository.kt` + its test, `PlayerViewModel.kt`) — no `@Entity`/`AuxenDatabase`/migration/Compose-component/`Graph` change, confirming the "no new table, no schema change, no golden re-record" claims.

---

## Self-review checklist

- **No new table / migration / schema change.** DB stays `version = 3`. Favorite tracks reuse `tracks`+`favorites`. `Entities.kt`/`AuxenDatabase.kt`/`Daos.kt` are untouched (Task 4 Step 5 asserts it).
- **No new track parser.** `favoriteTracksFrom` reuses `TidalV1PageTrack.toTrack()` (`internal`, same module) and `parseIso8601Millis`. `tidalV1CoverUrl` is reached transitively through `toTrack` — never re-declared.
- **The `added_at` decision is explicit and tested.** `TidalFavoriteTrack` carries the remote `created`; `LibraryRepository.favoriteTrack(track, addedAtMillis)` threads it; `LibraryRepositoryTest.favoriteTrackOrdersByRemoteAddedAtNotThePullClock` proves the Tracks-tab order follows Tidal's, not the pull clock.
- **The two-way push is strictly Tidal-gated.** `pushTrackFavorite` returns early for non-TIDAL; `toggleFavorite` passes `track.source`, so LOCAL hearts never touch Tidal. Local `setFavorite` runs first and unchanged; the push is best-effort (`runCatching`).
- **`CollectionPullSummary` change is complete.** Field added; the sole construction (`pullAll`) and sole read (`syncFavoritesFromTidal` status string) both updated; no test constructs it (grep-verified), so nothing else breaks.
- **Every new name is collision-free** (grep-verified zero existing declarations): `TidalFavoriteTrack`, `favoriteTracksFrom`, `favoriteTracksUrl`, `favoriteTracksWriteUrl`, `favoriteTrackDeleteUrl`, `favoriteTracks`, `addFavoriteTrack`, `removeFavoriteTrack`, `favoriteTrack`, `pullFavoriteTracks`, `pushTrackFavorite`.
- **Imports added where needed:** `TidalFavorites.kt` gains `import io.github.auxen.model.Track`; its test gains `import io.github.auxen.model.Source`. `TidalProvider` (`FormBody`/`Track`), `LibraryRepository` (`FavoriteEntity`), `PlayerViewModel` (`Source`) already import what the new code names.
- **Unit tests are pure / DB-only, never live HTTP.** Task 1 tests the parse + URL shapes; Task 3's one test is in-memory Room. The provider reads/writes and the sync orchestration are untested (internal-v1 precedent).
- **Roborazzi goldens unaffected** — no Compose component or parameter added; the status string is not a component.
- **Every gradle line carries `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`; commits use explicit pathspecs; tokens never logged.**
- **Scope:** 3 code tasks + 1 on-device checkpoint — within the brief's ~4-task budget.
