package io.github.auxen.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Mirrors [io.github.auxen.ui.SleepTimerSheetTest]'s conventions: semantics assertions, not screenshots. */
@RunWith(RobolectricTestRunner::class)
class FxSectionCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun collapsedHidesContent() {
        compose.setContent {
            AuxenTheme {
                FxSectionCard(
                    title = "Test section",
                    subtitle = null,
                    enabled = true,
                    onEnabledChange = {},
                    expanded = false,
                    onExpandedChange = {},
                ) { Text("SECTION CONTENT") }
            }
        }
        compose.onNodeWithText("SECTION CONTENT").assertDoesNotExist()
    }

    @Test
    fun expandedShowsContent() {
        compose.setContent {
            AuxenTheme {
                FxSectionCard(
                    title = "Test section",
                    subtitle = null,
                    enabled = true,
                    onEnabledChange = {},
                    expanded = true,
                    onExpandedChange = {},
                ) { Text("SECTION CONTENT") }
            }
        }
        compose.onNodeWithText("SECTION CONTENT").assertIsDisplayed()
    }

    @Test
    fun switchToggleFiresOnEnabledChangeOnlyNotOnExpandedChange() {
        val enabledCalls = mutableListOf<Boolean>()
        val expandedCalls = mutableListOf<Boolean>()
        compose.setContent {
            AuxenTheme {
                FxSectionCard(
                    title = "Test section",
                    subtitle = null,
                    enabled = false,
                    onEnabledChange = { enabledCalls.add(it) },
                    expanded = false,
                    onExpandedChange = { expandedCalls.add(it) },
                ) { Text("SECTION CONTENT") }
            }
        }
        compose.onNode(isToggleable()).performClick()
        assertEquals(listOf(true), enabledCalls)
        assertTrue("switch click must not affect expanded state, got $expandedCalls", expandedCalls.isEmpty())
    }

    @Test
    fun chevronClickFiresOnExpandedChangeOnlyNotOnEnabledChange() {
        val enabledCalls = mutableListOf<Boolean>()
        val expandedCalls = mutableListOf<Boolean>()
        compose.setContent {
            AuxenTheme {
                FxSectionCard(
                    title = "Test section",
                    subtitle = null,
                    enabled = false,
                    onEnabledChange = { enabledCalls.add(it) },
                    expanded = false,
                    onExpandedChange = { expandedCalls.add(it) },
                ) { Text("SECTION CONTENT") }
            }
        }
        compose.onNodeWithContentDescription("Expand Test section").performClick()
        assertEquals(listOf(true), expandedCalls)
        assertTrue("chevron click must not affect enabled state, got $enabledCalls", enabledCalls.isEmpty())
    }
}
