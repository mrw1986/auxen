# Clickable names + long-press context menus — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mirror the desktop's three right-click context menus (`TrackContextMenu`, `AlbumContextMenu`, `ArtistContextMenu`) on Android as **long-press** menus, and make artist/album **names** tappable where the desktop makes them tappable. Track long-press already exists (`TrackActionSheet`); this plan **extends** it rather than adding a parallel mechanism, and adds the two menus that do not exist yet (album, artist).

**The user's request (verbatim, 2026-07-16):**

> "artist and album names should be clickable, we should have long press options, etc. similar to the desktop version but rightclick instead of long press."

Standing directive: *parity is the floor, not the target — proactively surface improvements, and don't port desktop bugs.* This plan drops four desktop entries that are **dead or wrong on the desktop itself** (documented below), and refuses to invent backends for three more.

**Architecture:** One surface, one model, nullable-gated affordances.

1. **One menu surface: `ModalBottomSheet`.** Not `DropdownMenu`. Reasons, in order of weight: (a) the app **already** uses `ModalBottomSheet` for the track long-press menu (`TrackActionSheet.kt:74`) — the brief's "do not design a second, parallel menu system" means the album/artist menus must be the same thing; (b) `DropdownMenu` wants an anchor and press coordinates, which is a mouse idiom — long-press → bottom sheet is the Android idiom for a list item, and gives full-width ≥48dp targets reachable one-handed; (c) the sheet already has established Robolectric/Roborazzi discipline in this repo (the stateless `…Content` split, below) that a new surface would have to reinvent.
2. **One pure model.** Which entries appear for a given entity + state is a **pure** function in `ActionSheetModel.kt` returning `List<ActionEntry>` — plain Kotlin, no Compose, unit-tested. That is where the conditionality bugs live (the desktop proves it: see *The desktop bugs we are not porting*). The composables render whatever list the model hands them.
3. **Every new affordance is gated on a nullable handler defaulting to `null`.** This is the existing `onShowInfo` precedent (`TrackActionSheet.kt:67`, `:178`), and it is load-bearing three ways: a host that cannot service an action never offers a dead one; each call site opts in explicitly; and **the Roborazzi goldens stay byte-identical**, because every golden fixture passes no handler (see *Golden risk* below). Do not "simplify" a gate into an always-on entry.

**The stateless `…Content` split is mandatory for every new sheet.** `ModalBottomSheet` hosts its content in a *separate window* whose animated entrance never settles under Robolectric, so `onRoot().captureRoboImage(...)` cannot capture it and node queries cannot reach it reliably. Every sheet in this repo is therefore split into a `ModalBottomSheet` wrapper + an `internal …Content` plain `Column` that renders in the main composition (`TrackActionSheet.kt:116-127`, `TrackInfoSheet.kt:42-54`). New sheets follow the same split or they are untestable.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01 → Compose UI/Foundation **1.7.6**, Material3 1.3.1), Navigation-Compose, Media3 1.5.1, JUnit4 + Robolectric + Roborazzi. minSdk 26 / compileSdk 35. Manual `Graph` service-locator DI.

---

## What the desktop actually has (read, not assumed)

Every row below was read out of `auxen/views/context_menu.py` at the stated line, and every "wired?" answer was traced to `auxen/window.py`.

### `TrackContextMenu` (`context_menu.py:147`, model at `:186-249`)

| Desktop entry | Real condition in `_build_menu_model` | Android verdict |
| --- | --- | --- |
| Play | always | **exists** (`TrackActionSheet`) |
| Play Next | always | **exists** |
| Add to Queue | always | **exists** |
| Add to Playlist ▸ (+ New Playlist…) | always | **exists** |
| Add / Remove from Collection | label flips on `track_data["is_favorite"]` | **exists** (and Android's is *correct*; the desktop's is not — see below) |
| Go to Album | `if track_data["album"] and track_data["artist"]` | **ADD** (Task 3) |
| Go to Artist | `if track_data["artist"]` | **ADD** (Task 3) |
| Go to Track Radio | `if is_tidal and callbacks.get("on_track_radio")` | **DROP** — no backend (Task 3) |
| Start Mix | `if is_tidal and callbacks.get("on_track_mix")` | **DROP** — no backend (Task 3) |
| View Lyrics | `if callbacks.get("on_view_lyrics")` | **already covered** — merged into "Credits & lyrics" |
| Credits | `if is_tidal and callbacks.get("on_credits")` | **exists** ("Credits & lyrics") |

> **The brief lists "Properties…" on the track menu. It is not there.** `TrackContextMenu._build_menu_model` builds exactly four sections — playback, organize, nav, extras — and **none contains a properties entry** (verified: `sed -n '147,350p' auxen/views/context_menu.py | grep -i properties` → no match). Only `AlbumContextMenu` (`:438-441`) and `ArtistContextMenu` (`:617-620`) have one. Do not add a track Properties entry "for parity"; there is nothing to be at parity with.

### `AlbumContextMenu` (`context_menu.py:357`, model at `:392-443`)

| Desktop entry | Real condition | Android verdict |
| --- | --- | --- |
| Play Album | always | **ADD** (Task 5) |
| Shuffle Album | always | **ADD** (Task 5) |
| Play Album Next | always | **ADD** (Task 5) — needs `playNextAll` (Task 4) |
| Add Album to Queue | always | **ADD** (Task 5) — needs `enqueueAll` (Task 4) |
| Add to Playlist ▸ (+ New Playlist…) | always | **ADD** (Task 5) — needs `addTracksToPlaylist` (Task 4) |
| Save / Remove from Collection | flips on `is_saved_in_collection` ctor arg | **DROP** — Android has no album-level favorite (Task 5) |
| Go to Artist | `if album_data["artist"]` | **ADD** (Task 5) |
| Properties… | `if callbacks.get("on_properties")` | **DROP** — open question Q1 |

### `ArtistContextMenu` (`context_menu.py:539`, model at `:567-622`) — enumerated in full

| Desktop entry | Real condition | Android verdict |
| --- | --- | --- |
| Play All by Artist | always | **ADD** (Task 6) |
| Add All to Queue | always | **ADD** (Task 6) — needs `enqueueAll` (Task 4) |
| Shuffle Artist | `if callbacks.get("on_shuffle_artist")` | **ADD** (Task 6) |
| View Artist Details | always | **ADD** (Task 6) |
| Go to Artist Radio | `if callbacks.get("on_artist_radio")` | **DROP** — no backend (Task 6) |
| Start Mix | `if callbacks.get("on_artist_mix")` | **DROP** — no backend (Task 6) |
| Follow / Unfollow Artist | flips on `artist_data["is_followed"]` | **DROP** — no backend, and the desktop toggle is dead (below) |
| Properties… | `if callbacks.get("on_properties")` | **DROP** — open question Q1 |

There is **no** "Add to Playlist" on the desktop artist menu. It is not ported. (The album menu has one; the artist menu does not.)

> **`ArtistContextMenu` has a real ordering bug at `:582-586`:** "Shuffle Artist" is appended to `playback_section` **after** `menu.append_section(None, playback_section)` at `:579`. It survives only because `Gio.Menu` sections are live-linked. Android's model builder appends in the order it lists them; Task 6 puts Shuffle in its natural position (after Play All). This is a divergence in *ordering only*, and it is deliberate.

---

## The desktop bugs we are NOT porting

These were traced, not guessed. Each one is a conditional that **never fires** on the desktop.

1. **Every "is the callback present?" gate is dead.** The menu models ask `self._callbacks.get("on_track_radio")`, but every view layer builds its callbacks dict with a `_noop`-defaulted lambda for *every* key — e.g. `"on_artist_radio": lambda name=artist_name: cbs.get("on_artist_radio", _noop)(name)` (`library.py:1614`). A lambda is always truthy, so **the gate is always true** and the entry always renders — whether or not `window.py` wires anything behind it. On the desktop the *effective* condition for Track Radio / Start Mix / Credits is therefore `is_tidal` alone. Android's model takes an explicit `can…: Boolean`, so the gate means what it says.
2. **The artist Follow/Unfollow toggle never shows "Unfollow".** `ArtistContextMenu` picks its label from `artist_data.get("is_followed", False)` (`context_menu.py:605`), but **no caller ever passes `is_followed`** — `library.py:1622` and `collection.py:1643` both build `artist_data = {"artist": artist_name}`, and `stats.py:527` passes `{"artist": artist}` (verified: `grep -rn "artist_data = " --include="*.py" auxen/` → three sites, none with `is_followed`). So the desktop always says "Follow Artist", even for an artist you already follow. Android drops the entry entirely rather than shipping a broken toggle.
3. **The track favourite label is wrong in six of nine views.** `is_favorite` is hardcoded `False` in `library.py:1487`, `album_detail.py:544`, `artist_detail.py:1131`, `explore.py:683`, `playlist_view.py:725`, `search.py:726`, `smart_playlist_view.py:499`, and hardcoded `True` in `collection.py:1490`. Only `home.py:1612` and `stats.py:656` pass a real value. **Android already does this right** — every host computes `isFavorite = "${track.source.name}:${track.sourceId}" in favoriteKeys` off the live flow. Keep it that way; do not "simplify" to a constant.
4. **The album Save/Remove toggle is wrong everywhere except Collection.** `is_saved_in_collection` defaults `False` (`context_menu.py:378`) and only `collection.py:1588` passes it. Moot for Android — the entry is dropped for lack of a backend — but it is the same class of bug and the reason the model is unit-tested here.

---

## Entries dropped, and why (a silently missing entry is a defect; this is the record)

| Dropped | Entity | Why | Where it belongs |
| --- | --- | --- | --- |
| Go to Track Radio | track | Needs `track.get_track_radio()`. No Android equivalent exists — `TidalProvider` has `search`, `getStreamInfo(ById)`, `artistAlbums`, `artistTopTracks`, `searchArtistIdExact` and nothing else. Building it = new endpoint + projection + tests. **Not invented here.** | Named follow-up: *Tidal radio + mixes* |
| Start Mix | track, artist | Needs the mixes API (`favorites.mixes()` / `session.home()`). Not built on Android at all. | Same follow-up |
| Go to Artist Radio | artist | Needs `artist.get_radio()`. Same as above. | Same follow-up |
| Follow / Unfollow Artist | artist | Needs Tidal favorites **writes** (`follow_artist`/`unfollow_artist`). Android has no Tidal-side collection write path; `toggleFavorite` is local-DB only. Also the desktop's own toggle is dead (bug #2 above), so there is no correct behaviour to copy. | Slice 2 (collection sync) |
| Save / Remove album from Collection | album | Android favourites are **per-track**: `LibraryRepository.setFavorite(track, …)` and `favoriteKeys` are keyed `"SOURCE:sourceId"` on a *track*. There is no album favourite table, entity, or flow. | Slice 2 (collection sync) |
| Properties… | album, artist | See open question **Q1** — needs a UX decision. | Q1 |
| View Lyrics (as a separate entry) | track | **Not a drop — a merge.** Android already surfaces lyrics inside "Credits & lyrics" (`TrackInfoSheet` renders a Lyrics section, `TrackInfoSheet.kt:96-103`). A second entry opening the same sheet would be a worse menu. | done |

---

## Golden risk (read before touching a shared component)

Committed goldens that this plan's files can move (`git ls-files android/app/src/test/screenshots/`):

| Golden | Captured from | Risk here |
| --- | --- | --- |
| `action-sheet-{light,dark}` | `TrackActionSheetContent`, **TIDAL** fixture, `isFavorite=true`, `showPlaylists=false`, **no `onShowInfo`** (`ComponentScreenshotTest.kt:431-444`) | Task 3 adds entries **gated on nullable handlers** the fixture does not pass → renders identically → **must not move** |
| `track-row-{tidal-favorite,local-explicit}-{light,dark}` | `AuxenTrackRow` (`ComponentScreenshotTest.kt:345-363`) | Task 2 adds only semantics + haptics — **neither is pixels** → **must not move** |
| `album-card-{light,dark}` | `AlbumCard(title="OutRun", artist="Kavinsky", …)` (`:374-384`) | Tasks 5/7 add nullable `onLongPress`/`onArtistClick`, default `null`; the tint is applied **only when the handler is non-null** → fixture unaffected → **must not move** |
| `now-playing-phone-{light,dark}`, `now-playing-tablet-dark` | `NowPlayingContent(title="Nightcall", artist="Kavinsky", …)` (`NowPlayingScreenshotTest.kt:72-75`) | Task 7 adds nullable `onOpenAlbum`/`onOpenArtist` + `album`, all defaulted; tint only when non-null → **must not move** |

**If one of these moves, a default leaked. Fix the default; do not re-record.** No golden in this plan is expected to change. There is no `ArtistRow` golden, so Task 6 has no golden exposure.

---

## Global Constraints

- **Gradle needs JDK 21.** The system JDK is 25 and breaks this project's Gradle (`IllegalArgumentException: 25.0.3`). **EVERY** gradle line carries `JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10` and runs from the repo root with `-p android`.
- **Verify honestly.** Never pipe gradle through `tail`/`head` and then read `$?` — that is the *pipe's* status. Read real output for `BUILD SUCCESSFUL`; parse test counts from the JUnit XML under `android/app/build/test-results/testDebugUnitTest/`.
- **Git: explicit pathspecs on every `git add`.** Never `git add -A`, never `git add .`, never a pathless `git commit -a`. The index is shared with a concurrent agent. `.playwright-mcp/`, `android/.kotlin/` and `docs/plans/2026-07-13-*.md` must **never** be committed.
- **Never re-declare an existing type or top-level function.** Kotlin "conflicting declarations" is a compile error. This bites twice in this plan and both are called out in-place: `SheetAction` (Task 5 makes the existing one `internal` and **reuses** it — it does **not** create a second one) and `Track.favoriteKey()` (a **private** top-level already exists in `QueueScreen.kt:234`; do **not** add a public one in `model/` — every other screen inlines the string, and this plan keeps inlining it).
- **Conversely: ADD THE IMPORT.** Kotlin resolves per file. New code in an existing file that names a type that file does not already import will not compile. Every task below carries an explicit **Imports to add** line. Both halves of this rule have bitten this series repeatedly.
- **`TidalMetadataRepository`'s constructor is FROZEN.** This plan does not touch it, or any Tidal file.
- **No new network calls, no new Tidal endpoints, in this plan at all.** Official `openapi.tidal.com/v2` is metadata-only (PREVIEW at our tier) — never playback/search. Every action here is backed by code that already exists (`play`, `playNext`, `enqueue`, `playAll`, `toggleFavorite`, `addToPlaylist`, `createPlaylistAndAdd`, `loadTrackDetail`) or by a trivial VM-local list variant of one (Task 4). If an action would need a new endpoint, it is in the *dropped* table above, not in a task.
- **Never log or print `accessToken`/`refreshToken`.** PKCE public client: never send `client_secret`. (Nothing here goes near auth; the rule stands anyway.)
- **Unit-test PURE logic only.** `ActionSheetModel.kt` is plain Kotlin and is fully unit-tested. Compose screens stay outside the unit-test boundary (established constraint); the sheets get Robolectric behaviour tests in the existing `TrackActionSheetUiTest` style, and the screens close on an on-device checkpoint.
- **Robolectric concessions carry over verbatim.** Taps inside a `ModalBottomSheet` must be dispatched via `performSemanticsAction(SemanticsActions.OnClick)`, not `performClick()`, and `assertExists()` not `assertIsDisplayed()` — the sheet's scaffolding swallows coordinate-based touches and its entrance never settles. The full root-cause is in `TrackActionSheetUiTest`'s class KDoc (concession 1). Do **not** rediscover this.
- Every code step below shows the **actual** code. No `TODO`, no "add error handling", no "similar to above".
- **Line numbers are pinned to `a6bdef1` and WILL drift — anchor on the quoted content, never on the number.** A concurrent agent is committing to these same files (it moved `PlayerViewModel.kt` by +25 lines in `791d00a` and `ArtistDetailScreen.kt` by +3 in `a6bdef1`, *after* the first draft of this plan was written against `26c03bb` — every citation below was exact at the time and three had already rotted by review). Every citation here is therefore given as **content + line**, and the content is authoritative. Before any insertion, `grep -n` for the quoted signature and use *that* line. A stale number that lands mid-function is a compile break, and `git diff HEAD` will **not** warn you — the drift arrives as *commits*, not as working-tree changes.

**Scope note (flagged per the brief):** this is **7 tasks**, one more than the ~6 the brief allows. Task 7 (clickable names on `AlbumCard`/`NowPlaying`) is the separable one — Tasks 1–6 deliver the whole long-press menu system and "Go to album/artist" navigation on their own. If the budget is hard, cut Task 7 and keep it as a follow-up; do not cut Task 1 (the model) or Task 3 (the seed nav arg fixes a dead-end).

---

### Task 1: The pure action model — where the conditionality lives

Pure Kotlin, no Compose, no Android. This is the only unit-tested part of the feature and it is where every entry-visibility rule lives. Tasks 3/5/6 render whatever it returns.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/ActionSheetModel.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/ActionSheetModelTest.kt` (create)

**Interfaces:**
- Consumes: `io.github.auxen.model.Track`, `io.github.auxen.model.Source`.
- Produces: `enum class ActionId`; `data class ActionEntry(id, label)`; `data class AlbumTarget(album, albumArtist)`; `fun albumTargetFor(track: Track): AlbumTarget?`; `fun trackActions(track, isFavorite, canGoToAlbum, canGoToArtist, canShowInfo): List<ActionEntry>`; `fun albumActions(albumArtist, canGoToArtist): List<ActionEntry>`; `fun artistActions(canViewArtist: Boolean): List<ActionEntry>`.

- [ ] **Step 1: RED — write the failing test**

Create `android/app/src/test/kotlin/io/github/auxen/ui/components/ActionSheetModelTest.kt`. **This fails to compile** — `ActionSheetModel.kt` does not exist yet, so `ActionId`, `ActionEntry`, `albumTargetFor`, `trackActions`, `albumActions` and `artistActions` are all unresolved references. That is the RED state: a compile failure naming exactly the symbols Step 2 creates.

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
        // groupAlbums keys on (album, albumArtist ?: artist). A compilation
        // track whose artist differs from its albumArtist must resolve to the
        // ALBUM ARTIST, or AlbumDetailScreen's lookup finds no group.
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
        // Blank and null both -> null. groupAlbums would file these under
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
        // Handler present + album present -> shown.
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
    fun creditsShownOnlyForTidalTracksWithAHandler() {
        val tidal = track(source = Source.TIDAL)
        assertEquals(true, ActionId.SHOW_INFO in ids(trackActions(tidal, false, false, false, canShowInfo = true)))
        // LOCAL: credits/lyrics come from the official API keyed on a CATALOG
        // id — a MediaStore track has nothing to fetch.
        assertEquals(false, ActionId.SHOW_INFO in ids(trackActions(track(source = Source.LOCAL), false, false, false, canShowInfo = true)))
        assertEquals(false, ActionId.SHOW_INFO in ids(trackActions(tidal, false, false, false, canShowInfo = false)))
    }

    @Test
    fun trackNavEntriesSitBetweenPlaylistAndCredits() {
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

    // ---- albumActions ----

    @Test
    fun albumActionsFullSetAndOrder() {
        assertEquals(
            listOf(
                ActionId.PLAY_ALBUM,
                ActionId.SHUFFLE_ALBUM,
                ActionId.PLAY_ALBUM_NEXT,
                ActionId.ENQUEUE_ALBUM,
                ActionId.ADD_TO_PLAYLIST,
                ActionId.GO_TO_ARTIST,
            ),
            ids(albumActions(albumArtist = "Kavinsky", canGoToArtist = true)),
        )
    }

    @Test
    fun albumGoToArtistHiddenWithoutHandlerOrName() {
        assertEquals(false, ActionId.GO_TO_ARTIST in ids(albumActions("Kavinsky", canGoToArtist = false)))
        assertEquals(false, ActionId.GO_TO_ARTIST in ids(albumActions("   ", canGoToArtist = true)))
    }

    // ---- artistActions ----

    @Test
    fun artistActionsFullSetAndOrder() {
        // No "Add to playlist": the desktop artist menu has none
        // (context_menu.py:567-622). Parity, deliberately.
        assertEquals(
            listOf(
                ActionId.PLAY_ARTIST,
                ActionId.SHUFFLE_ARTIST,
                ActionId.ENQUEUE_ARTIST,
                ActionId.VIEW_ARTIST,
            ),
            ids(artistActions(canViewArtist = true)),
        )
    }

    @Test
    fun artistViewDetailsHiddenWithoutHandler() {
        assertEquals(
            listOf(ActionId.PLAY_ARTIST, ActionId.SHUFFLE_ARTIST, ActionId.ENQUEUE_ARTIST),
            ids(artistActions(canViewArtist = false)),
        )
    }
}
```

Run it and confirm the RED is the expected compile error, not something else:

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*ActionSheetModelTest*'
```

Expected: **compilation failure** in `ActionSheetModelTest.kt` — `Unresolved reference: ActionId` / `ActionEntry` / `albumTargetFor` / `trackActions` / `albumActions` / `artistActions`. Any other failure means the fixture is wrong; fix the fixture before Step 2.

- [ ] **Step 2: GREEN — create `ActionSheetModel.kt`**

Create `android/app/src/main/kotlin/io/github/auxen/ui/components/ActionSheetModel.kt`:

```kotlin
package io.github.auxen.ui.components

import io.github.auxen.model.Source
import io.github.auxen.model.Track

/**
 * Which entries a long-press menu shows, for every entity — the Android port of
 * the desktop's three `_build_menu_model` methods
 * (`auxen/views/context_menu.py`), as PURE functions.
 *
 * Pure, and free of Compose/Android on purpose: entry visibility is where the
 * conditionality bugs live, and the desktop proves it — four of its gates never
 * fire (see the plan's *desktop bugs we are not porting*). Everything here is
 * unit-tested; the composables in `TrackActionSheet.kt`/`AlbumActionSheet.kt`/
 * `ArtistActionSheet.kt` only render the list they are handed.
 *
 * **The `can…` flags mean "the host can service this action"** — they are NOT
 * decoration. The desktop asks `callbacks.get("on_track_radio")`, which is
 * always truthy because every view wraps every callback in a `_noop`-defaulted
 * lambda, so the gate is dead. These are real Booleans passed from a host that
 * knows whether it has a handler.
 */
enum class ActionId {
    // Track
    PLAY,
    PLAY_NEXT,
    ENQUEUE,
    TOGGLE_FAVORITE,
    ADD_TO_PLAYLIST,
    GO_TO_ALBUM,
    GO_TO_ARTIST,
    SHOW_INFO,

    // Album
    PLAY_ALBUM,
    SHUFFLE_ALBUM,
    PLAY_ALBUM_NEXT,
    ENQUEUE_ALBUM,

    // Artist
    PLAY_ARTIST,
    SHUFFLE_ARTIST,
    ENQUEUE_ARTIST,
    VIEW_ARTIST,
}

/** One rendered menu row: a stable [id] for dispatch, a display [label]. */
data class ActionEntry(val id: ActionId, val label: String)

/**
 * The (album, albumArtist) pair a "Go to album" navigation must target.
 *
 * **This MUST reproduce `groupAlbums`' grouping key exactly**
 * (`data/LibraryGrouping.kt:30-38`): `AlbumDetailScreen` finds its group with
 * `groupAlbums(pool).firstOrNull { it.album == album && it.albumArtist == artist }`
 * (`AlbumDetailScreen.kt:84`). Pass `track.artist` where `groupAlbums` used
 * `track.albumArtist` and the lookup silently finds nothing — the user lands on
 * an empty album screen. Hence `albumArtist ?: artist`, matching `:34-36`.
 *
 * Divergence from `groupAlbums`, deliberately: it files a blank/absent album
 * under `"Unknown Album"`, this returns null. The desktop hides "Go to Album"
 * for a track with no album (`context_menu.py:228`) and so do we — navigating
 * to the "Unknown Album" bucket is not what the user asked for.
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
 * Order is load-bearing: it is what the `action-sheet-*` Roborazzi goldens
 * captured. The two nav entries slot between "Add to playlist" and
 * "Credits & lyrics", mirroring the desktop's section order (playback →
 * organize → nav → extras).
 *
 * Dropped vs. the desktop, for want of a backend, NOT by oversight: "Go to
 * Track Radio" (`track.get_track_radio()`) and "Start Mix" (the mixes API).
 * Neither exists on Android. "View Lyrics" is not dropped but MERGED — lyrics
 * render inside the "Credits & lyrics" sheet already (`TrackInfoSheet.kt:96`).
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
    // Credits and lyrics come from the official metadata API keyed on the
    // track's CATALOG id, so there is nothing to fetch for a MediaStore track.
    if (canShowInfo && track.source == Source.TIDAL) {
        add(ActionEntry(ActionId.SHOW_INFO, "Credits & lyrics"))
    }
}

/**
 * Album menu — desktop `AlbumContextMenu` (`context_menu.py:392-443`).
 *
 * Dropped: "Save/Remove from Collection" — Android's favourites are per-TRACK
 * (`LibraryRepository.setFavorite(track, …)`, `favoriteKeys` keyed
 * `"SOURCE:sourceId"` on a track); there is no album-level favourite to toggle.
 * Belongs to Slice 2. Also dropped: "Properties…" (open question Q1).
 */
fun albumActions(
    albumArtist: String,
    canGoToArtist: Boolean,
): List<ActionEntry> = buildList {
    add(ActionEntry(ActionId.PLAY_ALBUM, "Play album"))
    add(ActionEntry(ActionId.SHUFFLE_ALBUM, "Shuffle album"))
    add(ActionEntry(ActionId.PLAY_ALBUM_NEXT, "Play album next"))
    add(ActionEntry(ActionId.ENQUEUE_ALBUM, "Add album to queue"))
    add(ActionEntry(ActionId.ADD_TO_PLAYLIST, "Add to playlist"))
    if (canGoToArtist && albumArtist.isNotBlank()) {
        add(ActionEntry(ActionId.GO_TO_ARTIST, "Go to artist"))
    }
}

/**
 * Artist menu — desktop `ArtistContextMenu` (`context_menu.py:567-622`).
 *
 * **No "Add to playlist"**: the desktop artist menu has none. Parity.
 *
 * Shuffle sits second, next to Play All. The desktop appends it to
 * `playback_section` AFTER that section was already added to the menu
 * (`:579` then `:582-586`) — it renders only because `Gio.Menu` sections are
 * live-linked. This is the natural order it was reaching for.
 *
 * Dropped: "Go to Artist Radio" (`artist.get_radio()`) and "Start Mix" (mixes
 * API) — no backend. "Follow/Unfollow Artist" — no Tidal favourites-write path
 * on Android, and the desktop's toggle is dead anyway (no caller ever passes
 * `is_followed`, so it always reads "Follow Artist"). "Properties…" — Q1.
 */
fun artistActions(canViewArtist: Boolean): List<ActionEntry> = buildList {
    add(ActionEntry(ActionId.PLAY_ARTIST, "Play all by artist"))
    add(ActionEntry(ActionId.SHUFFLE_ARTIST, "Shuffle artist"))
    add(ActionEntry(ActionId.ENQUEUE_ARTIST, "Add all to queue"))
    if (canViewArtist) {
        add(ActionEntry(ActionId.VIEW_ARTIST, "View artist details"))
    }
}
```

**Imports to add:** the file is new; it needs exactly `io.github.auxen.model.Source` and `io.github.auxen.model.Track`. Nothing else — `buildList` is stdlib.

**Name-collision check before writing** (the never-re-declare rule). Run and expect **zero** hits:

```bash
grep -rn "enum class ActionId\|data class ActionEntry\|data class AlbumTarget\|fun albumTargetFor\|fun trackActions\|fun albumActions\|fun artistActions" --include="*.kt" android/
```

- [ ] **Step 3: Confirm GREEN**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*ActionSheetModelTest*'
```

Expected: `BUILD SUCCESSFUL`, 13 tests passing. Confirm the count from the XML rather than trusting the console:

```bash
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  android/app/build/test-results/testDebugUnitTest/TEST-io.github.auxen.ui.components.ActionSheetModelTest.xml
```

Expected: `failures="0" errors="0"` and `tests="13"`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/ActionSheetModel.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/ActionSheetModelTest.kt
git commit -m "feat(android): pure long-press action model for track/album/artist

Ports the desktop's three _build_menu_model methods as pure, unit-tested
functions. Entry visibility takes explicit can* Booleans rather than the
desktop's always-truthy callback-presence gates, and albumTargetFor
reproduces groupAlbums' key so Go-to-album resolves the right group.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 2: `AuxenTrackRow` — make the long-press reachable and felt

A long-press-only affordance is invisible and, today, **unreachable by TalkBack**: `AuxenTrackRow.kt:63` passes `onLongClick` with no `onLongClickLabel`, so TalkBack announces no long-press action and there is no way to trigger the sheet. It also gives no haptic, which the platform expects.

**Verified against Foundation 1.7.6 (this project's version), not assumed:** `combinedClickable` does **not** perform haptic feedback itself — the string `Haptic` appears nowhere in `androidx/compose/foundation/*Clickable*.class` (it occurs only under `foundation/text/`). So the manual call below is required and will **not** double-buzz. (Foundation added auto-haptics for `combinedClickable` in a later version; this project is on 1.7.6 via Compose BOM 2024.12.01.) `combinedClickable`'s 5th parameter is `onLongClickLabel: String?` — confirmed from the classfile signature `combinedClickable-cJG_KMw(Modifier, boolean, String, Role, String, Function0, Function0, Function0)`. `HapticFeedbackType.LongPress` and `HapticFeedback.performHapticFeedback` both exist in Compose UI 1.7.6.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/AuxenTrackRow.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/AuxenTrackRowUiTest.kt` (add one case)

**Interfaces:**
- Consumes: `LocalHapticFeedback`, `HapticFeedbackType` (Compose UI 1.7.6).
- Produces: no signature change — `AuxenTrackRow`'s parameter list is untouched. Behaviour only.

- [ ] **Step 1: RED — assert the missing a11y affordance**

Add to `AuxenTrackRowUiTest.kt`. **This fails**: the row currently sets no `onLongClickLabel`, so `SemanticsProperties.OnLongClick`'s label is null and `assertEquals` reports `null != "Show track actions"`.

```kotlin
    /**
     * A long-press-only affordance is invisible; without a label TalkBack
     * announces no long-press action at all and the sheet is unreachable for a
     * screen-reader user. The label is the ONLY thing that surfaces it.
     */
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
        assertEquals(
            "Show track actions",
            node.config[SemanticsActions.OnLongClick].label,
        )
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

**Imports to add to `AuxenTrackRowUiTest.kt`:** `androidx.compose.ui.semantics.SemanticsActions`. (`assertEquals`, `onNodeWithText`, `AuxenTheme` and the `track(...)` fixture are already there.)

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest --tests '*AuxenTrackRowUiTest*'
```

Expected RED: `longPressExposesALabelledSemanticsActionWhenHandlerPresent` fails with `expected:<Show track actions> but was:<null>`. `noLongClickActionWhenHandlerAbsent` should already pass (Compose omits the action when `onLongClick` is null) — it is a regression guard for Step 2's `?.let`.

- [ ] **Step 2: GREEN — label + haptic**

In `AuxenTrackRow.kt`, add the haptics handle at the top of the composable body (before `Row`):

```kotlin
    val haptics = LocalHapticFeedback.current
```

and replace the `combinedClickable` call at `:63`:

```kotlin
            .combinedClickable(
                onClick = onPlay,
                // Both are gated on the SAME null check as onLongClick, so a
                // host that offers no sheet advertises no long-press action and
                // buzzes for nothing. Foundation 1.7.6's combinedClickable does
                // NOT perform haptics itself (verified against the artifact) —
                // this call is required, and does not double-buzz.
                onLongClickLabel = onLongPress?.let { "Show track actions" },
                onLongClick = onLongPress?.let { handler ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        handler()
                    }
                },
            )
```

**Imports to add to `AuxenTrackRow.kt`:** `androidx.compose.ui.hapticfeedback.HapticFeedbackType` and `androidx.compose.ui.platform.LocalHapticFeedback`. (`combinedClickable` and `ExperimentalFoundationApi` are already imported at `:5`/`:3`.)

The label is a literal, matching this file's own precedent — its `contentDescription`s at `:125` are literals too. (Repo-wide the convention is mixed; `R.string.settings_gear_a11y` exists. Consistency *within the file* wins here.)

- [ ] **Step 3: Confirm GREEN + goldens unmoved**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest verifyRoborazziDebug
```

Expected: `BUILD SUCCESSFUL`. **`track-row-*` goldens must NOT move** — semantics and haptics are not pixels. If `verifyRoborazziDebug` reports a diff on `track-row-tidal-favorite-*` or `track-row-local-explicit-*`, something else changed: find it and revert it. **Do not re-record.**

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/AuxenTrackRow.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/AuxenTrackRowUiTest.kt
git commit -m "feat(android): labelled, haptic long-press on AuxenTrackRow

The long-press action sheet was unreachable by TalkBack (no
onLongClickLabel) and gave no haptic. Both are gated on the same null
handler check, so a host with no sheet advertises nothing. Foundation
1.7.6's combinedClickable does not self-haptic; verified, no double-buzz.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 3: Track sheet on the model + "Go to album" / "Go to artist" + the seed nav arg

Rewire `TrackActionSheetContent` onto Task 1's model, add the two nav entries, and wire them from **all six** track-row hosts. Also fixes a real dead-end (below) and closes the Home gap.

**Two real problems this task fixes, both traced:**

1. **`HomeScreen` has no long-press at all.** `HomeScreen.kt:141-146` renders `AuxenTrackRow` with **no `onLongPress`** and hosts no `TrackActionSheet` — Recently Played is the one track list with no menu. (`grep -rn "onLongPress" ui/` → five hosts; Home is absent.)
2. **"Go to album" from a Tidal-only track would dead-end.** `AlbumDetailScreen` derives its remote seed from *local* tracks only: `tracks.firstOrNull { it.isTidal }?.sourceId` where `tracks = group?.tracks ?: emptyList()` (`:85`, `:94`). Navigate there from a Tidal **search result** with no local group and `group` is null → `tracks` is empty → `seedTidalTrackId` is **null** → `loadAlbumDetail(album, artist, null, null)` takes its early return — `if (tidalAlbumId == null && seedTidalTrackId == null) { albumDetailLoading.value = false; return }`, the block opening `PlayerViewModel.kt:534` inside `fun loadAlbumDetail` (`:528`) — → `displayTracks` is empty → the user lands on "no tracks". A silently broken entry is worse than a missing one. **Fix:** carry the originating track's id as an optional `seedTrackId` nav arg; `loadAlbumDetail` **already takes `seedTidalTrackId`** and resolves the album id from it via `trackRelations` — the screen just never had one to pass.

   **The artist route needs no such arg** — and this asymmetry is real, not an oversight. `loadArtistDetail` has **no** early return; with no id and no seed it falls through to `Graph.tidal.searchArtistIdExact(artistName)` (`PlayerViewModel.kt:476-479`), which resolves a Tidal-only artist in one request. `loadAlbumDetail` has no by-name fallback (there is no album-search-by-name path), hence its early return, hence the seed.

> **TRAP — `topBarTitle` matches route strings literally.** `MainActivity.kt:90-100` maps `"album/{album}/{artist}?tidalAlbumId={tidalAlbumId}"` to `"Album"` by **exact string match** on the registered route pattern (`currentBackStackEntryAsState().destination.route` returns the *pattern*, not the filled URL). Adding an argument changes that pattern. **Update `topBarTitle` in the same edit** or the Album screen's top bar silently loses its label and renders `""`.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/TrackActionSheet.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (route + `albumRoute` + `topBarTitle`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/AlbumDetailScreen.kt` (accept + prefer the seed arg)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt`, `.../CollectionScreen.kt`, `.../SearchScreen.kt`, `.../HomeScreen.kt`, `.../ArtistDetailScreen.kt` (wire nav; Home also gains its sheet)
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/TrackActionSheetUiTest.kt` (add cases)

**Interfaces:**
- Consumes: `ActionId`/`ActionEntry`/`trackActions`/`albumTargetFor` (Task 1).
- Produces: `TrackActionSheet(..., onGoToAlbum: (() -> Unit)? = null, onGoToArtist: (() -> Unit)? = null)`; same two on `TrackActionSheetContent`; nav route `album/{album}/{artist}?tidalAlbumId={tidalAlbumId}&seedTrackId={seedTrackId}`; `AlbumDetailScreen(..., seedTrackId: String? = null)`.

- [ ] **Step 1: RED — the two nav entries do not exist**

Add to `TrackActionSheetUiTest.kt`. **These fail**: `TrackActionSheet` has no `onGoToAlbum`/`onGoToArtist` parameters, so the file does not compile — `Cannot find a parameter with this name: onGoToAlbum`.

```kotlin
    /**
     * The nav entries are gated on BOTH a handler and the data to navigate
     * with — the desktop gates "Go to Album" on `album and artist`
     * (context_menu.py:228). A host that passes no handler must not show a
     * dead row; this is also what keeps the `action-sheet-*` goldens (which
     * pass no handler) stable.
     */
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

    @Test
    fun navActionsAbsentWhenHostProvidesNoHandlers() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                TrackActionSheet(
                    track = track(),
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

        compose.onNodeWithText("Go to album").assertDoesNotExist()
        compose.onNodeWithText("Go to artist").assertDoesNotExist()
    }
```

The existing `track(...)` helper at `TrackActionSheetUiTest.kt:20-25` builds a `Track` with **no album**, which would make `goToAlbumAndArtistShownWithHandlersAndInvokeThem` fail for the wrong reason. Give it an album parameter:

```kotlin
private fun track(
    title: String = "Everlong",
    artist: String = "Foo Fighters",
    album: String? = "The Colour and the Shape",
    source: Source = Source.LOCAL,
    sourceId: String = "x",
) = Track(title = title, artist = artist, album = album, source = source, sourceId = sourceId)
```

**Imports to add to `TrackActionSheetUiTest.kt`:** `androidx.compose.ui.test.assertDoesNotExist` is already used at `:139`; `SemanticsActions`, `performSemanticsAction`, `assertEquals` are all present. No new imports.

- [ ] **Step 2: GREEN — render `TrackActionSheetContent` from the model**

Replace the `if (!showPlaylists) { … }` branch of `TrackActionSheetContent` (`TrackActionSheet.kt:157-180`) with a model-driven render. Add `onGoToAlbum`/`onGoToArtist` params to **both** `TrackActionSheet` and `TrackActionSheetContent`.

`TrackActionSheetContent`'s new signature and body:

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
            // ActionSheetModelTest); this only maps id -> icon + handler. Keep
            // it that way: the desktop's conditionality bugs all live in the
            // "which entries" half.
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
                    // The album/artist ids are never produced by trackActions.
                    // An exhaustive `when` over the shared enum needs a branch;
                    // it is unreachable, so it renders nothing.
                    else -> Unit
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

In `TrackActionSheet`, thread the two new handlers through the same dismiss-first wrapper the existing `onShowInfo` uses (`:89`) — navigation must not leave a sheet stranded over the destination:

```kotlin
            onShowInfo = onShowInfo?.let { { it(); onDismiss() } },
            onGoToAlbum = onGoToAlbum?.let { { it(); onDismiss() } },
            onGoToArtist = onGoToArtist?.let { { it(); onDismiss() } },
```

and add to `TrackActionSheet`'s own parameter list, after `onShowInfo`:

```kotlin
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
```

**Imports to add to `TrackActionSheet.kt`:** `androidx.compose.material.icons.filled.Album` and `androidx.compose.material.icons.filled.Person`. `ActionId`/`trackActions` are same-package (`io.github.auxen.ui.components`) and need none. Everything else named above is already imported.

- [ ] **Step 3: Add the seed nav arg (route + `topBarTitle` + `AlbumDetailScreen`)**

In `MainActivity.kt`, extend `albumRoute` (`:181-182`):

```kotlin
    fun albumRoute(album: String, artist: String, tidalAlbumId: String? = null, seedTrackId: String? = null): String =
        "album/${Uri.encode(album)}/${Uri.encode(artist)}" +
            listOfNotNull(
                tidalAlbumId?.let { "tidalAlbumId=$it" },
                seedTrackId?.let { "seedTrackId=$it" },
            ).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
```

Register the new argument (`:336-347`):

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

**And update `topBarTitle` (`:91`) in the same edit** — it matches the pattern literally:

```kotlin
    "album/{album}/{artist}?tidalAlbumId={tidalAlbumId}&seedTrackId={seedTrackId}" -> "Album"
```

In `AlbumDetailScreen.kt`, accept the arg and prefer it over the local-derived seed:

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

and replace the seed derivation (`:94`):

```kotlin
    // The nav-arg seed wins: it is the Tidal track the user actually
    // long-pressed, and it is the ONLY seed available when this album has no
    // local group at all (a "Go to album" from a Tidal search result). Without
    // it, loadAlbumDetail takes its early return and the screen settles on
    // "no tracks" — a dead-end. The local fallback keeps every existing
    // navigation (Library/Collection card taps, which pass no seed) unchanged.
    val seedTidalTrackId = seedTrackId ?: tracks.firstOrNull { it.isTidal }?.sourceId
```

`LaunchedEffect`'s key list at `:95` must gain the new value or a re-navigation to the same album with a *different* seed would not refetch:

```kotlin
    LaunchedEffect(album, artist, tidalAlbumId, seedTidalTrackId) {
        viewModel.loadAlbumDetail(album, artist, tidalAlbumId, seedTidalTrackId)
    }
```

(The expression already reads `seedTidalTrackId`; it now resolves to the new definition. No further change.)

**Imports to add:** none — `AlbumDetailScreen.kt` gains only a `String?` parameter, and `MainActivity.kt` already imports `navArgument` (`:61`) and `Uri` (`:5`).

- [ ] **Step 4: Wire the six track hosts**

> **This is the largest single step in the plan** — it is the one that makes Task 3 touch eight files. It is still *one* coherent change (wire two nav entries + the seed arg through every track-row host), so it stays one commit rather than fragmenting into six near-identical ones. But work host-by-host and **compile after each**: the failure mode here is a missing per-file import (Kotlin resolves per file), and six hosts means six chances at it. `HomeScreen` is not like the other five — it has no sheet at all today and needs the whole block, not just two handlers.

Each host already holds `sheetTrack`; give the sheet the two handlers. The **album target must come from `albumTargetFor`**, never from `track.album`/`track.artist` directly — see its KDoc.

`LibraryScreen`, `CollectionScreen`, `SearchScreen` take new nav lambdas from `MainActivity` (they have none today). Add to each signature:

```kotlin
    onOpenAlbumForTrack: (album: String, artist: String, seedTrackId: String?) -> Unit,
    onOpenArtistByName: (String) -> Unit,
```

and in each `TrackActionSheet(...)` call:

```kotlin
            onGoToAlbum = albumTargetFor(track)?.let { target ->
                { onOpenAlbumForTrack(target.album, target.albumArtist, track.sourceId.takeIf { track.isTidal }) }
            },
            onGoToArtist = { onOpenArtistByName(track.artist) },
```

`MainActivity` passes, for each of the three:

```kotlin
                            onOpenAlbumForTrack = { album, artist, seed ->
                                navController.navigate(albumRoute(album, artist, seedTrackId = seed))
                            },
                            onOpenArtistByName = { name -> navController.navigate(artistRoute(name)) },
```

`AlbumDetailScreen` — offer only "Go to artist" (the user is already on the album):

```kotlin
            onGoToArtist = { onOpenArtist(track.artist, null) },
```

`ArtistDetailScreen` — offer only "Go to album" (already on the artist). It has `onOpenAlbum(album, artist, tidalAlbumId)` but no seed parameter; rather than widen it, navigate with the album target and let the screen's own local seed resolve, **except** it cannot for a Tidal-only track. Widen `onOpenAlbum` instead — it is one call site:

```kotlin
    onOpenAlbum: (album: String, artist: String, tidalAlbumId: String?, seedTrackId: String?) -> Unit,
```

Its two existing call sites gain a trailing `null` — identify them by their **enclosing lambda**, not by line (this file is being actively committed to; `a6bdef1` moved both +3):

- inside `items(albums, …)`: `onClick = { onOpenAlbum(album.album, album.albumArtist, null) }` (`:224` as of `a6bdef1`)
- inside `items(tidalOnlyAlbums, …)`: `onClick = { onOpenAlbum(summary.title, detail?.name ?: artist, summary.id) }` (`:237`)

and `MainActivity.kt:356-358` becomes:

```kotlin
                            onOpenAlbum = { album, artist, tidalAlbumId, seedTrackId ->
                                navController.navigate(albumRoute(album, artist, tidalAlbumId, seedTrackId))
                            },
```

and the sheet gets:

```kotlin
            onGoToAlbum = albumTargetFor(track)?.let { target ->
                { onOpenAlbum(target.album, target.albumArtist, null, track.sourceId.takeIf { track.isTidal }) }
            },
```

`HomeScreen` — **has no sheet at all today.** Give it the same treatment as the other list hosts: add `playlists`, a `sheetTrack`, `onLongPress`, and the sheet block, plus the two nav lambdas in its signature. `HomeScreen(viewModel)` at `MainActivity.kt:328` becomes:

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

and in `HomeScreen`'s body, alongside the existing `favoriteKeys`:

```kotlin
    val playlists by viewModel.playlists.collectAsState()
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
```

`AuxenTrackRow` at `:141-146` gains `onLongPress = { sheetTrack = track }`, and the `LazyColumn`'s closing brace is followed by the standard sheet block (copy `SearchScreen.kt:222-235` verbatim, adding the two nav handlers above).

**Imports to add:**
- `HomeScreen.kt`: `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.setValue`, `io.github.auxen.ui.components.TrackActionSheet`, `io.github.auxen.ui.components.albumTargetFor`. (`Track`, `collectAsState`, `getValue` are already imported.)
- `LibraryScreen.kt`, `CollectionScreen.kt`, `SearchScreen.kt`, `ArtistDetailScreen.kt`, `AlbumDetailScreen.kt`: `io.github.auxen.ui.components.albumTargetFor` (except `AlbumDetailScreen`, which uses only `onGoToArtist` and needs none).

- [ ] **Step 5: Build + full suite + goldens**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest verifyRoborazziDebug assembleDebug
```

Expected: `BUILD SUCCESSFUL`. **`action-sheet-{light,dark}` must NOT move** — `ComponentScreenshotTest.ActionSheetRootPage` passes neither `onGoToAlbum` nor `onGoToArtist`, so `trackActions` returns the same five entries it always did. A diff there means a handler defaulted non-null: fix the default, **do not re-record**.

- [ ] **Step 6: On-device checkpoint**

Locally-built APK (CI has no Tidal client id — Tidal login fails on a CI APK):
1. Search a Tidal-only artist → long-press a result → **"Go to album"** → the album screen shows the **full remote tracklist**, not "no tracks". *This is the dead-end fix; if it shows "no tracks", the seed arg is not arriving — check `topBarTitle` and the route string match.*
2. The Album screen's top bar still reads **"Album"** (the `topBarTitle` trap).
3. Home → Recently Played → long-press → the sheet appears (it never did before).
4. Long-press a track with no album (a stray file) → **"Go to album" absent**, "Go to artist" present.
5. TalkBack on → focus a track row → it announces a long-press action labelled "Show track actions"; long-press buzzes **once**.

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
git commit -m "feat(android): Go to album/artist on the track sheet; Home gains a sheet

Renders TrackActionSheetContent from the pure model and adds the desktop's
two nav entries. Carries the originating track id as an optional seedTrackId
nav arg so Go-to-album from a Tidal-only track resolves its remote tracklist
instead of dead-ending on 'no tracks'. topBarTitle updated for the new route
pattern (it matches literally). Home's Recently Played had no menu at all.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 4: ViewModel list actions — the backing for the album/artist sheets

Four additive VM methods. **Not new backends** — each is the list form of a method that already exists, built from the same pieces `fun playAll(tracks: List<Track>, shuffled: Boolean = false)` already uses (`PlayerViewModel.kt:669`): `Graph.library.upsert`, `Graph.mediaItemFor`, the `MediaController`. No new endpoint, no new repository method, no network.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt`

**Interfaces:**
- Consumes: `Graph.library.upsert/addTrackToPlaylist/createPlaylist`, `Graph.mediaItemFor`, `controller` — all existing.
- Produces: `fun playNextAll(tracks: List<Track>)`, `fun enqueueAll(tracks: List<Track>)`, `fun addTracksToPlaylist(tracks: List<Track>, playlistId: Long)`, `fun createPlaylistAndAddAll(tracks: List<Track>, name: String)`.

- [ ] **Step 1: Add the four methods**

Insert after the **closing brace of `fun playNext(track: Track)`** — `PlayerViewModel.kt:905-913` as of `a6bdef1`, i.e. immediately above `fun addToPlaylist(track: Track, playlistId: Long)`.

> **Anchor on the signature, not the number.** `grep -n "fun playNext" PlayerViewModel.kt`, then insert after *that* function's closing brace. This file is being actively moved by a concurrent agent (`791d00a` shifted it +25 lines). Landing this block mid-function — `fun play(track: Track)` sits directly above `playNext` and ends only ~10 lines earlier — is a compile break, not a lint nit.

```kotlin
    /**
     * Insert [tracks] after the current queue item, in order — the album/artist
     * "Play next". The list form of [playNext]; `addMediaItems(index, items)`
     * inserts the whole block at once, so album order is preserved (calling
     * [playNext] per track in a loop would reverse it, each one landing at the
     * same index and pushing the previous down).
     */
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

    /** Append [tracks] to the queue, in order — the album/artist "Add to queue". */
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

    /**
     * Add every track in [tracks] to [playlistId], in order.
     *
     * Sequential, not concurrent, deliberately: `addTrackToPlaylist` is
     * upsert-then-append (`LibraryRepository.kt:69-72`) and the append writes a
     * position derived from the playlist's current length, so parallel writes
     * would race into a scrambled order.
     */
    fun addTracksToPlaylist(tracks: List<Track>, playlistId: Long) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            runCatching { tracks.forEach { Graph.library.addTrackToPlaylist(it, playlistId) } }
        }
    }

    /** Create a playlist named [name] and add every track in [tracks] to it, in order. */
    fun createPlaylistAndAddAll(tracks: List<Track>, name: String) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val id = Graph.library.createPlaylist(name)
                tracks.forEach { Graph.library.addTrackToPlaylist(it, id) }
            }
        }
    }
```

**Imports to add:** none. `Graph` (`:22`), `Track` (`:29`), `viewModelScope` (`:11`) and `launch` (`:44`) are all already imported.

**No unit test.** The `PlayerViewModel` is outside the unit-test boundary (Global Constraints) — it needs a `MediaController`, which needs a live `PlaybackService`. These close on Tasks 5/6's on-device checkpoints, exactly as the existing `play`/`enqueue`/`playAll` do.

- [ ] **Step 2: Build**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest assembleDebug
```

Expected: `BUILD SUCCESSFUL`, suite unchanged and green.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/PlayerViewModel.kt
git commit -m "feat(android): list forms of playNext/enqueue/addToPlaylist

Backs the album and artist long-press menus. Each is the list variant of an
existing single-track method, built from the same pieces playAll already
uses — no new endpoint, no new repository method. playNextAll inserts the
block in one addMediaItems call so album order survives.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 5: Album long-press — `AlbumActionSheet` + `AlbumCard`

**Scope: album cards backed by an `AlbumGroup` only** — Library's grid, Collection's grid, and `ArtistDetailScreen`'s **local** albums row (`items(albums, …)`). The Tidal-only cards in the discography row (`items(tidalOnlyAlbums, …)`, the `AlbumCard` whose `onPlay = null`) are `AlbumSummary` values with **no tracks**: Play/Shuffle/Play-next/Enqueue/Add-to-playlist would all be dead, leaving a one-entry menu. They pass `onLongPress = null` and keep tap-to-open.

> **Line numbers in `ArtistDetailScreen.kt` drift** — a concurrent agent is committing to it. Identify the two `AlbumCard` call sites by their enclosing `items(albums, …)` / `items(tidalOnlyAlbums, …)` lambda, not by line. (`HomeScreen`'s cards are also excluded — see open question **Q3**; its `onClick` plays the album rather than opening it, which is a pre-existing inconsistency this plan does not touch.)

> **Do NOT create a second `SheetAction`.** One already exists at `TrackActionSheet.kt:210` as a **file-private** top-level. Declaring another in `AlbumActionSheet.kt` compiles but forks the sheet-row styling; declaring one in the same file is a "conflicting declarations" error. **Change the existing one from `private` to `internal`** and reuse it — same package, same module.

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/TrackActionSheet.kt` (`private fun SheetAction` → `internal fun SheetAction`, one word)
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumActionSheet.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumCard.kt` (nullable `onLongPress`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt`, `.../CollectionScreen.kt`, `.../ArtistDetailScreen.kt` (host the sheet)
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/AlbumActionSheetUiTest.kt` (create)

**Interfaces:**
- Consumes: `albumActions`/`ActionId`/`ActionEntry` (Task 1); `playAll`/`playNextAll`/`enqueueAll`/`addTracksToPlaylist`/`createPlaylistAndAddAll` (Task 4); `AlbumGroup` (`data/LibraryGrouping.kt:11`).
- Produces: `internal fun SheetAction` (visibility widened); `@Composable fun AlbumActionSheet(...)`; `internal fun AlbumActionSheetContent(...)`; `AlbumCard(..., onLongPress: (() -> Unit)? = null)`.

- [ ] **Step 1: RED — the sheet does not exist**

Create `android/app/src/test/kotlin/io/github/auxen/ui/components/AlbumActionSheetUiTest.kt`. **Fails to compile**: `Unresolved reference: AlbumActionSheet`.

```kotlin
package io.github.auxen.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour tests for [AlbumActionSheet] — the album long-press menu (mobile
 * counterpart of the desktop AlbumContextMenu).
 *
 * Both Robolectric concessions from [TrackActionSheetUiTest]'s class KDoc apply
 * verbatim and for the same root causes: sheet rows are tapped via
 * `performSemanticsAction(SemanticsActions.OnClick)` because coordinate-based
 * `performClick()` is swallowed by the ModalBottomSheet's full-root scaffolding
 * node, and the "New playlist…" AlertDialog + OutlinedTextField combination
 * never idles, so the create flow is not asserted end-to-end. Read that KDoc
 * before "fixing" anything here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class AlbumActionSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val playlists = listOf(PlaylistEntity(id = 1, name = "Road Trip", color = "#3498db"))

    @Test
    fun playAndShuffleInvokeAndDismiss() {
        var played = 0
        var shuffled = 0
        var dismissed = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumActionSheet(
                    album = "OutRun",
                    albumArtist = "Kavinsky",
                    playlists = playlists,
                    onDismiss = { dismissed++ },
                    onPlay = { played++ },
                    onShuffle = { shuffled++ },
                    onPlayNext = {},
                    onEnqueue = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Play album").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, played)
        assertEquals(1, dismissed)

        compose.onNodeWithText("Shuffle album").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, shuffled)
        assertEquals(2, dismissed)
    }

    @Test
    fun goToArtistShownOnlyWithAHandler() {
        var wentToArtist = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumActionSheet(
                    album = "OutRun",
                    albumArtist = "Kavinsky",
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onShuffle = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                    onGoToArtist = { wentToArtist++ },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Go to artist").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, wentToArtist)
    }

    @Test
    fun goToArtistAbsentWithoutHandler() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumActionSheet(
                    album = "OutRun",
                    albumArtist = "Kavinsky",
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onShuffle = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Go to artist").assertDoesNotExist()
        compose.onNodeWithText("Play album").assertExists()
    }

    @Test
    fun addToPlaylistShowsPlaylistPage() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumActionSheet(
                    album = "OutRun",
                    albumArtist = "Kavinsky",
                    playlists = playlists,
                    onDismiss = {},
                    onPlay = {},
                    onShuffle = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onAddToPlaylist = {},
                    onCreatePlaylist = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Add to playlist").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNodeWithText("Road Trip").assertExists()
        compose.onNodeWithText("Play album").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Widen `SheetAction`**

In `TrackActionSheet.kt:210`, change exactly one word:

```kotlin
internal fun SheetAction(label: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
```

Kotlin `private` on a top-level declaration is **file**-private, so `AlbumActionSheet.kt` cannot see it otherwise. `internal` is module-wide, which is what both new sheets need. **Do not copy it.**

- [ ] **Step 3: GREEN — create `AlbumActionSheet.kt`**

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
 * AlbumContextMenu (`auxen/views/context_menu.py:392-443`).
 *
 * Same shape, same idiom and the same `SheetAction` rows as [TrackActionSheet];
 * the entry list itself comes from the pure [albumActions] model.
 *
 * [onGoToArtist] is optional: a host already ON the artist (the artist screen's
 * albums row) omits it and the action is not offered.
 *
 * **Not offered: "Save to Collection".** Android's favourites are per-track
 * (`LibraryRepository.setFavorite(track, …)`); there is no album-level
 * favourite to toggle. Belongs to Slice 2 — do not fake it by favouriting
 * every track, which is a different thing and not undoable as one action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumActionSheet(
    album: String,
    albumArtist: String,
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
            album = album,
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
            // Dismiss FIRST, then navigate: leaving a sheet mounted over the
            // destination is the same trap TrackActionSheet documents at :88.
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

/**
 * Stateless body of [AlbumActionSheet].
 *
 * Split out for the same reason [TrackActionSheetContent] is: the Material 3
 * [ModalBottomSheet] wrapper hosts its content in a *separate* window whose
 * animated entrance never settles under Robolectric, so the sheet itself cannot
 * be captured or reliably queried. This plain [Column] renders in the main
 * composition.
 */
@Composable
internal fun AlbumActionSheetContent(
    album: String,
    albumArtist: String,
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
            album,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Text(
            albumArtist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (!showPlaylists) {
            albumActions(albumArtist = albumArtist, canGoToArtist = onGoToArtist != null).forEach { entry ->
                when (entry.id) {
                    ActionId.PLAY_ALBUM ->
                        SheetAction(entry.label, { Icon(Icons.Filled.PlayArrow, null) }) { onPlay() }
                    ActionId.SHUFFLE_ALBUM ->
                        SheetAction(entry.label, { Icon(Icons.Filled.Shuffle, null) }) { onShuffle() }
                    ActionId.PLAY_ALBUM_NEXT ->
                        SheetAction(entry.label, { Icon(Icons.Filled.SkipNext, null) }) { onPlayNext() }
                    ActionId.ENQUEUE_ALBUM ->
                        SheetAction(entry.label, { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }) { onEnqueue() }
                    ActionId.ADD_TO_PLAYLIST ->
                        SheetAction(entry.label, { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }) { onShowPlaylists() }
                    ActionId.GO_TO_ARTIST ->
                        SheetAction(entry.label, { Icon(Icons.Filled.Person, null) }) { onGoToArtist?.invoke() }
                    else -> Unit
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

- [ ] **Step 4: `AlbumCard` — nullable long-press**

`AlbumCard.kt:48` currently uses `Modifier.clickable(onClick = onClick)`. Add the parameter after `onPlay`:

```kotlin
    onLongPress: (() -> Unit)? = null,
```

and replace the modifier:

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
                // Same gate/haptic discipline as AuxenTrackRow (Task 2): a card
                // with no sheet advertises no long-press action. Foundation
                // 1.7.6 does not self-haptic.
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

**Imports to add to `AlbumCard.kt`:** `androidx.compose.foundation.ExperimentalFoundationApi`, `androidx.compose.foundation.combinedClickable`, `androidx.compose.ui.hapticfeedback.HapticFeedbackType`, `androidx.compose.ui.platform.LocalHapticFeedback`. **Remove** the now-unused `androidx.compose.foundation.clickable` import at `:4` (the `IconButton` at `:67` uses `onClick`, not `clickable`) — an unused import is a lint warning, not an error, but leave the file clean.

**Golden:** `album-card-*` must not move. `combinedClickable` with a null `onLongClick` renders identically to `clickable`, and the fixture passes no `onLongPress`.

- [ ] **Step 5: Host the sheet in the three album grids**

Pattern, identical in `LibraryScreen`, `CollectionScreen` and `ArtistDetailScreen`. State beside the existing `sheetTrack`:

```kotlin
    var sheetAlbum by remember { mutableStateOf<AlbumGroup?>(null) }
```

`AlbumCard(...)` gains:

```kotlin
                            onLongPress = { sheetAlbum = album },
```

and after the screen's existing `sheetTrack?.let { … }` block:

```kotlin
    sheetAlbum?.let { group ->
        AlbumActionSheet(
            album = group.album,
            albumArtist = group.albumArtist,
            playlists = playlists,
            onDismiss = { sheetAlbum = null },
            onPlay = { viewModel.playAll(group.tracks) },
            onShuffle = { viewModel.playAll(group.tracks, shuffled = true) },
            onPlayNext = { viewModel.playNextAll(group.tracks) },
            onEnqueue = { viewModel.enqueueAll(group.tracks) },
            onAddToPlaylist = { viewModel.addTracksToPlaylist(group.tracks, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAddAll(group.tracks, it) },
            onGoToArtist = { onOpenArtist(group.albumArtist) },
        )
    }
```

Per-screen deltas:
- **`LibraryScreen`** — `onOpenArtist: (String) -> Unit` already exists in its signature (`:84`). Use as written.
- **`CollectionScreen`** — same (`:75`). Use as written.
- **`ArtistDetailScreen`** — the user is already on the artist, so **omit `onGoToArtist`** (the model then hides the entry). Only the **local** `items(albums, …)` cards get `onLongPress`; the `items(tidalOnlyAlbums, …)` cards **do not** — they have no tracks and would show a menu whose every playback entry is dead. Leave their `onPlay = null` as-is and add no `onLongPress`.
- **`HomeScreen`** — excluded, see **Q3**.

**Imports to add:** `io.github.auxen.ui.components.AlbumActionSheet` in all three; `io.github.auxen.data.AlbumGroup` in `ArtistDetailScreen.kt` (`LibraryScreen.kt:45` and `CollectionScreen.kt:50` already import it).

- [ ] **Step 6: Build + full suite + goldens**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest verifyRoborazziDebug assembleDebug
```

Expected: `BUILD SUCCESSFUL`; `album-card-*` and `action-sheet-*` **unmoved**.

- [ ] **Step 7: On-device checkpoint**

1. Library → Albums → long-press a card → sheet with Play/Shuffle/Play next/Add to queue/Add to playlist/Go to artist. One buzz.
2. "Play album next" with something playing → the **whole album** lands directly after the current track, **in order** (this is what `addMediaItems(index, items)` buys; a per-track loop would reverse it).
3. "Add to playlist" → pick one → open the playlist → tracks are in album order.
4. Artist screen → long-press a **local** album card → sheet appears, **no "Go to artist"**. Long-press a **Tidal-only** discography card → **nothing happens** (by design); tap still opens it.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumActionSheet.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumCard.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/TrackActionSheet.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/ArtistDetailScreen.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/AlbumActionSheetUiTest.kt
git commit -m "feat(android): album long-press action sheet

Ports the desktop AlbumContextMenu as a ModalBottomSheet reusing
TrackActionSheet's SheetAction row (widened private -> internal, not
duplicated). Entries come from the pure albumActions model. Save-to-
Collection is dropped: Android favourites are per-track. Tidal-only
discography cards get no long-press — they have no tracks to act on.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 6: Artist long-press — `ArtistActionSheet` + `ArtistRow`

**Scope: artist rows backed by an `ArtistGroup`** — Library's Artists tab and Collection's Artists tab. The **similar-artists carousel** in `ArtistDetailScreen` (the `ArtistRow` inside `items(similarArtists)`) passes only a name + id and has no tracks; its only possible entry is "View artist details", which its **tap already does**. It gets no long-press. Same reasoning as the Tidal-only album cards.

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistActionSheet.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistRow.kt` (nullable `onLongPress`)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt`, `.../CollectionScreen.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/ArtistActionSheetUiTest.kt` (create)

**Interfaces:**
- Consumes: `artistActions`/`ActionId` (Task 1); `SheetAction` (`internal` as of Task 5); `playAll`/`enqueueAll` (Task 4); `ArtistGroup` (`data/LibraryGrouping.kt:19`).
- Produces: `@Composable fun ArtistActionSheet(...)`; `internal fun ArtistActionSheetContent(...)`; `ArtistRow(..., onLongPress: (() -> Unit)? = null)`.

- [ ] **Step 1: RED**

Create `android/app/src/test/kotlin/io/github/auxen/ui/components/ArtistActionSheetUiTest.kt`. **Fails to compile**: `Unresolved reference: ArtistActionSheet`.

```kotlin
package io.github.auxen.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertDoesNotExist
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

/**
 * Behaviour tests for [ArtistActionSheet] — the artist long-press menu (mobile
 * counterpart of the desktop ArtistContextMenu).
 *
 * Robolectric concession 1 from [TrackActionSheetUiTest]'s class KDoc applies
 * verbatim: rows are tapped via `performSemanticsAction(SemanticsActions.OnClick)`,
 * never `performClick()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class ArtistActionSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun playShuffleAndEnqueueInvokeAndDismiss() {
        var played = 0
        var shuffled = 0
        var enqueued = 0
        var dismissed = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                ArtistActionSheet(
                    artist = "Kavinsky",
                    onDismiss = { dismissed++ },
                    onPlayAll = { played++ },
                    onShuffle = { shuffled++ },
                    onEnqueue = { enqueued++ },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Play all by artist").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Shuffle artist").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Add all to queue").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, played)
        assertEquals(1, shuffled)
        assertEquals(1, enqueued)
        assertEquals(3, dismissed)
    }

    @Test
    fun viewArtistDetailsShownOnlyWithAHandler() {
        var viewed = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                ArtistActionSheet(
                    artist = "Kavinsky",
                    onDismiss = {},
                    onPlayAll = {},
                    onShuffle = {},
                    onEnqueue = {},
                    onViewArtist = { viewed++ },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("View artist details").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, viewed)
    }

    @Test
    fun viewArtistDetailsAbsentWithoutHandler() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                ArtistActionSheet(
                    artist = "Kavinsky",
                    onDismiss = {},
                    onPlayAll = {},
                    onShuffle = {},
                    onEnqueue = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("View artist details").assertDoesNotExist()
        compose.onNodeWithText("Play all by artist").assertExists()
    }

    /**
     * The desktop artist menu has NO "Add to playlist"
     * (context_menu.py:567-622). Parity, deliberately — this pins it so nobody
     * "restores" it by symmetry with the album sheet.
     */
    @Test
    fun noAddToPlaylistOnTheArtistMenu() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                ArtistActionSheet(
                    artist = "Kavinsky",
                    onDismiss = {},
                    onPlayAll = {},
                    onShuffle = {},
                    onEnqueue = {},
                    onViewArtist = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Add to playlist").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: GREEN — create `ArtistActionSheet.kt`**

```kotlin
package io.github.auxen.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
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
 * ArtistContextMenu (`auxen/views/context_menu.py:567-622`).
 *
 * [onViewArtist] is optional: a host already on the artist's page omits it.
 *
 * **Deliberately smaller than the desktop's menu.** Not offered, and each for a
 * reason, not an oversight:
 *  - *Go to Artist Radio* / *Start Mix* — need `artist.get_radio()` / the mixes
 *    API. Neither exists on Android; building them is a separate slice.
 *  - *Follow / Unfollow Artist* — needs Tidal favourites WRITES, which Android
 *    has no path for (`toggleFavorite` is local-DB only). The desktop's toggle
 *    is dead anyway: no caller passes `is_followed`, so it always reads
 *    "Follow Artist". Slice 2 owns this.
 *  - *Properties…* — see the plan's open question Q1.
 *  - *Add to playlist* — the desktop artist menu genuinely has none. Parity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistActionSheet(
    artist: String,
    onDismiss: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onEnqueue: () -> Unit,
    onViewArtist: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ArtistActionSheetContent(
            artist = artist,
            onPlayAll = { onPlayAll(); onDismiss() },
            onShuffle = { onShuffle(); onDismiss() },
            onEnqueue = { onEnqueue(); onDismiss() },
            // Dismiss FIRST, then navigate — same reason as the other sheets.
            onViewArtist = onViewArtist?.let { { it(); onDismiss() } },
        )
    }
}

/**
 * Stateless body of [ArtistActionSheet] — split out for the same reason
 * [TrackActionSheetContent] is (the ModalBottomSheet wrapper's separate window
 * never settles under Robolectric).
 */
@Composable
internal fun ArtistActionSheetContent(
    artist: String,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onEnqueue: () -> Unit,
    onViewArtist: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            artist,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        artistActions(canViewArtist = onViewArtist != null).forEach { entry ->
            when (entry.id) {
                ActionId.PLAY_ARTIST ->
                    SheetAction(entry.label, { Icon(Icons.Filled.PlayArrow, null) }) { onPlayAll() }
                ActionId.SHUFFLE_ARTIST ->
                    SheetAction(entry.label, { Icon(Icons.Filled.Shuffle, null) }) { onShuffle() }
                ActionId.ENQUEUE_ARTIST ->
                    SheetAction(entry.label, { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }) { onEnqueue() }
                ActionId.VIEW_ARTIST ->
                    SheetAction(entry.label, { Icon(Icons.Filled.Person, null) }) { onViewArtist?.invoke() }
                else -> Unit
            }
        }
    }
}
```

- [ ] **Step 3: `ArtistRow` — nullable long-press**

`ArtistRow.kt:41` uses `.clickable(onClick = onClick)`. Add the parameter and swap the modifier, exactly as `AlbumCard` did:

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

`onLongPress` goes **last** so the existing call sites are unaffected. All three current callers (`LibraryScreen`, `CollectionScreen`, `ArtistDetailScreen`'s similar-artists carousel) use named arguments, so ordering is moot — but keep the optional-params-last convention.

**Imports to add to `ArtistRow.kt`:** `androidx.compose.foundation.ExperimentalFoundationApi`, `androidx.compose.foundation.combinedClickable`, `androidx.compose.ui.hapticfeedback.HapticFeedbackType`, `androidx.compose.ui.platform.LocalHapticFeedback`. **Remove** the unused `androidx.compose.foundation.clickable` at `:3`.

There is **no `ArtistRow` golden**, so no golden exposure here.

- [ ] **Step 4: Host the sheet in the two artist lists**

In `LibraryScreen` and `CollectionScreen`, beside the other sheet state:

```kotlin
    var sheetArtist by remember { mutableStateOf<ArtistGroup?>(null) }
```

`ArtistRow(...)` gains:

```kotlin
                            onLongPress = { sheetArtist = artist },
```

and after the album sheet block:

```kotlin
    sheetArtist?.let { group ->
        ArtistActionSheet(
            artist = group.artist,
            onDismiss = { sheetArtist = null },
            onPlayAll = { viewModel.playAll(group.tracks) },
            onShuffle = { viewModel.playAll(group.tracks, shuffled = true) },
            onEnqueue = { viewModel.enqueueAll(group.tracks) },
            onViewArtist = { onOpenArtist(group.artist) },
        )
    }
```

`ArtistDetailScreen`'s similar-artists `ArtistRow`s get **no** `onLongPress` — see the scope note.

**Imports to add:** `io.github.auxen.ui.components.ArtistActionSheet` and `io.github.auxen.data.ArtistGroup` in both files (`LibraryScreen.kt:45` imports `AlbumGroup` but **not** `ArtistGroup`; `CollectionScreen.kt:50` likewise — both currently reach `ArtistGroup` only as `groupArtists`' inferred return type, which does **not** import the name).

- [ ] **Step 5: Build + full suite + goldens**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest verifyRoborazziDebug assembleDebug
```

Expected: `BUILD SUCCESSFUL`, no golden moves.

- [ ] **Step 6: On-device checkpoint**

1. Library → Artists → long-press → sheet: Play all by artist / Shuffle artist / Add all to queue / View artist details. **No** Follow, **no** Radio, **no** Properties, **no** Add to playlist.
2. "Add all to queue" → the queue grows by that artist's track count, order preserved.
3. Artist screen → similar-artists carousel → long-press → **nothing** (by design); tap still navigates.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistActionSheet.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/components/ArtistRow.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt \
        android/app/src/test/kotlin/io/github/auxen/ui/components/ArtistActionSheetUiTest.kt
git commit -m "feat(android): artist long-press action sheet

Ports the desktop ArtistContextMenu. Radio/Mix/Follow are dropped for want
of a backend — and the desktop's Follow toggle is dead anyway (no caller
passes is_followed, so it always reads 'Follow Artist'). No Add-to-playlist:
the desktop artist menu has none. Similar-artist rows get no long-press;
their only possible entry is what a tap already does.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

### Task 7: Clickable names — the sites the desktop actually makes clickable

**This task is the separable one.** If scope must be cut, cut this; Tasks 1–6 stand alone.

**Where names are clickable on the desktop — the complete inventory.** The desktop marks a clickable name with the `clickable-link` CSS class; `grep -rn "clickable-link" --include="*.py" auxen/` returns exactly four sites (a fifth, `sidebar.py:489`, is a Tidal *plan* label, not a name):

| Desktop site | Target | Android status |
| --- | --- | --- |
| `album_detail.py:172` — album header artist | artist detail | **already done** (`AlbumDetailScreen.kt:129-139`, tinted `colorScheme.primary`) |
| `home.py:169` — album-card **artist subtitle** | artist detail | **ADD** (Step 1) |
| `now_playing.py:458` — player **artist** | artist detail (`:833-844`) | **ADD** (Step 2) |
| `now_playing.py:444` — player **title** | album detail (`:846-861`) | **ADD** (Step 2) |

> **Track-row names are deliberately NOT made clickable — and this is parity, not a shortfall.** The desktop's standard track row has **no** `clickable-link` label; its answer for "go to this track's album/artist" is the right-click menu, which is exactly what Task 3 ported. On Android the case against is stronger still: `AuxenTrackRow`'s subtitle is a single `bodySmall` `Text` rendering `listOfNotNull(track.artist, track.album).joinToString(" — ")` (`:101-107`) inside a row whose entire surface is `combinedClickable(onClick = onPlay)`. Splitting it into competing tap targets would put two ~16dp-tall links inside a 60dp row whose primary action is *play* — under the 48dp minimum, a mis-tap magnet, and a golden-moving change to a shared component. **If the user wants track-row names tappable specifically, that is open question Q2 — do not build it on a guess.**

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumCard.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/NowPlayingScreen.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt` (nav for Now Playing)
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt`, `.../CollectionScreen.kt` (pass the card's artist handler)

**Interfaces:**
- Consumes: `albumTargetFor` (Task 1); `artistRoute`/`albumRoute` (`MainActivity`, Task 3).
- Produces: `AlbumCard(..., onArtistClick: (() -> Unit)? = null)`; `NowPlayingContent(..., album: String? = null, onOpenAlbum: (() -> Unit)? = null, onOpenArtist: (() -> Unit)? = null)`.

- [ ] **Step 1: `AlbumCard` — tappable artist subtitle**

Add the parameter and make the subtitle a link **only when a handler is present**. `AlbumCard.kt:96-104` becomes:

```kotlin
        if (artist != null) {
            Text(
                artist,
                style = MaterialTheme.typography.bodySmall,
                // Tinted ONLY when it is genuinely tappable — an untinted link
                // is invisible, and a tinted non-link is a lie. This is also
                // what keeps `album-card-*` stable: the golden fixture passes
                // no handler, so it stays onSurfaceVariant exactly as today.
                color = if (onArtistClick != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onArtistClick != null) {
                    Modifier.clickable(onClick = onArtistClick)
                } else {
                    Modifier
                },
            )
        }
```

with, in the parameter list after `onLongPress`:

```kotlin
    onArtistClick: (() -> Unit)? = null,
```

**Imports:** `androidx.compose.foundation.clickable` — which Task 5 removed. **Re-add it**; both `combinedClickable` (the card) and `clickable` (the subtitle) are now used. This is exactly the per-file import trap the Global Constraints call out.

Wire it where an artist route exists. `LibraryScreen.kt:180-188` and `CollectionScreen.kt:186-194`:

```kotlin
                            onArtistClick = { onOpenArtist(album.albumArtist) },
```

`ArtistDetailScreen`'s cards pass `artist = null` already (`:213`), so there is no subtitle to link — no change. `HomeScreen`'s cards are covered by **Q3**.

- [ ] **Step 2: Now Playing — tappable title (→ album) and artist (→ artist)**

`NowPlayingContent` currently takes `title` and `artist` strings and **no album**. Add three defaulted params to `NowPlayingContent` (`:161-164`):

```kotlin
    album: String? = null,
    onOpenAlbum: (() -> Unit)? = null,
    onOpenArtist: (() -> Unit)? = null,
```

thread them into `TrackMetadata` (called at `:224` and `:239`) and add the same three to `private fun TrackMetadata`'s own signature (`:359-366`).

> **The two Texts do NOT share a resting colour — do not "unify" them.** The title (`:370-377`) sets **no** `color` at all (so it inherits `LocalContentColor.current`) and carries `modifier = Modifier.basicMarquee()`. The artist (`:380-386`) sets `color = MaterialTheme.colorScheme.onSurfaceVariant` and has **no** marquee. Each link gate must fall back to **that Text's own current colour**, or the `now-playing-*` goldens move — which is exactly the "leaked default" the golden rule is about.

Title (`:370-377`) — keep the existing style/marquee, `.then()` the clickable on so `basicMarquee()` survives:

```kotlin
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
                // No colour today => LocalContentColor. Keep that as the
                // non-link fallback.
                color = if (onOpenAlbum != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .basicMarquee()
                    .then(if (onOpenAlbum != null) Modifier.clickable(onClick = onOpenAlbum) else Modifier),
            )
```

Artist (`:380-386`) — note the **`onSurfaceVariant`** fallback, not `LocalContentColor`, and no marquee:

```kotlin
            Text(
                artist,
                style = MaterialTheme.typography.bodyLarge,
                color = if (onOpenArtist != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onOpenArtist != null) Modifier.clickable(onClick = onOpenArtist) else Modifier,
            )
```

The `typography-details-*` golden pins the title's style but renders its **own** standalone `Text("Nightcall", style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp))` — inside `TypographyDetailsPreview()`, `ComponentScreenshotTest.kt:735`, commented "NowPlayingScreen.kt's title style, verbatim" — not `TrackMetadata` — so it cannot move here. Keep the title's `titleMedium.copy(fontSize = 22.sp)` byte-identical anyway: that golden exists to catch a Fraunces/DM-Sans regression, and the two are meant to stay in lockstep.

`NowPlayingScreen` (`:103-137`) resolves the handlers from `viewModel.currentTrack`, which is the real `Track` (the metadata strings are not enough — `MediaMetadata` has no album field wired here):

```kotlin
    val albumTarget = track?.let { albumTargetFor(it) }

    NowPlayingContent(
        // …existing args…
        album = albumTarget?.album,
        // Gate on the target, not on `track != null`: a track with no album has
        // nothing to navigate to, and `albumTargetFor` is the one function that
        // decides that (and computes groupAlbums' key while it is at it).
        onOpenAlbum = albumTarget?.let { target ->
            { onOpenAlbum(target.album, target.albumArtist, track.sourceId.takeIf { track.isTidal }) }
        },
        onOpenArtist = track?.artist?.takeIf { it.isNotBlank() }?.let { name -> { onOpenArtist(name) } },
        // …
    )
```

and `NowPlayingScreen` gains two nav params:

```kotlin
fun NowPlayingScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAlbum: (album: String, artist: String, seedTrackId: String?) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
```

`MainActivity.kt:388-394`:

```kotlin
                    composable("nowplaying") {
                        NowPlayingScreen(
                            viewModel,
                            onBack = { navController.popBackStack() },
                            onOpenQueue = { navController.navigate("queue") { launchSingleTop = true } },
                            onOpenAlbum = { album, artist, seed ->
                                navController.navigate(albumRoute(album, artist, seedTrackId = seed))
                            },
                            onOpenArtist = { name -> navController.navigate(artistRoute(name)) },
                        )
                    }
```

> **Navigating away from Now Playing works, but check it on-device.** `"nowplaying"` is neither a `TAB_ROUTE` nor an `OVERLAY_ROUTE` (`MainActivity.kt:71-74`), and the Scaffold special-cases it at `:209`, `:249` and `:297` to hide all global chrome. Pushing `album/…` on top of it therefore restores the top bar and mini-player normally, and Back returns to Now Playing. Confirm in Step 4 — this is the one navigation in the plan whose surrounding chrome logic is route-conditional.

**Imports to add to `NowPlayingScreen.kt`:** `androidx.compose.foundation.clickable` and `io.github.auxen.ui.components.albumTargetFor` — those two only. `LocalContentColor` (`:42`), `basicMarquee` (`:5`), `MaterialTheme`, `TextOverflow` and `sp` are all already imported.

- [ ] **Step 3: Build + full suite + goldens**

```bash
JAVA_HOME=/home/mrw1986/.jdks/jdk-21.0.11+10 ./gradlew -p android testDebugUnitTest verifyRoborazziDebug assembleDebug
```

Expected: `BUILD SUCCESSFUL`. **`album-card-*` and `now-playing-*` must NOT move** — both fixtures pass no handler, so both texts keep their current colour and carry no clickable. A diff means the tint gate leaked; fix the gate, **do not re-record**.

- [ ] **Step 4: On-device checkpoint**

1. Library → Albums → tap an album card's **artist name** (not the art) → artist detail. Tap the **art** → album detail. The two do not fight.
2. Play a track → open Now Playing → tap the **artist** → artist detail; Back → Now Playing intact (art, transport, mini-player chrome all correct).
3. Now Playing → tap the **title** → album detail with the real tracklist. Back → Now Playing.
4. Play a track with **no album** → the Now Playing title is **not** tinted and does nothing.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/github/auxen/ui/components/AlbumCard.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/NowPlayingScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/LibraryScreen.kt \
        android/app/src/main/kotlin/io/github/auxen/ui/CollectionScreen.kt
git commit -m "feat(android): clickable artist/album names where the desktop has them

Ports the desktop's clickable-link sites: the album card's artist subtitle
(home.py:169) and Now Playing's title -> album / artist -> artist
(now_playing.py:444,458). The album-detail header artist link already
existed. Track-row names stay non-clickable — the desktop's are too, and a
16dp link inside a 60dp play-me row is a mis-tap magnet (see Q2).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_015p9fkaZoHPmbaVsrB5mcip"
```

---

## Open questions — flagged, not invented

**These are UX decisions. Do not guess; get an answer first.** Each blocks only its own item; Tasks 1–6 proceed regardless.

- **Q1 — What should "Properties…" be on a phone, if anything?** The desktop's `show_artist_properties` (`properties_dialog.py:82`) / `show_album_properties` (`:219`) are two things at once: (a) a **read-only stat block** — album/track counts, source ("Local + Tidal"), total duration, custom-art path; and (b) a **custom-art picker** that opens a `Gtk.FileDialog`, writes the chosen path to a `custom_art:artist:{name}` setting, and invalidates the art caches. Part (a) is nearly free on Android and arguably belongs *on the detail screens*, not behind a menu — the artist screen already shows `"${albums.size + tidalOnlyAlbums.size} albums • ${displayTracks.size} tracks"` (`ArtistDetailScreen.kt:155`). Part (b) needs a SAF `OpenDocument` picker, a persisted URI permission, a custom-art settings key, and cache invalidation across `ArtworkImage`/Coil — a real feature, not a menu entry. **Which, if either, do you want?** Until answered, "Properties…" is on neither sheet.
- **Q2 — Should track-**row** artist/album names be tappable?** Task 7 deliberately does not do this: the desktop's track rows are not clickable either (only the four `clickable-link` sites are), and on a phone it means two sub-48dp links inside a 60dp row whose whole surface means *play*. Task 3's "Go to album"/"Go to artist" is the ported mechanism. **If you meant track rows specifically**, say so and it becomes its own task — the honest design is probably a leading-art tap or a trailing chevron, not tiny inline links, and that is a decision, not an implementation detail.
- **Q3 — `HomeScreen`'s album cards tap-to-PLAY, not tap-to-open.** `HomeScreen.kt:127` passes `onClick = { viewModel.playAll(album.tracks) }` — the same thing as its own play button at `:128` — so a Recently Added card cannot open its album at all. Every other grid navigates. This looks like a bug, but it predates this plan and "fixing" it changes Home's established behaviour, so Task 5 leaves Home's cards without a long-press menu (a menu offering "Play album" on a card that already plays on tap is noise) and Task 7 leaves the subtitle unlinked. **Should Home's cards open the album like every other grid?** If yes, it is a one-line change plus the two affordances.
- **Q4 — Is Tidal radio / mixes worth a slice?** Three dropped entries (Track Radio, Artist Radio, Start Mix) all need the same unbuilt backend: `track.get_track_radio()`, `artist.get_radio()`, and the mixes API. That is a real slice — endpoints, projections, tests, and a decision about *where a radio queue lands* (replace the queue? a new "radio" surface?). Not started on a guess.

## Follow-ups this plan names but does not do

- **Tidal radio + mixes** (Q4) — unblocks Track Radio, Artist Radio, Start Mix.
- **Slice 2 (collection sync)** — unblocks album "Save to Collection" and artist "Follow/Unfollow", both of which need Tidal favourites **writes**.
- **`TrackActionSheet` on Queue rows.** `QueueScreen` renders `AuxenTrackRow` twice (`:128`, `:198`) with no long-press. Its rows already carry a `longPressDraggableHandle` (`:214`) on a *dedicated handle*, so the row surface is technically free — but putting a second long-press behaviour a few dp from a long-press drag handle is a UX decision, not a wiring job. Out of scope.
- **`PlaylistDetailScreen` long-press.** `:152` has no `onLongPress`. It is the one remaining track list without a menu after Task 3 (which covers Home). Its rows already carry three trailing `IconButton`s (up/down/remove), so the menu's value is lower and its "Remove from playlist" entry would need a playlist-scoped action the other hosts do not have. Deferred deliberately.

---

## Self-review (writing-plans checklist)

**Spec coverage:**
- **One menu mechanism, not three** ✓ `ModalBottomSheet`, chosen because the app already uses it for `TrackActionSheet`; stated with reasons in *Architecture* §1. `TrackActionSheet` is **extended** (Task 3), not duplicated; `AlbumActionSheet`/`ArtistActionSheet` reuse its `SheetAction` row (Task 5 Step 2 widens `private`→`internal` rather than forking it) and its mandatory `…Content` split.
- **Entries per entity + real conditionality** ✓ Three tables in *What the desktop actually has*, each row read at a cited line, with the **real** condition (not the brief's paraphrase). Encoded in the pure model (Task 1) and pinned by 13 unit tests.
- **Entries dropped, with reasons** ✓ Its own table: Track Radio, Start Mix, Artist Radio (no backend → Q4/follow-up); Follow/Unfollow, album Save-to-Collection (no backend → Slice 2); Properties (→ Q1). "View Lyrics" documented as a **merge**, not a drop. No backend is invented anywhere.
- **Clickable names** ✓ Task 7, scoped by the desktop's own `clickable-link` inventory (four sites, one already done). Constraint honoured: the screens are keyed by **name**, `TrackEntity` has no `album_id`, so `albumTargetFor` reproduces `groupAlbums`' key exactly — and the "no matching target" case is answered concretely: the entry **hides** when there is no album, and the **seed nav arg** (Task 3) stops the Tidal-only case dead-ending on "no tracks".
- **Accessibility + touch semantics** ✓ `onLongClickLabel` + `performHapticFeedback(LongPress)` on all three long-pressable components (Tasks 2/5/6), each gated on the same null handler so nothing dead is advertised. Verified against the actual artifact that Foundation **1.7.6** does not self-haptic (no double-buzz) and that `onLongClickLabel` is real in this version.
- **Testability** ✓ Entry construction is a **pure** function (`ActionSheetModel.kt`) with 13 unit tests covering every gate in both directions. Compose stays outside the unit boundary; sheets get Robolectric behaviour tests in the established style, screens close on-device.
- **Golden risk** ✓ Its own section, listing the four at-risk goldens, the fixture each captures, and why each stays put. **No golden is expected to move**; the rule "a moved golden means a leaked default — fix the default, don't re-record" is repeated at each verify step.

**Divergences from the desktop, all deliberate and documented:** four dead desktop conditionals are not ported (`_noop` callback gates; `is_followed`; `is_favorite`; `is_saved_in_collection`); `ArtistContextMenu`'s Shuffle ordering bug is not reproduced; "Go to album" hides rather than navigating to the `"Unknown Album"` bucket; track-row names stay non-clickable (which **is** desktop parity — see Q2).

**Placeholder scan:** every logic step (Tasks 1–7) carries real code, real imports and real assertions. No `TODO`, no "add error handling", no "similar to above". The Compose steps give the actual composables and the actual wiring, then close on on-device checkpoints — matching Slice 1's treatment of untested Compose.

**Name-collision sweep** (the never-re-declare rule), run over `*.kt` for every new symbol: `ActionId`, `ActionEntry`, `AlbumTarget`, `albumTargetFor`, `trackActions`, `albumActions`, `artistActions`, `AlbumActionSheet`, `AlbumActionSheetContent`, `ArtistActionSheet`, `ArtistActionSheetContent`, `playNextAll`, `enqueueAll`, `addTracksToPlaylist`, `createPlaylistAndAddAll` — **zero hits each**, all net-new (Task 1 Step 2 re-runs the check before writing). The two intentional **re-uses, not re-declarations**: `SheetAction` (widened to `internal`, reused by both new sheets) and `Track.favoriteKey()` (a file-private one already exists at `QueueScreen.kt:234`; this plan adds **no** public sibling and keeps inlining the key string, as every other screen does).

**Import check** (per-file resolution; a missing import has bitten this series): every task carries an explicit *Imports to add* line. The subtle ones: `AlbumCard.kt` **loses** `clickable` in Task 5 (replaced by `combinedClickable`) and **regains** it in Task 7 (the artist subtitle needs it) — both are stated in place; `LibraryScreen.kt`/`CollectionScreen.kt` import `AlbumGroup` but **not** `ArtistGroup` (they only ever used `groupArtists`' inferred return type), so Task 6 must add it; `HomeScreen.kt` needs `remember`/`mutableStateOf`/`setValue`/`TrackActionSheet`/`albumTargetFor`, none of which it has today; `ActionSheetModel.kt` needs only `Source` + `Track`; `PlayerViewModel.kt` needs **nothing** new (`Graph`, `Track`, `viewModelScope`, `launch` all already imported).

**Type-consistency check:** `ActionId`/`ActionEntry` are produced by all three model builders and consumed by all three `…Content` composables through one `when`, so a new entry cannot be rendered without a branch (the `else -> Unit` covers the other entities' ids, which that builder provably never emits). `albumTargetFor` returns `AlbumTarget?` and is the **single** source of the `(album, albumArtist)` pair for every "go to album" call site — the track sheet (Task 3), and Now Playing (Task 7) — so `groupAlbums`' key is reproduced in exactly one place. `AlbumGroup.tracks`/`ArtistGroup.tracks` are `List<Track>`, matching `playAll`/`playNextAll`/`enqueueAll`/`addTracksToPlaylist`/`createPlaylistAndAddAll`'s `List<Track>`. The new `seedTrackId` nav arg is `String?` throughout — route arg → `AlbumDetailScreen` param → `loadAlbumDetail`'s existing `seedTidalTrackId: String?`, whose signature is **unchanged**.

**Scope:** **7 tasks**, one over the brief's ~6 — flagged in Global Constraints. Task 7 is the separable one.
