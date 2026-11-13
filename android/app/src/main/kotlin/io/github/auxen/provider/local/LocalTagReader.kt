package io.github.auxen.provider.local

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads embedded ID3/Vorbis/MP4 tags for a local track via
 * [MediaMetadataRetriever], filling the metadata [LocalProvider]'s MediaStore
 * projection leaves null. Opens the SAME MediaStore content URI playback uses
 * (`ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, sourceId.toLong())`), so
 * no file path or extra permission is needed — the sourceId identity is
 * unchanged, and READ_MEDIA_AUDIO already covers this URI.
 *
 * This I/O wrapper is deliberately untested (the network-wrapper precedent in
 * this package); its pure counterparts are [parseDiscNumber] and
 * [Track.mergedWith]. All work is off the main thread; any failure (unreadable
 * file, retriever throw) degrades to null — enrichment is best-effort.
 */
class LocalTagReader(private val context: Context) {

    suspend fun read(sourceId: String): TrackTags? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            sourceId.toLong(),
        )
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                TrackTags(
                    genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                        ?.trim()?.takeIf { it.isNotEmpty() },
                    discNumber = parseDiscNumber(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                    ),
                    sampleRateHz = sampleRateHz(retriever),
                    bitDepth = bitDepth(retriever),
                    bitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()?.let { it / 1000 }?.takeIf { it > 0 },
                )
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    /**
     * Sample rate in Hz. METADATA_KEY_SAMPLERATE is an API 31+ constant, so
     * this returns null below 31 (honest: MediaMetadataRetriever cannot report
     * it there — genre/discNumber still fill on all APIs).
     */
    private fun sampleRateHz(retriever: MediaMetadataRetriever): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            ?.toIntOrNull()?.takeIf { it > 0 }
    }

    /** Bits per sample. METADATA_KEY_BITS_PER_SAMPLE is API 31+; null below 31. */
    private fun bitDepth(retriever: MediaMetadataRetriever): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
            ?.toIntOrNull()?.takeIf { it > 0 }
    }
}
