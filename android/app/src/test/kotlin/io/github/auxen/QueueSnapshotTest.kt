package io.github.auxen

import androidx.media3.common.util.UnstableApi
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [Graph.queueEntriesFrom] carries a stable, per-occurrence id for every queue
 * item — the prerequisite for the Queue screen's collision-free reorder keys.
 * Runs under Robolectric because it round-trips real `MediaItem`/`Bundle`
 * extras built by [Graph.mediaItemFor].
 *
 * These functions touch no `Graph` `lateinit` state (only the eagerly-created
 * `json` and `UUID`), so there is nothing to reset between methods — order
 * independence holds regardless of JVM method ordering.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class QueueSnapshotTest {

    private val track = Track(title = "Nightcall", artist = "Kavinsky", source = Source.TIDAL, sourceId = "t1")

    @Test
    fun `each occurrence of the same track gets a distinct queue id`() {
        // Two enqueues of the SAME track — as "play next" twice would produce.
        val items = listOf(Graph.mediaItemFor(track), Graph.mediaItemFor(track))

        val entries = Graph.queueEntriesFrom(items)

        assertEquals(2, entries.size)
        assertEquals(track, entries[0].track)
        assertEquals(track, entries[1].track)
        assertNotEquals("duplicate occurrences must have unique ids", entries[0].id, entries[1].id)
    }

    @Test
    fun `the queue id survives a round-trip through the media item's metadata`() {
        val item = Graph.mediaItemFor(track)
        val stampedId = item.mediaMetadata.extras?.getString(Graph.QUEUE_UID_KEY)

        val entry = Graph.queueEntriesFrom(listOf(item)).single()

        assertEquals(stampedId, entry.id)
    }
}
