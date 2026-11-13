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
import kotlinx.coroutines.flow.MutableSharedFlow
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

/**
 * Tidal songs [ids] in play order. Nothing Tidal is playing unless [playing] names one, as when a
 * local song plays and these come next.
 */
private fun queue(
    vararg ids: String,
    active: Boolean = true,
    estimate: Long = ESTIMATE,
    playing: String? = null,
) = CacheQueueSnapshot(
    candidates = ids.map { CacheCandidate(it, Track(title = "Song $it", artist = "A", source = Source.TIDAL, sourceId = it), estimate) },
    playingId = playing,
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
    val played = mutableListOf<String>()

    /** Makes commit return false without touching staging, so the scheduler has to clean up. */
    var refuseCommits = false

    // The clear generation each staging folder was made in. Like TrackCacheStore, a commit from
    // before the last clear is refused.
    private val stagedIn = mutableMapOf<File, Long>()

    override fun staging(trackId: String): File =
        File(root, "$trackId-${stagings.size}").also {
            it.mkdirs()
            stagings += it
            stagedIn[it] = state.value.generation
        }

    override suspend fun commit(trackId: String, staging: File, result: DownloadResult, track: Track): Boolean {
        if (refuseCommits || stagedIn[staging] != state.value.generation) return false
        commits += trackId
        staging.deleteRecursively()
        state.update { it.copy(entries = it.entries + (trackId to row(trackId, result.sizeBytes))) }
        return true
    }

    /** Empties the index and bumps its generation, the way TrackCacheStore.clear() does. */
    fun clear() {
        state.update { CacheSnapshot(generation = it.generation + 1) }
    }

    override suspend fun discard(staging: File) {
        discarded += staging
        staging.deleteRecursively()
    }

    override suspend fun evict(trackIds: Collection<String>) {
        evicted += trackIds
        state.update { it.copy(entries = it.entries - trackIds.toSet()) }
    }

    override suspend fun markPlayed(trackId: String) {
        played += trackId
        state.update { snap ->
            val row = snap.entries[trackId] ?: return@update snap
            snap.copy(entries = snap.entries + (trackId to row.copy(lastPlayedAtMillis = 1L)))
        }
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

    /** The byte budget each call got, in call order. */
    val budgets = mutableListOf<Pair<String, Long>>()
    var maxRunning = 0
        private set
    private val pending = mutableMapOf<String, CompletableDeferred<Long>>()

    suspend fun download(trackId: String, staging: File, maxBytes: Long, durationSeconds: Double?): DownloadResult {
        calls += trackId
        budgets += trackId to maxBytes
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

    fun tooLarge(trackId: String, sizeBytes: Long) =
        assertTrue(pending.getValue(trackId).completeExceptionally(SongTooLargeException(trackId, sizeBytes)))
}

// Robolectric only so android.util.Log works on the failure path.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CacheAheadSchedulerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val settings = MutableStateFlow(CacheSettings(CacheNetworkMode.WIFI_AND_MOBILE, LIMIT))
    private val network = MutableStateFlow(ONLINE_WIFI)
    private val loggedIn = MutableStateFlow(true)
    private val downloads = FakeDownloads()
    private var free = PLENTY_FREE
    private lateinit var stagingRoot: File

    @Before
    fun setUp() {
        stagingRoot = tmp.newFolder("staging")
    }

    private fun TestScope.scheduler(index: FakeCacheIndex) =
        CacheAheadScheduler(backgroundScope, index, downloads::download, settings, network, loggedIn) { free }

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

    @Test fun `caches the songs ahead before the one playing`() = runTest {
        val index = index()
        val scheduler = scheduler(index)

        scheduler.update(queue("1", "2", "3", playing = "1"))
        runCurrent()
        assertEquals(listOf("2"), downloads.calls)
        downloads.finish("2")
        runCurrent()
        assertEquals(listOf("2", "3"), downloads.calls)
        downloads.finish("3")
        runCurrent()

        // Still in the window, so its room was held all along.
        assertEquals(listOf("2", "3", "1"), downloads.calls)
    }

    @Test fun `keeps the next song's download going when it starts playing`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2", "3", playing = "1"))
        runCurrent()
        assertEquals(listOf("2"), downloads.calls)

        scheduler.update(queue("2", "3", playing = "2"))
        runCurrent()
        assertEquals(emptyList<String>(), downloads.cancelled)

        downloads.finish("2")
        runCurrent()
        assertEquals(listOf("2"), index.commits)
        assertEquals(listOf("2", "3"), downloads.calls)
    }

    @Test fun `logging out cancels the download and starts nothing new`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2"))
        runCurrent()

        loggedIn.value = false
        runCurrent()
        assertEquals(listOf("1"), downloads.cancelled)

        // A new queue while logged out still starts nothing.
        scheduler.update(queue("3", "4"))
        runCurrent()
        assertEquals(listOf("1"), downloads.calls)
        assertEquals(emptyList<String>(), index.commits)
    }

    @Test fun `logging back in resumes caching`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        loggedIn.value = false
        scheduler.update(queue("1", "2"))
        runCurrent()
        assertEquals(emptyList<String>(), downloads.calls)

        loggedIn.value = true
        runCurrent()

        assertEquals(listOf("1"), downloads.calls)
    }

    @Test fun `starts nothing until the stored session has been read`() = runTest {
        // Like the session store before its first read: no value yet.
        val session = MutableSharedFlow<Boolean>(replay = 1)
        val scheduler = CacheAheadScheduler(backgroundScope, index(), downloads::download, settings, network, session) { free }
        scheduler.update(queue("1", "2"))
        runCurrent()
        assertEquals(emptyList<String>(), downloads.calls)

        session.tryEmit(true)
        runCurrent()

        assertEquals(listOf("1"), downloads.calls)
    }

    @Test fun `a clear cancels the download without a backoff`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2"))
        runCurrent()

        index.clear()
        runCurrent()

        assertEquals(listOf("1"), downloads.cancelled)
        assertEquals(1, index.discarded.size)
        // No failure was recorded, so the same song may start again at once.
        assertEquals(listOf("1", "1"), downloads.calls)
    }

    @Test fun `a download refused because of a clear isn't counted as a failure`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2"))
        runCurrent()

        // Finishes before the scheduler sees the clear, so its commit is refused.
        index.clear()
        downloads.finish("1")
        runCurrent()

        assertEquals(emptyList<String>(), index.commits)
        assertEquals(listOf("1", "1"), downloads.calls)
    }

    @Test fun `a clear drops every backoff`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2"))
        runCurrent()
        downloads.fail("1")
        runCurrent()
        assertEquals(listOf("1", "2"), downloads.calls)
        downloads.finish("2")
        runCurrent()

        index.clear()
        runCurrent()

        // Song 1 waited 30 s before the clear. Now it starts again right away.
        assertEquals(listOf("1", "2", "1"), downloads.calls)
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

    @Test fun `Off mode cancels the in-flight download`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2"))
        runCurrent()

        settings.value = CacheSettings(CacheNetworkMode.OFF, LIMIT)
        runCurrent()

        assertEquals(listOf("1"), downloads.cancelled)
        assertEquals(index.stagings, index.discarded)
        assertFalse(index.stagings.single().exists())
        assertEquals(emptyList<String>(), index.commits)
        assertEquals(listOf("1"), downloads.calls)
    }

    @Test fun `an emptied queue cancels the in-flight download`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2"))
        runCurrent()

        // What the service sends when the timeline empties.
        scheduler.update(CacheQueueSnapshot(emptyList(), playingId = null, active = false))
        runCurrent()

        assertEquals(listOf("1"), downloads.cancelled)
        assertEquals(index.stagings, index.discarded)
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

        scheduler.update(queue("1", "2", playing = "1"))
        runCurrent()

        assertEquals(listOf("old"), index.evicted)
        assertTrue("1" in index.snapshot.value.ids)
        assertEquals(emptyList<String>(), downloads.calls)
    }

    @Test fun `does not evict anything for a song that still would not fit`() = runTest {
        // The playing song isn't a candidate (its Track didn't decode), but it stays protected.
        val index = index(row("playing", 600), row("old", 300, lastPlayedAtMillis = 10))
        val scheduler = scheduler(index)

        // 900 cached + 500 for song 2 is 400 over the limit, and only "old" (300) may go.
        scheduler.update(queue("2", estimate = 500).copy(playingId = "playing"))
        runCurrent()

        assertEquals(emptyList<String>(), index.evicted)
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

    @Test fun `a song that keeps failing waits 600 s on every later retry`() = runTest {
        val scheduler = scheduler(index())
        scheduler.update(queue("1"))
        runCurrent()

        // Attempts 1-3 use the listed delays. From then on the last one repeats.
        val waits = RETRY_DELAYS_MILLIS + RETRY_DELAYS_MILLIS.last() + RETRY_DELAYS_MILLIS.last()
        assertEquals(listOf(30_000L, 120_000L, 600_000L, 600_000L, 600_000L), waits)
        waits.forEachIndexed { failed, wait ->
            downloads.fail("1")
            runCurrent()
            advanceTimeBy(wait - 1)
            runCurrent()
            assertEquals("still waiting after failure ${failed + 1}", failed + 1, downloads.calls.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("retried after failure ${failed + 1}", failed + 2, downloads.calls.size)
        }
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

    @Test fun `does not re-download a song evicted for coming in over its estimate`() = runTest {
        val index = index(row("1", 400), row("2", 400))
        val scheduler = scheduler(index)
        // 800 cached + 100 estimated for song 3 fits the limit, so it downloads.
        scheduler.update(queue("1", "2", "3"))
        runCurrent()
        assertEquals(listOf("3"), downloads.calls)

        // It lands at 300, so 1100 is over the limit and song 3 falls out of the window.
        downloads.finish("3", sizeBytes = 300)
        runCurrent()
        assertEquals(listOf("3"), index.evicted)

        // Later passes know its real size and leave it out.
        repeat(3) {
            network.value = if (network.value == ONLINE_WIFI) ONLINE_METERED else ONLINE_WIFI
            runCurrent()
        }
        assertEquals(listOf("3"), index.evicted)
        assertEquals(listOf("3"), downloads.calls)
    }

    @Test fun `a song too large for its budget is remembered without a backoff`() = runTest {
        val index = index(row("1"))
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2", "3"))
        runCurrent()
        assertEquals(listOf("2"), downloads.calls)

        // 100 cached + 950 is over the limit, so song 2 (and the run after it) leaves the window.
        downloads.tooLarge("2", 950)
        runCurrent()
        assertEquals(listOf(index.stagings.single()), index.discarded)
        network.value = ONLINE_METERED
        runCurrent()
        assertEquals(listOf("2"), downloads.calls)

        // No backoff to wait out: room for its real size restarts it at once, with that room.
        settings.value = CacheSettings(CacheNetworkMode.WIFI_AND_MOBILE, 2_000)
        runCurrent()
        assertEquals(listOf("2", "2"), downloads.calls)
        assertEquals("2" to 1_900L, downloads.budgets.last())
        assertEquals(emptyList<String>(), index.evicted)
    }

    @Test fun `gives the download the room left once evictions are done`() = runTest {
        val index = index(row("2", 300), row("old", 400, lastPlayedAtMillis = 10))
        val scheduler = scheduler(index)

        // 700 cached + 500 for song 1 is 200 over, so "old" goes and 300 stays cached.
        scheduler.update(queue("1", "2", estimate = 500))
        runCurrent()

        assertEquals(listOf("old"), index.evicted)
        assertEquals(listOf("1" to LIMIT - 300), downloads.budgets)
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

    @Test fun `marks the playing song played when its download commits`() = runTest {
        val index = index()
        val scheduler = scheduler(index)
        scheduler.update(queue("1", "2", playing = "1"))
        runCurrent()

        downloads.finish("2")
        runCurrent()
        downloads.finish("1")
        runCurrent()

        // Song 1 was heard while it downloaded. Song 2 hasn't played yet.
        assertEquals(listOf("1"), index.played)
        assertEquals(1L, index.snapshot.value.entries.getValue("1").lastPlayedAtMillis)
    }

    @Test fun `keeps scheduling after an unexpected error`() = runTest {
        // evict and commit failures are caught where they happen. A free-space read isn't.
        var failures = 1
        val scheduler = CacheAheadScheduler(backgroundScope, index(), downloads::download, settings, network, loggedIn) {
            if (failures-- > 0) throw IllegalStateException("statfs failed")
            free
        }
        scheduler.update(queue("1", "2"))
        runCurrent()
        assertEquals(emptyList<String>(), downloads.calls)

        // That pass was dropped. The next change plans again.
        network.value = ONLINE_METERED
        runCurrent()

        assertEquals(listOf("1"), downloads.calls)
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
