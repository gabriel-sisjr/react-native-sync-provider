package com.margelo.nitro.syncprovider.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO for [SyncDeadLetterEntity], with the moves between it and the queue table, each in
 * one transaction.
 */
@Dao
internal interface SyncDeadLetterDao {

  /** A newer terminal failure of the same id replaces the older dead letter. */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(row: SyncDeadLetterEntity)

  @Query("SELECT * FROM sync_dead_letters ORDER BY deadLetteredAt ASC, id ASC")
  suspend fun listAll(): List<SyncDeadLetterEntity>

  @Query("SELECT * FROM sync_dead_letters WHERE id = :id LIMIT 1")
  suspend fun findById(id: String): SyncDeadLetterEntity?

  @Query("DELETE FROM sync_dead_letters WHERE id = :id")
  suspend fun deleteById(id: String): Int

  @Query("DELETE FROM sync_dead_letters")
  suspend fun clear()

  @Query("SELECT COUNT(*) FROM sync_dead_letters")
  suspend fun count(): Int

  @Query("DELETE FROM sync_items WHERE id = :id")
  suspend fun deleteQueued(id: String): Int

  @Query("DELETE FROM sync_items")
  suspend fun clearQueued()

  /** Move a queue row here: never in both tables, never in neither. */
  @Transaction
  suspend fun moveFromQueue(row: SyncDeadLetterEntity) {
    insert(row)
    deleteQueued(row.id)
  }

  /** Empty the queue and the dead letters together. */
  @Transaction
  suspend fun clearAll() {
    clearQueued()
    clear()
  }
}
