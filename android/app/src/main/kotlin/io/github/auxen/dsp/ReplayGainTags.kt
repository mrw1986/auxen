package io.github.auxen.dsp

import java.io.InputStream

/** Parsed ReplayGain tag values, in dB as stored (no unit conversion). */
data class ReplayGainInfo(val trackGainDb: Double?, val albumGainDb: Double?)

/**
 * Byte-level ReplayGain tag parser -- no external dependencies, matches only
 * the two containers Auxen's local library realistically holds.
 *
 * ### Honest subset, not a general-purpose tag library
 *  - **FLAC**: `fLaC` magic -> scan METADATA_BLOCK_HEADERs for a
 *    VORBIS_COMMENT block (type 4) -> case-insensitive
 *    `REPLAYGAIN_TRACK_GAIN` / `REPLAYGAIN_ALBUM_GAIN` comments, values like
 *    `"-6.50 dB"` (leading numeric parsed, trailing unit text ignored).
 *  - **MP3**: `ID3` magic -> syncsafe tag-size header -> scan frames for
 *    `TXXX` with description `replaygain_track_gain` /
 *    `replaygain_album_gain` (case-insensitive), ISO-8859-1 or UTF-8 text
 *    encoding only (encoding bytes 0 and 3). ID3v2.3 frame sizes are plain
 *    big-endian per spec; ID3v2.4 frame sizes are syncsafe -- both are
 *    handled, keyed off the header's major version byte. The outer tag size
 *    is always syncsafe at every version.
 *  - **Explicitly out of scope**: unsynchronisation, compressed/encrypted
 *    frames, UTF-16 TXXX encodings (bytes 1/2). Frames using them are
 *    skipped, not crashed on.
 *  - **Anything else** (unrecognized magic, truncated/malformed structure
 *    anywhere after a valid magic) -> `null`. A recognized container that
 *    parses successfully but has no RG comments/frames present ->
 *    [ReplayGainInfo] with both fields `null`, NOT an overall `null` --
 *    `null` specifically means "couldn't even identify/parse a supported
 *    container", distinct from "identified one, found no RG tags in it".
 *
 * Never throws: [parse] reads the whole (caller-bounded) stream into memory
 * once, then every subsequent step is defensive -- an out-of-range offset,
 * negative length, or truncated block simply stops that step and returns
 * whatever was successfully parsed so far.
 */
object ReplayGainTags {

    fun parse(input: InputStream): ReplayGainInfo? {
        val bytes = runCatching { input.readBytes() }.getOrNull() ?: return null
        return when {
            hasMagic(bytes, FLAC_MAGIC) -> parseFlac(bytes)
            hasMagic(bytes, ID3_MAGIC) -> parseId3(bytes)
            else -> null
        }
    }

    private fun hasMagic(bytes: ByteArray, magic: ByteArray): Boolean {
        if (bytes.size < magic.size) return false
        for (i in magic.indices) if (bytes[i] != magic[i]) return false
        return true
    }

    // ---- FLAC ----

    private fun parseFlac(bytes: ByteArray): ReplayGainInfo {
        var offset = FLAC_MAGIC.size
        var trackGain: Double? = null
        var albumGain: Double? = null
        var last = false
        while (!last && offset + 4 <= bytes.size) {
            val header = bytes[offset].toInt() and 0xFF
            last = (header and 0x80) != 0
            val type = header and 0x7F
            val length = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            val blockStart = offset + 4
            val blockEnd = blockStart + length
            if (length < 0 || blockEnd > bytes.size) break
            if (type == VORBIS_COMMENT_BLOCK_TYPE) {
                val (t, a) = parseVorbisComments(bytes, blockStart, blockEnd)
                trackGain = trackGain ?: t
                albumGain = albumGain ?: a
            }
            offset = blockEnd
        }
        return ReplayGainInfo(trackGain, albumGain)
    }

    private fun parseVorbisComments(bytes: ByteArray, start: Int, end: Int): Pair<Double?, Double?> {
        var pos = start
        fun readU32LE(): Int? {
            if (pos + 4 > end) return null
            val v = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        val vendorLength = readU32LE() ?: return null to null
        // Subtraction-based bound (not `pos + vendorLength > end`): a Vorbis
        // comment length is a genuine unbounded 32-bit field (unlike FLAC's
        // own 24-bit block-length header), so a crafted value near
        // Int.MAX_VALUE would overflow an addition-based check to a negative
        // sum that wrongly passes it (fix round, review of commit 73bd755,
        // Important #1).
        if (vendorLength < 0 || vendorLength > end - pos) return null to null
        pos += vendorLength
        val commentCount = readU32LE() ?: return null to null
        if (commentCount < 0) return null to null

        var trackGain: Double? = null
        var albumGain: Double? = null
        for (i in 0 until commentCount) {
            val len = readU32LE() ?: break
            if (len < 0 || len > end - pos) break
            val comment = String(bytes, pos, len, Charsets.UTF_8)
            pos += len
            val eq = comment.indexOf('=')
            if (eq <= 0) continue
            val key = comment.substring(0, eq).uppercase()
            val value = comment.substring(eq + 1)
            when (key) {
                "REPLAYGAIN_TRACK_GAIN" -> parseGainValue(value)?.let { trackGain = it }
                "REPLAYGAIN_ALBUM_GAIN" -> parseGainValue(value)?.let { albumGain = it }
            }
        }
        return trackGain to albumGain
    }

    // ---- ID3v2.3 / v2.4 ----

    private fun parseId3(bytes: ByteArray): ReplayGainInfo {
        if (bytes.size < 10) return ReplayGainInfo(null, null)
        val majorVersion = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val hasExtendedHeader = (flags and 0x40) != 0
        val tagSize = syncsafeInt(bytes, 6) ?: return ReplayGainInfo(null, null)
        var offset = 10
        val tagEnd = (offset + tagSize).coerceAtMost(bytes.size)

        if (hasExtendedHeader && offset + 4 <= tagEnd) {
            // Extended header size is syncsafe in v2.4, plain big-endian in
            // v2.3; we don't need its content, just its width to skip past it.
            val extSize = if (majorVersion >= 4) syncsafeInt(bytes, offset) else beInt(bytes, offset)
            // Subtraction-based bound (not `offset + extSize > tagEnd`): v2.3's
            // plain big-endian extSize is a genuinely unbounded 32-bit field
            // (v2.4's syncsafe form is capped at 28 bits and can't reach this),
            // so a crafted value near Int.MAX_VALUE would overflow an
            // addition-based check to a negative sum that wrongly passes it
            // (fix round, review of commit 73bd755, Important #1).
            if (extSize != null && extSize >= 0 && extSize <= tagEnd - offset) {
                offset += extSize.coerceAtLeast(4)
            }
        }

        var trackGain: Double? = null
        var albumGain: Double? = null
        while (offset + 10 <= tagEnd) {
            if (bytes[offset] == 0.toByte()) break // padding reached
            val frameId = String(bytes, offset, 4, Charsets.US_ASCII)
            val frameStart = offset + 10
            val frameSize = if (majorVersion >= 4) syncsafeInt(bytes, offset + 4) else beInt(bytes, offset + 4)
            // Same subtraction-based bound as extSize above -- v2.3's plain
            // frameSize is the other genuinely unbounded 32-bit field here.
            if (frameSize == null || frameSize < 0 || frameSize > tagEnd - frameStart) break
            val frameEnd = frameStart + frameSize
            if (frameId == "TXXX") {
                parseTxxxFrame(bytes, frameStart, frameEnd)?.let { (desc, value) ->
                    when (desc.lowercase()) {
                        "replaygain_track_gain" -> parseGainValue(value)?.let { trackGain = it }
                        "replaygain_album_gain" -> parseGainValue(value)?.let { albumGain = it }
                    }
                }
            }
            offset = frameEnd
        }
        return ReplayGainInfo(trackGain, albumGain)
    }

    private fun parseTxxxFrame(bytes: ByteArray, start: Int, end: Int): Pair<String, String>? {
        if (start >= end) return null
        val encoding = bytes[start].toInt() and 0xFF
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            3 -> Charsets.UTF_8
            else -> return null // UTF-16 variants explicitly out of scope
        }
        val payload = start + 1
        val terminator = indexOfNul(bytes, payload, end)
        if (terminator < 0) return null
        val description = String(bytes, payload, terminator - payload, charset)
        var valueEnd = end
        val valueStart = terminator + 1
        if (valueEnd > valueStart && bytes[valueEnd - 1] == 0.toByte()) valueEnd -= 1
        if (valueStart > valueEnd) return null
        val value = String(bytes, valueStart, valueEnd - valueStart, charset)
        return description to value
    }

    private fun indexOfNul(bytes: ByteArray, from: Int, to: Int): Int {
        for (i in from until to) if (bytes[i] == 0.toByte()) return i
        return -1
    }

    private fun syncsafeInt(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    // ---- shared ----

    /** Parses a leading numeric value like "-6.50 dB" or "+3.2"; null if unparseable. */
    private fun parseGainValue(raw: String): Double? {
        val match = GAIN_VALUE_REGEX.find(raw.trim()) ?: return null
        return match.value.toDoubleOrNull()
    }

    private val GAIN_VALUE_REGEX = Regex("^[+-]?[0-9]*\\.?[0-9]+")
    private val FLAC_MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
    private val ID3_MAGIC = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())
    private const val VORBIS_COMMENT_BLOCK_TYPE = 4
}
