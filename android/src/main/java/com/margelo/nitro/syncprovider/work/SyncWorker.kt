package com.margelo.nitro.syncprovider.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.SyncProviderRuntime
import com.margelo.nitro.syncprovider.events.SyncEventEmitter

/**
 * WorkManager-driven flush worker. Always runs against the singleton
 * [SyncProviderRuntime] so configuration set via the JS facade is honored.
 */
internal class SyncWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val runtime = SyncProviderRuntime.getOrNull(applicationContext)
      ?: SyncProviderRuntime.bootstrap(applicationContext)

    val emitter = runtime.emitter
    val isPeriodic = inputData.getBoolean(KEY_PERIODIC, false)
    if (isPeriodic) {
      runCatching {
        emitter.emit(SyncEventEmitter.event(SyncEventType.BACKGROUND_SYNC_STARTED))
      }
    }

    return try {
      val result = runtime.dispatcher.flush()
      if (isPeriodic) {
        emitter.emit(SyncEventEmitter.event(SyncEventType.BACKGROUND_SYNC_COMPLETED))
      }
      if (result.failureCount > 0) Result.retry() else Result.success()
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
