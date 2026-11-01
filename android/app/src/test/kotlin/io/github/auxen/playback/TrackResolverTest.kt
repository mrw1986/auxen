package io.github.auxen.playback

import io.github.auxen.provider.StreamInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackResolverTest {

    private var now = 0L
    private var fetchCount = 0
    private val fetch: suspend (String) -> StreamInfo = { id ->
        fetchCount++
        StreamInfo(uri = "https://cdn.example/$id?n=$fetchCount")
    }
    private val resolver = TrackResolver(fetch = fetch, clock = { now }, ttlMillis = 1000)

    @Test
    fun cachesWithinTtl() = runBlocking {
        val first = resolver.resolve("42")
        now = 500
        val second = resolver.resolve("42")
        assertEquals(first.uri, second.uri)
        assertEquals(1, fetchCount)
    }

    @Test
    fun refetchesAfterTtlExpires() = runBlocking {
        resolver.resolve("42")
        now = 1500
        resolver.resolve("42")
        assertEquals(2, fetchCount)
    }

    @Test
    fun invalidateForcesRefetch() = runBlocking {
        resolver.resolve("42")
        resolver.invalidate("42")
        resolver.resolve("42")
        assertEquals(2, fetchCount)
    }

    @Test
    fun distinctTracksCachedIndependently() = runBlocking {
        resolver.resolve("1")
        resolver.resolve("2")
        resolver.resolve("1")
        assertEquals(2, fetchCount)
    }

    @Test
    fun invalidateAllForcesRefetchForEveryTrack() = runBlocking {
        resolver.resolve("1")
        resolver.resolve("2")
        resolver.invalidateAll()
        resolver.resolve("1")
        resolver.resolve("2")
        assertEquals(4, fetchCount)
    }

    @Test
    fun `cached info is returned without fetching`() = runBlocking {
        val local = StreamInfo(uri = "file:///cache/42/audio", trackGainDb = -6.5)
        val cached = TrackResolver(fetch, { now }, 1000, cachedInfo = { id -> local.takeIf { id == "42" } })

        assertEquals(local, cached.resolve("42"))
        assertEquals(0, fetchCount)
    }

    @Test
    fun `cached info is not kept after the cache drops it`() = runBlocking {
        var local: StreamInfo? = StreamInfo(uri = "file:///cache/42/audio")
        val cached = TrackResolver(fetch, { now }, 1000, cachedInfo = { local })
        cached.resolve("42")

        local = null
        val after = cached.resolve("42")

        assertEquals("https://cdn.example/42?n=1", after.uri)
        assertEquals(1, fetchCount)
    }
}
