package io.github.auxen.playback.cache

import io.github.auxen.db.CachedTrackEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheWindowPlannerTest {

    private fun track(
        id: String,
        source: Source = Source.TIDAL,
        durationSeconds: Double? = null,
        sampleRateHz: Int? = null,
        bitDepth: Int? = null,
    ) = Track(
        title = "Song $id",
        artist = "Artist",
        source = source,
        sourceId = id,
        durationSeconds = durationSeconds,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
    )

    private fun candidate(id: String, estimatedBytes: Long) =
        CacheCandidate(trackId = id, track = track(id), estimatedBytes = estimatedBytes)

    private fun row(
        id: String,
        sizeBytes: Long = 100L,
        cachedAtMillis: Long = 0L,
        lastPlayedAtMillis: Long? = null,
    ) = CachedTrackEntity(
        tidalTrackId = id,
        kind = CacheKind.PROGRESSIVE.name,
        entryFile = "$id/track.flac",
        sizeBytes = sizeBytes,
        mimeType = "audio/flac",
        sampleRateHz = 44_100,
        bitDepth = 16,
        trackGainDb = null,
        albumGainDb = null,
        cachedAtMillis = cachedAtMillis,
        lastPlayedAtMillis = lastPlayedAtMillis,
        trackJson = "{}",
    )

    // playOrder

    @Test fun `play order walks forward to the end`() {
        assertEquals(listOf(2, 3, 4), playOrder(2) { if (it < 4) it + 1 else -1 })
    }

    @Test fun `play order stops when repeat-all loops`() {
        assertEquals(listOf(1, 2, 0), playOrder(1) { (it + 1) % 3 })
    }

    @Test fun `play order follows a shuffled next`() {
        val next = mapOf(0 to 2, 2 to 1, 1 to -1)
        assertEquals(listOf(0, 2, 1), playOrder(0) { next.getValue(it) })
    }

    @Test fun `play order with a self loop is just the start`() {
        assertEquals(listOf(3), playOrder(3) { it })
    }

    // estimateBytes

    @Test fun `estimate uses duration and quality`() {
        val t = track("1", durationSeconds = 240.0, sampleRateHz = 44_100, bitDepth = 16)
        assertEquals(29_635_200L, estimateBytes(t))
    }

    @Test fun `estimate falls back to 24-bit 96 kHz and 300 s`() {
        assertEquals(120_960_000L, estimateBytes(track("1")))
    }

    // cacheCandidates

    @Test fun `candidates keep Tidal items in play order`() {
        val ids = listOf("TIDAL:1", "LOCAL:9", "TIDAL:2")
        val result = cacheCandidates(
            order = listOf(0, 1, 2),
            mediaIdAt = { ids[it] },
            trackAt = { track(ids[it].substringAfter(':')) },
        )
        assertEquals(listOf("1", "2"), result.map { it.trackId })
    }

    @Test fun `cacheCandidates dedupes a track queued twice`() {
        val ids = listOf("TIDAL:1", "TIDAL:2", "TIDAL:1")
        val result = cacheCandidates(
            order = listOf(0, 1, 2),
            mediaIdAt = { ids[it] },
            trackAt = { track(ids[it].substringAfter(':')) },
        )
        assertEquals(listOf("1", "2"), result.map { it.trackId })
    }

    @Test fun `candidates skip items with no track`() {
        val ids = listOf("TIDAL:1", "TIDAL:2", "TIDAL:3")
        val result = cacheCandidates(
            order = listOf(0, 1, 2),
            mediaIdAt = { ids[it] },
            trackAt = { if (it == 1) null else track(ids[it].substringAfter(':')) },
        )
        assertEquals(listOf("1", "3"), result.map { it.trackId })
    }

    @Test fun `candidates carry the track's size estimate`() {
        val t = track("1", durationSeconds = 240.0, sampleRateHz = 44_100, bitDepth = 16)
        val result = cacheCandidates(listOf(0), { "TIDAL:1" }, { t })
        assertEquals(listOf(CacheCandidate("1", t, 29_635_200L)), result)
    }

    // capacityBytes

    @Test fun `capacity is the limit when the device has room`() {
        assertEquals(2_000_000_000L, capacityBytes(2_000_000_000L, 0L, 10_000_000_000L))
    }

    @Test fun `capacity shrinks to keep 1 GB free`() {
        assertEquals(800_000_000L, capacityBytes(2_000_000_000L, 300_000_000L, 1_500_000_000L))
    }

    @Test fun `capacity never drops below what is already cached`() {
        assertEquals(300_000_000L, capacityBytes(2_000_000_000L, 300_000_000L, 500_000_000L))
    }

    // planWindow

    @Test fun `window fills in order until the next song does not fit`() {
        val candidates = listOf(candidate("a", 100), candidate("b", 100), candidate("c", 100))
        assertEquals(listOf("a", "b"), planWindow(candidates, emptyMap(), 250L).trackIds)
    }

    @Test fun `window counts real size for cached songs`() {
        val candidates = listOf(candidate("a", 100), candidate("b", 100))
        val cached = mapOf("a" to row("a", sizeBytes = 50))
        assertEquals(listOf("a", "b"), planWindow(candidates, cached, 150L).trackIds)
    }

    @Test fun `window stops at the first song that does not fit even if a later one would`() {
        val candidates = listOf(candidate("a", 100), candidate("b", 300), candidate("c", 10))
        assertEquals(listOf("a"), planWindow(candidates, emptyMap(), 150L).trackIds)
    }

    @Test fun `window is empty when the current song alone is too big`() {
        val window = planWindow(listOf(candidate("a", 500)), emptyMap(), 400L)
        assertEquals(emptyList<String>(), window.trackIds)
        assertEquals(emptyList<CacheCandidate>(), window.toDownload)
    }

    @Test fun `toDownload is the uncached window songs in order`() {
        val candidates = listOf(candidate("a", 100), candidate("b", 100), candidate("c", 100))
        val cached = mapOf("a" to row("a", sizeBytes = 100))
        val window = planWindow(candidates, cached, 1_000L)
        assertEquals(listOf("a", "b", "c"), window.trackIds)
        assertEquals(listOf("b", "c"), window.toDownload.map { it.trackId })
    }

    // pickEvictions

    @Test fun `evictions never touch protected ids`() {
        val cached = listOf(
            row("keep", lastPlayedAtMillis = 1),
            row("drop", lastPlayedAtMillis = 2),
        )
        val evicted = pickEvictions(cached, protectedIds = setOf("keep"), bytesToFree = 1_000L)
        assertEquals(listOf("drop"), evicted)
        assertTrue("keep" !in evicted)
    }

    @Test fun `evictions take oldest played first and never-played last`() {
        val cached = listOf(
            row("p10", lastPlayedAtMillis = 10),
            row("n1", cachedAtMillis = 1, lastPlayedAtMillis = null),
            row("p5", lastPlayedAtMillis = 5),
        )
        assertEquals(listOf("p5", "p10", "n1"), pickEvictions(cached, emptySet(), 1_000L))
    }

    @Test fun `evictions stop once enough bytes are freed`() {
        val cached = listOf(
            row("a", lastPlayedAtMillis = 1),
            row("b", lastPlayedAtMillis = 2),
            row("c", lastPlayedAtMillis = 3),
        )
        assertEquals(listOf("a", "b"), pickEvictions(cached, emptySet(), 150L))
    }

    @Test fun `no evictions when nothing needs freeing`() {
        val cached = listOf(row("a", lastPlayedAtMillis = 1))
        assertEquals(emptyList<String>(), pickEvictions(cached, emptySet(), 0L))
    }

    @Test fun `evictions return every evictable row when that is not enough`() {
        val cached = listOf(
            row("a", lastPlayedAtMillis = 1),
            row("b", cachedAtMillis = 2),
            row("keep", lastPlayedAtMillis = 3),
        )
        assertEquals(listOf("a", "b"), pickEvictions(cached, setOf("keep"), 10_000L))
    }

    // CacheSnapshot

    @Test fun `snapshot sums sizes and counts entries`() {
        val snapshot = CacheSnapshot(mapOf("a" to row("a", 100), "b" to row("b", 250)))
        assertEquals(setOf("a", "b"), snapshot.ids)
        assertEquals(350L, snapshot.totalBytes)
        assertEquals(2, snapshot.count)
    }
}
