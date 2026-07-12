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
import io.github.auxen.matching.DuplicateResolver
import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track
import io.github.auxen.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    val localTracks = MutableStateFlow<List<Track>>(emptyList())
    val searchResults = MutableStateFlow<List<Track>>(emptyList())
    var searchInFlight by mutableStateOf(false)
        private set

    private val _tidalLogin = MutableStateFlow<TidalLoginState>(TidalLoginState.LoggedOut)
    val tidalLogin: StateFlow<TidalLoginState> = _tidalLogin

    private var loginJob: Job? = null

    init {
        val context = app.applicationContext
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    nowPlaying = mediaMetadata
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            })
        }, MoreExecutors.directExecutor())

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
            val c = controller ?: return@launch
            runCatching { Graph.mediaItemFor(track) }.onSuccess { c.addMediaItem(it) }
        }
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
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
