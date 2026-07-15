package io.github.auxen.provider.tidal

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Builds a standalone, throwaway [ExoPlayer] (NOT the app's real
 * [io.github.auxen.playback.PlaybackService] pipeline) fed directly with
 * [manifestUrl], purely to confirm the official API's URL is actually
 * playable end-to-end through Media3's own machinery -- the go/no-go
 * spike's decisive on-device check (Tidal official-API migration, Task 1).
 * [onResult] fires once, either when playback reaches [Player.STATE_READY]
 * (the stream decoded successfully -- confirmed, not necessarily "played to
 * completion") or on the first playback error.
 *
 * **DSP-chain caveat, deliberate scope cut:** unlike the real pipeline
 * (`PlaybackService.EqRenderersFactory`), this throwaway player runs
 * Media3's stock renderers with no EQ/AutoEq/bass/balance/limiter/reverb --
 * confirming those requires refactoring `EqRenderersFactory` for reuse
 * outside `PlaybackService`, out of scope for a spike whose only question
 * is "does the official API serve a genuinely playable full-length stream
 * at all." Flagged explicitly in the Task 1 report.
 *
 * The caller owns the returned player's lifecycle -- release it once
 * [onResult] fires or the caller navigates away.
 */
@UnstableApi
fun probePlayback(context: Context, manifestUrl: String, onResult: (Result<Unit>) -> Unit): ExoPlayer {
    val player = ExoPlayer.Builder(context).build()
    var resolved = false
    player.addListener(
        object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (!resolved && state == Player.STATE_READY) {
                    resolved = true
                    player.pause()
                    onResult(Result.success(Unit))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!resolved) {
                    resolved = true
                    onResult(Result.failure(error))
                }
            }
        },
    )
    player.setMediaItem(
        MediaItem.Builder()
            .setUri(manifestUrl)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build(),
    )
    player.prepare()
    player.playWhenReady = true
    return player
}
