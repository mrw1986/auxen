package io.github.auxen.playback.browse

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition

/**
 * Bridges browse -> playback. Android Auto plays an item by sending back only its
 * mediaId (URI/extras stripped), so this holds the full playable MediaItems last
 * served for each browse folder, keyed by mediaId, and reconstructs a play queue
 * from them: [expand] turns a tapped leaf into "its sibling list, starting at it";
 * [resolve] returns the full item for a single id. Not thread-safe on its own;
 * the service confines all access to its session callback thread.
 */
class BrowseQueueResolver {
    private val parentToItems = LinkedHashMap<String, List<MediaItem>>()
    private val idToItem = HashMap<String, MediaItem>()
    private val idToParent = HashMap<String, String>()

    fun remember(parentId: String, playableChildren: List<MediaItem>) {
        // Drop the old membership for this parent before rebinding (so a removed
        // sibling doesn't linger), then re-index.
        parentToItems[parentId]?.forEach { old ->
            if (idToParent[old.mediaId] == parentId) {
                idToParent.remove(old.mediaId)
                idToItem.remove(old.mediaId)
            }
        }
        parentToItems[parentId] = playableChildren
        playableChildren.forEach {
            idToItem[it.mediaId] = it
            idToParent[it.mediaId] = parentId
        }
    }

    fun expand(tappedMediaId: String): MediaItemsWithStartPosition? {
        val parent = idToParent[tappedMediaId] ?: return null
        val list = parentToItems[parent] ?: return null
        val index = list.indexOfFirst { it.mediaId == tappedMediaId }
        if (index < 0) return null
        return MediaItemsWithStartPosition(list, index, 0L)
    }

    fun resolve(mediaId: String): MediaItem? = idToItem[mediaId]
}
