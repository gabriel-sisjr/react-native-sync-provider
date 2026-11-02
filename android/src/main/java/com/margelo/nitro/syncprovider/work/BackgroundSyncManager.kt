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
 * - `syncprovider-flush-now` — [OneTimeWorkRequest], the opt-in retry kick.
 * - `syncprovider-recovery` — one-shot recovery on app launch / boot.
 */
internal class BackgroundSyncManager(context: Context) {

  private val appContext = context.applicationContext
  private val prefs: SharedPreferences =
    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val workManager: WorkManager
    get() = WorkManager.getInstance(appContext)

  fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

  /** Whether the consumer opted out of all OS-scheduled dispatch. Survives process death. */
  fun isForegroundOnly(): Boolean = prefs.getBoolean(KEY_FOREGROUND_ONLY, false)

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
      foregroundOnly = prefs.getBoolean(KEY_FOREGROUND_ONLY, false),
      taskIdentifier = prefs.getString(KEY_TASK_ID, null),
    )
  }

  /**
   * Register the periodic flush job and persist a flag so we can re-arm it
   * on boot.
   *
   * **`foregroundOnly` handling:** when [BackgroundSyncOptions.foregroundOnly]
   * is `true` the consumer opts out of OS-scheduled background work entirely. In that
   * case we register **no** periodic work and proactively cancel both the periodic and
   * one-time unique works (clearing anything a previous `enable` left armed), but we
   * still [persist] the options so the flag survives process death / reboot.
   *
   * **`KEY_ENABLED` semantics:** we deliberately keep `KEY_ENABLED = true` even in the
   * `foregroundOnly` branch. Calling `enableBackgroundSync` expresses the consumer's
   * *intent* to have background sync on; `foregroundOnly` governs only *whether* the OS
   * schedules work, not *whether* the feature is conceptually enabled. Therefore
   * `isBackgroundSyncEnabled()` reflects intent (returns `true`) while no OS work runs.
   * This keeps the flag composable — flipping `foregroundOnly` back to `false` via a
   * later `enable` re-arms the periodic job without a separate enable step — and leaves
   * `disable()` (`KEY_ENABLED = false`) as the single explicit "off" switch.
   *
   * @throws SyncException with [SyncErrorCode.BACKGROUND_TASK_REGISTRATION_FAILED]
   *   on WorkManager errors (typically due to a missing AndroidX initializer
   *   in the host app).
   */
  fun enable(options: BackgroundSyncOptions) {
    try {
      if (options.foregroundOnly == true) {
        // Publish the flag before cancelling: a cycle ending in between must see it,
        // or it could re-arm a kick after the cancel below.
        persist(options)
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        workManager.cancelUniqueWork(UNIQUE_PERIODIC)
        workManager.cancelUniqueWork(UNIQUE_ONE_TIME)
        return
      }

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

  /**
   * Turn background sync off, including any pending retry kick. The flag is published
   * first, so that a cycle ending in between cannot re-arm a kick after the cancel.
   */
  fun disable() {
    prefs.edit().putBoolean(KEY_ENABLED, false).apply()
    runCatching {
      workManager.cancelUniqueWork(UNIQUE_PERIODIC)
      workManager.cancelUniqueWork(UNIQUE_ONE_TIME)
    }
  }

  /** Drop a pending retry kick, for when dispatch must stop but stays enabled. */
  fun cancelRetryKick() {
    runCatching { workManager.cancelUniqueWork(UNIQUE_ONE_TIME) }
  }

  /**
   * Arm the opt-in retry kick: a delayed one-time flush that survives process death.
   * Does nothing unless the consumer enabled background sync and did not ask for
   * `foregroundOnly`. It honors the same constraints as the periodic work.
   *
   * [ExistingWorkPolicy.REPLACE] keeps one kick armed, always for the latest deadline.
   * Delays are computed from absolute deadlines, so re-arming never postpones a wake.
   *
   * A failure here is only logged: the callers run at the end of a flush cycle that
   * already completed, and it must not be rejected for this.
   */
  fun scheduleOneTimeFlush(initialDelayMs: Long = 0L) {
    if (!isEnabled() || isForegroundOnly()) return
    try {
      val builder = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(lastOptions()?.let(::buildConstraints) ?: defaultConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
      if (initialDelayMs > 0L) {
        builder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
      }
      workManager.enqueueUniqueWork(UNIQUE_ONE_TIME, ExistingWorkPolicy.REPLACE, builder.build())
    } catch (t: Throwable) {
      Log.e(TAG, "scheduleOneTimeFlush failed", t)
    }
  }

  /**
   * One-shot recovery scheduling, called when a JS engine attaches and by
   * [com.margelo.nitro.syncprovider.work.BootCompletedReceiver] on boot.
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
    // Under foregroundOnly, boot and recovery must not re-arm OS-scheduled work.
    // Note: this gates only the *scheduling* re-arm. Data-integrity recovery
    // (RecoveryWorker -> storage.resetInFlight()) runs independently and is
    // intentionally untouched here.
    if (opts.foregroundOnly == true) return
    runCatching { enable(opts) }.onFailure {
      Log.e(TAG, "Failed to re-arm periodic work after recovery", it)
    }
  }

  private fun defaultConstraints(): Constraints =
    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

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
      // A plain boolean (false when null), so readers need no tri-state check.
      putBoolean(KEY_FOREGROUND_ONLY, options.foregroundOnly == true)
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
    private const val KEY_FOREGROUND_ONLY = "foregroundOnly"
    private const val KEY_TASK_ID = "taskIdentifier"

    /** WorkManager refuses to register periodic work below 15 minutes. */
    const val MIN_INTERVAL_MS: Long = 15L * 60L * 1000L

    /* Unique work names. */
    const val UNIQUE_PERIODIC = "syncprovider-flush-periodic"
    const val UNIQUE_ONE_TIME = "syncprovider-flush-now"
    const val UNIQUE_RECOVERY = "syncprovider-recovery"
  }
}
