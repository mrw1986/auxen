package io.github.auxen.provider.local

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.provider.MusicProvider
import io.github.auxen.provider.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /** Return the full local library, ordered by artist / album / track number. */
    suspend fun allTracks(limit: Int = 5000): List<Track> =
        queryTracks(
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            selectionArgs = null,
            limit = limit,
        )

    override suspend fun getStreamInfo(track: Track): StreamInfo {
        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            track.sourceId.toLong(),
        )
        return StreamInfo(uri = uri.toString(), sampleRateHz = track.sampleRateHz, bitDepth = track.bitDepth)
    }

    private suspend fun queryTracks(
        selection: String,
        selectionArgs: Array<String>?,
        limit: Int,
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
            "${MediaStore.Audio.Media.ARTIST}, ${MediaStore.Audio.Media.ALBUM}, ${MediaStore.Audio.Media.TRACK}",
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
    }
}
