package io.github.auxen

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.auxen.data.LibraryRepository
import io.github.auxen.db.AuxenDatabase
import io.github.auxen.dsp.AudioFxController
import io.github.auxen.dsp.AutoEqController
import io.github.auxen.dsp.AutoEqRepository
import io.github.auxen.dsp.EqController
import io.github.auxen.model.QueueEntry
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.playback.QueueStateStore
import io.github.auxen.playback.TrackResolver
import io.github.auxen.provider.local.LocalProvider
import io.github.auxen.provider.tidal.TidalAuth
import io.github.auxen.provider.tidal.TidalOfficialClient
import io.github.auxen.provider.tidal.TidalOfficialSession
import io.github.auxen.provider.tidal.TidalProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

@UnstableApi
class AuxenApp : Application() {
    /** Application-scoped; outlives any UI, so restore work survives navigation. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        EqController.initialize(this)
        // AutoEqController.initialize also runs the one-time legacy
        // migration (a pre-split install's AutoEq profile, merged into
        // EqController's old combined eq_state) -- must come after
        // EqController.initialize so the migration reads a real restored
        // state, not defaults from a race (AutoEq split, Task 1).
        AutoEqController.initialize(this)
        AudioFxController.initialize(this)
        restoreAutoEqProfile()
    }

    /**
     * Validate — and only if necessary re-apply — the AutoEq profile marker
     * persisted by the Equalizer screen. Runs off the main thread because
     * [AutoEqRepository.ensureLoaded] has a ~3s cold path. A profile that
     * vanished after an asset update simply clears the setting — restore must
     * never crash the app.
     *
     * [AutoEqController.initialize] already restores the full
     * [io.github.auxen.dsp.EqState] (filters, preamp, and the user's enabled
     * flag) from ITS OWN DataStore, so once that has settled
     * ([AutoEqController.awaitInitialized]) re-applying the profile would be
     * redundant and, worse, force `enabled = true` — clobbering a persisted
     * `enabled = false` on every launch. So when the restored state already
     * carries filters we only *validate* the marker (bundled name still in
     * the index, or custom text still present) and clear it on a miss. We
     * re-apply from the marker only when DataStore was empty/fresh (no
     * filters), where the default `enabled = true` is correct.
     *
     * Targets [AutoEqController], not [EqController], as of the AutoEq
     * split (Task 1) — this marker/restore mechanism has always been about
     * the searched/imported correction profile, never the graphic EQ.
     */
    private fun restoreAutoEqProfile() {
        appScope.launch {
            runCatching {
                val saved = Graph.library.getSetting(KEY_AUTOEQ_PROFILE)
                if (saved.isNullOrBlank()) return@runCatching

                // Wait for the DataStore restore so `hasFilters` reflects a
                // settled state rather than racing the load.
                AutoEqController.awaitInitialized()
                val hasFilters = AutoEqController.state.value.filters.isNotEmpty()

                if (saved.startsWith("custom:")) {
                    val name = saved.removePrefix("custom:")
                    val text = Graph.library.getSetting(KEY_AUTOEQ_CUSTOM_TEXT)
                    when {
                        text.isNullOrBlank() -> Graph.library.setSetting(KEY_AUTOEQ_PROFILE, "")
                        !hasFilters -> AutoEqController.importAutoEq(text, name)
                        // else: DataStore already restored the parametric state
                        // (incl. the enabled flag); marker is valid, leave it.
                    }
                    return@runCatching
                }

                Graph.autoEq.ensureLoaded()
                val profile = Graph.autoEq.search(saved, limit = Int.MAX_VALUE)
                    .firstOrNull { it.name == saved }
                if (profile == null) {
                    Graph.library.setSetting(KEY_AUTOEQ_PROFILE, "")
                    return@runCatching
                }
                if (!hasFilters) {
                    AutoEqController.importAutoEq(Graph.autoEq.profileText(profile), profile.name)
                }
                // else: marker validated against the index; DataStore state kept.
            }
        }
    }

    private companion object {
        const val KEY_AUTOEQ_PROFILE = "autoeq_profile"
        const val KEY_AUTOEQ_CUSTOM_TEXT = "autoeq_custom_text"
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
    /** Official Tidal API PKCE session (Tidal official-API migration, Task 1) -- additive, independent of [tidalAuth]. */
    lateinit var tidalOfficialSession: TidalOfficialSession
        private set
    lateinit var tidalOfficialApi: TidalOfficialClient
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

    /**
     * MediaMetadata extras key holding a per-occurrence UUID stamped by
     * [mediaItemFor]. Unlike [MediaItem.mediaId] (`SOURCE:sourceId`, shared by
     * every occurrence of a track and parsed by ReplayGain/Tidal routing in
     * `PlaybackService`), this is unique per enqueued item, so a queue with the
     * same track twice still has distinct, reorder-stable keys — see [QueueEntry].
     */
    const val QUEUE_UID_KEY = "auxen.queueUid"

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
        tidalOfficialSession = TidalOfficialSession(context, httpClient)
        tidalOfficialApi = TidalOfficialClient(httpClient)
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
        val extras = Bundle().apply {
            putString(TRACK_EXTRA_KEY, json.encodeToString(track))
            // Fresh per-occurrence id (see QUEUE_UID_KEY / QueueEntry). NOT the
            // mediaId, which stays "SOURCE:sourceId" for routing/detection.
            putString(QUEUE_UID_KEY, UUID.randomUUID().toString())
        }
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

    /**
     * The inverse of [mediaItemFor]: decodes the [Track] embedded in
     * [metadata]'s extras by that function, or null if [metadata] wasn't
     * built by it (missing/corrupt extras). Shared by every call site that
     * needs to go from a live `Player`'s state back to a [Track] --
     * `PlaybackService.currentTracks` and [io.github.auxen.ui.PlayerViewModel]'s
     * `nowPlaying`/`currentTrack`/`queue` all used to duplicate this same
     * extras-decode snippet (Desktop-Parity Screens, sub-batch A, Task 2).
     */
    fun trackFor(metadata: MediaMetadata): Track? =
        metadata.extras?.getString(TRACK_EXTRA_KEY)
            ?.let { encoded -> runCatching { json.decodeFromString<Track>(encoded) }.getOrNull() }

    fun trackFor(mediaItem: MediaItem): Track? = trackFor(mediaItem.mediaMetadata)

    /** Snapshot every track in [player]'s current queue, in order. */
    fun tracksFrom(player: Player): List<Track> =
        (0 until player.mediaItemCount).mapNotNull { i -> trackFor(player.getMediaItemAt(i)) }

    /**
     * Snapshot [player]'s queue as [QueueEntry]s — like [tracksFrom] but each
     * item also carries its per-occurrence id (the [QUEUE_UID_KEY] extra
     * stamped by [mediaItemFor]). Items that predate the stamp (an older
     * restored queue) fall back to `"$index:SOURCE:sourceId"`, which is only
     * unique within a single snapshot but that is all the Queue UI needs it
     * for. Windows whose [Track] fails to decode are dropped, exactly as
     * [tracksFrom] drops them.
     */
    fun queueEntriesFrom(player: Player): List<QueueEntry> =
        queueEntriesFrom((0 until player.mediaItemCount).map(player::getMediaItemAt))

    /** [queueEntriesFrom] over a plain media-item list — the unit-testable core. */
    fun queueEntriesFrom(items: List<MediaItem>): List<QueueEntry> =
        items.mapIndexedNotNull { index, item ->
            val track = trackFor(item) ?: return@mapIndexedNotNull null
            val id = item.mediaMetadata.extras?.getString(QUEUE_UID_KEY)
                ?: "$index:${track.source.name}:${track.sourceId}"
            QueueEntry(id, track)
        }
}
