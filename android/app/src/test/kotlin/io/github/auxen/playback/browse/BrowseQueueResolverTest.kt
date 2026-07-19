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

    @Test fun `re-remembering the same parent replaces its list`() {
        val r = BrowseQueueResolver()
        r.remember("p", listOf(leaf("TIDAL:1"), leaf("TIDAL:2")))
        r.remember("p", listOf(leaf("TIDAL:9")))
        // TIDAL:2 was only in the old list for parent p; but other parents could
        // still hold it. Here nothing else does, so it is gone.
        assertNull(r.expand("TIDAL:2"))
        assertEquals(listOf("TIDAL:9"), r.expand("TIDAL:9")!!.mediaItems.map { it.mediaId })
    }
}
