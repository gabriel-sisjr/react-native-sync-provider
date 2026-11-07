package com.margelo.nitro.syncprovider.storage

import com.margelo.nitro.syncprovider.HttpMethod
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncItem
import com.margelo.nitro.syncprovider.SyncItemInput
import com.margelo.nitro.syncprovider.SyncPriority
import com.margelo.nitro.syncprovider.SyncResult
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.database.SyncDeadLetterDao
import com.margelo.nitro.syncprovider.database.SyncDeadLetterEntity
import com.margelo.nitro.syncprovider.database.SyncItemDao
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.database.SyncResultDao
import com.margelo.nitro.syncprovider.database.SyncResultEntity
import com.margelo.nitro.syncprovider.error.SyncException
import com.margelo.nitro.syncprovider.util.JsonMap
import com.margelo.nitro.syncprovider.util.Ulid
import com.margelo.nitro.syncprovider.util.UrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

/**
 * High-level persistence facade.
 *
 * Every public function runs the underlying SQL on [Dispatchers.IO]. Callers
 * may invoke this from any dispatcher.
 *
 * Capacity is enforced softly via [maxQueueSize]: when the consumer attempts
 * to enqueue beyond it, [SyncErrorCode.QUEUE_FULL] is thrown — no eviction. Only queued
 * rows count: dead letters count toward no cap, and nothing prunes them.
 */
internal class SyncQueueStorage(
  private val itemDao: SyncItemDao,
  private val resultDao: SyncResultDao,
  private val deadLetterDao: SyncDeadLetterDao,
  @Volatile var maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
) {

  /* ----------------------------- queue ops ------------------------------ */

  suspend fun enqueue(input: SyncItemInput): String = withContext(Dispatchers.IO) {
    validate(input)
    val current = itemDao.count()
    if (current >= maxQueueSize) {
      throw SyncException(
        SyncErrorCode.QUEUE_FULL,
        "Queue is full (size=$current, max=$maxQueueSize).",
      )
    }
    val entity = toEntity(input)
    itemDao.insert(entity)
    entity.id
  }

  suspend fun enqueueBatch(inputs: List<SyncItemInput>): List<String> =
    withContext(Dispatchers.IO) {
      if (inputs.isEmpty()) return@withContext emptyList()
      inputs.forEach { validate(it) }
      val current = itemDao.count()
      if (current + inputs.size > maxQueueSize) {
        throw SyncException(
          SyncErrorCode.QUEUE_FULL,
          "Queue would exceed cap (size=$current, incoming=${inputs.size}, max=$maxQueueSize).",
        )
      }
      val entities = inputs.map { toEntity(it) }
      itemDao.insertBatch(entities)
      entities.map { it.id }
    }

  /** Remove a queued row, or else a dead letter with that id. True when either was deleted. */
  suspend fun removeItem(id: String): Boolean = withContext(Dispatchers.IO) {
    itemDao.deleteById(id) > 0 || deadLetterDao.deleteById(id) > 0
  }

  /** Empty the queue and the dead letters, together. */
  suspend fun clear(): Unit = withContext(Dispatchers.IO) { deadLetterDao.clearAll() }

  suspend fun count(): Int = withContext(Dispatchers.IO) { itemDao.count() }

  suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
    itemDao.countByStatus(SyncItemEntity.STATUS_PENDING)
  }

  suspend fun getPending(limit: Int = 0): List<SyncItem> = withContext(Dispatchers.IO) {
    val rows = if (limit > 0) {
      itemDao.findByStatusesLimit(
        listOf(SyncItemEntity.STATUS_PENDING),
        limit,
      )
    } else {
      itemDao.findByStatuses(listOf(SyncItemEntity.STATUS_PENDING))
    }
    rows.map { toSyncItem(it) }
  }

  /**
   * Atomically claim the next [limit] **eligible** pending rows, marking them
   * `IN_FLIGHT`. A row is eligible only when its retry cooldown has elapsed
   * (`nextRetryAt <= now`); cooling rows are skipped. Returned rows are
   * ordered by priority then `createdAt`.
   *
   * The select-then-flip runs in a single Room `@Transaction`
   * ([SyncItemDao.claimPending]) so concurrent cycles cannot double-claim.
   *
   * @param now epoch-millis clock; defaults to wall-clock, injectable for tests.
   */
  suspend fun claimPending(
    limit: Int,
    now: Long = System.currentTimeMillis(),
  ): List<SyncItemEntity> = withContext(Dispatchers.IO) {
    itemDao.claimPending(now, limit)
  }

  /**
   * Earliest deadline among `PENDING` rows with a retry cooldown or a credential hold,
   * or `null` when none exist. A deadline in the past means a retry is due now.
   */
  suspend fun nextRetryDeadline(): Long? =
    withContext(Dispatchers.IO) { itemDao.nextRetryDeadline() }

  /**
   * Earliest deadline across every `PENDING` row, or `null` when the queue has
   * none. `0` means at least one row is eligible now, with no deadline.
   */
  suspend fun nextPendingDeadline(): Long? =
    withContext(Dispatchers.IO) { itemDao.nextPendingDeadline() }

  suspend fun markSucceeded(id: String): Unit = withContext(Dispatchers.IO) {
    itemDao.deleteById(id)
  }

  /**
   * Re-queue a retriable item as `PENDING` and persist its cooldown deadline.
   * The row is excluded from [claimPending] until `nextRetryAt <= now`.
   */
  suspend fun markRetry(
    id: String,
    attempts: Int,
    lastAttemptAt: Long,
    lastErrorCode: SyncErrorCode,
    nextRetryAt: Long,
  ): Unit = withContext(Dispatchers.IO) {
    itemDao.updateStatus(
      id = id,
      status = SyncItemEntity.STATUS_PENDING,
      attempts = attempts,
      lastAttemptAt = lastAttemptAt,
      lastErrorCode = lastErrorCode.name,
      nextRetryAt = nextRetryAt,
    )
  }

  /**
   * A terminal failure: move the claimed row to the dead letters, in one transaction, with
   * the outcome of its final attempt.
   *
   * @param attempts attempts made in total.
   * @param lastErrorMessage cut to [SyncDeadLetterEntity.MAX_ERROR_MESSAGE_LENGTH].
   */
  @Suppress("LongParameterList")
  suspend fun moveToDeadLetters(
    entity: SyncItemEntity,
    attempts: Int,
    lastAttemptAt: Long,
    lastErrorCode: SyncErrorCode,
    lastErrorMessage: String?,
    deadLetteredAt: Long,
  ): Unit = withContext(Dispatchers.IO) {
    deadLetterDao.moveFromQueue(
      SyncDeadLetterEntity(
        id = entity.id,
        method = entity.method,
        url = entity.url,
        headers = entity.headers,
        body = entity.body,
        contentType = entity.contentType,
        priority = entity.priority,
        priorityWeight = entity.priorityWeight,
        createdAt = entity.createdAt,
        metadata = entity.metadata,
        attempts = attempts,
        lastAttemptAt = lastAttemptAt,
        lastErrorCode = lastErrorCode.name,
        lastErrorMessage = lastErrorMessage?.take(SyncDeadLetterEntity.MAX_ERROR_MESSAGE_LENGTH),
        deadLetteredAt = deadLetteredAt,
      ),
    )
  }

  suspend fun resetInFlight(): Int = withContext(Dispatchers.IO) {
    itemDao.resetInFlight()
  }

  fun observeAll(): Flow<List<SyncItem>> =
    itemDao.observeAll().map { rows -> rows.map(::toSyncItem) }

  /* ---------------------------- history ops ----------------------------- */

  suspend fun recordResult(result: SyncResult): Unit = withContext(Dispatchers.IO) {
    val entity = SyncResultEntity(
      id = UUID.randomUUID().toString(),
      startedAt = result.startedAt.toLong(),
      finishedAt = result.finishedAt.toLong(),
      successCount = result.successCount.toInt(),
      failureCount = result.failureCount.toInt(),
      succeededIds = stringArrayToJson(result.succeededIds),
      failedIds = stringArrayToJson(result.failedIds),
      errors = JsonMap.encode(result.errors).orEmpty().ifEmpty { "{}" },
    )
    resultDao.insert(entity)
  }

  suspend fun getLastResult(): SyncResult? = withContext(Dispatchers.IO) {
    resultDao.findLast()?.let(::toSyncResult)
  }

  suspend fun getHistory(limit: Int = 0): List<SyncResult> = withContext(Dispatchers.IO) {
    val rows = if (limit > 0) resultDao.findLatest(limit) else resultDao.findAll()
    rows.map(::toSyncResult)
  }

  suspend fun clearHistory(): Unit = withContext(Dispatchers.IO) { resultDao.clear() }

  /* ----------------------------- helpers -------------------------------- */

  private fun validate(input: SyncItemInput) {
    if (input.url.isBlank() || !UrlValidator.isValid(input.url)) {
      throw SyncException(
        SyncErrorCode.INVALID_URL,
        "Item url is not a valid absolute http(s) URL: '${input.url}'.",
      )
    }
  }

  private fun toEntity(input: SyncItemInput): SyncItemEntity {
    val priority = (input.priority ?: SyncPriority.NORMAL).name
    val now = System.currentTimeMillis()
    return SyncItemEntity(
      id = Ulid.generate(now),
      method = input.method.name,
      url = input.url,
      headers = JsonMap.encode(input.headers),
      body = input.body,
      contentType = input.contentType,
      priority = priority,
      priorityWeight = SyncItemEntity.priorityWeight(priority),
      createdAt = now,
      attempts = 0,
      lastAttemptAt = null,
      lastErrorCode = null,
      status = SyncItemEntity.STATUS_PENDING,
      metadata = JsonMap.encode(input.metadata),
      // 0 = eligible now: no cooldown or credential hold.
      nextRetryAt = 0L,
    )
  }

  private fun toSyncItem(entity: SyncItemEntity): SyncItem = SyncItem(
    id = entity.id,
    method = parseMethod(entity.method),
    url = entity.url,
    headers = JsonMap.decode(entity.headers),
    body = entity.body,
    contentType = entity.contentType,
    priority = parsePriority(entity.priority),
    createdAt = entity.createdAt.toDouble(),
    attempts = entity.attempts.toDouble(),
    nextRetryAt = entity.nextRetryAt.toDouble(),
    metadata = JsonMap.decode(entity.metadata),
  )

  private fun toSyncResult(entity: SyncResultEntity): SyncResult = SyncResult(
    startedAt = entity.startedAt.toDouble(),
    finishedAt = entity.finishedAt.toDouble(),
    successCount = entity.successCount.toDouble(),
    failureCount = entity.failureCount.toDouble(),
    succeededIds = jsonToStringArray(entity.succeededIds),
    failedIds = jsonToStringArray(entity.failedIds),
    errors = JsonMap.decode(entity.errors).orEmpty(),
  )

  private fun parseMethod(name: String): HttpMethod = runCatching {
    HttpMethod.valueOf(name.uppercase())
  }.getOrDefault(HttpMethod.GET)

  private fun parsePriority(name: String): SyncPriority = runCatching {
    SyncPriority.valueOf(name.uppercase())
  }.getOrDefault(SyncPriority.NORMAL)

  private fun stringArrayToJson(values: Array<String>): String {
    val arr = JSONArray()
    for (v in values) arr.put(v)
    return arr.toString()
  }

  private fun jsonToStringArray(raw: String): Array<String> {
    if (raw.isBlank()) return emptyArray()
    return runCatching {
      val arr = JSONArray(raw)
      Array(arr.length()) { i -> arr.optString(i, "") }
    }.getOrDefault(emptyArray())
  }

  companion object {
    /** Queue cap used when `SyncOptions.maxQueueSize` is not set. */
    const val DEFAULT_MAX_QUEUE_SIZE: Int = 10_000

    fun from(
      database: SyncDatabase,
      maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    ): SyncQueueStorage = SyncQueueStorage(
      itemDao = database.syncItemDao(),
      resultDao = database.syncResultDao(),
      deadLetterDao = database.syncDeadLetterDao(),
      maxQueueSize = maxQueueSize,
    )
  }
}
