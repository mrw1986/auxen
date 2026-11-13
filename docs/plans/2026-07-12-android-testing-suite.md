# Auxen Android — Automated Testing Suite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Playwright-grade automated UI testing for the Android app: semantic Compose UI tests and golden-image screenshot tests running on the JVM in every `gradlew test` (Tier 1), plus an emulator-based end-to-end smoke suite on GitHub Actions (Tier 2 — CI only; local emulators crash on this workstation's kernel, verified 2026-07-12).

**Architecture:** Tier 1 builds on the existing Robolectric config: `createComposeRule` drives real composables with semantic finders/assertions, and Roborazzi captures/verifies golden PNGs per screen in light+dark. Goldens live in `android/app/src/test/screenshots/` (committed). Tier 2 adds a `connectedCheck`-free Maestro flow suite under `android/maestro/` executed by a new CI job with a KVM-enabled emulator via `reactivecircus/android-emulator-runner`.

**Tech Stack:** Compose UI Test (BOM-managed `ui-test-junit4`), Robolectric 4.14.1 (present), Roborazzi 1.32.2, Maestro (CI-installed), GitHub Actions.

## Global Constraints

- All Gradle commands from `/home/mrw1986/Projects/auxen/android` prefixed with `JAVA_HOME=~/.jdks/jdk-21.0.11+10`.
- Test baseline at plan start: 69 tests, 0 failures — every task keeps the full suite green and states its expected new total.
- Screenshot tests MUST be deterministic: fixed clock/data inputs, no network, `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, RobolectricDeviceQualifiers for a stable device profile.
- NO local-emulator dependencies anywhere in Tier 1; Tier 2 lives exclusively in CI (`.github/workflows/android.yml` additions must not break the existing build job).
- UI tests exercise composables with FAKE state (direct composable invocation with test data), never the live `Graph`/`MediaController` — no service/network in JVM tests.
- Code style: KDoc, 4-space indent, trailing commas.
- Commit messages: conventional commits with the implementer's co-author trailer given per dispatch.

---

### Task 1: Tier-1 infrastructure — Compose UI test + Roborazzi wiring

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/app/build.gradle.kts`
- Modify: `android/build.gradle.kts` (root — Roborazzi plugin `apply false`)
- Create: `android/app/src/test/kotlin/io/github/auxen/ui/testutil/ComposeTestInfra.kt`
- Test (proof-of-life): `android/app/src/test/kotlin/io/github/auxen/ui/components/BadgesUiTest.kt`

**Interfaces:**
- Consumes: existing theme/components.
- Produces (every later task relies on):
  - Gradle: `testImplementation(libs.androidx.compose.ui.test.junit4)`, `debugImplementation(libs.androidx.compose.ui.test.manifest)`, `testImplementation(libs.roborazzi)`, `testImplementation(libs.roborazzi.compose)`, `testImplementation(libs.roborazzi.junit.rule)`, Roborazzi plugin id `io.github.takahirom.roborazzi` applied to `:app`.
  - `ComposeTestInfra.kt`: `fun auxenScreenshotName(name: String): String` (returns `"src/test/screenshots/$name.png"`) and KDoc'd conventions comment (device qualifier constant `TEST_DEVICE = RobolectricDeviceQualifiers.Pixel7` — a stable non-foldable profile for goldens).

- [ ] **Step 1: Version catalog additions**

`[versions]`:

```toml
roborazzi = "1.32.2"
```

`[libraries]`:

```toml
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
roborazzi = { group = "io.github.takahirom.roborazzi", name = "roborazzi", version.ref = "roborazzi" }
roborazzi-compose = { group = "io.github.takahirom.roborazzi", name = "roborazzi-compose", version.ref = "roborazzi" }
roborazzi-junit-rule = { group = "io.github.takahirom.roborazzi", name = "roborazzi-junit-rule", version.ref = "roborazzi" }
```

`[plugins]`:

```toml
roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
```

Root `android/build.gradle.kts` plugins block: `alias(libs.plugins.roborazzi) apply false`. App module plugins block: `alias(libs.plugins.roborazzi)`. App dependencies: the five `testImplementation`/`debugImplementation` lines above.

If Roborazzi 1.32.2 does not resolve, use the nearest available 1.3x release and note it in the report — do not drop below 1.30.

- [ ] **Step 2: Write ComposeTestInfra.kt**

```kotlin
package io.github.auxen.ui.testutil

import org.robolectric.RuntimeEnvironment

/**
 * Conventions for JVM Compose tests:
 *  - Robolectric qualifiers pin a stable Pixel 7 profile so goldens don't
 *    drift across contributors' machines;
 *  - goldens live in app/src/test/screenshots (committed);
 *  - screenshot tests run @GraphicsMode(NATIVE); behavior tests don't care.
 */
const val TEST_DEVICE = "w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav"

fun auxenScreenshotName(name: String): String = "src/test/screenshots/$name.png"
```

- [ ] **Step 3: Proof-of-life test (TDD for the infra itself)**

Create `BadgesUiTest.kt`:

```kotlin
package io.github.auxen.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import io.github.auxen.model.Source
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.auxen.ui.testutil.TEST_DEVICE

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class BadgesUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sourceBadgeRendersSourceName() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                SourceBadge(Source.TIDAL)
            }
        }
        compose.onNodeWithText("TIDAL").assertIsDisplayed()
    }

    @Test
    fun qualityBadgeHidesUnknown() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                QualityBadge("Unknown")
            }
        }
        compose.onNodeWithText("UNKNOWN").assertDoesNotExist()
    }
}
```

Run: `JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.ui.components.BadgesUiTest"`
Expected: first run may fail on missing deps/config — iterate until PASS. Then full suite: **71 tests**, 0 failures.

- [ ] **Step 4: Commit**

```
test(android): Compose UI test + Roborazzi infrastructure
```

---

### Task 2: Behavior tests — canonical row, action sheet, mini player

**Files:**
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/AuxenTrackRowUiTest.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/components/TrackActionSheetUiTest.kt`

**Interfaces:** consumes Task 1 infra; components under test invoked directly with fake `Track` data and recording lambdas.

- [ ] **Step 1: AuxenTrackRowUiTest**

Cover (one `@Test` each, arrange with a `track(...)` helper like `MatchingTest`'s):
1. `tapRowInvokesOnPlay` — `performClick` on the row → `onPlay` lambda recorded once.
2. `longPressInvokesOnLongPress` — `performTouchInput { longClick() }` → recorded.
3. `heartTogglesDescription` — `isFavorite=false` shows contentDescription "Add to favorites"; recompose with true shows "Remove from favorites" (use `onNodeWithContentDescription`).
4. `explicitBadgeOnlyWhenExplicit` — "E" text exists iff `track.explicit`.
5. `tidalRowShowsQualityBadge_localDoesNot` — TIDAL+FLAC shows "FLAC"; LOCAL hides it.
6. `durationFormatted` — durationSeconds 227.0 renders "3:47".

Complete test file with imports mirroring Task 1's pattern; recording lambdas as `var played = 0` counters asserted after interaction.

- [ ] **Step 2: TrackActionSheetUiTest**

Cover:
1. `playNextActionInvokesAndDismisses` — tap "Play next" → `onPlayNext` recorded AND `onDismiss` recorded.
2. `addToPlaylistShowsPlaylistPage` — tap "Add to playlist" → playlist names from the fake list appear; root actions ("Play next") gone.
3. `newPlaylistDialogCreates` — navigate to playlists page, tap "New playlist…", type "Road Trip" into the text field (`performTextInput`), tap "Create" → `onCreatePlaylist("Road Trip")` recorded.
4. `favoriteLabelReflectsState` — isFavorite=true shows "Remove from favorites" action label.

Note `ModalBottomSheet` under Robolectric: use `compose.mainClock`/`waitForIdle` after `setContent`; if sheet animation flakes, set `compose.mainClock.autoAdvance = true` (default) and assert with `onNodeWithText(...).assertExists()` rather than `assertIsDisplayed` where needed — document any such concession in the test's KDoc.

- [ ] **Step 3: Run + commit**

Full suite expected: **81 tests**, 0 failures.

```
test(android): behavior tests for track row and action sheet
```

---

### Task 3: Screen-level state tests — Library, Search, Collection

**Files:**
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/ScreensLogicTest.kt`

**Interfaces:** consumes grouping/sort pure functions + `greetingForHour` — this task rounds out PURE-LOGIC coverage that the screens rely on (screen composables themselves take live PlayerViewModel and are covered by goldens in Task 4 + Tier 2 flows; do NOT attempt to fake PlayerViewModel — it's concrete and Media3-coupled, a refactor out of scope here).

- [ ] **Step 1: Write the tests**

1. `greetingBoundaries` — hours 4→evening, 5→morning, 11→morning, 12→afternoon, 17→afternoon, 18→evening (closes an M3a review minor).
2. `sortOptionsPerTabMatchDesktop` — replicate `sortOptionsFor` expectations via the public `LibrarySort` sets if exposed; if `sortOptionsFor` is private, assert through `LibrarySort.entries` membership per the plan's documented sets and mark the private-visibility limitation in KDoc (do not change visibility for testability in this task).
3. `searchTypeFilterSemantics` — the Search screen filters client-side by `Source`; replicate: given a mixed list, "Local"→only LOCAL, "Tidal"→only TIDAL, "All"→unchanged (pure list filtering assertions mirroring the composable's logic).

Full suite expected: **84 tests** (3 new), 0 failures.

- [ ] **Step 2: Commit**

```
test(android): screen logic coverage — greeting, sort options, type filters
```

---

### Task 4: Roborazzi golden screenshots — components in light + dark

**Files:**
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/screenshots/ComponentScreenshotTest.kt`
- Create (generated, committed): `android/app/src/test/screenshots/*.png`
- Modify: `android/app/build.gradle.kts` (roborazzi output dir config if needed)

**Interfaces:** consumes Tasks 1 components; produces the golden baseline later work verifies against.

- [ ] **Step 1: Write the screenshot tests**

`ComponentScreenshotTest.kt`: `@GraphicsMode(GraphicsMode.Mode.NATIVE)` + `@Config(qualifiers = TEST_DEVICE, sdk = [35])`. One test per (surface × theme):

```kotlin
    @Test
    fun trackRow_dark() = captureComponent("track-row-dark", darkTheme = true) {
        AuxenTrackRow(
            track = sampleTidalTrack,
            isFavorite = true,
            onPlay = {},
            onToggleFavorite = {},
        )
    }
```

with a private `captureComponent(name, darkTheme, content)` helper wrapping `compose.setContent { AuxenTheme(darkTheme) { Surface { content() } } }` then `compose.onRoot().captureRoboImage(auxenScreenshotName(name))`.

Surfaces (×2 themes = 12 goldens): `AuxenTrackRow` (tidal/favorite), `AuxenTrackRow` (local/explicit), `AlbumCard`, `SourceBadge`+`QualityBadge` row, `SectionHeader` with action, `TrackActionSheet` root page (sheet content composable only if the ModalBottomSheet wrapper can't capture — note which in KDoc).

Deterministic inputs: fixed sample tracks (no network art — `albumArtUrl = null` renders the placeholder deterministically).

- [ ] **Step 2: Record goldens, verify, commit**

Record: `JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:recordRoborazziDebug`
Verify (must pass cleanly right after recording): `JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:verifyRoborazziDebug`
Then full suite: **~96 tests** (12 new), 0 failures. Commit goldens + tests:

```
test(android): Roborazzi golden screenshots for core components
```

---

### Task 5: CI wiring for Tier 1 + Tier 2 emulator smoke job

**Files:**
- Modify: `.github/workflows/android.yml`
- Create: `android/maestro/smoke.yaml`

**Interfaces:** consumes everything prior; produces the CI gates.

- [ ] **Step 1: Tier-1 CI**

The existing `build` job's `test` step already runs the new JVM tests (including `verifyRoborazziDebug` IF wired into `check` — wire it explicitly: add a step `./gradlew verifyRoborazziDebug` after unit tests). Upload the Roborazzi diff report dir (`app/build/outputs/roborazzi/`) as an artifact `roborazzi-diffs` on failure (`if: failure()`).

- [ ] **Step 2: Maestro smoke flow**

`android/maestro/smoke.yaml`:

```yaml
appId: io.github.auxen
---
- launchApp
- assertVisible: "Auxen"
- tapOn: "Library"
- tapOn: "Search"
- tapOn: "Collection"
- tapOn: "Home"
- assertVisible:
    text: "Good .*"
    # regex greeting: morning/afternoon/evening
```

(Adjust selector syntax to current Maestro — the implementer verifies against Maestro docs; keep flow minimal: app launches, all four tabs navigate, no crashes.)

- [ ] **Step 3: Tier-2 CI job**

New job `emulator-smoke` in `android.yml` (needs: build):

```yaml
  emulator-smoke:
    runs-on: ubuntu-latest
    needs: build
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
      - name: Build debug APK
        run: cd android && ./gradlew :app:assembleDebug
      - name: Install Maestro
        run: curl -fsSL https://get.maestro.mobile.dev | bash
      - name: Run smoke flow on emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 35
          arch: x86_64
          target: google_apis
          disable-animations: true
          script: |
            adb install android/app/build/outputs/apk/debug/app-debug.apk
            export PATH="$PATH:$HOME/.maestro/bin"
            maestro test android/maestro/smoke.yaml
```

Marked `continue-on-error: true` for the FIRST landing (flaky-emulator insurance); a follow-up removes it once two consecutive runs pass.

- [ ] **Step 4: Verify + commit + push**

Local: full suite still green. Push and confirm both CI jobs go green (watch the run).

```
ci(android): Roborazzi verification + emulator smoke tests
```

---

### Task 6: Regression tests for the 2026-07-12 device bugs

**Files:** determined by the (by-then confirmed) root causes of the Tidal HTTP 400 and instant-playback-stop bugs.

This task is intentionally a placeholder gate: it MUST be re-specified by the controller once both root causes are confirmed, then executed like any other task — at minimum: (a) a `TidalAuth` test asserting a clear, actionable failure when `BuildConfig` credentials are blank (no raw HTTP 400 surfaced to the user), and (b) a unit test pinning whatever the playback root cause turns out to be (e.g., `ParametricEqProcessor` handling of non-16-bit/float encodings via graceful bypass rather than `UnhandledAudioFormatException`). The controller fills in exact code at dispatch time.
