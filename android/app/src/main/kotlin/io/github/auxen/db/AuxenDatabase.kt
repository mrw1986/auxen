package io.github.auxen.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        PlayHistoryEntity::class,
        SettingEntity::class,
        QueueItemEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AuxenDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun settingsDao(): SettingsDao
    abstract fun queueDao(): QueueDao

    companion object {
        fun build(context: Context): AuxenDatabase =
            Room.databaseBuilder(context, AuxenDatabase::class.java, "auxen.db").build()
    }
}
