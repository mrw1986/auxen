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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MIGRATION_2_3 (adds favorite_albums / favorite_artists), exercised through
 * Room's OWN open-time schema validation.
 *
 * WHY NOT MigrationTestHelper: the module sets `exportSchema = false`, so there
 * is no `schemas/<version>.json` for the helper to build the "before" database
 * from, and `androidx.room:room-testing` is not a dependency. So the v2 database
 * is manufactured directly:
 *
 *   1. Let Room create the CURRENT (v3) schema in a real file-backed database.
 *   2. Drop the two v3-only tables and stamp `user_version = 2`. This IS a
 *      faithful v2 database precisely BECAUSE MIGRATION_2_3 is additive:
 *      v2 == v3 minus favorite_albums/favorite_artists.
 *   3. Reopen through the PRODUCTION [AuxenDatabase.build] path. Room sees
 *      user_version 2 < 3, runs the registered MIGRATION_2_3, then calls its
 *      generated `validateMigration`, comparing the post-migration SQLite schema
 *      with the schema derived from the @Entity classes. A wrong column
 *      name/type, a missing NOT NULL, or a missing unique index throws
 *      IllegalStateException("Migration didn't properly handle: …").
 *
 * Also proves MIGRATION_2_3 is REGISTERED in build(): an unregistered migration
 * throws "A migration from 2 to 3 was required but not found".
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

        // 2. Rewind the file to v2 (additive migration ⇒ v2 == v3 minus the new tables).
        val path = context.getDatabasePath(AuxenDatabase.DB_NAME).absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE IF EXISTS `favorite_albums`")
            raw.execSQL("DROP TABLE IF EXISTS `favorite_artists`")
            raw.version = 2
        }

        // 3. Reopen through the production builder: Room runs MIGRATION_2_3 and
        //    validates the result against the compiled @Entity schema.
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
