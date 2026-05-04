package com.margelo.nitro.syncprovider.database

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted summary of a single flush cycle. One row per `flush()` invocation.
 */
@Keep
@Entity(tableName = "sync_results")
internal data class SyncResultEntity(
  @PrimaryKey
  @ColumnInfo(name = "id")
  val id: String,

  @ColumnInfo(name = "startedAt")
  val startedAt: Long,

  @ColumnInfo(name = "finishedAt")
  val finishedAt: Long,

  @ColumnInfo(name = "successCount")
  val successCount: Int,

  @ColumnInfo(name = "failureCount")
  val failureCount: Int,

  /** JSON-encoded `Array<String>` of succeeded item ULIDs. */
  @ColumnInfo(name = "succeededIds")
  val succeededIds: String,

  /** JSON-encoded `Array<String>` of failed item ULIDs. */
  @ColumnInfo(name = "failedIds")
  val failedIds: String,

  /** JSON-encoded `Map<String,String>` of failed itemId → error message. */
  @ColumnInfo(name = "errors")
  val errors: String,
)
