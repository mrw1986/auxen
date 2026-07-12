package io.github.auxen

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.github.auxen.data.LibraryRepository
import io.github.auxen.db.AuxenDatabase
import io.github.auxen.dsp.AutoEqRepository
import io.github.auxen.dsp.EqController
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.playback.QueueStateStore
import io.github.auxen.playback.TrackResolver
import io.github.auxen.provider.local.LocalProvider
import io.github.auxen.provider.tidal.TidalAuth
import io.github.auxen.provider.tidal.TidalProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    lateinit var queueStore: QueueStateStore
        private set
    lateinit var autoEq: AutoEqRepository
        private set

    /** MediaMetadata extras key holding the serialized [Track] JSON. */
    const val TRACK_EXTRA_KEY = "auxen.track"

    val json = Json { ignoreUnknownKeys = true }

    lateinit var resolver: TrackResolver
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
        queueStore = QueueStateStore(db)
        autoEq = AutoEqRepository(context)
        resolver = TrackResolver(fetch = { id -> tidal.getStreamInfoById(id) })
    }

    /**
     * Build a playable [MediaItem] for a track from either source — cheap
     * and non-suspending. Local tracks point straight at MediaStore; Tidal
     * tracks get a stable `auxen://tidal/<id>` URI that [TrackResolver]
     * turns into a fresh (short-lived) stream URL at open() time, so long
     * queues never hold expired URLs.
     */
    fun mediaItemFor(track: Track): MediaItem {
        val uri = if (track.source == Source.LOCAL) {
            ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                track.sourceId.toLong(),
            ).toString()
        } else {
            "auxen://tidal/${track.sourceId}"
        }
        val extras = Bundle().apply { putString(TRACK_EXTRA_KEY, json.encodeToString(track)) }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.albumArtUrl?.let(Uri::parse))
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId("${track.source.name}:${track.sourceId}")
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }
}
