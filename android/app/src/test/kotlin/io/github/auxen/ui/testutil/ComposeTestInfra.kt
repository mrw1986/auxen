package io.github.auxen.ui.testutil

/**
 * Conventions for JVM Compose tests (Robolectric + Roborazzi).
 *
 * ## Device qualifier
 * [TEST_DEVICE] pins a stable **Pixel 7** profile (a non-foldable, non-round,
 * 420dpi phone) so screenshot goldens don't drift across contributors'
 * machines. It is the fully expanded form of
 * `RobolectricDeviceQualifiers.Pixel7`, inlined as a compile-time `const`
 * because `@Config(qualifiers = ...)` annotation arguments must be constants.
 * Apply it with `@Config(qualifiers = TEST_DEVICE)` on the test class.
 *
 * ## Goldens
 * Screenshot goldens live in `app/src/test/screenshots` (committed) and are
 * addressed via [auxenScreenshotName].
 *
 * ## Graphics mode
 * Screenshot-capturing tests must run under `@GraphicsMode(GraphicsMode.Mode.NATIVE)`
 * so Robolectric renders real pixels rather than a no-op canvas. Pure behavior
 * tests (assertions on the semantics tree, like the proof-of-life
 * `BadgesUiTest`) do not need it — `createComposeRule()` drives the semantics
 * tree without touching the graphics pipeline.
 *
 * ## Config discoveries for later tasks
 *  - `android.testOptions.unitTests.isIncludeAndroidResources = true` is
 *    already set in `app/build.gradle.kts`; Compose tests require it.
 *  - The Compose BOM is applied to the test classpath
 *    (`testImplementation(platform(libs.androidx.compose.bom))`) so the
 *    unversioned `ui-test-junit4` / `ui-test-manifest` artifacts resolve.
 *  - No custom `@Config(sdk = ...)` is needed; the module's compileSdk (35)
 *    is used by default.
 */
const val TEST_DEVICE = "w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav"

/** Golden path for a screenshot named [name], relative to the module root. */
fun auxenScreenshotName(name: String): String = "src/test/screenshots/$name.png"
