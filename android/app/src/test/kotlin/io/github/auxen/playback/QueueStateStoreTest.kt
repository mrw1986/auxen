package io.github.auxen.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.auxen.db.AuxenDatabase
import io.github.auxen.db.QueueItemEntity
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueStateStoreTest {

    private lateinit var db: AuxenDatabase
    private lateinit var store: QueueStateStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AuxenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = QueueStateStore(db)
    }

    @After
    fun tearDown() = db.close()

    private fun track(id: String) = Track(
        title = "Song $id",
        artist = "Artist",
        source = Source.TIDAL,
        sourceId = id,
    )

    @Test
    fun saveLoadRoundTrip() = runBlocking {
        store.save(listOf(track("1"), track("2"), track("3")), index = 1, positionMs = 42_000)
        val saved = store.load()!!
        assertEquals(listOf("Song 1", "Song 2", "Song 3"), saved.tracks.map { it.title })
        assertEquals(1, saved.index)
        assertEquals(42_000L, saved.positionMs)
    }

    @Test
    fun emptyQueueLoadsAsNull() = runBlocking {
        assertNull(store.load())
        store.save(listOf(track("1")), 0, 0)
        store.save(emptyList(), 0, 0)
        assertNull(store.load())
    }

    @Test
    fun corruptedRowsAreSkippedAndIndexClamped() = runBlocking {
        store.save(listOf(track("1"), track("2")), index = 1, positionMs = 5)
        db.queueDao().replaceAll(
            listOf(
                QueueItemEntity(0, "not json"),
                QueueItemEntity(1, Json.encodeToString(Track.serializer(), track("2"))),
            ),
        )
        val saved = store.load()!!
        assertEquals(listOf("Song 2"), saved.tracks.map { it.title })
        assertEquals(0, saved.index) // clamped from 1 to last valid index
    }
}
