# Auxen Android — Milestone 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the desktop app's persistence + matching layer to Android: Room DB (tracks, favorites, playlists, play counts), local↔Tidal track matching with quality-aware duplicate resolution, lazy Tidal stream resolution via `ResolvingDataSource` (fixes short-lived-URL expiry), and queue persistence with playback resumption.

**Architecture:** New `matching/` package (pure-Kotlin port of `auxen/matching.py`), new `db/` package (Room entities + DAOs mirroring `auxen/db.py`'s schema subset), a `LibraryRepository` facade in `data/`, and a rework of the playback path so Tidal `MediaItem`s carry stable `auxen://tidal/<id>` URIs that a `ResolvingDataSource` resolves to fresh stream URLs at open() time. Queue state (track JSON + index + position) persists to Room and restores on service start and via `onPlaybackResumption`.

**Tech Stack:** Kotlin 2.0.21, Room 2.7.1 (KSP), Media3 1.5.1, Robolectric 4.14.1 for DAO tests, kotlinx-serialization for queue snapshots.

## Global Constraints

- All Gradle commands run from `/home/mrw1986/Projects/auxen/android` and MUST be prefixed with `JAVA_HOME=~/.jdks/jdk-21.0.11+10` (system Java 25 is too new for Gradle 8.14).
- Never add `org.gradle.java.home` to any gradle.properties.
- New versions (exact): `room = "2.7.1"`, `ksp = "2.0.21-1.0.28"`, `robolectric = "4.14.1"`, `androidxTestCore = "1.6.1"`.
- Do NOT commit Tidal credentials; they live in `~/.gradle/gradle.properties` as `auxen.tidalClientId` / `auxen.tidalClientSecret`.
- The `mediaId` format for all `MediaItem`s is `"${source.name}:${sourceId}"`, e.g. `"TIDAL:12345"` / `"LOCAL:678"`. Several tasks parse this — keep it exact.
- The `MediaMetadata` extras key for the serialized Track JSON is the constant `Graph.TRACK_EXTRA_KEY = "auxen.track"`.
- Commit after each task with conventional-commit messages ending in `Co-Authored-By:` trailer per repo convention (worker agents: use the trailer given in each task's commit step).
- Follow existing code style: KDoc comments referencing the desktop module being ported, 4-space indent, trailing commas.

---

### Task 1: Port `matching.py` → `matching/Matching.kt`

Pure-Kotlin port of the desktop matching module, including a dependency-free
replacement for `thefuzz.fuzz.ratio`. Reference (read them): desktop
`auxen/matching.py` and `tests/test_matching.py`.

`fuzz.ratio` semantics (verified against the installed `thefuzz` on this
machine): `ratio(a, b) = round(200 * LCS(a, b) / (len(a) + len(b)))` where
LCS is the longest-common-subsequence length. Reference values used in tests:

| a | b | ratio |
|---|---|-------|
| `everlong` | `everlong acoustic version` | 48 |
| `hey there` | `hey their` | 89 |
| `bohemian rhapsody` | `bohemian rapsody` | 97 |
| `foo fighters` | `foo fighter` | 96 |
| `completely different` | `something else` | 41 |
| `paranoid android` | `paranoid android remaster` | 78 |

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/matching/Matching.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/matching/MatchingTest.kt`

**Interfaces:**
- Consumes: `io.github.auxen.model.Track`, `Source`, `SourcePriority` (exist).
- Produces (used by Task 4):
  - `fun normalizeForMatching(text: String): String`
  - `fun fuzzRatio(a: String, b: String): Int`
  - `fun tracksMatch(a: Track, b: Track, threshold: Int = 85): Boolean`
  - `fun pickPreferredTrack(trackA: Track, trackB: Track, priority: SourcePriority): Track`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/matching/MatchingTest.kt`:

```kotlin
package io.github.auxen.matching

import io.github.auxen.model.Source
import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun track(
    title: String,
    artist: String,
    source: Source = Source.LOCAL,
    sourceId: String = "x",
    format: String? = null,
    bitDepth: Int? = null,
    sampleRateHz: Int? = null,
    bitrateKbps: Int? = null,
) = Track(
    title = title,
    artist = artist,
    source = source,
    sourceId = sourceId,
    format = format,
    bitDepth = bitDepth,
    sampleRateHz = sampleRateHz,
    bitrateKbps = bitrateKbps,
)

class NormalizeTest {
    @Test
    fun stripsAndLowercases() = assertEquals("hello world", normalizeForMatching("  Hello World  "))

    @Test
    fun handlesFeatVariations() {
        assertEquals("song ft artist", normalizeForMatching("Song feat. Artist"))
        assertEquals("song ft artist", normalizeForMatching("Song feat Artist"))
        assertEquals("song ft artist", normalizeForMatching("Song featuring Artist"))
        assertEquals("song ft artist", normalizeForMatching("Song ft Artist"))
    }

    @Test
    fun removesNonAlphanumeric() = assertEquals("song remix", normalizeForMatching("Song (Remix)"))

    @Test
    fun collapsesMultipleSpaces() = assertEquals("a b c", normalizeForMatching("a   b    c"))

    @Test
    fun emptyString() = assertEquals("", normalizeForMatching(""))
}

class FuzzRatioTest {
    @Test
    fun matchesTheFuzzReferenceValues() {
        assertEquals(48, fuzzRatio("everlong", "everlong acoustic version"))
        assertEquals(89, fuzzRatio("hey there", "hey their"))
        assertEquals(97, fuzzRatio("bohemian rhapsody", "bohemian rapsody"))
        assertEquals(96, fuzzRatio("foo fighters", "foo fighter"))
        assertEquals(41, fuzzRatio("completely different", "something else"))
        assertEquals(78, fuzzRatio("paranoid android", "paranoid android remaster"))
    }

    @Test
    fun identicalAndEmpty() {
        assertEquals(100, fuzzRatio("abc", "abc"))
        assertEquals(100, fuzzRatio("", ""))
        assertEquals(0, fuzzRatio("abc", ""))
    }

    @Test
    fun tiesRoundHalfToEvenLikePython() {
        // LCS=1, lensum=16 → raw 12.5; Python round() gives 12, not 13.
        assertEquals(12, fuzzRatio("abcdefgh", "zyxwvuta"))
    }
}

class TracksMatchTest {
    @Test
    fun sameSongExactAfterNormalization() {
        val a = track("Everlong", "Foo Fighters", Source.LOCAL, "a")
        val b = track("Everlong", "Foo Fighters", Source.TIDAL, "b")
        assertTrue(tracksMatch(a, b))
    }

    @Test
    fun caseInsensitive() {
        val a = track("EVERLONG", "foo fighters", Source.LOCAL, "a")
        val b = track("everlong", "FOO FIGHTERS", Source.TIDAL, "b")
        assertTrue(tracksMatch(a, b))
    }

    @Test
    fun differentSongNoMatch() {
        val a = track("Everlong", "Foo Fighters", Source.LOCAL, "a")
        val b = track("My Hero", "Foo Fighters", Source.TIDAL, "b")
        assertFalse(tracksMatch(a, b))
    }

    @Test
    fun featVariationMatches() {
        val a = track("Song feat. Artist", "Someone", Source.LOCAL, "a")
        val b = track("Song ft Artist", "Someone", Source.TIDAL, "b")
        assertTrue(tracksMatch(a, b))
    }

    @Test
    fun fuzzyNearThreshold() {
        val a = track("Paranoid Android", "Radiohead", Source.LOCAL, "a")
        val b = track("Paranoid Android (Remaster)", "Radiohead", Source.TIDAL, "b")
        // Normalized fuzzy ratio is 78, so the default 85 rejects it.
        assertFalse(tracksMatch(a, b))
        // But a caller can lower the threshold to accept remasters.
        assertTrue(tracksMatch(a, b, threshold = 75))
    }

    @Test
    fun differentArtistNoMatch() {
        val a = track("Reckoner", "Radiohead", Source.LOCAL, "a")
        val b = track("Reckoner", "Beyonce", Source.TIDAL, "b")
        assertFalse(tracksMatch(a, b))
    }
}

class PickPreferredTrackTest {
    private val local = track("Song", "Artist", Source.LOCAL, "l", format = "MP3", bitrateKbps = 128)
    private val tidalHiRes =
        track("Song", "Artist", Source.TIDAL, "t", format = "FLAC", bitDepth = 24, sampleRateHz = 96_000)

    @Test
    fun preferLocal() {
        assertSame(local, pickPreferredTrack(local, tidalHiRes, SourcePriority.PREFER_LOCAL))
        assertSame(local, pickPreferredTrack(tidalHiRes, local, SourcePriority.PREFER_LOCAL))
    }

    @Test
    fun preferTidal() {
        assertSame(tidalHiRes, pickPreferredTrack(local, tidalHiRes, SourcePriority.PREFER_TIDAL))
        assertSame(tidalHiRes, pickPreferredTrack(tidalHiRes, local, SourcePriority.PREFER_TIDAL))
    }

    @Test
    fun preferQualityPicksHigherScore() {
        assertSame(tidalHiRes, pickPreferredTrack(local, tidalHiRes, SourcePriority.PREFER_QUALITY))
        assertSame(tidalHiRes, pickPreferredTrack(tidalHiRes, local, SourcePriority.PREFER_QUALITY))
    }

    @Test
    fun preferQualityTieReturnsFirst() {
        val a = track("Song", "Artist", Source.LOCAL, "a", format = "FLAC")
        val b = track("Song", "Artist", Source.TIDAL, "b", format = "FLAC")
        assertSame(a, pickPreferredTrack(a, b, SourcePriority.PREFER_QUALITY))
    }

    @Test
    fun alwaysAskDefaultsToLocal() {
        assertSame(local, pickPreferredTrack(local, tidalHiRes, SourcePriority.ALWAYS_ASK))
        assertSame(local, pickPreferredTrack(tidalHiRes, local, SourcePriority.ALWAYS_ASK))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.matching.*"`
Expected: FAIL to compile (`unresolved reference: normalizeForMatching`).

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/kotlin/io/github/auxen/matching/Matching.kt`:

```kotlin
package io.github.auxen.matching

import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track

/**
 * Track matching and source-priority logic — Kotlin port of
 * `auxen/matching.py` from the desktop app.
 */

private val FEAT_PATTERN = Regex("""\b(?:feat\.?|featuring)\b""", RegexOption.IGNORE_CASE)
private val NON_ALNUM_PATTERN = Regex("[^a-z0-9 ]")
private val MULTI_SPACE_PATTERN = Regex(" {2,}")

/**
 * Normalize a string for fuzzy track matching: trim + lowercase, fold
 * "feat."/"featuring" to "ft", strip non-alphanumerics, collapse spaces.
 */
fun normalizeForMatching(text: String): String {
    var result = text.trim().lowercase()
    result = FEAT_PATTERN.replace(result, "ft")
    result = NON_ALNUM_PATTERN.replace(result, " ")
    result = MULTI_SPACE_PATTERN.replace(result, " ")
    return result.trim()
}

/**
 * Similarity score in 0..100, matching `thefuzz.fuzz.ratio` (the indel /
 * normalized-Levenshtein ratio): round(200 * LCS / (|a| + |b|)), with
 * Python's round-half-to-even tie behavior.
 */
fun fuzzRatio(a: String, b: String): Int {
    val lensum = a.length + b.length
    if (lensum == 0) return 100
    return Math.rint(200.0 * lcsLength(a, b) / lensum).toInt()
}

/** Longest-common-subsequence length, O(min) two-row DP. */
private fun lcsLength(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    var prev = IntArray(b.length + 1)
    var curr = IntArray(b.length + 1)
    for (i in a.indices) {
        for (j in b.indices) {
            curr[j + 1] = if (a[i] == b[j]) prev[j] + 1 else maxOf(prev[j + 1], curr[j])
        }
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[b.length]
}

/**
 * True when two tracks are considered the same song: exact match after
 * normalization, else both title and artist fuzzy scores >= [threshold].
 */
fun tracksMatch(a: Track, b: Track, threshold: Int = 85): Boolean {
    val titleA = normalizeForMatching(a.title)
    val titleB = normalizeForMatching(b.title)
    val artistA = normalizeForMatching(a.artist)
    val artistB = normalizeForMatching(b.artist)

    if (titleA == titleB && artistA == artistB) return true

    return fuzzRatio(titleA, titleB) >= threshold && fuzzRatio(artistA, artistB) >= threshold
}

/**
 * Return the preferred of two duplicate tracks according to [priority].
 * ALWAYS_ASK returns the local track as a default; the caller is
 * responsible for prompting the user.
 */
fun pickPreferredTrack(trackA: Track, trackB: Track, priority: SourcePriority): Track = when (priority) {
    SourcePriority.PREFER_LOCAL -> if (trackA.isLocal) trackA else trackB
    SourcePriority.PREFER_TIDAL -> if (trackA.isTidal) trackA else trackB
    SourcePriority.PREFER_QUALITY -> if (trackB.qualityScore > trackA.qualityScore) trackB else trackA
    SourcePriority.ALWAYS_ASK -> if (trackA.isLocal) trackA else trackB
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.matching.*"`
Expected: PASS (all tests green).

- [ ] **Step 5: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/matching/Matching.kt \
        android/app/src/test/kotlin/io/github/auxen/matching/MatchingTest.kt
git commit -m "feat(android): port matching.py — fuzzy track matching + source priority"
```

---

### Task 2: Room database — entities, DAOs, `AuxenDatabase`

Ports the desktop `auxen/db.py` schema subset needed for milestone 2:
`tracks`, `favorites`, `playlists`, `playlist_tracks`, `play_history`,
`settings`, plus an Android-specific `queue_items` table. Timestamps are
epoch-millis `Long`s (not SQLite datetime strings). DAO tests run on the
JVM via Robolectric.

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/build.gradle.kts`
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/main/kotlin/io/github/auxen/db/Entities.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/db/Daos.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/db/AuxenDatabase.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/db/TrackMapping.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/db/DaoTest.kt`

**Interfaces:**
- Consumes: `io.github.auxen.model.Track`, `Source`.
- Produces (used by Tasks 3, 7):
  - `AuxenDatabase.build(context: Context): AuxenDatabase` and DAO accessors `trackDao()`, `favoriteDao()`, `playlistDao()`, `playHistoryDao()`, `settingsDao()`, `queueDao()`
  - `TrackDao.upsert(track: TrackEntity, nowMillis: Long): Long` (returns row id; preserves `id`/`added_at`/`play_count` on update)
  - `TrackEntity.toTrack(): Track` and `Track.toEntity(): TrackEntity` (in `TrackMapping.kt`)
  - `FavoriteEntity(trackId: Long, matchGroupId: String?, addedAtMillis: Long)`
  - `PlayHistoryEntity(id: Long = 0, trackId: Long, playedAtMillis: Long, durationListenedSeconds: Double? = null)`
  - `SettingEntity(key: String, value: String)`
  - `QueueItemEntity(position: Int, trackJson: String)`

- [ ] **Step 1: Add Gradle dependencies**

In `android/gradle/libs.versions.toml`, add to `[versions]`:

```toml
room = "2.7.1"
ksp = "2.0.21-1.0.28"
robolectric = "4.14.1"
androidxTestCore = "1.6.1"
```

Add to `[libraries]`:

```toml
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
```

Add to `[plugins]`:

```toml
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

In `android/build.gradle.kts` (root), add inside the `plugins {}` block:

```kotlin
    alias(libs.plugins.ksp) apply false
```

In `android/app/build.gradle.kts`:
- add `alias(libs.plugins.ksp)` to the `plugins {}` block;
- inside `android {}` add:

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
```

- in `dependencies {}` add:

```kotlin
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
```

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (deps resolve; nothing uses Room yet).

- [ ] **Step 2: Write the failing DAO test**

Create `android/app/src/test/kotlin/io/github/auxen/db/DaoTest.kt`:

```kotlin
package io.github.auxen.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun track(sourceId: String, title: String = "Song $sourceId") = Track(
    title = title,
    artist = "Artist",
    source = Source.LOCAL,
    sourceId = sourceId,
    format = "FLAC",
)

@RunWith(RobolectricTestRunner::class)
class DaoTest {

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

    // ---------------- tracks ----------------

    @Test
    fun upsertInsertsThenUpdatesPreservingIdAndPlayCount() = runBlocking {
        val id = db.trackDao().upsert(track("1").toEntity(), nowMillis = 1000)
        assertTrue(id > 0)

        db.trackDao().recordPlay(id, playedAtMillis = 2000)

        // Re-upsert with changed metadata: same row id, play stats preserved.
        val id2 = db.trackDao().upsert(track("1", title = "Renamed").toEntity(), nowMillis = 3000)
        assertEquals(id, id2)
        val entity = db.trackDao().byId(id)!!
        assertEquals("Renamed", entity.title)
        assertEquals(1, entity.playCount)
        assertEquals(1000, entity.addedAtMillis)
        assertEquals(2000L, entity.lastPlayedAtMillis)
    }

    @Test
    fun bySourceIdRoundTripsTrackFields() = runBlocking {
        db.trackDao().upsert(track("42").toEntity(), nowMillis = 1)
        val entity = db.trackDao().bySourceId("LOCAL", "42")
        assertNotNull(entity)
        assertEquals("Song 42", entity!!.toTrack().title)
        assertEquals(Source.LOCAL, entity.toTrack().source)
        assertNull(db.trackDao().bySourceId("TIDAL", "42"))
    }

    // ---------------- favorites ----------------

    @Test
    fun favoriteInsertDeleteAndFlows() = runBlocking {
        val id = db.trackDao().upsert(track("7").toEntity(), nowMillis = 1)
        db.favoriteDao().insert(FavoriteEntity(trackId = id, matchGroupId = null, addedAtMillis = 5))

        assertTrue(db.favoriteDao().isFavorite(id))
        assertEquals(listOf("LOCAL:7"), db.favoriteDao().favoriteKeys().first())
        assertEquals("Song 7", db.favoriteDao().favorites().first().single().title)

        db.favoriteDao().delete(id)
        assertTrue(db.favoriteDao().favoriteKeys().first().isEmpty())
    }

    // ---------------- playlists ----------------

    @Test
    fun playlistAppendKeepsPositionsAndDeleteCascades() = runBlocking {
        val t1 = db.trackDao().upsert(track("1").toEntity(), nowMillis = 1)
        val t2 = db.trackDao().upsert(track("2").toEntity(), nowMillis = 1)
        val pl = db.playlistDao().insert(PlaylistEntity(name = "Mix", color = "#d4a039"))

        db.playlistDao().appendTrack(pl, t1)
        db.playlistDao().appendTrack(pl, t2)
        assertEquals(listOf("Song 1", "Song 2"), db.playlistDao().tracksIn(pl).map { it.title })

        db.playlistDao().removeTrack(pl, t1)
        assertEquals(listOf("Song 2"), db.playlistDao().tracksIn(pl).map { it.title })

        db.playlistDao().deleteWithTracks(pl)
        assertTrue(db.playlistDao().playlists().first().isEmpty())
        assertTrue(db.playlistDao().tracksIn(pl).isEmpty())
    }

    // ---------------- play history ----------------

    @Test
    fun recentlyPlayedOrdersByLatestPlay() = runBlocking {
        val t1 = db.trackDao().upsert(track("1").toEntity(), nowMillis = 1)
        val t2 = db.trackDao().upsert(track("2").toEntity(), nowMillis = 1)
        db.playHistoryDao().insert(PlayHistoryEntity(trackId = t1, playedAtMillis = 100))
        db.playHistoryDao().insert(PlayHistoryEntity(trackId = t2, playedAtMillis = 200))
        db.playHistoryDao().insert(PlayHistoryEntity(trackId = t1, playedAtMillis = 300))

        assertEquals(
            listOf("Song 1", "Song 2"),
            db.playHistoryDao().recentlyPlayed(limit = 10).map { it.title },
        )
    }

    // ---------------- settings + queue ----------------

    @Test
    fun settingsPutGetOverwrites() = runBlocking {
        assertNull(db.settingsDao().get("k"))
        db.settingsDao().put(SettingEntity("k", "v1"))
        db.settingsDao().put(SettingEntity("k", "v2"))
        assertEquals("v2", db.settingsDao().get("k"))
    }

    @Test
    fun queueReplaceAllRoundTrips() = runBlocking {
        db.queueDao().replaceAll(listOf(QueueItemEntity(0, "a"), QueueItemEntity(1, "b")))
        db.queueDao().replaceAll(listOf(QueueItemEntity(0, "c")))
        assertEquals(listOf("c"), db.queueDao().all().map { it.trackJson })
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.db.*"`
Expected: FAIL to compile (`unresolved reference: AuxenDatabase`).

- [ ] **Step 4: Write the entities**

Create `android/app/src/main/kotlin/io/github/auxen/db/Entities.kt`:

```kotlin
package io.github.auxen.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema — port of the desktop `auxen/db.py` tables needed on Android.
 * Timestamps are epoch millis (the desktop app uses SQLite datetime text).
 */

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["source", "source_id"], unique = true),
        Index("match_group_id"),
        Index(value = ["title", "artist"]),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String? = null,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    @ColumnInfo(name = "duration") val durationSeconds: Double? = null,
    @ColumnInfo(name = "track_number") val trackNumber: Int? = null,
    @ColumnInfo(name = "disc_number") val discNumber: Int? = null,
    val source: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "bitrate") val bitrateKbps: Int? = null,
    val format: String? = null,
    @ColumnInfo(name = "sample_rate") val sampleRateHz: Int? = null,
    @ColumnInfo(name = "bit_depth") val bitDepth: Int? = null,
    @ColumnInfo(name = "album_art_url") val albumArtUrl: String? = null,
    @ColumnInfo(name = "match_group_id") val matchGroupId: String? = null,
    val explicit: Boolean = false,
    @ColumnInfo(name = "added_at") val addedAtMillis: Long = 0,
    @ColumnInfo(name = "last_played_at") val lastPlayedAtMillis: Long? = null,
    @ColumnInfo(name = "play_count") val playCount: Int = 0,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "match_group_id") val matchGroupId: String? = null,
    @ColumnInfo(name = "added_at") val addedAtMillis: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: String? = null,
    val source: String? = null,
    @ColumnInfo(name = "tidal_playlist_id") val tidalPlaylistId: String? = null,
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlist_id", "track_id", "position"])
data class PlaylistTrackEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "track_id") val trackId: Long,
    val position: Int,
)

@Entity(tableName = "play_history", indices = [Index("track_id"), Index("played_at")])
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "played_at") val playedAtMillis: Long,
    @ColumnInfo(name = "duration_listened") val durationListenedSeconds: Double? = null,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Persisted playback queue — one row per queue slot, track as JSON. */
@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    @ColumnInfo(name = "track_json") val trackJson: String,
)
```

- [ ] **Step 5: Write the DAOs**

Create `android/app/src/main/kotlin/io/github/auxen/db/Daos.kt`:

```kotlin
package io.github.auxen.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE source = :source AND source_id = :sourceId")
    suspend fun bySourceId(source: String, sourceId: String): TrackEntity?

    @Query("UPDATE tracks SET play_count = play_count + 1, last_played_at = :playedAtMillis WHERE id = :id")
    suspend fun recordPlay(id: Long, playedAtMillis: Long)

    @Query("UPDATE tracks SET match_group_id = :groupId WHERE id IN (:ids)")
    suspend fun setMatchGroup(ids: List<Long>, groupId: String)

    /**
     * Insert-or-update keyed by (source, source_id). Metadata is refreshed;
     * row id, added_at and play stats are preserved. Mirrors the desktop
     * `Database.insert_track` upsert semantics.
     */
    @Transaction
    suspend fun upsert(track: TrackEntity, nowMillis: Long): Long {
        val existing = bySourceId(track.source, track.sourceId)
            ?: return insertIgnore(track.copy(id = 0, addedAtMillis = nowMillis))
        update(
            track.copy(
                id = existing.id,
                addedAtMillis = existing.addedAtMillis,
                lastPlayedAtMillis = existing.lastPlayedAtMillis,
                playCount = existing.playCount,
            ),
        )
        return existing.id
    }
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE track_id = :trackId")
    suspend fun delete(trackId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE track_id = :trackId)")
    suspend fun isFavorite(trackId: Long): Boolean

    @Query(
        "SELECT t.* FROM tracks t JOIN favorites f ON f.track_id = t.id " +
            "ORDER BY f.added_at DESC",
    )
    fun favorites(): Flow<List<TrackEntity>>

    @Query("SELECT t.source || ':' || t.source_id FROM tracks t JOIN favorites f ON f.track_id = t.id")
    fun favoriteKeys(): Flow<List<String>>
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlists ORDER BY name")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun clearTracks(playlistId: Long)

    @Transaction
    suspend fun deleteWithTracks(id: Long) {
        clearTracks(id)
        delete(id)
    }

    @Insert
    suspend fun insertTrack(entry: PlaylistTrackEntity)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Transaction
    suspend fun appendTrack(playlistId: Long, trackId: Long) {
        insertTrack(PlaylistTrackEntity(playlistId, trackId, nextPosition(playlistId)))
    }

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun removeTrack(playlistId: Long, trackId: Long)

    @Query(
        "SELECT t.* FROM tracks t JOIN playlist_tracks pt ON pt.track_id = t.id " +
            "WHERE pt.playlist_id = :playlistId ORDER BY pt.position",
    )
    suspend fun tracksIn(playlistId: Long): List<TrackEntity>
}

@Dao
interface PlayHistoryDao {
    @Insert
    suspend fun insert(entry: PlayHistoryEntity)

    @Query(
        "SELECT t.* FROM tracks t JOIN play_history h ON h.track_id = t.id " +
            "GROUP BY t.id ORDER BY MAX(h.played_at) DESC LIMIT :limit",
    )
    suspend fun recentlyPlayed(limit: Int): List<TrackEntity>
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?
}

@Dao
interface QueueDao {
    @Query("DELETE FROM queue_items")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<QueueItemEntity>)

    @Transaction
    suspend fun replaceAll(items: List<QueueItemEntity>) {
        clear()
        insertAll(items)
    }

    @Query("SELECT * FROM queue_items ORDER BY position")
    suspend fun all(): List<QueueItemEntity>
}
```

- [ ] **Step 6: Write the database class and Track mapping**

Create `android/app/src/main/kotlin/io/github/auxen/db/AuxenDatabase.kt`:

```kotlin
package io.github.auxen.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        PlayHistoryEntity::class,
        SettingEntity::class,
        QueueItemEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AuxenDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun settingsDao(): SettingsDao
    abstract fun queueDao(): QueueDao

    companion object {
        fun build(context: Context): AuxenDatabase =
            Room.databaseBuilder(context, AuxenDatabase::class.java, "auxen.db").build()
    }
}
```

Create `android/app/src/main/kotlin/io/github/auxen/db/TrackMapping.kt`:

```kotlin
package io.github.auxen.db

import io.github.auxen.model.Source
import io.github.auxen.model.Track

fun Track.toEntity(): TrackEntity = TrackEntity(
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    genre = genre,
    year = year,
    durationSeconds = durationSeconds,
    trackNumber = trackNumber,
    discNumber = discNumber,
    source = source.name,
    sourceId = sourceId,
    bitrateKbps = bitrateKbps,
    format = format,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    albumArtUrl = albumArtUrl,
    matchGroupId = matchGroupId,
    explicit = explicit,
)

fun TrackEntity.toTrack(): Track = Track(
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    genre = genre,
    year = year,
    durationSeconds = durationSeconds,
    trackNumber = trackNumber,
    discNumber = discNumber,
    source = Source.valueOf(source),
    sourceId = sourceId,
    bitrateKbps = bitrateKbps,
    format = format,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    albumArtUrl = albumArtUrl,
    matchGroupId = matchGroupId,
    explicit = explicit,
)
```

- [ ] **Step 7: Run test to verify it passes**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.db.*"`
Expected: PASS. Also run the full suite once: `JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest` — all green.

- [ ] **Step 8: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/gradle/libs.versions.toml android/build.gradle.kts android/app/build.gradle.kts \
        android/app/src/main/kotlin/io/github/auxen/db/ \
        android/app/src/test/kotlin/io/github/auxen/db/
git commit -m "feat(android): Room database — tracks, favorites, playlists, play history, queue"
```

---

### Task 3: `LibraryRepository` + `Graph` wiring

Facade over the DAOs mirroring the desktop `Database` API surface the UI
needs: upsert, favorites (as Flows), play recording keyed by mediaId, and
the source-priority setting. Wire it into `Graph`.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (the `Graph` object)
- Test: `android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt`

**Interfaces:**
- Consumes: `AuxenDatabase`, `TrackDao.upsert`, `FavoriteDao`, `PlayHistoryDao`, `SettingsDao`, `Track.toEntity()`, `TrackEntity.toTrack()` (Task 2).
- Produces (used by Tasks 4, 5):
  - `class LibraryRepository(db: AuxenDatabase, clock: () -> Long = System::currentTimeMillis)`
  - `suspend fun upsert(track: Track): Long`
  - `fun favorites(): Flow<List<Track>>`
  - `fun favoriteKeys(): Flow<Set<String>>` — keys are `"SOURCE:sourceId"`
  - `suspend fun setFavorite(track: Track, favorite: Boolean)`
  - `suspend fun recordPlay(mediaId: String)` — mediaId is `"SOURCE:sourceId"`; silently no-ops on unknown tracks/malformed ids
  - `suspend fun sourcePriority(): SourcePriority` (default `PREFER_QUALITY`) / `suspend fun setSourcePriority(priority: SourcePriority)`
  - `Graph.library: LibraryRepository`, `Graph.db: AuxenDatabase`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/data/LibraryRepositoryTest.kt`:

```kotlin
package io.github.auxen.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.auxen.db.AuxenDatabase
import io.github.auxen.model.Source
import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track
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
class LibraryRepositoryTest {

    private lateinit var db: AuxenDatabase
    private lateinit var repo: LibraryRepository
    private var now = 1000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AuxenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = LibraryRepository(db, clock = { now })
    }

    @After
    fun tearDown() = db.close()

    private val tidalTrack = Track(
        title = "Everlong",
        artist = "Foo Fighters",
        source = Source.TIDAL,
        sourceId = "99",
        format = "FLAC",
    )

    @Test
    fun setFavoriteUpsertsAndToggles() = runBlocking {
        repo.setFavorite(tidalTrack, favorite = true)
        assertEquals(setOf("TIDAL:99"), repo.favoriteKeys().first())
        assertEquals("Everlong", repo.favorites().first().single().title)

        repo.setFavorite(tidalTrack, favorite = false)
        assertTrue(repo.favoriteKeys().first().isEmpty())
    }

    @Test
    fun recordPlayIncrementsCountAndWritesHistory() = runBlocking {
        val id = repo.upsert(tidalTrack)
        now = 2000
        repo.recordPlay("TIDAL:99")
        now = 3000
        repo.recordPlay("TIDAL:99")

        val entity = db.trackDao().byId(id)!!
        assertEquals(2, entity.playCount)
        assertEquals(3000L, entity.lastPlayedAtMillis)
        assertEquals(listOf("Everlong"), db.playHistoryDao().recentlyPlayed(10).map { it.title })
    }

    @Test
    fun recordPlayIgnoresUnknownAndMalformedIds() = runBlocking {
        repo.recordPlay("TIDAL:does-not-exist")
        repo.recordPlay("garbage")
        // No exception, no history rows.
        assertTrue(db.playHistoryDao().recentlyPlayed(10).isEmpty())
    }

    @Test
    fun sourcePriorityDefaultsToQualityAndPersists() = runBlocking {
        assertEquals(SourcePriority.PREFER_QUALITY, repo.sourcePriority())
        repo.setSourcePriority(SourcePriority.PREFER_LOCAL)
        assertEquals(SourcePriority.PREFER_LOCAL, repo.sourcePriority())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.data.*"`
Expected: FAIL to compile (`unresolved reference: LibraryRepository`).

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/kotlin/io/github/auxen/data/LibraryRepository.kt`:

```kotlin
package io.github.auxen.data

import io.github.auxen.db.AuxenDatabase
import io.github.auxen.db.FavoriteEntity
import io.github.auxen.db.PlayHistoryEntity
import io.github.auxen.db.SettingEntity
import io.github.auxen.db.toEntity
import io.github.auxen.db.toTrack
import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Library persistence facade — the Android analog of the desktop
 * `auxen.db.Database` surface the UI consumes (favorites, play counts,
 * settings). Backed by Room; injectable clock for tests.
 */
class LibraryRepository(
    private val db: AuxenDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Insert or refresh a track row; returns its stable row id. */
    suspend fun upsert(track: Track): Long = db.trackDao().upsert(track.toEntity(), clock())

    fun favorites(): Flow<List<Track>> =
        db.favoriteDao().favorites().map { list -> list.map { it.toTrack() } }

    /** Favorite identity keys ("SOURCE:sourceId") for fast UI lookups. */
    fun favoriteKeys(): Flow<Set<String>> =
        db.favoriteDao().favoriteKeys().map { it.toSet() }

    suspend fun setFavorite(track: Track, favorite: Boolean) {
        val id = upsert(track)
        if (favorite) {
            db.favoriteDao().insert(FavoriteEntity(id, track.matchGroupId, clock()))
        } else {
            db.favoriteDao().delete(id)
        }
    }

    /**
     * Record a play for a mediaId of the form "SOURCE:sourceId". Unknown
     * tracks and malformed ids are ignored — playback must never crash on
     * bookkeeping.
     */
    suspend fun recordPlay(mediaId: String) {
        val parts = mediaId.split(':', limit = 2)
        if (parts.size != 2) return
        val entity = db.trackDao().bySourceId(parts[0], parts[1]) ?: return
        val now = clock()
        db.trackDao().recordPlay(entity.id, now)
        db.playHistoryDao().insert(PlayHistoryEntity(trackId = entity.id, playedAtMillis = now))
    }

    suspend fun sourcePriority(): SourcePriority =
        db.settingsDao().get(KEY_SOURCE_PRIORITY)
            ?.let { stored -> SourcePriority.entries.firstOrNull { it.name == stored } }
            ?: SourcePriority.PREFER_QUALITY

    suspend fun setSourcePriority(priority: SourcePriority) =
        db.settingsDao().put(SettingEntity(KEY_SOURCE_PRIORITY, priority.name))

    private companion object {
        const val KEY_SOURCE_PRIORITY = "source_priority"
    }
}
```

In `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt`, add to the `Graph` object (new properties next to the existing ones, initialized in `Graph.init` after `local = ...`):

```kotlin
    lateinit var db: AuxenDatabase
        private set
    lateinit var library: LibraryRepository
        private set
```

and in `init(context: Context)`:

```kotlin
        db = AuxenDatabase.build(context)
        library = LibraryRepository(db)
```

with imports `io.github.auxen.data.LibraryRepository` and `io.github.auxen.db.AuxenDatabase`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.data.*" :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/data/ \
        android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt \
        android/app/src/test/kotlin/io/github/auxen/data/
git commit -m "feat(android): LibraryRepository — favorites, play counts, source priority"
```

---

### Task 4: Quality-aware duplicate resolution in search

Merge local + Tidal search results: pairs that `tracksMatch` collapse into
the preferred track (per the user's `SourcePriority` setting) tagged with a
`matchGroupId`. Mirrors the desktop match-group behavior.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/matching/DuplicateResolver.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (the `search` function, currently lines 83–92)
- Test: `android/app/src/test/kotlin/io/github/auxen/matching/DuplicateResolverTest.kt`

**Interfaces:**
- Consumes: `tracksMatch`, `pickPreferredTrack` (Task 1); `Graph.library.sourcePriority()` (Task 3).
- Produces: `DuplicateResolver.merge(local: List<Track>, tidal: List<Track>, priority: SourcePriority): List<Track>`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/matching/DuplicateResolverTest.kt`:

```kotlin
package io.github.auxen.matching

import io.github.auxen.model.Source
import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun local(sourceId: String, title: String, format: String? = "MP3") =
    Track(title = title, artist = "Artist", source = Source.LOCAL, sourceId = sourceId, format = format)

private fun tidal(sourceId: String, title: String, format: String? = "FLAC") =
    Track(title = title, artist = "Artist", source = Source.TIDAL, sourceId = sourceId, format = format)

class DuplicateResolverTest {

    @Test
    fun collapsesMatchedPairToPreferredWithGroupId() {
        val merged = DuplicateResolver.merge(
            local = listOf(local("1", "Everlong")),
            tidal = listOf(tidal("t1", "Everlong")),
            priority = SourcePriority.PREFER_QUALITY,
        )
        assertEquals(1, merged.size)
        // Tidal FLAC (500) beats local MP3 (100).
        assertEquals(Source.TIDAL, merged[0].source)
        assertEquals("LOCAL:1", merged[0].matchGroupId)
    }

    @Test
    fun preferLocalKeepsLocalEntry() {
        val merged = DuplicateResolver.merge(
            local = listOf(local("1", "Everlong")),
            tidal = listOf(tidal("t1", "Everlong")),
            priority = SourcePriority.PREFER_LOCAL,
        )
        assertEquals(Source.LOCAL, merged.single().source)
    }

    @Test
    fun unmatchedTracksPassThroughInOrder() {
        val merged = DuplicateResolver.merge(
            local = listOf(local("1", "Everlong"), local("2", "My Hero")),
            tidal = listOf(tidal("t1", "Everlong"), tidal("t2", "Walk")),
            priority = SourcePriority.PREFER_QUALITY,
        )
        // Everlong collapses; My Hero and Walk pass through untouched.
        assertEquals(listOf("Everlong", "My Hero", "Walk"), merged.map { it.title })
        assertNull(merged[1].matchGroupId)
        assertNull(merged[2].matchGroupId)
    }

    @Test
    fun eachTidalTrackConsumedAtMostOnce() {
        val merged = DuplicateResolver.merge(
            local = listOf(local("1", "Everlong"), local("2", "Everlong")),
            tidal = listOf(tidal("t1", "Everlong")),
            priority = SourcePriority.PREFER_TIDAL,
        )
        // First local pairs with the only Tidal copy; second stays local.
        assertEquals(2, merged.size)
        assertEquals(Source.TIDAL, merged[0].source)
        assertEquals(Source.LOCAL, merged[1].source)
    }

    @Test
    fun emptyInputs() {
        assertEquals(0, DuplicateResolver.merge(emptyList(), emptyList(), SourcePriority.PREFER_QUALITY).size)
        assertEquals(
            listOf("Walk"),
            DuplicateResolver.merge(emptyList(), listOf(tidal("t", "Walk")), SourcePriority.PREFER_QUALITY)
                .map { it.title },
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.matching.DuplicateResolverTest"`
Expected: FAIL to compile (`unresolved reference: DuplicateResolver`).

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/kotlin/io/github/auxen/matching/DuplicateResolver.kt`:

```kotlin
package io.github.auxen.matching

import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track

/**
 * Quality-aware duplicate resolution for merged local + Tidal result lists —
 * the Android analog of the desktop app's match-group handling.
 */
object DuplicateResolver {

    /**
     * Merge [local] and [tidal] results. Each local track pairs with the
     * first unconsumed Tidal track that [tracksMatch]es it; the pair
     * collapses to [pickPreferredTrack]'s winner, tagged with a
     * deterministic matchGroupId (the local track's "SOURCE:sourceId").
     * Unmatched tracks pass through unchanged, local first.
     */
    fun merge(local: List<Track>, tidal: List<Track>, priority: SourcePriority): List<Track> {
        val remaining = tidal.toMutableList()
        val merged = mutableListOf<Track>()
        for (localTrack in local) {
            val matchIndex = remaining.indexOfFirst { tracksMatch(localTrack, it) }
            if (matchIndex == -1) {
                merged += localTrack
                continue
            }
            val tidalTrack = remaining.removeAt(matchIndex)
            val groupId = "${localTrack.source.name}:${localTrack.sourceId}"
            merged += pickPreferredTrack(localTrack, tidalTrack, priority).copy(matchGroupId = groupId)
        }
        merged += remaining
        return merged
    }
}
```

In `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt`, replace the body of `search` so the combined list goes through the resolver (add imports `io.github.auxen.matching.DuplicateResolver`):

```kotlin
    /** Search local library and Tidal, collapsing duplicates — like the desktop app. */
    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            searchInFlight = true
            val local = runCatching { Graph.local.search(query) }.getOrDefault(emptyList())
            val tidal = runCatching { Graph.tidal.search(query) }.getOrDefault(emptyList())
            val priority = runCatching { Graph.library.sourcePriority() }
                .getOrDefault(io.github.auxen.model.SourcePriority.PREFER_QUALITY)
            searchResults.value = DuplicateResolver.merge(local, tidal, priority)
            searchInFlight = false
        }
    }
```

(Use a proper `import io.github.auxen.model.SourcePriority` instead of the inline qualified name.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.matching.*" :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/matching/DuplicateResolver.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/test/kotlin/io/github/auxen/matching/DuplicateResolverTest.kt
git commit -m "feat(android): quality-aware duplicate resolution in merged search results"
```

---

### Task 5: Favorites UI + play recording

Heart toggle on every track row, a Favorites tab, and play-count recording
in the playback service. UI is verified by compilation + the existing test
suite (no Compose test infra in this project yet); the repository behavior
underneath is already covered by Task 3's tests.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/Screens.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt`

**Interfaces:**
- Consumes: `Graph.library` (Task 3).
- Produces (used by UI only):
  - `PlayerViewModel.favoriteKeys: StateFlow<Set<String>>`, `PlayerViewModel.favorites: StateFlow<List<Track>>`, `PlayerViewModel.toggleFavorite(track: Track)`
  - `FavoritesScreen(viewModel: PlayerViewModel, modifier: Modifier)` composable

- [ ] **Step 1: Extend PlayerViewModel**

In `PlayerViewModel.kt` add (imports: `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.stateIn`):

```kotlin
    /** "SOURCE:sourceId" keys of favorited tracks, for O(1) heart-state lookups. */
    val favoriteKeys: StateFlow<Set<String>> = Graph.library.favoriteKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val favorites: StateFlow<List<Track>> = Graph.library.favorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            val key = "${track.source.name}:${track.sourceId}"
            runCatching { Graph.library.setFavorite(track, key !in favoriteKeys.value) }
        }
    }
```

Also make `play` and `enqueue` upsert the track so play recording can find it (insert the `upsert` line as the first statement inside each coroutine):

```kotlin
    fun play(track: Track) {
        viewModelScope.launch {
            runCatching { Graph.library.upsert(track) }
            val c = controller ?: return@launch
            runCatching { Graph.mediaItemFor(track) }.onSuccess { item ->
                c.setMediaItem(item)
                c.prepare()
                c.play()
            }
        }
    }

    fun enqueue(track: Track) {
        viewModelScope.launch {
            runCatching { Graph.library.upsert(track) }
            val c = controller ?: return@launch
            runCatching { Graph.mediaItemFor(track) }.onSuccess { c.addMediaItem(it) }
        }
    }
```

(Note: `Graph.mediaItemFor` is still `suspend` at this point; Task 6 makes it non-suspend — both forms compile inside the coroutine.)

- [ ] **Step 2: Add heart toggle and FavoritesScreen to Screens.kt**

In `Screens.kt`:

1. Change `TrackRow` to accept favorite state (replace the existing private composable):

```kotlin
@Composable
private fun TrackRow(
    track: Track,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.albumArtUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track.artist, track.album).joinToString(" — "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        AssistChip(
            onClick = {},
            label = { Text(if (track.source == Source.TIDAL) track.qualityLabel else "Local") },
        )
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        IconButton(onClick = onEnqueue) {
            Icon(Icons.Filled.PlaylistAdd, contentDescription = "Add to queue")
        }
    }
}
```

New imports needed: `androidx.compose.material.icons.filled.Favorite`,
`androidx.compose.material.icons.filled.FavoriteBorder`,
`androidx.compose.material3.LocalContentColor`.

2. Update both call sites (`LibraryScreen` and `SearchScreen`) — collect the keys once above the `LazyColumn` (`val favoriteKeys by viewModel.favoriteKeys.collectAsState()`) and pass:

```kotlin
                TrackRow(
                    track,
                    isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                    onPlay = { viewModel.play(track) },
                    onEnqueue = { viewModel.enqueue(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                )
```

3. Add the new screen at the end of the file:

```kotlin
@UnstableApi
@Composable
fun FavoritesScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val tracks by viewModel.favorites.collectAsState()
    val favoriteKeys by viewModel.favoriteKeys.collectAsState()

    if (tracks.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No favorites yet", style = MaterialTheme.typography.titleMedium)
            Text("Tap the heart on any track to add it here.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(tracks, key = { "${it.source}:${it.sourceId}" }) { track ->
                TrackRow(
                    track,
                    isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                    onPlay = { viewModel.play(track) },
                    onEnqueue = { viewModel.enqueue(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                )
            }
        }
    }
}
```

- [ ] **Step 3: Add the Favorites tab in MainActivity**

In `MainActivity.kt`'s `MainScreen`, insert a Favorites tab after Search
(import `androidx.compose.material.icons.filled.Favorite`):

```kotlin
    val tabs = listOf(
        Tab("Library") { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
        Tab("Search") { Icon(Icons.Filled.Search, contentDescription = null) },
        Tab("Favorites") { Icon(Icons.Filled.Favorite, contentDescription = null) },
        Tab("Equalizer") { Icon(Icons.Filled.Equalizer, contentDescription = null) },
        Tab("Account") { Icon(Icons.Filled.Person, contentDescription = null) },
    )
```

and in the `when (selectedTab)`:

```kotlin
        when (selectedTab) {
            0 -> LibraryScreen(viewModel, contentModifier)
            1 -> SearchScreen(viewModel, contentModifier)
            2 -> FavoritesScreen(viewModel, contentModifier)
            3 -> EqualizerScreen(contentModifier)
            4 -> AccountScreen(viewModel, contentModifier)
        }
```

- [ ] **Step 4: Record plays in PlaybackService**

In `PlaybackService.kt`, add a service coroutine scope and a listener
(imports: `androidx.media3.common.MediaItem`, `androidx.media3.common.Player`,
`io.github.auxen.Graph`, `kotlinx.coroutines.CoroutineScope`,
`kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.SupervisorJob`,
`kotlinx.coroutines.cancel`, `kotlinx.coroutines.launch`):

```kotlin
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

In `onCreate`, after `val player = ExoPlayer.Builder(...)...build()`:

```kotlin
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mediaId = mediaItem?.mediaId ?: return
                serviceScope.launch { runCatching { Graph.library.recordPlay(mediaId) } }
            }
        })
```

In `onDestroy`, before `super.onDestroy()`:

```kotlin
        serviceScope.cancel()
```

- [ ] **Step 5: Verify build and tests**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/ui/ \
        android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt
git commit -m "feat(android): favorites UI (heart + tab) and play-count recording"
```

---

### Task 6: Lazy Tidal stream resolution via ResolvingDataSource

Fixes milestone 1's known limitation: Tidal URLs were resolved at enqueue
time and expire. Tidal `MediaItem`s now carry a stable `auxen://tidal/<id>`
URI; a `ResolvingDataSource` resolves it to a fresh stream URL when the
item is actually opened. DASH manifests (which can't flow through a
progressive DataSpec rewrite) surface as a typed exception that the service
catches, swapping in a fully-resolved `data:` DASH item. Expired progressive
URLs (HTTP 401/403/410) invalidate the cache entry and re-prepare, which
re-resolves through the same path.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (`Graph`)
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/TrackResolver.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/TidalUriResolver.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/TrackResolverTest.kt`

**Interfaces:**
- Consumes: `TidalProvider.getStreamInfo`, `StreamInfo(uri, mimeType, sampleRateHz, bitDepth)` (exist); `Graph.library.upsert` unaffected.
- Produces (used by Task 7):
  - `Graph.mediaItemFor(track: Track): MediaItem` — now **non-suspend**; Tidal URI `auxen://tidal/<sourceId>`, local URI `content://...`; metadata extras carry `Graph.TRACK_EXTRA_KEY` → Track JSON
  - `Graph.TRACK_EXTRA_KEY: String = "auxen.track"` and `Graph.json: Json`
  - `Graph.resolver: TrackResolver`
  - `TrackResolver(fetch: suspend (String) -> StreamInfo, clock: () -> Long = ..., ttlMillis: Long = 20 * 60 * 1000)` with `suspend fun resolve(tidalTrackId: String): StreamInfo` and `fun invalidate(tidalTrackId: String)`
  - `TidalProvider.getStreamInfoById(trackId: String): StreamInfo`

- [ ] **Step 1: Write the failing TrackResolver test**

Create `android/app/src/test/kotlin/io/github/auxen/playback/TrackResolverTest.kt`:

```kotlin
package io.github.auxen.playback

import io.github.auxen.provider.StreamInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackResolverTest {

    private var now = 0L
    private var fetchCount = 0
    private val resolver = TrackResolver(
        fetch = { id ->
            fetchCount++
            StreamInfo(uri = "https://cdn.example/$id?n=$fetchCount")
        },
        clock = { now },
        ttlMillis = 1000,
    )

    @Test
    fun cachesWithinTtl() = runBlocking {
        val first = resolver.resolve("42")
        now = 500
        val second = resolver.resolve("42")
        assertEquals(first.uri, second.uri)
        assertEquals(1, fetchCount)
    }

    @Test
    fun refetchesAfterTtlExpires() = runBlocking {
        resolver.resolve("42")
        now = 1500
        resolver.resolve("42")
        assertEquals(2, fetchCount)
    }

    @Test
    fun invalidateForcesRefetch() = runBlocking {
        resolver.resolve("42")
        resolver.invalidate("42")
        resolver.resolve("42")
        assertEquals(2, fetchCount)
    }

    @Test
    fun distinctTracksCachedIndependently() = runBlocking {
        resolver.resolve("1")
        resolver.resolve("2")
        resolver.resolve("1")
        assertEquals(2, fetchCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.playback.*"`
Expected: FAIL to compile (`unresolved reference: TrackResolver`).

- [ ] **Step 3: Implement TrackResolver**

Create `android/app/src/main/kotlin/io/github/auxen/playback/TrackResolver.kt`:

```kotlin
package io.github.auxen.playback

import io.github.auxen.provider.StreamInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Tidal track ids to fresh [StreamInfo] with a short TTL cache.
 * Tidal stream URLs are short-lived, so nothing here is persisted; the
 * cache only avoids duplicate API calls during one listening session.
 */
class TrackResolver(
    private val fetch: suspend (tidalTrackId: String) -> StreamInfo,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {

    private data class Entry(val info: StreamInfo, val resolvedAtMillis: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun resolve(tidalTrackId: String): StreamInfo {
        cache[tidalTrackId]?.let { entry ->
            if (clock() - entry.resolvedAtMillis < ttlMillis) return entry.info
        }
        val info = fetch(tidalTrackId)
        cache[tidalTrackId] = Entry(info, clock())
        return info
    }

    /** Drop a cached entry, e.g. after the CDN answered 401/403/410. */
    fun invalidate(tidalTrackId: String) {
        cache.remove(tidalTrackId)
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 20L * 60 * 1000
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.playback.*"`
Expected: PASS.

- [ ] **Step 5: Add `getStreamInfoById` to TidalProvider**

In `TidalProvider.kt`, rework `getStreamInfo` to delegate (the body moves
unchanged into the new function, with `track.sourceId` replaced by `trackId`):

```kotlin
    override suspend fun getStreamInfo(track: Track): StreamInfo = getStreamInfoById(track.sourceId)

    /**
     * Resolve a playable stream by Tidal track id. Requests Hi-Res lossless;
     * Tidal answers with either a BTS manifest (direct FLAC/AAC URLs) or a
     * DASH MPD, which is passed to ExoPlayer as a data: URI.
     */
    suspend fun getStreamInfoById(trackId: String): StreamInfo {
        val token = validToken() ?: error("Not logged in to Tidal")
        val url = "$API_BASE/tracks/$trackId/playbackinfopostpaywall".toHttpUrl()
            .newBuilder()
            .addQueryParameter("audioquality", "HI_RES_LOSSLESS")
            .addQueryParameter("playbackmode", "STREAM")
            .addQueryParameter("assetpresentation", "FULL")
            .addQueryParameter("countryCode", countryCode)
            .build()
        val info = json.decodeFromString<PlaybackInfo>(get(url.toString(), token))

        return when {
            info.manifestMimeType.contains("vnd.tidal.bts") -> {
                val decoded = String(Base64.decode(info.manifest, Base64.DEFAULT))
                val bts = json.decodeFromString<BtsManifest>(decoded)
                StreamInfo(
                    uri = bts.urls.firstOrNull() ?: error("Empty BTS manifest for track $trackId"),
                    mimeType = bts.mimeType,
                    sampleRateHz = info.sampleRate,
                    bitDepth = info.bitDepth,
                )
            }
            info.manifestMimeType.contains("dash+xml") -> StreamInfo(
                uri = "data:application/dash+xml;base64,${info.manifest}",
                mimeType = "application/dash+xml",
                sampleRateHz = info.sampleRate,
                bitDepth = info.bitDepth,
            )
            else -> error("Unsupported Tidal manifest type: ${info.manifestMimeType}")
        }
    }
```

- [ ] **Step 6: Rework `Graph.mediaItemFor` to be non-suspend + add resolver**

In `AuxenApp.kt`'s `Graph` (new imports: `android.content.ContentUris`,
`android.os.Bundle`, `android.provider.MediaStore`,
`io.github.auxen.playback.TrackResolver`, `kotlinx.serialization.json.Json`,
`kotlinx.serialization.encodeToString`):

```kotlin
    /** MediaMetadata extras key holding the serialized [Track] JSON. */
    const val TRACK_EXTRA_KEY = "auxen.track"

    val json = Json { ignoreUnknownKeys = true }

    lateinit var resolver: TrackResolver
        private set
```

in `init(context)` add:

```kotlin
        resolver = TrackResolver(fetch = { id -> tidal.getStreamInfoById(id) })
```

and replace the whole `suspend fun mediaItemFor` with:

```kotlin
    /**
     * Build a playable [MediaItem] for a track from either source — cheap
     * and non-suspending. Local tracks point straight at MediaStore; Tidal
     * tracks get a stable `auxen://tidal/<id>` URI that [TrackResolver]
     * turns into a fresh (short-lived) stream URL at open() time, so long
     * queues never hold expired URLs.
     */
    fun mediaItemFor(track: Track): MediaItem {
        val uri = if (track.source == Source.LOCAL) {
            ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                track.sourceId.toLong(),
            ).toString()
        } else {
            "auxen://tidal/${track.sourceId}"
        }
        val extras = Bundle().apply { putString(TRACK_EXTRA_KEY, json.encodeToString(track)) }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.albumArtUrl?.let(Uri::parse))
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId("${track.source.name}:${track.sourceId}")
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }
```

(`Graph.mediaItemFor` callers in `PlayerViewModel` keep compiling — the
call just stops suspending.)

- [ ] **Step 7: Implement the DataSpec resolver**

Create `android/app/src/main/kotlin/io/github/auxen/playback/TidalUriResolver.kt`:

```kotlin
package io.github.auxen.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import io.github.auxen.provider.StreamInfo
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Rewrites `auxen://tidal/<id>` DataSpecs to fresh Tidal stream URLs just
 * before the data source opens — the milestone-2 fix for short-lived URLs.
 * Runs on Media3's loader thread, so blocking on the network call is fine.
 */
@UnstableApi
class TidalUriResolver(private val resolver: TrackResolver) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != "auxen") return dataSpec
        val trackId = dataSpec.uri.lastPathSegment
            ?: throw IOException("Malformed auxen URI: ${dataSpec.uri}")
        val info = runBlocking { resolver.resolve(trackId) }
        if (info.uri.startsWith("data:")) {
            // A DASH manifest can't flow through a progressive DataSpec;
            // PlaybackService catches this and swaps in a DASH media item.
            throw TidalDashStreamException(trackId, info)
        }
        return dataSpec.withUri(Uri.parse(info.uri))
    }
}

/** Signals that a Tidal track resolved to a DASH manifest, not a direct URL. */
class TidalDashStreamException(
    val trackId: String,
    val streamInfo: StreamInfo,
) : IOException("Tidal track $trackId resolved to a DASH manifest")
```

- [ ] **Step 8: Wire the data source + error recovery into PlaybackService**

In `PlaybackService.kt` (new imports: `androidx.media3.common.MimeTypes`,
`androidx.media3.common.PlaybackException`,
`androidx.media3.datasource.DefaultDataSource`,
`androidx.media3.datasource.HttpDataSource`,
`androidx.media3.datasource.ResolvingDataSource`,
`androidx.media3.exoplayer.source.DefaultMediaSourceFactory`):

Replace the player construction with:

```kotlin
        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this),
            TidalUriResolver(Graph.resolver),
        )

        val player = ExoPlayer.Builder(this, EqRenderersFactory(this, eqProcessor))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
```

Extend the `Player.Listener` added in Task 5 with error recovery (the
listener object grows a second override; `retryGuard` is a service field):

```kotlin
    /** mediaId -> last recovery attempt, to stop error/retry loops. */
    private val retryGuard = mutableMapOf<String, Long>()
```

```kotlin
            override fun onPlayerError(error: PlaybackException) {
                val currentPlayer = mediaSession?.player ?: return
                val item = currentPlayer.currentMediaItem ?: return

                val dash = findCause<TidalDashStreamException>(error)
                if (dash != null) {
                    // Swap the stable auxen:// item for the resolved DASH manifest.
                    val newItem = item.buildUpon()
                        .setUri(dash.streamInfo.uri)
                        .setMimeType(MimeTypes.APPLICATION_MPD)
                        .build()
                    currentPlayer.replaceMediaItem(currentPlayer.currentMediaItemIndex, newItem)
                    currentPlayer.prepare()
                    currentPlayer.play()
                    return
                }

                val http = findCause<HttpDataSource.InvalidResponseCodeException>(error)
                val expired = http != null && http.responseCode in intArrayOf(401, 403, 410)
                if (expired && item.mediaId.startsWith("TIDAL:")) {
                    val now = System.currentTimeMillis()
                    if (now - (retryGuard[item.mediaId] ?: 0) < RETRY_COOLDOWN_MILLIS) return
                    retryGuard[item.mediaId] = now
                    // Cached URL went stale: drop it and re-prepare — the
                    // auxen:// URI re-resolves to a fresh URL on open.
                    Graph.resolver.invalidate(item.mediaId.substringAfter(':'))
                    currentPlayer.prepare()
                    currentPlayer.play()
                }
            }
```

Add at file scope (bottom of `PlaybackService.kt`):

```kotlin
private const val RETRY_COOLDOWN_MILLIS = 60_000L

private inline fun <reified T : Throwable> findCause(error: Throwable): T? {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is T) return cause
        cause = cause.cause
    }
    return null
}
```

- [ ] **Step 9: Verify build and full test suite**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 10: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/playback/ \
        android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt \
        android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt \
        android/app/src/test/kotlin/io/github/auxen/playback/
git commit -m "feat(android): lazy Tidal stream resolution via ResolvingDataSource"
```

---

### Task 7: Queue persistence + playback resumption

Persist the queue (track JSON per slot + current index + position) to Room
on every queue change, restore it when the service starts, and serve it to
`onPlaybackResumption` so the media notification / Bluetooth can resume
after process death.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/QueueStateStore.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (`Graph`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (`togglePlayPause`)
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/QueueStateStoreTest.kt`

**Interfaces:**
- Consumes: `QueueDao.replaceAll/all`, `SettingsDao.put/get`, `QueueItemEntity`, `SettingEntity` (Task 2); `Graph.mediaItemFor` non-suspend + `Graph.TRACK_EXTRA_KEY` + `Graph.json` (Task 6).
- Produces:
  - `class QueueStateStore(db: AuxenDatabase)` with
    `suspend fun save(tracks: List<Track>, index: Int, positionMs: Long)`,
    `suspend fun load(): SavedQueue?`, and
    `data class SavedQueue(val tracks: List<Track>, val index: Int, val positionMs: Long)`
  - `Graph.queueStore: QueueStateStore`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/playback/QueueStateStoreTest.kt`:

```kotlin
package io.github.auxen.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.auxen.db.AuxenDatabase
import io.github.auxen.db.QueueItemEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueStateStoreTest {

    private lateinit var db: AuxenDatabase
    private lateinit var store: QueueStateStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AuxenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = QueueStateStore(db)
    }

    @After
    fun tearDown() = db.close()

    private fun track(id: String) = Track(
        title = "Song $id",
        artist = "Artist",
        source = Source.TIDAL,
        sourceId = id,
    )

    @Test
    fun saveLoadRoundTrip() = runBlocking {
        store.save(listOf(track("1"), track("2"), track("3")), index = 1, positionMs = 42_000)
        val saved = store.load()!!
        assertEquals(listOf("Song 1", "Song 2", "Song 3"), saved.tracks.map { it.title })
        assertEquals(1, saved.index)
        assertEquals(42_000L, saved.positionMs)
    }

    @Test
    fun emptyQueueLoadsAsNull() = runBlocking {
        assertNull(store.load())
        store.save(listOf(track("1")), 0, 0)
        store.save(emptyList(), 0, 0)
        assertNull(store.load())
    }

    @Test
    fun corruptedRowsAreSkippedAndIndexClamped() = runBlocking {
        store.save(listOf(track("1"), track("2")), index = 1, positionMs = 5)
        db.queueDao().replaceAll(
            listOf(
                QueueItemEntity(0, "not json"),
                QueueItemEntity(1, Json.encodeToString(Track.serializer(), track("2"))),
            ),
        )
        val saved = store.load()!!
        assertEquals(listOf("Song 2"), saved.tracks.map { it.title })
        assertEquals(0, saved.index) // clamped from 1 to last valid index
    }
}
```

(Additional import for the test: `kotlinx.serialization.json.Json`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.playback.QueueStateStoreTest"`
Expected: FAIL to compile (`unresolved reference: QueueStateStore`).

- [ ] **Step 3: Implement QueueStateStore**

Create `android/app/src/main/kotlin/io/github/auxen/playback/QueueStateStore.kt`:

```kotlin
package io.github.auxen.playback

import io.github.auxen.db.AuxenDatabase
import io.github.auxen.db.QueueItemEntity
import io.github.auxen.db.SettingEntity
import io.github.auxen.model.Track
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the playback queue across process death — the Android analog of
 * the desktop app's queue persistence. Tracks are stored as JSON per queue
 * slot so restoration doesn't depend on the library tables.
 */
class QueueStateStore(private val db: AuxenDatabase) {

    data class SavedQueue(val tracks: List<Track>, val index: Int, val positionMs: Long)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(tracks: List<Track>, index: Int, positionMs: Long) {
        db.queueDao().replaceAll(
            tracks.mapIndexed { i, track -> QueueItemEntity(i, json.encodeToString(track)) },
        )
        db.settingsDao().put(SettingEntity(KEY_INDEX, index.toString()))
        db.settingsDao().put(SettingEntity(KEY_POSITION, positionMs.toString()))
    }

    /** Null when nothing (valid) is saved. Corrupted rows are skipped. */
    suspend fun load(): SavedQueue? {
        val items = db.queueDao().all()
        val tracks = items.mapNotNull { item ->
            runCatching { json.decodeFromString<Track>(item.trackJson) }.getOrNull()
        }
        if (tracks.isEmpty()) return null
        val index = (db.settingsDao().get(KEY_INDEX)?.toIntOrNull() ?: 0)
            .coerceIn(0, tracks.size - 1)
        val positionMs = (db.settingsDao().get(KEY_POSITION)?.toLongOrNull() ?: 0)
            .coerceAtLeast(0)
        return SavedQueue(tracks, index, positionMs)
    }

    private companion object {
        const val KEY_INDEX = "queue_index"
        const val KEY_POSITION = "queue_position_ms"
    }
}
```

In `Graph` (`AuxenApp.kt`): add

```kotlin
    lateinit var queueStore: QueueStateStore
        private set
```

and in `init(context)`:

```kotlin
        queueStore = QueueStateStore(db)
```

(import `io.github.auxen.playback.QueueStateStore`).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.playback.QueueStateStoreTest"`
Expected: PASS.

- [ ] **Step 5: Wire save/restore into PlaybackService**

In `PlaybackService.kt` (new imports: `androidx.media3.common.Timeline`,
`androidx.media3.session.MediaSession.MediaItemsWithStartPosition`,
`com.google.common.util.concurrent.ListenableFuture`,
`com.google.common.util.concurrent.SettableFuture`,
`io.github.auxen.model.Track`, `kotlinx.coroutines.Job`,
`kotlinx.coroutines.delay`, `kotlinx.coroutines.withContext`,
`kotlinx.coroutines.MainScope`):

Add fields:

```kotlin
    private val mainScope = MainScope()
    private var queueSaveJob: Job? = null
```

Add helper functions to the service class:

```kotlin
    /** Snapshot the queue's Tracks from each item's metadata extras. */
    private fun currentTracks(player: Player): List<Track> =
        (0 until player.mediaItemCount).mapNotNull { i ->
            player.getMediaItemAt(i).mediaMetadata.extras
                ?.getString(Graph.TRACK_EXTRA_KEY)
                ?.let { encoded -> runCatching { Graph.json.decodeFromString<Track>(encoded) }.getOrNull() }
        }

    /** Debounced queue persist; snapshots on the main thread, writes on IO. */
    private fun scheduleQueueSave(player: Player) {
        val tracks = currentTracks(player)
        val index = player.currentMediaItemIndex
        val positionMs = player.currentPosition.coerceAtLeast(0)
        queueSaveJob?.cancel()
        queueSaveJob = serviceScope.launch {
            delay(500)
            runCatching { Graph.queueStore.save(tracks, index, positionMs) }
        }
    }
```

Extend the existing `Player.Listener` (from Tasks 5/6) with:

```kotlin
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) scheduleQueueSave(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) scheduleQueueSave(player)
            }
```

and extend `onMediaItemTransition` (Task 5) with a `scheduleQueueSave(player)`
call after the recordPlay launch.

At the end of `onCreate`, restore the queue (paused — the user decides when
to hit play, and no Tidal resolution happens until they do):

```kotlin
        mainScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { Graph.queueStore.load() }.getOrNull() }
                ?: return@launch
            if (player.mediaItemCount > 0) return@launch // a controller beat us to it
            player.setMediaItems(saved.tracks.map(Graph::mediaItemFor), saved.index, saved.positionMs)
            // No prepare(): stream resolution stays lazy until the user plays.
        }
```

Add a session callback for notification/Bluetooth resumption and register it
— change the session builder to
`MediaSession.Builder(this, player).setSessionActivity(sessionActivity).setCallback(AuxenSessionCallback()).build()`
and add the inner class:

```kotlin
    private inner class AuxenSessionCallback : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaItemsWithStartPosition>()
            serviceScope.launch {
                val saved = runCatching { Graph.queueStore.load() }.getOrNull()
                if (saved == null) {
                    future.setException(UnsupportedOperationException("No saved queue"))
                } else {
                    future.set(
                        MediaItemsWithStartPosition(
                            saved.tracks.map(Graph::mediaItemFor),
                            saved.index,
                            saved.positionMs,
                        ),
                    )
                }
            }
            return future
        }
    }
```

In `onDestroy`, before `serviceScope.cancel()`: flush a final synchronous-ish
save and cancel the main scope:

```kotlin
        mediaSession?.player?.let { p ->
            val tracks = currentTracks(p)
            val index = p.currentMediaItemIndex
            val positionMs = p.currentPosition.coerceAtLeast(0)
            queueSaveJob?.cancel()
            kotlinx.coroutines.runBlocking {
                runCatching { Graph.queueStore.save(tracks, index, positionMs) }
            }
        }
        mainScope.cancel()
```

(Use a top-of-file `import kotlinx.coroutines.runBlocking` instead of the
inline qualified name.)

- [ ] **Step 6: Make togglePlayPause prepare an idle restored player**

In `PlayerViewModel.kt` replace `togglePlayPause`:

```kotlin
    fun togglePlayPause() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.playbackState == Player.STATE_IDLE -> {
                // Restored-from-disk queue: prepare (which lazily resolves
                // the current stream) and then play.
                c.prepare()
                c.play()
            }
            else -> c.play()
        }
    }
```

- [ ] **Step 7: Verify build and full test suite**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
cd /home/mrw1986/Projects/auxen
git add android/app/src/main/kotlin/io/github/auxen/playback/ \
        android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/test/kotlin/io/github/auxen/playback/QueueStateStoreTest.kt
git commit -m "feat(android): queue persistence + playback resumption"
```

---

### Task 8: Docs, full verification, push

**Files:**
- Modify: `docs/plans/2026-07-03-android-app.md` (status line, limitations, roadmap table)

- [ ] **Step 1: Update the design doc**

In `docs/plans/2026-07-03-android-app.md`:
- Change the `**Status:**` line to: `**Status:** Milestones 1–2 implemented in `android/`.`
- In "Known limitations of milestone 1", mark the stream-URL bullet and the
  Room bullet as resolved, e.g. append `— **fixed in milestone 2** (ResolvingDataSource / Room)` to each.
- In the roadmap table, change the milestone 2 row's label to `**2 — done**`,
  and add one sentence below the table: playlist UI intentionally deferred to
  milestone 3 (DB layer + DAOs landed in milestone 2).

- [ ] **Step 2: Full verification**

Run: `cd /home/mrw1986/Projects/auxen/android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew test assembleDebug`
Expected: BUILD SUCCESSFUL, zero test failures. Paste the test summary into the task report.

- [ ] **Step 3: Commit and push**

```bash
cd /home/mrw1986/Projects/auxen
git add docs/plans/2026-07-03-android-app.md
git commit -m "docs(android): mark milestone 2 complete"
git push origin claude/android-app-availability-y6uzb0
```
