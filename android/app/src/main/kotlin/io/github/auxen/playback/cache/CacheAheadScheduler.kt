package io.github.auxen.playback.cache

import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CacheAhead"

/** How long a failed song waits before its next try, by attempt. The last value repeats. */
val RETRY_DELAYS_MILLIS = listOf(30_000L, 120_000L, 600_000L)

/**
 * The queue as the scheduler sees it: [candidates] in play order from the current song, the
 * song playing now, and whether playback is running (or buffering toward it).
 */
data class CacheQueueSnapshot(
    val candidates: List<CacheCandidate>,
    val playingId: String?,
    val active: Boolean,
)

private data class Inputs(
    val queue: CacheQueueSnapshot,
    val settings: CacheSettings,
    val network: NetworkState,
    val cache: CacheSnapshot,
)

/** How one download ended. [sizeBytes] is the song's real size, once the download learned it. */
private sealed class Outcome(val sizeBytes: Long?) {
    class Committed(sizeBytes: Long) : Outcome(sizeBytes)

    /** The song won't fit its budget. Not a failure: the next pass plans with its real size. */
    class TooLarge(sizeBytes: Long) : Outcome(sizeBytes)

    class Failed(sizeBytes: Long? = null) : Outcome(sizeBytes)
}

private class InFlight(val trackId: String, val job: Deferred<Outcome>)

private class Backoff(val attempts: Int, val timer: Job)

/**
 * Downloads the songs coming up in the queue, one at a time and nearest first, so they play
 * from disk.
 *
 * Every decision runs in one coroutine. [update] only posts the queue, and a single collector
 * re-plans whenever the queue, settings, network or cache index changes, or a download or retry
 * timer ends. The download is a child of that loop rather than a `collectLatest` body, so a
 * pause or an unrelated change never cancels it. Only its song leaving the window, losing
 * permission to cache, or [stop] does.
 */
class CacheAheadScheduler(
    scope: CoroutineScope,
    private val index: CacheIndex,
    private val download: suspend (
        trackId: String,
        staging: File,
        maxBytes: Long,
        durationSeconds: Double?,
    ) -> DownloadResult,
    settings: Flow<CacheSettings>,
    network: StateFlow<NetworkState>,
    private val freeDeviceBytes: () -> Long,
) {
    private val queue = MutableStateFlow(CacheQueueSnapshot(emptyList(), playingId = null, active = false))

    // Bumped when a download or a retry timer ends, so the loop looks again.
    private val tick = MutableStateFlow(0L)

    // Loop-only state. Nothing outside the loop coroutine reads or writes these.
    private var inFlight: InFlight? = null
    private var backoffs: Map<String, Backoff> = emptyMap()

    // Real sizes of queued songs, from a finished or too-large download, so a song that came in
    // over its estimate isn't fetched again only to be evicted. A song's size doesn't depend on the
    // cache, so clearing the cache forgets none of these.
    private var knownSizes: Map<String, Long> = emptyMap()

    // Cached bytes plus free device bytes, sampled only while nothing downloads. The download's
    // own staging eats free space as it goes, so sampling mid-download would shrink capacity
    // until that same download no longer fit and got cancelled, over and over.
    private var pool = 0L

    private val loop: Job = scope.launch {
        val loopScope = this
        // The index starts empty. Planning against that would re-fetch cached songs, trim with
        // nothing protected, and race load()'s wipe of the staging folders.
        index.awaitLoaded()
        combine(queue, settings, network, index.snapshot, tick) { q, s, n, c, _ -> Inputs(q, s, n, c) }
            .collect { decideGuarded(loopScope, it) }
    }

    fun update(snapshot: CacheQueueSnapshot) {
        queue.value = snapshot
    }

    /** Cancels the in-flight download and every retry. Nothing starts after this. */
    fun stop() {
        loop.cancel()
    }

    // A pass that throws is logged and dropped. Thrown out of the loop, it would end caching and,
    // with no handler on the service scope, crash the app. Inputs re-trigger the next pass.
    private suspend fun decideGuarded(loopScope: CoroutineScope, inputs: Inputs) {
        try {
            decide(loopScope, inputs)
        } catch (e: Exception) {
            // A real cancel rethrows here. A stray CancellationException while active is a failure.
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "A cache-ahead pass failed (${e.javaClass.simpleName})")
        }
    }

    private suspend fun decide(loopScope: CoroutineScope, inputs: Inputs) {
        val queue = inputs.queue
        settle(loopScope, queue.playingId)
        dropUnqueued(queue)
        val used = inputs.cache.totalBytes
        if (inFlight == null) pool = used + freeDeviceBytes()
        val capacity = capacityBytes(inputs.settings.limitBytes, used, (pool - used).coerceAtLeast(0L))
        val window = planWindow(queue.candidates, inputs.cache.entries, capacity, knownSizes)
        val allowed = mayCache(inputs.settings.mode, inputs.network)
        inFlight?.takeIf { !allowed || it.trackId !in window.trackIds }?.job?.cancel()

        val protectedIds = window.trackIds.toSet() + listOfNotNull(queue.playingId)
        val next = window.toDownload
            .firstOrNull { isReady(it.trackId) }
            ?.takeIf { inFlight == null && allowed && queue.active }
            ?.takeIf { fitsOnceEvicted(inputs.cache, protectedIds, it, capacity) }
        val bytesToFree = used + (next?.estimatedBytes ?: 0L) - capacity
        val usedAfter = evictForRoom(inputs.cache, protectedIds, bytesToFree)
        // The room left is the download's budget, so a song bigger than its estimate stops there.
        if (next != null && usedAfter + next.estimatedBytes <= capacity) start(loopScope, next, capacity - usedAfter)
    }

    /**
     * Whether [next] fits beside the songs that can't be evicted. When it doesn't, evicting the
     * rest would empty the cache and still not make room. That only happens when the playing
     * song is cached but outside the window, since the window itself always fits.
     */
    private fun fitsOnceEvicted(
        cache: CacheSnapshot,
        protectedIds: Set<String>,
        next: CacheCandidate,
        capacity: Long,
    ): Boolean {
        val kept = cache.entries.values.sumOf { if (it.tidalTrackId in protectedIds) it.sizeBytes else 0L }
        return kept + next.estimatedBytes <= capacity
    }

    // Clears a finished download and keeps any size it learned. Success or too large forgets its
    // backoff, failure grows it, cancel leaves it.
    private suspend fun settle(loopScope: CoroutineScope, playingId: String?) {
        val done = inFlight?.takeIf { it.job.isCompleted } ?: return
        inFlight = null
        if (done.job.isCancelled) return
        // Already complete, so this returns at once.
        val outcome = done.job.await()
        outcome.sizeBytes?.let { knownSizes = knownSizes + (done.trackId to it) }
        if (outcome is Outcome.Failed) {
            recordFailure(loopScope, done.trackId)
            return
        }
        backoffs = backoffs - done.trackId
        // Cached while it played, so it's been heard. Unmarked, it would outlive songs marked played.
        if (outcome is Outcome.Committed && done.trackId == playingId) markPlayedQuietly(done.trackId)
    }

    private suspend fun markPlayedQuietly(id: String) {
        try {
            index.markPlayed(id)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Couldn't mark cached track $id played (${e.javaClass.simpleName})")
        }
    }

    private fun recordFailure(loopScope: CoroutineScope, trackId: String) {
        val attempts = (backoffs[trackId]?.attempts ?: 0) + 1
        val wait = RETRY_DELAYS_MILLIS[minOf(attempts, RETRY_DELAYS_MILLIS.size) - 1]
        val timer = loopScope.launch { delay(wait) }
        timer.invokeOnCompletion { bump() }
        backoffs = backoffs + (trackId to Backoff(attempts, timer))
    }

    // A song that left the queue starts fresh if it comes back, and the maps stay queue-sized.
    private fun dropUnqueued(queue: CacheQueueSnapshot) {
        val queued = queue.candidates.mapTo(HashSet()) { it.trackId }
        if (knownSizes.keys.any { it !in queued }) knownSizes = knownSizes.filterKeys { it in queued }
        val stale = backoffs.filterKeys { it !in queued }
        if (stale.isEmpty()) return
        stale.values.forEach { it.timer.cancel() }
        backoffs = backoffs - stale.keys
    }

    private fun isReady(trackId: String): Boolean = backoffs[trackId]?.timer?.isCompleted ?: true

    /**
     * Deletes cached songs outside [protectedIds] (the window and the playing song) until
     * [bytesToFree] is freed or nothing else may go. Returns the bytes still cached afterwards.
     */
    private suspend fun evictForRoom(cache: CacheSnapshot, protectedIds: Set<String>, bytesToFree: Long): Long {
        val used = cache.totalBytes
        if (bytesToFree <= 0L) return used
        val victims = pickEvictions(cache.entries.values, protectedIds, bytesToFree)
        if (victims.isEmpty()) return used
        try {
            index.evict(victims)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Couldn't evict ${victims.size} cached tracks (${e.javaClass.simpleName})")
            return used
        }
        return used - victims.sumOf { cache.entries.getValue(it).sizeBytes }
    }

    private fun start(loopScope: CoroutineScope, candidate: CacheCandidate, maxBytes: Long) {
        val job = loopScope.async { fetch(candidate, maxBytes) }
        job.invokeOnCompletion { bump() }
        inFlight = InFlight(candidate.trackId, job)
    }

    /**
     * Downloads one song, writing at most [maxBytes], and commits it. Anything short of a
     * commit, a cancel included, discards the staging folder. Failures are logged by track id
     * and exception class only, since a message could carry a signed URL.
     */
    private suspend fun fetch(candidate: CacheCandidate, maxBytes: Long): Outcome {
        val id = candidate.trackId
        // Runs on the scope's dispatcher (IO in the service), since it creates folders.
        val staging = try {
            index.staging(id)
        } catch (e: Exception) {
            logFailure(id, e)
            return Outcome.Failed()
        }
        var committed = false
        try {
            val result = download(id, staging, maxBytes, candidate.track.durationSeconds)
            committed = index.commit(id, staging, result, candidate.track)
            return if (committed) Outcome.Committed(result.sizeBytes) else Outcome.Failed(result.sizeBytes)
        } catch (e: SongTooLargeException) {
            return Outcome.TooLarge(e.sizeBytes)
        } catch (e: Exception) {
            // A real cancel rethrows here. A stray CancellationException while active is a failure.
            currentCoroutineContext().ensureActive()
            logFailure(id, e)
            return Outcome.Failed()
        } finally {
            if (!committed) withContext(NonCancellable) { discardQuietly(id, staging) }
        }
    }

    private suspend fun discardQuietly(id: String, staging: File) {
        try {
            index.discard(staging)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't discard the staging folder for track $id (${e.javaClass.simpleName})")
        }
    }

    private fun logFailure(id: String, e: Exception) {
        Log.w(TAG, "Couldn't cache track $id (${e.javaClass.simpleName})")
    }

    private fun bump() = tick.update { it + 1 }
}
