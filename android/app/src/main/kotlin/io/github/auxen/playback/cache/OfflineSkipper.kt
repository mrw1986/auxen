package io.github.auxen.playback.cache

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.github.auxen.model.Source
import io.github.auxen.playback.isOfflineFailure

private val LOCAL_MEDIA_ID_PREFIX = "${Source.LOCAL.name}:"

/** How long a held player error still counts as the reason playback stopped. */
const val HELD_ERROR_MAX_AGE_MS = 60_000L

/** The slice of the player the offline skipper reads and drives. */
interface PlayerQueue {
    val currentIndex: Int
    val positionMs: Long
    val playWhenReady: Boolean
    val playerError: PlaybackException?
    fun mediaIdAt(index: Int): String
    fun uriAt(index: Int): String?
    fun playOrderFrom(index: Int): List<Int>
    fun replaceUri(index: Int, uri: String, mimeType: String?)
    fun seekTo(index: Int, positionMs: Long)
    fun prepareAndPlay()
    fun pause()
}

/**
 * [PlayerQueue] over whatever [player] returns at each call, so a bit-perfect player rebuild
 * needs no rebinding. With no player it reads as an empty queue and ignores commands.
 */
class PlayerQueueAdapter(private val player: () -> Player?) : PlayerQueue {
    override val currentIndex: Int
        get() = player()?.takeIf { it.mediaItemCount > 0 }?.currentMediaItemIndex ?: C.INDEX_UNSET
    override val positionMs: Long get() = player()?.currentPosition ?: 0
    override val playWhenReady: Boolean get() = player()?.playWhenReady ?: false
    override val playerError: PlaybackException? get() = player()?.playerError

    override fun mediaIdAt(index: Int): String = itemAt(index)?.mediaId.orEmpty()

    override fun uriAt(index: Int): String? = itemAt(index)?.localConfiguration?.uri?.toString()

    override fun playOrderFrom(index: Int): List<Int> {
        val p = player()?.takeIf { index in 0 until it.mediaItemCount } ?: return emptyList()
        return playOrder(index) { nextIndexForCache(p, it) }
    }

    override fun replaceUri(index: Int, uri: String, mimeType: String?) {
        val item = itemAt(index) ?: return
        player()?.replaceMediaItem(index, item.buildUpon().setUri(uri).setMimeType(mimeType).build())
    }

    override fun seekTo(index: Int, positionMs: Long) {
        player()?.seekTo(index, positionMs)
    }

    override fun prepareAndPlay() {
        player()?.run {
            prepare()
            play()
        }
    }

    override fun pause() {
        player()?.pause()
    }

    private fun itemAt(index: Int) =
        player()?.takeIf { index in 0 until it.mediaItemCount }?.getMediaItemAt(index)
}

/**
 * The index played after [index], for planning ahead. Repeat-one counts as off: a song on
 * repeat still has the rest of the queue after it once the listener moves on.
 */
internal fun nextIndexForCache(player: Player, index: Int): Int {
    val repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
    return player.currentTimeline.getNextWindowIndex(index, repeatMode, player.shuffleModeEnabled)
}

/** Local songs always play offline; a Tidal song only once it's cached. */
fun isPlayableOffline(mediaId: String, cached: (String) -> CachedPlayback?): Boolean {
    if (mediaId.startsWith(LOCAL_MEDIA_ID_PREFIX)) return true
    val trackId = tidalTrackIdOf(mediaId) ?: return false
    return cached(trackId) != null
}

fun findSkipTarget(order: List<Int>, isPlayable: (Int) -> Boolean): Int? = order.firstOrNull(isPlayable)

/** What [OfflineSkipper] did about the current song. */
sealed interface OfflineAction {
    data object None : OfflineAction
    data class Skipped(val to: Int) : OfflineAction
    data class ResumedFromCache(val atMs: Long) : OfflineAction
    data class PausedNothingAhead(val index: Int, val mediaId: String) : OfflineAction
}

/**
 * Keeps playback going with no network: resumes a cached song where the network dropped it,
 * skips songs that can't play offline (they stay queued) and pauses when nothing ahead can.
 *
 * It only acts while playback is wanted. A paused player is left alone, so a queue restored
 * at startup, or edited while paused, never starts playing on its own.
 */
class OfflineSkipper(
    private val queue: PlayerQueue,
    private val cached: (String) -> CachedPlayback?,
    private val network: () -> NetworkState,
    private val status: OfflineStatus,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    /**
     * Call when a song becomes current or the user hits play. With no validated network a
     * resolve can only fail, so an uncached song is skipped before its error comes back.
     */
    fun onItemStarting(): OfflineAction {
        if (network().online || !queue.playWhenReady) return OfflineAction.None
        val current = queue.currentIndex
        if (current < 0 || isPlayable(current)) return OfflineAction.None
        return handled(skipOrPause(current))
    }

    fun onError(error: PlaybackException): OfflineAction = handleError(error, network().online)

    /**
     * A validated network can outlive the radio, so its loss may come after the error did. An
     * error held longer than [HELD_ERROR_MAX_AGE_MS] is from playback that already stopped, and
     * a later drop mustn't restart it.
     */
    fun onNetworkChanged(state: NetworkState): OfflineAction {
        val error = queue.playerError ?: return OfflineAction.None
        if (clock() - error.timestampMs > HELD_ERROR_MAX_AGE_MS) return OfflineAction.None
        return handleError(error, state.online)
    }

    private fun handleError(error: PlaybackException, online: Boolean): OfflineAction {
        if (!isOfflineFailure(error, online)) return OfflineAction.None
        // Media3 can report a song's error in the same batch as the move onto it. If a skip
        // already moved on, the error belongs to the song skipped past, not the current one.
        if (queue.playerError !== error) return quietBanner()
        val current = queue.currentIndex
        if (current < 0) return OfflineAction.None
        // Nothing to restart, but the UI shouldn't flag an error the next play deals with.
        if (!queue.playWhenReady) return quietBanner()
        val trackId = tidalTrackIdOf(queue.mediaIdAt(current))
        val playback = trackId?.let(cached)
        val action = if (trackId != null && playback != null) {
            resume(current, trackId, playback)
        } else {
            skipOrPause(current)
        }
        return handled(action)
    }

    // Re-preparing the network copy would only fail again, so a song not already on its
    // cached copy moves onto it first. The position is read before that move resets it.
    private fun resume(index: Int, trackId: String, playback: CachedPlayback): OfflineAction {
        val at = queue.positionMs
        val (uri, mimeType) = when (playback.kind) {
            CacheKind.PROGRESSIVE -> stableTidalUri(trackId) to null
            CacheKind.DASH -> playback.uri to MimeTypes.APPLICATION_MPD
        }
        if (queue.uriAt(index) != uri) queue.replaceUri(index, uri, mimeType)
        queue.seekTo(index, at)
        queue.prepareAndPlay()
        return OfflineAction.ResumedFromCache(at)
    }

    private fun skipOrPause(current: Int): OfflineAction {
        val target = findSkipTarget(queue.playOrderFrom(current).drop(1), ::isPlayable)
        if (target != null) {
            queue.seekTo(target, 0)
            queue.prepareAndPlay()
            return OfflineAction.Skipped(target)
        }
        queue.pause()
        status.showNothingCachedAhead()
        return OfflineAction.PausedNothingAhead(current, queue.mediaIdAt(current))
    }

    private fun isPlayable(index: Int): Boolean = isPlayableOffline(queue.mediaIdAt(index), cached)

    private fun quietBanner(): OfflineAction {
        status.markHandled()
        return OfflineAction.None
    }

    private fun handled(action: OfflineAction): OfflineAction {
        if (action != OfflineAction.None) status.markHandled()
        return action
    }
}
