package io.github.auxen.playback.cache

import android.app.Application
import io.github.auxen.db.CachedTrackEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.provider.StreamInfo
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val LIMIT = 1_000L
private const val ESTIMATE = 100L

// Far more than the limit, so the limit is what binds unless a test says otherwise.
private const val PLENTY_FREE = 1_000_000_000_000L

private val ONLINE_WIFI = NetworkState(online = true, unmetered = true)
private val ONLINE_METERED = NetworkState(online = true, unmetered = false)

private fun row(id: String, sizeBytes: Long = ESTIMATE, lastPlayedAtMillis: Long? = null) = CachedTrackEntity(
    tidalTrackId = id,
    kind = CacheKind.PROGRESSIVE.name,
    entryFile = PROGRESSIVE_FILE,
    sizeBytes = sizeBytes,
    mimeType = "audio/flac",
    sampleRateHz = null,
    bitDepth = null,
    trackGainDb = null,
    albumGainDb = null,
    cachedAtMillis = 0L,
    lastPlayedAtMillis = lastPlayedAtMillis,
    trackJson = "{}",
)

private fun queue(vararg ids: String, active: Boolean = true, estimate: Long = ESTIMATE) = CacheQueueSnapshot(
    candidates = ids.map { CacheCandidate(it, Track(title = "Song $it", artist = "A", source = Source.TIDAL, sourceId = it), estimate) },
    playingId = ids.firstOrNull(),
    active = active,
)

/**
 * An in-memory cache index that records what the scheduler asks of it. Built with
 * `loaded = false` it stays unloaded, like TrackCacheStore before load(), until [finishLoading].
 */
private class FakeCacheIndex(
    private val root: File,
    cached: List<CachedTrackEntity>,
    loaded: Boolean = true,
) : CacheIndex {
    private val state = MutableStateFlow(CacheSnapshot(cached.associateBy { it.tidalTrackId }))
    override val snapshot: StateFlow<CacheSnapshot> = state
    private val loadedSignal = CompletableDeferred<Unit>().apply { if (loaded) complete(Unit) }

    val stagings = mutableListOf<File>()
    val commits = mutableListOf<String>()
    val discarded = mutableListOf<File>()
    val evicted = mutableListOf<String>()

    /** Makes commit return false without touching staging, so the scheduler has to clean up. */
    var refuseCommits = false

    override fun staging(trackId: String): File =
        File(root, "$trackId-${stagings.size}").also { it.mkdirs(); stagings += it }

    override suspend fun commit(trackId: String, staging: File, result: DownloadResult, track: Track): Boolean {
        if (refuseCommits) return false
        commits += trackId
        staging.deleteRecursively()
        state.update { CacheSnapshot(it.entries + (trackId to row(trackId, result.sizeBytes))) }
        return true
    }

    override suspend fun discard(staging: File) {
        discarded += staging
        staging.deleteRecursively()
    }

    override suspend fun evict(trackIds: Collection<String>) {
        evicted += trackIds
        state.update { CacheSnapshot(it.entries - trackIds.toSet()) }
    }

    override suspend fun awaitLoaded() = loadedSignal.await()

    /** Fills the snapshot the way TrackCacheStore.load() does, then lets waiters through. */
    fun finishLoading(vararg rows: CachedTrackEntity) {
        state.value = CacheSnapshot(rows.associateBy { it.tidalTrackId })
        loadedSignal.complete(Unit)
    }
}

/** Downloads that hang until the test finishes or fails them. */
private class FakeDownloads {
    val calls = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    var maxRunning = 0
        private set
    private val pending = mutableMapOf<String, CompletableDeferred<Long>>()

    suspend fun download(trackId: String, staging: File): DownloadResult {
        calls += trackId
        val done = CompletableDeferred<Long>()
        pending[trackId] = done
        maxRunning = maxOf(maxRunning, pending.size)
        try {
            val size = done.await()
            return DownloadResult(CacheKind.PROGRESSIVE, PROGRESSIVE_FILE, size, "audio/flac", StreamInfo("https://cdn.example/a"))
        } catch (e: CancellationException) {
            cancelled += trackId
            throw e
        } finally {
            pending.remove(trackId)
        }
    }

    fun finish(trackId: String, sizeBytes: Long = ESTIMATE) = assertTrue(pending.getValue(trackId).complete(sizeBytes))

    fun fail(trackId: String) = assertTrue(pending.getValue(trackId).completeExceptionally(IOException("HTTP 500 for audio")))
}

// Robolectric only so android.util.Log works on the failure path.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CacheAheadSchedulerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val settings = MutableStateFlow(CacheSettings(CacheNetworkMode.WIFI_AND_MOBILE, LIMIT))
    private val network = MutableStateFlow(ONLINE_WIFI)
    private val downloads = FakeDownloads()
    private var free = PLENTY_FREE
    private lateinit var stagingRoot: File

    @Before
    fun setUp() {
        stagingRoot = tmp.newFolder("staging")
    }

    private fun TestScope.scheduler(index: FakeCacheIndex) =
        CacheAheadScheduler(backgroundScope, index, downloads::download, settings, network) { free }

    private fun index(vararg cached: CachedTrackEntity) = FakeCacheIndex(stagingRoot, cached.toList())

    @Test fun `downloads window songs one at a time in play order`() = runTest {
        val index = index()
        val scheduler = scheduler(index)

        scheduler.update(queue("1", "2", "3"))
        runCurrent()
        assertEquals(listOf("1"), downloads.calls)
        downloads.finish("1")
        runCurrent()
        assertEquals(listOf("1", "2"), downloads.calls)
        downloads.finish("2")
        runCurrent()
        downloads.finish("3")
        runCurrent()

        assertEquals(listOf("1", "2", "3"), downloads.calls)
        assertEquals(listOf("1", "2", "3"), index.commits)
        assertEquals(1, downloads.maxRunning)
    }

    @Test fun `skips songs already cached`() = runTest {
        val index = index(row("2"))
        val scheduler = scheduler(index)

        scheduler.update(queue("1", "2", "3"))
        runCurrent()
        downloads.finish("1")
        runCurrent()

        assertEquals(listOf("1", "3"), downloads.calls)
    }

    @Test fun `does not start a new download while playback is inactive`() = runTest {
        val scheduler = scheduler(index())

        scheduler.update(queue("1", "2", active = false))
        runCurrent()
        assertEquals(emptyList<String>(), downloads.calls)

        scheduler.update(queue("1", "2", active = true))
        runCurrent()
        assertEquals(listOf("1"), downloads.calls)
    }

    @Test fun `lets the in-flight download finish after a pause`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2"))
        runCurrent()

        scheduler.update(queue("1", "2", active = false))
        runCurrent()
        assertEquals(emptyList<String>(), downloads.cancelled)
        downloads.finish("1")
        runCurrent()

        assertEquals(listOf("1"), index.commits)
        // Song 2 waits for playback to resume.
        assertEquals(listOf("1"), downloads.calls)
    }

    @Test fun `Off mode starts nothing`() = runTest {
        settings.value = CacheSettings(CacheNetworkMode.OFF, LIMIT)
        val scheduler = scheduler(index())

        scheduler.update(queue("1", "2"))
        runCurrent()

        assertEquals(emptyList<String>(), downloads.calls)
    }

    @Test fun `offline starts nothing`() = runTest {
        network.value = NetworkState(online = false, unmetered = false)
        val scheduler = scheduler(index())

        scheduler.update(queue("1", "2"))
        runCurrent()

        assertEquals(emptyList<String>(), downloads.calls)
    }

    @Test fun `Wi-Fi only waits on a metered network and resumes when unmetered`() = runTest {
        settings.value = CacheSettings(CacheNetworkMode.WIFI_ONLY, LIMIT)
        network.value = ONLINE_METERED
        val scheduler = scheduler(index())

        scheduler.update(queue("1", "2"))
        runCurrent()
        assertEquals(emptyList<String>(), downloads.calls)

        network.value = ONLINE_WIFI
        runCurrent()
        assertEquals(listOf("1"), downloads.calls)
    }

    @Test fun `switching to metered in Wi-Fi only cancels the in-flight download`() = runTest {
        settings.value = CacheSettings(CacheNetworkMode.WIFI_ONLY, LIMIT)
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2"))
        runCurrent()

        network.value = ONLINE_METERED
        runCurrent()

        assertEquals(listOf("1"), downloads.cancelled)
        assertEquals(index.stagings, index.discarded)
        assertFalse(index.stagings.single().exists())
        assertEquals(emptyList<String>(), index.commits)
        assertEquals(listOf("1"), downloads.calls)
    }

    @Test fun `cancels the in-flight download when its song leaves the window`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2"))
        runCurrent()

        // Skipped ahead: song 1 is behind the playhead now.
        scheduler.update(queue("2"))
        runCurrent()

        assertEquals(listOf("1"), downloads.cancelled)
        assertEquals(listOf(index.stagings.first()), index.discarded)
        assertEquals(listOf("1", "2"), downloads.calls)
    }

    @Test fun `evicts oldest-played songs outside the window to make room`() = runTest {
        val index = index(row("old", 400, lastPlayedAtMillis = 10), row("recent", 400, lastPlayedAtMillis = 20))
        val scheduler = scheduler(index)

        // 800 cached + 300 for song 1 is 100 over the limit.
        scheduler.update(queue("1", estimate = 300))
        runCurrent()

        assertEquals(listOf("old"), index.evicted)
        assertEquals(listOf("1"), downloads.calls)
    }

    @Test fun `never evicts the playing song`() = runTest {
        // The playing song came in over the whole limit, so it doesn't fit the window.
        val index = index(row("1", 1_100), row("old", 100, lastPlayedAtMillis = 10))
        val scheduler = scheduler(index)

        scheduler.update(queue("1", "2"))
        runCurrent()

        assertEquals(listOf("old"), index.evicted)
        assertTrue("1" in index.snapshot.value.ids)
        assertEquals(emptyList<String>(), downloads.calls)
    }

    @Test fun `a failed song backs off 30 s while later songs continue`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2", "3"))
        runCurrent()

        downloads.fail("1")
        runCurrent()
        assertEquals(listOf(index.stagings.first()), index.discarded)
        assertEquals(listOf("1", "2"), downloads.calls)
        downloads.finish("2")
        runCurrent()
        downloads.finish("3")
        runCurrent()
        assertEquals(listOf("1", "2", "3"), downloads.calls)

        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(listOf("1", "2", "3"), downloads.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("1", "2", "3", "1"), downloads.calls)
    }

    @Test fun `stop cancels everything`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2", "3"))
        runCurrent()
        downloads.fail("1")
        runCurrent()

        scheduler.stop()
        runCurrent()
        assertEquals(listOf("2"), downloads.cancelled)
        assertEquals(index.stagings, index.discarded)

        // Neither a new queue nor song 1's retry starts anything.
        scheduler.update(queue("4", "5"))
        advanceTimeBy(RETRY_DELAYS_MILLIS.last() + 1)
        runCurrent()
        assertEquals(listOf("1", "2"), downloads.calls)
        assertEquals(emptyList<String>(), index.commits)
    }

    @Test fun `re-plans and evicts after a commit that comes in over its estimate`() = runTest {
        val index = index(row("old", 300, lastPlayedAtMillis = 10))
        val scheduler = scheduler(index)
        scheduler.update(queue("1", estimate = 600))
        runCurrent()
        assertEquals(emptyList<String>(), index.evicted)

        // 300 + 800 is over the limit once song 1 lands.
        downloads.finish("1", sizeBytes = 800)
        runCurrent()

        assertEquals(listOf("old"), index.evicted)
        assertTrue("1" in index.snapshot.value.ids)
    }

    @Test fun `keeps the in-flight download as its staging fills the device`() = runTest {
        // The device, not the limit, binds: room for 300 bytes above the headroom.
        free = DEVICE_HEADROOM_BYTES + 300
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", estimate = 300))
        runCurrent()

        // 200 bytes are in staging now, and a network change makes it plan again.
        free -= 200
        network.value = ONLINE_METERED
        runCurrent()
        assertEquals(emptyList<String>(), downloads.cancelled)

        downloads.finish("1", sizeBytes = 300)
        runCurrent()
        assertEquals(listOf("1"), index.commits)
        assertEquals(emptyList<String>(), index.evicted)
    }

    @Test fun `waits for the cache index to load before planning`() = runTest {
        // Like TrackCacheStore before load(): the snapshot is empty though song 1 is on disk.
        val index = FakeCacheIndex(stagingRoot, emptyList(), loaded = false)
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2"))
        runCurrent()
        assertEquals(emptyList<String>(), downloads.calls)

        index.finishLoading(row("1"))
        runCurrent()

        assertEquals(listOf("2"), downloads.calls)
    }

    @Test fun `discards the staging when the cache refuses a commit`() = runTest {
        val index = index().apply { refuseCommits = true }
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2"))
        runCurrent()

        downloads.finish("1")
        runCurrent()

        assertEquals(listOf(index.stagings.first()), index.discarded)
        assertFalse(index.stagings.first().exists())
        // Refused counts as failed: song 1 backs off and song 2 goes ahead.
        assertEquals(listOf("1", "2"), downloads.calls)
    }
}
