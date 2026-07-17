package io.github.auxen.provider.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidalMetadataPrimitivesTest {
    private fun file(href: String?, w: Int?) = ArtworkFile(href = href, meta = ArtworkFileMeta(width = w, height = w))

    @Test
    fun `picks the smallest file at or above the target width`() {
        val files = listOf(file("a80", 80), file("a320", 320), file("a640", 640), file("a1280", 1280))
        assertEquals("a640", pickArtworkUrl(files, targetPx = 500))
        assertEquals("a640", pickArtworkUrl(files, targetPx = 640))
    }

    @Test
    fun `falls back to the largest file when none reach the target`() {
        val files = listOf(file("a80", 80), file("a160", 160))
        assertEquals("a160", pickArtworkUrl(files, targetPx = 640))
    }

    @Test
    fun `ignores files without an href`() {
        val files = listOf(file(null, 1280), file("a320", 320))
        assertEquals("a320", pickArtworkUrl(files, targetPx = 300))
    }

    @Test
    fun `returns null for empty files`() {
        assertNull(pickArtworkUrl(emptyList(), targetPx = 640))
    }

    @Test fun `parses ISO 8601 track duration to seconds`() {
        assertEquals(178.0, parseIsoDurationSeconds("PT2M58S")!!, 0.0)
        assertEquals(180.0, parseIsoDurationSeconds("PT3M")!!, 0.0)
    }

    @Test fun `duration is null for null or unparseable input`() {
        assertNull(parseIsoDurationSeconds(null))
        assertNull(parseIsoDurationSeconds("not-a-duration"))
    }
}
