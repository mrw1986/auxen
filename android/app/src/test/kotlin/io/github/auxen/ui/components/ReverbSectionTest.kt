package io.github.auxen.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.auxen.dsp.AudioFxController
import io.github.auxen.dsp.ReverbState
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locks in the preset-label <-> `PresetReverb.PRESET_*` int mapping
 * (`NONE`=0 .. `PLATE`=6, see [ReverbState.preset]'s KDoc) -- an
 * off-by-one here would silently apply the wrong platform reverb preset.
 */
@RunWith(RobolectricTestRunner::class)
class ReverbSectionTest {
    @get:Rule val compose = createComposeRule()

    // ReverbSection's onCommit now merges against the LIVE
    // AudioFxController.reverbState.value (final review round, Important
    // #2), so a selection test's assertion on the WHOLE committed object
    // depends on that singleton's state -- reset it so JUnit's (undefined)
    // method/class execution order can't leak state between tests.
    @Before
    fun setUp() {
        AudioFxController.resetForTest()
    }

    @After
    fun tearDown() {
        AudioFxController.resetForTest()
    }

    @Test
    fun currentPresetLabelIsShownOnTheTrigger() {
        compose.setContent {
            AuxenTheme {
                ReverbSection(
                    state = ReverbState(preset = 4),
                    onStateChange = {},
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        compose.onNodeWithText("Medium hall").assertExists()
    }

    /**
     * All seven `PresetReverb.PRESET_*` labels, index-aligned. Every one is
     * exercised below via [assertSelectingPresetCommits] (final review
     * round, Minor #6: the earlier version of this test only covered four
     * of the seven).
     */
    private val allPresetLabels = listOf(
        "None", "Small room", "Medium room", "Large room", "Medium hall", "Large hall", "Plate",
    )

    /**
     * Shared body for the seven per-preset tests below -- `setContent` can
     * only be called once per test, so this can't be a single looping
     * `@Test` (`IllegalStateException: Cannot call setContent twice per
     * test!` when it tried to be one).
     *
     * Starts from a DIFFERENT preset than [targetIndex] (a fixed +1 offset,
     * wrapping) so selecting "None" is exercised the same way as every
     * other preset -- opening the dropdown from a non-matching trigger
     * label, never clicking a node whose text collides with the target item
     * before the menu is open.
     */
    private fun assertSelectingPresetCommits(targetIndex: Int) {
        var committed: ReverbState? = null
        val startIndex = (targetIndex + 1) % allPresetLabels.size
        compose.setContent {
            AuxenTheme {
                ReverbSection(
                    state = ReverbState(preset = startIndex),
                    onStateChange = { committed = it },
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        compose.onNodeWithText(allPresetLabels[startIndex]).performClick() // opens the dropdown
        compose.onNodeWithText(allPresetLabels[targetIndex]).performClick()
        assertEquals(ReverbState(preset = targetIndex), committed)
    }

    @Test fun selectingNoneCommitsPresetZero() = assertSelectingPresetCommits(0)
    @Test fun selectingSmallRoomCommitsPresetOne() = assertSelectingPresetCommits(1)
    @Test fun selectingMediumRoomCommitsPresetTwo() = assertSelectingPresetCommits(2)
    @Test fun selectingLargeRoomCommitsPresetThree() = assertSelectingPresetCommits(3)
    @Test fun selectingMediumHallCommitsPresetFour() = assertSelectingPresetCommits(4)
    @Test fun selectingLargeHallCommitsPresetFive() = assertSelectingPresetCommits(5)
    @Test fun selectingPlateCommitsPresetSix() = assertSelectingPresetCommits(6)
}
