# Auxen Android — DSP Suite (a): Engine Implementation Plan

> **STATUS: SHIPPED (Tasks 1–7 + fix rounds; commits 34f709a..9d91b84).** Retained as record. Review-driven deviations that WON over the text below:
> - The knee worked example ("<0.5 dB reduction at threshold") is wrong at the default kneeDb=6 — reduction at threshold is exactly kneeDb/8 (0.75 dB). Formula itself unchanged.
> - The ReplayGain gain formula as literally written has a misplaced paren; shipped code uses the standard `10^((gain + preamp)/20)`.
> - Task 3's "restorer inactive/pass-through" prose was resolved per the Interfaces contract: inactive for 16-bit input, active float→16-bit conversion for float input.
> - Album↔track gain fallback is symmetric in both directions (plan only specified album→track).
> - An interim `[eq, restorer]` wiring commit (c52403c) landed between Tasks 2 and 6 to keep every commit on the branch device-safe.
> - Known bounded limitation (documented, real fix roadmapped): at gapless transitions, per-track RG gains land after the next track's buffered head — an unramped step, typically small (identical in album mode).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The audio engine for the Wavelet-class effects chain: per-effect state with independent persistence, a float-through processor chain with headroom (bass boost, channel balance, soft-knee limiter, ReplayGain), and the pipeline wiring — leaving UI, platform effects (reverb/virtualizer), and the sleep timer to plan DSP-b.

**Architecture:** A new `AudioFxController` (sibling of `EqController`) owns each effect's `@Serializable` state under its own DataStore key — the binding rule is per-effect independence. The processor chain is re-architected to run float BETWEEN our processors: `ParametricEqProcessor` stops clamping and emits float for 16-bit input; new small processors (`ReplayGainProcessor` → EQ → `BassBoostProcessor` → `BalanceProcessor` → `LimiterProcessor`) transform float; a final `EncodingRestorerProcessor` converts back to 16-bit so `DefaultAudioSink`'s built-in 16-bit-only processors stay happy (the M2 lesson, now solved at chain level instead of per-processor). ReplayGain values come from Tidal's playbackinfo response and, for local files, a pure-Kotlin tag reader (FLAC VorbisComment + ID3v2 TXXX).

**Tech Stack:** Kotlin, Media3 `BaseAudioProcessor` (existing pattern), kotlinx-serialization, DataStore (existing), no new dependencies.

## Global Constraints

- All Gradle commands from `/home/mrw1986/Projects/auxen/android` prefixed with `JAVA_HOME=~/.jdks/jdk-21.0.11+10`.
- Test baseline at plan start: **108 tests, 0 failures**; every task states its expected new total and keeps `:app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug` green.
- BINDING (user directive): every effect individually toggleable; each state persists under its own DataStore key; toggling one effect never touches another's state. Disabled processors pass audio through untouched.
- Chain order is LAW: `ReplayGain → ParametricEq → BassBoost → Balance → Limiter → EncodingRestorer`, installed in `EqRenderersFactory`. Only `EncodingRestorer` may emit 16-bit; every other processor emits float for any accepted input.
- All processors follow the `ParametricEqProcessor` conventions: `@Volatile` state + generation counter, `updateState()`, pass-through when disabled, 16-bit AND float input accepted, per-sample float math, KDoc explaining the audio contract.
- The M2 regression test `sixteenBitInputYieldsSixteenBitOutput` is deliberately superseded: the chain-level invariant replaces the processor-level one (Task 2 rewrites it with an explanatory KDoc — this is an intentional, documented revisit, not a regression).
- Code style: KDoc, 4-space indent, trailing commas. Commit messages: conventional commits with the implementer's co-author trailer given per dispatch.

---

### Task 1: Per-effect state + AudioFxController

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/dsp/AudioFxState.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/dsp/AudioFxController.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/dsp/AudioFxControllerTest.kt`

**Interfaces:**
- Consumes: DataStore prefs pattern from `EqController` (read it first).
- Produces (every later task relies on these exact shapes):

```kotlin
@Serializable
data class BassBoostState(
    val enabled: Boolean = false,
    val freqHz: Double = 80.0,      // 40..200 in UI
    val gainDb: Double = 6.0,       // 0..12 in UI
)

@Serializable
data class BalanceState(
    val enabled: Boolean = false,
    val balance: Float = 0f,        // -1 = full left … +1 = full right
)

@Serializable
data class LimiterState(
    val enabled: Boolean = true,    // safety net defaults ON
    val thresholdDb: Double = -1.0,
    val kneeDb: Double = 6.0,
    val releaseMs: Double = 120.0,
)

@Serializable
data class ReplayGainState(
    val enabled: Boolean = false,
    val albumMode: Boolean = false, // false = track gain, true = album gain
    val preampDb: Double = 0.0,     // -12..+12
    val fallbackDb: Double = 0.0,   // applied when a track has no RG data
)
```

- `object AudioFxController` with, for each effect X in {bassBoost, balance, limiter, replayGain}: `val xState: StateFlow<XState>`, `fun updateX(state: XState)` (persists that effect's key only), plus `fun initialize(context: Context)` (restores all four independently; malformed stored JSON → that effect's defaults, others unaffected) and `suspend fun awaitInitialized()` (same pattern as EqController's). Listeners: `fun attachProcessors(bass: BassBoostProcessor, balance: BalanceProcessor, limiter: LimiterProcessor, replayGain: ReplayGainProcessor)` pushes current + future states into the processors (Tasks 3–5 define them; for THIS task declare the function but leave the body wiring `TODO`-free by taking the processors as `((State) -> Unit)` lambdas instead: `attachBassBoost(apply: (BassBoostState) -> Unit)` etc. — four small attach functions, each replays current state and subscribes).

- [ ] **Step 1: Failing tests** — `AudioFxControllerTest` (Robolectric, real DataStore in test dir): (a) defaults before initialize; (b) `updateBassBoost` persists and survives a fresh `initialize` round-trip while the OTHER three states remain default (the independence contract); (c) malformed JSON planted under the limiter key → limiter falls back to defaults, bass boost's stored state still loads; (d) `attachBalance` replays current state immediately and receives subsequent updates. Write complete test code following `EqControllerTest`'s persistence patterns if one exists — check; else follow `LibraryRepositoryTest` structure. Expected RED: unresolved references.
- [ ] **Step 2: Implement** state file + controller. Persistence: one string preference key per effect (`fx_bass_boost`, `fx_balance`, `fx_limiter`, `fx_replay_gain`) holding the state JSON; a shared internal `Json { ignoreUnknownKeys = true }`.
- [ ] **Step 3: GREEN + full suite.** Expected total: **112** (108 + 4).
- [ ] **Step 4: Commit** — `feat(android): per-effect audio FX state with independent persistence`

---

### Task 2: Float-through chain — EQ unclamps, EncodingRestorer lands

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/dsp/ParametricEqProcessor.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/dsp/EncodingRestorerProcessor.kt`
- Modify: `android/app/src/test/kotlin/io/github/auxen/dsp/ParametricEqProcessorTest.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/dsp/EncodingRestorerProcessorTest.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/dsp/ProcessorChainTest.kt`

**Interfaces:**
- Produces: `EncodingRestorerProcessor : BaseAudioProcessor` — accepts `ENCODING_PCM_FLOAT` (converts to 16-bit with the symmetric `*32768` scale + clamp used today) and `ENCODING_PCM_16BIT` (declares itself inactive by returning `AudioFormat.NOT_SET`, i.e. zero-cost pass-through — verify BaseAudioProcessor's inactive contract by reading it; if returning NOT_SET breaks the chain, emit pass-through 16-bit instead and document). It is ALWAYS last among our processors.
- `ParametricEqProcessor` change: 16-bit input now yields **float output, no clamping** (the limiter and restorer own the ceiling). Float input remains float. The `queueInput` clamp block is removed; KDoc updated to describe chain-level headroom.

- [ ] **Step 1: Rewrite the superseded regression test FIRST.** In `ParametricEqProcessorTest`: replace `sixteenBitInputYieldsSixteenBitOutput` with `sixteenBitInputYieldsFloatForChainHeadroom` (asserts float output encoding) carrying a KDoc explaining the supersession: the sink-compat invariant moved to the CHAIN level (`ProcessorChainTest.chainRestoresSixteenBitForTheSink`), enabling inter-processor headroom. Update `disabledStatePassesSixteenBitSamplesThrough` to read float output and assert sample VALUES are the exact `/32768f` promotions; update `preampAttenuatesSixteenBitSamples` accordingly. Add `boostBeyondFullScaleIsNotClampedMidChain`: EQ state with a big low-shelf boost + full-scale input → output float sample magnitude may exceed 1.0 (assert it does for a crafted case). Run: these fail against current code (RED).
- [ ] **Step 2: Implement** the EQ change (emit float always; drop clamp; buffer sizing `sampleCount * 4`) and `EncodingRestorerProcessor` (complete code — mirror ParametricEqProcessor's buffer walk; no state, no toggles: it's plumbing, not an effect).
- [ ] **Step 3: ProcessorChainTest** — the new chain-level invariant, complete code: configure EQ → restorer in sequence feeding one's output format into the next's `configure`, push a 16-bit buffer through both `queueInput`/`output` hops manually, and assert (a) final encoding 16-bit, (b) a full-scale-boosted sample clamps only at the restorer, (c) disabled-everything round-trips 16-bit samples exactly. Also assert the FLOAT-input case: EQ passes float, restorer inactive/pass-through.
- [ ] **Step 4: GREEN + full suite.** Expected total: **~115** (rewrites + 2 restorer tests + 3 chain tests; state the real number).
- [ ] **Step 5: Commit** — `feat(android): float-through DSP chain with encoding restorer`

---

### Task 3: BassBoostProcessor + BalanceProcessor

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/dsp/BassBoostProcessor.kt`
- Create: `android/app/src/main/kotlin/io/github/auxen/dsp/BalanceProcessor.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/dsp/BassBoostProcessorTest.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/dsp/BalanceProcessorTest.kt`

**Interfaces:**
- Produces: `BassBoostProcessor : BaseAudioProcessor` with `updateState(BassBoostState)` — one `Biquad.lowShelf(sampleRate, freqHz, q = 0.707, gainDb, channelCount)` rebuilt on state/format generation change (exactly the `rebuildFiltersIfNeeded` pattern); float in/out (accept 16-bit too, promoting like the EQ does — the chain normally hands it float, but standalone safety matters). `BalanceProcessor : BaseAudioProcessor` with `updateState(BalanceState)` — per-channel gains `left = min(1f, 1f - balance)`, `right = min(1f, 1f + balance)`, channels beyond 2 untouched; disabled or `balance == 0f` → pass-through.
- Consumes: `Biquad` (exists), state types (Task 1).

- [ ] **Step 1: Failing tests** (complete code in the same style as `ParametricEqProcessorTest`): bass — disabled passthrough exact; enabled with +6 dB @ 80 Hz boosts a 50 Hz sine's RMS by ~5–6 dB while a 5 kHz sine changes <0.5 dB (generate sines in the test, measure RMS over the steady-state tail); balance — full-left zeroes the right channel and leaves left untouched, half-right attenuates left by 0.5, mono (1-channel) input unaffected by design (document).
- [ ] **Step 2: Implement both.** Complete code following the established processor skeleton.
- [ ] **Step 3: GREEN + full suite.** Expected: **~121**.
- [ ] **Step 4: Commit** — `feat(android): bass boost and channel balance processors`

---

### Task 4: LimiterProcessor

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/dsp/LimiterProcessor.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/dsp/LimiterProcessorTest.kt`

**Interfaces:**
- Produces: `LimiterProcessor : BaseAudioProcessor` with `updateState(LimiterState)`. Algorithm (document in KDoc verbatim):
  - Per FRAME (all channels of one sample instant): `peak = max(|ch_i|)`.
  - Soft-knee desired gain in dB: with `over = 20*log10(peak) - thresholdDb` and knee `k = kneeDb`: reduction `0` when `over <= -k/2`; `((over + k/2)^2) / (2k)` when `|over| < k/2`; `over` when `over >= k/2`. `desiredGain = 10^(-reduction/20)` (guard `peak <= 0` → desired 1).
  - Envelope: instant attack, exponential release — `gain = if (desired < gain) desired else gain + (1 - releaseCoef) * (desired - gain)` with `releaseCoef = exp(-1.0 / (releaseMs / 1000.0 * sampleRate))`, gain state seeded 1.0, reset in `onFlush`.
  - Apply `gain` to every channel of the frame. Final hard safety clamp at ±1 AFTER the gain (belt for pathological attacks; document).
  - Disabled → pass-through with NO clamp (the restorer still clamps at conversion).
- Consumes: `LimiterState` (Task 1).

- [ ] **Step 1: Failing tests** (complete code): (a) below-threshold sine passes bit-identically (float compare exact); (b) a +6 dB-over-threshold constant block settles to output ≤ threshold linear value +0.1 dB; (c) attack is instant — first over-threshold frame already limited; (d) release — after the loud block ends, gain recovers toward 1.0 with the configured time constant (sample the gain trajectory via output amplitude of a quiet probe tone; assert monotonic recovery and >90 % recovery after 5× releaseMs); (e) disabled passthrough exact even for >1.0 samples; (f) knee — a signal exactly AT threshold receives < 0.5 dB reduction (soft, not brick).
- [ ] **Step 2: Implement.** Complete code; dB math via `ln`/`exp` helpers, no per-frame allocations.
- [ ] **Step 3: GREEN + full suite.** Expected: **~127**.
- [ ] **Step 4: Commit** — `feat(android): soft-knee limiter processor`

---

### Task 5: ReplayGain — tag reader, Tidal fields, gain processor

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/dsp/ReplayGainTags.kt` (pure parser)
- Create: `android/app/src/main/kotlin/io/github/auxen/dsp/ReplayGainProcessor.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/ContentProvider.kt` (StreamInfo gains fields)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/tidal/TidalProvider.kt` (parse playbackinfo RG fields)
- Modify: `android/app/src/main/kotlin/io/github/auxen/provider/local/LocalProvider.kt` (read tags for the streamed file)
- Test: `android/app/src/test/kotlin/io/github/auxen/dsp/ReplayGainTagsTest.kt`, `ReplayGainProcessorTest.kt`

**Interfaces:**
- Produces:
  - `object ReplayGainTags { fun parse(input: InputStream): ReplayGainInfo? }` with `data class ReplayGainInfo(val trackGainDb: Double?, val albumGainDb: Double?)`. Supported containers, documented as the honest subset: FLAC (`fLaC` magic → VORBIS_COMMENT metadata block → `REPLAYGAIN_TRACK_GAIN` / `REPLAYGAIN_ALBUM_GAIN`, case-insensitive, `"-6.50 dB"`-style values) and MP3 ID3v2.3/2.4 (`ID3` header, syncsafe size, TXXX frames with ISO-8859-1 or UTF-8 encodings, descriptions `replaygain_track_gain`/`replaygain_album_gain`; unsynchronisation and compressed frames are out of scope → return what parses, never throw). Anything else → null.
  - `StreamInfo` gains two fields: `trackGainDb: Double? = null`, `albumGainDb: Double? = null` (default-null keeps every existing constructor call compiling — verify).
  - `TidalProvider`: `PlaybackInfo` DTO gains `trackReplayGain: Double? = null`, `albumReplayGain: Double? = null`; both mapped into the returned `StreamInfo` for BTS and DASH branches.
  - `LocalProvider.getStreamInfo`: opens the content URI (`contentResolver.openInputStream`), runs `ReplayGainTags.parse` (wrapped `runCatching`, budgeted: read at most the first 512 KB — enforce with a bounded stream), fills the StreamInfo fields.
  - `ReplayGainProcessor : BaseAudioProcessor` with `updateState(ReplayGainState)` AND `fun setTrackGains(trackGainDb: Double?, albumGainDb: Double?)` (called per track): applied linear gain = `10^((chosenGain ?: fallbackDb) + preampDb) / 20)` where chosenGain honors `albumMode` with track-gain fallback; disabled → pass-through. Gain change takes effect from the next buffer (no ramp — document; a click on manual toggle mid-play is acceptable for now).
- Consumes: Task 1 state; the wiring of `setTrackGains` into the playback service happens in Task 6.

- [ ] **Step 1: Failing parser tests** — build tiny FLAC and ID3v2 fixtures IN the test as byte arrays (complete code: hand-assemble a minimal `fLaC` + VORBIS_COMMENT block with the two comments; a minimal ID3v2.3 header + one TXXX frame; a garbage buffer → null; a FLAC without RG comments → `ReplayGainInfo(null, null)` or null — pick and document). Processor tests: gain math for track/album/fallback/preamp combinations, disabled passthrough.
- [ ] **Step 2: Implement all pieces.** Parser complete code (byte-level, no dependencies); processor follows the skeleton; provider edits minimal.
- [ ] **Step 3: GREEN + full suite** (`assembleDebug` proves provider edits compile everywhere). Expected: **~135**.
- [ ] **Step 4: Commit** — `feat(android): ReplayGain — tag reader, Tidal gains, gain processor`

---

### Task 6: Pipeline integration

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt` (chain assembly + per-track RG push)
- Modify: `android/app/src/main/kotlin/io/github/auxen/AuxenApp.kt` (AudioFxController.initialize)
- Modify: `android/app/src/main/kotlin/io/github/auxen/dsp/AudioFxController.kt` (finalize attach wiring if lambdas need adjusting)
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/TrackResolver.kt` or service (RG values reach the processor when a Tidal stream resolves)
- Test: extend `ProcessorChainTest` with the full six-stage chain

**Interfaces:**
- `EqRenderersFactory.buildAudioSink` installs `arrayOf(replayGainProcessor, eqProcessor, bassBoostProcessor, balanceProcessor, limiterProcessor, encodingRestorerProcessor)` — order per Global Constraints.
- `AuxenApp.onCreate`: `AudioFxController.initialize(this)` beside `EqController.initialize`.
- Service: single instances of the new processors created in `onCreate`, attached to `AudioFxController` (each effect's attach called once), RG per-track push: on media item transition, the current item's resolved `StreamInfo` gains — LOCAL: available at `getStreamInfo` time; TIDAL: available when `TrackResolver.resolve` returns. Simplest correct route (implement this one): `ReplayGainProcessor.setTrackGains` is called from a small `RgGainRouter` in the service — on `onMediaItemTransition`, launch: if mediaId is LOCAL, call `Graph.local.getStreamInfo`-equivalent tag read via a new lightweight `Graph.local.replayGainFor(sourceId)` (add it — reads tags only); if TIDAL, `Graph.resolver.resolve(id)` (cache hit in the common case since playback just resolved it) and use its gains. `runCatching` everywhere; null gains → `setTrackGains(null, null)`.
- Chain test extension: all six processors chained manually as in Task 2's test — RG boost +6 dB into an already-full-scale 16-bit signal, limiter enabled → final 16-bit output NEVER wraps/overflows (assert ceiling), and with limiter disabled → restorer clamps (assert clamp, no wrap).

- [ ] Steps: failing chain test first → wiring → GREEN → full suite (**~137**) → commit `feat(android): six-stage DSP chain wired into the playback pipeline`.

---

### Task 7: Close-out

- [ ] Full verification (`test verifyRoborazziDebug assembleDebug`), totals recorded.
- [ ] `docs/plans/2026-07-03-android-app.md`: audio-path section updated for the six-stage chain.
- [ ] Commit `docs(android): DSP engine shipped`; push; CI watch (both jobs now required).
- [ ] Controller then runs the whole-batch final review workflow before DSP-b planning; roadmap + parity artifacts update after DSP-b (UI) ships, since user-visible effects need the UI half.
