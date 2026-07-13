package io.github.auxen.provider.local

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import io.github.auxen.dsp.ReplayGainInfo
import io.github.auxen.dsp.ReplayGainTags
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.provider.MusicProvider
import io.github.auxen.provider.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Local music source backed by Android's MediaStore.
 *
 * The scanner-facing analog of the desktop app's mutagen-based local
 * provider. MediaStore keeps the index for us, so a "library scan" is just a
 * query; format/bit-depth details beyond what MediaStore exposes can be
 * enriched later with a MediaMetadataRetriever pass.
 */
class LocalProvider(private val context: Context) : MusicProvider {

    override suspend fun search(query: String, limit: Int): List<Track> =
        queryTracks(
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                "(${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)",
            selectionArgs = arrayOf("%$query%", "%$query%"),
            limit = limit,
        )

    /**
     * Return the full local library, ordered newest-first (DATE_ADDED desc).
     *
     * The Library's default "Recently Added" sort keeps input order as its
     * recency proxy, so the list must arrive in recency order; name/artist
     * sorts re-sort this list explicitly.
     */
    suspend fun allTracks(limit: Int = 5000): List<Track> =
        queryTracks(
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            selectionArgs = null,
            limit = limit,
            sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )

    /** Most recently added local tracks — desktop "Recently Added" section. */
    suspend fun recentlyAdded(limit: Int = 30): List<Track> =
        queryTracks(
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            selectionArgs = null,
            limit = limit,
            sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )

    override suspend fun getStreamInfo(track: Track): StreamInfo = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            track.sourceId.toLong(),
        )
        val gains = replayGainFor(track.sourceId)
        StreamInfo(
            uri = uri.toString(),
            sampleRateHz = track.sampleRateHz,
            bitDepth = track.bitDepth,
            trackGainDb = gains?.trackGainDb,
            albumGainDb = gains?.albumGainDb,
        )
    }

    /**
     * Lightweight tag-only ReplayGain read for [sourceId] -- used by
     * [io.github.auxen.playback.PlaybackService]'s RgGainRouter on every
     * media-item transition, without re-resolving the full [StreamInfo]
     * (DSP-a Task 6). Shares the same read path [getStreamInfo] uses.
     *
     * ReplayGain tags live at the front of a FLAC/MP3 file's own metadata
     * blocks, not scattered through it -- a 512KB budget is generous for
     * that and avoids pulling a whole (possibly 100+MB Hi-Res) file into
     * memory just to check for two optional comments. runCatching: a
     * missing/unreadable/malformed file must never fail playback, it just
     * means no ReplayGain data for this track.
     */
    suspend fun replayGainFor(sourceId: String): ReplayGainInfo? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, sourceId.toLong())
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ReplayGainTags.parse(BoundedInputStream(stream, REPLAY_GAIN_READ_BUDGET_BYTES))
            }
        }.getOrNull()
    }

    private suspend fun queryTracks(
        selection: String,
        selectionArgs: Array<String>?,
        limit: Int,
        sortOrder: String = "${MediaStore.Audio.Media.ARTIST}, ${MediaStore.Audio.Media.ALBUM}, ${MediaStore.Audio.Media.TRACK}",
    ): List<Track> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        val tracks = mutableListOf<Track>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumArtistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val bitrateCol = cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext() && tracks.size < limit) {
                val albumId = cursor.getLong(albumIdCol)
                val artUri = ContentUris.withAppendedId(ALBUM_ART_URI, albumId)
                tracks += Track(
                    title = cursor.getString(titleCol) ?: "Unknown",
                    artist = cursor.getString(artistCol) ?: "Unknown",
                    source = Source.LOCAL,
                    sourceId = cursor.getLong(idCol).toString(),
                    album = cursor.getString(albumCol),
                    albumArtist = if (albumArtistCol >= 0) cursor.getString(albumArtistCol) else null,
                    year = cursor.getInt(yearCol).takeIf { it > 0 },
                    durationSeconds = cursor.getLong(durationCol) / 1000.0,
                    trackNumber = cursor.getInt(trackCol).takeIf { it > 0 }?.rem(1000),
                    bitrateKbps = if (bitrateCol >= 0) (cursor.getInt(bitrateCol) / 1000).takeIf { it > 0 } else null,
                    format = mimeToFormat(cursor.getString(mimeCol)),
                    albumArtUrl = artUri.toString(),
                )
            }
        }
        tracks
    }

    private fun mimeToFormat(mime: String?): String? = when (mime?.lowercase()) {
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/mpeg" -> "MP3"
        "audio/mp4", "audio/aac", "audio/mp4a-latm" -> "AAC"
        "audio/ogg", "audio/vorbis" -> "OGG"
        "audio/opus" -> "OPUS"
        "audio/wav", "audio/x-wav" -> "WAV"
        "audio/alac" -> "ALAC"
        else -> mime?.substringAfter('/')?.uppercase()
    }

    private companion object {
        val ALBUM_ART_URI = android.net.Uri.parse("content://media/external/audio/albumart")
        const val REPLAY_GAIN_READ_BUDGET_BYTES = 512L * 1024L
    }
}

/**
 * Wraps [delegate], reporting EOF once [limit] bytes have been read.
 *
 * [ReplayGainTags.parse] reads its input to EOF (`InputStream.readBytes()`)
 * to work byte-array-style over small, fully-buffered tag fixtures in tests;
 * without this wrapper, handing it a real audio file's stream directly would
 * pull the entire file into memory just to find a leading tag block.
 */
private class BoundedInputStream(private val delegate: InputStream, private val limit: Long) : InputStream() {
    private var remaining = limit

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = delegate.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val read = delegate.read(b, off, toRead)
        if (read > 0) remaining -= read
        return read
    }
}
