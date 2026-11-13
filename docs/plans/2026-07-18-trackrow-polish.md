# Track-row Declutter + Playlist Drag-to-Reorder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On a normal phone viewport the shared track row (`AuxenTrackRow`) packs too much fixed trailing content after the title, so the title collapses to "A…" / "…" — worst on the **playlist detail** screen, which stacks a source pill, a quality pill, a heart, and three trailing buttons (↑ ↓ ✕) after the title. The user approved **Option A**: drop the redundant `SourceBadge` from the row, move the quality (FLAC / Hi-Res) off the title row onto the existing "artist — album" subtitle line, and replace the playlist's up/down buttons with a **drag handle** ("make up and down arrows a handle that I can drag songs instead of clicking a button. other than that, go with your recommended option."). The playlist's **Remove** moves into a `⋮` overflow to free the row.

**This is a UI-polish sibling of the shipped screen work** — it mirrors the app's own **proven** drag-reorder pattern (`QueueScreen.kt`'s `ReorderableQueueList`) rather than inventing one, and adds **no** dependency (`sh.calvin.reorderable` 2.5.1 is already declared: `gradle/libs.versions.toml:22,39` + `app/build.gradle.kts:105`). It is small and reuse-heavy:

- **No new ViewModel method, no new repository method, no schema change.** The drag's on-drop commit reuses the existing `PlayerViewModel.movePlaylistTrack(id, from, to)` (`PlayerViewModel.kt:991`) → `LibraryRepository.movePlaylistTrack(playlistId, fromIndex, toIndex)` (`LibraryRepository.kt:144`), and Remove reuses `PlayerViewModel.removeFromPlaylist(id, track)` (`PlayerViewModel.kt:984`). Both already exist and are unchanged.
- **`movePlaylistTrack` operates on POSITIONS, not track ids.** It reads the playlist's entities in position order (`PlaylistDao.tracksIn`), `removeAt(fromIndex)`, `add(toIndex, moved)`, then rewrites every position via `PlaylistDao.reorder` (`LibraryRepository.kt:144-151`, `Daos.kt:193-200`). This is the **same** "remove-at-from, insert-at-to" semantics as the reorderable callback's `list.apply { add(to.index, removeAt(from.index)) }` — so an index→index move reproduces the dragged order **exactly, even when the playlist repeats a track id** (see Design decision 1). `LibraryRepositoryTest.playlistManagementRoundTrip` (`LibraryRepositoryTest.kt:128-129`) already pins this position-based behavior and is untouched.
- **No new Compose component.** `AuxenTrackRow` is edited in place (two badges removed from the main row, quality relocated onto the subtitle); no new parameter is added, so no call site's signature changes.

**Architecture:** Two additive UI changes, both reusing existing infrastructure:

- **`AuxenTrackRow` declutter (app-wide).** Remove `SourceBadge(track.source)` and the inline `QualityBadge` from the main row; render the quality (via the **existing** `QualityBadge`, unchanged) as a non-truncating suffix on the subtitle `Row`, gated on `source == TIDAL` exactly as today. The title `Column(weight(1f))` keeps its weight and gains the freed width. This changes **every** row app-wide (Search / PlaylistDetail / Queue / Library / Collection) — and therefore every golden that renders `AuxenTrackRow` (see Global Constraints → Goldens).
- **`PlaylistDetailScreen` drag-to-reorder.** Replace the `LazyColumn` + `itemsIndexed` + `↑ ↓ ✕` trailing with the Queue's proven pattern: `rememberReorderableLazyListState` + `ReorderableItem` + `longPressDraggableHandle` + an optimistic local order during the drag, committing the **net** move once on drop via `movePlaylistTrack`. **Remove** moves into a `⋮` (`DropdownMenu`) in the trailing slot. Rows carry a stable per-occurrence key (the original playlist position); no `queueMoveTarget`-style id-translation is needed because the playlist list is **unfiltered** (unlike the Queue, which hides the pinned playing row) — the reorderable indices ARE the playlist positions.

**Design decisions (locked for this slice):**

- **DECIDED (1) — row key = original playlist position; from/to map DIRECTLY to `movePlaylistTrack`, correct even for duplicate track ids.** A playlist's composite PK is `(playlist_id, track_id, position)`, so the **same** `track_id` can appear at multiple positions; `"$source:$sourceId"` is therefore **not** a unique row key, and a bare current index is not stable under reorder (its key would change as the row moves, breaking `ReorderableItem` identity + animation). So each row is wrapped in a private `ReorderablePlaylistRow(id: Int, track: Track)` whose `id` is the track's **original position** in the loaded list (`tracks.mapIndexed { i, t -> ReorderablePlaylistRow(i, t) }`), assigned once and carried with the row as the optimistic `order` mutates — exactly the role `QueueEntry.id` plays in the Queue. On drop, `from = rows.indexOfFirst { it.id == draggedId }` (the row's original DB position) and `to = order.indexOfFirst { it.id == draggedId }` (its position in the dragged order); a single `movePlaylistTrack(playlistId, from, to)` reproduces the dragged order. This is exact for duplicates because the move is position-based: `movePlaylistTrack` removes the **entity** at `from` and reinserts it at `to`, then `reorder` rewrites all positions — two identical `track_id`s stay two distinct rows at distinct positions, and swapping identical tracks is a visual/semantic no-op anyway. Unlike `QueueScreen.queueMoveTarget` — which exists only because the Queue drags a **filtered** view (playing row pinned out) and must translate a filtered index back to a real controller index — the playlist list contains every row, so its reorderable indices are already the real positions; no translation layer is added.
- **DECIDED (2) — quality goes on the subtitle as the existing `QualityBadge` chip, placed AFTER a weight-limited subtitle text so it never truncates.** The subtitle already truncates with ellipsis; appending `" · FLAC"` into the same string would let a long "artist — album" ellipsize the quality away — the exact failure the brief warns about. Instead the subtitle becomes a `Row` mirroring the title row's proven shape: `Text(subtitle, Modifier.weight(1f, fill = false), maxLines = 1, ellipsis)` followed by (when `source == TIDAL && qualityLabel != "Unknown"`) `Spacer(6.dp)` + `QualityBadge(track.qualityLabel)`. The subtitle text absorbs the truncation; the fixed-size chip stays fully visible at the truncation point ("Kavinsky — OutRun `HI-RES`"). Reusing `QualityBadge` (Badges.kt:38, unchanged) keeps the established WCAG-tuned amber-pill identity — no new styling, no re-declaration — and keeps the change a pure **move**. Gating on `source == TIDAL` preserves the documented contract "`AuxenTrackRow` gates `QualityBadge` on `source == Source.TIDAL`" that `AuxenTrackRowUiTest.tidalRowShowsQualityBadge_localDoesNot` asserts — that behavior test passes **unchanged** (a TIDAL FLAC row still renders exactly one "FLAC" node; a LOCAL FLAC row still renders none).
- **DECIDED (3) — only PlaylistDetail gets the `⋮`; Collection / Library / Search keep their single inline "add to queue" icon.** The badge removal alone frees the width those three screens needed (their titles were crowded by the pills, not by their one trailing `PlaylistAdd` icon). A `⋮` there would save **no** width (a `⋮` is the same ~48 dp as the icon it would replace), turn a one-tap "add to queue" into a two-tap menu action, and duplicate the full secondary-action surface those rows already expose via long-press → `TrackActionSheet`. Fewer moving parts wins: the `⋮` is added **only** to PlaylistDetail, where it genuinely earns its place (relocating Remove to make room for the drag handle, on the worst-crowded — formerly three-button — row). The Queue keeps its existing `✕` + drag handle trailing; the badge removal just declutters it (no trailing change there).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), `sh.calvin.reorderable` 2.5.1 (already a dep), JUnit4 + Robolectric 4.14.1 + Roborazzi (`recordRoborazziDebug` / `verifyRoborazziDebug`, goldens under `app/src/test/screenshots/`). minSdk 26 / compileSdk 35 / Media3 1.5.1. No new dependency, no manifest change.

## Global Constraints

- **Every gradle command must carry `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`.** The system default JDK breaks this project's Gradle with a cryptic `java.lang.IllegalArgumentException: 25.0.3`. Every `Run:` line below already carries the prefix — do not drop it. Never pipe gradle through `tail`/`head` and read the pipe's exit status; read the real output for `BUILD SUCCESSFUL`.
- **Mirror the Queue reorder pattern; do NOT reinvent it and do NOT add a dependency.** `sh.calvin.reorderable` 2.5.1 is already declared (`libs.versions.toml:22,39`, `app/build.gradle.kts:105`) and consumed by `QueueScreen.kt`. Task 2 imports `sh.calvin.reorderable.ReorderableItem` + `rememberReorderableLazyListState` (same two the Queue uses).
- **`longPressDraggableHandle` is NOT an import — it is a member of the `ReorderableCollectionItemScope` receiver** that `ReorderableItem { … }` provides. The Queue calls it from inside `AuxenTrackRow(trailing = { … })` — a `RowScope` lambda nested inside the `ReorderableItem` content — and Kotlin resolves it against the enclosing `ReorderableCollectionItemScope` implicit receiver (no import, and `RowScope` does not shadow it). Task 2 reproduces this exact nesting; the drag-handle `Icon` MUST live inside the `AuxenTrackRow` `trailing` lambda, itself inside the `ReorderableItem` content lambda. Do NOT add an import for `longPressDraggableHandle` — there is none to add.
- **Reuse, never re-declare — a duplicate declaration is a Kotlin "conflicting declarations" COMPILE ERROR.** `AuxenTrackRow`, `SourceBadge`, `QualityBadge`, `formatDuration` (`ui/components/`), `PlayerViewModel.movePlaylistTrack`/`removeFromPlaylist`, `LibraryRepository.movePlaylistTrack`, `PlaylistDao.reorder`, `Track`, `Source` all already exist and are **reused, never re-declared**. The one NEW name introduced here — the private `ReorderablePlaylistRow` in `PlaylistDetailScreen.kt` — was grep-verified to have zero existing declarations in `app/src`.
- **When new code in an EXISTING file names a type that file does not import, ADD THE IMPORT — Kotlin resolves per-file.** Task 2 declares `ReorderablePlaylistRow(val track: Track)`, naming `io.github.auxen.model.Track`, which `PlaylistDetailScreen.kt` does **not** import today (the current `itemsIndexed(tracks) { _, track -> … }` infers the element type without naming it) — so Task 2 must ADD `import io.github.auxen.model.Track`, plus the reorderable / icon / menu / haptics / lazy imports listed in the task, and REMOVE the now-unused `itemsIndexed` / `ArrowUpward` / `ArrowDownward` imports. Task 1 needs **zero** import changes in `AuxenTrackRow.kt`: `QualityBadge` is same-package (never imported), `Source` stays used (the subtitle gate), and every layout primitive is already imported.
- **Goldens re-record INTENTIONALLY and ISOLATED — Task 1 is a deliberate visual change.** Dropping the source pill and relocating the quality changes exactly SIX goldens: the four `AuxenTrackRow` component goldens (`track-row-tidal-favorite-{light,dark}`, `track-row-local-explicit-{light,dark}`) **and** the two `queue-screen-populated-{light,dark}` goldens (which render `AuxenTrackRow` transitively via `QueueContent` — this is expected collateral, NOT a leak). Run `verifyRoborazziDebug` **UNFILTERED** (a `--tests` filter silently excludes the screenshot classes → a vacuous pass); confirm exactly those six fail and NO other golden does; re-record; confirm `git status` on the screenshots dir lists exactly those six PNGs modified; re-verify green. If a SEVENTH golden shifts, STOP (a leak) — the one documented caveat is `typography-details-*` / the Fraunces variable-font weight-cache flake described in `ComponentScreenshotTest`'s KDoc; investigate before recording it in. PlaylistDetail has **no** golden (grep-verified), so Task 2 re-records nothing.
- **Git: explicit pathspecs only, INCLUDING the re-recorded golden PNGs.** Never `git add -A` / `git add .` / `git commit -a` — the working tree carries unrelated untracked files (`.playwright-mcp/`, other `docs/plans/*`) that must never be committed. Each commit block below lists exact pathspecs.
- Every code step below shows the actual code. No `TODO`/"add error handling"/"similar to above" placeholders.

---

### Task 1: `AuxenTrackRow` declutter — drop the source pill, move quality to the subtitle (+ re-record the 6 affected goldens)

Remove `SourceBadge(track.source)` and the inline `QualityBadge` block from the main row, and render the quality as a non-truncating suffix on the subtitle `Row`. The title `Column(weight(1f))` is unchanged and inherits the freed width. Heart, duration, the explicit "E" badge, and the `trailing` slot are untouched. This is an app-wide visual change → re-record the six `AuxenTrackRow`-rendering goldens.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/AuxenTrackRow.kt`
- Re-record (record task rewrites the PNGs; they are committed):
  - `android/app/src/test/screenshots/track-row-tidal-favorite-light.png`
  - `android/app/src/test/screenshots/track-row-tidal-favorite-dark.png`
  - `android/app/src/test/screenshots/track-row-local-explicit-light.png`
  - `android/app/src/test/screenshots/track-row-local-explicit-dark.png`
  - `android/app/src/test/screenshots/queue-screen-populated-light.png`
  - `android/app/src/test/screenshots/queue-screen-populated-dark.png`

**Interfaces:**
- Consumes: `QualityBadge(label)` (`Badges.kt:38`, same package — REUSED, unchanged), `Source.TIDAL`, `track.qualityLabel` (`Track.kt:69`). No new import (same-package `QualityBadge`; `Source` already imported at `AuxenTrackRow.kt:35`).
- Produces: no signature change — `AuxenTrackRow(...)` keeps its exact parameter list; only the row's internal layout changes.

- [ ] **Step 1: Edit `AuxenTrackRow.kt` — update the KDoc, relocate the quality, drop the source pill**

First, update the function KDoc layout summary (`AuxenTrackRow.kt:47-48`):

Replace:
```kotlin
 * The canonical track row — Android port of the desktop
 * `make_standard_track_row`: [Art][Title(+E)+Subtitle][Duration][Source][Quality][Heart][trailing].
```
with:
```kotlin
 * The canonical track row — Android port of the desktop
 * `make_standard_track_row`: [Art][Title(+E) / Subtitle(+Quality)][Duration][Heart][trailing].
 * The source pill is intentionally omitted (it is redundant on a Tidal-filtered
 * list and available in the track action sheet); the quality pill rides on the
 * subtitle line so the title column gets the full freed width on phone widths.
```

Then replace the subtitle `Text` (`AuxenTrackRow.kt:134-140`):
```kotlin
            Text(
                listOfNotNull(track.artist, track.album).joinToString(" — "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
```
with a subtitle `Row` that appends the quality as a non-truncating suffix (mirrors the title row's `weight(1f, fill = false)` + optional-badge shape directly above it):
```kotlin
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(track.artist, track.album).joinToString(" — "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The subtitle text absorbs the ellipsis so the fixed-size
                    // quality chip after it stays fully visible even when
                    // "artist — album" is long (fill = false keeps it hugging
                    // its content, leaving room for the chip).
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Quality moved off the title row (was [Duration][Source][Quality]
                // after the title). Still Tidal-only, matching the prior inline
                // gate and the AuxenTrackRowUiTest contract; the != "Unknown"
                // guard avoids a dangling spacer when QualityBadge self-suppresses.
                if (track.source == Source.TIDAL && track.qualityLabel != "Unknown") {
                    Spacer(Modifier.width(6.dp))
                    QualityBadge(track.qualityLabel)
                }
            }
```

Then delete the source pill + inline quality block from the main row, i.e. remove `AuxenTrackRow.kt:149-154`:
```kotlin
        Spacer(Modifier.width(8.dp))
        SourceBadge(track.source)
        if (track.source == Source.TIDAL) {
            Spacer(Modifier.width(4.dp))
            QualityBadge(track.qualityLabel)
        }
```
so the row after the duration `Text` goes straight to the heart `IconButton`. (The `Spacer(Modifier.width(8.dp))` between the subtitle `Column` and the duration `Text` at `AuxenTrackRow.kt:142` STAYS — only the source-pill spacer at `:149` is removed.)

- [ ] **Step 2: Verify goldens UNFILTERED to see exactly which shift (the RED)**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:verifyRoborazziDebug`

Do NOT add a `--tests` filter — that would silently exclude the screenshot classes and pass vacuously. Expected: the task FAILS, reporting a pixel mismatch for **exactly these six** goldens and no others:
`track-row-tidal-favorite-light`, `track-row-tidal-favorite-dark`, `track-row-local-explicit-light`, `track-row-local-explicit-dark`, `queue-screen-populated-light`, `queue-screen-populated-dark`.
The two `queue-screen-populated-*` are expected — `QueueContent` renders `AuxenTrackRow` for its pinned + up-next rows, so dropping the source pill changes them too. **If any OTHER golden fails** (e.g. `badges-row-*`, `mini-player-controls-*`, `queue-screen-empty-*`, `action-sheet-*`, or `typography-details-*`), STOP and investigate — the badge change must not touch a surface that does not render `AuxenTrackRow`. (`badges-row-*` renders `SourceBadge`/`QualityBadge` directly and MUST stay green — proof the components themselves are unchanged.)

- [ ] **Step 3: Re-record the goldens intentionally**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:recordRoborazziDebug`
Expected: `BUILD SUCCESSFUL`; the PNGs under `app/src/test/screenshots/` are rewritten.

- [ ] **Step 4: Confirm the re-record is ISOLATED to the six intended PNGs**

Run: `cd android && git status --porcelain app/src/test/screenshots/`
Expected: exactly six PNGs listed as modified (`M`): the four `track-row-*` and the two `queue-screen-populated-*` above — nothing else. If a seventh appears, STOP: it is either a leak from the badge change (investigate the row) or the documented `typography-details-*` Fraunces weight-cache flake (`ComponentScreenshotTest` KDoc) — resolve before committing; do not blindly add it.

- [ ] **Step 5: Re-verify green (goldens + the behavior test in one unfiltered run)**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew :app:verifyRoborazziDebug`
Expected: `BUILD SUCCESSFUL`. This runs the full unit-test suite in verify mode, so it also re-runs `AuxenTrackRowUiTest` — including `tidalRowShowsQualityBadge_localDoesNot`, which still passes unchanged (the TIDAL FLAC row renders exactly one "FLAC" node — now on the subtitle line — and the LOCAL FLAC row renders none, because the subtitle quality stays gated on `source == TIDAL`).

- [ ] **Step 6: Build the APK**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/AuxenTrackRow.kt \
        android/app/src/test/screenshots/track-row-tidal-favorite-light.png \
        android/app/src/test/screenshots/track-row-tidal-favorite-dark.png \
        android/app/src/test/screenshots/track-row-local-explicit-light.png \
        android/app/src/test/screenshots/track-row-local-explicit-dark.png \
        android/app/src/test/screenshots/queue-screen-populated-light.png \
        android/app/src/test/screenshots/queue-screen-populated-dark.png
git commit -m "feat(android): declutter AuxenTrackRow for phone widths

Drops the redundant SourceBadge from the shared track row and moves the
quality pill (FLAC/Hi-Res) off the title row onto the artist-album subtitle,
as a non-truncating suffix after a weight-limited subtitle text, so the title
column reclaims the freed width and stops collapsing to an ellipsis. Quality
stays Tidal-gated (unchanged AuxenTrackRowUiTest contract); QualityBadge and
SourceBadge components are reused unchanged. Re-records the six goldens that
render AuxenTrackRow (the four track-row component goldens plus the two
queue-screen-populated goldens, which render it via QueueContent); every other
golden stays byte-identical.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 2: `PlaylistDetailScreen` — drag-to-reorder + `⋮` overflow (Remove)

Replace the playlist's `LazyColumn` + `itemsIndexed` + `↑ ↓ ✕` trailing with the Queue's proven reorderable pattern (`rememberReorderableLazyListState` + `ReorderableItem` + `longPressDraggableHandle`, optimistic local order, net move committed once on drop) and relocate **Remove from playlist** into a `⋮` `DropdownMenu`. Reuses `movePlaylistTrack`/`removeFromPlaylist` unchanged. No golden renders PlaylistDetail, so nothing re-records — the gate is `assembleDebug` + a green unfiltered `verifyRoborazziDebug` (proving no golden shifted).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlaylistDetailScreen.kt`

**Interfaces:**
- Consumes: `PlayerViewModel.movePlaylistTrack(id, from, to)` (`:991`, REUSED), `PlayerViewModel.removeFromPlaylist(id, track)` (`:984`, REUSED), `AuxenTrackRow` (Task 1), `sh.calvin.reorderable.{ReorderableItem, rememberReorderableLazyListState}` (already a dep). `longPressDraggableHandle` is the `ReorderableCollectionItemScope` receiver member (no import — see Global Constraints).
- Produces: private `data class ReorderablePlaylistRow(val id: Int, val track: Track)` (file-local; grep-verified collision-free).

- [ ] **Step 1: Update imports**

In `PlaylistDetailScreen.kt`, ADD these imports (in the existing import block, keeping alphabetical grouping):
```kotlin
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import io.github.auxen.model.Track
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
```
and REMOVE these now-unused imports:
```kotlin
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
```
(Keep `androidx.compose.material.icons.filled.Close` — it becomes the Remove menu item's leading icon. `Box`, `mutableStateOf`, `remember`, `getValue`, `setValue`, `LaunchedEffect`, `LazyColumn`, `Icon`, `IconButton`, `Text` are already imported.)

- [ ] **Step 2: Add the private row holder (file top level, beside `PLAYLIST_COLORS`)**

After the `PLAYLIST_COLORS` declaration (`PlaylistDetailScreen.kt:63-66`), add:
```kotlin
/**
 * A playlist row's drag-stable identity: its ORIGINAL position in the loaded
 * playlist, assigned once and carried with the row as the optimistic order
 * mutates during a drag — the role QueueEntry.id plays in QueueScreen. A
 * playlist can repeat a track id (its PK is (playlist_id, track_id, position)),
 * so "source:sourceId" is NOT a unique row key and a bare current index is not
 * stable under reorder; the original position is both.
 */
private data class ReorderablePlaylistRow(val id: Int, val track: Track)
```

- [ ] **Step 3: Update the screen KDoc**

Replace `PlaylistDetailScreen.kt:77-80`:
```kotlin
/**
 * Playlist detail — desktop PlaylistView: color-dot header, Play All/Shuffle,
 * rename/recolor/delete, and per-row remove + move up/down reordering.
 */
```
with:
```kotlin
/**
 * Playlist detail — desktop PlaylistView: color-dot header, Play All/Shuffle,
 * rename/recolor/delete, and drag-to-reorder tracks (long-press the drag handle)
 * with a per-row overflow menu for Remove. Reorder mirrors QueueScreen's
 * ReorderableQueueList (optimistic order during the drag, one movePlaylistTrack
 * on drop); the playlist list is unfiltered, so reorderable indices ARE playlist
 * positions and map straight to movePlaylistTrack — no index translation needed.
 */
```

- [ ] **Step 4: Replace the non-empty `LazyColumn` branch with the reorderable list**

Replace the `else` branch body (`PlaylistDetailScreen.kt:149-173`, from `} else {` through the `LazyColumn { itemsIndexed(...) { ... } }`):
```kotlin
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
```
with the reorderable version (mirrors `QueueScreen.ReorderableQueueList` `:174-231`):
```kotlin
        } else {
            // Drag-to-reorder, mirroring QueueScreen.ReorderableQueueList: `order`
            // is an optimistic local copy the reorder callback mutates in place for
            // smooth animation; it re-syncs to the real list whenever that changes
            // for any OTHER reason — but never mid-drag, so a completed drag can't
            // be clobbered before it commits. The controller is never touched until
            // the drag ends; on drop, one net movePlaylistTrack reproduces the
            // dragged order. Rows carry a stable per-occurrence key (original
            // position) so a repeated track id still has a unique, drag-stable
            // identity. movePlaylistTrack is position-based (remove-at-from,
            // insert-at-to, then rewrite positions), so from/to map straight from
            // the reorderable indices — no queueMoveTarget-style translation, since
            // this list is unfiltered (unlike the Queue, which pins the playing row).
            val rows = remember(tracks) { tracks.mapIndexed { i, t -> ReorderablePlaylistRow(i, t) } }
            var order by remember { mutableStateOf(rows) }
            var draggingId by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(rows) { if (draggingId == null) order = rows }

            val haptics = LocalHapticFeedback.current
            val lazyListState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
                order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }

            LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
                items(order, key = { it.id }) { row ->
                    ReorderableItem(reorderState, key = row.id) { _ ->
                        val track = row.track
                        var menuOpen by remember { mutableStateOf(false) }
                        AuxenTrackRow(
                            track = track,
                            isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                            onPlay = { viewModel.play(track) },
                            onToggleFavorite = { viewModel.toggleFavorite(track) },
                            trailing = {
                                // Remove relocated from the old inline ✕ into a ⋮
                                // overflow, freeing the row for the drag handle.
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Remove from playlist") },
                                            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                viewModel.removeFromPlaylist(playlistId, track)
                                            },
                                        )
                                    }
                                }
                                // Long-press drag handle. longPressDraggableHandle is
                                // the enclosing ReorderableItem scope's member (no
                                // import). On drop, from = the row's ORIGINAL position
                                // (its index in `rows`, unchanged during the drag),
                                // to = its index in the dragged `order`; one
                                // movePlaylistTrack reproduces the order.
                                Icon(
                                    Icons.Filled.DragHandle,
                                    contentDescription = "Reorder ${track.title}",
                                    modifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = { draggingId = row.id },
                                        onDragStopped = {
                                            val id = draggingId
                                            draggingId = null
                                            if (id != null) {
                                                val from = rows.indexOfFirst { it.id == id }
                                                val to = order.indexOfFirst { it.id == id }
                                                if (from >= 0 && to >= 0 && from != to) {
                                                    viewModel.movePlaylistTrack(playlistId, from, to)
                                                }
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
```

- [ ] **Step 5: Build + prove no golden shifted**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew assembleDebug :app:verifyRoborazziDebug`
Expected: `BUILD SUCCESSFUL`. PlaylistDetail has no golden, so `verifyRoborazziDebug` stays green (the six re-recorded in Task 1 now match; nothing else moved). If any golden fails here, STOP — Task 2 must not touch any golden surface.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/PlaylistDetailScreen.kt
git commit -m "feat(android): drag-to-reorder playlist tracks + overflow remove

Replaces PlaylistDetailScreen's up/down/✕ trailing with a long-press drag
handle plus a ⋮ overflow (Remove from playlist), mirroring QueueScreen's
reorderable pattern (optimistic order during the drag, one movePlaylistTrack on
drop). Rows key on their original position so a repeated track id stays a
unique, drag-stable row; movePlaylistTrack is position-based, so the reorderable
from/to map directly with no queueMoveTarget-style translation (the list is
unfiltered). No new dependency (sh.calvin.reorderable already declared); reuses
movePlaylistTrack/removeFromPlaylist unchanged. No golden renders this screen.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 3: On-device checkpoint (gates completion)

No production code. Build a local APK (CI runners have no Tidal client id, so any on-device Tidal check must use a locally-built APK), install on a signed-in device, and verify the polish end-to-end. Completion is gated on this passing.

- [ ] **Step 1: Build a labelled local APK**

Run: `cd android && JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew assembleDebug`
Then copy the APK out of `build/outputs` (unsafe to hand out while other agents build) to a stable name carrying the commit SHA, and verify it with `aapt2 dump badging` before install (confirm `package: name='io.github.auxen'` and the expected `versionName`). Install on a device signed in to Tidal.

- [ ] **Step 2: Verify the declutter (titles readable)**

  - Open **Collection → Tracks** on a phone. Long track titles now render fully / ellipsize far later than before (no more "A…"), because the source pill is gone and the quality rides on the subtitle.
  - Confirm the **quality still shows** on the subtitle line for Tidal tracks (e.g. "artist — album  FLAC" / "… HI-RES"), and stays visible even when the artist/album is long (the subtitle text truncates, the quality chip does not).
  - Confirm **LOCAL** tracks show no quality chip (unchanged gate) and **no** source pill appears on any row (Collection / Library / Search / Queue / Playlist).
  - Confirm the explicit **"E"** badge and the **heart** are still inline on the title row.

- [ ] **Step 3: Verify playlist drag-to-reorder**

  - Open a playlist with several tracks (ideally one with a **duplicate** track, if available). **Long-press the drag handle** on a row and drag it to a new position; on release the list settles in the new order with a haptic tick, and the order **persists** after leaving and re-opening the playlist (the drop committed via `movePlaylistTrack`).
  - Drag a row to the **top** and to the **bottom**; both land correctly.
  - If a duplicate track is present, drag one occurrence and confirm only that occurrence moves (the other stays put).
  - Tapping a row still **plays** it; the drag handle does not trigger playback.

- [ ] **Step 4: Verify the `⋮` overflow (Remove)**

  - Tap the **⋮** on a playlist row → the menu opens with **Remove from playlist**; tapping it removes that track and the list refreshes (reuses `removeFromPlaylist`; no Tidal re-import — `refreshPlaylistTracks` re-reads locally).
  - Collection / Library / Search rows still show their single **add-to-queue** icon (no `⋮` there, per Design decision 3); the Queue still shows **✕ + drag handle**.

- [ ] **Step 5: Final tree check**

Run `cd android && git status` and `git diff --stat` and confirm only the two source files plus the six golden PNGs changed across Tasks 1–2 (`AuxenTrackRow.kt`, `PlaylistDetailScreen.kt`, and the four `track-row-*` + two `queue-screen-populated-*` PNGs) — no other `.kt`, no other golden, no dependency/manifest change.

---

## Self-review checklist

- **No new component, no new parameter, no signature change.** `AuxenTrackRow`'s parameter list is byte-identical; all five call sites compile unchanged. The only new declaration is the file-private `ReorderablePlaylistRow` in `PlaylistDetailScreen.kt` (grep-verified collision-free).
- **No new dependency.** `sh.calvin.reorderable` 2.5.1 is already declared (`libs.versions.toml:22,39`, `app/build.gradle.kts:105`) and used by `QueueScreen`; Task 2 imports the same two symbols the Queue does.
- **Reuses `movePlaylistTrack`/`removeFromPlaylist` unchanged.** No ViewModel/repository/DAO/schema edit; `LibraryRepositoryTest.playlistManagementRoundTrip` (`:128-129`) — the position-based-move pin the drag mapping depends on — is untouched and passes.
- **Duplicate-track correctness is explicit and justified.** Row key = original position (drag-stable, unique across duplicate ids); `movePlaylistTrack` is position-based, so the direct index→index map reproduces the dragged order exactly; no `queueMoveTarget` translation (the list is unfiltered). Argued from the DAO source in Design decision 1.
- **Quality stays visible and Tidal-gated.** Subtitle text truncates; the fixed-size `QualityBadge` chip after it does not. Gate `source == TIDAL` preserves the `AuxenTrackRowUiTest.tidalRowShowsQualityBadge_localDoesNot` contract — that behavior test passes unchanged.
- **`⋮` only where it earns its place.** PlaylistDetail (relocates Remove for the drag handle); Collection/Library/Search keep their one-tap add-to-queue icon; Queue keeps ✕ + handle. Justified in Design decision 3.
- **Goldens re-recorded INTENTIONALLY and ISOLATED — six, not four.** Enumerated: `track-row-tidal-favorite-{light,dark}`, `track-row-local-explicit-{light,dark}`, `queue-screen-populated-{light,dark}` (the last two are `AuxenTrackRow` collateral via `QueueContent`). Verify UNFILTERED → confirm only those six fail → record → `git status` shows exactly those six → re-verify green; a seventh shift STOPS the task (leak, or the documented Fraunces flake). `badges-row-*` must stay green (components unchanged). PlaylistDetail has no golden.
- **Imports: added where new code names an unimported type, removed where dead.** Task 1: zero import changes (`QualityBadge` same-package; `Source` still used). Task 2: adds the reorderable/icon/menu/haptics/lazy imports + `io.github.auxen.model.Track`; removes `itemsIndexed`/`ArrowUpward`/`ArrowDownward`; `longPressDraggableHandle` is a scope receiver member (no import).
- **Every gradle line carries `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10`; verify runs UNFILTERED; commits use explicit pathspecs including the six golden PNGs; no `git add -A`.**
- **Scope:** 2 code tasks + 1 on-device checkpoint — within the brief's ~3-task shape.
