package com.margelo.nitro.syncprovider

import android.content.Context
import com.margelo.nitro.syncprovider.connectivity.ConnectivityMonitor
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.http.SyncDispatcher
import com.margelo.nitro.syncprovider.retry.RetryTimer
import com.margelo.nitro.syncprovider.retry.RetryWake
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.work.BackgroundSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide runtime container shared between the Nitro [SyncProvider]
 * HybridObject and WorkManager-spawned workers.
 *
 * The bridge instance can come and go (RN reload, app cold-start), but
 * WorkManager may instantiate a `SyncWorker` when the bridge is dead. This
 * runtime is therefore detached from any particular HybridObject and uses the
 * application context.
 *
 * Lifecycle:
 * - [bootstrap] is idempotent and safe to call from any thread.
 * - [attachEngine] / [detachEngine] track live JS engines. The retry timer and the
 *   connectivity watch only run while one is attached.
 * - [shutdown] cancels the [scope] and drops the singleton; rarely used in
 *   practice (only the test harness calls it).
 */
internal class SyncProviderRuntime private constructor(
  appContext: Context,
) {

  /**
   * Shared scope. `SupervisorJob` so a child failure cannot kill the rest of
   * the runtime. Uses `Dispatchers.Default` because most of our async work is
   * CPU- and I/O-mixed; specific call sites switch to `Dispatchers.IO` when
   * blocking.
   */
  val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  val database: SyncDatabase = SyncDatabase.get(appContext)
  val emitter: SyncEventEmitter = SyncEventEmitter()
  val storage: SyncQueueStorage = SyncQueueStorage.from(database)
  val backgroundSyncManager: BackgroundSyncManager = BackgroundSyncManager(appContext)
  val connectivityMonitor: ConnectivityMonitor = ConnectivityMonitor(appContext, emitter, scope)

  /**
   * The active configuration. [SyncDispatcher] reads through it, so configuration
   * changes apply on the next attempt.
   */
  private val optionsRef: AtomicReference<SyncOptions> = AtomicReference(defaultOptions())
  private val pausedFlag = AtomicBoolean(false)

  private val attachedEngines = AtomicInteger(0)

  @Volatile private var connectivityWatch: Job? = null

  val dispatcher: SyncDispatcher = SyncDispatcher(
    storage = storage,
    emitter = emitter,
    getOptions = { currentOptions() },
    verboseLogging = { false },
    isPaused = { pausedFlag.get() },
    onCycleFinished = { notBefore -> retryWake.arm(notBefore) },
  )

  private val retryTimer = RetryTimer(
    scope = scope,
    isOffline = { connectivityMonitor.isOffline() },
    flush = { dispatcher.flush() },
  )

  private val retryWake = RetryWake(
    storage = storage,
    timer = retryTimer,
    scheduleKick = { delayMs -> backgroundSyncManager.scheduleOneTimeFlush(initialDelayMs = delayMs) },
    isHalted = { isWakeHalted() },
    isEngineLive = { attachedEngines.get() > 0 },
  )

  init {
    applyQueueCap(currentOptions())
  }

  /** Whether the engine is paused. */
  val isPaused: Boolean get() = pausedFlag.get()

  fun currentOptions(): SyncOptions = optionsRef.get()

  /** Whether the in-process retry timer is counting down. */
  val isRetryTimerArmed: Boolean get() = retryTimer.isWaiting

  /** Items waiting to be dispatched, cooling ones included. */
  suspend fun queueSize(): Int = storage.pendingCount()

  /** Apply a `configureSync` from JS. */
  suspend fun configure(options: SyncOptions) {
    optionsRef.set(options)
    applyQueueCap(options)
    if (options.strategy == SyncStrategy.MANUAL) backgroundSyncManager.cancelRetryKick()
    retryWake.arm()
  }

  fun pause() {
    pausedFlag.set(true)
    retryTimer.cancel()
    backgroundSyncManager.cancelRetryKick()
  }

  suspend fun resume() {
    pausedFlag.set(false)
    retryWake.armForResume()
  }

  /** Arm the next retry wake from the current queue. */
  suspend fun armRetryWake() {
    retryWake.arm()
  }

  /**
   * True when a background worker must do nothing: the engine is paused, or the consumer
   * asked for no OS-scheduled dispatch.
   *
   * The `MANUAL` strategy does not block it. Enabling background sync is an explicit
   * request, and `MANUAL` only keeps the retry timer and the retry kick unarmed.
   */
  fun isBackgroundDispatchBlocked(): Boolean =
    isPaused || backgroundSyncManager.isForegroundOnly()

  /** A JS engine now lives in this process. Pair with [detachEngine]. */
  fun attachEngine() {
    if (attachedEngines.getAndIncrement() == 0) {
      connectivityMonitor.start()
      connectivityWatch = connectivityMonitor.state
        .filter { it.status == ConnectionStatus.CONNECTED || it.status == ConnectionStatus.METERED }
        .onEach { retryTimer.onNetworkAvailable() }
        .launchIn(scope)
    }
    // Recovery arms the first retry wake once it has finished.
    backgroundSyncManager.scheduleRecovery()
  }

  fun detachEngine() {
    if (attachedEngines.decrementAndGet() == 0) {
      connectivityWatch?.cancel()
      connectivityWatch = null
      retryTimer.cancel()
    }
  }

  fun shutdown() {
    retryTimer.cancel()
    runCatching { connectivityMonitor.stop() }
    runCatching { scope.coroutineContext[Job]?.cancel() }
    INSTANCE.set(null)
  }

  private fun applyQueueCap(options: SyncOptions) {
    storage.maxQueueSize = (options.maxQueueSize?.toInt() ?: SyncQueueStorage.DEFAULT_MAX_QUEUE_SIZE)
      .coerceAtLeast(1)
  }

  private fun isWakeHalted(): Boolean =
    isPaused || currentOptions().strategy == SyncStrategy.MANUAL

  companion object {
    private val INSTANCE: AtomicReference<SyncProviderRuntime?> = AtomicReference(null)

    fun bootstrap(context: Context): SyncProviderRuntime {
      val existing = INSTANCE.get()
      if (existing != null) return existing
      val created = SyncProviderRuntime(context.applicationContext)
      return if (INSTANCE.compareAndSet(null, created)) {
        created
      } else {
        INSTANCE.get()!!
      }
    }

    fun getOrNull(context: Context?): SyncProviderRuntime? {
      val existing = INSTANCE.get()
      if (existing != null) return existing
      return context?.let { bootstrap(it) }
    }

    fun defaultOptions(): SyncOptions = SyncOptions(
      strategy = SyncStrategy.AUTOMATIC,
      retryPolicy = RetryPolicy(
        maxAttempts = 5.0,
        backoff = BackoffStrategy.EXPONENTIAL,
        baseDelayMs = 1_000.0,
        maxDelayMs = 60_000.0,
        jitter = true,
        retryOnStatusCodes = doubleArrayOf(408.0, 425.0, 429.0, 500.0, 502.0, 503.0, 504.0),
      ),
      batchSize = 25.0,
      requestTimeoutMs = 30_000.0,
      maxQueueSize = SyncQueueStorage.DEFAULT_MAX_QUEUE_SIZE.toDouble(),
      persistQueue = true,
      defaultHeaders = null,
    )
  }
}
