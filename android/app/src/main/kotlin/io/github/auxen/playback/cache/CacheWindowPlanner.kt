package io.github.auxen.playback.cache

import io.github.auxen.db.CachedTrackEntity
import io.github.auxen.model.Track
import kotlin.math.roundToLong

/** Free space the cache always leaves on the device so it never fills the phone. */
const val DEVICE_HEADROOM_BYTES = 1_000_000_000L

// Mirrors Media3's C.INDEX_UNSET without pulling an Android type into pure code.
private const val INDEX_UNSET = -1

// Missing metadata falls back to a long hi-res song, so the guess errs high, not low.
private const val FALLBACK_DURATION_SECONDS = 300.0
private const val FALLBACK_BIT_DEPTH = 24
private const val FALLBACK_SAMPLE_RATE_HZ = 96_000
private const val CHANNELS = 2
private const val FLAC_COMPRESSION_RATIO = 0.7

/**
 * Queue indexes in the order the player will reach them, starting at [start].
 * Stops at the end of the queue or the first index it has already seen, so
 * repeat-all and shuffle loops terminate.
 */
fun playOrder(start: Int, next: (Int) -> Int): List<Int> {
    val seen = LinkedHashSet<Int>()
    var index = start
    while (index != INDEX_UNSET && seen.add(index)) {
        index = next(index)
    }
    return seen.toList()
}

/** Rough on-disk size of a Tidal stream: raw PCM bits shrunk by a typical FLAC ratio. */
fun estimateBytes(track: Track): Long {
    val seconds = track.durationSeconds?.takeIf { it > 0 && it.isFinite() } ?: FALLBACK_DURATION_SECONDS
    val bitDepth = track.bitDepth?.takeIf { it > 0 } ?: FALLBACK_BIT_DEPTH
    val sampleRate = track.sampleRateHz?.takeIf { it > 0 } ?: FALLBACK_SAMPLE_RATE_HZ
    // Rounded, not truncated: 0.7 isn't exact in binary and would shave a byte off.
    return (seconds * bitDepth * sampleRate * CHANNELS * FLAC_COMPRESSION_RATIO / 8).roundToLong()
}

/**
 * Tidal tracks along [order], first occurrence only. Local tracks are skipped
 * because they're already on the device.
 */
fun cacheCandidates(
    order: List<Int>,
    mediaIdAt: (Int) -> String,
    trackAt: (Int) -> Track?,
): List<CacheCandidate> = order
    .mapNotNull { index ->
        val trackId = tidalTrackIdOf(mediaIdAt(index)) ?: return@mapNotNull null
        val track = trackAt(index) ?: return@mapNotNull null
        CacheCandidate(trackId, track, estimateBytes(track))
    }
    .distinctBy { it.trackId }

/**
 * Bytes the cache may hold: the user's limit, cut down so the device keeps
 * [headroomBytes] free. Space already cached counts as usable since evicting
 * it gives it straight back.
 */
fun capacityBytes(
    limitBytes: Long,
    usedBytes: Long,
    freeDeviceBytes: Long,
    headroomBytes: Long = DEVICE_HEADROOM_BYTES,
): Long = minOf(limitBytes, usedBytes + (freeDeviceBytes - headroomBytes).coerceAtLeast(0L))

// Clamps instead of wrapping, so one absurd size can't turn a running total
// negative and make the rest of the queue look like it fits.
private fun satAdd(a: Long, b: Long): Long =
    if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

/** The songs to keep cached, in play order, and the subset still to fetch. */
data class CacheWindow(val trackIds: List<String>, val toDownload: List<CacheCandidate>)

/**
 * Takes [candidates] in order while they fit in [capacityBytes]. Stops at the
 * first song that doesn't fit, even if a later smaller one would, so the
 * window is always an unbroken run of what plays next.
 */
fun planWindow(
    candidates: List<CacheCandidate>,
    cached: Map<String, CachedTrackEntity>,
    capacityBytes: Long,
): CacheWindow {
    val runningTotals = candidates
        .runningFold(0L) { total, c -> satAdd(total, cached[c.trackId]?.sizeBytes ?: c.estimatedBytes) }
        .drop(1)
    val fitCount = runningTotals.takeWhile { it <= capacityBytes }.size
    val window = candidates.take(fitCount)
    return CacheWindow(
        trackIds = window.map { it.trackId },
        toDownload = window.filter { it.trackId !in cached },
    )
}

// Played songs go first (oldest play first). Never-played ones are usually
// prefetched songs not heard yet, so they go last (oldest cache first).
private val EVICTION_ORDER = compareBy<CachedTrackEntity>(
    { it.lastPlayedAtMillis == null },
    { it.lastPlayedAtMillis ?: it.cachedAtMillis },
    { it.tidalTrackId },
)

/**
 * Track ids to delete, in eviction order, until at least [bytesToFree] is
 * freed. Returns every unprotected row if that still isn't enough.
 */
fun pickEvictions(
    cached: Collection<CachedTrackEntity>,
    protectedIds: Set<String>,
    bytesToFree: Long,
): List<String> {
    val ordered = cached
        .filter { it.tidalTrackId !in protectedIds }
        .sortedWith(EVICTION_ORDER)
    val freedBefore = ordered.runningFold(0L) { total, row -> satAdd(total, row.sizeBytes) }
    val evictCount = freedBefore.take(ordered.size).takeWhile { it < bytesToFree }.size
    return ordered.take(evictCount).map { it.tidalTrackId }
}
