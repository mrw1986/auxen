# Android — Split Headphone Correction (AutoEq) from the Graphic EQ

> **STATUS: SHIPPED (Tasks 1–2 + migration crash-safety fixes; commits c6cb045, 41496b4, 94c32f1, 80209ba).** Retained as record. Notes: a whole-batch Opus final review killed the two-preamp over-attenuation concern (each stage's preamp only offsets its own boosts — series design sound) and caught a migration crash-safety bug (guard persisted before payloads) which was fixed, then refined to a destination→source→guard write order with zero data-loss window. The migration test can't reproduce the historical race timing on a fast idle machine (proven by a temporary delay(200) reintroduction); the safety is by construction, not test-enforced.

**Motivation (user, 2026-07-13):** "should the headphone autoeq have its own toggle like Wavelet? I don't like mixing the eq and autoeq." Today they ARE the same thing: `EqController.importAutoEq` parses an AutoEq profile into the single `EqState` and overwrites the 10-band graphic EQ. Importing a headphone profile wipes your manual EQ, and there's one shared enable switch. Wavelet keeps headphone correction and the graphic EQ as independent, separately-toggleable stages.

**Goal:** Two independent EQ stages, each with its own enable flag, its own persistence, and its own UI section:
- **Tune for your headphones** (AutoEq correction) — driven by a searched/imported profile; no manual bands.
- **Equalizer** (graphic) — the 10-band sliders + presets; no AutoEq picker.

Both are `ParametricEqProcessor` instances (the class already takes arbitrary `FilterSpec` lists + preamp). This adds one processor to the chain.

**Chain order (updated LAW):** `ReplayGain → AutoEq → GraphicEq → BassBoost → Balance → Limiter → EncodingRestorer`. Correction sits before the user's graphic taste; both are LTI so magnitude is order-independent, but each stage carries its own preamp so headroom composes predictably. `ProcessorChainOrderTest` must be updated to 7 processors.

**Non-goals:** changing the AutoEq parser, the bundled 8,850-profile database, or the graphic-EQ math. This is a separation + UI split, not a DSP redesign.

**Gate (every task):** `cd android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug` green; golden re-record/eyeball discipline; the ComponentScreenshotTest AA-drift check.

---

### Task 1: AutoEqController + second processor + chain wiring + migration

**Files:** new `dsp/AutoEqController.kt`; `dsp/EqController.kt` (remove `importAutoEq`); `playback/PlaybackService.kt` (second processor, chain, init); `ProcessorChainOrderTest`; new `AutoEqControllerTest` + a migration test.

1. **`AutoEqController`** — mirror `EqController` exactly (same object shape, `@Volatile initJob`, `attachProcessor`, `initialize`, `awaitInitialized`, `setState`), but:
   - Its own DataStore: `internal val Context.autoEqDataStore by preferencesDataStore(name = "autoeq")`, key `autoeq_state`.
   - Reuse `EqState` as its state type (AutoEq profiles already parse to `EqState`; `bands` stays null, `presetName` = profile name).
   - Move `importAutoEq(text, profileName): Result<EqState>` here (verbatim — keep the `runCatching { AutoEqParser.parse(...) }.onSuccess { setState(it) }` shape and the Q≤0/Fc≤0 rejection semantics it relies on).
   - Add `setEnabled(enabled)` and `clear()` (`setState(EqState())` — empties filters, disables, drops the profile name).
2. **`EqController`** — delete `importAutoEq` and its `AutoEqParser` import. It is now purely graphic (`setBand`, `applyPreset`, `setEnabled`). Leave everything else untouched.
3. **`PlaybackService`** — construct a second `ParametricEqProcessor` (call it `autoEqProcessor`), `AutoEqController.attachProcessor(autoEqProcessor)` alongside the existing EQ attach, `AutoEqController.initialize(applicationContext)` alongside `EqController.initialize`, and include `autoEqProcessor` in `buildDspProcessorChain` in the LAW position (right after `replayGain`, before `eqProcessor`). Update `buildDspProcessorChain`'s signature/params accordingly.
4. **`ProcessorChainOrderTest`** — update the expected type list to the 7-element order `[ReplayGainProcessor, ParametricEqProcessor, ParametricEqProcessor, BassBoostProcessor, BalanceProcessor, LimiterProcessor, EncodingRestorerProcessor]`. Note both AutoEq and graphic are the same class — assert position/count, and if the test can't distinguish two `ParametricEqProcessor`s by type alone, assert the array length + that indices 1 and 2 are both `ParametricEqProcessor` (the wiring that they're the autoEq vs eq instance is covered by the attach in Task 1; add an identity assertion if `buildDspProcessorChain` takes them as named params).
5. **Migration (one-time, robust).** Existing users have their AutoEq profile persisted in the OLD `eq_state` (merged behavior: `bands == null && filters.isNotEmpty()` ⇒ it was an AutoEq import, not graphic). In `AutoEqController.initialize`, after loading its own (empty) state, run a one-time migration guarded by a boolean pref (`autoeq_migrated`):
   - Read the legacy `EqController` `eq_state`. If it looks like an AutoEq import (`bands == null && filters.isNotEmpty()`), seed `AutoEqController` from it (preserving its `enabled`), and reset the legacy graphic state to flat/disabled (`EqController.setState(EqState())` or `fromBands(all-zero, enabled=false)`), so the correction isn't double-applied once both stages exist.
   - If the legacy state is graphic (`bands != null`), leave it in `EqController` and AutoEq starts empty/disabled.
   - Set `autoeq_migrated = true`. Order the coroutines so the migration reads legacy state before EqController may overwrite it (join `EqController.awaitInitialized()` first, or read the raw DataStore value directly).
   - Test the migration: seed a legacy AutoEq-shaped `eq_state`, initialize, assert AutoEqController holds it (enabled) and EqController is flat; and the graphic case leaves Eq intact and AutoEq empty; and that it runs only once (marker set).
6. Tests mirror `EqControllerTest` / `AudioFxControllerTest`: state round-trip through the new DataStore, importAutoEq success/failure (bad profile → failed Result, DataStore untouched — carry the DSP-a Important-#1 guarantee), enable independence.

### Task 2: UI — two sections, AutoEq picker moves out of the EQ card

**Files:** `ui/EqualizerScreen.kt`, `ui/components/FxSections.kt` (respect the 800-line cap), `strings.xml`.

Today the first `FxSectionCard` ("Equalizer") holds the 10-band sliders + presets AND the AutoEq picker ("Find your headphone model" search, ProfileRow results, custom import, active-profile display, imePadding/BringIntoViewRequester keyboard handling). Split it:

1. **"Tune for your headphones"** section (new, FIRST on the screen) — own switch bound to `AutoEqController.setEnabled`. Contains the entire AutoEq picker block moved from the EQ card: the search field (keep the `imePadding` + `BringIntoViewRequester` wiring intact — it must still scroll above the keyboard), `AutoEqPickerResults`/`ProfileRow`, "Import custom profile…", the active-profile name display, and a "Remove profile" action calling `AutoEqController.clear()`. `importAutoEq` now targets `AutoEqController`. No sliders here.
2. **"Equalizer"** section (graphic, SECOND) — own switch bound to `EqController.setEnabled`. The 10-band `VerticalSlider`s + preset chips only. No AutoEq picker.
3. Both read their own controller's `state` StateFlow for enabled + content; strictly independent (the binding per-effect-independence rule). Remaining sections (Bass/Balance/Limiter/Reverb/Virtualizer/VolNorm) unchanged, in order after these two.
4. Copy via `strings.xml`. Section titles/subtitles user-facing ("Tune for your headphones" / "Headphone correction curve, applied before your manual EQ"). Keep the existing "Find your headphone model" placeholder.
5. Goldens: new `autoEqSection` pair; the `eq`/graphic section golden changes (loses the picker); `equalizer-all-sections-collapsed` gains a card (now 8). Re-record + eyeball; a11y contentDescriptions on the new switch + any moved controls; verify imePadding still lifts the search field above the keyboard after the move.

### Task 3: Close-out

1. Clean-state full gate (`--rerun-tasks`), final test count.
2. Whole-batch review (lead — smaller surface than DSP-a/b; a focused review may suffice over a full workflow).
3. Roadmap/parity note (lead): the split is a UX refinement of the shipped EQ/AutoEq; record it. Update the master plan's EQ description (`Eq.kt` front-ends: graphic + AutoEq are now separate stages).
4. Fold into the next APK to the user.
