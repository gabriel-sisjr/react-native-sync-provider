package com.margelo.nitro.syncprovider.testutil

import com.margelo.nitro.syncprovider.BackoffStrategy
import com.margelo.nitro.syncprovider.HttpMethod
import com.margelo.nitro.syncprovider.RetryPolicy
import com.margelo.nitro.syncprovider.SyncItemInput
import com.margelo.nitro.syncprovider.SyncOptions
import com.margelo.nitro.syncprovider.SyncPriority
import com.margelo.nitro.syncprovider.SyncStrategy
import com.margelo.nitro.syncprovider.database.SyncItemEntity

internal object TestData {

  fun retryPolicy(
    maxAttempts: Int = 3,
    backoff: BackoffStrategy = BackoffStrategy.EXPONENTIAL,
    baseDelayMs: Long = 100L,
    maxDelayMs: Long = 5_000L,
    jitter: Boolean = false,
    retryOnStatusCodes: IntArray = intArrayOf(408, 425, 429, 500, 502, 503, 504),
  ): RetryPolicy = RetryPolicy(
    maxAttempts = maxAttempts.toDouble(),
    backoff = backoff,
    baseDelayMs = baseDelayMs.toDouble(),
    maxDelayMs = maxDelayMs.toDouble(),
    jitter = jitter,
    retryOnStatusCodes = DoubleArray(retryOnStatusCodes.size) { i -> retryOnStatusCodes[i].toDouble() },
  )

  fun syncOptions(
    retryPolicy: RetryPolicy = retryPolicy(),
    batchSize: Int? = 25,
    requestTimeoutMs: Long? = 30_000L,
    maxQueueSize: Int? = 10_000,
    persistQueue: Boolean? = true,
    defaultHeaders: Map<String, String>? = null,
    strategy: SyncStrategy = SyncStrategy.AUTOMATIC,
  ): SyncOptions = SyncOptions(
    strategy = strategy,
    retryPolicy = retryPolicy,
    batchSize = batchSize?.toDouble(),
    requestTimeoutMs = requestTimeoutMs?.toDouble(),
    maxQueueSize = maxQueueSize?.toDouble(),
    persistQueue = persistQueue,
    defaultHeaders = defaultHeaders,
  )

  fun input(
    url: String = "https://api.example.com/v1/test",
    method: HttpMethod = HttpMethod.POST,
    headers: Map<String, String>? = mapOf("X-Test" to "1"),
    body: String? = """{"hello":"world"}""",
    contentType: String? = "application/json",
    priority: SyncPriority? = SyncPriority.NORMAL,
    metadata: Map<String, String>? = null,
  ): SyncItemInput = SyncItemInput(
    method = method,
    url = url,
    headers = headers,
    body = body,
    contentType = contentType,
    priority = priority,
    metadata = metadata,
  )

  fun entity(
    id: String,
    method: String = "POST",
    url: String = "https://api.example.com/v1/test",
    priority: String = "NORMAL",
    createdAt: Long = 1_700_000_000_000L,
    attempts: Int = 0,
    status: String = SyncItemEntity.STATUS_PENDING,
    body: String? = """{"hello":"world"}""",
    contentType: String? = "application/json",
  ): SyncItemEntity = SyncItemEntity(
    id = id,
    method = method,
    url = url,
    headers = null,
    body = body,
    contentType = contentType,
    priority = priority,
    priorityWeight = SyncItemEntity.priorityWeight(priority),
    createdAt = createdAt,
    attempts = attempts,
    lastAttemptAt = null,
    lastErrorCode = null,
    status = status,
    metadata = null,
  )
}
