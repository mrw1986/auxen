package io.github.auxen.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import io.github.auxen.dsp.LimiterState
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The subtitle copy here was deliberately corrected during DSP-a review: the
 * true reduction at threshold is `kneeDb/8` dB (0.75 dB at the default 6 dB
 * knee), not "<0.5 dB" as an earlier draft claimed
 * (docs/plans/2026-07-13-android-dsp-b-ui.md line 9). This test locks that
 * regression in place -- the exact approved subtitle must render, and no
 * text anywhere in the section may contain the retracted "<0.5" claim.
 */
@RunWith(RobolectricTestRunner::class)
class LimiterSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun subtitleUsesTheCorrectedKneeCopyNotTheRetractedClaim() {
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
        compose.onNodeWithText("Soft-knee protection against clipping — on by default.").assertExists()
        assertEquals(0, compose.onAllNodesWithText("<0.5", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("0.5 dB", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun expandedShowsThresholdAndReleaseControls() {
        compose.setContent {
            AuxenTheme {
                LimiterSection(
                    state = LimiterState(thresholdDb = -3.0, releaseMs = 200.0),
                    onStateChange = {},
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        compose.onNodeWithText("Threshold").assertExists()
        compose.onNodeWithText("-3.0 dB").assertExists()
        compose.onNodeWithText("Release").assertExists()
        compose.onNodeWithText("200 ms").assertExists()
    }
}
