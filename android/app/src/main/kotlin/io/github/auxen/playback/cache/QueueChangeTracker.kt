package io.github.auxen.playback.cache

/**
 * Tells a real queue change from a cache or DASH swap. Both fire PLAYLIST_CHANGED, but a swap
 * keeps the queue's media ids. Feed it [queueMediaIds] on every PLAYLIST_CHANGED. Main thread only.
 */
class QueueChangeTracker {

    /** The ids from the last [changed] or [seed] call. Empty to start with. */
    var lastIds: List<String> = emptyList()
        private set

    /** True when [ids] differ from [lastIds]. Remembers [ids] either way. */
    fun changed(ids: List<String>): Boolean {
        val changed = ids != lastIds
        lastIds = ids
        return changed
    }

    /** Sets [lastIds] for a queue loaded before anything was listening. */
    fun seed(ids: List<String>) {
        lastIds = ids
    }
}
