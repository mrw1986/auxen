package io.github.auxen.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import io.github.auxen.dsp.BassBoostState
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DSP-b Task 4's "UI-scale interactions" sweep item, made concrete: Android's
 * largest standard accessibility font-scale setting is 2.0x. Rather than
 * invent new harness infrastructure, this overrides [LocalDensity] directly
 * (pure Compose, no Robolectric-specific API) the same way a system font-size
 * change would reach composition, and confirms the label/value text a user
 * actually reads is still present and not silently dropped or crashed out at
 * that scale. `FxSectionCard`'s title/subtitle Column carries `Modifier.weight(1f)`
 * against the fixed-size Switch + chevron IconButton specifically so it has
 * room to grow at larger scales instead of clipping -- this is the regression
 * pin for that.
 */
@RunWith(RobolectricTestRunner::class)
class FxSectionsUiScaleTest {
    @get:Rule val compose = createComposeRule()

    private fun setContentAtFontScale(fontScale: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = fontScale)) {
                AuxenTheme { content() }
            }
        }
    }

    @Test
    fun fxSectionCardSurvivesDoubleFontScale() {
        setContentAtFontScale(2.0f) {
            FxSectionCard(
                title = "Bass boost",
                subtitle = "Low-end lift, centered below the fundamentals.",
                enabled = true,
                onEnabledChange = {},
                expanded = true,
                onExpandedChange = {},
            ) {}
        }
        compose.onNodeWithText("Bass boost").assertExists()
        compose.onNodeWithText("Low-end lift, centered below the fundamentals.").assertExists()
    }

    @Test
    fun bassBoostSectionSurvivesDoubleFontScale() {
        setContentAtFontScale(2.0f) {
            BassBoostSection(
                state = BassBoostState(),
                onStateChange = {},
                expanded = true,
                onExpandedChange = {},
            )
        }
        compose.onNodeWithText("Frequency").assertExists()
        compose.onNodeWithText("Gain").assertExists()
        compose.onNodeWithText("80 Hz").assertExists()
        compose.onNodeWithText("6.0 dB").assertExists()
    }
}
