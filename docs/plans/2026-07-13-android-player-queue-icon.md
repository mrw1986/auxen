# Player / Queue / Icon Batch — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** Fix the queue "double-up," give queue reorder smooth animated drag, fix + elevate the Now Playing screen, and make the launcher icon fill the frame — all from on-device feedback (2026-07-13).

**Architecture:** Native Kotlin/Compose/Media3. The live queue IS the MediaController's media items; mutations go through `controller.moveMediaItem/removeMediaItem/seekTo/clearMediaItems`. Visual order is driven by the VM's `queue` flow (fed by a `Player.Listener`), never optimistic mutation.

**Tech Stack:** Compose (Material3), Media3 1.5.1, Coil, `sh.calvin.reorderable` (NEW dep — user-approved), JDK 21 local / JDK 17 CI.

## Global Constraints (verbatim / binding)
- **Gate (every task):** `cd android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug` green; re-record/eyeball goldens; heed the ComponentScreenshotTest AA-drift trap.
- **CI runs JDK 17, local runs JDK 21.** JUnit4 method order is hashcode-based and NOT stable across JVMs — any `object`/singleton state a test touches MUST be reset in `@Before`/`@After`, or it passes local and fails CI (the AutoEqControllerTest lesson, commit 218fc5f).
- **Do NOT change the `MediaItem` `mediaId` format** (`"${source.name}:${sourceId}"`, `AuxenApp.kt:201`). It is parsed by ReplayGain routing and Tidal detection (`PlaybackService.kt:300,402,414,417,709`). Per-occurrence stable ids must come from a NEW field, not mediaId.
- **Never mutate the queue optimistically** — commit via controller calls; the `Player.Listener` (`onTimelineChanged`/`onMediaItemTransition`) re-derives `queue`.
- **No secrets** in code/logs; unrelated here but standing.

---

### Task 1: Queue — exclude playing track from list + animated reorder library

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/QueueScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (queue snapshot carries stable ids)
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (`mediaItemFor` stamps a per-occurrence id; `tracksFrom` reads it)
- Modify: `android/app/build.gradle.kts` + `gradle/libs.versions.toml` (add `sh.calvin.reorderable`)
- Test: `android/app/src/test/.../QueueScreenUiTest.kt` (+ VM test), goldens.

**The two problems this task fixes:**
1. **Double-up (user bug):** `QueueContent` pins the playing track (`QueueScreen.kt:118-133`) AND `ReorderableQueueList` also renders it in the scrollable list at its index — so the current track shows twice. The desktop reference excludes the current-index track from the list. **Do that:** the reorderable list must render every queue entry EXCEPT the playing one; the pinned header stays.
2. **No drag animation (user request):** replace the hand-rolled `detectDragGesturesAfterLongPress` drag with `sh.calvin.reorderable` for smooth animated reorder + haptics.

**Stable per-occurrence id (prerequisite for both — unique keys with duplicate tracks, and animation identity):**
- `mediaId` is not unique per occurrence and must not change. Add a per-occurrence id another way: in `AuxenApp.mediaItemFor(track)`, put a fresh unique id into `MediaMetadata` extras (e.g. key `"auxen.queueUid"`, value = `UUID.randomUUID().toString()`). `moveMediaItem` preserves the MediaItem (and its metadata), so the id follows the item across reorders. A second enqueue of the same track gets a different id ⇒ no key collision, ever.
- Change the VM queue snapshot from `List<Track>` to `List<QueueEntry>` where `data class QueueEntry(val id: String, val track: Track)`. Build it in the listener from the controller: for each window `i`, read the extras id via `getMediaItemAt(i).mediaMetadata.extras?.getString("auxen.queueUid")`; fall back to `"$i:${track.favoriteKey()}"` if absent (restored/edge items) — document that the fallback is only for items that predate the stamp and is still unique-enough within one snapshot.
- Keep `queueIndex` as the real controller index. Expose the playing entry's id too (or derive playing-ness by index) so the pinned header and the list-exclusion agree.

**Index translation (the reason the double-up existed):** the reorderable list now shows a FILTERED list (all entries except the playing one), but every mutation (`jumpTo`/`removeFromQueue`/`moveInQueue`) needs the REAL queue index. Because entries carry stable ids and you have the full `queue` list, translate by id: `realIndex = queue.indexOfFirst { it.id == entry.id }`. For `moveInQueue(fromReal, toReal)`, compute both endpoints' real indices from the full list AFTER accounting for the excluded playing row. Unit-test this translation directly with a queue where the playing track sits in the middle and duplicates exist.

**Reorder impl (`sh.calvin.reorderable`):**
- Add the dep (latest stable 2.x compatible with the project's Compose BOM — check Maven Central / the library's GitHub for the current version and API; the API is `rememberReorderableLazyListState { from, to -> ... }` + `ReorderableItem(state, key = entry.id) { ... }`).
- Key each `ReorderableItem`/`LazyColumn` item by `entry.id` (now unique).
- On reorder callback, translate the filtered from/to to real queue indices and call `onMove`. Drive the drag handle via the library's `draggableHandle()` modifier on the trailing handle icon; keep long-press-to-drag.
- Delete the hand-rolled `targetDragIndex`, `visualOrder`, `pointerInput` drag machinery (and its now-dead tests). Keep `AuxenTrackRow`.
- The playing row stays pinned (not in the reorderable list), so "can't drag the playing track" falls out naturally.

**Tests:** VM test for id-carrying snapshot + index translation (playing-in-middle, duplicate tracks); `QueueScreenUiTest` — a queue with a duplicate track still composes (no key collision) AND the playing track appears exactly once (pinned, not in the list); golden pair (populated + empty). Reset any singleton state in `@Before`.

---

### Task 2: Now Playing — responsive layout fix + design elevation

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/NowPlayingScreen.kt`
- Possibly add: a blurred-backdrop helper; goldens.

**The bug:** `NowPlayingScreen` art is `Modifier.fillMaxWidth().aspectRatio(1f)` inside a **non-scrollable** `Column` (`NowPlayingScreen.kt:97-100,134-139`). On large/near-square screens the square art consumes the full height and pushes the title, badges, seek, and transport off the bottom — the user sees "just image + timebar." All the controls already exist and are correct; they're just off-screen.

**Fix (layout):** guarantee the transport row is ALWAYS visible.
- Constrain the art: put it in a container with `Modifier.weight(1f, fill = false)` and cap it (e.g. `heightIn(max = ...)` or `aspectRatio(1f)` inside a weighted box) so it shrinks on short/square screens instead of pushing controls away. The metadata + seek + transport block gets its intrinsic height at the bottom and never clips.
- Consider `BoxWithConstraints`: on wide/near-square screens (tablet), a two-pane layout (art left, controls right) is a strong improvement and uses the space well; on tall phones keep the stacked layout. Implementer's call, but the acceptance bar is: **on the user's near-square tablet AND on a tall phone, every control (title, badges, seek+times, shuffle/prev/play/next/repeat) is visible without scrolling.**

**Elevate the design (this is the "massive overhaul"):**
- Blurred, dimmed album-art backdrop filling the screen behind a scrim (premium now-playing feel), with the sharp art card on top.
- Larger, clearly-primary play/pause; balanced transport spacing; refined type hierarchy for title/artist; keep marquee.
- Keep ALL existing functionality (favorite, source/quality badges, 3-state repeat, shuffle, sleep timer, queue button, seek with monospace times). Nothing regresses.
- Respect theme (light/dark) and `AuxenColors`.

**Tests:** a golden pair (dark + a populated state). If adding to ComponentScreenshotTest, heed the Fraunces-cache/AA-drift trap. A documented manual check that controls are visible at both a phone and a tablet width (or a layout test asserting the transport row is placed within the viewport at a constrained height).

---

### Task 3: App icon — fill the frame

**Files:**
- Modify: `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Maybe: `android/app/src/main/res/values/ic_launcher_background.xml` (the `@color/ic_launcher_background`), `ic_launcher_monochrome.xml`.

**Change:** user chose "fill the frame" — the bull should dominate the tile (like Claude/Authenticator), not sit small on a card.
- The foreground currently wraps the mark in `<group scaleX="0.72" scaleY="0.72">` (`ic_launcher_foreground.xml:6`). Increase the scale so the bull fills the frame — target roughly the adaptive safe zone edge. **Important:** adaptive foregrounds are drawn on a 108dp canvas where only the central 72dp is guaranteed unclipped; pushing too far risks horn/ear clipping under circular masks. Scale up meaningfully (aim for the mark reading as full-bleed) but verify the horns/ears aren't clipped by a circle mask — preview at circle, squircle, and rounded-square. A scale in the ~0.92–1.0 range on this centered mark is the starting point; tune by eye.
- Keep the background a refined dark ground (the mark's cream/gold reads well on it). Do not introduce a contrasting "card" look.
- Update `ic_launcher_monochrome.xml` to match the new framing if it references the same paths/scale, so themed icons stay aligned.
- minSdk is 26 and there are no raster mipmaps, so only the vector(s) change.

**Tests:** no unit test; verify by rendering the adaptive icon under all three mask shapes (Android Studio preview or `assembleDebug` + inspect). Confirm nothing else references the old scale.

---

### Close-out (lead)
Per-task Opus review (spec + quality); clean-state gate; ledger updates; a fresh **locally-built** APK (CI APKs lack Tidal creds — [[android-ci-apk-lacks-tidal-creds]]); push after review; final whole-batch review.
