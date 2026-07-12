package io.github.auxen.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.auxen.model.Source
import io.github.auxen.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun track(sourceId: String, title: String = "Song $sourceId") = Track(
    title = title,
    artist = "Artist",
    source = Source.LOCAL,
    sourceId = sourceId,
    format = "FLAC",
)

@RunWith(RobolectricTestRunner::class)
class DaoTest {

    private lateinit var db: AuxenDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AuxenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    // ---------------- tracks ----------------

    @Test
    fun upsertInsertsThenUpdatesPreservingIdAndPlayCount() = runBlocking {
        val id = db.trackDao().upsert(track("1").toEntity(), nowMillis = 1000)
        assertTrue(id > 0)

        db.trackDao().recordPlay(id, playedAtMillis = 2000)

        // Re-upsert with changed metadata: same row id, play stats preserved.
        val id2 = db.trackDao().upsert(track("1", title = "Renamed").toEntity(), nowMillis = 3000)
        assertEquals(id, id2)
        val entity = db.trackDao().byId(id)!!
        assertEquals("Renamed", entity.title)
        assertEquals(1, entity.playCount)
        assertEquals(1000, entity.addedAtMillis)
        assertEquals(2000L, entity.lastPlayedAtMillis)
    }

    @Test
    fun bySourceIdRoundTripsTrackFields() = runBlocking {
        db.trackDao().upsert(track("42").toEntity(), nowMillis = 1)
        val entity = db.trackDao().bySourceId("LOCAL", "42")
        assertNotNull(entity)
        assertEquals("Song 42", entity!!.toTrack().title)
        assertEquals(Source.LOCAL, entity.toTrack().source)
        assertNull(db.trackDao().bySourceId("TIDAL", "42"))
    }

    // ---------------- favorites ----------------

    @Test
    fun favoriteInsertDeleteAndFlows() = runBlocking {
        val id = db.trackDao().upsert(track("7").toEntity(), nowMillis = 1)
        db.favoriteDao().insert(FavoriteEntity(trackId = id, matchGroupId = null, addedAtMillis = 5))

        assertTrue(db.favoriteDao().isFavorite(id))
        assertEquals(listOf("LOCAL:7"), db.favoriteDao().favoriteKeys().first())
        assertEquals("Song 7", db.favoriteDao().favorites().first().single().title)

        db.favoriteDao().delete(id)
        assertTrue(db.favoriteDao().favoriteKeys().first().isEmpty())
    }

    // ---------------- playlists ----------------

    @Test
    fun playlistAppendKeepsPositionsAndDeleteCascades() = runBlocking {
        val t1 = db.trackDao().upsert(track("1").toEntity(), nowMillis = 1)
        val t2 = db.trackDao().upsert(track("2").toEntity(), nowMillis = 1)
        val pl = db.playlistDao().insert(PlaylistEntity(name = "Mix", color = "#d4a039"))

        db.playlistDao().appendTrack(pl, t1)
        db.playlistDao().appendTrack(pl, t2)
        assertEquals(listOf("Song 1", "Song 2"), db.playlistDao().tracksIn(pl).map { it.title })

        db.playlistDao().removeTrack(pl, t1)
        assertEquals(listOf("Song 2"), db.playlistDao().tracksIn(pl).map { it.title })

        db.playlistDao().deleteWithTracks(pl)
        assertTrue(db.playlistDao().playlists().first().isEmpty())
        assertTrue(db.playlistDao().tracksIn(pl).isEmpty())
    }

    @Test
    fun playlistReorderAndRecolor() = runBlocking {
        val t1 = db.trackDao().upsert(track("1").toEntity(), nowMillis = 1)
        val t2 = db.trackDao().upsert(track("2").toEntity(), nowMillis = 1)
        val t3 = db.trackDao().upsert(track("3").toEntity(), nowMillis = 1)
        val pl = db.playlistDao().insert(PlaylistEntity(name = "Mix", color = "#d4a039"))
        db.playlistDao().appendTrack(pl, t1)
        db.playlistDao().appendTrack(pl, t2)
        db.playlistDao().appendTrack(pl, t3)

        db.playlistDao().reorder(pl, listOf(t3, t1, t2))
        assertEquals(listOf("Song 3", "Song 1", "Song 2"), db.playlistDao().tracksIn(pl).map { it.title })

        db.playlistDao().recolor(pl, "#3498db")
        assertEquals("#3498db", db.playlistDao().playlists().first().single().color)
    }

    // ---------------- play history ----------------

    @Test
    fun recentlyPlayedOrdersByLatestPlay() = runBlocking {
        val t1 = db.trackDao().upsert(track("1").toEntity(), nowMillis = 1)
        val t2 = db.trackDao().upsert(track("2").toEntity(), nowMillis = 1)
        db.playHistoryDao().insert(PlayHistoryEntity(trackId = t1, playedAtMillis = 100))
        db.playHistoryDao().insert(PlayHistoryEntity(trackId = t2, playedAtMillis = 200))
        db.playHistoryDao().insert(PlayHistoryEntity(trackId = t1, playedAtMillis = 300))

        assertEquals(
            listOf("Song 1", "Song 2"),
            db.playHistoryDao().recentlyPlayed(limit = 10).map { it.title },
        )
    }

    // ---------------- settings + queue ----------------

    @Test
    fun settingsPutGetOverwrites() = runBlocking {
        assertNull(db.settingsDao().get("k"))
        db.settingsDao().put(SettingEntity("k", "v1"))
        db.settingsDao().put(SettingEntity("k", "v2"))
        assertEquals("v2", db.settingsDao().get("k"))
    }

    @Test
    fun queueReplaceAllRoundTrips() = runBlocking {
        db.queueDao().replaceAll(listOf(QueueItemEntity(0, "a"), QueueItemEntity(1, "b")))
        db.queueDao().replaceAll(listOf(QueueItemEntity(0, "c")))
        assertEquals(listOf("c"), db.queueDao().all().map { it.trackJson })
    }
}
