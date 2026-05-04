package com.margelo.nitro.syncprovider

import android.content.Context
import com.margelo.nitro.syncprovider.connectivity.ConnectivityMonitor
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.http.SyncDispatcher
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.work.BackgroundSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
   * Mutable holder for [SyncOptions]. Defaults are written by
   * [SyncProvider] on first use; [SyncDispatcher] reads through this lambda
   * so configuration changes apply on the next attempt.
   */
  private val optionsRef: AtomicReference<SyncOptions> = AtomicReference(defaultOptions())

  val dispatcher: SyncDispatcher = SyncDispatcher(
    storage = storage,
    emitter = emitter,
    getOptions = { optionsRef.get() },
    verboseLogging = { false },
  )

  /** Pause flag honored by [SyncProvider.flush]. */
  @Volatile var paused: Boolean = false

  fun currentOptions(): SyncOptions = optionsRef.get()

  fun updateOptions(options: SyncOptions) {
    optionsRef.set(options)
    storage.maxQueueSize = (options.maxQueueSize?.toInt() ?: SyncQueueStorage.DEFAULT_MAX_QUEUE_SIZE)
      .coerceAtLeast(1)
  }

  fun shutdown() {
    runCatching { connectivityMonitor.stop() }
    runCatching { scope.coroutineContext[Job]?.cancel() }
    INSTANCE.set(null)
  }

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
