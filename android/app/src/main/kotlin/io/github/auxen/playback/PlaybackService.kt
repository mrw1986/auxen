package io.github.auxen.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.auxen.dsp.EqController
import io.github.auxen.dsp.ParametricEqProcessor
import io.github.auxen.ui.MainActivity

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

    override fun onCreate() {
        super.onCreate()

        val eqProcessor = ParametricEqProcessor()
        EqController.attachProcessor(eqProcessor)

        val player = ExoPlayer.Builder(this, EqRenderersFactory(this, eqProcessor))
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
        super.onDestroy()
    }
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
