package io.github.auxen.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        PlayHistoryEntity::class,
        SettingEntity::class,
        QueueItemEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AuxenDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun settingsDao(): SettingsDao
    abstract fun queueDao(): QueueDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        /** v1 -> v2: adds the search_history table (additive, no data touched). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_history` (" +
                        "`query` TEXT NOT NULL, " +
                        "`searched_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`query`))",
                )
            }
        }

        fun build(context: Context): AuxenDatabase =
            Room.databaseBuilder(context, AuxenDatabase::class.java, "auxen.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
