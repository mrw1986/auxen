package io.github.auxen.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * Queue-review fix round: [QueueContent] crash regression for a queue
 * containing the same track more than once. The live queue is the raw
 * MediaController media-item list with no dedup (a playlist with a
 * duplicate, or "play next" on the same song twice, both produce this) --
 * `ReorderableQueueList`'s `LazyColumn` used to key its items by track
 * identity (`track.favoriteKey()`), which collides for duplicates and
 * crashes Compose with `IllegalArgumentException: Key "..." was already
 * used`. Positional keying fixes it; this proves composition survives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TEST_DEVICE)
class QueueScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `renders a queue with the same track twice without crashing`() {
        val duplicate = Track(title = "Nightcall", artist = "Kavinsky", source = Source.TIDAL, sourceId = "t1")
        compose.setContent {
            AuxenTheme(darkTheme = true) {
                QueueContent(
                    queue = listOf(duplicate, duplicate),
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
        // Pinned "Now playing" row + two rows in the reorderable list below it.
        compose.onAllNodesWithText("Nightcall").assertCountEquals(3)
    }
}
