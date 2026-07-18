# Slice 2 — Collection Sync (Two-Way) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Populate the Collection **Albums** and **Artists** tabs from the user's Tidal saved albums / followed artists, and push local favorite toggles back to Tidal — all on the **internal-v1** API the rest of the app already uses (`api.tidal.com/v1`, the same API `TidalProvider` streams and searches through). Album and artist favorites get their own narrow local tables (`favorite_albums`/`favorite_artists`, DB `version = 3`) so a Tidal-favorited album/artist is **visible in Collection even when the user has no local tracks for it** — which is the bug this slice fixes: on-device the Collection Albums/Artists tabs are empty ("No favorite artists yet") even though the signed-in user has **114 saved albums and 110 followed artists**, because Collection derives those tabs ONLY by grouping locally-favorited *tracks* and never fetches the user's Tidal collection.

**Why this plan was rewritten (2026-07-18):** the previous revision was built entirely on the sanctioned official `openapi.tidal.com/v2` `userCollections` API. That whole official-v2 stack was **deleted from the app** (`TidalMetadataRepository`, the `TidalJsonApi` compound-document layer, `TidalOfficialClient`/`TidalOfficialEndpoints`, PKCE writes, the `TidalOfficialDebugScreen`) — confirmed absent from `app/src` — so the old plan cannot be built. This revision re-points every mechanism onto internal-v1 favorites, exactly what the desktop `auxen/providers/tidal.py` uses (`session.user.favorites.albums()/artists()`, `add_album/remove_album/add_artist/remove_artist`). Internal-v1 is **richer** than the official path was: a favorite album carries its cover, artist and track count; a favorite artist carries its name and picture — so the old plan's entire "title-only placeholder / self-heal / no artist / no art" caveat is **gone**, and a pulled favorite renders fully (cover + title + artist for albums, picture + name for artists).

**Architecture:** Additive, mirroring the internal-v1 patterns already in the tidal package (`artistPage`/`searchAll`/`albumDetail` on `TidalProvider`, with the *pure* parse in a sibling file like `TidalInternalAlbum.kt`/`TidalMultiTypeSearch.kt`). Four cleanly-separated concerns:

- **Pure favorites parse (unit-tested core)** — a new `TidalFavorites.kt` with the internal-v1 response DTOs, the request URL builders, and the pure `favoriteAlbumsFrom`/`favoriteArtistsFrom` projections. No network in the unit tests (the internal-v1 precedent: every `*From(body, json)` and `*Url(...)` is pure and tested with fixtures; the suspend methods that call the network are not).
- **`TidalProvider` favorites methods** — capture the user id from the session bootstrap the provider already does, then paginated reads (`favoriteAlbums()`/`favoriteArtists()`) and writes (`addFavoriteAlbum`/`removeFavoriteAlbum`/`addFavoriteArtist`/`removeFavoriteArtist`), added alongside the existing read methods with two new private `post`/`delete` HTTP helpers (the provider only has `get` today).
- **The local surface for album/artist favorites** — two narrow Room tables (`favorite_albums`, `favorite_artists`) + `MIGRATION_2_3`, each holding the favorite record plus the minimal display metadata the tabs render. Deliberately *not* a catalog mirror (see the locked decision).
- **Reconcile + push** — a new `TidalCollectionSync` (held on `Graph`) composing `TidalProvider` (favorites) with `LibraryRepository` (local DB). Its merge is a **pure** set-difference (`newRemoteFavorites`, unit-tested), reused across albums and artists; its network+DB orchestration is not. Pull writes new remote favorites into the tables; push fires the internal-v1 add/remove on a Collection toggle. The Collection Albums/Artists tabs render a **pure union** (`mergeCollectionAlbums`/`mergeCollectionArtists`, unit-tested) of the local-grouped favorites and the favorite-table rows.

**Design decisions (locked for this slice):**

- **Dedup / conflict key = `source:sourceId`** (e.g. `TIDAL:75413016`), the same identity `LibraryRepository.favoriteKeys()` already emits for tracks. The two favorite tables carry a **unique index on `(source, source_id)`** exactly like `TrackEntity`, so `source:sourceId` stays the one dedup key across the whole app.
- **Pull is an additive union (non-destructive).** Remote favorites missing locally are inserted; local favorites are **never** deleted by a pull. Idempotent — a re-pull of an already-present favorite is a no-op upsert. A destructive "mirror-delete" mode (drop a local favorite the user removed on another device) is future work (needs tombstones).
- **Push is optimistic and best-effort.** A Collection unfavorite updates the local table immediately, then fires the internal-v1 write for **Tidal-source items only**. A failed write never rolls back the local change; the next pull re-reconciles the union. No offline write-queue in this slice (documented limitation).
- **DECIDED 2026-07-15 (user) — album/artist favorites get narrow local tables, not a catalog mirror.** Tracks-only was rejected; favoriting a Tidal album/artist must round-trip *and* be visible. The shape is deliberately **narrow**: `favorite_albums` holds `source`, `source_id`, `title`, `artist`, `art_url`, `added_at`; `favorite_artists` holds `source`, `source_id`, `name`, `art_url`, `added_at`. This is *not* a full albums/artists catalog — the need is precisely "show a Tidal-favorited album/artist in Collection even with no local tracks, and round-trip its favorite state" (YAGNI). It matches the **desktop Collection semantics the Android port tracks**: Collection Albums = *Tidal saved albums (`favorites.albums()`) ∪ local albums with favorited tracks*; Artists likewise.
  - **One column beyond the old plan's list, deliberately.** The old (official-v2) plan's `favorite_albums` had `title`/`art_url` only, because the official favorites endpoint could not return an album's artist. Internal-v1 **does** (`item.artists[0].name`), and "renders fully" is the stated goal, so this revision adds a nullable `artist` column to `favorite_albums`. `art_url` is now **populated** on every pull (album cover @640 via `tidalV1CoverUrl`, artist picture @480 via `tidalV1ArtistPictureUrl`), not a null self-heal hook. `numberOfTracks`/`releaseDate`/`explicit` are intentionally **not** stored — the Collection card does not render them (YAGNI).
- **Dedup is exact-name (trimmed, case-insensitive), matching desktop.** A remote favorite whose normalized title/name matches a local group is dropped in favour of the local group (strictly richer: it has playable tracks). Clean/explicit and deluxe variants stay visible as separate entries — the desktop's intentional behaviour (project memory: *"Tidal album dedup: exact name match — user wants clean/explicit variants shown"*).
- **Track favorites are OUT OF SCOPE this slice.** The Collection **Tracks** tab already works (it groups locally-favorited tracks, and internal-v1 track favoriting round-trips are unchanged). Pulling Tidal favorite *tracks* into the local DB is optional parity and is **deferred** — this slice is exactly the Albums/Artists emptiness fix. `toggleFavorite` (tracks) is untouched.

**Tech Stack:** Kotlin, kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), OkHttp 4.12 (`FormBody` for the write body — the same class `TidalAuth` already posts with), AndroidX DataStore (session), Room 2.7.1 (`exportSchema = false`), Compose (Collection), JUnit4 + Robolectric 4.14.1. `java.time` (`OffsetDateTime`/`Instant`) for the ISO-8601 `created` timestamp (minSdk 26). minSdk 26 / compileSdk 35 / Media3 1.5.1.

## Global Constraints

- **Every gradle command must carry `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`.** The system default JDK breaks this project's Gradle with a cryptic `java.lang.IllegalArgumentException: 25.0.3`. Every `Run:` line below already carries the prefix — do not drop it. Never pipe gradle through `tail`/`head` and read the exit status of the pipe; read the real output for `BUILD SUCCESSFUL`.
- **Never log or print `accessToken`/`refreshToken`.** Only human-readable names/titles/counts go to Logcat or any status string. The provider's `Authorization: Bearer $token` header is built inline in `get`/`post`/`delete` and never logged.
- **Do not unit-test live HTTP execution** (internal-v1 precedent — every `TidalProvider` suspend method is untested; its pure `*From`/`*Url` helpers are). Unit-test **pure** logic only: the favorites JSON parse, the request-URL shapes, the write-payload field, the pure merge/reconcile set-logic, and the pure Collection tab union. Extract a pure function from every suspend/network wrapper so the logic is testable with no network.
- **Writes never execute in tests** — assert the request **shape** (URL path/query) and the form field the payload carries (`albumId`/`artistId`); the suspend write wrappers themselves are untested (network).
- **New classes stay plain instantiable classes held on `Graph`** (`TidalCollectionSync`), never JVM-wide `object`s with mutable state — so tests need no `@Before` reset.
- **Internal-v1 auth (`TidalAuth.kt`) and streaming are untouched.** A favorited Tidal track still streams via `TidalProvider.getStreamInfoById(sourceId)`. `TidalProvider` is *extended* (favorites methods, user-id capture, `post`/`delete` helpers), never rewritten. Confirm with `git status`/`git diff` at the end.
- **Never re-declare an existing type — a duplicate declaration is a Kotlin "conflicting declarations" COMPILE ERROR.** `tidalV1CoverUrl` (`TidalInternalArtist.kt`), `tidalV1ArtistPictureUrl` (`TidalArtistPage.kt`), `TidalV1AlbumArtist` (`TidalInternalArtist.kt`), `AlbumGroup`/`ArtistGroup`/`groupAlbums`/`groupArtists` (`data/LibraryGrouping.kt`), `Source`/`Track` (`model`) all already exist and are **reused, never re-declared**. Every NEW name introduced here was grep-verified to have zero existing declarations in `app/src` (see the self-review).
- **When new code in an EXISTING file names a type that file does not import, ADD THE IMPORT — Kotlin resolves per-file.** The concrete instance in this slice: `PlayerViewModel.kt` imports `io.github.auxen.model.QueueEntry`/`SourcePriority`/`Track` but **NOT `Source`** (today's `toggleFavorite` only touches the `String` `track.source.name`), so Task 5 — which names `Source.TIDAL` — must add `import io.github.auxen.model.Source`. `CollectionScreen.kt` **already** imports `Source`. The new files carry complete import blocks in their code steps.
- **New assignments in `Graph.init(context)` go after ALL of their dependencies.** Every `Graph` field is a `lateinit var`, so a wrong-order assignment **compiles** and then throws `UninitializedPropertyAccessException` on app launch — no unit test or `assembleDebug` here catches it. `TidalCollectionSync` needs `tidal` AND `library`; `tidal` is assigned two statements before `library` (with `db` in between). See Task 4, Step 5.
- **The Room DB goes `version = 2` → `version = 3` exactly once** (verified current version is `2` in `db/AuxenDatabase.kt`), via the additive `MIGRATION_2_3` registered in `AuxenDatabase.build()`. `exportSchema` stays `false`; the migration test (Task 3) is designed around that (no schema JSON, no `room-testing` dependency).
- **Git: explicit pathspecs only.** Never `git add -A` / `git add .` / `git commit -a` — the working tree carries unrelated untracked files (`.playwright-mcp/`, other `docs/plans/*`) that must never be committed.
- Every code step below shows the actual code. No `TODO`/"add error handling"/"similar to above" placeholders.

---

### Task 1: Pure internal-v1 favorites parse + request URLs (`TidalFavorites.kt`)

The unit-tested core. `GET users/{userId}/favorites/albums` and `.../artists` return the paginated wrapper `{ "items": [ { "created", "item": {…} } ], "totalNumberOfItems": N }` (probed live; user has 114 albums, 110 artists). The album `item` carries `id`/`title`/`cover`/`numberOfTracks`/`releaseDate`/`explicit`/`artists[]`; the artist `item` carries `id`/`name`/`picture`. Project each page **purely** to a narrow domain type, reusing the existing 640 cover / 480 picture URL builders. Writes target `.../favorites/albums` (POST form `albumId`) and `.../favorites/albums/{id}` (DELETE) — the exact tidalapi shapes (`tidalapi/user.py` `Favorites.add_album`/`remove_album`/`add_artist`/`remove_artist`), so their URL builders live here too and are shape-tested.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalFavorites.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalFavoritesTest.kt`

**Interfaces:**
- Consumes: `tidalV1CoverUrl` (`TidalInternalArtist.kt`, same package — 640, albums), `tidalV1ArtistPictureUrl` (`TidalArtistPage.kt`, same package — 480, artists), `TidalV1AlbumArtist` (`TidalInternalArtist.kt`, same package). All REUSED, never re-declared.
- Produces: `TidalFavoriteAlbum(id, title, artistName, coverUrl, addedAtMillis)`; `TidalFavoriteArtist(id, name, pictureUrl, addedAtMillis)`; `fun parseIso8601Millis(String?): Long?`; `fun favoriteAlbumsFrom(String, Json): List<TidalFavoriteAlbum>`; `fun favoriteArtistsFrom(String, Json): List<TidalFavoriteArtist>`; the request URL builders `favoriteAlbumsUrl`/`favoriteArtistsUrl` (read, paged), `favoriteAlbumsWriteUrl`/`favoriteArtistsWriteUrl` (POST), `favoriteAlbumDeleteUrl`/`favoriteArtistDeleteUrl` (DELETE).

- [ ] **Step 1: Write the failing test**

Create `TidalFavoritesTest.kt`:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure internal-v1 favorites parsing + request-URL shapes. No live HTTP — the
 * TidalProvider suspend methods that call these are untested (internal-v1
 * precedent), only the pure projection + URL builders are.
 *
 * Fixtures use the real internal-v1 favorites wrapper shape probed live
 * (`.superpowers/sdd/slice2-favorites-schema.md`): `{ items:[{ created, item }],
 * totalNumberOfItems }`, album item `{id,title,cover,numberOfTracks,releaseDate,
 * explicit,artists[]}`, artist item `{id,name,picture}`.
 */
class TidalFavoritesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val albumsPage = """
        {
          "items": [
            { "created": "2023-05-01T12:00:00.000+0000",
              "item": { "id": 75413016, "title": "OutRun", "cover": "aa-bb-cc",
                        "numberOfTracks": 13, "releaseDate": "2013-02-25", "explicit": false,
                        "artists": [ { "id": 11, "name": "Kavinsky" } ] } },
            { "created": "2023-06-01T12:00:00Z",
              "item": { "id": 99, "title": "No Cover", "cover": null,
                        "artists": [] } }
          ],
          "totalNumberOfItems": 2
        }
    """.trimIndent()

    private val artistsPage = """
        {
          "items": [
            { "created": "2023-05-01T12:00:00Z",
              "item": { "id": 7, "name": "Prompto", "picture": "dd-ee-ff" } },
            { "created": null,
              "item": { "id": 8, "name": "No Picture", "picture": null } }
          ],
          "totalNumberOfItems": 2
        }
    """.trimIndent()

    @Test
    fun `favoriteAlbumsFrom projects id title artist cover and addedAt`() {
        val albums = favoriteAlbumsFrom(albumsPage, json)
        assertEquals(2, albums.size)
        assertEquals("75413016", albums[0].id)
        assertEquals("OutRun", albums[0].title)
        assertEquals("Kavinsky", albums[0].artistName)
        assertEquals("https://resources.tidal.com/images/aa/bb/cc/640x640.jpg", albums[0].coverUrl)
        assertEquals(1682942400000L, albums[0].addedAtMillis) // 2023-05-01T12:00:00Z
    }

    @Test
    fun `favoriteAlbumsFrom tolerates a missing cover and empty artists`() {
        val second = favoriteAlbumsFrom(albumsPage, json)[1]
        assertEquals("99", second.id)
        assertNull(second.coverUrl)
        assertNull(second.artistName)
    }

    @Test
    fun `favoriteArtistsFrom projects id name picture and addedAt`() {
        val artists = favoriteArtistsFrom(artistsPage, json)
        assertEquals(2, artists.size)
        assertEquals("7", artists[0].id)
        assertEquals("Prompto", artists[0].name)
        assertEquals("https://resources.tidal.com/images/dd/ee/ff/480x480.jpg", artists[0].pictureUrl)
        assertNull(artists[1].pictureUrl)
        assertNull(artists[1].addedAtMillis)
    }

    @Test
    fun `favorite parsers return empty list on garbage or an error envelope`() {
        assertEquals(emptyList<TidalFavoriteAlbum>(), favoriteAlbumsFrom("{\"status\":404}", json))
        assertEquals(emptyList<TidalFavoriteArtist>(), favoriteArtistsFrom("not json", json))
    }

    @Test
    fun `parseIso8601Millis handles Z and offsets and null on garbage`() {
        assertEquals(0L, parseIso8601Millis("1970-01-01T00:00:00Z"))
        assertEquals(0L, parseIso8601Millis("1970-01-01T01:00:00+01:00"))
        assertNull(parseIso8601Millis("not-a-date"))
        assertNull(parseIso8601Millis(null))
    }

    @Test
    fun `read URL is paged and country-scoped, ordered most-recent-first`() {
        val url = favoriteAlbumsUrl("https://api.tidal.com/v1", "42", "US", limit = 50, offset = 100)
        assertEquals("/v1/users/42/favorites/albums", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("100", url.queryParameter("offset"))
        assertEquals("DATE", url.queryParameter("order"))
        assertEquals("DESC", url.queryParameter("orderDirection"))
        assertEquals("US", url.queryParameter("countryCode"))
    }

    @Test
    fun `write URL and delete URL target the collection relationship`() {
        val add = favoriteArtistsWriteUrl("https://api.tidal.com/v1", "42", "US")
        assertEquals("/v1/users/42/favorites/artists", add.encodedPath)
        assertEquals("US", add.queryParameter("countryCode"))

        val del = favoriteAlbumDeleteUrl("https://api.tidal.com/v1", "42", "99", "US")
        assertEquals("/v1/users/42/favorites/albums/99", del.encodedPath)
        assertEquals("US", del.queryParameter("countryCode"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalFavoritesTest*'`
Expected: FAIL — compilation error: `favoriteAlbumsFrom`, `favoriteArtistsFrom`, `parseIso8601Millis`, `TidalFavoriteAlbum`, `TidalFavoriteArtist`, and the URL builders are unresolved references (`TidalFavorites.kt` does not exist yet).

- [ ] **Step 3: Implement `TidalFavorites.kt`**

Create `TidalFavorites.kt`:

```kotlin
package io.github.auxen.provider.tidal

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The pure parts of the user's Tidal favorites on the INTERNAL v1 API — the
 * saved-albums / followed-artists collection the desktop reads via
 * `session.user.favorites.albums()/artists()`. Every `*From(body, json)` and
 * `*Url(...)` here is pure and unit-tested; the suspend methods that call the
 * network live on [TidalProvider] (untested, internal-v1 precedent).
 *
 * Internal-v1 is richer than the retired official path: a favorite album
 * carries its cover + artist + track count, a favorite artist its name +
 * picture — so a pulled favorite renders fully (no title-only placeholder).
 * Field names are verbatim from the probed schema and `tidalapi/user.py`.
 */

/** A Tidal saved album, projected narrow for Collection (cover + title + artist). */
data class TidalFavoriteAlbum(
    val id: String,
    val title: String,
    val artistName: String?,
    val coverUrl: String?,
    val addedAtMillis: Long?,
)

/** A Tidal followed artist, projected narrow for Collection (picture + name). */
data class TidalFavoriteArtist(
    val id: String,
    val name: String,
    val pictureUrl: String?,
    val addedAtMillis: Long?,
)

// ---- internal-v1 response DTOs (every field nullable/defaulted, the tolerance
// every DTO in this package applies to a reverse-engineered endpoint) ----

@Serializable
private data class TidalV1FavoriteAlbum(
    val id: Long,
    val title: String = "",
    val cover: String? = null,
    val artist: TidalV1AlbumArtist? = null,
    val artists: List<TidalV1AlbumArtist> = emptyList(),
)

@Serializable
private data class TidalV1FavoriteAlbumItem(val created: String? = null, val item: TidalV1FavoriteAlbum? = null)

@Serializable
private data class TidalV1FavoriteAlbumsResponse(
    val items: List<TidalV1FavoriteAlbumItem> = emptyList(),
    val totalNumberOfItems: Int = 0,
)

@Serializable
private data class TidalV1FavoriteArtist(val id: Long, val name: String = "", val picture: String? = null)

@Serializable
private data class TidalV1FavoriteArtistItem(val created: String? = null, val item: TidalV1FavoriteArtist? = null)

@Serializable
private data class TidalV1FavoriteArtistsResponse(
    val items: List<TidalV1FavoriteArtistItem> = emptyList(),
    val totalNumberOfItems: Int = 0,
)

/** Parse an ISO-8601 date-time (`created`) to epoch millis, tolerant of `Z`/offset forms; null on absence/parse failure. */
fun parseIso8601Millis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

/**
 * Pure: one `favorites/albums` page body → [TidalFavoriteAlbum]s. Reuses
 * [tidalV1CoverUrl] (640 — it IS an album cover). Wrapped in `runCatching` so a
 * malformed body / v1 error envelope degrades to empty, never crashes the pull.
 */
fun favoriteAlbumsFrom(body: String, json: Json): List<TidalFavoriteAlbum> = runCatching {
    json.decodeFromString<TidalV1FavoriteAlbumsResponse>(body).items.mapNotNull { wrapper ->
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
}.getOrDefault(emptyList())

/**
 * Pure: one `favorites/artists` page body → [TidalFavoriteArtist]s. Reuses
 * [tidalV1ArtistPictureUrl] (480 — an artist picture is NOT served at 640, so
 * the cover builder must not be used here; see its KDoc). Same degrade-to-empty
 * discipline as [favoriteAlbumsFrom].
 */
fun favoriteArtistsFrom(body: String, json: Json): List<TidalFavoriteArtist> = runCatching {
    json.decodeFromString<TidalV1FavoriteArtistsResponse>(body).items.mapNotNull { wrapper ->
        val artist = wrapper.item ?: return@mapNotNull null
        TidalFavoriteArtist(
            id = artist.id.toString(),
            name = artist.name,
            pictureUrl = tidalV1ArtistPictureUrl(artist.picture),
            addedAtMillis = parseIso8601Millis(wrapper.created),
        )
    }
}.getOrDefault(emptyList())

// ---- request URL builders (pure; `order=DATE&orderDirection=DESC` gives
// most-recent-first, matching the probed schema) ----

/** `GET {apiBase}/users/{userId}/favorites/albums?limit&offset&order=DATE&orderDirection=DESC&countryCode`. */
fun favoriteAlbumsUrl(apiBase: String, userId: String, countryCode: String, limit: Int, offset: Int): HttpUrl =
    "$apiBase/users/$userId/favorites/albums".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("order", "DATE")
        .addQueryParameter("orderDirection", "DESC")
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `GET {apiBase}/users/{userId}/favorites/artists?limit&offset&order=DATE&orderDirection=DESC&countryCode`. */
fun favoriteArtistsUrl(apiBase: String, userId: String, countryCode: String, limit: Int, offset: Int): HttpUrl =
    "$apiBase/users/$userId/favorites/artists".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("order", "DATE")
        .addQueryParameter("orderDirection", "DESC")
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `POST {apiBase}/users/{userId}/favorites/albums?countryCode` (form body `albumId=<id>`). */
fun favoriteAlbumsWriteUrl(apiBase: String, userId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/albums".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `POST {apiBase}/users/{userId}/favorites/artists?countryCode` (form body `artistId=<id>`). */
fun favoriteArtistsWriteUrl(apiBase: String, userId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/artists".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `DELETE {apiBase}/users/{userId}/favorites/albums/{albumId}?countryCode`. */
fun favoriteAlbumDeleteUrl(apiBase: String, userId: String, albumId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/albums/$albumId".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()

/** `DELETE {apiBase}/users/{userId}/favorites/artists/{artistId}?countryCode`. */
fun favoriteArtistDeleteUrl(apiBase: String, userId: String, artistId: String, countryCode: String): HttpUrl =
    "$apiBase/users/$userId/favorites/artists/$artistId".toHttpUrl().newBuilder()
        .addQueryParameter("countryCode", countryCode)
        .build()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalFavoritesTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalFavorites.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalFavoritesTest.kt
git commit -m "feat(android): pure internal-v1 favorites parse + request URLs

Adds TidalFavorites.kt — favoriteAlbumsFrom/favoriteArtistsFrom projections
(cover@640, picture@480, addedAt from the created wrapper) and the read/write/
delete URL builders for users/{id}/favorites/{albums,artists}. Pure and unit-
tested against the probed internal-v1 wrapper shape; no network.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 2: `TidalProvider` — capture the user id, read + write favorites

Wire the pure layer to the network. The favorites base is `users/{userId}/favorites`, and the user id comes from the session bootstrap the provider **already does**: `restoreSession()` GETs `$API_BASE/sessions` (line ~95) and decodes it into `ApiSession`, which today reads only `countryCode`. The `/sessions` response also carries `userId` (this is exactly how tidalapi resolves `session.user.id` — `tidalapi/session.py` reads `request["userId"]`). So capture it there — do **not** invent a new resolver. Then add the paginated reads and the four writes, plus two private `post`/`delete` HTTP helpers (the provider only has `get` today).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt`

**Interfaces:**
- Consumes: `favoriteAlbumsUrl`/`favoriteArtistsUrl`/`favoriteAlbumsWriteUrl`/`favoriteArtistsWriteUrl`/`favoriteAlbumDeleteUrl`/`favoriteArtistDeleteUrl`, `favoriteAlbumsFrom`/`favoriteArtistsFrom` (Task 1); the existing private `validToken()`/`get()`, `countryCode`, `json`, `API_BASE`.
- Produces: `TidalProvider.currentUserId: String?`; `favoriteAlbums(): List<TidalFavoriteAlbum>`; `favoriteArtists(): List<TidalFavoriteArtist>`; `addFavoriteAlbum(String): Boolean`; `removeFavoriteAlbum(String): Boolean`; `addFavoriteArtist(String): Boolean`; `removeFavoriteArtist(String): Boolean`. (The pure URL/parse are Task 1's tested surface; these network methods are untested by precedent — this task's gate is `assembleDebug` + Task 1's green tests.)

- [ ] **Step 1: Capture `userId` from the session bootstrap**

In `TidalProvider.kt`, extend the private `ApiSession` DTO to also read `userId`, add a `private var userId`, expose it read-only, set it in `restoreSession()`, and clear it in `logout()`.

Change the DTO:

```kotlin
    @Serializable
    private data class ApiSession(val countryCode: String = "US", val userId: Long? = null)
```

Add the field beside `countryCode`/`bootstrapped` (top of the class):

```kotlin
    private var userId: String? = null

    /** The signed-in user's Tidal id (the favorites collection owner), or null before a successful [restoreSession]. */
    val currentUserId: String? get() = userId
```

In `restoreSession()`, replace the body of the `runCatching { … }` that decodes `/sessions` so it also captures `userId`:

```kotlin
        return runCatching {
            val body = get("$API_BASE/sessions", session.accessToken)
            val api = json.decodeFromString<ApiSession>(body)
            countryCode = api.countryCode
            userId = api.userId?.toString()
            bootstrapped = true
        }.isSuccess
```

In `logout()`, clear it alongside `bootstrapped`:

```kotlin
    suspend fun logout() = sessionMutex.withLock {
        auth.logout()
        bootstrapped = false
        userId = null
    }
```

- [ ] **Step 2: Add the `post`/`delete` HTTP helpers**

In the `// Internals` region, beside the existing private `get(url, token)`, add (same `withContext(Dispatchers.IO)` + `check(resp.isSuccessful)` discipline; a write's 2xx body is unused). Add `import okhttp3.FormBody` to the file's imports (the provider does not import it yet; `TidalAuth.kt` already uses this exact class for its OAuth posts):

```kotlin
    private suspend fun post(url: String, form: FormBody, token: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").post(form).build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "Tidal API error: HTTP ${resp.code} for $url" }
            resp.body?.string().orEmpty()
        }
    }

    private suspend fun delete(url: String, token: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").delete().build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "Tidal API error: HTTP ${resp.code} for $url" }
            resp.body?.string().orEmpty()
        }
    }
```

- [ ] **Step 3: Add the paginated reads**

In the public methods region (beside `artistPage`/`albumDetail`), add. The page-size loop mirrors the desktop's `get_favorite_albums` (`page_size = 50`, stop when a page returns fewer than a full page), and the `FAVORITES_MAX` cap bounds a pathological loop, exactly as `albumDetail`'s `ALBUM_ITEMS_MAX` does:

```kotlin
    /**
     * The user's Tidal saved albums, paginated — the internal-v1 port of the
     * desktop's `session.user.favorites.albums()` (`auxen/providers/tidal.py`
     * `get_favorite_albums`). Metadata only: resolves no stream. Returns an
     * empty list when not logged in / the user id is unresolved / any page
     * fails, matching every other read here (a missing collection degrades the
     * Collection tab, never breaks it).
     */
    suspend fun favoriteAlbums(): List<TidalFavoriteAlbum> {
        val token = validToken() ?: return emptyList()
        val uid = userId ?: return emptyList()
        val all = mutableListOf<TidalFavoriteAlbum>()
        var offset = 0
        while (offset < FAVORITES_MAX) {
            val url = favoriteAlbumsUrl(API_BASE, uid, countryCode, FAVORITES_PAGE, offset)
            val body = runCatching { get(url.toString(), token) }.getOrNull() ?: break
            val page = favoriteAlbumsFrom(body, json)
            all += page
            if (page.size < FAVORITES_PAGE) break
            offset += FAVORITES_PAGE
        }
        return all
    }

    /**
     * The user's Tidal followed artists, paginated — the internal-v1 port of
     * the desktop's `session.user.favorites.artists()`
     * (`get_followed_artists`). Same not-logged-in / degrade contract as
     * [favoriteAlbums].
     */
    suspend fun favoriteArtists(): List<TidalFavoriteArtist> {
        val token = validToken() ?: return emptyList()
        val uid = userId ?: return emptyList()
        val all = mutableListOf<TidalFavoriteArtist>()
        var offset = 0
        while (offset < FAVORITES_MAX) {
            val url = favoriteArtistsUrl(API_BASE, uid, countryCode, FAVORITES_PAGE, offset)
            val body = runCatching { get(url.toString(), token) }.getOrNull() ?: break
            val page = favoriteArtistsFrom(body, json)
            all += page
            if (page.size < FAVORITES_PAGE) break
            offset += FAVORITES_PAGE
        }
        return all
    }
```

- [ ] **Step 4: Add the four writes**

Beside the reads, add (the POST body field is `albumId`/`artistId` — the exact form field tidalapi sends, `tidalapi/user.py` `Favorites.add_album`/`add_artist` use `data={"albumId": …}`/`{"artistId": …}`, which the Python `requests` library serializes as `application/x-www-form-urlencoded`; `FormBody` is OkHttp's equivalent). Best-effort: return `false` on any failure, matching the desktop's swallow-and-return-bool):

```kotlin
    /** Save [albumId] to the user's Tidal collection. Best-effort — false on any failure. */
    suspend fun addFavoriteAlbum(albumId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteAlbumsWriteUrl(API_BASE, uid, countryCode)
        val form = FormBody.Builder().add("albumId", albumId).build()
        return runCatching { post(url.toString(), form, token) }.isSuccess
    }

    /** Remove [albumId] from the user's Tidal collection. Best-effort. */
    suspend fun removeFavoriteAlbum(albumId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteAlbumDeleteUrl(API_BASE, uid, albumId, countryCode)
        return runCatching { delete(url.toString(), token) }.isSuccess
    }

    /** Follow [artistId] in the user's Tidal collection. Best-effort. */
    suspend fun addFavoriteArtist(artistId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteArtistsWriteUrl(API_BASE, uid, countryCode)
        val form = FormBody.Builder().add("artistId", artistId).build()
        return runCatching { post(url.toString(), form, token) }.isSuccess
    }

    /** Unfollow [artistId] in the user's Tidal collection. Best-effort. */
    suspend fun removeFavoriteArtist(artistId: String): Boolean {
        val token = validToken() ?: return false
        val uid = userId ?: return false
        val url = favoriteArtistDeleteUrl(API_BASE, uid, artistId, countryCode)
        return runCatching { delete(url.toString(), token) }.isSuccess
    }
```

Add the two page constants to the private `companion object` (beside `ALBUM_ITEMS_PAGE`/`ALBUM_ITEMS_MAX`):

```kotlin
        // favorites paging: 50 matches the desktop page size; MAX bounds a
        // pathological loop (114 albums / 110 artists are ~3 pages).
        const val FAVORITES_PAGE = 50
        const val FAVORITES_MAX = 5000
```

- [ ] **Step 5: Build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalFavoritesTest*' --tests '*TidalProvider*' assembleDebug`
Expected: PASS; APK builds. (No new pure logic in this task — reads/writes are network; Task 1's parse/URL tests cover the pure surface they call.)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt
git commit -m "feat(android): TidalProvider reads/writes Tidal favorite albums + artists

Captures userId from the existing GET /sessions bootstrap (no new resolver),
adds paginated favoriteAlbums()/favoriteArtists() reads and add/remove writes
(POST form albumId/artistId, DELETE by id) over internal-v1
users/{id}/favorites, with two new private post/delete HTTP helpers. Auth and
streaming untouched; tokens never logged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 3: Local schema — `favorite_albums` / `favorite_artists` + `MIGRATION_2_3`

The local surface for the **2026-07-15 decision**. Two narrow favorite tables (not a catalog mirror) so a Tidal-favorited album/artist can be shown in Collection with no local tracks for it, and its favorite state can round-trip. Both mirror `TrackEntity`'s identity precedent exactly: an autogenerated row id plus a **unique index on `(source, source_id)`**. The migration is additive (`CREATE TABLE IF NOT EXISTS` + `CREATE UNIQUE INDEX IF NOT EXISTS`, no existing data touched), following the `MIGRATION_1_2` precedent. Current DB version is **2** (verified in `db/AuxenDatabase.kt`), so this is `MIGRATION_2_3` → `version = 3`.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/Entities.kt` (add `FavoriteAlbumEntity`, `FavoriteArtistEntity`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/Daos.kt` (add `FavoriteAlbumDao`, `FavoriteArtistDao`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/AuxenDatabase.kt` (register both entities + DAOs, `version = 3`, `DB_NAME`, `MIGRATION_2_3`, `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`)
- Test: `android/app/src/test/kotlin/io/github/auxen/db/FavoriteCollectionDaoTest.kt` (create)
- Test: `android/app/src/test/kotlin/io/github/auxen/db/MigrationTest.kt` (create)

**Interfaces:**
- Produces: `FavoriteAlbumEntity(id, source, sourceId, title, artist, artUrl, addedAtMillis)`; `FavoriteArtistEntity(id, source, sourceId, name, artUrl, addedAtMillis)`; `FavoriteAlbumDao`/`FavoriteArtistDao` each with `upsert`/`bySourceId`/`delete`/`all()`/`keys()`; `AuxenDatabase.favoriteAlbumDao()`/`favoriteArtistDao()`; `AuxenDatabase.DB_NAME`; `AuxenDatabase.MIGRATION_2_3`.
- Consumed by: `LibraryRepository` (Task 4), `mergeCollectionAlbums`/`mergeCollectionArtists` (Task 5).

**On testing the migration honestly.** The module sets `exportSchema = false` and does **not** depend on `androidx.room:room-testing`; there is also no `androidTest` source set. So `MigrationTestHelper` is **not** an option — it builds the "before" database from an exported `<version>.json` that does not exist and cannot be regenerated retroactively. Rather than spec a test that cannot run, `MigrationTest` drives Room's **own** open-time schema validation: create the current schema, drop the two v3-only tables, stamp `user_version = 2`, then reopen through the production `AuxenDatabase.build()`. Room detects `user_version 2 < 3`, runs the registered `MIGRATION_2_3`, then calls its generated `validateMigration`, which compares the post-migration SQLite schema (columns, affinities, nullability, primary keys, indices) against the schema Room derived from the `@Entity` classes at compile time — throwing `IllegalStateException("Migration didn't properly handle: …")` on any mismatch. A real migration test with **no schema JSON and no new dependency**, that additionally proves the migration is *registered* (an unregistered one throws "A migration from 2 to 3 was required but not found"). Reuses the existing Robolectric + `androidx.test.core` deps (like `DaoTest`/`SearchHistoryDaoTest`).

- [ ] **Step 1: Write the failing tests**

Create `FavoriteCollectionDaoTest.kt`:

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
 * The narrow favorite-album/favorite-artist tables (2026-07-15 decision): a
 * Tidal-favorited album/artist must be visible in Collection even with no local
 * tracks. Identity is (source, source_id) — the key FavoriteDao.favoriteKeys()
 * already emits for tracks.
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

    @Test
    fun albumUpsertInsertsThenRefreshesPreservingRowId() = runBlocking {
        val dao = db.favoriteAlbumDao()
        val id = dao.upsert(FavoriteAlbumEntity(source = "TIDAL", sourceId = "9", title = "Album Nine", addedAtMillis = 100))
        assertTrue(id > 0)

        // Same (source, source_id) → same row, refreshed metadata, no duplicate.
        val id2 = dao.upsert(
            FavoriteAlbumEntity(
                source = "TIDAL", sourceId = "9", title = "Album Nine (Deluxe)",
                artist = "Kavinsky", artUrl = "https://art/9.jpg", addedAtMillis = 200,
            ),
        )
        assertEquals(id, id2)
        val row = dao.bySourceId("TIDAL", "9")!!
        assertEquals("Album Nine (Deluxe)", row.title)
        assertEquals("Kavinsky", row.artist)
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
 * from, and `androidx.room:room-testing` is not a dependency. So the v2 database
 * is manufactured directly:
 *
 *   1. Let Room create the CURRENT (v3) schema in a real file-backed database.
 *   2. Drop the two v3-only tables and stamp `user_version = 2`. This IS a
 *      faithful v2 database precisely BECAUSE MIGRATION_2_3 is additive:
 *      v2 == v3 minus favorite_albums/favorite_artists.
 *   3. Reopen through the PRODUCTION [AuxenDatabase.build] path. Room sees
 *      user_version 2 < 3, runs the registered MIGRATION_2_3, then calls its
 *      generated `validateMigration`, comparing the post-migration SQLite schema
 *      with the schema derived from the @Entity classes. A wrong column
 *      name/type, a missing NOT NULL, or a missing unique index throws
 *      IllegalStateException("Migration didn't properly handle: …").
 *
 * Also proves MIGRATION_2_3 is REGISTERED in build(): an unregistered migration
 * throws "A migration from 2 to 3 was required but not found".
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
        //    validates the result against the compiled @Entity schema.
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

Note on `close()`: `AuxenDatabase` extends `androidx.room.RoomDatabase`, which does **not** implement `Closeable` in Room 2.7.1 — so `use { }` does NOT apply to it and every database is closed explicitly above. `SQLiteDatabase` *does* (via `SQLiteClosable`), which is why the raw-SQLite block is the one place `use { }` is used.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*FavoriteCollectionDaoTest*' --tests '*MigrationTest*'`
Expected: FAIL — compilation error: `FavoriteAlbumEntity`, `FavoriteArtistEntity`, `AuxenDatabase.favoriteAlbumDao()`/`favoriteArtistDao()`, `AuxenDatabase.DB_NAME` are unresolved references (DB is still `version = 2` with eight entities).

- [ ] **Step 3: Add the entities**

In `Entities.kt`, append (both mirror `TrackEntity`: autogenerated row id + a unique `(source, source_id)` index):

```kotlin
/**
 * A favorited album — the local surface for Tidal saved albums (user decision
 * 2026-07-15). Deliberately NOT a catalog mirror: only the favorite record plus
 * the minimal display metadata Collection renders. On internal-v1 [artist] and
 * [artUrl] are POPULATED on a pull (the album carries its artist name + cover),
 * so a remote-only album card renders fully.
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
    val artist: String? = null,
    @ColumnInfo(name = "art_url") val artUrl: String? = null,
    @ColumnInfo(name = "added_at") val addedAtMillis: Long,
)

/**
 * A favorited (followed) artist — the local surface for Tidal followed artists
 * (user decision 2026-07-15). [artUrl] is the picture @480, populated on a pull.
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

In `Daos.kt`, append (the `upsert` mirrors `TrackDao.upsert`'s insert-or-refresh-preserving-row-id semantics; `keys()` mirrors `FavoriteDao.favoriteKeys()`'s `source || ':' || source_id` projection so the pure `newRemoteFavorites` set-difference works unchanged for albums/artists in Task 4):

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

Rewrite `AuxenDatabase.kt` to (the DDL below is exactly what Room generates for the two entities — `CREATE TABLE IF NOT EXISTS`, backtick-quoted, `INTEGER PRIMARY KEY AUTOINCREMENT` for the autogenerated `Long` id, `TEXT NOT NULL` for non-null Strings, `TEXT` for the nullable `artist`/`art_url`, `INTEGER NOT NULL` for the non-null `added_at`, and Room's default index name `index_<table>_<col>_<col>`). Note `artist` is nullable `TEXT` and sits between `title` and `art_url` — the entity's declaration order:

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
                        "`artist` TEXT, " +
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

If `MigrationTest` fails with `IllegalStateException: Migration didn't properly handle: favorite_albums(…)`, the message prints Room's **expected** vs **found** column/index sets — diff them and fix the DDL in `MIGRATION_2_3` to match the entity, never the other way round.

```bash
git add android/app/src/main/kotlin/io/github/auxen/db/Entities.kt \
        android/app/src/main/kotlin/io/github/auxen/db/Daos.kt \
        android/app/src/main/kotlin/io/github/auxen/db/AuxenDatabase.kt \
        android/app/src/test/kotlin/io/github/auxen/db/FavoriteCollectionDaoTest.kt \
        android/app/src/test/kotlin/io/github/auxen/db/MigrationTest.kt
git commit -m "feat(android): favorite_albums/favorite_artists tables + MIGRATION_2_3

Adds the local surface for Tidal saved albums / followed artists (user
decision 2026-07-15): two narrow favorite tables (not a catalog mirror), each
mirroring TrackEntity's unique (source, source_id) identity, plus an additive
v2->v3 migration. MigrationTest drives Room's own open-time schema validation
(exportSchema=false rules out MigrationTestHelper).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 4: `TidalCollectionSync` + `LibraryRepository` accessors + `Graph` wiring

Tie the favorites read/write layer to the local DB. `TidalCollectionSync` (on `Graph`) composes `TidalProvider` (favorites) with `LibraryRepository` (the favorite tables). Its merge is **one pure `newRemoteFavorites` set-difference reused across albums and artists** (unit-tested), plus pure `favoriteAlbumEntityFor`/`favoriteArtistEntityFor` projections; its pull/push suspend orchestration is not unit-tested (network precedent).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt` (favorite-album/artist accessors)
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (`Graph.tidalCollectionSync` wiring)
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionSyncTest.kt` (create)

**Interfaces:**
- Consumes: `TidalProvider.favoriteAlbums()`/`favoriteArtists()`/`addFavoriteAlbum`/`removeFavoriteAlbum`/`addFavoriteArtist`/`removeFavoriteArtist` (Task 2); `TidalFavoriteAlbum`/`TidalFavoriteArtist` (Task 1); `FavoriteAlbumEntity`/`FavoriteArtistEntity` + their DAOs (Task 3); `Source`.
- Produces: `LibraryRepository.favoriteAlbums()`, `.favoriteAlbumKeys()`, `.favoriteAlbum(entity)`, `.unfavoriteAlbum(source, sourceId)`, `.favoriteArtists()`, `.favoriteArtistKeys()`, `.favoriteArtist(entity)`, `.unfavoriteArtist(source, sourceId)`; `fun <T> newRemoteFavorites(remote, localKeys, id): List<T>`; `fun favoriteAlbumEntityFor(fav, addedAtMillis): FavoriteAlbumEntity`; `fun favoriteArtistEntityFor(fav, addedAtMillis): FavoriteArtistEntity`; `data class CollectionPullSummary(albums, artists)`; `TidalCollectionSync(tidal, library, clock)` with `pullFavoriteAlbums(): Int`, `pullFavoriteArtists(): Int`, `pullAll(): CollectionPullSummary`, `pushAlbumFavorite(sourceId, favorite)`, `pushArtistFavorite(sourceId, favorite)`; `Graph.tidalCollectionSync`.

- [ ] **Step 1: Write the failing test for the pure logic**

Create `TidalCollectionSyncTest.kt`:

```kotlin
package io.github.auxen.provider.tidal

import io.github.auxen.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure merge/projection logic for two-way collection sync. The network+DB
 * orchestration (pull/push) is untested (internal-v1 precedent); only the
 * additive-union delta and the entity projections are unit-tested. ONE
 * newRemoteFavorites serves both albums and artists.
 */
class TidalCollectionSyncTest {

    private fun album(id: String, title: String = "T", addedAt: Long? = null) =
        TidalFavoriteAlbum(id = id, title = title, artistName = "A", coverUrl = "c", addedAtMillis = addedAt)

    private fun artist(id: String, name: String = "N", addedAt: Long? = null) =
        TidalFavoriteArtist(id = id, name = name, pictureUrl = "p", addedAtMillis = addedAt)

    @Test
    fun `newRemoteFavorites keeps only remote favorites not already local (additive union)`() {
        val remote = listOf(album("111"), album("222"), album("333"))
        val localKeys = setOf("TIDAL:111", "LOCAL:999")
        assertEquals(listOf("222", "333"), newRemoteFavorites(remote, localKeys) { it.id }.map { it.id })
    }

    @Test
    fun `newRemoteFavorites returns empty when every remote favorite is already local`() {
        val remote = listOf(album("111"), album("222"))
        assertEquals(emptyList<String>(), newRemoteFavorites(remote, setOf("TIDAL:111", "TIDAL:222")) { it.id }.map { it.id })
    }

    @Test
    fun `newRemoteFavorites is reused unchanged for artists`() {
        val remote = listOf(artist("7"), artist("8"))
        assertEquals(listOf("7"), newRemoteFavorites(remote, setOf("TIDAL:8")) { it.id }.map { it.id })
    }

    @Test
    fun `favoriteAlbumEntityFor projects a Tidal album favorite row with cover and artist`() {
        val row = favoriteAlbumEntityFor(album("9", title = "Album Nine"), addedAtMillis = 1_000)
        assertEquals(Source.TIDAL.name, row.source)
        assertEquals("9", row.sourceId)
        assertEquals("Album Nine", row.title)
        assertEquals("A", row.artist)
        assertEquals("c", row.artUrl)
        assertEquals(1_000L, row.addedAtMillis)
    }

    @Test
    fun `favoriteAlbumEntityFor falls back to a stable title when the favorite has none`() {
        val blank = TidalFavoriteAlbum(id = "12", title = "  ", artistName = null, coverUrl = null, addedAtMillis = null)
        val row = favoriteAlbumEntityFor(blank, 0)
        assertEquals("Tidal album 12", row.title)
        assertNull(row.artist)
        assertNull(row.artUrl)
    }

    @Test
    fun `favoriteArtistEntityFor projects a Tidal artist favorite row with picture`() {
        val row = favoriteArtistEntityFor(artist("7", name = "Prompto"), addedAtMillis = 2_000)
        assertEquals(Source.TIDAL.name, row.source)
        assertEquals("7", row.sourceId)
        assertEquals("Prompto", row.name)
        assertEquals("p", row.artUrl)
        assertEquals(2_000L, row.addedAtMillis)
    }

    @Test
    fun `favoriteArtistEntityFor falls back to a stable name when the favorite has none`() {
        val blank = TidalFavoriteArtist(id = "21", name = "", pictureUrl = null, addedAtMillis = null)
        assertEquals("Tidal artist 21", favoriteArtistEntityFor(blank, 0).name)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalCollectionSyncTest*'`
Expected: FAIL — compilation error: `newRemoteFavorites`, `favoriteAlbumEntityFor`, `favoriteArtistEntityFor` are unresolved references (`TidalCollectionSync.kt` does not exist yet).

- [ ] **Step 3: Add the repository accessors**

In `LibraryRepository.kt`, add the imports `io.github.auxen.db.FavoriteAlbumEntity` and `io.github.auxen.db.FavoriteArtistEntity`, then add (DB access stays encapsulated in the repository; every write is idempotent):

```kotlin
    /** Favorited albums, newest favorite first (the Collection Albums tab's remote half). */
    fun favoriteAlbums(): Flow<List<FavoriteAlbumEntity>> = db.favoriteAlbumDao().all()

    /** "SOURCE:sourceId" keys of favorited albums — the album analogue of [favoriteKeys]. */
    fun favoriteAlbumKeys(): Flow<Set<String>> = db.favoriteAlbumDao().keys().map { it.toSet() }

    /** Insert-or-refresh a favorite album row. Idempotent on (source, source_id). */
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
import kotlinx.coroutines.flow.first

/** What one full pull added locally, per favorite type. */
data class CollectionPullSummary(val albums: Int, val artists: Int)

/**
 * Two-way reconcile between the user's Tidal favorites (internal-v1, via
 * [TidalProvider]) and the local favorite tables ([LibraryRepository]) — saved
 * albums and followed artists. Held on `Graph` (a plain class, not an object).
 * Pure merge logic is unit-tested; the network+DB orchestration is not
 * (internal-v1 precedent). Playback of a synced favorite stays on the
 * internal-v1 stream path — this class touches metadata + favorite-state only.
 */
class TidalCollectionSync(
    private val tidal: TidalProvider,
    private val library: LibraryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Pull Tidal saved albums into `favorite_albums` (additive union: never
     * deletes a local favorite). Returns the number of newly-added favorites.
     */
    suspend fun pullFavoriteAlbums(): Int {
        val remote = tidal.favoriteAlbums()
        val fresh = newRemoteFavorites(remote, library.favoriteAlbumKeys().first()) { it.id }
        fresh.forEach { library.favoriteAlbum(favoriteAlbumEntityFor(it, it.addedAtMillis ?: clock())) }
        return fresh.size
    }

    /** Pull Tidal followed artists into `favorite_artists` (additive union). Returns the number newly added. */
    suspend fun pullFavoriteArtists(): Int {
        val remote = tidal.favoriteArtists()
        val fresh = newRemoteFavorites(remote, library.favoriteArtistKeys().first()) { it.id }
        fresh.forEach { library.favoriteArtist(favoriteArtistEntityFor(it, it.addedAtMillis ?: clock())) }
        return fresh.size
    }

    /** One pull of both favorite types. */
    suspend fun pullAll(): CollectionPullSummary =
        CollectionPullSummary(albums = pullFavoriteAlbums(), artists = pullFavoriteArtists())

    /**
     * Push an album favorite toggle to Tidal. Best-effort and optimistic — the
     * caller has already updated the local table; a failure here is not rolled
     * back. No-op for local-source ids (they are not on Tidal).
     */
    suspend fun pushAlbumFavorite(source: Source, sourceId: String, favorite: Boolean) {
        if (source != Source.TIDAL) return
        if (favorite) tidal.addFavoriteAlbum(sourceId) else tidal.removeFavoriteAlbum(sourceId)
    }

    /** Push an artist follow toggle to Tidal. Same optimistic, Tidal-only contract as [pushAlbumFavorite]. */
    suspend fun pushArtistFavorite(source: Source, sourceId: String, favorite: Boolean) {
        if (source != Source.TIDAL) return
        if (favorite) tidal.addFavoriteArtist(sourceId) else tidal.removeFavoriteArtist(sourceId)
    }
}

/**
 * Remote favorites whose `TIDAL:<id>` key is not already locally favorited.
 * Pure and type-agnostic: the caller supplies the id selector and the matching
 * local key set (`favoriteAlbumKeys` or `favoriteArtistKeys`), both of which
 * emit the same `SOURCE:sourceId` shape.
 */
fun <T> newRemoteFavorites(remote: List<T>, localFavoriteKeys: Set<String>, id: (T) -> String): List<T> =
    remote.filter { "${Source.TIDAL.name}:${id(it)}" !in localFavoriteKeys }

/**
 * A [FavoriteAlbumEntity] for a pulled Tidal album favorite — cover + artist
 * populated from internal-v1. Title falls back to a stable placeholder only
 * when the favorite has none. Pure.
 */
fun favoriteAlbumEntityFor(fav: TidalFavoriteAlbum, addedAtMillis: Long): FavoriteAlbumEntity = FavoriteAlbumEntity(
    source = Source.TIDAL.name,
    sourceId = fav.id,
    title = fav.title.ifBlank { "Tidal album ${fav.id}" },
    artist = fav.artistName?.ifBlank { null },
    artUrl = fav.coverUrl,
    addedAtMillis = addedAtMillis,
)

/**
 * A [FavoriteArtistEntity] for a pulled Tidal followed artist — picture @480
 * populated from internal-v1. Name falls back only when absent. Pure.
 */
fun favoriteArtistEntityFor(fav: TidalFavoriteArtist, addedAtMillis: Long): FavoriteArtistEntity = FavoriteArtistEntity(
    source = Source.TIDAL.name,
    sourceId = fav.id,
    name = fav.name.ifBlank { "Tidal artist ${fav.id}" },
    artUrl = fav.pictureUrl,
    addedAtMillis = addedAtMillis,
)
```

- [ ] **Step 5: Wire `Graph.tidalCollectionSync`**

In `AuxenApp.kt`, in `object Graph`, add the field beside `library` (with `import io.github.auxen.provider.tidal.TidalCollectionSync` in the import block):

```kotlin
    lateinit var tidalCollectionSync: TidalCollectionSync
        private set
```

**Placement in `init(context)` is load-bearing.** `TidalCollectionSync` takes BOTH `tidal` and `library`, both `lateinit var`s on `Graph`. In the current `init(context)` they are assigned two statements apart, with `db` in between:

```kotlin
        tidal = TidalProvider(tidalAuth, httpClient)   // assigned first
        db = AuxenDatabase.build(context)
        library = LibraryRepository(db)                // …library only lands HERE
```

So the new line goes **immediately after `library = LibraryRepository(db)`**, NOT after the `tidal = …` line:

```kotlin
        db = AuxenDatabase.build(context)
        library = LibraryRepository(db)
        tidalCollectionSync = TidalCollectionSync(tidal = tidal, library = library)   // ADD THIS LINE HERE
        queueStore = QueueStateStore(db)
```

Putting it directly after `tidal = …` **compiles cleanly and then throws at runtime on every app launch**: `kotlin.UninitializedPropertyAccessException: lateinit property library has not been initialized`. Nothing in this task's gates catches that (`assembleDebug` only compiles; the unit tests never call `Graph.init()`); it would first surface at Task 6's on-device checkpoint as "the app won't start at all". Order the assignment after both dependencies.

- [ ] **Step 6: Run tests + build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*TidalCollectionSyncTest*' --tests '*LibraryRepositoryTest*' assembleDebug`
Expected: PASS; APK builds (`Graph` wiring compiles; `TidalCollectionSync` defaults `clock`).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalCollectionSync.kt \
        android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/tidal/TidalCollectionSyncTest.kt
git commit -m "feat(android): reconcile Tidal favorite albums/artists + push toggles

TidalCollectionSync pulls saved albums + followed artists (additive union; one
pure newRemoteFavorites set-difference reused across both types; cover/picture-
populated favorite rows) and pushes local toggles to Tidal (optimistic, Tidal-
only). LibraryRepository gains the favorite-album/artist accessors; Graph wires
the sync after its tidal + library deps.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 5: Collection Albums/Artists tabs = local-grouped ∪ Tidal-favorited, + auto-pull

The payoff, and the desktop's Collection semantics: **Albums = Tidal saved albums ∪ local albums with favorited tracks**; **Artists** likewise. Today `CollectionScreen.kt` derives both tabs purely by grouping favorited *tracks* (`groupAlbums(filtered)`/`groupArtists(filtered)`), so a Tidal-favorited album with no favorited tracks is invisible. Add a **pure** union in `data/CollectionMerge.kt` (unit-tested), feed it the favorite tables, render it, and pull on Collection open.

Dedup follows the desktop rule (**exact name match**, trimmed + case-insensitive — not fuzzy): a remote favorite whose title/name matches a local group is dropped in favour of the local group (it has playable tracks). On internal-v1 a remote-only entry now carries its artist + art, so it renders fully; only its play affordance is absent (no local tracks to play). `groupAlbums`/`groupArtists`/`AlbumGroup`/`ArtistGroup` are REUSED, not re-declared.

**Pull trigger — DECISION (flagged).** The old plan put a "Sync from Tidal" button on the deleted `TidalOfficialDebugScreen`; there is no debug screen now. This slice uses **auto-pull-on-Collection-open, guarded** (once per app process, only when `Graph.tidal.isLoggedIn`) as the primary trigger, because that is what actually makes the empty tabs fill without the user hunting for a button — the reported bug. It is additive/idempotent, and it is *on Collection open*, not on every launch (no launch-time network surprise). A small **manual "Sync from Tidal" affordance** (an `IconButton` in the Collection header, `force = true`) is added for re-sync + the on-device idempotency check; it is safe for Roborazzi because **no golden captures `CollectionScreen`** — the goldens are per-component (`AlbumCard`/`ArtistRow`/track rows, verified in `ComponentScreenshotTest`/`AlbumCardUiTest`/`ArtistRowUiTest`), and those keep rendering unchanged because the new `onToggleFavorite` param defaults to null. **Open UX question for the user:** the manual affordance's exact placement/styling (a header icon vs. a Material3 pull-to-refresh) is a genuine design call — this plan ships the header icon as the low-risk default; swapping it for pull-to-refresh is a follow-up, not a blocker.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/data/CollectionMerge.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumCard.kt` (optional `onToggleFavorite`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistRow.kt` (optional `onToggleFavorite`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (`favoriteAlbums`/`favoriteArtists` flows, `toggleFavoriteAlbum`/`toggleFavoriteArtist`, `syncFavoritesFromTidal` + status flow + guard)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt` (render the union + the manual sync affordance + auto-pull)
- Test: `android/app/src/test/kotlin/io/github/auxen/data/CollectionMergeTest.kt` (create)

**Interfaces:**
- Consumes: `AlbumGroup`/`ArtistGroup`/`groupAlbums`/`groupArtists` (`data/LibraryGrouping.kt`, REUSED); `FavoriteAlbumEntity`/`FavoriteArtistEntity` (Task 3); `LibraryRepository.favoriteAlbums()`/`favoriteArtists()` + `unfavoriteAlbum`/`unfavoriteArtist` (Task 4); `TidalCollectionSync.pushAlbumFavorite`/`pushArtistFavorite`/`pullAll` (Task 4); `Graph.tidal.isLoggedIn`; `Source`/`Track`.
- Produces: `CollectionAlbum(title, artist, artUrl, source, tidalId, tracks)` with `key`/`isRemoteFavorite`/`toAlbumGroup()`; `CollectionArtist(name, artUrl, source, tidalId, trackCount)` with `key`/`isRemoteFavorite`; `fun mergeCollectionAlbums(local, remote): List<CollectionAlbum>`; `fun mergeCollectionArtists(local, remote): List<CollectionArtist>`; `PlayerViewModel.favoriteAlbums`/`favoriteArtists`/`toggleFavoriteAlbum`/`toggleFavoriteArtist`/`syncFavoritesFromTidal`/`tidalCollectionStatus`; `AlbumCard(onToggleFavorite = …)`; `ArtistRow(onToggleFavorite = …)`.

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
 * The Collection Albums/Artists union — desktop semantics: Tidal saved albums ∪
 * local albums with favorited tracks (Artists likewise). Pure; no Room, no
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

    private fun savedAlbum(id: String, title: String, artist: String? = "Saved Artist", art: String? = "https://art/$id.jpg") =
        FavoriteAlbumEntity(source = "TIDAL", sourceId = id, title = title, artist = artist, artUrl = art, addedAtMillis = 0)

    private fun followedArtist(id: String, name: String, art: String? = "https://pic/$id.jpg") =
        FavoriteArtistEntity(source = "TIDAL", sourceId = id, name = name, artUrl = art, addedAtMillis = 0)

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
    fun `a saved album with no local tracks still surfaces, fully rendered`() {
        val merged = mergeCollectionAlbums(emptyList(), listOf(savedAlbum("9", "Album Nine")))
        assertEquals(listOf("Album Nine"), merged.map { it.title })
        assertEquals("9", merged[0].tidalId)
        assertTrue(merged[0].isRemoteFavorite)
        assertEquals(Source.TIDAL, merged[0].source)
        // Internal-v1 carries the artist + cover, so a remote-only card renders fully.
        assertEquals("Saved Artist", merged[0].artist)
        assertEquals("https://art/9.jpg", merged[0].artUrl)
        assertTrue(merged[0].tracks.isEmpty())
    }

    @Test
    fun `a saved album that already has a local group is deduped in favour of the local group`() {
        val local = groupAlbums(listOf(tidalTrack("1", "OutRun", "Kavinsky")))
        val merged = mergeCollectionAlbums(local, listOf(savedAlbum("9", "OutRun")))
        assertEquals(1, merged.size)
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
        assertEquals("Saved Artist", group.albumArtist)
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
    fun `a followed artist with no local tracks still surfaces with its picture`() {
        val merged = mergeCollectionArtists(emptyList(), listOf(followedArtist("7", "Prompto")))
        assertEquals(listOf("Prompto"), merged.map { it.name })
        assertEquals("https://pic/7.jpg", merged[0].artUrl)
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
 * reused from [groupAlbums]/[groupArtists] (LibraryGrouping.kt).
 */

/**
 * One Collection Albums-tab entry. Either a locally-grouped album (favorited
 * tracks — has [tracks]) or a Tidal saved album with no local tracks (has
 * [tidalId]). On internal-v1 both carry [artist] + [artUrl].
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
    // The local ArtistGroup this row came from, or null for a follow-only remote
    // artist (which has no local tracks). Carried so the Collection Artists tab
    // can keep its long-press action sheet (Play / Shuffle / Play Next / Enqueue),
    // added in the 2026-07-18 context-menu work — the sheet needs a real track
    // pool, which only a local group has. mergeCollectionArtists sets this to the
    // local group for local entries and null for remote-only ones.
    val localGroup: ArtistGroup? = null,
) {
    val isRemoteFavorite: Boolean get() = tidalId != null

    val key: String get() = if (tidalId != null) "tidal:$tidalId" else "local:$name"
}

/**
 * Albums tab = [local] groups ∪ [remote] saved albums, local first. A remote
 * favorite whose title matches a local group's album title is dropped: the
 * local group is strictly richer (playable tracks). Dedup is EXACT-name
 * (trimmed, case-insensitive) — never fuzzy, so clean/explicit and deluxe
 * variants deliberately stay visible as separate entries (desktop behaviour).
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
                artist = row.artist,
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
            localGroup = group, // carries the track pool so the row keeps its long-press action sheet
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

Both params are **defaulted to null**, so every existing call site (Library, Home, ArtistDetail, and the `ComponentScreenshotTest`/`AlbumCardUiTest`/`ArtistRowUiTest` goldens) compiles and renders unchanged. The heart only ever appears on a remote favorite, which is by definition already favorited, so it always renders filled and always means "remove from collection" — no `isFavorite` param needed.

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

(Confirm `Icon`/`IconButton`/`Alignment`/`AuxenColors` are already imported in `AlbumCard.kt`; the source badge + play button already use `IconButton`/`Alignment`, so add only whichever of `Icon`/`AuxenColors` the file does not yet import.)

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

(Confirm `MaterialTheme` and `Icons` are imported in `ArtistRow.kt`; add whichever is missing.)

- [ ] **Step 5: Add the ViewModel flows, toggles, and guarded sync**

In `PlayerViewModel.kt`, add the imports `io.github.auxen.data.CollectionAlbum`, `io.github.auxen.data.CollectionArtist`, `io.github.auxen.db.FavoriteAlbumEntity`, `io.github.auxen.db.FavoriteArtistEntity`, and — **required** — `import io.github.auxen.model.Source` (the toggles below name `Source.TIDAL`; stock `PlayerViewModel.kt` imports `SourcePriority`/`Track` from that package but *not* `Source`, so omitting it is an `unresolved reference: Source` compile error). Then add beside the existing `favorites`/`favoriteKeys` flows:

```kotlin
    /** Favorited albums (Tidal saved albums + local), newest first — the Collection Albums tab's remote half. */
    val favoriteAlbums: StateFlow<List<FavoriteAlbumEntity>> = Graph.library.favoriteAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Favorited (followed) artists, newest first — the Collection Artists tab's remote half. */
    val favoriteArtists: StateFlow<List<FavoriteArtistEntity>> = Graph.library.favoriteArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var collectionSynced = false
    private val _tidalCollectionStatus = MutableStateFlow<String?>(null)
    val tidalCollectionStatus: StateFlow<String?> = _tidalCollectionStatus
```

Add the guarded pull + the two unfavorite toggles near `toggleFavorite` (the status string carries only counts and names — never a token):

```kotlin
    /**
     * Pull the user's Tidal saved albums + followed artists into the local
     * favorite tables. No-op when not signed in. Guarded to once per process
     * (the auto-pull-on-Collection-open path passes force = false); the manual
     * "Sync from Tidal" affordance passes force = true. Additive + idempotent.
     */
    fun syncFavoritesFromTidal(force: Boolean = false) {
        if (!Graph.tidal.isLoggedIn) return
        if (collectionSynced && !force) return
        collectionSynced = true
        viewModelScope.launch {
            _tidalCollectionStatus.value = "Syncing favorites from Tidal…"
            _tidalCollectionStatus.value = runCatching { Graph.tidalCollectionSync.pullAll() }.fold(
                onSuccess = { "Synced +${it.albums} album(s), +${it.artists} artist(s) from Tidal." },
                onFailure = { collectionSynced = false; "Favorites sync failed: ${it.message}" },
            )
        }
    }

    /**
     * Remove a Collection album entry from the collection. Only a remote
     * favorite row carries a Tidal id to write with (a locally-grouped album is
     * on the tab because its TRACKS are favorited). Optimistic: the local row
     * goes first, then a best-effort push.
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

- [ ] **Step 6: Render the union + the manual sync affordance + auto-pull in `CollectionScreen`**

In `CollectionScreen.kt`, add the imports `io.github.auxen.data.mergeCollectionAlbums`, `io.github.auxen.data.mergeCollectionArtists`, `androidx.compose.material.icons.filled.Sync`, `androidx.compose.runtime.LaunchedEffect` (confirm — it is already imported for the track-info sheet), and collect the two new flows + status beside the existing `favorites`/`playlists`:

```kotlin
    val favoriteAlbumRows by viewModel.favoriteAlbums.collectAsState()
    val favoriteArtistRows by viewModel.favoriteArtists.collectAsState()
    val syncStatus by viewModel.tidalCollectionStatus.collectAsState()
```

Auto-pull once when the screen enters (the ViewModel's `collectionSynced` guard makes it once-per-process, and its `isLoggedIn` guard makes it a no-op when signed out):

```kotlin
    LaunchedEffect(Unit) { viewModel.syncFavoritesFromTidal() }
```

Add a source-filter predicate for the favorite rows next to the existing `matches(track)` (the All/Tidal/Local chips must apply to the remote half too):

```kotlin
    fun matchesSource(source: String): Boolean = when (filter) {
        "tidal" -> source == Source.TIDAL.name
        "local" -> source == Source.LOCAL.name
        else -> true
    }
```

Add a manual **Sync** icon to the filter `Row` (the `Row` that renders the All/Tidal/Local chips, shown for `tab != 3`) — after the `forEach` that emits the chips, so it trails them:

```kotlin
                IconButton(onClick = { viewModel.syncFavoritesFromTidal(force = true) }) {
                    Icon(Icons.Filled.Sync, contentDescription = "Sync favorites from Tidal")
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
                                // PRESERVE the existing album long-press action sheet (Play /
                                // Shuffle / Play Next / Enqueue / Add-to-Playlist), added in the
                                // 2026-07-18 context-menu work. The pre-rewrite code wired
                                // `onLongPress = { albumSheet = album }` (CollectionScreen.kt:203);
                                // this rewrite MUST keep it or it silently drops the menu and
                                // orphans `albumSheet` + `AlbumActionSheet`. A remote-only saved
                                // album (no local tracks) has nothing to feed the sheet, so gate it.
                                onLongPress = album.toAlbumGroup().takeIf { it.tracks.isNotEmpty() }
                                    ?.let { grp -> { albumSheet = grp } },
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
                                // PRESERVE the existing artist long-press action sheet (Play /
                                // Shuffle / Play Next / Enqueue), added in the 2026-07-18
                                // context-menu work. The pre-rewrite code wired
                                // `onLongPress = { artistSheet = artist }` (CollectionScreen.kt:227);
                                // keep it or it silently drops the menu and orphans `artistSheet` +
                                // `ArtistActionSheet`. The sheet needs a real track pool, which only
                                // a local group has — so wire it off `localGroup` (null for a
                                // follow-only remote artist, which correctly gets no menu).
                                onLongPress = artist.localGroup?.let { grp -> { artistSheet = grp } },
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

The `AlbumGroup`/`groupAlbums`/`groupArtists` imports already present stay — `groupAlbums`/`groupArtists` still produce the *local* half, and `AlbumGroup` is still the `onOpenAlbum` parameter type. `syncStatus` may optionally be surfaced as a transient `Text` under the filter row; it is otherwise observed for the on-device checkpoint. (If you render it, it is plain human text — never a token.)

**Known limitation to record, not fix here:** tapping a remote-only saved album opens `AlbumDetailScreen`, which resolves its track list by name from the local pool and will show its empty state (no local tracks). Fetching a saved album's tracks from Tidal is enrichment work, out of scope for this slice.

- [ ] **Step 7: Run tests + build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*CollectionMergeTest*' --tests '*LibraryGroupingTest*' verifyRoborazziDebug assembleDebug`
Expected: PASS; APK builds. `verifyRoborazziDebug` must still pass **unchanged**: `onToggleFavorite` defaults to null so the `AlbumCard`/`ArtistRow` goldens render exactly as before, and no golden captures `CollectionScreen`. If a golden shifts, a default leaked — fix the default rather than re-recording.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/data/CollectionMerge.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumCard.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistRow.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt \
        android/app/src/test/kotlin/io/github/auxen/data/CollectionMergeTest.kt
git commit -m "feat(android): Collection Albums/Artists = local-grouped + Tidal saved

Surfaces Tidal saved albums / followed artists in Collection even with no local
tracks (desktop CollectionView semantics), via a pure mergeCollectionAlbums/
Artists union over the favorite tables (exact-name dedup favouring the richer
local group). Auto-pulls on Collection open (guarded once-per-process, signed-in
only) with a manual Sync affordance; remote entries get an unfavorite heart
(optional defaulted param keeps every AlbumCard/ArtistRow golden unchanged).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 6: On-device checkpoint (the slice deliverable)

No new code — this task proves the two-way path on device, gated on the migration running over a real v2 install. The pull is auto-triggered on Collection open (Task 5) with a manual **Sync** re-trigger; push (album/artist unfavorite) is wired into the Collection hearts (Task 5).

- [ ] **Step 1: Full gate + local APK build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest verifyRoborazziDebug assembleDebug`
Expected: PASS — the whole suite, the screenshot gate, and the APK. This is the same triple CI runs (`.github/workflows/android.yml`). Build the APK **locally** — CI has no Tidal credentials, so on-device login only works from a locally-built APK.

- [ ] **Step 2: On-device checkpoint (the Slice 2 deliverable)**

Install **over an existing v2 install** (do NOT uninstall first — that is the only way to exercise `MIGRATION_2_3` on real data), then:

1. **App launches + migration.** The app must launch and Collection must still list existing favorites. Two failure modes hide behind "it won't start", and no earlier gate catches either — read the stack trace:
   - `kotlin.UninitializedPropertyAccessException: lateinit property library has not been initialized` in `Graph.init` ⇒ the `tidalCollectionSync = …` line was placed **before** `library = LibraryRepository(db)`. Move it after. See Task 4, Step 5.
   - `IllegalStateException` on first DB access ⇒ a real `MIGRATION_2_3` failure (Task 3's `MigrationTest` should have caught it).
2. **Login.** Sign in to Tidal (the existing device-code flow). Confirm the app reports logged-in (`restoreSession()` captured the user id from `/sessions`).
3. **Auto-pull.** Open Collection. On first open while signed in it pulls automatically; the status reads "Synced +N album(s), +N artist(s) from Tidal." (Tap the **Sync** icon to re-trigger.)
4. **Albums surface (the decision under test).** Collection → **Albums** shows your Tidal saved albums *including ones you have no local tracks for* — **with cover art and artist name** (internal-v1 carries both). Expect ~114.
5. **Artists surface.** Collection → **Artists** shows your followed artists **with pictures**, remote-only ones subtitled "Followed on Tidal". Expect ~110.
6. **Filter chips.** Switch All → Tidal → Local; confirm remote-only entries appear under All/Tidal and vanish under Local.
7. **Play a locally-favorited Tidal track** (Tracks tab) and confirm it still streams (auth/streaming untouched).
8. **Push remove (albums/artists).** Tap the heart on a remote-only saved album card and on a followed artist row. Each disappears from the tab; confirm in the official Tidal app that the album is no longer saved / the artist no longer followed.
9. **Idempotency.** Tap **Sync** again; confirm "+0 album(s), +0 artist(s)" and no duplicates.
10. **Verify no token/refresh-token ever appears in Logcat** during the whole flow.

Paste the status readout + the Collection Albums/Artists tab result back to close the loop.

---

## Self-review (writing-plans checklist)

- **Schema coverage (probed, GROUND TRUTH):** READ — `GET users/{userId}/favorites/albums?limit&offset&order=DATE&orderDirection=DESC&countryCode` and `.../artists`, wrapper `{ items:[{ created, item }], totalNumberOfItems }`; album item `{id,title,cover,numberOfTracks,releaseDate,explicit,artists[]}`, artist item `{id,name,picture}` (Task 1). WRITE — `POST .../favorites/albums` form `albumId=<id>`, `POST .../favorites/artists` form `artistId=<id>`, `DELETE .../favorites/albums/{id}`, `DELETE .../favorites/artists/{id}` — the exact tidalapi shapes (`tidalapi/user.py` `Favorites.add_album/add_artist` use `data={"albumId"/"artistId": …}`, form-urlencoded; `FormBody` is OkHttp's equivalent) (Tasks 1–2). USER ID — from the `/sessions` bootstrap the provider already does (`request["userId"]`, exactly how tidalapi resolves `session.user.id`); captured in `ApiSession.userId`, no new resolver (Task 2). SCHEMA — narrow `favorite_albums`/`favorite_artists` + additive `MIGRATION_2_3`, `version = 3` (current version verified `2`) (Task 3). RECONCILE — additive-union merge keyed on `source:sourceId`, optimistic push (Task 4). SURFACE — Collection Albums/Artists = local-grouped ∪ remote-favorited, exact-name dedup (Task 5).
- **What was excised (all deleted official-v2):** `userCollections`, `TidalMetadataRepository`, `TidalJsonApi`/compound-doc parsing, `TidalOfficialClient`/`TidalOfficialEndpoints`, PKCE, `executeUnit`, `resolveCollectionId`/`currentUserId`/`userIdFrom`, the collection-id discovery problem, the JSON:API `include`-limitation discussion, the title-only-placeholder / self-heal / no-artist / no-art caveat, the `TidalOfficialDebugScreen` sync button, and every `Authorization: Bearer` + `application/vnd.api+json` / `client_secret` mention — grep-verified absent from `app/src` (only two internal-v1 files mention "official-v2" in explanatory KDoc). None remain in this plan.
- **Placeholder scan:** every code step shows real code; every test step real assertions. The only non-TDD surfaces are Task 5's Compose rendering (its *logic* — the union — is fully unit-tested as a pure function) and Task 6's on-device checkpoint. No `TODO`/"similar to above".
- **Type consistency:** `TidalFavoriteAlbum`/`TidalFavoriteArtist` (Task 1) → `newRemoteFavorites`/`favoriteAlbumEntityFor`/`favoriteArtistEntityFor` (Task 4) → `FavoriteAlbumEntity`/`FavoriteArtistEntity` (Task 3) → `LibraryRepository.favoriteAlbums()`/`favoriteArtists()` → `mergeCollectionAlbums`/`mergeCollectionArtists` (Task 5) → `CollectionAlbum`/`CollectionArtist`; fields align at each hop. `TidalCollectionSync(tidal, library, clock)` matches its `Graph` construction; `pullAll`/`pushAlbumFavorite`/`pushArtistFavorite` match the ViewModel call sites. `Source.TIDAL.name` used identically in `FavoriteAlbumDao.keys()`/`FavoriteArtistDao.keys()` (`source || ':' || source_id`), `newRemoteFavorites`, and the merge.
- **No re-declarations (compile-error trap):** grep-verified zero existing declarations in `app/src` for every NEW name — `TidalFavoriteAlbum`, `TidalFavoriteArtist`, `parseIso8601Millis`, `favoriteAlbumsFrom`, `favoriteArtistsFrom`, `favoriteAlbumsUrl`/`favoriteArtistsUrl`/`favoriteAlbumsWriteUrl`/`favoriteArtistsWriteUrl`/`favoriteAlbumDeleteUrl`/`favoriteArtistDeleteUrl`, `FavoriteAlbumEntity`, `FavoriteArtistEntity`, `FavoriteAlbumDao`, `FavoriteArtistDao`, `MIGRATION_2_3`, `DB_NAME`, `TidalCollectionSync`, `CollectionPullSummary`, `newRemoteFavorites`, `favoriteAlbumEntityFor`, `favoriteArtistEntityFor`, `CollectionAlbum`, `CollectionArtist`, `mergeCollectionAlbums`, `mergeCollectionArtists`, `syncFavoritesFromTidal`. REUSED, never re-declared: `tidalV1CoverUrl` (640), `tidalV1ArtistPictureUrl` (480), `TidalV1AlbumArtist`, `AlbumGroup`/`ArtistGroup`/`groupAlbums`/`groupArtists`, `Source`/`Track`.
- **Every symbol new code names resolves (the per-file import trap):** `PlayerViewModel.kt` needs `import io.github.auxen.model.Source` added (it imports `SourcePriority`/`Track`/`QueueEntry` but not `Source`) — called out in Task 5 Step 5. `TidalProvider.kt` needs `import okhttp3.FormBody` for the write helper — Task 2 Step 2. `CollectionScreen.kt` already imports `Source`/`AlbumGroup`/`groupAlbums`/`groupArtists`, needing only the two merge functions + `Icons.Filled.Sync`. `LibraryRepository.kt` needs the two new entity imports. `TidalFavorites.kt` reuses `tidalV1CoverUrl`/`tidalV1ArtistPictureUrl`/`TidalV1AlbumArtist` from the same package (no import). The new files carry complete import blocks in their code steps.
- **`Graph.init(context)` order checked, not assumed:** `tidalCollectionSync` lands **after `library = LibraryRepository(db)`** (two statements after `tidal = …`, past `db = …`), because it needs both `tidal` and `library`. Wrong order compiles and throws `UninitializedPropertyAccessException` at launch — pinned by explicit instruction (Task 4 Step 5) and a Global Constraint, since no gate here calls `Graph.init()`.
- **The migration test can actually run:** `exportSchema = false` + no `androidx.room:room-testing` + no `androidTest` source set ⇒ `MigrationTestHelper` is impossible and is NOT specced. Task 3's `MigrationTest` manufactures the v2 file (create v3 → drop the two new tables → `user_version = 2`, valid because the migration is additive) and reopens via `AuxenDatabase.build()`, letting Room's generated `validateMigration` be the assertion. Runs on the existing Robolectric + `androidx.test.core` deps.
- **Roborazzi:** no golden captures `CollectionScreen` (goldens are per-component: `AlbumCard`/`ArtistRow`/track rows in `ComponentScreenshotTest`/`AlbumCardUiTest`/`ArtistRowUiTest`). The new `onToggleFavorite` param defaults to null so those goldens render unchanged. Populating previously-empty Collection tabs is a real on-device change but has no golden. Task 5's gate runs `verifyRoborazziDebug`; a shift means a leaked default — fix the default, don't re-record.
- **Boundaries honored:** internal-v1 auth (`TidalAuth.kt`) untouched; streaming/search stay internal-v1; no token logging; writes/network never unit-tested; `TidalCollectionSync` is a plain `Graph`-held class; every gradle line carries `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`; every `git add` uses explicit pathspecs. `git status` at the end should show only the files listed across Tasks 1–5.
- **Open questions / flagged decisions:**
  1. **Pull-trigger UX (flagged, not invented):** this plan ships **auto-pull-on-Collection-open (guarded, signed-in only)** as the primary fix plus a header **Sync** icon for manual re-sync. The exact manual affordance (header icon vs. Material3 pull-to-refresh vs. a Settings entry) is a genuine UX call the user may want to redirect; the header icon is the low-risk default (no golden captures Collection). Not a blocker.
  2. **Track-favorite two-way sync is deferred** (optional parity). The Collection Tracks tab already works off locally-favorited tracks; pulling Tidal favorite *tracks* into the DB and pushing track hearts to Tidal is a clean follow-up on the same `TidalProvider` favorites surface.
  3. **Destructive "mirror-delete" pull** (drop a local favorite removed on another device) and an **offline write-queue** are deferred (documented limitations; the pull is additive/idempotent).
  4. **A remote-only saved album's detail screen** shows its empty state (no local tracks to list). Fetching a saved album's tracks from Tidal is enrichment work, out of scope.
  5. **`artist` column on `favorite_albums`** is one column beyond the old plan's listed narrow schema — added deliberately because internal-v1 provides the album artist and "renders fully" is the goal. Called out in Design decisions and here so it is a visible schema choice, not a silent drift.
