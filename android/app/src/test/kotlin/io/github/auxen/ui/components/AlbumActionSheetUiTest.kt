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
class AlbumActionSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun playAndQueueEntriesInvokeTheirHandlers() {
        var play = 0
        var next = 0
        var enqueue = 0
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumActionSheetContent(
                    albumTitle = "OutRun",
                    albumArtist = "Kavinsky",
                    playlists = emptyList(),
                    showPlaylists = false,
                    onPlay = { play++ },
                    onShuffle = {},
                    onPlayNext = { next++ },
                    onEnqueue = { enqueue++ },
                    onAddToPlaylist = {},
                    onShowPlaylists = {},
                    onNewPlaylist = {},
                    onGoToArtist = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Play album").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Play album next").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Add album to queue").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, play)
        assertEquals(1, next)
        assertEquals(1, enqueue)
    }

    @Test
    fun goToArtistHiddenWhenHandlerAbsent() {
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                AlbumActionSheetContent(
                    albumTitle = "OutRun",
                    albumArtist = "Kavinsky",
                    playlists = emptyList(),
                    showPlaylists = false,
                    onPlay = {},
                    onShuffle = {},
                    onPlayNext = {},
                    onEnqueue = {},
                    onAddToPlaylist = {},
                    onShowPlaylists = {},
                    onNewPlaylist = {},
                    onGoToArtist = null,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Add album to playlist").assertExists()
        compose.onNodeWithText("Go to artist").assertDoesNotExist()
    }
}
