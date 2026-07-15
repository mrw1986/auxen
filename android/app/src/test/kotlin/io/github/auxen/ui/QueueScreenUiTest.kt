package io.github.auxen.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import io.github.auxen.model.QueueEntry
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.ui.testutil.TEST_DEVICE
import io.github.auxen.ui.theme.AuxenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [QueueContent] composition regressions.
 *
 * The live queue is the raw MediaController media-item list with no dedup (a
 * playlist with a duplicate, or "play next" on the same song twice, both
 * produce this). Each occurrence now carries a distinct [QueueEntry.id], so
 * the reorderable `LazyColumn` keys never collide (they once did, when keyed
 * by track identity — `IllegalArgumentException: Key "..." was already used`).
 * These prove composition survives duplicates AND that the playing track is
 * shown only in the pinned header, never also in the scrollable list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class QueueScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `renders a queue with the same track twice without crashing, playing shown once`() {
        val track = Track(title = "Nightcall", artist = "Kavinsky", source = Source.TIDAL, sourceId = "t1")
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                QueueContent(
                    // Same track, two DISTINCT per-occurrence ids.
                    queue = listOf(QueueEntry("uid-1", track), QueueEntry("uid-2", track)),
                    playingIndex = 0,
                    favoriteKeys = emptySet(),
                    onJumpTo = {},
                    onRemove = {},
                    onMove = { _, _ -> },
                    onToggleFavorite = {},
                    onClear = {},
                )
            }
        }
        // Pinned now-playing row (occurrence 0) + ONE up-next row (occurrence 1).
        // Three would mean the playing track leaked back into the list.
        compose.onAllNodesWithText("Nightcall").assertCountEquals(2)
    }

    @Test
    fun `the playing track is pinned and excluded from the scrollable list`() {
        val playing = Track(title = "Playing Song", artist = "A", source = Source.TIDAL, sourceId = "p")
        val other = Track(title = "Other Song", artist = "B", source = Source.TIDAL, sourceId = "o")
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                QueueContent(
                    queue = listOf(QueueEntry("uid-p", playing), QueueEntry("uid-o", other)),
                    playingIndex = 0,
                    favoriteKeys = emptySet(),
                    onJumpTo = {},
                    onRemove = {},
                    onMove = { _, _ -> },
                    onToggleFavorite = {},
                    onClear = {},
                )
            }
        }
        // Playing appears once (pinned only); other appears once (list only).
        compose.onAllNodesWithText("Playing Song").assertCountEquals(1)
        compose.onAllNodesWithText("Other Song").assertCountEquals(1)
    }
}
