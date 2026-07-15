package io.github.auxen.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.auxen.db.AuxenDatabase
import io.github.auxen.model.Source
import io.github.auxen.model.SourcePriority
import io.github.auxen.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryTest {

    private lateinit var db: AuxenDatabase
    private lateinit var repo: LibraryRepository
    private var now = 1000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AuxenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = LibraryRepository(db, clock = { now })
    }

    @After
    fun tearDown() = db.close()

    private val tidalTrack = Track(
        title = "Everlong",
        artist = "Foo Fighters",
        source = Source.TIDAL,
        sourceId = "99",
        format = "FLAC",
    )

    @Test
    fun setFavoriteUpsertsAndToggles() = runBlocking {
        repo.setFavorite(tidalTrack, favorite = true)
        assertEquals(setOf("TIDAL:99"), repo.favoriteKeys().first())
        assertEquals("Everlong", repo.favorites().first().single().title)

        repo.setFavorite(tidalTrack, favorite = false)
        assertTrue(repo.favoriteKeys().first().isEmpty())
    }

    @Test
    fun recordPlayIncrementsCountAndWritesHistory() = runBlocking {
        val id = repo.upsert(tidalTrack)
        now = 2000
        repo.recordPlay("TIDAL:99")
        now = 3000
        repo.recordPlay("TIDAL:99")

        val entity = db.trackDao().byId(id)!!
        assertEquals(2, entity.playCount)
        assertEquals(3000L, entity.lastPlayedAtMillis)
        assertEquals(listOf("Everlong"), db.playHistoryDao().recentlyPlayed(10).map { it.title })
    }

    @Test
    fun recordPlayIgnoresUnknownAndMalformedIds() = runBlocking {
        repo.recordPlay("TIDAL:does-not-exist")
        repo.recordPlay("garbage")
        // No exception, no history rows.
        assertTrue(db.playHistoryDao().recentlyPlayed(10).isEmpty())
    }

    @Test
    fun sourcePriorityDefaultsToQualityAndPersists() = runBlocking {
        assertEquals(SourcePriority.PREFER_QUALITY, repo.sourcePriority())
        repo.setSourcePriority(SourcePriority.PREFER_LOCAL)
        assertEquals(SourcePriority.PREFER_LOCAL, repo.sourcePriority())
    }

    @Test
    fun createPlaylistAndAddTrackRoundTrips() = runBlocking {
        val playlistId = repo.createPlaylist("Road Trip")
        repo.addTrackToPlaylist(tidalTrack, playlistId)
        repo.addTrackToPlaylist(tidalTrack.copy(sourceId = "100", title = "Walk"), playlistId)

        val names = repo.playlists().first().map { it.name }
        assertEquals(listOf("Road Trip"), names)
        assertEquals(
            listOf("Everlong", "Walk"),
            db.playlistDao().tracksIn(playlistId).map { it.title },
        )
    }

    @Test
    fun createPlaylistUsesDefaultAmberColor() = runBlocking {
        repo.createPlaylist("Mix")
        assertEquals("#d4a039", repo.playlists().first().single().color)
    }

    @Test
    fun playlistManagementRoundTrip() = runBlocking {
        val id = repo.createPlaylist("Mix")
        repo.addTrackToPlaylist(tidalTrack, id)
        repo.addTrackToPlaylist(tidalTrack.copy(sourceId = "100", title = "Walk"), id)

        assertEquals(listOf("Everlong", "Walk"), repo.playlistTracks(id).map { it.title })

        repo.movePlaylistTrack(id, fromIndex = 1, toIndex = 0)
        assertEquals(listOf("Walk", "Everlong"), repo.playlistTracks(id).map { it.title })

        repo.renamePlaylist(id, "Road Trip")
        repo.recolorPlaylist(id, "#3498db")
        val entity = repo.playlists().first().single()
        assertEquals("Road Trip", entity.name)
        assertEquals("#3498db", entity.color)

        repo.removeFromPlaylist(id, tidalTrack.copy(sourceId = "100", title = "Walk"))
        assertEquals(listOf("Everlong"), repo.playlistTracks(id).map { it.title })

        repo.deletePlaylist(id)
        assertTrue(repo.playlists().first().isEmpty())
    }

    @Test
    fun recentlyPlayedMapsHistoryToTracks() = runBlocking {
        repo.upsert(tidalTrack)
        repo.recordPlay("TIDAL:99")
        assertEquals(listOf("Everlong"), repo.recentlyPlayed().map { it.title })
    }

    @Test
    fun searchHistoryRoundTripsTrimmedAndIgnoresBlank() = runBlocking {
        repo.addSearchHistory("  radiohead  ")
        repo.addSearchHistory("")
        repo.addSearchHistory("   ")
        assertEquals(listOf("radiohead"), repo.searchHistory().first())

        repo.deleteSearchHistoryItem("radiohead")
        assertTrue(repo.searchHistory().first().isEmpty())
    }

    @Test
    fun settingsPassthroughRoundTrips() = runBlocking {
        assertEquals(null, repo.getSetting("library_tab"))
        repo.setSetting("library_tab", "2")
        assertEquals("2", repo.getSetting("library_tab"))
    }
}
