# Auxen Android — Milestone 3b: Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the desktop-parity screens on the M3a foundation: full Home (filter chips, stat cards, Recently Added carousel), Library with Albums/Artists/Tracks tabs + sort, Search with type filters and persistent history, Collection with tabs + source filter, album/artist detail, and playlist detail with management actions.

**Architecture:** Screens compose the M3a components (`AuxenTrackRow`, `AlbumCard`, `SectionHeader`, badges, `TrackActionSheet`) over data that mostly already exists (Room favorites/playlists/history, `LocalProvider`, `PlayerViewModel`). New data work: a `search_history` table (Room schema v2 with an explicit migration), a `LocalProvider.recentlyAdded()` query, in-memory album/artist grouping of the local library (pure, testable functions in a new `data/LibraryGrouping.kt`), and playlist reorder/rename/recolor/delete repository methods. Detail screens are new navigation routes taking encoded arguments.

**Tech Stack:** Jetpack Compose (BOM 2024.12.01, Material 3), navigation-compose 2.8.5, Room 2.7.1 (schema v2 + Migration), Media3 1.5.1. Design source of record: `.superpowers/sdd/m3-ui-inventory.md`.

## Global Constraints

- All Gradle commands run from `/home/mrw1986/Projects/auxen/android` and MUST be prefixed with `JAVA_HOME=~/.jdks/jdk-21.0.11+10`.
- Test baseline at plan start: **57 tests, 0 failures** — every task ends with `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL and states its expected new total.
- Room schema change (Task 1) MUST ship a real `Migration(1, 2)` — never `fallbackToDestructiveMigration` (it would wipe the queue/favorites on existing installs).
- Design tokens only via `AuxenColors`/`MaterialTheme`; typography roles per M3a (`titleLarge` = Fraunces section titles, `displaySmall` = greeting).
- Route names (M3a set is law): existing `home|library|search|collection|equalizer|account|nowplaying`; new routes exactly `album/{album}/{artist}` (both Uri-encoded), `artist/{artist}` (Uri-encoded), `playlist/{playlistId}` (Long).
- Settings keys (desktop parity): `home_filter`, `collection_filter`, `library_tab`, `library_sort_<tab>`, `library_dir_<tab>` — stored via existing `SettingsDao`.
- mediaId/favorite-key format `"${source.name}:${sourceId}"` everywhere.
- Filter pill values are exactly `all` / `tidal` / `local` (lowercase, persisted).
- Code style: KDoc, 4-space indent, trailing commas.
- Commit messages: conventional commits with the implementer's co-author trailer given per dispatch.

---

### Task 1: Search history — Room v2 migration, DAO, repository

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/Entities.kt` (add entity)
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/Daos.kt` (add DAO)
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/AuxenDatabase.kt` (version 2 + migration)
- Modify: `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt` (history methods)
- Test: `android/app/src/test/kotlin/io/github/auxen/db/SearchHistoryDaoTest.kt` (new)
- Test: `android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt` (extend)

**Interfaces:**
- Consumes: existing `AuxenDatabase`/`SettingsDao` patterns.
- Produces (Task 4 relies on):
  - `SearchHistoryEntity(query: String /* @PrimaryKey */, searchedAtMillis: Long)`
  - `SearchHistoryDao`: `put(entry)` (REPLACE), `recent(limit: Int): Flow<List<SearchHistoryEntity>>` (newest first), `delete(query: String)`, `clear()`
  - `AuxenDatabase.searchHistoryDao()`; DB `version = 2` with `MIGRATION_1_2`
  - `LibraryRepository`: `fun searchHistory(limit: Int = 10): Flow<List<String>>`, `suspend fun addSearchHistory(query: String)`, `suspend fun deleteSearchHistoryItem(query: String)`, `suspend fun clearSearchHistory()`

- [ ] **Step 1: Write the failing DAO test**

Create `android/app/src/test/kotlin/io/github/auxen/db/SearchHistoryDaoTest.kt`:

```kotlin
package io.github.auxen.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchHistoryDaoTest {

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
    fun ordersNewestFirstAndDedupesByQuery() = runBlocking {
        db.searchHistoryDao().put(SearchHistoryEntity("radiohead", searchedAtMillis = 100))
        db.searchHistoryDao().put(SearchHistoryEntity("foo fighters", searchedAtMillis = 200))
        // Re-searching an old query bumps it to the top, no duplicate row.
        db.searchHistoryDao().put(SearchHistoryEntity("radiohead", searchedAtMillis = 300))

        assertEquals(
            listOf("radiohead", "foo fighters"),
            db.searchHistoryDao().recent(10).first().map { it.query },
        )
    }

    @Test
    fun respectsLimitDeleteAndClear() = runBlocking {
        for (i in 1..5) db.searchHistoryDao().put(SearchHistoryEntity("q$i", searchedAtMillis = i.toLong()))
        assertEquals(listOf("q5", "q4", "q3"), db.searchHistoryDao().recent(3).first().map { it.query })

        db.searchHistoryDao().delete("q5")
        assertEquals(listOf("q4", "q3", "q2", "q1"), db.searchHistoryDao().recent(10).first().map { it.query })

        db.searchHistoryDao().clear()
        assertTrue(db.searchHistoryDao().recent(10).first().isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.db.SearchHistoryDaoTest"`
Expected: FAIL to compile (`unresolved reference: SearchHistoryEntity`).

- [ ] **Step 3: Add entity, DAO, migration**

Append to `Entities.kt`:

```kotlin
/** Recent search queries — desktop `search_history` table. */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    @ColumnInfo(name = "searched_at") val searchedAtMillis: Long,
)
```

Append to `Daos.kt`:

```kotlin
@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY searched_at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}
```

In `AuxenDatabase.kt`: add `SearchHistoryEntity::class` to the `entities` list, bump `version = 2`, add `abstract fun searchHistoryDao(): SearchHistoryDao`, and change the companion to:

```kotlin
    companion object {
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

        fun build(context: Context): AuxenDatabase =
            Room.databaseBuilder(context, AuxenDatabase::class.java, "auxen.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
```

Imports: `androidx.room.migration.Migration`, `androidx.sqlite.db.SupportSQLiteDatabase`.

- [ ] **Step 4: Run to verify pass**

Same command as Step 2. Expected: PASS (2 tests).

- [ ] **Step 5: Repository methods + failing test first**

Append to `LibraryRepositoryTest.kt`:

```kotlin
    @Test
    fun searchHistoryRoundTripsTrimmedAndIgnoresBlank() = runBlocking {
        repo.addSearchHistory("  radiohead  ")
        repo.addSearchHistory("")
        repo.addSearchHistory("   ")
        assertEquals(listOf("radiohead"), repo.searchHistory().first())

        repo.deleteSearchHistoryItem("radiohead")
        assertTrue(repo.searchHistory().first().isEmpty())
    }
```

Run the data test class — expected compile FAIL (`unresolved reference: addSearchHistory`). Then append to `LibraryRepository.kt`:

```kotlin
    /** Recent search queries, newest first — desktop get_search_history. */
    fun searchHistory(limit: Int = 10): Flow<List<String>> =
        db.searchHistoryDao().recent(limit).map { list -> list.map { it.query } }

    suspend fun addSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        db.searchHistoryDao().put(SearchHistoryEntity(trimmed, clock()))
    }

    suspend fun deleteSearchHistoryItem(query: String) = db.searchHistoryDao().delete(query)

    suspend fun clearSearchHistory() = db.searchHistoryDao().clear()
```

(Import `io.github.auxen.db.SearchHistoryEntity`.) Re-run — PASS.

- [ ] **Step 6: Full verification**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, **60 tests** (57 + 3), 0 failures.

- [ ] **Step 7: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/db/ \
        android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt \
        android/app/src/test/kotlin/io/github/auxen/db/SearchHistoryDaoTest.kt \
        android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt
git commit -m "feat(android): search history table with v1->v2 Room migration"
```

---

### Task 2: Library grouping + sort (pure data layer)

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/data/LibraryGrouping.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/data/LibraryGroupingTest.kt`

**Interfaces:**
- Consumes: `Track` model.
- Produces (Tasks 3/6/7 rely on):
  - `data class AlbumGroup(val album: String, val albumArtist: String, val artUrl: String?, val year: Int?, val tracks: List<Track>)`
  - `data class ArtistGroup(val artist: String, val artUrl: String?, val tracks: List<Track>)` (trackCount via `tracks.size`)
  - `enum class LibrarySort { RECENTLY_ADDED, NAME, ARTIST, TRACK_COUNT }`
  - `fun groupAlbums(tracks: List<Track>): List<AlbumGroup>` (key = album + albumArtist-or-artist; tracks sorted by disc then track number; unknown album -> "Unknown Album")
  - `fun groupArtists(tracks: List<Track>): List<ArtistGroup>` (key = artist; "Unknown Artist" fallback)
  - `fun sortAlbums(albums: List<AlbumGroup>, sort: LibrarySort, ascending: Boolean): List<AlbumGroup>`
  - `fun sortArtists(artists: List<ArtistGroup>, sort: LibrarySort, ascending: Boolean): List<ArtistGroup>`
  - `fun sortTracks(tracks: List<Track>, sort: LibrarySort, ascending: Boolean): List<Track>`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/data/LibraryGroupingTest.kt`:

```kotlin
package io.github.auxen.data

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

private fun t(
    title: String,
    artist: String = "Artist",
    album: String? = "Album",
    albumArtist: String? = null,
    trackNumber: Int? = null,
    discNumber: Int? = null,
    sourceId: String = title,
) = Track(
    title = title,
    artist = artist,
    source = Source.LOCAL,
    sourceId = sourceId,
    album = album,
    albumArtist = albumArtist,
    trackNumber = trackNumber,
    discNumber = discNumber,
)

class LibraryGroupingTest {

    @Test
    fun groupAlbumsKeysOnAlbumPlusAlbumArtistAndOrdersTracks() {
        val tracks = listOf(
            t("B2", album = "X", trackNumber = 2, discNumber = 1),
            t("A1", album = "X", trackNumber = 1, discNumber = 1),
            t("C1", album = "X", trackNumber = 1, discNumber = 2),
            t("Solo", album = "Y", artist = "Other"),
        )
        val albums = groupAlbums(tracks)
        assertEquals(2, albums.size)
        val x = albums.first { it.album == "X" }
        assertEquals(listOf("A1", "B2", "C1"), x.tracks.map { it.title })
    }

    @Test
    fun sameAlbumNameDifferentArtistStaysSeparate() {
        val tracks = listOf(
            t("One", album = "Greatest Hits", artist = "Queen"),
            t("Two", album = "Greatest Hits", artist = "ABBA"),
        )
        assertEquals(2, groupAlbums(tracks).size)
    }

    @Test
    fun unknownAlbumAndArtistFallbacks() {
        val albums = groupAlbums(listOf(t("Loose", album = null)))
        assertEquals("Unknown Album", albums.single().album)
        val artists = groupArtists(listOf(t("Loose", artist = "")))
        assertEquals("Unknown Artist", artists.single().artist)
    }

    @Test
    fun groupArtistsAggregatesTracks() {
        val artists = groupArtists(
            listOf(t("One", artist = "Queen"), t("Two", artist = "Queen"), t("Three", artist = "ABBA")),
        )
        assertEquals(2, artists.size)
        assertEquals(2, artists.first { it.artist == "Queen" }.tracks.size)
    }

    @Test
    fun sortModesAndDirection() {
        val albums = groupAlbums(
            listOf(
                t("One", album = "Bravo", artist = "Zed"),
                t("Two", album = "Alpha", artist = "Ann"),
            ),
        )
        assertEquals(listOf("Alpha", "Bravo"), sortAlbums(albums, LibrarySort.NAME, ascending = true).map { it.album })
        assertEquals(listOf("Bravo", "Alpha"), sortAlbums(albums, LibrarySort.NAME, ascending = false).map { it.album })
        assertEquals(listOf("Two", "One"), sortTracks(listOf(t("One"), t("Two")), LibrarySort.NAME, ascending = false).map { it.title })

        val artists = groupArtists(
            listOf(t("A", artist = "Duo1"), t("B", artist = "Duo1"), t("C", artist = "Solo")),
        )
        assertEquals(
            listOf("Duo1", "Solo"),
            sortArtists(artists, LibrarySort.TRACK_COUNT, ascending = false).map { it.artist },
        )
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.data.LibraryGroupingTest"`
Expected: FAIL to compile (`unresolved reference: groupAlbums`).

- [ ] **Step 3: Implement**

Create `android/app/src/main/kotlin/io/github/auxen/data/LibraryGrouping.kt`:

```kotlin
package io.github.auxen.data

import io.github.auxen.model.Track

/**
 * In-memory album/artist grouping and sorting of the local library —
 * the Android analog of the desktop `Database.get_albums`/`get_artists`
 * queries, computed over the MediaStore-backed track list.
 */

data class AlbumGroup(
    val album: String,
    val albumArtist: String,
    val artUrl: String?,
    val year: Int?,
    val tracks: List<Track>,
)

data class ArtistGroup(
    val artist: String,
    val artUrl: String?,
    val tracks: List<Track>,
)

enum class LibrarySort { RECENTLY_ADDED, NAME, ARTIST, TRACK_COUNT }

private const val UNKNOWN_ALBUM = "Unknown Album"
private const val UNKNOWN_ARTIST = "Unknown Artist"

fun groupAlbums(tracks: List<Track>): List<AlbumGroup> =
    tracks
        .groupBy { track ->
            val album = track.album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM
            val artist = track.albumArtist?.takeIf { it.isNotBlank() }
                ?: track.artist.takeIf { it.isNotBlank() }
                ?: UNKNOWN_ARTIST
            album to artist
        }
        .map { (key, groupTracks) ->
            val sorted = groupTracks.sortedWith(
                compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }),
            )
            AlbumGroup(
                album = key.first,
                albumArtist = key.second,
                artUrl = sorted.firstNotNullOfOrNull { it.albumArtUrl },
                year = sorted.firstNotNullOfOrNull { it.year },
                tracks = sorted,
            )
        }

fun groupArtists(tracks: List<Track>): List<ArtistGroup> =
    tracks
        .groupBy { it.artist.takeIf { name -> name.isNotBlank() } ?: UNKNOWN_ARTIST }
        .map { (artist, groupTracks) ->
            ArtistGroup(
                artist = artist,
                artUrl = groupTracks.firstNotNullOfOrNull { it.albumArtUrl },
                tracks = groupTracks,
            )
        }

fun sortAlbums(albums: List<AlbumGroup>, sort: LibrarySort, ascending: Boolean): List<AlbumGroup> {
    val sorted = when (sort) {
        LibrarySort.NAME -> albums.sortedBy { it.album.lowercase() }
        LibrarySort.ARTIST -> albums.sortedBy { it.albumArtist.lowercase() }
        // MediaStore order is the recency proxy: keep input order.
        LibrarySort.RECENTLY_ADDED, LibrarySort.TRACK_COUNT -> albums
    }
    return if (ascending) sorted else sorted.reversed()
}

fun sortArtists(artists: List<ArtistGroup>, sort: LibrarySort, ascending: Boolean): List<ArtistGroup> {
    val sorted = when (sort) {
        LibrarySort.NAME, LibrarySort.ARTIST -> artists.sortedBy { it.artist.lowercase() }
        LibrarySort.TRACK_COUNT -> artists.sortedBy { it.tracks.size }
        LibrarySort.RECENTLY_ADDED -> artists
    }
    return if (ascending) sorted else sorted.reversed()
}

fun sortTracks(tracks: List<Track>, sort: LibrarySort, ascending: Boolean): List<Track> {
    val sorted = when (sort) {
        LibrarySort.NAME -> tracks.sortedBy { it.title.lowercase() }
        LibrarySort.ARTIST -> tracks.sortedBy { it.artist.lowercase() }
        LibrarySort.RECENTLY_ADDED, LibrarySort.TRACK_COUNT -> tracks
    }
    return if (ascending) sorted else sorted.reversed()
}
```

- [ ] **Step 4: Run to verify pass**

Same command as Step 2. Expected: PASS (5 tests). Then full suite: **65 tests**, 0 failures.

- [ ] **Step 5: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/data/LibraryGrouping.kt \
        android/app/src/test/kotlin/io/github/auxen/data/LibraryGroupingTest.kt
git commit -m "feat(android): library album/artist grouping and sort"
```

---

### Task 3: Library screen — tabs, sort, grid

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt` (new home for the screen)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/Screens.kt` (delete old `LibraryScreen`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (library UI state)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (route passes nav callbacks)

**Interfaces:**
- Consumes: `groupAlbums`/`groupArtists`/`sortAlbums`/`sortArtists`/`sortTracks`/`LibrarySort` (Task 2); `AlbumCard`/`AuxenTrackRow`/`TrackActionSheet` (M3a); `SettingsDao` via repository settings helpers (below).
- Produces (Tasks 6/7 reuse):
  - `LibraryRepository`: `suspend fun getSetting(key: String): String?`, `suspend fun setSetting(key: String, value: String)` (thin passthroughs to `SettingsDao`)
  - `PlayerViewModel`: `libraryTab: StateFlow<Int>` (0=Albums,1=Artists,2=Tracks), `librarySort: StateFlow<LibrarySort>`, `librarySortAscending: StateFlow<Boolean>`, `fun setLibraryTab(index: Int)`, `fun setLibrarySort(sort: LibrarySort)`, `fun toggleLibrarySortDirection()` — each persisting via the settings keys `library_tab`, `library_sort_<tab>`, `library_dir_<tab>` and restoring on init
  - `LibraryScreen(viewModel, onOpenAlbum: (AlbumGroup) -> Unit, onOpenArtist: (String) -> Unit, modifier)` composable
  - `PlayerViewModel.playAll(tracks: List<Track>, shuffled: Boolean = false)` — sets the whole list as the queue and plays (used by every detail screen)

- [ ] **Step 1: Repository setting passthroughs + failing test**

Append to `LibraryRepositoryTest.kt`:

```kotlin
    @Test
    fun settingsPassthroughRoundTrips() = runBlocking {
        assertEquals(null, repo.getSetting("library_tab"))
        repo.setSetting("library_tab", "2")
        assertEquals("2", repo.getSetting("library_tab"))
    }
```

Run the class — compile FAIL. Add to `LibraryRepository.kt`:

```kotlin
    suspend fun getSetting(key: String): String? = db.settingsDao().get(key)

    suspend fun setSetting(key: String, value: String) =
        db.settingsDao().put(SettingEntity(key, value))
```

Re-run — PASS.

- [ ] **Step 2: ViewModel library state**

Add to `PlayerViewModel.kt` (imports: `io.github.auxen.data.LibrarySort`):

```kotlin
    val libraryTab = MutableStateFlow(0)
    val librarySort = MutableStateFlow(LibrarySort.RECENTLY_ADDED)
    val librarySortAscending = MutableStateFlow(true)

    private fun libraryTabName(index: Int) = when (index) {
        0 -> "albums"
        1 -> "artists"
        else -> "tracks"
    }

    private fun restoreLibraryState() {
        viewModelScope.launch {
            runCatching {
                libraryTab.value = Graph.library.getSetting("library_tab")?.toIntOrNull()?.coerceIn(0, 2) ?: 0
                restoreLibrarySortFor(libraryTab.value)
            }
        }
    }

    private suspend fun restoreLibrarySortFor(tab: Int) {
        val name = libraryTabName(tab)
        librarySort.value = Graph.library.getSetting("library_sort_$name")
            ?.let { stored -> LibrarySort.entries.firstOrNull { it.name == stored } }
            ?: LibrarySort.RECENTLY_ADDED
        librarySortAscending.value = Graph.library.getSetting("library_dir_$name") != "desc"
    }

    fun setLibraryTab(index: Int) {
        libraryTab.value = index
        viewModelScope.launch {
            runCatching {
                Graph.library.setSetting("library_tab", index.toString())
                restoreLibrarySortFor(index)
            }
        }
    }

    fun setLibrarySort(sort: LibrarySort) {
        librarySort.value = sort
        viewModelScope.launch {
            runCatching { Graph.library.setSetting("library_sort_${libraryTabName(libraryTab.value)}", sort.name) }
        }
    }

    fun toggleLibrarySortDirection() {
        librarySortAscending.value = !librarySortAscending.value
        viewModelScope.launch {
            runCatching {
                Graph.library.setSetting(
                    "library_dir_${libraryTabName(libraryTab.value)}",
                    if (librarySortAscending.value) "asc" else "desc",
                )
            }
        }
    }

    /** Replace the queue with [tracks] and play — desktop Play All / Shuffle. */
    fun playAll(tracks: List<Track>, shuffled: Boolean = false) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val c = controller ?: return@launch
            val ordered = if (shuffled) tracks.shuffled() else tracks
            runCatching { ordered.forEach { Graph.library.upsert(it) } }
            val items = ordered.mapNotNull { runCatching { Graph.mediaItemFor(it) }.getOrNull() }
            if (items.isEmpty()) return@launch
            c.setMediaItems(items)
            c.prepare()
            c.play()
        }
    }
```

Call `restoreLibraryState()` at the end of `init`.

- [ ] **Step 3: Write LibraryScreen.kt**

Create `android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt` (this REPLACES the old one in Screens.kt — delete that one and its now-unused imports in the same commit):

```kotlin
package io.github.auxen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.data.AlbumGroup
import io.github.auxen.data.LibrarySort
import io.github.auxen.data.groupAlbums
import io.github.auxen.data.groupArtists
import io.github.auxen.data.sortAlbums
import io.github.auxen.data.sortArtists
import io.github.auxen.data.sortTracks
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.TrackActionSheet

private val TAB_LABELS = listOf("Albums", "Artists", "Tracks")

private fun sortOptionsFor(tab: Int): List<LibrarySort> = when (tab) {
    0 -> listOf(LibrarySort.RECENTLY_ADDED, LibrarySort.NAME, LibrarySort.ARTIST)
    1 -> listOf(LibrarySort.NAME, LibrarySort.TRACK_COUNT, LibrarySort.RECENTLY_ADDED)
    else -> listOf(LibrarySort.RECENTLY_ADDED, LibrarySort.NAME, LibrarySort.ARTIST)
}

private fun sortLabel(sort: LibrarySort): String = when (sort) {
    LibrarySort.RECENTLY_ADDED -> "Recently Added"
    LibrarySort.NAME -> "Name"
    LibrarySort.ARTIST -> "Artist"
    LibrarySort.TRACK_COUNT -> "Track Count"
}

/**
 * Library — desktop LibraryView: Albums/Artists/Tracks tabs, per-tab sort +
 * direction (persisted), album grid, artist list, track list.
 */
@UnstableApi
@Composable
fun LibraryScreen(
    viewModel: PlayerViewModel,
    onOpenAlbum: (AlbumGroup) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracks by viewModel.localTracks.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val tab by viewModel.libraryTab.collectAsState()
    val sort by viewModel.librarySort.collectAsState()
    val ascending by viewModel.librarySortAscending.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(Unit) { viewModel.loadLibrary() }

    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TAB_LABELS.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = tab == index,
                    onClick = { viewModel.setLibraryTab(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TAB_LABELS.size),
                ) { Text(label) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${tracks.size} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { sortMenuOpen = true }) { Text(sortLabel(sort)) }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                sortOptionsFor(tab).forEach { option ->
                    DropdownMenuItem(
                        text = { Text(sortLabel(option)) },
                        onClick = {
                            viewModel.setLibrarySort(option)
                            sortMenuOpen = false
                        },
                    )
                }
            }
            IconButton(onClick = { viewModel.toggleLibrarySortDirection() }) {
                Icon(
                    if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    contentDescription = if (ascending) "Ascending" else "Descending",
                )
            }
        }

        when (tab) {
            0 -> {
                val albums = sortAlbums(groupAlbums(tracks), sort, ascending)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                ) {
                    items(albums, key = { "${it.album}|${it.albumArtist}" }) { album ->
                        AlbumCard(
                            title = album.album,
                            artist = album.albumArtist,
                            artUrl = album.artUrl,
                            source = null,
                            onClick = { onOpenAlbum(album) },
                            onPlay = { viewModel.playAll(album.tracks) },
                        )
                    }
                }
            }
            1 -> {
                val artists = sortArtists(groupArtists(tracks), sort, ascending)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(artists, key = { it.artist }) { artist ->
                        ArtistRow(
                            name = artist.artist,
                            trackCount = artist.tracks.size,
                            onClick = { onOpenArtist(artist.artist) },
                        )
                    }
                }
            }
            else -> {
                val sorted = sortTracks(tracks, sort, ascending)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sorted, key = { "${it.source}:${it.sourceId}" }) { track ->
                        AuxenTrackRow(
                            track = track,
                            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                            onPlay = { viewModel.play(track) },
                            onToggleFavorite = { viewModel.toggleFavorite(track) },
                            onLongPress = { sheetTrack = track },
                            trailing = {
                                IconButton(onClick = { viewModel.enqueue(track) }) {
                                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to queue")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    sheetTrack?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
            playlists = playlists,
            onDismiss = { sheetTrack = null },
            onPlay = { viewModel.play(track) },
            onPlayNext = { viewModel.playNext(track) },
            onEnqueue = { viewModel.enqueue(track) },
            onToggleFavorite = { viewModel.toggleFavorite(track) },
            onAddToPlaylist = { viewModel.addToPlaylist(track, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAdd(track, it) },
        )
    }
}

@Composable
private fun ArtistRow(name: String, trackCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "$trackCount tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

(Additional import for the file: `androidx.compose.foundation.clickable`.)

- [ ] **Step 4: Route wiring in MainActivity**

Change the library route to pass navigation callbacks (Task 7 adds the destination composables; for THIS task navigate to routes that don't exist yet would crash — so in this task wire the callbacks to the routes and add TEMPORARY no-op destinations):

```kotlin
            composable("library") {
                LibraryScreen(
                    viewModel,
                    onOpenAlbum = { album ->
                        navController.navigate("album/${Uri.encode(album.album)}/${Uri.encode(album.albumArtist)}")
                    },
                    onOpenArtist = { artist -> navController.navigate("artist/${Uri.encode(artist)}") },
                )
            }
            composable("album/{album}/{artist}") { backStack ->
                DetailPlaceholder(
                    title = Uri.decode(backStack.arguments?.getString("album") ?: ""),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("artist/{artist}") { backStack ->
                DetailPlaceholder(
                    title = Uri.decode(backStack.arguments?.getString("artist") ?: ""),
                    onBack = { navController.popBackStack() },
                )
            }
```

with (bottom of MainActivity.kt; Task 7 replaces it):

```kotlin
/** Replaced by real detail screens in Task 7. */
@Composable
private fun DetailPlaceholder(title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(title, style = MaterialTheme.typography.displaySmall)
    }
}
```

Imports: `android.net.Uri`, `androidx.compose.material3.TextButton`.

- [ ] **Step 5: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, **66 tests** (65 + settings test), 0 failures.

- [ ] **Step 6: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/ui/ \
        android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt \
        android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt
git commit -m "feat(android): Library screen with tabs, sort, and album grid"
```

---

### Task 4: Search screen v2 — debounce, type filters, history

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/SearchScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/Screens.kt` (delete old `SearchScreen`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (debounced search + history state)

**Interfaces:**
- Consumes: `LibraryRepository.searchHistory/addSearchHistory/deleteSearchHistoryItem/clearSearchHistory` (Task 1); M3a components.
- Produces:
  - `PlayerViewModel`: `searchQuery: StateFlow<String>`, `fun onSearchQueryChange(query: String)` (300ms debounce then runs the existing merged search; blank clears results), `fun commitSearch()` (records history), `searchHistoryItems: StateFlow<List<String>>`, `fun deleteSearchHistory(query: String)`, `fun clearSearchHistory()`
  - `SearchScreen(viewModel, modifier)` — self-contained (no new nav args)

- [ ] **Step 1: ViewModel search state**

Add to `PlayerViewModel.kt` (imports: `kotlinx.coroutines.Job`, `kotlinx.coroutines.delay` already present from polling):

```kotlin
    val searchQuery = MutableStateFlow("")
    val searchHistoryItems: StateFlow<List<String>> = Graph.library.searchHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var searchDebounceJob: Job? = null

    /** Debounced live search — desktop SearchView's 300ms debounce. */
    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
        searchDebounceJob?.cancel()
        if (query.isBlank()) {
            searchResults.value = emptyList()
            return
        }
        searchDebounceJob = viewModelScope.launch {
            delay(300)
            search(query)
        }
    }

    /** Record the current query in history (called on keyboard submit). */
    fun commitSearch() {
        val query = searchQuery.value
        if (query.isBlank()) return
        viewModelScope.launch { runCatching { Graph.library.addSearchHistory(query) } }
    }

    fun deleteSearchHistory(query: String) {
        viewModelScope.launch { runCatching { Graph.library.deleteSearchHistoryItem(query) } }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { runCatching { Graph.library.clearSearchHistory() } }
    }
```

- [ ] **Step 2: Write SearchScreen.kt**

Create `android/app/src/main/kotlin/io/github/auxen/ui/SearchScreen.kt`:

```kotlin
package io.github.auxen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.SectionHeader
import io.github.auxen.ui.components.TrackActionSheet

private val TYPE_FILTERS = listOf("All", "Local", "Tidal")

/**
 * Search — desktop SearchView: debounced input, type filter chips,
 * DB-backed search history shown before typing.
 */
@UnstableApi
@Composable
fun SearchScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val history by viewModel.searchHistoryItems.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var typeFilter by remember { mutableStateOf("All") }
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.onSearchQueryChange(it) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            label = { Text("Search local + Tidal") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    viewModel.commitSearch()
                    keyboard?.hide()
                },
            ),
        )

        if (query.isBlank()) {
            if (history.isNotEmpty()) {
                SectionHeader(
                    "Recent Searches",
                    actionLabel = "Clear all",
                    onAction = { viewModel.clearSearchHistory() },
                )
                LazyColumn {
                    items(history, key = { it }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.onSearchQueryChange(item)
                                    viewModel.commitSearch()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                item,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                            IconButton(onClick = { viewModel.deleteSearchHistory(item) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove $item")
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TYPE_FILTERS.forEach { filter ->
                    FilterChip(
                        selected = typeFilter == filter,
                        onClick = { typeFilter = filter },
                        label = { Text(filter) },
                    )
                }
            }
            if (viewModel.searchInFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                )
            }
            val filtered = when (typeFilter) {
                "Local" -> results.filter { it.source == Source.LOCAL }
                "Tidal" -> results.filter { it.source == Source.TIDAL }
                else -> results
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { "${it.source}:${it.sourceId}" }) { track ->
                    AuxenTrackRow(
                        track = track,
                        isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                        onPlay = { viewModel.play(track) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        onLongPress = { sheetTrack = track },
                        trailing = {
                            IconButton(onClick = { viewModel.enqueue(track) }) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to queue")
                            }
                        },
                    )
                }
            }
        }
    }

    sheetTrack?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
            playlists = playlists,
            onDismiss = { sheetTrack = null },
            onPlay = { viewModel.play(track) },
            onPlayNext = { viewModel.playNext(track) },
            onEnqueue = { viewModel.enqueue(track) },
            onToggleFavorite = { viewModel.toggleFavorite(track) },
            onAddToPlaylist = { viewModel.addToPlaylist(track, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAdd(track, it) },
        )
    }
}
```

Delete the old `SearchScreen` from `Screens.kt` (and any imports it alone used).

- [ ] **Step 3: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 66 tests, 0 failures.

- [ ] **Step 4: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/ui/
git commit -m "feat(android): Search screen with debounce, type filters, history"
```

---

### Task 5: Home v2 — filter chips, stat cards, Recently Added carousel

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/local/LocalProvider.kt` (recentlyAdded query)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (home state)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt` (full rewrite of internals, same signature)

**Interfaces:**
- Consumes: M3a `AlbumCard`/`SectionHeader`/`AuxenTrackRow`; Task 3's `playAll` + settings passthroughs; `groupAlbums` (Task 2).
- Produces:
  - `LocalProvider.recentlyAdded(limit: Int = 30): List<Track>` (suspend; MediaStore query ordered by `DATE_ADDED DESC`)
  - `PlayerViewModel`: `homeFilter: StateFlow<String>` ("all"/"tidal"/"local", persisted key `home_filter`), `fun setHomeFilter(value: String)`, `recentlyAdded: StateFlow<List<Track>>`, `fun refreshHome()` (loads recentlyAdded + recentlyPlayed)

- [ ] **Step 1: LocalProvider.recentlyAdded**

Add to `LocalProvider.kt` (the private `queryTracks` currently hardcodes its ORDER BY — add a `sortOrder` parameter with the existing value as default, then):

```kotlin
    /** Most recently added local tracks — desktop "Recently Added" section. */
    suspend fun recentlyAdded(limit: Int = 30): List<Track> =
        queryTracks(
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            selectionArgs = null,
            limit = limit,
            sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )
```

and change the private helper signature to:

```kotlin
    private suspend fun queryTracks(
        selection: String,
        selectionArgs: Array<String>?,
        limit: Int,
        sortOrder: String = "${MediaStore.Audio.Media.ARTIST}, ${MediaStore.Audio.Media.ALBUM}, ${MediaStore.Audio.Media.TRACK}",
    ): List<Track> = withContext(Dispatchers.IO) {
```

using `sortOrder` in the `contentResolver.query(...)` call.

- [ ] **Step 2: ViewModel home state**

Add to `PlayerViewModel.kt`:

```kotlin
    val homeFilter = MutableStateFlow("all")
    val recentlyAdded = MutableStateFlow<List<Track>>(emptyList())

    fun setHomeFilter(value: String) {
        homeFilter.value = value
        viewModelScope.launch { runCatching { Graph.library.setSetting("home_filter", value) } }
    }

    /** Refresh Home data: recently added (MediaStore) + recently played (DB). */
    fun refreshHome() {
        refreshRecentlyPlayed()
        viewModelScope.launch {
            runCatching { Graph.local.recentlyAdded() }.onSuccess { recentlyAdded.value = it }
        }
    }
```

and in the restore block that already reads settings on init (Task 3's `restoreLibraryState`), also restore `homeFilter`:

```kotlin
                homeFilter.value = Graph.library.getSetting("home_filter") ?: "all"
```

- [ ] **Step 3: Rewrite HomeScreen internals**

Replace `HomeScreen.kt`'s content (keep `greetingForHour` and the signature) with:

```kotlin
/**
 * Home — desktop HomePage: greeting, All/Tidal/Local filter chips (persisted),
 * stat cards, Recently Added carousel, Recently Played list.
 */
@UnstableApi
@Composable
fun HomeScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val localTracks by viewModel.localTracks.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val filter by viewModel.homeFilter.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.loadLibrary()
        viewModel.refreshHome()
    }

    fun matches(track: Track): Boolean = when (filter) {
        "tidal" -> track.source == Source.TIDAL
        "local" -> track.source == Source.LOCAL
        else -> true
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("all" to "All", "tidal" to "Tidal", "local" to "Local").forEach { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { viewModel.setHomeFilter(value) },
                        label = { Text(label) },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard("Local Tracks", localTracks.size.toString(), Modifier.weight(1f))
                StatCard("Favorites", favoriteKeys.size.toString(), Modifier.weight(1f))
                StatCard("Recently Played", recentlyPlayed.size.toString(), Modifier.weight(1f))
            }
        }
        val addedAlbums = groupAlbums(recentlyAdded.filter(::matches))
        if (addedAlbums.isNotEmpty()) {
            item { SectionHeader("Recently Added") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(addedAlbums, key = { "${it.album}|${it.albumArtist}" }) { album ->
                        AlbumCard(
                            title = album.album,
                            artist = album.albumArtist,
                            artUrl = album.artUrl,
                            source = Source.LOCAL,
                            onClick = { viewModel.playAll(album.tracks) },
                            onPlay = { viewModel.playAll(album.tracks) },
                        )
                    }
                }
            }
        }
        val played = recentlyPlayed.filter(::matches)
        if (played.isNotEmpty()) {
            item { SectionHeader("Recently Played") }
            items(played, key = { "${it.source}:${it.sourceId}" }) { track ->
                AuxenTrackRow(
                    track = track,
                    isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                    onPlay = { viewModel.play(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                )
            }
        } else if (addedAlbums.isEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Play something from your Library or Search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Amber-accented stat card — desktop stat-card row. */
@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = AuxenColors.AmberPrimary)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

New imports for the file: `androidx.compose.foundation.background`, `androidx.compose.foundation.layout.PaddingValues`, `androidx.compose.foundation.layout.Row`, `androidx.compose.foundation.layout.fillMaxWidth`, `androidx.compose.foundation.lazy.LazyRow`, `androidx.compose.foundation.shape.RoundedCornerShape`, `androidx.compose.material3.FilterChip`, `androidx.compose.ui.draw.clip`, `io.github.auxen.data.groupAlbums`, `io.github.auxen.model.Source`, `io.github.auxen.model.Track`, `io.github.auxen.ui.components.AlbumCard`, `io.github.auxen.ui.theme.AuxenColors`.

- [ ] **Step 4: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 66 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/provider/local/LocalProvider.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/
git commit -m "feat(android): Home with filter chips, stat cards, recently added carousel"
```

---

### Task 6: Collection screen — tabs, source filter, playlists

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/Screens.kt` (delete old `FavoritesScreen`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (collection route + playlist route placeholder)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (collection filter persistence)

**Interfaces:**
- Consumes: `favorites`/`favoriteKeys`/`playlists` StateFlows; `groupAlbums`/`groupArtists` (Task 2); M3a components; Task 3 `playAll`.
- Produces:
  - `PlayerViewModel`: `collectionFilter: StateFlow<String>` ("all"/"tidal"/"local", persisted key `collection_filter`), `fun setCollectionFilter(value: String)`
  - `CollectionScreen(viewModel, onOpenPlaylist: (Long) -> Unit, onOpenAlbum: (AlbumGroup) -> Unit, onOpenArtist: (String) -> Unit, modifier)` — 4 tabs: Tracks, Albums, Artists, Playlists (albums/artists grouped FROM the favorites list)

- [ ] **Step 1: ViewModel collection filter**

Add to `PlayerViewModel.kt` (and restore `collectionFilter` from settings in the same init restore block):

```kotlin
    val collectionFilter = MutableStateFlow("all")

    fun setCollectionFilter(value: String) {
        collectionFilter.value = value
        viewModelScope.launch { runCatching { Graph.library.setSetting("collection_filter", value) } }
    }
```

restore line: `collectionFilter.value = Graph.library.getSetting("collection_filter") ?: "all"`.

- [ ] **Step 2: Write CollectionScreen.kt**

```kotlin
package io.github.auxen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.data.AlbumGroup
import io.github.auxen.data.groupAlbums
import io.github.auxen.data.groupArtists
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.TrackActionSheet
import io.github.auxen.ui.theme.AuxenColors

private val COLLECTION_TABS = listOf("Tracks", "Albums", "Artists", "Playlists")

/**
 * Collection — desktop CollectionView: favorited content in Tracks/Albums/
 * Artists tabs plus Playlists, with the All/Tidal/Local source filter.
 */
@UnstableApi
@Composable
fun CollectionScreen(
    viewModel: PlayerViewModel,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAlbum: (AlbumGroup) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val favorites by viewModel.favorites.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val filter by viewModel.collectionFilter.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var sheetTrack by remember { mutableStateOf<Track?>(null) }

    fun matches(track: Track): Boolean = when (filter) {
        "tidal" -> track.source == Source.TIDAL
        "local" -> track.source == Source.LOCAL
        else -> true
    }
    val filtered = favorites.filter(::matches)

    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            COLLECTION_TABS.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = tab == index,
                    onClick = { tab = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = COLLECTION_TABS.size),
                ) { Text(label) }
            }
        }
        if (tab != 3) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("all" to "All", "tidal" to "Tidal", "local" to "Local").forEach { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { viewModel.setCollectionFilter(value) },
                        label = { Text(label) },
                    )
                }
            }
        }

        when (tab) {
            0 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (filtered.isEmpty()) {
                    item { EmptyCollectionHint() }
                }
                items(filtered, key = { "${it.source}:${it.sourceId}" }) { track ->
                    AuxenTrackRow(
                        track = track,
                        isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                        onPlay = { viewModel.play(track) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        onLongPress = { sheetTrack = track },
                        trailing = {
                            IconButton(onClick = { viewModel.enqueue(track) }) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to queue")
                            }
                        },
                    )
                }
            }
            1 -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                items(groupAlbums(filtered), key = { "${it.album}|${it.albumArtist}" }) { album ->
                    AlbumCard(
                        title = album.album,
                        artist = album.albumArtist,
                        artUrl = album.artUrl,
                        source = album.tracks.firstOrNull()?.source,
                        onClick = { onOpenAlbum(album) },
                        onPlay = { viewModel.playAll(album.tracks) },
                    )
                }
            }
            2 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(groupArtists(filtered), key = { it.artist }) { artist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenArtist(artist.artist) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(artist.artist, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${artist.tracks.size} favorited",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (playlists.isEmpty()) {
                    item { EmptyCollectionHint(message = "No playlists yet — long-press any track to add one.") }
                }
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPlaylist(playlist.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(14.dp).background(
                                runCatching { Color(android.graphics.Color.parseColor(playlist.color ?: "#d4a039")) }
                                    .getOrDefault(AuxenColors.AmberPrimary),
                                CircleShape,
                            ),
                        )
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }
    }

    sheetTrack?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
            playlists = playlists,
            onDismiss = { sheetTrack = null },
            onPlay = { viewModel.play(track) },
            onPlayNext = { viewModel.playNext(track) },
            onEnqueue = { viewModel.enqueue(track) },
            onToggleFavorite = { viewModel.toggleFavorite(track) },
            onAddToPlaylist = { viewModel.addToPlaylist(track, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAdd(track, it) },
        )
    }
}

@Composable
private fun EmptyCollectionHint(message: String = "Tap the heart on any track to add it here.") {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

- [ ] **Step 3: Route wiring**

In `MainActivity.kt`: change the collection route to

```kotlin
            composable("collection") {
                CollectionScreen(
                    viewModel,
                    onOpenPlaylist = { id -> navController.navigate("playlist/$id") },
                    onOpenAlbum = { album ->
                        navController.navigate("album/${Uri.encode(album.album)}/${Uri.encode(album.albumArtist)}")
                    },
                    onOpenArtist = { artist -> navController.navigate("artist/${Uri.encode(artist)}") },
                )
            }
            composable("playlist/{playlistId}") { backStack ->
                DetailPlaceholder(
                    title = "Playlist ${backStack.arguments?.getString("playlistId")}",
                    onBack = { navController.popBackStack() },
                )
            }
```

Delete the old `FavoritesScreen` from `Screens.kt` (with its now-unused imports). If `Screens.kt` is now nearly empty (only `AccountScreen` remains), keep it — do not relocate `AccountScreen`.

- [ ] **Step 4: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 66 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/ui/
git commit -m "feat(android): Collection with tabs, source filter, and playlists"
```

---

### Task 7: Album + Artist detail screens

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/AlbumDetailScreen.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (replace `DetailPlaceholder` for album/artist routes)

**Interfaces:**
- Consumes: `localTracks`/`favorites` (albums/artists are recomputed from them by name — a detail screen re-derives its group so it works from any entry point), `groupAlbums`/`groupArtists`, `playAll`, M3a components.
- Produces:
  - `AlbumDetailScreen(viewModel, album: String, artist: String, onBack: () -> Unit, onOpenArtist: (String) -> Unit)`
  - `ArtistDetailScreen(viewModel, artist: String, onBack: () -> Unit, onOpenAlbum: (AlbumGroup) -> Unit)`
  - Both derive their data as: `(localTracks + favorites).distinctBy { "${it.source}:${it.sourceId}" }` filtered to the album/artist, so Tidal favorites appear too.

- [ ] **Step 1: Write AlbumDetailScreen.kt**

```kotlin
package io.github.auxen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import io.github.auxen.data.groupAlbums
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.SourceBadge
import io.github.auxen.ui.components.TrackActionSheet
import io.github.auxen.ui.theme.AuxenColors

/**
 * Album detail — desktop AlbumDetailView: header (art, title, artist link,
 * meta, Play All + Shuffle) and the ordered track list.
 */
@UnstableApi
@Composable
fun AlbumDetailScreen(
    viewModel: PlayerViewModel,
    album: String,
    artist: String,
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val localTracks by viewModel.localTracks.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var sheetTrack by remember { mutableStateOf<Track?>(null) }

    val pool = (localTracks + favorites).distinctBy { "${it.source}:${it.sourceId}" }
    val group = groupAlbums(pool).firstOrNull { it.album == album && it.albumArtist == artist }
    val tracks = group?.tracks ?: emptyList()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                AsyncImage(
                    model = group?.artUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(140.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(album, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AuxenColors.AmberPrimary,
                        modifier = Modifier.clickable { onOpenArtist(artist) },
                    )
                    val meta = listOfNotNull(
                        group?.year?.toString(),
                        "${tracks.size} tracks",
                    ).joinToString(" • ")
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    tracks.firstOrNull()?.let { SourceBadge(it.source, Modifier.padding(top = 6.dp)) }
                }
            }
        }
        item {
            Row(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = { viewModel.playAll(tracks) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuxenColors.AmberPrimary,
                        contentColor = AuxenColors.BgDeep,
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("Play All")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { viewModel.playAll(tracks, shuffled = true) }) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Text("Shuffle")
                }
            }
        }
        items(tracks, key = { "${it.source}:${it.sourceId}" }) { track ->
            AuxenTrackRow(
                track = track,
                isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                onPlay = { viewModel.play(track) },
                onToggleFavorite = { viewModel.toggleFavorite(track) },
                onLongPress = { sheetTrack = track },
            )
        }
    }

    sheetTrack?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
            playlists = playlists,
            onDismiss = { sheetTrack = null },
            onPlay = { viewModel.play(track) },
            onPlayNext = { viewModel.playNext(track) },
            onEnqueue = { viewModel.enqueue(track) },
            onToggleFavorite = { viewModel.toggleFavorite(track) },
            onAddToPlaylist = { viewModel.addToPlaylist(track, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAdd(track, it) },
        )
    }
}
```

- [ ] **Step 2: Write ArtistDetailScreen.kt**

```kotlin
package io.github.auxen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import io.github.auxen.data.AlbumGroup
import io.github.auxen.data.groupAlbums
import io.github.auxen.model.Track
import io.github.auxen.ui.components.AlbumCard
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.components.SectionHeader
import io.github.auxen.ui.components.TrackActionSheet

/**
 * Artist detail — desktop ArtistDetailView: circular art header, Play All +
 * Shuffle, horizontally-scrolling Albums row, track list.
 */
@UnstableApi
@Composable
fun ArtistDetailScreen(
    viewModel: PlayerViewModel,
    artist: String,
    onBack: () -> Unit,
    onOpenAlbum: (AlbumGroup) -> Unit,
) {
    val localTracks by viewModel.localTracks.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var sheetTrack by remember { mutableStateOf<Track?>(null) }

    val pool = (localTracks + favorites).distinctBy { "${it.source}:${it.sourceId}" }
    val tracks = pool.filter { it.artist == artist || it.albumArtist == artist }
    val albums = groupAlbums(tracks)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = tracks.firstNotNullOfOrNull { it.albumArtUrl },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(artist, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${albums.size} albums • ${tracks.size} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = { viewModel.playAll(tracks) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = io.github.auxen.ui.theme.AuxenColors.AmberPrimary,
                        contentColor = io.github.auxen.ui.theme.AuxenColors.BgDeep,
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("Play All")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { viewModel.playAll(tracks, shuffled = true) }) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Text("Shuffle")
                }
            }
        }
        if (albums.isNotEmpty()) {
            item { SectionHeader("Albums") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(albums, key = { "${it.album}|${it.albumArtist}" }) { album ->
                        AlbumCard(
                            title = album.album,
                            artist = null,
                            artUrl = album.artUrl,
                            source = album.tracks.firstOrNull()?.source,
                            onClick = { onOpenAlbum(album) },
                            onPlay = { viewModel.playAll(album.tracks) },
                        )
                    }
                }
            }
        }
        item { SectionHeader("Tracks") }
        items(tracks, key = { "${it.source}:${it.sourceId}" }) { track ->
            AuxenTrackRow(
                track = track,
                isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                onPlay = { viewModel.play(track) },
                onToggleFavorite = { viewModel.toggleFavorite(track) },
                onLongPress = { sheetTrack = track },
            )
        }
    }

    sheetTrack?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
            playlists = playlists,
            onDismiss = { sheetTrack = null },
            onPlay = { viewModel.play(track) },
            onPlayNext = { viewModel.playNext(track) },
            onEnqueue = { viewModel.enqueue(track) },
            onToggleFavorite = { viewModel.toggleFavorite(track) },
            onAddToPlaylist = { viewModel.addToPlaylist(track, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAdd(track, it) },
        )
    }
}
```

(Convert the two inline `io.github.auxen.ui.theme.AuxenColors` qualified names to a proper import.)

- [ ] **Step 3: Replace the album/artist placeholders in MainActivity**

```kotlin
            composable("album/{album}/{artist}") { backStack ->
                AlbumDetailScreen(
                    viewModel,
                    album = Uri.decode(backStack.arguments?.getString("album") ?: ""),
                    artist = Uri.decode(backStack.arguments?.getString("artist") ?: ""),
                    onBack = { navController.popBackStack() },
                    onOpenArtist = { artist -> navController.navigate("artist/${Uri.encode(artist)}") },
                )
            }
            composable("artist/{artist}") { backStack ->
                ArtistDetailScreen(
                    viewModel,
                    artist = Uri.decode(backStack.arguments?.getString("artist") ?: ""),
                    onBack = { navController.popBackStack() },
                    onOpenAlbum = { album ->
                        navController.navigate("album/${Uri.encode(album.album)}/${Uri.encode(album.albumArtist)}")
                    },
                )
            }
```

Keep `DetailPlaceholder` only if the playlist route still uses it (Task 8 replaces that one).

- [ ] **Step 4: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 66 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/ui/
git commit -m "feat(android): album and artist detail screens"
```

---

### Task 8: Playlist detail + management

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/Daos.kt` (reorder + rename/recolor methods)
- Modify: `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/PlaylistDetailScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (replace playlist placeholder; delete `DetailPlaceholder`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/db/DaoTest.kt` (extend), `android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt` (extend)

**Interfaces:**
- Consumes: existing `PlaylistDao` (`tracksIn`, `rename`, `deleteWithTracks`, `removeTrack`, `clearTracks`, `insertTrack`); M3a components; `PLAYLIST_COLORS` palette below.
- Produces:
  - `PlaylistDao`: `@Query("UPDATE playlists SET color = :color WHERE id = :id") suspend fun recolor(id: Long, color: String)`; `@Transaction suspend fun reorder(playlistId: Long, orderedTrackIds: List<Long>)` (clearTracks then insert each with position = list index)
  - `LibraryRepository`: `suspend fun playlistTracks(playlistId: Long): List<Track>`, `suspend fun renamePlaylist(id: Long, name: String)`, `suspend fun recolorPlaylist(id: Long, color: String)`, `suspend fun deletePlaylist(id: Long)`, `suspend fun removeFromPlaylist(playlistId: Long, track: Track)`, `suspend fun movePlaylistTrack(playlistId: Long, fromIndex: Int, toIndex: Int)`
  - `PlayerViewModel`: `playlistTracks: StateFlow<List<Track>>`, `fun loadPlaylist(id: Long)`, `fun renamePlaylist(id: Long, name: String)`, `fun recolorPlaylist(id: Long, color: String)`, `fun deletePlaylist(id: Long)`, `fun removeFromPlaylist(id: Long, track: Track)`, `fun movePlaylistTrack(id: Long, from: Int, to: Int)` (each reloads `playlistTracks` after mutating)
  - `PlaylistDetailScreen(viewModel, playlistId: Long, onBack: () -> Unit)`
  - `val PLAYLIST_COLORS = listOf("#d4a039", "#00c4cc", "#7cb87a", "#9b59b6", "#e74c3c", "#3498db", "#e67e22", "#1abc9c")` (in `PlaylistDetailScreen.kt`, desktop `PLAYLIST_COLORS`)

- [ ] **Step 1: Failing DAO test**

Append to `DaoTest.kt`:

```kotlin
    @Test
    fun playlistReorderAndRecolor() = runBlocking {
        val t1 = db.trackDao().upsert(track("1").toEntity(), nowMillis = 1)
        val t2 = db.trackDao().upsert(track("2").toEntity(), nowMillis = 1)
        val t3 = db.trackDao().upsert(track("3").toEntity(), nowMillis = 1)
        val pl = db.playlistDao().insert(PlaylistEntity(name = "Mix", color = "#d4a039"))
        db.playlistDao().appendTrack(pl, t1)
        db.playlistDao().appendTrack(pl, t2)
        db.playlistDao().appendTrack(pl, t3)

        db.playlistDao().reorder(pl, listOf(t3, t1, t2))
        assertEquals(listOf("Song 3", "Song 1", "Song 2"), db.playlistDao().tracksIn(pl).map { it.title })

        db.playlistDao().recolor(pl, "#3498db")
        assertEquals("#3498db", db.playlistDao().playlists().first().single().color)
    }
```

Run the class — compile FAIL (`unresolved reference: reorder`).

- [ ] **Step 2: DAO methods**

Add to `PlaylistDao` in `Daos.kt`:

```kotlin
    @Query("UPDATE playlists SET color = :color WHERE id = :id")
    suspend fun recolor(id: Long, color: String)

    /** Rewrite the playlist's ordering in one transaction. */
    @Transaction
    suspend fun reorder(playlistId: Long, orderedTrackIds: List<Long>) {
        clearTracks(playlistId)
        orderedTrackIds.forEachIndexed { index, trackId ->
            insertTrack(PlaylistTrackEntity(playlistId, trackId, index))
        }
    }
```

Run the DAO test — PASS.

- [ ] **Step 3: Repository + failing test**

Append to `LibraryRepositoryTest.kt`:

```kotlin
    @Test
    fun playlistManagementRoundTrip() = runBlocking {
        val id = repo.createPlaylist("Mix")
        repo.addTrackToPlaylist(tidalTrack, id)
        repo.addTrackToPlaylist(tidalTrack.copy(sourceId = "100", title = "Walk"), id)

        assertEquals(listOf("Everlong", "Walk"), repo.playlistTracks(id).map { it.title })

        repo.movePlaylistTrack(id, fromIndex = 1, toIndex = 0)
        assertEquals(listOf("Walk", "Everlong"), repo.playlistTracks(id).map { it.title })

        repo.renamePlaylist(id, "Road Trip")
        repo.recolorPlaylist(id, "#3498db")
        val entity = repo.playlists().first().single()
        assertEquals("Road Trip", entity.name)
        assertEquals("#3498db", entity.color)

        repo.removeFromPlaylist(id, tidalTrack.copy(sourceId = "100", title = "Walk"))
        assertEquals(listOf("Everlong"), repo.playlistTracks(id).map { it.title })

        repo.deletePlaylist(id)
        assertTrue(repo.playlists().first().isEmpty())
    }
```

Run — compile FAIL. Then add to `LibraryRepository.kt`:

```kotlin
    suspend fun playlistTracks(playlistId: Long): List<Track> =
        db.playlistDao().tracksIn(playlistId).map { it.toTrack() }

    suspend fun renamePlaylist(id: Long, name: String) = db.playlistDao().rename(id, name)

    suspend fun recolorPlaylist(id: Long, color: String) = db.playlistDao().recolor(id, color)

    suspend fun deletePlaylist(id: Long) = db.playlistDao().deleteWithTracks(id)

    suspend fun removeFromPlaylist(playlistId: Long, track: Track) {
        val entity = db.trackDao().bySourceId(track.source.name, track.sourceId) ?: return
        db.playlistDao().removeTrack(playlistId, entity.id)
    }

    /** Move one entry and rewrite positions — desktop reorder_playlist_track. */
    suspend fun movePlaylistTrack(playlistId: Long, fromIndex: Int, toIndex: Int) {
        val entities = db.playlistDao().tracksIn(playlistId)
        if (fromIndex !in entities.indices || toIndex !in entities.indices) return
        val ids = entities.map { it.id }.toMutableList()
        val moved = ids.removeAt(fromIndex)
        ids.add(toIndex, moved)
        db.playlistDao().reorder(playlistId, ids)
    }
```

Run — PASS.

- [ ] **Step 4: ViewModel + screen**

`PlayerViewModel.kt`:

```kotlin
    val playlistTracks = MutableStateFlow<List<Track>>(emptyList())

    fun loadPlaylist(id: Long) {
        viewModelScope.launch {
            runCatching { Graph.library.playlistTracks(id) }.onSuccess { playlistTracks.value = it }
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { runCatching { Graph.library.renamePlaylist(id, name) } }
    }

    fun recolorPlaylist(id: Long, color: String) {
        viewModelScope.launch { runCatching { Graph.library.recolorPlaylist(id, color) } }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { runCatching { Graph.library.deletePlaylist(id) } }
    }

    fun removeFromPlaylist(id: Long, track: Track) {
        viewModelScope.launch {
            runCatching { Graph.library.removeFromPlaylist(id, track) }
            loadPlaylist(id)
        }
    }

    fun movePlaylistTrack(id: Long, from: Int, to: Int) {
        viewModelScope.launch {
            runCatching { Graph.library.movePlaylistTrack(id, from, to) }
            loadPlaylist(id)
        }
    }
```

Create `PlaylistDetailScreen.kt`:

```kotlin
package io.github.auxen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.auxen.ui.components.AuxenTrackRow
import io.github.auxen.ui.theme.AuxenColors

/** Desktop PLAYLIST_COLORS — the 8-swatch picker palette. */
val PLAYLIST_COLORS = listOf(
    "#d4a039", "#00c4cc", "#7cb87a", "#9b59b6",
    "#e74c3c", "#3498db", "#e67e22", "#1abc9c",
)

private fun parseColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#d4a039")) }
        .getOrDefault(AuxenColors.AmberPrimary)

/**
 * Playlist detail — desktop PlaylistView: color-dot header, Play All/Shuffle,
 * rename/recolor/delete, and per-row remove + move up/down reordering.
 */
@UnstableApi
@Composable
fun PlaylistDetailScreen(
    viewModel: PlayerViewModel,
    playlistId: Long,
    onBack: () -> Unit,
) {
    val tracks by viewModel.playlistTracks.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    var showRename by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    LaunchedEffect(playlistId) { viewModel.loadPlaylist(playlistId) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Box(Modifier.size(16.dp).background(parseColor(playlist?.color), CircleShape))
            Text(
                playlist?.name ?: "Playlist",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            IconButton(onClick = { renameText = playlist?.name.orEmpty(); showRename = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename")
            }
            IconButton(onClick = { showColors = true }) {
                Icon(Icons.Filled.Palette, contentDescription = "Change color")
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete playlist", tint = MaterialTheme.colorScheme.error)
            }
        }
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(
                onClick = { viewModel.playAll(tracks) },
                enabled = tracks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AuxenColors.AmberPrimary,
                    contentColor = AuxenColors.BgDeep,
                ),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("Play All")
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { viewModel.playAll(tracks, shuffled = true) }, enabled = tracks.isNotEmpty()) {
                Icon(Icons.Filled.Shuffle, contentDescription = null)
                Text("Shuffle")
            }
        }
        if (tracks.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("This playlist is empty", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Long-press any track and choose Add to Playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(tracks, key = { i, t -> "$i|${t.source}:${t.sourceId}" }) { index, track ->
                    AuxenTrackRow(
                        track = track,
                        isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                        onPlay = { viewModel.play(track) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        trailing = {
                            IconButton(
                                onClick = { viewModel.movePlaylistTrack(playlistId, index, index - 1) },
                                enabled = index > 0,
                            ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up") }
                            IconButton(
                                onClick = { viewModel.movePlaylistTrack(playlistId, index, index + 1) },
                                enabled = index < tracks.size - 1,
                            ) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down") }
                            IconButton(onClick = { viewModel.removeFromPlaylist(playlistId, track) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove from playlist")
                            }
                        },
                    )
                }
            }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) viewModel.renamePlaylist(playlistId, renameText.trim())
                        showRename = false
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
    if (showColors) {
        AlertDialog(
            onDismissRequest = { showColors = false },
            title = { Text("Playlist color") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PLAYLIST_COLORS.forEach { hex ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(parseColor(hex), CircleShape)
                                .clickable {
                                    viewModel.recolorPlaylist(playlistId, hex)
                                    showColors = false
                                },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showColors = false }) { Text("Cancel") } },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete playlist?") },
            text = { Text("\"${playlist?.name.orEmpty()}\" will be deleted. Tracks stay in your library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(playlistId)
                        showDeleteConfirm = false
                        onBack()
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}
```

Replace the playlist placeholder route in `MainActivity.kt`:

```kotlin
            composable("playlist/{playlistId}") { backStack ->
                PlaylistDetailScreen(
                    viewModel,
                    playlistId = backStack.arguments?.getString("playlistId")?.toLongOrNull() ?: -1L,
                    onBack = { navController.popBackStack() },
                )
            }
```

Delete `DetailPlaceholder` (no remaining users) and its unused imports.

- [ ] **Step 5: Verify**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, **68 tests** (66 + 2), 0 failures.

- [ ] **Step 6: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/ \
        android/app/src/test/kotlin/io/github/auxen/
git commit -m "feat(android): playlist detail with reorder, rename, recolor, delete"
```

---

### Task 9: Docs, verification, push

**Files:**
- Modify: `docs/plans/2026-07-03-android-app.md`

- [ ] **Step 1: Update the design doc**

Change the milestone 3 roadmap row to `**3 — done (3a+3b)**` and replace the 3a note below the table with: `Milestone 3 shipped in two parts: 3a (theme, components, nav shell, mini player, Now Playing) and 3b (Home, Library, Search + history, Collection, album/artist/playlist detail). Tidal discovery surfaces (Explore/Mixes/Moods), queue reorder UI, stats, and lyrics remain for milestone 4 planning.`

- [ ] **Step 2: Full verification**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL, 68 tests, 0 failures. Record totals.

- [ ] **Step 3: Commit and push**

```bash
cd /home/mrw1986/Projects/auxen
git add docs/plans/2026-07-03-android-app.md
git commit -m "docs(android): milestone 3b screens shipped"
git push origin claude/android-app-availability-y6uzb0
```
