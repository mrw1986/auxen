package io.github.auxen.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The narrow favorite-album/favorite-artist tables (2026-07-15 decision): a
 * Tidal-favorited album/artist must be visible in Collection even with no local
 * tracks. Identity is (source, source_id) — the key FavoriteDao.favoriteKeys()
 * already emits for tracks.
 */
@RunWith(RobolectricTestRunner::class)
class FavoriteCollectionDaoTest {

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

    @Test
    fun albumUpsertInsertsThenRefreshesPreservingRowId() = runBlocking {
        val dao = db.favoriteAlbumDao()
        val id = dao.upsert(FavoriteAlbumEntity(source = "TIDAL", sourceId = "9", title = "Album Nine", addedAtMillis = 100))
        assertTrue(id > 0)

        // Same (source, source_id) → same row, refreshed metadata, no duplicate.
        val id2 = dao.upsert(
            FavoriteAlbumEntity(
                source = "TIDAL", sourceId = "9", title = "Album Nine (Deluxe)",
                artist = "Kavinsky", artUrl = "https://art/9.jpg", addedAtMillis = 200,
            ),
        )
        assertEquals(id, id2)
        val row = dao.bySourceId("TIDAL", "9")!!
        assertEquals("Album Nine (Deluxe)", row.title)
        assertEquals("Kavinsky", row.artist)
        assertEquals("https://art/9.jpg", row.artUrl)
        assertEquals(200L, row.addedAtMillis)
        assertEquals(1, dao.all().first().size)
    }

    @Test
    fun albumKeysEmitSourceColonSourceIdAndDeleteRemoves() = runBlocking {
        val dao = db.favoriteAlbumDao()
        dao.upsert(FavoriteAlbumEntity(source = "TIDAL", sourceId = "9", title = "Nine", addedAtMillis = 100))
        dao.upsert(FavoriteAlbumEntity(source = "LOCAL", sourceId = "abc", title = "Local One", addedAtMillis = 50))

        assertEquals(setOf("TIDAL:9", "LOCAL:abc"), dao.keys().first().toSet())

        dao.delete("TIDAL", "9")
        assertEquals(listOf("LOCAL:abc"), dao.keys().first())
        assertNull(dao.bySourceId("TIDAL", "9"))
    }

    @Test
    fun albumsAreOrderedNewestFavoriteFirst() = runBlocking {
        val dao = db.favoriteAlbumDao()
        dao.upsert(FavoriteAlbumEntity(source = "TIDAL", sourceId = "1", title = "Older", addedAtMillis = 100))
        dao.upsert(FavoriteAlbumEntity(source = "TIDAL", sourceId = "2", title = "Newer", addedAtMillis = 300))
        assertEquals(listOf("Newer", "Older"), dao.all().first().map { it.title })
    }

    @Test
    fun artistUpsertKeysAndDeleteRoundTrip() = runBlocking {
        val dao = db.favoriteArtistDao()
        val id = dao.upsert(FavoriteArtistEntity(source = "TIDAL", sourceId = "7", name = "Prompto", addedAtMillis = 10))
        val id2 = dao.upsert(
            FavoriteArtistEntity(
                source = "TIDAL", sourceId = "7", name = "Prompto",
                artUrl = "https://art/7.jpg", addedAtMillis = 20,
            ),
        )
        assertEquals(id, id2)
        assertEquals("https://art/7.jpg", dao.bySourceId("TIDAL", "7")!!.artUrl)
        assertEquals(listOf("TIDAL:7"), dao.keys().first())

        dao.delete("TIDAL", "7")
        assertTrue(dao.all().first().isEmpty())
    }
}
