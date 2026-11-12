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
 * @see SyncDeadLetterEntity
 */
@Database(
  entities = [
    SyncItemEntity::class,
    SyncResultEntity::class,
    SyncDeadLetterEntity::class,
  ],
  version = 3,
  exportSchema = true,
)
internal abstract class SyncDatabase : RoomDatabase() {
  abstract fun syncItemDao(): SyncItemDao
  abstract fun syncResultDao(): SyncResultDao
  abstract fun syncDeadLetterDao(): SyncDeadLetterDao

  companion object {
    private const val DB_NAME = "syncprovider.db"

    /**
     * v1 -> v2: adds the [SyncItemEntity.nextRetryAt] column and the
     * composite `(status, nextRetryAt)` index, dropping the now-redundant
     * single-column `index_sync_items_status` (its role is filled by the new
     * index's left prefix).
     *
     * The column is added `NOT NULL DEFAULT 0` so pre-existing rows survive as
     * eligible now, with no data loss, and the migrated schema matches a fresh
     * install. They keep their `attempts`, so a migrated row can show
     * `attempts >= 1` with `nextRetryAt = 0`. Index names MUST match Room's
     * generated names byte-for-byte for
     * [androidx.room.testing.MigrationTestHelper] validation.
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

    /**
     * v2 -> v3: adds the `sync_dead_letters` table, then moves every `FAILED` queue row
     * into it. `PENDING` and `IN_FLIGHT` rows stay as they are.
     *
     * A moved row keeps its request fields and `attempts`. It has no `lastErrorMessage`.
     * `deadLetteredAt` is its `lastAttemptAt` when that is positive, else its `createdAt`;
     * `lastAttemptAt` takes that same value when it had none, and a missing
     * `lastErrorCode` becomes `MAX_ATTEMPTS_EXCEEDED`.
     *
     * SQLiteOpenHelper runs the upgrade in one transaction, so the copy and the delete
     * land together or not at all. The table and index statements MUST match Room's
     * generated ones byte-for-byte for [androidx.room.testing.MigrationTestHelper]
     * validation.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `sync_dead_letters` (`id` TEXT NOT NULL, " +
            "`method` TEXT NOT NULL, `url` TEXT NOT NULL, `headers` TEXT, `body` TEXT, " +
            "`contentType` TEXT, `priority` TEXT NOT NULL, `priorityWeight` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `metadata` TEXT, `attempts` INTEGER NOT NULL, " +
            "`lastAttemptAt` INTEGER NOT NULL, `lastErrorCode` TEXT NOT NULL, " +
            "`lastErrorMessage` TEXT, `deadLetteredAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_sync_dead_letters_deadLetteredAt` " +
            "ON `sync_dead_letters` (`deadLetteredAt`)",
        )
        db.execSQL(
          """
          INSERT INTO `sync_dead_letters` (
            `id`, `method`, `url`, `headers`, `body`, `contentType`, `priority`,
            `priorityWeight`, `createdAt`, `metadata`, `attempts`, `lastAttemptAt`,
            `lastErrorCode`, `lastErrorMessage`, `deadLetteredAt`
          )
          SELECT
            `id`, `method`, `url`, `headers`, `body`, `contentType`, `priority`,
            `priorityWeight`, `createdAt`, `metadata`, `attempts`,
            CASE WHEN `lastAttemptAt` > 0 THEN `lastAttemptAt` ELSE `createdAt` END,
            COALESCE(NULLIF(`lastErrorCode`, ''), 'MAX_ATTEMPTS_EXCEEDED'),
            NULL,
            CASE WHEN `lastAttemptAt` > 0 THEN `lastAttemptAt` ELSE `createdAt` END
          FROM `sync_items`
          WHERE `status` = 'FAILED'
          """.trimIndent(),
        )
        db.execSQL("DELETE FROM `sync_items` WHERE `status` = 'FAILED'")
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
          .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
