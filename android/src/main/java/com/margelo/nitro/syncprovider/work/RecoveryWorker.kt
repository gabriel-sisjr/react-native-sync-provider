package com.margelo.nitro.syncprovider.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.margelo.nitro.syncprovider.SyncProviderRuntime
import kotlinx.coroutines.CancellationException

/**
 * Re-claim items left in `IN_FLIGHT` from a previous process, re-arm the periodic
 * background sync if the consumer enabled it, and arm the next retry wake.
 *
 * Never skipped: this is data integrity, not background dispatch. It issues no
 * network request of its own.
 */
internal class RecoveryWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val runtime = SyncProviderRuntime.getOrNull(applicationContext)
      ?: SyncProviderRuntime.bootstrap(applicationContext)
    return try {
      val resetCount = runtime.dispatcher.resetInFlight()
      if (resetCount > 0) {
        Log.i(TAG, "Recovery reset $resetCount in-flight item(s) to PENDING")
      }
      runtime.backgroundSyncManager.maybeReschedulePeriodic()
      runtime.armRetryWake()
      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Log.e(TAG, "RecoveryWorker failed", t)
      Result.retry()
    }
  }

  companion object {
    private const val TAG = "RecoveryWorker"
  }
}
