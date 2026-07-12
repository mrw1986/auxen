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
    val searchResults = MutableStateFlow<List<Track>>(emptyList())
    var searchInFlight by mutableStateOf(false)
        private set

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

    /** Current playback position and track duration, in milliseconds — polled while a controller exists. */
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)

    fun refreshRecentlyPlayed() {
        viewModelScope.launch {
            runCatching { Graph.library.recentlyPlayed() }.onSuccess { recentlyPlayed.value = it }
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
            searchResults.value = DuplicateResolver.merge(local, tidal, priority)
            searchInFlight = false
        }
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
