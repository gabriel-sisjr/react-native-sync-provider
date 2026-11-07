package com.margelo.nitro.syncprovider.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.margelo.nitro.syncprovider.BackoffStrategy
import com.margelo.nitro.syncprovider.NetworkQuality
import com.margelo.nitro.syncprovider.RetryPolicy
import com.margelo.nitro.syncprovider.SyncOptions
import com.margelo.nitro.syncprovider.SyncStrategy
import com.margelo.nitro.syncprovider.util.JsonMap

/** Where the active configuration came from. */
internal enum class ConfigSource {
  /** Nothing was ever configured. */
  DEFAULTS,

  /** Restored from disk: this process has not heard from JS yet. */
  PERSISTED,

  /** Set by `configureSync` in this process. */
  LIVE,
}

/**
 * The configuration a runtime operates under.
 *
 * Do not log instances: [options] holds the decrypted `defaultHeaders`.
 *
 * @property hasDefaultHeaders the consumer configured headers, whether or not they
 *   could be recovered.
 * @property secretUnavailable [hasDefaultHeaders] is true but the headers cannot be
 *   read. Dispatching without them would turn valid items into authorization failures.
 */
internal data class EngineConfig(
  val options: SyncOptions,
  val source: ConfigSource,
  val hasDefaultHeaders: Boolean = false,
  val secretUnavailable: Boolean = false,
)

/**
 * Persists what a process with no JS needs in order to act: every [SyncOptions] field
 * and the pause flag in plain SharedPreferences, and `defaultHeaders` through
 * [SecretStore], encrypted. Only the fact that headers exist is stored in the clear.
 *
 * Doubles are stored as raw bits so that values JSON cannot represent, such as an
 * infinite `maxDelayMs`, survive unchanged.
 *
 * Writes use `commit()`. Callers are off the main thread, and a kill right after
 * `configureSync` or `pauseSync` must not lose the change.
 */
internal class SyncConfigStore(
  context: Context,
  private val secrets: SecretStore,
) {
  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** The persisted configuration, or `null` when `configureSync` never ran. */
  fun load(): EngineConfig? {
    val options = readOptions() ?: return null
    if (!prefs.getBoolean(KEY_HAS_DEFAULT_HEADERS, false)) {
      return EngineConfig(options, ConfigSource.PERSISTED)
    }
    val headers = (secrets.read() as? SecretRead.Present)?.let { JsonMap.decode(it.value) }
    return EngineConfig(
      options = options.copy(defaultHeaders = headers),
      source = ConfigSource.PERSISTED,
      hasDefaultHeaders = true,
      secretUnavailable = headers == null,
    )
  }

  /**
   * Replace the persisted configuration. Options without `defaultHeaders` delete the
   * stored secret, which is how a consumer clears credentials at logout.
   *
   * When headers cannot be encrypted, the previous secret is deleted as well: a cold
   * worker must fail safe, never authenticate with a credential that was replaced.
   */
  fun save(options: SyncOptions) {
    val headers = JsonMap.encode(options.defaultHeaders)
    if (headers == null) {
      secrets.delete()
    } else if (!secrets.write(headers)) {
      secrets.delete()
      Log.w(TAG, "Default headers could not be stored; cold workers will not dispatch.")
    }
    writeOptions(options, hasDefaultHeaders = headers != null)
  }

  fun isPaused(): Boolean = prefs.getBoolean(KEY_PAUSED, false)

  fun setPaused(paused: Boolean) {
    prefs.edit().putBoolean(KEY_PAUSED, paused).commit()
  }

  private fun writeOptions(options: SyncOptions, hasDefaultHeaders: Boolean) {
    val policy = options.retryPolicy
    prefs.edit().apply {
      putString(KEY_STRATEGY, options.strategy.name)
      putDouble(KEY_MAX_ATTEMPTS, policy.maxAttempts)
      putString(KEY_BACKOFF, policy.backoff.name)
      putDouble(KEY_BASE_DELAY_MS, policy.baseDelayMs)
      putDouble(KEY_MAX_DELAY_MS, policy.maxDelayMs)
      putBoolean(KEY_JITTER, policy.jitter)
      putString(KEY_RETRY_STATUS_CODES, policy.retryOnStatusCodes.joinToString(STATUS_CODE_SEPARATOR))
      putOptionalDouble(KEY_BATCH_SIZE, options.batchSize)
      putOptionalDouble(KEY_REQUEST_TIMEOUT_MS, options.requestTimeoutMs)
      putOptionalDouble(KEY_MAX_QUEUE_SIZE, options.maxQueueSize)
      if (options.persistQueue != null) {
        putBoolean(KEY_PERSIST_QUEUE, options.persistQueue)
      } else {
        remove(KEY_PERSIST_QUEUE)
      }
      putOptionalString(KEY_MIN_NETWORK_QUALITY, options.minNetworkQuality?.name)
      putOptionalString(KEY_HEALTH_CHECK_URL, options.healthCheckUrl)
      putOptionalDouble(KEY_HEALTH_CHECK_TTL_MS, options.healthCheckTtlMs)
      putOptionalDouble(KEY_HEALTH_CHECK_BACKOFF_MS, options.healthCheckBackoffMs)
      putBoolean(KEY_HAS_DEFAULT_HEADERS, hasDefaultHeaders)
    }.commit()
  }

  private fun readOptions(): SyncOptions? {
    val strategy = prefs.getString(KEY_STRATEGY, null) ?: return null
    return runCatching {
      SyncOptions(
        strategy = SyncStrategy.valueOf(strategy),
        retryPolicy = RetryPolicy(
          maxAttempts = readDouble(KEY_MAX_ATTEMPTS),
          backoff = BackoffStrategy.valueOf(prefs.getString(KEY_BACKOFF, null).orEmpty()),
          baseDelayMs = readDouble(KEY_BASE_DELAY_MS),
          maxDelayMs = readDouble(KEY_MAX_DELAY_MS),
          jitter = prefs.getBoolean(KEY_JITTER, false),
          retryOnStatusCodes = readStatusCodes(),
        ),
        batchSize = readOptionalDouble(KEY_BATCH_SIZE),
        requestTimeoutMs = readOptionalDouble(KEY_REQUEST_TIMEOUT_MS),
        maxQueueSize = readOptionalDouble(KEY_MAX_QUEUE_SIZE),
        persistQueue = if (prefs.contains(KEY_PERSIST_QUEUE)) prefs.getBoolean(KEY_PERSIST_QUEUE, false) else null,
        defaultHeaders = null,
        minNetworkQuality = readNetworkQuality(),
        healthCheckUrl = prefs.getString(KEY_HEALTH_CHECK_URL, null),
        healthCheckTtlMs = readOptionalDouble(KEY_HEALTH_CHECK_TTL_MS),
        healthCheckBackoffMs = readOptionalDouble(KEY_HEALTH_CHECK_BACKOFF_MS),
      )
    }.onFailure {
      Log.w(TAG, "Persisted configuration is unreadable; using defaults: ${it.javaClass.simpleName}")
    }.getOrNull()
  }

  private fun readStatusCodes(): DoubleArray =
    prefs.getString(KEY_RETRY_STATUS_CODES, null)
      .orEmpty()
      .split(STATUS_CODE_SEPARATOR)
      .filter { it.isNotEmpty() }
      .map { it.toDouble() }
      .toDoubleArray()

  /**
   * Unlike the required enums, an unreadable name only drops this field: it reads as
   * unset, which means no gate, instead of discarding the whole configuration.
   */
  private fun readNetworkQuality(): NetworkQuality? {
    val name = prefs.getString(KEY_MIN_NETWORK_QUALITY, null) ?: return null
    val quality = NetworkQuality.entries.firstOrNull { it.name == name }
    if (quality == null) Log.w(TAG, "Persisted minNetworkQuality is unreadable; leaving it unset.")
    return quality
  }

  private fun readDouble(key: String): Double =
    readOptionalDouble(key) ?: throw IllegalStateException("Missing $key")

  private fun readOptionalDouble(key: String): Double? =
    if (prefs.contains(key)) Double.fromBits(prefs.getLong(key, 0L)) else null

  private fun SharedPreferences.Editor.putDouble(key: String, value: Double) {
    putLong(key, value.toRawBits())
  }

  private fun SharedPreferences.Editor.putOptionalDouble(key: String, value: Double?) {
    if (value != null) putDouble(key, value) else remove(key)
  }

  private fun SharedPreferences.Editor.putOptionalString(key: String, value: String?) {
    if (value != null) putString(key, value) else remove(key)
  }

  private companion object {
    const val TAG = "SyncConfigStore"
    const val PREFS_NAME = "syncprovider.config.prefs"
    const val STATUS_CODE_SEPARATOR = ","

    const val KEY_STRATEGY = "strategy"
    const val KEY_MAX_ATTEMPTS = "retry.maxAttempts"
    const val KEY_BACKOFF = "retry.backoff"
    const val KEY_BASE_DELAY_MS = "retry.baseDelayMs"
    const val KEY_MAX_DELAY_MS = "retry.maxDelayMs"
    const val KEY_JITTER = "retry.jitter"
    const val KEY_RETRY_STATUS_CODES = "retry.retryOnStatusCodes"
    const val KEY_BATCH_SIZE = "batchSize"
    const val KEY_REQUEST_TIMEOUT_MS = "requestTimeoutMs"
    const val KEY_MAX_QUEUE_SIZE = "maxQueueSize"
    const val KEY_PERSIST_QUEUE = "persistQueue"
    const val KEY_MIN_NETWORK_QUALITY = "minNetworkQuality"
    const val KEY_HEALTH_CHECK_URL = "healthCheckUrl"
    const val KEY_HEALTH_CHECK_TTL_MS = "healthCheckTtlMs"
    const val KEY_HEALTH_CHECK_BACKOFF_MS = "healthCheckBackoffMs"
    const val KEY_HAS_DEFAULT_HEADERS = "hasDefaultHeaders"
    const val KEY_PAUSED = "paused"
  }
}
