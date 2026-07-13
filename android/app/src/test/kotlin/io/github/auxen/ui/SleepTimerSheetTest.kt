package io.github.auxen.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.auxen.playback.SleepTimerState
import io.github.auxen.ui.theme.AuxenTheme
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
                    remainingText = null,
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

    @Test
    fun armedShowsCountdownAndCancelNotPresets() {
        compose.setContent {
            AuxenTheme {
                SleepTimerSheetContent(
                    state = SleepTimerState(endElapsedRealtime = 999_999L, finishTrack = false),
                    remainingText = "14:32",
                    finishTrackChecked = false,
                    onFinishTrackCheckedChange = {},
                    onSelectDuration = {},
                    onCancelTimer = {},
                )
            }
        }
        compose.onNodeWithText("14:32", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel timer").assertIsDisplayed()
        compose.onNodeWithText("15m").assertDoesNotExist()
    }

    @Test
    fun armedWithFinishTrackShowsFinishTrackWordingNotCountdown() {
        compose.setContent {
            AuxenTheme {
                SleepTimerSheetContent(
                    state = SleepTimerState(endElapsedRealtime = 999_999L, finishTrack = true),
                    remainingText = "3:10",
                    finishTrackChecked = true,
                    onFinishTrackCheckedChange = {},
                    onSelectDuration = {},
                    onCancelTimer = {},
                )
            }
        }
        compose.onNodeWithText("Pausing after this track").assertIsDisplayed()
        compose.onNodeWithText("Cancel timer").assertIsDisplayed()
    }
}
