package io.github.auxen.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class ArtistRowUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longPressExposesALabelledSemanticsActionWhenHandlerPresent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                ArtistRow(name = "Kavinsky", onClick = {}, onLongPress = {})
            }
        }

        val node = compose.onNodeWithText("Kavinsky").fetchSemanticsNode()
        assertEquals("Show artist actions", node.config[SemanticsActions.OnLongClick].label)
    }

    @Test
    fun noLongClickActionWhenHandlerAbsent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                ArtistRow(name = "Kavinsky", onClick = {})
            }
        }

        val node = compose.onNodeWithText("Kavinsky").fetchSemanticsNode()
        assertEquals(false, node.config.contains(SemanticsActions.OnLongClick))
    }
}
