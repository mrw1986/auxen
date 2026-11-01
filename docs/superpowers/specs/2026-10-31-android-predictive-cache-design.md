# Android predictive cache: design

Date: 2026-10-31
Status: approved by the user 2026-10-31
Scope: Android app only (`android/`). The desktop GTK app is out of scope.

## Intent

Keep music playing through dead zones while driving. The user listens on their phone, often through Android Auto or Bluetooth, and has hit stretches of 30 minutes or more with no service. Today, any Tidal song that isn't downloaded stops when service drops.

The model is Google Play Music. It quietly cached the songs coming up in your queue, so losing service mostly went unnoticed. The user loved that. Success means a drive through a dead zone sounds the same as a drive with service, with no manual downloading.

## Goals

1. While a queue plays, cache the songs coming up next (whole files, on device), so they play with no network.
2. Cache as far down the queue as a user-set storage limit allows. A bigger limit covers longer dead zones.
3. When offline, skip uncached songs and play the next cached one. If nothing ahead is cached, pause and say so, then resume on its own if service returns within 30 minutes.
4. Show which songs are cached with a small icon on track rows.
5. Settings: a network mode, a size limit, a used-space readout and a Clear cache button.

## Non-goals

- Manual "download this album/playlist" offline mode. This is automatic caching only. Pinning could come later and build on this.
- The desktop app.
- Caching Tidal radio or mixes specially. They aren't the user's main sources (albums, favorites and playlists are). Whatever is in the queue gets cached the same way, though.
- A streaming quality setting. The app always requests `HI_RES_LOSSLESS` today, and that stays as it is.
- Re-checking Tidal licenses for cached files, the way Tidal's own app does every 30 days.

## Anti-goals (a failure even if it technically works)

- Half-cached songs that can't play offline. A song counts as cached only when the whole file is on disk.
- Caching that evicts the upcoming songs you'll need to make room for songs further out.
- Breaking or slowing online playback, Android Auto browsing, the DASH swap, or the expired-URL recovery in `PlaybackService.onPlayerError`.
- Caching over mobile data when the user picked Wi-Fi only.
- Filling the phone's storage. Stay inside the limit and leave headroom for the OS.

## Decided with the user

| Topic | Decision |
|---|---|
| Sources | Albums, favorites and playlists. These are fixed lists, so the queue is known ahead. |
| Listening style | Both straight through and skipping around, so cache several songs ahead, not just the next one. |
| How far ahead | Fill down the queue until the user's storage limit is reached. |
| Size limit | Set by the user in Settings (GPM did this). |
| Network mode | Three choices: Wi-Fi and mobile data / Wi-Fi only / Off. |
| Eviction | Played songs stay cached until space is needed, then the oldest-played go first. |
| Controls | Clear cache button and a used-space readout. |
| Quality | Cache at whatever quality is streaming, which today means Hi-Res whenever a song has it. |
| Offline, next song not cached | Skip ahead to the next cached song. |
| Offline, nothing ahead cached | Pause and show "Offline, nothing cached ahead". |
| Skipped songs | Stay in the queue, so you can go back to them once online. |
| Manual skip or tap to an uncached song while offline | Same skip-ahead behavior. |
| Cached indicator | Yes, a small icon on queue and track rows, like GPM. |
| Auto-resume after an offline pause | Yes, if service returns within 30 minutes, resuming on the song it couldn't play. Past 30 minutes it stays paused. Any pause or queue change by the user cancels it. |
| When caching runs | Only while music is playing. No background downloads while paused. |
| If cached Hi-Res can't play or seek on Media3 1.5.1 | Upgrade Media3, or use Media3's own DASH download tooling for Hi-Res only. A 16-bit LOSSLESS copy is a last resort and needs the user's sign-off first. |

## How playback works today (recon, 2026-10-31)

- `Graph.mediaItemFor` (`AuxenApp.kt`) gives every Tidal track a stable `auxen://tidal/<id>` URI with mediaId `TIDAL:<id>`.
- `PlaybackService.buildPlayer` wraps `DefaultDataSource` in a `ResolvingDataSource` with `TidalUriResolver`. Just before open, `TidalUriResolver.resolveDataSpec` calls `TrackResolver.resolve(id)`, which hits `TidalProvider.getStreamInfoById` (`playbackinfopostpaywall`, `HI_RES_LOSSLESS`, `STREAM`). `TrackResolver` keeps a 20-minute in-memory TTL cache and nothing else.
- Tidal answers in one of two shapes:
  - **BTS**: a single direct file URL (FLAC, or AAC for some tracks). This plays as a progressive stream.
  - **DASH** (Hi-Res): an MPD manifest, passed as a `data:application/dash+xml;base64,...` URI. `TidalUriResolver` throws `TidalDashStreamException`, and `onPlayerError` / `preResolveUpcoming` swap the queue item to the DASH URI (`swapDashCopies`). The MPD is a single representation with a `SegmentTemplate` and `SegmentTimeline`: an init segment plus numbered media segments from one URL template. tidalapi's `DashInfo.get_urls` builds the segment list the same way.
- `preResolveUpcoming` pre-resolves the current and next items while `playWhenReady`.
- `RgGainRouter` gets ReplayGain values by calling `Graph.resolver.resolve(id)`. Offline, that fails and falls back to the fallback gain.
- There's no disk cache, no Media3 `SimpleCache`, no `DownloadManager`, and no connectivity monitoring anywhere in the app today.
- Persistence: Room `AuxenDatabase` (version 3, has a `SettingsDao`), and DataStore for some controllers (for example `bitPerfectDataStore`). HTTP goes through OkHttp. Media3 is version 1.5.1.
- There's no DRM handling and streams play today, so the streams are unencrypted.

## Approach (Claude's call, open to change)

### Whole-song files with our own index, not the Media3 cache

Each cached song is a complete local file (or, for DASH, a complete local set; see below). A Room table indexes them. `TidalUriResolver` checks the index first, and a cached song never touches the network.

I picked this over Media3 `SimpleCache` + `CacheDataSource` + `CacheWriter` for three reasons:
- `LeastRecentlyUsedCacheEvictor` evicts by span, which can leave partial songs. That conflicts with the first anti-goal.
- Media3 keys DASH segments by URL by default, and Tidal segment URLs probably carry expiring tokens (unverified). A re-resolved song might never hit the cache. A custom cache key factory could fix that, but it depends on Media3 internals we haven't verified.
- Even with the Media3 cache, offline playback needs the manifest and StreamInfo stored. Once we own that index, owning the files is a small step, and it makes the readout, Clear cache, the cached icon and the "is it cached?" check simple.

### Components (names are suggestions)

- **`CachedTrack` Room entity / DAO**: `tidalTrackId` (PK), file path(s), byte size, `mimeType`, `kind` (PROGRESSIVE or DASH), the `StreamInfo` fields (`sampleRateHz`, `bitDepth`, `trackGainDb`, `albumGainDb`), `cachedAtMillis`, `lastPlayedAtMillis`, and the track's own metadata (the serialized `Track`: title, artist, album, duration, art URL). A row is written only after the file is complete. This needs a Room migration from version 3 to 4. The metadata isn't used in v1. It's stored so a later "Cached" browse folder or offline album view doesn't need a second migration (Claude's call).
- **`TrackCacheStore`**: owns the cache directory, lookups, an atomic commit (download to a temp name, then rename, then insert the row), eviction, clear, total size, and a `Flow<Set<String>>` of cached ids for the UI.
- **`TrackDownloader`**: given a track id, resolves a fresh StreamInfo and downloads it with OkHttp:
  - BTS: stream the single URL to a file.
  - DASH: parse the MPD (init segment + segment timeline), download the init and every media segment, and store them so playback can seek (see "DASH storage" below).
- **`CacheAheadScheduler`** (runs in `PlaybackService`): computes the upcoming window and downloads one song at a time, nearest first. It only runs while playback is active (`isPlaying`, or `playWhenReady` while buffering). When the user pauses, the download in progress finishes, but nothing new starts (Claude's call). There's no WorkManager and no new foreground service type. It re-plans on queue changes, media item transitions, shuffle and repeat changes, setting changes, and network changes. It cancels a download in progress when that song drops out of the window.
- **`NetworkPolicy`**: wraps `ConnectivityManager`. It reports online (validated network) and unmetered.
- **Resolver hook**: `TidalUriResolver.resolveDataSpec` checks `TrackCacheStore` first. On a hit it returns a local `file://` DataSpec (progressive) and never calls `TrackResolver`. On a hit it also bumps `lastPlayedAtMillis`, or the bump happens at media item transition (builder's choice).
- **Offline skip**: see the behavior section.
- **Settings UI**: a new "Offline cache" section in `SettingsScreen`.
- **Cached icon**: a small icon on track rows (queue, album, playlist, collection, search) driven by the cached-ids Flow.

### The window: what gets cached

- Play order is `player.currentMediaItemIndex` followed by repeated `getNextMediaItemIndex()`, so shuffle and repeat are respected. Stop at the end of the queue, or when an index repeats (repeat-all loops).
- The current song is included and comes first. If service drops mid-song, the player's buffer (about 50 seconds by default) runs out. With the current song cached, recovery can resume from the file at the same position (see the behavior section). This costs one extra download of the current song when it wasn't cached already. Claude's call.
- Only Tidal items. Local tracks are skipped since they're already on the device.
- Walk forward and add songs while (cached upcoming bytes + the next song's estimated size) fits under the limit. Eviction can free space from songs outside the window, oldest `lastPlayedAtMillis` first, never-played and oldest `cachedAtMillis` last. It never evicts a song inside the window, and never the one playing.
- Size isn't known until a song is resolved. Estimate from duration × the quality's bitrate, and correct it once the download finishes. The builder chooses the exact estimate.

### Network modes

- **Wi-Fi and mobile data**: cache whenever a validated network exists.
- **Wi-Fi only**: cache only when the active network is `NET_CAPABILITY_NOT_METERED`, so a metered Wi-Fi hotspot counts as mobile. Pause downloads when the network switches to metered.
- **Off**: no new caching. Already-cached songs still play and stay until evicted or cleared. Claude's call.
- Since caching only runs while playing, "Wi-Fi only" caches only while you're listening on Wi-Fi. The user accepted that.

### Playback behavior

- **Online, song cached**: play from the file. No resolve, no DASH swap.
- **Online, song not cached**: same as today.
- **Song finishes caching while a DASH-swapped copy sits in the queue**: swapped items hold a network `data:` MPD and would fail offline. When a song commits to the cache, restore any not-playing swapped copies to `auxen://tidal/<id>` so the next play takes the cached path. `preResolveUpcoming` skips cached ids.
- **Offline, about to play an uncached Tidal song** (a natural transition, next/previous, a tap, a cold start from a restored queue): seek to the next playable item in play order. Playable means cached Tidal or local. Skipped songs stay in the queue. If nothing ahead is playable, pause and show "Offline, nothing cached ahead". There's no message on a successful skip, since in the car the screen usually isn't visible anyway (Claude's call).
- **Auto-resume**: after the "nothing cached ahead" pause, keep a "paused by offline" flag with a timestamp. When a validated network returns within 30 minutes, clear the flag and play from the song it stopped on. These clear the flag: a user pause or play, a queue change, a media item change by the user, or the 30 minutes running out. Make this a pure, unit-tested state machine. Because playback isn't active during the pause, the service has to stay alive for those 30 minutes. Check that it does on a device, since `onTaskRemoved` calls `stopSelf()` when not playing.
- **Service drops mid-song**: when the player errors with a network failure, the current song is cached, and we're offline, re-prepare from the file at the current position. If it isn't cached, apply the skip-ahead rule.
- **Detecting offline**: use `NetworkPolicy` (no validated network) together with the actual failure, either a resolve that fails with an `IOException` that isn't HTTP 401/403/410, or a load error. Don't skip on the connectivity flag alone while a network is available but slow. The builder decides how to combine these.
- The expired-URL recovery (401/403/410) and the DASH swap stay as they are for uncached songs.
- **ReplayGain and quality display offline**: `RgGainRouter` and anything else that reads StreamInfo must use the cached row's StreamInfo fields when the song is cached. One way is for `TrackResolver.resolve` to return the cached StreamInfo first.

### DASH storage (needs a spike)

Cached Hi-Res songs must play and seek like streamed ones. Two options:

1. **Local DASH**: keep the init and media segments as files in a per-track folder, and write a local MPD whose `SegmentTemplate` points at those files. At play time, swap the item to the local MPD with `MimeTypes.APPLICATION_MPD`, the same way `swapDashCopies` does today. Seeking works exactly as it does when streaming. This is the safer bet.
2. **One concatenated file**: init plus segments joined into a single fragmented MP4, played as progressive. That's simpler storage, but seeking may be poor or missing without an `sidx` box. Unverified.

The first plan task should be a spike: fetch one real Hi-Res manifest, check its structure and whether segment URLs carry expiring tokens, then try option 1 (and option 2 if it's cheap) on a device. That includes seeking. Pick based on the result. For option 1, a cached DASH song's "file path" in the index is its folder and local MPD.

If neither option plays and seeks on Media3 1.5.1, or if the segment links expire too fast to download a whole song, the fallback the user picked is to upgrade Media3, or use Media3's own DASH download tooling (`DashDownloader` / `DownloadManager`) for Hi-Res songs only. Re-check Android Auto browsing and the existing tests after any upgrade. Caching a 16-bit LOSSLESS copy instead is a last resort, and only with the user's sign-off.

### Storage

- Location: `context.noBackupFilesDir/track_cache/`. That's app-private, excluded from backups, and not wiped by the OS the way `cacheDir` can be. Claude's call.
- Write to a temp name, then rename on commit. On startup, delete temp files and any files without index rows, and drop rows whose files are missing.
- Headroom: never let caching push free device storage below 1 GB, whatever the limit. Claude's call.
- On Tidal logout, clear the cache. Claude's call.
- Files stay app-private, aren't exposed through MediaStore, and aren't playable outside Auxen.

### Settings

A new "Offline cache" section in `SettingsScreen`:
- Cache over: Wi-Fi and mobile data / Wi-Fi only / Off. Default: Wi-Fi and mobile data, since that's what makes the driving case work. Claude's call.
- Storage limit: presets of 500 MB, 1 GB, 2 GB, 5 GB and 10 GB. Default 2 GB, which at roughly 70-250 MB per Hi-Res song is about 10-25 songs, comfortably over 30 minutes. Claude's call on the presets and default.
- Readout: "1.2 GB used · 14 songs".
- Clear cache: a button with a confirm dialog.
- The values persist in DataStore or `SettingsDao`, whichever matches nearby settings (builder's call).

### Cached icon

A small icon (for example a filled download or offline-pin glyph) on the trailing side of track rows in the queue, album, playlist, collection and search lists, for fully cached Tidal songs only. No progress state for partial downloads. Claude's call.

In Android Auto, browse items for cached songs carry the download-status extra, so the car shows its own downloaded badge where the head unit supports it. This is cheap (Claude's call).

### Offline message surfaces

"Offline, nothing cached ahead" shows in the phone app, through the existing `PlayerViewModel` playback banner. Offline skips must not show as error banners there (filter them the way `isRecoverableDashError` filters the DASH swap). Media3 1.5.1 has no clean way to send a non-fatal message to the car, so in v1 the car screen just shows paused. Claude's call. Revisit if Media3 gets upgraded.

### Out of scope for v1

- Starting new playback from Android Auto album or artist screens while offline. Those go through `TidalBrowseFetcher`, which needs the network. Favorites and playlists come from the local database and already work offline. Cached songs in a restored queue play fine.
- A "Cached" browse folder. The metadata stored in `CachedTrack` keeps this cheap to add later.

## Constraints

- Kotlin, Media3 1.5.1, Room, OkHttp, Compose. Stay on the current stack. A Media3 upgrade is allowed only if the DASH spike shows it's needed (the user's choice).
- TDD per the repo's global rules. Keep the window planner, eviction policy, the MPD-to-segment-list parser and the offline skip-target finder as pure, unit-tested functions, the way `AuxenBrowseTree` and `BrowseQueueResolver` are.
- Don't break Android Auto (`MediaLibraryService`, `playback/browse/`) or the 570+ existing tests.
- Build APKs locally for on-device testing, since CI has no Tidal credentials. Name them with the commit SHA and confirm the About string (`0.1.0+<sha>`) on the phone.

## Left to the builder

- Exact class names, package layout (suggested: `playback/cache/`), and whether the scheduler lives in `PlaybackService` or a helper it owns.
- How the scheduler is threaded and cancelled (coroutines in `serviceScope` are the natural fit).
- The size estimate formula, and whether the `lastPlayedAtMillis` bump happens in the resolver or at media item transition.
- The DataStore vs `SettingsDao` choice for settings.
- How to combine the connectivity signal and actual failures for offline detection, within the rule above.
- Retry and backoff for failed downloads.
- Exact icon glyph and placement, matching the existing row components.

## Validation (on device, by the user)

1. Play an album online with Wi-Fi off and mobile data on. Watch the cached icons fill in down the queue, and check the Settings readout.
2. Turn on airplane mode mid-song. The song keeps playing (resumes from cache) and the next songs play.
3. In airplane mode, skip forward past cached songs into uncached ones. It skips to the next cached song, or pauses with the message when none are left.
4. Hi-Res song from the cache: seek around the timeline.
5. Wi-Fi only mode on mobile data: no new caching. Back on Wi-Fi: caching resumes.
6. Clear cache: the readout goes to zero and the icons disappear.
7. In airplane mode, play until nothing cached is left. It pauses, then turn airplane mode off within 30 minutes and it resumes on its own. Repeat, waiting more than 30 minutes, and it stays paused.
8. Do the same over Android Auto (or the DHU) in the car.

## Spike result

Date: 2026-11-01. Decision: **option 1, local DASH.** Media3 1.5.1 prepares and seeks a local DASH folder as is. No upgrade needed.

### The real Tidal Hi-Res MPD

One favorite, fetched with tidalapi 0.8.11, the desktop session and quality `HI_RES_LOSSLESS`.

- Favorites lists report `audio_quality = LOSSLESS` even for Hi-Res tracks. Hi-Res only shows as the `HIRES_LOSSLESS` media tag. The stream call still returns `HI_RES_LOSSLESS`, 24-bit, 96 kHz, as `application/dash+xml`.
- Static MPD, `isoff-main` profile. One Period, one AdaptationSet, one Representation (`FLAC_HIRES,96000,24`). `mimeType="audio/mp4"`, `codecs="flac"`, `audioSamplingRate="96000"`.
- `SegmentTemplate`: `timescale="96000"`, `startNumber="1"`, `initialization=".../0.mp4?token=..."`, `media=".../$Number$.mp4?token=..."`. Both are absolute URLs on one CDN host. There's no `BaseURL`.
- `SegmentTimeline`: two `<S>` elements, `d="380928" r="78"` then `d="150270"`, with no `t`. Count media segments as the sum of `1 + r` per `<S>`: 80 here (79 x 3.968 s plus one 1.565 s), plus the init. Asking for segment 81 returns 400.
- Segment URLs carry one query key, `token`. No Policy, Signature, hdnts or exp keys.
- Init segment: `ftyp` + `moov`, with `mvex/mehd` holding the full duration. Media segments are bare `moof` + `mdat`, with no `styp` or `sidx`. FLAC uses 4096-sample frames. The `fLaC` sample entry's rate field is 0, since 96000 doesn't fit in it. The real rate is in the `dfLa` STREAMINFO.
- One 5:15 song is about 112 MB.

The scrubbed copy is `android/app/src/test/resources/dash/tidal_hires_manifest.mpd`. Hosts are `cdn.example`, queries are `?token=x`, and the opaque asset id in the path is `ASSET`. Everything else is byte-for-byte.

### Token lifetime

- Right away: the init, first and last segment all returned 200.
- A full song, downloaded one segment at a time: 81 requests, 112 MB, 37.6 s on this connection. Every request returned 200.
- 30-minute re-check: pending, a later task records the result. The decision doesn't depend on it, because Task 6 re-resolves on 401/403/410 anyway.

### Playback tests

`LocalDashPlaybackTest` (Robolectric, Media3 1.5.1) runs on `dash/local_flac/`, a synthetic 6 s, 24-bit/96 kHz FLAC fixture built to Tidal's layout: the same MPD elements and attributes, the same box layout, and 4096-sample frames.

1. Local DASH prepares with the full duration: PASS. Duration 5999 ms, seekable.
2. Local DASH seeks to the middle: PASS. `seekTo(4000)` lands at exactly 4000, and it then plays to the end.
3. Concatenated fMP4 as progressive: it knows its duration (6000 ms, from `mehd`) and plays to the end, but `isCurrentMediaItemSeekable` is false and `seekTo(4000)` lands at 0. With no `sidx`, the extractor has no seek index.

I also ran the same checks once on the real downloaded song, locally and not committed:

- Local DASH: duration 315037 ms, seekable, and `seekTo(310000)` landed at 310000.
- Concatenated: not seekable, and the seek landed at 0.
- Pressing play on the real song fails under Robolectric. The pass-through test codec copies the sample entry's 0 Hz into the AudioSink, which rejects it. A real FLAC decoder reads 96 kHz from `dfLa`, and the app already plays these same init segments from the network today. So that's a test harness limit, not a local DASH one. The synthetic fixture keeps ffmpeg's 48000 in that field so the renderer can run.

### What this means for the plan

- Option 1 as planned. Store the init and the numbered segments, and write a local MPD whose `SegmentTemplate` uses relative names (`init.mp4`, `seg_$Number$.mp4`), the way the fixture does.
- Option 2 is out. Seeking would need a generated `sidx`.
- Media3 stays on 1.5.1.
- Real decode of a cached Hi-Res song still needs the on-device check in Validation step 4.
