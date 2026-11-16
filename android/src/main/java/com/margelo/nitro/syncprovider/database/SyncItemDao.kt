package com.margelo.nitro.syncprovider.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [SyncItemEntity].
 *
 * Uses `suspend` for one-shot operations and [Flow] for observers. Every
 * mutation is single-statement except [insertBatch] / [resetInFlight], which
 * are wrapped in `@Transaction` for atomicity.
 */
@Dao
internal interface SyncItemDao {

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(item: SyncItemEntity): Long

  @Transaction
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertBatch(items: List<SyncItemEntity>)

  @Query("DELETE FROM sync_items WHERE id = :id")
  suspend fun deleteById(id: String): Int

  @Query("DELETE FROM sync_items")
  suspend fun clear()

  @Query("SELECT COUNT(*) FROM sync_items")
  suspend fun count(): Int

  /** Whether the queue still has row [id], in any status. */
  @Query("SELECT EXISTS(SELECT 1 FROM sync_items WHERE id = :id)")
  suspend fun exists(id: String): Boolean

  @Query("SELECT COUNT(*) FROM sync_items WHERE status = :status")
  suspend fun countByStatus(status: String): Int

  @Query(
    """
      SELECT * FROM sync_items
      WHERE status IN (:statuses)
      ORDER BY priorityWeight ASC, createdAt ASC
    """,
  )
  suspend fun findByStatuses(statuses: List<String>): List<SyncItemEntity>

  @Query(
    """
      SELECT * FROM sync_items
      WHERE status IN (:statuses)
      ORDER BY priorityWeight ASC, createdAt ASC
      LIMIT :limit
    """,
  )
  suspend fun findByStatusesLimit(statuses: List<String>, limit: Int): List<SyncItemEntity>

  /**
   * Atomically claim the next [limit] **eligible** `PENDING` rows. A row is
   * eligible when `nextRetryAt <= now`, i.e. its retry cooldown, if any, has elapsed. The select + bulk status flip run in a single
   * [Transaction] so two concurrent flush cycles can never claim the same row.
   *
   * Returned entities still carry their pre-claim `PENDING` status in memory
   * (only the persisted rows are flipped to `IN_FLIGHT`); the dispatcher reads
   * only the request fields and [SyncItemEntity.attempts], never `status`.
   *
   * @param now epoch-millis "current" instant; injectable so tests can drive the
   *   cooldown clock deterministically.
   */
  @Transaction
  suspend fun claimPending(now: Long, limit: Int): List<SyncItemEntity> {
    val rows = selectClaimable(now, limit)
    // Chunked because `batchSize` has no upper bound and SQLite caps bind
    // parameters per statement (999 on the versions older Android releases ship).
    rows.map { it.id }.chunked(MAX_IDS_PER_STATEMENT).forEach { markInFlight(it) }
    return rows
  }

  /**
   * Eligible-`PENDING` selection backing [claimPending]. The `status` equality +
   * `nextRetryAt` range predicate is served by the composite
   * `index_sync_items_status_nextRetryAt` index.
   */
  @Query(
    """
      SELECT * FROM sync_items
      WHERE status = '${SyncItemEntity.STATUS_PENDING}' AND nextRetryAt <= :now
      ORDER BY priorityWeight ASC, createdAt ASC
      LIMIT :limit
    """,
  )
  suspend fun selectClaimable(now: Long, limit: Int): List<SyncItemEntity>

  /** Bulk flip the given rows to `IN_FLIGHT`; the write half of [claimPending]. */
  @Query(
    """
      UPDATE sync_items
      SET status = '${SyncItemEntity.STATUS_IN_FLIGHT}'
      WHERE id IN (:ids)
    """,
  )
  suspend fun markInFlight(ids: List<String>)

  /**
   * Earliest deadline among `PENDING` rows with a retry cooldown or a credential hold
   * (`nextRetryAt > 0`), or `null` when there are none. Drives every retry wake.
   *
   * A deadline in the past is returned on purpose: its row is due now and would
   * otherwise be stranded. Rows with no deadline (`nextRetryAt = 0`, eligible now) stay
   * out, so rows left over from a full batch do not wake the engine by themselves.
   */
  @Query(
    """
      SELECT MIN(nextRetryAt) FROM sync_items
      WHERE status = '${SyncItemEntity.STATUS_PENDING}' AND nextRetryAt > 0
    """,
  )
  suspend fun nextRetryDeadline(): Long?

  /**
   * Earliest `nextRetryAt` across **all** `PENDING` rows, or `null` when there are
   * none. Returns `0` as soon as one row is eligible with no deadline, which is what
   * lets `resumeSync` wake for such rows too.
   */
  @Query(
    """
      SELECT MIN(nextRetryAt) FROM sync_items
      WHERE status = '${SyncItemEntity.STATUS_PENDING}'
    """,
  )
  suspend fun nextPendingDeadline(): Long?

  @Query("SELECT * FROM sync_items ORDER BY priorityWeight ASC, createdAt ASC")
  fun observeAll(): Flow<List<SyncItemEntity>>

  @Query("SELECT * FROM sync_items WHERE id = :id LIMIT 1")
  suspend fun findById(id: String): SyncItemEntity?

  @Query(
    """
      UPDATE sync_items
      SET status = :status,
          attempts = :attempts,
          lastAttemptAt = :lastAttemptAt,
          lastErrorCode = :lastErrorCode,
          nextRetryAt = :nextRetryAt
      WHERE id = :id
    """,
  )
  suspend fun updateStatus(
    id: String,
    status: String,
    attempts: Int,
    lastAttemptAt: Long?,
    lastErrorCode: String?,
    nextRetryAt: Long,
  ): Int

  /**
   * Atomically reset every row currently marked `IN_FLIGHT` back to `PENDING`.
   * Used by the recovery flow after process death or device reboot.
   */
  @Query(
    """
      UPDATE sync_items
      SET status = '${SyncItemEntity.STATUS_PENDING}'
      WHERE status = '${SyncItemEntity.STATUS_IN_FLIGHT}'
    """,
  )
  suspend fun resetInFlight(): Int

  private companion object {
    const val MAX_IDS_PER_STATEMENT = 500
  }
}
