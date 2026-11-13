package io.github.auxen.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
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
class ArtistActionSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun allFourEntriesRenderAndInvokeHandlers() {
        var play = 0
        var shuffle = 0
        var next = 0
        var enqueue = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                ArtistActionSheetContent(
                    artistName = "Kavinsky",
                    onPlay = { play++ },
                    onShuffle = { shuffle++ },
                    onPlayNext = { next++ },
                    onEnqueue = { enqueue++ },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Play").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Shuffle").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Play next").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Add to queue").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, play)
        assertEquals(1, shuffle)
        assertEquals(1, next)
        assertEquals(1, enqueue)
    }
}
