package io.github.auxen.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Every fixture below was hand-assembled and its byte layout independently
 * verified against a Python mirror of [ReplayGainTags]'s parsing algorithm
 * before being transcribed here (script kept at
 * `/tmp/.../scratchpad/full_rg_verify.py`, not committed) -- this is the
 * "verify before trust" pattern used for the DSP-a numeric tests, applied to
 * binary layout instead of audio math.
 */
class ReplayGainTagsTest {

    // fLaC magic + one VORBIS_COMMENT metadata block (last block, empty vendor
    // string) with REPLAYGAIN_TRACK_GAIN="-6.50 dB" and
    // REPLAYGAIN_ALBUM_GAIN="-7.20 dB".
    private val minimalFlacWithRg = byteArrayOf(
        102, 76, 97, 67, -124, 0, 0, 76, 0, 0, 0, 0, 2, 0, 0, 0, 30, 0, 0, 0, 82, 69, 80, 76, 65, 89, 71, 65,
        73, 78, 95, 84, 82, 65, 67, 75, 95, 71, 65, 73, 78, 61, 45, 54, 46, 53, 48, 32, 100, 66, 30, 0, 0, 0,
        82, 69, 80, 76, 65, 89, 71, 65, 73, 78, 95, 65, 76, 66, 85, 77, 95, 71, 65, 73, 78, 61, 45, 55, 46, 50,
        48, 32, 100, 66,
    )

    // Same structure, but the one comment is TITLE=Some Song -- no RG tags present.
    private val minimalFlacWithoutRg = byteArrayOf(
        102, 76, 97, 67, -124, 0, 0, 27, 0, 0, 0, 0, 1, 0, 0, 0, 15, 0, 0, 0, 84, 73, 84, 76, 69, 61, 83, 111,
        109, 101, 32, 83, 111, 110, 103,
    )

    // ID3v2.3 header (no extended header, no unsynchronisation) + one TXXX
    // frame: encoding ISO-8859-1, description "replaygain_track_gain", value
    // "-3.10 dB" (frame size is plain big-endian per the v2.3 spec, not
    // syncsafe -- only the outer tag size is syncsafe at any version).
    private val minimalId3v23WithTxxx = byteArrayOf(
        73, 68, 51, 3, 0, 0, 0, 0, 0, 41, 84, 88, 88, 88, 0, 0, 0, 31, 0, 0, 0, 114, 101, 112, 108, 97, 121,
        103, 97, 105, 110, 95, 116, 114, 97, 99, 107, 95, 103, 97, 105, 110, 0, 45, 51, 46, 49, 48, 32, 100, 66,
    )

    private val garbageBuffer = byteArrayOf(0, 1, 2, -1, -2, 16, 32, 48)

    @Test
    fun flacWithReplayGainCommentsParsesBothValues() {
        val info = ReplayGainTags.parse(ByteArrayInputStream(minimalFlacWithRg))
        assertEquals(ReplayGainInfo(trackGainDb = -6.5, albumGainDb = -7.2), info)
    }

    @Test
    fun flacWithoutReplayGainCommentsReturnsBothNullNotOverallNull() {
        // A recognized, successfully-parsed container with no RG tags present
        // returns ReplayGainInfo(null, null), not null -- null is reserved for
        // "couldn't identify/parse a supported container at all" (documented
        // in ReplayGainTags's class KDoc).
        val info = ReplayGainTags.parse(ByteArrayInputStream(minimalFlacWithoutRg))
        assertEquals(ReplayGainInfo(trackGainDb = null, albumGainDb = null), info)
    }

    @Test
    fun id3v23WithTxxxTrackGainParsesTrackOnly() {
        val info = ReplayGainTags.parse(ByteArrayInputStream(minimalId3v23WithTxxx))
        assertEquals(ReplayGainInfo(trackGainDb = -3.1, albumGainDb = null), info)
    }

    @Test
    fun garbageBufferReturnsNull() {
        val info = ReplayGainTags.parse(ByteArrayInputStream(garbageBuffer))
        assertNull(info)
    }

    @Test
    fun emptyStreamReturnsNull() {
        val info = ReplayGainTags.parse(ByteArrayInputStream(ByteArray(0)))
        assertNull(info)
    }

    @Test
    fun neverThrowsOnTruncatedFlac() {
        // fLaC magic + a metadata-block header claiming a length that runs
        // past the end of the buffer -- must not throw, must return a
        // best-effort result (both null, since the truncated block can't be read).
        val truncated = byteArrayOf(102, 76, 97, 67, -124, 0, -1, -1) // huge bogus length
        val info = ReplayGainTags.parse(ByteArrayInputStream(truncated))
        assertEquals(ReplayGainInfo(trackGainDb = null, albumGainDb = null), info)
    }
}
