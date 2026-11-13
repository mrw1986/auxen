# Parity Screens, Sub-batch A — Settings + Queue

**Context:** First slice of the Desktop-Parity Screens & Tidal Discovery batch (roadmap batch 2). Scoped by an Opus discovery workflow (wf_179a0976). Sub-batch A is the two genuinely-missing nav routes, both with **zero Tidal-API dependency** — the highest-visibility gap on the lowest-risk ground. Sub-batches B (Tidal foundation + favorites merge/sync-back), C (detail enrichment + context menus), D (Tidal page-discovery — needs a page-parser spike first) follow.

All infra already exists; this is UI + thin VM wiring. Two independent tracks (Settings, Queue) — implement as two tasks.

**Gate (every task):** `cd android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug` green; golden re-record/eyeball; the ComponentScreenshotTest AA-drift check. **Note:** CI runs JDK 17 while local is JDK 21 — controller/object singleton tests MUST reset state in `@Before` (order-independent), or they pass local and fail CI (the AutoEqControllerTest lesson, commit 218fc5f).

---

### Task 1: Settings screen (theme mode + source priority + About)

**Files:** new `ui/SettingsScreen.kt`; `ui/PlayerViewModel.kt`; `ui/theme/Theme.kt`; `ui/MainActivity.kt`; `res/values/strings.xml`; goldens.

Desktop reference: `auxen/views/` settings/preferences — persists `color_scheme`, source priority, shows About. The persistence layer here is already a working key-value store (`LibraryRepository.getSetting/setSetting`, `SettingsDao`, already used for home_filter/library_tab).

**Step 1 — Theme mode (do this BEFORE editing AuxenTheme, so nothing renders with a dangling reference):**
- Add a `ThemeMode` enum (System/Dark/Light) — put it near the theme code.
- Persist via `LibraryRepository.getSetting/setSetting` key `color_scheme` (values "system"/"dark"/"light").
- Add `themeMode: StateFlow<ThemeMode>` + `setThemeMode(mode)` to `PlayerViewModel`, backed by that setting.
- `AuxenTheme` (`Theme.kt:84`) currently hardcodes `darkTheme = isSystemInDarkTheme()`. Change it to accept a resolved `darkTheme: Boolean` param (caller resolves System → `isSystemInDarkTheme()`, Dark → true, Light → false). Thread `themeMode` from `MainActivity`'s top-level `setContent` (collectAsState → resolve → pass to AuxenTheme). Every existing `AuxenTheme { }` test/golden call keeps working (they pass `darkTheme` explicitly already, per the BrandBlock luminance-detection note — verify none break).

**Step 2 — Source priority UI:** the data layer is fully built (`SourcePriority` enum `model/Track.kt:9` = PREFER_LOCAL/TIDAL/QUALITY/ALWAYS_ASK; `LibraryRepository.sourcePriority()/setSourcePriority()` `:99-105`; consumed in `PlayerViewModel.search` `:305`). Only the UI/VM surface is missing. Expose `sourcePriority: StateFlow<SourcePriority>` + `setSourcePriority(p)` in PlayerViewModel wrapping the existing repo methods. Render a 4-option selector with user-friendly labels.

**Step 3 — SettingsScreen.kt** — three grouped sections (match the app's section-card visual idiom; reuse existing card/switch/segmented patterns — see EqualizerScreen's FxSectionCard or a lighter grouping):
- **Appearance:** theme mode (System/Dark/Light — SingleChoiceSegmentedButtonRow or radio group).
- **Playback:** source priority (4-option selector, with a one-line explanation of each).
- **About:** app name "Auxen", tagline, version (`BuildConfig.VERSION_NAME`), a short blurb, and the AutoEq attribution line if not already elsewhere. (Subscription/quality info is deferred to sub-batch B.)
- All copy via strings.xml; monospace nowhere needed here; a11y contentDescriptions on interactive controls.

**Step 4 — Nav + entry:**
- `composable("settings") { SettingsScreen(...) }` in the NavHost (MainActivity.kt ~172-227) and add `"settings"` to `OVERLAY_ROUTES` (line 53 — so tab back-stacks don't restore it, the dead-tab-tap lesson).
- Add a Settings gear `IconButton` to the top-bar actions row (MainActivity.kt:112-117, alongside Equalizer + Account) → `navigate("settings") { launchSingleTop = true }`.

**Step 5 — Tests/goldens:** VM logic tests (themeMode persist/restore, sourcePriority persist/restore — reset any singleton state in @Before); a SettingsScreen golden pair (dark/light) via captureComponent. Verify switching theme mode actually re-themes the app (a small UI test or documented manual check). Heed the action-sheet golden-drift trap if adding to ComponentScreenshotTest.

### Task 2: Queue screen (view / jump / remove / reorder / clear)

**Files:** new `ui/QueueScreen.kt`; `ui/PlayerViewModel.kt`; `ui/MainActivity.kt`; `components/MiniPlayerBar.kt` and/or `ui/NowPlayingScreen.kt`; goldens.

Desktop reference: `auxen/views/` queue panel — pinned now-playing, reorderable list, jump/remove, clear.

What exists: `QueueStateStore` + `QueueDao` persist the queue across process death (PlaybackService restore/`scheduleQueueSave`/`currentTracks:500-505`). This is NOT a live reorderable model — the live queue is the MediaController's media items. There are **no** `moveMediaItem`/`removeMediaItem` calls anywhere yet.

**Step 1 — Live queue state in PlayerViewModel:** add `queue: StateFlow<List<Track>>` (or a small QueueItem with the playing index) built from the MediaController snapshot (`mediaItemCount` + `getMediaItemAt(i)` → Track — reuse the exact mapping in `PlaybackService.currentTracks:500-505`; extract a shared mapper if clean). Keep it fresh via a `Player.Listener` on `onTimelineChanged` / `onMediaItemTransition` (NOT optimistic local mutation — the controller reindexes asynchronously). Expose the current playing index too.

**Step 2 — Mutations (all net-new):** `jumpTo(index)` = `controller.seekTo(index, 0)`; `removeFromQueue(index)` = `controller.removeMediaItem(index)`; `moveInQueue(from, to)` = `controller.moveMediaItem(from, to)`; `clearQueue()` = `controller.clearMediaItems()`. Let the existing debounced `QueueStateStore`/`scheduleQueueSave` persist the result — do NOT double-manage persistence.

**Step 3 — QueueScreen.kt:** pinned now-playing header (current track) + a reorderable `LazyColumn` of `AuxenTrackRow` (use its trailing slot for a drag handle + remove affordance), tap-to-jump, empty state, "Clear queue" button. **Reorder impl:** Compose has no built-in reorderable LazyColumn. Check the classpath first (`reorderable`/`sh.calvin.reorderable` or similar) — if none, implement manual drag via `Modifier.pointerInput` + `detectDragGesturesAfterLongPress` driving `moveInQueue`, committing on drop (mind index stability during async controller reindex — drive the visual order from the VM's queue flow, apply the move on drag-end). Flag if the manual impl gets heavy; a small dependency may be cleaner (ask before adding).

**Step 4 — Nav + entry:** `composable("queue")` overlay route + add to `OVERLAY_ROUTES`; a queue `IconButton` on `MiniPlayerBar` and/or `NowPlayingScreen` → `navigate("queue")`.

**Step 5 — Tests/goldens:** VM tests for the snapshot mapping + mutation methods (mock/fake controller); a QueueScreen golden pair (populated + empty). Reorder correctness (from/to index math) unit-tested. Singleton state reset in @Before.

### Close-out (lead)
Clean-state gate, focused whole-sub-batch review (Opus), roadmap/parity note (Settings + Queue → Shipped; theme toggle + source-priority UI + queue panel rows), push, fold into next APK.
