package io.github.auxen.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import io.github.auxen.Graph
import io.github.auxen.data.LibrarySort
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.dsp.BitPerfectController
import io.github.auxen.matching.DuplicateResolver
import io.github.auxen.model.QueueEntry
import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track
import io.github.auxen.playback.PlaybackService
import io.github.auxen.provider.tidal.formatTidalStreamingProbeResult
import io.github.auxen.provider.tidal.probePlayback
import io.github.auxen.provider.tidal.probeStreaming
import io.github.auxen.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

/** UI-facing login status for the Tidal account screen. */
sealed interface TidalLoginState {
    data object LoggedOut : TidalLoginState
    data class AwaitingApproval(val verificationUrl: String) : TidalLoginState
    data object LoggedIn : TidalLoginState
    data class Error(val message: String) : TidalLoginState
}

@UnstableApi
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    var controller: MediaController? by mutableStateOf(null)
        private set

    var nowPlaying: MediaMetadata? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set

    /** The full track behind [nowPlaying], decoded from the media item's extras. */
    var currentTrack: Track? by mutableStateOf(null)
        private set
    var shuffleEnabled: Boolean by mutableStateOf(false)
        private set
    var repeatMode: Int by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set

    val localTracks = MutableStateFlow<List<Track>>(emptyList())
    val searchQuery = MutableStateFlow("")
    val searchResults = MutableStateFlow<List<Track>>(emptyList())
    var searchInFlight by mutableStateOf(false)
        private set

    /**
     * Per-surface async-load signals — the browse-screen analog of
     * [searchInFlight] (polish C2). Each is true while that surface's load runs
     * and false when it finishes (success OR failure), so a screen shows a
     * spinner while loading and its `EmptyState` only once the load has
     * completed and the list is genuinely empty — never a flash of "nothing
     * here" before the data arrives.
     *
     * [libraryLoading] / [homeLoading] start `true` because Library and Home
     * both kick off their load from a mount `LaunchedEffect` (Home is the start
     * destination and `MainActivity` also runs [loadLibrary] from the
     * permission callback), so the very first frame must show the spinner, not
     * the empty state; [loadLibrary] / [refreshHome] flip them false when done
     * (in a `finally`, so a failed/cancelled load never leaves a stuck spinner).
     * [collectionLoading] starts `true` and flips false once the favorites +
     * playlists DB flows first resolve (seeded in `init`).
     */
    val libraryLoading = MutableStateFlow(true)
    val homeLoading = MutableStateFlow(true)
    val collectionLoading = MutableStateFlow(true)

    val searchHistoryItems: StateFlow<List<String>> = Graph.library.searchHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var searchDebounceJob: Job? = null

    private val _tidalLogin = MutableStateFlow<TidalLoginState>(TidalLoginState.LoggedOut)
    val tidalLogin: StateFlow<TidalLoginState> = _tidalLogin

    /** "SOURCE:sourceId" keys of favorited tracks, for O(1) heart-state lookups. */
    val favoriteKeys: StateFlow<Set<String>> = Graph.library.favoriteKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val favorites: StateFlow<List<Track>> = Graph.library.favorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = Graph.library.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Recently played tracks for the Home screen; refreshed on demand. */
    val recentlyPlayed = MutableStateFlow<List<Track>>(emptyList())

    /** Home source filter ("all"/"tidal"/"local"), persisted under `home_filter`. */
    val homeFilter = MutableStateFlow("all")

    /** Collection source filter ("all"/"tidal"/"local"), persisted under `collection_filter`. */
    val collectionFilter = MutableStateFlow("all")

    /** Most recently added local tracks for the Home carousel; refreshed on demand. */
    val recentlyAdded = MutableStateFlow<List<Track>>(emptyList())

    /** Current playback position and track duration, in milliseconds — polled while a controller exists. */
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)

    /** Last playback error, for the UI banner; null when none/dismissed. */
    val playbackError = MutableStateFlow<String?>(null)

    fun dismissPlaybackError() {
        playbackError.value = null
    }

    /** Selected Library tab (0=Albums, 1=Artists, 2=Tracks), persisted per session. */
    val libraryTab = MutableStateFlow(0)

    /** Active sort for the current Library tab, persisted per tab. */
    val librarySort = MutableStateFlow(LibrarySort.RECENTLY_ADDED)

    /** Sort direction for the current Library tab; true = ascending. */
    val librarySortAscending = MutableStateFlow(true)

    /**
     * Live queue snapshot (Queue screen) -- the MediaController's own media
     * items, NOT the debounced `QueueStateStore` restore-on-relaunch copy
     * (`PlaybackService.scheduleQueueSave`, unaffected by this: mutations
     * below go through the controller, which is what that debounced save
     * already observes). Refreshed from [refreshQueue] on `onTimelineChanged`/
     * `onMediaItemTransition`, never mutated locally/optimistically -- the
     * controller reindexes asynchronously, so a local splice could disagree
     * with the eventual real state.
     */
    val queue = MutableStateFlow<List<QueueEntry>>(emptyList())

    /** Real controller index of the currently-playing item within [queue]; -1 if none. */
    val queueIndex = MutableStateFlow(-1)

    private fun refreshQueue(c: Player) {
        queue.value = Graph.queueEntriesFrom(c)
        queueIndex.value = c.currentMediaItemIndex
    }

    /** Jump playback directly to [index] in the queue (Queue screen tap-to-jump). */
    fun jumpTo(index: Int) {
        controller?.seekTo(index, 0)
    }

    /** Remove the item at [index] from the live queue. */
    fun removeFromQueue(index: Int) {
        controller?.removeMediaItem(index)
    }

    /** Move the item at [from] to [to] in the live queue (drag-reorder commit). */
    fun moveInQueue(from: Int, to: Int) {
        controller?.moveMediaItem(from, to)
    }

    /** Empty the live queue entirely ("Clear queue"). */
    fun clearQueue() {
        controller?.clearMediaItems()
    }

    /**
     * Bit-Perfect / Direct playback mode (Settings screen). Unlike [themeMode]/
     * [sourcePriority], this is NOT restored/persisted through the VM: it is a
     * DSP-pipeline setting owned by [BitPerfectController] (its own DataStore,
     * initialized in `AuxenApp`), so the UI and the `PlaybackService` observe
     * the exact same StateFlow. The VM just re-exposes it and forwards writes.
     */
    val bitPerfect: StateFlow<Boolean> = BitPerfectController.enabled

    /** Toggle Bit-Perfect / Direct mode. The service rebuilds its player on change. */
    fun setBitPerfect(enabled: Boolean) = BitPerfectController.setEnabled(enabled)

    /** Appearance choice, persisted under `color_scheme` (Settings screen). */
    val themeMode = MutableStateFlow(ThemeMode.SYSTEM)

    /** Source-priority tie-break for search-result merging (Settings screen; consumed by [search]). */
    val sourcePriority = MutableStateFlow(SourcePriority.PREFER_QUALITY)

    /**
     * The go/no-go streaming spike's visible status readout (Tidal
     * official-API migration, Task 1; "Try official Tidal login (beta)" in
     * Settings) -- null until a login/probe has run this session.
     */
    val tidalOfficialStatus = MutableStateFlow<String?>(null)

    /** The spike's throwaway validation player -- NOT the app's real playback pipeline; see [probePlayback]'s KDoc. */
    private var tidalOfficialProbePlayer: ExoPlayer? = null

    /** Step 1 of the official-API login: the URL to open in a Custom Tab. */
    fun beginTidalOfficialLogin(): String = Graph.tidalOfficialSession.beginLogin()

    /** Step 2: call from `MainActivity.onNewIntent` with the `auxen://auth-callback` redirect. */
    fun completeTidalOfficialLogin(redirect: Uri) {
        viewModelScope.launch {
            val result = Graph.tidalOfficialSession.completeLogin(redirect)
            tidalOfficialStatus.value = result.fold(
                onSuccess = { "Logged in via the official Tidal API. Access token acquired -- run the streaming probe below." },
                onFailure = { "Official-API login failed: ${it.message}" },
            )
        }
    }

    /**
     * Runs the go/no-go streaming spike for [trackId]: fetches
     * `/trackManifests/{id}` + `/trackFiles/{id}`, and -- if either came
     * back FULL with a usable URL -- attempts a throwaway Media3 play to
     * confirm it's genuinely playable (manifest URL preferred; a DASH
     * manifest is required for [probePlayback]'s MIME hint, which a bare
     * `trackFiles` file URL isn't).
     */
    fun runTidalOfficialStreamingProbe(trackId: String) {
        viewModelScope.launch {
            val session = Graph.tidalOfficialSession.storedSession()
            if (session == null) {
                tidalOfficialStatus.value = "Not logged in via the official API yet -- tap \"Try official Tidal login\" first."
                return@launch
            }
            tidalOfficialStatus.value = "Probing track $trackId…"
            val probe = probeStreaming(Graph.tidalOfficialApi, session.accessToken, trackId)
            Log.i("AuxenTidalOfficial", formatTidalStreamingProbeResult(probe))

            val manifestUrl = probe.manifestUri.takeIf { probe.manifestPresentation == "FULL" }
            if (manifestUrl == null) {
                tidalOfficialStatus.value = formatTidalStreamingProbeResult(probe)
                return@launch
            }

            tidalOfficialProbePlayer?.release()
            tidalOfficialProbePlayer = probePlayback(getApplication(), manifestUrl) { playbackResult ->
                val withPlayback = probe.copy(playbackConfirmed = playbackResult.isSuccess)
                tidalOfficialStatus.value = formatTidalStreamingProbeResult(withPlayback)
                Log.i("AuxenTidalOfficial", formatTidalStreamingProbeResult(withPlayback))
            }
        }
    }

    /**
     * The metadata analogue of [tidalOfficialStatus]: the official-API
     * artist-metadata probe's visible readout (Tidal metadata-layer
     * Foundation, Slice 0) -- null until [runTidalMetadataProbe] has run.
     */
    private val _tidalMetadataStatus = MutableStateFlow<String?>(null)
    val tidalMetadataStatus: StateFlow<String?> = _tidalMetadataStatus.asStateFlow()

    /** Debug/probe: fetch an artist's official-API metadata overview and render it as a status string. */
    fun runTidalMetadataProbe(artistId: String) {
        viewModelScope.launch {
            _tidalMetadataStatus.value = "Fetching artist $artistId…"
            _tidalMetadataStatus.value = Graph.tidalMetadata.artistOverview(artistId.trim()).fold(
                onSuccess = { o ->
                    buildString {
                        appendLine("Artist: ${o.name} (id ${o.id})")
                        appendLine("Biography: ${o.biography?.take(160) ?: "—"}")
                        appendLine("Similar (${o.similarArtistNames.size}): ${o.similarArtistNames.take(5).joinToString()}")
                        appendLine("Albums (${o.albumTitles.size}): ${o.albumTitles.take(5).joinToString()}")
                    }
                },
                onFailure = { "Metadata probe failed: ${it.message}" },
            )
        }
    }

    private val _tidalIdProbeStatus = MutableStateFlow<String?>(null)
    val tidalIdProbeStatus: StateFlow<String?> = _tidalIdProbeStatus.asStateFlow()

    /**
     * Slice-1 gate: prove an internal-v1 track id is the SAME catalog id the
     * official v2 API accepts. Resolves the track's artist/album ids on the
     * official API, then fetches the artist name to make the match
     * human-verifiable. Only names/ids/ISRC in the readout — never a token.
     */
    fun runTidalIdEquivalenceProbe(tidalTrackId: String) {
        viewModelScope.launch {
            val id = tidalTrackId.trim()
            _tidalIdProbeStatus.value = "Resolving track $id on the official v2 API…"
            val repo = Graph.tidalMetadata
            val relations = repo.trackRelations(id)
            val rel = relations.getOrNull()
            if (rel == null) {
                _tidalIdProbeStatus.value =
                    "FAIL: the official API rejected track id $id — ${relations.exceptionOrNull()?.message}. " +
                        "id-equivalence does NOT hold; fall back to ISRC/barcodeId matching."
                return@launch
            }
            val artistName = rel.artistId?.let { repo.artistOverview(it).getOrNull()?.name }
            // Slice 1 Task 3: prove the artist id official-v2 just handed us is
            // ALSO accepted by internal-v1, which is where the discography, the
            // top tracks and the by-name id resolution all come from (official
            // v2's ?include= returns an arbitrary page). Names/titles/ids only —
            // never a token, never a full request URL.
            val artistId = rel.artistId
            val discography = artistId?.let { Graph.tidal.artistAlbums(it) }.orEmpty()
            val top = artistId?.let { Graph.tidal.artistTopTracks(it) }.orEmpty()
            // Decision 4, both directions: the real name must resolve, and a
            // near-miss must resolve to NOTHING rather than to a nearby artist.
            val exactHit = artistName?.let { Graph.tidal.searchArtistIdExact(it) }
            val nearMissName = artistName?.let { "$it Zzz" }
            val nearMissHit = nearMissName?.let { Graph.tidal.searchArtistIdExact(it) }
            _tidalIdProbeStatus.value = buildString {
                appendLine("Track id $id resolves on the official v2 API (same catalog id).")
                appendLine("ISRC: ${rel.isrc ?: "—"}")
                appendLine("Artist: ${artistName ?: "—"} (id ${artistId ?: "—"})")
                appendLine("Album id: ${rel.albumId ?: "—"}")
                appendLine(
                    if (artistId != null && rel.albumId != null) {
                        "PASS — the internal-v1 track id is the same catalog id the official v2 API accepts."
                    } else {
                        "PARTIAL — track resolved but a relationship was missing; inspect the ids above."
                    },
                )
                appendLine()
                appendLine("Discography (internal-v1 artists/$artistId/albums?limit=50): ${discography.size}")
                discography.take(8).forEach { appendLine("  • ${it.title} (${it.albumType ?: "—"}, ${it.releaseYear ?: "—"})") }
                appendLine(
                    if (discography.isEmpty()) {
                        "FAIL — internal-v1 returned no albums for artist id $artistId. Either the " +
                            "official-v2 artist id is NOT an internal-v1 artist id, or you are not logged in " +
                            "on the internal-v1 path (Account → Log in to Tidal). Task 3's discography " +
                            "depends on this — do not build Task 6 until it passes."
                    } else {
                        "PASS — the official-v2 artist id is accepted by internal-v1."
                    },
                )
                appendLine()
                appendLine("Top tracks (internal-v1 artists/$artistId/toptracks?limit=50): ${top.size}")
                top.take(8).forEach { appendLine("  • ${it.title}") }
                appendLine(
                    if (top.isEmpty()) {
                        "FAIL — internal-v1 returned no top tracks for artist id $artistId. Task 3's " +
                            "top-tracks section depends on this — do not build Task 6 until it passes."
                    } else {
                        "PASS — toptracks answered. EYEBALL THE ORDER: it must read like the artist's " +
                            "best-known songs, not an arbitrary page of remixes/B-sides."
                    },
                )
                appendLine()
                appendLine("Exact-match search for \"${artistName ?: "—"}\": id ${exactHit ?: "null"}")
                appendLine("Near-miss search for \"${nearMissName ?: "—"}\": id ${nearMissHit ?: "null"}")
                appendLine(
                    when {
                        exactHit == null ->
                            "FAIL — the exact name did not resolve. Decision 4's fallback would never fire; " +
                                "check the internal-v1 login and the `types=artists` param."
                        nearMissHit != null ->
                            "FAIL — a NEAR-MISS resolved to id $nearMissHit. artistIdFromSearch is matching " +
                                "fuzzily; it must require an exact case-insensitive name match. This is the " +
                                "desktop bug we are here to NOT ship."
                        exactHit != artistId ->
                            "PARTIAL — the exact name resolved to $exactHit but the track's artist id is " +
                                "$artistId. Two artists share this name; inspect before relying on the fallback."
                        else -> "PASS — exact name resolves to the same artist id; a near-miss resolves to nothing."
                    },
                )
            }
        }
    }

    // ---- Slice 1: official-API detail enrichment (overlays local grouping) ----
    val artistDetail = MutableStateFlow<io.github.auxen.provider.tidal.TidalArtistDetail?>(null)

    /**
     * The artist discography, from INTERNAL-v1 (`artists/{id}/albums`) — a
     * separate flow from [artistDetail] because it is a separate API and a
     * separate call. [io.github.auxen.provider.tidal.TidalMetadataRepository]'s
     * constructor is frozen and holds no internal-v1 provider, so this
     * composition happens here, not there. Empty means "no discography" (not
     * logged in to internal-v1, unknown artist, or the call failed) — the row
     * simply falls back to local albums.
     */
    val artistAlbums = MutableStateFlow<List<io.github.auxen.provider.tidal.AlbumSummary>>(emptyList())

    /**
     * The artist's top tracks in POPULARITY order, from INTERNAL-v1
     * (`artists/{id}/toptracks`) — same reasoning as [artistAlbums]: separate
     * API, separate call, composed here because the repository cannot reach the
     * internal-v1 provider. Empty means "no top tracks", and the screen then
     * shows the plain local track list exactly as it does today
     * ([io.github.auxen.provider.tidal.mergeArtistTracks] returns the local list
     * untouched on an empty top-tracks list).
     */
    val artistTopTracks = MutableStateFlow<List<Track>>(emptyList())
    val artistDetailLoading = MutableStateFlow(false)
    private var artistDetailKey: String? = null
    private var artistDetailJob: Job? = null

    val albumDetail = MutableStateFlow<io.github.auxen.provider.tidal.TidalAlbumDetail?>(null)
    val albumDetailLoading = MutableStateFlow(false)
    private var albumDetailKey: String? = null
    private var albumDetailJob: Job? = null

    /**
     * Load remote artist enrichment. Resolves the artist id through the chain in
     * the plan's *Artist-id resolution*, then fans out to the reads. Keyed so a
     * recomposition/re-navigation to the same artist does not refetch.
     *
     * The id chain, in order — first hit wins:
     *  1. [tidalArtistId] — already known (a similar-artist tap).
     *  2. [seedTidalTrackId] → `trackRelations` → artistId (gate-proven, precise).
     *  3. `searchArtistIdExact(artistName)` — internal-v1, EXACT name match only.
     *  4. null → no remote enrichment; the screen renders exactly as today.
     *
     * Step 3 also catches the case where a seed exists but `trackRelations`
     * fails, which is why it is an elvis fallthrough rather than an else-branch.
     *
     * There is deliberately **no early return** for "no id and no seed": that
     * artist is exactly who step 3 exists for. `searchArtistIdExact` is one
     * request, only on artist-page open, and the key guard stops it repeating.
     *
     * The official artist document and the two internal-v1 lists are fetched off
     * ONE resolved id and each is allowed to fail on its own: a missing
     * discography must not cost us the biography, and vice versa.
     *
     * **Why every write is guarded on [key] and not just by `cancel()`** — do not
     * "simplify" these checks away. `cancel()` alone cannot stop a superseded
     * load dead, because the internal-v1 provider wraps its network reads in
     * `runCatching { get(...) }` (e.g. `TidalProvider.artistAlbums`), and
     * `runCatching` catches `Throwable` — **including the `CancellationException`
     * that cancellation delivers**. A cancelled loader therefore resumes with
     * `emptyList()` in hand and runs on until its next REAL suspension point; a
     * `MutableStateFlow` write is not one. Without the [key] guard it would still
     * clobber the winner's discography with an empty list and clear the winner's
     * spinner early. The guard is the actual correctness mechanism; `cancel()` is
     * the optimisation that stops the wasted request.
     */
    fun loadArtistDetail(artistName: String, tidalArtistId: String?, seedTidalTrackId: String?) {
        val key = "$artistName|$tidalArtistId|$seedTidalTrackId"
        if (key == artistDetailKey) return
        artistDetailKey = key
        // Cancel the load this one supersedes. An artist page legitimately calls
        // this TWICE for the same artist: once while the local library is still
        // loading (seedTidalTrackId = null, so the id resolves via the exact-name
        // search) and again when the seed lands (resolving precisely via
        // trackRelations). Those are two different keys, so both run — and
        // LaunchedEffect restarting cancels only ITS OWN coroutine, never the
        // viewModelScope job below. Unguarded, the two race and whichever network
        // round-trip lands LAST wins: for two Tidal artists sharing an exact
        // name, that is the WRONG artist's bio on the page. Same cancel-and-
        // relaunch idiom as searchDebounceJob/loginJob.
        artistDetailJob?.cancel()
        artistDetail.value = null
        artistAlbums.value = emptyList()
        artistTopTracks.value = emptyList()
        artistDetailJob = viewModelScope.launch {
            artistDetailLoading.value = true
            try {
                val artistId = tidalArtistId
                    ?: seedTidalTrackId?.let { Graph.tidalMetadata.trackRelations(it).getOrNull()?.artistId }
                    ?: Graph.tidal.searchArtistIdExact(artistName)
                    ?: return@launch
                Graph.tidalMetadata.artistDetail(artistId).onSuccess {
                    if (artistDetailKey == key) artistDetail.value = it
                }
                // Internal-v1, per the artist-lists decision. Neither throws
                // (both return empty on any failure), so neither can take down
                // the official detail resolved just above, nor each other.
                // Published one at a time, in this order, so the discography row
                // appears as soon as it lands instead of waiting on top tracks.
                val albums = Graph.tidal.artistAlbums(artistId)
                if (artistDetailKey == key) artistAlbums.value = albums
                val top = Graph.tidal.artistTopTracks(artistId)
                if (artistDetailKey == key) artistTopTracks.value = top
            } finally {
                if (artistDetailKey == key) artistDetailLoading.value = false
            }
        }
    }

    /**
     * Load remote album enrichment. Symmetric to [loadArtistDetail]; consumed by
     * AlbumDetailScreen (Task 7) — including its cancel-and-key-guard, which is
     * load-bearing for exactly the same reason and is documented there.
     */
    fun loadAlbumDetail(album: String, artist: String, tidalAlbumId: String?, seedTidalTrackId: String?) {
        val key = "$album|$artist|$tidalAlbumId|$seedTidalTrackId"
        if (key == albumDetailKey) return
        albumDetailKey = key
        albumDetailJob?.cancel()
        albumDetail.value = null
        if (tidalAlbumId == null && seedTidalTrackId == null) {
            // Nothing to load — but this path must still clear the spinner, and
            // it is the ONE path that nothing else will. The cancelled job above
            // does not do it: its `finally` is key-guarded, and [albumDetailKey]
            // is already this key, not its own — that guard is what stops a
            // superseded load from clearing the WINNER's spinner early. Here
            // there IS no winner to launch, so the flag would stay true forever
            // (Tidal album still in flight -> back -> a local-only album), and
            // an album with no tracks to fall back on would spin instead of
            // settling to its EmptyState. [loadArtistDetail] has no early return
            // and so cannot reach this state; this asymmetry is why the check
            // lives here rather than in the shared `finally`.
            albumDetailLoading.value = false
            return
        }
        albumDetailJob = viewModelScope.launch {
            albumDetailLoading.value = true
            try {
                val albumId = tidalAlbumId
                    ?: Graph.tidalMetadata.trackRelations(seedTidalTrackId!!).getOrNull()?.albumId
                    ?: return@launch
                Graph.tidalMetadata.albumDetail(albumId).onSuccess {
                    if (albumDetailKey == key) albumDetail.value = it
                }
            } finally {
                if (albumDetailKey == key) albumDetailLoading.value = false
            }
        }
    }

    val trackDetail = MutableStateFlow<io.github.auxen.provider.tidal.TidalTrackDetail?>(null)
    val trackDetailLoading = MutableStateFlow(false)
    private var trackDetailToken = 0L
    private var trackDetailJob: Job? = null

    /**
     * Load credits + lyrics for a Tidal track (its sourceId IS the catalog id —
     * no resolution needed).
     *
     * **The token guard is the same stale-write mechanism [loadArtistDetail] and
     * [loadAlbumDetail] document at length**, and it is load-bearing here for the
     * same reason: the info sheet is opened per-track, so long-pressing track A,
     * dismissing mid-flight and opening track B leaves A's request in the air. If
     * A lands last, its `onSuccess` would clobber B's — and since the sheet reads
     * its header from [TidalTrackDetail.title], the user would sit on a sheet
     * showing A's title, A's credits and A's lyrics with no way to tell it is
     * stale except to notice the title. Cancelling A helps (unlike the
     * internal-v1 reads, `Graph.tidalMetadata.trackDetail` lets a
     * `CancellationException` propagate — `mapCatching` never sees it, because
     * the client's own `withContext` rethrows on the way out), but cancellation
     * is not instantaneous and A's `finally` still races B's spinner. The guard
     * is what actually settles both.
     *
     * **A monotonic token, not the siblings' string key** — deliberately. Their
     * key doubles as a dedup ("same artist → don't refetch"); this load must NOT
     * dedup, because [clearTrackDetail] empties the flow on every dismiss, so
     * re-opening the SAME track has to refetch or the sheet would stay blank
     * forever. The token identifies the newest load and nothing else.
     */
    fun loadTrackDetail(track: Track) {
        trackDetail.value = null
        if (!track.isTidal) return
        val token = ++trackDetailToken
        trackDetailJob?.cancel()
        trackDetailJob = viewModelScope.launch {
            trackDetailLoading.value = true
            try {
                Graph.tidalMetadata.trackDetail(track.sourceId).onSuccess {
                    if (trackDetailToken == token) trackDetail.value = it
                }
            } finally {
                if (trackDetailToken == token) trackDetailLoading.value = false
            }
        }
    }

    fun clearTrackDetail() { trackDetail.value = null }

    private fun libraryTabName(index: Int) = when (index) {
        0 -> "albums"
        1 -> "artists"
        else -> "tracks"
    }

    private fun restoreLibraryState() {
        viewModelScope.launch {
            runCatching {
                libraryTab.value = Graph.library.getSetting("library_tab")?.toIntOrNull()?.coerceIn(0, 2) ?: 0
                restoreLibrarySortFor(libraryTab.value)
                homeFilter.value = Graph.library.getSetting("home_filter") ?: "all"
                collectionFilter.value = Graph.library.getSetting("collection_filter") ?: "all"
                themeMode.value = ThemeMode.fromSetting(Graph.library.getSetting("color_scheme"))
                sourcePriority.value = Graph.library.sourcePriority()
            }
        }
    }

    private suspend fun restoreLibrarySortFor(tab: Int) {
        val name = libraryTabName(tab)
        librarySort.value = Graph.library.getSetting("library_sort_$name")
            ?.let { stored -> LibrarySort.entries.firstOrNull { it.name == stored } }
            ?: LibrarySort.RECENTLY_ADDED
        librarySortAscending.value = Graph.library.getSetting("library_dir_$name") != "desc"
    }

    fun setLibraryTab(index: Int) {
        libraryTab.value = index
        viewModelScope.launch {
            runCatching {
                Graph.library.setSetting("library_tab", index.toString())
                restoreLibrarySortFor(index)
            }
        }
    }

    fun setLibrarySort(sort: LibrarySort) {
        librarySort.value = sort
        viewModelScope.launch {
            runCatching { Graph.library.setSetting("library_sort_${libraryTabName(libraryTab.value)}", sort.name) }
        }
    }

    fun toggleLibrarySortDirection() {
        librarySortAscending.value = !librarySortAscending.value
        viewModelScope.launch {
            runCatching {
                Graph.library.setSetting(
                    "library_dir_${libraryTabName(libraryTab.value)}",
                    if (librarySortAscending.value) "asc" else "desc",
                )
            }
        }
    }

    /** Replace the queue with [tracks] and play — desktop Play All / Shuffle. */
    fun playAll(tracks: List<Track>, shuffled: Boolean = false) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val c = controller ?: return@launch
            val ordered = if (shuffled) tracks.shuffled() else tracks
            runCatching { ordered.forEach { Graph.library.upsert(it) } }
            val items = ordered.mapNotNull { runCatching { Graph.mediaItemFor(it) }.getOrNull() }
            if (items.isEmpty()) return@launch
            // The queue's static order is the source of truth — Play All is
            // in-order and Shuffle pre-randomizes client-side (desktop
            // semantics), so the player's shuffle mode must not reorder it.
            c.shuffleModeEnabled = false
            c.setMediaItems(items)
            c.prepare()
            c.play()
        }
    }

    private suspend fun loadRecentlyPlayed() {
        runCatching { Graph.library.recentlyPlayed() }.onSuccess { recentlyPlayed.value = it }
    }

    fun setHomeFilter(value: String) {
        homeFilter.value = value
        viewModelScope.launch { runCatching { Graph.library.setSetting("home_filter", value) } }
    }

    fun setCollectionFilter(value: String) {
        collectionFilter.value = value
        viewModelScope.launch { runCatching { Graph.library.setSetting("collection_filter", value) } }
    }

    fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
        viewModelScope.launch { runCatching { Graph.library.setSetting("color_scheme", mode.settingValue) } }
    }

    fun setSourcePriority(priority: SourcePriority) {
        sourcePriority.value = priority
        viewModelScope.launch { runCatching { Graph.library.setSourcePriority(priority) } }
    }

    /** Refresh Home data: recently added (MediaStore) + recently played (DB). */
    fun refreshHome() {
        viewModelScope.launch {
            homeLoading.value = true
            try {
                loadRecentlyPlayed()
                runCatching { Graph.local.recentlyAdded() }.onSuccess { recentlyAdded.value = it }
            } finally {
                homeLoading.value = false
            }
        }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            val key = "${track.source.name}:${track.sourceId}"
            runCatching { Graph.library.setFavorite(track, key !in favoriteKeys.value) }
        }
    }

    private var loginJob: Job? = null

    init {
        val context = app.applicationContext
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            // Seed state so a screen opened before any event shows the real values.
            shuffleEnabled = c.shuffleModeEnabled
            repeatMode = c.repeatMode
            isPlaying = c.isPlaying
            // Media3 doesn't replay metadata to new listeners — seed from the
            // restored queue so the mini bar appears without a player event.
            if (c.mediaItemCount > 0) {
                val metadata = c.mediaMetadata
                nowPlaying = metadata
                currentTrack = Graph.trackFor(metadata)
            }
            refreshQueue(c)
            c.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    nowPlaying = mediaMetadata
                    currentTrack = Graph.trackFor(mediaMetadata)
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    shuffleEnabled = shuffleModeEnabled
                }

                override fun onRepeatModeChanged(mode: Int) {
                    repeatMode = mode
                }

                // The queue's actual contents (add/remove/reorder) surface here,
                // not onMediaMetadataChanged -- that one only fires for the
                // CURRENT item. onMediaItemTransition also covers plain
                // track-advance, where the timeline itself doesn't change but
                // the playing index does.
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    refreshQueue(c)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    refreshQueue(c)
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("Auxen", "Playback error", error)
                    val chain = generateSequence(error as Throwable) { it.cause }
                        .joinToString(" ← ") { t ->
                            t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
                        }
                    playbackError.value = "${error.errorCodeName}\n$chain"
                }
            })
        }, MoreExecutors.directExecutor())

        viewModelScope.launch {
            while (isActive) {
                controller?.let {
                    positionMs.value = it.currentPosition.coerceAtLeast(0)
                    durationMs.value = it.duration.coerceAtLeast(0)
                }
                delay(500)
            }
        }

        viewModelScope.launch {
            if (Graph.tidal.restoreSession()) _tidalLogin.value = TidalLoginState.LoggedIn
        }

        // Seed [collectionLoading]: resolve the favorites + playlists DB reads
        // once so Collection shows a spinner (not its EmptyState) until they
        // first emit. try/finally guarantees the flag clears even if a flow
        // throws, so the spinner can never stick.
        viewModelScope.launch {
            try {
                Graph.library.favorites().first()
                Graph.library.playlists().first()
            } finally {
                collectionLoading.value = false
            }
        }

        restoreLibraryState()
    }

    fun loadLibrary() {
        viewModelScope.launch {
            libraryLoading.value = true
            try {
                runCatching { Graph.local.allTracks() }.onSuccess { localTracks.value = it }
            } finally {
                libraryLoading.value = false
            }
        }
    }

    /** Search local library and Tidal, collapsing duplicates — like the desktop app. */
    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            searchInFlight = true
            val local = runCatching { Graph.local.search(query) }.getOrDefault(emptyList())
            val tidal = runCatching { Graph.tidal.search(query) }.getOrDefault(emptyList())
            val priority = runCatching { Graph.library.sourcePriority() }
                .getOrDefault(SourcePriority.PREFER_QUALITY)
            val merged = DuplicateResolver.merge(local, tidal, priority)
            // Latest-query-wins: drop stale responses that resolve after the
            // field changed or was cleared.
            if (query == searchQuery.value) {
                searchResults.value = merged
                searchInFlight = false
            }
        }
    }

    /** Debounced live search — desktop SearchView's 300ms debounce. */
    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
        searchDebounceJob?.cancel()
        if (query.isBlank()) {
            searchResults.value = emptyList()
            searchInFlight = false
            return
        }
        searchDebounceJob = viewModelScope.launch {
            delay(300)
            search(query)
        }
    }

    /** Record the current query in history (called on keyboard submit). */
    fun commitSearch() {
        val query = searchQuery.value
        if (query.isBlank()) return
        viewModelScope.launch { runCatching { Graph.library.addSearchHistory(query) } }
    }

    fun deleteSearchHistory(query: String) {
        viewModelScope.launch { runCatching { Graph.library.deleteSearchHistoryItem(query) } }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { runCatching { Graph.library.clearSearchHistory() } }
    }

    fun play(track: Track) {
        viewModelScope.launch {
            runCatching { Graph.library.upsert(track) }
            val c = controller ?: return@launch
            runCatching { Graph.mediaItemFor(track) }.onSuccess { item ->
                c.setMediaItem(item)
                c.prepare()
                c.play()
            }
        }
    }

    fun enqueue(track: Track) {
        viewModelScope.launch {
            runCatching { Graph.library.upsert(track) }
            val c = controller ?: return@launch
            runCatching { Graph.mediaItemFor(track) }.onSuccess { c.addMediaItem(it) }
        }
    }

    /** Insert after the current queue item — desktop "Play Next". */
    fun playNext(track: Track) {
        viewModelScope.launch {
            runCatching { Graph.library.upsert(track) }
            val c = controller ?: return@launch
            val item = runCatching { Graph.mediaItemFor(track) }.getOrNull() ?: return@launch
            val index = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
            c.addMediaItem(index, item)
        }
    }

    fun addToPlaylist(track: Track, playlistId: Long) {
        viewModelScope.launch { runCatching { Graph.library.addTrackToPlaylist(track, playlistId) } }
    }

    fun createPlaylistAndAdd(track: Track, name: String) {
        viewModelScope.launch {
            runCatching {
                val id = Graph.library.createPlaylist(name)
                Graph.library.addTrackToPlaylist(track, id)
            }
        }
    }

    /** Ordered tracks of the open playlist — reloaded after every membership/order change. */
    val playlistTracks = MutableStateFlow<List<Track>>(emptyList())

    fun loadPlaylist(id: Long) {
        viewModelScope.launch {
            runCatching { Graph.library.playlistTracks(id) }.onSuccess { playlistTracks.value = it }
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { runCatching { Graph.library.renamePlaylist(id, name) } }
    }

    fun recolorPlaylist(id: Long, color: String) {
        viewModelScope.launch { runCatching { Graph.library.recolorPlaylist(id, color) } }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { runCatching { Graph.library.deletePlaylist(id) } }
    }

    fun removeFromPlaylist(id: Long, track: Track) {
        viewModelScope.launch {
            runCatching { Graph.library.removeFromPlaylist(id, track) }
            loadPlaylist(id)
        }
    }

    fun movePlaylistTrack(id: Long, from: Int, to: Int) {
        viewModelScope.launch {
            runCatching { Graph.library.movePlaylistTrack(id, from, to) }
            loadPlaylist(id)
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.playbackState == Player.STATE_IDLE -> {
                // Restored-from-disk queue: prepare (which lazily resolves
                // the current stream) and then play.
                c.prepare()
                c.play()
            }
            else -> c.play()
        }
    }

    fun skipNext() {
        controller?.seekToNext()
    }

    fun skipPrevious() {
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    /** Off -> All -> One -> Off, like the desktop repeat button. */
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun startTidalLogin() {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            runCatching {
                val auth = Graph.tidalAuth.requestDeviceAuthorization()
                _tidalLogin.value = TidalLoginState.AwaitingApproval("https://${auth.verificationUriComplete}")
                Graph.tidalAuth.awaitLogin(auth)
                check(Graph.tidal.restoreSession()) { "Session bootstrap failed" }
                _tidalLogin.value = TidalLoginState.LoggedIn
            }.onFailure {
                _tidalLogin.value = TidalLoginState.Error(it.message ?: "Login failed")
            }
        }
    }

    fun tidalLogout() {
        loginJob?.cancel()
        viewModelScope.launch {
            Graph.tidal.logout()
            _tidalLogin.value = TidalLoginState.LoggedOut
        }
    }

    override fun onCleared() {
        controller?.release()
        tidalOfficialProbePlayer?.release()
        super.onCleared()
    }
}
