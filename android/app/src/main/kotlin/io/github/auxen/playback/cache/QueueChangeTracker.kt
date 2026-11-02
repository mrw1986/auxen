package io.github.auxen.playback.cache

/**
 * Tells a real queue change from a cache or DASH swap. Both fire PLAYLIST_CHANGED, but a swap
 * keeps every entry's key. Feed it [queueEntryKeys] on every PLAYLIST_CHANGED. Main thread only.
 */
class QueueChangeTracker {

    /** The keys from the last [changed] or [seed] call. Empty to start with. */
    var lastKeys: List<String> = emptyList()
        private set

    /** True when [keys] differ from [lastKeys]. Remembers [keys] either way. */
    fun changed(keys: List<String>): Boolean {
        val changed = keys != lastKeys
        lastKeys = keys
        return changed
    }

    /** Sets [lastKeys] for a queue loaded before anything was listening. */
    fun seed(keys: List<String>) {
        lastKeys = keys
    }
}
