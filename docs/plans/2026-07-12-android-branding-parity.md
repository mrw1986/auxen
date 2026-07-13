# Android Branding Parity Implementation Plan

> **STATUS: SHIPPED (all 3 tasks + fix round approved; commits e7661dd, 557602e, e0e845d, 83814e7).** This plan is retained as a historical record. Where the text below conflicts with the shipped state, the following review-driven deviations WON and are the current spec:
> - **Dark brand title is gold `#F0C560`** (`AuxenColors.BrandTitleGold`), NOT TextPrimary — per `data/style.css:116` (.sidebar-brand-title), caught in review.
> - **Brand lockup typeface is Josefin Sans 700** (`res/font/josefin_sans.ttf`, `JosefinSans` in Type.kt), NOT Fraunces — per `style.css:112` and `docs/branding/logo-guide.md`; the Fraunces instruction below came from the stale ui-mockup.html.
> - **BrandBlock placement:** compact lockup is the top-bar TITLE on all non-account routes (MainActivity), suppressed on the account route where the full lockup lives in content. The Home LazyColumn brand item described below was shipped then REMOVED in the fix round (duplicate brand chrome vs the app-wide top bar).
> - **Theme detection inside BrandBlock** uses `MaterialTheme.colorScheme.background.luminance() < 0.5f` (agrees with explicit `AuxenTheme(darkTheme=…)` overrides in tests), not `isSystemInDarkTheme()`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Android app the same brand identity as the desktop app: the ox-with-headphones logo (launcher icon, splash screen, in-app brand block, media-notification icon), the amber brand colors, and the pun taglines ("UNORTHODOX AUDIO", "Feed the Ox").

**Architecture:** The desktop logo SVGs in `data/logo/svg/` are potrace-traced two-group vectors (one cream/dark-brown ox path + one amber headphone-accent path, wrapped in a `translate(0,H) scale(0.1,-0.1)` flip transform). They convert mechanically to Android VectorDrawables by copying path data verbatim and reproducing the flip as a `<group>` transform. Launcher = adaptive icon (minSdk 26, no legacy PNGs needed) with a monochrome layer for Android 13+ themed icons. Splash = androidx core-splashscreen with theme-aware logo variant + background (mirrors the desktop's `Adw.StyleManager` logo swap). In-app = a `BrandBlock` composable replicating the desktop sidebar brand row.

**Tech Stack:** Android VectorDrawable, adaptive-icon XML, androidx.core:core-splashscreen 1.0.1, Jetpack Compose, Media3 DefaultMediaNotificationProvider, Roborazzi goldens.

## Global Constraints

- **Brand assets are canonical — never re-draw.** Path data is copied VERBATIM from the source SVGs: `data/logo/svg/auxen-dark-transparent.svg` (cream ox `#fef8e4` + amber `#b68312`, for dark backgrounds) and `data/logo/svg/auxen-light-transparent.svg` (dark-brown ox `#2a1f14` + amber `#b68312`, for light backgrounds). Both use `viewBox="0 0 2048 2048"` with group transform `translate(0.000000,2048.000000) scale(0.100000,-0.100000)` — verify this in the source file before converting; if the light SVG's viewBox differs, use its actual numbers.
- **Exact colors:** brand background `#0C0B0F` (BgDeep); cream `#FEF8E4`; amber accent `#B68312`; brand subtitle gold `#E0B868` (on dark) / `#7A5820` (on light); brand title `#8A6010` (on light; on dark it uses TextPrimary `#F0ECE4`). Light splash/window background `#FAF8F2` (existing light `background` token).
- **Taglines verbatim, exact casing:** `AUXEN` (brand title), `UNORTHODOX AUDIO` (brand subtitle — used by the desktop splash `auxen/views/splash.py:45` and sidebar `auxen/views/sidebar.py:271`), and the flavor line `Feed the ox — play something to get started.` (derived from the mockup's "Feed the Ox" tagline).
- **Theme-aware logo swap:** dark theme shows the cream-ox drawable, light theme the dark-brown-ox drawable — mirroring the desktop's `notify::dark` handler. Selection in Compose uses the same dark/light source `AuxenTheme` uses (check `ui/theme/Theme.kt`; it is `isSystemInDarkTheme()` unless the theme reads an app setting).
- **minSdk 26 / compileSdk 35** — adaptive icon only; do NOT generate density PNG mipmaps.
- **Gate for every task:** `cd android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug` → BUILD SUCCESSFUL, 0 failures. New goldens are recorded with `:app:recordRoborazziDebug` and committed.
- Conventional commits, co-authored: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

### Task 1: Brand vector drawables, adaptive launcher icon, theme-aware splash screen

**Files:**
- Create: `android/app/src/main/res/drawable/auxen_logo.xml` (cream ox, for dark bg)
- Create: `android/app/src/main/res/drawable/auxen_logo_on_light.xml` (dark-brown ox, for light bg)
- Create: `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `android/app/src/main/res/drawable/ic_launcher_monochrome.xml`
- Create: `android/app/src/main/res/drawable/splash_logo.xml`, `android/app/src/main/res/drawable/splash_logo_on_light.xml`
- Create: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `android/app/src/main/res/values/colors.xml`, `android/app/src/main/res/values-night/themes.xml`
- Modify: `android/app/src/main/res/values/themes.xml`, `android/app/src/main/AndroidManifest.xml`, `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`, `android/app/src/main/kotlin/io/github/auxen/ui/MainActivity.kt`
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/BrandAssetsTest.kt`

**Interfaces:**
- Consumes: source SVGs listed in Global Constraints.
- Produces: `R.drawable.auxen_logo`, `R.drawable.auxen_logo_on_light` (used by Task 2's BrandBlock), `R.drawable.ic_launcher_monochrome` path data (Task 3 copies it for the notification icon), `@style/Theme.Auxen.Starting`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.auxen.ui

import androidx.appcompat.content.res.AppCompatResources
import androidx.test.core.app.ApplicationProvider
import io.github.auxen.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Brand drawables must inflate — catches malformed pathData or transform XML. */
@RunWith(RobolectricTestRunner::class)
class BrandAssetsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun brandLogoDrawablesInflate() {
        assertNotNull(AppCompatResources.getDrawable(context, R.drawable.auxen_logo))
        assertNotNull(AppCompatResources.getDrawable(context, R.drawable.auxen_logo_on_light))
        assertNotNull(AppCompatResources.getDrawable(context, R.drawable.ic_launcher_foreground))
        assertNotNull(AppCompatResources.getDrawable(context, R.drawable.ic_launcher_monochrome))
        assertNotNull(AppCompatResources.getDrawable(context, R.drawable.splash_logo))
        assertNotNull(AppCompatResources.getDrawable(context, R.drawable.splash_logo_on_light))
    }
}
```

If `androidx.appcompat` is not already a dependency, use `context.getDrawable(...)` instead — do not add a dependency for the test.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.ui.BrandAssetsTest" 2>&1 | tail -20`
Expected: FAIL — unresolved reference `R.drawable.auxen_logo` (compile error counts as the red step here).

- [ ] **Step 3: Create the two logo VectorDrawables**

`drawable/auxen_logo.xml` — this exact wrapper; `PATHDATA_CREAM` is the entire `d="..."` attribute of the `<path>` inside the `<g ... fill="#fef8e4">` group of `data/logo/svg/auxen-dark-transparent.svg`, copied byte-for-byte (newlines inside the attribute are fine); `PATHDATA_AMBER` likewise from the `fill="#b68312"` group:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="2048"
    android:viewportHeight="2048">
    <!-- Potrace flip: SVG translate(0,2048) scale(0.1,-0.1) -->
    <group
        android:scaleX="0.1"
        android:scaleY="-0.1"
        android:translateY="2048">
        <path android:fillColor="#FEF8E4" android:pathData="PATHDATA_CREAM" />
        <path android:fillColor="#B68312" android:pathData="PATHDATA_AMBER" />
    </group>
</vector>
```

`drawable/auxen_logo_on_light.xml` — identical structure, path data from `auxen-light-transparent.svg`, fills `#2A1F14` (ox) and `#B68312` (amber). The light SVG's traced geometry differs from the dark one — copy its own path data, do not reuse the dark paths with recolored fills.

- [ ] **Step 4: Create launcher foreground + monochrome layers**

`drawable/ic_launcher_foreground.xml` — same content as `auxen_logo.xml` but with an outer group scaling the art into the adaptive-icon safe zone (inner ~66/108 of the canvas):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="2048"
    android:viewportHeight="2048">
    <group android:pivotX="1024" android:pivotY="1024" android:scaleX="0.55" android:scaleY="0.55">
        <group android:scaleX="0.1" android:scaleY="-0.1" android:translateY="2048">
            <path android:fillColor="#FEF8E4" android:pathData="PATHDATA_CREAM" />
            <path android:fillColor="#B68312" android:pathData="PATHDATA_AMBER" />
        </group>
    </group>
</vector>
```

`drawable/ic_launcher_monochrome.xml` — identical to `ic_launcher_foreground.xml` but BOTH paths use `android:fillColor="#FFFFFFFF"` (the system tints monochrome layers; only silhouette matters).

- [ ] **Step 5: Create adaptive icon, background color, splash inset drawables**

`res/values/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#0C0B0F</color>
    <color name="splash_background">#FAF8F2</color>
    <color name="window_background">#FAF8F2</color>
</resources>
```

`res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

`drawable/splash_logo.xml` (inset keeps the art inside the splash icon's circular reveal; fraction insets are supported from API 26):

```xml
<inset xmlns:android="http://schemas.android.com/apk/res/android"
    android:drawable="@drawable/auxen_logo"
    android:inset="20%" />
```

`drawable/splash_logo_on_light.xml` — same, wrapping `@drawable/auxen_logo_on_light`.

- [ ] **Step 6: Splash dependency**

`android/gradle/libs.versions.toml` — add under the existing sections (match file's formatting):

```toml
coreSplashscreen = "1.0.1"
androidx-core-splashscreen = { group = "androidx.core", name = "core-splashscreen", version.ref = "coreSplashscreen" }
```

`android/app/build.gradle.kts` dependencies block:

```kotlin
implementation(libs.androidx.core.splashscreen)
```

- [ ] **Step 7: Theme-aware splash + window background themes**

Replace `res/values/themes.xml` (light — desktop light theme analog):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Auxen" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowDrawsSystemBarBackgrounds">true</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:windowBackground">@color/window_background</item>
    </style>

    <style name="Theme.Auxen.Starting" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/splash_background</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/splash_logo_on_light</item>
        <item name="postSplashScreenTheme">@style/Theme.Auxen</item>
    </style>
</resources>
```

Create `res/values-night/themes.xml` (dark — cream ox on BgDeep, mirroring the desktop splash):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Auxen" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowDrawsSystemBarBackgrounds">true</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:windowBackground">#0C0B0F</item>
    </style>

    <style name="Theme.Auxen.Starting" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">#0C0B0F</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/splash_logo</item>
        <item name="postSplashScreenTheme">@style/Theme.Auxen</item>
    </style>
</resources>
```

- [ ] **Step 8: Manifest + MainActivity wiring**

`AndroidManifest.xml`: on `<application>` add `android:icon="@mipmap/ic_launcher"`; on the launcher `<activity>` set `android:theme="@style/Theme.Auxen.Starting"` (the application-level `android:theme="@style/Theme.Auxen"` stays).

`ui/MainActivity.kt` — first line of `onCreate`, before `super.onCreate(savedInstanceState)`:

```kotlin
installSplashScreen()
```

with import `androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen`.

- [ ] **Step 9: Run test to verify it passes, then the full gate**

Run: `cd android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug`
Expected: BUILD SUCCESSFUL, BrandAssetsTest passes, all existing tests green (currently 112 + new).

Sanity-check the APK actually carries the icon: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -i "ic_launcher\|splash"` — the mipmap + drawables must be listed.

- [ ] **Step 10: Commit**

```bash
git add android/ && git commit -m "feat(android): ox launcher icon, themed splash screen, brand drawables"
```

---

### Task 2: In-app brand block, taglines, brand colors

**Files:**
- Create: `android/app/src/main/kotlin/io/github/auxen/ui/components/BrandBlock.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/theme/Color.kt`
- Modify: `android/app/src/main/kotlin/io/github/auxen/ui/HomeScreen.kt`
- Modify: the Account screen composable (in `ui/Screens.kt` or its own file — locate `account` route content)
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `android/app/src/test/kotlin/io/github/auxen/ui/BrandBlockTest.kt` + updated Roborazzi goldens

**Interfaces:**
- Consumes: `R.drawable.auxen_logo` / `R.drawable.auxen_logo_on_light` (Task 1); existing `AuxenColors`, Fraunces font family from `ui/theme` (check `Type.kt` for the exact `FontFamily` val name).
- Produces: `BrandBlock(compact: Boolean = false, modifier: Modifier = Modifier)` composable.

- [ ] **Step 1: Add brand colors to `AuxenColors`** (append inside the object; values from desktop `data/style.css` `.sidebar-brand-*` rules):

```kotlin
    // Brand block — desktop .sidebar-brand-title/.sidebar-brand-subtitle colors
    val BrandGold = Color(0xFFE0B868)          // subtitle on dark
    val BrandTitleOnLight = Color(0xFF8A6010)  // title on light
    val BrandSubtitleOnLight = Color(0xFF7A5820)
```

- [ ] **Step 2: Add strings**

`res/values/strings.xml`:

```xml
    <string name="brand_name">AUXEN</string>
    <string name="brand_tagline">UNORTHODOX AUDIO</string>
    <string name="empty_home_flavor">Feed the ox — play something to get started.</string>
```

- [ ] **Step 3: Write the failing UI test**

```kotlin
package io.github.auxen.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.auxen.ui.components.BrandBlock
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrandBlockTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullBrandBlockShowsNameAndTagline() {
        compose.setContent { AuxenTheme { BrandBlock() } }
        compose.onNodeWithText("AUXEN").assertIsDisplayed()
        compose.onNodeWithText("UNORTHODOX AUDIO").assertIsDisplayed()
    }

    @Test fun compactBrandBlockOmitsTagline() {
        compose.setContent { AuxenTheme { BrandBlock(compact = true) } }
        compose.onNodeWithText("AUXEN").assertIsDisplayed()
        compose.onNodeWithText("UNORTHODOX AUDIO").assertDoesNotExist()
    }
}
```

(Match the import/pattern conventions of the existing Compose tests in `app/src/test` — e.g. how they wrap `AuxenTheme` — before finalizing.)

- [ ] **Step 4: Run test to verify it fails**

Run: `cd android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.ui.BrandBlockTest" 2>&1 | tail -20`
Expected: FAIL — unresolved reference `BrandBlock`.

- [ ] **Step 5: Implement `BrandBlock`** (`ui/components/BrandBlock.kt`) — desktop sidebar brand row: 52px theme-aware ox logo + "AUXEN" + small-caps-style gold tagline. Adapt the type styles to the actual names in `ui/theme/Type.kt` (Fraunces display family):

```kotlin
package io.github.auxen.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.auxen.R
import io.github.auxen.ui.theme.AuxenColors

/**
 * Desktop sidebar brand row (auxen/views/sidebar.py): theme-aware ox logo,
 * AUXEN title, UNORTHODOX AUDIO subtitle. [compact] drops the subtitle and
 * shrinks the logo for use in screen headers.
 */
@Composable
fun BrandBlock(compact: Boolean = false, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val logo = if (dark) R.drawable.auxen_logo else R.drawable.auxen_logo_on_light
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        Image(
            painterResource(logo),
            contentDescription = null,
            modifier = Modifier.size(if (compact) 28.dp else 52.dp),
        )
        Column {
            Text(
                stringResource(R.string.brand_name),
                fontFamily = /* Fraunces family from Type.kt */,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 16.sp else 22.sp,
                letterSpacing = 1.sp,
                color = if (dark) AuxenColors.TextPrimary else AuxenColors.BrandTitleOnLight,
            )
            if (!compact) {
                Text(
                    stringResource(R.string.brand_tagline),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                    color = if (dark) AuxenColors.BrandGold else AuxenColors.BrandSubtitleOnLight,
                )
            }
        }
    }
}
```

If `AuxenTheme` derives dark/light from something other than `isSystemInDarkTheme()`, use that same source here so the logo always matches the theme.

- [ ] **Step 6: Place it**

- `HomeScreen.kt`: add `BrandBlock(compact = true)` at the very top of the screen's content column, above the greeting text (desktop always shows the brand above nav; the Home header is the phone analog). Keep existing paddings consistent with the column.
- Account screen: add `BrandBlock()` (full) at the top of the screen content, above the Tidal login/account section.
- `HomeScreen.kt` empty state (currently `Text("Nothing here yet", ...)`): add below it `Text(stringResource(R.string.empty_home_flavor), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)`.

- [ ] **Step 7: Tests + goldens**

Run: `cd android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest --tests "io.github.auxen.ui.BrandBlockTest"` → PASS.
Existing Home-screen goldens will now differ — re-record: `./gradlew :app:recordRoborazziDebug`, then `git diff --stat` the golden images and eyeball the changed Home golden PNG (brand row visible, nothing else broken). Add a golden test for `BrandBlock` (both compact and full) following the existing Roborazzi test pattern in the repo.
Then full gate: `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add android/ && git commit -m "feat(android): in-app brand block with ox logo and taglines"
```

---

### Task 3: Media notification small icon

**Files:**
- Create: `android/app/src/main/res/drawable/ic_stat_auxen.xml`
- Modify: `android/app/src/main/kotlin/io/github/auxen/playback/PlaybackService.kt`
- Test: extend `android/app/src/test/kotlin/io/github/auxen/ui/BrandAssetsTest.kt`

**Interfaces:**
- Consumes: monochrome path data from Task 1's `ic_launcher_monochrome.xml` (same silhouette, no safe-zone group).
- Produces: `R.drawable.ic_stat_auxen`; PlaybackService notification shows the ox silhouette instead of Media3's default note glyph.

- [ ] **Step 1: Failing test** — add to `BrandAssetsTest`:

```kotlin
    @Test fun notificationIconInflates() {
        assertNotNull(AppCompatResources.getDrawable(context, R.drawable.ic_stat_auxen))
    }
```

Run it; expected FAIL (unresolved `R.drawable.ic_stat_auxen`).

- [ ] **Step 2: Create `drawable/ic_stat_auxen.xml`** — status-bar icons are alpha-only white, 24dp, full-frame (no safe-zone scaling):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="2048"
    android:viewportHeight="2048">
    <group
        android:scaleX="0.1"
        android:scaleY="-0.1"
        android:translateY="2048">
        <path android:fillColor="#FFFFFFFF" android:pathData="PATHDATA_CREAM" />
        <path android:fillColor="#FFFFFFFF" android:pathData="PATHDATA_AMBER" />
    </group>
</vector>
```

(Path data identical to `auxen_logo.xml`'s two paths.)

- [ ] **Step 3: Wire into PlaybackService** — in `onCreate`, after the session is built (before any notification is posted), matching Media3 1.5.1 API:

```kotlin
setMediaNotificationProvider(
    DefaultMediaNotificationProvider(this).apply {
        setSmallIcon(R.drawable.ic_stat_auxen)
    }
)
```

with imports `androidx.media3.session.DefaultMediaNotificationProvider` and `io.github.auxen.R`.

- [ ] **Step 4: Full gate**

Run: `cd android && JAVA_HOME=~/.jdks/jdk-21.0.11+10 ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests green (existing PlaybackService tests must not regress).

- [ ] **Step 5: Commit**

```bash
git add android/ && git commit -m "feat(android): ox silhouette media notification icon"
```

---

## Non-Goals (this batch)

- The Android 12 system splash cannot render text, so "UNORTHODOX AUDIO" does not appear on the splash itself (icon only) — the tagline lives in the in-app BrandBlock. Documented adaptation, not a gap.
- Full light-mode component contrast pass — already tracked in the M3b.1 polish backlog.
- No changes to the dark color scheme (already verified token-for-token against `data/style.css`).
