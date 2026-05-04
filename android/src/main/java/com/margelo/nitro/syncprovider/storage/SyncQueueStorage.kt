package com.margelo.nitro.syncprovider.storage

import com.margelo.nitro.syncprovider.HttpMethod
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncItem
import com.margelo.nitro.syncprovider.SyncItemInput
import com.margelo.nitro.syncprovider.SyncPriority
import com.margelo.nitro.syncprovider.SyncResult
import com.margelo.nitro.syncprovider.database.SyncDatabase
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
 * to enqueue beyond it, [SyncErrorCode.QUEUE_FULL] is thrown — no eviction.
 */
internal class SyncQueueStorage(
  private val itemDao: SyncItemDao,
  private val resultDao: SyncResultDao,
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

  suspend fun removeItem(id: String): Boolean = withContext(Dispatchers.IO) {
    itemDao.deleteById(id) > 0
  }

  suspend fun clear(): Unit = withContext(Dispatchers.IO) { itemDao.clear() }

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
   * Atomically claim the next [limit] pending rows, marking them `IN_FLIGHT`.
   * Returned rows are ordered by priority then `createdAt`.
   */
  suspend fun claimPending(limit: Int): List<SyncItemEntity> = withContext(Dispatchers.IO) {
    val rows = itemDao.findByStatusesLimit(listOf(SyncItemEntity.STATUS_PENDING), limit)
    rows.forEach { entity ->
      itemDao.updateStatus(
        id = entity.id,
        status = SyncItemEntity.STATUS_IN_FLIGHT,
        attempts = entity.attempts,
        lastAttemptAt = entity.lastAttemptAt,
        lastErrorCode = entity.lastErrorCode,
      )
    }
    rows
  }

  suspend fun markSucceeded(id: String): Unit = withContext(Dispatchers.IO) {
    itemDao.deleteById(id)
  }

  suspend fun markRetry(
    id: String,
    attempts: Int,
    lastAttemptAt: Long,
    lastErrorCode: SyncErrorCode,
  ): Unit = withContext(Dispatchers.IO) {
    itemDao.updateStatus(
      id = id,
      status = SyncItemEntity.STATUS_PENDING,
      attempts = attempts,
      lastAttemptAt = lastAttemptAt,
      lastErrorCode = lastErrorCode.name,
    )
  }

  suspend fun markFailed(
    id: String,
    attempts: Int,
    lastAttemptAt: Long,
    lastErrorCode: SyncErrorCode,
  ): Unit = withContext(Dispatchers.IO) {
    itemDao.updateStatus(
      id = id,
      status = SyncItemEntity.STATUS_FAILED,
      attempts = attempts,
      lastAttemptAt = lastAttemptAt,
      lastErrorCode = lastErrorCode.name,
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
    /** Default cap from the ux-researcher decisions brief. */
    const val DEFAULT_MAX_QUEUE_SIZE: Int = 10_000

    fun from(
      database: SyncDatabase,
      maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    ): SyncQueueStorage = SyncQueueStorage(
      itemDao = database.syncItemDao(),
      resultDao = database.syncResultDao(),
      maxQueueSize = maxQueueSize,
    )
  }
}
