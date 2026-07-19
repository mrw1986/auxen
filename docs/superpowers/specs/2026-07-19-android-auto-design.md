# Auxen — Android Auto Support (v1) — Design

- **Date:** 2026-07-19
- **Branch:** `claude/android-app-availability-y6uzb0`
- **Baseline commit:** `68821ae`
- **Status:** Approved design, pre-implementation

## 1. Summary

Add **Android Auto** (phone-projected, *not* Google Automotive OS) media-browsing
support to Auxen. The user browses a curated, driving-safe subset of their library
on the car head unit and taps to play; playback runs through the existing Media3
stack unchanged. v1 is **browse + tap-to-play only** — no voice/Assistant search
(deferred to a later version).

This is an extension of existing scaffolding, not a rebuild:
- `PlaybackService` is already a Media3 `MediaSessionService`.
- `AndroidManifest.xml` already declares the `android.media.browse.MediaBrowserService`
  intent-filter on that service.
- A code comment in `PlaybackService` already anticipates "(later) Android Auto —
  all driven by the one MediaSession."

## 2. Goals / Non-goals

### Goals
- Browse **Recently played**, **Playlists**, **Albums**, **Artists**, and
  **Favorite tracks** from the car.
- Tap any track to play it, enqueueing its sibling list from that point.
- Tap **▶ Play all** on an album/artist to play the whole thing.
- Reuse the existing streaming/resolution path with zero new playback code.
- Fail safe: a network or empty condition shows a friendly non-playable node,
  never a crash or a silent empty list.

### Non-goals (v1)
- Voice / Google Assistant search (`onSearch` / `onGetSearchResult`) — deferred.
- Google Automotive OS (native head-unit build target) — out of scope; this is
  phone-projected Android Auto only.
- Tidal mixes / Home sections in the car (user excluded them).
- Playback resumption on cold car-connect (`onPlaybackResumption`) — can be a
  fast-follow; not required for v1.

## 3. Key existing facts this design relies on

- `Graph.mediaItemFor(track: Track): MediaItem` (`AuxenApp.kt`) builds a `MediaItem`
  with `mediaId = "SOURCE:sourceId"`, a `auxen://tidal/<id>` (or MediaStore) URI,
  and the full `Track` JSON embedded in extras.
- A `ResolvingDataSource.Factory` (`PlaybackService.kt` ~L347, `TrackResolver`)
  turns `auxen://tidal/<id>` into a **fresh short-lived stream URL at open() time**.
  → **Any `MediaItem` produced by `mediaItemFor` is automatically playable**,
  regardless of how it entered the queue. The browse tree needs no streaming logic.
- Room flows already exist on `LibraryRepository`: `playlists()`, `favoriteAlbums()`,
  `favoriteArtists()`, favorite-track queries, and recently-played. The browse tree
  reads a **snapshot** via `.first()`.

## 4. Architecture

Four components, each with one purpose and an independently testable boundary.

### 4.1 `AuxenBrowseTree` (pure mapping — no Android, no network)
- **Input:** a browse `mediaId` + snapshots of repo data (lists passed in).
- **Output:** an ordered `List<MediaItem>` of children (or a single node for
  the root / detail).
- **Responsibility:** define the tree shape and node identity. Builds folder nodes
  (`isBrowsable=true, isPlayable=false`, appropriate `mediaType`) and delegates
  playable leaves to `Graph.mediaItemFor`.
- **No** suspend calls, **no** network, **no** DB — callers pass already-fetched
  data in. This keeps it 100% unit-testable and is where the tree structure lives.
- **Browse id scheme** (stable, parseable):
  - `auxen://browse/root`
  - `auxen://browse/recent`
  - `auxen://browse/playlists`, `auxen://browse/playlist/<localPlaylistId>`
  - `auxen://browse/albums`, `auxen://browse/album/<tidalId>`
  - `auxen://browse/artists`, `auxen://browse/artist/<tidalId>`
  - `auxen://browse/favorites`
  - `auxen://browse/playall/album/<tidalId>`, `auxen://browse/playall/artist/<tidalId>`
    (the pinned "▶ Play all" leaf — a playable node whose activation enqueues the
    whole fetched list)
  - Leaf **track** ids stay `"SOURCE:sourceId"` (unchanged from `mediaItemFor`),
    so routing/detection elsewhere is unaffected.

### 4.2 `TidalBrowseFetcher` (the only network piece)
- **Input:** an album id or artist id.
- **Output:** an ordered `List<Track>` (album tracks / artist tracks), or a
  failure signal.
- **Responsibility:** on-demand fetch of the track lists that are not fully stored
  locally. Isolated here so `AuxenBrowseTree` stays pure. Reuses existing
  `TidalProvider` / `TidalCollectionSync` fetch paths where they exist; adds album-
  and artist-track fetch only if not already present.
- Failure → returns a typed failure the caller maps to the error node (§6).

### 4.3 `PlaybackService` → `MediaLibraryService`
- Change base class `MediaSessionService` → `MediaLibraryService`.
- Change `MediaSession` → `MediaLibrarySession`; `mediaSession = MediaLibrarySession.Builder(...)`.
- Change `AuxenSessionCallback : MediaSession.Callback` →
  `AuxenSessionCallback : MediaLibrarySession.Callback` (the latter extends the
  former — existing playback overrides are inherited/unchanged).
- **Add** the three library callbacks:
  - `onGetLibraryRoot` → returns the `auxen://browse/root` node (with content-style
    extras, §5).
  - `onGetChildren(parentId, page, pageSize, params)` → resolves `parentId`:
    local branches read repo snapshots synchronously-ish (coroutine + `.first()`);
    album/artist detail branches call `TidalBrowseFetcher` then `AuxenBrowseTree`.
    Returns a `ListenableFuture<LibraryResult<...>>` (coroutine-backed).
  - `onGetItem(mediaId)` → single-node lookup.
- **Leaf activation → queue expansion:** when the car plays a leaf track, the
  session builds the queue from the leaf's **sibling list** starting at the tapped
  index (so tapping track 3 of a playlist plays 3→end). "▶ Play all" builds the
  queue from index 0 of the fetched album/artist list. Implemented via the
  callback's media-item resolution (`onAddMediaItems` / `onSetMediaItems`).
- **Untouched:** notification provider, bit-perfect player-swap (`setPlayer`),
  queue save/restore, the `ResolvingDataSource` factory, and all transport-control
  callbacks. These are explicitly out of scope for behavioral change and are the
  regression surface to protect.

### 4.4 Manifest
- Add `res/xml/automotive_app_desc.xml`:
  ```xml
  <automotiveApp>
      <uses name="media"/>
  </automotiveApp>
  ```
- Add inside `<application>`:
  ```xml
  <meta-data
      android:name="com.google.android.gms.car.application"
      android:resource="@xml/automotive_app_desc"/>
  ```
- The `MediaBrowserService` intent-filter on `PlaybackService` already exists — no
  change needed there.

## 5. Browse tree (v1)

```
Root  (auxen://browse/root)
├─ Recently played   → [track…]                       local, instant, playable
├─ Playlists         → [Playlist] → [track…]          local membership; Tidal
│                                                       tracks import on open
├─ Albums            → [Album]  → ▶ Play all + [track…]  tracks fetched on demand
├─ Artists           → [Artist] → ▶ Play all + [track…]  tracks fetched on demand
└─ Favorite tracks   → [track…]                       local, instant, playable
```

- **Depth:** root → category → (detail) → tracks. Albums/Artists are the deepest
  at 3 levels; acceptable given the pinned "Play all" gives a 2-tap path to audio.
- **Content style:** root sets Android Auto content-style hints via
  `LibraryParams`/root extras — **list** for track-bearing nodes, **grid** for the
  Albums/Artists category nodes (art-forward). This is a cosmetic hint; if it adds
  meaningful complexity it can be dropped without affecting function.
- **Ordering** mirrors the app: Recently played and Favorite tracks newest-first;
  Playlists/Albums/Artists in the Collection's existing order.

## 6. Error & empty handling

Consistent with Auxen's existing null-safe sync philosophy (a failed fetch changes
nothing, never guesses):

- **Network/failed Tidal fetch** (album/artist tracks): return a single
  **non-playable** node titled e.g. "Couldn't load — check connection". Never a
  crash, never a silent empty that reads as "no tracks".
- **Genuinely empty** local branch (no playlists / no favorites yet): a single
  non-playable "Nothing here yet" node.
- **Expired/failed stream at play time:** already handled by the existing
  `TrackResolver` / player error path — unchanged.
- `onGetChildren`/`onGetItem` never throw to the framework; failures map to
  `LibraryResult` error codes or the friendly node as appropriate.

## 7. Testing

- **`AuxenBrowseTree` — unit tests (JVM, deterministic):**
  - root returns exactly the five categories in order;
  - each category expands correctly from sample repo data;
  - album/artist detail prepends the "▶ Play all" node;
  - empty inputs yield the "Nothing here yet" node;
  - browse-id parse/round-trip (build id → parse id → same target);
  - leaf track ids remain `"SOURCE:sourceId"`.
- **`TidalBrowseFetcher`** — thin; failure-path mapping covered where it has logic.
- **Service promotion** — validated by the **existing** unit suite continuing to
  pass (playback/session behavior is unchanged by contract).
- **On-device (manual, user-run):** Desktop Head Unit (DHU) or the real car.
  Claude cannot run DHU; a validation checklist is provided at implementation time
  (connect → all five categories present → drill each → tap track plays & enqueues
  siblings → Play all works → airplane-mode on an album shows the error node, not a
  crash → transport controls + now-playing correct on the head unit).

## 8. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Service base-class change regresses playback/notification/bit-perfect swap | Keep all existing callbacks/behaviors byte-identical; rely on the full existing suite passing; isolate the change to base class + added browse methods. Independent review of `PlaybackService` diff. |
| In-car network latency/failure on album/artist drill | On-demand fetch is isolated in `TidalBrowseFetcher`; failures render the error node (§6); local branches (Recent/Playlists/Favorites) stay instant. |
| Deep tree feels unsafe while driving | "▶ Play all" gives a shallow 2-tap audio path; shallow branches lead. |
| Content-style hints add complexity | Cosmetic-only; droppable without functional impact. |
| Cannot validate in-car from dev machine | User-run DHU/car checklist; unit tests cover all pure logic. |

## 9. Open questions / future (not v1)

- Voice/Assistant search (`onSearch`, `onGetSearchResult`).
- Cold-connect playback resumption (`onPlaybackResumption`).
- Tidal mixes/Home in the car.
- Google Automotive OS native target.

## 10. Implementation order (for the plan)

1. `AuxenBrowseTree` + unit tests (pure; no device needed).
2. `TidalBrowseFetcher` (album/artist track fetch) + failure mapping.
3. Promote `PlaybackService` to `MediaLibraryService`; wire the three callbacks +
   leaf→queue expansion; keep everything else unchanged.
4. Manifest (`automotive_app_desc.xml` + meta-data).
5. Content-style hints (optional, last).
6. Build + existing suite green; deliver an identifiable APK (`0.1.0+<sha>`);
   user validates via DHU/car checklist.
