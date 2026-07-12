package io.github.auxen.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.auxen.Graph
import io.github.auxen.dsp.EqController
import io.github.auxen.dsp.ParametricEqProcessor
import io.github.auxen.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground media playback service.
 *
 * Media3's [MediaSessionService] gives us everything MPRIS gave the desktop
 * app and more: lockscreen/notification controls, Bluetooth AVRCP, output
 * switching, and (later) Android Auto — all driven by the one MediaSession.
 *
 * The audiophile part is in [EqRenderersFactory]: the EQ AudioProcessor is
 * installed directly into the player's audio sink with float output enabled,
 * so 24-bit content is never truncated to 16-bit on capable devices and the
 * DSP chain runs before the audio ever leaves the app.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Last expiry-recovery attempt (elapsedRealtime), bounding the 4xx retry path. */
    private var lastExpiryRecoveryAtMillis = 0L

    override fun onCreate() {
        super.onCreate()

        val eqProcessor = ParametricEqProcessor()
        EqController.attachProcessor(eqProcessor)

        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this),
            TidalUriResolver(Graph.resolver),
        )

        val player = ExoPlayer.Builder(this, EqRenderersFactory(this, eqProcessor))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mediaId = mediaItem?.mediaId ?: return
                serviceScope.launch { runCatching { Graph.library.recordPlay(mediaId) } }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentPlayer = mediaSession?.player ?: return

                val dash = findCause<TidalDashStreamException>(error)
                if (dash != null) {
                    // Swap every still-unresolved copy of the failing track —
                    // mediaIds are not unique in a playlist, and repairing only
                    // the first copy would loop forever on duplicates.
                    val targetMediaId = "TIDAL:${dash.trackId}"
                    var swapped = false
                    for (i in 0 until currentPlayer.mediaItemCount) {
                        val queued = currentPlayer.getMediaItemAt(i)
                        if (queued.mediaId != targetMediaId) continue
                        if (queued.localConfiguration?.uri?.scheme != "auxen") continue
                        currentPlayer.replaceMediaItem(
                            i,
                            queued.buildUpon()
                                .setUri(dash.streamInfo.uri)
                                .setMimeType(MimeTypes.APPLICATION_MPD)
                                .build(),
                        )
                        swapped = true
                    }
                    if (!swapped) return
                    currentPlayer.prepare()
                    currentPlayer.play()
                    return
                }

                val http = findCause<HttpDataSource.InvalidResponseCodeException>(error)
                val expired = http != null && http.responseCode in intArrayOf(401, 403, 410)
                if (expired) {
                    val anyTidal = (0 until currentPlayer.mediaItemCount)
                        .any { currentPlayer.getMediaItemAt(it).mediaId.startsWith("TIDAL:") }
                    if (!anyTidal) return
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastExpiryRecoveryAtMillis < RETRY_COOLDOWN_MILLIS) return
                    lastExpiryRecoveryAtMillis = now
                    // The expired URL may belong to any queued Tidal item
                    // (current or pre-buffering): drop every cached stream URL
                    // and restore the stable auxen:// URI on previously
                    // DASH-swapped items so re-prepare can re-resolve them.
                    Graph.resolver.invalidateAll()
                    for (i in 0 until currentPlayer.mediaItemCount) {
                        val queued = currentPlayer.getMediaItemAt(i)
                        if (!queued.mediaId.startsWith("TIDAL:")) continue
                        if (queued.localConfiguration?.uri?.scheme == "auxen") continue
                        val restored = queued.buildUpon()
                            .setUri("auxen://tidal/${queued.mediaId.substringAfter(':')}")
                            .setMimeType(null)
                            .build()
                        currentPlayer.replaceMediaItem(i, restored)
                    }
                    currentPlayer.prepare()
                    currentPlayer.play()
                }
            }
        })

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}

private const val RETRY_COOLDOWN_MILLIS = 60_000L

private inline fun <reified T : Throwable> findCause(error: Throwable): T? {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is T) return cause
        cause = cause.cause
    }
    return null
}

/** Renderers factory that injects the EQ into a float-output audio sink. */
@UnstableApi
private class EqRenderersFactory(
    context: Context,
    private val eqProcessor: ParametricEqProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        // Float output keeps 24-bit sources bit-exact through the EQ; on
        // devices without float support the sink falls back automatically.
        .setEnableFloatOutput(true)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(eqProcessor))
        .build()
}
