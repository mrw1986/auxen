package io.github.auxen.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.auxen.model.Source
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
class AlbumCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longPressExposesALabelledSemanticsActionWhenHandlerPresent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumCard(
                    title = "OutRun",
                    artist = "Kavinsky",
                    artUrl = null,
                    source = Source.TIDAL,
                    onClick = {},
                    onLongPress = {},
                )
            }
        }

        val node = compose.onNodeWithText("OutRun").fetchSemanticsNode()
        assertEquals("Show album actions", node.config[SemanticsActions.OnLongClick].label)
    }

    /** No handler -> no long-click action advertised, so no dead affordance. */
    @Test
    fun noLongClickActionWhenHandlerAbsent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumCard(
                    title = "OutRun",
                    artist = "Kavinsky",
                    artUrl = null,
                    source = Source.TIDAL,
                    onClick = {},
                )
            }
        }

        val node = compose.onNodeWithText("OutRun").fetchSemanticsNode()
        assertEquals(false, node.config.contains(SemanticsActions.OnLongClick))
    }
}
