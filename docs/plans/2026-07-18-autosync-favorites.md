# Auto-sync Tidal favorites (mirror pull that reflects removals) on app open/resume

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This feature is **DELETE-CAPABLE** — data-loss safety is the top concern; read the Global Constraints before writing any code.

**Goal:** Two-way favoriting already works (favorite in Auxen -> appears in Tidal; unfavorite in Auxen -> removed in Tidal). But a change made **in the Tidal app** (e.g. un-favoriting a track there) only reflects in Auxen after the user presses the manual **Sync** button. Make it automatic. Two parts:

- **(A) Make the pull a MIRROR** so Tidal-side REMOVALS propagate. Today the three favorite pulls (`pullFavoriteAlbums`/`pullFavoriteArtists`/`pullFavoriteTracks`) are **additive-only**: they ADD remote-not-local favorites but never REMOVE a local favorite gone from Tidal. Extend them to also REMOVE local **TIDAL-source** favorites no longer present remotely. A LOCAL-source favorite is **never** deleted by a Tidal pull.
- **(B) Auto-trigger the pull on app open + resume** (the user's chosen trigger over background/WorkManager), **throttled**. Replace the once-per-process `collectionSynced` guard with a **time-throttle**, add a `MainActivity.onResume` trigger, and keep the existing Collection-open `LaunchedEffect` and the manual Sync button.

**This is the direct sibling of Slice 3** (`docs/plans/2026-07-18-tidal-favorite-tracks.md`, shipped). Read that plan for house style. This change is materially smaller: it reuses the entire favorites/collection-sync layer already in place and adds no new table, no migration, no Compose component.

## GROUND TRUTH — verified against current source (2026-07-18)

The brief's ground-truth section predates Slice 3+; several cited signatures/line numbers have drifted. The **verified** state that this plan builds on:

- **The three favorite reads on `TidalProvider` return `List<X>` and degrade to `emptyList()` on ANY failure** — `favoriteAlbums()` (`TidalProvider.kt:419`), `favoriteArtists()` (`:441`), `favoriteTracks()` (`:499`). Each does `validToken() ?: return emptyList()`, `userId ?: return emptyList()`, then a paginated loop `val body = runCatching { get(...) }.getOrNull() ?: break`. **An empty result is therefore AMBIGUOUS** ("genuinely 0 favorites" vs "the fetch failed") — the crux of the safety design below.
- **The three pulls are additive-only** — `TidalCollectionSync.pullFavoriteAlbums/Artists/Tracks` (`TidalCollectionSync.kt:29`, `:37`, `:52`) each do `val remote = tidal.favoriteX(); val fresh = newRemoteFavorites(remote, localKeys) { … }; fresh.forEach { add }` and `return fresh.size` (an `Int`). `newRemoteFavorites` (`:132`) returns remote-not-already-local; nothing is ever deleted. **Grep-verified: the sole caller of each pull is `pullAll()` (`:91-96`); the sole caller of each provider read is its pull.**
- **`CollectionPullSummary` already has FOUR fields** — `data class CollectionPullSummary(val albums: Int, val artists: Int, val tracks: Int, val playlists: Int)` (`:10`). (The brief described a 3-field Slice-2 shape.) Its only construction is `pullAll()` (`:91`); its only read is the status string in `PlayerViewModel.syncFavoritesFromTidal` (`:635-638`). **No test constructs it** (grep-verified).
- **Delete accessors that EXIST:** `LibraryRepository.unfavoriteAlbum(source, sourceId)` (`LibraryRepository.kt:191`) -> `favoriteAlbumDao().delete(source, sourceId)`; `unfavoriteArtist(source, sourceId)` (`:202`). Key reads: `favoriteKeys()` (tracks, `:36`), `favoriteAlbumKeys()` (`:186`), `favoriteArtistKeys()` (`:197`) — all `Flow<Set<String>>` of `"SOURCE:sourceId"`.
- **Tracks have NO delete-by-key accessor.** A favorite track lives in `favorites` (PK `track_id`) joined to `tracks`. `TrackDao.bySourceId(source, sourceId)` (`Daos.kt:22-23`) resolves the track id; `FavoriteDao.delete(trackId)` (`Daos.kt:57-58`, `DELETE FROM favorites WHERE track_id`) removes only the favorite row. **This plan ADDS `LibraryRepository.unfavoriteTrack(source, sourceId)`** as the track analogue of `unfavoriteAlbum`/`unfavoriteArtist`.
- **`syncFavoritesFromTidal(force)` is at `PlayerViewModel.kt:629`** (the brief's `:629` is correct), guarded once-per-process by `private var collectionSynced` (`:152`) + `Graph.tidal.isLoggedIn`. Called from `CollectionScreen.kt:106` (`LaunchedEffect(Unit)`, `force = false`) and the manual Sync button (`CollectionScreen.kt:159`, `force = true`). The success status string at `:636` already reports 4 counts (`+albums, +artists, +tracks, +playlists`).
- **App lifecycle root:** `MainActivity : ComponentActivity()` (`MainActivity.kt:98`), `onCreate` (`:103`), `setContent` (`:113`). The VM is held as `private val viewModel: PlayerViewModel by viewModels()` (`:100`) and passed into `MainScreen(viewModel, …)` (`:121`). **There is NO `onResume` override today** — this plan ADDS one, reaching the same `viewModel` property.
- **`SystemClock.elapsedRealtime()` has strong precedent** in this codebase for interval timing (`SleepTimer.kt:57/73/106`, `PlaybackService.kt:508`) — the throttle uses it (monotonic, immune to wall-clock jumps), not `System.currentTimeMillis()`.
- **`PlayerViewModel` has a `private companion object`** (`:1079`) for the new `THROTTLE_MS`, and imports `android.app.Application` + `android.util.Log` but **not** `android.os.SystemClock` — the throttle adds that import.

## THE CRITICAL SAFETY DESIGN — mirror-delete MUST NOT wipe favorites on a failed fetch

A naive mirror ("delete every local Tidal favorite not in `remote`") would, on any fetch that returns `emptyList()`, **DELETE EVERY TIDAL FAVORITE.** This is the #1 thing to prevent. Guards, all baked into a **pure, unit-tested** core:

1. **Distinguish a TRUSTWORTHY fetch from an untrustworthy one at the type level — including an HTTP 200 whose body is not a favorites page.** Change `favoriteAlbums()/favoriteArtists()/favoriteTracks()` to return `List<X>?`. A page is trustworthy only when **both** (a) `get(...)` did not throw (network up, HTTP 2xx) **and** (b) the body **strictly decodes** as a favorites envelope. The read returns:
   - `null` when `validToken()`/`userId` is unavailable, **OR any page's `get(...)` fails** (network / non-2xx), **OR any page's body fails to strictly decode** as a favorites envelope.
   - a real, **complete** list (possibly empty) only when **every** page fetched with HTTP success **and** strictly decoded.

   **Why the strict decode is load-bearing (the captive-portal wipe).** Captive-portal WiFi (airport/hotel/café) answers `GET` with `200 + <html>login…</html>`; a Tidal error envelope answers `200 + {"status":…}`; schema drift can drop or rename `items`. Because the parser uses `Json { ignoreUnknownKeys = true }` and **every `…Response` field is defaulted** (`items = emptyList()`, `totalNumberOfItems = 0`), the *lenient* parser SILENTLY decodes all three to an empty envelope — so a mere `?: break` / `?: return null` on the lenient parse would hand the mirror a **trusted `[]`** and delete every favorite, on a condition that **recurs on every `onResume`** behind that WiFi. Two changes close it:
     - **Make `totalNumberOfItems` a REQUIRED field** (drop its `= 0` default) on the three favorites `…Response` DTOs. Now HTML, an error envelope, and drift that drops/renames the field all **fail to decode** (`MissingFieldException` / `SerializationException`) instead of defaulting to an empty envelope. (Playlist DTOs are left alone — playlists are out of mirror scope.)
     - **Read each page through a STRICT parser** `favoriteAlbumsPageOrNull`/`favoriteArtistsPageOrNull`/`favoriteTracksPageOrNull` that returns `null` on any decode failure and the (possibly-empty) list on a valid decode; the read does `favoriteXPageOrNull(body, json) ?: return null` on EVERY page. A **genuine** empty page (`{"items":[],"totalNumberOfItems":0}`) still decodes → trusted `[]` → the to-zero mirror still works (a user who really cleared their Tidal favorites is mirrored). The existing lenient `favoriteXFrom` parsers and their Slice-2/3 tests are kept untouched (they remain the tested *projection* surface; the strict variants add only the null-on-decode-failure behavior).

   The reconcile then does `reconcileFavorites(tidal.favoriteX(), …)`, and a `null` remote **adds nothing and — critically — deletes nothing.** Local state is untouched on any failed / partial / bad-body fetch.

   > **DELIBERATE DEVIATION FROM THE BRIEF (safety-hardening).** The brief permitted a *first-page* failure to return null but a *later-page* failure to `break` with the partial list. **That is unsafe for a mirror:** if page 1 (50 items) succeeds and page 2 fails, a partial `remote` of 50 makes the mirror treat every page-2+ favorite as "stale" and **delete real favorites**. So this plan returns `null` on **any** page failure (throw OR bad-decode) — the mirror is all-or-nothing. The only cost is that a mid-pagination failure syncs nothing that cycle (no adds either); the next throttled sync reconciles, and adds are idempotent, so nothing is lost. This is the SAFE (don't-delete) branch, per the brief's escalation rule.

2. **Mirror-delete is SCOPED to `source == "TIDAL"`.** The stale set is computed by filtering the local key set to `"TIDAL:"`-prefixed keys before diffing against the remote id set. A LOCAL-source favorite is not represented on Tidal and is never a delete candidate.

3. **The reconcile is a PURE, unit-tested set-diff** — a single `reconcileFavorites(remote, localKeys, id)` that returns *both* the add-list and the stale-remove-list, and returns `(empty, empty)` when `remote == null`. It **reuses** the existing pure `newRemoteFavorites` for the add half. A unit test asserts the null-remote-deletes-nothing property directly. The network+DB orchestration (the pulls) stays untested (internal-v1 precedent) and calls only the tested pure function + the existing/added delete accessors.

## DECIDED design (implement exactly; genuine sub-forks are flagged in the report)

- **Mirror scope = albums, artists, tracks** — the three favorite types with `"SOURCE:sourceId"` key sets and existing delete accessors. **Playlists are OUT of mirror scope:** `pullPlaylists` (`TidalCollectionSync.kt:68`) is a shell find-or-create (LIST-eager, tracks-lazy), not a keyed favorite set; it stays additive as-is. The brief scopes the mirror to the three favorite types only.
  - **KNOWN LIMITATION (accepted, documented follow-up):** because playlists are additive-only, a playlist you **un-favorite in Tidal** persists in Auxen as a stale shell until manually deleted. Mirroring playlist removals is deferred (it needs a keyed-favorite reconcile for the shell table); it is not a data-loss risk (a stale extra shell, never a deletion).
- **Pull return shape.** Each pull returns the `FavoriteReconcile<T>` it applied (its `toAdd.size` = adds performed, `staleTidalIds.size` = removes performed). `pullAll` reads those sizes and folds a single new `removed` grand-total into `CollectionPullSummary` (minimal: one new field, not per-type removed counts). Status string reports adds per type + one `-N removed`.
- **`THROTTLE_MS = 30_000` (30s).** Long enough that a resume storm (returning from the notification shade, a permission dialog, split-screen focus changes — each fires `onResume`) doesn't hammer Tidal's paginated favorites endpoints; short enough that a normal "make a change in Tidal, switch back to Auxen" flow (usually tens of seconds to minutes) reflects immediately. The manual Sync button passes `force = true` to bypass it.
- **Throttle-on-failure = stamp-on-attempt, no reset.** `lastSyncAtMillis` is stamped once when a sync is *attempted* (after passing the throttle+login gates). A failed sync does **not** reset it. Rationale: unlike the old once-per-process `collectionSynced` guard (which HAD to reset on failure or a failed first sync would never retry), a *time*-throttle inherently allows the next trigger to retry after `THROTTLE_MS` — so no reset is needed, and not-resetting prevents repeated resumes from hammering Tidal during a sustained outage. `force = true` (manual Sync) always bypasses for an immediate retry. (Note: because the reads degrade to `null`/no-throw, `pullAll` rarely *throws* — an offline sync succeeds with `+0 … -0` — so the failure branch is a rare belt-and-suspenders path anyway.)
- **Auto-trigger wiring = `MainActivity.onResume` override, NOT a Compose `LifecycleEventObserver`.** The Activity holds the VM as a property (`by viewModels()`, `:100`) — the *same* instance passed into the Compose tree — so a plain `onResume` override reaches `viewModel.syncFavoritesFromTidal()` directly with zero new moving parts. A Compose `LifecycleEventObserver` would need `LocalLifecycleOwner` + a `DisposableEffect` + observer bookkeeping for no benefit. Keep the Collection-open `LaunchedEffect` (now throttled, not once-per-process) and the manual button.

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), OkHttp 4.12, Room 2.7.1 (existing `favorites`/`favorite_albums`/`favorite_artists`/`tracks` tables — **no schema change, DB stays at its current version**), Compose (Collection/MainActivity — unchanged UI, one comment + one lifecycle override), JUnit4 + Robolectric 4.14.1, `android.os.SystemClock` for the throttle. minSdk 26 / compileSdk 35 / Media3 1.5.1.

## Global Constraints

- **DATA-LOSS SAFETY IS THE TOP CONSTRAINT.** Never delete a LOCAL-source favorite from a Tidal pull (the stale set is filtered to `"TIDAL:"` keys). Never delete on a `null`/failed/partial fetch (`reconcileFavorites(null, …)` returns `(empty, empty)`; the provider reads return `null` on any page failure). The reconcile pure core is unit-tested **including the null-safety case**. The mirror is idempotent (a second sync with unchanged Tidal state adds 0, removes 0). The throttle never wedges (time-based; retry after `THROTTLE_MS`; `force` always bypasses).
- **Every gradle command must carry `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`.** The system default JDK breaks this project's Gradle (`java.lang.IllegalArgumentException: 25.0.3`). Every `Run:` line below carries the prefix — do not drop it. Never pipe gradle through `tail`/`head` and read the pipe's exit status; read the real output for `BUILD SUCCESSFUL`.
- **Never log or print `accessToken`/`refreshToken`.** Only human-readable counts/titles go to any status string or Logcat. The `Authorization: Bearer $token` header is built inline in `get`/`post`/`delete` and never logged; this change adds no logging.
- **Internal-v1 only.** `TidalAuth.kt` and streaming are untouched; a favorited Tidal track still streams via `getStreamInfoById`. `TidalProvider` is *extended in place* (three read signatures change return type; no new endpoint).
- **No DB migration.** `unfavoriteTrack` uses the existing `TrackDao.bySourceId` + `FavoriteDao.delete(trackId)` on the existing `tracks`/`favorites` tables. No `@Entity`, no `AuxenDatabase`, no DAO **query** change (a new `LibraryRepository` method is not a schema change). Confirm with `git diff` at the end (Task 4).
- **Reuse, never re-declare — a duplicate declaration is a Kotlin "conflicting declarations" COMPILE ERROR.** `newRemoteFavorites`, `favoriteAlbumEntityFor`, `favoriteArtistEntityFor`, `CollectionPullSummary`, `favoriteAlbumKeys`/`favoriteArtistKeys`/`favoriteKeys`, `favoriteAlbum`/`favoriteArtist`/`favoriteTrack`, `unfavoriteAlbum`/`unfavoriteArtist`, `Source`, `TidalFavoriteAlbum`/`TidalFavoriteArtist`/`TidalFavoriteTrack`, `TrackDao.bySourceId`, `FavoriteDao.delete` all already exist and are **reused**. Every NEW name (`reconcileFavorites`, `FavoriteReconcile`, `unfavoriteTrack`, `THROTTLE_MS`, `lastSyncAtMillis`) is grep-verified to have zero existing declarations in `app/src`.
- **Nullable-read change is contained.** The return type of `favoriteAlbums()/favoriteArtists()/favoriteTracks()` changes from `List<X>` to `List<X>?`. Grep confirms the **only** callers are the three pulls in `TidalCollectionSync.kt` (`PlayerViewModel.kt:145/149` and `LibraryRepository.kt:183/194` are the *repository's* `favoriteAlbums()/favoriteArtists()` — a different receiver returning `Flow<List<Entity>>`, untouched). So the ripple is exactly the three pulls, all rewritten here.
- **The reads use a STRICT parser; the lenient parsers stay for their tests.** The reads switch from the lenient `favoriteXFrom` (degrades a bad body to `[]`) to the strict `favoriteXPageOrNull` (`null` on any decode failure). `totalNumberOfItems` becomes a REQUIRED field (no default) on the three favorites `…Response` DTOs so a non-favorites 200 body fails to decode. Grep-verified: the lenient `favoriteAlbumsFrom`/`favoriteArtistsFrom`/`favoriteTracksFrom` are called only by the reads (now switched) + their own tests — those tests stay green (making `totalNumberOfItems` required does **not** break them: every positive fixture already carries it, and the degrade-to-empty tests still pass because the *lenient* parser catches the new `MissingFieldException`). The `…Response` DTOs are `private` — the required-field change is not a public API change. `favoritePlaylistsFrom` and its DTOs are untouched (playlists out of mirror scope). Every new name (`favoriteAlbumsPageOrNull`/`favoriteArtistsPageOrNull`/`favoriteTracksPageOrNull`, the private `toFavoriteAlbums`/`toFavoriteArtists`/`toFavoriteTracks` projections) is grep-verified collision-free.
- **Unit tests are pure / DB-only, never live HTTP.** Task 1 tests `reconcileFavorites` (pure, incl. null-safety) and `unfavoriteTrack` (in-memory Room, matching `LibraryRepositoryTest`/`FavoriteCollectionDaoTest` discipline). The provider reads and the pull/throttle orchestration stay untested (internal-v1 + VM/Activity precedent).
- **Roborazzi goldens are unaffected.** No Compose component or parameter is added; the only UI-adjacent change is the status **string** and a stale **comment**. No golden re-records.
- **Git: explicit pathspecs only.** Never `git add -A` / `git add .` / `git commit -a` — the tree carries unrelated untracked files (`.playwright-mcp/`, other `docs/plans/*`).
- Every code step shows the actual code. No `TODO`/"add error handling"/"similar to above" placeholders.

---

### Task 1: Pure mirror-safe core — strict favorites parser + reconcile + `unfavoriteTrack` (the tested safety core)

The unit-tested heart of the feature, added with **zero** behavior change to the shipping pulls (they still return `Int` and call `newRemoteFavorites`/the lenient parser until Task 2 wires the new core in). Three tested primitives, all consumed together in Task 2:
- **Strict favorites parser** (`favoriteXPageOrNull`) — the mirror-safe read parser that returns `null` on a bad body (HTML captive portal / v1 error envelope / missing `totalNumberOfItems`) instead of the lenient `[]`, so an untrustworthy 200 can never drive a mirror-delete. Backed by making `totalNumberOfItems` a REQUIRED field on the three favorites `…Response` DTOs.
- **`reconcileFavorites`** — folds the additive union (`newRemoteFavorites`, reused) and the mirror-delete set into one pure function whose `null`-remote branch is the data-loss guard, unit-tested as such.
- **`unfavoriteTrack`** — the track analogue of `unfavoriteAlbum`/`unfavoriteArtist`, removing only the `favorites` row.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalFavorites.kt` (require `totalNumberOfItems`; extract projections; add the three strict `favoriteXPageOrNull`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt` (add `FavoriteReconcile` + `reconcileFavorites`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt` (add `unfavoriteTrack`)
- Modify: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalFavoritesTest.kt` (strict-parser tests)
- Modify: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionSyncTest.kt`
- Modify: `android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt`

**Interfaces:**
- Consumes: the existing private `TidalV1FavoriteAlbumsResponse`/`…ArtistsResponse`/`…TracksResponse` DTOs + `tidalV1CoverUrl`/`tidalV1ArtistPictureUrl`/`parseIso8601Millis`/`TidalV1PageTrack.toTrack` (`TidalFavorites.kt`, REUSED via the extracted projections); `newRemoteFavorites` + `Source` (`TidalCollectionSync.kt`, REUSED); `TrackDao.bySourceId` + `FavoriteDao.delete` (`Daos.kt`, REUSED via `db.trackDao()`/`db.favoriteDao()`).
- Produces: `fun favoriteAlbumsPageOrNull(body, json): List<TidalFavoriteAlbum>?`, `favoriteArtistsPageOrNull(...): List<TidalFavoriteArtist>?`, `favoriteTracksPageOrNull(...): List<TidalFavoriteTrack>?` (strict, null on decode failure); the private projections `toFavoriteAlbums`/`toFavoriteArtists`/`toFavoriteTracks`; `totalNumberOfItems` now required on the three DTOs; `data class FavoriteReconcile<T>(val toAdd: List<T>, val staleTidalIds: List<String>)`; `fun <T> reconcileFavorites(remote: List<T>?, localKeys: Set<String>, id: (T) -> String): FavoriteReconcile<T>`; `suspend fun LibraryRepository.unfavoriteTrack(source: String, sourceId: String)`.

- [ ] **Step 1: Write the failing tests**

Add to `TidalFavoritesTest.kt`, inside the class (after the existing lenient-parser tests), the strict-parser tests — they use only the already-imported `assertEquals`/`assertNull` and reuse the existing `albumsPage`/`artistsPage`/`tracksPage` fixtures (each already carries `totalNumberOfItems`):

```kotlin
    @Test
    fun `strict page parsers decode a genuine page (incl a genuine EMPTY) to a trusted non-null list`() {
        // A real page decodes to the same projection the lenient parser produces...
        assertEquals(2, favoriteAlbumsPageOrNull(albumsPage, json)!!.size)
        assertEquals(2, favoriteArtistsPageOrNull(artistsPage, json)!!.size)
        assertEquals(2, favoriteTracksPageOrNull(tracksPage, json)!!.size)
        // ...and a GENUINE empty page (items:[] WITH totalNumberOfItems) is a
        // TRUSTED empty [] — non-null — so the to-zero mirror still works.
        val emptyPage = """{"items":[],"totalNumberOfItems":0}"""
        assertEquals(emptyList<TidalFavoriteAlbum>(), favoriteAlbumsPageOrNull(emptyPage, json))
        assertEquals(emptyList<TidalFavoriteArtist>(), favoriteArtistsPageOrNull(emptyPage, json))
        assertEquals(emptyList<TidalFavoriteTrack>(), favoriteTracksPageOrNull(emptyPage, json))
    }

    @Test
    fun `strict page parsers return NULL on a non-favorites 200 body (captive portal, error envelope, drift)`() {
        // THE captive-portal wipe guard. Each of these is an HTTP-200 body that is
        // NOT a favorites page; a null result makes the read return null so the
        // mirror deletes nothing (vs the lenient parser's trusted []).
        val html = "<html><body>Sign in to WiFi</body></html>"          // captive portal
        val errorEnvelope = """{"status":404,"userMessage":"not found"}""" // no totalNumberOfItems
        val drift = """{"tracks":[{"item":{"id":1}}],"totalCount":1}"""     // items dropped/renamed, no totalNumberOfItems
        for (bad in listOf(html, errorEnvelope, drift, "not json")) {
            assertNull(favoriteAlbumsPageOrNull(bad, json))
            assertNull(favoriteArtistsPageOrNull(bad, json))
            assertNull(favoriteTracksPageOrNull(bad, json))
        }
    }
```

Add to `TidalCollectionSyncTest.kt` — first the import (below the existing `import org.junit.Assert.assertNull`):

```kotlin
import org.junit.Assert.assertTrue
```

then, inside the `TidalCollectionSyncTest` class (after the `newRemoteFavorites` tests), the reconcile tests. They reuse the existing `album(id)` helper:

```kotlin
    @Test
    fun `reconcileFavorites adds remote-not-local and removes stale local TIDAL favorites`() {
        // 111 already local (skip add), 222 new (add); local TIDAL 999 is gone
        // from remote (stale -> remove); LOCAL 42 is never a delete candidate.
        val remote = listOf(album("111"), album("222"))
        val localKeys = setOf("TIDAL:111", "TIDAL:999", "LOCAL:42")
        val (toAdd, stale) = reconcileFavorites(remote, localKeys) { it.id }
        assertEquals(listOf("222"), toAdd.map { it.id })
        assertEquals(listOf("999"), stale)
    }

    @Test
    fun `reconcileFavorites never removes a LOCAL-source favorite even against an empty remote`() {
        // A CONFIRMED empty remote (the user really has 0) still spares every
        // LOCAL favorite; only the TIDAL-source local with no remote match is stale.
        val localKeys = setOf("TIDAL:5", "LOCAL:5", "LOCAL:999")
        val (toAdd, stale) = reconcileFavorites(emptyList<TidalFavoriteAlbum>(), localKeys) { it.id }
        assertTrue(toAdd.isEmpty())
        assertEquals(listOf("5"), stale)
    }

    @Test
    fun `reconcileFavorites is idempotent when local matches remote exactly`() {
        val remote = listOf(album("1"), album("2"))
        val (toAdd, stale) = reconcileFavorites(remote, setOf("TIDAL:1", "TIDAL:2")) { it.id }
        assertTrue(toAdd.isEmpty())
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `reconcileFavorites on a NULL (failed) fetch adds nothing and removes nothing`() {
        // THE data-loss guard: a null remote = a failed/untrusted fetch. Even with
        // local TIDAL favorites present, NOTHING is added and — critically —
        // NOTHING is stale, so the mirror deletes nothing on a failed fetch.
        val localKeys = setOf("TIDAL:1", "TIDAL:2", "LOCAL:3")
        val (toAdd, stale) = reconcileFavorites<TidalFavoriteAlbum>(null, localKeys) { it.id }
        assertTrue(toAdd.isEmpty())
        assertTrue(stale.isEmpty())
    }
```

Add to `LibraryRepositoryTest.kt` (inside the class, after `favoriteTrackOrdersByRemoteAddedAtNotThePullClock`):

```kotlin
    @Test
    fun unfavoriteTrackRemovesOnlyTheFavoriteRowNotTheTrack() = runBlocking {
        repo.favoriteTrack(tidalTrack, addedAtMillis = 100)
        assertEquals(setOf("TIDAL:99"), repo.favoriteKeys().first())

        repo.unfavoriteTrack(Source.TIDAL.name, "99")
        // The favorite is gone...
        assertTrue(repo.favoriteKeys().first().isEmpty())
        assertTrue(repo.favorites().first().isEmpty())
        // ...but the track row survives (it may be in a playlist / play history).
        assertEquals("Everlong", db.trackDao().bySourceId("TIDAL", "99")!!.title)

        // No-op when no such track exists locally (must not throw).
        repo.unfavoriteTrack(Source.TIDAL.name, "does-not-exist")
        assertTrue(repo.favoriteKeys().first().isEmpty())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalFavoritesTest*' --tests '*TidalCollectionSyncTest*' --tests '*LibraryRepositoryTest*'`
Expected: FAIL — all three test classes fail to **compile**: `favoriteAlbumsPageOrNull`/`favoriteArtistsPageOrNull`/`favoriteTracksPageOrNull` (in `TidalFavoritesTest`), `reconcileFavorites`/`FavoriteReconcile` (in `TidalCollectionSyncTest`), and `repo.unfavoriteTrack` (in `LibraryRepositoryTest`) are unresolved references (they do not exist yet). A compile-error RED is expected here because the new tests share the existing green class files.

- [ ] **Step 3: Require `totalNumberOfItems`, extract projections, add the strict parsers in `TidalFavorites.kt`**

First, make `totalNumberOfItems` a REQUIRED field (drop `= 0`) on the three favorites response DTOs (`:71-74`, `:82-86`, `:91-95`) — leave `items = emptyList()` and the playlist DTOs alone:

```kotlin
@Serializable
private data class TidalV1FavoriteAlbumsResponse(
    val items: List<TidalV1FavoriteAlbumItem> = emptyList(),
    val totalNumberOfItems: Int,
)
```
```kotlin
@Serializable
private data class TidalV1FavoriteArtistsResponse(
    val items: List<TidalV1FavoriteArtistItem> = emptyList(),
    val totalNumberOfItems: Int,
)
```
```kotlin
@Serializable
private data class TidalV1FavoriteTracksResponse(
    val items: List<TidalV1FavoriteTrackItem> = emptyList(),
    val totalNumberOfItems: Int,
)
```

Then extract each lenient parser's projection into a private helper and add the strict variant beside it. For albums, replace `favoriteAlbumsFrom` (`:126-138`) with:

```kotlin
/** Pure projection of a decoded albums page — the tested body shared by the lenient and strict parsers. */
private fun TidalV1FavoriteAlbumsResponse.toFavoriteAlbums(): List<TidalFavoriteAlbum> = items.mapNotNull { wrapper ->
    val album = wrapper.item ?: return@mapNotNull null
    val artist = album.artist ?: album.artists.firstOrNull()
    TidalFavoriteAlbum(
        id = album.id.toString(),
        title = album.title,
        artistName = artist?.name?.ifBlank { null },
        coverUrl = tidalV1CoverUrl(album.cover),
        addedAtMillis = parseIso8601Millis(wrapper.created),
    )
}

/**
 * LENIENT: one `favorites/albums` page body -> [TidalFavoriteAlbum]s, degrading a
 * malformed body / v1 error envelope to EMPTY (never crashes). Retained as the
 * tested projection surface; the mirror-pull reads use [favoriteAlbumsPageOrNull].
 */
fun favoriteAlbumsFrom(body: String, json: Json): List<TidalFavoriteAlbum> = runCatching {
    json.decodeFromString<TidalV1FavoriteAlbumsResponse>(body).toFavoriteAlbums()
}.getOrDefault(emptyList())

/**
 * STRICT (mirror-safe): the same projection, but `null` on ANY decode failure —
 * an HTML captive-portal page, a v1 error envelope, or drift that drops/renames
 * `items` (now that `totalNumberOfItems` is REQUIRED, all three fail to decode).
 * A GENUINE page — including a genuine empty `{"items":[],"totalNumberOfItems":0}`
 * — decodes to a TRUSTED (possibly-empty) list. The favorites reads gate on this
 * so an ambiguous empty from a bad 200 becomes `null` (mirror deletes nothing),
 * never a trusted `[]` that would wipe the collection.
 */
fun favoriteAlbumsPageOrNull(body: String, json: Json): List<TidalFavoriteAlbum>? = runCatching {
    json.decodeFromString<TidalV1FavoriteAlbumsResponse>(body).toFavoriteAlbums()
}.getOrNull()
```

Apply the identical shape to `favoriteArtistsFrom` (`:146-156`) -> `toFavoriteArtists()` + `favoriteArtistsPageOrNull` (reusing `tidalV1ArtistPictureUrl`), and `favoriteTracksFrom` (`:166-171`) -> `toFavoriteTracks()` + `favoriteTracksPageOrNull` (reusing `TidalV1PageTrack.toTrack`). The lenient functions keep their exact signatures + `getOrDefault(emptyList())` behavior (their Slice-2/3 tests stay green — every positive fixture already carries `totalNumberOfItems`, and the degrade-to-empty tests still pass because the lenient parser catches the new `MissingFieldException`).

- [ ] **Step 4: Add `reconcileFavorites` + `FavoriteReconcile` in `TidalCollectionSync.kt`**

Add immediately after the existing `newRemoteFavorites` function (`:126-133`), reusing it for the add half:

```kotlin
/**
 * The outcome of one pure favorites reconcile: [toAdd] are remote favorites to
 * insert locally (the additive union), [staleTidalIds] are local TIDAL-source
 * sourceIds to unfavorite (the mirror-delete set).
 */
data class FavoriteReconcile<T>(val toAdd: List<T>, val staleTidalIds: List<String>)

/**
 * Pure two-way reconcile of a CONFIRMED [remote] favorite list against the local
 * favorite [localKeys] ("SOURCE:sourceId", mixed sources). [toAdd] reuses
 * [newRemoteFavorites] (remote favorites whose `TIDAL:<id>` key is not already
 * local). [staleTidalIds] is the mirror-delete set: local **TIDAL-source**
 * sourceIds NOT in the confirmed remote id set — LOCAL-source keys are filtered
 * out first, so a local favorite is NEVER deleted by a Tidal pull.
 *
 * **Data-loss guard:** a NULL [remote] means the fetch FAILED / is untrusted
 * (see [TidalProvider.favoriteAlbums] — it returns null on any page failure).
 * Both lists are then EMPTY, so a failed/partial fetch adds nothing and — the
 * point of this whole design — deletes nothing. Only a genuinely-empty CONFIRMED
 * fetch (`remote = []`) can yield a non-empty stale set. Idempotent: an unchanged
 * remote+local yields (empty, empty). [id] extracts a remote favorite's Tidal id.
 */
fun <T> reconcileFavorites(remote: List<T>?, localKeys: Set<String>, id: (T) -> String): FavoriteReconcile<T> {
    if (remote == null) return FavoriteReconcile(emptyList(), emptyList())
    val prefix = "${Source.TIDAL.name}:"
    val remoteIds = remote.mapTo(mutableSetOf(), id)
    val staleTidalIds = localKeys
        .filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix) }
        .filter { it !in remoteIds }
    return FavoriteReconcile(newRemoteFavorites(remote, localKeys, id), staleTidalIds)
}
```

- [ ] **Step 5: Add `LibraryRepository.unfavoriteTrack`**

In `LibraryRepository.kt`, add immediately after `unfavoriteArtist` (`:202`):

```kotlin
    /**
     * Remove [sourceId]'s favorite row (source [source]) — the track analogue of
     * [unfavoriteAlbum]/[unfavoriteArtist], used by the Tidal mirror-pull to drop
     * a favorite the user un-favorited on Tidal. Resolves the track id via
     * [io.github.auxen.db.TrackDao.bySourceId] and deletes ONLY the `favorites`
     * row (never the `tracks` row — the track may still be in a playlist or play
     * history). No-op when no such track exists locally.
     */
    suspend fun unfavoriteTrack(source: String, sourceId: String) {
        val id = db.trackDao().bySourceId(source, sourceId)?.id ?: return
        db.favoriteDao().delete(id)
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalFavoritesTest*' --tests '*TidalCollectionSyncTest*' --tests '*LibraryRepositoryTest*'`
Expected: PASS — the two new strict-parser tests, the four new reconcile tests, and the new `unfavoriteTrack` DB test pass; all pre-existing tests in the three classes keep passing (the shipping pulls + lenient parsers are untouched this task; `totalNumberOfItems` becoming required breaks no existing test).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalFavorites.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt \
        android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalFavoritesTest.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionSyncTest.kt \
        android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt
git commit -m "feat(android): mirror-safe strict favorites parser + reconcile + unfavoriteTrack

Closes the captive-portal/200-bad-body wipe hole: makes totalNumberOfItems a
REQUIRED field on the three favorites Response DTOs and adds strict
favoriteXPageOrNull parsers that return null on any decode failure (HTML, a v1
error envelope, dropped/renamed items) while a genuine (incl. genuine-empty) page
decodes to a trusted list. The lenient favoriteXFrom parsers + their tests are
retained as the shared projection surface. Adds reconcileFavorites — a pure
set-diff folding the additive union (reusing newRemoteFavorites) and the
mirror-delete set (local TIDAL-source sourceIds absent from a CONFIRMED remote),
returning (empty, empty) for a NULL remote so a failed fetch deletes nothing.
Adds LibraryRepository.unfavoriteTrack (resolve id via bySourceId, delete only
the favorites row). Unit/DB tests cover the strict parser (bad-body -> null,
genuine-empty -> trusted []), reconcile (add+remove, TIDAL-only filter,
idempotency, null-safety), and the favorite-row-only delete. No pull is wired to
these yet; shipping behavior is unchanged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 2: Nullable provider reads + mirror the three pulls + report removals

Wire the tested core into the network+DB path. This is one task because the return-type change (`List<X>` -> `List<X>?`) and its only callers (the three pulls) **must move together** to keep the build green — a nullable read with a non-null caller (or vice versa) is a compile error, so they cannot be split across task boundaries.

> **DELIBERATE MERGE vs the brief's task shape.** The brief proposed separate "provider nullable" and "mirror pulls" tasks. They are inseparable for a green build (the type change ripples straight into the callers), so they are one task here. The pure logic they exercise is already RED->GREEN tested in Task 1; this task's gate is `assembleDebug` + the full existing suite green (network + orchestration untested by precedent).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt` (three reads -> nullable)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt` (three pulls -> mirror; `CollectionPullSummary.removed`; `pullAll`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (status string)

**Interfaces:**
- Consumes: `reconcileFavorites`/`FavoriteReconcile` + the strict `favoriteAlbumsPageOrNull`/`favoriteArtistsPageOrNull`/`favoriteTracksPageOrNull` (all Task 1); `favoriteAlbumEntityFor`/`favoriteArtistEntityFor`/`newRemoteFavorites` (REUSED); `library.favoriteAlbumKeys()/favoriteArtistKeys()/favoriteKeys()`, `library.favoriteAlbum/favoriteArtist/favoriteTrack`, `library.unfavoriteAlbum/unfavoriteArtist/unfavoriteTrack` (the last new in Task 1); the provider's private `validToken`/`userId`/`get`/`favoriteXUrl`/`FAVORITES_PAGE`/`FAVORITES_MAX`. No new imports.
- Produces: `favoriteAlbums(): List<TidalFavoriteAlbum>?`, `favoriteArtists(): List<TidalFavoriteArtist>?`, `favoriteTracks(): List<TidalFavoriteTrack>?`; the three mirroring pulls returning `FavoriteReconcile<…>`; `CollectionPullSummary(albums, artists, tracks, playlists, removed)`.

- [ ] **Step 1: Make the three provider reads nullable — null on any get-failure OR bad-body decode**

In `TidalProvider.kt`, change each read's return type, its failure exits, AND its page parser (from the lenient `favoriteXFrom` to the strict `favoriteXPageOrNull` from Task 1 — **this is the change that closes the captive-portal wipe**; the mechanical `?: break -> ?: return null` on the *lenient* parse would still return a trusted `[]` on a 200-bad-body). For `favoriteAlbums()` (`:419-433`):

```kotlin
    suspend fun favoriteAlbums(): List<TidalFavoriteAlbum>? {
        val token = validToken() ?: return null
        val uid = userId ?: return null
        val all = mutableListOf<TidalFavoriteAlbum>()
        var offset = 0
        while (offset < FAVORITES_MAX) {
            val url = favoriteAlbumsUrl(API_BASE, uid, countryCode, FAVORITES_PAGE, offset)
            val body = runCatching { get(url.toString(), token) }.getOrNull() ?: return null
            val page = favoriteAlbumsPageOrNull(body, json) ?: return null
            all += page
            if (page.size < FAVORITES_PAGE) break
            offset += FAVORITES_PAGE
        }
        return all
    }
```

Apply the identical edits to `favoriteArtists()` (`:441-455`) and `favoriteTracks()` (`:499-513`): return type `-> List<TidalFavoriteArtist>?` / `-> List<TidalFavoriteTrack>?`; both `?: return emptyList()` guards -> `?: return null`; the `get(...)` `?: break` -> `?: return null`; and the page parse `favoriteArtistsFrom(body, json)` / `favoriteTracksFrom(body, json)` -> `favoriteArtistsPageOrNull(body, json) ?: return null` / `favoriteTracksPageOrNull(body, json) ?: return null`. **Do NOT touch** `favoritePlaylists()` (`:540`) — playlists stay non-null/additive/lenient. Update each read's KDoc: change "Returns an empty list when not logged in / … / any page fails" to "Returns **null** when not logged in, the user id is unresolved, any page's fetch fails, OR any page's body does not strictly decode as a favorites envelope (an HTML captive-portal page / v1 error envelope is never mistaken for an empty collection); a complete (possibly empty) list only on full success — the mirror in [TidalCollectionSync] relies on this to distinguish a genuine empty from a failure."

- [ ] **Step 2: Mirror the three pulls in `TidalCollectionSync.kt`**

Replace `pullFavoriteAlbums()` (`:25-34`) with:

```kotlin
    /**
     * Pull Tidal saved albums into `favorite_albums`, MIRRORING Tidal: ADD
     * remote-not-local favorites and REMOVE local TIDAL-source favorites gone
     * from Tidal (via [reconcileFavorites], keyed `TIDAL:<id>`). A null (failed)
     * fetch reconciles nothing — no add, no delete. Returns the applied reconcile
     * (its sizes are the adds/removes performed). LOCAL favorites are never touched.
     */
    suspend fun pullFavoriteAlbums(): FavoriteReconcile<TidalFavoriteAlbum> {
        val localKeys = library.favoriteAlbumKeys().first()
        val reconcile = reconcileFavorites(tidal.favoriteAlbums(), localKeys) { it.id }
        reconcile.toAdd.forEach { library.favoriteAlbum(favoriteAlbumEntityFor(it, it.addedAtMillis ?: clock())) }
        reconcile.staleTidalIds.forEach { library.unfavoriteAlbum(Source.TIDAL.name, it) }
        return reconcile
    }
```

Replace `pullFavoriteArtists()` (`:36-42`) with:

```kotlin
    /** Pull Tidal followed artists into `favorite_artists`, mirroring Tidal (add + remove). Same contract as [pullFavoriteAlbums]. */
    suspend fun pullFavoriteArtists(): FavoriteReconcile<TidalFavoriteArtist> {
        val localKeys = library.favoriteArtistKeys().first()
        val reconcile = reconcileFavorites(tidal.favoriteArtists(), localKeys) { it.id }
        reconcile.toAdd.forEach { library.favoriteArtist(favoriteArtistEntityFor(it, it.addedAtMillis ?: clock())) }
        reconcile.staleTidalIds.forEach { library.unfavoriteArtist(Source.TIDAL.name, it) }
        return reconcile
    }
```

Replace `pullFavoriteTracks()` (`:44-57`) with:

```kotlin
    /**
     * Pull Tidal favorite tracks into the EXISTING `tracks`+`favorites` tables,
     * mirroring Tidal (add + remove). Adds each fresh track at its remote
     * `created` millis (falling back to the clock when the wrapper carried none);
     * removes local TIDAL-source favorite tracks gone from Tidal via
     * [LibraryRepository.unfavoriteTrack] (drops only the favorite row). Same
     * null-safe, LOCAL-preserving contract as [pullFavoriteAlbums].
     */
    suspend fun pullFavoriteTracks(): FavoriteReconcile<TidalFavoriteTrack> {
        val localKeys = library.favoriteKeys().first()
        val reconcile = reconcileFavorites(tidal.favoriteTracks(), localKeys) { it.track.sourceId }
        reconcile.toAdd.forEach { library.favoriteTrack(it.track, it.addedAtMillis ?: clock()) }
        reconcile.staleTidalIds.forEach { library.unfavoriteTrack(Source.TIDAL.name, it) }
        return reconcile
    }
```

- [ ] **Step 3: Add `removed` to the summary and fold it in `pullAll`**

Change `CollectionPullSummary` (`:10`):

```kotlin
/** What one full pull changed locally: adds per favorite type + total mirror-removes across the three keyed types. */
data class CollectionPullSummary(val albums: Int, val artists: Int, val tracks: Int, val playlists: Int, val removed: Int)
```

Replace `pullAll()` (`:90-96`):

```kotlin
    /** One pull of all favorite types (playlists are LIST-eager, additive; the other three mirror Tidal). */
    suspend fun pullAll(): CollectionPullSummary {
        val albums = pullFavoriteAlbums()
        val artists = pullFavoriteArtists()
        val tracks = pullFavoriteTracks()
        val playlists = pullPlaylists()
        return CollectionPullSummary(
            albums = albums.toAdd.size,
            artists = artists.toAdd.size,
            tracks = tracks.toAdd.size,
            playlists = playlists,
            removed = albums.staleTidalIds.size + artists.staleTidalIds.size + tracks.staleTidalIds.size,
        )
    }
```

- [ ] **Step 4: Report removals in the status string**

In `PlayerViewModel.kt`, update the success arm of `syncFavoritesFromTidal` (`:636`):

```kotlin
                onSuccess = { "Synced +${it.albums} album(s), +${it.artists} artist(s), +${it.tracks} track(s), +${it.playlists} playlist(s), -${it.removed} removed from Tidal." },
```

(Leave the `onFailure` arm untouched in this task — the throttle rewrite in Task 3 replaces the surrounding guard.)

- [ ] **Step 5: Build + full test suite**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest assembleDebug`
Expected: PASS; APK builds. `TidalFavoritesTest` (strict parsers), `TidalCollectionSyncTest` (pure), and `LibraryRepositoryTest` (DB) stay green — no test constructs `CollectionPullSummary` (grep-verified), so the new `removed` field breaks nothing; the pulls' return-type change touches only `pullAll` (grep-verified sole caller); the reads' switch to `favoriteXPageOrNull` is covered by Task 1's strict-parser tests. No new unit test here (network + orchestration untested by precedent; Task 1 covers the pure logic — strict-parse null-on-bad-body AND reconcile null-safety).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt
git commit -m "feat(android): mirror Tidal favorites pull (propagate removals)

Makes favoriteAlbums/Artists/Tracks return List<X>? — null on token/userId
missing, any page fetch failure, OR any page body that does not strictly decode
(reads now use favoriteXPageOrNull), a complete list only on full success — so an
ambiguous empty (incl. a captive-portal 200+HTML) is never mistaken for a real
empty. Rewrites
the three pulls to reconcileFavorites: ADD remote-not-local and REMOVE local
TIDAL-source favorites gone from Tidal (unfavoriteAlbum/Artist/Track), scoped to
TIDAL source so LOCAL favorites are never deleted, and a null fetch reconciles
nothing. CollectionPullSummary gains a `removed` total, folded into pullAll and
the sync status string. Playlists stay additive. No schema change.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 3: Auto-trigger on app open + resume (throttle + `onResume`)

Replace the once-per-process `collectionSynced` guard with a time-throttle, and add a `MainActivity.onResume` trigger so a change made in the Tidal app reflects when the user returns to Auxen — without pressing Sync. Keep the Collection-open `LaunchedEffect` (now throttled) and the manual button (`force = true`, bypasses the throttle).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (throttle state + `syncFavoritesFromTidal` + `THROTTLE_MS` + import)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (`onResume`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt` (stale comment only)

**Interfaces:**
- Consumes: `SystemClock.elapsedRealtime()`; `Graph.tidal.isLoggedIn`; `Graph.tidalCollectionSync.pullAll()`; the Activity's `viewModel` property (`MainActivity.kt:100`).
- Produces: a time-throttled `syncFavoritesFromTidal(force)`; `MainActivity.onResume()`.

- [ ] **Step 1: Add the `SystemClock` import to `PlayerViewModel.kt`**

In the import block (after `import android.content.ComponentName`, `:4`):

```kotlin
import android.os.SystemClock
```

- [ ] **Step 2: Replace the once-per-process guard with a throttle timestamp**

In `PlayerViewModel.kt`, replace `private var collectionSynced = false` (`:152`):

```kotlin
    /** elapsedRealtime of the last favorites-sync attempt; the throttle floor. 0 = never synced. */
    private var lastSyncAtMillis = 0L
```

- [ ] **Step 3: Rewrite `syncFavoritesFromTidal` to throttle instead of once-per-process**

Replace the whole function (`:623-640`, including its KDoc):

```kotlin
    /**
     * Pull the user's Tidal favorites into the local library, MIRRORING Tidal —
     * additive for new favorites, removing local TIDAL-source favorites the user
     * un-favorited on Tidal (a failed/untrusted fetch changes nothing; LOCAL-source
     * favorites are never touched). No-op when not signed in.
     *
     * Auto-triggered on app resume ([MainActivity.onResume]) and Collection open,
     * both THROTTLED to at most once per [THROTTLE_MS] so a resume storm never
     * spams Tidal; the manual "Sync from Tidal" affordance passes [force] = true
     * to bypass the throttle. The timestamp is stamped on ATTEMPT (not reset on
     * failure): the time-throttle lets the next trigger retry after [THROTTLE_MS],
     * and not resetting avoids hammering Tidal during a sustained outage. Runs off
     * the main thread (viewModelScope).
     */
    fun syncFavoritesFromTidal(force: Boolean = false) {
        if (!Graph.tidal.isLoggedIn) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSyncAtMillis < THROTTLE_MS) return
        lastSyncAtMillis = now
        viewModelScope.launch {
            _tidalCollectionStatus.value = "Syncing favorites from Tidal…"
            _tidalCollectionStatus.value = runCatching { Graph.tidalCollectionSync.pullAll() }.fold(
                onSuccess = { "Synced +${it.albums} album(s), +${it.artists} artist(s), +${it.tracks} track(s), +${it.playlists} playlist(s), -${it.removed} removed from Tidal." },
                onFailure = { "Favorites sync failed: ${it.message}" },
            )
        }
    }
```

- [ ] **Step 4: Add `THROTTLE_MS` to the companion object**

In `PlayerViewModel.kt`, inside the existing `private companion object` (`:1079`), add:

```kotlin
        /**
         * Minimum spacing between auto favorites-syncs (resume + Collection-open).
         * 30s: long enough that a resume storm (notification shade, permission
         * dialog, split-screen focus changes — each fires onResume) doesn't spam
         * Tidal's paginated favorites endpoints, short enough that a normal
         * "change in Tidal, back to Auxen" flow reflects immediately. The manual
         * Sync button (force = true) always bypasses it.
         */
        private const val THROTTLE_MS = 30_000L
```

- [ ] **Step 5: Add `MainActivity.onResume`**

In `MainActivity.kt`, add the override immediately after `onCreate` closes (`:124`), before `requiredPermissions()`:

```kotlin
    override fun onResume() {
        super.onResume()
        // Reflect Tidal-side favorite changes when the app returns to the
        // foreground — the user's chosen trigger over background sync. The pull is
        // throttled and isLoggedIn-gated inside the VM, so a rapid resume storm is
        // cheap and a signed-out session is a no-op. This reaches the SAME VM
        // instance the Compose tree uses (the `viewModel` property above).
        viewModel.syncFavoritesFromTidal()
    }
```

- [ ] **Step 6: Fix the now-stale Collection-open comment**

In `CollectionScreen.kt`, replace the comment above the `LaunchedEffect` (`:102-105`) — the call itself is unchanged:

```kotlin
    // Auto-pull the user's Tidal favorites (albums/artists/tracks/playlists) on
    // Collection open. The ViewModel throttles it (at most once per THROTTLE_MS,
    // shared with the app-resume trigger) and its isLoggedIn guard makes it a
    // no-op when signed out, so this is not a launch-time network surprise.
    LaunchedEffect(Unit) { viewModel.syncFavoritesFromTidal() }
```

- [ ] **Step 7: Build + full test suite**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest assembleDebug`
Expected: PASS; APK builds. No unit test changes (the throttle lives in the VM and the trigger in the Activity — both untested orchestration by precedent; there is no `PlayerViewModelTest`/`MainActivityTest`). Existing suites stay green.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt
git commit -m "feat(android): auto-sync Tidal favorites on app open + resume

Replaces the once-per-process collectionSynced guard with a 30s elapsedRealtime
throttle and adds a MainActivity.onResume trigger, so a favorite changed in the
Tidal app reflects when the user returns to Auxen without pressing Sync. The
Collection-open LaunchedEffect and the manual Sync button (force = true, bypasses
the throttle) are kept; the timestamp is stamped on attempt so the throttle never
wedges and a sustained outage is not hammered. Still isLoggedIn-gated, still off
the main thread.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 4: On-device checkpoint (gates completion)

No production code. CI runners have no Tidal client id, so on-device Tidal testing **must** use a locally-built APK. Completion of this feature is gated on this checkpoint passing — **including the data-loss safety test.**

- [ ] **Step 1: Build a labelled local APK**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew assembleDebug`
Copy the APK out of `build/outputs` (unsafe to hand out while other agents build) to a stable name carrying the commit SHA, verify with `aapt2 dump badging` (confirm `package: name='io.github.auxen'` and the expected `versionName`) before install. Install on a device already signed in to Tidal.

- [ ] **Step 2: Verify the mirror — removals propagate on resume**

  - In the **official Tidal app**, un-favorite one track, un-favorite one album, and unfollow one artist that Auxen currently shows.
  - Switch to Auxen (bring it to the foreground — do **not** press Sync). Within a moment the resume-triggered sync runs (throttled, isLoggedIn-gated). Confirm the un-favorited **track** disappears from the Collection Tracks tab, the **album** from Albums, the **artist** from Artists — **without pressing Sync**.
  - The status line shows a non-zero `-N removed` (e.g. `… -3 removed from Tidal.`).

- [ ] **Step 3: Verify additive still works + LOCAL is untouched**

  - In the official Tidal app, favorite a track/album/artist that Auxen did not have. Return to Auxen -> it appears (adds still work through the mirror).
  - Favorite a **LOCAL** track in Auxen (a local-source track), then trigger a Tidal sync (resume or open Collection). Confirm the LOCAL favorite is **still present** — a Tidal pull must never delete a local-source favorite.

- [ ] **Step 4: THE DATA-LOSS SAFETY TEST — a failed sync must NOT wipe favorites**

  - With Auxen showing a populated Collection (Tidal favorites present), enable **airplane mode** (or otherwise kill connectivity).
  - Trigger a sync: press the manual **Sync from Tidal** button (`force = true`, bypasses the throttle) and/or background+foreground Auxen to fire `onResume`.
  - **Confirm every Tidal favorite is STILL present** — the failed fetch returns `null`, so the mirror reconciles nothing (no add, no delete). Favorites must not be wiped. (The status may read a benign `Synced +0 … -0 removed` or a `Favorites sync failed:` message — either is acceptable; the load-bearing assertion is that **nothing was deleted**.)
  - Restore connectivity, sync again -> the collection reconciles normally.

- [ ] **Step 5: THE CAPTIVE-PORTAL SAFETY TEST — a 200-with-a-non-favorites-body must NOT wipe favorites**

  This is the specific path the airplane-mode test does NOT cover: `get()` succeeds (HTTP 200) but the body is not a favorites page. Reproduce one of:
  - **Preferred (real):** join a WiFi network with a captive portal (many airport/hotel/café networks) so `api.tidal.com` requests are intercepted and answered with a `200 + <html>login…</html>`, then trigger a sync (manual Sync and/or `onResume`).
  - **Fallback (forced):** if no captive portal is available, temporarily point the device's traffic at a proxy/hosts entry that returns `200` HTML for `api.tidal.com`, or reason it from the Task 1 strict-parser test (`strict page parsers return NULL on a non-favorites 200 body`) plus the read's `?: return null` — the read returns `null`, the mirror reconciles nothing.
  - **Confirm every Tidal favorite is STILL present** (the strict parser rejects the non-JSON/error body -> read returns `null` -> no delete). If any favorite disappears here, the strict-parser gate is not wired — STOP.

- [ ] **Step 6: Verify idempotency, throttle, and no secret leakage**

  - With no Tidal-side changes, trigger a sync -> status reports `+0 … -0 removed` (idempotent; no duplicates, no spurious deletes).
  - Rapidly background+foreground Auxen several times within 30s and confirm only the first resume actually pulls (the others are throttled — no repeated "Syncing…" storm); after ~30s a resume pulls again.
  - Scan Logcat across the whole session and confirm **no `accessToken`/`refreshToken`** appears anywhere.

- [ ] **Step 7: Final tree check**

Run `git status` and `git diff --stat main` and confirm only the **ten** files across Tasks 1–3 changed: `TidalFavorites.kt` + `TidalFavoritesTest.kt`, `TidalCollectionSync.kt` + `TidalCollectionSyncTest.kt`, `LibraryRepository.kt` + `LibraryRepositoryTest.kt`, `TidalProvider.kt`, `PlayerViewModel.kt`, `MainActivity.kt`, `CollectionScreen.kt` — **no** `@Entity`/`AuxenDatabase`/`Daos.kt`/migration change (no schema change), **no** Compose component/golden change.

---

## Self-review checklist

- **DATA-LOSS SAFETY — the top concern, guarded in a tested pure core.**
  - **Never delete on a failed/untrusted fetch — INCLUDING a 200 with a non-favorites body:** the reads return `null` on token/userId missing, any `get()` failure, OR any page body that does not strictly decode (`favoriteXPageOrNull`). `totalNumberOfItems` is a REQUIRED field, so a captive-portal HTML page, a v1 error envelope, or drift that drops/renames `items` all fail to decode -> `null` -> mirror reconciles nothing. **This is the fix for the reviewer's blocker** (a captive-portal `200 + HTML` recurs on every `onResume`; the lenient parser would have handed the mirror a trusted `[]`). **Unit-tested** (`strict page parsers return NULL on a non-favorites 200 body`) and exercised on-device (Task 4 Step 5).
  - **Never delete on a failed/partial fetch:** the reads return `null` on token/userId missing OR **any** page failure (stricter than the brief — a partial page never reaches the mirror); `reconcileFavorites(null, …)` returns `(empty, empty)`; the pull passes the provider result straight in, so a `null` reconciles nothing. **Unit-tested** (`reconcileFavorites on a NULL (failed) fetch adds nothing and removes nothing`).
  - **Never delete a LOCAL-source favorite:** the stale set filters `localKeys` to `"TIDAL:"` keys before diffing. Unit-tested (`… never removes a LOCAL-source favorite even against an empty remote`).
  - **Idempotent:** unchanged remote+local -> `(empty, empty)`. Unit-tested.
  - **Only a genuinely-empty CONFIRMED fetch (`{"items":[],"totalNumberOfItems":0}` -> trusted `[]`) can delete** — the correct behavior (the user really cleared their Tidal favorites). A genuine empty still decodes; only a *bad-body* empty becomes `null`. Unit-tested (`strict page parsers decode a genuine page (incl a genuine EMPTY) to a trusted non-null list`).
- **The reconcile reuses, never re-declares.** `reconcileFavorites` calls the existing pure `newRemoteFavorites` for the add half; only the stale-set and null-guard are new.
- **`unfavoriteTrack` removes only the favorite row.** Resolves the id via `TrackDao.bySourceId`, deletes via `FavoriteDao.delete(trackId)`; the `tracks` row survives (playlist/history-safe); no-op when absent. DB-tested.
- **The nullable-read change is contained.** Grep-verified: the sole callers of the three provider reads are the three pulls (both rewritten here); `pullAll` is the sole caller of the pulls; no test constructs `CollectionPullSummary`. Nothing else compiles against the changed types.
- **Throttle never wedges.** Time-based (`elapsedRealtime`), stamped on attempt, no failure reset -> the next trigger retries after `THROTTLE_MS`; `force = true` always bypasses. `SystemClock.elapsedRealtime()` matches existing codebase precedent (SleepTimer/PlaybackService) and is immune to wall-clock jumps.
- **`onResume` reaches the right VM.** `MainActivity` holds `viewModel` via `by viewModels()` — the same instance passed into `MainScreen` — so the plain Activity override needs no Compose lifecycle plumbing.
- **Strict parser added without disturbing the lenient one.** `favoriteXPageOrNull` and the lenient `favoriteXFrom` share extracted private projections (`toFavoriteX`); the lenient functions keep their signatures + `getOrDefault(emptyList())` behavior and their Slice-2/3 tests. Requiring `totalNumberOfItems` breaks no existing test (every positive fixture carries it; degrade-to-empty tests still pass via the lenient `runCatching`). The `…Response` DTOs are `private` — not a public-API change. `favoritePlaylistsFrom` untouched.
- **No schema change / migration.** `unfavoriteTrack` uses existing tables + DAOs; `Daos.kt`/`Entities.kt`/`AuxenDatabase.kt` are untouched (Task 4 Step 7 asserts it).
- **`CollectionPullSummary` change is complete.** One field added (`removed`); the sole construction (`pullAll`) and sole read (the status string) both updated; no test constructs it, so nothing else breaks.
- **Roborazzi goldens unaffected** — no Compose component or parameter added; only a status string and a comment change.
- **KNOWN LIMITATION (accepted):** playlists are additive-only (not mirrored) — a playlist un-favorited in Tidal persists as a stale shell; a documented follow-up, never a deletion/data-loss risk.
- **Tokens never logged; internal-v1 auth/streaming untouched; every gradle line carries `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`; commits use explicit pathspecs.**
- **Scope:** 3 code tasks (10 files) + 1 on-device checkpoint (incl. the airplane-mode AND captive-portal/200-bad-body safety tests) — within the brief's ~4-task budget. Task 1 now also carries the strict favorites parser (pure, so it belongs with the tested core). The brief's separate "provider nullable" and "mirror pulls" tasks are merged (they cannot compile apart); the brief's separate "reconcile"/"unfavoriteTrack" work is merged into the single tested-core Task 1.
