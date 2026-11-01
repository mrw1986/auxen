package io.github.auxen.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.auxen.model.Source
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

/**
 * MIGRATION_2_3 (adds favorite_albums / favorite_artists) and MIGRATION_3_4
 * (adds cached_tracks), exercised through Room's OWN open-time schema validation.
 *
 * WHY NOT MigrationTestHelper: the module sets `exportSchema = false`, so there
 * is no `schemas/<version>.json` for the helper to build the "before" database
 * from, and `androidx.room:room-testing` is not a dependency. So the old
 * database is manufactured directly:
 *
 *   1. Let Room create the CURRENT (v4) schema in a real file-backed database.
 *   2. Drop the tables added after the target version and stamp `user_version`
 *      back. This IS a faithful old database precisely BECAUSE every migration
 *      is additive: v3 == v4 minus cached_tracks, and v2 == v3 minus
 *      favorite_albums/favorite_artists.
 *   3. Reopen through the PRODUCTION [AuxenDatabase.build] path. Room runs the
 *      registered migrations up to the current version, then calls its
 *      generated `validateMigration`, comparing the post-migration SQLite schema
 *      with the schema derived from the @Entity classes. A wrong column
 *      name/type, a missing NOT NULL, or a missing unique index throws
 *      IllegalStateException("Migration didn't properly handle: …").
 *
 * Also proves the migrations are REGISTERED in build(): an unregistered one
 * throws "A migration from N to M was required but not found".
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(AuxenDatabase.DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(AuxenDatabase.DB_NAME)
    }

    @Test
    fun migration2to3AddsFavoriteCollectionTablesAndPreservesExistingData() = runBlocking {
        // 1. Create the current schema on disk and seed a row that must survive.
        val seeded = AuxenDatabase.build(context)
        val trackId = seeded.trackDao().upsert(
            Track(title = "Keeper", artist = "Artist", source = Source.LOCAL, sourceId = "keep-1").toEntity(),
            nowMillis = 1_000,
        )
        seeded.favoriteDao().insert(FavoriteEntity(trackId = trackId, matchGroupId = null, addedAtMillis = 1_000))
        seeded.close()

        // 2. Rewind the file to v2 (additive migrations ⇒ v2 == v4 minus the v3 and v4 tables).
        val path = context.getDatabasePath(AuxenDatabase.DB_NAME).absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE IF EXISTS `favorite_albums`")
            raw.execSQL("DROP TABLE IF EXISTS `favorite_artists`")
            raw.execSQL("DROP TABLE IF EXISTS `cached_tracks`")
            raw.version = 2
        }

        // 3. Reopen through the production builder: Room runs MIGRATION_2_3 then
        //    MIGRATION_3_4 and validates the result against the compiled @Entity schema.
        val migrated = AuxenDatabase.build(context)

        // The additive migration left the v2 data alone.
        assertEquals(listOf("LOCAL:keep-1"), migrated.favoriteDao().favoriteKeys().first())

        // The new tables exist and satisfy their entity contract post-migration.
        migrated.favoriteAlbumDao().upsert(
            FavoriteAlbumEntity(source = "TIDAL", sourceId = "9", title = "Album Nine", addedAtMillis = 2_000),
        )
        migrated.favoriteArtistDao().upsert(
            FavoriteArtistEntity(source = "TIDAL", sourceId = "7", name = "Prompto", addedAtMillis = 3_000),
        )
        assertEquals(listOf("TIDAL:9"), migrated.favoriteAlbumDao().keys().first())
        assertEquals(listOf("TIDAL:7"), migrated.favoriteArtistDao().keys().first())
        migrated.close()
    }

    @Test
    fun `migrates 3 to 4 adding cached_tracks and keeps existing rows`() = runBlocking {
        val seeded = AuxenDatabase.build(context)
        val trackId = seeded.trackDao().upsert(
            Track(title = "Keeper", artist = "Artist", source = Source.LOCAL, sourceId = "keep-1").toEntity(),
            nowMillis = 1_000,
        )
        seeded.favoriteDao().insert(FavoriteEntity(trackId = trackId, matchGroupId = null, addedAtMillis = 1_000))
        seeded.close()

        // Additive migration, so v3 == v4 minus cached_tracks.
        val path = context.getDatabasePath(AuxenDatabase.DB_NAME).absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE IF EXISTS `cached_tracks`")
            raw.version = 3
        }

        val migrated = AuxenDatabase.build(context)

        assertEquals(listOf("LOCAL:keep-1"), migrated.favoriteDao().favoriteKeys().first())
        assertTrue(migrated.cachedTrackDao().all().isEmpty())

        val row = CachedTrackEntity(
            tidalTrackId = "42",
            kind = "DASH",
            entryFile = "manifest.mpd",
            sizeBytes = 9_000,
            mimeType = "audio/mp4",
            sampleRateHz = 96_000,
            bitDepth = 24,
            trackGainDb = -7.25,
            albumGainDb = -6.5,
            cachedAtMillis = 2_000,
            lastPlayedAtMillis = 3_000,
            trackJson = """{"title":"Keeper"}""",
        )
        migrated.cachedTrackDao().upsert(row)
        assertEquals(listOf(row), migrated.cachedTrackDao().all())
        migrated.close()
    }

    @Test
    fun reopeningAnAlreadyMigratedDatabaseRunsNoMigrationAndKeepsData() = runBlocking {
        val first = AuxenDatabase.build(context)
        first.favoriteAlbumDao().upsert(
            FavoriteAlbumEntity(source = "TIDAL", sourceId = "5", title = "Five", addedAtMillis = 1),
        )
        first.close()

        val second = AuxenDatabase.build(context)
        assertEquals(listOf("TIDAL:5"), second.favoriteAlbumDao().keys().first())
        second.close()
    }
}
