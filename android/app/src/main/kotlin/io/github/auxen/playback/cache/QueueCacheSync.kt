package io.github.auxen.playback.cache

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player

private const val TIDAL_PREFIX = "TIDAL:"

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
    val trackId = tidalTrackId(item) ?: return null
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
        val trackId = tidalTrackId(item) ?: continue
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
 * The queue's media ids in order. Cache swaps keep them, so comparing two of these tells a
 * real queue change from a swap that only fired PLAYLIST_CHANGED.
 */
fun queueMediaIds(player: Player): List<String> = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

private fun restorePlayingItem(player: Player, lookup: (String) -> CachedPlayback?, cacheRootUri: String) {
    val index = player.currentMediaItemIndex
    if (index !in 0 until player.mediaItemCount) return
    val item = player.getMediaItemAt(index)
    val trackId = tidalTrackId(item) ?: return
    if (lookup(trackId) != null) return
    val restored = queueItemUpdate(item, null, cacheRootUri) ?: return
    replacePlayingItem(player, restored)
}

private fun tidalTrackId(item: MediaItem): String? =
    item.mediaId.takeIf { it.startsWith(TIDAL_PREFIX) }?.removePrefix(TIDAL_PREFIX)?.takeIf { it.isNotEmpty() }

private fun stableItem(item: MediaItem, trackId: String): MediaItem =
    item.buildUpon().setUri("auxen://tidal/$trackId").setMimeType(null).build()
