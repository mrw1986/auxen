package io.github.auxen.ui

import io.github.auxen.model.QueueEntry
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure logic for [QueueScreen]'s drag-reorder index translation, matching
 * [ScreensLogicTest]'s "no VM/Compose runtime" convention. [queueMoveTarget]
 * is `internal` in `QueueScreen.kt`.
 *
 * The Queue screen pins the playing track and lets the user drag only the
 * *other* entries, so the reorderable list is a FILTERED view while the
 * ViewModel's `moveInQueue` needs REAL controller indices. These tests pin
 * that translation with the playing track in the middle and with duplicate
 * tracks present — exactly where positional/identity index bugs hide.
 */
class QueueScreenLogicTest {

    private fun entry(id: String, title: String = id, sourceId: String = id) =
        QueueEntry(id, Track(title = title, artist = "A", source = Source.TIDAL, sourceId = sourceId))

    // Queue [A, B, C, D, E] with C (index 2) playing → up-next filtered = [A, B, D, E].
    private val a = entry("A")
    private val b = entry("B")
    private val c = entry("C")
    private val d = entry("D")
    private val e = entry("E")
    private val queue = listOf(a, b, c, d, e)

    @Test
    fun `dragging an up-next item up past the playing track maps to a single real move`() {
        // Drag D (real 3) above B in the filtered list: [A, B, D, E] -> [A, D, B, E].
        val reordered = listOf(a, d, b, e)
        assertEquals(3 to 1, queueMoveTarget(queue, reordered, draggedId = "D"))
    }

    @Test
    fun `dragging an up-next item down past the playing track maps to a single real move`() {
        // Drag A (real 0) below D in the filtered list: [A, B, D, E] -> [B, D, A, E].
        val reordered = listOf(b, d, a, e)
        assertEquals(0 to 3, queueMoveTarget(queue, reordered, draggedId = "A"))
    }

    @Test
    fun `dragging an up-next item to the front maps to real index zero`() {
        // Drag E (real 4) to the front: [A, B, D, E] -> [E, A, B, D].
        val reordered = listOf(e, a, b, d)
        assertEquals(4 to 0, queueMoveTarget(queue, reordered, draggedId = "E"))
    }

    @Test
    fun `dragging an up-next item to the end maps to the last real index`() {
        // Drag A (real 0) to the end: [A, B, D, E] -> [B, D, E, A].
        val reordered = listOf(b, d, e, a)
        assertEquals(0 to 4, queueMoveTarget(queue, reordered, draggedId = "A"))
    }

    @Test
    fun `a drop that changed nothing is a no-op`() {
        assertNull(queueMoveTarget(queue, listOf(a, b, d, e), draggedId = "D"))
    }

    @Test
    fun `duplicate tracks resolve to the exact dragged occurrence, not a same-track sibling`() {
        // X1 and X2 are the SAME track (same source:sourceId) but distinct queue ids.
        val x1 = entry("X1", title = "Dup", sourceId = "dup")
        val x2 = entry("X2", title = "Dup", sourceId = "dup")
        val y = entry("Y")
        // X1 (index 0) is playing → filtered up-next = [X2, Y]. Drag Y above X2.
        val dupQueue = listOf(x1, x2, y)
        val reordered = listOf(y, x2)
        // Must map to (2 -> 1): the "before X2" target is X2's real index (1),
        // NOT X1's (0). A favoriteKey-based lookup would wrongly pick X1.
        assertEquals(2 to 1, queueMoveTarget(dupQueue, reordered, draggedId = "Y"))
    }

    @Test
    fun `an unknown dragged id is a no-op guard`() {
        assertNull(queueMoveTarget(queue, listOf(a, b, d, e), draggedId = "nope"))
    }
}
