package com.margelo.nitro.syncprovider.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.margelo.nitro.syncprovider.SyncProviderRuntime

/**
 * Re-claim items left in `IN_FLIGHT` from a previous process and re-arm the
 * periodic background sync if the consumer enabled it.
 */
internal class RecoveryWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val runtime = SyncProviderRuntime.getOrNull(applicationContext)
      ?: SyncProviderRuntime.bootstrap(applicationContext)
    return try {
      val resetCount = runtime.storage.resetInFlight()
      if (resetCount > 0) {
        Log.i(TAG, "Recovery reset $resetCount in-flight item(s) to PENDING")
      }
      runtime.backgroundSyncManager.maybeReschedulePeriodic()
      Result.success()
    } catch (t: Throwable) {
      Log.e(TAG, "RecoveryWorker failed", t)
      Result.retry()
    }
  }

  companion object {
    private const val TAG = "RecoveryWorker"
  }
}
