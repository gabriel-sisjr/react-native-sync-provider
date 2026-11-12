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

  /**
   * Move a queue row here: never in both tables, never in neither, and nothing at all when
   * the queue no longer has the row. The queue row is deleted first and the dead letter is
   * written only when that delete removed it, so an item that `clearQueue()` or
   * `removeItem(id)` purged while its request was in flight is not brought back with its
   * headers and body.
   *
   * @return true when the row was moved; false when it was no longer queued, in which case
   *   nothing was written.
   */
  @Transaction
  suspend fun moveFromQueue(row: SyncDeadLetterEntity): Boolean {
    if (deleteQueued(row.id) == 0) return false
    insert(row)
    return true
  }

  /** Empty the queue and the dead letters together. */
  @Transaction
  suspend fun clearAll() {
    clearQueued()
    clear()
  }

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertQueued(item: SyncItemEntity)

  @Query("SELECT COUNT(*) FROM sync_items")
  suspend fun countQueued(): Int

  /**
   * Put a dead letter back in the queue as a fresh item: same id, its original
   * `createdAt`, `PENDING` with no attempt, cooldown or error recorded. The capacity check,
   * the insert and the delete run in one transaction.
   */
  @Transaction
  suspend fun replay(id: String, maxQueueSize: Int): ReplayOutcome {
    val row = findById(id) ?: return ReplayOutcome.UNKNOWN
    if (countQueued() >= maxQueueSize) return ReplayOutcome.QUEUE_FULL
    insertQueued(
      SyncItemEntity(
        id = row.id,
        method = row.method,
        url = row.url,
        headers = row.headers,
        body = row.body,
        contentType = row.contentType,
        priority = row.priority,
        priorityWeight = row.priorityWeight,
        createdAt = row.createdAt,
        attempts = 0,
        lastAttemptAt = null,
        lastErrorCode = null,
        status = SyncItemEntity.STATUS_PENDING,
        metadata = row.metadata,
        nextRetryAt = 0L,
      ),
    )
    deleteById(id)
    return ReplayOutcome.REPLAYED
  }
}

/** What [SyncDeadLetterDao.replay] did. */
internal enum class ReplayOutcome {
  REPLAYED,

  /** No dead letter with that id: nothing changed. */
  UNKNOWN,

  /** The queue is at its cap: the dead letter stays. */
  QUEUE_FULL,
}
