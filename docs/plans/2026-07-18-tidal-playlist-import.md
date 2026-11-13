# Slice 4 — Tidal Playlist Import (Pull-Only) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import the user's **Tidal favorite playlists** (probed live: **6** — 3 their own + 3 followed, sizes 4→100 tracks, ~280 tracks total) into the Collection **Playlists** tab so they appear — and play — alongside locally-created playlists, on the **internal-v1** API the rest of the app already streams and searches through (`api.tidal.com/v1`). Today the Playlists tab lists ONLY locally-created playlists (`LibraryRepository.playlists()`), so a user with 6 Tidal playlists sees only what they made in-app. **Pull-only:** importing Tidal playlists so they render + play locally; creating or editing a Tidal playlist FROM the app is out of scope (no `TidalProvider` playlist writes).

**This is the direct sibling of Slice 3** (`docs/plans/2026-07-18-tidal-favorite-tracks.md`, shipped) — it reuses the same track parser and the same `TidalFavorites`/`TidalProvider`/`TidalCollectionSync` patterns. Like Slice 3 it is **materially smaller** than the album/artist Slice 2 because the local surface already exists:

- **No new Room table, no migration, no schema change.** `PlaylistEntity` (`db/Entities.kt:91-98`) **already** declares `val source: String? = null` and `@ColumnInfo(name = "tidal_playlist_id") val tidalPlaylistId: String? = null` — present since the v1 schema but never read/written today (`createPlaylist` leaves both null). A Tidal-imported playlist reuses `PlaylistEntity` + `PlaylistTrackEntity` **as-is**: stamp `source = "TIDAL"` + `tidalPlaylistId = <uuid>`. **DB stays `version = 3`** (verified in `db/AuxenDatabase.kt:23`). This slice touches no `@Entity`, no `AuxenDatabase`, no migration. Adding two `@Query` methods to `PlaylistDao` is a compile-time query (validated against the existing `playlists` table columns `id`/`tidal_playlist_id`, both present) — **not** a schema change and **not** a migration.
- **No new track parser.** `TidalV1PageTrack.toTrack()` (`provider/tidal/TidalArtistPage.kt:259-277`) already maps a v1 track JSON object → a fully-playable `Track` (`id`→`sourceId`, `artists[0].name`→`artist` [there is NO singular `artist` key — see the DTO's KDoc `TidalArtistPage.kt:111-123`], `album.title`→`album`, `album.cover`→`albumArtUrl` via `tidalV1CoverUrl`, duration, `trackNumber`, `volumeNumber`→`discNumber`, `explicit`, `audioQuality`→format/quality, `source` hardcoded `TIDAL`). It is already reused by album-detail, multi-type search, and Slice 3's favorite tracks. Slice 4 reuses it once more, plus `parseIso8601Millis` (`provider/tidal/TidalFavorites.kt:114`) for the `created` timestamp.
- **No UI change, no playback change, no new Compose component.** The Playlists tab (`CollectionScreen.kt` tab 3, `else->` branch) renders `items(playlists)` → a color-dot + name, tap → `onOpenPlaylist(playlist.id)` — **source-blind**. `PlaylistDetailScreen.kt` loads via `viewModel.loadPlaylist(playlistId)`, tracks from `viewModel.playlistTracks`, plays via `viewModel.playAll(tracks)` — all generic. A Tidal-track playlist renders + plays with ZERO screen change (`playAll` → `Graph.mediaItemFor` → `auxen://tidal/<id>` for Tidal tracks). Both screens are **untouched**, so **Roborazzi goldens are unaffected** (no new component, no new parameter).
- **No `Graph` wiring change.** `Graph.tidalCollectionSync` (`AuxenApp.kt:148`, wired `:182`), `Graph.library` (`:146`), `Graph.tidal` (`:142`) are already consumed by `PlayerViewModel`. Slice 4 only adds methods to the existing `TidalCollectionSync`/`TidalProvider`/`LibraryRepository` and one branch to the existing `PlayerViewModel.loadPlaylist`.

**Architecture:** Additive, mirroring Slice 3 exactly:

- **Pure playlist parse + request URLs (unit-tested core)** — a new `TidalPlaylists.kt` (mirroring `TidalFavorites.kt`) with the internal-v1 response DTOs, the `favoritePlaylistsFrom` (LIST → `TidalPlaylist`) and `playlistTracksFrom` (ITEMS → tracks, videos dropped) projections reusing `TidalV1PageTrack.toTrack` + `parseIso8601Millis`, and the `favoritePlaylistsUrl`/`playlistItemsUrl` request builders. No network in the unit tests.
- **`TidalProvider` playlist reads** — a paginated `favoritePlaylists()` (LIST) and `playlistItems(uuid)` (a playlist's tracks), mirroring the shipped `favoriteTracks()` (`TidalProvider.kt:499-513`), reusing the private `get`/`validToken`, `userId`, `countryCode`, `API_BASE`, `FAVORITES_PAGE`/`FAVORITES_MAX`. Read-only — no writes.
- **DB/repo idempotent import (in code, no migration)** — two `PlaylistDao` queries (`byId`, `playlistByTidalId`) + `LibraryRepository` `importTidalPlaylistShell(uuid, name)` (find-or-create by uuid) and `importTidalPlaylistTracks(playlistId, tracks)` (set membership in Tidal order). A Robolectric in-memory-Room test proves idempotency (re-import same uuid → same id, no dup) + Tidal-order membership.
- **Sync (list-eager) + lazy tracks** — extend `TidalCollectionSync` with `pullPlaylists()` (LIST-only shells, folded into `pullAll()`; `CollectionPullSummary` gains a `playlists` count) and `importPlaylistTracks(playlistId, uuid)` (fetch + set membership), wired into the **existing** `PlayerViewModel.loadPlaylist` so a Tidal playlist's tracks are fetched the first time it is opened.

**Design decisions (locked for this slice):**

- **DECIDED — trigger granularity = LIST-EAGER + TRACKS-LAZY.** This is the perf-critical call. `pullPlaylists()` (folded into `pullAll()`, which auto-runs once-per-process on Collection open, signed-in-only) fetches ONLY the playlist LIST (one cheap call for 6 playlists) and find-or-creates a local `PlaylistEntity` **shell** per playlist (`source=TIDAL`, `tidalPlaylistId=uuid`, name). It does NOT fetch the ~280 tracks. A playlist's tracks are fetched **lazily** the first time it is opened: `PlayerViewModel.loadPlaylist(id)` reads the local tracks; if the playlist is a Tidal shell (`source == "TIDAL"`, has a uuid) with **no local tracks**, it fetches `playlists/{uuid}/items`, imports them in Tidal order, and re-reads. Subsequent opens use the local copy (no re-fetch). This keeps the first Collection open fast and only pays a playlist's track cost when it is actually opened.
  - **Key UX tradeoff (FLAGGED):** a just-synced playlist shows its tracks only after you open it once (a brief lag on that first open). This is the right default for ~280 tracks across 6 playlists. The alternative — eager-fetch-all-tracks-on-sync — would make every Collection open pull ~280 tracks even for playlists never opened. If the user prefers eager, `pullAll()` would call `importPlaylistTracks` for every shell instead (spelled out below so it can be redirected without re-planning).
- **DECIDED — idempotent import by uuid in CODE (no unique index → no migration).** There is no unique index on `tidal_playlist_id` (adding one WOULD need a migration), so uniqueness is enforced by a find-by-uuid-first path: `PlaylistDao.playlistByTidalId(uuid)` + `LibraryRepository.importTidalPlaylistShell` which returns the existing id when found and inserts otherwise. A re-sync therefore never duplicates a playlist. Proven by the Task-3 DB test.
- **DECIDED — membership reconciliation on import = clear + re-insert in Tidal order.** `importTidalPlaylistTracks` upserts each fetched track then rewrites the ordering via the existing `PlaylistDao.reorder` (`clearTracks` + `insertTrack` by index), so an imported playlist mirrors Tidal's order exactly and a re-import reflects Tidal changes.
- **RECOMMENDED, FLAGGED as a UX call — imported playlists are create-only shells; local edits persist; NOTHING is auto-clobbered by a routine sync.** `importTidalPlaylistShell` is find-**or**-create (it does NOT update an existing shell's name), and the track import fires only when a playlist has **no local tracks** (the lazy guard). So after the first open: a local **rename**, **reorder**, or **track-removal** of an imported playlist **persists** — a routine re-sync neither renames the existing shell nor re-imports a populated playlist's tracks. **Deleting** an imported playlist removes the LOCAL copy only; the next sync recreates an empty shell (which re-fetches its tracks on the next open). The consequence, flagged: **Tidal-side changes** to an already-imported playlist (a rename, or added/removed tracks on Tidal) do **not** propagate without a delete-and-resync. A per-playlist "refresh from Tidal" affordance is **deferred** (it would add UI to `PlaylistDetailScreen` and is out of this slice's ~5-task budget). Recommendation: do NOT specially gate rename/reorder/remove for `source==TIDAL` this slice — local edits are useful and non-destructive; the delete+resync path covers refresh. If the user would rather imported playlists be strictly read-only mirrors, we would gate those actions when `source==TIDAL` (a follow-up).
- **DECIDED — playlist ART is deferred.** The Playlists tab renders a color dot + name; `PlaylistDetailScreen` renders the name. `PlaylistEntity` has no art column (only `color`), so storing the Tidal playlist cover would need a column + migration — not worth it this slice. Imported shells get the same default amber `color` (`#d4a039`) `createPlaylist` uses, so the color dot renders. The probed `image`/`squareImage` cover ids are ignored. (`TidalPlaylist` carries no cover field.)
- **Pull-only.** No `TidalProvider` playlist write methods. Creating/editing a Tidal playlist from the app is out of scope.

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), OkHttp 4.12 (`HttpUrl`), Room 2.7.1 (existing `playlists`/`playlist_tracks`/`tracks` tables, **no schema change**), Compose (Collection/PlaylistDetail — unchanged), JUnit4 + Robolectric 4.14.1. `java.time` for the ISO-8601 `created` timestamp (via the existing `parseIso8601Millis`). minSdk 26 / compileSdk 35 / Media3 1.5.1.

## Global Constraints

- **Every gradle command must carry `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`.** The system default JDK breaks this project's Gradle with a cryptic `java.lang.IllegalArgumentException: 25.0.3`. Every `Run:` line below already carries the prefix — do not drop it. Never pipe gradle through `tail`/`head` and read the pipe's exit status; read the real output for `BUILD SUCCESSFUL`.
- **Internal-v1 only. Never log or print `accessToken`/`refreshToken`.** Only human-readable titles/counts go to Logcat or any status string. The provider's `Authorization: Bearer $token` header is built inline in the existing private `get` and never logged. `TidalAuth.kt` and streaming are untouched — a playlist's Tidal tracks still stream via `getStreamInfoById`.
- **Do not unit-test live HTTP execution** (internal-v1 precedent — every `TidalProvider` suspend method is untested; its pure `*From`/`*Url` helpers are). Unit-test **pure** logic only: the playlist JSON parse + the request-URL shapes (Task 1). The one non-pure test added (Task 3) is a Robolectric **in-memory Room** test — not live HTTP — matching the codebase's DB-test discipline (`LibraryRepositoryTest`, `FavoriteCollectionDaoTest`). The pull/lazy-import suspend orchestration (Task 4) stays untested (network + ViewModel precedent).
- **Reuse, never re-declare — a duplicate declaration is a Kotlin "conflicting declarations" COMPILE ERROR.** `TidalV1PageTrack` + `TidalV1PageTrack.toTrack()` (`TidalArtistPage.kt`), `tidalV1CoverUrl` (reached transitively through `toTrack`), `parseIso8601Millis` (`TidalFavorites.kt`), `PlaylistEntity`/`PlaylistTrackEntity` (`Entities.kt`), `PlaylistDao.insert`/`rename`/`reorder`/`clearTracks`/`tracksIn` (`Daos.kt`), `LibraryRepository.upsert`/`playlists`/`createPlaylist`/`playlistTracks` (`LibraryRepository.kt`), `PlayerViewModel.playlistTracks`/`loadPlaylist` (`PlayerViewModel.kt`), `CollectionPullSummary`/`newRemoteFavorites` (`TidalCollectionSync.kt`), `FAVORITES_PAGE`/`FAVORITES_MAX`/`API_BASE`/`get`/`validToken`/`userId`/`countryCode`/`json` (`TidalProvider.kt`), `Source`/`Track` (`model`) all already exist and are **reused, never re-declared**. Every NEW name introduced here (`TidalPlaylist`, `TidalPlaylistPage`, `favoritePlaylistsFrom`, `playlistTracksFrom`, `favoritePlaylistsUrl`, `playlistItemsUrl`, `favoritePlaylists`, `playlistItems`, `PlaylistDao.byId`, `PlaylistDao.playlistByTidalId`, `LibraryRepository.playlist`, `LibraryRepository.playlistByTidalId`, `importTidalPlaylistShell`, `importTidalPlaylistTracks`, `pullPlaylists`, `importPlaylistTracks`, and the `CollectionPullSummary.playlists` field) was grep-verified to have **zero** existing declarations in `app/src`.
- **When new code in an EXISTING file names a type that file does not import, ADD THE IMPORT — Kotlin resolves per-file.** The instances in this slice:
  - New file `TidalPlaylists.kt` declares `TidalPlaylistPage(val tracks: List<Track>, …)`, so it imports `io.github.auxen.model.Track` (plus `kotlinx.serialization.*`, `okhttp3.HttpUrl`). `parseIso8601Millis` and `TidalV1PageTrack.toTrack` are same-package top-level declarations → NO import.
  - `LibraryRepository.kt` today imports `PlaylistEntity`, `SourcePriority`, `Track` but **NOT `Source`** — Task 3 names `Source.TIDAL.name`, so it must add `import io.github.auxen.model.Source`.
  - `LibraryRepositoryTest.kt` today imports `assertEquals`/`assertTrue` but **NOT `assertNull`** — Task 3's new tests use `assertNull`, so add `import org.junit.Assert.assertNull`.
  - `TidalProvider.kt` **already** imports `Track`; the new reads name only `Track`/`TidalPlaylist`/`TidalPlaylistPage` (same package) → no new import. `TidalCollectionSync.kt` **already** imports `Source`. `PlayerViewModel.kt` **already** imports `Source` (`Source.TIDAL` used at `:651`/`:660`) and `Track` → no new import. `TidalPlaylistsTest.kt` (new) imports `io.github.auxen.model.Source` + `kotlinx.serialization.json.Json` + JUnit asserts.
- **`CollectionPullSummary` is a data class with four required fields after this slice** (`albums`, `artists`, `tracks`, `playlists`). Its only construction site is `pullAll()` (`TidalCollectionSync.kt:60-64`) and its only read site is the status string in `PlayerViewModel.syncFavoritesFromTidal` (`PlayerViewModel.kt:636`) — both updated in Task 4. No test constructs it (grep-verified: the only textual matches are the declaration and `pullAll`). Adding the field therefore breaks no existing test.
- **Reuse `FAVORITES_PAGE`/`FAVORITES_MAX` for playlist pagination — no new page constants.** They are generic paging bounds (`50` / `5000`); the largest probed playlist (100 tracks) is two pages of 50. `playlistItems` paginates on the **raw** page item count (`TidalPlaylistPage.rawItemCount`), NOT the filtered track count, because dropping non-track items (videos) from a full 50-item page would otherwise look like the last page and truncate a large playlist.
- **Room `playlists`/`playlist_tracks` schema is untouched — no migration.** Adding `PlaylistDao.byId`/`playlistByTidalId` (`@Query` reads over existing columns) and stamping the existing `source`/`tidal_playlist_id` columns does NOT change the schema. `Entities.kt`/`AuxenDatabase.kt` stay untouched; DB stays `version = 3`. Task 5 Step 5 asserts it.
- **Git: explicit pathspecs only.** Never `git add -A` / `git add .` / `git commit -a` — the working tree carries unrelated untracked files (`.playwright-mcp/`, other `docs/plans/*`) that must never be committed.
- Every code step below shows the actual code. No `TODO`/"add error handling"/"similar to above" placeholders.

---

### Task 1: Pure Tidal playlist parse + request URLs (`TidalPlaylists.kt`)

The unit-tested core. `GET users/{userId}/favorites/playlists` returns the paginated LIST wrapper `{ "items": [ { "created", "item": { "uuid", "title", "numberOfTracks", "image", "squareImage" } } ], "totalNumberOfItems": N }` (probed live: total = 6); the playlist id is a **uuid string** (e.g. `30631867-b9d8-4bb1-a525-44019daf4b5b`), not numeric. `GET playlists/{uuid}/items` returns `{ "items": [ { "type": "track", "item": {<v1 track>} } ], "totalNumberOfItems": N }` where each `item` is exactly the shape `TidalV1PageTrack` already models, and a playlist may also contain videos (`type != "track"`) that must be dropped. Project each **purely**, reusing `TidalV1PageTrack.toTrack()` (no new track parser) and `parseIso8601Millis` (`created`).

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalPlaylists.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalPlaylistsTest.kt`

**Interfaces:**
- Consumes: `TidalV1PageTrack` + `TidalV1PageTrack.toTrack()` (`TidalArtistPage.kt`, same package — REUSED), `parseIso8601Millis` (`TidalFavorites.kt`, same package — REUSED), `tidalV1CoverUrl` (reached transitively through `toTrack`). All REUSED, never re-declared.
- Produces: `data class TidalPlaylist(val uuid: String, val title: String, val addedAtMillis: Long?)`; `data class TidalPlaylistPage(val tracks: List<Track>, val rawItemCount: Int)`; `fun favoritePlaylistsFrom(String, Json): List<TidalPlaylist>`; `fun playlistTracksFrom(String, Json): TidalPlaylistPage`; `fun favoritePlaylistsUrl(apiBase, userId, countryCode, limit, offset): HttpUrl`; `fun playlistItemsUrl(apiBase, uuid, countryCode, limit, offset): HttpUrl`.

- [ ] **Step 1: Write the failing test**

Create `TidalPlaylistsTest.kt`:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure internal-v1 playlist parsing + request-URL shapes. No live HTTP — the
 * TidalProvider suspend methods that call these are untested (internal-v1
 * precedent); only the pure projection + URL builders are.
 *
 * Fixtures use the probed wrappers: LIST `{ items:[{ created, item:{ uuid,
 * title, numberOfTracks } }], totalNumberOfItems }`; ITEMS `{ items:[{ type,
 * item:{<v1 track>} }], totalNumberOfItems }` where a `type != "track"` item
 * (a video) must be dropped.
 */
class TidalPlaylistsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val playlistsPage = """
        {
          "items": [
            { "created": "2023-05-01T12:00:00.000+0000",
              "item": { "uuid": "30631867-b9d8-4bb1-a525-44019daf4b5b",
                        "title": "2024", "numberOfTracks": 100,
                        "image": "aa-bb-cc", "squareImage": "dd-ee-ff" } },
            { "created": null,
              "item": { "uuid": "", "title": "Unusable (blank uuid)" } }
          ],
          "totalNumberOfItems": 2
        }
    """.trimIndent()

    private val itemsPage = """
        {
          "items": [
            { "type": "track",
              "item": { "id": 12345, "title": "Nightcall", "duration": 258.0,
                        "trackNumber": 1, "volumeNumber": 1, "explicit": false,
                        "audioQuality": "LOSSLESS",
                        "artists": [ { "id": 11, "name": "Kavinsky" } ],
                        "album": { "id": 75413016, "title": "OutRun", "cover": "aa-bb-cc" } } },
            { "type": "video",
              "item": { "id": 999, "title": "Some Music Video" } },
            { "type": "track",
              "item": { "id": 222, "title": "No Album", "artists": [], "album": null } }
          ],
          "totalNumberOfItems": 3
        }
    """.trimIndent()

    @Test
    fun `favoritePlaylistsFrom projects uuid, title and addedAt, dropping blank-uuid rows`() {
        val playlists = favoritePlaylistsFrom(playlistsPage, json)
        assertEquals(1, playlists.size) // the blank-uuid row is dropped
        assertEquals("30631867-b9d8-4bb1-a525-44019daf4b5b", playlists[0].uuid)
        assertEquals("2024", playlists[0].title)
        assertEquals(1682942400000L, playlists[0].addedAtMillis) // 2023-05-01T12:00:00Z
    }

    @Test
    fun `favoritePlaylistsFrom returns empty list on garbage or an error envelope`() {
        assertEquals(emptyList<TidalPlaylist>(), favoritePlaylistsFrom("not json", json))
        assertEquals(emptyList<TidalPlaylist>(), favoritePlaylistsFrom("{\"status\":404}", json))
    }

    @Test
    fun `playlistTracksFrom keeps track items, drops videos, and reports the raw item count`() {
        val page = playlistTracksFrom(itemsPage, json)
        assertEquals(3, page.rawItemCount) // RAW items, before filtering — drives pagination
        assertEquals(2, page.tracks.size)  // the video item is dropped
        val first = page.tracks[0]
        assertEquals("12345", first.sourceId)
        assertEquals("Nightcall", first.title)
        assertEquals("Kavinsky", first.artist) // artists[0].name — there is NO singular `artist` key
        assertEquals("OutRun", first.album)
        assertEquals(Source.TIDAL, first.source)
        assertEquals("FLAC", first.format) // LOSSLESS -> FLAC via toTrack's quality map
        assertEquals("https://resources.tidal.com/images/aa/bb/cc/640x640.jpg", first.albumArtUrl)
    }

    @Test
    fun `playlistTracksFrom tolerates a missing album and empty artists`() {
        val second = playlistTracksFrom(itemsPage, json).tracks[1]
        assertEquals("222", second.sourceId)
        assertEquals("", second.artist) // empty artists -> artists.firstOrNull()?.name.orEmpty()
        assertNull(second.album) // null album -> album?.title
        assertNull(second.albumArtUrl) // null album -> tidalV1CoverUrl(null)
    }

    @Test
    fun `playlistTracksFrom returns an empty page on garbage`() {
        val page = playlistTracksFrom("not json", json)
        assertTrue(page.tracks.isEmpty())
        assertEquals(0, page.rawItemCount)
    }

    @Test
    fun `favorite playlists read URL is paged and country-scoped, most-recent-first`() {
        val url = favoritePlaylistsUrl("https://api.tidal.com/v1", "42", "US", limit = 50, offset = 100)
        assertEquals("/v1/users/42/favorites/playlists", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("100", url.queryParameter("offset"))
        assertEquals("DATE", url.queryParameter("order"))
        assertEquals("DESC", url.queryParameter("orderDirection"))
        assertEquals("US", url.queryParameter("countryCode"))
    }

    @Test
    fun `playlist items URL targets the playlist and is paged and country-scoped`() {
        val url = playlistItemsUrl(
            "https://api.tidal.com/v1", "30631867-b9d8-4bb1-a525-44019daf4b5b", "US", limit = 50, offset = 50,
        )
        assertEquals("/v1/playlists/30631867-b9d8-4bb1-a525-44019daf4b5b/items", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("50", url.queryParameter("offset"))
        assertEquals("US", url.queryParameter("countryCode"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalPlaylistsTest*'`
Expected: FAIL — compilation error: `favoritePlaylistsFrom`, `playlistTracksFrom`, `favoritePlaylistsUrl`, `playlistItemsUrl`, `TidalPlaylist`, and `TidalPlaylistPage` are unresolved references (`TidalPlaylists.kt` does not exist yet).

- [ ] **Step 3: Implement `TidalPlaylists.kt`**

Create `TidalPlaylists.kt`:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The pure parts of the user's Tidal PLAYLISTS on the INTERNAL v1 API — the
 * favorite (own + followed) playlists the desktop reads via
 * `session.user.favorites.playlists()`, plus a playlist's items. Every
 * `*From(body, json)` / `*Url(...)` here is pure and unit-tested; the suspend
 * methods that call the network live on [TidalProvider] (untested, internal-v1
 * precedent). Reuses the existing [TidalV1PageTrack.toTrack] projection and
 * [parseIso8601Millis] — NO new track parser, NO new timestamp parser.
 *
 * Pull-only: this models READING playlists, never creating/editing one on Tidal.
 */

/**
 * A Tidal favorite playlist, projected narrow for the Playlists tab. Identified
 * by its [uuid] — a UUID string, NOT a numeric id (the reason imported playlists
 * key on `tidal_playlist_id`, not `source:sourceId`). Art is DEFERRED this slice
 * (PlaylistEntity has no art column), so no cover field.
 */
data class TidalPlaylist(val uuid: String, val title: String, val addedAtMillis: Long?)

/**
 * One page of a playlist's items → its playable [Track]s (non-track items like
 * videos dropped) PLUS the page's RAW item count. The raw count is carried
 * because pagination cannot key on the filtered track count: a full 50-item page
 * containing one video would project to 49 tracks and be mistaken for the last
 * page, truncating a large playlist. [TidalProvider.playlistItems] paginates on
 * [rawItemCount] and appends [tracks].
 */
data class TidalPlaylistPage(val tracks: List<Track>, val rawItemCount: Int)

// ---- internal-v1 response DTOs (every field nullable/defaulted — the tolerance
// every DTO in this package applies to a reverse-engineered endpoint) ----

@Serializable
private data class TidalV1PlaylistInfo(val uuid: String = "", val title: String = "", val numberOfTracks: Int = 0)

@Serializable
private data class TidalV1FavoritePlaylistItem(val created: String? = null, val item: TidalV1PlaylistInfo? = null)

@Serializable
private data class TidalV1FavoritePlaylistsResponse(
    val items: List<TidalV1FavoritePlaylistItem> = emptyList(),
    val totalNumberOfItems: Int = 0,
)

@Serializable
private data class TidalV1PlaylistItemWrapper(val type: String? = null, val item: TidalV1PageTrack? = null)

@Serializable
private data class TidalV1PlaylistItemsResponse(
    val items: List<TidalV1PlaylistItemWrapper> = emptyList(),
    val totalNumberOfItems: Int = 0,
)

/**
 * Pure: one `favorites/playlists` page body → [TidalPlaylist]s. A row with a
 * blank uuid (unusable — the uuid IS the import key) is dropped. Same
 * degrade-to-empty discipline as [favoriteAlbumsFrom]: a malformed body / v1
 * error envelope yields an empty list, never a crash.
 */
fun favoritePlaylistsFrom(body: String, json: Json): List<TidalPlaylist> = runCatching {
    json.decodeFromString<TidalV1FavoritePlaylistsResponse>(body).items.mapNotNull { wrapper ->
        val info = wrapper.item ?: return@mapNotNull null
        if (info.uuid.isBlank()) return@mapNotNull null
        TidalPlaylist(uuid = info.uuid, title = info.title, addedAtMillis = parseIso8601Millis(wrapper.created))
    }
}.getOrDefault(emptyList())

/**
 * Pure: one playlist `items` page body → a [TidalPlaylistPage]. Keeps only
 * `type == "track"` items (a playlist may also contain videos), projecting each
 * via the existing [TidalV1PageTrack.toTrack] (NO new track parser). Carries the
 * raw item count so the caller can detect the last page despite the filtering.
 * Degrades to an empty page on a malformed body / v1 error envelope.
 */
fun playlistTracksFrom(body: String, json: Json): TidalPlaylistPage = runCatching {
    val response = json.decodeFromString<TidalV1PlaylistItemsResponse>(body)
    val tracks = response.items.filter { it.type == "track" }.mapNotNull { it.item?.toTrack() }
    TidalPlaylistPage(tracks = tracks, rawItemCount = response.items.size)
}.getOrDefault(TidalPlaylistPage(emptyList(), 0))

// ---- request URL builders (pure) ----

/** `GET {apiBase}/users/{userId}/favorites/playlists?limit&offset&order=DATE&orderDirection=DESC&countryCode`. */
fun favoritePlaylistsUrl(apiBase: String, userId: String, countryCode: String, limit: Int, offset: Int): HttpUrl =
    "$apiBase/users/$userId/favorites/playlists".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("order", "DATE")
        .addQueryParameter("orderDirection", "DESC")
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `GET {apiBase}/playlists/{uuid}/items?limit&offset&countryCode` (a playlist's tracks, paged). */
fun playlistItemsUrl(apiBase: String, uuid: String, countryCode: String, limit: Int, offset: Int): HttpUrl =
    "$apiBase/playlists/$uuid/items".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("countryCode", countryCode)
        .build()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalPlaylistsTest*'`
Expected: PASS (all seven new tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalPlaylists.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalPlaylistsTest.kt
git commit -m "feat(android): pure Tidal playlist parse + request URLs

Adds TidalPlaylists.kt — favoritePlaylistsFrom (LIST -> TidalPlaylist by uuid)
and playlistTracksFrom (ITEMS -> tracks, dropping non-track/video items, with
the raw item count for pagination) reusing the existing TidalV1PageTrack.toTrack
projection and parseIso8601Millis (no new track parser) — plus the
favoritePlaylistsUrl/playlistItemsUrl request builders. Pure and unit-tested
against the probed internal-v1 wrapper shapes; no network.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 2: `TidalProvider` — read favorite playlists + a playlist's items

Wire the pure layer to the network. Mirrors the shipped `favoriteTracks()` (`TidalProvider.kt:499-513`): a paginated read reusing `FAVORITES_PAGE`/`FAVORITES_MAX` and the private `get`/`validToken`/`userId`/`countryCode`/`API_BASE`/`json`. `favoritePlaylists()` is user-scoped (needs `userId`); `playlistItems(uuid)` is playlist-scoped (no `userId`). Read-only — no writes (pull-only).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt`

**Interfaces:**
- Consumes: `favoritePlaylistsUrl`/`playlistItemsUrl`/`favoritePlaylistsFrom`/`playlistTracksFrom` (Task 1); the existing private `validToken()`/`get()`, `userId`, `countryCode`, `json`, `API_BASE`, `FAVORITES_PAGE`, `FAVORITES_MAX`. No new imports (`Track` already imported).
- Produces: `favoritePlaylists(): List<TidalPlaylist>`; `playlistItems(uuid: String): List<Track>`. (Task 1's parse/URL tests cover the pure surface these call; the network methods are untested by precedent — this task's gate is `assembleDebug` + Task 1 green.)

- [ ] **Step 1: Add the two paginated reads**

In `TidalProvider.kt`, immediately after `removeFavoriteTrack` (`:524-530`) and before the `// Internals` region (`:532`), add:

```kotlin
    /**
     * The user's Tidal favorite PLAYLISTS (own + followed), paginated — the
     * internal-v1 port of `session.user.favorites.playlists()`. LIST only: each
     * carries its uuid + title, NOT its tracks (those are fetched lazily via
     * [playlistItems] when a playlist is opened). Same not-logged-in / degrade
     * contract as [favoriteTracks] (a missing collection degrades the Playlists
     * tab, never breaks it).
     */
    suspend fun favoritePlaylists(): List<TidalPlaylist> {
        val token = validToken() ?: return emptyList()
        val uid = userId ?: return emptyList()
        val all = mutableListOf<TidalPlaylist>()
        var offset = 0
        while (offset < FAVORITES_MAX) {
            val url = favoritePlaylistsUrl(API_BASE, uid, countryCode, FAVORITES_PAGE, offset)
            val body = runCatching { get(url.toString(), token) }.getOrNull() ?: break
            val page = favoritePlaylistsFrom(body, json)
            all += page
            if (page.size < FAVORITES_PAGE) break
            offset += FAVORITES_PAGE
        }
        return all
    }

    /**
     * One Tidal playlist's tracks by its [uuid], paginated — the internal-v1
     * `playlists/{uuid}/items`. Non-track items (e.g. videos) are dropped by
     * [playlistTracksFrom]; pagination advances on the RAW page item count (via
     * [TidalPlaylistPage.rawItemCount]) so filtering never truncates a large
     * playlist early. Metadata only: resolves no stream. Empty when not logged in
     * / any page fails.
     */
    suspend fun playlistItems(uuid: String): List<Track> {
        val token = validToken() ?: return emptyList()
        val all = mutableListOf<Track>()
        var offset = 0
        while (offset < FAVORITES_MAX) {
            val url = playlistItemsUrl(API_BASE, uuid, countryCode, FAVORITES_PAGE, offset)
            val body = runCatching { get(url.toString(), token) }.getOrNull() ?: break
            val page = playlistTracksFrom(body, json)
            all += page.tracks
            if (page.rawItemCount < FAVORITES_PAGE) break
            offset += FAVORITES_PAGE
        }
        return all
    }
```

- [ ] **Step 2: Build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalPlaylistsTest*' --tests '*TidalProvider*' assembleDebug`
Expected: PASS; APK builds. (No new pure logic in this task — the reads are network; Task 1's tests cover the pure surface they call.)

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt
git commit -m "feat(android): TidalProvider reads Tidal playlists + a playlist's items

Adds paginated favoritePlaylists() (LIST of favorite own+followed playlists,
reusing FAVORITES_PAGE/MAX and the userId captured at /sessions bootstrap) and
playlistItems(uuid) (a playlist's tracks, paginating on the raw item count so
video-filtering never truncates a large playlist) over internal-v1
users/{id}/favorites/playlists and playlists/{uuid}/items, mirroring the shipped
favorite tracks and reusing the private get helper. Read-only (pull-only); auth
and streaming untouched; tokens never logged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 3: DB/repo idempotent import — `PlaylistDao` + `LibraryRepository`

The local surface for the import, idempotent by uuid **in code** (no unique index → no migration). Two `PlaylistDao` reads (`byId` for the lazy-fetch guard, `playlistByTidalId` for find-or-create) and two `LibraryRepository` writes: `importTidalPlaylistShell(uuid, name)` (find-or-create the `source=TIDAL` shell, returning its id) and `importTidalPlaylistTracks(playlistId, tracks)` (set membership in Tidal order, reusing the existing `PlaylistDao.reorder`), plus thin `playlist(id)`/`playlistByTidalId(uuid)` reads. A Robolectric in-memory-Room test proves idempotency + Tidal-order membership.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/Daos.kt` (add `PlaylistDao.byId`, `PlaylistDao.playlistByTidalId`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt` (add `playlist`, `playlistByTidalId`, `importTidalPlaylistShell`, `importTidalPlaylistTracks`; add `import io.github.auxen.model.Source`)
- Modify: `android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt` (add the idempotency + ordering tests; add `import org.junit.Assert.assertNull`)

**Interfaces:**
- Consumes: `PlaylistDao.insert`/`rename`/`reorder`/`tracksIn` (`Daos.kt`, REUSED); `LibraryRepository.upsert`/`playlists`/`playlistTracks` (REUSED); `PlaylistEntity` (REUSED); `Source` (`model`, newly imported here).
- Produces: `PlaylistDao.byId(id): PlaylistEntity?`; `PlaylistDao.playlistByTidalId(uuid): PlaylistEntity?`; `LibraryRepository.playlist(id): PlaylistEntity?`; `LibraryRepository.playlistByTidalId(uuid): PlaylistEntity?`; `LibraryRepository.importTidalPlaylistShell(uuid, name): Long`; `LibraryRepository.importTidalPlaylistTracks(playlistId, tracks)`.

- [ ] **Step 1: Write the failing tests**

Add to `LibraryRepositoryTest.kt` — first the import (after the existing `import org.junit.Assert.assertEquals`):

```kotlin
import org.junit.Assert.assertNull
```

then, inside the `LibraryRepositoryTest` class (after `playlistManagementRoundTrip`), the import tests:

```kotlin
    @Test
    fun importTidalPlaylistShellIsIdempotentByUuidAndStampsSource() = runBlocking {
        val id = repo.importTidalPlaylistShell("uuid-1", "2024")
        // Re-import the SAME uuid (even under a different Tidal-side name) -> the
        // SAME playlist id, no duplicate. Create-only: the first name sticks.
        val again = repo.importTidalPlaylistShell("uuid-1", "2024 (renamed on Tidal)")
        assertEquals(id, again)
        assertEquals(1, repo.playlists().first().size)

        val entity = repo.playlist(id)!!
        assertEquals("TIDAL", entity.source)
        assertEquals("uuid-1", entity.tidalPlaylistId)
        assertEquals("2024", entity.name)
    }

    @Test
    fun importTidalPlaylistTracksSetsMembershipInTidalOrderAndReimportMirrors() = runBlocking {
        val id = repo.importTidalPlaylistShell("uuid-1", "2024")
        val a = tidalTrack.copy(sourceId = "1", title = "A")
        val b = tidalTrack.copy(sourceId = "2", title = "B")
        val c = tidalTrack.copy(sourceId = "3", title = "C")

        repo.importTidalPlaylistTracks(id, listOf(a, b, c))
        assertEquals(listOf("A", "B", "C"), repo.playlistTracks(id).map { it.title })

        // A re-import mirrors Tidal exactly: a dropped track leaves, order follows.
        repo.importTidalPlaylistTracks(id, listOf(c, a))
        assertEquals(listOf("C", "A"), repo.playlistTracks(id).map { it.title })
    }

    @Test
    fun playlistByTidalIdFindsOnlyTidalShellsAndPlaylistReadsById() = runBlocking {
        val localId = repo.createPlaylist("Local Mix")
        val tidalId = repo.importTidalPlaylistShell("uuid-9", "Tidal Mix")

        assertEquals(tidalId, repo.playlistByTidalId("uuid-9")!!.id)
        assertNull(repo.playlistByTidalId("nope"))
        // A locally-created playlist has a null tidal_playlist_id and is not found by uuid.
        assertNull(repo.playlist(localId)!!.tidalPlaylistId)
        assertNull(repo.playlist(localId)!!.source)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*LibraryRepositoryTest*'`
Expected: FAIL — the `LibraryRepositoryTest` class fails to **compile**: `repo.importTidalPlaylistShell`, `repo.importTidalPlaylistTracks`, `repo.playlist`, and `repo.playlistByTidalId` are unresolved references (they do not exist yet).

- [ ] **Step 3: Add the two `PlaylistDao` reads**

In `Daos.kt`, inside `PlaylistDao` (after `insert`, `:138`), add:

```kotlin
    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    /** The imported shell for a Tidal playlist, keyed by its Tidal uuid (no unique index — LIMIT 1). */
    @Query("SELECT * FROM playlists WHERE tidal_playlist_id = :uuid LIMIT 1")
    suspend fun playlistByTidalId(uuid: String): PlaylistEntity?
```

- [ ] **Step 4: Add the `LibraryRepository` reads + writes**

In `LibraryRepository.kt`, add the import (in the existing import block, after `import io.github.auxen.model.SourcePriority`):

```kotlin
import io.github.auxen.model.Source
```

Then add, immediately after `deletePlaylist` (`:98`):

```kotlin
    /** The playlist row by id (with source + tidal_playlist_id), or null — the lazy-fetch guard reads it. */
    suspend fun playlist(id: Long): PlaylistEntity? = db.playlistDao().byId(id)

    /** An imported Tidal playlist by its Tidal [uuid], or null. */
    suspend fun playlistByTidalId(uuid: String): PlaylistEntity? = db.playlistDao().playlistByTidalId(uuid)

    /**
     * Find-or-create the LOCAL shell for a Tidal playlist, keyed by its Tidal
     * [uuid]. Idempotent in CODE (there is no unique index on tidal_playlist_id,
     * so uniqueness is enforced by finding first): a re-import of the same uuid
     * returns the SAME playlist id and never duplicates. Create-only — an
     * existing shell is returned untouched (a local rename is NOT clobbered by a
     * re-sync; the tradeoff: a Tidal-side rename does not propagate without
     * delete+resync). Stamps source = TIDAL + tidalPlaylistId = uuid, with the
     * default amber color so the tab's color-dot renders (playlist art is
     * deferred this slice). Creates only the shell — the tracks are imported
     * lazily by [importTidalPlaylistTracks] when the playlist is first opened.
     */
    suspend fun importTidalPlaylistShell(uuid: String, name: String): Long {
        db.playlistDao().playlistByTidalId(uuid)?.let { return it.id }
        return db.playlistDao().insert(
            PlaylistEntity(name = name, color = "#d4a039", source = Source.TIDAL.name, tidalPlaylistId = uuid),
        )
    }

    /**
     * Replace a playlist's membership with [tracks] in the given order — the
     * import of a Tidal playlist's items in Tidal order (a re-import mirrors
     * Tidal exactly). Upserts each track into `tracks` first, then rewrites the
     * ordering in one transaction (clear + re-insert by index) via the existing
     * [PlaylistDao.reorder].
     */
    suspend fun importTidalPlaylistTracks(playlistId: Long, tracks: List<Track>) {
        val trackIds = tracks.map { upsert(it) }
        db.playlistDao().reorder(playlistId, trackIds)
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*LibraryRepositoryTest*'`
Expected: PASS (the pre-existing playlist/favorite tests keep passing; the three new import tests pass).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/db/Daos.kt \
        android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt \
        android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt
git commit -m "feat(android): idempotent Tidal playlist import in PlaylistDao + LibraryRepository

Adds PlaylistDao.byId/playlistByTidalId (reads over the existing playlists
columns — no schema change) and LibraryRepository.importTidalPlaylistShell
(find-or-create by uuid, stamping source=TIDAL + tidal_playlist_id; idempotent in
code, no unique index/migration) + importTidalPlaylistTracks (set membership in
Tidal order via the existing reorder). A Robolectric in-memory Room test proves
re-import gives the same playlist id (no dup) and mirrors Tidal order. DB stays
version 3; Entities/AuxenDatabase untouched.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 4: Sync (list-eager) + tracks-lazy wiring — `TidalCollectionSync`, `PlayerViewModel`

Tie the read + import layers together. `pullPlaylists()` fetches the LIST and find-or-creates a shell per playlist (LIST-eager), folded into `pullAll()`; `CollectionPullSummary` gains a `playlists` count reported by the sync status string. `importPlaylistTracks(playlistId, uuid)` fetches a playlist's items and sets its membership. `PlayerViewModel.loadPlaylist` gains the tracks-lazy branch: a Tidal shell with no local tracks fetches + imports its items the first time it is opened.

**No new unit test in this task.** The idempotency + Tidal-order core is already proven by Task 3's DB test; `pullPlaylists`/`importPlaylistTracks`/`loadPlaylist` are network + ViewModel orchestration, untested by internal-v1 precedent (the shipped `pullFavoriteTracks`/`syncFavoritesFromTidal`/`loadPlaylist` carry no unit test either). Gate: all prior tests stay green + `assembleDebug`.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt` (`CollectionPullSummary.playlists`, `pullPlaylists`, `importPlaylistTracks`, `pullAll`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (`loadPlaylist` lazy branch, status string)

**Interfaces:**
- Consumes: `TidalProvider.favoritePlaylists()`/`playlistItems(uuid)` (Task 2); `LibraryRepository.playlistByTidalId`/`importTidalPlaylistShell`/`importTidalPlaylistTracks`/`playlist`/`playlistTracks` (Task 3); `Source` (`model`, already imported in both files); `Graph.tidalCollectionSync`/`Graph.library` (already wired).
- Produces: `TidalCollectionSync.pullPlaylists(): Int`; `TidalCollectionSync.importPlaylistTracks(playlistId, uuid): Int`; `CollectionPullSummary(albums, artists, tracks, playlists)`; the tracks-lazy `PlayerViewModel.loadPlaylist`.

- [ ] **Step 1: Extend `TidalCollectionSync` — pull the LIST, import a playlist's tracks, fold into the summary**

In `TidalCollectionSync.kt`, change the summary (`:10`) to carry a `playlists` count:

```kotlin
/** What one full pull added locally, per favorite type. */
data class CollectionPullSummary(val albums: Int, val artists: Int, val tracks: Int, val playlists: Int)
```

Add `pullPlaylists()` and `importPlaylistTracks()` after `pullFavoriteTracks()` (`:52-57`):

```kotlin
    /**
     * Pull the user's Tidal favorite playlists as local SHELLS — LIST-eager,
     * tracks-lazy. Find-or-creates a `source=TIDAL` [io.github.auxen.db.PlaylistEntity]
     * per remote playlist (idempotent by uuid) but does NOT fetch its ~tracks —
     * those are imported lazily by [importPlaylistTracks] when a playlist is first
     * opened, keeping the Collection-open pull to one cheap LIST call. Returns the
     * number of NEWLY-created shells (an already-imported playlist is a no-op, not
     * recounted); additive — never deletes a local playlist.
     */
    suspend fun pullPlaylists(): Int {
        val remote = tidal.favoritePlaylists()
        var added = 0
        remote.forEach { playlist ->
            if (library.playlistByTidalId(playlist.uuid) == null) added++
            library.importTidalPlaylistShell(playlist.uuid, playlist.title)
        }
        return added
    }

    /**
     * Fetch a Tidal playlist's items and set the local membership in Tidal order
     * (clear + re-insert). The tracks-lazy half of the import: called when a Tidal
     * playlist shell is first opened (see PlayerViewModel.loadPlaylist). Returns
     * the number of tracks imported.
     */
    suspend fun importPlaylistTracks(playlistId: Long, uuid: String): Int {
        val tracks = tidal.playlistItems(uuid)
        library.importTidalPlaylistTracks(playlistId, tracks)
        return tracks.size
    }
```

Change `pullAll()` (`:59-64`) to fold playlists in:

```kotlin
    /** One pull of all favorite types (playlists are LIST-eager; their tracks lazy). */
    suspend fun pullAll(): CollectionPullSummary = CollectionPullSummary(
        albums = pullFavoriteAlbums(),
        artists = pullFavoriteArtists(),
        tracks = pullFavoriteTracks(),
        playlists = pullPlaylists(),
    )
```

- [ ] **Step 2: Wire the tracks-lazy fetch + update the status string in `PlayerViewModel`**

In `PlayerViewModel.kt`, replace `loadPlaylist` (`:953-957`) — `Source` is already imported (used at `:651`/`:660`):

```kotlin
    fun loadPlaylist(id: Long) {
        viewModelScope.launch {
            val local = runCatching { Graph.library.playlistTracks(id) }.getOrDefault(emptyList())
            playlistTracks.value = local
            // Tracks-lazy import: a Tidal playlist is synced LIST-only (an empty
            // shell) until first opened. When a Tidal shell has no local tracks,
            // fetch its items now, import them in Tidal order, then re-read. Local
            // playlists and already-populated Tidal playlists skip the network.
            if (local.isEmpty()) {
                val playlist = runCatching { Graph.library.playlist(id) }.getOrNull()
                val uuid = playlist?.tidalPlaylistId
                if (playlist?.source == Source.TIDAL.name && uuid != null) {
                    runCatching { Graph.tidalCollectionSync.importPlaylistTracks(id, uuid) }
                    runCatching { Graph.library.playlistTracks(id) }.onSuccess { playlistTracks.value = it }
                }
            }
        }
    }
```

and, in `syncFavoritesFromTidal` (`:636`), extend the success status string to report playlists:

```kotlin
                onSuccess = { "Synced +${it.albums} album(s), +${it.artists} artist(s), +${it.tracks} track(s), +${it.playlists} playlist(s) from Tidal." },
```

- [ ] **Step 3: Run tests + build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalPlaylistsTest*' --tests '*LibraryRepositoryTest*' --tests '*TidalCollectionSyncTest*' assembleDebug`
Expected: PASS; APK builds. `TidalCollectionSyncTest` (the pure `newRemoteFavorites`/`favoriteAlbumEntityFor`/`favoriteArtistEntityFor` tests) keeps passing — it constructs no `CollectionPullSummary`, so the new `playlists` field does not touch it.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt
git commit -m "feat(android): sync Tidal playlist shells (list-eager) + lazy track import

TidalCollectionSync.pullPlaylists imports the favorite-playlist LIST as local
source=TIDAL shells (idempotent by uuid; additive), folded into pullAll;
CollectionPullSummary gains a playlists count, reported in the sync status
string. importPlaylistTracks fetches a playlist's items and sets membership in
Tidal order. PlayerViewModel.loadPlaylist now fetches a Tidal shell's tracks
lazily the first time it is opened (fetch-when-empty), so Collection open stays
cheap and only opened playlists pay their track cost. Playlists tab and
PlaylistDetail render/play a Tidal playlist unchanged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 5: On-device checkpoint (gates completion)

No production code. Build a local APK (CI runners have no Tidal client id, so on-device Tidal testing **must** use a locally-built APK), install on a signed-in device, and verify end-to-end. Completion of this slice is gated on this checkpoint passing.

- [ ] **Step 1: Build a labelled local APK**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew assembleDebug`
Then copy the APK out of `build/outputs` (unsafe to hand out while other agents build) to a stable name carrying the commit SHA, and verify it with `aapt2 dump badging` before install (confirm `package: name='io.github.auxen'` and the expected `versionName`). Install on a device already signed in to Tidal.

- [ ] **Step 2: Verify the LIST-eager sync**

Open Collection. The auto-pull-on-open fires (guarded, once per process, only when logged in). Confirm:
  - the sync status reads **"Synced +N album(s), +N artist(s), +N track(s), +N playlist(s) from Tidal."** with **+6 playlist(s)** on the probed account;
  - the **Playlists** tab now lists the 6 Tidal playlists (mixed with any local playlists, ordered by name), each with a color dot + name;
  - the first Collection open stays responsive (only the cheap LIST call ran — no ~280 tracks fetched).

- [ ] **Step 3: Verify tracks-lazy open + playback**

  - Open a Tidal playlist. Confirm a brief first-open fetch, then its tracks render (with album art) and **Play All** plays (streaming via the unchanged `getStreamInfoById` path).
  - Re-open the same playlist. Confirm it is **instant** (local copy, no re-fetch).
  - Open the **100-track "2024"** playlist. Confirm all **100 tracks** appear (pagination fetched every page — the raw-count pagination did not truncate).

- [ ] **Step 4: Verify idempotency + edits + no secret leakage**

  - Tap the manual **Sync from Tidal** affordance (force = true). Confirm a re-sync reports **+0 playlist(s)** (idempotent: no duplicate playlists) and that an already-opened playlist's tracks are unchanged (a routine re-sync does not re-import a populated playlist).
  - **Delete** an imported playlist. Confirm it disappears locally; re-sync recreates it as an (empty) shell that re-fetches its tracks on the next open. (The Tidal-side playlist is untouched — pull-only.)
  - Scan Logcat during the whole session and confirm **no `accessToken`/`refreshToken`** appears anywhere.

- [ ] **Step 5: Final tree check**

Run `git status` and `git diff --stat` and confirm only these files changed across Tasks 1–4:
  - `provider/tidal/TidalPlaylists.kt` (new) + its test `TidalPlaylistsTest.kt` (new)
  - `provider/tidal/TidalProvider.kt`
  - `db/Daos.kt`
  - `data/LibraryRepository.kt` + its test `LibraryRepositoryTest.kt`
  - `provider/tidal/TidalCollectionSync.kt`
  - `ui/PlayerViewModel.kt`

and that **`db/Entities.kt`, `db/AuxenDatabase.kt`, `AuxenApp.kt` (Graph), `ui/CollectionScreen.kt`, `ui/PlaylistDetailScreen.kt` are UNCHANGED** — confirming no new table, no migration (DB still `version = 3`), no `Graph` wiring change, and no Compose component change (so no Roborazzi golden re-record).

---

## Self-review checklist

- **No new table / migration / schema change. DB stays `version = 3`.** `PlaylistEntity` already carries `source` + `tidal_playlist_id` (`Entities.kt:91-98`, verified); the import reuses `playlists`/`playlist_tracks`/`tracks` as-is. The two new `PlaylistDao` methods are `@Query` reads over existing columns — not a schema change. `Entities.kt`/`AuxenDatabase.kt` untouched (Task 5 Step 5 asserts it).
- **No new track parser.** `playlistTracksFrom` reuses `TidalV1PageTrack.toTrack()` (`internal`, same module) and `parseIso8601Millis`; `tidalV1CoverUrl` is reached transitively through `toTrack` — never re-declared.
- **Idempotency is in code, proven.** `PlaylistDao.playlistByTidalId` + `importTidalPlaylistShell` find-or-create (no unique index → no migration); `LibraryRepositoryTest.importTidalPlaylistShellIsIdempotentByUuidAndStampsSource` proves re-import → same id, one row, `source=TIDAL`; `...SetsMembershipInTidalOrderAndReimportMirrors` proves Tidal-order membership + clear-and-re-insert on re-import.
- **List-eager + tracks-lazy is explicit.** `pullPlaylists` (folded into `pullAll`) imports LIST-only shells; `PlayerViewModel.loadPlaylist` fetches a Tidal shell's tracks only when it has none (fetch-when-empty guard). The first-open lag is the flagged tradeoff; the eager alternative is spelled out for redirection.
- **`CollectionPullSummary` change is complete.** Field added; the sole construction (`pullAll`) and sole read (`syncFavoritesFromTidal` status string) both updated; no test constructs it (grep-verified), so nothing else breaks.
- **Editability flag + refresh-path open question surfaced.** Imported playlists are create-only shells; local rename/reorder/remove persist; delete+resync is the refresh path; a per-playlist "refresh from Tidal" and Tidal-side-change propagation are deferred and flagged for a user call (do-not-gate recommended).
- **Playlist art deferred.** No art column, no migration; shells get the default amber color so the color dot renders. Noted.
- **Every new name is collision-free** (grep-verified zero existing declarations): `TidalPlaylist`, `TidalPlaylistPage`, `favoritePlaylistsFrom`, `playlistTracksFrom`, `favoritePlaylistsUrl`, `playlistItemsUrl`, `favoritePlaylists`, `playlistItems`, `PlaylistDao.byId`, `PlaylistDao.playlistByTidalId`, `LibraryRepository.playlist`, `LibraryRepository.playlistByTidalId`, `importTidalPlaylistShell`, `importTidalPlaylistTracks`, `pullPlaylists`, `importPlaylistTracks`, `CollectionPullSummary.playlists`.
- **Imports added where needed:** new `TidalPlaylists.kt` imports `io.github.auxen.model.Track`; `LibraryRepository.kt` gains `import io.github.auxen.model.Source`; `LibraryRepositoryTest.kt` gains `import org.junit.Assert.assertNull`; `TidalPlaylistsTest.kt` imports `Source`. `TidalProvider`/`TidalCollectionSync`/`PlayerViewModel` already import what the new code names.
- **Unit tests are pure / DB-only, never live HTTP.** Task 1 tests the parse + URL shapes; Task 3's tests are in-memory Room. The provider reads and the sync/ViewModel orchestration are untested (internal-v1 precedent); Task 4 adds no unit test by design (network + ViewModel), its correctness core proven in Task 3.
- **Roborazzi goldens unaffected** — no Compose component or parameter added; `CollectionScreen`/`PlaylistDetailScreen` untouched (both already render a Tidal playlist source-blind); the status string is not a component.
- **Pull-only.** No `TidalProvider` playlist write methods. Auth (`TidalAuth.kt`) + streaming untouched; a playlist's Tidal tracks stream via the unchanged `getStreamInfoById`.
- **Every gradle line carries `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`; commits use explicit pathspecs; tokens never logged.**
- **Scope:** 4 code tasks + 1 on-device checkpoint — within the brief's ~5-task budget.
