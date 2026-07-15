package io.github.auxen.ui.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.auxen.model.Source
import io.github.auxen.ui.NowPlayingContent
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.testutil.auxenScreenshotName
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens + layout-visibility regression for [NowPlayingContent] — the
 * stateless body [io.github.auxen.ui.NowPlayingScreen] delegates to (same
 * ViewModel-free split as `QueueContent`/`SettingsContent`, so no live
 * `PlayerViewModel`/`MediaController` is needed here).
 *
 * ## The bug this pins
 * The pre-fix screen put the album art in a `fillMaxWidth().aspectRatio(1f)`
 * inside a non-scrollable `Column`. On a large / near-square screen the square
 * art consumed the whole height and pushed the title, badges, seek row and the
 * entire transport row off the bottom — the user saw "just image + timebar".
 * [nowPlayingControlsVisible_nearSquare] renders the screen at a near-square
 * 840×800dp window and asserts every transport control is displayed; it fails
 * against the old always-square-art layout and passes once the art is
 * height-constrained (and, on wide/near-square screens, moved beside the
 * controls in a two-pane layout).
 *
 * ## Determinism
 *  - `@GraphicsMode(NATIVE)` + `@Config(qualifiers, sdk = 35)` — same pins as
 *    `ComponentScreenshotTest`.
 *  - `artworkModel = null`, so `AsyncImage` renders its transparent placeholder
 *    (the blurred backdrop path is skipped for a null model) and the art card
 *    shows the deterministic `surfaceVariant` fill — no network, no bitmap.
 *  - Short title/artist that fit their column, so `basicMarquee` never scrolls
 *    (it only animates on overflow) and the frame is stable.
 *  - No `ModalBottomSheet`/`DropdownMenu` here (the sleep-timer sheet lives in
 *    the stateful `NowPlayingScreen`, not the content), so nothing has an
 *    animated entrance that fails to settle under Robolectric.
 *
 * ## Fraunces AA-drift trap
 * The only text here uses `titleMedium` (DM Sans), `bodyLarge`/`bodySmall`
 * (DM Sans) and `FontFamily.Monospace` — none request a `Fraunces` variable
 * weight, so these goldens are outside the shared-JVM Fraunces-typeface-cache
 * hazard documented on `ComponentScreenshotTest`.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TEST_DEVICE, sdk = [35])
class NowPlayingScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** A fully-populated, playing Tidal track — favorited, Hi-Res. */
    @Composable
    private fun PopulatedContent() {
        NowPlayingContent(
            title = "Nightcall",
            artist = "Kavinsky",
            artworkModel = null,
            source = Source.TIDAL,
            qualityLabel = "Hi-Res",
            isFavorite = true,
            positionMs = 96_000,
            durationMs = 258_000,
            isPlaying = true,
            shuffleEnabled = true,
            repeatMode = Player.REPEAT_MODE_ALL,
            sleepTimerArmed = true,
            onBack = {},
            onOpenQueue = {},
            onOpenSleepTimer = {},
            onToggleFavorite = {},
            onTogglePlayPause = {},
            onSkipPrevious = {},
            onSkipNext = {},
            onToggleShuffle = {},
            onCycleRepeat = {},
            onSeek = {},
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun capture(name: String, darkTheme: Boolean) {
        compose.setContent {
            AuxenTheme(darkTheme = darkTheme) {
                Surface { PopulatedContent() }
            }
        }
        compose.onRoot().captureRoboImage(auxenScreenshotName(name))
    }

    // --- Phone (TEST_DEVICE, 411×914 portrait) — the stacked layout ---

    @Test
    fun nowPlayingPhone_light() = capture("now-playing-phone-light", darkTheme = false)

    @Test
    fun nowPlayingPhone_dark() = capture("now-playing-phone-dark", darkTheme = true)

    // --- Near-square tablet (840×800) — the two-pane layout ---

    @Test
    @Config(qualifiers = "w840dp-h800dp-normal-notlong-notround-any-420dpi-keyshidden-nonav")
    fun nowPlayingTablet_dark() = capture("now-playing-tablet-dark", darkTheme = true)

    /**
     * The actual bug fix, asserted structurally: at a near-square 840×800dp
     * window, every transport control is laid out within the viewport. Fails
     * against the old full-height-square-art layout.
     */
    @Test
    @Config(qualifiers = "w840dp-h800dp-normal-notlong-notround-any-420dpi-keyshidden-nonav")
    fun nowPlayingControlsVisible_nearSquare() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                Surface { PopulatedContent() }
            }
        }
        // State-aware labels (polish item 13): PopulatedContent is playing with
        // shuffle on and repeat-all, so the toggle handles are "Shuffle on" /
        // "Repeat all" rather than the old static "Shuffle" / "Repeat".
        listOf("Shuffle on", "Previous", "Pause", "Next", "Repeat all").forEach { desc ->
            compose.onNodeWithContentDescription(desc).assertIsDisplayed()
        }
    }

    /**
     * Polish item 13: the shuffle/repeat/favorite/sleep-timer controls convey
     * their on/off state to screen readers via a state-aware contentDescription
     * (not color/tint alone). PopulatedContent is favorited, shuffle-on,
     * repeat-all, timer-armed, so every "on" label must be present.
     */
    @Test
    fun nowPlayingControls_stateAwareA11yLabels() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                Surface { PopulatedContent() }
            }
        }
        listOf(
            "Shuffle on",
            "Repeat all",
            "Remove from favorites",
            "Sleep timer (armed)",
        ).forEach { desc ->
            compose.onNodeWithContentDescription(desc).assertExists()
        }
    }
}
