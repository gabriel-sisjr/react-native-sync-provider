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

  /**
   * Settle, in one transaction, the rows a cycle claimed and leaves `IN_FLIGHT`: an aborted
   * cycle's, or the rest of a batch that a rejected credential stopped. Each change applies only
   * to a row still `IN_FLIGHT`: one whose outcome was written meanwhile, or that a purge removed,
   * is left as it is.
   * - [unsent]: back to `PENDING` unchanged;
   * - [delivered]: rows whose request was answered with a `2xx`, removed;
   * - [retries]: back to `PENDING` with these values: a retry, which counts its attempt, or a
   *   credential hold, which does not;
   * - [exhausted]: rows that become dead letters, moved here.
   *
   * @return the ids of [delivered], [retries] and [exhausted] that were applied.
   */
  @Transaction
  suspend fun settleAbortedClaims(
    unsent: List<String>,
    delivered: List<String>,
    retries: List<ScheduledRetry>,
    exhausted: List<SyncDeadLetterEntity>,
  ): Set<String> {
    unsent.chunked(MAX_IDS_PER_STATEMENT).forEach { releaseQueuedInFlight(it) }
    val applied = mutableSetOf<String>()
    for (id in delivered) {
      if (deleteQueuedInFlight(id) > 0) applied += id
    }
    for (retry in retries) {
      val changed = retryQueuedInFlight(
        id = retry.id,
        attempts = retry.attempts,
        lastAttemptAt = retry.lastAttemptAt,
        lastErrorCode = retry.lastErrorCode,
        nextRetryAt = retry.nextRetryAt,
      )
      if (changed > 0) applied += retry.id
    }
    for (row in exhausted) {
      if (deleteQueuedInFlight(row.id) == 0) continue
      insert(row)
      applied += row.id
    }
    return applied
  }

  @Query(
    """
      UPDATE sync_items
      SET status = '${SyncItemEntity.STATUS_PENDING}'
      WHERE status = '${SyncItemEntity.STATUS_IN_FLIGHT}' AND id IN (:ids)
    """,
  )
  suspend fun releaseQueuedInFlight(ids: List<String>): Int

  @Query(
    """
      UPDATE sync_items
      SET status = '${SyncItemEntity.STATUS_PENDING}',
          attempts = :attempts,
          lastAttemptAt = :lastAttemptAt,
          lastErrorCode = :lastErrorCode,
          nextRetryAt = :nextRetryAt
      WHERE id = :id AND status = '${SyncItemEntity.STATUS_IN_FLIGHT}'
    """,
  )
  suspend fun retryQueuedInFlight(
    id: String,
    attempts: Int,
    lastAttemptAt: Long,
    lastErrorCode: String,
    nextRetryAt: Long,
  ): Int

  @Query("DELETE FROM sync_items WHERE id = :id AND status = '${SyncItemEntity.STATUS_IN_FLIGHT}'")
  suspend fun deleteQueuedInFlight(id: String): Int

  private companion object {
    /** Below the 999 bind parameters older SQLite versions allow per statement. */
    const val MAX_IDS_PER_STATEMENT = 500
  }
}

/** A retry [SyncDeadLetterDao.settleAbortedClaims] writes: the attempt counted, and its cooldown. */
internal data class ScheduledRetry(
  val id: String,
  val attempts: Int,
  val lastAttemptAt: Long,
  val lastErrorCode: String,
  val nextRetryAt: Long,
)

/** What [SyncDeadLetterDao.replay] did. */
internal enum class ReplayOutcome {
  REPLAYED,

  /** No dead letter with that id: nothing changed. */
  UNKNOWN,

  /** The queue is at its cap: the dead letter stays. */
  QUEUE_FULL,
}
