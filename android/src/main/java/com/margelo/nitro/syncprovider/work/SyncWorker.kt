package com.margelo.nitro.syncprovider.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.SyncProviderRuntime
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import kotlinx.coroutines.CancellationException

/**
 * WorkManager-driven flush worker. Runs against the singleton [SyncProviderRuntime].
 *
 * Inert when dispatch is blocked. See [SyncProviderRuntime.isBackgroundDispatchBlocked].
 */
internal class SyncWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val runtime = SyncProviderRuntime.getOrNull(applicationContext)
      ?: SyncProviderRuntime.bootstrap(applicationContext)

    if (runtime.isBackgroundDispatchBlocked()) return Result.success()

    val emitter = runtime.emitter
    val isPeriodic = inputData.getBoolean(KEY_PERIODIC, false)
    if (isPeriodic) {
      runCatching {
        emitter.emit(SyncEventEmitter.event(SyncEventType.BACKGROUND_SYNC_STARTED))
      }
    }

    return try {
      runtime.dispatcher.flush()
      if (isPeriodic) {
        emitter.emit(SyncEventEmitter.event(SyncEventType.BACKGROUND_SYNC_COMPLETED))
      }
      // A completed flush is always a worker success: retriable items are re-driven
      // by their persisted cooldowns and terminal failures are done. Returning
      // Result.retry() on `failureCount > 0` would make WorkManager's own backoff
      // race the retry wake, so it stays reserved for whole-worker failures.
      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Log.e(TAG, "SyncWorker failed", t)
      Result.retry()
    }
  }

  companion object {
    private const val TAG = "SyncWorker"
    const val KEY_PERIODIC = "periodic"
  }
}
