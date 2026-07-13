# Android — Fix Reverb + Virtualizer (no audible effect on device)

**Bug (user-confirmed, physical device):** toggling reverb or virtualizer produces no audible change. Root-caused via research against four independent working open-source Media3 players (RiMusic, ViTune, Kreate, RiPlay) + the Wavelet author's own AudioEffect bug-report repo (Pitt van de Witt). CI cannot verify audio audibility — the final proof is the user's device, so this ships WITH a diagnostic log line and a device checklist.

## Two independent root causes

1. **Reverb is an AUXILIARY (send) effect, never routed.** `PresetReverb(_, sessionId).setEnabled(true)` alone is inaudible. It must be sent into the AudioTrack via Media3's public `player.setAuxEffectInfo(AuxEffectInfo(reverb.id, sendLevel))` (→ `AudioTrack.attachAuxEffect` + `setAuxEffectSendLevel`). Requires `MODIFY_AUDIO_SETTINGS`. Per-session reverb IS feasible this way — no session 0, no raw-AudioTrack access needed.
2. **Virtualizer hits a known Android 13/14 platform bug.** It's an insert effect (no aux routing), but on 13/14 it's silent unless `forceVirtualizationMode(VIRTUALIZATION_MODE_BINAURAL)` is called ~50 ms AFTER `enabled = true`. Sourced from the Wavelet author's documented workaround.

**Also wrong (contributing fragility):** effects are built eagerly in `onCreate` before any AudioTrack exists (a documented failure mode, androidx/media #1397), against a session forced via `setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)`. Working apps read the stable `player.audioSessionId` and create/apply effects LAZILY tied to real playback, re-applying on track transitions.

## Fix

**Files:** `AndroidManifest.xml`, `playback/PlaybackService.kt`, tests, a diagnostic log.

### 1. Manifest
Add `<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />` (normal permission, no runtime request needed).

### 2. Session id + timing
- Remove `player.setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)` and its long justification comment.
- Remove the eager `onCreate` effect build and the reliance on the synthetic `onAudioSessionIdChanged`. Keep the `onAudioSessionIdChanged` override (harmless, fires on genuine changes) but drive effects off `player.audioSessionId`.
- Build/apply effects LAZILY on playback start and re-apply on transitions: apply from `onMediaItemTransition` (and `onIsPlayingChanged(true)`), guarding against `C.AUDIO_SESSION_ID_UNSET`. Store desired state; apply when a real session exists.

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
