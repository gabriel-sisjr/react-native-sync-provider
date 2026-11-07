package com.margelo.nitro.syncprovider.http

import android.util.Log
import com.margelo.nitro.syncprovider.NetworkQuality
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.SyncOptions
import com.margelo.nitro.syncprovider.SyncResult
import com.margelo.nitro.syncprovider.connectivity.meetsMinimum
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.error.SyncException
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.retry.RetryPolicyEvaluator
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.util.JsonMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlin.random.Random

/**
 * Drains the queue by issuing HTTP requests via a singleton OkHttpClient.
 *
 * Retry model (persisted cooldowns):
 * - Each flush cycle makes **at most one** HTTP attempt per claimed item. On a
 *   retriable failure the item returns to `PENDING` with
 *   `nextRetryAt = <attempt finished> + backoff`, and [SyncQueueStorage.claimPending]
 *   skips it until that deadline elapses.
 * - [SyncItemEntity.attempts] is cross-cycle: it continues from whatever prior
 *   cycles persisted, so the retry budget survives process death and reboots.
 * - [onCycleFinished] fires after every cycle, whatever its outcome, so the next
 *   wake is always re-armed.
 *
 * Concurrency model:
 * - [flushMutex] serializes cycles: a concurrent [flush] waits for the running one
 *   and then runs its own.
 * - Within a cycle, items are dispatched via [supervisorScope] so a single item's
 *   failure cannot cancel siblings.
 * - A dispatch that started always records its outcome, even if the cycle is
 *   cancelled. Rows that never started are released back to `PENDING`.
 * - Per-attempt timeout is enforced with `withTimeoutOrNull(...)`; the underlying
 *   OkHttp call also has its own timeouts.
 *
 * @param clock epoch-millis source; injectable so tests can move time.
 * @param random PRNG forwarded to [RetryPolicyEvaluator.computeDelayMs] for
 *   back-off jitter; injectable for reproducible delays in tests.
 * @param isPaused checked inside the mutex, so every caller obeys it, workers included.
 * @param credentialPolicy read once per cycle. See [CredentialPolicy].
 * @param networkQuality the classification the `minNetworkQuality` gate compares. Read only
 *   while a gate is configured.
 * @param healthCheck run before the claim while `healthCheckUrl` is set.
 * @param onHealthCheckSkipped called after every cycle the health check skipped, to arm the
 *   next wake no earlier than its backoff.
 * @param onCycleFinished called after every cycle. Its argument, when present, is
 *   the earliest instant the next wake may fire. Failures are logged and never
 *   reject a cycle that already completed.
 */
internal class SyncDispatcher(
  private val storage: SyncQueueStorage,
  private val emitter: SyncEventEmitter,
  private val getOptions: () -> SyncOptions,
  private val verboseLogging: () -> Boolean = { false },
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val random: Random = Random.Default,
  private val isPaused: () -> Boolean = { false },
  private val credentialPolicy: () -> CredentialPolicy = { CredentialPolicy.TRUSTED },
  private val networkQuality: () -> NetworkQuality = { NetworkQuality.GOOD },
  private val healthCheck: HealthCheck = HealthCheck(HeadProbe()),
  private val onHealthCheckSkipped: suspend () -> Unit = {},
  private val onCycleFinished: suspend (notBefore: Long?) -> Unit = {},
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
   * cannot run (e.g. the storage layer fails).
   *
   * See [flushWithOutcome] for when no cycle runs.
   */
  suspend fun flush(): SyncResult = flushWithOutcome().result

  /**
   * [flush], telling callers that act on it whether a cycle ran. Every flush passes these
   * checks first, in order, and skips the cycle at the first that fails, with the empty
   * sentinel result and no `SYNC_STARTED`:
   * 1. paused;
   * 2. default headers that cannot be recovered;
   * 3. the network-quality gate: with `minNetworkQuality` set to more than `OFFLINE` and
   *    [networkQuality] below it, `SYNC_FAILED` is emitted with `NETWORK_QUALITY_TOO_LOW`;
   * 4. the health check, while `healthCheckUrl` is set: one `HEAD` unless a `2xx` answer is
   *    younger than `healthCheckTtlMs`. A failure, and every flush during the backoff that
   *    follows it, emits `SYNC_FAILED` with `HEALTH_CHECK_FAILED`.
   * No history row is written and no item changes.
   */
  suspend fun flushWithOutcome(): FlushOutcome = flushMutex.withLock {
    if (isPaused()) return@withLock FlushOutcome.Skipped(FlushSkip.PAUSED)
    val credentials = credentialPolicy()
    if (credentials == CredentialPolicy.UNAVAILABLE) {
      Log.w(TAG, "Default headers cannot be recovered; leaving the queue untouched.")
      return@withLock FlushOutcome.Skipped(FlushSkip.CREDENTIALS_UNAVAILABLE)
    }
    val options = getOptions()
    if (isBelowMinimumQuality(options)) {
      emitter.emit(
        SyncEventEmitter.event(
          type = SyncEventType.SYNC_FAILED,
          errorCode = SyncErrorCode.NETWORK_QUALITY_TOO_LOW,
        ),
      )
      return@withLock FlushOutcome.Skipped(FlushSkip.NETWORK_QUALITY_TOO_LOW)
    }
    healthCheckSkip(options)?.let { return@withLock FlushOutcome.Skipped(it) }
    FlushOutcome.Ran(runCycle(options, credentials))
  }

  /**
   * Reset rows left `IN_FLIGHT` by a previous process. Takes the flush mutex because
   * a running cycle's rows are legitimately `IN_FLIGHT`: resetting them would make
   * them claimable while their request is still on the wire.
   */
  suspend fun resetInFlight(): Int = flushMutex.withLock { storage.resetInFlight() }

  /* ------------------------------ internals ----------------------------- */

  /** Null when the cycle may run. Otherwise the event is emitted and the next wake armed. */
  private suspend fun healthCheckSkip(options: SyncOptions): FlushSkip? {
    val url = options.healthCheckUrl ?: return null
    val verdict = healthCheck.check(
      url = url,
      ttlMs = options.healthCheckTtlMs?.toLong() ?: HealthCheck.DEFAULT_TTL_MS,
      backoffMs = options.healthCheckBackoffMs?.toLong() ?: HealthCheck.DEFAULT_BACKOFF_MS,
    )
    val skip = when (verdict) {
      HealthCheck.Verdict.Healthy -> return null
      is HealthCheck.Verdict.Failed -> FlushSkip.HEALTH_CHECK_FAILED
      is HealthCheck.Verdict.BackingOff -> FlushSkip.HEALTH_CHECK_BACKOFF
    }
    emitter.emit(
      SyncEventEmitter.event(
        type = SyncEventType.SYNC_FAILED,
        errorCode = SyncErrorCode.HEALTH_CHECK_FAILED,
      ),
    )
    runCatching { onHealthCheckSkipped() }
      .onFailure { Log.w(TAG, "Could not arm the wake after the health check", it) }
    return skip
  }

  /** Reads [networkQuality] only while a gate is configured: unset and `OFFLINE` gate nothing. */
  private fun isBelowMinimumQuality(options: SyncOptions): Boolean {
    val minimum = options.minNetworkQuality ?: return false
    if (minimum == NetworkQuality.OFFLINE) return false
    return !networkQuality().meetsMinimum(minimum)
  }

  private suspend fun runCycle(options: SyncOptions, credentials: CredentialPolicy): SyncResult {
    val startedAt = clock()
    syncing.set(true)
    var notBefore: Long? = null
    var terminalEventSent = false
    try {
      emitter.emit(SyncEventEmitter.event(SyncEventType.SYNC_STARTED))

      val batchSize = (options.batchSize?.toInt() ?: DEFAULT_BATCH_SIZE).coerceAtLeast(1)
      val claimed = storage.claimPending(batchSize, startedAt)
      val outcomes = if (claimed.isEmpty()) {
        emptyList()
      } else {
        dispatchClaimed(claimed, options, credentials)
      }
      notBefore = outcomes
        .filterIsInstance<ItemOutcome.CredentialRejected>()
        .maxOfOrNull { it.nextRetryAt }

      val result = buildResult(startedAt, outcomes)
      storage.recordResult(result)

      terminalEventSent = true
      emitter.emit(
        SyncEventEmitter.event(
          type = if (result.failedIds.isEmpty()) {
            SyncEventType.SYNC_SUCCEEDED
          } else {
            SyncEventType.SYNC_FAILED
          },
        ),
      )
      return result
    } catch (t: Throwable) {
      withContext(NonCancellable) { abortCycle(t, terminalEventSent) }
      throw t
    } finally {
      syncing.set(false)
      withContext(NonCancellable) {
        runCatching { onCycleFinished(notBefore) }
          .onFailure { Log.w(TAG, "Could not arm the next retry wake", it) }
      }
    }
  }

  /**
   * Leave no path without a terminal event and no row stranded. On cancellation the
   * supervisor scope has already waited for its children and this cycle still holds
   * the mutex, so any row left `IN_FLIGHT` is one that never started.
   */
  private suspend fun abortCycle(cause: Throwable, terminalEventSent: Boolean) {
    if (cause is CancellationException) {
      runCatching { storage.resetInFlight() }
        .onFailure { Log.e(TAG, "Could not release unattempted rows", it) }
    }
    if (terminalEventSent) return
    runCatching {
      emitter.emit(
        SyncEventEmitter.event(
          type = SyncEventType.SYNC_FAILED,
          errorCode = (cause as? SyncException)?.code ?: SyncErrorCode.NETWORK_ERROR,
        ),
      )
    }.onFailure { Log.e(TAG, "Could not emit the terminal event", it) }
  }

  /**
   * With headers restored from disk, one item goes first. If the server rejects the
   * credential, the rest of the batch is released untouched, so an expired token
   * costs one request per wake rather than a burst of `batchSize`.
   */
  private suspend fun dispatchClaimed(
    claimed: List<SyncItemEntity>,
    options: SyncOptions,
    credentials: CredentialPolicy,
  ): List<ItemOutcome> {
    if (credentials != CredentialPolicy.PERSISTED) {
      return dispatchConcurrently(claimed, options, credentials)
    }
    val first = dispatchRecordingOutcome(claimed.first(), options, credentials)
    if (first is ItemOutcome.CredentialRejected) {
      storage.resetInFlight()
      return listOf(first)
    }
    return listOf(first) + dispatchConcurrently(claimed.drop(1), options, credentials)
  }

  private suspend fun dispatchConcurrently(
    entities: List<SyncItemEntity>,
    options: SyncOptions,
    credentials: CredentialPolicy,
  ): List<ItemOutcome> = supervisorScope {
    entities
      .map { entity ->
        async(Dispatchers.IO) { dispatchRecordingOutcome(entity, options, credentials) }
      }
      .awaitAll()
  }

  /**
   * Non-cancellable because the request may reach the server whatever happens to the
   * cycle. Abandoning its outcome would leave the row to be sent a second time.
   */
  private suspend fun dispatchRecordingOutcome(
    entity: SyncItemEntity,
    options: SyncOptions,
    credentials: CredentialPolicy,
  ): ItemOutcome = withContext(NonCancellable) {
    runCatching { dispatchItem(entity, options, credentials) }
      .getOrElse { t -> ItemOutcome.Failed(entity.id, SyncErrorCode.NETWORK_ERROR, t.message ?: "") }
  }

  /**
   * Dispatch a single attempt for [entity]. On a retriable failure with budget
   * remaining, the item is re-queued `PENDING` with a persisted cooldown
   * ([ItemOutcome.Rescheduled]); otherwise it terminally succeeds or fails. The
   * [SyncItemEntity.attempts] counter continues across cycles (never reset).
   */
  private suspend fun dispatchItem(
    entity: SyncItemEntity,
    options: SyncOptions,
    credentials: CredentialPolicy,
  ): ItemOutcome {
    val timeoutMs = (options.requestTimeoutMs?.toLong() ?: DEFAULT_REQUEST_TIMEOUT_MS)
      .coerceAtLeast(MIN_REQUEST_TIMEOUT_MS)
    val policy = options.retryPolicy
    val maxAttempts = policy.maxAttempts.toInt().coerceAtLeast(1)

    // Cross-cycle attempt counter: this attempt is one past whatever prior
    // cycles persisted. Never reset to 0 — the budget must survive cycles.
    val attempt = entity.attempts + 1
    val startedAt = clock()

    // Defensive: a row that already exhausted its budget must not be re-attempted.
    // Terminal rows leave the queue for the dead letters, so this only fires if maxAttempts
    // was lowered via configureSync between cycles.
    if (entity.attempts >= maxAttempts) {
      val message = "Retry budget exhausted"
      deadLetter(
        entity = entity,
        attempts = entity.attempts,
        lastAttemptAt = entity.lastAttemptAt?.takeIf { it > 0L } ?: startedAt,
        code = SyncErrorCode.MAX_ATTEMPTS_EXCEEDED,
        detail = message,
        statusCode = null,
      )
      return ItemOutcome.Failed(entity.id, SyncErrorCode.MAX_ATTEMPTS_EXCEEDED, message)
    }

    return when (val outcome = runOnce(entity, timeoutMs, options.defaultHeaders)) {
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
        ItemOutcome.Succeeded(entity.id, outcome.statusCode)
      }
      is AttemptResult.Failure -> {
        // Read after the attempt: measuring from its start would shorten the
        // cooldown by the request's own duration, down to nothing for a timeout.
        val finishedAt = clock()
        when {
          credentials == CredentialPolicy.PERSISTED &&
            outcome.code == SyncErrorCode.UNAUTHORIZED ->
            holdForCredentialRenewal(entity, attempt, startedAt, finishedAt, outcome, options)
          outcome.retryable && RetryPolicyEvaluator.hasBudget(attempt, policy) ->
            reschedule(entity, attempt, startedAt, finishedAt, outcome, options)
          else -> failTerminally(entity, attempt, startedAt, outcome)
        }
      }
    }
  }

  /** Persist the cooldown; the row is excluded from claims until it elapses. */
  private suspend fun reschedule(
    entity: SyncItemEntity,
    attempt: Int,
    startedAt: Long,
    finishedAt: Long,
    outcome: AttemptResult.Failure,
    options: SyncOptions,
  ): ItemOutcome {
    val nextRetryAt = finishedAt +
      RetryPolicyEvaluator.computeDelayMs(attempt, options.retryPolicy, random)
    storage.markRetry(entity.id, attempt, startedAt, outcome.code, nextRetryAt)
    emitRetrying(entity, attempt, outcome)
    return ItemOutcome.Rescheduled(entity.id)
  }

  /**
   * A restored token was rejected. The item is not at fault, so it stays `PENDING`
   * without spending an attempt. Because attempts do not advance, the backoff would
   * never grow either: [MIN_CREDENTIAL_HOLD_MS] keeps an expired token from turning
   * into an endless stream of rejected requests from background wakes.
   */
  private suspend fun holdForCredentialRenewal(
    entity: SyncItemEntity,
    attempt: Int,
    startedAt: Long,
    finishedAt: Long,
    outcome: AttemptResult.Failure,
    options: SyncOptions,
  ): ItemOutcome {
    val backoffMs = RetryPolicyEvaluator.computeDelayMs(attempt, options.retryPolicy, random)
    val nextRetryAt = finishedAt + maxOf(backoffMs, MIN_CREDENTIAL_HOLD_MS)
    storage.markRetry(entity.id, entity.attempts, startedAt, outcome.code, nextRetryAt)
    emitRetrying(entity, attempt, outcome)
    return ItemOutcome.CredentialRejected(entity.id, nextRetryAt)
  }

  private suspend fun failTerminally(
    entity: SyncItemEntity,
    attempt: Int,
    startedAt: Long,
    outcome: AttemptResult.Failure,
  ): ItemOutcome {
    // A non-retryable failure keeps its real code; only a retriable one can run out
    // of budget.
    val terminalCode = if (outcome.retryable) SyncErrorCode.MAX_ATTEMPTS_EXCEEDED else outcome.code
    deadLetter(entity, attempt, startedAt, terminalCode, outcome.detail, outcome.statusCode)
    return ItemOutcome.Failed(entity.id, terminalCode, outcome.message)
  }

  /**
   * Move [entity] to the dead letters in one transaction, then report `ITEM_FAILED` with the
   * real code and, right after it, `ITEM_DEAD_LETTERED` carrying that code in its metadata.
   */
  @Suppress("LongParameterList")
  private suspend fun deadLetter(
    entity: SyncItemEntity,
    attempts: Int,
    lastAttemptAt: Long,
    code: SyncErrorCode,
    detail: String,
    statusCode: Int?,
  ) {
    storage.moveToDeadLetters(
      entity = entity,
      attempts = attempts,
      lastAttemptAt = lastAttemptAt,
      lastErrorCode = code,
      lastErrorMessage = detail,
      deadLetteredAt = clock(),
    )
    emitter.emit(
      SyncEventEmitter.event(
        type = SyncEventType.ITEM_FAILED,
        itemId = entity.id,
        errorCode = code,
        statusCode = statusCode,
        attempt = attempts,
      ),
    )
    emitter.emit(
      SyncEventEmitter.event(
        type = SyncEventType.ITEM_DEAD_LETTERED,
        itemId = entity.id,
        errorCode = SyncErrorCode.DEAD_LETTERED,
        metadata = mapOf(METADATA_LAST_ERROR_CODE to code.name),
      ),
    )
  }

  private suspend fun emitRetrying(
    entity: SyncItemEntity,
    attempt: Int,
    outcome: AttemptResult.Failure,
  ) {
    emitter.emit(
      SyncEventEmitter.event(
        type = SyncEventType.ITEM_RETRYING,
        itemId = entity.id,
        errorCode = outcome.code,
        statusCode = outcome.statusCode,
        attempt = attempt,
      ),
    )
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
            // The status line only: the response's headers and body stay out of it.
            val statusLine = "HTTP $code ${resp.message}".trim()
            when {
              code in 200..299 -> AttemptResult.Success(code)
              code == 401 || code == 403 -> AttemptResult.Failure(
                code = SyncErrorCode.UNAUTHORIZED,
                message = "HTTP $code",
                statusCode = code,
                retryable = false,
                detail = statusLine,
              )
              RetryPolicyEvaluator.shouldRetryStatus(code, policy) -> AttemptResult.Failure(
                code = SyncErrorCode.SERVER_ERROR,
                message = "HTTP $code",
                statusCode = code,
                retryable = true,
                detail = statusLine,
              )
              code in 400..499 -> AttemptResult.Failure(
                code = SyncErrorCode.SERVER_ERROR,
                message = "HTTP $code",
                statusCode = code,
                retryable = false,
                detail = statusLine,
              )
              else -> AttemptResult.Failure(
                code = SyncErrorCode.SERVER_ERROR,
                message = "HTTP $code",
                statusCode = code,
                retryable = false,
                detail = statusLine,
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
    // Only the type: OkHttp puts header values in these messages.
    Log.e(TAG, "Failed to build OkHttp request for ${entity.id}: ${it.javaClass.simpleName}")
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
      // BASIC, because every level above it logs header values.
      logging.level = HttpLoggingInterceptor.Level.BASIC
      builder.addInterceptor(logging)
    }
    return builder.build()
  }

  /**
   * Rescheduled and held items count as neither success nor failure: they are still
   * queued, and their outcome belongs to a later cycle.
   */
  private fun buildResult(startedAt: Long, outcomes: List<ItemOutcome>): SyncResult {
    val succeeded = outcomes.filterIsInstance<ItemOutcome.Succeeded>().map { it.itemId }
    val failed = outcomes.filterIsInstance<ItemOutcome.Failed>()
    return SyncResult(
      startedAt = startedAt.toDouble(),
      finishedAt = clock().toDouble(),
      successCount = succeeded.size.toDouble(),
      failureCount = failed.size.toDouble(),
      succeededIds = succeeded.toTypedArray(),
      failedIds = failed.map { it.itemId }.toTypedArray(),
      errors = failed.associate { it.itemId to "${it.code.name}: ${it.message}" },
    )
  }

  private sealed interface AttemptResult {
    data class Success(val statusCode: Int) : AttemptResult
    /**
     * @property detail what a dead letter records: the status line, reason phrase included,
     *   or the exception message. Never a header or a body.
     */
    data class Failure(
      val code: SyncErrorCode,
      val message: String,
      val statusCode: Int?,
      val retryable: Boolean,
      val detail: String = message,
    ) : AttemptResult
  }

  private sealed interface ItemOutcome {
    val itemId: String
    data class Succeeded(override val itemId: String, val statusCode: Int) : ItemOutcome

    /** Retriable failure re-queued with a persisted cooldown. */
    data class Rescheduled(override val itemId: String) : ItemOutcome

    /** Restored credential rejected: the item waits, without spending an attempt. */
    data class CredentialRejected(override val itemId: String, val nextRetryAt: Long) : ItemOutcome
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

    /** Floor for the wait after a restored credential is rejected. */
    const val MIN_CREDENTIAL_HOLD_MS: Long = 60_000L

    /** Where `ITEM_DEAD_LETTERED` carries the real failure code. */
    const val METADATA_LAST_ERROR_CODE = "lastErrorCode"

    /**
     * The zeroed result that means "no cycle ran". `flush()` returns it to JS unchanged
     * while paused, while default headers cannot be recovered, and while the
     * network-quality gate holds flushes back. `getLastSyncResult()` returns it when no
     * result is stored, and only there does the facade map it to `undefined`.
     */
    fun emptyResult(): SyncResult = SyncResult(
      startedAt = 0.0,
      finishedAt = 0.0,
      successCount = 0.0,
      failureCount = 0.0,
      succeededIds = emptyArray(),
      failedIds = emptyArray(),
      errors = emptyMap(),
    )
  }
}
