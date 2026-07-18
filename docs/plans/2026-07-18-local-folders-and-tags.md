# Local music folders + ID3/tag metadata enrichment — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two additive local-library features the desktop app already has, both **MediaStore-backed (Option A, user-chosen — low risk)**:

1. **Folder selection** — "select which local folders we should pull music from." The user picks a subset of the on-device folders that contain music; only those tracks appear in Library / Home / Search. The **default (nothing selected) is identical to today's behaviour — all music.**
2. **Tag enrichment** — "pull local track info using ID tags." A `MediaMetadataRetriever` pass reads embedded ID3/Vorbis/MP4 tags to fill the metadata fields MediaStore leaves **always null** for local tracks (`genre`, `discNumber`, and — API 31+ — `sampleRateHz`, `bitDepth`). Filling `sampleRateHz`/`bitDepth` is what finally lets a local 24-bit/96 kHz FLAC label as **Hi-Res** (`Track.qualityLabel`, `model/Track.kt:69`), which it can never do today because those fields are null.

**Decided approach (user chose this — do NOT re-open it): MediaStore stays the discovery + identity + playback source.** Local track `sourceId` **stays the MediaStore `_ID` string**; playback stays `ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, sourceId.toLong())` **unchanged**; existing favorites/playlists/play-history keep matching. Neither feature adds a DB column, table, or migration — both operate in the `LocalProvider` read path and the in-memory `localTracks` list. Folder selection is persisted in the **existing `settings` key-value table** (`SettingEntity` / `getSetting` / `setSetting`), the same store the rest of the library uses.

**Architecture:** Additive, mirroring the package's existing "pure core + untested I/O wrapper" split (the precedent every `TidalProvider` file follows, and `ReplayGainTags` / `LocalProvider.replayGainFor` locally: a pure byte/value parser, with the content-URI stream/​retriever I/O in a thin untested wrapper). Three cleanly-separated concerns:

- **Pure folder logic (unit-tested core)** — a new `LocalFolders.kt` with `folderPathOf` (a MediaStore `DATA` path → its parent-directory folder identity), `isFolderIncluded` (the (selected-set, folder) → included? predicate, empty-set = all), `foldersFrom` (a flat `DATA` list → the distinct `MusicFolder`s with per-folder counts), and the settings-value codec `encodeFolders`/`decodeFolders`. No live MediaStore in its tests.
- **Pure tag→field merge (unit-tested core) + the untested retriever wrapper** — `LocalTagEnrichment.kt` with `parseDiscNumber` (`"1/2"` → 1) and `Track.mergedWith(TrackTags)` (fill-only-null merge), and `LocalTagReader.kt`, the `MediaMetadataRetriever` I/O wrapper that opens the track's MediaStore content URI, guards the API-31-only keys behind `Build.VERSION.SDK_INT`, and produces a `TrackTags`. The wrapper is untested (network-wrapper precedent); its pure counterparts are.
- **The read-path + UI wiring** — `LocalProvider` gains a folder filter (applied uniformly to `search`/`allTracks`/`recentlyAdded` via the pure predicate), a `musicFolders()` enumerator, and an `enrich(track)` method; `SettingsScreen` gains a "Music folders" section that opens a folder checklist; `PlayerViewModel` reads/persists the selection, rescans on change, and enriches a track **lazily** (only when its Track-info sheet is opened or it becomes the now-playing track) with an in-memory cache — never the whole library.

## Design decisions (locked for this feature)

- **Folder mechanism = A2 (folder toggle-list from MediaStore), NOT A1 (SAF `OpenDocumentTree`).** Discovery stays in MediaStore, so the self-consistent mechanism is to *filter MediaStore by folder*. A2 queries the distinct folders that contain music and presents them as a checklist; the user includes a subset. Chosen over A1 because: (a) it needs no `takePersistableUriPermission` / URI→path juggling; (b) it shows the user exactly the folders MediaStore actually indexes (A1 lets you browse to a folder whose files may not be MediaStore-indexed, so they'd silently not appear — a confusing mismatch); (c) it answers "which folders" directly. A1 is noted as future work only if bucket-listing proves insufficient.
  - **Folder identity = the parent directory of the `DATA` column (one code path, all APIs 26–35).** `BUCKET_DISPLAY_NAME`/`BUCKET_ID` are API 29+ and `RELATIVE_PATH` is API 29+, which would split the code into a 26–28 branch and a 29+ branch. Instead we read `MediaStore.Audio.Media.DATA` (the absolute file path) as an **opaque grouping key** and take its parent dir — one uniform path across minSdk 26 → 35. `DATA` is deprecated *for direct file access* (scoped storage), but the column value is still returned by queries on every API level, and we never use it to open a file (playback stays on the content URI keyed by `sourceId`). The full path is the identity, so two same-named folders on different volumes never collide. `RELATIVE_PATH` is the "modern" alternative and is noted as deferred — unnecessary while `DATA` is only a grouping key.
- **Pre-API-31 sample rate / bit depth = MMR guarded on API 31+, null below 31 (Option 1). A FLAC/WAV header parse is explicitly deferred.** `MediaMetadataRetriever.METADATA_KEY_SAMPLERATE` and `METADATA_KEY_BITS_PER_SAMPLE` are **API 31+ constants**; minSdk is 26, so both reads are guarded with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` and yield **null below 31 — we do NOT claim MMR gives bit depth pre-31.** `genre` and `discNumber` still fill on **all** APIs (`METADATA_KEY_GENRE`/`METADATA_KEY_DISC_NUMBER` are API 10+). Chosen over a bespoke FLAC-`STREAMINFO`/WAV-`fmt ` header parser because: the on-device checkpoint device (and the user's phone) is API 31+, so Hi-Res labelling works there; the header parser is a whole extra byte-parsing task with its own fixtures, pushing scope past the ~5-task ceiling. The `ReplayGainTags`/`BoundedInputStream` content-URI-stream precedent makes the header fallback a clean follow-up, noted as future work; the pure `mergedWith` merge already tolerates the nulls, so adding it later needs no change to the merge or the UI.
- **Enrichment timing = LAZY (on-demand), with an in-memory cache keyed by `source:sourceId`.** MMR opens + parses each file, so enriching every track on each library load would jank a large library. Instead a track is enriched only when the user actually inspects or plays it: (a) when its **Track-info sheet opens** — the surface that renders "Sample rate" / "Bit depth" (`TrackInfoSheet.kt:118-119`), the guaranteed checkpoint surface; and (b) when it becomes the **now-playing** track — the surface that renders the "Hi-Res" quality badge (`NowPlayingScreen.kt`). Both share one process-lifetime cache (`mutableMapOf<String, Track>`), so MMR runs at most once per track per process; all MMR I/O is on `Dispatchers.IO`. **Enrichment never mutates `localTracks`** — so album grouping / dedup (`data/LibraryGrouping.kt`, which keys on album/albumArtist/trackNumber/discNumber) are untouched and can never shift a track between groups because of an enriched value. A consequence, accepted and documented: the "Hi-Res" badge on *un-inspected* Library list rows (`AuxenTrackRow`) upgrades only after the track is opened/played, not eagerly.
- **Tag→field merge rule = fill-only-null; MediaStore stays authoritative for identity fields.** `mergedWith` fills `genre`, `discNumber`, `sampleRateHz`, `bitDepth`, and `bitrateKbps` (the last only if MediaStore's was absent) from the embedded tags, and changes **nothing else**. `title`/`artist`/`album`/`year`/`trackNumber` keep their MediaStore values even when a tag differs — those are the fields grouping/dedup key on, and MediaStore already indexes them; overriding them from MMR would risk group/dedup drift for zero user-visible gain on the info sheet. This is the low-risk choice consistent with "Option A, playback + grouping untouched."

## Tech Stack

Kotlin, Jetpack Compose (Material 3), AndroidX Room 2.7.1 (`settings` table reused — **no schema change**, DB stays `version = 3`), kotlinx.serialization (`Json`, `ListSerializer(String.serializer())` for the folder codec), `android.media.MediaMetadataRetriever`, `android.provider.MediaStore`, kotlinx.coroutines (`Dispatchers.IO`). Tests: JUnit4 + Robolectric 4.14.1 (only where a test touches Android types; the pure tests are plain JVM), Roborazzi (screen-level goldens). minSdk 26 / targetSdk 35 / compileSdk 35.

## Global Constraints

- **Every gradle command must carry `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`.** The system default JDK breaks this project's Gradle with a cryptic `java.lang.IllegalArgumentException: 25.0.3`. Every `Run:` line below already carries the prefix — do not drop it. Never pipe gradle through `tail`/`head` and read the pipe's exit status; read the real output for `BUILD SUCCESSFUL`.
- **`sourceId` stays the MediaStore `_ID`; playback is UNTOUCHED.** The three `sourceId.toLong()` playback/read sites — `Graph.mediaItemFor` (`AuxenApp.kt:199`), `LocalProvider.getStreamInfo` (`LocalProvider.kt:61`), `LocalProvider.replayGainFor` (`LocalProvider.kt:87`) — are **not modified**. Enrichment fills metadata fields on a `Track` copy; it never touches the id or the stream URI. Confirm with `git diff` at the end.
- **No DB migration.** Slice 2 has already landed (`AuxenDatabase` is `version = 3`, with `favorite_albums`/`favorite_artists`); **this feature adds no entity, column, table, or migration.** Folder selection persists in the existing `settings` table via `LibraryRepository.getSetting`/`setSetting` (`LibraryRepository.kt:124-128`), reusing `SettingEntity` (`db/Entities.kt:115-116`). Do not touch `AuxenDatabase`, `Entities.kt`, or `Daos.kt`.
- **Extract PURE functions and unit-test THOSE — no live MediaStore/MMR in tests.** The tested surface is `folderPathOf`, `folderDisplayName`, `isFolderIncluded`, `foldersFrom`, `encodeFolders`/`decodeFolders`, `parseDiscNumber`, and `Track.mergedWith`, all fed **fabricated inputs** (a list of fake `DATA` strings, a fabricated `TrackTags`). The MediaStore query wrappers (`queryTracks`/`musicFolders`) and the `MediaMetadataRetriever` wrapper (`LocalTagReader.read`) are **untested** — the same discipline the `TidalProvider` network methods follow (their pure `*From`/`*Url` helpers are tested; the suspend methods are not).
- **No new runtime permission is needed — declare none.** Listing folders and running `MediaMetadataRetriever` both read the **same MediaStore audio content URIs** already covered by the existing `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) grant requested in `MainActivity` (`MainActivity.kt:108-133`). `MediaMetadataRetriever.setDataSource(context, uri)` on an already-readable content URI requires no additional permission. **Do not add a manifest permission or a new `RequestMultiplePermissions` entry** — there is nothing new to request.
- **Never re-declare an existing type.** Every new name here was grep-verified to have **zero** existing declarations in `app/src` (`folderPathOf`, `folderDisplayName`, `isFolderIncluded`, `foldersFrom`, `MusicFolder`, `encodeFolders`, `decodeFolders`, `TrackTags`, `parseDiscNumber`, `mergedWith`, `LocalTagReader`, `setIncludedFolders`, `musicFolders`, `enrich`, `enrichedInfoTrack` — see the self-review). Reused, never re-declared: `Track`/`Source` (`model`), `MusicProvider`/`StreamInfo` (`provider/ContentProvider.kt`), `SettingEntity`, `getSetting`/`setSetting`.
- **When new code in an EXISTING file names a type that file does not import, ADD THE IMPORT — Kotlin resolves per-file.** Concretely: `PlayerViewModel.kt` already imports `io.github.auxen.model.Source` and `io.github.auxen.model.Track` but **not** `MusicFolder`/`decodeFolders`/`encodeFolders`, so Task 5/Task 4's VM changes must add `import io.github.auxen.provider.local.MusicFolder`, `import io.github.auxen.provider.local.decodeFolders`, `import io.github.auxen.provider.local.encodeFolders`. `SettingsScreen.kt` needs the imports listed in Task 4's step. The new files in `provider/local/` share LocalProvider's package, so their cross-references need **no** import. The six Track-info-sheet call sites need **no** new import (the guard added there compares `Track` values, which those files already import).
- **All MMR I/O is off the main thread** (`LocalTagReader.read` runs in `withContext(Dispatchers.IO)`), and enrichment is **lazy + cached** (never a whole-library eager pass).
- **The default with no folders selected MUST equal today's behaviour (all music).** `isFolderIncluded(emptySet(), anyFolder)` returns `true`; `LocalProvider.includedFolders` starts `emptySet()`; `decodeFolders(null)` returns `emptySet()`. Every existing caller of `allTracks()`/`recentlyAdded()`/`search()` (including existing tests) keeps working with no signature change.
- **Roborazzi: no new per-component golden — the new Settings row is screen-level.** It lives inside `SettingsContent`, which the existing `settings-screen-{light,dark}.png` goldens already capture (`ComponentScreenshotTest.kt:1050-1066`). Adding the row changes that screen, so those two goldens are **re-recorded** (Task 4), and the `SettingsPreview()` composable gains the two new params so the test still compiles. No new `@Test` / golden file is added.
- **Git: explicit pathspecs only.** Never `git add -A` / `git add .` / `git commit -a` — the working tree carries unrelated untracked files (`.playwright-mcp/`, other `docs/plans/*`) that must never be committed.
- Every code step below shows the actual code. No `TODO` / "add error handling" / "similar to above" placeholders.

---

### Task 1: Pure folder logic + settings codec (`LocalFolders.kt`)

The unit-tested core of the folder feature. A folder's identity is the parent directory of a MediaStore `DATA` path; `isFolderIncluded` is the (selected-set, folder) predicate the read path applies per row (empty set = all folders = today's default); `foldersFrom` turns a flat list of `DATA` strings into the distinct `MusicFolder`s with counts for the settings checklist; `encodeFolders`/`decodeFolders` round-trip the selection through the `settings` table as a JSON string array (garbage → empty set, the safe default).

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/local/LocalFolders.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/local/LocalFoldersTest.kt`

**Interfaces:**
- Produces: `data class MusicFolder(path, displayName, trackCount)`; `fun folderPathOf(String): String`; `fun folderDisplayName(String): String`; `fun isFolderIncluded(Set<String>, String): Boolean`; `fun foldersFrom(List<String>): List<MusicFolder>`; `fun encodeFolders(Set<String>): String`; `fun decodeFolders(String?): Set<String>`.
- Consumed by: `LocalProvider` (Task 3), `PlayerViewModel` (Task 4).

- [ ] **Step 1: Write the failing test**

Create `LocalFoldersTest.kt`:

```kotlin
package io.github.auxen.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure folder logic — the (selected, folder) predicate, folder identity
 * derivation from a MediaStore DATA path, distinct-folder enumeration with
 * counts, and the settings-table JSON codec. No live MediaStore: every input
 * is a fabricated DATA string, exactly as the plan requires (the query wrapper
 * that produces these strings is untested).
 */
class LocalFoldersTest {

    @Test
    fun `folderPathOf returns the parent directory`() {
        assertEquals("/storage/emulated/0/Music/FLAC", folderPathOf("/storage/emulated/0/Music/FLAC/song.flac"))
        assertEquals("/storage/emulated/0/Music", folderPathOf("/storage/emulated/0/Music/track.mp3"))
    }

    @Test
    fun `folderPathOf is blank for a parentless or empty path`() {
        assertEquals("", folderPathOf("song.flac"))
        assertEquals("", folderPathOf(""))
    }

    @Test
    fun `folderDisplayName strips the primary-volume prefix`() {
        assertEquals("Music/FLAC", folderDisplayName("/storage/emulated/0/Music/FLAC"))
        assertEquals("Music", folderDisplayName("/storage/emulated/0/Music"))
        assertEquals("Internal storage", folderDisplayName("/storage/emulated/0"))
        // A non-primary volume (SD card) has no prefix to strip — shown fuller.
        assertEquals("storage/1234-5678/Music", folderDisplayName("/storage/1234-5678/Music"))
    }

    @Test
    fun `isFolderIncluded treats an empty selection as all folders`() {
        assertTrue(isFolderIncluded(emptySet(), "/storage/emulated/0/Music"))
        assertTrue(isFolderIncluded(setOf("/a", "/b"), "/a"))
        assertFalse(isFolderIncluded(setOf("/a", "/b"), "/c"))
        // A blank folder (null DATA) is excluded when a selection is active.
        assertFalse(isFolderIncluded(setOf("/a"), ""))
    }

    @Test
    fun `foldersFrom groups distinct folders with counts sorted by display name`() {
        val folders = foldersFrom(
            listOf(
                "/storage/emulated/0/Music/Rock/a.flac",
                "/storage/emulated/0/Music/Rock/b.flac",
                "/storage/emulated/0/Music/Jazz/c.mp3",
                "bad-path-no-parent",
                "",
            ),
        )
        assertEquals(2, folders.size)
        // Jazz sorts before Rock (case-insensitive display-name order).
        assertEquals(listOf("Jazz", "Rock"), folders.map { it.displayName })
        assertEquals("/storage/emulated/0/Music/Jazz", folders[0].path)
        assertEquals(1, folders[0].trackCount)
        assertEquals(2, folders[1].trackCount)
    }

    @Test
    fun `encodeFolders and decodeFolders round-trip`() {
        val set = setOf("/storage/emulated/0/Music/Rock", "/storage/emulated/0/Music/Jazz")
        assertEquals(set, decodeFolders(encodeFolders(set)))
    }

    @Test
    fun `decodeFolders is empty for null, blank, or garbage`() {
        assertEquals(emptySet<String>(), decodeFolders(null))
        assertEquals(emptySet<String>(), decodeFolders(""))
        assertEquals(emptySet<String>(), decodeFolders("not json"))
        assertEquals(emptySet<String>(), decodeFolders("{\"unexpected\":true}"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*LocalFoldersTest*'`
Expected: FAIL — compilation error: `folderPathOf`, `folderDisplayName`, `isFolderIncluded`, `foldersFrom`, `encodeFolders`, `decodeFolders`, `MusicFolder` are unresolved references (`LocalFolders.kt` does not exist yet).

- [ ] **Step 3: Implement `LocalFolders.kt`**

Create `LocalFolders.kt`:

```kotlin
package io.github.auxen.provider.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A folder on the device that contains music, plus how many tracks it holds —
 * one checklist row in the "Music folders" setting. [path] is the folder's
 * stable identity (the parent directory of a track's MediaStore DATA column);
 * [displayName] is the friendly tail shown to the user.
 */
data class MusicFolder(val path: String, val displayName: String, val trackCount: Int)

/**
 * The folder identity for a MediaStore DATA path — its parent directory. E.g.
 * `/storage/emulated/0/Music/FLAC/song.flac` -> `/storage/emulated/0/Music/FLAC`.
 * The full path is the identity (two same-named folders on different volumes
 * never collide). A parentless or blank path yields "".
 */
fun folderPathOf(dataPath: String): String {
    val trimmed = dataPath.trim()
    val slash = trimmed.lastIndexOf('/')
    return if (slash < 0) "" else trimmed.substring(0, slash)
}

/**
 * A friendly label for a folder identity [path]: the primary external-storage
 * prefix (`/storage/emulated/0`) is stripped so the user sees `Music/FLAC`
 * rather than the full absolute path. The internal-storage root itself shows
 * as "Internal storage"; a non-primary volume (SD card) has no prefix to strip
 * and is shown fuller.
 */
fun folderDisplayName(path: String): String {
    val stripped = path.removePrefix(PRIMARY_VOLUME_PREFIX).trim('/')
    return stripped.ifBlank { "Internal storage" }
}

/**
 * Whether a track in [folder] is included given the user's [included] set. An
 * EMPTY set means "all folders" — the default, byte-identical to today's
 * behaviour (no filtering). This is the pure core the MediaStore read path
 * applies per row.
 */
fun isFolderIncluded(included: Set<String>, folder: String): Boolean =
    included.isEmpty() || folder in included

/**
 * Group a flat list of MediaStore DATA [dataPaths] into the distinct music
 * [MusicFolder]s that contain them, with per-folder track counts, sorted by
 * display name (case-insensitive). Blank/parentless paths are dropped. Pure —
 * the live MediaStore query that produces [dataPaths] is the untested feeder.
 */
fun foldersFrom(dataPaths: List<String>): List<MusicFolder> =
    dataPaths.asSequence()
        .map(::folderPathOf)
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .map { (path, count) -> MusicFolder(path, folderDisplayName(path), count) }
        .sortedBy { it.displayName.lowercase() }

/** Serialize a folder-identity set for the settings table (a JSON string array). */
fun encodeFolders(folders: Set<String>): String =
    FOLDERS_JSON.encodeToString(ListSerializer(String.serializer()), folders.toList())

/**
 * Decode the persisted folder selection. Null/blank/garbage -> empty set (=
 * "all folders", the safe default that never accidentally hides the library).
 */
fun decodeFolders(value: String?): Set<String> {
    if (value.isNullOrBlank()) return emptySet()
    return runCatching {
        FOLDERS_JSON.decodeFromString(ListSerializer(String.serializer()), value).toSet()
    }.getOrDefault(emptySet())
}

private const val PRIMARY_VOLUME_PREFIX = "/storage/emulated/0"
private val FOLDERS_JSON = Json { ignoreUnknownKeys = true }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*LocalFoldersTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/local/LocalFolders.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/local/LocalFoldersTest.kt
git commit -m "feat(android): pure local-folder logic + settings codec

Adds LocalFolders.kt — folderPathOf (DATA path -> folder identity),
isFolderIncluded (empty set = all, today's default), foldersFrom (distinct
folders + counts for the picker), and encodeFolders/decodeFolders for the
settings table. Pure and unit-tested with fabricated DATA inputs; no MediaStore.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 2: Pure tag→field merge (`LocalTagEnrichment.kt`) + the retriever wrapper (`LocalTagReader.kt`)

The unit-tested enrichment core plus its untested I/O wrapper. `parseDiscNumber` handles the `"disc/total"` tag form; `Track.mergedWith` is the **fill-only-null** merge (MediaStore stays authoritative for identity fields). `LocalTagReader` opens the track's MediaStore content URI with a `MediaMetadataRetriever`, guards the API-31-only sample-rate/bit-depth keys behind `Build.VERSION.SDK_INT`, and produces a `TrackTags`.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/local/LocalTagEnrichment.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/provider/local/LocalTagReader.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/provider/local/LocalTagEnrichmentTest.kt`

**Interfaces:**
- Produces: `data class TrackTags(genre, discNumber, sampleRateHz, bitDepth, bitrateKbps)`; `fun parseDiscNumber(String?): Int?`; `fun Track.mergedWith(TrackTags): Track`; `class LocalTagReader(Context)` with `suspend fun read(sourceId: String): TrackTags?`.
- Consumes: `Track` (`model`), `MediaMetadataRetriever`, `MediaStore`, `ContentUris`.
- Consumed by: `LocalProvider.enrich` (Task 3).

- [ ] **Step 1: Write the failing test**

Create `LocalTagEnrichmentTest.kt` (pure only — `LocalTagReader`'s retriever I/O is untested, per the plan):

```kotlin
package io.github.auxen.provider.local

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tag enrichment — disc-number parsing and the fill-only-null merge. The
 * MediaMetadataRetriever wrapper (LocalTagReader) is untested (I/O, the
 * network-wrapper precedent); this covers the logic it feeds.
 */
class LocalTagEnrichmentTest {

    private fun localFlac(
        genre: String? = null,
        discNumber: Int? = null,
        sampleRateHz: Int? = null,
        bitDepth: Int? = null,
        bitrateKbps: Int? = null,
        trackNumber: Int? = 3,
        year: Int? = 2013,
    ) = Track(
        title = "Song",
        artist = "Artist",
        source = Source.LOCAL,
        sourceId = "42",
        album = "Album",
        format = "FLAC",
        genre = genre,
        discNumber = discNumber,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        bitrateKbps = bitrateKbps,
        trackNumber = trackNumber,
        year = year,
    )

    @Test
    fun `parseDiscNumber takes the disc before the slash`() {
        assertEquals(1, parseDiscNumber("1/2"))
        assertEquals(3, parseDiscNumber("3"))
        assertEquals(2, parseDiscNumber(" 2 / 3 "))
    }

    @Test
    fun `parseDiscNumber is null for blank, zero, or garbage`() {
        assertNull(parseDiscNumber(null))
        assertNull(parseDiscNumber(""))
        assertNull(parseDiscNumber("0"))
        assertNull(parseDiscNumber("N/A"))
    }

    @Test
    fun `mergedWith fills the null fields from the tags`() {
        val enriched = localFlac().mergedWith(
            TrackTags(genre = "Synthwave", discNumber = 1, sampleRateHz = 96_000, bitDepth = 24, bitrateKbps = 900),
        )
        assertEquals("Synthwave", enriched.genre)
        assertEquals(1, enriched.discNumber)
        assertEquals(96_000, enriched.sampleRateHz)
        assertEquals(24, enriched.bitDepth)
        assertEquals(900, enriched.bitrateKbps)
    }

    @Test
    fun `mergedWith with filled sample rate and bit depth labels Hi-Res`() {
        // The core motivation: a 24-bit/96kHz FLAC can never be Hi-Res today
        // (both fields null). After enrichment it is.
        assertEquals("FLAC", localFlac().qualityLabel)
        val enriched = localFlac().mergedWith(TrackTags(sampleRateHz = 96_000, bitDepth = 24))
        assertEquals("Hi-Res", enriched.qualityLabel)
        assertEquals(1000, enriched.qualityScore)
    }

    @Test
    fun `mergedWith never overrides an existing value or the identity fields`() {
        val base = localFlac(genre = "Rock", bitrateKbps = 800, trackNumber = 3, year = 2013)
        val enriched = base.mergedWith(
            TrackTags(genre = "Pop", bitrateKbps = 999, discNumber = 2),
        )
        assertEquals("Rock", enriched.genre)      // existing genre kept
        assertEquals(800, enriched.bitrateKbps)   // existing bitrate kept
        assertEquals(2, enriched.discNumber)      // was null -> filled
        assertEquals(3, enriched.trackNumber)     // identity field untouched
        assertEquals(2013, enriched.year)         // identity field untouched
        assertEquals("Song", enriched.title)
        assertEquals("42", enriched.sourceId)     // id never changes
    }

    @Test
    fun `mergedWith with empty tags is a no-op copy`() {
        val base = localFlac(genre = "Rock")
        assertEquals(base, base.mergedWith(TrackTags()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*LocalTagEnrichmentTest*'`
Expected: FAIL — compilation error: `parseDiscNumber`, `mergedWith`, `TrackTags` are unresolved references.

- [ ] **Step 3: Implement `LocalTagEnrichment.kt`**

Create `LocalTagEnrichment.kt`:

```kotlin
package io.github.auxen.provider.local

import io.github.auxen.model.Track

/**
 * The embedded-tag values a [LocalTagReader] MediaMetadataRetriever pass
 * extracts for a local track — the fields the MediaStore projection in
 * [LocalProvider] leaves null. All nullable: a tag may be absent, and
 * [sampleRateHz]/[bitDepth] are ALSO null below API 31, where
 * MediaMetadataRetriever cannot report them (the KEY constants are 31+).
 */
data class TrackTags(
    val genre: String? = null,
    val discNumber: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
)

/**
 * Parse a disc-number tag, often the `"disc/total"` form (`"1/2"`) — the tail
 * is dropped. A bare `"3"` yields 3; blank/zero/garbage yields null. Pure.
 */
fun parseDiscNumber(raw: String?): Int? =
    raw?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

/**
 * Merge [tags] into this track, FILLING ONLY fields the MediaStore read left
 * null. MediaStore stays authoritative for identity-ish fields (title/artist/
 * album/year/trackNumber), which album grouping and dedup key on, so tag
 * enrichment never shifts a track between groups. Concretely: genre, disc
 * number, sample rate, bit depth, and (only when absent) bitrate are filled
 * from [tags]; everything else is unchanged. Pure — the unit-tested core.
 *
 * qualityLabel/qualityScore recompute for free off the filled sampleRateHz/
 * bitDepth, so a 24-bit/96kHz FLAC becomes "Hi-Res" once enriched (API 31+).
 */
fun Track.mergedWith(tags: TrackTags): Track = copy(
    genre = genre ?: tags.genre,
    discNumber = discNumber ?: tags.discNumber,
    sampleRateHz = sampleRateHz ?: tags.sampleRateHz,
    bitDepth = bitDepth ?: tags.bitDepth,
    bitrateKbps = bitrateKbps ?: tags.bitrateKbps,
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*LocalTagEnrichmentTest*'`
Expected: PASS.

- [ ] **Step 5: Implement the untested retriever wrapper `LocalTagReader.kt`**

Create `LocalTagReader.kt` (I/O only — no unit test, by precedent; guards the API-31 keys):

```kotlin
package io.github.auxen.provider.local

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads embedded ID3/Vorbis/MP4 tags for a local track via
 * [MediaMetadataRetriever], filling the metadata [LocalProvider]'s MediaStore
 * projection leaves null. Opens the SAME MediaStore content URI playback uses
 * (`ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, sourceId.toLong())`), so
 * no file path or extra permission is needed — the sourceId identity is
 * unchanged, and READ_MEDIA_AUDIO already covers this URI.
 *
 * This I/O wrapper is deliberately untested (the network-wrapper precedent in
 * this package); its pure counterparts are [parseDiscNumber] and
 * [Track.mergedWith]. All work is off the main thread; any failure (unreadable
 * file, retriever throw) degrades to null — enrichment is best-effort.
 */
class LocalTagReader(private val context: Context) {

    suspend fun read(sourceId: String): TrackTags? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            sourceId.toLong(),
        )
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                TrackTags(
                    genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                        ?.trim()?.takeIf { it.isNotEmpty() },
                    discNumber = parseDiscNumber(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                    ),
                    sampleRateHz = sampleRateHz(retriever),
                    bitDepth = bitDepth(retriever),
                    bitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()?.let { it / 1000 }?.takeIf { it > 0 },
                )
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    /**
     * Sample rate in Hz. METADATA_KEY_SAMPLERATE is an API 31+ constant, so
     * this returns null below 31 (honest: MediaMetadataRetriever cannot report
     * it there — genre/discNumber still fill on all APIs).
     */
    private fun sampleRateHz(retriever: MediaMetadataRetriever): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            ?.toIntOrNull()?.takeIf { it > 0 }
    }

    /** Bits per sample. METADATA_KEY_BITS_PER_SAMPLE is API 31+; null below 31. */
    private fun bitDepth(retriever: MediaMetadataRetriever): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
            ?.toIntOrNull()?.takeIf { it > 0 }
    }
}
```

- [ ] **Step 6: Build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*LocalTagEnrichmentTest*' --tests '*LocalFoldersTest*' compileDebugKotlin`
Expected: PASS; `LocalTagReader.kt` compiles (the API-31 keys resolve at compileSdk 35 and are runtime-guarded).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/local/LocalTagEnrichment.kt \
        android/app/src/main/kotlin/io/github/auxen/provider/local/LocalTagReader.kt \
        android/app/src/test/kotlin/io/github/auxen/provider/local/LocalTagEnrichmentTest.kt
git commit -m "feat(android): local tag enrichment — pure merge + MMR reader

Adds LocalTagEnrichment.kt (parseDiscNumber + fill-only-null Track.mergedWith,
unit-tested) and LocalTagReader.kt (MediaMetadataRetriever over the track's
MediaStore content URI, untested I/O). Sample rate / bit depth are guarded on
API 31+ (the KEY constants are 31+; null below); genre/discNumber fill on all
APIs. Fills the fields that let a local 24/96 FLAC label as Hi-Res.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 3: `LocalProvider` — folder filter, folder enumeration, enrichment entry point

Wire the pure layers into the read path. `LocalProvider` gains a `includedFolders` config field (set by the VM; empty = all) applied uniformly inside `queryTracks` — so `search`/`allTracks`/`recentlyAdded` all honour the selection with **no signature change** (they route through `queryTracks`, and `search` is a `MusicProvider` interface method whose signature must not change). It also gains `musicFolders()` (the picker's feed) and `enrich(track)` (the lazy entry point delegating to `LocalTagReader` + `mergedWith`). No cross-file imports needed — everything is in package `io.github.auxen.provider.local`.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/local/LocalProvider.kt`

**Interfaces:**
- Consumes: `isFolderIncluded`/`folderPathOf`/`foldersFrom` (Task 1), `LocalTagReader`/`Track.mergedWith` (Task 2); the existing `queryTracks`, `MediaStore`, `Source`, `Track`.
- Produces: `LocalProvider.setIncludedFolders(Set<String>)`; `LocalProvider.musicFolders(): List<MusicFolder>`; `LocalProvider.enrich(Track): Track`. (No new pure logic — this task's gate is `assembleDebug` + Task 1/2's green tests.)

- [ ] **Step 1: Add the folder-selection field, the retriever, and the setter**

In `LocalProvider.kt`, add the fields at the top of the class body (right after `class LocalProvider(private val context: Context) : MusicProvider {`):

```kotlin
    private val tagReader = LocalTagReader(context)

    /**
     * The user's chosen music folders (folder identities = MediaStore DATA
     * parent dirs). EMPTY = all folders — today's default. Set by
     * PlayerViewModel from the persisted selection before each library read,
     * and applied uniformly by [queryTracks] so search/allTracks/recentlyAdded
     * all honour it. Plain config, not logic — the logic is the pure
     * [isFolderIncluded]; @Volatile because the setter (main thread) and the
     * reads ([queryTracks] on Dispatchers.IO) run on different threads.
     */
    @Volatile
    private var includedFolders: Set<String> = emptySet()

    /** Replace the active folder filter. Empty set restores "all folders". */
    fun setIncludedFolders(folders: Set<String>) {
        includedFolders = folders
    }
```

- [ ] **Step 2: Add `DATA` to the projection and apply the filter in the cursor loop**

In `queryTracks`, add `MediaStore.Audio.Media.DATA` to the `projection` array (after `MediaStore.Audio.Media.ALBUM_ID,`):

```kotlin
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
```

Add its column index beside the others (after `val albumIdCol = ...`):

```kotlin
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
```

Apply the filter as the **first** statement inside the `while (cursor.moveToNext() && tracks.size < limit) {` loop body (before `val albumId = ...`):

```kotlin
            while (cursor.moveToNext() && tracks.size < limit) {
                val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                if (!isFolderIncluded(includedFolders, folderPathOf(data.orEmpty()))) continue
                val albumId = cursor.getLong(albumIdCol)
```

(`continue` re-evaluates the `while` guard, so a skipped row never consumes a `limit` slot and the pre-`continue` behaviour is unchanged when `includedFolders` is empty.)

- [ ] **Step 3: Add `musicFolders()` and `enrich()`**

After `recentlyAdded(...)` (and before `getStreamInfo`), add:

```kotlin
    /**
     * The distinct on-device folders that contain music, with per-folder track
     * counts — the source for the "Music folders" setting checklist. Ignores
     * the active [includedFolders] filter (the user must see ALL folders to
     * choose among them). The grouping/counting is the pure [foldersFrom]; this
     * method is just its MediaStore feeder (untested, like the query wrappers).
     */
    suspend fun musicFolders(): List<MusicFolder> = withContext(Dispatchers.IO) {
        val paths = mutableListOf<String>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DATA),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { cursor ->
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            if (dataCol >= 0) {
                while (cursor.moveToNext()) {
                    cursor.getString(dataCol)?.let { paths += it }
                }
            }
        }
        foldersFrom(paths)
    }

    /**
     * Enrich [track] with its embedded tags (genre, disc number, and — API 31+
     * — sample rate / bit depth), filling only the fields the MediaStore read
     * left null (the pure [Track.mergedWith]). Returns [track] unchanged for a
     * non-local track, a read failure, or when the tags add nothing. Off the
     * main thread via [LocalTagReader]. Lazy by design — callers enrich a track
     * only when its details are shown / it starts playing, never the whole
     * library (see PlayerViewModel).
     */
    suspend fun enrich(track: Track): Track {
        if (track.source != Source.LOCAL) return track
        val tags = tagReader.read(track.sourceId) ?: return track
        return track.mergedWith(tags)
    }
```

- [ ] **Step 4: Build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest --tests '*LocalFoldersTest*' --tests '*LocalTagEnrichmentTest*' assembleDebug`
Expected: PASS; APK builds. (No new pure logic in this task; the folder filter + `musicFolders`/`enrich` are MediaStore/MMR wrappers, covered by Task 1/2's pure tests.)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/provider/local/LocalProvider.kt
git commit -m "feat(android): LocalProvider folder filter + folder list + enrich()

Adds a DATA column to the query, an includedFolders config field applied
uniformly in queryTracks (empty = all, unchanged default) so search/allTracks/
recentlyAdded honour the selection, a musicFolders() enumerator for the picker,
and enrich(track) delegating to LocalTagReader + the pure mergedWith. sourceId
and the playback URI are untouched.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 4: Settings UI — "Music folders" section, persistence, and rescan

Add a "Music folders" `SettingsSectionCard` whose row shows the current selection summary and opens a folder-checklist dialog. The row's display state (a summary string + an `onClick`) is passed into the stateless `SettingsContent` — keeping the "stateless content composable" split the file's KDoc emphasizes and the screen golden deterministic — while the dialog itself is hosted in the stateful `SettingsScreen` shell. `PlayerViewModel` exposes the folder list + persisted selection, persists a change via `setSetting`, pushes it onto `LocalProvider`, and rescans (`loadLibrary` + `refreshHome`).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/SettingsScreen.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/test/kotlin/io/github/auxen/ui/screenshots/ComponentScreenshotTest.kt` (add the two new params to `SettingsPreview`)
- Re-record: `android/app/src/test/screenshots/settings-screen-light.png`, `.../settings-screen-dark.png`

**Interfaces:**
- Consumes: `Graph.local.musicFolders`/`setIncludedFolders` (Task 3), `Graph.library.getSetting`/`setSetting`, `encodeFolders`/`decodeFolders`/`MusicFolder` (Task 1).
- Produces: `PlayerViewModel.musicFolders: StateFlow<List<MusicFolder>>`; `PlayerViewModel.selectedFolders: StateFlow<Set<String>>`; `loadMusicFolders()`; `setMusicFolders(Set<String>)`; `SettingsContent(..., musicFoldersSummary, onOpenMusicFolders)`.

- [ ] **Step 1: PlayerViewModel — folder state, seeding, persist + rescan**

Add the three provider-local imports to `PlayerViewModel.kt` (alongside the existing `io.github.auxen.model.*` / `io.github.auxen.provider.tidal.*` imports):

```kotlin
import io.github.auxen.provider.local.MusicFolder
import io.github.auxen.provider.local.decodeFolders
import io.github.auxen.provider.local.encodeFolders
```

Add the folder state fields (near the other `MutableStateFlow` library fields, e.g. beside `val localTracks`):

```kotlin
    /** The device's music folders for the "Music folders" picker; loaded lazily when the dialog opens. */
    val musicFolders = MutableStateFlow<List<MusicFolder>>(emptyList())

    private val _selectedFolders = MutableStateFlow<Set<String>>(emptySet())

    /** The persisted folder-selection identities (empty = all folders). */
    val selectedFolders: StateFlow<Set<String>> = _selectedFolders
```

Replace `loadLibrary()` so it applies the persisted selection before querying, and add a shared `applyFolderSelection()` helper:

```kotlin
    fun loadLibrary() {
        viewModelScope.launch {
            libraryLoading.value = true
            try {
                applyFolderSelection()
                runCatching { Graph.local.allTracks() }.onSuccess { localTracks.value = it }
            } finally {
                libraryLoading.value = false
            }
        }
    }

    /**
     * Read the persisted folder selection into [_selectedFolders] and push it
     * onto the local provider, so the next query (allTracks/recentlyAdded/
     * search) filters to it. Called before each library/home load and once at
     * init, so a filter set on a prior launch is honoured from the first read.
     */
    private suspend fun applyFolderSelection() {
        val folders = decodeFolders(runCatching { Graph.library.getSetting(KEY_MUSIC_FOLDERS) }.getOrNull())
        _selectedFolders.value = folders
        Graph.local.setIncludedFolders(folders)
    }
```

Add `applyFolderSelection()` to `refreshHome()` before the `recentlyAdded()` read:

```kotlin
    fun refreshHome() {
        viewModelScope.launch {
            homeLoading.value = true
            try {
                applyFolderSelection()
                loadRecentlyPlayed()
                runCatching { Graph.local.recentlyAdded() }.onSuccess { recentlyAdded.value = it }
            } finally {
                homeLoading.value = false
            }
        }
    }
```

Add the picker load + persist/rescan methods (e.g. beside `setSourcePriority`):

```kotlin
    /** Populate [musicFolders] for the picker dialog. Idempotent; safe to call on every open. */
    fun loadMusicFolders() {
        viewModelScope.launch {
            runCatching { Graph.local.musicFolders() }.onSuccess { musicFolders.value = it }
        }
    }

    /**
     * Persist a new folder selection ([folders]; empty = all), push it onto the
     * local provider, and rescan Library + Home so the change is visible
     * immediately. Search re-reads the same provider field on its next query.
     */
    fun setMusicFolders(folders: Set<String>) {
        viewModelScope.launch {
            runCatching { Graph.library.setSetting(KEY_MUSIC_FOLDERS, encodeFolders(folders)) }
            _selectedFolders.value = folders
            Graph.local.setIncludedFolders(folders)
            loadLibrary()
            refreshHome()
        }
    }
```

Seed the selection at construction — add one line inside the existing `init { … }` block (after `restoreLibraryState()`):

```kotlin
        viewModelScope.launch { applyFolderSelection() }
```

Add a `private companion object` holding the settings key (the class has none today; place it at the end of the class body):

```kotlin
    private companion object {
        const val KEY_MUSIC_FOLDERS = "local_music_folders"
    }
```

- [ ] **Step 2: SettingsScreen — the section, row, and picker dialog**

Add these imports to `SettingsScreen.kt`:

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
// (androidx.compose.runtime.getValue is already imported in SettingsScreen.kt — do not re-add)
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.auxen.provider.local.MusicFolder
```

Replace the `SettingsScreen` shell so it collects the folder state and hosts the dialog:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val themeMode by viewModel.themeMode.collectAsState()
    val sourcePriority by viewModel.sourcePriority.collectAsState()
    val bitPerfect by viewModel.bitPerfect.collectAsState()
    val folders by viewModel.musicFolders.collectAsState()
    val selected by viewModel.selectedFolders.collectAsState()
    var pickerOpen by remember { mutableStateOf(false) }

    SettingsContent(
        themeMode = themeMode,
        onThemeModeChange = viewModel::setThemeMode,
        sourcePriority = sourcePriority,
        onSourcePriorityChange = viewModel::setSourcePriority,
        bitPerfect = bitPerfect,
        onBitPerfectChange = viewModel::setBitPerfect,
        musicFoldersSummary = musicFoldersSummary(selected.size),
        onOpenMusicFolders = { viewModel.loadMusicFolders(); pickerOpen = true },
        modifier = modifier,
    )

    if (pickerOpen) {
        MusicFoldersDialog(
            folders = folders,
            selected = selected,
            onConfirm = { chosen -> viewModel.setMusicFolders(chosen); pickerOpen = false },
            onDismiss = { pickerOpen = false },
        )
    }
}
```

Add the two params to `SettingsContent` (in the signature, with defaults so the golden preview and any other caller compile) and render the new section after Appearance:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    sourcePriority: SourcePriority,
    onSourcePriorityChange: (SourcePriority) -> Unit,
    bitPerfect: Boolean = false,
    onBitPerfectChange: (Boolean) -> Unit = {},
    musicFoldersSummary: String = "",
    onOpenMusicFolders: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
```

Add the section inside the `Column`, after the Appearance `SettingsSectionCard { … }` closes and before the Playback card:

```kotlin
        SettingsSectionCard(title = stringResource(R.string.settings_music_folders_title)) {
            MusicFoldersRow(summary = musicFoldersSummary, onClick = onOpenMusicFolders)
        }
```

Add the row composable, the summary helper, and the dialog (beside the other private composables, e.g. after `BitPerfectRow`):

```kotlin
/**
 * The "Music folders" row: a label + the current-selection summary on the left,
 * a chevron on the right; the whole row opens the folder picker. Only the row
 * (not the dialog) is captured by the settings-screen golden, so its render is
 * deterministic from the [summary] param alone.
 */
@Composable
private fun MusicFoldersRow(summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_music_folders_label), style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "All folders" when nothing is chosen (the default), else the chosen count. */
@Composable
private fun musicFoldersSummary(count: Int): String =
    if (count == 0) stringResource(R.string.settings_music_folders_all)
    else stringResource(R.string.settings_music_folders_count, count)

/**
 * The folder checklist. Works on a local copy committed only on Save, so
 * cancelling leaves the persisted selection untouched. An empty device folder
 * list (permission not yet granted / no music) shows a notice instead of rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicFoldersDialog(
    folders: List<MusicFolder>,
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val working = remember(folders, selected) {
        mutableStateListOf<String>().apply { addAll(selected) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_music_folders_title)) },
        text = {
            if (folders.isEmpty()) {
                Text(stringResource(R.string.settings_music_folders_empty))
            } else {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        stringResource(R.string.settings_music_folders_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    folders.forEach { folder ->
                        val checked = folder.path in working
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { on ->
                                        if (on) working.add(folder.path) else working.remove(folder.path)
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(folder.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.settings_music_folders_track_count, folder.trackCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(working.toSet()) }) {
                Text(stringResource(R.string.settings_music_folders_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_music_folders_cancel)) }
        },
    )
}
```

- [ ] **Step 3: Add the string resources**

In `app/src/main/res/values/strings.xml`, add (near the other `settings_*` strings):

```xml
    <string name="settings_music_folders_title">Music folders</string>
    <string name="settings_music_folders_label">Included folders</string>
    <string name="settings_music_folders_all">All folders</string>
    <string name="settings_music_folders_count">%1$d folder(s) selected</string>
    <string name="settings_music_folders_hint">Only music in the selected folders will appear. Select none to include all.</string>
    <string name="settings_music_folders_empty">No music folders found.</string>
    <string name="settings_music_folders_track_count">%1$d tracks</string>
    <string name="settings_music_folders_save">Save</string>
    <string name="settings_music_folders_cancel">Cancel</string>
```

- [ ] **Step 4: Keep the settings-screen golden compiling, then re-record it**

The screen golden feeds `SettingsContent` directly, so update `SettingsPreview()` in `ComponentScreenshotTest.kt` to pass the two new params (a non-default "3 folders selected" summary, so a regression that dropped the row would show in the diff):

```kotlin
    @Composable
    private fun SettingsPreview() {
        SettingsContent(
            themeMode = ThemeMode.DARK,
            onThemeModeChange = {},
            sourcePriority = SourcePriority.PREFER_TIDAL,
            onSourcePriorityChange = {},
            bitPerfect = true,
            onBitPerfectChange = {},
            musicFoldersSummary = "3 folder(s) selected",
            onOpenMusicFolders = {},
        )
    }
```

Re-record the two settings goldens (the screen changed — the new row is now part of `SettingsContent`):

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:recordRoborazziDebug --tests '*ComponentScreenshotTest.settingsScreen_light' --tests '*ComponentScreenshotTest.settingsScreen_dark'`
Then verify the whole screenshot + unit suite is green against the re-recorded goldens:
Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest assembleDebug`
Expected: PASS; `settings-screen-light.png` / `settings-screen-dark.png` now show the "Music folders" row; APK builds.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/SettingsScreen.kt \
        android/app/src/main/res/values/strings.xml \
        android/app/src/test/kotlin/io/github/auxen/ui/screenshots/ComponentScreenshotTest.kt \
        android/app/src/test/screenshots/settings-screen-light.png \
        android/app/src/test/screenshots/settings-screen-dark.png
git commit -m "feat(android): Music folders setting — checklist, persistence, rescan

Adds a Music folders section to Settings: a row (summary + chevron) opening a
folder checklist sourced from LocalProvider.musicFolders(). The selection
persists in the settings table (encode/decodeFolders) and rescans Library +
Home on change; the default (none selected) is all music. Re-records the
screen-level settings goldens for the new row.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 5: Lazy tag enrichment wiring (Track-info sheet + now-playing) + on-device checkpoint

Wire lazy enrichment into the two surfaces that render the enriched fields. `loadTrackDetail(track)` — already called from every Track-info sheet's `LaunchedEffect(track)` — also fires a local enrichment whose result flows through a new `enrichedInfoTrack` state; the six sheet call sites pass the enriched copy (guarded to the same track) to `TrackInfoSheet`. The now-playing track is enriched when it becomes current, so its "Hi-Res" quality badge upgrades. One process-lifetime cache keyed by `source:sourceId` means MMR runs at most once per track.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/AlbumDetailScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/SearchScreen.kt`

**Interfaces:**
- Consumes: `Graph.local.enrich` (Task 3), the existing `loadTrackDetail`/`clearTrackDetail`/`trackDetail`, the two `currentTrack = Graph.trackFor(...)` sites (`PlayerViewModel.kt:603, 609`).
- Produces: `PlayerViewModel.enrichedInfoTrack: StateFlow<Track?>`; the enriched-track pass-through at all six `TrackInfoSheet` call sites.

- [ ] **Step 1: PlayerViewModel — the enriched-info flow, cache, and now-playing enrichment**

Add the state + cache (beside `trackDetail`):

```kotlin
    /**
     * The enriched-on-demand copy of the track whose info sheet is open — its
     * genre / disc number / sample rate / bit depth filled from embedded tags.
     * Null until enrichment lands (the sheet shows the un-enriched track's
     * MediaStore values meanwhile, then upgrades). Cleared on dismiss.
     */
    val enrichedInfoTrack = MutableStateFlow<Track?>(null)

    /**
     * Process-lifetime cache of enriched local tracks, keyed by "source:sourceId",
     * shared by the info sheet and now-playing so MediaMetadataRetriever runs at
     * most once per track per process.
     */
    private val enrichedCache = mutableMapOf<String, Track>()
```

Fold enrichment into `loadTrackDetail` and clear it in `clearTrackDetail` (keep the existing Tidal `trackDetail` behaviour intact — only add the two enrichment lines and the local enrichment launcher):

```kotlin
    fun loadTrackDetail(track: Track) {
        trackDetail.value = null
        // Seed from cache (may be null) + kick off local enrichment. Both lines run
        // BEFORE the isTidal guard, so a LOCAL track still enriches even though it
        // skips the Tidal credits/lyrics fetch below.
        enrichedInfoTrack.value = enrichedCache[trackKey(track)]
        enrichLocalTrackForInfo(track)
        if (!track.isTidal) return // PRESERVED guard — a LOCAL track must NOT hit Graph.tidal.trackDetail (its sourceId is a MediaStore id, not a Tidal track id).
        val token = ++trackDetailToken
        trackDetailJob?.cancel()
        trackDetailJob = viewModelScope.launch {
            trackDetailLoading.value = true
            try {
                val detail = Graph.tidal.trackDetail(track)
                if (trackDetailToken == token && detail != null) trackDetail.value = detail
            } finally {
                if (trackDetailToken == token) trackDetailLoading.value = false
            }
        }
    }

    fun clearTrackDetail() {
        trackDetail.value = null
        enrichedInfoTrack.value = null
    }

    /**
     * Enrich a LOCAL info-sheet track off the main thread; publish to
     * [enrichedInfoTrack] + cache.
     *
     * LOAD-BEARING INVARIANT: enrichment publishes ONLY to `enrichedInfoTrack` /
     * `currentTrack` / `enrichedCache` — it must NEVER write `localTracks`. The
     * merge fills `discNumber`, which is a grouping/sort key
     * (`LibraryGrouping.groupTracksByDisc`), so folding enriched rows back into
     * `localTracks` would silently regroup the library (e.g. flip a single-disc
     * album to multi-disc). Keep enrichment out of the grouping source.
     */
    private fun enrichLocalTrackForInfo(track: Track) {
        if (track.source != Source.LOCAL) return
        viewModelScope.launch {
            val enriched = runCatching { Graph.local.enrich(track) }.getOrDefault(track)
            enrichedCache[trackKey(enriched)] = enriched
            enrichedInfoTrack.value = enriched
        }
    }

    private fun trackKey(track: Track): String = "${track.source.name}:${track.sourceId}"
```

> Note: reconcile with the current source (`PlayerViewModel.kt:406-420`). **KEEP the existing `if (!track.isTidal) return` guard (currently at `:408`) — do NOT drop it** — and place the two enrichment lines (`enrichedInfoTrack.value = …` seed + `enrichLocalTrackForInfo(track)`) BEFORE it, exactly as shown, so a LOCAL track enriches yet still skips the Tidal fetch. Preserve the `trackDetailToken`/`trackDetailJob`/`trackDetailLoading` dance verbatim. `clearTrackDetail` is a one-liner today (`:422`, `trackDetail.value = null`) — add only the `enrichedInfoTrack.value = null` line.

Enrich the now-playing track. After **each** of the two `currentTrack = Graph.trackFor(...)` assignments (`PlayerViewModel.kt:608` `Graph.trackFor(metadata)` and `:614` `Graph.trackFor(mediaMetadata)`), add a call:

```kotlin
                currentTrack = Graph.trackFor(metadata)
                enrichCurrentTrackIfLocal()
```

(and the same after the `:609` assignment, matching its surrounding variable name). Add the helper:

```kotlin
    /**
     * Enrich the now-playing track (if local) so its "Hi-Res" quality badge
     * upgrades once tags are read. Uses the cache first; applies the result
     * only if it is still the current track (the user may have skipped).
     */
    private fun enrichCurrentTrackIfLocal() {
        val track = currentTrack ?: return
        if (track.source != Source.LOCAL) return
        val key = trackKey(track)
        enrichedCache[key]?.let { currentTrack = it; return }
        viewModelScope.launch {
            val enriched = runCatching { Graph.local.enrich(track) }.getOrDefault(track)
            enrichedCache[key] = enriched
            if (currentTrack?.let(::trackKey) == key) currentTrack = enriched
        }
    }
```

- [ ] **Step 2: The six Track-info-sheet call sites**

All six sites are **byte-identical** today (verified). In each of `LibraryScreen.kt`, `CollectionScreen.kt`, `AlbumDetailScreen.kt`, `ArtistDetailScreen.kt`, `HomeScreen.kt`, `SearchScreen.kt`, replace:

```kotlin
    infoTrack?.let { track ->
        val detail by viewModel.trackDetail.collectAsState()
        val detailLoading by viewModel.trackDetailLoading.collectAsState()
        LaunchedEffect(track) { viewModel.loadTrackDetail(track) }
        TrackInfoSheet(
            track = track,
            detail = detail,
            loading = detailLoading,
            onDismiss = { infoTrack = null; viewModel.clearTrackDetail() },
        )
    }
```

with:

```kotlin
    infoTrack?.let { track ->
        val detail by viewModel.trackDetail.collectAsState()
        val detailLoading by viewModel.trackDetailLoading.collectAsState()
        val enriched by viewModel.enrichedInfoTrack.collectAsState()
        LaunchedEffect(track) { viewModel.loadTrackDetail(track) }
        TrackInfoSheet(
            // The enriched copy for THIS track (guarded against a stale value
            // from a previous open); falls back to the un-enriched track until
            // MediaMetadataRetriever lands.
            track = enriched?.takeIf { it.source == track.source && it.sourceId == track.sourceId } ?: track,
            detail = detail,
            loading = detailLoading,
            onDismiss = { infoTrack = null; viewModel.clearTrackDetail() },
        )
    }
```

No new imports are needed in these files: `collectAsState`/`getValue` are already imported (used for `detail`), and the guard compares `Track` values (already imported). Apply the identical edit in all six.

- [ ] **Step 3: Build**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew testDebugUnitTest assembleDebug`
Expected: PASS; APK builds. (No new unit-tested logic; enrichment plumbing is VM/UI wiring over Task 2's tested `mergedWith`.)

- [ ] **Step 4: On-device checkpoint (gates completion)**

Build the debug APK locally (Tidal credentials are **not** required — this feature is local-only), install on a device, and verify. Prefer an **API 31+** device so sample rate / bit depth populate (see the pre-31 caveat below).

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:assembleDebug`
Then install the APK (e.g. `adb install -r app/build/outputs/apk/debug/app-debug.apk`) and check:

1. **Folder filter narrows the library.** Settings → Music folders → the dialog lists the device's music folders with per-folder track counts. Select a subset → Save. Library shows **only** tracks from those folders; Home "Recently added" likewise; Search results respect it.
2. **Default restores everything.** Re-open the picker, deselect all → Save. The full library returns (empty selection = all).
3. **Sample rate / bit depth fill.** Open a local FLAC's row → "Track info" → the **Sample rate** and **Bit depth** rows are populated (they were absent before); **Genre** and disc number fill too.
4. **Hi-Res label.** Play a local 24-bit/96 kHz FLAC → Now Playing shows the **"Hi-Res"** quality badge (it showed "FLAC" before enrichment).

Pre-31 caveat to confirm honestly: on an **API < 31** device, steps 1–2 and Genre/disc# in step 3 still work, but Sample rate / Bit depth stay blank and the badge stays "FLAC" (the `METADATA_KEY_SAMPLERATE`/`_BITS_PER_SAMPLE` constants are 31+). This is the documented, intended behaviour, not a bug.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/AlbumDetailScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/SearchScreen.kt
git commit -m "feat(android): lazy tag enrichment on the info sheet + now playing

Enriches a local track on demand — when its Track-info sheet opens (fills the
Sample rate / Bit depth / Genre rows) and when it becomes now-playing (upgrades
the Hi-Res badge) — via a shared process cache so MediaMetadataRetriever runs at
most once per track. Never enriches the whole library; localTracks + album
grouping are untouched.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

## Self-review checklist

- **No re-declared types.** grep-verified zero existing declarations in `app/src` for every new name: `MusicFolder`, `folderPathOf`, `folderDisplayName`, `isFolderIncluded`, `foldersFrom`, `encodeFolders`, `decodeFolders`, `TrackTags`, `parseDiscNumber`, `mergedWith`, `LocalTagReader`, `setIncludedFolders`, `musicFolders`, `enrich`, `enrichedInfoTrack`, `KEY_MUSIC_FOLDERS`. Reused, never re-declared: `Track`/`Source`, `MusicProvider`/`StreamInfo`, `SettingEntity`, `getSetting`/`setSetting`, `SettingsContent`/`SettingsSectionCard`.
- **`sourceId` = MediaStore `_ID`, playback untouched.** No edit to `Graph.mediaItemFor` (`AuxenApp.kt:199`), `LocalProvider.getStreamInfo` (`:61`), or `replayGainFor` (`:87`); `enrich` returns a `copy()` with the same `sourceId`.
- **No DB migration / schema change.** `AuxenDatabase` (`version = 3`), `Entities.kt`, `Daos.kt` are not modified; the selection lives in the existing `settings` table via `getSetting`/`setSetting`.
- **Pure functions unit-tested; I/O untested.** Tested: `LocalFoldersTest` (7 cases) + `LocalTagEnrichmentTest` (6 cases). Untested (I/O, by precedent): `queryTracks` filter, `musicFolders`, `LocalTagReader.read`.
- **Default = all music.** `decodeFolders(null)` = `emptySet()`, `includedFolders` starts empty, `isFolderIncluded(emptySet(), …)` = true; existing `allTracks()`/`recentlyAdded()`/`search()` callers unchanged (no signature change).
- **Pre-31 honesty.** `sampleRateHz`/`bitDepth` guarded on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`; null below 31; genre/discNumber fill on all APIs. No claim of MMR bit depth pre-31.
- **MMR off the main thread + lazy + cached.** `LocalTagReader.read` in `withContext(Dispatchers.IO)`; enrichment only on info-sheet open / now-playing; one `enrichedCache`.
- **No new runtime permission.** Folder listing + MMR read the same audio content URIs already covered by `READ_MEDIA_AUDIO`; the manifest and `MainActivity` permission flow are not touched.
- **Imports added where a new type is named in an existing file.** `PlayerViewModel` gets `MusicFolder`/`decodeFolders`/`encodeFolders`; `SettingsScreen` gets its Compose + `MusicFolder` imports; the six sheet files need none.
- **Roborazzi.** No new golden; the screen-level `settings-screen-{light,dark}` goldens are re-recorded and `SettingsPreview` updated so the suite compiles.
- **Every gradle line carries `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`; git uses explicit pathspecs.**
- **Scope = 5 tasks** (Task 1 pure folders, Task 2 pure enrichment + reader, Task 3 provider wiring, Task 4 settings UI, Task 5 lazy enrichment wiring + checkpoint) — at the ~5-task ceiling, no overflow.
