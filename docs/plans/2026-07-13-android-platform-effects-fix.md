# Android — Fix Reverb + Virtualizer (no audible effect on device)

**Bug (user-confirmed, physical device):** toggling reverb or virtualizer produces no audible change. Root-caused via research against four independent working open-source Media3 players (RiMusic, ViTune, Kreate, RiPlay) + the Wavelet author's own AudioEffect bug-report repo (Pitt van de Witt). CI cannot verify audio audibility — the final proof is the user's device, so this ships WITH a diagnostic log line and a device checklist.

## Two independent root causes

1. **Reverb is an AUXILIARY (send) effect, never routed.** `PresetReverb(_, sessionId).setEnabled(true)` alone is inaudible. It must be sent into the AudioTrack via Media3's public `player.setAuxEffectInfo(AuxEffectInfo(reverb.id, sendLevel))` (→ `AudioTrack.attachAuxEffect` + `setAuxEffectSendLevel`). Requires `MODIFY_AUDIO_SETTINGS`. Per-session reverb IS feasible this way — no session 0, no raw-AudioTrack access needed.
2. **Virtualizer hits a known Android 13/14 platform bug.** It's an insert effect (no aux routing), but on 13/14 it's silent unless `forceVirtualizationMode(VIRTUALIZATION_MODE_BINAURAL)` is called ~50 ms AFTER `enabled = true`. Sourced from the Wavelet author's documented workaround.

**STRONGEST reverb cause (guaranteed, device-independent), added after the diagnosis workflow (wf_87583ade):** enabling reverb leaves `preset` at its default `0 = PRESET_NONE` ("No reverb"). The enable toggle (`FxSections.kt:311`) sends only `copy(enabled = it)` — no preset — and the preset dropdown is a separate control. So "reverb on" = reverberation preset none = zero effect, before any routing question. This alone likely explains the reverb report and must be fixed first.

**RECONCILIATION — the two investigations disagreed on the session id; the diagnosis wins.** The reference research said to drop `setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)`. The diagnosis workflow bytecode-verified (Media3 1.5.1) that this call is CORRECT and NECESSARY: the constructor session id is never propagated to the sink without it; with it, the AudioTrack is built with that exact id, stable across transitions, and session-mismatch is RULED OUT. **KEEP `setAudioSessionId(UNSET)` — do NOT remove it.** (This corrects the first draft of this plan.) Effects created before an AudioTrack is the standard documented pattern and is sound as long as the id matches (it does) — so re-applying on playback start is belt-and-suspenders, not a required rewrite.

## Fix

**Files:** `AndroidManifest.xml`, `playback/PlaybackService.kt`, tests, a diagnostic log.

### 1. Manifest
Add `<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />` (normal permission, no runtime request needed).

### 2. Reverb preset-on-enable (FIX 1 — the strongest, cheapest, do first)
Never enable reverb with `PRESET_NONE`. Default `ReverbState.preset = 1` (`PRESET_SMALLROOM`, `AudioFxState.kt`) AND, at the enable toggle, if enabling while `preset == 0` set a real preset (e.g. 1). Unit-testable. On devices where per-session PresetReverb resolves as an inline insert, this alone fixes reverb.

### 2b. Session id — KEEP the UNSET call
Do NOT remove `player.setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)` (bytecode-verified correct; the sink won't get the session id otherwise). Keep the eager build. Additionally re-apply effects on `onMediaItemTransition` / `onIsPlayingChanged(true)` as belt-and-suspenders for AudioTrack recreation — do not rely on it as the primary path.

### 3. Reverb (aux-routed)
```kotlin
// enable path (reverb state enabled)
if (reverb == null) reverb = runCatching { PresetReverb(1, player.audioSessionId) }.getOrNull()
reverb?.let { r ->
    runCatching {
        r.preset = clampReverbPreset(state.preset)   // PRESET_SMALLROOM..PRESET_PLATE
        r.enabled = true
        r.id.let { player.setAuxEffectInfo(AuxEffectInfo(it, 1f)) }   // THE missing send route
    }
}
// disable path
runCatching {
    reverb?.enabled = false
    player.clearAuxEffectInfo()
}
```
On teardown/release: `reverb?.release(); reverb = null` (and clear aux info). `AuxEffectInfo` from `androidx.media3.common.AuxEffectInfo`.

### 4. Virtualizer (insert + forceVirtualizationMode)
```kotlin
if (virtualizer == null) virtualizer = runCatching { Virtualizer(0, player.audioSessionId) }.getOrNull()
virtualizer?.let { v ->
    runCatching {
        if (v.strengthSupported) v.setStrength(clampVirtualizerStrength(state.strength))
        v.enabled = state.enabled
    }
    if (state.enabled) {
        val captured = v
        serviceScope.launch {   // ~50ms delay, Android 13/14 workaround
            delay(50)
            runCatching { captured.forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_BINAURAL) }
        }
    }
}
```
Capture the instance (it may be rebuilt) before the delayed call. `serviceScope` is IO — `forceVirtualizationMode` on the effect object is fine off-main.

### 5. Re-apply on transitions
Add a single `reapplySessionEffects()` that re-runs reverb (route or clear) + virtualizer for the CURRENT AudioFxController states, called from `onMediaItemTransition` (the AudioTrack can be recreated between tracks / on route changes) — one line alongside the existing RG-router + sleep-timer transition hooks. `attachReverb`/`attachVirtualizer` lambdas continue to apply on live state changes; on release, clear aux info.

### 6. Diagnostic log (so the user can confirm on-device)
Since audibility is un-CI-testable, emit ONE structured log line (tag `AuxenFx`) on every apply, so `adb logcat | grep AuxenFx` disambiguates every hypothesis:
`sessionId=<id> reverb[created=<b> id=<int> enabled=<b> auxRouteSet=<b>] virtualizer[created=<b> strengthSupported=<b> strength=<int> enabled=<b> forceModeApplied=<b>]`
Include the values from `getEnabled()`/`hasControl()` where available. Gate behind a simple const or always-on at Log.i (music apps log during playback; acceptable). This is the shipped fallback if the fix still doesn't work on the user's specific device.

### 7. Tests + device checklist
- Platform effect objects aren't Robolectric-testable (established). Test what IS: the apply-decision logic (given a state, does it choose route vs clear; strength clamping; the guard against UNSET session), extracted into pure/testable helpers where possible.
- No golden changes (no UI change in this task — the reverb/virtualizer SECTIONS from DSP-b stay; only the wiring changes).
- Report MUST include a device-verification checklist: (a) toggle reverb + hear tail/space, (b) toggle virtualizer on headphones + hear widening, (c) if either still silent, run `adb logcat | grep AuxenFx` during a toggle and paste the line back — its fields pinpoint the remaining cause.

**Gate:** full build green. Commit `fix(android): route reverb as aux effect and force virtualizer binaural mode`. Report SHA/tests + the device checklist.

## Corroboration
Cross-check against the diagnosis workflow (wf_87583ade) when it completes; if its adversarial-verify step surfaces any concern with `setAuxEffectInfo` or the force-mode timing, fold it in before the implementer commits.
