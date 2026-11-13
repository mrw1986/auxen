package io.github.auxen.provider.local

import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tag enrichment — disc-number parsing and the fill-only-null merge. The
 * MediaMetadataRetriever wrapper (LocalTagReader) is untested (I/O, the
 * network-wrapper precedent); this covers the logic it feeds.
 */
class LocalTagEnrichmentTest {

    private fun localFlac(
        genre: String? = null,
        discNumber: Int? = null,
        sampleRateHz: Int? = null,
        bitDepth: Int? = null,
        bitrateKbps: Int? = null,
        trackNumber: Int? = 3,
        year: Int? = 2013,
    ) = Track(
        title = "Song",
        artist = "Artist",
        source = Source.LOCAL,
        sourceId = "42",
        album = "Album",
        format = "FLAC",
        genre = genre,
        discNumber = discNumber,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        bitrateKbps = bitrateKbps,
        trackNumber = trackNumber,
        year = year,
    )

    @Test
    fun `parseDiscNumber takes the disc before the slash`() {
        assertEquals(1, parseDiscNumber("1/2"))
        assertEquals(3, parseDiscNumber("3"))
        assertEquals(2, parseDiscNumber(" 2 / 3 "))
    }

    @Test
    fun `parseDiscNumber is null for blank, zero, or garbage`() {
        assertNull(parseDiscNumber(null))
        assertNull(parseDiscNumber(""))
        assertNull(parseDiscNumber("0"))
        assertNull(parseDiscNumber("N/A"))
    }

    @Test
    fun `mergedWith fills the null fields from the tags`() {
        val enriched = localFlac().mergedWith(
            TrackTags(genre = "Synthwave", discNumber = 1, sampleRateHz = 96_000, bitDepth = 24, bitrateKbps = 900),
        )
        assertEquals("Synthwave", enriched.genre)
        assertEquals(1, enriched.discNumber)
        assertEquals(96_000, enriched.sampleRateHz)
        assertEquals(24, enriched.bitDepth)
        assertEquals(900, enriched.bitrateKbps)
    }

    @Test
    fun `mergedWith with filled sample rate and bit depth labels Hi-Res`() {
        // The core motivation: a 24-bit/96kHz FLAC can never be Hi-Res today
        // (both fields null). After enrichment it is.
        assertEquals("FLAC", localFlac().qualityLabel)
        val enriched = localFlac().mergedWith(TrackTags(sampleRateHz = 96_000, bitDepth = 24))
        assertEquals("Hi-Res", enriched.qualityLabel)
        assertEquals(1000, enriched.qualityScore)
    }

    @Test
    fun `mergedWith never overrides an existing value or the identity fields`() {
        val base = localFlac(genre = "Rock", bitrateKbps = 800, trackNumber = 3, year = 2013)
        val enriched = base.mergedWith(
            TrackTags(genre = "Pop", bitrateKbps = 999, discNumber = 2),
        )
        assertEquals("Rock", enriched.genre)      // existing genre kept
        assertEquals(800, enriched.bitrateKbps)   // existing bitrate kept
        assertEquals(2, enriched.discNumber)      // was null -> filled
        assertEquals(3, enriched.trackNumber)     // identity field untouched
        assertEquals(2013, enriched.year)         // identity field untouched
        assertEquals("Song", enriched.title)
        assertEquals("42", enriched.sourceId)     // id never changes
    }

    @Test
    fun `mergedWith with empty tags is a no-op copy`() {
        val base = localFlac(genre = "Rock")
        assertEquals(base, base.mergedWith(TrackTags()))
    }
}
