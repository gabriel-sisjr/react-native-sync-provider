package com.margelo.nitro.syncprovider.testutil

import android.content.Context
import androidx.work.WorkManager
import com.margelo.nitro.syncprovider.SyncProviderRuntime
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.work.BackgroundSyncManager

/**
 * Isolates tests that use the real [SyncProviderRuntime] and [SyncDatabase] singletons.
 *
 * [com.margelo.nitro.syncprovider.work.RecoveryWorker] is a coroutine worker, so it
 * outlives even a synchronous executor. If it finishes after a test shut the runtime
 * down, it bootstraps a new one, and the next test receives that runtime with a closed
 * database. [awaitRecovery] and [reset] together close that window.
 */
internal object RuntimeTestSupport {
  private const val AWAIT_TIMEOUT_MS = 20_000L
  private const val POLL_MS = 10L

  /** Drop any runtime and open database, so that the next one is built from scratch. */
  fun reset() {
    SyncProviderRuntime.getOrNull(null)?.shutdown()
    val field = SyncDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    (field.get(null) as SyncDatabase?)?.close()
    field.set(null, null)
  }

  /** Block until no recovery is enqueued or running. Requires a test WorkManager. */
  fun awaitRecovery(context: Context) {
    val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
    while (!isRecoveryFinished(context)) {
      check(System.currentTimeMillis() < deadline) { "Recovery did not finish" }
      Thread.sleep(POLL_MS)
    }
  }

  private fun isRecoveryFinished(context: Context): Boolean =
    WorkManager.getInstance(context)
      .getWorkInfosForUniqueWork(BackgroundSyncManager.UNIQUE_RECOVERY)
      .get()
      .all { it.state.isFinished }
}
