package io.github.auxen.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.auxen.dsp.ReverbState
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertEquals
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

    @Test
    fun selectingPlateCommitsPresetSix() {
        var committed: ReverbState? = null
        compose.setContent {
            AuxenTheme {
                ReverbSection(
                    state = ReverbState(preset = 0),
                    onStateChange = { committed = it },
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        compose.onNodeWithText("None").performClick() // opens the dropdown menu
        compose.onNodeWithText("Plate").performClick()
        assertEquals(ReverbState(preset = 6), committed)
    }

    @Test
    fun selectingSmallRoomCommitsPresetOne() {
        var committed: ReverbState? = null
        compose.setContent {
            AuxenTheme {
                ReverbSection(
                    state = ReverbState(preset = 0),
                    onStateChange = { committed = it },
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
        compose.onNodeWithText("None").performClick()
        compose.onNodeWithText("Small room").performClick()
        assertEquals(ReverbState(preset = 1), committed)
    }
}
