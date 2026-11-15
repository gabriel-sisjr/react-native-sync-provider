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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.io.InterruptedIOException
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
 * - Each attempt is bounded by OkHttp's call timeout: `requestTimeoutMs`, raised to 1 s and
 *   capped at `Int.MAX_VALUE` ms. Running out of it reports `TIMEOUT`, and so does one of the
 *   client's fixed socket timeouts: 10 s to connect, 30 s for a stalled read or write. See
 *   [transportFailureCode].
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
   * Leave no path without a terminal event and no row stranded. Whatever ended the cycle, the
   * supervisor scope has already waited for its children and this cycle still holds the
   * mutex, so a row left `IN_FLIGHT` is one whose dispatch never started, or whose outcome
   * could not be written: either goes back to `PENDING`, as recovery would put it.
   *
   * A cancelled cycle, a worker WorkManager stopped say, did not fail: its `SYNC_FAILED`
   * carries no code. Any other cause reports its code, or `NETWORK_ERROR` when it has none.
   */
  private suspend fun abortCycle(cause: Throwable, terminalEventSent: Boolean) {
    runCatching { storage.resetInFlight() }
      .onFailure { Log.e(TAG, "Could not release unattempted rows", it) }
    if (terminalEventSent) return
    val code = when (cause) {
      is CancellationException -> null
      is SyncException -> cause.code
      else -> SyncErrorCode.NETWORK_ERROR
    }
    runCatching {
      emitter.emit(SyncEventEmitter.event(type = SyncEventType.SYNC_FAILED, errorCode = code))
    }.onFailure { Log.e(TAG, "Could not emit the terminal event", it) }
  }

  /**
   * With headers restored from disk, one item goes first. If the server rejects the
   * credential, the rest of the batch is released untouched, so an expired token
   * costs one request per wake rather than a burst of `batchSize`. An item purged before
   * its turn is skipped, and the next one goes first in its place.
   */
  private suspend fun dispatchClaimed(
    claimed: List<SyncItemEntity>,
    options: SyncOptions,
    credentials: CredentialPolicy,
  ): List<ItemOutcome> {
    if (credentials != CredentialPolicy.PERSISTED) {
      return dispatchConcurrently(claimed, options, credentials)
    }
    val leading = mutableListOf<ItemOutcome>()
    for (entity in claimed) {
      // Dispatches are not cancellable once started: a cancelled cycle starts no more.
      currentCoroutineContext().ensureActive()
      val outcome = dispatchRecordingOutcome(entity, options, credentials)
      leading += outcome
      if (outcome is ItemOutcome.CredentialRejected) {
        storage.resetInFlight()
        return leading
      }
      if (outcome !is ItemOutcome.Gone) break
    }
    return leading + dispatchConcurrently(claimed.drop(leading.size), options, credentials)
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
   *
   * A cancellation or an [Error] raised in here is no outcome of this item: it is rethrown,
   * it ends the cycle, and [abortCycle] puts the row back to `PENDING`. Any other exception
   * ends the item, see [failUnexpectedly].
   */
  private suspend fun dispatchRecordingOutcome(
    entity: SyncItemEntity,
    options: SyncOptions,
    credentials: CredentialPolicy,
  ): ItemOutcome = withContext(NonCancellable) {
    try {
      dispatchItem(entity, options, credentials)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      failUnexpectedly(entity, e)
    }
  }

  /**
   * An exception no attempt result accounts for, such as a failed storage read. A row still
   * `IN_FLIGHT` has no outcome yet: it ends as a permanent `INVALID_PAYLOAD` failure, a dead
   * letter, as iOS ends a failure it cannot classify. Left `IN_FLIGHT`, it would come back
   * after every recovery to fail the same way, never sent and never failed. A row whose outcome
   * was already written, or that a purge removed, is left as it is and not counted.
   *
   * When that cannot be written either, the row stays `IN_FLIGHT` for the next recovery.
   */
  private suspend fun failUnexpectedly(entity: SyncItemEntity, cause: Exception): ItemOutcome {
    val detail = cause.message ?: cause.javaClass.simpleName
    val failed = ItemOutcome.Failed(entity.id, SyncErrorCode.INVALID_PAYLOAD, detail)
    val moved = try {
      storage.isInFlight(entity.id) &&
        deadLetter(
          entity = entity,
          attempts = entity.attempts,
          lastAttemptAt = entity.lastAttemptAt?.takeIf { it > 0L } ?: clock(),
          code = SyncErrorCode.INVALID_PAYLOAD,
          detail = detail,
          statusCode = null,
          requestSent = false,
        )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Only the type: a message can carry what the request held.
      Log.e(TAG, "Could not record the failure of ${entity.id}: ${e.javaClass.simpleName}")
      return failed
    }
    return if (moved) failed else ItemOutcome.Gone(entity.id)
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
      .coerceIn(MIN_REQUEST_TIMEOUT_MS, MAX_REQUEST_TIMEOUT_MS)
    val policy = options.retryPolicy
    val maxAttempts = policy.maxAttempts.toInt().coerceAtLeast(1)

    // A purge since the claim, a logout say, leaves nothing to send: no request and no event
    // of its own. Once the request is out, its outcome is reported even if a purge follows.
    if (!storage.isQueued(entity.id)) return ItemOutcome.Gone(entity.id)

    // Cross-cycle attempt counter: this attempt is one past whatever prior
    // cycles persisted. Never reset to 0 — the budget must survive cycles.
    val attempt = entity.attempts + 1
    val startedAt = clock()

    // Defensive: a row that already exhausted its budget must not be re-attempted.
    // Terminal rows leave the queue for the dead letters, so this only fires if maxAttempts
    // was lowered via configureSync between cycles.
    if (entity.attempts >= maxAttempts) {
      val message = "Retry budget exhausted"
      val moved = deadLetter(
        entity = entity,
        attempts = entity.attempts,
        lastAttemptAt = entity.lastAttemptAt?.takeIf { it > 0L } ?: startedAt,
        code = SyncErrorCode.MAX_ATTEMPTS_EXCEEDED,
        detail = message,
        statusCode = null,
        requestSent = false,
      )
      // Purged since the check above: no request went out, so there is nothing to end.
      if (!moved) return ItemOutcome.Gone(entity.id)
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
    val nextRetryAt = RetryPolicyEvaluator.deadline(
      from = finishedAt,
      delayMs = RetryPolicyEvaluator.computeDelayMs(attempt, options.retryPolicy, random),
    )
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
    val nextRetryAt = RetryPolicyEvaluator.deadline(
      from = finishedAt,
      delayMs = maxOf(backoffMs, MIN_CREDENTIAL_HOLD_MS),
    )
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
    deadLetter(
      entity = entity,
      attempts = attempt,
      lastAttemptAt = startedAt,
      code = terminalCode,
      detail = outcome.detail,
      statusCode = outcome.statusCode,
      requestSent = true,
    )
    return ItemOutcome.Failed(entity.id, terminalCode, outcome.message)
  }

  /**
   * Move [entity] to the dead letters in one transaction, then report `ITEM_FAILED` with the
   * real code and, right after it, `ITEM_DEAD_LETTERED` carrying that code in its metadata.
   *
   * A row that `clearQueue()` or `removeItem(id)` purged meanwhile is not moved: nothing is
   * written and no `ITEM_DEAD_LETTERED` follows. When its request went out ([requestSent]),
   * typically purged while that request was in flight, `ITEM_FAILED` still reports how it
   * ended, as `ITEM_SUCCEEDED` and `ITEM_RETRYING` do for a purged row, and the cycle still
   * counts it as failed. When none did, nothing is reported. The same holds on iOS.
   *
   * @return true when the row was moved.
   */
  @Suppress("LongParameterList")
  private suspend fun deadLetter(
    entity: SyncItemEntity,
    attempts: Int,
    lastAttemptAt: Long,
    code: SyncErrorCode,
    detail: String,
    statusCode: Int?,
    requestSent: Boolean,
  ): Boolean {
    val moved = storage.moveToDeadLetters(
      entity = entity,
      attempts = attempts,
      lastAttemptAt = lastAttemptAt,
      lastErrorCode = code,
      lastErrorMessage = detail,
      deadLetteredAt = clock(),
    )
    if (!moved && !requestSent) return false
    emitter.emit(
      SyncEventEmitter.event(
        type = SyncEventType.ITEM_FAILED,
        itemId = entity.id,
        errorCode = code,
        statusCode = statusCode,
        attempt = attempts,
      ),
    )
    if (!moved) return false
    emitter.emit(
      SyncEventEmitter.event(
        type = SyncEventType.ITEM_DEAD_LETTERED,
        itemId = entity.id,
        errorCode = SyncErrorCode.DEAD_LETTERED,
        metadata = mapOf(METADATA_LAST_ERROR_CODE to code.name),
      ),
    )
    return true
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

    // The call timeout bounds the whole call: connecting, sending the request and reading the
    // response. It is the attempt's only time limit besides the client's socket timeouts.
    val call = client.newBuilder()
      .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
      .build()
      .newCall(request)

    val startedAt = System.nanoTime()
    try {
      call.execute().use { response -> responseOutcome(response) }
    } catch (e: IOException) {
      val ranOutOfTime = System.nanoTime() - startedAt >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)
      transportFailure(e, ranOutOfTime)
    } catch (e: CancellationException) {
      // Not the attempt's outcome. An Error is not caught either.
      throw e
    } catch (e: Exception) {
      // Anything but an I/O failure is permanent, as on iOS: a retry would fail the same way,
      // and NETWORK_ERROR stays a code that is always retried.
      runCatching { call.cancel() }
      AttemptResult.Failure(
        code = SyncErrorCode.INVALID_PAYLOAD,
        message = e.message ?: e.javaClass.simpleName,
        statusCode = null,
        retryable = false,
      )
    }
  }

  /** The outcome of an attempt the server answered, from its status line. */
  private fun responseOutcome(resp: Response): AttemptResult {
    val code = resp.code
    // Read for each response, so a configureSync made while the request was out applies to it.
    val policy = getOptions().retryPolicy
    // The status line only: the response's headers and body stay out of it.
    val statusLine = "HTTP $code ${resp.message}".trim()
    return when {
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

  /** An attempt that failed before any response. Retried, a timeout and a network error alike. */
  private fun transportFailure(failure: IOException, ranOutOfTime: Boolean): AttemptResult.Failure {
    val code = transportFailureCode(failure, ranOutOfTime)
    val fallback = if (code == SyncErrorCode.TIMEOUT) "Request timed out" else "Network error"
    return AttemptResult.Failure(
      code = code,
      message = failure.message ?: fallback,
      statusCode = null,
      retryable = true,
    )
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
   * queued, and their outcome belongs to a later cycle. Nor do items purged before their
   * dispatch started: they were never sent.
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

    /** Purged before its dispatch started: never sent, and nothing reported for it. */
    data class Gone(override val itemId: String) : ItemOutcome
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

    /**
     * The longest call timeout OkHttp takes, about 24.8 days. A longer one, `Infinity` included,
     * makes it throw "timeout too large." before the request starts.
     */
    private const val MAX_REQUEST_TIMEOUT_MS: Long = Int.MAX_VALUE.toLong()
    private const val CONNECT_TIMEOUT_MS: Long = 10_000L
    private const val READ_TIMEOUT_MS: Long = 30_000L
    private const val WRITE_TIMEOUT_MS: Long = 30_000L

    /** Floor for the wait after a restored credential is rejected. */
    const val MIN_CREDENTIAL_HOLD_MS: Long = 60_000L

    /** Where `ITEM_DEAD_LETTERED` carries the real failure code. */
    const val METADATA_LAST_ERROR_CODE = "lastErrorCode"

    /**
     * The code of an attempt that failed with [failure] before any response. Both are retried.
     * - [SyncErrorCode.TIMEOUT] when one of the client's socket timeouts fired, which OkHttp
     *   raises as a `SocketTimeoutException`: 10 s to connect, 30 s for a stalled read or write.
     * - [SyncErrorCode.TIMEOUT] when the call timeout, `requestTimeoutMs`, ran out. OkHttp 4.12.0
     *   then ends the call with an `InterruptedIOException("timeout")`, whatever failure it was
     *   in. A thread interrupt raises the same class ("interrupted"), so the class does not
     *   decide alone: [ranOutOfTime], whether the call had run for its whole timeout, does.
     *   OkHttp's watchdog also cancels the call, but the call can see the timeout before that
     *   `cancel()` runs, so `isCanceled()` could miss it.
     * - [SyncErrorCode.NETWORK_ERROR] for any other I/O failure: DNS, a refused or dropped
     *   connection, TLS, or an interrupt before the deadline.
     */
    internal fun transportFailureCode(failure: IOException, ranOutOfTime: Boolean): SyncErrorCode =
      when {
        failure is SocketTimeoutException -> SyncErrorCode.TIMEOUT
        failure is InterruptedIOException && ranOutOfTime -> SyncErrorCode.TIMEOUT
        else -> SyncErrorCode.NETWORK_ERROR
      }

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
