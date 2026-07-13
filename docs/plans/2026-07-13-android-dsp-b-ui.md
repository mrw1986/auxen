# Android DSP-b — Effect UI, Platform Effects, Sleep Timer

**Goal:** Make the DSP-a engine user-visible and complete the Wavelet-class suite: per-effect toggle UI on the Equalizer screen, platform PresetReverb + Virtualizer via the audio session, and the sleep timer. Standing user requirement (BINDING): **every effect individually toggleable, independently persisted, no coupling.**

**Prerequisite:** DSP-a complete (chain live: ReplayGain → Eq → Bass → Balance → Limiter → Restorer; `AudioFxController` slots for bass/balance/limiter/replayGain; `buildDspProcessorChain` order-pinned by test).

**Verification gate (every task):** `cd android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug` — green, plus golden re-record/eyeball discipline and the ComponentScreenshotTest AA-drift check (documented in prior reports).

**Copy correction carried from DSP-a review:** limiter knee reduction at threshold is `kneeDb/8` dB (0.75 dB at the default 6 dB knee) — UI copy must not claim "<0.5 dB at threshold."

---

### Task 1: Audio-session plumbing + platform effect states (reverb, virtualizer)

**Files:** `dsp/AudioFxState.kt`, `dsp/AudioFxController.kt`, `playback/PlaybackService.kt`, tests.

1. New states (same @Serializable pattern; exact defaults):
```kotlin
@Serializable
data class ReverbState(val enabled: Boolean = false, val preset: Int = 0) // PresetReverb.PRESET_* ordinal-compatible Short range 0..6
@Serializable
data class VirtualizerState(val enabled: Boolean = false, val strength: Int = 500) // 0..1000 per android.media.audiofx.Virtualizer
```
2. Two new `FxSlot`s in AudioFxController (keys `fx_reverb`, `fx_virtualizer`) + `attachReverb`/`attachVirtualizer` — identical pattern to the four existing slots (CopyOnWriteArrayList listeners, per-slot persist jobs).
3. PlaybackService: platform effects need the sink's audio session. Add `setAudioSessionId` handling — ExoPlayer emits `Player.Listener.onAudioSessionIdChanged(id)`; on each change (and on first ready), tear down and recreate the two effects:
```kotlin
private var reverb: PresetReverb? = null
private var virtualizer: Virtualizer? = null
private fun rebuildSessionEffects(sessionId: Int) {
    reverb?.release(); virtualizer?.release(); reverb = null; virtualizer = null
    if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
    // Emulators/devices may lack effect implementations — never crash playback for an effect.
    reverb = runCatching { PresetReverb(0, sessionId) }.getOrNull()
    virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
    applyReverb(AudioFxController-current state); applyVirtualizer(...)
}
```
   `attachReverb`/`attachVirtualizer` lambdas apply enabled/preset/strength to the live instances (null-safe). Release both in `onDestroy`.
4. Tests: state serialization/defaults + controller slot round-trip (mirror `AudioFxControllerTest` patterns). Platform effect objects are NOT unit-testable under Robolectric — test the state plumbing and the null-safe apply path; the runCatching guard is the documented device gate (CI smoke covers playback not breaking).

### Task 2: Sleep timer

**Files:** `playback/SleepTimer.kt` (new), `playback/PlaybackService.kt`, `ui/NowPlayingScreen.kt`, tests.

1. `SleepTimerController` (object, same idiom as EqController but no persistence — a timer doesn't survive restarts): `state: StateFlow<SleepTimerState>` where `data class SleepTimerState(val endElapsedRealtime: Long? = null, val finishTrack: Boolean = false)`; `start(minutes: Int, finishTrack: Boolean)`, `cancel()`.
2. Service side: a coroutine on serviceScope watches the state; at expiry either `player.pause()` immediately or arm a one-shot "pause at next onMediaItemTransition" when finishTrack (mind the existing listener — add the check inside the existing transition callback, one line, like the RG router line).
3. Now Playing UI: timer icon button in the control row → `ModalBottomSheet` (skipPartiallyExpanded = true — small-screen lesson) with preset durations (15/30/45/60/90 min), "finish last track" switch, live countdown text when armed, Cancel.
4. Tests: controller state transitions; countdown math via injected clock (no Date.now in tests — pass a clock lambda); UI semantics test for the sheet (mirror BrandBlockTest conventions).

### Task 3: Equalizer screen — per-effect sections

**Files:** `ui/EqualizerScreen.kt` (restructure), new `ui/components/FxSections.kt` (respect the 800-line file cap), string resources.

Section list (each an expandable card with its OWN Switch bound to its state's `enabled` — no master coupling):
1. **Equalizer** (existing 10-band + presets + Tune-for-your-headphones picker — existing master toggle becomes this section's switch; AutoEq flow unchanged).
2. **Bass boost** — frequency slider 40–160 Hz (default 80), gain slider 0–12 dB (default 6).
3. **Balance** — single slider -1..+1, center snap, labels L/R, live percent readout (monospace).
4. **Limiter** — threshold slider -12..0 dB (default -1), release 40–500 ms (default 120); knee stays at default 6 dB (advanced-free UI). Section subtitle: "Soft-knee protection against clipping — on by default." (Do NOT use the <0.5 dB claim; at threshold the reduction is kneeDb/8.)
5. **Reverb** — preset dropdown (None/Small room/Medium room/Large room/Medium hall/Large hall/Plate per PresetReverb constants).
6. **Virtualizer** — strength slider 0–100 % (maps to 0..1000).
7. **Volume normalization (ReplayGain)** — mode segmented control Track/Album, preamp slider -12..+12 dB, fallback gain slider -12..0 dB. User-facing name is "Volume normalization"; keep "ReplayGain" in the subtitle for the initiated.

Rules: every slider writes through AudioFxController.updateX (debounce continuous drags with the established 50 ms pattern if needed); all text via strings.xml; DM Sans/labels per type roles; numeric readouts monospace (Task 4 DSP-a convention).

### Task 4: Goldens + polish

- Golden pairs (dark/light) per new section in ComponentScreenshotTest (drift discipline as usual).
- One "all sections collapsed" EqualizerScreen-level composition golden if feasible with existing harness patterns; otherwise per-section only (document).
- Sweep: imePadding still correct after restructure; UI-scale interactions; a11y contentDescriptions on all new controls.

### Task 5: Close-out

1. Clean-state full gate (`--rerun-tasks`), final test count.
2. Whole-batch final review workflow (lead).
3. Roadmap + parity matrix updates & artifact republish (lead): DSP suite → Shipped; sleep timer → Shipped; reverb/virtualizer rows.
4. Push; fresh APK to the user — this is the build where the DSP suite becomes audible/toggleable.
