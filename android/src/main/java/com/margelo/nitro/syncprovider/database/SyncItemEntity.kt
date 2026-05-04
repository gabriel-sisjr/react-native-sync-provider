package com.margelo.nitro.syncprovider.database

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted representation of a queued HTTP request.
 *
 * @property id ULID assigned by [com.margelo.nitro.syncprovider.util.Ulid].
 * @property method HTTP verb name (`GET`, `POST`, …).
 * @property url Absolute http(s) URL to dispatch.
 * @property headers JSON-encoded `Map<String,String>` (or `null` when absent).
 * @property body Request body, currently always serialized to a UTF-8 string.
 * @property contentType MIME type to apply to the request body. Defaults to
 *   `application/json` for write methods when unset.
 * @property priority `HIGH | NORMAL | LOW`; mapped to a sort weight on read.
 * @property createdAt Epoch-millis timestamp of enqueue.
 * @property attempts Total number of dispatch attempts (success or failure).
 * @property lastAttemptAt Epoch-millis of the most recent attempt, or `null`.
 * @property lastErrorCode Last [com.margelo.nitro.syncprovider.SyncErrorCode]
 *   name observed on this row, or `null` if never failed.
 * @property status One of `PENDING`, `IN_FLIGHT`, `FAILED`.
 * @property metadata Free-form JSON blob attached by the consumer.
 */
@Keep
@Entity(
  tableName = "sync_items",
  indices = [
    Index(value = ["status"]),
    Index(value = ["priorityWeight", "createdAt"]),
  ],
)
internal data class SyncItemEntity(
  @PrimaryKey
  @ColumnInfo(name = "id")
  val id: String,

  @ColumnInfo(name = "method")
  val method: String,

  @ColumnInfo(name = "url")
  val url: String,

  @ColumnInfo(name = "headers")
  val headers: String?,

  @ColumnInfo(name = "body")
  val body: String?,

  @ColumnInfo(name = "contentType")
  val contentType: String?,

  @ColumnInfo(name = "priority")
  val priority: String,

  /** Numeric mirror of [priority] used for SQL ordering (HIGH=0, NORMAL=1, LOW=2). */
  @ColumnInfo(name = "priorityWeight")
  val priorityWeight: Int,

  @ColumnInfo(name = "createdAt")
  val createdAt: Long,

  @ColumnInfo(name = "attempts")
  val attempts: Int,

  @ColumnInfo(name = "lastAttemptAt")
  val lastAttemptAt: Long?,

  @ColumnInfo(name = "lastErrorCode")
  val lastErrorCode: String?,

  @ColumnInfo(name = "status")
  val status: String,

  @ColumnInfo(name = "metadata")
  val metadata: String?,
) {
  companion object {
    const val STATUS_PENDING = "PENDING"
    const val STATUS_IN_FLIGHT = "IN_FLIGHT"
    const val STATUS_FAILED = "FAILED"

    fun priorityWeight(priority: String): Int = when (priority.uppercase()) {
      "HIGH" -> 0
      "LOW" -> 2
      else -> 1 // NORMAL is the default
    }
  }
}
