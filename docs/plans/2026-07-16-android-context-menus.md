# Track info everywhere + long-press navigation + album/artist menus — Implementation Plan (re-grounded 2026-07-17)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the one real gap the user hit — *credits/lyrics/technical info is only reachable from two screens* — by finishing the wiring of the **already-shared** track long-press menu (`TrackActionSheet`) and its info sheet (`TrackInfoSheet`) across every track list, folding the desktop's "Properties" into a **Details** section, and adding the two nav entries ("Go to album", "Go to artist") the desktop's `TrackContextMenu` has. Plus one small bug fix: Home album cards open on tap like every other grid.

**This is a re-grounding of the stale 2026-07-16 draft.** Since that draft: the artist page was rebuilt, search became multi-type, and the **official Tidal v2 stack was retired** (the app is 100% internal-v1; `TidalMetadataRepository` is gone and the "frozen constructor" constraint is void). The draft assumed long-press menus "barely exist" and that "Track info" was unreachable. **Both are wrong now** — see *Current state (verified)*. Every citation below was re-read against the working tree on 2026-07-17.

**Three user decisions (2026-07-17), baked in:**
- **D1 — Properties becomes a "Details" section inside `TrackInfoSheet`.** No separate Properties dialog, no "Properties" menu entry. The info sheet becomes **Credits + Lyrics + Details**, and Details (format / bitrate / sample rate / bit depth / source) is pure display of fields already on the `Track` model — no new fetch. This makes the info sheet useful for **local** tracks too, so "Track info" is offered on local rows, not just Tidal.
- **D2 — track-row artist/album names are NOT tappable.** The old draft's "Task 7 (clickable names)" is **DELETED**. "Go to album" / "Go to artist" are reached through the long-press menu only, matching the desktop.
- **D3 — Home album cards OPEN on tap** (they currently *play* on tap — a bug; every other album grid opens). Small task at the end.

**D4 — the album + artist long-press menus are now IN scope (user request 2026-07-17).** After the track menu was re-grounded to the five tasks below, the user asked: *"can you make sure we also have the corresponding 'add to' options on long press? add to queue, add to playlist, play next, etc."* The track menu already has those; the request is for the **album-level and artist-level** equivalents — exactly the menus the first re-ground deferred. They are un-deferred here as **Tasks 6–8** (a pure `albumActions`/`artistActions` model, `AlbumCard`/`ArtistRow` long-press, and `AlbumActionSheet`/`ArtistActionSheet` backed by four new ViewModel list-methods). Scope grew from five tasks to **eight** — above the old ~5 ceiling, and deliberately so; see *Scope* at the end of the Global Constraints.

**Architecture:** one surface, one pure model, nullable-gated affordances.

1. **One menu surface, already chosen: `ModalBottomSheet`.** The app already uses it for `TrackActionSheet` and `TrackInfoSheet`; this plan **extends** those, it does not add a parallel mechanism.
2. **One pure model.** Which entries a track menu shows, for a given track + host capabilities, is a **pure** function in `ActionSheetModel.kt` returning `List<ActionEntry>` — plain Kotlin, no Compose, unit-tested. That is where the conditionality bugs live (the desktop proves it — four of its gates never fire; see *Desktop bugs we are not porting*). The composable renders whatever list the model hands it.
3. **Every optional affordance is gated on a nullable handler defaulting to `null`.** This is the existing `onShowInfo` precedent (`TrackActionSheet.kt:67`). It is load-bearing three ways: a host that cannot service an action never offers a dead one; each call site opts in explicitly; and the `action-sheet-*` Roborazzi goldens stay byte-identical, because the golden fixture passes no optional handler (see *Golden risk*).

**The stateless `…Content` split is already in place and stays mandatory.** `ModalBottomSheet` hosts its content in a *separate window* whose animated entrance never settles under Robolectric, so `onRoot().captureRoboImage(...)` cannot capture it and node queries cannot reach it. Both existing sheets are split into a `ModalBottomSheet` wrapper + an `internal …Content` plain `Column` (`TrackActionSheet.kt:128`, `TrackInfoSheet.kt:57`). Keep it.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01 → UI/Foundation **1.7.6**, Material3 1.3.1), Navigation-Compose, Media3, JUnit4 + Robolectric + Roborazzi. minSdk 26 / compileSdk 35. Manual `Graph` service-locator DI.

---

## Current state (verified) — what already exists

Read out of the working tree on 2026-07-17. The gap is **narrow**; do not rebuild what is here.

| Fact | Evidence |
| --- | --- |
| `AuxenTrackRow` has an `onLongPress` param and calls `combinedClickable(onClick = onPlay, onLongClick = onLongPress)` | `AuxenTrackRow.kt:59`, `:65` — but **no `onLongClickLabel` and no haptic** (Task 2 fixes this) |
| `TrackActionSheet` already renders **Play / Play next / Add to queue / Favorite toggle / Add to playlist (+ New playlist dialog)** and an **optional** "Credits & lyrics" | `TrackActionSheet.kt:158-180` |
| The "Credits & lyrics" entry is gated `track.source == Source.TIDAL && onShowInfo != null` | `TrackActionSheet.kt:178` |
| `TrackInfoSheet` (Credits + Lyrics) exists, internal-v1 sourced via `TidalTrackDetail` | `TrackInfoSheet.kt`, `TidalInternalTrackDetail.kt` |
| `TrackActionSheet` is wired on **six** hosts | `AlbumDetailScreen.kt:243`, `ArtistDetailScreen.kt:384`, `LibraryScreen.kt:241`, `CollectionScreen.kt:267`, `SearchScreen.kt:382`, plus `HomeScreen` — **wait: Home has NO sheet** (see below) |
| `onShowInfo` + `TrackInfoSheet` are wired on **only two** hosts | `AlbumDetailScreen.kt:254,258-267` and `ArtistDetailScreen.kt:395,399-408` |
| `LibraryScreen`, `CollectionScreen`, `SearchScreen` host `TrackActionSheet` but pass **no `onShowInfo`** and have **no `TrackInfoSheet`** | grep: `onShowInfo` absent in those three files |
| `HomeScreen` (Recently Played) renders `AuxenTrackRow` with **no `onLongPress`** and hosts **no sheet at all** | `HomeScreen.kt:141-147` |
| `HomeScreen` album cards **play on tap**: `onClick = { viewModel.playAll(album.tracks) }` — same as their play button | `HomeScreen.kt:127-128` (D3) |
| `loadTrackDetail` already early-returns for non-Tidal (`if (!track.isTidal) return`) | `PlayerViewModel.kt:391` — so a local info sheet shows no spinner and no credits, cleanly |
| `AlbumCard`/`ArtistRow` use plain `clickable(onClick)` — **no long-press** | `AlbumCard.kt:48`, `ArtistRow.kt:41` (Task 7 adds `combinedClickable`; album/artist menus are Tasks 6–8) |
| `AlbumGroup`/`ArtistGroup` both carry `tracks: List<Track>` — Library/Collection/Home/artist-local album+artist cards already pass them to `playAll` | `LibraryGrouping.kt:11-23`; `onPlay = { viewModel.playAll(album.tracks) }` at `LibraryScreen.kt:186`, `CollectionScreen.kt:192`, `HomeScreen.kt`, `ArtistDetailScreen.kt:244,273` — so album/artist play/queue is **synchronous**, no fetch |
| Tidal-only cards pass `onPlay = null` — **no track pool at long-press time** | `SearchScreen.kt:327` (search albums), `ArtistDetailScreen.kt:295` (remote discography); search/similar artist rows are `ArtistLink`, no tracks — these stay tap-only (see *Deferred*) |

**So the real gap is exactly this:**
1. `TrackActionSheet` has no "Go to album" / "Go to artist" entries.
2. The info sheet ("Track info") is unreachable from Library, Collection, Search, and Home.
3. The info sheet shows only Credits + Lyrics — no Details (D1), and is Tidal-gated, so it offers nothing to a local track.
4. Home's Recently Played has no menu, and Home album cards play-on-tap (D3).

This plan closes exactly that (Tasks 1–5). **Tasks 6–8 add the album + artist long-press menus** (D4) — the "add to queue / add to playlist / play next" equivalents at the collection level. `AlbumCard`/`ArtistRow` gain a labelled-haptic long-press (Task 7) exactly as `AuxenTrackRow` does in Task 2, and the two new sheets are backed by four new ViewModel list-methods over the track pool the cards **already hold** (`AlbumGroup`/`ArtistGroup.tracks`). Only the Tidal-only cards — search results, remote discography, similar-artist rows, which have *no* track pool at long-press time — stay tap-only; the honest async path for them is grounded in *Deferred*, not built.

---

## What the desktop `TrackContextMenu` actually has (read, not assumed)

Every row read from `auxen/views/context_menu.py:186-249` on 2026-07-17.

| Desktop entry | Real condition in `_build_menu_model` | Android verdict |
| --- | --- | --- |
| Play | always | **exists** |
| Play Next | always | **exists** |
| Add to Queue | always | **exists** |
| Add to Playlist ▸ (+ New Playlist…) | always | **exists** |
| Add / Remove from Collection | label flips on `is_favorite` | **exists** (and Android's is *correct*; the desktop's is hardcoded wrong in 6 of 9 views) |
| Go to Album | `if track_data["album"] and track_data["artist"]` (`:228`) | **ADD** (Task 4) |
| Go to Artist | `if track_data["artist"]` (`:230`) | **ADD** (Task 4) |
| Go to Track Radio | `if is_tidal and callbacks.get("on_track_radio")` (`:234`) | **DROP** — no Android backend (deferred: *Tidal radio + mixes*) |
| Start Mix | `if is_tidal and callbacks.get("on_track_mix")` (`:237`) | **DROP** — same |
| View Lyrics / Credits | Tidal + callback (`:242+`) | **exists** as the info sheet — becomes "Track info" (Credits + Lyrics + Details) |

> **There is no "Properties" on the desktop TRACK menu.** `TrackContextMenu` builds four sections — playback, organize, nav, extras — none with a properties entry (only `AlbumContextMenu:440` and `ArtistContextMenu:619` have one). D1 does not port a desktop track-Properties entry (there is none); it adds a **Details** section to the info sheet, which is strictly better than the desktop's modal Properties dialog.

---

## Desktop bugs we are NOT porting (traced, not guessed)

1. **Every "is the callback present?" gate is dead.** Each view builds its callbacks dict with a `_noop`-defaulted lambda for every key, and a lambda is always truthy, so `callbacks.get("on_track_radio")` is always true — the effective condition is `is_tidal` alone. Android's model takes explicit `can…: Boolean` flags, so a gate means what it says.
2. **The track favourite label is hardcoded wrong in 6 of 9 desktop views** (`is_favorite=False` in `library.py`, `album_detail.py`, `artist_detail.py`, `search.py`, …; `True` in `collection.py`). **Android already does this right** — every host computes `isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys` off the live flow. Keep it; do not "simplify" to a constant.

---

## Golden risk (read before touching a shared surface)

| Golden | Captured from | Risk here |
| --- | --- | --- |
| `action-sheet-{light,dark}` | `TrackActionSheetContent`, **TIDAL** fixture, `isFavorite=true`, `showPlaylists=false`, **no `onShowInfo`/`onGoToAlbum`/`onGoToArtist`** (`ComponentScreenshotTest.kt:436-448`) | Task 4 adds entries **gated on nullable handlers the fixture does not pass** → `trackActions` returns the same five base entries → **must not move** |
| `track-row-{tidal-favorite,local-explicit}-{light,dark}` | `AuxenTrackRow` (`ComponentScreenshotTest.kt`) | Task 2 adds only a semantics label + a haptic — **neither is pixels** → **must not move** |

There is **no** `TrackInfoSheet` golden (verified: no match for `TrackInfoSheet`/`track-info` in `ui/screenshots/`), so Task 3's Details section moves nothing. There is no `AlbumCard`/`ArtistRow` behavioural change in this plan.

**If a golden moves, a default leaked. Fix the default; do not re-record.** No golden in this plan is expected to change.

---

## Global Constraints

- **Gradle needs JDK 21.** The system JDK breaks this project's Gradle. **EVERY** gradle line carries `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10` and runs from the repo root with `-p android`.
- **Verify honestly.** Never pipe gradle through `tail`/`head` and read `$?` (that is the pipe's status). Read real output for `BUILD SUCCESSFUL`; parse counts from the JUnit XML under `android/app/build/test-results/testDebugUnitTest/`.
- **Git: explicit pathspecs on every `git add`.** Never `git add -A`, never `git add .`, never `git commit -a`. The index is shared with a concurrent agent. `.playwright-mcp/`, `android/.kotlin/`, and `docs/plans/2026-07-13-*.md` must **never** be committed.
- **Internal-v1 only.** The official Tidal v2 stack is **retired** — do not reference `TidalMetadataRepository`, `openapi.tidal.com`, or any v2 file. Credits/lyrics come from the internal-v1 `tracks/{id}/credits` + `tracks/{id}/lyrics` reads already assembled by `Graph.tidal.trackDetail`.
- **No new network calls, no new endpoints.** Every action here is backed by code that already exists (`play`, `playNext`, `enqueue`, `toggleFavorite`, `addToPlaylist`, `createPlaylistAndAdd`, `loadTrackDetail`) or by pure display of fields already on the `Track` model (D1 Details). If an action would need a new endpoint, it is in the *deferred* section, not in a task.
- **Never re-declare an existing type or top-level function.** Kotlin "conflicting declarations" is a compile error. Task 1 Step 2 re-runs a name-collision sweep before writing.
- **Conversely: ADD THE IMPORT.** Kotlin resolves per file. Every task carries an explicit **Imports to add** line.
- **Never log or print `accessToken`/`refreshToken`.** (Nothing here goes near auth; the rule stands.)
- **Unit-test PURE logic only.** `ActionSheetModel.kt` is plain Kotlin and is fully unit-tested. Compose stays outside the unit-test boundary; the sheets get Robolectric behaviour tests in the established `TrackActionSheetUiTest`/`TrackInfoSheetUiTest` style; screens close on an on-device checkpoint.
- **Robolectric concessions carry over verbatim.** Taps inside a `ModalBottomSheet` go through `performSemanticsAction(SemanticsActions.OnClick)`, not `performClick()`, and `assertExists()` not `assertIsDisplayed()`. The full root cause is in `TrackActionSheetUiTest`'s class KDoc. Do not rediscover it.
- **Line numbers are pinned to the 2026-07-17 tree and WILL drift — anchor on the quoted content, never the number.** A concurrent agent commits to these same files. Before any insertion, `grep -n` for the quoted signature and use *that* line.

**Scope: 8 tasks.** Tasks 1–5 are the track menu (inside the original brief's ~4–5). Tasks 6–8 add the album + artist long-press menus (D4) — the user explicitly asked for the collection-level "add to" options, so they are now in scope, and honestly building them takes three more tasks (a pure `albumActions`/`artistActions` model; `AlbumCard`/`ArtistRow` long-press; two new sheets + four ViewModel list-methods + host wiring). **This is above the old ~5 ceiling — stated plainly, because D4 is a real, user-requested addition, not scope creep.** The Tidal-only cards remain deferred (no track pool at long-press; the async path is grounded in *Deferred*).

---

### Task 1: The pure track-action model — where the conditionality lives

Pure Kotlin, no Compose, no Android. The only unit-tested part of the feature and where every entry-visibility rule lives. Task 4's composable renders whatever it returns.

**Key divergence from the old draft, baked in for D1:** "Track info" is **no longer Tidal-gated**. Because the info sheet now carries a Details section drawn from the `Track` itself (format/bitrate/…), it is worth opening for a **local** track too. The gate is *handler presence alone*.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/ActionSheetModel.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/ActionSheetModelTest.kt` (create)

**Interfaces:**
- Consumes: `io.github.auxen.model.Track`, `io.github.auxen.model.Source`.
- Produces: `enum class ActionId`; `data class ActionEntry(id, label)`; `data class AlbumTarget(album, albumArtist)`; `fun albumTargetFor(track: Track): AlbumTarget?`; `fun trackActions(track, isFavorite, canGoToAlbum, canGoToArtist, canShowInfo): List<ActionEntry>`.

- [ ] **Step 1: RED — write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/ui/components/ActionSheetModelTest.kt`. **This fails to compile** — `ActionSheetModel.kt` does not exist, so `ActionId`, `ActionEntry`, `albumTargetFor`, `trackActions` are unresolved. That is the RED: a compile failure naming exactly the symbols Step 2 creates.

```kotlin
package io.github.auxen.ui.components

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun track(
    title: String = "Everlong",
    artist: String = "Foo Fighters",
    album: String? = "The Colour and the Shape",
    albumArtist: String? = null,
    source: Source = Source.LOCAL,
    sourceId: String = "x",
) = Track(
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    source = source,
    sourceId = sourceId,
)

class ActionSheetModelTest {

    private fun ids(entries: List<ActionEntry>) = entries.map { it.id }
    private fun labelOf(entries: List<ActionEntry>, id: ActionId) =
        entries.firstOrNull { it.id == id }?.label

    // ---- albumTargetFor: must reproduce groupAlbums' key EXACTLY ----

    @Test
    fun albumTargetPrefersAlbumArtistOverArtist() {
        // groupAlbums keys on (album, albumArtist ?: artist)
        // (LibraryGrouping.kt:66-70). A compilation track whose artist differs
        // from its albumArtist must resolve to the ALBUM ARTIST or
        // AlbumDetailScreen's lookup (:85) finds no group.
        val t = track(artist = "Nina Simone", albumArtist = "Various Artists")
        assertEquals(
            AlbumTarget("The Colour and the Shape", "Various Artists"),
            albumTargetFor(t),
        )
    }

    @Test
    fun albumTargetFallsBackToArtistWhenAlbumArtistNullOrBlank() {
        assertEquals("Foo Fighters", albumTargetFor(track(albumArtist = null))?.albumArtist)
        assertEquals("Foo Fighters", albumTargetFor(track(albumArtist = "   "))?.albumArtist)
    }

    @Test
    fun albumTargetIsNullWithoutAnAlbum() {
        // Blank and null both -> null. groupAlbums files these under
        // "Unknown Album"; navigating there is useless, so the entry hides.
        assertNull(albumTargetFor(track(album = null)))
        assertNull(albumTargetFor(track(album = "  ")))
    }

    @Test
    fun albumTargetIsNullWhenNoArtistNameAtAll() {
        assertNull(albumTargetFor(track(artist = "", albumArtist = null)))
    }

    // ---- trackActions ----

    @Test
    fun trackActionsBaseSetAndOrder() {
        val entries = trackActions(
            track = track(),
            isFavorite = false,
            canGoToAlbum = false,
            canGoToArtist = false,
            canShowInfo = false,
        )
        // Exactly the five always-on entries, in the order the sheet renders
        // today. This order IS the action-sheet golden; changing it moves it.
        assertEquals(
            listOf(
                ActionId.PLAY,
                ActionId.PLAY_NEXT,
                ActionId.ENQUEUE,
                ActionId.TOGGLE_FAVORITE,
                ActionId.ADD_TO_PLAYLIST,
            ),
            ids(entries),
        )
    }

    @Test
    fun trackFavoriteLabelFlips() {
        val fav = trackActions(track(), true, false, false, false)
        val notFav = trackActions(track(), false, false, false, false)
        assertEquals("Remove from favorites", labelOf(fav, ActionId.TOGGLE_FAVORITE))
        assertEquals("Add to favorites", labelOf(notFav, ActionId.TOGGLE_FAVORITE))
    }

    @Test
    fun goToAlbumNeedsBothTheHandlerAndAnAlbum() {
        assertEquals(
            true,
            ActionId.GO_TO_ALBUM in ids(trackActions(track(), false, canGoToAlbum = true, canGoToArtist = false, canShowInfo = false)),
        )
        // Handler present but NO album -> hidden (nothing to navigate to).
        assertEquals(
            false,
            ActionId.GO_TO_ALBUM in ids(trackActions(track(album = null), false, canGoToAlbum = true, canGoToArtist = false, canShowInfo = false)),
        )
        // Album present but host offers no handler -> hidden (no dead action).
        assertEquals(
            false,
            ActionId.GO_TO_ALBUM in ids(trackActions(track(), false, canGoToAlbum = false, canGoToArtist = false, canShowInfo = false)),
        )
    }

    @Test
    fun goToArtistNeedsBothTheHandlerAndAnArtistName() {
        assertEquals(
            true,
            ActionId.GO_TO_ARTIST in ids(trackActions(track(), false, false, canGoToArtist = true, canShowInfo = false)),
        )
        assertEquals(
            false,
            ActionId.GO_TO_ARTIST in ids(trackActions(track(artist = "  "), false, false, canGoToArtist = true, canShowInfo = false)),
        )
        assertEquals(
            false,
            ActionId.GO_TO_ARTIST in ids(trackActions(track(), false, false, canGoToArtist = false, canShowInfo = false)),
        )
    }

    @Test
    fun trackInfoOfferedForEitherSourceWhenHostCanShowIt() {
        // D1: the info sheet now carries a Details section drawn from the Track
        // itself, so it is useful for a LOCAL track too — not Tidal-only.
        assertEquals(true, ActionId.SHOW_INFO in ids(trackActions(track(source = Source.TIDAL), false, false, false, canShowInfo = true)))
        assertEquals(true, ActionId.SHOW_INFO in ids(trackActions(track(source = Source.LOCAL), false, false, false, canShowInfo = true)))
        // No handler -> no dead row (also what keeps the action-sheet golden stable).
        assertEquals(false, ActionId.SHOW_INFO in ids(trackActions(track(source = Source.TIDAL), false, false, false, canShowInfo = false)))
    }

    @Test
    fun trackNavEntriesSitBetweenPlaylistAndInfo() {
        val entries = trackActions(
            track = track(source = Source.TIDAL),
            isFavorite = true,
            canGoToAlbum = true,
            canGoToArtist = true,
            canShowInfo = true,
        )
        assertEquals(
            listOf(
                ActionId.PLAY,
                ActionId.PLAY_NEXT,
                ActionId.ENQUEUE,
                ActionId.TOGGLE_FAVORITE,
                ActionId.ADD_TO_PLAYLIST,
                ActionId.GO_TO_ALBUM,
                ActionId.GO_TO_ARTIST,
                ActionId.SHOW_INFO,
            ),
            ids(entries),
        )
    }
}
```

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*ActionSheetModelTest*'
```

Expected: **compilation failure** in `ActionSheetModelTest.kt` — `Unresolved reference: ActionId` / `ActionEntry` / `albumTargetFor` / `trackActions`. Any other failure means the fixture is wrong; fix it before Step 2.

- [ ] **Step 2: GREEN — create `ActionSheetModel.kt`**

**Name-collision check before writing** (the never-re-declare rule). Run and expect **zero** hits:

```bash
grep -rn "enum class ActionId\|data class ActionEntry\|data class AlbumTarget\|fun albumTargetFor\|fun trackActions" --include="*.kt" android/
```

Create `android/app/src/main/kotlin/io/github/auxen/ui/components/ActionSheetModel.kt`:

```kotlin
package io.github.auxen.ui.components

import io.github.auxen.model.Track

/**
 * Which entries the track long-press menu shows — the Android port of the
 * desktop's `TrackContextMenu._build_menu_model` (`auxen/views/context_menu.py:186-249`),
 * as PURE functions.
 *
 * Pure and free of Compose/Android on purpose: entry visibility is where the
 * conditionality bugs live, and the desktop proves it — its callback-presence
 * gates never fire (see the plan's *Desktop bugs we are not porting*).
 * Everything here is unit-tested; [TrackActionSheetContent] only renders the
 * list it is handed.
 *
 * **The `can…` flags mean "the host can service this action"** — real Booleans
 * passed from a host that knows whether it has a handler, not the desktop's
 * always-truthy `_noop`-lambda gates.
 */
enum class ActionId {
    PLAY,
    PLAY_NEXT,
    ENQUEUE,
    TOGGLE_FAVORITE,
    ADD_TO_PLAYLIST,
    GO_TO_ALBUM,
    GO_TO_ARTIST,
    SHOW_INFO,
}

/** One rendered menu row: a stable [id] for dispatch, a display [label]. */
data class ActionEntry(val id: ActionId, val label: String)

/**
 * The (album, albumArtist) pair a "Go to album" navigation must target.
 *
 * **This MUST reproduce `groupAlbums`' grouping key exactly**
 * (`data/LibraryGrouping.kt:66-70`): `AlbumDetailScreen` finds its group with
 * `groupAlbums(pool).firstOrNull { it.album == album && it.albumArtist == artist }`
 * (`AlbumDetailScreen.kt:85`). Pass `track.artist` where `groupAlbums` used
 * `track.albumArtist ?: track.artist` and the lookup silently finds nothing —
 * the user lands on an empty album screen. Hence `albumArtist ?: artist`.
 *
 * Divergence from `groupAlbums`, deliberately: it files a blank/absent album
 * under "Unknown Album"; this returns null. The desktop hides "Go to Album" for
 * a track with no album (`context_menu.py:228`) and so do we.
 */
data class AlbumTarget(val album: String, val albumArtist: String)

fun albumTargetFor(track: Track): AlbumTarget? {
    val album = track.album?.takeIf { it.isNotBlank() } ?: return null
    val albumArtist = track.albumArtist?.takeIf { it.isNotBlank() }
        ?: track.artist.takeIf { it.isNotBlank() }
        ?: return null
    return AlbumTarget(album, albumArtist)
}

/**
 * Track menu — desktop `TrackContextMenu` (`context_menu.py:186-249`).
 *
 * Order is load-bearing: the first five entries are what the `action-sheet-*`
 * goldens captured. The two nav entries slot between "Add to playlist" and
 * "Track info", mirroring the desktop's section order (playback → organize →
 * nav → extras).
 *
 * [canShowInfo] is NOT Tidal-gated (D1): the info sheet carries a Details
 * section read straight off this [track], so it is offered for a local track
 * too. Dropped vs. the desktop, for want of a backend: "Go to Track Radio" and
 * "Start Mix" (see the plan's *Deferred*).
 */
fun trackActions(
    track: Track,
    isFavorite: Boolean,
    canGoToAlbum: Boolean,
    canGoToArtist: Boolean,
    canShowInfo: Boolean,
): List<ActionEntry> = buildList {
    add(ActionEntry(ActionId.PLAY, "Play"))
    add(ActionEntry(ActionId.PLAY_NEXT, "Play next"))
    add(ActionEntry(ActionId.ENQUEUE, "Add to queue"))
    add(
        ActionEntry(
            ActionId.TOGGLE_FAVORITE,
            if (isFavorite) "Remove from favorites" else "Add to favorites",
        ),
    )
    add(ActionEntry(ActionId.ADD_TO_PLAYLIST, "Add to playlist"))
    if (canGoToAlbum && albumTargetFor(track) != null) {
        add(ActionEntry(ActionId.GO_TO_ALBUM, "Go to album"))
    }
    if (canGoToArtist && track.artist.isNotBlank()) {
        add(ActionEntry(ActionId.GO_TO_ARTIST, "Go to artist"))
    }
    if (canShowInfo) {
        add(ActionEntry(ActionId.SHOW_INFO, "Track info"))
    }
}
```

**Imports to add:** the file is new; it needs exactly `io.github.auxen.model.Track`. Nothing else — `buildList` is stdlib.

- [ ] **Step 3: Confirm GREEN**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*ActionSheetModelTest*'
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  android/app/build/test-results/testDebugUnitTest/TEST-io.github.auxen.ui.components.ActionSheetModelTest.xml
```

Expected: `BUILD SUCCESSFUL`, `failures="0" errors="0"`, `tests="9"`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/ActionSheetModel.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/ActionSheetModelTest.kt
git commit -m "feat(android): pure track-action model with go-to-album/artist + info

Ports the desktop TrackContextMenu's entry-visibility rules as a pure,
unit-tested function. Entry gates take explicit can* Booleans rather than
the desktop's always-truthy callback-presence gates. Track info is no longer
Tidal-gated: with the Details section (D1) it is useful for local tracks too.
albumTargetFor reproduces groupAlbums' key so go-to-album resolves the right
group.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 2: `AuxenTrackRow` — make the long-press reachable and felt

A long-press-only affordance is invisible and, today, **unreachable by TalkBack**: `AuxenTrackRow.kt:65` passes `onLongClick` with no `onLongClickLabel`, so TalkBack announces no long-press action, and there is no haptic.

**Verified against Foundation 1.7.6 (this project's version):** `combinedClickable` does **not** self-haptic in 1.7.6 (auto-haptics landed later), so the manual call is required and will not double-buzz. `combinedClickable`'s `onLongClickLabel: String?` parameter and `HapticFeedbackType.LongPress` / `HapticFeedback.performHapticFeedback` all exist in 1.7.6.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/AuxenTrackRow.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/AuxenTrackRowUiTest.kt` (add two cases)

**Interfaces:**
- Consumes: `LocalHapticFeedback`, `HapticFeedbackType` (Compose UI 1.7.6).
- Produces: no signature change — behaviour only.

- [ ] **Step 1: RED — assert the missing a11y affordance**

Add to `AuxenTrackRowUiTest.kt` (which already has a `track(...)` helper and `AuxenTheme` in scope). **The first fails**: the row sets no `onLongClickLabel`, so the action's label is null.

```kotlin
    @Test
    fun longPressExposesALabelledSemanticsActionWhenHandlerPresent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AuxenTrackRow(
                    track = track(title = "My Hero"),
                    isFavorite = false,
                    onPlay = {},
                    onToggleFavorite = {},
                    onLongPress = {},
                )
            }
        }

        val node = compose.onNodeWithText("My Hero").fetchSemanticsNode()
        assertEquals("Show track actions", node.config[SemanticsActions.OnLongClick].label)
    }

    /** No handler -> no long-click action advertised, so no dead affordance. */
    @Test
    fun noLongClickActionWhenHandlerAbsent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AuxenTrackRow(
                    track = track(title = "My Hero"),
                    isFavorite = false,
                    onPlay = {},
                    onToggleFavorite = {},
                )
            }
        }

        val node = compose.onNodeWithText("My Hero").fetchSemanticsNode()
        assertEquals(false, node.config.contains(SemanticsActions.OnLongClick))
    }
```

**Imports to add to `AuxenTrackRowUiTest.kt`:** `androidx.compose.ui.semantics.SemanticsActions`. (`assertEquals`, `onNodeWithText`, `fetchSemanticsNode`, `AuxenTheme`, and `track(...)` are already present.)

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*AuxenTrackRowUiTest*'
```

Expected RED: `longPressExposesALabelledSemanticsActionWhenHandlerPresent` fails `expected:<Show track actions> but was:<null>`. `noLongClickActionWhenHandlerAbsent` already passes (a regression guard for Step 2's `?.let`).

- [ ] **Step 2: GREEN — label + haptic**

In `AuxenTrackRow.kt`, add the haptics handle at the top of the composable body (before `Row`):

```kotlin
    val haptics = LocalHapticFeedback.current
```

and replace the `combinedClickable` call at `:65`:

```kotlin
            .combinedClickable(
                onClick = onPlay,
                // Both are gated on the SAME null check as onLongClick, so a
                // host that offers no sheet advertises no long-press action and
                // buzzes for nothing. Foundation 1.7.6's combinedClickable does
                // NOT self-haptic — this call is required and does not double-buzz.
                onLongClickLabel = onLongPress?.let { "Show track actions" },
                onLongClick = onLongPress?.let { handler ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        handler()
                    }
                },
            )
```

**Imports to add to `AuxenTrackRow.kt`:** `androidx.compose.ui.hapticfeedback.HapticFeedbackType` and `androidx.compose.ui.platform.LocalHapticFeedback`. (`combinedClickable`/`ExperimentalFoundationApi` are already imported at `:5`/`:3`.)

- [ ] **Step 3: Confirm GREEN + goldens unmoved**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest verifyRoborazziDebug
```

Expected: `BUILD SUCCESSFUL`. **`track-row-*` goldens must NOT move** — semantics and haptics are not pixels. A diff means something else changed; find and revert it. Do not re-record.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/AuxenTrackRow.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/AuxenTrackRowUiTest.kt
git commit -m "feat(android): labelled, haptic long-press on AuxenTrackRow

The long-press action sheet was unreachable by TalkBack (no onLongClickLabel)
and gave no haptic. Both are gated on the same null handler check, so a host
with no sheet advertises nothing. Foundation 1.7.6's combinedClickable does
not self-haptic; verified, no double-buzz.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 3: `TrackInfoSheet` gains a Details section (D1)

Fold the desktop's "Properties" into the info sheet as a **Details** section — format / bitrate / sample rate / bit depth / source — read straight off the `Track` (`Track.kt:34-37`, `Source`). No new fetch. This is what makes the info sheet worth opening on a **local** track, and it is why Task 1 dropped the Tidal gate.

**What the model actually carries (verified, so the section shows only real data):** `format: String?`, `bitrateKbps: Int?`, `sampleRateHz: Int?`, `bitDepth: Int?`, `source: Source`. The local MediaStore scan populates `format` + `bitrateKbps` only (`LocalProvider.kt:147-148`); `sampleRateHz`/`bitDepth` are typically null for local tracks, so those rows self-hide. **There is no file-path field** — a local track's `sourceId` is a MediaStore `_ID`, not a path (`LocalProvider.kt:141`) — so Details shows **no path row** (a path would need a new MediaStore query, which the "no new fetch" constraint forbids; see the Q on file paths).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/TrackInfoSheet.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/AlbumDetailScreen.kt` (caller: pass `track`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt` (caller: pass `track`)
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/TrackInfoSheetUiTest.kt`

**Interfaces:**
- Produces: `TrackInfoSheet(track: Track, detail, loading, onDismiss)`; `TrackInfoSheetContent(track: Track, detail, loading)`.

- [ ] **Step 1: RED — Details section does not exist**

Add to `TrackInfoSheetUiTest.kt`. **This fails to compile**: `TrackInfoSheetContent` has no `track` parameter — `Cannot find a parameter with this name: track`.

```kotlin
    @Test
    fun detailsSectionRendersLocalTechnicalMetadata() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackInfoSheetContent(
                    track = Track(
                        title = "My Hero",
                        artist = "Foo Fighters",
                        source = Source.LOCAL,
                        sourceId = "1",
                        format = "FLAC",
                        bitrateKbps = 1000,
                    ),
                    detail = null,
                    loading = false,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Details").assertExists()
        compose.onNodeWithText("FLAC").assertExists()
        compose.onNodeWithText("Local").assertExists()
        // A local track has no catalog-keyed credits/lyrics block.
        compose.onNodeWithText("Lyrics").assertDoesNotExist()
    }
```

**Imports to add to `TrackInfoSheetUiTest.kt`:** `io.github.auxen.model.Source`, `io.github.auxen.model.Track`. **Do NOT add `assertDoesNotExist`/`assertExists` imports** — they are member functions of `SemanticsNodeInteraction`, not importable top-level symbols; this file already calls `.assertExists()`/`.assertDoesNotExist()` un-imported, and adding `import androidx.compose.ui.test.assertExists` (or `assertDoesNotExist`) is an unresolved-reference compile error.

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TrackInfoSheetUiTest*'
```

Expected RED: `Cannot find a parameter with this name: track`.

- [ ] **Step 2: GREEN — Details section + `Track` param**

Rewrite `TrackInfoSheet.kt` so both the wrapper and the content take the `Track`. The credits/lyrics block is now gated on `track.isTidal` (a local track has nothing to fetch — `loadTrackDetail` already early-returns for it, `PlayerViewModel.kt:391`), and the Details section always renders.

Replace the wrapper and content:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackInfoSheet(
    track: Track,
    detail: TidalTrackDetail?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        TrackInfoSheetContent(track = track, detail = detail, loading = loading)
    }
}

@Composable
internal fun TrackInfoSheetContent(track: Track, detail: TidalTrackDetail?, loading: Boolean) {
    Column(
        Modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            track.title.takeIf { it.isNotBlank() } ?: "Track info",
            style = MaterialTheme.typography.titleMedium,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // Credits + lyrics are catalog-keyed (Tidal only). A LOCAL track has
        // nothing to fetch, so the whole block is skipped and the sheet shows
        // Details alone — which is exactly why "Track info" is now offered for
        // local tracks (D1).
        if (track.isTidal) {
            when {
                detail != null -> {
                    if (detail.credits.isNotEmpty()) {
                        Text("Credits", style = MaterialTheme.typography.titleSmall)
                        detail.credits.forEach { credit ->
                            Text(
                                credit.role,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                credit.names.joinToString(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("Lyrics", style = MaterialTheme.typography.titleSmall)
                    Text(
                        detail.lyricsText
                            ?: (if (detail.lyricsSynced != null) "Synced lyrics only." else "No lyrics available."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                loading -> LoadingState()
                else -> Text("No credits or lyrics available.")
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("Details", style = MaterialTheme.typography.titleSmall)
        DetailRow("Format", track.format)
        DetailRow("Bitrate", track.bitrateKbps?.let { "$it kbps" })
        DetailRow("Sample rate", track.sampleRateHz?.let { "%.1f kHz".format(it / 1000.0) })
        DetailRow("Bit depth", track.bitDepth?.let { "$it-bit" })
        DetailRow("Source", if (track.isTidal) "Tidal" else "Local")
    }
}

/** One label/value Details row; renders nothing when the value is absent/blank. */
@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
```

**Imports to add to `TrackInfoSheet.kt`:** `androidx.compose.foundation.layout.Row`, `androidx.compose.foundation.layout.fillMaxWidth`, `androidx.compose.foundation.layout.width`, `io.github.auxen.model.Track`. (`Column`, `Spacer`, `height`, `padding`, `verticalScroll`, `rememberScrollState`, `HorizontalDivider`, `MaterialTheme`, `Text` are already imported; `LoadingState` is same-package.)

Now fix the existing test helpers and the two assertions the reshaping changes. In `TrackInfoSheetUiTest.kt`, add a Tidal fixture and thread it through both helpers:

```kotlin
    private val tidalTrack = Track(
        title = "Nightcall",
        artist = "Kavinsky",
        source = Source.TIDAL,
        sourceId = "t1",
        format = "FLAC",
        bitrateKbps = 1411,
    )

    private fun show(detail: TidalTrackDetail?, loading: Boolean = false, track: Track = tidalTrack) {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackInfoSheetContent(track = track, detail = detail, loading = loading)
            }
        }
        compose.waitForIdle()
    }

    private fun showLoading() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackInfoSheetContent(track = tidalTrack, detail = null, loading = true)
            }
        }
    }
```

Three existing tests assert on behaviour the reshaping changes; update them:

```kotlin
    @Test
    fun settledWithNoDetailShowsTheFallbackNotice() {
        show(null, loading = false)

        // The credits/lyrics block settles on its notice; Details still renders.
        compose.onNodeWithText("No credits or lyrics available.").assertExists()
        compose.onNodeWithText("Details").assertExists()
        compose.onNodeWithText("Lyrics").assertDoesNotExist()
    }

    @Test
    fun blankTitleFallsBackToThePlaceholderHeader() {
        // The header now comes from the Track's own title; a blank one falls back.
        show(detail(), track = tidalTrack.copy(title = ""))

        compose.onNodeWithText("Track info").assertExists()
    }

    @Test
    fun loadingWithNoDetailShowsTheSpinnerAndNotTheFallbackNotice() {
        // NOT unchanged: the header now comes from `track.title` (D1), and the
        // settled-notice text changed to "No credits or lyrics available." — both
        // assertions this test used to make had to move. showLoading() renders the
        // tidalTrack (title "Nightcall") with detail == null and loading == true,
        // so the spinner shows and the settled notice does not.
        showLoading()

        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        ).assertExists()
        // Header is the Track's own title now, not the detail's — pins that the
        // body composed while the spinner is up.
        compose.onNodeWithText("Nightcall").assertExists()
        // The settled notice must NOT show while loading (the spinner is up), and
        // its text changed with the Details reshaping.
        compose.onNodeWithText("No credits or lyrics available.").assertDoesNotExist()
        // Title + divider + a wrapped 40dp spinner + the small Details block stays
        // well bounded; an unbounded-height fillMaxSize leak would measure orders
        // of magnitude past this. (Compose 1.7.6's bounds API has
        // assertHeightIsAtLeast/EqualTo but no AtMost, hence the explicit read.)
        val height = compose.onRoot().getUnclippedBoundsInRoot().height
        assertTrue("spinner frame measured $height — fillMaxSize leaked", height < 600.dp)
    }
```

(The other existing tests — `creditsRenderAsRoleHeaderWithJoinedNames`, `lyricsTextRenders`, `syncedOnlyLyricsShowTheSyncedNote`, `absentLyricsShowThePlaceholder`, `creditsSectionIsHiddenWhenThereAreNone` — all call `show(detail())` on the default Tidal fixture and are unchanged. The header now reads `track.title`, but the default `tidalTrack.title` is `"Nightcall"` — the same string `detail().title` carried — so `creditsRenderAsRoleHeaderWithJoinedNames`' `onNodeWithText("Nightcall")` still finds the header. Note `loadingWithNoDetailShows…` is **NOT** in this list: it calls `showLoading()`, whose header now renders `"Nightcall"` instead of the old `"Track info"` placeholder, so it was rewritten above.)

Update the two callers to pass `track`. `AlbumDetailScreen.kt:262` and `ArtistDetailScreen.kt:403` are inside `infoTrack?.let { track -> … }`, so `track` is already in scope:

```kotlin
        TrackInfoSheet(
            track = track,
            detail = detail,
            loading = detailLoading,
            onDismiss = { infoTrack = null; viewModel.clearTrackDetail() },
        )
```

**Imports to add to `AlbumDetailScreen.kt` / `ArtistDetailScreen.kt`:** none — `track` is a local already in scope.

- [ ] **Step 3: Confirm GREEN**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TrackInfoSheetUiTest*'
```

Expected: `BUILD SUCCESSFUL`, all `TrackInfoSheetUiTest` cases green (the new Details case + the three updated cases — `settledWithNoDetailShowsTheFallbackNotice`, `blankTitleFallsBackToThePlaceholderHeader`, `loadingWithNoDetailShowsTheSpinnerAndNotTheFallbackNotice` — + the unchanged credits/lyrics cases).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/TrackInfoSheet.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/AlbumDetailScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/TrackInfoSheetUiTest.kt
git commit -m "feat(android): Details section in TrackInfoSheet (D1)

Folds the desktop's Properties into the info sheet as a Details block —
format/bitrate/sample rate/bit depth/source — read off the Track model, no
new fetch. Rows self-hide when a field is null (local scans populate only
format+bitrate). Credits/lyrics stay Tidal-gated; Details always renders, so
the sheet is now worth opening on a local track. No file-path row: sourceId
is a MediaStore id, not a path.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 4: Extend `TrackActionSheet` — model render, go-to nav, info everywhere, seed arg, Home sheet

Render `TrackActionSheetContent` from Task 1's model, add "Go to album" / "Go to artist", rename the info entry to **"Track info"** and drop its Tidal gate, then wire the info sheet + nav on the three hosts that lack them (**Library, Collection, Search**) and give **Home** a menu for the first time. Carries the originating track id as an optional `seedTrackId` nav arg so "Go to album" from a Tidal-only track resolves its remote tracklist instead of dead-ending.

> **This is the largest task** — one coherent change (finish the shared menu everywhere) across ~9 files. Work host-by-host and **compile after each**; the failure mode is a per-file missing import.

**Two real problems it fixes, both traced:**

1. **`HomeScreen` Recently Played has no menu.** `HomeScreen.kt:141-147` renders `AuxenTrackRow` with no `onLongPress` and hosts no sheet.
2. **"Go to album" from a Tidal-only track would dead-end.** `AlbumDetailScreen` derives its remote seed from local tracks only (`tracks.firstOrNull { it.isTidal }?.sourceId`, `:95`, over `group?.tracks ?: emptyList()`, `:86`). Navigate there from a Tidal **search result** with no local group → `tracks` empty → `seedTidalTrackId` null → `loadAlbumDetail(album, artist, null, null)` takes its early return (`PlayerViewModel.kt:330-331`, `if (tidalAlbumId == null && seedTidalTrackId == null)`) → the screen settles on "no tracks". **Fix:** carry the track's id as an optional `seedTrackId` nav arg; `loadAlbumDetail` already resolves the album id from a seed via `trackRelations`. The **artist** route needs no seed — `loadArtistDetail` has no early return and falls through to `searchArtistIdExact(name)`.

> **TRAP — `topBarTitle` matches route strings literally.** `MainActivity.kt:86-100` maps `"album/{album}/{artist}?tidalAlbumId={tidalAlbumId}"` → `"Album"` by exact match on the registered route pattern. Adding an argument changes the pattern; **update `topBarTitle:87` in the same edit** or the Album top bar renders `""`.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/TrackActionSheet.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (route + `albumRoute` + `topBarTitle` + host wiring)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/AlbumDetailScreen.kt` (accept + prefer the seed arg; go-to-artist)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt` (go-to-album)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt`, `.../CollectionScreen.kt`, `.../SearchScreen.kt` (info sheet + nav)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt` (whole sheet + info + nav)
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/TrackActionSheetUiTest.kt`

**Interfaces:**
- Consumes: `ActionId`/`ActionEntry`/`trackActions`/`albumTargetFor` (Task 1).
- Produces: `TrackActionSheet(..., onGoToAlbum: (() -> Unit)? = null, onGoToArtist: (() -> Unit)? = null)` (also on `TrackActionSheetContent`); nav route `album/{album}/{artist}?tidalAlbumId={tidalAlbumId}&seedTrackId={seedTrackId}`; `AlbumDetailScreen(..., seedTrackId: String? = null)`; a uniform host lambda `onOpenAlbumForTrack: (album: String, artist: String, seedTrackId: String?) -> Unit`.

- [ ] **Step 1: RED — the nav entries + the renamed info label do not exist**

The info label rename (from "Credits & lyrics" to "Track info") and the widened gate (local tracks now show it) change three existing assertions, and the two nav entries are new. Update `TrackActionSheetUiTest.kt`.

First give the existing `track(...)` helper (`:19-24`) an album so a go-to-album test has something to resolve:

```kotlin
private fun track(
    title: String = "Everlong",
    artist: String = "Foo Fighters",
    album: String? = "The Colour and the Shape",
    source: Source = Source.LOCAL,
    sourceId: String = "x",
) = Track(title = title, artist = artist, album = album, source = source, sourceId = sourceId)
```

Rewrite the three "Credits & lyrics" tests (`creditsActionShownForTidalTrackAndInvokesHandler`, `creditsActionAbsentForLocalTrack`, `creditsActionAbsentWhenHostProvidesNoHandler`) — the middle one **inverts** (D1: a local track WITH a handler now shows the entry):

```kotlin
    @Test
    fun trackInfoActionShownAndInvokesHandler() {
        var infoShown = 0
        var dismissed = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(source = Source.TIDAL),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = { dismissed++ },
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                    onShowInfo = { infoShown++ },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Track info").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, infoShown)
        assertEquals(1, dismissed)
    }

    @Test
    fun trackInfoActionShownForLocalTrackWithHandler() {
        // D1: the Details section makes the info sheet useful for local tracks.
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(source = Source.LOCAL),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                    onShowInfo = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Track info").assertExists()
    }

    @Test
    fun trackInfoActionAbsentWhenHostProvidesNoHandler() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(source = Source.TIDAL),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                )
            }
        }
        compose.waitForIdle()

        // No handler -> no dead row; also what keeps the action-sheet golden stable.
        compose.onNodeWithText("Track info").assertDoesNotExist()
    }
```

Add the two nav-entry tests (these fail to compile first — `TrackActionSheet` has no `onGoToAlbum`/`onGoToArtist`):

```kotlin
    @Test
    fun goToAlbumAndArtistShownWithHandlersAndInvokeThem() {
        var album = 0
        var artist = 0
        var dismissed = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = { dismissed++ },
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                    onGoToAlbum = { album++ },
                    onGoToArtist = { artist++ },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Go to album").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, album)
        assertEquals(1, dismissed)

        compose.onNodeWithText("Go to artist").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, artist)
        assertEquals(2, dismissed)
    }

    @Test
    fun goToAlbumAbsentForTrackWithNoAlbum() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track().copy(album = null),
                    isFavorite = false,
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                    onGoToAlbum = {},
                    onGoToArtist = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Go to album").assertDoesNotExist()
        compose.onNodeWithText("Go to artist").assertExists()
    }
```

**Imports to add to `TrackActionSheetUiTest.kt`:** none. `assertDoesNotExist`/`assertExists` are member functions of `SemanticsNodeInteraction`, not importable top-level symbols — this file already calls them un-imported (e.g. `:137`, `:139`), and adding an import for either is an unresolved-reference compile error. (`SemanticsActions`, `performSemanticsAction`, `onNodeWithText`, `assertEquals`, `Source`, `Track`, `AuxenTheme` are all already present.)

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*TrackActionSheetUiTest*'
```

Expected RED: compile failure `Cannot find a parameter with this name: onGoToAlbum`.

- [ ] **Step 2: GREEN — render `TrackActionSheetContent` from the model**

Add `onGoToAlbum`/`onGoToArtist` to **both** `TrackActionSheet` and `TrackActionSheetContent`, and replace the `if (!showPlaylists) { … }` root-action block (`TrackActionSheet.kt:157-180`) with a model-driven render.

`TrackActionSheetContent`'s new signature and root block:

```kotlin
@Composable
internal fun TrackActionSheetContent(
    track: Track,
    isFavorite: Boolean,
    playlists: List<PlaylistEntity>,
    showPlaylists: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onShowPlaylists: () -> Unit,
    onNewPlaylist: () -> Unit,
    onShowInfo: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            track.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (!showPlaylists) {
            // Which entries appear is decided by the PURE model (unit-tested in
            // ActionSheetModelTest); this only maps id -> icon + handler.
            val entries = trackActions(
                track = track,
                isFavorite = isFavorite,
                canGoToAlbum = onGoToAlbum != null,
                canGoToArtist = onGoToArtist != null,
                canShowInfo = onShowInfo != null,
            )
            entries.forEach { entry ->
                when (entry.id) {
                    ActionId.PLAY ->
                        SheetAction(entry.label, { Icon(Icons.Filled.PlayArrow, null) }) { onPlay() }
                    ActionId.PLAY_NEXT ->
                        SheetAction(entry.label, { Icon(Icons.Filled.SkipNext, null) }) { onPlayNext() }
                    ActionId.ENQUEUE ->
                        SheetAction(entry.label, { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }) { onEnqueue() }
                    ActionId.TOGGLE_FAVORITE ->
                        SheetAction(
                            entry.label,
                            {
                                Icon(
                                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    null,
                                    tint = if (isFavorite) AuxenColors.FavoriteRed else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                        ) { onToggleFavorite() }
                    ActionId.ADD_TO_PLAYLIST ->
                        SheetAction(entry.label, { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }) { onShowPlaylists() }
                    ActionId.GO_TO_ALBUM ->
                        SheetAction(entry.label, { Icon(Icons.Filled.Album, null) }) { onGoToAlbum?.invoke() }
                    ActionId.GO_TO_ARTIST ->
                        SheetAction(entry.label, { Icon(Icons.Filled.Person, null) }) { onGoToArtist?.invoke() }
                    ActionId.SHOW_INFO ->
                        SheetAction(entry.label, { Icon(Icons.Filled.Info, null) }) { onShowInfo?.invoke() }
                }
            }
        } else {
            SheetAction("New playlist…", { Icon(Icons.Filled.Add, null) }) { onNewPlaylist() }
            playlists.forEach { playlist ->
                SheetAction(
                    playlist.name,
                    {
                        val dotColor = remember(playlist.color) {
                            playlist.color
                                ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                                ?: AuxenColors.AmberPrimary
                        }
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(dotColor, CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                    },
                ) { onAddToPlaylist(playlist.id) }
            }
        }
    }
}
```

(The `when` is exhaustive over the 8-entry `ActionId` enum, so no `else` branch is needed.)

In `TrackActionSheet`, thread the two new handlers through the same dismiss-first wrapper the existing `onShowInfo` uses (`:89`), and add them to the parameter list after `onShowInfo:67`:

```kotlin
            onShowInfo = onShowInfo?.let { { it(); onDismiss() } },
            onGoToAlbum = onGoToAlbum?.let { { it(); onDismiss() } },
            onGoToArtist = onGoToArtist?.let { { it(); onDismiss() } },
```

```kotlin
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
```

**Imports to add to `TrackActionSheet.kt`:** `androidx.compose.material.icons.filled.Album` and `androidx.compose.material.icons.filled.Person`. (`Icons.Filled.Info` is already imported at `:17`; `ActionId`/`trackActions` are same-package.)

- [ ] **Step 3: Add the seed nav arg (route + `albumRoute` + `topBarTitle` + `AlbumDetailScreen`)**

In `MainActivity.kt`, extend `albumRoute` (`:157-158`):

```kotlin
    fun albumRoute(album: String, artist: String, tidalAlbumId: String? = null, seedTrackId: String? = null): String =
        "album/${Uri.encode(album)}/${Uri.encode(artist)}" +
            listOfNotNull(
                tidalAlbumId?.let { "tidalAlbumId=$it" },
                seedTrackId?.let { "seedTrackId=$it" },
            ).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
```

Update `topBarTitle:87` (matches literally):

```kotlin
    "album/{album}/{artist}?tidalAlbumId={tidalAlbumId}&seedTrackId={seedTrackId}" -> "Album"
```

Register the argument at the album `composable` (`:322-333`):

```kotlin
                    composable(
                        "album/{album}/{artist}?tidalAlbumId={tidalAlbumId}&seedTrackId={seedTrackId}",
                        arguments = listOf(
                            navArgument("tidalAlbumId") { nullable = true; defaultValue = null },
                            navArgument("seedTrackId") { nullable = true; defaultValue = null },
                        ),
                    ) { backStack ->
                        AlbumDetailScreen(
                            viewModel,
                            album = backStack.arguments?.getString("album").orEmpty(),
                            artist = backStack.arguments?.getString("artist").orEmpty(),
                            tidalAlbumId = backStack.arguments?.getString("tidalAlbumId"),
                            seedTrackId = backStack.arguments?.getString("seedTrackId"),
                            onOpenArtist = { name, id -> navController.navigate(artistRoute(name, id)) },
                        )
                    }
```

In `AlbumDetailScreen.kt`, accept the arg and prefer it over the local-derived seed (`:62-68`, `:95-98`):

```kotlin
fun AlbumDetailScreen(
    viewModel: PlayerViewModel,
    album: String,
    artist: String,
    tidalAlbumId: String? = null,
    seedTrackId: String? = null,
    onOpenArtist: (name: String, tidalArtistId: String?) -> Unit,
) {
```

```kotlin
    // The nav-arg seed wins: it is the Tidal track the user long-pressed, and
    // the ONLY seed available when this album has no local group (a "Go to
    // album" from a Tidal search result). Without it, loadAlbumDetail takes its
    // early return and the screen settles on "no tracks". The local fallback
    // keeps every existing card-tap navigation (which passes no seed) unchanged.
    val seedTidalTrackId = seedTrackId ?: tracks.firstOrNull { it.isTidal }?.sourceId
    LaunchedEffect(album, artist, tidalAlbumId, seedTidalTrackId) {
        viewModel.loadAlbumDetail(album, artist, tidalAlbumId, seedTidalTrackId)
    }
```

**Imports to add:** none — `AlbumDetailScreen.kt` gains a `String?` param; `MainActivity.kt` already imports `navArgument` (`:60`) and `Uri`.

- [ ] **Step 4: Wire the hosts**

Each host already holds `sheetTrack` and computes `favoriteKeys`/`playlists`. The **album target must come from `albumTargetFor`**, never `track.album`/`track.artist` directly.

**`LibraryScreen` / `CollectionScreen`** — reuse their existing `onOpenArtist: (String)` for go-to-artist; take one new lambda for the seed-carrying go-to-album; add the info sheet. Add to each signature (after the existing `onOpenArtist`):

```kotlin
    onOpenAlbumForTrack: (album: String, artist: String, seedTrackId: String?) -> Unit,
```

Beside `var sheetTrack`:

```kotlin
    var infoTrack by remember { mutableStateOf<Track?>(null) }
```

In each `TrackActionSheet(...)` call, add:

```kotlin
            onShowInfo = { infoTrack = track },
            onGoToAlbum = albumTargetFor(track)?.let { target ->
                { onOpenAlbumForTrack(target.album, target.albumArtist, track.sourceId.takeIf { track.isTidal }) }
            },
            onGoToArtist = { onOpenArtist(track.artist) },
```

and, after the `sheetTrack?.let { … }` block, the info sheet (identical to Album/Artist screens):

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

**`SearchScreen`** — its `onOpenArtist` is `(String, String?)`; take the same new `onOpenAlbumForTrack` lambda; add the info sheet the same way. In the `TrackActionSheet(...)` call:

```kotlin
            onShowInfo = { infoTrack = track },
            onGoToAlbum = albumTargetFor(track)?.let { target ->
                { onOpenAlbumForTrack(target.album, target.albumArtist, track.sourceId.takeIf { track.isTidal }) }
            },
            onGoToArtist = { onOpenArtist(track.artist, null) },
```

**`AlbumDetailScreen`** — already has the info sheet; the user is already on the album, so offer only go-to-artist. In its `TrackActionSheet(...)` (`:243-255`), add:

```kotlin
            onGoToArtist = { onOpenArtist(track.artist, null) },
```

**`ArtistDetailScreen`** — already has the info sheet; already on the artist, so offer only go-to-album. Add the new lambda to its signature (after `onOpenArtist`):

```kotlin
    onOpenAlbumForTrack: (album: String, artist: String, seedTrackId: String?) -> Unit,
```

and in its `TrackActionSheet(...)` (`:384-396`):

```kotlin
            onGoToAlbum = albumTargetFor(track)?.let { target ->
                { onOpenAlbumForTrack(target.album, target.albumArtist, track.sourceId.takeIf { track.isTidal }) }
            },
```

**`HomeScreen`** — has no sheet at all. Change its signature to take the ViewModel plus the two nav lambdas it needs (album cards get D3 in Task 5 reusing `onOpenAlbumForTrack`):

```kotlin
@UnstableApi
@Composable
fun HomeScreen(
    viewModel: PlayerViewModel,
    onOpenAlbumForTrack: (album: String, artist: String, seedTrackId: String?) -> Unit,
    onOpenArtistByName: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
```

Beside the existing `favoriteKeys`:

```kotlin
    val playlists by viewModel.playlists.collectAsState()
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var infoTrack by remember { mutableStateOf<Track?>(null) }
```

The Recently Played `AuxenTrackRow` (`:141-147`) gains `onLongPress`:

```kotlin
                AuxenTrackRow(
                    track = track,
                    isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys,
                    onPlay = { viewModel.play(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                    onLongPress = { sheetTrack = track },
                )
```

and after the `LazyColumn`'s closing brace, the sheet + info blocks:

```kotlin
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
            onShowInfo = { infoTrack = track },
            onGoToAlbum = albumTargetFor(track)?.let { target ->
                { onOpenAlbumForTrack(target.album, target.albumArtist, track.sourceId.takeIf { track.isTidal }) }
            },
            onGoToArtist = { onOpenArtistByName(track.artist) },
        )
    }

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

Finally, wire the new lambdas from `MainActivity`. Home (`:314`), Library (`:315-321`), Collection (`:357-363`), Search (`:348-356`), Artist (`:337-347`) each gain an `onOpenAlbumForTrack`; Home also gets `onOpenArtistByName`:

```kotlin
                    composable("home") {
                        HomeScreen(
                            viewModel,
                            onOpenAlbumForTrack = { album, artist, seed ->
                                navController.navigate(albumRoute(album, artist, seedTrackId = seed))
                            },
                            onOpenArtistByName = { name -> navController.navigate(artistRoute(name)) },
                        )
                    }
```

For Library/Collection/Search/Artist, add the one lambda to the existing call:

```kotlin
                            onOpenAlbumForTrack = { album, artist, seed ->
                                navController.navigate(albumRoute(album, artist, seedTrackId = seed))
                            },
```

**Imports to add:**
- `LibraryScreen.kt`, `CollectionScreen.kt`, `SearchScreen.kt`: `io.github.auxen.ui.components.albumTargetFor`, `io.github.auxen.ui.components.TrackInfoSheet`, `androidx.compose.runtime.LaunchedEffect` (if not already imported).
- `ArtistDetailScreen.kt`: `io.github.auxen.ui.components.albumTargetFor` (it already imports `TrackInfoSheet`, `LaunchedEffect`).
- `AlbumDetailScreen.kt`: none (uses only `onOpenArtist`, already in scope).
- `HomeScreen.kt`: `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.setValue`, `io.github.auxen.ui.components.TrackActionSheet`, `io.github.auxen.ui.components.TrackInfoSheet`, `io.github.auxen.ui.components.albumTargetFor`. (`Track`, `collectAsState`, `getValue`, `LaunchedEffect` already imported.)

- [ ] **Step 5: Build + full suite + goldens**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest verifyRoborazziDebug assembleDebug
```

Expected: `BUILD SUCCESSFUL`. **`action-sheet-{light,dark}` must NOT move** — the fixture passes no `onShowInfo`/`onGoToAlbum`/`onGoToArtist`, so `trackActions` returns the same five entries. A diff means a handler defaulted non-null: fix the default, do not re-record.

- [ ] **Step 6: On-device checkpoint** (locally-built APK — CI has no Tidal client id)

1. Search a Tidal-only artist → long-press a result → **"Go to album"** → the album screen shows the **full remote tracklist**, not "no tracks". (If it shows "no tracks", the seed arg is not arriving — check `topBarTitle` and the route string match.)
2. The Album top bar still reads **"Album"**.
3. Home → Recently Played → long-press → sheet appears (it never did before) → **"Track info"** → a local track shows a **Details** section (Format/Bitrate/Source), a Tidal track shows Credits/Lyrics **and** Details.
4. Library/Collection/Search track → long-press → **"Track info"** opens the info sheet.
5. Long-press a track with no album → **"Go to album" absent**, "Go to artist" present.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/TrackActionSheet.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/AlbumDetailScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/SearchScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/TrackActionSheetUiTest.kt
git commit -m "feat(android): Track info + go-to-album/artist on every track menu

Renders TrackActionSheetContent from the pure model and adds the desktop's
two nav entries. Wires the info sheet (now Credits+Lyrics+Details) on
Library/Collection/Search and gives Home's Recently Played a menu for the
first time. Carries the long-pressed track id as an optional seedTrackId nav
arg so go-to-album from a Tidal-only result resolves its remote tracklist
instead of dead-ending on 'no tracks'; topBarTitle updated for the new route
pattern. Info entry renamed 'Track info' and no longer Tidal-gated.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 5: Home album cards open on tap (D3)

`HomeScreen.kt:127` sets `onClick = { viewModel.playAll(album.tracks) }` — the same as the card's own play button (`:128`) — so a Recently Added card cannot open its album, though every other album grid does. Make the tap open; keep Play on the play button. Reuses the `onOpenAlbumForTrack` lambda Task 4 added to `HomeScreen` (Home cards are `AlbumGroup`s; the seed is any Tidal track in the group, else null).

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt`

- [ ] **Step 1: Change the card's onClick**

`HomeScreen.kt:122-132`, the `AlbumCard` in the Recently Added `LazyRow`:

```kotlin
                        AlbumCard(
                            title = album.album,
                            artist = album.albumArtist,
                            artUrl = album.artUrl,
                            source = Source.LOCAL,
                            // D3: open the album like every other grid; Play stays
                            // on the card's play button below.
                            onClick = {
                                onOpenAlbumForTrack(
                                    album.album,
                                    album.albumArtist,
                                    album.tracks.firstOrNull { it.isTidal }?.sourceId,
                                )
                            },
                            onPlay = { viewModel.playAll(album.tracks) },
                            modifier = Modifier.width(150.dp),
                        )
```

**Imports to add:** none — `onOpenAlbumForTrack` is a parameter (Task 4), `AlbumCard`/`Source`/`width` already imported.

- [ ] **Step 2: Build + goldens**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest verifyRoborazziDebug assembleDebug
```

Expected: `BUILD SUCCESSFUL`, no golden moves (`AlbumCard`'s API is unchanged).

- [ ] **Step 3: On-device checkpoint**

1. Home → Recently Added → **tap a card** → the album opens (not plays).
2. Tap the card's **play button** → the album plays.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt
git commit -m "fix(android): Home album cards open on tap, not play (D3)

Recently Added cards tapped to play — identical to their play button, and
unlike every other album grid, which opens. Tap now opens the album; Play
stays on the card's play button.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 6: The pure album + artist action models (D4)

Pure Kotlin, no Compose. The album/artist counterpart of Task 1 — where the album-menu conditionality lives. Task 8's two sheets render whatever these return.

**Why NEW enums/entry types and NOT more `ActionId` values (load-bearing):** Task 4's `TrackActionSheetContent` renders `trackActions` through a `when (entry.id)` that is **exhaustive over the 8-value `ActionId`** (`ActionSheetModel.kt`, no `else` branch — the plan states this). Adding album/artist values to that same enum would make Task 4's `when` non-exhaustive and stop it compiling — i.e. it would *disturb* a shipped task. So album and artist get their **own** enums (`AlbumActionId`, `ArtistActionId`) and entry types (`AlbumActionEntry`, `ArtistActionEntry`), leaving `ActionId`/`ActionEntry`/`trackActions` byte-identical.

**Album conditionality (the one real gate):** Play / Shuffle / Play next / Add to queue / Add to playlist are always present — the card only exists because its `AlbumGroup` has a non-empty `tracks` list (`LibraryGrouping.kt:63-83`), so there is always something to play. **Go to artist** is gated on `canGoToArtist && !albumArtist.isNullOrBlank()`, mirroring the desktop's `if album_data.get("artist")` (`context_menu.py:432`). **Artist menu** has no conditional entry — all four (Play / Shuffle / Play next / Add to queue) always show over the non-empty `ArtistGroup.tracks` — but it is still a pure function so order + labels are centralized and golden-order-testable, exactly like `trackActions`.

**Files:**
- Modify (append): `android/app/src/main/kotlin/io/github/auxen/ui/components/ActionSheetModel.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/CollectionActionsTest.kt` (create)

**Interfaces:**
- Produces: `enum class AlbumActionId`; `enum class ArtistActionId`; `data class AlbumActionEntry(id, label)`; `data class ArtistActionEntry(id, label)`; `fun albumActions(albumArtist: String?, canGoToArtist: Boolean): List<AlbumActionEntry>`; `fun artistActions(): List<ArtistActionEntry>`.

- [ ] **Step 1: RED — write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/ui/components/CollectionActionsTest.kt`. **This fails to compile** — none of `AlbumActionId`, `ArtistActionId`, `albumActions`, `artistActions` exists yet. That compile failure naming exactly those symbols is the RED.

```kotlin
package io.github.auxen.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionActionsTest {

    private fun albumIds(entries: List<AlbumActionEntry>) = entries.map { it.id }
    private fun artistIds(entries: List<ArtistActionEntry>) = entries.map { it.id }

    // ---- albumActions ----

    @Test
    fun albumBaseSetAndOrderWithoutNav() {
        // No handler for go-to-artist -> the five always-on playback/organize
        // entries, in render order.
        assertEquals(
            listOf(
                AlbumActionId.PLAY,
                AlbumActionId.SHUFFLE,
                AlbumActionId.PLAY_NEXT,
                AlbumActionId.ENQUEUE,
                AlbumActionId.ADD_TO_PLAYLIST,
            ),
            albumIds(albumActions(albumArtist = "Kavinsky", canGoToArtist = false)),
        )
    }

    @Test
    fun albumGoToArtistNeedsBothTheHandlerAndAnArtistName() {
        // Handler + a real album artist -> shown, last (nav section trails the rest).
        assertEquals(
            AlbumActionId.GO_TO_ARTIST,
            albumIds(albumActions("Kavinsky", canGoToArtist = true)).last(),
        )
        // Handler present but the album has no artist -> hidden (nothing to go to).
        assertEquals(false, AlbumActionId.GO_TO_ARTIST in albumIds(albumActions(null, canGoToArtist = true)))
        assertEquals(false, AlbumActionId.GO_TO_ARTIST in albumIds(albumActions("   ", canGoToArtist = true)))
        // Artist present but the host offers no handler -> hidden (no dead action).
        assertEquals(false, AlbumActionId.GO_TO_ARTIST in albumIds(albumActions("Kavinsky", canGoToArtist = false)))
    }

    @Test
    fun albumLabelsMatchTheDesktopWording() {
        val e = albumActions("Kavinsky", canGoToArtist = true).associate { it.id to it.label }
        assertEquals("Play album", e[AlbumActionId.PLAY])
        assertEquals("Shuffle album", e[AlbumActionId.SHUFFLE])
        assertEquals("Play album next", e[AlbumActionId.PLAY_NEXT])
        assertEquals("Add album to queue", e[AlbumActionId.ENQUEUE])
        assertEquals("Add album to playlist", e[AlbumActionId.ADD_TO_PLAYLIST])
        assertEquals("Go to artist", e[AlbumActionId.GO_TO_ARTIST])
    }

    // ---- artistActions ----

    @Test
    fun artistFixedSetAndOrder() {
        // No conditionality: the card exists only for a non-empty ArtistGroup, so
        // all four playback entries always show, in this order.
        assertEquals(
            listOf(
                ArtistActionId.PLAY,
                ArtistActionId.SHUFFLE,
                ArtistActionId.PLAY_NEXT,
                ArtistActionId.ENQUEUE,
            ),
            artistIds(artistActions()),
        )
    }

    @Test
    fun artistLabels() {
        val e = artistActions().associate { it.id to it.label }
        assertEquals("Play", e[ArtistActionId.PLAY])
        assertEquals("Shuffle", e[ArtistActionId.SHUFFLE])
        assertEquals("Play next", e[ArtistActionId.PLAY_NEXT])
        assertEquals("Add to queue", e[ArtistActionId.ENQUEUE])
    }
}
```

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*CollectionActionsTest*'
```

Expected: **compilation failure** — `Unresolved reference: AlbumActionId` / `ArtistActionId` / `albumActions` / `artistActions`. Any other failure means the fixture is wrong; fix it before Step 2.

- [ ] **Step 2: GREEN — append the models to `ActionSheetModel.kt`**

**Name-collision check before writing** (the never-re-declare rule). Run and expect **zero** hits:

```bash
grep -rn "enum class AlbumActionId\|enum class ArtistActionId\|data class AlbumActionEntry\|data class ArtistActionEntry\|fun albumActions\|fun artistActions" --include="*.kt" android/
```

Append to `android/app/src/main/kotlin/io/github/auxen/ui/components/ActionSheetModel.kt` (do NOT touch the existing `ActionId`/`ActionEntry`/`trackActions`/`albumTargetFor` above — they stay byte-identical):

```kotlin
/**
 * Which entries the **album** long-press menu shows — the Android port of the
 * desktop `AlbumContextMenu._build_menu_model` (`auxen/views/context_menu.py:392-443`).
 *
 * A separate enum from [ActionId] on purpose: [TrackActionSheetContent] renders
 * [trackActions] through a `when` that is exhaustive over [ActionId], so adding
 * album ids there would break that (shipped) render. Play/Shuffle/PlayNext/
 * Enqueue/AddToPlaylist are unconditional — the card only exists for a non-empty
 * `AlbumGroup.tracks`, so there is always something to play. Dropped vs. the
 * desktop, for want of a backend: Save/Remove from Collection (no album-level
 * favourite; see *Deferred → collection sync*).
 */
enum class AlbumActionId {
    PLAY,
    SHUFFLE,
    PLAY_NEXT,
    ENQUEUE,
    ADD_TO_PLAYLIST,
    GO_TO_ARTIST,
}

/** One album-menu row: a stable [id] for dispatch, a display [label]. */
data class AlbumActionEntry(val id: AlbumActionId, val label: String)

/**
 * Album menu. [canGoToArtist] means "the host can service a go-to-artist nav".
 * "Go to artist" is the one gated entry, mirroring the desktop's
 * `if album_data.get("artist")` (`context_menu.py:432`): it needs both a handler
 * and a real album artist to navigate to. Nav trails the playback/organize
 * entries, matching the desktop's section order.
 */
fun albumActions(albumArtist: String?, canGoToArtist: Boolean): List<AlbumActionEntry> = buildList {
    add(AlbumActionEntry(AlbumActionId.PLAY, "Play album"))
    add(AlbumActionEntry(AlbumActionId.SHUFFLE, "Shuffle album"))
    add(AlbumActionEntry(AlbumActionId.PLAY_NEXT, "Play album next"))
    add(AlbumActionEntry(AlbumActionId.ENQUEUE, "Add album to queue"))
    add(AlbumActionEntry(AlbumActionId.ADD_TO_PLAYLIST, "Add album to playlist"))
    if (canGoToArtist && !albumArtist.isNullOrBlank()) {
        add(AlbumActionEntry(AlbumActionId.GO_TO_ARTIST, "Go to artist"))
    }
}

/**
 * Which entries the **artist** long-press menu shows — the Android port of the
 * desktop `ArtistContextMenu._build_menu_model` (`auxen/views/context_menu.py:567-622`),
 * trimmed to what a ViewModel backs.
 *
 * No conditional entry: the card exists only for a non-empty `ArtistGroup.tracks`.
 * Dropped vs. the desktop, for want of a backend: Go to Artist Radio / Start Mix
 * (*Deferred → Tidal radio + mixes*) and Follow/Unfollow (needs Tidal favourites
 * *writes*; *Deferred → collection sync*). No "Add to playlist" — the desktop
 * artist menu has none either. "View artist details"/"Go to artist" is omitted:
 * this menu is opened FROM an artist row, so it would be self-referential.
 */
enum class ArtistActionId {
    PLAY,
    SHUFFLE,
    PLAY_NEXT,
    ENQUEUE,
}

/** One artist-menu row. */
data class ArtistActionEntry(val id: ArtistActionId, val label: String)

fun artistActions(): List<ArtistActionEntry> = listOf(
    ArtistActionEntry(ArtistActionId.PLAY, "Play"),
    ArtistActionEntry(ArtistActionId.SHUFFLE, "Shuffle"),
    ArtistActionEntry(ArtistActionId.PLAY_NEXT, "Play next"),
    ArtistActionEntry(ArtistActionId.ENQUEUE, "Add to queue"),
)
```

**Imports to add:** none — the appended code names only stdlib (`buildList`, `listOf`) and its own new types; `ActionSheetModel.kt` already declares `package io.github.auxen.ui.components`.

- [ ] **Step 3: Confirm GREEN**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*CollectionActionsTest*'
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  android/app/build/test-results/testDebugUnitTest/TEST-io.github.auxen.ui.components.CollectionActionsTest.xml
```

Expected: `BUILD SUCCESSFUL`, `failures="0" errors="0"`, `tests="5"`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/ActionSheetModel.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/CollectionActionsTest.kt
git commit -m "feat(android): pure album + artist action models (D4)

Ports the desktop AlbumContextMenu/ArtistContextMenu entry-visibility rules as
pure, unit-tested functions. Separate enums from the track model's ActionId so
TrackActionSheetContent's exhaustive when stays compiling. Album 'Go to artist'
gated on handler + a non-blank album artist; artist menu is the fixed four
playback entries over a non-empty ArtistGroup. Radio/mix/follow/collection-write
dropped for want of a backend (see Deferred).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 7: `AlbumCard` + `ArtistRow` — labelled, haptic long-press

Give both cards the exact affordance Task 2 gave `AuxenTrackRow`: a nullable `onLongPress` rendered through `combinedClickable` with an `onLongClickLabel` and a `LongPress` haptic, both gated on the same null check so a call site that offers no sheet advertises nothing and buzzes for nothing. Today both use plain `clickable(onClick)` (`AlbumCard.kt:48`, `ArtistRow.kt:41`) — no long-press at all.

**Golden safety (read first):** `AlbumCard` has `album-card-{light,dark}` goldens (`ComponentScreenshotTest.kt:374,381`), captured with **no `onLongPress`** (`AlbumCard(... onClick = {}, onPlay = {})`). `combinedClickable(onClick, onLongClick = null)` must render byte-identical to `clickable(onClick)` — semantics and haptics are not pixels — so the goldens **must not move**. **`ArtistRow` has NO golden** (verified: no `artist-row`/`ArtistRow(` capture in `ComponentScreenshotTest.kt`), so it moves nothing regardless. If `album-card-*` moves, a default leaked (e.g. a non-null default handler); fix the default, do not re-record.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumCard.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistRow.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/AlbumCardUiTest.kt` (create), `.../ArtistRowUiTest.kt` (create)

**Interfaces:**
- Produces: `AlbumCard(..., onLongPress: (() -> Unit)? = null)`; `ArtistRow(..., onLongPress: (() -> Unit)? = null)`. No positional break — the param is added last with a default.

- [ ] **Step 1: RED — assert the missing a11y affordance**

Create `android/app/src/test/kotlin/io/github/auxen/ui/components/AlbumCardUiTest.kt`. The `contentDescription = title` on the art gives a stable node to fetch. **The first test fails** — no `onLongClickLabel` is set today.

```kotlin
package io.github.auxen.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.auxen.model.Source
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class AlbumCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longPressExposesALabelledSemanticsActionWhenHandlerPresent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumCard(
                    title = "OutRun",
                    artist = "Kavinsky",
                    artUrl = null,
                    source = Source.TIDAL,
                    onClick = {},
                    onLongPress = {},
                )
            }
        }

        val node = compose.onNodeWithText("OutRun").fetchSemanticsNode()
        assertEquals("Show album actions", node.config[SemanticsActions.OnLongClick].label)
    }

    /** No handler -> no long-click action advertised, so no dead affordance. */
    @Test
    fun noLongClickActionWhenHandlerAbsent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumCard(
                    title = "OutRun",
                    artist = "Kavinsky",
                    artUrl = null,
                    source = Source.TIDAL,
                    onClick = {},
                )
            }
        }

        val node = compose.onNodeWithText("OutRun").fetchSemanticsNode()
        assertEquals(false, node.config.contains(SemanticsActions.OnLongClick))
    }
}
```

Create `android/app/src/test/kotlin/io/github/auxen/ui/components/ArtistRowUiTest.kt` (same shape, label "Show artist actions", node fetched by the artist name):

```kotlin
package io.github.auxen.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class ArtistRowUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longPressExposesALabelledSemanticsActionWhenHandlerPresent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                ArtistRow(name = "Kavinsky", onClick = {}, onLongPress = {})
            }
        }

        val node = compose.onNodeWithText("Kavinsky").fetchSemanticsNode()
        assertEquals("Show artist actions", node.config[SemanticsActions.OnLongClick].label)
    }

    @Test
    fun noLongClickActionWhenHandlerAbsent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                ArtistRow(name = "Kavinsky", onClick = {})
            }
        }

        val node = compose.onNodeWithText("Kavinsky").fetchSemanticsNode()
        assertEquals(false, node.config.contains(SemanticsActions.OnLongClick))
    }
}
```

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*AlbumCardUiTest*' --tests '*ArtistRowUiTest*'
```

Expected RED: the two `longPressExposes…` tests fail to compile first (`Cannot find a parameter with this name: onLongPress`); once the param exists they fail `expected:<Show album/artist actions> but was:<null>`. The two `noLongClickAction…` tests pass (regression guards for the `?.let` gate).

- [ ] **Step 2: GREEN — `AlbumCard`**

In `AlbumCard.kt`, opt in to the foundation API, take the haptics handle, and swap `clickable` for `combinedClickable` on the outer `Column` (`:48`):

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(
    title: String,
    artist: String?,
    artUrl: String?,
    source: Source?,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = onLongPress?.let { "Show album actions" },
                onLongClick = onLongPress?.let { handler ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        handler()
                    }
                },
            ),
    ) {
```

**Imports to add to `AlbumCard.kt`:** `androidx.compose.foundation.ExperimentalFoundationApi`, `androidx.compose.foundation.combinedClickable`, `androidx.compose.ui.hapticfeedback.HapticFeedbackType`, `androidx.compose.ui.platform.LocalHapticFeedback`. (Remove the now-unused `androidx.compose.foundation.clickable` import at `:4`.)

- [ ] **Step 3: GREEN — `ArtistRow`**

In `ArtistRow.kt`, the same treatment on the outer `Row` (`:38-42`); the `.padding(...)` stays after the clickable:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    artUrl: String? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = onLongPress?.let { "Show artist actions" },
                onLongClick = onLongPress?.let { handler ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        handler()
                    }
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
```

**Imports to add to `ArtistRow.kt`:** `androidx.compose.foundation.ExperimentalFoundationApi`, `androidx.compose.foundation.combinedClickable`, `androidx.compose.ui.hapticfeedback.HapticFeedbackType`, `androidx.compose.ui.platform.LocalHapticFeedback`. (Remove the now-unused `androidx.compose.foundation.clickable` import at `:3`.)

- [ ] **Step 4: Confirm GREEN + goldens unmoved**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest verifyRoborazziDebug
```

Expected: `BUILD SUCCESSFUL`, all four new tests green. **`album-card-{light,dark}` must NOT move** (the fixture passes no `onLongPress`, so `onLongClick` is null and the card renders exactly as before). A diff means the default leaked — fix it, do not re-record.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumCard.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistRow.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/AlbumCardUiTest.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/ArtistRowUiTest.kt
git commit -m "feat(android): labelled, haptic long-press on AlbumCard + ArtistRow (D4)

Both cards gain a nullable onLongPress rendered through combinedClickable with
an onLongClickLabel and a LongPress haptic, gated on the same null check as the
handler so a call site with no sheet advertises nothing. Mirrors Task 2's
AuxenTrackRow treatment. album-card goldens unmoved (fixture passes no handler);
ArtistRow has no golden.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 8: `AlbumActionSheet` + `ArtistActionSheet` — VM list-methods, sheets, host wiring

The payload of D4: four new ViewModel list-methods over a track pool the cards already hold, two new sheets rendering Task 6's models, and the `AlbumCard`/`ArtistRow` long-press wired on every `AlbumGroup`/`ArtistGroup`-backed call site.

> **This is the largest task of the album/artist slice** — work file-by-file and **compile after each**; the failure mode is a per-file missing import.

**The track list is synchronous — no fetch.** Every wired call site already passes `group.tracks` to `viewModel.playAll(...)` for its play button (`LibraryScreen.kt:186`, `CollectionScreen.kt:192`, `ArtistDetailScreen.kt:244,273`, `HomeScreen.kt`). The menu actions run over the **same** in-hand `AlbumGroup`/`ArtistGroup.tracks`. `playAll(tracks, shuffled)` already exists (`PlayerViewModel.kt:464`) and backs Play + Shuffle; the four *new* methods are the list forms of the existing single-track queue/playlist methods. **The Tidal-only cards** (`onPlay = null`: `SearchScreen.kt:327`, `ArtistDetailScreen.kt:295`; and the `ArtistLink` rows in Search + similar-artists) have **no track pool at long-press time** — they are NOT wired here; their honest async path is in *Deferred*.

**No `MainActivity` change.** The album menu's "Go to artist" reuses each host's **existing** `onOpenArtist` (Home uses the `onOpenArtistByName` Task 4 added). The sheets are self-contained per screen (state + `viewModel` + existing nav lambda), so the nav graph is untouched — which also keeps this task off the files Tasks 1–5 edit there.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt` (four list-methods)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/TrackActionSheet.kt` (widen `SheetAction` `private`→`internal`)
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumActionSheet.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistActionSheet.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt`, `.../CollectionScreen.kt` (album + artist long-press + both sheets)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt` (album long-press + album sheet)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt` (local-album long-press + album sheet)
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/AlbumActionSheetUiTest.kt`, `.../ArtistActionSheetUiTest.kt` (create)

**Interfaces:**
- Consumes: `AlbumActionId`/`ArtistActionId`/`albumActions`/`artistActions` (Task 6); the internal `SheetAction`.
- Produces on `PlayerViewModel`: `playNextAll(tracks: List<Track>)`, `enqueueAll(tracks: List<Track>)`, `addTracksToPlaylist(tracks: List<Track>, playlistId: Long)`, `createPlaylistAndAddAll(tracks: List<Track>, name: String)`.
- Produces components: `AlbumActionSheet(...)` + `internal AlbumActionSheetContent(...)`; `ArtistActionSheet(...)` + `internal ArtistActionSheetContent(...)`.

- [ ] **Step 1: The four ViewModel list-methods**

Each is the list form of an existing single-track method, built from the same `Graph.library.upsert` + `Graph.mediaItemFor` + `MediaController` pieces `playAll` (`:464`) already uses — **no new endpoint**. Add after `createPlaylistAndAdd` (`PlayerViewModel.kt:719-726`):

```kotlin
    /** List form of [playNext] — insert the whole block after the current item,
     *  in ONE addMediaItems call so album/artist order survives. */
    fun playNextAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val c = controller ?: return@launch
            runCatching { tracks.forEach { Graph.library.upsert(it) } }
            val items = tracks.mapNotNull { runCatching { Graph.mediaItemFor(it) }.getOrNull() }
            if (items.isEmpty()) return@launch
            val index = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
            c.addMediaItems(index, items)
        }
    }

    /** List form of [enqueue] — append the whole block to the queue, in order. */
    fun enqueueAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val c = controller ?: return@launch
            runCatching { tracks.forEach { Graph.library.upsert(it) } }
            val items = tracks.mapNotNull { runCatching { Graph.mediaItemFor(it) }.getOrNull() }
            if (items.isEmpty()) return@launch
            c.addMediaItems(items)
        }
    }

    /** List form of [addToPlaylist] — appendTrack (which upserts) per track, in
     *  order. */
    fun addTracksToPlaylist(tracks: List<Track>, playlistId: Long) {
        viewModelScope.launch {
            runCatching { tracks.forEach { Graph.library.addTrackToPlaylist(it, playlistId) } }
        }
    }

    /** List form of [createPlaylistAndAdd] — one new playlist, then every track
     *  appended in order. */
    fun createPlaylistAndAddAll(tracks: List<Track>, name: String) {
        viewModelScope.launch {
            runCatching {
                val id = Graph.library.createPlaylist(name)
                tracks.forEach { Graph.library.addTrackToPlaylist(it, id) }
            }
        }
    }
```

**Imports to add to `PlayerViewModel.kt`:** none — `Track`, `Graph`, `viewModelScope`, `controller` are all already in scope; `addMediaItems(index, items)` and `addMediaItems(items)` are `androidx.media3.common.Player` methods the `MediaController` already exposes (`playNext` at `:711` already calls the single-item `addMediaItem(index, item)`).

> **Not unit-tested, by precedent.** The single-track `play`/`enqueue`/`playNext`/`addToPlaylist` have no unit tests — they need a live `MediaController`/DB. These four follow that precedent: verified by the build, the Robolectric sheet tests (which assert the sheet *invokes* them), and the on-device checkpoint. The unit-tested core is the entry-visibility model (Task 6).

- [ ] **Step 2: Widen `SheetAction` to `internal`**

In `TrackActionSheet.kt`, change the row helper (`:210`) from `private` to `internal` so the two new sheets reuse it rather than forking it:

```kotlin
@Composable
internal fun SheetAction(label: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
```

Nothing else in `TrackActionSheet.kt` changes. (This is the only edit to a Task-4 file in this task, and it is purely a visibility widening — no behaviour, no golden impact.)

- [ ] **Step 3: RED — the two sheets do not exist**

Create `android/app/src/test/kotlin/io/github/auxen/ui/components/AlbumActionSheetUiTest.kt`. **This fails to compile** — `AlbumActionSheetContent` does not exist. The Robolectric concessions are the same as `TrackActionSheetUiTest` (tap via `performSemanticsAction(SemanticsActions.OnClick)`; `assertExists()` not `assertIsDisplayed()`) — see that file's class KDoc; do not rediscover them.

```kotlin
package io.github.auxen.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class AlbumActionSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun playAndQueueEntriesInvokeTheirHandlers() {
        var play = 0
        var next = 0
        var enqueue = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumActionSheetContent(
                    albumTitle = "OutRun",
                    albumArtist = "Kavinsky",
                    playlists = emptyList(),
                    showPlaylists = false,
                    onPlay = { play++ },
                    onShuffle = {},
                    onPlayNext = { next++ },
                    onEnqueue = { enqueue++ },
                    onAddToPlaylist = {},
                    onShowPlaylists = {},
                    onNewPlaylist = {},
                    onGoToArtist = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Play album").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Play album next").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Add album to queue").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, play)
        assertEquals(1, next)
        assertEquals(1, enqueue)
    }

    @Test
    fun goToArtistHiddenWhenHandlerAbsent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumActionSheetContent(
                    albumTitle = "OutRun",
                    albumArtist = "Kavinsky",
                    playlists = emptyList(),
                    showPlaylists = false,
                    onPlay = {},
                    onShuffle = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onAddToPlaylist = {},
                    onShowPlaylists = {},
                    onNewPlaylist = {},
                    onGoToArtist = null,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Add album to playlist").assertExists()
        compose.onNodeWithText("Go to artist").assertDoesNotExist()
    }
}
```

Create `android/app/src/test/kotlin/io/github/auxen/ui/components/ArtistActionSheetUiTest.kt`:

```kotlin
package io.github.auxen.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class ArtistActionSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun allFourEntriesRenderAndInvokeHandlers() {
        var play = 0
        var shuffle = 0
        var next = 0
        var enqueue = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                ArtistActionSheetContent(
                    artistName = "Kavinsky",
                    onPlay = { play++ },
                    onShuffle = { shuffle++ },
                    onPlayNext = { next++ },
                    onEnqueue = { enqueue++ },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Play").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Shuffle").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Play next").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Add to queue").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, play)
        assertEquals(1, shuffle)
        assertEquals(1, next)
        assertEquals(1, enqueue)
    }
}
```

**Imports:** `assertDoesNotExist`/`assertExists` are member functions of `SemanticsNodeInteraction`, called un-imported (same as `TrackActionSheetUiTest`); do **not** add imports for them.

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*AlbumActionSheetUiTest*' --tests '*ArtistActionSheetUiTest*'
```

Expected RED: compile failure `Unresolved reference: AlbumActionSheetContent` / `ArtistActionSheetContent`.

- [ ] **Step 4: GREEN — create the two sheets**

Create `android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumActionSheet.kt`. It mirrors `TrackActionSheet`'s structure (root actions ↔ playlist-picker page + New-playlist dialog + the `…Content` split), renders `albumActions` from Task 6, and reuses the now-`internal` `SheetAction`:

```kotlin
package io.github.auxen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.ui.theme.AuxenColors

/**
 * Album long-press action sheet — the mobile substitute for the desktop
 * AlbumContextMenu. Renders [albumActions] (Task 6) and reuses [SheetAction] +
 * the same playlist-picker + New-playlist dialog flow as [TrackActionSheet].
 *
 * [onGoToArtist] is optional: a host that cannot navigate to an artist omits it
 * and [albumActions] drops the "Go to artist" row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumActionSheet(
    albumTitle: String,
    albumArtist: String?,
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onGoToArtist: (() -> Unit)? = null,
) {
    var showPlaylists by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AlbumActionSheetContent(
            albumTitle = albumTitle,
            albumArtist = albumArtist,
            playlists = playlists,
            showPlaylists = showPlaylists,
            onPlay = { onPlay(); onDismiss() },
            onShuffle = { onShuffle(); onDismiss() },
            onPlayNext = { onPlayNext(); onDismiss() },
            onEnqueue = { onEnqueue(); onDismiss() },
            onAddToPlaylist = { id -> onAddToPlaylist(id); onDismiss() },
            onShowPlaylists = { showPlaylists = true },
            onNewPlaylist = { showNameDialog = true },
            // Dismiss FIRST, then navigate: an open ModalBottomSheet contending
            // with a screen transition is the same hazard TrackActionSheet documents.
            onGoToArtist = onGoToArtist?.let { { it(); onDismiss() } },
        )
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onCreatePlaylist(newName.trim())
                            showNameDialog = false
                            onDismiss()
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text("Cancel") } },
        )
    }
}

/** Stateless body — split out for the same Robolectric reason as
 *  [TrackActionSheetContent] (the [ModalBottomSheet] window never settles). */
@Composable
internal fun AlbumActionSheetContent(
    albumTitle: String,
    albumArtist: String?,
    playlists: List<PlaylistEntity>,
    showPlaylists: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onShowPlaylists: () -> Unit,
    onNewPlaylist: () -> Unit,
    onGoToArtist: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            albumTitle,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        if (!albumArtist.isNullOrBlank()) {
            Text(
                albumArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (!showPlaylists) {
            albumActions(albumArtist = albumArtist, canGoToArtist = onGoToArtist != null).forEach { entry ->
                when (entry.id) {
                    AlbumActionId.PLAY ->
                        SheetAction(entry.label, { Icon(Icons.Filled.PlayArrow, null) }) { onPlay() }
                    AlbumActionId.SHUFFLE ->
                        SheetAction(entry.label, { Icon(Icons.Filled.Shuffle, null) }) { onShuffle() }
                    AlbumActionId.PLAY_NEXT ->
                        SheetAction(entry.label, { Icon(Icons.Filled.SkipNext, null) }) { onPlayNext() }
                    AlbumActionId.ENQUEUE ->
                        SheetAction(entry.label, { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }) { onEnqueue() }
                    AlbumActionId.ADD_TO_PLAYLIST ->
                        SheetAction(entry.label, { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }) { onShowPlaylists() }
                    AlbumActionId.GO_TO_ARTIST ->
                        SheetAction(entry.label, { Icon(Icons.Filled.Person, null) }) { onGoToArtist?.invoke() }
                }
            }
        } else {
            SheetAction("New playlist…", { Icon(Icons.Filled.Add, null) }) { onNewPlaylist() }
            playlists.forEach { playlist ->
                SheetAction(
                    playlist.name,
                    {
                        val dotColor = remember(playlist.color) {
                            playlist.color
                                ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                                ?: AuxenColors.AmberPrimary
                        }
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(dotColor, CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                    },
                ) { onAddToPlaylist(playlist.id) }
            }
        }
    }
}
```

(The `when` is exhaustive over the 6-value `AlbumActionId`, so no `else`.)

Create `android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistActionSheet.kt` — simpler: no playlist page, no dialog, four fixed actions:

```kotlin
package io.github.auxen.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Artist long-press action sheet — the mobile substitute for the desktop
 * ArtistContextMenu's playback section. Renders [artistActions] (Task 6) over
 * the artist's in-hand `ArtistGroup.tracks`. No playlist entry (the desktop
 * artist menu has none) and no "Go to artist" (this is opened FROM an artist row).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistActionSheet(
    artistName: String,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ArtistActionSheetContent(
            artistName = artistName,
            onPlay = { onPlay(); onDismiss() },
            onShuffle = { onShuffle(); onDismiss() },
            onPlayNext = { onPlayNext(); onDismiss() },
            onEnqueue = { onEnqueue(); onDismiss() },
        )
    }
}

/** Stateless body — split out for the same Robolectric reason as
 *  [TrackActionSheetContent]. */
@Composable
internal fun ArtistActionSheetContent(
    artistName: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            artistName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        artistActions().forEach { entry ->
            when (entry.id) {
                ArtistActionId.PLAY ->
                    SheetAction(entry.label, { Icon(Icons.Filled.PlayArrow, null) }) { onPlay() }
                ArtistActionId.SHUFFLE ->
                    SheetAction(entry.label, { Icon(Icons.Filled.Shuffle, null) }) { onShuffle() }
                ArtistActionId.PLAY_NEXT ->
                    SheetAction(entry.label, { Icon(Icons.Filled.SkipNext, null) }) { onPlayNext() }
                ArtistActionId.ENQUEUE ->
                    SheetAction(entry.label, { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }) { onEnqueue() }
            }
        }
    }
}
```

**No golden is added for either sheet** — like `TrackInfoSheet`, they are behaviour-tested only (the `ModalBottomSheet` window does not settle under Robolectric; a golden would capture the `…Content` split, which we do not need here).

- [ ] **Step 5: Confirm the sheet tests GREEN**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*AlbumActionSheetUiTest*' --tests '*ArtistActionSheetUiTest*'
```

Expected: `BUILD SUCCESSFUL`, all three cases green.

- [ ] **Step 6: Wire the hosts**

Each host holds an `AlbumGroup`/`ArtistGroup` per card and already has `playlists` in scope (`LibraryScreen.kt:90`, `CollectionScreen.kt:80`, `ArtistDetailScreen.kt:81`; `HomeScreen` gains it in Task 4). Add the sheet state beside the existing `sheetTrack`/`infoTrack`, wire `onLongPress` on the card, and host the sheet after the screen's content.

**`LibraryScreen`** — album grid (`:180`) and artist list (`:202`). Add state near the top:

```kotlin
    var albumSheet by remember { mutableStateOf<AlbumGroup?>(null) }
    var artistSheet by remember { mutableStateOf<ArtistGroup?>(null) }
```

On the album `AlbumCard(...)` add `onLongPress = { albumSheet = album }`; on the `ArtistRow(...)` add `onLongPress = { artistSheet = artist }`. After the screen body, host both sheets:

```kotlin
    albumSheet?.let { group ->
        AlbumActionSheet(
            albumTitle = group.album,
            albumArtist = group.albumArtist,
            playlists = playlists,
            onDismiss = { albumSheet = null },
            onPlay = { viewModel.playAll(group.tracks) },
            onShuffle = { viewModel.playAll(group.tracks, shuffled = true) },
            onPlayNext = { viewModel.playNextAll(group.tracks) },
            onEnqueue = { viewModel.enqueueAll(group.tracks) },
            onAddToPlaylist = { viewModel.addTracksToPlaylist(group.tracks, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAddAll(group.tracks, it) },
            onGoToArtist = { onOpenArtist(group.albumArtist) },
        )
    }
    artistSheet?.let { group ->
        ArtistActionSheet(
            artistName = group.artist,
            onDismiss = { artistSheet = null },
            onPlay = { viewModel.playAll(group.tracks) },
            onShuffle = { viewModel.playAll(group.tracks, shuffled = true) },
            onPlayNext = { viewModel.playNextAll(group.tracks) },
            onEnqueue = { viewModel.enqueueAll(group.tracks) },
        )
    }
```

**`CollectionScreen`** — identical, on its album grid (`:186`) and artist list (`:211`); `onOpenArtist` is `(String)` here too, so `onGoToArtist = { onOpenArtist(group.albumArtist) }`.

**`HomeScreen`** — album long-press only (Recently Added is `AlbumGroup`; Home has no artist row). Add `var albumSheet by remember { mutableStateOf<AlbumGroup?>(null) }`, add `onLongPress = { albumSheet = album }` to the `AlbumCard(...)` **Task 5 produced** (`:122`), and host the album sheet with `onGoToArtist = { onOpenArtistByName(group.albumArtist) }` (the lambda Task 4 added). No artist sheet.

**`ArtistDetailScreen`** — album long-press on the two **local** `AlbumCard`s (`:238`, `:267`; the Tidal `RemoteAlbumSummary` cards at `:278` are NOT wired — no track pool). Add `var albumSheet by remember { mutableStateOf<AlbumGroup?>(null) }`, `onLongPress = { albumSheet = album }` on both local cards, and host the album sheet with `onGoToArtist = { onOpenArtist(group.albumArtist, null) }` (`onOpenArtist` here is `(name, tidalArtistId?)`).

**Imports to add:**
- `LibraryScreen.kt`, `CollectionScreen.kt`: `io.github.auxen.ui.components.AlbumActionSheet`, `io.github.auxen.ui.components.ArtistActionSheet`, `io.github.auxen.data.ArtistGroup` (if not already imported). (`AlbumGroup`, `mutableStateOf`, `remember`, `getValue`, `setValue` are already imported — the screens already host `sheetTrack`/`infoTrack`.)
- `HomeScreen.kt`: `io.github.auxen.ui.components.AlbumActionSheet`, `io.github.auxen.data.AlbumGroup` (if not already imported).
- `ArtistDetailScreen.kt`: `io.github.auxen.ui.components.AlbumActionSheet`, `io.github.auxen.data.AlbumGroup` (if not already imported).

- [ ] **Step 7: Build + full suite + goldens**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest verifyRoborazziDebug assembleDebug
```

Expected: `BUILD SUCCESSFUL`. **`album-card-{light,dark}` must NOT move** (Task 7 kept the default null; wiring a real handler at the call sites does not touch the golden fixture). No other golden is in play — the two new sheets add none.

- [ ] **Step 8: On-device checkpoint** (locally-built APK — CI has no Tidal client id)

1. Library → Albums → **long-press an album card** → sheet appears with Play album / Shuffle album / Play album next / Add album to queue / Add album to playlist / Go to artist.
2. **Add album to queue** → the whole album lands at the end of the queue, in order. **Play album next** → the whole album inserts right after the current track, in order.
3. **Add album to playlist ▸** → pick a playlist (or **New playlist…**) → every album track is added.
4. **Go to artist** → the album's artist page opens.
5. Library → Artists → **long-press an artist row** → Play / Shuffle / Play next / Add to queue; each acts over the artist's tracks.
6. Collection Albums/Artists, Home Recently Added album card, and an artist page's **local** album card → same album/artist sheets.
7. A **Tidal search** album/artist card → **long-press does nothing** (no sheet) — tap still opens it. (Confirms the Tidal-only cards stayed tap-only.)

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/TrackActionSheet.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumActionSheet.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistActionSheet.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/AlbumActionSheetUiTest.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/ArtistActionSheetUiTest.kt
git commit -m "feat(android): album + artist long-press menus (D4)

Adds AlbumActionSheet/ArtistActionSheet rendering the pure Task 6 models, backed
by four new ViewModel list-methods (playNextAll/enqueueAll/addTracksToPlaylist/
createPlaylistAndAddAll) over the AlbumGroup/ArtistGroup.tracks the cards already
hold — no fetch, no new endpoint; Play/Shuffle reuse playAll. Wires AlbumCard/
ArtistRow long-press on every AlbumGroup/ArtistGroup-backed call site (Library,
Collection, Home albums, artist-page local albums). SheetAction widened to
internal (not forked). Tidal-only cards stay tap-only (no track pool). No
MainActivity change: go-to-artist reuses each host's existing onOpenArtist.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

## Deferred / flagged — grounded, not built here

Named so a future plan can pick them up cleanly, and so nothing looks silently dropped.

### Album + artist long-press on the Tidal-only cards (the honest async path)

Tasks 6–8 wire the album/artist menus on every card backed by an `AlbumGroup`/`ArtistGroup` — Library/Collection grids, Home Recently Added, and the artist page's **local** album cards — because those hold `tracks` synchronously. The remaining cards do **not**:

- **Search Albums results** (`SearchScreen.kt:313`, `onPlay = null`) and an artist page's **Tidal remote discography** (`ArtistDetailScreen.kt:278`, `onPlay = null`) — a `RemoteAlbumSummary`, no track list in hand.
- **Search Artists results and similar-artist rows** (`SearchScreen.kt:344`, `ArtistDetailScreen.kt:329`) — an `ArtistLink`, no track list in hand.

For these, "play/queue/add-to-playlist" is **inherently async**: the tracklist must be fetched first. The fetch already exists — `Graph.tidal.albumDetail(albumId)` returns the album's tracks (it backs `loadAlbumDetail`, `PlayerViewModel.kt:354`) and `Graph.tidal.artistPage(artistId)` returns the artist's (`:311`). The honest follow-up is a `resolveThen(action)` path: on long-press, fetch → then apply the same list-method — with a brief loading affordance or a fire-and-forget "Queued <album>" toast, and error handling for a failed/empty fetch. It is deferred **only** because it adds an async UX (loading/error/empty states per card) that the synchronous slice does not need, and because tap-to-open already gives these discovery cards a play path. **No new endpoint is required** — this is wiring, when someone wants it.

### Entries with no backend (kept out of Tasks 6–8 on purpose)

Traced against the desktop menus, dropped for want of an Android backend:

- **Album Save/Remove from Collection** (`AlbumContextMenu`, `context_menu.py:421-428`) — Android favourites are **per-track** (`LibraryRepository.setFavorite(track, …)`, `LibraryRepository.kt:36`); there is no album-level favourite. → *collection sync*.
- **Artist Follow/Unfollow** (`ArtistContextMenu`, `:602-614`) — needs Tidal favourites **writes**, which Android has no path for (`toggleFavorite` is local-DB only). The desktop's own toggle is dead anyway: no caller passes `is_followed`, so it always reads "Follow Artist". → *collection sync*.
- **Go to Artist Radio / Start Mix** (`:592-599`) and **Go to Track Radio / Start Mix** (track menu) — no Android radio/mix backend. → *Tidal radio + mixes*.
- **Album/Artist Properties** (`:438-441`, `:617-620`) — the desktop's modal Properties dialog. The **track** Properties folded into the info-sheet Details section (D1); an album/artist-level Details would need aggregate stats (track count, total runtime, year span) — a UX call, not a port.

### Other named follow-ups

- **Tidal radio + mixes** — unblocks Go to Track Radio, Go to Artist Radio, Start Mix (all need `track.get_track_radio()` / `artist.get_radio()` / the mixes API, none built on Android; plus a decision about where a radio queue lands).
- **Collection sync (Tidal favourites writes)** — unblocks album Save-to-Collection and artist Follow/Unfollow.
- **`QueueScreen` / `PlaylistDetailScreen` long-press.** `QueueScreen` renders `AuxenTrackRow` with no `onLongPress` (its rows already carry a drag handle — a second long-press next to it is a UX call). `PlaylistDetailScreen` rows carry up/down/remove `IconButton`s already; a "Remove from playlist" menu entry would need a playlist-scoped action.

---

## Open questions — flagged, not invented

- **Q1 — File path / more Details fields.** The Details section (D1) shows what the `Track` model carries. There is **no file-path field** (a local `sourceId` is a MediaStore `_ID`), so no path is shown. Surfacing a real path would need a MediaStore `RELATIVE_PATH`/`DISPLAY_NAME` query — a new read, which the "no new fetch" constraint forbids. **Want a path row?** It is a small follow-up (one MediaStore projection), not a guess to bake in now.
- **Q2 — Track-row names tappable?** Answered by D2: **no**. Go-to-album/artist is the long-press menu. If the user later wants inline tappable names specifically, that is its own task (the honest design is a leading-art tap or a trailing chevron, not two sub-48dp links inside a 60dp play-me row).
- **Q3 — Album/artist long-press menus:** now built (Tasks 6–8, D4) on every `AlbumGroup`/`ArtistGroup`-backed card. Only the Tidal-only cards (search results, remote discography, similar artists) stay tap-only — their play/queue needs an async tracklist fetch; the honest path is grounded in *Deferred*.

---

## Self-review (writing-plans checklist)

**Re-grounding — every stale assumption corrected:**
- The old draft claimed "Track info" was unreachable and menus "barely exist". **Verified false:** `TrackActionSheet` is wired on six hosts; the info sheet is wired on **two** (Album + Artist), not one. Corrected in *Current state*.
- All official-v2 references removed; the "frozen `TidalMetadataRepository` constructor" constraint is gone (retired). Credits/lyrics are internal-v1 via `Graph.tidal.trackDetail`.
- Search/Library/Collection signatures re-read (`onOpenArtist`/`onOpenAlbum` already exist; the old draft said they "have none").
- D1/D2/D3 baked in; old Task 7 (clickable names) **deleted** per D2.

**Spec coverage:**
- **One mechanism, extended not duplicated** ✓ `TrackActionSheet`/`TrackInfoSheet` are extended; no parallel menu.
- **Real conditionality, pure + tested** ✓ `trackActions`/`albumTargetFor` are pure, 9 unit tests covering every gate in both directions. The one substantive divergence from the old draft — `SHOW_INFO` no longer Tidal-gated (D1) — is pinned by `trackInfoOfferedForEitherSourceWhenHostCanShowIt`.
- **D1 Details** ✓ Task 3, fields verified present on `Track`/`TrackEntity`; rows self-hide when null; no file-path row (honest — the data is not on the model).
- **Info reachable everywhere** ✓ Task 4 wires Library/Collection/Search + Home; Album/Artist already had it.
- **D3** ✓ Task 5, reuses the real `albumRoute` via `onOpenAlbumForTrack`.
- **Accessibility** ✓ Task 2 adds `onLongClickLabel` + haptic to `AuxenTrackRow` (confirmed missing), gated on the same null handler.
- **Golden risk** ✓ Its own section; `action-sheet-*` stays put (fixture passes no optional handler); no `TrackInfoSheet` golden exists; `track-row-*` unmoved (semantics/haptics are not pixels).
- **Escalation over invention** ✓ The Tidal-only-card async menus and Tidal radio/mixes/collection-writes are grounded and deferred, not specced against nonexistent methods.

**Album + artist menus (D4, Tasks 6–8):**
- **Pure conditionality** ✓ `albumActions`/`artistActions` are pure (`ActionSheetModel.kt`), 5 unit tests (`CollectionActionsTest`) covering the "Go to artist" gate in both directions and the label/order. Separate enums from `ActionId` so Task 4's exhaustive `when` is not disturbed.
- **Synchronous track pool, no fetch** ✓ Every wired card holds `AlbumGroup`/`ArtistGroup.tracks` (already passed to `playAll` for the play button); Play/Shuffle reuse `playAll(tracks, shuffled)`, the four new list-methods reuse the same `upsert`/`mediaItemFor`/`MediaController` pieces — no new endpoint.
- **Reuse, not fork** ✓ `SheetAction` widened `private`→`internal`; the new sheets keep the `…Content` split and the New-playlist dialog flow. No `MainActivity` change — go-to-artist reuses each host's existing `onOpenArtist`.
- **Golden risk** ✓ `AlbumCard`'s `album-card-*` stays put (Task 7's `onLongPress` defaults null → `combinedClickable` renders identically); `ArtistRow` has no golden; the new sheets add none.
- **Honest deferral** ✓ Tidal-only cards (no synchronous track pool) and radio/mix/follow/collection-write stay in *Deferred* with the exact reason and the existing fetch (`albumDetail`/`artistPage`) named for the async follow-up.

**Scope:** **8 tasks.** Tasks 1–5 (track menu) are inside the original ~4–5; Tasks 6–8 add the album + artist menus the user explicitly requested (D4). Above the old ceiling, deliberately and stated plainly — a real user-requested addition, grounded in the desktop menus and in what the ViewModel can back.
