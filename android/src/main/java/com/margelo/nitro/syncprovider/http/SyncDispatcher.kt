package com.margelo.nitro.syncprovider.http

import android.util.Log
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.SyncOptions
import com.margelo.nitro.syncprovider.SyncResult
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.retry.RetryPolicyEvaluator
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.util.JsonMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drains the queue by issuing HTTP requests via a singleton OkHttpClient.
 *
 * Concurrency model:
 * - The class is a singleton (within a [com.margelo.nitro.syncprovider.SyncProvider]
 *   instance); a [Mutex] gates the [flush] entry point so only one cycle runs
 *   at a time. Concurrent callers see the in-progress cycle and return its
 *   shared result via the `_isSyncing` flag (they get a NO-OP empty result).
 * - Within a cycle, items are dispatched via [supervisorScope] so a single
 *   item's failure cannot cancel siblings.
 * - Per-attempt timeout is enforced with `withTimeoutOrNull(...)`; the
 *   underlying OkHttp call also has its own timeouts.
 */
internal class SyncDispatcher(
  private val storage: SyncQueueStorage,
  private val emitter: SyncEventEmitter,
  private val getOptions: () -> SyncOptions,
  private val verboseLogging: () -> Boolean = { false },
) {
  private val flushMutex = Mutex()
  private val syncing = AtomicBoolean(false)

  /**
   * OkHttp is intentionally instantiated once. Per-cycle option changes
   * (timeouts) are honored by reading [SyncOptions.requestTimeoutMs] inside
   * [flush] and applying it via `newCall(...)` builder when needed; here we
   * pick conservative defaults.
   */
  private val client: OkHttpClient by lazy { buildClient() }

  fun isSyncing(): Boolean = syncing.get()

  /**
   * Run a single flush cycle. Returns the aggregated [SyncResult]. Per-item
   * failures are aggregated; the function only throws when the cycle itself
   * cannot start (e.g. the storage layer rejects with [SyncErrorCode.NETWORK_ERROR]).
   */
  suspend fun flush(): SyncResult = flushMutex.withLock {
    val options = getOptions()
    val startedAt = System.currentTimeMillis()
    syncing.set(true)
    val succeeded = mutableListOf<String>()
    val failed = mutableListOf<String>()
    val errors = HashMap<String, String>()
    try {
      emitter.emit(
        SyncEventEmitter.event(SyncEventType.SYNC_STARTED),
      )

      val batchSize = (options.batchSize?.toInt() ?: DEFAULT_BATCH_SIZE).coerceAtLeast(1)
      val claimed = storage.claimPending(batchSize)
      if (claimed.isEmpty()) {
        return@withLock buildResult(startedAt, succeeded, failed, errors)
      }

      supervisorScope {
        val deferreds = claimed.map { entity ->
          async(Dispatchers.IO) {
            runCatching { dispatchWithRetry(entity, options) }
              .fold(
                onSuccess = { outcome -> outcome },
                onFailure = { t ->
                  ItemOutcome.Failed(entity.id, SyncErrorCode.NETWORK_ERROR, t.message ?: "")
                },
              )
          }
        }
        val outcomes = deferreds.awaitAll()
        for (outcome in outcomes) {
          when (outcome) {
            is ItemOutcome.Succeeded -> succeeded += outcome.itemId
            is ItemOutcome.Failed -> {
              failed += outcome.itemId
              errors[outcome.itemId] = "${outcome.code.name}: ${outcome.message}"
            }
          }
        }
      }

      val result = buildResult(startedAt, succeeded, failed, errors)
      storage.recordResult(result)

      emitter.emit(
        SyncEventEmitter.event(
          type = if (failed.isEmpty()) SyncEventType.SYNC_SUCCEEDED else SyncEventType.SYNC_FAILED,
        ),
      )
      result
    } finally {
      syncing.set(false)
    }
  }

  /* ------------------------------ internals ----------------------------- */

  private suspend fun dispatchWithRetry(
    entity: SyncItemEntity,
    options: SyncOptions,
  ): ItemOutcome {
    val timeoutMs = (options.requestTimeoutMs?.toLong() ?: DEFAULT_REQUEST_TIMEOUT_MS)
      .coerceAtLeast(MIN_REQUEST_TIMEOUT_MS)
    val policy = options.retryPolicy
    val maxAttempts = policy.maxAttempts.toInt().coerceAtLeast(1)

    var attempt = entity.attempts
    var lastErrorCode: SyncErrorCode = SyncErrorCode.NETWORK_ERROR
    var lastErrorMessage: String = ""

    while (attempt < maxAttempts) {
      attempt += 1
      val now = System.currentTimeMillis()
      val outcome = runOnce(entity, timeoutMs, options.defaultHeaders)
      when (outcome) {
        is AttemptResult.Success -> {
          storage.markSucceeded(entity.id)
          emitter.emit(
            SyncEventEmitter.event(
              type = SyncEventType.ITEM_SUCCEEDED,
              itemId = entity.id,
              statusCode = outcome.statusCode,
              attempt = attempt,
            ),
          )
          return ItemOutcome.Succeeded(entity.id, outcome.statusCode)
        }
        is AttemptResult.Failure -> {
          lastErrorCode = outcome.code
          lastErrorMessage = outcome.message
          val canRetry = outcome.retryable &&
            RetryPolicyEvaluator.hasBudget(attempt, policy)
          if (!canRetry) {
            val terminalCode = if (!RetryPolicyEvaluator.hasBudget(attempt, policy) && outcome.retryable) {
              SyncErrorCode.MAX_ATTEMPTS_EXCEEDED
            } else {
              outcome.code
            }
            storage.markFailed(entity.id, attempt, now, terminalCode)
            emitter.emit(
              SyncEventEmitter.event(
                type = SyncEventType.ITEM_FAILED,
                itemId = entity.id,
                errorCode = terminalCode,
                statusCode = outcome.statusCode,
                attempt = attempt,
              ),
            )
            return ItemOutcome.Failed(entity.id, terminalCode, outcome.message)
          }
          // Will retry inside the same flush cycle: emit RETRYING and back-off.
          emitter.emit(
            SyncEventEmitter.event(
              type = SyncEventType.ITEM_RETRYING,
              itemId = entity.id,
              errorCode = outcome.code,
              statusCode = outcome.statusCode,
              attempt = attempt,
            ),
          )
          val delayMs = RetryPolicyEvaluator.computeDelayMs(attempt, policy)
          if (delayMs > 0) delay(delayMs)
          // Persist the increment so a crash mid-cycle doesn't lose the count.
          storage.markRetry(entity.id, attempt, now, outcome.code)
        }
      }
    }
    // Budget exhausted without success — already persisted above on the last
    // failed attempt path; defensive fallback in case we ever exit the loop.
    return ItemOutcome.Failed(entity.id, lastErrorCode, lastErrorMessage)
  }

  private suspend fun runOnce(
    entity: SyncItemEntity,
    timeoutMs: Long,
    defaultHeaders: Map<String, String>?,
  ): AttemptResult = withContext(Dispatchers.IO) {
    val request = buildRequest(entity, defaultHeaders) ?: return@withContext AttemptResult.Failure(
      code = SyncErrorCode.INVALID_PAYLOAD,
      message = "Failed to build request for item ${entity.id}",
      statusCode = null,
      retryable = false,
    )

    val call = client.newBuilder()
      .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
      .build()
      .newCall(request)

    val attemptOutcome: AttemptResult = try {
      val response = withTimeoutOrNull(timeoutMs) {
        runCatching { call.execute() }
      }
      if (response == null) {
        runCatching { call.cancel() }
        return@withContext AttemptResult.Failure(
          code = SyncErrorCode.TIMEOUT,
          message = "Request timed out (${timeoutMs}ms)",
          statusCode = null,
          retryable = true,
        )
      }
      response.fold(
        onSuccess = { httpResponse ->
          httpResponse.use { resp ->
            val code = resp.code
            val policy = getOptions().retryPolicy
            when {
              code in 200..299 -> AttemptResult.Success(code)
              code == 401 || code == 403 -> AttemptResult.Failure(
                code = SyncErrorCode.UNAUTHORIZED,
                message = "HTTP $code",
                statusCode = code,
                retryable = false,
              )
              RetryPolicyEvaluator.shouldRetryStatus(code, policy) -> AttemptResult.Failure(
                code = SyncErrorCode.SERVER_ERROR,
                message = "HTTP $code",
                statusCode = code,
                retryable = true,
              )
              code in 400..499 -> AttemptResult.Failure(
                code = SyncErrorCode.SERVER_ERROR,
                message = "HTTP $code",
                statusCode = code,
                retryable = false,
              )
              else -> AttemptResult.Failure(
                code = SyncErrorCode.SERVER_ERROR,
                message = "HTTP $code",
                statusCode = code,
                retryable = false,
              )
            }
          }
        },
        onFailure = { t ->
          when (t) {
            is SocketTimeoutException -> AttemptResult.Failure(
              code = SyncErrorCode.TIMEOUT,
              message = t.message ?: "Request timed out",
              statusCode = null,
              retryable = true,
            )
            is IOException -> AttemptResult.Failure(
              code = SyncErrorCode.NETWORK_ERROR,
              message = t.message ?: "Network error",
              statusCode = null,
              retryable = true,
            )
            else -> AttemptResult.Failure(
              code = SyncErrorCode.NETWORK_ERROR,
              message = t.message ?: t.javaClass.simpleName,
              statusCode = null,
              retryable = false,
            )
          }
        },
      )
    } catch (t: Throwable) {
      runCatching { call.cancel() }
      AttemptResult.Failure(
        code = SyncErrorCode.NETWORK_ERROR,
        message = t.message ?: t.javaClass.simpleName,
        statusCode = null,
        retryable = false,
      )
    }
    attemptOutcome
  }

  private fun buildRequest(
    entity: SyncItemEntity,
    defaultHeaders: Map<String, String>?,
  ): Request? = runCatching {
    val builder = Request.Builder().url(entity.url)
    defaultHeaders?.forEach { (k, v) -> builder.header(k, v) }
    JsonMap.decode(entity.headers)?.forEach { (k, v) -> builder.header(k, v) }

    val body: RequestBody? = when (entity.method.uppercase()) {
      "GET", "DELETE" -> null
      else -> {
        val mediaType = (entity.contentType ?: "application/json").toMediaTypeOrNull()
        (entity.body ?: "").toByteArray(Charsets.UTF_8).toRequestBody(mediaType)
      }
    }
    builder.method(entity.method.uppercase(), body)
    builder.build()
  }.getOrElse {
    Log.e(TAG, "Failed to build OkHttp request for ${entity.id}", it)
    null
  }

  private fun buildClient(): OkHttpClient {
    val builder = OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .retryOnConnectionFailure(false)

    if (verboseLogging()) {
      val logging = HttpLoggingInterceptor { msg -> Log.d(TAG, msg) }
      logging.level = HttpLoggingInterceptor.Level.BODY
      builder.addInterceptor(logging)
    }
    return builder.build()
  }

  private fun buildResult(
    startedAt: Long,
    succeeded: List<String>,
    failed: List<String>,
    errors: Map<String, String>,
  ): SyncResult = SyncResult(
    startedAt = startedAt.toDouble(),
    finishedAt = System.currentTimeMillis().toDouble(),
    successCount = succeeded.size.toDouble(),
    failureCount = failed.size.toDouble(),
    succeededIds = succeeded.toTypedArray(),
    failedIds = failed.toTypedArray(),
    errors = errors,
  )

  private sealed interface AttemptResult {
    data class Success(val statusCode: Int) : AttemptResult
    data class Failure(
      val code: SyncErrorCode,
      val message: String,
      val statusCode: Int?,
      val retryable: Boolean,
    ) : AttemptResult
  }

  private sealed interface ItemOutcome {
    val itemId: String
    data class Succeeded(override val itemId: String, val statusCode: Int) : ItemOutcome
    data class Failed(
      override val itemId: String,
      val code: SyncErrorCode,
      val message: String,
    ) : ItemOutcome
  }

  companion object {
    private const val TAG = "SyncDispatcher"
    private const val DEFAULT_BATCH_SIZE = 25
    private const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 30_000L
    private const val MIN_REQUEST_TIMEOUT_MS: Long = 1_000L
    private const val CONNECT_TIMEOUT_MS: Long = 10_000L
    private const val READ_TIMEOUT_MS: Long = 30_000L
    private const val WRITE_TIMEOUT_MS: Long = 30_000L
  }
}
