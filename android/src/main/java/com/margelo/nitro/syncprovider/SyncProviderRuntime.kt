package com.margelo.nitro.syncprovider

import android.content.Context
import android.util.Log
import com.margelo.nitro.syncprovider.config.ConfigSource
import com.margelo.nitro.syncprovider.config.EngineConfig
import com.margelo.nitro.syncprovider.config.KeystoreSecretStore
import com.margelo.nitro.syncprovider.config.SecretStore
import com.margelo.nitro.syncprovider.config.SyncConfigStore
import com.margelo.nitro.syncprovider.config.SyncOptionsValidator
import com.margelo.nitro.syncprovider.connectivity.ConnectivityMonitor
import com.margelo.nitro.syncprovider.connectivity.LatencyProbe
import com.margelo.nitro.syncprovider.connectivity.meetsMinimum
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.http.CredentialPolicy
import com.margelo.nitro.syncprovider.http.HeadProbe
import com.margelo.nitro.syncprovider.http.HealthCheck
import com.margelo.nitro.syncprovider.http.SyncDispatcher
import com.margelo.nitro.syncprovider.retry.RetryTimer
import com.margelo.nitro.syncprovider.retry.RetryWake
import com.margelo.nitro.syncprovider.retry.WakeHold
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.work.BackgroundSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Whatever process builds it, a runtime restores the configuration persisted by the
 * last `configureSync`, so a worker with no JS behaves as the consumer configured.
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
  secretStore: SecretStore,
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

  /** The one `HEAD` probe the latency measurement and the health check share, connections too. */
  val headProbe: HeadProbe = HeadProbe()

  /** Shared with the quality measurement, whose `2xx` answers count as passed checks. */
  val healthCheck: HealthCheck = HealthCheck(headProbe)

  val connectivityMonitor: ConnectivityMonitor = ConnectivityMonitor(
    context = appContext,
    emitter = emitter,
    scope = scope,
    healthCheckUrl = { currentOptions().healthCheckUrl },
    measure = LatencyProbe(headProbe)::measure,
    onDefaultNetworkChanged = { retryTimer.resetGateBackoff() },
    onHealthySample = { url -> healthCheck.recordHealthy(url) },
  )

  private val configStore = SyncConfigStore(appContext, secretStore)

  /**
   * The active configuration. [SyncDispatcher] reads through it, so configuration
   * changes apply on the next attempt.
   */
  private val configRef: AtomicReference<EngineConfig> = AtomicReference(
    configStore.load() ?: EngineConfig(defaultOptions(), ConfigSource.DEFAULTS),
  )
  private val pausedFlag = AtomicBoolean(configStore.isPaused())

  /**
   * Keeps memory and disk in the same order. Otherwise a logout racing an earlier
   * `configureSync` could leave this process logged out while the old credential
   * stays persisted for the next cold worker.
   */
  private val configureMutex = Mutex()

  private val attachedEngines = AtomicInteger(0)

  @Volatile private var connectivityWatch: Job? = null

  val dispatcher: SyncDispatcher = SyncDispatcher(
    storage = storage,
    emitter = emitter,
    getOptions = { currentOptions() },
    verboseLogging = { false },
    isPaused = { pausedFlag.get() },
    credentialPolicy = { credentialPolicy() },
    networkQuality = { connectivityMonitor.qualityForGate() },
    healthCheck = healthCheck,
    onHealthCheckSkipped = { retryWake.arm() },
    onCycleFinished = { notBefore -> retryWake.arm(notBefore) },
  )

  private val retryTimer = RetryTimer(
    scope = scope,
    isOffline = { connectivityMonitor.isOffline() },
    flush = { flushForWake() },
    holdReason = { wakeHold() },
  )

  private val retryWake = RetryWake(
    storage = storage,
    timer = retryTimer,
    scheduleKick = { delayMs -> backgroundSyncManager.scheduleOneTimeFlush(initialDelayMs = delayMs) },
    isHalted = { isWakeHalted() },
    isEngineLive = { isEngineAttached },
    isQualityGateOpen = { isQualityGateOpen() },
    scheduleGatedKick = { delayMs, gateStreak ->
      backgroundSyncManager.scheduleOneTimeFlush(initialDelayMs = delayMs, gateStreak = gateStreak)
    },
    healthBackoffUntil = { healthCheck.wakeNotBefore() },
  )

  init {
    applyQueueCap(currentOptions())
  }

  /** Whether the engine is paused. Survives process death, until a JS engine attaches. */
  val isPaused: Boolean get() = pausedFlag.get()

  fun currentOptions(): SyncOptions = configRef.get().options

  /** Where the active configuration came from. */
  val configSource: ConfigSource get() = configRef.get().source

  /** Whether the in-process retry timer is counting down. */
  val isRetryTimerArmed: Boolean get() = retryTimer.isWaiting

  /** Whether a JS engine lives in this process. A process started for a worker has none. */
  val isEngineAttached: Boolean get() = attachedEngines.get() > 0

  /** Items waiting to be dispatched, cooling ones included. */
  suspend fun queueSize(): Int = storage.pendingCount()

  /**
   * Apply a `configureSync` from JS: it takes effect now, and in every later process.
   * Every call also lifts the credential hold, whether or not the headers changed.
   *
   * Invalid options are rejected before anything is applied or persisted; see
   * [SyncOptionsValidator].
   */
  suspend fun configure(options: SyncOptions) {
    SyncOptionsValidator.validate(options)
    configureMutex.withLock {
      configRef.set(
        EngineConfig(
          options = options,
          source = ConfigSource.LIVE,
          hasDefaultHeaders = !options.defaultHeaders.isNullOrEmpty(),
        ),
      )
      retryWake.clearCredentialHold()
      applyQueueCap(options)
      withContext(Dispatchers.IO) {
        runCatching { configStore.save(options) }
          .onFailure { Log.w(TAG, "Could not persist the configuration: ${it.javaClass.simpleName}") }
      }
    }
    connectivityMonitor.onConfigurationChanged()
    healthCheck.clearBackoff()
    if (options.strategy == SyncStrategy.MANUAL) backgroundSyncManager.cancelRetryKick()
    retryWake.arm()
  }

  fun pause() {
    pausedFlag.set(true)
    configStore.setPaused(true)
    retryTimer.cancel()
    backgroundSyncManager.cancelRetryKick()
  }

  suspend fun resume() {
    pausedFlag.set(false)
    configStore.setPaused(false)
    retryWake.armForResume()
  }

  /** Arm the next retry wake from the current queue, no earlier than a credential hold. */
  suspend fun armRetryWake() {
    retryWake.arm()
  }

  /**
   * Re-arm a kick the network-quality gate turned back, [gateStreak] kicks in a row. See
   * [RetryWake.armAfterGate].
   */
  suspend fun armAfterQualityGate(gateStreak: Int) {
    retryWake.armAfterGate(gateStreak)
  }

  /** Remove a queued item, or else a dead letter with that id. Emits `ITEM_REMOVED` when one went. */
  suspend fun removeItem(id: String): Boolean {
    val removed = storage.removeItem(id)
    if (removed) emitter.emit(SyncEventEmitter.event(SyncEventType.ITEM_REMOVED, itemId = id))
    return removed
  }

  /** Empty the queue and the dead letters together, with one `QUEUE_CLEARED`. */
  suspend fun clearQueue() {
    storage.clear()
    emitter.emit(SyncEventEmitter.event(SyncEventType.QUEUE_CLEARED))
  }

  /** Dead letters, by `deadLetteredAt` then id. */
  suspend fun listDeadLetter(): List<SyncDeadLetterItem> = storage.listDeadLetters()

  /**
   * Put a dead letter back in the queue under its id, as a fresh item: `PENDING`, no attempt,
   * no cooldown, its original `createdAt`. Emits `ITEM_ENQUEUED`. Like an enqueue, it does
   * not flush. An unknown id changes nothing.
   *
   * @throws com.margelo.nitro.syncprovider.error.SyncException `QUEUE_FULL` when the queue is
   *   full; the dead letter stays.
   */
  suspend fun replayDeadLetter(id: String) {
    if (storage.replayDeadLetter(id)) {
      emitter.emit(SyncEventEmitter.event(SyncEventType.ITEM_ENQUEUED, itemId = id))
    }
  }

  /** Delete a dead letter for good. Emits `ITEM_REMOVED` when one was deleted. */
  suspend fun discardDeadLetter(id: String) {
    if (storage.discardDeadLetter(id)) {
      emitter.emit(SyncEventEmitter.event(SyncEventType.ITEM_REMOVED, itemId = id))
    }
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

  /**
   * A JS engine now lives in this process. Pair with [detachEngine].
   *
   * Every new engine starts unpaused. JS cannot read the flag and assumes it is off, so
   * keeping it would stop the queue silently. It happens on every attach, not only the
   * first, because a reload starts a new JS without disposing the old hybrid. A worker
   * never attaches, so a process with no JS keeps honoring the flag.
   */
  fun attachEngine() {
    // Before recovery is scheduled: a paused engine arms no wake.
    pausedFlag.set(false)
    configStore.setPaused(false)
    if (attachedEngines.getAndIncrement() == 0) {
      connectivityMonitor.start()
      connectivityWatch = connectivityMonitor.state
        .filter { it.status == ConnectionStatus.CONNECTED || it.status == ConnectionStatus.METERED }
        .onEach { state ->
          retryTimer.onNetworkAvailable()
          if (state.networkQuality.meetsMinimum(currentOptions().minNetworkQuality)) {
            retryTimer.onDispatchPossible()
          }
        }
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

  /**
   * A timer wake runs one flush. The timer checked the gate before it; should the quality
   * have dropped since, the dispatcher turns the flush back, and the wake is held after all.
   */
  private suspend fun flushForWake() {
    if (dispatcher.flushWithOutcome().isHeldByQualityGate) retryTimer.holdForQuality()
  }

  /** See [timerWakeHold]. */
  private fun wakeHold(): WakeHold? =
    timerWakeHold(currentOptions().minNetworkQuality, connectivityMonitor, healthCheck)

  private fun isQualityGateOpen(): Boolean {
    val minimum = currentOptions().minNetworkQuality
    return connectivityMonitor.currentState().networkQuality.meetsMinimum(minimum)
  }

  private fun isWakeHalted(): Boolean =
    isPaused ||
      currentOptions().strategy == SyncStrategy.MANUAL ||
      credentialPolicy() == CredentialPolicy.UNAVAILABLE

  private fun credentialPolicy(): CredentialPolicy {
    val config = configRecoveringSecret()
    return when {
      config.secretUnavailable -> CredentialPolicy.UNAVAILABLE
      config.source == ConfigSource.PERSISTED && config.hasDefaultHeaders -> CredentialPolicy.PERSISTED
      else -> CredentialPolicy.TRUSTED
    }
  }

  /** A failed read can be transient, so it is retried until it succeeds or JS configures. */
  private fun configRecoveringSecret(): EngineConfig {
    val config = configRef.get()
    if (!config.secretUnavailable) return config
    val reloaded = configStore.load() ?: return config
    configRef.compareAndSet(config, reloaded)
    return configRef.get()
  }

  companion object {
    private const val TAG = "SyncProviderRuntime"

    private val INSTANCE: AtomicReference<SyncProviderRuntime?> = AtomicReference(null)

    fun bootstrap(context: Context): SyncProviderRuntime =
      bootstrap(context, KeystoreSecretStore(context))

    fun bootstrap(context: Context, secretStore: SecretStore): SyncProviderRuntime {
      val existing = INSTANCE.get()
      if (existing != null) return existing
      val created = SyncProviderRuntime(context.applicationContext, secretStore)
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
      // Unset: no quality gate and no health check. Their defaults apply where they are used.
      minNetworkQuality = null,
      healthCheckUrl = null,
      healthCheckTtlMs = null,
      healthCheckBackoffMs = null,
    )
  }
}

/**
 * The checks a timer wake runs before dispatching, silently and in the dispatcher's order:
 * the quality gate, read only when [minimum] sets one, since the reading can start a new
 * measurement when the last one is 30 s old; then the health check's hold on automatic
 * wakes, which lasts at least [HealthCheck.MIN_WAKE_BACKOFF_MS] after a failure.
 */
internal fun timerWakeHold(
  minimum: NetworkQuality?,
  connectivity: ConnectivityMonitor,
  healthCheck: HealthCheck,
  now: Long = System.currentTimeMillis(),
): WakeHold? {
  if (!connectivity.passesGate(minimum)) return WakeHold.Quality
  val notBefore = healthCheck.wakeNotBefore()
  return if (notBefore > 0L) WakeHold.Health(notBefore - now) else null
}
