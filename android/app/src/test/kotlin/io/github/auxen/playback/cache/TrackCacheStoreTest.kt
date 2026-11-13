package io.github.auxen.playback.cache

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.auxen.Graph
import io.github.auxen.db.AuxenDatabase
import io.github.auxen.db.CachedTrackDao
import io.github.auxen.db.CachedTrackEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import io.github.auxen.provider.StreamInfo
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Records each delete batch so a test can prove none exceeds SQLite's bind-variable cap, and
 * runs a hook before each row delete so a test can see what was still on disk at that moment.
 * [beforeAll] lets a test make the startup read fail.
 */
private class RecordingDao(private val inner: CachedTrackDao) : CachedTrackDao by inner {
    val deleteBatchSizes = mutableListOf<Int>()
    var beforeDelete: (List<String>) -> Unit = {}
    var beforeDeleteAll: () -> Unit = {}
    var beforeAll: () -> Unit = {}

    override suspend fun all(): List<CachedTrackEntity> {
        beforeAll()
        return inner.all()
    }

    override suspend fun delete(ids: List<String>) {
        deleteBatchSizes += ids.size
        beforeDelete(ids)
        inner.delete(ids)
    }

    override suspend fun deleteAll() {
        beforeDeleteAll()
        inner.deleteAll()
    }
}

private fun track(id: String) = Track(title = "Song $id", artist = "Artist", source = Source.TIDAL, sourceId = id)

private val HI_RES = StreamInfo(
    uri = "https://example.invalid/stream",
    mimeType = null,
    sampleRateHz = 96_000,
    bitDepth = 24,
    trackGainDb = -6.5,
    albumGainDb = -7.25,
)

private fun result(
    kind: CacheKind = CacheKind.PROGRESSIVE,
    entry: String = "audio",
    sizeBytes: Long = 1_000,
    streamInfo: StreamInfo = HI_RES,
) = DownloadResult(kind, entry, sizeBytes, mimeType = "audio/flac", streamInfo = streamInfo)

private fun row(id: String) = CachedTrackEntity(
    tidalTrackId = id,
    kind = CacheKind.PROGRESSIVE.name,
    entryFile = "audio",
    sizeBytes = 1_000,
    mimeType = "audio/flac",
    sampleRateHz = null,
    bitDepth = null,
    trackGainDb = null,
    albumGainDb = null,
    cachedAtMillis = 1,
    lastPlayedAtMillis = null,
    trackJson = "{}",
)

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TrackCacheStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AuxenDatabase
    private lateinit var dao: RecordingDao
    private lateinit var root: File
    private lateinit var store: TrackCacheStore
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AuxenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = RecordingDao(db.cachedTrackDao())
        // Not created up front: on a fresh install noBackupFilesDir/track_cache doesn't exist yet.
        root = File(tmp.root, "track_cache")
        store = newStore()
    }

    @After
    fun tearDown() = db.close()

    private fun newStore() = TrackCacheStore(root, dao) { now }

    private fun stage(id: String, entry: String = "audio", bytes: Int = 1_000): File {
        val dir = store.staging(id)
        File(dir, entry).writeBytes(ByteArray(bytes))
        return dir
    }

    private suspend fun cache(id: String, kind: CacheKind = CacheKind.PROGRESSIVE, entry: String = "audio") {
        assertTrue(store.commit(id, stage(id, entry), result(kind, entry), track(id)))
    }

    private fun ids() = store.snapshot.value.ids

    private suspend fun rowIds() = dao.all().map { it.tidalTrackId }.toSet()

    @Test
    fun `commit moves staging into place and records the row`() = runBlocking {
        now = 4_242L
        val staging = stage("7", bytes = 1_234)

        assertTrue(store.commit("7", staging, result(sizeBytes = 1_234), track("7")))

        assertTrue(File(root, "7/audio").isFile)
        assertEquals(1_234L, File(root, "7/audio").length())
        assertFalse(staging.exists())
        assertEquals(setOf("7"), ids())
        val row = dao.all().single()
        assertEquals("7", row.tidalTrackId)
        assertEquals("PROGRESSIVE", row.kind)
        assertEquals("audio", row.entryFile)
        assertEquals(1_234L, row.sizeBytes)
        assertEquals(4_242L, row.cachedAtMillis)
        assertNull(row.lastPlayedAtMillis)
        assertEquals(track("7"), Graph.json.decodeFromString(Track.serializer(), row.trackJson))
        assertEquals(row, store.snapshot.value.entries["7"])
    }

    @Test
    fun `commit replaces an older copy of the same track`() = runBlocking {
        cache("7")

        cache("7", CacheKind.DASH, MANIFEST_FILE)

        assertEquals(listOf(MANIFEST_FILE), File(root, "7").list()!!.toList())
        assertEquals("DASH", dao.all().single().kind)
        assertEquals(setOf("7"), ids())
    }

    @Test
    fun `commit refuses an entry file that is missing or outside staging`() = runBlocking {
        val missing = store.staging("7")
        assertFalse(store.commit("7", missing, result(entry = "audio"), track("7")))
        assertFalse(missing.exists())

        File(tmp.root, "outside").writeText("not a track")
        val escaping = stage("8")
        assertFalse(store.commit("8", escaping, result(entry = "../../../outside"), track("8")))
        assertFalse(escaping.exists())

        assertTrue(dao.all().isEmpty())
        assertTrue(ids().isEmpty())
        assertFalse(File(root, "7").exists())
        assertFalse(File(root, "8").exists())
    }

    @Test
    fun `cachedPlayback returns a file uri for the entry file`() = runBlocking {
        cache("7")
        cache("8", CacheKind.DASH, MANIFEST_FILE)

        assertEquals("file://${root.absolutePath}/", store.rootUri)
        assertEquals(
            CachedPlayback(CacheKind.PROGRESSIVE, "file://" + File(root, "7/audio").absolutePath),
            store.cachedPlayback("7"),
        )
        assertEquals(
            CachedPlayback(CacheKind.DASH, "file://" + File(root, "8/$MANIFEST_FILE").absolutePath),
            store.cachedPlayback("8"),
        )
    }

    @Test
    fun `streamInfoFor carries the row's gains and quality`() = runBlocking {
        cache("7")

        assertEquals(
            StreamInfo(
                uri = "file://" + File(root, "7/audio").absolutePath,
                mimeType = "audio/flac",
                sampleRateHz = 96_000,
                bitDepth = 24,
                trackGainDb = -6.5,
                albumGainDb = -7.25,
            ),
            store.streamInfoFor("7"),
        )
    }

    @Test
    fun `uncached ids return null`() = runBlocking {
        cache("7")

        assertNull(store.cachedPlayback("42"))
        assertNull(store.streamInfoFor("42"))
        assertNull(store.cachedPlayback("../7"))
        assertNull(store.streamInfoFor("../7"))
    }

    @Test
    fun `evict deletes folders and rows`() = runBlocking {
        cache("7")
        cache("8")
        cache("9")

        store.evict(listOf("7", "8"))

        assertFalse(File(root, "7").exists())
        assertFalse(File(root, "8").exists())
        assertTrue(File(root, "9/audio").isFile)
        assertEquals(setOf("9"), rowIds())
        assertEquals(setOf("9"), ids())
        assertNull(store.cachedPlayback("7"))
    }

    // deleteRecursively's order is unspecified, so a crash mid-delete can leave manifest.mpd without
    // some segments. With the row already gone that's just an orphan folder load() removes.
    @Test
    fun `evict deletes rows before their folders`() = runBlocking {
        cache("7")
        val onDiskAtRowDelete = mutableListOf<Boolean>()
        dao.beforeDelete = { ids -> if ("7" in ids) onDiskAtRowDelete += File(root, "7/audio").isFile }

        store.evict(listOf("7"))

        assertEquals(listOf(true), onDiskAtRowDelete)
        assertFalse(File(root, "7").exists())
    }

    @Test
    fun `clear deletes rows before folders`() = runBlocking {
        cache("7")
        var onDiskAtRowDelete: Boolean? = null
        dao.beforeDeleteAll = { onDiskAtRowDelete = File(root, "7/audio").isFile }

        store.clear()

        assertEquals(true, onDiskAtRowDelete)
        assertFalse(File(root, "7").exists())
    }

    @Test
    fun `clear deletes everything and empties the snapshot`() = runBlocking {
        cache("7")
        cache("8")
        stage("9")

        store.clear()

        // The staging root stays, emptied: a download started after the clear may be using it.
        assertEquals(listOf(".tmp"), root.list()!!.toList())
        assertTrue(File(root, ".tmp").list()!!.isEmpty())
        assertTrue(dao.all().isEmpty())
        assertEquals(CacheSnapshot(generation = 1L), store.snapshot.value)
    }

    @Test
    fun `the clear generation survives later writes`() = runBlocking {
        store.clear()

        cache("7")
        store.markPlayed("7")
        store.evict(listOf("7"))

        assertEquals(CacheSnapshot(generation = 1L), store.snapshot.value)
    }

    @Test
    fun `a staging folder made after a clear survives that clear's sweep`() = runBlocking {
        val old = stage("8")
        // What staging() names a folder once clear() has bumped the generation to 1. The
        // scheduler can start that download while the sweep is still running.
        val fresh = File(root, ".tmp/7-1-123").apply { mkdirs() }
        File(fresh, "audio").writeBytes(ByteArray(1_000))

        store.clear()

        assertTrue(File(fresh, "audio").isFile)
        assertFalse(old.exists())
        assertTrue(store.commit("7", fresh, result(), track("7")))
        assertEquals(setOf("7"), ids())
    }

    @Test
    fun `a staging dir created before clear is discarded on commit`() = runBlocking {
        val staging = stage("7")

        store.clear()
        // A downloader that was mid-write when clear() ran can recreate its folder and finish.
        staging.mkdirs()
        File(staging, "audio").writeBytes(ByteArray(1_000))

        assertFalse(store.commit("7", staging, result(), track("7")))
        assertTrue(dao.all().isEmpty())
        assertFalse(File(root, "7").exists())
        assertFalse(staging.exists())
        assertTrue(ids().isEmpty())
    }

    @Test
    fun `markPlayed updates the row and snapshot`() = runBlocking {
        cache("7")
        now = 9_000L

        store.markPlayed("7")
        store.markPlayed("42")

        assertEquals(9_000L, dao.all().single().lastPlayedAtMillis)
        assertEquals(9_000L, store.snapshot.value.entries["7"]!!.lastPlayedAtMillis)
        assertEquals(setOf("7"), ids())
    }

    @Test
    fun `load drops temp folders, orphan folders and rows with missing files`() = runBlocking {
        cache("7")
        File(root, ".tmp/x").mkdirs()
        File(root, ".tmp/x/audio").writeText("partial")
        File(root, "99").mkdirs()
        File(root, "99/audio").writeText("no row")
        File(root, "not an id").mkdirs()
        dao.upsert(row("5"))

        val restarted = newStore()
        restarted.load()

        assertFalse(File(root, ".tmp/x").exists())
        assertFalse(File(root, "99").exists())
        assertFalse(File(root, "not an id").exists())
        assertTrue(File(root, "7/audio").isFile)
        assertEquals(setOf("7"), rowIds())
        assertEquals(setOf("7"), restarted.snapshot.value.ids)
        assertEquals(CacheKind.PROGRESSIVE, restarted.cachedPlayback("7")?.kind)
    }

    @Test
    fun `a row with an unknown kind is dropped on load and never served`() = runBlocking {
        // The file is there, so only the kind keeps it from playing.
        File(root, "7").mkdirs()
        File(root, "7/audio").writeText("audio")
        dao.upsert(row("7").copy(kind = "OPUS"))
        cache("8")

        val restarted = newStore()
        restarted.load()

        // Checked before any read, since a read would also drop the row.
        assertEquals(setOf("8"), restarted.snapshot.value.ids)
        assertEquals(setOf("8"), rowIds())
        assertFalse(File(root, "7").exists())
        assertNull(restarted.cachedPlayback("7"))
        assertNull(restarted.streamInfoFor("7"))
    }

    @Test
    fun `load drops more than 999 rows with missing files`() = runBlocking {
        // An Auto Backup restore brings back auxen.db rows without their noBackupFilesDir folders.
        repeat(1_200) { dao.upsert(row("$it")) }

        store.load()

        assertTrue(dao.all().isEmpty())
        assertEquals(CacheSnapshot(), store.snapshot.value)
        // Before SQLite 3.32 (API 30 and below) a statement can bind at most 999 variables.
        assertTrue(dao.deleteBatchSizes.max() <= 999)
    }

    @Test
    fun `awaitLoaded returns once load finishes`() = runBlocking {
        assertNull(withTimeoutOrNull(50) { store.awaitLoaded() })

        store.load()

        withTimeout(5_000) { store.awaitLoaded() }
    }

    @Test
    fun `awaitLoaded returns even when load fails`() = runBlocking {
        dao.beforeAll = { throw IOException("disk read failed") }

        assertThrows(IOException::class.java) { runBlocking { store.load() } }

        // The scheduler then plans against the empty snapshot rather than waiting forever.
        withTimeout(5_000) { store.awaitLoaded() }
    }

    @Test
    fun `cachedPlayback drops the row when the entry file is missing`() = runBlocking {
        cache("7")
        assertTrue(File(root, "7/audio").delete())

        assertNull(store.cachedPlayback("7"))
        assertFalse("7" in ids())
        awaitRowsGone()
        assertFalse(File(root, "7").exists())
    }

    @Test
    fun `staging rejects an id that would escape the root`() = runBlocking {
        listOf("../x", "a/b", "..", "").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { store.staging(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.commit("../x", File(root, ".tmp/x"), result(), track("x")) }
        }
        cache("7")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.evict(listOf("7", "../x")) }
        }
        // Every id is checked before anything changes, so the valid one is still cached.
        assertEquals(setOf("7"), ids())
        assertEquals(setOf("7"), rowIds())
        assertTrue(File(root, "7/audio").isFile)
        assertFalse(File(tmp.root, "x").exists())
    }

    @Test
    fun `discard deletes a staging folder and ignores anything outside it`() = runBlocking {
        cache("7")
        val staging = stage("8")
        val outside = tmp.newFolder("keep")
        File(outside, "file").writeText("keep me")

        store.discard(staging)
        store.discard(outside)
        store.discard(File(root, "7"))
        store.discard(File(root, ".tmp"))

        assertFalse(staging.exists())
        assertTrue(File(outside, "file").isFile)
        assertTrue(File(root, "7/audio").isFile)
        assertTrue(File(root, ".tmp").isDirectory)
    }

    @Test
    fun `freeDeviceBytes reports space on the cache volume`() = runBlocking {
        store.load()

        assertTrue(store.freeDeviceBytes() > 0)
    }

    // The row drop runs on the store's own background scope, so wait for it in real time.
    private suspend fun awaitRowsGone() = withContext(Dispatchers.Default) {
        withTimeout(5_000) {
            while (dao.all().isNotEmpty()) delay(10)
        }
    }
}
