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
 * @property attempts Attempts recorded for this item. An attempt is recorded together
 *   with its outcome, so a request cut short by a process kill leaves the count as it
 *   was, and a rejection of a credential restored from disk keeps the count from before
 *   the dispatch. `0` means no attempt recorded, not that no request was ever sent.
 * @property lastAttemptAt Epoch-millis of the most recent attempt, or `null`.
 * @property lastErrorCode Last [com.margelo.nitro.syncprovider.SyncErrorCode]
 *   name observed on this row, or `null` if never failed.
 * @property status `PENDING` or `IN_FLIGHT`. Schemas before v3 also held `FAILED` rows; the
 *   v2 -> v3 migration moves them to [SyncDeadLetterEntity], as every terminal failure now is.
 * @property metadata Free-form JSON blob attached by the consumer.
 * @property nextRetryAt Epoch-millis before which a `PENDING` row is not eligible for
 *   dispatch. `0` means "eligible now": no cooldown or credential hold is pending. A
 *   positive value is a retry cooldown or a credential hold, and the row is eligible
 *   again once `nextRetryAt <= now`. `0` does not mean "never attempted": a request cut
 *   short by a process kill records nothing, and a row migrated from the v1 schema
 *   keeps its `attempts` with `nextRetryAt = 0`. Indexed with [status] via
 *   `index_sync_items_status_nextRetryAt`, which serves the claim query.
 */
@Keep
@Entity(
  tableName = "sync_items",
  indices = [
    // Composite (status, nextRetryAt): serves status-only lookups via its left
    // prefix and the "eligible PENDING rows" claim query. It supersedes the v1
    // single-column `index_sync_items_status`, which the v1 -> v2 migration drops.
    Index(value = ["status", "nextRetryAt"]),
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

  /**
   * Epoch-millis retry-eligibility deadline; `0` (default) means "eligible now", with
   * no cooldown or credential hold pending. The SQL `DEFAULT 0` keeps the v1 -> v2
   * `ALTER TABLE ADD COLUMN` in sync with the fresh-install schema so Room's migration
   * validation passes.
   */
  @ColumnInfo(name = "nextRetryAt", defaultValue = "0")
  val nextRetryAt: Long,
) {
  companion object {
    const val STATUS_PENDING = "PENDING"
    const val STATUS_IN_FLIGHT = "IN_FLIGHT"
    /** Only in schemas before v3, which the v2 -> v3 migration moves to the dead letters. */
    const val STATUS_FAILED = "FAILED"

    fun priorityWeight(priority: String): Int = when (priority.uppercase()) {
      "HIGH" -> 0
      "LOW" -> 2
      else -> 1 // NORMAL is the default
    }
  }
}
