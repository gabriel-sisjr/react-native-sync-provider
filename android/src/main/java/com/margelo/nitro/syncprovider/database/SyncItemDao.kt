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
          lastErrorCode = :lastErrorCode
      WHERE id = :id
    """,
  )
  suspend fun updateStatus(
    id: String,
    status: String,
    attempts: Int,
    lastAttemptAt: Long?,
    lastErrorCode: String?,
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
}
