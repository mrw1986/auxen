package io.github.auxen.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Every fixture below was hand-assembled and its byte layout independently
 * verified against a Python mirror of [ReplayGainTags]'s parsing algorithm
 * before being transcribed here -- this is the "verify before trust" pattern
 * used for the DSP-a numeric tests, applied to binary layout instead of
 * audio math.
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

    // Fix round (review of commit 73bd755), Important #1: fLaC magic + a
    // VORBIS_COMMENT block whose vendor_length field is 0x7FFFFFFF (max
    // positive Int32) -- Vorbis comment lengths are a genuine unbounded
    // 32-bit field (unlike FLAC's own 24-bit block-length header), so the
    // old addition-based bound check (`pos + vendorLength > end`) overflows
    // to a negative sum that wrongly passes, and the next array access
    // throws. Reproduced and the fix verified in Python (both old and new
    // logic against this exact byte layout) before writing this test.
    private val flacHugeVendorLength = byteArrayOf(102, 76, 97, 67, -124, 0, 0, 4, -1, -1, -1, 127)

    // Fix round, Important #1 (ID3 side): ID3v2.3 header + one TXXX frame
    // whose plain big-endian frameSize is 0x7FFFFFF0 -- matches the
    // reviewer's own hand-simulated example exactly (frameStart + frameSize
    // overflows to -2147483644, then bytes[negative] throws on the next
    // loop iteration). v2.3's frameSize is plain big-endian, a genuinely
    // unbounded 32-bit field -- v2.4's syncsafe form is capped at 28 bits
    // and can't reach this value at all.
    private val id3v23HugeFrameSize = byteArrayOf(73, 68, 51, 3, 0, 0, 0, 0, 0, 10, 84, 88, 88, 88, 127, -1, -1, -16, 0, 0)

    // Final-review fix round (review of commit dd1bd55/9d91b84), Important #2:
    // ID3v2.3 header, extended-header flag set, extended header content of
    // exactly 6 bytes (2 flags + 4 padding-size, the spec-minimal content for
    // v2.3 -- whose own 4-byte size field EXCLUDES itself, unlike v2.4's,
    // which includes itself), then one TXXX frame. The pre-fix code skipped
    // only `extSize` (6) bytes instead of `4 + extSize` (10), landing 4 bytes
    // short -- right on a zero byte inside the extended header's padding-size
    // field, which the frame-scan loop misreads as end-of-tag padding and
    // gives up with (null, null) instead of finding the TXXX frame.
    private val id3v23WithExtendedHeader = byteArrayOf(
        73, 68, 51, 3, 0, 64, 0, 0, 0, 51, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 84, 88, 88, 88, 0, 0, 0, 31, 0, 0, 0,
        114, 101, 112, 108, 97, 121, 103, 97, 105, 110, 95, 116, 114, 97, 99, 107, 95, 103, 97, 105, 110, 0,
        45, 51, 46, 49, 48, 32, 100, 66,
    )

    // Same scenario, ID3v2.4: extended header size (syncsafe, 6, self-
    // inclusive) + 1-byte num-flag-bytes + 1-byte extended flags = 6 bytes
    // total, matching spec. Unaffected by the v2.3 bug either way -- included
    // to pin that the v2.4 branch (already correct) still works after the
    // v2.3-only fix.
    private val id3v24WithExtendedHeader = byteArrayOf(
        73, 68, 51, 4, 0, 64, 0, 0, 0, 47, 0, 0, 0, 6, 1, 0, 84, 88, 88, 88, 0, 0, 0, 31, 0, 0, 0, 114, 101,
        112, 108, 97, 121, 103, 97, 105, 110, 95, 116, 114, 97, 99, 107, 95, 103, 97, 105, 110, 0, 45, 51, 46,
        49, 48, 32, 100, 66,
    )

    // Final-review fix round, discriminating replacement for the earlier
    // (Task 5 fix round) v2.4 syncsafe-coverage fixture: that fixture's frame
    // size (31) is under 128, so its syncsafe and plain-big-endian byte
    // encodings are IDENTICAL -- a parser that accidentally called beInt()
    // instead of syncsafeInt() for v2.4 would still have passed it. This one
    // uses a frame content length of 200 bytes (the TXXX value padded with
    // trailing spaces -- parseGainValue only reads the leading numeric
    // prefix, so the padding doesn't affect the parsed gain), whose syncsafe
    // encoding (0,0,1,72) and plain big-endian encoding (0,0,0,200) are
    // genuinely different byte sequences: verified in Python that reading it
    // with beInt() instead yields 328, not 200, which would break the frame
    // scan instead of finding the gain.
    private val id3v24HighBitFrameSize = byteArrayOf(
        73, 68, 51, 4, 0, 0, 0, 0, 1, 82, 84, 88, 88, 88, 0, 0, 1, 72,
        0, 0, 0, 114, 101, 112, 108, 97, 121, 103, 97, 105, 110, 95, 116, 114, 97, 99,
        107, 95, 103, 97, 105, 110, 0, 45, 51, 46, 49, 48, 32, 100, 66, 32, 32, 32,
        32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
        32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
        32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
        32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
        32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
        32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
        32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
        32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
        32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32,
        32, 32, 32, 32,
    )

    // Final-review fix round, Minor: lowercase FLAC comment key (parser
    // already uppercases before matching -- coverage, not a bug fix).
    private val flacLowercaseKey = byteArrayOf(
        102, 76, 97, 67, -124, 0, 0, 42, 0, 0, 0, 0, 1, 0, 0, 0, 30, 0, 0, 0, 114, 101, 112, 108, 97, 121,
        103, 97, 105, 110, 95, 116, 114, 97, 99, 107, 95, 103, 97, 105, 110, 61, 45, 54, 46, 53, 48, 32, 100, 66,
    )

    // Final-review fix round, Minor: uppercase ID3 TXXX description (parser
    // already lowercases before matching -- coverage, not a bug fix).
    private val id3UppercaseDescription = byteArrayOf(
        73, 68, 51, 3, 0, 0, 0, 0, 0, 41, 84, 88, 88, 88, 0, 0, 0, 31, 0, 0, 0, 82, 69, 80, 76, 65, 89, 71,
        65, 73, 78, 95, 84, 82, 65, 67, 75, 95, 71, 65, 73, 78, 0, 45, 51, 46, 49, 48, 32, 100, 66,
    )

    // Final-review fix round, Minor: multi-frame ID3 -- TIT2 (title, must be
    // skipped, not just the first frame ever seen) + TXXX track gain + TXXX
    // album gain, proving the frame-scan loop correctly advances past a
    // non-TXXX frame and keeps finding both RG frames after it.
    private val id3MultiFrameTitleAndBothGains = byteArrayOf(
        73, 68, 51, 3, 0, 0, 0, 0, 0, 102, 84, 73, 84, 50, 0, 0, 0, 10, 0, 0, 0, 84, 101, 115, 116, 32, 83,
        111, 110, 103, 84, 88, 88, 88, 0, 0, 0, 31, 0, 0, 0, 114, 101, 112, 108, 97, 121, 103, 97, 105, 110,
        95, 116, 114, 97, 99, 107, 95, 103, 97, 105, 110, 0, 45, 51, 46, 49, 48, 32, 100, 66, 84, 88, 88, 88,
        0, 0, 0, 31, 0, 0, 0, 114, 101, 112, 108, 97, 121, 103, 97, 105, 110, 95, 97, 108, 98, 117, 109, 95,
        103, 97, 105, 110, 0, 45, 52, 46, 53, 48, 32, 100, 66,
    )

    // Final-review fix round, Important #2 companion (item 11a): same TXXX
    // content as minimalId3v23WithTxxx, but the unsynchronisation bit (0x80)
    // is set in the header flags byte. The class KDoc documents
    // unsynchronisation as explicitly out of scope; before this fix round
    // that was aspirational -- nothing actually checked the flag, so this
    // exact fixture would have parsed the (potentially misinterpreted, since
    // real unsynchronised data is byte-stuffed) gain anyway.
    private val id3v23Unsynchronised = byteArrayOf(
        73, 68, 51, 3, 0, -128, 0, 0, 0, 41, 84, 88, 88, 88, 0, 0, 0, 31, 0, 0, 0, 114, 101, 112, 108, 97,
        121, 103, 97, 105, 110, 95, 116, 114, 97, 99, 107, 95, 103, 97, 105, 110, 0, 45, 51, 46, 49, 48, 32,
        100, 66,
    )

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

    @Test
    fun hugeVendorLengthNearIntMaxDoesNotOverflowBoundsCheckInFlac() {
        // Before the fix round: throws ArrayIndexOutOfBoundsException. A
        // deliberately unwrapped call (no assertThrows) so the test errors
        // out against the buggy code for the right reason, not just fails
        // an assertion.
        val info = ReplayGainTags.parse(ByteArrayInputStream(flacHugeVendorLength))
        assertEquals(ReplayGainInfo(trackGainDb = null, albumGainDb = null), info)
    }

    @Test
    fun hugeFrameSizeNearIntMaxDoesNotOverflowBoundsCheckInId3v23() {
        // Before the fix round: throws ArrayIndexOutOfBoundsException with
        // index -2147483644, exactly matching the reviewer's hand simulation.
        val info = ReplayGainTags.parse(ByteArrayInputStream(id3v23HugeFrameSize))
        assertEquals(ReplayGainInfo(trackGainDb = null, albumGainDb = null), info)
    }

    @Test
    fun id3v23ExtendedHeaderSkipsExactlyPastItToTheFrame() {
        // Before this fix round: the v2.3 skip was 4 bytes short (extSize
        // alone, not 4 + extSize -- v2.3's size field excludes itself),
        // landing on a zero byte inside the extended header's own content and
        // misreading it as end-of-tag padding -- returns (null, null) instead
        // of finding the TXXX frame.
        val info = ReplayGainTags.parse(ByteArrayInputStream(id3v23WithExtendedHeader))
        assertEquals(ReplayGainInfo(trackGainDb = -3.1, albumGainDb = null), info)
    }

    @Test
    fun id3v24ExtendedHeaderStillParsesAfterTheV23OnlyFix() {
        val info = ReplayGainTags.parse(ByteArrayInputStream(id3v24WithExtendedHeader))
        assertEquals(ReplayGainInfo(trackGainDb = -3.1, albumGainDb = null), info)
    }

    @Test
    fun id3v24HighBitFrameSizeDiscriminatesSyncsafeFromPlainBigEndian() {
        // Frame size 200: syncsafe bytes (0,0,1,72) differ from plain
        // big-endian bytes (0,0,0,200) -- verified in Python that reading
        // this fixture with beInt() instead of syncsafeInt() yields 328, not
        // 200, which would break the frame scan rather than find the gain.
        val info = ReplayGainTags.parse(ByteArrayInputStream(id3v24HighBitFrameSize))
        assertEquals(ReplayGainInfo(trackGainDb = -3.1, albumGainDb = null), info)
    }

    @Test
    fun flacLowercaseCommentKeyMatchesCaseInsensitively() {
        val info = ReplayGainTags.parse(ByteArrayInputStream(flacLowercaseKey))
        assertEquals(ReplayGainInfo(trackGainDb = -6.5, albumGainDb = null), info)
    }

    @Test
    fun id3UppercaseTxxxDescriptionMatchesCaseInsensitively() {
        val info = ReplayGainTags.parse(ByteArrayInputStream(id3UppercaseDescription))
        assertEquals(ReplayGainInfo(trackGainDb = -3.1, albumGainDb = null), info)
    }

    @Test
    fun multiFrameId3SkipsNonTxxxAndFindsBothGains() {
        val info = ReplayGainTags.parse(ByteArrayInputStream(id3MultiFrameTitleAndBothGains))
        assertEquals(ReplayGainInfo(trackGainDb = -3.1, albumGainDb = -4.5), info)
    }

    @Test
    fun unsynchronisedId3ReturnsBothNullRatherThanAttemptingToParse() {
        // The class KDoc has always documented unsynchronisation as out of
        // scope; before this fix round nothing actually checked the header
        // flag, so this exact fixture would still have parsed the gain.
        val info = ReplayGainTags.parse(ByteArrayInputStream(id3v23Unsynchronised))
        assertEquals(ReplayGainInfo(trackGainDb = null, albumGainDb = null), info)
    }
}
