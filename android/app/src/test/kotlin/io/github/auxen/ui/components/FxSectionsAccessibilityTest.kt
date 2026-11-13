package io.github.auxen.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.auxen.dsp.BalanceState
import io.github.auxen.dsp.BassBoostState
import io.github.auxen.dsp.LimiterState
import io.github.auxen.dsp.ReplayGainState
import io.github.auxen.dsp.VirtualizerState
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Switch inside [FxSectionCard] and every continuous [androidx.compose.material3.Slider]
 * across the per-effect sections has no visible-text label of its own that
 * TalkBack could merge into an accessible name (unlike a `Button`, whose own
 * `Text` content becomes its name automatically) -- with up to seven
 * switches and a dozen-plus sliders on one screen, an unlabeled one is
 * indistinguishable from any other. Every case here just needs
 * [androidx.compose.ui.test.onNodeWithContentDescription] to find something,
 * not to assert on styling -- that's the actual accessibility gap DSP-b
 * Task 4's polish sweep asked for.
 */
@RunWith(RobolectricTestRunner::class)
class FxSectionsAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fxSectionCardSwitchHasContentDescriptionMatchingTitle() {
        compose.setContent {
            AuxenTheme {
                FxSectionCard(
                    title = "Test section",
                    subtitle = null,
                    enabled = true,
                    onEnabledChange = {},
                    expanded = false,
                    onExpandedChange = {},
                ) {}
            }
        }
        compose.onNodeWithContentDescription("Test section").assertExists()
    }

    @Test
    fun bassBoostSlidersHaveLabelContentDescriptions() {
        compose.setContent {
            AuxenTheme {
                BassBoostSection(
                    state = BassBoostState(),
                    onStateChange = {},
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Frequency").assertExists()
        compose.onNodeWithContentDescription("Gain").assertExists()
    }

    @Test
    fun balanceSliderHasContentDescription() {
        compose.setContent {
            AuxenTheme {
                BalanceSection(
                    state = BalanceState(),
                    onStateChange = {},
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        // Distinct from the section's own switch, which is separately
        // labeled "Balance" (its title) -- the two must not collide.
        compose.onNodeWithContentDescription("Balance amount").assertExists()
    }

    @Test
    fun limiterSlidersHaveLabelContentDescriptions() {
        compose.setContent {
            AuxenTheme {
                LimiterSection(
                    state = LimiterState(),
                    onStateChange = {},
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Threshold").assertExists()
        compose.onNodeWithContentDescription("Release").assertExists()
    }

    @Test
    fun virtualizerSliderHasContentDescription() {
        compose.setContent {
            AuxenTheme {
                VirtualizerSection(
                    state = VirtualizerState(),
                    onStateChange = {},
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Strength").assertExists()
    }

    @Test
    fun volumeNormalizationSlidersHaveLabelContentDescriptions() {
        compose.setContent {
            AuxenTheme {
                VolumeNormalizationSection(
                    state = ReplayGainState(),
                    onStateChange = {},
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Preamp").assertExists()
        compose.onNodeWithContentDescription("Fallback gain").assertExists()
    }
}
