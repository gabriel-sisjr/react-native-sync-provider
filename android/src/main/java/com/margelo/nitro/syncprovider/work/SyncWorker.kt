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
 * WorkManager-driven flush worker. Runs against the singleton [SyncProviderRuntime],
 * which restores the persisted configuration when this is a cold start with no JS.
 * With no JS engine attached, it also recovers rows a dead process left `IN_FLIGHT`.
 *
 * Inert when dispatch is blocked, recovery included.
 * See [SyncProviderRuntime.isBackgroundDispatchBlocked].
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
      // Recovery otherwise needs an engine to attach, a boot or an app update, and until
      // then flush cannot see a row a dead process left IN_FLIGHT. The reset takes the
      // flush mutex, so it cannot catch a running cycle. It sends nothing, so unlike
      // dispatch it does not wait for headers that cannot be recovered.
      if (!runtime.isEngineAttached) runtime.dispatcher.resetInFlight()
      val outcome = runtime.dispatcher.flushWithOutcome()
      // A kick the network-quality gate held re-arms itself for when the gate decides
      // again, backing off, never through Result.retry(): WorkManager's backoff would take
      // the timing away from the library. A periodic run leaves it to the next period.
      if (outcome.isHeldByQualityGate && !isPeriodic) {
        runtime.armAfterQualityGate(inputData.getInt(KEY_GATE_STREAK, 0))
      }
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

    /** Consecutive kicks the network-quality gate turned back before this one. */
    const val KEY_GATE_STREAK = "gateStreak"
  }
}
