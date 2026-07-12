package io.github.auxen.ui

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import io.github.auxen.Graph
import io.github.auxen.data.LibrarySort
import io.github.auxen.db.PlaylistEntity
import io.github.auxen.matching.DuplicateResolver
import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track
import io.github.auxen.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    /** Selected Library tab (0=Albums, 1=Artists, 2=Tracks), persisted per session. */
    val libraryTab = MutableStateFlow(0)

    /** Active sort for the current Library tab, persisted per tab. */
    val librarySort = MutableStateFlow(LibrarySort.RECENTLY_ADDED)

    /** Sort direction for the current Library tab; true = ascending. */
    val librarySortAscending = MutableStateFlow(true)

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
            c.setMediaItems(items)
            c.prepare()
            c.play()
        }
    }

    fun refreshRecentlyPlayed() {
        viewModelScope.launch {
            runCatching { Graph.library.recentlyPlayed() }.onSuccess { recentlyPlayed.value = it }
        }
    }

    fun setHomeFilter(value: String) {
        homeFilter.value = value
        viewModelScope.launch { runCatching { Graph.library.setSetting("home_filter", value) } }
    }

    fun setCollectionFilter(value: String) {
        collectionFilter.value = value
        viewModelScope.launch { runCatching { Graph.library.setSetting("collection_filter", value) } }
    }

    /** Refresh Home data: recently added (MediaStore) + recently played (DB). */
    fun refreshHome() {
        refreshRecentlyPlayed()
        viewModelScope.launch {
            runCatching { Graph.local.recentlyAdded() }.onSuccess { recentlyAdded.value = it }
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
                currentTrack = metadata.extras
                    ?.getString(Graph.TRACK_EXTRA_KEY)
                    ?.let { encoded -> runCatching { Graph.json.decodeFromString<Track>(encoded) }.getOrNull() }
            }
            c.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    nowPlaying = mediaMetadata
                    currentTrack = mediaMetadata.extras
                        ?.getString(Graph.TRACK_EXTRA_KEY)
                        ?.let { encoded -> runCatching { Graph.json.decodeFromString<Track>(encoded) }.getOrNull() }
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

        restoreLibraryState()
    }

    fun loadLibrary() {
        viewModelScope.launch {
            runCatching { Graph.local.allTracks() }.onSuccess { localTracks.value = it }
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
        super.onCleared()
    }
}
