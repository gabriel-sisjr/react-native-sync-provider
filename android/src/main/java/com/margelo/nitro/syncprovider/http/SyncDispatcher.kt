package com.margelo.nitro.syncprovider.http

import android.util.Log
import com.margelo.nitro.syncprovider.AfterDispatchContext
import com.margelo.nitro.syncprovider.BeforeDispatchAction
import com.margelo.nitro.syncprovider.BeforeDispatchContext
import com.margelo.nitro.syncprovider.DispatchOutcome
import com.margelo.nitro.syncprovider.HeaderResolverContext
import com.margelo.nitro.syncprovider.HttpMethod
import com.margelo.nitro.syncprovider.NetworkQuality
import com.margelo.nitro.syncprovider.RetryAction
import com.margelo.nitro.syncprovider.RetryDecision
import com.margelo.nitro.syncprovider.RetryDeciderContext
import com.margelo.nitro.syncprovider.RetryPolicy
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.SyncOptions
import com.margelo.nitro.syncprovider.SyncResult
import com.margelo.nitro.syncprovider.callbacks.CallbackResult
import com.margelo.nitro.syncprovider.callbacks.JsCallbacks
import com.margelo.nitro.syncprovider.callbacks.SkipReason
import com.margelo.nitro.syncprovider.config.CallbackTimeout
import com.margelo.nitro.syncprovider.connectivity.meetsMinimum
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.error.SyncException
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.retry.RetryPolicyEvaluator
import com.margelo.nitro.syncprovider.storage.AbortedClaim
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.util.JsonMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
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
import java.util.concurrent.ConcurrentHashMap
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
 * - With a retry decider installed by JS, a failure the policy would retry is first put to it
 *   (see [retryOrAsk]). It never retries more than the policy does: it answers RETRY, with its
 *   own delay or the policy's, DEAD_LETTER or DROP, and without an answer the policy applies. It
 *   is never asked about a success, a failure the policy would not retry, a spent budget or a
 *   credential hold.
 * - With a header resolver or `beforeDispatch` installed by JS, each item asks them before its
 *   request goes out (see [intercept]). One that fails, or a SKIP, defers the item without
 *   spending an attempt (see [deferForInterceptor]). `afterDispatch` is told how each request
 *   that went out ended, and never awaited (see [notifyAfterDispatch]). With none installed,
 *   nothing is asked and each request carries the static headers, as before they existed.
 * - [onCycleFinished] fires after every cycle, whatever its outcome, so the next
 *   wake is always re-armed. It is told how the cycle ended ([CycleEnding]): the wake after a
 *   cycle that failed is held back, so a failure that persists cannot run cycles back to back.
 *
 * Concurrency model:
 * - [flushMutex] serializes cycles: a concurrent [flush] waits for the running one
 *   and then runs its own. A JS callback is awaited under it, so a callback that waits for
 *   `flush()` holds the cycle until `callbackTimeoutMs`, then its fallback applies.
 * - Within a cycle, items are dispatched via [supervisorScope]: an item whose attempt fails
 *   records that outcome and leaves its siblings alone. Each item asks JS on its own, so up to
 *   `batchSize` callbacks can be waited for at once.
 * - A dispatch that started finishes even if the cycle is cancelled, and records its outcome,
 *   unless something raised around its attempt ends the cycle: a storage failure, a
 *   cancellation or an [Error] (see [dispatchRecordingOutcome]). Dispatches already started
 *   still finish then, and none starts after. A wait for JS is the exception: the cycle's end
 *   cuts it short. The retry decider's fallback then applies, while an item waiting on an
 *   interceptor sends nothing and leaves its row to the settle. Unless a cancellation ended the
 *   cycle, each row it claimed and left without a recorded outcome is then settled with what its
 *   attempt established (see [settlements]): an answer the server gave is applied as the attempt
 *   would have applied it, a `2xx` included, and as the retry decider decided it; a request that
 *   went out with no answer classified counts the attempt, with its cooldown. A row never sent,
 *   and every row after a cancellation, comes back as it was. A settle never asks JS.
 * - Each attempt is bounded by OkHttp's call timeout: `requestTimeoutMs`, raised to 1 s and
 *   capped at `Int.MAX_VALUE` ms. Running out of it reports `TIMEOUT`, and so does one of the
 *   client's fixed socket timeouts: 10 s to connect, 30 s for a stalled read or write. See
 *   [transportFailureCode]. The timeout cannot interrupt a DNS lookup in progress: an attempt
 *   blocked in one can run past `requestTimeoutMs`, and then reports `TIMEOUT`.
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
 * @param onCycleFinished called after every cycle, with how it ended. Its first argument, when
 *   present, is the earliest instant the next wake may fire. Failures are logged and never
 *   reject a cycle that already completed.
 * @param httpClient the one OkHttp client, built once, on the first cycle with items to send,
 *   before any attempt: a client that cannot be built fails that cycle, not its items. Each
 *   attempt derives its own call from it, with that cycle's `requestTimeoutMs`.
 * @param callbacks what JS installed, read when each item needs it, and the gateway that waits
 *   for its answers, for at most the cycle's `callbackTimeoutMs`.
 */
@Suppress("LongParameterList")
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
  private val onCycleFinished: suspend (notBefore: Long?, ending: CycleEnding) -> Unit =
    { _, _ -> },
  private val httpClient: Lazy<OkHttpClient> = lazy { newClient(verboseLogging()) },
  private val callbacks: JsCallbacks = JsCallbacks(),
) {
  private val flushMutex = Mutex()
  private val syncing = AtomicBoolean(false)

  /**
   * The rows whose request the running cycle sent, each with what its attempt established (see
   * [SentRequest]). Its dispatches write it and [abortCycle] reads it. One cycle runs at a time,
   * under [flushMutex], and each starts by clearing it.
   */
  private val requestsSent = ConcurrentHashMap<String, SentRequest>()

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
    var ending = CycleEnding.COMPLETED
    var terminalEventSent = false
    var claimed: List<SyncItemEntity> = emptyList()
    requestsSent.clear()
    try {
      emitter.emit(SyncEventEmitter.event(SyncEventType.SYNC_STARTED))

      val batchSize = (options.batchSize?.toInt() ?: DEFAULT_BATCH_SIZE).coerceAtLeast(1)
      // Not cancellable: a cancellation that lands once the claim has committed would discard
      // the rows it took, and only recovery would release them. It surfaces right after, at the
      // first check, and the rows are then the settle's to give back.
      claimed = withContext(NonCancellable) { storage.claimPending(batchSize, startedAt) }
      val outcomes = if (claimed.isEmpty()) {
        emptyList()
      } else {
        // Before any attempt: a client that cannot be built aborts the cycle, item-free.
        httpClient.value
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
      // From the cycle's own job, read here: inside the block below it is always active.
      ending = if (t is CancellationException && !currentCoroutineContext().isActive) {
        CycleEnding.CANCELLED
      } else {
        CycleEnding.FAILED
      }
      val held = withContext(NonCancellable) {
        abortCycle(t, terminalEventSent, claimed, options.retryPolicy)
      }
      notBefore = listOfNotNull(notBefore, held).maxOrNull()
      throw t
    } finally {
      syncing.set(false)
      withContext(NonCancellable) {
        runCatching { onCycleFinished(notBefore, ending) }
          .onFailure { Log.w(TAG, "Could not arm the next retry wake", it) }
      }
    }
  }

  /**
   * Leave no path without a terminal event and no row stranded. Whatever ended the cycle, the
   * supervisor scope has already waited for its children and this cycle still holds the
   * mutex, so a row it [claimed] that is still `IN_FLIGHT` has no outcome recorded: its dispatch
   * never started, or failed around its attempt before its outcome was written. Each such row is
   * settled in one transaction (see [settlements]) and then reported as its own write would have
   * reported it, before the cycle's `SYNC_FAILED`. A row `IN_FLIGHT` that this cycle did not
   * claim, such as another process's claim, is left to its owner. When even that write fails,
   * the rows wait for recovery.
   *
   * A cancelled cycle, a worker WorkManager stopped say, did not fail: its `SYNC_FAILED`
   * carries no code. Any other cause reports its code, or `NETWORK_ERROR` when it has none.
   *
   * @return the latest credential hold the cycle's answers set, whether their own write or the
   *   settle recorded it, for the next wake to wait for; null after a cancellation.
   */
  private suspend fun abortCycle(
    cause: Throwable,
    terminalEventSent: Boolean,
    claimed: List<SyncItemEntity>,
    policy: RetryPolicy,
  ): Long? {
    val code = when (cause) {
      is CancellationException -> null
      is SyncException -> cause.code
      else -> SyncErrorCode.NETWORK_ERROR
    }
    val settlements = settlements(claimed, cause, code, policy)
    val applied = runCatching {
      storage.settleAbortedClaims(settlements.map { it.claim }).mapTo(HashSet()) { it.id }
    }.onFailure { Log.e(TAG, "Could not release the cycle's rows: ${it.javaClass.simpleName}") }
      .getOrDefault(emptySet())
    // The events each write gives, after the writes are in.
    settlements.filter { it.claim.id in applied }.forEach { settlement ->
      runCatching { emitSettled(settlement) }
        .onFailure { Log.e(TAG, "Could not report a released row: ${it.javaClass.simpleName}") }
    }
    if (!terminalEventSent) {
      runCatching {
        emitter.emit(SyncEventEmitter.event(type = SyncEventType.SYNC_FAILED, errorCode = code))
      }.onFailure { Log.e(TAG, "Could not emit the terminal event", it) }
    }
    return settlements.mapNotNull { it.heldUntil }.maxOrNull()
  }

  /**
   * What each row [claimed] by an aborted cycle becomes. A cancellation is no outcome: every row
   * comes back as it was. After any other cause:
   * - a row whose answer was classified gets what its dispatch decided for that answer, exactly
   *   as its own write would have written it (see [SentRequest.outcome]): removed as delivered
   *   after a `2xx`; back to `PENDING` with its cooldown and real code after a retryable answer;
   *   held, without spending the attempt, after a rejected restored credential; a dead letter
   *   with its real code after a permanent answer, or with `MAX_ATTEMPTS_EXCEEDED` once a
   *   retryable one spent the budget;
   * - a row whose request went out with no answer classified, an [Error] raised during the send
   *   say, counts that attempt as a retryable failure would, with [code]: back to `PENDING` with
   *   the retry policy's cooldown, or a dead letter with `MAX_ATTEMPTS_EXCEEDED` once the budget
   *   is spent. Released at once and uncounted instead, a row whose send kept failing that way
   *   would be sent again on every wake;
   * - a row whose request never went out comes back as it was.
   */
  private fun settlements(
    claimed: List<SyncItemEntity>,
    cause: Throwable,
    code: SyncErrorCode?,
    policy: RetryPolicy,
  ): List<Settlement> {
    val now = clock()
    return claimed.map { entity ->
      val sent = requestsSent[entity.id]
      if (code == null || sent == null) return@map Settlement(AbortedClaim.Unsent(entity.id))
      sent.outcome?.let { return@map it }
      val attempt = entity.attempts + 1
      val claim = if (RetryPolicyEvaluator.hasBudget(attempt, policy)) {
        AbortedClaim.Retry(
          id = entity.id,
          attempts = attempt,
          lastAttemptAt = sent.startedAt,
          lastErrorCode = code,
          nextRetryAt = RetryPolicyEvaluator.deadline(
            from = now,
            delayMs = RetryPolicyEvaluator.computeDelayMs(attempt, policy, random),
          ),
        )
      } else {
        AbortedClaim.Exhausted(
          entity = entity,
          attempts = attempt,
          lastAttemptAt = sent.startedAt,
          lastErrorCode = SyncErrorCode.MAX_ATTEMPTS_EXCEEDED,
          lastErrorMessage = cause.message ?: cause.javaClass.simpleName,
          deadLetteredAt = now,
        )
      }
      Settlement(claim, attempt = attempt)
    }
  }

  /** The item events of a write [abortCycle] applied: the ones its own write would have given. */
  private suspend fun emitSettled(settlement: Settlement) {
    when (val claim = settlement.claim) {
      is AbortedClaim.Delivered -> emitter.emit(
        SyncEventEmitter.event(
          type = SyncEventType.ITEM_SUCCEEDED,
          itemId = claim.id,
          statusCode = settlement.statusCode,
          attempt = settlement.attempt,
        ),
      )
      // The drop writes before it reports, so the dispatch never reported a drop the settle writes.
      is AbortedClaim.Dropped -> emitter.emit(
        SyncEventEmitter.event(
          type = SyncEventType.ITEM_FAILED,
          itemId = claim.id,
          errorCode = SyncErrorCode.RETRY_DECIDER_REJECTED,
          statusCode = settlement.statusCode,
          attempt = settlement.attempt,
        ),
      )
      is AbortedClaim.Retry -> emitter.emit(
        SyncEventEmitter.event(
          type = SyncEventType.ITEM_RETRYING,
          itemId = claim.id,
          errorCode = claim.lastErrorCode,
          statusCode = settlement.statusCode,
          attempt = settlement.attempt,
        ),
      )
      is AbortedClaim.Exhausted -> {
        emitter.emit(
          SyncEventEmitter.event(
            type = SyncEventType.ITEM_FAILED,
            itemId = claim.id,
            errorCode = claim.lastErrorCode,
            statusCode = settlement.statusCode,
            attempt = settlement.attempt,
          ),
        )
        emitter.emit(
          SyncEventEmitter.event(
            type = SyncEventType.ITEM_DEAD_LETTERED,
            itemId = claim.id,
            errorCode = SyncErrorCode.DEAD_LETTERED,
            metadata = mapOf(METADATA_LAST_ERROR_CODE to claim.lastErrorCode.name),
          ),
        )
      }
      is AbortedClaim.Unsent -> Unit
    }
  }

  /**
   * Remember what [settlement] writes for [id]'s answer, before that write: if it fails, the
   * cycle's settle applies it instead. A row whose request never went out has nothing to remember.
   */
  private fun remember(id: String, settlement: Settlement) {
    requestsSent.computeIfPresent(id) { _, sent -> sent.copy(outcome = settlement) }
  }

  /**
   * With headers restored from disk, one item goes first. If the server rejects the
   * credential, the rest of the batch is released untouched, so an expired token
   * costs one request per wake rather than a burst of `batchSize`. An item purged before
   * its turn is skipped, and the next one goes first in its place. So is an item a JS
   * interceptor deferred: it sent nothing, so it tested no credential.
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
        // Through the settle, so that only this cycle's claims are released.
        storage.settleAbortedClaims(claimed.drop(leading.size).map { AbortedClaim.Unsent(it.id) })
        return leading
      }
      if (outcome !is ItemOutcome.Gone && outcome !is ItemOutcome.Deferred) break
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
   * Only the attempt's own failure is an outcome of the item (see [runOnce]). Anything raised
   * around it, a storage read or write, an event, a cancellation or an [Error], is rethrown:
   * it ends the cycle, as a failed store write does on iOS, and [abortCycle] settles the cycle's
   * rows that have no recorded outcome. A failed local write is never the item's failure: an
   * answer the server gave is applied as this dispatch would have applied it, so after a `2xx`
   * the row is delivered.
   *
   * A wait for JS is the one thing a cancellation reaches. The dispatch gets a stop signal made
   * before `NonCancellable`, a child of the caller's job: when the cycle is cancelled, or its
   * scope ends after a sibling failed, the wait ends at once. A wait for the retry decider then
   * gets its fallback, and the dispatch finishes as usual. A wait for an interceptor, before the
   * request, ends the dispatch with a [CancellationException] instead, nothing sent, even when JS
   * had answered: the row has no outcome, and the settle gives it back as it was. The signal
   * must be completed in all cases, or the caller's job would never complete.
   */
  private suspend fun dispatchRecordingOutcome(
    entity: SyncItemEntity,
    options: SyncOptions,
    credentials: CredentialPolicy,
  ): ItemOutcome {
    val stop = Job(currentCoroutineContext()[Job])
    try {
      return withContext(NonCancellable) { dispatchItem(entity, options, credentials, stop) }
    } finally {
      stop.complete()
    }
  }

  /**
   * Dispatch a single attempt for [entity]. On a retriable failure with budget
   * remaining, the item is re-queued `PENDING` with a persisted cooldown
   * ([ItemOutcome.Rescheduled]), unless the retry decider decides otherwise; otherwise it
   * terminally succeeds or fails. The [SyncItemEntity.attempts] counter continues across cycles
   * (never reset).
   *
   * In order: the purge check, the spent-budget guard, the request built with the static
   * headers, the JS interceptors installed (see [intercept]), the request, its outcome written
   * and reported (see [recordOutcome]), then `afterDispatch` (see [notifyAfterDispatch]). An item
   * that cannot be sent, a spent budget or a request OkHttp refuses, never reaches an
   * interceptor, and an interceptor can defer the item ([ItemOutcome.Deferred]) without spending
   * the attempt. With no interceptor installed, nothing waits between the guard and the request.
   *
   * @param stop cancelled when the cycle stops; it ends a wait for JS at once.
   */
  private suspend fun dispatchItem(
    entity: SyncItemEntity,
    options: SyncOptions,
    credentials: CredentialPolicy,
    stop: Job,
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

    // Built before any interceptor is asked: a request OkHttp refuses fails as it always has.
    val built = buildRequest(entity, options.defaultHeaders)
      ?: return recordOutcome(
        entity, attempt, startedAt, unbuildable(entity), credentials, options, stop,
      )
    val ready = when (val interception = intercept(entity, attempt, built, options, stop)) {
      is Interception.Send -> interception
      is Interception.Deferred ->
        return deferForInterceptor(entity, attempt, policy, interception.skipped)
      Interception.Gone -> return ItemOutcome.Gone(entity.id)
    }
    // After a wait for JS, the attempt starts when its request is ready.
    val attemptStartedAt = if (ready.awaited) clock() else startedAt
    val sent = runOnce(entity.id, ready.request, timeoutMs, attemptStartedAt)
    val outcome = recordOutcome(
      entity, attempt, attemptStartedAt, sent.result, credentials, options, stop,
    )
    sent.durationMs?.let { notifyAfterDispatch(entity, attempt, sent.result, outcome, it, options) }
    return outcome
  }

  /**
   * Write and report what [entity]'s attempt [result] comes to, as described at [dispatchItem].
   *
   * @param startedAt epoch ms the attempt started, its `lastAttemptAt`.
   */
  @Suppress("LongParameterList")
  private suspend fun recordOutcome(
    entity: SyncItemEntity,
    attempt: Int,
    startedAt: Long,
    result: AttemptResult,
    credentials: CredentialPolicy,
    options: SyncOptions,
    stop: Job,
  ): ItemOutcome {
    val policy = options.retryPolicy
    return when (val outcome = result) {
      is AttemptResult.Success -> {
        remember(
          entity.id,
          Settlement(AbortedClaim.Delivered(entity.id), attempt, outcome.statusCode),
        )
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
            retryOrAsk(entity, attempt, startedAt, finishedAt, outcome, options, stop)
          else -> failTerminally(entity, attempt, startedAt, outcome)
        }
      }
    }
  }

  /** What an attempt whose request cannot be built comes to: a permanent failure, nothing sent. */
  private fun unbuildable(entity: SyncItemEntity): AttemptResult.Failure = AttemptResult.Failure(
    code = SyncErrorCode.INVALID_PAYLOAD,
    message = "Failed to build request for item ${entity.id}",
    statusCode = null,
    retryable = false,
  )

  /**
   * Ask the JS interceptors installed about the [attempt] that [built] is about to make: the
   * header resolver, then `beforeDispatch`, each read when the item needs it. With neither
   * installed, nothing is asked or read, and [built] goes out as it is.
   *
   * - The resolver's headers are merged between `defaultHeaders` and the item's own headers: one
   *   entry per name, whatever its case, with the spelling and value of the layer that wins.
   *   Anything but headers OkHttp sends as they are defers the item, and `beforeDispatch` is then
   *   not asked.
   * - `beforeDispatch` sees the headers of the request built that way, the one that goes out. The
   *   `Content-Type` OkHttp takes from the body is not among them: the stored content type has
   *   its own field. A `Content-Type` that a layer sets is. PROCEED sends the request unchanged;
   *   SKIP, or anything but an answer, defers the item.
   * - After each wait, the row must still be queued: a row purged meanwhile is not sent.
   * - A cycle that stopped during a wait, or after its answer, sends nothing: this throws a
   *   [CancellationException], and the settle gives the row back as it was.
   *
   * None of it changes the credential policy, which depends on `defaultHeaders` alone.
   */
  private suspend fun intercept(
    entity: SyncItemEntity,
    attempt: Int,
    built: Request,
    options: SyncOptions,
    stop: Job,
  ): Interception {
    val resolver = callbacks.registry.headerResolver.installed
    var hook = callbacks.registry.beforeDispatch.installed
    // An orphan, left by a hybrid torn down, counts: its request ends at once without calling
    // JS, and the item is deferred, never sent with the static headers alone.
    if (resolver == null && hook == null) return Interception.Send(built, awaited = false)
    val timeoutMs = CallbackTimeout.ms(options)
    val method = methodOf(entity)
    val metadata = JsonMap.decode(entity.metadata)
    var request = built
    if (resolver != null) {
      val context = HeaderResolverContext(
        itemId = entity.id,
        attempt = attempt.toDouble(),
        url = entity.url,
        method = method,
        metadata = metadata,
      )
      val answer = callbacks.resolveHeaders(resolver, context, timeoutMs, stop)
      val headers = when (answer) {
        is CallbackResult.Answered -> answer.value
        is CallbackResult.Skipped -> return notAnswered(entity, answer.reason, stop)
      }
      ensureNotStopped(entity, stop)
      if (!storage.isQueued(entity.id)) return Interception.Gone
      if (headers.isNotEmpty()) {
        // In name order, as on iOS: of two names that differ only by case, the one sorted last
        // wins, whatever the order of the map. Never null: OkHttp took the rest of the
        // request, and the gateway checked these headers.
        request = buildRequest(entity, options.defaultHeaders, headers.toSortedMap())
          ?: return Interception.Deferred(skipped = false)
      }
      // Read again after the wait: each install is read when the item needs it.
      hook = callbacks.registry.beforeDispatch.installed
    }
    if (hook != null) {
      val context = BeforeDispatchContext(
        itemId = entity.id,
        attempt = attempt.toDouble(),
        url = entity.url,
        method = method,
        headers = headersOf(request),
        contentType = entity.contentType,
        metadata = metadata,
      )
      val action = when (val answer = callbacks.beforeDispatch(hook, context, timeoutMs, stop)) {
        is CallbackResult.Answered -> answer.value
        is CallbackResult.Skipped -> return notAnswered(entity, answer.reason, stop)
      }
      ensureNotStopped(entity, stop)
      if (action == BeforeDispatchAction.SKIP) return Interception.Deferred(skipped = true)
      if (!storage.isQueued(entity.id)) return Interception.Gone
    }
    // The last check before the request: a stop during the one above sends nothing either.
    ensureNotStopped(entity, stop)
    return Interception.Send(request, awaited = true)
  }

  /** An interceptor gave nothing to use: the item is deferred, unless the cycle stopped. */
  private fun notAnswered(entity: SyncItemEntity, reason: SkipReason, stop: Job): Interception {
    // The gateway logged the stop.
    if (reason == SkipReason.STOPPED) throw CancellationException(stoppedBefore(entity))
    ensureNotStopped(entity, stop)
    return Interception.Deferred(skipped = false)
  }

  /** A stopped cycle sends nothing more: throws once [stop] is cancelled. */
  private fun ensureNotStopped(entity: SyncItemEntity, stop: Job) {
    if (!stop.isCancelled) return
    val message = stoppedBefore(entity)
    callbacks.log.log(Log.INFO, "$message; it is not sent in this cycle")
    throw CancellationException(message)
  }

  private fun stoppedBefore(entity: SyncItemEntity): String =
    "The cycle stopped before the request of item ${entity.id} went out"

  /**
   * A JS interceptor kept [entity]'s request from going out: the header resolver or
   * `beforeDispatch` gave nothing to use, or `beforeDispatch` answered SKIP ([skipped]). No
   * attempt was made, so none is spent: the row goes back to `PENDING` with its `attempts` and
   * `lastAttemptAt` as they were, [SyncErrorCode.INTERCEPTOR_FAILED] as its code, and a cooldown
   * of the would-be [attempt]'s backoff or [MIN_INTERCEPTOR_DEFER_MS], whichever is longer.
   * `ITEM_RETRYING` then reports it with that code, no status, and the would-be attempt.
   *
   * Only this item waits: it counts in neither total, it sets no hold, and the cycle goes on. A
   * row purged during the wait stays purged, with no event. When the write fails, nothing was
   * remembered for the row, so the cycle's settle gives it back as it was.
   */
  private suspend fun deferForInterceptor(
    entity: SyncItemEntity,
    attempt: Int,
    policy: RetryPolicy,
    skipped: Boolean,
  ): ItemOutcome {
    val backoffMs = RetryPolicyEvaluator.computeDelayMs(attempt, policy, random)
    val nextRetryAt = RetryPolicyEvaluator.deadline(
      from = clock(),
      delayMs = maxOf(backoffMs, MIN_INTERCEPTOR_DEFER_MS),
    )
    val deferred = storage.markDeferred(entity.id, SyncErrorCode.INTERCEPTOR_FAILED, nextRetryAt)
    if (skipped) {
      val consequence =
        if (deferred) "it is not sent until $nextRetryAt" else "it was removed meanwhile"
      callbacks.log.log(
        Log.INFO,
        "beforeDispatch answered SKIP for item ${entity.id}; $consequence",
      )
    }
    if (!deferred) return ItemOutcome.Gone(entity.id)
    emitter.emit(
      SyncEventEmitter.event(
        type = SyncEventType.ITEM_RETRYING,
        itemId = entity.id,
        errorCode = SyncErrorCode.INTERCEPTOR_FAILED,
        attempt = attempt,
      ),
    )
    return ItemOutcome.Deferred(entity.id)
  }

  /**
   * Tell `afterDispatch`, when JS installed it, how [entity]'s request ended, once its [outcome]
   * is written and reported. Nothing waits for it, and its answer changes nothing. It is called
   * on the gateway's notify context, Main, after the item event reached the listeners, so it
   * follows that event as a rule, not as a guarantee.
   *
   * It is told the [attempt] made; `SUCCEEDED`, `RETRYING` (a retry or a credential hold) or
   * `FAILED` (a dead letter, a spent budget, or the retry decider's DEAD_LETTER or DROP) with the
   * code the item event carried; the status; [durationMs], the request's own time; and the item's
   * URL, method and metadata. Never its headers or its body.
   */
  @Suppress("LongParameterList")
  private fun notifyAfterDispatch(
    entity: SyncItemEntity,
    attempt: Int,
    result: AttemptResult,
    outcome: ItemOutcome,
    durationMs: Long,
    options: SyncOptions,
  ) {
    val hook = callbacks.registry.afterDispatch.installed ?: return
    val (reported, errorCode) = when (outcome) {
      is ItemOutcome.Succeeded -> DispatchOutcome.SUCCEEDED to null
      is ItemOutcome.Rescheduled, is ItemOutcome.CredentialRejected ->
        DispatchOutcome.RETRYING to (result as? AttemptResult.Failure)?.code
      is ItemOutcome.Failed -> DispatchOutcome.FAILED to outcome.code
      // Neither follows a request that went out.
      is ItemOutcome.Gone, is ItemOutcome.Deferred -> return
    }
    val context = AfterDispatchContext(
      itemId = entity.id,
      attempt = attempt.toDouble(),
      outcome = reported,
      statusCode = result.statusCode?.toDouble(),
      errorCode = errorCode,
      url = entity.url,
      method = methodOf(entity),
      metadata = JsonMap.decode(entity.metadata),
      durationMs = durationMs.toDouble(),
    )
    callbacks.afterDispatch(hook, context, CallbackTimeout.ms(options))
  }

  /** [request]'s headers, one entry per name, each with the spelling it was set with. */
  private fun headersOf(request: Request): Map<String, String> {
    val headers = request.headers
    return buildMap<String, String>(headers.size) {
      for (i in 0 until headers.size) put(headers.name(i), headers.value(i))
    }
  }

  /** The item's method as JS knows it: GET when the stored one is unknown. */
  private fun methodOf(entity: SyncItemEntity): HttpMethod =
    runCatching { HttpMethod.valueOf(entity.method.uppercase()) }.getOrDefault(HttpMethod.GET)

  /**
   * A failure the policy retries. With no retry decider installed, the row cools down for the
   * policy's delay, from the end of the attempt. With one, the decider is asked first, and what
   * it decides is applied:
   * - RETRY: the row cools down for the decider's `delayMs`, used as given (no jitter, no
   *   `maxDelayMs`), or for the policy's delay when it gives none or an unusable one;
   * - DEAD_LETTER: the row becomes a dead letter with [SyncErrorCode.RETRY_DECIDER_REJECTED]
   *   (see [rejectToDeadLetter]);
   * - DROP: the row is deleted (see [drop]).
   *
   * Without a decision (a failed or wrong answer, none within `callbackTimeoutMs`, an owner
   * gone, a stopped cycle) the policy's retry applies. Either way the delay counts from the
   * moment the decision was taken, not from the end of the attempt.
   *
   * The policy's retry is remembered before the wait: should anything raise during it, the
   * settle applies that retry. Each outcome is remembered again before its own write, so the
   * settle applies what was decided, and never asks JS.
   */
  @Suppress("LongParameterList")
  private suspend fun retryOrAsk(
    entity: SyncItemEntity,
    attempt: Int,
    startedAt: Long,
    finishedAt: Long,
    outcome: AttemptResult.Failure,
    options: SyncOptions,
    stop: Job,
  ): ItemOutcome {
    val policyDelayMs = RetryPolicyEvaluator.computeDelayMs(attempt, options.retryPolicy, random)
    val policyRetry = AbortedClaim.Retry(
      id = entity.id,
      attempts = attempt,
      lastAttemptAt = startedAt,
      lastErrorCode = outcome.code,
      nextRetryAt = RetryPolicyEvaluator.deadline(from = finishedAt, delayMs = policyDelayMs),
    )
    val decider = callbacks.registry.retryDecider.installed
      ?: return rescheduled(entity, attempt, outcome, policyRetry)

    remember(entity.id, Settlement(policyRetry, attempt, outcome.statusCode))
    val answer = callbacks.decide(
      decider = decider,
      context = deciderContext(entity, attempt, outcome),
      timeoutMs = CallbackTimeout.ms(options),
      stop = stop,
    )
    val decidedAt = clock()
    val decision = (answer as? CallbackResult.Answered)?.value
    return when (decision?.action) {
      RetryAction.DEAD_LETTER -> rejectToDeadLetter(entity, attempt, startedAt, outcome)
      RetryAction.DROP -> drop(entity, attempt, outcome)
      RetryAction.RETRY, null -> {
        val delayMs = decision?.let { usableDelayMs(entity, it) } ?: policyDelayMs
        val nextRetryAt = RetryPolicyEvaluator.deadline(from = decidedAt, delayMs = delayMs)
        rescheduled(entity, attempt, outcome, policyRetry.copy(nextRetryAt = nextRetryAt))
      }
    }
  }

  /** Persist the cooldown [retry] names; the row is excluded from claims until it elapses. */
  private suspend fun rescheduled(
    entity: SyncItemEntity,
    attempt: Int,
    outcome: AttemptResult.Failure,
    retry: AbortedClaim.Retry,
  ): ItemOutcome {
    remember(entity.id, Settlement(retry, attempt, outcome.statusCode))
    storage.markRetry(
      entity.id,
      retry.attempts,
      retry.lastAttemptAt,
      retry.lastErrorCode,
      retry.nextRetryAt,
    )
    emitRetrying(entity, attempt, outcome)
    return ItemOutcome.Rescheduled(entity.id)
  }

  /**
   * The retry decider rejected the item: it becomes a dead letter with
   * [SyncErrorCode.RETRY_DECIDER_REJECTED], and the attempt's own failure as its message. A row
   * purged during the wait is not brought back; see [deadLetter].
   */
  private suspend fun rejectToDeadLetter(
    entity: SyncItemEntity,
    attempt: Int,
    startedAt: Long,
    outcome: AttemptResult.Failure,
  ): ItemOutcome {
    deadLetter(
      entity = entity,
      attempts = attempt,
      lastAttemptAt = startedAt,
      code = SyncErrorCode.RETRY_DECIDER_REJECTED,
      detail = outcome.detail,
      statusCode = outcome.statusCode,
      requestSent = true,
    )
    return ItemOutcome.Failed(entity.id, SyncErrorCode.RETRY_DECIDER_REJECTED, outcome.message)
  }

  /**
   * The retry decider dropped the item: its queue row is deleted, with no dead letter, and then
   * `ITEM_FAILED` reports [SyncErrorCode.RETRY_DECIDER_REJECTED]. The write comes first, so a
   * settle that applies the drop after a failed delete reports it once. A row purged during the
   * wait still gets its `ITEM_FAILED`: its request went out.
   */
  private suspend fun drop(
    entity: SyncItemEntity,
    attempt: Int,
    outcome: AttemptResult.Failure,
  ): ItemOutcome {
    remember(entity.id, Settlement(AbortedClaim.Dropped(entity.id), attempt, outcome.statusCode))
    storage.markDropped(entity.id)
    emitter.emit(
      SyncEventEmitter.event(
        type = SyncEventType.ITEM_FAILED,
        itemId = entity.id,
        errorCode = SyncErrorCode.RETRY_DECIDER_REJECTED,
        statusCode = outcome.statusCode,
        attempt = attempt,
      ),
    )
    return ItemOutcome.Failed(entity.id, SyncErrorCode.RETRY_DECIDER_REJECTED, outcome.message)
  }

  /** What the retry decider is told about the attempt that just failed. */
  private fun deciderContext(
    entity: SyncItemEntity,
    attempt: Int,
    outcome: AttemptResult.Failure,
  ): RetryDeciderContext = RetryDeciderContext(
    itemId = entity.id,
    attempt = attempt.toDouble(),
    errorCode = outcome.code,
    statusCode = outcome.statusCode?.toDouble(),
    url = entity.url,
    method = methodOf(entity),
    metadata = JsonMap.decode(entity.metadata),
  )

  /**
   * A RETRY's own delay: finite and at least 0, its fraction dropped, at most
   * [RetryPolicyEvaluator.MAX_DELAY_MS]; the deadline it gives stops at
   * [RetryPolicyEvaluator.MAX_DEADLINE_MS]. Null when there is none, or none usable, which means
   * the policy's delay: an unusable delay is not a failed answer.
   */
  private fun usableDelayMs(entity: SyncItemEntity, decision: RetryDecision): Long? {
    val delayMs = decision.delayMs ?: return null
    if (!delayMs.isFinite() || delayMs < 0.0) {
      callbacks.log.log(
        Log.WARN,
        "retryDecider for item ${entity.id}: delayMs $delayMs is not a usable delay; " +
          "the policy's delay applies",
      )
      return null
    }
    return minOf(delayMs, RetryPolicyEvaluator.MAX_DELAY_MS.toDouble()).toLong()
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
    val hold = AbortedClaim.Retry(entity.id, entity.attempts, startedAt, outcome.code, nextRetryAt)
    remember(entity.id, Settlement(hold, attempt, outcome.statusCode, heldUntil = nextRetryAt))
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
    val deadLetteredAt = clock()
    val exhausted =
      AbortedClaim.Exhausted(entity, attempts, lastAttemptAt, code, detail, deadLetteredAt)
    remember(entity.id, Settlement(exhausted, attempts, statusCode))
    val moved = storage.moveToDeadLetters(
      entity = entity,
      attempts = attempts,
      lastAttemptAt = lastAttemptAt,
      lastErrorCode = code,
      lastErrorMessage = detail,
      deadLetteredAt = deadLetteredAt,
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

  /**
   * Send [request], item [id]'s attempt, and classify how it ended.
   *
   * @param attemptStartedAt epoch ms; recorded in [requestsSent] when the request goes out.
   * @return the outcome, with the request's own duration when it went out: from just before it
   *   was sent to its end, on the monotonic clock, so no wait for JS is in it.
   */
  private suspend fun runOnce(
    id: String,
    request: Request,
    timeoutMs: Long,
    attemptStartedAt: Long,
  ): Attempt = withContext(Dispatchers.IO) {
    // Built by the cycle before any attempt, so reading it here cannot fail an item.
    val shared = httpClient.value
    var call: Call? = null
    var startedAt = System.nanoTime()
    var sent = false
    val result = try {
      // The call is derived here, so that one that cannot be built fails the attempt, as a
      // request that cannot be built does. The call timeout bounds the whole call: connecting,
      // sending the request and reading the response. It is the attempt's only time limit
      // besides the client's socket timeouts.
      val built = shared.newBuilder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
        .newCall(request)
      call = built
      startedAt = System.nanoTime()
      // From here the server may get the request: an abort counts this attempt.
      requestsSent[id] = SentRequest(attemptStartedAt)
      sent = true
      built.execute().use { response -> responseOutcome(response) }
    } catch (e: IOException) {
      val ranOutOfTime = System.nanoTime() - startedAt >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)
      transportFailure(e, ranOutOfTime)
    } catch (e: CancellationException) {
      // Not the attempt's outcome. An Error is not caught either.
      throw e
    } catch (e: Exception) {
      // Anything but an I/O failure is permanent, as on iOS: a retry would fail the same way,
      // and NETWORK_ERROR stays a code that is always retried.
      call?.let { runCatching { it.cancel() } }
      AttemptResult.Failure(
        code = SyncErrorCode.INVALID_PAYLOAD,
        message = e.message ?: e.javaClass.simpleName,
        statusCode = null,
        retryable = false,
      )
    }
    val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(0L)
    Attempt(result, durationMs.takeIf { sent })
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

  /**
   * The request [entity] makes, or null when OkHttp refuses it: its URL, or a header name or
   * value. Its headers are [defaultHeaders], then [resolved], the header resolver's, then the
   * item's own: each later layer replaces a name an earlier one set, whatever its case.
   */
  private fun buildRequest(
    entity: SyncItemEntity,
    defaultHeaders: Map<String, String>?,
    resolved: Map<String, String>? = null,
  ): Request? = runCatching {
    val builder = Request.Builder().url(entity.url)
    defaultHeaders?.forEach { (k, v) -> builder.header(k, v) }
    resolved?.forEach { (k, v) -> builder.header(k, v) }
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

  /**
   * Rescheduled and held items count as neither success nor failure: they are still
   * queued, and their outcome belongs to a later cycle. Nor do items purged before their
   * request went out, or deferred by a JS interceptor: they were never sent.
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
    /** The status the server answered, when it answered. */
    val statusCode: Int?

    data class Success(override val statusCode: Int) : AttemptResult
    /**
     * @property detail what a dead letter records: the status line, reason phrase included,
     *   or the exception message. Never a header or a body.
     */
    data class Failure(
      val code: SyncErrorCode,
      val message: String,
      override val statusCode: Int?,
      val retryable: Boolean,
      val detail: String = message,
    ) : AttemptResult
  }

  /**
   * What [runOnce] came to: the attempt's [result], and [durationMs], how long its request took
   * in whole ms. [durationMs] is null exactly when no request went out, [requestsSent] then
   * having no entry for it.
   */
  private data class Attempt(val result: AttemptResult, val durationMs: Long?)

  /** What the JS interceptors made of an item's request (see [intercept]). */
  private sealed interface Interception {
    /** Send [request]. [awaited]: JS was asked first. */
    data class Send(val request: Request, val awaited: Boolean) : Interception

    /** Send nothing in this cycle: an interceptor gave nothing to use, or [skipped] it. */
    data class Deferred(val skipped: Boolean) : Interception

    /** Purged during a wait: nothing is sent, and nothing reported. */
    data object Gone : Interception
  }

  /**
   * A request the running cycle sent.
   *
   * @property startedAt epoch ms its attempt started.
   * @property outcome what the dispatch decided to write for the row once its answer was
   *   classified, set before that write: [abortCycle] applies it if the write fails. Null while
   *   the request is out, and for good when the send raised what no outcome classifies, an
   *   [Error] say.
   */
  private data class SentRequest(val startedAt: Long, val outcome: Settlement? = null)

  /**
   * One row's write in a settle, with what its item event reports.
   *
   * @property attempt the attempt the event reports; a credential hold reports it without
   *   spending it.
   * @property heldUntil the end of a credential hold, which the next wake waits for.
   */
  private data class Settlement(
    val claim: AbortedClaim,
    val attempt: Int = 0,
    val statusCode: Int? = null,
    val heldUntil: Long? = null,
  )

  private sealed interface ItemOutcome {
    val itemId: String
    data class Succeeded(override val itemId: String, val statusCode: Int) : ItemOutcome

    /** Retriable failure re-queued with a persisted cooldown. */
    data class Rescheduled(override val itemId: String) : ItemOutcome

    /** Restored credential rejected: the item waits, without spending an attempt. */
    data class CredentialRejected(override val itemId: String, val nextRetryAt: Long) : ItemOutcome

    /** Purged before its request went out: never sent, and nothing reported for it. */
    data class Gone(override val itemId: String) : ItemOutcome

    /** Kept from going out by a JS interceptor: it waits, without spending the attempt. */
    data class Deferred(override val itemId: String) : ItemOutcome
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

    /**
     * Floor for the wait of an item a JS interceptor deferred, the credential hold's: a failing
     * interceptor does not spend attempts, so the backoff alone would never grow.
     */
    const val MIN_INTERCEPTOR_DEFER_MS: Long = MIN_CREDENTIAL_HOLD_MS

    /** Where `ITEM_DEAD_LETTERED` carries the real failure code. */
    const val METADATA_LAST_ERROR_CODE = "lastErrorCode"

    /** The shared client: the fixed socket timeouts, and no retry of OkHttp's own. */
    private fun newClient(verboseLogging: Boolean): OkHttpClient {
      val builder = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)

      if (verboseLogging) {
        val logging = HttpLoggingInterceptor { msg -> Log.d(TAG, msg) }
        // BASIC, because every level above it logs header values.
        logging.level = HttpLoggingInterceptor.Level.BASIC
        builder.addInterceptor(logging)
      }
      return builder.build()
    }

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
