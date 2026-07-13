package io.github.auxen.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.auxen.playback.SleepTimerState
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Mirrors [BrandBlockTest]'s conventions: semantics assertions, not screenshots. */
@RunWith(RobolectricTestRunner::class)
class SleepTimerSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unarmedShowsPresetsAndFinishTrackSwitchNotCountdown() {
        compose.setContent {
            AuxenTheme {
                SleepTimerSheetContent(
                    state = SleepTimerState(),
                    remainingSeconds = null,
                    finishTrackChecked = false,
                    onFinishTrackCheckedChange = {},
                    onSelectDuration = {},
                    onCancelTimer = {},
                )
            }
        }
        compose.onNodeWithText("15m").assertIsDisplayed()
        compose.onNodeWithText("30m").assertIsDisplayed()
        compose.onNodeWithText("45m").assertIsDisplayed()
        compose.onNodeWithText("60m").assertIsDisplayed()
        compose.onNodeWithText("90m").assertIsDisplayed()
        compose.onNodeWithText("Finish last track").assertIsDisplayed()
        compose.onNodeWithText("Cancel timer").assertDoesNotExist()
    }

    // Final review round, merge-blocking #1: five OutlinedButtons in a
    // plain fillMaxWidth() Row don't all fit once the available width is
    // narrow enough -- measured directly (see the fix's commit message):
    // at 200dp the row squeezes to a 20dp-wide "45m" button followed by
    // TWO literally zero-width, zero-position ones ("60m"/"90m" both
    // measure DpRect(0,0,0,0)). Not reproducible at this screen's default
    // TEST_DEVICE width (411dp is comfortably wide enough for five ~58dp
    // Material3 min-width buttons), so this test forces the same overflow
    // deterministically via an explicit narrow width constraint rather
    // than depending on exact button/font metrics at one specific device
    // profile. assertIsDisplayed()/assertExists() above don't catch this
    // (a zero-width node still technically "exists"); only a bounds check
    // does. FlowRow (the fix) wraps overflow to a new line instead of
    // squeezing, so every button keeps its full intrinsic width regardless
    // of how narrow the available space is.
    @Test
    fun everyPresetButtonKeepsNonZeroWidthAndStaysWithinBoundsEvenWhenNarrow() {
        val constrainedWidth = 200.dp
        compose.setContent {
            AuxenTheme {
                Box(modifier = Modifier.width(constrainedWidth)) {
                    SleepTimerSheetContent(
                        state = SleepTimerState(),
                        remainingSeconds = null,
                        finishTrackChecked = false,
                        onFinishTrackCheckedChange = {},
                        onSelectDuration = {},
                        onCancelTimer = {},
                    )
                }
            }
        }
        for (label in listOf("15m", "30m", "45m", "60m", "90m")) {
            val bounds = compose.onNodeWithText(label).getBoundsInRoot()
            val width = bounds.right - bounds.left
            assertTrue("$label button must have non-zero width, got $width", width.value > 0f)
            assertTrue(
                "$label button's right edge (${bounds.right}) must stay within the constrained width ($constrainedWidth)",
                bounds.right <= constrainedWidth,
            )
        }
    }

    @Test
    fun armedShowsLiveCountdownAndCancelNotPresets() {
        compose.setContent {
            AuxenTheme {
                SleepTimerSheetContent(
                    state = SleepTimerState(endElapsedRealtime = 999_999L, finishTrack = false),
                    remainingSeconds = 872.0, // 14:32
                    finishTrackChecked = false,
                    onFinishTrackCheckedChange = {},
                    onSelectDuration = {},
                    onCancelTimer = {},
                )
            }
        }
        compose.onNodeWithText("Pausing in 14:32", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel timer").assertIsDisplayed()
        compose.onNodeWithText("15m").assertDoesNotExist()
    }

    // Final review round, Important #3a: finishTrack no longer shows the
    // static "Pausing after this track" message for the ENTIRE countdown --
    // only once it actually expires (the pendingTrackEnd phase, tested
    // separately below). While still counting down, it gets a live
    // countdown too, with extra context appended.
    @Test
    fun armedWithFinishTrackWhileCountingDownShowsLiveCountdownWithContext() {
        compose.setContent {
            AuxenTheme {
                SleepTimerSheetContent(
                    state = SleepTimerState(endElapsedRealtime = 999_999L, finishTrack = true),
                    remainingSeconds = 190.0, // 3:10
                    finishTrackChecked = true,
                    onFinishTrackCheckedChange = {},
                    onSelectDuration = {},
                    onCancelTimer = {},
                )
            }
        }
        compose.onNodeWithText("Pausing in 3:10 — will finish the playing track").assertIsDisplayed()
        compose.onNodeWithText("Cancel timer").assertIsDisplayed()
        compose.onNodeWithText("Pausing after this track").assertDoesNotExist()
    }

    // Final review round, Important #3b: the pendingTrackEnd phase -- the
    // countdown has expired, finishTrack was armed, and the service is
    // waiting for the current track to end. No countdown left to show; the
    // static message takes over, but Cancel keeps working.
    @Test
    fun pendingTrackEndShowsStaticMessageAndWorkingCancelNotCountdownOrPresets() {
        compose.setContent {
            AuxenTheme {
                SleepTimerSheetContent(
                    state = SleepTimerState(endElapsedRealtime = null, finishTrack = true, pendingTrackEnd = true),
                    remainingSeconds = null,
                    finishTrackChecked = true,
                    onFinishTrackCheckedChange = {},
                    onSelectDuration = {},
                    onCancelTimer = {},
                )
            }
        }
        compose.onNodeWithText("Pausing after this track").assertIsDisplayed()
        compose.onNodeWithText("Cancel timer").assertIsDisplayed()
        compose.onNodeWithText("15m").assertDoesNotExist()
        compose.onNodeWithText("Pausing in", substring = true).assertDoesNotExist()
    }
}
