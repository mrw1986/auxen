# Auxen for Android — native app plan

**Status:** Milestone 1 scaffolded in `android/` (this commit).
**Goal:** A native Kotlin Android player with first-class media sessions and
built-in Wavelet-style headphone correction, sharing Auxen's core concepts
(unified local + Tidal library, quality-aware duplicate handling) with the
GTK desktop app.

## Why native (recap of the decision)

GTK4/libadwaita and PyGObject have no Android story, so the desktop codebase
cannot be ported directly. The Python core splits cleanly (providers, models,
matching, smart playlists are UI-free), and those modules serve as the spec
for their Kotlin counterparts. Android users expect Media3 media sessions
(lockscreen/notification controls, Bluetooth AVRCP, output switching, Android
Auto), which only a native app delivers well.

## Architecture

```
android/app  (single module for now; split into :core/:playback/:ui when it grows)
 └── io.github.auxen
     ├── model/        Track, Source, quality scoring   ← port of auxen/models.py
     ├── provider/     MusicProvider interface          ← port of providers/base.py
     │   ├── local/    MediaStore-backed library        ← analog of providers/local.py
     │   └── tidal/    OAuth device flow + v1 API       ← port of providers/tidal.py
     ├── dsp/          EQ engine (the Wavelet part)     ← superset of equalizer.py
     ├── playback/     MediaSessionService + ExoPlayer  ← analog of player.py + mpris.py
     └── ui/           Jetpack Compose (Material 3)     ← analog of views/
```

### The audio path (the audiophile part)

Wavelet attaches `DynamicsProcessing` to other apps' audio sessions — it's at
the mercy of the OEM's effect implementation and processes post-mix. Auxen
does correction **inside** the player instead:

```
ExoPlayer decoder → ParametricEqProcessor (float64 biquads, float32 samples)
                  → DefaultAudioSink (float output enabled) → AudioTrack
```

- `dsp/Biquad.kt` — RBJ cookbook peaking/low-shelf/high-shelf, transposed
  direct form II, double-precision state (verified against the analytic
  transfer function: response at fc matches requested gain to <0.001 dB).
- `dsp/ParametricEqProcessor.kt` — Media3 `AudioProcessor` installed via a
  custom `RenderersFactory`. Promotes 16-bit input to float and emits float,
  so nothing is re-quantised after the EQ; 24-bit sources stay bit-exact on
  devices with float `AudioTrack` support.
- `dsp/Eq.kt` — two front-ends over the same engine:
  - the desktop app's 10-band graphic EQ (same ISO bands, same ten presets),
    with automatic preamp = −(largest boost) to prevent clipping;
  - `AutoEqParser` for AutoEq `ParametricEq.txt` exports (PK/LSC/HSC lines),
    i.e. correction profiles for ~5,000 headphone models — the Wavelet
    feature, but in-app and in float.

### Tidal

`TidalAuth` implements the same OAuth device-code flow tidalapi uses
(auth.tidal.com, `link.tidal.com/XXXXX` approval, refresh-token persistence
in DataStore). `TidalProvider` talks to the v1 API: `/sessions` bootstrap,
track search, and `playbackinfopostpaywall` stream resolution, handling both
BTS manifests (direct FLAC/AAC URLs) and DASH MPDs (fed to ExoPlayer as a
`data:` URI via media3-exoplayer-dash).

Client id/secret are **not** in the repo — supply the pair the desktop app
uses via `auxen.tidalClientId` / `auxen.tidalClientSecret` Gradle properties.

### Known limitations of milestone 1

- Tidal stream URLs are resolved at enqueue time and are short-lived; long
  queues need re-resolution on error (`ResolvingDataSource`, milestone 2).
- EQ settings flow through an in-process singleton (`EqController`); should
  become MediaSession custom commands if playback ever moves out of process.
- Local library metadata is MediaStore-only (no bit depth / sample rate);
  needs a `MediaMetadataRetriever` enrichment pass for FLAC quality display.
- No Room database yet — favorites/playlists/play counts (db.py) not ported.

## Roadmap

| Milestone | Scope |
| --- | --- |
| **1 — this commit** | Project scaffold, playback service + media session, float EQ engine + AutoEq import, Tidal device login + search + lossless/Hi-Res streaming, local library browse, minimal Compose UI |
| 2 | Room DB (favorites, playlists, play counts), local↔Tidal matching (port `matching.py`), quality-aware duplicate resolution, queue persistence, `ResolvingDataSource` for URL refresh |
| 3 | Album/artist/playlist views, Tidal home/mixes/explore, lyrics panel, last.fm scrobbling (ports of the corresponding desktop modules) |
| 4 | Android Auto, Chromecast/output routing, gapless + crossfade (crossfade needs a second player instance), sleep timer, widgets |
| 5 | Per-device EQ profiles (auto-switch on Bluetooth device connect — the Wavelet UX), EQ curve visualisation, downloads/offline for local sync |

## Building

Requires Android Studio (or SDK + JDK 17+):

```bash
cd android
./gradlew assembleDebug      # or open in Android Studio
./gradlew test               # JVM unit tests (DSP + parser)
```

With Tidal credentials in `~/.gradle/gradle.properties`:

```properties
auxen.tidalClientId=...
auxen.tidalClientSecret=...
```

> Note: this milestone was authored in an environment without access to
> Google's Maven/SDK repositories, so it has not been compiled yet. Expect a
> normal round of first-build fixes (see PR/commit description).
