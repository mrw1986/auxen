package io.github.auxen.playback.browse

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Needs Robolectric for a working `android.os.Bundle` shadow -- MediaItem.Builder
 * construction (and reading `localConfiguration`) touches Android shadows, same
 * reason as [AuxenBrowseTreeTest] / [io.github.auxen.GraphMapperTest].
 */
@RunWith(RobolectricTestRunner::class)
class BrowseQueueResolverTest {
    private fun leaf(id: String) = MediaItem.Builder().setMediaId(id).build()

    @Test fun `expand returns the sibling list with the tapped item as start index`() {
        val r = BrowseQueueResolver()
        r.remember("parentA", listOf(leaf("TIDAL:1"), leaf("TIDAL:2"), leaf("TIDAL:3")))
        val out = r.expand("TIDAL:3")!!
        assertEquals(listOf("TIDAL:1", "TIDAL:2", "TIDAL:3"), out.mediaItems.map { it.mediaId })
        assertEquals(2, out.startIndex)
    }

    @Test fun `expand of an unknown id is null`() {
        assertNull(BrowseQueueResolver().expand("TIDAL:99"))
    }

    @Test fun `resolve returns the full remembered item`() {
        val r = BrowseQueueResolver()
        val item = MediaItem.Builder().setMediaId("TIDAL:7").setUri("auxen://tidal/7").build()
        r.remember("p", listOf(item))
        assertEquals("auxen://tidal/7", r.resolve("TIDAL:7")?.localConfiguration?.uri.toString())
        assertNull(r.resolve("TIDAL:404"))
    }

    @Test fun `expandParent returns the whole list from the top`() {
        val r = BrowseQueueResolver()
        r.remember("auxen://browse/album/111", listOf(leaf("TIDAL:1"), leaf("TIDAL:2")))
        val out = r.expandParent("auxen://browse/album/111")!!
        assertEquals(listOf("TIDAL:1", "TIDAL:2"), out.mediaItems.map { it.mediaId })
        assertEquals(0, out.startIndex)
        assertNull(r.expandParent("auxen://browse/album/999"))
    }

    @Test fun `re-remembering the same parent replaces its list`() {
        val r = BrowseQueueResolver()
        r.remember("p", listOf(leaf("TIDAL:1"), leaf("TIDAL:2")))
        r.remember("p", listOf(leaf("TIDAL:9")))
        // TIDAL:2 was only in the old list for parent p; but other parents could
        // still hold it. Here nothing else does, so it is gone.
        assertNull(r.expand("TIDAL:2"))
        assertEquals(listOf("TIDAL:9"), r.expand("TIDAL:9")!!.mediaItems.map { it.mediaId })
    }

    @Test fun `a track served under two parents belongs to the most recently remembered`() {
        val r = BrowseQueueResolver()
        r.remember("auxen://browse/album/X", listOf(leaf("TIDAL:7"), leaf("TIDAL:8")))
        r.remember("auxen://browse/favorites", listOf(leaf("TIDAL:1"), leaf("TIDAL:7")))
        // last remember wins ownership of the shared id
        val shared = r.expand("TIDAL:7")!!
        assertEquals(listOf("TIDAL:1", "TIDAL:7"), shared.mediaItems.map { it.mediaId })
        assertEquals(1, shared.startIndex)
        // the album-only sibling still resolves under the album's list
        assertEquals(listOf("TIDAL:7", "TIDAL:8"), r.expand("TIDAL:8")!!.mediaItems.map { it.mediaId })
    }
}
