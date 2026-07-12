package io.github.auxen

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.github.auxen.data.LibraryRepository
import io.github.auxen.db.AuxenDatabase
import io.github.auxen.dsp.EqController
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.provider.local.LocalProvider
import io.github.auxen.provider.tidal.TidalAuth
import io.github.auxen.provider.tidal.TidalProvider
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@UnstableApi
class AuxenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        EqController.initialize(this)
    }
}

/**
 * Minimal service locator. If/when the app grows past a handful of
 * dependencies, swap for Hilt without changing call sites much.
 */
@UnstableApi
object Graph {
    lateinit var httpClient: OkHttpClient
        private set
    lateinit var local: LocalProvider
        private set
    lateinit var tidalAuth: TidalAuth
        private set
    lateinit var tidal: TidalProvider
        private set
    lateinit var db: AuxenDatabase
        private set
    lateinit var library: LibraryRepository
        private set

    fun init(context: Context) {
        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        local = LocalProvider(context)
        tidalAuth = TidalAuth(context, httpClient)
        tidal = TidalProvider(tidalAuth, httpClient)
        db = AuxenDatabase.build(context)
        library = LibraryRepository(db)
    }

    /**
     * Build a playable [MediaItem] for a track from either source.
     * Tidal stream URLs are resolved here (they are short-lived), which is
     * the Android analog of the desktop `get_stream_uri()` call.
     */
    suspend fun mediaItemFor(track: Track): MediaItem {
        val provider = if (track.source == Source.TIDAL) tidal else local
        val stream = provider.getStreamInfo(track)
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.albumArtUrl?.let(Uri::parse))
            .build()
        val builder = MediaItem.Builder()
            .setMediaId("${track.source.name}:${track.sourceId}")
            .setUri(stream.uri)
            .setMediaMetadata(metadata)
        stream.mimeType?.let { builder.setMimeType(it) }
        return builder.build()
    }
}
