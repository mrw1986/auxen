# Android Predictive Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While a queue plays, cache the upcoming Tidal songs as whole files on the phone so playback keeps going through dead zones, skip uncached songs when offline, and auto-resume when service comes back within 30 minutes.

**Architecture:** A new `playback/cache/` package owns everything. Pure functions plan the window, pick evictions, parse the DASH manifest, find skip targets and run the auto-resume state machine. `TrackCacheStore` keeps one folder per song under `noBackupFilesDir/track_cache/` with a Room index. `TrackDownloader` fetches BTS or DASH with OkHttp. `CacheAheadScheduler` runs in `PlaybackService` only while playing. `TidalUriResolver` checks the store before the network, and an `OfflineSkipper` plus `AutoResumeController` handle the offline behavior inside the service.

**Tech Stack:** Kotlin, Media3 1.5.1 (exoplayer, exoplayer-dash, session), Room 2.7.1 + KSP, DataStore preferences 1.1.1, OkHttp 4.12.0, kotlinx coroutines 1.9.0, Compose Material 3, JUnit 4 + Robolectric 4.14.1. New test-only deps: `kotlinx-coroutines-test` 1.9.0 (Task 10) and `media3-test-utils` + `media3-test-utils-robolectric` 1.5.1 (Task 1).

**Spec:** `docs/superpowers/specs/2026-10-31-android-predictive-cache-design.md`

## Global Constraints

- Media3 stays at **1.5.1** unless Task 1 picks fallback (c). Then stop and re-plan with the user before Task 4.
- A song counts as cached only when the whole file (or the whole DASH folder) is on disk and its row is written. Never a partial.
- Eviction never touches a song inside the current window, and never the one playing.
- Never let caching push free device storage below **1 GB** (`DEVICE_HEADROOM_BYTES = 1_000_000_000L`), whatever the limit.
- Storage root: `context.noBackupFilesDir/track_cache/`. App-private. Not in MediaStore.
- Defaults: network mode **Wi-Fi and mobile data**, limit **2 GB**. Presets: **500 MB / 1 GB / 2 GB / 5 GB / 10 GB** (decimal: `500_000_000L`, `1_000_000_000L`, `2_000_000_000L`, `5_000_000_000L`, `10_000_000_000L`).
- Caching runs only while playback is active (`isPlaying`, or `playWhenReady` while `STATE_BUFFERING`). On pause the in-flight download finishes, nothing new starts. No WorkManager, no new foreground service type.
- Wi-Fi only = active network has `NET_CAPABILITY_NOT_METERED`. Online = `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`.
- Auto-resume window: **30 minutes** (`AUTO_RESUME_WINDOW_MILLIS = 1_800_000L`), measured on `SystemClock.elapsedRealtime()`.
- Exact UI copy: banner `Offline, nothing cached ahead`; readout `1.2 GB used · 14 songs` (middle dot U+00B7); section title `Offline cache`.
- Leaf track `MediaItem`s keep `mediaId = "SOURCE:sourceId"` and the stable `auxen://tidal/<id>` URI from `Graph.mediaItemFor`. Don't change either.
- The DASH swap (`swapDashCopies`), the expired-URL recovery (401/403/410) and Android Auto browsing must keep working for uncached songs.
- Never log or store `accessToken`/`refreshToken`. Scrub segment URL tokens from any committed fixture.
- `PlaybackService.kt` is 1373 lines. New logic goes in `playback/cache/` files. The service only gets thin wiring.
- Gradle: `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 /home/mrw1986/Projects/auxen/android/gradlew -p /home/mrw1986/Projects/auxen/android <task>`. No `cd`. Absolute paths everywhere.
- Unit tests: `:app:testDebugUnitTest --tests '<fqcn>'`. Run the full `:app:testDebugUnitTest :app:verifyRoborazziDebug` before committing any task that modifies an existing file. Known trap: adding test methods to `ComponentScreenshotTest` can shift the `action-sheet-*` goldens slightly. If that happens, check it's deterministic before re-recording.
- Queue item swaps (`replaceMediaItem`) fire `onTimelineChanged(PLAYLIST_CHANGED)` and `onMediaItemTransition`. Anything that reacts to "the user changed the queue" or "the user moved to another song" must compare mediaIds, not react to the callback alone. Otherwise the cache's own swaps cancel auto-resume and clear the offline notice.
- Git: `git -C /home/mrw1986/Projects/auxen ...` with explicit pathspecs only. Never `git add -A`/`.`/`-a`. Don't touch `.serena/project.yml`, `.playwright-mcp/`, `android/.kotlin/`, `docs/plans/2026-07-13-*.md`. Never commit `~/.gradle/gradle.properties`.
- Commit messages: conventional commits, ending with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_0148cep9fsLV6a3QQKyJnHMd
  ```
- APKs: build locally (CI has no Tidal creds), name them `auxen-<sha>-predictive-cache.apk`, verify About shows `0.1.0+<sha>` before any on-device test.

## Review Focus

1. **The same song queued twice** (an album plus a playlist that shares a track, or repeat-all). Expect one download and one row, and every non-playing copy in the queue switched to the cached path. Tests: Task 3 `cacheCandidates dedupes a track queued twice`, Task 9 `reconcile updates every non-current copy of a track queued twice`.
2. **Clear cache or logout while a cached song is playing or queued.** Expect queued items that point at deleted files to go back to `auxen://`, and the playing song to keep its position and stream instead. Test: Task 9 `queueItemUpdate restores a local item whose cache entry is gone`.
3. **Clear or logout while a download is in flight.** Expect the late commit to be thrown away, not to bring back a row after the user cleared. Test: Task 5 `a staging dir created before clear is discarded on commit`.
4. **Network flips to metered mid-download in Wi-Fi only mode.** Expect the in-flight download to stop and its staging folder to be deleted. Test: Task 10 `switching to metered in Wi-Fi only cancels the in-flight download`.
5. **Cached files deleted outside the app** (storage cleaner, manual delete). Expect the row to be dropped and the song to stream normally. Test: Task 5 `cachedPlayback drops the row when the entry file is missing`.

---

## File Structure

New, all under `android/app/src/main/kotlin/io/github/auxen/playback/cache/`:
- `CacheModels.kt`: `CacheKind`, `CachedPlayback`, `DownloadResult`, `CacheCandidate`, `CacheSnapshot`, `CacheIndex` interface.
- `CacheWindowPlanner.kt`: pure play order, size estimate, candidates, capacity, window, evictions.
- `DashManifest.kt`: pure MPD parse to segment list, MPD localizing, data-URI decode.
- `TrackCacheStore.kt`: folders, Room index, snapshot, commit, evict, clear, reconcile.
- `TrackDownloader.kt`: BTS and DASH download with OkHttp.
- `NetworkMonitor.kt`: `NetworkState`, `mayCache`, `networkStateOf`, `ConnectivityNetworkMonitor`.
- `CacheSettings.kt`: `CacheNetworkMode`, `CacheSettings`, presets, `CacheSettingsStore`, readout and label formatters.
- `CacheAheadScheduler.kt`: the download loop.
- `QueueCacheSync.kt`: `queueItemUpdate`, `reconcileQueueWithCache`.
- `OfflineSkipper.kt`: `PlayerQueue`, `PlayerQueueAdapter`, `findSkipTarget`, `isPlayableOffline`, `OfflineSkipper`.
- `OfflineStatus.kt`: in-process signal the service sets and `PlayerViewModel` reads.
- `AutoResume.kt`: pure state machine + `AutoResumeController`.

New UI: `android/app/src/main/kotlin/io/github/auxen/ui/OfflineCacheSection.kt`.

Modified: `db/Entities.kt`, `db/Daos.kt`, `db/AuxenDatabase.kt`, `AuxenApp.kt` (`Graph`), `playback/TrackResolver.kt`, `playback/TidalUriResolver.kt`, `playback/RecoverableErrors.kt`, `playback/PlaybackService.kt`, `playback/browse/AuxenBrowseTree.kt`, `ui/PlayerViewModel.kt`, `ui/SettingsScreen.kt`, `ui/components/AuxenTrackRow.kt` and its call sites, `AndroidManifest.xml`, `res/values/strings.xml`, `gradle/libs.versions.toml`, `app/build.gradle.kts`.

Tests mirror the source paths under `android/app/src/test/kotlin/io/github/auxen/`. Fixtures go in `android/app/src/test/resources/dash/`.

---

## Task 1: DASH spike and storage decision

Goal: answer three questions before any cache code exists. (1) What does a real Tidal Hi-Res MPD look like, and do its segment URLs carry tokens that expire before a whole song downloads? (2) Does a local DASH folder (local MPD + init + numbered segments) play and seek in ExoPlayer 1.5.1? (3) Does one concatenated fMP4 play and seek as progressive? The answer picks option 1, option 2 or fallback (c).

**Files:**
- Create (scratch, not committed): `/tmp/claude-1000/-home-mrw1986-Projects-auxen/9f2e2dc1-08c5-469e-a77f-da518bac08ad/scratchpad/dash_spike.py`
- Create: `android/app/src/test/resources/dash/tidal_hires_manifest.mpd` (real structure, tokens scrubbed)
- Create: `android/app/src/test/resources/dash/local_flac/` (synthetic fixture: `manifest.mpd`, `init.mp4`, `seg_<n>.mp4`)
- Create: `android/app/src/test/kotlin/io/github/auxen/playback/cache/LocalDashPlaybackTest.kt`
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts` (test deps)
- Modify: the spec, adding a `## Spike result` section

**Interfaces:**
- Produces: the decision (option 1, 2 or c) and two fixtures that Tasks 4 and 6 test against. The rest of this plan assumes **option 1 (local DASH)**. If the spike picks option 2, Task 4 drops `localizeMpd`, Task 6 writes a single `audio.mp4` for DASH and Task 9 treats DASH like PROGRESSIVE (no `CachedDashStreamException`). If it picks (c), stop after Step 7 and bring it to the user.

- [ ] **Step 1: Fetch a real Hi-Res manifest**

Write `dash_spike.py` (run with `python3 -I`) using the desktop session at `~/.local/share/auxen/tidal_session.json` and tidalapi 0.8.11 (already installed). Load the session, pick a Hi-Res track from the user's favorites (`session.user.favorites.tracks()`, first one whose `audio_quality` is `HI_RES_LOSSLESS`), call `track.get_stream()` and `stream.get_stream_manifest()`, and save the raw MPD to the scratchpad. Print: `mimeType`, `codecs`, `timescale`, `startNumber`, the `initialization` and `media` templates, the number of `<S>` elements and the total segment count (sum of `1 + r` per `<S>`), and the query-string keys on the segment URLs. Don't print tokens or the session file.

- [ ] **Step 2: Check token lifetime**

In the same script, fetch the first and last segment URLs immediately (expect 200), then again after 30 minutes (schedule the re-check, don't block). Record whether they still return 200. Also record how long one full-song download takes on this connection.

- [ ] **Step 3: Save the scrubbed fixture**

Copy the MPD to `android/app/src/test/resources/dash/tidal_hires_manifest.mpd`, replacing every URL's host with `cdn.example` and every query string with `?token=x`. Keep the `SegmentTemplate`/`SegmentTimeline` structure exact. Verify: `grep -c 'token=x' <fixture>` > 0 and `grep -E 'Policy|Signature|hdnts|exp=' <fixture>` prints nothing.

- [ ] **Step 4: Build the synthetic local-DASH fixture**

Generate a 6-second stereo 24-bit/96 kHz sine as FLAC-in-fMP4 DASH with ~2 s segments, mirroring Tidal's layout (single representation, `SegmentTemplate` + `SegmentTimeline`, `$Number$`). Start from:

```bash
ffmpeg -f lavfi -i "sine=frequency=440:sample_rate=96000:duration=6" -ac 2 -c:a flac -sample_fmt s32 -strict -2 \
  -f dash -seg_duration 2 -use_template 1 -use_timeline 1 \
  -init_seg_name init.mp4 -media_seg_name 'seg_$Number$.mp4' \
  <scratch>/local_flac/manifest.mpd
```

Then edit the generated `manifest.mpd` so it has only the elements and attributes the Tidal fixture has (drop ffmpeg extras like `ProgramInformation`). Copy the folder to `android/app/src/test/resources/dash/local_flac/`. Expect under 2 MB total.

- [ ] **Step 5: Add the Media3 test utils**

In `libs.versions.toml`: `media3-test-utils = { group = "androidx.media3", name = "media3-test-utils", version.ref = "media3" }` and `media3-test-utils-robolectric = { group = "androidx.media3", name = "media3-test-utils-robolectric", version.ref = "media3" }`. In `build.gradle.kts`: `testImplementation(libs.media3.test.utils)` and `testImplementation(libs.media3.test.utils.robolectric)`.

- [ ] **Step 6: Write `LocalDashPlaybackTest`**

Robolectric. Copy the `local_flac` resource folder into a temp dir (so the URIs are real `file://` paths). Build an `ExoPlayer` with `FakeClock(true)` and `CapturingRenderersFactory`, set a `MediaItem` with the local MPD's file URI and `MimeTypes.APPLICATION_MPD`, prepare and use `TestPlayerRunHelper`:

```kotlin
@Test fun `local DASH folder prepares with the full duration`()      // duration in 5_800..6_200 ms
@Test fun `local DASH folder seeks to the middle`()                  // seekTo(4_000); READY; currentPosition in 4_000..4_300
@Test fun `concatenated fMP4 reports whether it is seekable`()       // init + segments joined, played as progressive;
                                                                     // assert and record isCurrentMediaItemSeekable
```

The third test documents option 2, so assert what you observe and name it in the spike result. If Robolectric can't run the FLAC renderer at all, assert on `STATE_READY`, `duration` and the post-seek position only, which come from the extractors and DASH source.

- [ ] **Step 7: Run it and record the decision**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.playback.cache.LocalDashPlaybackTest'`
Expected: the first two PASS for option 1. Add `## Spike result` to the spec with the date, the MPD facts from Step 1, the token result from Step 2, the three test results and the decision:
- Option 1 if local DASH prepares and seeks, and tokens outlive a full-song download (or a re-resolve on 401/403 resumes cleanly, which Task 6 implements anyway).
- Option 2 only if option 1 fails and the concatenated file is seekable.
- (c) if neither plays and seeks. Stop and bring the result to the user.

- [ ] **Step 8: Commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/gradle/libs.versions.toml android/app/build.gradle.kts \
  android/app/src/test/resources/dash android/app/src/test/kotlin/io/github/auxen/playback/cache/LocalDashPlaybackTest.kt \
  docs/superpowers/specs/2026-10-31-android-predictive-cache-design.md
git -C /home/mrw1986/Projects/auxen commit -m "test(android): spike local DASH playback for the track cache"
```

---

## Task 2: Cache index table and migration

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/Entities.kt` (add entity)
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/Daos.kt` (add DAO)
- Modify: `android/app/src/main/kotlin/io/github/auxen/db/AuxenDatabase.kt` (entity list, `version = 4`, `cachedTrackDao()`, `MIGRATION_3_4`, `addMigrations`)
- Test: `android/app/src/test/kotlin/io/github/auxen/db/MigrationTest.kt`, `android/app/src/test/kotlin/io/github/auxen/db/DaoTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Entity(tableName = "cached_tracks")
  data class CachedTrackEntity(
      @PrimaryKey @ColumnInfo(name = "tidal_track_id") val tidalTrackId: String,
      @ColumnInfo(name = "kind") val kind: String,              // CacheKind.name: "PROGRESSIVE" | "DASH"
      @ColumnInfo(name = "entry_file") val entryFile: String,   // file name inside the track folder
      @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
      @ColumnInfo(name = "mime_type") val mimeType: String?,
      @ColumnInfo(name = "sample_rate_hz") val sampleRateHz: Int?,
      @ColumnInfo(name = "bit_depth") val bitDepth: Int?,
      @ColumnInfo(name = "track_gain_db") val trackGainDb: Double?,
      @ColumnInfo(name = "album_gain_db") val albumGainDb: Double?,
      @ColumnInfo(name = "cached_at_millis") val cachedAtMillis: Long,
      @ColumnInfo(name = "last_played_at_millis") val lastPlayedAtMillis: Long?,
      @ColumnInfo(name = "track_json") val trackJson: String,   // Graph.json-encoded Track
  )
  @Dao interface CachedTrackDao {
      @Upsert suspend fun upsert(row: CachedTrackEntity)
      @Query("SELECT * FROM cached_tracks") suspend fun all(): List<CachedTrackEntity>
      @Query("DELETE FROM cached_tracks WHERE tidal_track_id IN (:ids)") suspend fun delete(ids: List<String>)
      @Query("DELETE FROM cached_tracks") suspend fun deleteAll()
      @Query("UPDATE cached_tracks SET last_played_at_millis = :at WHERE tidal_track_id = :id") suspend fun markPlayed(id: String, at: Long)
  }
  ```
  `AuxenDatabase.cachedTrackDao(): CachedTrackDao`, `AuxenDatabase.MIGRATION_3_4`.

- [ ] **Step 1: Write the failing tests**

In `MigrationTest`, following the existing 2→3 test: build the DB, seed a favorite, drop `cached_tracks` with raw SQL, set `raw.version = 3`, reopen with `AuxenDatabase.build(context)`.

```kotlin
@Test fun `migrates 3 to 4 adding cached_tracks and keeps existing rows`()
// after reopen: favorites still has the seeded row; cachedTrackDao().all() is empty; upsert + all() round-trips
```

In `DaoTest`:

```kotlin
@Test fun `cached track upsert replaces by id`()            // upsert twice with different size; all() has 1 row, new size
@Test fun `cached track markPlayed sets last played`()      // markPlayed("7", 123L); row.lastPlayedAtMillis == 123L
@Test fun `cached track delete by ids and deleteAll`()      // delete(listOf("1")) leaves "2"; deleteAll() leaves none
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.db.MigrationTest' --tests 'io.github.auxen.db.DaoTest'`
Expected: compile failure, `CachedTrackEntity` unresolved.

- [ ] **Step 3: Add the entity, DAO and `MIGRATION_3_4`**

`MIGRATION_3_4` is raw `CREATE TABLE IF NOT EXISTS \`cached_tracks\` (...)` with backticked columns, matching Room's generated schema exactly (TEXT NOT NULL for the PK, `kind`, `entry_file`, `track_json`; INTEGER NOT NULL for `size_bytes`, `cached_at_millis`; nullable INTEGER/REAL/TEXT for the rest; `PRIMARY KEY(\`tidal_track_id\`)`). Add it to `build()`.

- [ ] **Step 4: Run them and confirm they pass**

Same command. Expected: PASS.

- [ ] **Step 5: Run the full suite, then commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/db android/app/src/test/kotlin/io/github/auxen/db
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): add the cached_tracks index table"
```

---

## Task 3: Window planner and eviction policy (pure)

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/CacheModels.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/CacheWindowPlanner.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/cache/CacheWindowPlannerTest.kt`

**Interfaces:**
- Consumes: `io.github.auxen.model.Track`, `io.github.auxen.db.CachedTrackEntity`.
- Produces (in `CacheModels.kt`):
  ```kotlin
  enum class CacheKind { PROGRESSIVE, DASH }
  data class CacheCandidate(val trackId: String, val track: Track, val estimatedBytes: Long)
  data class CacheSnapshot(val entries: Map<String, CachedTrackEntity> = emptyMap()) {
      val ids: Set<String> get() = entries.keys
      val totalBytes: Long get() = entries.values.sumOf { it.sizeBytes }
      val count: Int get() = entries.size
  }
  ```
- Produces (in `CacheWindowPlanner.kt`):
  ```kotlin
  const val DEVICE_HEADROOM_BYTES = 1_000_000_000L
  fun playOrder(start: Int, next: (Int) -> Int): List<Int>     // next returns C.INDEX_UNSET (-1) at the end
  fun estimateBytes(track: Track): Long
  fun cacheCandidates(order: List<Int>, mediaIdAt: (Int) -> String, trackAt: (Int) -> Track?): List<CacheCandidate>
  fun capacityBytes(limitBytes: Long, usedBytes: Long, freeDeviceBytes: Long, headroomBytes: Long = DEVICE_HEADROOM_BYTES): Long
  data class CacheWindow(val trackIds: List<String>, val toDownload: List<CacheCandidate>)
  fun planWindow(candidates: List<CacheCandidate>, cached: Map<String, CachedTrackEntity>, capacityBytes: Long): CacheWindow
  fun pickEvictions(cached: Collection<CachedTrackEntity>, protectedIds: Set<String>, bytesToFree: Long): List<String>
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
// playOrder
@Test fun `play order walks forward to the end`()            // start 2, next = { if (it < 4) it + 1 else -1 } -> [2, 3, 4]
@Test fun `play order stops when repeat-all loops`()         // start 1, next = { (it + 1) % 3 } -> [1, 2, 0]
@Test fun `play order follows a shuffled next`()             // start 0, next = mapOf(0 to 2, 2 to 1, 1 to -1) -> [0, 2, 1]
@Test fun `play order with a self loop is just the start`()  // start 3, next = { it } -> [3]

// estimateBytes: bytes = durationSeconds * bitDepth * sampleRateHz * 2 * 0.7 / 8
@Test fun `estimate uses duration and quality`()             // 240 s, 16-bit, 44_100 Hz -> 29_635_200L
@Test fun `estimate falls back to 24-bit 96 kHz and 300 s`() // no duration, no quality -> 120_960_000L

// cacheCandidates
@Test fun `candidates keep Tidal items in play order`()      // ids "TIDAL:1", "LOCAL:9", "TIDAL:2" -> ["1", "2"]
@Test fun `cacheCandidates dedupes a track queued twice`()   // "TIDAL:1", "TIDAL:2", "TIDAL:1" -> ["1", "2"]
@Test fun `candidates skip items with no track`()            // trackAt returns null for one index -> it's dropped

// capacityBytes = min(limit, used + max(0, free - headroom))
@Test fun `capacity is the limit when the device has room`()       // (2e9, 0, 10e9) -> 2_000_000_000L
@Test fun `capacity shrinks to keep 1 GB free`()                   // (2e9, 300e6, 1.5e9) -> 800_000_000L
@Test fun `capacity never drops below what is already cached`()    // (2e9, 300e6, 0.5e9) -> 300_000_000L

// planWindow: walk candidates, size = cached row's sizeBytes ?: estimatedBytes, stop at the first that doesn't fit
@Test fun `window fills in order until the next song does not fit`()       // sizes 100, 100, 100; capacity 250 -> ids [a, b]
@Test fun `window counts real size for cached songs`()                     // a cached at 50 (est 100); capacity 150 -> [a, b]
@Test fun `window stops at the first song that does not fit even if a later one would`()  // 100, 300, 10; cap 150 -> [a]
@Test fun `window is empty when the current song alone is too big`()       // 500; cap 400 -> []
@Test fun `toDownload is the uncached window songs in order`()             // a cached, b and c not -> toDownload [b, c]

// pickEvictions: played rows oldest lastPlayed first, then never-played rows oldest cachedAt first
@Test fun `evictions never touch protected ids`()
@Test fun `evictions take oldest played first and never-played last`()     // played@10, played@5, never(cached@1) -> [p5, p10, n1]
@Test fun `evictions stop once enough bytes are freed`()                   // sizes 100 each; bytesToFree 150 -> 2 ids
@Test fun `no evictions when nothing needs freeing`()                      // bytesToFree 0 -> []
@Test fun `evictions return every evictable row when that is not enough`() // returns all non-protected
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.playback.cache.CacheWindowPlannerTest'`
Expected: compile failure.

- [ ] **Step 3: Implement `CacheModels.kt` and `CacheWindowPlanner.kt`**

All pure, no Android types. `cacheCandidates` parses `"TIDAL:<id>"` mediaIds only and calls `estimateBytes` on the track. `playOrder` stops on `-1` or the first repeated index.

- [ ] **Step 4: Run them and confirm they pass**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/playback/cache/CacheModels.kt \
  android/app/src/main/kotlin/io/github/auxen/playback/cache/CacheWindowPlanner.kt \
  android/app/src/test/kotlin/io/github/auxen/playback/cache/CacheWindowPlannerTest.kt
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): plan the cache-ahead window and evictions"
```

---

## Task 4: DASH manifest parsing and localizing (pure)

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/DashManifest.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/cache/DashManifestTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  const val INIT_FILE = "init.mp4"
  const val MANIFEST_FILE = "manifest.mpd"
  fun segmentFileName(number: Int): String                     // "seg_$number.mp4"
  data class DashSegmentList(val initUrl: String, val media: List<Pair<Int, String>>)  // (segment number, URL)
  fun decodeDataUriMpd(uri: String): String                    // "data:application/dash+xml;base64,..." -> XML; throws IOException otherwise
  fun parseSegmentList(mpdXml: String): DashSegmentList
  fun localizeMpd(mpdXml: String): String                      // initialization="init.mp4", media="seg_$Number$.mp4"
  ```

- [ ] **Step 1: Write the failing tests**

Load both fixtures from Task 1 as resources (`javaClass.classLoader!!.getResource("dash/...")`).

```kotlin
@Test fun `parses the Tidal fixture init and segment count`()  // initUrl ends with the fixture's init path; media.size == count from the spike result
@Test fun `segment numbers start at startNumber and are contiguous`()
@Test fun `segment URLs substitute Number`()                   // no "$Number$" left in any URL
@Test fun `S elements with r expand to 1 + r segments`()       // inline MPD: <S d="10" r="2"/><S d="5"/> -> 4 segments
@Test fun `missing startNumber defaults to 1`()                // inline MPD without startNumber -> first number 1
@Test fun `negative r is rejected`()                           // r="-1" -> IllegalArgumentException
@Test fun `localized MPD points at local file names and keeps the timeline`()  // reparse of localizeMpd(fixture): initUrl == "init.mp4", first media URL == "seg_<start>.mp4", same segment count
@Test fun `decodes a base64 data URI`()                        // round-trips a small XML string
@Test fun `rejects a non-data URI`()                           // "https://x" -> IOException
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.playback.cache.DashManifestTest'`
Expected: compile failure.

- [ ] **Step 3: Implement `DashManifest.kt`**

Parse with `javax.xml.parsers.DocumentBuilderFactory` (namespace aware, works on JVM and Android). Read the first `SegmentTemplate` under the single `Representation`. Media segment count is the sum of `1 + (r ?: 0)` per `<S>`. Decode with `java.util.Base64` (minSdk is 26). `localizeMpd` rewrites only the two template attributes and serializes with `javax.xml.transform`.

- [ ] **Step 4: Run them and confirm they pass**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/playback/cache/DashManifest.kt \
  android/app/src/test/kotlin/io/github/auxen/playback/cache/DashManifestTest.kt
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): parse and localize Tidal DASH manifests"
```

---

## Task 5: TrackCacheStore

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/cache/CacheModels.kt` (add `CachedPlayback`, `DownloadResult`, `CacheIndex`)
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/TrackCacheStore.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/cache/TrackCacheStoreTest.kt`

**Interfaces:**
- Consumes: `CachedTrackDao` (Task 2), `CacheSnapshot`, `CacheKind` (Task 3), `MANIFEST_FILE` (Task 4), `io.github.auxen.provider.StreamInfo`, `Graph.json`.
- Produces (in `CacheModels.kt`):
  ```kotlin
  data class CachedPlayback(val kind: CacheKind, val uri: String)   // "file://<abs path>" of the entry file
  data class DownloadResult(val kind: CacheKind, val entryFile: String, val sizeBytes: Long, val mimeType: String?, val streamInfo: StreamInfo)
  interface CacheIndex {
      val snapshot: StateFlow<CacheSnapshot>
      fun staging(trackId: String): File
      suspend fun commit(trackId: String, staging: File, result: DownloadResult, track: Track): Boolean
      suspend fun discard(staging: File)
      suspend fun evict(trackIds: Collection<String>)
  }
  ```
- Produces (in `TrackCacheStore.kt`):
  ```kotlin
  class TrackCacheStore(root: File, dao: CachedTrackDao, clock: () -> Long = System::currentTimeMillis) : CacheIndex {
      val rootUri: String                                   // "file://<root abs path>/"
      suspend fun load()                                    // reconcile, then fill snapshot; call once at startup
      fun cachedPlayback(trackId: String): CachedPlayback?  // sync; null and drops the row if the entry file is gone
      fun streamInfoFor(trackId: String): StreamInfo?       // sync; uri = cachedPlayback uri, gains/quality from the row
      suspend fun clear()
      suspend fun markPlayed(trackId: String)
      fun freeDeviceBytes(): Long                           // root.usableSpace
  }
  ```

Layout: `root/<trackId>/<entryFile>` for both kinds. Staging: `root/.tmp/<trackId>-<generation>-<nanoTime>/`. `commit` renames staging to `root/<trackId>` (replacing any old folder), upserts the row (`cachedAtMillis = clock()`, `lastPlayedAtMillis = null`, `trackJson = Graph.json.encodeToString(track)`) and updates the snapshot. It returns false and deletes the staging folder if the staging folder's generation is older than the last `clear()`.

- [ ] **Step 1: Write the failing tests**

Robolectric, in-memory Room (`allowMainThreadQueries`), `TemporaryFolder` for `root`, a fake clock.

```kotlin
@Test fun `commit moves staging into place and records the row`()       // folder root/7 exists with entry file; snapshot.ids == {"7"}; row sizeBytes matches
@Test fun `cachedPlayback returns a file uri for the entry file`()      // uri == "file://" + root/7/audio absolute path
@Test fun `streamInfoFor carries the row's gains and quality`()
@Test fun `uncached ids return null`()
@Test fun `evict deletes folders and rows`()
@Test fun `clear deletes everything and empties the snapshot`()
@Test fun `a staging dir created before clear is discarded on commit`() // staging("7"); clear(); commit(...) == false; no row, no folder, staging gone
@Test fun `markPlayed updates the row and snapshot`()
@Test fun `load drops temp folders, orphan folders and rows with missing files`()  // seed .tmp/x, root/99 (no row), row 5 (no folder) -> all gone; valid row 7 kept
@Test fun `cachedPlayback drops the row when the entry file is missing`()          // commit, delete file behind the store's back -> null; snapshot no longer has it
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.playback.cache.TrackCacheStoreTest'`
Expected: compile failure.

- [ ] **Step 3: Implement the store**

Keep the snapshot in a `MutableStateFlow<CacheSnapshot>`. The sync methods read it (the resolver runs on loader threads). Disk and DAO work happen in the suspend methods. Dropping a missing-file row from `cachedPlayback` updates the snapshot right away and deletes the row on a store-owned `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.

- [ ] **Step 4: Run them and confirm they pass**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/playback/cache/CacheModels.kt \
  android/app/src/main/kotlin/io/github/auxen/playback/cache/TrackCacheStore.kt \
  android/app/src/test/kotlin/io/github/auxen/playback/cache/TrackCacheStoreTest.kt
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): store whole cached tracks with a Room index"
```

---

## Task 6: TrackDownloader

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/TrackDownloader.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/cache/TrackDownloaderTest.kt`

**Interfaces:**
- Consumes: `DownloadResult`, `CacheKind` (Task 5), `decodeDataUriMpd`, `parseSegmentList`, `localizeMpd`, `INIT_FILE`, `MANIFEST_FILE`, `segmentFileName` (Task 4), `StreamInfo`.
- Produces:
  ```kotlin
  const val PROGRESSIVE_FILE = "audio"
  class TrackDownloader(
      private val http: OkHttpClient,
      private val resolve: suspend (trackId: String, fresh: Boolean) -> StreamInfo,
  ) {
      suspend fun download(trackId: String, into: File): DownloadResult
  }
  ```
  BTS (`uri` not `data:`): GET into `into/audio`; `mimeType = streamInfo.mimeType ?: response Content-Type`. DASH: decode, parse, GET init then every media segment in order into `INIT_FILE` / `segmentFileName(n)`, write `localizeMpd(xml)` to `MANIFEST_FILE`. `sizeBytes` is the sum of all written files. Any non-2xx throws `IOException`. On 401/403/410 mid-download, call `resolve(trackId, fresh = true)` once, re-parse, and continue from the failing segment. A second failure throws. Checks `coroutineContext.ensureActive()` between segments and between 64 KB chunks, and cancels the OkHttp `Call` on cancellation.

- [ ] **Step 1: Write the failing tests**

Fake the network with an OkHttp application `Interceptor` that serves canned bodies by URL and records requests (no MockWebServer in the deps). Use the Task 1 Tidal fixture, base64'd into a `data:` URI, for DASH.

```kotlin
@Test fun `BTS download writes one file and reports its size`()        // kind PROGRESSIVE, entryFile "audio", size == body length
@Test fun `BTS mime type falls back to Content-Type`()
@Test fun `DASH download writes init, every segment and a local manifest`()  // kind DASH, entryFile "manifest.mpd"; init.mp4 + seg_<n>.mp4 for every n; size == sum
@Test fun `DASH requests segments in order`()
@Test fun `a 403 mid-DASH re-resolves once and resumes at the failing segment`()   // resolve called with fresh=true once; no segment fetched twice except the failed one
@Test fun `a second 403 fails the download`()
@Test fun `a 500 fails the download`()
@Test fun `cancelling stops further requests`()                       // cancel after the 2nd segment; no 3rd request recorded
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.playback.cache.TrackDownloaderTest'`
Expected: compile failure.

- [ ] **Step 3: Implement `TrackDownloader`**

- [ ] **Step 4: Run them and confirm they pass**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/playback/cache/TrackDownloader.kt \
  android/app/src/test/kotlin/io/github/auxen/playback/cache/TrackDownloaderTest.kt
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): download whole Tidal tracks for the cache"
```

---

## Task 7: Network monitor and cache settings

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/NetworkMonitor.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/CacheSettings.kt`
- Modify: `android/app/src/main/AndroidManifest.xml` (add `ACCESS_NETWORK_STATE`)
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/cache/NetworkMonitorTest.kt`, `android/app/src/test/kotlin/io/github/auxen/playback/cache/CacheSettingsTest.kt`, `android/app/src/test/kotlin/io/github/auxen/ManifestPermissionsTest.kt`

**Interfaces:**
- Produces (in `NetworkMonitor.kt`):
  ```kotlin
  data class NetworkState(val online: Boolean, val unmetered: Boolean)
  fun mayCache(mode: CacheNetworkMode, net: NetworkState): Boolean
  fun networkStateOf(caps: NetworkCapabilities?): NetworkState   // online = INTERNET && VALIDATED; unmetered = NOT_METERED
  class ConnectivityNetworkMonitor(context: Context) { val state: StateFlow<NetworkState> }  // registerDefaultNetworkCallback
  ```
- Produces (in `CacheSettings.kt`):
  ```kotlin
  enum class CacheNetworkMode { WIFI_AND_MOBILE, WIFI_ONLY, OFF }
  const val DEFAULT_CACHE_LIMIT_BYTES = 2_000_000_000L
  val CACHE_LIMIT_PRESETS: List<Long>   // 500_000_000, 1_000_000_000, 2_000_000_000, 5_000_000_000, 10_000_000_000
  data class CacheSettings(val mode: CacheNetworkMode = CacheNetworkMode.WIFI_AND_MOBILE, val limitBytes: Long = DEFAULT_CACHE_LIMIT_BYTES)
  internal val Context.trackCacheDataStore: DataStore<Preferences>   // preferencesDataStore(name = "track_cache")
  class CacheSettingsStore(private val dataStore: DataStore<Preferences>) {
      val settings: Flow<CacheSettings>
      suspend fun setMode(mode: CacheNetworkMode)
      suspend fun setLimit(bytes: Long)   // require(bytes in CACHE_LIMIT_PRESETS)
  }
  fun formatCacheReadout(totalBytes: Long, count: Int): String
  fun formatLimitLabel(bytes: Long): String
  ```

- [ ] **Step 1: Write the failing tests**

`NetworkMonitorTest` (Robolectric for `networkStateOf`, using `ShadowNetworkCapabilities.newInstance()` + `addCapability`):

```kotlin
@Test fun `mayCache is false when offline in every mode`()
@Test fun `Wi-Fi and mobile caches on any validated network`()
@Test fun `Wi-Fi only caches only when unmetered`()
@Test fun `Off never caches`()
@Test fun `networkStateOf null is offline`()
@Test fun `networkStateOf needs VALIDATED to be online`()
@Test fun `networkStateOf reads NOT_METERED as unmetered`()
```

`CacheSettingsTest` (DataStore from `PreferenceDataStoreFactory.create(produceFile = { tmp.newFile("cache.preferences_pb") })`):

```kotlin
@Test fun `defaults are Wi-Fi and mobile and 2 GB`()
@Test fun `mode and limit persist`()
@Test fun `setLimit rejects a value that is not a preset`()
@Test fun `an unknown stored mode falls back to the default`()
@Test fun `readout formats GB with one decimal`()      // (1_200_000_000, 14) -> "1.2 GB used · 14 songs"
@Test fun `readout formats MB below 1 GB`()            // (340_000_000, 1) -> "340 MB used · 1 song"
@Test fun `readout rounds MB down`()                    // (999_999_999, 2) -> "999 MB used · 2 songs"
@Test fun `readout for an empty cache`()               // (0, 0) -> "0 MB used · 0 songs"
@Test fun `limit labels`()                             // 500_000_000 -> "500 MB", 1_000_000_000 -> "1 GB", 10_000_000_000 -> "10 GB"
```

`ManifestPermissionsTest`: `manifest declares ACCESS_NETWORK_STATE`, same shape as the existing test.

- [ ] **Step 2: Run them and confirm they fail**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.playback.cache.NetworkMonitorTest' --tests 'io.github.auxen.playback.cache.CacheSettingsTest' --tests 'io.github.auxen.ManifestPermissionsTest'`
Expected: compile failure, then the manifest test fails.

- [ ] **Step 3: Implement both files and add the permission**

Readout uses `Locale.US` formatting so the decimal point is fixed. GB is one decimal at `>= 1_000_000_000`, otherwise whole MB rounded down. "song" is singular only for 1.

- [ ] **Step 4: Run them and confirm they pass**

Same command. Expected: PASS.

- [ ] **Step 5: Run the full suite, then commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/playback/cache/NetworkMonitor.kt \
  android/app/src/main/kotlin/io/github/auxen/playback/cache/CacheSettings.kt android/app/src/main/AndroidManifest.xml \
  android/app/src/test/kotlin/io/github/auxen/playback/cache/NetworkMonitorTest.kt \
  android/app/src/test/kotlin/io/github/auxen/playback/cache/CacheSettingsTest.kt \
  android/app/src/test/kotlin/io/github/auxen/ManifestPermissionsTest.kt
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): add network monitoring and cache settings"
```

---

## Task 8: Graph wiring, Settings section and logout clear

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (`Graph` fields + `init`, lines 136-186)
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/OfflineCacheSection.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/SettingsScreen.kt` (`SettingsScreen` 66-94, `SettingsContent` 98-174)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (cache state + actions; `tidalLogout` ~1088)
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/OfflineCacheSectionUiTest.kt`; re-record Settings goldens in `android/app/src/test/screenshots/`

**Interfaces:**
- Consumes: `TrackCacheStore` (Task 5), `ConnectivityNetworkMonitor`, `CacheSettingsStore`, `trackCacheDataStore`, `formatCacheReadout`, `formatLimitLabel`, `CACHE_LIMIT_PRESETS` (Task 7).
- Produces:
  - `Graph.trackCache: TrackCacheStore` (root `File(context.noBackupFilesDir, "track_cache")`, `db.cachedTrackDao()`), `Graph.cacheSettings: CacheSettingsStore`, `Graph.network: ConnectivityNetworkMonitor`. `init` launches `trackCache.load()` on a `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
  - `@Composable fun OfflineCacheSection(settings: CacheSettings, readout: String, onModeChange: (CacheNetworkMode) -> Unit, onLimitChange: (Long) -> Unit, onClear: () -> Unit)`
  - `SettingsContent` gains `offlineCache: CacheSettings = CacheSettings()`, `cacheReadout: String = ""`, `onCacheModeChange: (CacheNetworkMode) -> Unit = {}`, `onCacheLimitChange: (Long) -> Unit = {}`, `onClearCache: () -> Unit = {}`.
  - `PlayerViewModel.cacheSettings: StateFlow<CacheSettings>`, `cacheReadout: StateFlow<String>`, `setCacheMode(mode)`, `setCacheLimit(bytes)`, `clearCache()`.

Strings (exact): `settings_offline_cache_title` "Offline cache", `settings_cache_over_label` "Cache over", `settings_cache_mode_wifi_and_mobile` "Wi-Fi and mobile data", `settings_cache_mode_wifi_only` "Wi-Fi only", `settings_cache_mode_off` "Off", `settings_cache_limit_label` "Storage limit", `settings_cache_clear` "Clear cache", `settings_cache_clear_confirm_title` "Clear cache?", `settings_cache_clear_confirm_body` "Cached songs will need service to play again.", `settings_cache_clear_confirm` "Clear", `settings_cache_clear_cancel` "Cancel".

Layout: the section goes between Playback and About. Mode is three `RadioButton` rows in a `selectableGroup` (like `SourcePriorityRow`). Limit is five `RadioButton` rows labeled with `formatLimitLabel`. Then the readout as `bodySmall`/`onSurfaceVariant` and an `OutlinedButton` "Clear cache" that opens an `AlertDialog`.

- [ ] **Step 1: Write the failing UI test**

`createComposeRule()`, no graphics mode needed.

```kotlin
@Test fun `shows the readout`()                                // "1.2 GB used · 14 songs" is displayed
@Test fun `selecting Wi-Fi only calls onModeChange`()          // click "Wi-Fi only" -> WIFI_ONLY
@Test fun `selecting 5 GB calls onLimitChange`()               // click "5 GB" -> 5_000_000_000L
@Test fun `the current mode and limit are selected`()          // assertIsSelected on "Off" and "1 GB" for those settings
@Test fun `Clear cache asks first and clears on confirm`()     // click "Clear cache"; "Clear cache?" shown; click "Clear" -> onClear called once
@Test fun `cancelling the confirm does not clear`()
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.ui.OfflineCacheSectionUiTest'`
Expected: compile failure.

- [ ] **Step 3: Implement the section, wire `SettingsContent`/`SettingsScreen`, the VM and `Graph`**

VM: `cacheSettings` = `Graph.cacheSettings.settings.stateIn(viewModelScope, Eagerly, CacheSettings())`. `cacheReadout` = `Graph.trackCache.snapshot.map { formatCacheReadout(it.totalBytes, it.count) }.stateIn(...)`. `tidalLogout` calls `Graph.trackCache.clear()` right after `Graph.tidal.logout()`.

- [ ] **Step 4: Run it and confirm it passes**

Same command. Expected: PASS.

- [ ] **Step 5: Re-record the Settings goldens and run the full suite**

Run: `... :app:recordRoborazziDebug --tests 'io.github.auxen.ui.screenshots.*'`, open the changed Settings PNGs to confirm only the new section was added, then the full `:app:testDebugUnitTest :app:verifyRoborazziDebug`.
Expected: full suite PASS, goldens verify.

- [ ] **Step 6: Commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt \
  android/app/src/main/kotlin/io/github/auxen/ui/OfflineCacheSection.kt android/app/src/main/kotlin/io/github/auxen/ui/SettingsScreen.kt \
  android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt android/app/src/main/res/values/strings.xml \
  android/app/src/test/kotlin/io/github/auxen/ui/OfflineCacheSectionUiTest.kt android/app/src/test/screenshots
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): add the Offline cache settings section"
```

---

## Task 9: Play cached songs from disk

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/TrackResolver.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/TidalUriResolver.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/RecoverableErrors.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/QueueCacheSync.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt` (`buildPlayer` 341-391, `onPlayerError` 505-543, `swapDashCopies` 865-882, `preResolveUpcoming` 890-911, `AuxenLoadErrorPolicy` ~1176)
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (resolver construction)
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/TrackResolverTest.kt`, `.../playback/TidalUriResolverTest.kt` (new), `.../playback/RecoverableErrorsTest.kt`, `.../playback/cache/QueueCacheSyncTest.kt` (new)

**Interfaces:**
- Consumes: `CachedPlayback`, `CacheKind` (Task 5), `Graph.trackCache.cachedPlayback/streamInfoFor/rootUri/snapshot` (Tasks 5, 8).
- Produces:
  ```kotlin
  // TrackResolver.kt: new LAST param (existing callers pass clock/ttl positionally), checked first, never stored in the TTL map
  class TrackResolver(fetch: suspend (String) -> StreamInfo, clock: () -> Long = System::currentTimeMillis, ttlMillis: Long = DEFAULT_TTL_MILLIS, cachedInfo: (String) -> StreamInfo? = { null })
  // TidalUriResolver.kt
  class TidalUriResolver(resolver: TrackResolver, cachedPlayback: (String) -> CachedPlayback? = { null })
  class CachedDashStreamException(val trackId: String, val manifestUri: String) : IOException(...)
  // RecoverableErrors.kt: isRecoverableDashError also returns true for CachedDashStreamException
  // QueueCacheSync.kt
  fun queueItemUpdate(item: MediaItem, playback: CachedPlayback?, cacheRootUri: String): MediaItem?   // null = leave as is
  fun reconcileQueueWithCache(player: Player, lookup: (String) -> CachedPlayback?, cacheRootUri: String)
  ```
  `queueItemUpdate` rules for a `TIDAL:<id>` item: PROGRESSIVE hit and URI isn't `auxen` → `auxen://tidal/<id>` with `setMimeType(null)`. DASH hit and URI isn't `playback.uri` → `playback.uri` with `MimeTypes.APPLICATION_MPD`. No hit and URI starts with `cacheRootUri` → `auxen://tidal/<id>` with `setMimeType(null)`. Anything else (including network `data:` copies for uncached songs) → null.
  `reconcileQueueWithCache` applies `queueItemUpdate` to every non-current Tidal item. The current item gets only the "no hit, points into the cache" restore, never a move onto the cache, since that would glitch the song that's playing (Task 11's error path moves it when it's needed). For that restore it saves `currentPosition` and `playWhenReady`, replaces the item, then `seekTo(index, position)` and restores `playWhenReady`. It's idempotent, so the timeline change it causes re-runs it with no further edits.

- [ ] **Step 1: Write the failing tests**

```kotlin
// TrackResolverTest
@Test fun `cached info is returned without fetching`()
@Test fun `cached info is not kept after the cache drops it`()   // cachedInfo returns info, then null -> next resolve fetches

// TidalUriResolverTest (Robolectric)
@Test fun `non-auxen specs pass through`()
@Test fun `a cached progressive track resolves to its file without fetching`()
@Test fun `a cached DASH track throws CachedDashStreamException with the manifest uri`()
@Test fun `an uncached direct stream resolves as before`()
@Test fun `an uncached DASH stream throws TidalDashStreamException as before`()

// RecoverableErrorsTest
@Test fun `a cached DASH swap signal is recoverable`()

// QueueCacheSyncTest (Robolectric for MediaItem/Uri; reconcile against a real ExoPlayer
// built with ApplicationProvider context and TestPlayerRunHelper is fine, or a small Player fake)
@Test fun `queueItemUpdate restores a DASH-swapped copy once cached as progressive`()
@Test fun `queueItemUpdate points a cached DASH track at its local manifest`()
@Test fun `queueItemUpdate leaves an already-updated item alone`()
@Test fun `queueItemUpdate leaves uncached network copies alone`()
@Test fun `queueItemUpdate restores a local item whose cache entry is gone`()
@Test fun `reconcile updates every non-current copy of a track queued twice`()   // same track at 0, 2 and 4, current = 2 -> 0 and 4 updated, 2 untouched
@Test fun `reconcile never moves the playing item onto the cache`()
@Test fun `reconcile restores a playing item whose file is gone and keeps its position`()
@Test fun `reconcile run twice makes no second edit`()
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.playback.TrackResolverTest' --tests 'io.github.auxen.playback.TidalUriResolverTest' --tests 'io.github.auxen.playback.RecoverableErrorsTest' --tests 'io.github.auxen.playback.cache.QueueCacheSyncTest'`
Expected: compile failure.

- [ ] **Step 3: Implement the resolver hooks and `QueueCacheSync.kt`**

- [ ] **Step 4: Wire the service and `Graph`**

- `Graph.init`: `resolver = TrackResolver(fetch = { id -> tidal.getStreamInfoById(id) }, cachedInfo = { id -> trackCache.streamInfoFor(id) })`. `trackCache` must be built before `resolver`.
- `TidalUriResolver.resolveDataSpec` checks `cachedPlayback` before calling the resolver, so a cached song never needs the network.
- `buildPlayer`: `TidalUriResolver(Graph.resolver, Graph.trackCache::cachedPlayback)`.
- `swapDashCopies(player, trackId, mpdUri: String)`: take the URI instead of the `StreamInfo`. Update both callers.
- `onPlayerError`: before the existing DASH branch, handle `findCause<CachedDashStreamException>`: `swapDashCopies(player, it.trackId, it.manifestUri)`, then `prepare()` + `play()`.
- Expired-URL recovery loop: also `continue` for items whose URI starts with `Graph.trackCache.rootUri`.
- `preResolveUpcoming`: skip ids where `Graph.trackCache.cachedPlayback(id)` is non-null. Don't resolve them.
- `AuxenLoadErrorPolicy`: also skip retries for `CachedDashStreamException`.
- `onCreate`: collect `Graph.trackCache.snapshot` on `mainScope` and call `reconcileQueueWithCache(player, Graph.trackCache::cachedPlayback, rootUri)` on each change after the first. That one path covers new commits, evict, clear and logout.
- `onTimelineChanged(PLAYLIST_CHANGED)`: also call `reconcileQueueWithCache`, so songs added to the queue later pick up their cached copies.

- [ ] **Step 5: Run the tests, then the full suite**

Same command, then full `:app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/playback/TrackResolver.kt \
  android/app/src/main/kotlin/io/github/auxen/playback/TidalUriResolver.kt android/app/src/main/kotlin/io/github/auxen/playback/RecoverableErrors.kt \
  android/app/src/main/kotlin/io/github/auxen/playback/cache/QueueCacheSync.kt android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt \
  android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt android/app/src/test/kotlin/io/github/auxen/playback
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): play cached Tidal tracks from disk"
```

---

## Task 10: CacheAheadScheduler and service wiring

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/CacheAheadScheduler.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt` (`onCreate` 217-326, listener in `configurePlayer` 416-544, `onDestroy` 1073)
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts` (`testImplementation` `org.jetbrains.kotlinx:kotlinx-coroutines-test` via `version.ref = "coroutines"`)
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/cache/CacheAheadSchedulerTest.kt`

**Interfaces:**
- Consumes: `CacheIndex`, `DownloadResult` (Task 5), `CacheCandidate`, `planWindow`, `capacityBytes`, `pickEvictions`, `playOrder`, `cacheCandidates` (Task 3), `TrackDownloader` (Task 6), `CacheSettings`, `NetworkState`, `mayCache` (Task 7).
- Produces:
  ```kotlin
  data class CacheQueueSnapshot(val candidates: List<CacheCandidate>, val playingId: String?, val active: Boolean)
  class CacheAheadScheduler(
      scope: CoroutineScope,
      index: CacheIndex,
      download: suspend (trackId: String, staging: File) -> DownloadResult,
      settings: Flow<CacheSettings>,
      network: StateFlow<NetworkState>,
      freeDeviceBytes: () -> Long,
  ) {
      fun update(snapshot: CacheQueueSnapshot)
      fun stop()
  }
  val RETRY_DELAYS_MILLIS = listOf(30_000L, 120_000L, 600_000L)   // per track, last value repeats
  ```

Behavior: one download at a time, nearest first. Re-plan on every input change (snapshot, settings, network, index snapshot). Start a download only when `snapshot.active && mayCache(mode, network)`. Before each one, evict with `pickEvictions(index entries, protectedIds = window ids + playingId, bytesToFree = used + estimate - capacity)`, and skip it if still over capacity. Cancel the in-flight download when its id leaves the window, when `mayCache` turns false, or on `stop()`; a pause alone does not cancel it. On failure or cancel, `discard(staging)`. A failed id waits `RETRY_DELAYS_MILLIS[attempt]` before it's tried again, while later songs go ahead. Queue items pick up new commits through Task 9's snapshot reconcile, so the scheduler has no commit callback.

- [ ] **Step 1: Write the failing tests**

`runTest` with a `StandardTestDispatcher`, a `FakeCacheIndex` (in-memory map, temp staging folders) and a fake `download` that suspends on a per-id `CompletableDeferred` and records calls.

```kotlin
@Test fun `downloads window songs one at a time in play order`()
@Test fun `skips songs already cached`()
@Test fun `does not start a new download while playback is inactive`()
@Test fun `lets the in-flight download finish after a pause`()
@Test fun `Off mode starts nothing`()
@Test fun `offline starts nothing`()
@Test fun `Wi-Fi only waits on a metered network and resumes when unmetered`()
@Test fun `switching to metered in Wi-Fi only cancels the in-flight download`()   // staging discarded, no commit
@Test fun `cancels the in-flight download when its song leaves the window`()
@Test fun `evicts oldest-played songs outside the window to make room`()
@Test fun `never evicts the playing song`()
@Test fun `a failed song backs off 30 s while later songs continue`()            // advanceTimeBy(29_999) no retry; advanceTimeBy(1) retry
@Test fun `stop cancels everything`()
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.playback.cache.CacheAheadSchedulerTest'`
Expected: compile failure.

- [ ] **Step 3: Implement `CacheAheadScheduler`**

- [ ] **Step 4: Run them and confirm they pass**

Same command. Expected: PASS.

- [ ] **Step 5: Wire it into `PlaybackService`**

- `onCreate`: `val downloader = TrackDownloader(Graph.httpClient) { id, fresh -> if (fresh) Graph.resolver.invalidate(id); Graph.resolver.resolve(id) }`, then build the scheduler with `serviceScope`, `Graph.trackCache`, `download = downloader::download`, `Graph.cacheSettings.settings`, `Graph.network.state` and `Graph.trackCache::freeDeviceBytes`.
- New private `updateCacheWindow(player: Player)`: build `playOrder(player.currentMediaItemIndex) { i -> player.currentTimeline.getNextWindowIndex(i, repeatModeForCache, player.shuffleModeEnabled) }` where `repeatModeForCache` maps `REPEAT_MODE_ONE` to `REPEAT_MODE_OFF` (same as `Player.getNextMediaItemIndex`). Then `cacheCandidates(order, { player.getMediaItemAt(it).mediaId }, { Graph.trackFor(player.getMediaItemAt(it)) })`, `active = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)`, and `scheduler.update(...)`.
- Call it from `onMediaItemTransition`, `onTimelineChanged`, `onIsPlayingChanged`, `onPlayWhenReadyChanged`, `onPlaybackStateChanged`, and new overrides `onShuffleModeEnabledChanged` and `onRepeatModeChanged`. Return early on an empty timeline.
- `onMediaItemTransition`: if the new item is `TIDAL:<id>` and `Graph.trackCache.cachedPlayback(id) != null`, `serviceScope.launch { Graph.trackCache.markPlayed(id) }`.
- `onDestroy`: `scheduler.stop()` before the scopes are cancelled.

- [ ] **Step 6: Run the full suite, then commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/playback/cache/CacheAheadScheduler.kt \
  android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt android/gradle/libs.versions.toml android/app/build.gradle.kts \
  android/app/src/test/kotlin/io/github/auxen/playback/cache/CacheAheadSchedulerTest.kt
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): cache upcoming queue songs while playing"
```

---

## Task 11: Offline skip, mid-song resume and the offline banner

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/OfflineSkipper.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/OfflineStatus.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/RecoverableErrors.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt` (listener, `onPlayerError`, `onCreate`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (`Graph.offlineStatus`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (`onPlayerError` 736-748, banner)
- Test: `.../playback/cache/OfflineSkipperTest.kt` (new), `.../playback/cache/OfflineStatusTest.kt` (new), `.../playback/RecoverableErrorsTest.kt`

**Interfaces:**
- Consumes: `CachedPlayback`, `CacheKind` (Task 5), `NetworkState` (Task 7), `playOrder` (Task 3), `MimeTypes.APPLICATION_MPD`.
- Produces:
  ```kotlin
  // RecoverableErrors.kt
  fun isNetworkFailure(error: Throwable): Boolean     // cause chain has UnknownHostException, ConnectException, SocketTimeoutException
                                                      // or NoRouteToHostException, or PlaybackException code 2001/2002;
                                                      // false if the chain has HttpDataSource.InvalidResponseCodeException
  fun isOfflineFailure(error: Throwable, online: Boolean): Boolean = !online && isNetworkFailure(error)
  fun shouldSuppressPlaybackBanner(error: Throwable, offlineHandledRecently: Boolean): Boolean   // isRecoverableDashError || offlineHandledRecently
  // OfflineStatus.kt
  const val OFFLINE_NOTHING_CACHED_MESSAGE = "Offline, nothing cached ahead"
  class OfflineStatus(clock: () -> Long = SystemClock::elapsedRealtime) {
      val nothingCachedAhead: StateFlow<Boolean>
      fun showNothingCachedAhead(); fun clear()
      fun markHandled(); fun handledRecently(windowMillis: Long = 5_000): Boolean
  }
  // OfflineSkipper.kt
  interface PlayerQueue {
      val currentIndex: Int; val positionMs: Long; val playerError: PlaybackException?
      fun mediaIdAt(index: Int): String; fun uriAt(index: Int): String?
      fun playOrderFrom(index: Int): List<Int>
      fun replaceUri(index: Int, uri: String, mimeType: String?)
      fun seekTo(index: Int, positionMs: Long); fun prepareAndPlay(); fun pause()
  }
  class PlayerQueueAdapter(player: () -> Player?) : PlayerQueue     // reads mediaSession?.player each call, so a bit-perfect rebuild needs no rebinding
  fun isPlayableOffline(mediaId: String, cached: (String) -> CachedPlayback?): Boolean   // LOCAL:* true; TIDAL:id cached; else false
  fun findSkipTarget(order: List<Int>, isPlayable: (Int) -> Boolean): Int?
  sealed interface OfflineAction {
      data object None : OfflineAction
      data class Skipped(val to: Int) : OfflineAction
      data class ResumedFromCache(val atMs: Long) : OfflineAction
      data class PausedNothingAhead(val index: Int, val mediaId: String) : OfflineAction
  }
  class OfflineSkipper(queue: PlayerQueue, cached: (String) -> CachedPlayback?, network: () -> NetworkState, status: OfflineStatus) {
      fun onItemStarting(): OfflineAction            // offline + current item not playable offline -> skip or pause
      fun onError(error: PlaybackException): OfflineAction
      fun onNetworkChanged(state: NetworkState): OfflineAction   // went offline while holding a network-failure error -> same as onError
  }
  ```
  `onError` when `isOfflineFailure(error, network().online)`:
  1. Current item cached → mid-song resume. If DASH and the URI isn't the local manifest, `replaceUri(current, playback.uri, APPLICATION_MPD)`. Then `seekTo(current, savedPosition)` and `prepareAndPlay()`. Returns `ResumedFromCache`.
  2. Otherwise `findSkipTarget(playOrderFrom(current).drop(1), ...)` → `seekTo(target, 0)` + `prepareAndPlay()` → `Skipped`.
  3. Nothing ahead → `pause()` + `status.showNothingCachedAhead()` → `PausedNothingAhead`.
  Every non-`None` result calls `status.markHandled()`. `onItemStarting` does steps 2-3 only, and only when `!network().online`. That's Claude's call within the spec's rule: with no validated network a resolve can only fail, so skip before the error bounce. A slow but validated network never skips there.

- [ ] **Step 1: Write the failing tests**

`OfflineSkipperTest` with a `FakePlayerQueue` (list of mediaIds/URIs, linear play order, records calls). Robolectric only if `PlaybackException` construction needs it.

```kotlin
@Test fun `online does nothing on item start`()
@Test fun `offline item start on a cached song does nothing`()
@Test fun `offline item start on an uncached song skips to the next cached one`()     // [T1 uncached, T2 uncached, T3 cached] current 0 -> seekTo(2, 0)
@Test fun `local tracks count as playable offline`()
@Test fun `skipped songs stay in the queue`()                                          // no remove calls; item count unchanged
@Test fun `nothing ahead pauses and shows the message`()                               // pause called; status.nothingCachedAhead true
@Test fun `skip does not wrap past the end without repeat`()                           // cached item only behind current -> pause
@Test fun `a network failure on a cached progressive song resumes at the same position`()   // seekTo(current, 41_000) + prepareAndPlay
@Test fun `a network failure on a cached DASH song swaps to the local manifest first`()
@Test fun `a network failure while online is not handled`()                             // None; no calls
@Test fun `an HTTP 403 is not an offline failure`()
@Test fun `going offline while holding a network error applies the error path`()
@Test fun `handled actions mark the status`()
```

`OfflineStatusTest`: `handledRecently is true within 5 s and false after`, `clear hides the message`.

`RecoverableErrorsTest`:

```kotlin
@Test fun `UnknownHostException nested in a source error is a network failure`()
@Test fun `SocketTimeoutException is a network failure`()
@Test fun `an InvalidResponseCodeException is not a network failure`()
@Test fun `offline failure needs no validated network`()          // same error: online=true -> false, online=false -> true
@Test fun `banner is suppressed for DASH swaps and recent offline handling`()
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.playback.cache.OfflineSkipperTest' --tests 'io.github.auxen.playback.cache.OfflineStatusTest' --tests 'io.github.auxen.playback.RecoverableErrorsTest'`
Expected: compile failure.

- [ ] **Step 3: Implement `OfflineStatus`, `OfflineSkipper`, `PlayerQueueAdapter` and the error helpers**

`PlayerQueueAdapter.playOrderFrom` uses the same `getNextWindowIndex` + `REPEAT_MODE_ONE` → `OFF` mapping as Task 10. Pull that mapping into one shared `internal fun nextIndexForCache(player: Player, index: Int): Int` in `OfflineSkipper.kt` and use it in both places.

- [ ] **Step 4: Run them and confirm they pass**

Same command. Expected: PASS.

- [ ] **Step 5: Wire the service, `Graph` and the VM**

- `Graph.offlineStatus = OfflineStatus()`.
- Service: one `OfflineSkipper(PlayerQueueAdapter { mediaSession?.player }, Graph.trackCache::cachedPlayback, { Graph.network.state.value }, Graph.offlineStatus)` built in `onCreate`.
- `onMediaItemTransition` and `onPlayWhenReadyChanged(true)`: `offlineSkipper.onItemStarting()`.
- `onPlayerError`: after the two DASH branches and before the expired-URL branch, `if (offlineSkipper.onError(error) != OfflineAction.None) return`.
- `onCreate`: collect `Graph.network.state` on `mainScope` and call `offlineSkipper.onNetworkChanged(it)`.
- Keep `lastQueueIds: List<String>` in the service. On `onTimelineChanged(PLAYLIST_CHANGED)`, compare the current mediaId list to it. Only a real change counts as a user queue change, since cache swaps keep the mediaIds (see Global Constraints).
- User play (`onPlayWhenReadyChanged(true)`) and a real queue change call `Graph.offlineStatus.clear()`.
- VM `onPlayerError`: replace the early return with `if (shouldSuppressPlaybackBanner(error, Graph.offlineStatus.handledRecently())) return`. This works in-process even if the controller loses the cause chain, since the service handles the error before the controller callback arrives.
- VM: collect `Graph.offlineStatus.nothingCachedAhead`. On true set `playbackError.value = OFFLINE_NOTHING_CACHED_MESSAGE`. On false, clear it only if it still holds that message.

- [ ] **Step 6: Run the full suite, then commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/playback/cache/OfflineSkipper.kt \
  android/app/src/main/kotlin/io/github/auxen/playback/cache/OfflineStatus.kt android/app/src/main/kotlin/io/github/auxen/playback/RecoverableErrors.kt \
  android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt \
  android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt android/app/src/test/kotlin/io/github/auxen/playback
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): skip uncached songs when offline"
```

---

## Task 12: Auto-resume after an offline pause

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/playback/cache/AutoResume.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt` (listener, network collector, `onTaskRemoved` 1066-1071)
- Test: `android/app/src/test/kotlin/io/github/auxen/playback/cache/AutoResumeTest.kt`

**Interfaces:**
- Consumes: `OfflineAction.PausedNothingAhead` (Task 11), `OfflineStatus.clear()`.
- Produces:
  ```kotlin
  const val AUTO_RESUME_WINDOW_MILLIS = 1_800_000L
  sealed interface AutoResumeState { data object Idle : AutoResumeState; data class Waiting(val pausedAtMillis: Long, val index: Int, val mediaId: String) : AutoResumeState }
  sealed interface AutoResumeEvent {
      data class OfflinePaused(val atMillis: Long, val index: Int, val mediaId: String) : AutoResumeEvent
      data class NetworkBack(val atMillis: Long) : AutoResumeEvent
      data object UserPlay : AutoResumeEvent
      data object QueueChanged : AutoResumeEvent                         // only when the mediaId list really changed
      data class ItemChanged(val mediaId: String) : AutoResumeEvent      // every transition; cancels only if it's a different song
  }
  data class AutoResumeStep(val state: AutoResumeState, val resumeIndex: Int? = null)
  fun reduceAutoResume(state: AutoResumeState, event: AutoResumeEvent): AutoResumeStep
  class AutoResumeController(clock: () -> Long = SystemClock::elapsedRealtime, onResume: (index: Int) -> Unit) {
      val isWaiting: Boolean
      fun on(event: AutoResumeEvent)
  }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `network back within 30 minutes resumes on the stopped song`()   // paused at 0, back at 600_000 -> resumeIndex 3, Idle
@Test fun `network back at exactly 30 minutes still resumes`()            // back at 1_800_000 -> resume
@Test fun `network back after 30 minutes stays paused`()                  // back at 1_800_001 -> no resume, Idle
@Test fun `user play cancels`()
@Test fun `queue change cancels`()
@Test fun `moving to a different song cancels`()
@Test fun `a swap of the same song does not cancel`()           // Waiting(mediaId "TIDAL:7") + ItemChanged("TIDAL:7") -> still Waiting
@Test fun `network back while idle does nothing`()
@Test fun `a second offline pause restarts the clock`()
@Test fun `controller calls onResume once and stops waiting`()
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.playback.cache.AutoResumeTest'`
Expected: compile failure.

- [ ] **Step 3: Implement `AutoResume.kt`**

- [ ] **Step 4: Run them and confirm they pass**

Same command. Expected: PASS.

- [ ] **Step 5: Wire the service**

- `onResume = { index -> mediaSession?.player?.let { p -> Graph.offlineStatus.clear(); p.seekTo(index, 0); p.prepare(); p.play() } }`. Clear the state before `play()` so the resulting `playWhenReady` change doesn't cancel anything.
- Feed `OfflinePaused` whenever `OfflineSkipper` returns `PausedNothingAhead`, from both `onError` and `onItemStarting`.
- Network collector: feed `NetworkBack` on each offline → online edge.
- `onPlayWhenReadyChanged(true)` → `UserPlay`. A real queue change (Task 11's `lastQueueIds` check) → `QueueChanged`. Every `onMediaItemTransition` → `ItemChanged(newItem.mediaId)`. A pause while `Waiting` changes nothing, since the player is already paused.
- `onTaskRemoved`: don't `stopSelf()` while `autoResume.isWaiting`.

- [ ] **Step 6: Check service lifetime on a device**

Build a debug APK, install it, start an album, swipe to background, turn on airplane mode, and play until the "nothing cached ahead" pause. Wait 10 minutes with the screen off, then turn airplane mode off. Check `adb logcat` that the service process is still alive and playback resumed. If it isn't, don't work around it. Record what happened in the ledger and raise it at handoff (Task 14). Keeping the service alive while paused may need a foreground notification, which is a product call for the user.

- [ ] **Step 7: Run the full suite, then commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/playback/cache/AutoResume.kt \
  android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt \
  android/app/src/test/kotlin/io/github/auxen/playback/cache/AutoResumeTest.kt
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): resume playback when service returns within 30 minutes"
```

---

## Task 13: Cached icon and Android Auto badge

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/AuxenTrackRow.kt`
- Modify: every `AuxenTrackRow(` call site (find with `grep -rn 'AuxenTrackRow(' /home/mrw1986/Projects/auxen/android/app/src/main`): AlbumDetailScreen, CollectionScreen, SearchScreen, PlaylistDetailScreen, LibraryScreen, ArtistDetailScreen, QueueScreen (both rows, plus a `cachedIds` param on its stateless content composable, default `emptySet()`), HomeScreen
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (`cachedIds`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/browse/AuxenBrowseTree.kt` (`trackList`, `albumDetail`, `artistDetail`, `playableTrack`, lines 44-67)
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt` (`childrenFor` 1033-1053)
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `.../ui/components/AuxenTrackRowUiTest.kt`, `.../playback/browse/AuxenBrowseTreeTest.kt`; re-record affected goldens

**Interfaces:**
- Consumes: `Graph.trackCache.snapshot` (Task 5).
- Produces:
  - `AuxenTrackRow(..., isCached: Boolean = false, ...)`. Shows `Icons.Filled.OfflinePin` (16.dp, `onSurfaceVariant`) just before the duration, only when `isCached && track.isTidal`. contentDescription is the new string `track_cached_cd` = "Available offline".
  - `PlayerViewModel.cachedIds: StateFlow<Set<String>>` from `Graph.trackCache.snapshot.map { it.ids }`. Call sites pass `isCached = track.isTidal && track.sourceId in cachedIds`.
  - `AuxenBrowseTree.trackList(tracks, cachedIds: Set<String> = emptySet())`, and the same param on `albumDetail` and `artistDetail`. A cached Tidal leaf gets `putLong(DOWNLOAD_STATUS_KEY, STATUS_DOWNLOADED)` added to its existing metadata extras, with the `TRACK_EXTRA_KEY` and `QUEUE_UID_KEY` entries kept. `const val DOWNLOAD_STATUS_KEY = "android.media.extra.DOWNLOAD_STATUS"`, `const val STATUS_DOWNLOADED = 2L`. First check whether Media3 1.5.1 exposes this key in `androidx.media3.session.MediaConstants`, and use its constant if so.

- [ ] **Step 1: Write the failing tests**

```kotlin
// AuxenTrackRowUiTest
@Test fun `a cached Tidal row shows the offline icon`()          // onNodeWithContentDescription("Available offline").assertExists()
@Test fun `an uncached row has no offline icon`()
@Test fun `a local row never shows the offline icon`()            // isCached = true, source LOCAL -> absent

// AuxenBrowseTreeTest
@Test fun `cached tidal leaf carries the downloaded status extra`()
@Test fun `uncached leaf has no download status`()
@Test fun `download status keeps the existing track extras`()
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `... :app:testDebugUnitTest --tests 'io.github.auxen.ui.components.AuxenTrackRowUiTest' --tests 'io.github.auxen.playback.browse.AuxenBrowseTreeTest'`
Expected: compile failure.

- [ ] **Step 3: Implement the row icon, the VM flow, the call sites and the browse extra**

Service `childrenFor`: pass `Graph.trackCache.snapshot.value.ids` to every `trackList`/`albumDetail`/`artistDetail` call.

- [ ] **Step 4: Run them, re-record goldens, run the full suite**

Same command, then full `:app:testDebugUnitTest :app:verifyRoborazziDebug`. Goldens shouldn't change, since the default is `isCached = false`. If any do, open them and confirm why before re-recording. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -C /home/mrw1986/Projects/auxen add android/app/src/main/kotlin/io/github/auxen/ui android/app/src/main/kotlin/io/github/auxen/playback/browse/AuxenBrowseTree.kt \
  android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt android/app/src/main/res/values/strings.xml \
  android/app/src/test/kotlin/io/github/auxen/ui android/app/src/test/kotlin/io/github/auxen/playback/browse android/app/src/test/screenshots
git -C /home/mrw1986/Projects/auxen commit -m "feat(android): mark cached songs in track rows and Android Auto"
```

---

## Task 14: Full check, APK and on-device handoff

**Files:** none new. Ledger at `.superpowers/sdd/progress.md` if running subagent-driven.

- [ ] **Step 1: Full suite and lint**

Run: `... :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug`
Expected: all tests PASS (570+ existing plus the new ones), goldens verify, no new lint errors.

- [ ] **Step 2: Cross-model review**

Run `codex_review` on the branch diff from `1fa204c`. Fix anything real, re-run Step 1, and commit fixes as `fix(android): ...`.

- [ ] **Step 3: Build and verify the APK**

Build `:app:assembleDebug` locally (creds come from `~/.gradle/gradle.properties`). Copy it out of `build/outputs` right away as `auxen-<sha>-predictive-cache.apk`, where `<sha>` is `git -C /home/mrw1986/Projects/auxen rev-parse --short HEAD`. Verify with `aapt2 dump badging` that `versionName` is `0.1.0+<sha>`.

- [ ] **Step 4: Hand off the validation checklist**

Give the user the APK path and the spec's 8 validation steps. Add the Task 12 service-lifetime result, and say plainly whether auto-resume survived 10 minutes in the background. Check the About string first. The merge decision stays with the user.
