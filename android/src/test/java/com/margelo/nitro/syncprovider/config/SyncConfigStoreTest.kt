package com.margelo.nitro.syncprovider.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.BackoffStrategy
import com.margelo.nitro.syncprovider.NetworkQuality
import com.margelo.nitro.syncprovider.SyncOptions
import com.margelo.nitro.syncprovider.SyncStrategy
import com.margelo.nitro.syncprovider.testutil.InMemorySecretStore
import com.margelo.nitro.syncprovider.testutil.TestData
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class SyncConfigStoreTest {

  private lateinit var context: Context
  private val secrets = InMemorySecretStore()

  private val store: SyncConfigStore
    get() = SyncConfigStore(context, secrets)

  private val headers = mapOf("Authorization" to "Bearer sensitive-token", "X-Tenant" to "42")

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
  }

  private fun fullOptions(defaultHeaders: Map<String, String>? = null): SyncOptions = TestData.syncOptions(
    strategy = SyncStrategy.OPPORTUNISTIC,
    retryPolicy = TestData.retryPolicy(
      maxAttempts = 9,
      backoff = BackoffStrategy.FIBONACCI,
      baseDelayMs = 250L,
      maxDelayMs = 120_000L,
      jitter = true,
      retryOnStatusCodes = intArrayOf(409, 503),
    ),
    batchSize = 7,
    requestTimeoutMs = 12_000L,
    maxQueueSize = 321,
    persistQueue = false,
    defaultHeaders = defaultHeaders,
    minNetworkQuality = NetworkQuality.MODERATE,
    healthCheckUrl = "https://api.example.com/health",
    healthCheckTtlMs = 0.0,
    healthCheckBackoffMs = 15_000.0,
  )

  private fun prefs() =
    context.getSharedPreferences("syncprovider.config.prefs", Context.MODE_PRIVATE)

  @Test
  fun `nothing is loaded before the first save`() {
    assertThat(store.load()).isNull()
  }

  @Test
  fun `every option survives a round trip`() {
    val options = fullOptions(headers)

    store.save(options)

    val loaded = store.load()!!
    assertThat(loaded.options).isEqualTo(options)
    assertThat(loaded.source).isEqualTo(ConfigSource.PERSISTED)
    assertThat(loaded.hasDefaultHeaders).isTrue()
    assertThat(loaded.secretUnavailable).isFalse()
  }

  @Test
  fun `unset optional fields stay unset`() {
    val options = TestData.syncOptions(
      batchSize = null,
      requestTimeoutMs = null,
      maxQueueSize = null,
      persistQueue = null,
    )

    store.save(options)

    assertThat(store.load()!!.options).isEqualTo(options)
  }

  @Test
  fun `a value JSON could not represent survives unchanged`() {
    val policy = TestData.retryPolicy().copy(maxDelayMs = Double.POSITIVE_INFINITY)

    store.save(TestData.syncOptions(retryPolicy = policy))

    assertThat(store.load()!!.options.retryPolicy.maxDelayMs).isEqualTo(Double.POSITIVE_INFINITY)
  }

  @Test
  fun `an empty list of retry status codes survives`() {
    val policy = TestData.retryPolicy(retryOnStatusCodes = intArrayOf())

    store.save(TestData.syncOptions(retryPolicy = policy))

    assertThat(store.load()!!.options.retryPolicy.retryOnStatusCodes).isEmpty()
  }

  @Test
  fun `a later save replaces an optional field with unset`() {
    store.save(fullOptions())

    store.save(TestData.syncOptions(batchSize = null, persistQueue = null))

    val options = store.load()!!.options
    assertThat(options.batchSize).isNull()
    assertThat(options.persistQueue).isNull()
    assertThat(options.minNetworkQuality).isNull()
    assertThat(options.healthCheckUrl).isNull()
    assertThat(options.healthCheckTtlMs).isNull()
    assertThat(options.healthCheckBackoffMs).isNull()
  }

  @Test
  fun `a record saved before the gate and health-check options existed loads without them`() {
    store.save(fullOptions())
    // What an older version left on disk: every key except these four.
    prefs().edit()
      .remove("minNetworkQuality")
      .remove("healthCheckUrl")
      .remove("healthCheckTtlMs")
      .remove("healthCheckBackoffMs")
      .commit()

    val options = store.load()!!.options

    assertThat(options).isEqualTo(
      fullOptions().copy(
        minNetworkQuality = null,
        healthCheckUrl = null,
        healthCheckTtlMs = null,
        healthCheckBackoffMs = null,
      ),
    )
  }

  @Test
  fun `an unreadable minNetworkQuality is left unset and keeps the rest of the record`() {
    store.save(fullOptions())
    prefs().edit().putString("minNetworkQuality", "SUPERB").commit()

    val options = store.load()!!.options

    assertThat(options).isEqualTo(fullOptions().copy(minNetworkQuality = null))
  }

  @Test
  fun `header values reach the secret store and never the plain preferences`() {
    store.save(fullOptions(headers))

    assertThat(secrets.stored).contains("sensitive-token")
    val plain = context.getSharedPreferences("syncprovider.config.prefs", Context.MODE_PRIVATE).all
    assertThat(plain.toString()).doesNotContain("sensitive-token")
    assertThat(plain.toString()).doesNotContain("Authorization")
    assertThat(plain["hasDefaultHeaders"]).isEqualTo(true)
  }

  @Test
  fun `saving without headers deletes the secret, which is the logout path`() {
    store.save(fullOptions(headers))

    store.save(fullOptions(defaultHeaders = null))

    assertThat(secrets.stored).isNull()
    val loaded = store.load()!!
    assertThat(loaded.hasDefaultHeaders).isFalse()
    assertThat(loaded.options.defaultHeaders).isNull()
  }

  @Test
  fun `empty headers count as no headers`() {
    store.save(fullOptions(headers))

    store.save(fullOptions(defaultHeaders = emptyMap()))

    assertThat(secrets.stored).isNull()
    assertThat(store.load()!!.hasDefaultHeaders).isFalse()
  }

  @Test
  fun `a secret that cannot be read is reported rather than dropped`() {
    store.save(fullOptions(headers))
    secrets.failReads = true

    val loaded = store.load()!!

    assertThat(loaded.hasDefaultHeaders).isTrue()
    assertThat(loaded.secretUnavailable).isTrue()
    assertThat(loaded.options.defaultHeaders).isNull()
    // The non-secret options still load.
    assertThat(loaded.options.retryPolicy.maxAttempts).isEqualTo(9.0)
  }

  @Test
  fun `a secret that vanished is reported as unavailable`() {
    store.save(fullOptions(headers))
    secrets.delete()

    assertThat(store.load()!!.secretUnavailable).isTrue()
  }

  @Test
  fun `a secret that is not valid JSON is reported as unavailable`() {
    store.save(fullOptions(headers))
    secrets.write("not json")

    assertThat(store.load()!!.secretUnavailable).isTrue()
  }

  @Test
  fun `headers that cannot be stored drop the old credential and fail safe`() {
    store.save(fullOptions(mapOf("Authorization" to "Bearer previous-user")))
    secrets.failWrites = true

    store.save(fullOptions(headers))

    // Falling back to the previous credential would authenticate as the wrong user.
    assertThat(secrets.stored).isNull()
    val loaded = store.load()!!
    assertThat(loaded.hasDefaultHeaders).isTrue()
    assertThat(loaded.secretUnavailable).isTrue()
  }

  @Test
  fun `an unreadable record is treated as no record`() {
    store.save(fullOptions())
    context.getSharedPreferences("syncprovider.config.prefs", Context.MODE_PRIVATE)
      .edit().putString("strategy", "NOT_A_STRATEGY").commit()

    assertThat(store.load()).isNull()
  }

  @Test
  fun `the pause flag survives a new store and is independent of the options`() {
    assertThat(store.isPaused()).isFalse()

    store.setPaused(true)
    store.save(fullOptions())
    assertThat(store.isPaused()).isTrue()

    store.setPaused(false)
    assertThat(store.isPaused()).isFalse()
  }

  @Test
  fun `a loaded secret does not leak through toString`() {
    secrets.write("sensitive-token")

    assertThat(secrets.read().toString()).doesNotContain("sensitive-token")
  }
}
