package io.github.auxen.playback.cache

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import io.github.auxen.Graph

/**
 * How a queued Tidal [item] should change now that the cache holds [playback] for it, or
 * nothing. Null means leave it as it is, which covers everything that isn't a Tidal item.
 *
 * A cached progressive song plays through the stable `auxen://tidal/<id>` URI, which
 * [io.github.auxen.playback.TidalUriResolver] maps to the file, so a DASH-swapped copy goes
 * back to it. A cached DASH song needs a DASH item, so it points at the local manifest. An
 * item still pointing into [cacheRootUri] once its entry is gone goes back to the stable URI.
 */
fun queueItemUpdate(item: MediaItem, playback: CachedPlayback?, cacheRootUri: String): MediaItem? {
    val trackId = tidalTrackIdOf(item.mediaId) ?: return null
    val uri = item.localConfiguration?.uri ?: return null
    if (playback == null) {
        return if (uri.toString().startsWith(cacheRootUri)) stableItem(item, trackId) else null
    }
    return when (playback.kind) {
        CacheKind.PROGRESSIVE -> if (uri.scheme == "auxen") null else stableItem(item, trackId)
        CacheKind.DASH -> if (uri.toString() == playback.uri) {
            null
        } else {
            item.buildUpon().setUri(playback.uri).setMimeType(MimeTypes.APPLICATION_MPD).build()
        }
    }
}

/**
 * Applies [queueItemUpdate] to every queued Tidal item but the playing one. That one is never
 * moved onto the cache, which would glitch the song mid-play; it only gets restored when it
 * points into the cache and its entry is gone, keeping its position.
 *
 * Idempotent, so the timeline change its own edits cause can run it again for free. Each item
 * is read fresh because that rerun can happen inside [Player.replaceMediaItem].
 */
fun reconcileQueueWithCache(player: Player, lookup: (String) -> CachedPlayback?, cacheRootUri: String) {
    val playing = player.currentMediaItemIndex
    for (index in 0 until player.mediaItemCount) {
        if (index == playing) continue
        val item = player.getMediaItemAt(index)
        val trackId = tidalTrackIdOf(item.mediaId) ?: continue
        queueItemUpdate(item, lookup(trackId), cacheRootUri)?.let { player.replaceMediaItem(index, it) }
    }
    restorePlayingItem(player, lookup, cacheRootUri)
}

/**
 * Replaces the playing item with [item] without moving the playhead or changing whether it
 * plays. Media3 starts a replaced current item from 0, so this seeks back to where it was.
 */
fun replacePlayingItem(player: Player, item: MediaItem) {
    val index = player.currentMediaItemIndex
    val position = player.currentPosition
    val playWhenReady = player.playWhenReady
    player.replaceMediaItem(index, item)
    player.seekTo(index, position)
    player.playWhenReady = playWhenReady
}

/**
 * Points every unresolved (`auxen`) copy of Tidal [trackId] at the DASH manifest [mpdUri] and
 * returns whether any changed. Copies already on a manifest are left alone. The playing copy
 * keeps its position and play state, so a resumed or restored Hi-Res song doesn't restart.
 */
fun swapDashCopies(player: Player, trackId: String, mpdUri: String): Boolean {
    val targetMediaId = TIDAL_MEDIA_ID_PREFIX + trackId
    var swapped = false
    for (index in 0 until player.mediaItemCount) {
        val queued = player.getMediaItemAt(index)
        if (queued.mediaId != targetMediaId || queued.localConfiguration?.uri?.scheme != "auxen") continue
        val dash = queued.buildUpon().setUri(mpdUri).setMimeType(MimeTypes.APPLICATION_MPD).build()
        if (index == player.currentMediaItemIndex) replacePlayingItem(player, dash) else player.replaceMediaItem(index, dash)
        swapped = true
    }
    return swapped
}

/**
 * True when the playing item is Tidal [trackId] already on a resolved, non-`auxen` URI. A DASH
 * signal for it is then stale: [swapDashCopies] ran after the error was raised but before its
 * callback, so the player only needs preparing again.
 */
fun isPlayingResolvedCopy(player: Player, trackId: String): Boolean {
    val item = player.currentMediaItem ?: return false
    val scheme = item.localConfiguration?.uri?.scheme ?: return false
    return item.mediaId == TIDAL_MEDIA_ID_PREFIX + trackId && scheme != "auxen"
}

/**
 * One key per queue entry, in order: its per-occurrence [Graph.QUEUE_UID_KEY], else its media id.
 * Swaps rebuild items with `buildUpon`, which keeps the uid, so comparing two of these tells a
 * real queue change from a swap that only fired PLAYLIST_CHANGED. A new Play All of the same
 * album has the same media ids but fresh uids, so it counts as a change.
 */
fun queueEntryKeys(player: Player): List<String> = List(player.mediaItemCount) { queueEntryKey(player.getMediaItemAt(it)) }

private fun queueEntryKey(item: MediaItem): String =
    item.mediaMetadata.extras?.getString(Graph.QUEUE_UID_KEY) ?: item.mediaId

private fun restorePlayingItem(player: Player, lookup: (String) -> CachedPlayback?, cacheRootUri: String) {
    val index = player.currentMediaItemIndex
    if (index !in 0 until player.mediaItemCount) return
    val item = player.getMediaItemAt(index)
    val trackId = tidalTrackIdOf(item.mediaId) ?: return
    if (lookup(trackId) != null) return
    val restored = queueItemUpdate(item, null, cacheRootUri) ?: return
    replacePlayingItem(player, restored)
}

private fun stableItem(item: MediaItem, trackId: String): MediaItem =
    item.buildUpon().setUri("auxen://tidal/$trackId").setMimeType(null).build()
