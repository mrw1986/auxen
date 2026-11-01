package io.github.auxen.playback.cache

/**
 * Lets a missing-file recovery run at most once per track every [cooldownMillis], so a cache
 * entry that keeps failing can't loop through evict and re-prepare. A blocked attempt doesn't
 * push the cooldown back. Not thread-safe: the service only calls it on the main thread.
 */
class CacheRecoveryGate(private val cooldownMillis: Long, private val clock: () -> Long) {
    private val acquiredAt = HashMap<String, Long>()

    fun tryAcquire(trackId: String): Boolean {
        val now = clock()
        acquiredAt.values.removeAll { now - it >= cooldownMillis }
        if (trackId in acquiredAt) return false
        acquiredAt[trackId] = now
        return true
    }
}
