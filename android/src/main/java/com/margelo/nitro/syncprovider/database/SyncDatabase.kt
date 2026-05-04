package com.margelo.nitro.syncprovider.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database aggregating queue + flush-history persistence.
 *
 * @see SyncItemEntity
 * @see SyncResultEntity
 */
@Database(
  entities = [
    SyncItemEntity::class,
    SyncResultEntity::class,
  ],
  version = 1,
  exportSchema = true,
)
internal abstract class SyncDatabase : RoomDatabase() {
  abstract fun syncItemDao(): SyncItemDao
  abstract fun syncResultDao(): SyncResultDao

  companion object {
    private const val DB_NAME = "syncprovider.db"

    @Volatile
    private var INSTANCE: SyncDatabase? = null

    fun get(context: Context): SyncDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          SyncDatabase::class.java,
          DB_NAME,
        )
          // Pre-1.0 we accept destructive downgrades only (no upgrades yet, so
          // no real migrations to define). Real migrations will land before 1.0.
          .fallbackToDestructiveMigrationOnDowngrade()
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
