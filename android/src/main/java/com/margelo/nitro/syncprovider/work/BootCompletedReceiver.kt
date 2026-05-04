package com.margelo.nitro.syncprovider.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.margelo.nitro.syncprovider.SyncProviderRuntime

/**
 * Schedules [RecoveryWorker] when the device finishes booting (or when the
 * package is replaced via OTA update).
 *
 * Exported with `RECEIVE_BOOT_COMPLETED` permission gating; see the manifest.
 */
class BootCompletedReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (
      action != Intent.ACTION_BOOT_COMPLETED &&
      action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
      action != Intent.ACTION_MY_PACKAGE_REPLACED
    ) {
      return
    }
    Log.i(TAG, "Received $action — scheduling recovery worker")
    runCatching {
      val runtime = SyncProviderRuntime.bootstrap(context.applicationContext)
      runtime.backgroundSyncManager.scheduleRecovery()
    }.onFailure { Log.e(TAG, "Failed to schedule RecoveryWorker", it) }
  }

  companion object {
    private const val TAG = "BootCompletedReceiver"
  }
}
