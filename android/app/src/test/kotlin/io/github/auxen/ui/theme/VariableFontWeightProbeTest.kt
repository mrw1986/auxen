@file:OptIn(ExperimentalTextApi::class, ExperimentalRoborazziApi::class)

package io.github.auxen.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.auxen.R
import io.github.auxen.ui.testutil.TEST_DEVICE
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Diagnostic probe for the Task 4 review finding: the `typography-details`
 * golden's "Fraunces Bold" line measures LOWER ink density than "Fraunces
 * SemiBold" directly above it — the opposite of what registering
 * `FontWeight.Bold` in `Fraunces` (Type.kt) was supposed to fix.
 *
 * ## Verdict: a Compose/Robolectric Typeface-cache collision, not a bug in
 * this app's code
 * Ruled out first: no label/weight swap in `ComponentScreenshotTest`'s
 * `TypographyDetailsPreview` or in `Type.kt`'s `Fraunces` registration —
 * both read correctly on inspection. Independently re-measured the
 * committed golden PNGs (ink density, same 40/255 threshold this probe
 * uses): light 0.4726→0.4144, dark 0.4887→0.4295 — confirms the inversion
 * is real, not eyeballing error (a prior visual "looks heavier" claim in
 * the original Task 4 report was wrong and is retracted there).
 *
 * Run `VariableFontWeightProbeTest` ALONE (`--tests
 * "io.github.auxen.ui.theme.VariableFontWeightProbeTest"`), all 4 probes
 * below show CORRECT weight differentiation (Bold/wght=900 denser than
 * SemiBold-or-lighter in every case) — proving the harness CAN instance
 * axes, the `Fraunces`/`DmSans` registrations are correct, and
 * `TypographyDetailsPreview` (replicated verbatim in the 3rd test) is
 * correct in isolation.
 *
 * Run the FULL suite (`testDebugUnitTest`, all 138 tests, one shared JVM —
 * Gradle does not fork per class by default), 2 of those same 3 multi-entry
 * probes below FLIP to the wrong direction. The smoking gun: the "wrong"
 * SemiBold measurement in that run was `0.4987474179229113` — bit-for-bit
 * identical (16 significant digits) to Probe 1's own `wght=900` measurement
 * from an unrelated earlier test in the same run. That is not measurement
 * noise; it is a stale `Typeface` instance for the `fraunces.ttf` resource
 * being reused for a later, different `FontVariation.Settings` request —
 * evidently the cache key does not fully account for variationSettings
 * identity across different `Font`/`FontFamily` object instances built from
 * the same `resId`. `DmSans` was NOT affected in this particular run (same
 * values in both the isolated and full-suite runs) — order/timing-dependent,
 * not a fixed Fraunces-only defect, consistent with a genuine cache race
 * rather than a deterministic per-family bug.
 *
 * Because of this, only Probe 1 (single-entry families, self-contained
 * within its own composition, never depends on any other test's prior
 * Typeface state) hard-asserts a weight-direction outcome — verified stable
 * across both isolated and full-suite runs. The multi-entry probes below
 * are diagnostic-only (they log measurements for manual inspection) and
 * only assert that visible ink rendered at all — asserting the
 * weight-direction on those would make the CI gate flaky depending on test
 * execution order, which is worse than not gating on it.
 *
 * This is why `Type.kt`'s `Fraunces` registration was KEPT as-is (proven
 * correct in isolation) rather than reverted, and why the
 * `typography-details` golden's claim of "proves Bold no longer snaps to
 * SemiBold" was retracted in the fix-round report — it's real, but not
 * reliably provable under Robolectric's full-suite shared-JVM execution.
 * On-device verification is unaffected (no shared JVM, no stale cache
 * across app launches).
 *
 * ## Implementation notes (two dead ends hit building this, kept so the
 * next person doesn't re-walk them)
 *  - `androidx.compose.ui.test.captureToImage()` throws
 *    `ComposeTimeoutException` ("Condition still not satisfied after 2000
 *    ms") under this exact Robolectric harness — a genuine incompatibility.
 *    `compose.onRoot().captureRoboImage(...)` (what `ComponentScreenshotTest`
 *    already uses successfully in 130+ passing tests) does not hit this, so
 *    it's used here instead, with `RoborazziOptions(taskType =
 *    RoborazziTaskType.Record)` forcing record mode explicitly regardless of
 *    which ambient Gradle task is running, writing to a scratch temp path
 *    outside `src/test/screenshots/` so this is never a committed golden.
 *  - `ComposeContentTestRule.setContent` may only be called once per `@Test`
 *    — comparing two weights therefore renders BOTH samples in one
 *    composition (a `Column`) and splits the single captured bitmap into
 *    row-bands afterward, rather than capturing twice.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TEST_DEVICE, sdk = [35])
class VariableFontWeightProbeTest {
    @get:Rule val compose = createComposeRule()

    /** Same construction Type.kt's private `variable()` helper uses. */
    private fun singleEntryFamily(resId: Int, weight: FontWeight) = FontFamily(
        Font(
            resId = resId,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        ),
    )

    /**
     * Renders [top] above [bottom] in one `Column` (single `setContent`
     * call), captures the composition once (see class KDoc for why
     * `captureRoboImage` is used over `captureToImage`), splits the result
     * into contiguous ink-containing row bands, and returns the ink
     * fraction (pixels differing from the background by more than 40/255
     * in any channel, over that band's bounding box — the same threshold
     * used to independently re-measure the committed goldens for this
     * report) of the first two bands found, in order.
     */
    private fun twoLineInkFractions(top: @Composable () -> Unit, bottom: @Composable () -> Unit): Pair<Double, Double> {
        compose.setContent {
            AuxenTheme(darkTheme = false) {
                Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        top()
                        bottom()
                    }
                }
            }
        }
        val scratchPath = File.createTempFile("variable-font-probe", ".png").apply { deleteOnExit() }.absolutePath
        compose.onRoot().captureRoboImage(scratchPath, RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bitmap = BitmapFactory.decodeFile(scratchPath)
        val bands = inkBands(bitmap)
        check(bands.size >= 2) { "Expected 2 ink bands, found ${bands.size}" }
        return inkFractionOf(bitmap, bands[0]) to inkFractionOf(bitmap, bands[1])
    }

    private data class Band(val y0: Int, val y1: Int)

    /** Contiguous row ranges containing at least one ink pixel, top to bottom. */
    private fun inkBands(bitmap: Bitmap): List<Band> {
        val bg = bitmap.getPixel(0, 0)
        val bands = mutableListOf<Band>()
        var start = -1
        for (y in 0 until bitmap.height) {
            val rowHasInk = (0 until bitmap.width).any { x -> channelDelta(bitmap.getPixel(x, y), bg) > 40 }
            if (rowHasInk && start == -1) {
                start = y
            } else if (!rowHasInk && start != -1) {
                bands.add(Band(start, y))
                start = -1
            }
        }
        if (start != -1) bands.add(Band(start, bitmap.height))
        return bands
    }

    private fun inkFractionOf(bitmap: Bitmap, band: Band): Double {
        val bg = bitmap.getPixel(0, 0)
        var ink = 0
        var cols = 0
        for (x in 0 until bitmap.width) {
            val colHasInk = (band.y0 until band.y1).any { y -> channelDelta(bitmap.getPixel(x, y), bg) > 40 }
            if (colHasInk) cols++
        }
        if (cols == 0) return 0.0
        for (y in band.y0 until band.y1) {
            for (x in 0 until bitmap.width) {
                if (channelDelta(bitmap.getPixel(x, y), bg) > 40) ink++
            }
        }
        val bbox = cols * (band.y1 - band.y0)
        return ink.toDouble() / bbox
    }

    private fun channelDelta(a: Int, b: Int): Int {
        val dr = kotlin.math.abs(AndroidColor.red(a) - AndroidColor.red(b))
        val dg = kotlin.math.abs(AndroidColor.green(a) - AndroidColor.green(b))
        val db = kotlin.math.abs(AndroidColor.blue(a) - AndroidColor.blue(b))
        return maxOf(dr, dg, db)
    }

    // --- Probe 1 (mandated): single-entry families, no FontMatcher involved.
    // Reliable regression gate -- verified passing in both an isolated run
    // and the full 138-test suite. Self-contained: both renders happen
    // within this one test's own single composition, so it never depends on
    // another test's prior Typeface-cache state the way the probes below do.

    @Test
    fun `Fraunces single-entry wght 300 vs 900 ink density`() {
        val (light, heavy) = twoLineInkFractions(
            top = { Text("Fraunces", fontFamily = singleEntryFamily(R.font.fraunces, FontWeight.W300), fontWeight = FontWeight.W300, fontSize = 32.sp) },
            bottom = { Text("Fraunces", fontFamily = singleEntryFamily(R.font.fraunces, FontWeight.W900), fontWeight = FontWeight.W900, fontSize = 32.sp) },
        )
        println("PROBE fraunces-single wght300=$light wght900=$heavy delta=${heavy - light}")
        // Directional assertion, not just "different": if this harness can
        // truly instance the axis, wght=900 (heaviest available) must be
        // MORE inked than wght=300 (lightest available), not merely unequal.
        assertTrue(
            "Expected wght=900 to be denser than wght=300 (light=$light, heavy=$heavy)",
            heavy > light,
        )
    }

    // --- Probes 2-4 (matcher-path investigation): the app's real multi-entry
    // families. Diagnostic only -- see class KDoc for why these don't
    // hard-assert a weight direction (proven order-dependent across the full
    // suite's shared JVM). Values are logged via println for manual
    // inspection; the only assertion is a basic "did anything render"
    // sanity check.

    @Test
    fun `Fraunces real multi-entry family SemiBold vs Bold ink density (diagnostic)`() {
        val (semiBold, bold) = twoLineInkFractions(
            top = { Text("Fraunces", fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 32.sp) },
            bottom = { Text("Fraunces", fontFamily = Fraunces, fontWeight = FontWeight.Bold, fontSize = 32.sp) },
        )
        println("PROBE fraunces-multi semiBold=$semiBold bold=$bold delta=${bold - semiBold}")
        assertTrue("Expected visible ink in both renders", semiBold > 0.05 && bold > 0.05)
    }

    /**
     * Replicates `ComponentScreenshotTest.TypographyDetailsPreview()` verbatim
     * (same 3 relevant lines, same weights/sizes/spacing) to check whether
     * the golden's inversion reproduces outside `ComponentScreenshotTest`'s
     * 30+-test class. Diagnostic only, see class KDoc.
     */
    @Test
    fun `TypographyDetailsPreview replica ink density (diagnostic)`() {
        compose.setContent {
            AuxenTheme(darkTheme = false) {
                Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nightcall", fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                        Text("Fraunces SemiBold", fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                        Text("Fraunces Bold", fontFamily = Fraunces, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }
            }
        }
        val scratchPath = File.createTempFile("typography-preview-replica", ".png").apply { deleteOnExit() }.absolutePath
        compose.onRoot().captureRoboImage(scratchPath, RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bitmap = BitmapFactory.decodeFile(scratchPath)
        val bands = inkBands(bitmap)
        check(bands.size >= 3) { "Expected 3 ink bands (Nightcall, SemiBold, Bold), found ${bands.size}" }
        val semiBold = inkFractionOf(bitmap, bands[1])
        val bold = inkFractionOf(bitmap, bands[2])
        println("PROBE typography-preview-replica semiBold=$semiBold bold=$bold delta=${bold - semiBold}")
        assertTrue("Expected visible ink in both renders", semiBold > 0.05 && bold > 0.05)
    }

    @Test
    fun `DmSans real multi-entry family Normal vs Bold ink density (diagnostic)`() {
        val (normal, bold) = twoLineInkFractions(
            top = { Text("DmSans", fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 32.sp) },
            bottom = { Text("DmSans", fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 32.sp) },
        )
        println("PROBE dmsans-multi normal=$normal bold=$bold delta=${bold - normal}")
        assertTrue("Expected visible ink in both renders", normal > 0.05 && bold > 0.05)
    }
}
