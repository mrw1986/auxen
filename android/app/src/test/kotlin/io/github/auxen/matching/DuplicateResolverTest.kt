package io.github.auxen.matching

import io.github.auxen.model.Source
import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun local(sourceId: String, title: String, format: String? = "MP3") =
    Track(title = title, artist = "Artist", source = Source.LOCAL, sourceId = sourceId, format = format)

private fun tidal(sourceId: String, title: String, format: String? = "FLAC") =
    Track(title = title, artist = "Artist", source = Source.TIDAL, sourceId = sourceId, format = format)

class DuplicateResolverTest {

    @Test
    fun collapsesMatchedPairToPreferredWithGroupId() {
        val merged = DuplicateResolver.merge(
            local = listOf(local("1", "Everlong")),
            tidal = listOf(tidal("t1", "Everlong")),
            priority = SourcePriority.PREFER_QUALITY,
        )
        assertEquals(1, merged.size)
        // Tidal FLAC (500) beats local MP3 (100).
        assertEquals(Source.TIDAL, merged[0].source)
        assertEquals("LOCAL:1", merged[0].matchGroupId)
    }

    @Test
    fun preferLocalKeepsLocalEntry() {
        val merged = DuplicateResolver.merge(
            local = listOf(local("1", "Everlong")),
            tidal = listOf(tidal("t1", "Everlong")),
            priority = SourcePriority.PREFER_LOCAL,
        )
        assertEquals(Source.LOCAL, merged.single().source)
    }

    @Test
    fun unmatchedTracksPassThroughInOrder() {
        val merged = DuplicateResolver.merge(
            local = listOf(local("1", "Everlong"), local("2", "My Hero")),
            tidal = listOf(tidal("t1", "Everlong"), tidal("t2", "Walk")),
            priority = SourcePriority.PREFER_QUALITY,
        )
        // Everlong collapses; My Hero and Walk pass through untouched.
        assertEquals(listOf("Everlong", "My Hero", "Walk"), merged.map { it.title })
        assertNull(merged[1].matchGroupId)
        assertNull(merged[2].matchGroupId)
    }

    @Test
    fun eachTidalTrackConsumedAtMostOnce() {
        val merged = DuplicateResolver.merge(
            local = listOf(local("1", "Everlong"), local("2", "Everlong")),
            tidal = listOf(tidal("t1", "Everlong")),
            priority = SourcePriority.PREFER_TIDAL,
        )
        // First local pairs with the only Tidal copy; second stays local.
        assertEquals(2, merged.size)
        assertEquals(Source.TIDAL, merged[0].source)
        assertEquals(Source.LOCAL, merged[1].source)
    }

    @Test
    fun emptyInputs() {
        assertEquals(0, DuplicateResolver.merge(emptyList(), emptyList(), SourcePriority.PREFER_QUALITY).size)
        assertEquals(
            listOf("Walk"),
            DuplicateResolver.merge(emptyList(), listOf(tidal("t", "Walk")), SourcePriority.PREFER_QUALITY)
                .map { it.title },
        )
    }
}
