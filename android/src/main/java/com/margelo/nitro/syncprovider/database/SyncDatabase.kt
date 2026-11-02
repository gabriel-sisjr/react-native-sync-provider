package com.margelo.nitro.syncprovider.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
  version = 2,
  exportSchema = true,
)
internal abstract class SyncDatabase : RoomDatabase() {
  abstract fun syncItemDao(): SyncItemDao
  abstract fun syncResultDao(): SyncResultDao

  companion object {
    private const val DB_NAME = "syncprovider.db"

    /**
     * v1 -> v2: adds the [SyncItemEntity.nextRetryAt] column and the
     * composite `(status, nextRetryAt)` index, dropping the now-redundant
     * single-column `index_sync_items_status` (its role is filled by the new
     * index's left prefix).
     *
     * The column is added `NOT NULL DEFAULT 0` so pre-existing rows survive as
     * "never attempted", with no data loss, and the migrated schema matches a
     * fresh install. Index names MUST match Room's generated names
     * byte-for-byte for [androidx.room.testing.MigrationTestHelper] validation.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE `sync_items` ADD COLUMN `nextRetryAt` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_sync_items_status_nextRetryAt` " +
            "ON `sync_items` (`status`, `nextRetryAt`)",
        )
        db.execSQL("DROP INDEX IF EXISTS `index_sync_items_status`")
      }
    }

    @Volatile
    private var INSTANCE: SyncDatabase? = null

    fun get(context: Context): SyncDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          SyncDatabase::class.java,
          DB_NAME,
        )
          .addMigrations(MIGRATION_1_2)
          // Upgrades are handled by the explicit migrations above. Downgrades
          // remain destructive pre-1.0: a rolled-back app wipes the queue
          // rather than failing to open. Recovery from that is roll-forward only.
          .fallbackToDestructiveMigrationOnDowngrade()
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
