package com.margelo.nitro.syncprovider

import android.content.Context
import android.util.Log
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.syncprovider.error.SyncException
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.cancellation.CancellationException

/**
 * Nitro `HybridObject` implementation. Every public method of this class
 * mirrors a method in [HybridSyncProviderSpec] one-for-one.
 *
 * The class is intentionally thin: all real work lives in components owned
 * by [SyncProviderRuntime] (database, dispatcher, connectivity monitor,
 * background sync). The bridge wraps each call in a [Promise.async] backed by
 * the module-scoped [coroutineScope] so failures route through Nitro's
 * Promise rejection path.
 *
 * Errors:
 * - Every escaping throwable is normalized to [SyncException]. The JS facade
 *   re-instantiates a `SyncError` with the matching `code` based on the
 *   `<code>: <message>` rejection payload.
 */
@DoNotStrip
class SyncProvider : HybridSyncProviderSpec() {

  private val tag = "SyncProvider"

  private val exceptionHandler = CoroutineExceptionHandler { _, t ->
    Log.e(tag, "uncaught coroutine error", t)
  }

  /**
   * Module-scoped coroutine scope. SupervisorJob() so a single rejected
   * Promise cannot cascade-cancel sibling work.
   */
  private val coroutineScope: CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + exceptionHandler,
  )

  private val runtime: SyncProviderRuntime by lazy {
    val context: Context = NitroModules.applicationContext
      ?: error("NitroModules.applicationContext is null. SyncProvider cannot bootstrap.")
    SyncProviderRuntime.bootstrap(context).also { rt ->
      // Start observing connectivity so getConnectionStatus() and
      // CONNECTION_CHANGED events stay live.
      rt.connectivityMonitor.start()
      // Schedule recovery on first construction (no-op if already done).
      rt.backgroundSyncManager.scheduleRecovery()
    }
  }

  /* ------------------------------ Queue ops ----------------------------- */

  override fun enqueue(item: SyncItemInput): Promise<String> = promise {
    runtime.storage.enqueue(item).also { id ->
      runtime.emitter.emit(
        SyncEventEmitter.event(
          type = SyncEventType.ITEM_ENQUEUED,
          itemId = id,
        ),
      )
    }
  }

  override fun enqueueBatch(items: Array<SyncItemInput>): Promise<Array<String>> = promise {
    val ids = runtime.storage.enqueueBatch(items.toList())
    ids.forEach { id ->
      runtime.emitter.emit(
        SyncEventEmitter.event(SyncEventType.ITEM_ENQUEUED, itemId = id),
      )
    }
    ids.toTypedArray()
  }

  override fun removeItem(id: String): Promise<Boolean> = promise {
    val removed = runtime.storage.removeItem(id)
    if (removed) {
      runtime.emitter.emit(
        SyncEventEmitter.event(SyncEventType.ITEM_REMOVED, itemId = id),
      )
    }
    removed
  }

  override fun clearQueue(): Promise<Unit> = promise {
    runtime.storage.clear()
    runtime.emitter.emit(SyncEventEmitter.event(SyncEventType.QUEUE_CLEARED))
  }

  override fun getQueueSize(): Promise<Double> = promise {
    runtime.storage.count().toDouble()
  }

  override fun getPendingItems(): Promise<Array<SyncItem>> = promise {
    runtime.storage.getPending().toTypedArray()
  }

  /* ------------------------------ Sync ops ------------------------------ */

  override fun flush(): Promise<SyncResult> = promise {
    if (runtime.paused) {
      // Pause is a soft signal: return an empty no-op result instead of
      // rejecting, so consumers can call flush() defensively.
      return@promise emptyResult()
    }
    runtime.dispatcher.flush()
  }

  override fun pauseSync(): Promise<Unit> = promise {
    runtime.paused = true
    runtime.emitter.emit(SyncEventEmitter.event(SyncEventType.PAUSED))
  }

  override fun resumeSync(): Promise<Unit> = promise {
    runtime.paused = false
    runtime.emitter.emit(SyncEventEmitter.event(SyncEventType.RESUMED))
  }

  override fun isSyncing(): Promise<Boolean> = promise {
    runtime.dispatcher.isSyncing()
  }

  /* ----------------------------- Config ops ----------------------------- */

  override fun configureSync(options: SyncOptions): Promise<Unit> = promise {
    runtime.updateOptions(options)
  }

  override fun getSyncConfig(): Promise<SyncOptions> = promise {
    runtime.currentOptions()
  }

  /* ----------------------------- History ops ---------------------------- */

  override fun getLastSyncResult(): Promise<SyncResult> = promise {
    runtime.storage.getLastResult() ?: emptyResult()
  }

  override fun getSyncHistory(limit: Double): Promise<Array<SyncResult>> = promise {
    val intLimit = limit.toInt().coerceAtLeast(0)
    runtime.storage.getHistory(intLimit).toTypedArray()
  }

  override fun clearSyncHistory(): Promise<Unit> = promise {
    runtime.storage.clearHistory()
  }

  /* ----------------------------- Connection ----------------------------- */

  override fun getConnectionStatus(): Promise<ConnectionState> = promise {
    runtime.connectivityMonitor.currentState()
  }

  /* -------------------------- Background sync --------------------------- */

  override fun enableBackgroundSync(options: BackgroundSyncOptions): Promise<Unit> = promise {
    runtime.backgroundSyncManager.enable(options)
  }

  override fun disableBackgroundSync(): Promise<Unit> = promise {
    runtime.backgroundSyncManager.disable()
  }

  override fun isBackgroundSyncEnabled(): Promise<Boolean> = promise {
    runtime.backgroundSyncManager.isEnabled()
  }

  /* ------------------------------- Events ------------------------------- */

  override fun addListener(
    event: String,
    callback: (event: SyncEvent) -> Unit,
  ): Promise<String> = promise {
    runtime.emitter.add(event) { syncEvent ->
      // Marshal back through the SyncEventEmitter dispatcher; the callback
      // itself is invoked on the main dispatcher inside emitter.emit.
      callback(syncEvent)
    }
  }

  override fun removeListener(event: String, subscriptionId: String): Promise<Unit> = promise {
    runtime.emitter.remove(event, subscriptionId)
  }

  /* ------------------------------ teardown ------------------------------ */

  /**
   * Cancel the module scope. Called by Nitro when the HybridObject is
   * disposed via JS.
   */
  override fun dispose() {
    runCatching { coroutineScope.coroutineContext[Job]?.cancel() }
    super.dispose()
  }

  /* ------------------------------ helpers ------------------------------- */

  /**
   * Wrap a suspend block into a Nitro [Promise], translating any throwable
   * to a [SyncException] so the JS side sees a consistent payload.
   */
  private inline fun <T> promise(crossinline block: suspend () -> T): Promise<T> =
    Promise.async(coroutineScope) {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: SyncException) {
        throw e
      } catch (t: Throwable) {
        Log.e(tag, "Unexpected error", t)
        throw SyncException(
          SyncErrorCode.NATIVE_MODULE_UNAVAILABLE,
          t.message ?: t.javaClass.simpleName,
          t,
        )
      }
    }

  private fun emptyResult(): SyncResult = SyncResult(
    startedAt = 0.0,
    finishedAt = 0.0,
    successCount = 0.0,
    failureCount = 0.0,
    succeededIds = emptyArray(),
    failedIds = emptyArray(),
    errors = emptyMap(),
  )
}
