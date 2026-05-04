package com.margelo.nitro.syncprovider.work

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.margelo.nitro.syncprovider.BackgroundSyncOptions
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.error.SyncException
import java.util.concurrent.TimeUnit

/**
 * Wraps [WorkManager] for the SyncProvider's three named work units:
 *
 * - `syncprovider-flush-periodic` — [PeriodicWorkRequest] (15min floor).
 * - `syncprovider-flush-now` — [OneTimeWorkRequest] kicked off opportunistically.
 * - `syncprovider-recovery` — one-shot recovery on app launch / boot.
 */
internal class BackgroundSyncManager(context: Context) {

  private val appContext = context.applicationContext
  private val prefs: SharedPreferences =
    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val workManager: WorkManager
    get() = WorkManager.getInstance(appContext)

  fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

  /** Snapshot of the most recently configured options, or `null`. */
  fun lastOptions(): BackgroundSyncOptions? {
    if (!prefs.contains(KEY_INTERVAL_MS)) return null
    return BackgroundSyncOptions(
      minimumIntervalMs = prefs.getLong(KEY_INTERVAL_MS, MIN_INTERVAL_MS).toDouble(),
      requiresCharging = prefs.getBoolean(KEY_REQ_CHARGING, false),
      requiresUnmeteredNetwork = prefs.getBoolean(KEY_REQ_UNMETERED, false),
      requiresDeviceIdle = if (prefs.contains(KEY_REQ_IDLE)) {
        prefs.getBoolean(KEY_REQ_IDLE, false)
      } else {
        null
      },
      taskIdentifier = prefs.getString(KEY_TASK_ID, null),
    )
  }

  /**
   * Register the periodic flush job and persist a flag so we can re-arm it
   * on boot.
   *
   * @throws SyncException with [SyncErrorCode.BACKGROUND_TASK_REGISTRATION_FAILED]
   *   on WorkManager errors (typically due to a missing AndroidX initializer
   *   in the host app).
   */
  fun enable(options: BackgroundSyncOptions) {
    try {
      val intervalMs = options.minimumIntervalMs.toLong().coerceAtLeast(MIN_INTERVAL_MS)
      val constraints = buildConstraints(options)

      val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMs, TimeUnit.MILLISECONDS)
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .setInputData(Data.Builder().putBoolean(SyncWorker.KEY_PERIODIC, true).build())
        .build()

      workManager.enqueueUniquePeriodicWork(
        UNIQUE_PERIODIC,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
      )
      persist(options)
      prefs.edit().putBoolean(KEY_ENABLED, true).apply()
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to register periodic work", t)
      throw SyncException(
        SyncErrorCode.BACKGROUND_TASK_REGISTRATION_FAILED,
        t.message ?: "WorkManager registration failed",
        t,
      )
    }
  }

  fun disable() {
    runCatching { workManager.cancelUniqueWork(UNIQUE_PERIODIC) }
    prefs.edit().putBoolean(KEY_ENABLED, false).apply()
  }

  /**
   * Schedule a one-time flush. If [urgent] is true and the OS supports it
   * (API 31+), the request is marked as expedited (RUN_AS_NON_EXPEDITED_WORK_REQUEST
   * fallback for quota-exhausted scenarios).
   */
  fun scheduleOneTimeFlush(urgent: Boolean = false) {
    try {
      val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
      val builder = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
      if (urgent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
      }
      workManager.enqueueUniqueWork(UNIQUE_ONE_TIME, ExistingWorkPolicy.KEEP, builder.build())
    } catch (t: Throwable) {
      Log.e(TAG, "scheduleOneTimeFlush failed", t)
      throw SyncException(
        SyncErrorCode.BACKGROUND_TASK_REGISTRATION_FAILED,
        t.message ?: "OneTime WorkRequest registration failed",
        t,
      )
    }
  }

  /**
   * One-shot recovery scheduling, called by the bridge on construction and
   * by [com.margelo.nitro.syncprovider.work.BootCompletedReceiver] on boot.
   */
  fun scheduleRecovery() {
    try {
      val request = OneTimeWorkRequestBuilder<RecoveryWorker>().build()
      workManager.enqueueUniqueWork(UNIQUE_RECOVERY, ExistingWorkPolicy.KEEP, request)
    } catch (t: Throwable) {
      Log.e(TAG, "scheduleRecovery failed", t)
    }
  }

  /**
   * Re-register the periodic worker if the consumer previously enabled it.
   * Used by the recovery flow after a boot or process death.
   */
  fun maybeReschedulePeriodic() {
    if (!isEnabled()) return
    val opts = lastOptions() ?: return
    runCatching { enable(opts) }.onFailure {
      Log.e(TAG, "Failed to re-arm periodic work after recovery", it)
    }
  }

  private fun buildConstraints(options: BackgroundSyncOptions): Constraints {
    val networkType = when {
      options.requiresUnmeteredNetwork == true -> NetworkType.UNMETERED
      else -> NetworkType.CONNECTED
    }
    val builder = Constraints.Builder().setRequiredNetworkType(networkType)
    if (options.requiresCharging == true) builder.setRequiresCharging(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && options.requiresDeviceIdle == true) {
      builder.setRequiresDeviceIdle(true)
    }
    return builder.build()
  }

  private fun persist(options: BackgroundSyncOptions) {
    prefs.edit().apply {
      putLong(KEY_INTERVAL_MS, options.minimumIntervalMs.toLong())
      putBoolean(KEY_REQ_CHARGING, options.requiresCharging == true)
      putBoolean(KEY_REQ_UNMETERED, options.requiresUnmeteredNetwork == true)
      if (options.requiresDeviceIdle != null) {
        putBoolean(KEY_REQ_IDLE, options.requiresDeviceIdle)
      } else {
        remove(KEY_REQ_IDLE)
      }
      if (options.taskIdentifier != null) putString(KEY_TASK_ID, options.taskIdentifier) else remove(KEY_TASK_ID)
    }.apply()
  }

  companion object {
    private const val TAG = "BackgroundSyncManager"
    private const val PREFS_NAME = "syncprovider.bgsync.prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL_MS = "intervalMs"
    private const val KEY_REQ_CHARGING = "requiresCharging"
    private const val KEY_REQ_UNMETERED = "requiresUnmeteredNetwork"
    private const val KEY_REQ_IDLE = "requiresDeviceIdle"
    private const val KEY_TASK_ID = "taskIdentifier"

    /** WorkManager refuses to register periodic work below 15 minutes. */
    const val MIN_INTERVAL_MS: Long = 15L * 60L * 1000L

    /* Unique work names. Documented in CLAUDE.md / agent memory. */
    const val UNIQUE_PERIODIC = "syncprovider-flush-periodic"
    const val UNIQUE_ONE_TIME = "syncprovider-flush-now"
    const val UNIQUE_RECOVERY = "syncprovider-recovery"
  }
}
