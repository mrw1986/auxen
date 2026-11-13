package io.github.auxen.matching

import io.github.auxen.model.Source
import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun track(
    title: String,
    artist: String,
    source: Source = Source.LOCAL,
    sourceId: String = "x",
    format: String? = null,
    bitDepth: Int? = null,
    sampleRateHz: Int? = null,
    bitrateKbps: Int? = null,
) = Track(
    title = title,
    artist = artist,
    source = source,
    sourceId = sourceId,
    format = format,
    bitDepth = bitDepth,
    sampleRateHz = sampleRateHz,
    bitrateKbps = bitrateKbps,
)

class NormalizeTest {
    @Test
    fun stripsAndLowercases() = assertEquals("hello world", normalizeForMatching("  Hello World  "))

    @Test
    fun handlesFeatVariations() {
        assertEquals("song ft artist", normalizeForMatching("Song feat. Artist"))
        assertEquals("song ft artist", normalizeForMatching("Song feat Artist"))
        assertEquals("song ft artist", normalizeForMatching("Song featuring Artist"))
        assertEquals("song ft artist", normalizeForMatching("Song ft Artist"))
    }

    @Test
    fun removesNonAlphanumeric() = assertEquals("song remix", normalizeForMatching("Song (Remix)"))

    @Test
    fun collapsesMultipleSpaces() = assertEquals("a b c", normalizeForMatching("a   b    c"))

    @Test
    fun emptyString() = assertEquals("", normalizeForMatching(""))
}

class FuzzRatioTest {
    @Test
    fun matchesTheFuzzReferenceValues() {
        assertEquals(48, fuzzRatio("everlong", "everlong acoustic version"))
        assertEquals(89, fuzzRatio("hey there", "hey their"))
        assertEquals(97, fuzzRatio("bohemian rhapsody", "bohemian rapsody"))
        assertEquals(96, fuzzRatio("foo fighters", "foo fighter"))
        assertEquals(41, fuzzRatio("completely different", "something else"))
        assertEquals(78, fuzzRatio("paranoid android", "paranoid android remaster"))
    }

    @Test
    fun identicalAndEmpty() {
        assertEquals(100, fuzzRatio("abc", "abc"))
        assertEquals(100, fuzzRatio("", ""))
        assertEquals(0, fuzzRatio("abc", ""))
    }

    @Test
    fun tiesRoundHalfToEvenLikePython() {
        // LCS=1, lensum=16 → raw 12.5; Python round() gives 12, not 13.
        assertEquals(12, fuzzRatio("abcdefgh", "zyxwvuta"))
    }
}

class TracksMatchTest {
    @Test
    fun sameSongExactAfterNormalization() {
        val a = track("Everlong", "Foo Fighters", Source.LOCAL, "a")
        val b = track("Everlong", "Foo Fighters", Source.TIDAL, "b")
        assertTrue(tracksMatch(a, b))
    }

    @Test
    fun caseInsensitive() {
        val a = track("EVERLONG", "foo fighters", Source.LOCAL, "a")
        val b = track("everlong", "FOO FIGHTERS", Source.TIDAL, "b")
        assertTrue(tracksMatch(a, b))
    }

    @Test
    fun differentSongNoMatch() {
        val a = track("Everlong", "Foo Fighters", Source.LOCAL, "a")
        val b = track("My Hero", "Foo Fighters", Source.TIDAL, "b")
        assertFalse(tracksMatch(a, b))
    }

    @Test
    fun featVariationMatches() {
        val a = track("Song feat. Artist", "Someone", Source.LOCAL, "a")
        val b = track("Song ft Artist", "Someone", Source.TIDAL, "b")
        assertTrue(tracksMatch(a, b))
    }

    @Test
    fun fuzzyNearThreshold() {
        val a = track("Paranoid Android", "Radiohead", Source.LOCAL, "a")
        val b = track("Paranoid Android (Remaster)", "Radiohead", Source.TIDAL, "b")
        // Normalized fuzzy ratio is 78, so the default 85 rejects it.
        assertFalse(tracksMatch(a, b))
        // But a caller can lower the threshold to accept remasters.
        assertTrue(tracksMatch(a, b, threshold = 75))
    }

    @Test
    fun differentArtistNoMatch() {
        val a = track("Reckoner", "Radiohead", Source.LOCAL, "a")
        val b = track("Reckoner", "Beyonce", Source.TIDAL, "b")
        assertFalse(tracksMatch(a, b))
    }
}

class PickPreferredTrackTest {
    private val local = track("Song", "Artist", Source.LOCAL, "l", format = "MP3", bitrateKbps = 128)
    private val tidalHiRes =
        track("Song", "Artist", Source.TIDAL, "t", format = "FLAC", bitDepth = 24, sampleRateHz = 96_000)

    @Test
    fun preferLocal() {
        assertSame(local, pickPreferredTrack(local, tidalHiRes, SourcePriority.PREFER_LOCAL))
        assertSame(local, pickPreferredTrack(tidalHiRes, local, SourcePriority.PREFER_LOCAL))
    }

    @Test
    fun preferTidal() {
        assertSame(tidalHiRes, pickPreferredTrack(local, tidalHiRes, SourcePriority.PREFER_TIDAL))
        assertSame(tidalHiRes, pickPreferredTrack(tidalHiRes, local, SourcePriority.PREFER_TIDAL))
    }

    @Test
    fun preferQualityPicksHigherScore() {
        assertSame(tidalHiRes, pickPreferredTrack(local, tidalHiRes, SourcePriority.PREFER_QUALITY))
        assertSame(tidalHiRes, pickPreferredTrack(tidalHiRes, local, SourcePriority.PREFER_QUALITY))
    }

    @Test
    fun preferQualityTieReturnsFirst() {
        val a = track("Song", "Artist", Source.LOCAL, "a", format = "FLAC")
        val b = track("Song", "Artist", Source.TIDAL, "b", format = "FLAC")
        assertSame(a, pickPreferredTrack(a, b, SourcePriority.PREFER_QUALITY))
    }

    @Test
    fun alwaysAskDefaultsToLocal() {
        assertSame(local, pickPreferredTrack(local, tidalHiRes, SourcePriority.ALWAYS_ASK))
        assertSame(local, pickPreferredTrack(tidalHiRes, local, SourcePriority.ALWAYS_ASK))
    }
}
