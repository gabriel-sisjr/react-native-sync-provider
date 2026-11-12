package com.margelo.nitro.syncprovider.database

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A queued request that failed for good: attempts exhausted, a response that is not
 * retried, or a credential rejected while it was live. It leaves the queue in the same
 * transaction, so it is never in both tables, and it counts toward no capacity cap.
 *
 * The request fields are the queue row's, unchanged.
 *
 * @property attempts attempts made in total.
 * @property lastAttemptAt epoch millis of the final attempt.
 * @property lastErrorCode the [com.margelo.nitro.syncprovider.SyncErrorCode] name of the
 *   final failure. A row moved from a schema without one holds `MAX_ATTEMPTS_EXCEEDED`.
 * @property lastErrorMessage the final failure's status line or exception message, at most
 *   [MAX_ERROR_MESSAGE_LENGTH] characters. Never a header or a body. `null` for a row
 *   moved from an earlier schema.
 * @property deadLetteredAt epoch millis of the move. Indexed: listings are ordered by it.
 */
@Keep
@Entity(
  tableName = "sync_dead_letters",
  indices = [Index(value = ["deadLetteredAt"])],
)
internal data class SyncDeadLetterEntity(
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

  @ColumnInfo(name = "priorityWeight")
  val priorityWeight: Int,

  @ColumnInfo(name = "createdAt")
  val createdAt: Long,

  @ColumnInfo(name = "metadata")
  val metadata: String?,

  @ColumnInfo(name = "attempts")
  val attempts: Int,

  @ColumnInfo(name = "lastAttemptAt")
  val lastAttemptAt: Long,

  @ColumnInfo(name = "lastErrorCode")
  val lastErrorCode: String,

  @ColumnInfo(name = "lastErrorMessage")
  val lastErrorMessage: String?,

  @ColumnInfo(name = "deadLetteredAt")
  val deadLetteredAt: Long,
) {
  companion object {
    const val MAX_ERROR_MESSAGE_LENGTH: Int = 1_024
  }
}
