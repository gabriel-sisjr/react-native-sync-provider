package com.margelo.nitro.syncprovider.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Schedules [RecoveryWorker] after a reboot, on `BOOT_COMPLETED`, which arrives once the user
 * first unlocks the device, and after an update of this app, on `MY_PACKAGE_REPLACED`.
 *
 * `LOCKED_BOOT_COMPLETED` is not handled. The receiver is not direct-boot aware, so a locked
 * device never delivers it, and making it aware would not help: the queue database, the
 * configuration and WorkManager's own database all live in credential-encrypted storage.
 *
 * Exported, with no `android:permission` in the manifest: `RECEIVE_BOOT_COMPLETED` lets the
 * system deliver the boot broadcast to the app, and restricts no sender. Both actions are
 * protected broadcasts that only the system can send, and any other action is ignored.
 */
class BootCompletedReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
      return
    }
    Log.i(TAG, "Received $action — scheduling recovery worker")
    // Only enqueues the worker. The runtime is built there, off the main thread,
    // because building it opens the database and decrypts the stored headers.
    runCatching {
      BackgroundSyncManager(context.applicationContext).scheduleRecovery()
    }.onFailure { Log.e(TAG, "Failed to schedule RecoveryWorker", it) }
  }

  companion object {
    private const val TAG = "BootCompletedReceiver"
  }
}
