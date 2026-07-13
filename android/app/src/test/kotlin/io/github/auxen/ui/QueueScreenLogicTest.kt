package io.github.auxen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure logic for [QueueScreen]'s manual drag-reorder, matching
 * [ScreensLogicTest]'s "no VM/Compose runtime" convention. [targetDragIndex]
 * is `internal` in `QueueScreen.kt`.
 *
 * Assumes a uniform row height (every row is the same [io.github.auxen.ui.components.AuxenTrackRow],
 * same content shape, so this holds in practice) -- captured once from the
 * first rendered row rather than tracked per-row through `LazyColumn`'s
 * item recycling, which is what keeps this whole reorder implementation
 * simple enough to not need a third-party reorderable-list dependency
 * (checked the classpath first: none present).
 */
class QueueScreenLogicTest {

    @Test
    fun `no movement returns the same index`() {
        assertEquals(2, targetDragIndex(itemCount = 5, draggedIndex = 2, dragOffsetPx = 0f, itemHeightPx = 100f))
    }

    @Test
    fun `less than half a row does not shift`() {
        assertEquals(2, targetDragIndex(itemCount = 5, draggedIndex = 2, dragOffsetPx = 40f, itemHeightPx = 100f))
        assertEquals(2, targetDragIndex(itemCount = 5, draggedIndex = 2, dragOffsetPx = -40f, itemHeightPx = 100f))
    }

    @Test
    fun `past the halfway point shifts by one row`() {
        assertEquals(3, targetDragIndex(itemCount = 5, draggedIndex = 2, dragOffsetPx = 60f, itemHeightPx = 100f))
        assertEquals(1, targetDragIndex(itemCount = 5, draggedIndex = 2, dragOffsetPx = -60f, itemHeightPx = 100f))
    }

    @Test
    fun `multiple rows of offset shifts by multiple indices`() {
        assertEquals(4, targetDragIndex(itemCount = 5, draggedIndex = 1, dragOffsetPx = 260f, itemHeightPx = 100f))
    }

    @Test
    fun `clamps to the list bounds instead of going out of range`() {
        assertEquals(4, targetDragIndex(itemCount = 5, draggedIndex = 2, dragOffsetPx = 1_000f, itemHeightPx = 100f))
        assertEquals(0, targetDragIndex(itemCount = 5, draggedIndex = 2, dragOffsetPx = -1_000f, itemHeightPx = 100f))
    }

    @Test
    fun `an unmeasured row height (zero) is a no-op guard, not a divide-by-zero`() {
        assertEquals(2, targetDragIndex(itemCount = 5, draggedIndex = 2, dragOffsetPx = 60f, itemHeightPx = 0f))
    }

    @Test
    fun `an empty queue is a no-op guard`() {
        assertEquals(0, targetDragIndex(itemCount = 0, draggedIndex = 0, dragOffsetPx = 60f, itemHeightPx = 100f))
    }
}
