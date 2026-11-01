package io.github.auxen.playback.cache

import android.util.Log
import io.github.auxen.Graph
import io.github.auxen.db.CachedTrackDao
import io.github.auxen.db.CachedTrackEntity
import io.github.auxen.model.Track
import io.github.auxen.provider.StreamInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "TrackCacheStore"
private const val TMP_DIR = ".tmp"

// Before SQLite 3.32 (API 30 and below) one statement binds at most 999 variables.
private const val DELETE_BATCH = 500

// Ids become folder names under root, so nothing that could walk out of it ("..", "/").
private val TRACK_ID = Regex("[0-9A-Za-z_-]{1,64}")

// <trackId>-<generation>-<stamp>; the stamp is nanoTime, which can be negative.
private val STAGING_NAME = Regex("(.+)-(\\d+)-(-?\\d+)")

/**
 * Whole cached Tidal tracks on disk, one folder per track (`root/<trackId>/<entryFile>`),
 * indexed by the `cached_tracks` table. A row exists only once its folder is complete.
 *
 * Every mutation runs under one lock on [Dispatchers.IO]. The sync readers
 * ([cachedPlayback], [streamInfoFor], [snapshot]) never lock: Media3 loader threads call them.
 */
class TrackCacheStore(
    private val root: File,
    private val dao: CachedTrackDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : CacheIndex {

    private val state = MutableStateFlow(CacheSnapshot())
    override val snapshot: StateFlow<CacheSnapshot> = state.asStateFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tmpRoot = File(root, TMP_DIR)

    // Bumped by clear(), so a download that started before it can't commit after it.
    @Volatile private var generation = 0L

    // nanoTime can repeat on a coarse clock; bumping past the last stamp keeps folders apart.
    private val lastStamp = AtomicLong(Long.MIN_VALUE)

    val rootUri: String = "file://${root.absolutePath}/"

    /** Reconciles disk with the table, then fills [snapshot]. Call once at startup. */
    suspend fun load() = locked {
        root.mkdirs()
        tmpRoot.deleteRecursively()
        val (kept, dropped) = dao.all().partition(::isPlayable)
        deleteRows(dropped.map { it.tidalTrackId })
        val keptIds = kept.mapTo(HashSet()) { it.tidalTrackId }
        root.listFiles()?.filter { it.name !in keptIds }?.forEach { it.deleteRecursively() }
        state.value = CacheSnapshot(kept.associateBy { it.tidalTrackId })
    }

    override fun staging(trackId: String): File {
        requireValidId(trackId)
        val stamp = lastStamp.updateAndGet { maxOf(it + 1, System.nanoTime()) }
        val dir = File(tmpRoot, "$trackId-$generation-$stamp")
        if (!dir.mkdirs()) throw IOException("Couldn't create staging folder ${dir.name}")
        return dir
    }

    override suspend fun commit(
        trackId: String,
        staging: File,
        result: DownloadResult,
        track: Track,
    ): Boolean {
        requireValidId(trackId)
        return locked {
            if (!isCurrentStaging(trackId, staging) || !holdsEntry(staging, result.entryFile)) {
                deleteStaging(staging)
                return@locked false
            }
            // Drop any older copy first so a failure below never leaves a row without its folder.
            removeLocked(listOf(trackId))
            val target = File(root, trackId)
            if (!staging.renameTo(target)) {
                deleteStaging(staging)
                return@locked false
            }
            val row = rowFor(trackId, result, track)
            try {
                dao.upsert(row)
            } catch (e: Throwable) {
                target.deleteRecursively()
                throw e
            }
            state.update { CacheSnapshot(it.entries + (trackId to row)) }
            true
        }
    }

    override suspend fun discard(staging: File) = locked { deleteStaging(staging) }

    override suspend fun evict(trackIds: Collection<String>) {
        val ids = trackIds.toList()
        ids.forEach(::requireValidId)
        if (ids.isNotEmpty()) locked { removeLocked(ids) }
    }

    /** Where to play [trackId] from, or null. Drops the entry if its file has gone missing. */
    fun cachedPlayback(trackId: String): CachedPlayback? =
        playable(trackId)?.let { CachedPlayback(it.kind, fileUri(it.entry)) }

    /** A local [StreamInfo] for [trackId] with the gains and quality recorded at download time. */
    fun streamInfoFor(trackId: String): StreamInfo? = playable(trackId)?.let {
        StreamInfo(
            uri = fileUri(it.entry),
            mimeType = it.row.mimeType,
            sampleRateHz = it.row.sampleRateHz,
            bitDepth = it.row.bitDepth,
            trackGainDb = it.row.trackGainDb,
            albumGainDb = it.row.albumGainDb,
        )
    }

    suspend fun clear() = locked {
        generation++
        state.value = CacheSnapshot()
        root.listFiles()?.forEach { it.deleteRecursively() }
        dao.deleteAll()
    }

    suspend fun markPlayed(trackId: String) = locked {
        if (trackId !in state.value.entries) return@locked
        val at = clock()
        dao.markPlayed(trackId, at)
        state.update { snap ->
            val row = snap.entries[trackId] ?: return@update snap
            CacheSnapshot(snap.entries + (trackId to row.copy(lastPlayedAtMillis = at)))
        }
    }

    fun freeDeviceBytes(): Long = root.usableSpace

    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private class Playable(val row: CachedTrackEntity, val kind: CacheKind, val entry: File)

    private fun playable(trackId: String): Playable? {
        if (!TRACK_ID.matches(trackId)) return null
        val row = state.value.entries[trackId] ?: return null
        val kind = kindOf(row)
        val entry = entryOf(row)
        if (kind != null && entry.isFile) return Playable(row, kind, entry)
        forgetMissing(trackId, row)
        return null
    }

    private fun isPlayable(row: CachedTrackEntity): Boolean =
        TRACK_ID.matches(row.tidalTrackId) && kindOf(row) != null && entryOf(row).isFile

    private fun kindOf(row: CachedTrackEntity): CacheKind? = CacheKind.entries.firstOrNull { it.name == row.kind }

    private fun entryOf(row: CachedTrackEntity) = File(File(root, row.tidalTrackId), row.entryFile)

    private fun fileUri(file: File) = "file://${file.absolutePath}"

    // The reader can't wait for the lock, so the snapshot drops the entry now and the disk catches up.
    private fun forgetMissing(trackId: String, row: CachedTrackEntity) {
        // Identity, not equality: a commit that replaced the row meanwhile must survive.
        state.update { if (it.entries[trackId] === row) CacheSnapshot(it.entries - trackId) else it }
        scope.launch {
            try {
                mutex.withLock {
                    if (trackId in state.value.entries) return@withLock
                    File(root, trackId).deleteRecursively()
                    dao.delete(listOf(trackId))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Harmless: load() drops the row at next startup.
                Log.w(TAG, "Couldn't drop missing cached track $trackId", e)
            }
        }
    }

    private suspend fun removeLocked(ids: List<String>) {
        state.update { CacheSnapshot(it.entries - ids.toSet()) }
        ids.forEach { File(root, it).deleteRecursively() }
        deleteRows(ids)
    }

    private suspend fun deleteRows(ids: List<String>) = ids.chunked(DELETE_BATCH).forEach { dao.delete(it) }

    private fun isCurrentStaging(trackId: String, staging: File): Boolean {
        if (!isUnderTmp(staging)) return false
        val match = STAGING_NAME.matchEntire(staging.name) ?: return false
        return match.groupValues[1] == trackId && match.groupValues[2].toLongOrNull() == generation
    }

    private fun holdsEntry(staging: File, entryFile: String): Boolean {
        val entry = File(staging, entryFile)
        return entry.isFile && entry.canonicalPath.startsWith(staging.canonicalPath + File.separator)
    }

    private fun isUnderTmp(file: File) = file.canonicalPath.startsWith(tmpRoot.canonicalPath + File.separator)

    private fun deleteStaging(staging: File) {
        if (isUnderTmp(staging)) staging.deleteRecursively()
    }

    private fun rowFor(trackId: String, result: DownloadResult, track: Track) = CachedTrackEntity(
        tidalTrackId = trackId,
        kind = result.kind.name,
        entryFile = result.entryFile,
        sizeBytes = result.sizeBytes,
        mimeType = result.mimeType,
        sampleRateHz = result.streamInfo.sampleRateHz,
        bitDepth = result.streamInfo.bitDepth,
        trackGainDb = result.streamInfo.trackGainDb,
        albumGainDb = result.streamInfo.albumGainDb,
        cachedAtMillis = clock(),
        lastPlayedAtMillis = null,
        trackJson = Graph.json.encodeToString(Track.serializer(), track),
    )
}

private fun requireValidId(trackId: String) =
    require(TRACK_ID.matches(trackId)) { "Not a cacheable track id: $trackId" }
