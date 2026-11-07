package com.margelo.nitro.syncprovider

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.margelo.nitro.syncprovider.config.ConfigSource
import com.margelo.nitro.syncprovider.config.SecretStore
import com.margelo.nitro.syncprovider.connectivity.ConnectivityMonitor
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.error.SyncException
import com.margelo.nitro.syncprovider.http.SyncDispatcher
import com.margelo.nitro.syncprovider.retry.RetryTimer
import com.margelo.nitro.syncprovider.testutil.InMemorySecretStore
import com.margelo.nitro.syncprovider.testutil.RuntimeTestSupport
import com.margelo.nitro.syncprovider.testutil.TestData
import com.margelo.nitro.syncprovider.work.BackgroundSyncManager
import com.margelo.nitro.syncprovider.work.RecoveryWorker
import com.margelo.nitro.syncprovider.work.SyncWorker
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The runtime wired for real: SQLite, SharedPreferences and a test WorkManager. Only
 * the Keystore is faked, which neither Robolectric nor a JVM can provide.
 *
 * [restartProcess] drops the runtime and keeps what is on disk, which is what a worker
 * started with no JS sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class SyncProviderRuntimeTest {

  private lateinit var context: Context
  private lateinit var server: MockWebServer
  private lateinit var runtime: SyncProviderRuntime
  private val secrets = InMemorySecretStore()

  /** Set by [recordWakes], whose constructor mocks [tearDown] undoes. */
  private var recordingWakes = false

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
    RuntimeTestSupport.reset()
    server = MockWebServer().apply { start() }
    runtime = SyncProviderRuntime.bootstrap(context, secrets)
  }

  @After
  fun tearDown() {
    RuntimeTestSupport.awaitRecovery(context)
    RuntimeTestSupport.reset()
    server.shutdown()
    if (recordingWakes) unmockkConstructor(RetryTimer::class, BackgroundSyncManager::class)
  }

  private fun restartProcess() {
    runtime.shutdown()
    runtime = SyncProviderRuntime.bootstrap(context, secrets)
  }

  private suspend fun runWorker(): ListenableWorker.Result =
    TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

  private suspend fun runPeriodicWorker(): ListenableWorker.Result =
    TestListenableWorkerBuilder<SyncWorker>(context)
      .setInputData(Data.Builder().putBoolean(SyncWorker.KEY_PERIODIC, true).build())
      .build()
      .doWork()

  private fun oneTimeWork(): List<WorkInfo> = active(BackgroundSyncManager.UNIQUE_ONE_TIME)

  private fun active(name: String): List<WorkInfo> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
      .filter { !it.state.isFinished }

  private fun manager(): ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)

  private fun goOnline() {
    val capabilities = ShadowNetworkCapabilities.newInstance()
    shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    shadowOf(manager()).setNetworkCapabilities(manager().activeNetwork, capabilities)
  }

  private fun goOffline() {
    shadowOf(manager()).setNetworkCapabilities(manager().activeNetwork, ShadowNetworkCapabilities.newInstance())
  }

  private fun rejectingWithoutToken(): Dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse =
      MockResponse().setResponseCode(if (request.getHeader("Authorization") == TOKEN) 200 else 401)
  }

  private suspend fun insert(
    id: String,
    attempts: Int = 0,
    nextRetryAt: Long = 0L,
    status: String = SyncItemEntity.STATUS_PENDING,
    createdAt: Long = 1L,
  ) {
    runtime.database.syncItemDao().insert(
      TestData.entity(
        id = id,
        url = server.url("/$id").toString(),
        attempts = attempts,
        nextRetryAt = nextRetryAt,
        status = status,
        createdAt = createdAt,
      ),
    )
  }

  private suspend fun row(id: String): SyncItemEntity? = runtime.database.syncItemDao().findById(id)

  private suspend fun awaitGone(id: String) {
    val gone = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
      while (row(id) != null) delay(10L)
      true
    }
    assertThat(gone).isTrue()
  }

  /** Attach an engine and wait out the recovery that arms the first wake. */
  private fun attachEngineAndRecover() {
    runtime.attachEngine()
    RuntimeTestSupport.awaitRecovery(context)
  }

  /** Inserts a row whose wake is far enough away that nothing can fire during a test. */
  private suspend fun insertFarCooling(id: String = "cooling") {
    insert(id, attempts = 1, nextRetryAt = System.currentTimeMillis() + FAR_MS)
  }

  /* -------------------------- the opt-in WorkManager kick --------------------- */

  @Test
  fun `an app that never enabled background sync gets no background work`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    insert("x")

    runtime.dispatcher.flush()

    assertThat(row("x")!!.attempts).isEqualTo(1)
    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `an enabled app gets a kick after a retriable failure`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("x")

    runtime.dispatcher.flush()

    assertThat(oneTimeWork()).hasSize(1)
  }

  @Test
  fun `the kick is delayed by the time left until the deadline`() = runBlocking<Unit> {
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insertFarCooling()

    runtime.armRetryWake()

    val spec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
      .getWorkSpec(oneTimeWork().single().id.toString())!!
    assertThat(spec.initialDelay).isIn(Range.closed(FAR_MS - 60_000L, FAR_MS))
  }

  @Test
  fun `a cooldown that elapsed during the cycle still gets a kick`() = runBlocking<Unit> {
    // The default policy's first backoff is shorter than this request.
    server.enqueue(MockResponse().setResponseCode(503).setHeadersDelay(300, TimeUnit.MILLISECONDS))
    server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(600, TimeUnit.MILLISECONDS))
    runtime.configure(
      TestData.syncOptions(retryPolicy = TestData.retryPolicy(baseDelayMs = 100L, maxDelayMs = 100L)),
    )
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("failing", createdAt = 1L)
    insert("slow-sibling", createdAt = 2L)

    runtime.dispatcher.flush()

    assertThat(oneTimeWork()).hasSize(1)
  }

  @Test
  fun `no kick is armed under foregroundOnly`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))
    insert("x")

    runtime.dispatcher.flush()

    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `no kick is armed under the MANUAL strategy, and configuring it cancels one`() = runBlocking<Unit> {
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("x", attempts = 1, nextRetryAt = System.currentTimeMillis() + 60_000L)
    runtime.armRetryWake()
    assertThat(oneTimeWork()).hasSize(1)

    runtime.configure(TestData.syncOptions(strategy = SyncStrategy.MANUAL))
    runtime.armRetryWake()

    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `no kick is armed while paused, and pausing cancels one`() = runBlocking<Unit> {
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("x", attempts = 1, nextRetryAt = System.currentTimeMillis() + 60_000L)
    runtime.armRetryWake()
    assertThat(oneTimeWork()).hasSize(1)

    runtime.pause()
    runtime.armRetryWake()

    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `disabling background sync stops the kick for good`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("x")
    runtime.dispatcher.flush()
    assertThat(oneTimeWork()).hasSize(1)

    runtime.backgroundSyncManager.disable()
    assertThat(oneTimeWork()).isEmpty()

    runtime.dispatcher.flush() // The row is cooling, so this cycle is empty.
    assertThat(oneTimeWork()).isEmpty()
  }

  /* ----------------------------- the worker's gates --------------------------- */

  @Test
  fun `a worker dispatches nothing while the engine is paused`() = runBlocking<Unit> {
    insert("x")
    runtime.pause()

    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("x")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
  }

  @Test
  fun `a worker dispatches nothing under foregroundOnly`() = runBlocking<Unit> {
    insert("x")
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun `a periodic worker dispatches under MANUAL, while the timer and the kick stay unarmed`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    runtime.configure(TestData.syncOptions(strategy = SyncStrategy.MANUAL))
    attachEngineAndRecover()
    insert("x")

    val result = runPeriodicWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(1)
    // The row is cooling, so any wake that was allowed would have armed for it.
    assertThat(row("x")!!.nextRetryAt).isGreaterThan(0L)
    assertThat(runtime.isRetryTimerArmed).isFalse()
    assertThat(oneTimeWork()).isEmpty()
  }

  /* ---------------------------- the in-process timer -------------------------- */

  @Test
  fun `engine start wakes for a retry that came due while the app was closed`() = runBlocking<Unit> {
    goOnline()
    server.enqueue(MockResponse().setResponseCode(200))
    insert("due", attempts = 1, nextRetryAt = 1L)

    runtime.attachEngine()

    awaitGone("due")
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `configureSync arms the timer`() = runBlocking<Unit> {
    goOnline()
    server.enqueue(MockResponse().setResponseCode(200))
    // Recovery must be over: its own wake would otherwise pick this row up.
    attachEngineAndRecover()
    insert("cooling", attempts = 1, nextRetryAt = System.currentTimeMillis() + 100L)

    runtime.configure(TestData.syncOptions())

    awaitGone("cooling")
  }

  @Test
  fun `the timer runs under foregroundOnly, because it is in-process`() = runBlocking<Unit> {
    goOnline()
    server.enqueue(MockResponse().setResponseCode(200))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))
    runtime.attachEngine()
    insert("cooling", attempts = 1, nextRetryAt = System.currentTimeMillis() + 100L)

    runtime.armRetryWake()

    awaitGone("cooling")
  }

  @Test
  fun `the timer stays off under the MANUAL strategy`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insertFarCooling()

    runtime.configure(TestData.syncOptions(strategy = SyncStrategy.MANUAL))

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `switching to the MANUAL strategy cancels an armed timer`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insertFarCooling()
    runtime.configure(TestData.syncOptions())
    assertThat(runtime.isRetryTimerArmed).isTrue()

    runtime.configure(TestData.syncOptions(strategy = SyncStrategy.MANUAL))

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `pausing cancels the timer`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insertFarCooling()
    runtime.armRetryWake()
    assertThat(runtime.isRetryTimerArmed).isTrue()

    runtime.pause()

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `the timer is not armed while paused`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insertFarCooling()
    runtime.pause()

    runtime.armRetryWake()

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `resuming dispatches never-attempted rows at once`() = runBlocking<Unit> {
    goOnline()
    server.enqueue(MockResponse().setResponseCode(200))
    runtime.attachEngine()
    runtime.pause()
    insert("new")

    runtime.resume()

    awaitGone("new")
  }

  @Test
  fun `detaching the last engine cancels the timer`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insertFarCooling()
    runtime.armRetryWake()
    assertThat(runtime.isRetryTimerArmed).isTrue()

    runtime.detachEngine()

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `a reload that attaches before detaching keeps the timer`() = runBlocking<Unit> {
    attachEngineAndRecover()
    runtime.attachEngine()
    insertFarCooling()
    runtime.armRetryWake()

    runtime.detachEngine()

    assertThat(runtime.isRetryTimerArmed).isTrue()
  }

  @Test
  fun `a process with no JS engine arms no timer`() = runBlocking<Unit> {
    insertFarCooling()

    runtime.armRetryWake()

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `a timer that fired offline flushes as soon as the network returns`() = runBlocking<Unit> {
    goOffline()
    server.enqueue(MockResponse().setResponseCode(200))
    insert("due", attempts = 1, nextRetryAt = 1L)
    runtime.attachEngine()
    delay(250L)
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("due")!!.attempts).isEqualTo(1)

    goOnline()
    val network = manager().activeNetwork!!
    shadowOf(manager()).networkCallbacks.forEach { it.onAvailable(network) }

    awaitGone("due")
    assertThat(server.requestCount).isEqualTo(1)
  }

  /* ----------------------------- cold-start config ---------------------------- */

  @Test
  fun `a runtime with nothing configured reports DEFAULTS`() {
    assertThat(runtime.configSource).isEqualTo(ConfigSource.DEFAULTS)
    assertThat(runtime.currentOptions()).isEqualTo(SyncProviderRuntime.defaultOptions())
  }

  @Test
  fun `configureSync in this process reports LIVE, and a restart reports PERSISTED`() = runBlocking {
    runtime.configure(TestData.syncOptions())
    assertThat(runtime.configSource).isEqualTo(ConfigSource.LIVE)

    restartProcess()

    assertThat(runtime.configSource).isEqualTo(ConfigSource.PERSISTED)
  }

  @Test
  fun `a configured maxAttempts survives a fresh runtime`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(retryPolicy = TestData.retryPolicy(maxAttempts = 10)))
    insert("budget", attempts = 6)
    server.enqueue(MockResponse().setResponseCode(200))

    restartProcess()
    runWorker()

    // With the default budget of 5, this row would fail without a single request.
    assertThat(runtime.currentOptions().retryPolicy.maxAttempts).isEqualTo(10.0)
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(row("budget")).isNull()
  }

  @Test
  fun `a cold worker honors the configured batchSize`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(batchSize = 2))
    repeat(3) { insert("row-$it", createdAt = it.toLong()) }
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200)
    }

    restartProcess()
    runWorker()

    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `a cold worker honors the configured requestTimeoutMs`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(requestTimeoutMs = 1_000L))
    insert("slow")
    val release = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        // Past the default 30s timeout, so only the configured one can end this early.
        release.await(40, TimeUnit.SECONDS)
        return MockResponse().setResponseCode(200)
      }
    }

    restartProcess()
    val startedAt = System.currentTimeMillis()
    runWorker()
    release.countDown()

    assertThat(System.currentTimeMillis() - startedAt).isLessThan(20_000L)
    assertThat(row("slow")!!.attempts).isEqualTo(1)
  }

  @Test
  fun `a cold periodic worker dispatches under a persisted MANUAL, which still gates the kick`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    runtime.configure(TestData.syncOptions(strategy = SyncStrategy.MANUAL))
    insert("x")

    restartProcess()
    runPeriodicWorker()

    assertThat(runtime.currentOptions().strategy).isEqualTo(SyncStrategy.MANUAL)
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(row("x")!!.nextRetryAt).isGreaterThan(0L)
    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `a fresh runtime applies the configured maxQueueSize`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(maxQueueSize = 321))

    restartProcess()

    assertThat(runtime.storage.maxQueueSize).isEqualTo(321)
  }

  /* --------------------------- cold-worker recovery --------------------------- */

  @Test
  fun `a cold worker dispatches a row a dead process left IN_FLIGHT`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(200))
    insert("orphan", status = SyncItemEntity.STATUS_IN_FLIGHT)

    restartProcess()
    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(row("orphan")).isNull()
  }

  @Test
  fun `a cold worker resets a row still cooling, without dispatching it`() = runBlocking<Unit> {
    val deadline = System.currentTimeMillis() + FAR_MS
    insert("cooling", attempts = 1, nextRetryAt = deadline, status = SyncItemEntity.STATUS_IN_FLIGHT)
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))

    restartProcess()
    runWorker()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("cooling")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row("cooling")!!.nextRetryAt).isEqualTo(deadline)
    // A row left IN_FLIGHT has no wake. Once reset, the cycle that follows arms one.
    assertThat(oneTimeWork()).hasSize(1)
  }

  @Test
  fun `a paused cold worker leaves an orphaned row alone`() = runBlocking<Unit> {
    insert("orphan", status = SyncItemEntity.STATUS_IN_FLIGHT)
    runtime.pause()

    restartProcess()
    runWorker()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("orphan")!!.status).isEqualTo(SyncItemEntity.STATUS_IN_FLIGHT)
  }

  @Test
  fun `a cold worker under foregroundOnly leaves an orphaned row alone`() = runBlocking<Unit> {
    insert("orphan", status = SyncItemEntity.STATUS_IN_FLIGHT)
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    restartProcess()
    runWorker()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("orphan")!!.status).isEqualTo(SyncItemEntity.STATUS_IN_FLIGHT)
  }

  @Test
  fun `a cold worker resets an orphaned row even when headers cannot be recovered`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    insert("orphan", status = SyncItemEntity.STATUS_IN_FLIGHT)
    secrets.failReads = true

    restartProcess()
    runWorker()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("orphan")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
  }

  @Test
  fun `a worker beside an attached engine leaves recovery to the engine`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insert("claimed", status = SyncItemEntity.STATUS_IN_FLIGHT)

    runWorker()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("claimed")!!.status).isEqualTo(SyncItemEntity.STATUS_IN_FLIGHT)
  }

  /* --------------------------------- pause lifetime ----------------------------- */

  @Test
  fun `a cold runtime stays paused and its worker is inert`() = runBlocking<Unit> {
    insert("x")
    runtime.pause()

    restartProcess()
    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(runtime.dispatcher.flush()).isEqualTo(SyncDispatcher.emptyResult())
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("x")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)

    restartProcess() // A runtime built for a worker leaves the flag for the next one.
    assertThat(runtime.isPaused).isTrue()
  }

  @Test
  fun `a cold recovery leaves the pause on and arms nothing`() = runBlocking<Unit> {
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insertFarCooling()
    runtime.pause()

    restartProcess()
    TestListenableWorkerBuilder<RecoveryWorker>(context).build().doWork()

    assertThat(runtime.isPaused).isTrue()
    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `a new JS engine clears the persisted pause, so a later cold worker dispatches`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(200))
    insert("x")
    runtime.pause()
    restartProcess()
    assertThat(runtime.isPaused).isTrue()

    attachEngineAndRecover()
    assertThat(runtime.isPaused).isFalse()

    restartProcess()
    assertThat(runtime.isPaused).isFalse()
    runWorker()
    assertThat(row("x")).isNull()
  }

  @Test
  fun `a new engine is unpaused before its recovery arms the first wake`() = runBlocking<Unit> {
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insertFarCooling()
    runtime.pause()
    mockkConstructor(BackgroundSyncManager::class)
    // Recovery runs at the instant it is scheduled, the worst case for whatever follows.
    every { anyConstructed<BackgroundSyncManager>().scheduleRecovery() } answers {
      runBlocking { TestListenableWorkerBuilder<RecoveryWorker>(context).build().doWork() }
    }
    try {
      restartProcess()

      runtime.attachEngine()

      assertThat(runtime.isRetryTimerArmed).isTrue()
      assertThat(oneTimeWork()).hasSize(1)
    } finally {
      unmockkConstructor(BackgroundSyncManager::class)
    }
  }

  @Test
  fun `an engine arriving after an inert worker wakes the queue in that process`() = runBlocking<Unit> {
    goOnline()
    server.enqueue(MockResponse().setResponseCode(200))
    insert("due", attempts = 1, nextRetryAt = 1L)
    runtime.pause()
    restartProcess()
    runWorker()
    assertThat(server.requestCount).isEqualTo(0)

    runtime.attachEngine()

    awaitGone("due")
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `a reload clears the pause even though the old engine never detached`() = runBlocking<Unit> {
    attachEngineAndRecover()
    runtime.pause()

    attachEngineAndRecover()

    assertThat(runtime.isPaused).isFalse()
  }

  @Test
  fun `a pause set after the engine attached holds until resumeSync`() = runBlocking<Unit> {
    attachEngineAndRecover()
    runtime.pause()
    restartProcess()
    assertThat(runtime.isPaused).isTrue()

    runtime.resume()
    restartProcess()

    assertThat(runtime.isPaused).isFalse()
  }

  /* ----------------------------- restored credentials ------------------------- */

  @Test
  fun `a cold worker authenticates with the persisted default headers`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    insert("auth")
    server.dispatcher = rejectingWithoutToken()

    restartProcess()
    runWorker()

    assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")).isEqualTo(TOKEN)
    assertThat(row("auth")).isNull()
  }

  @Test
  fun `a rejected persisted token leaves the item pending and the worker successful`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to "Bearer expired")))
    insert("auth")
    server.dispatcher = rejectingWithoutToken()

    restartProcess()
    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    val held = row("auth")!!
    assertThat(held.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(held.attempts).isEqualTo(0)
    assertThat(held.lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
    assertThat(held.nextRetryAt).isGreaterThan(System.currentTimeMillis())
  }

  @Test
  fun `a 401 stays terminal once JS has configured in this process`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to "Bearer expired")))
    restartProcess()
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to "Bearer expired")))
    insert("auth")
    server.dispatcher = rejectingWithoutToken()

    runtime.dispatcher.flush()

    assertThat(row("auth")!!.status).isEqualTo(SyncItemEntity.STATUS_FAILED)
  }

  @Test
  fun `a 401 stays terminal when no headers were ever persisted`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions())
    insert("auth")
    server.dispatcher = rejectingWithoutToken()

    restartProcess()
    runWorker()

    assertThat(row("auth")!!.status).isEqualTo(SyncItemEntity.STATUS_FAILED)
  }

  @Test
  fun `a logout cannot overtake a configureSync that is still being written`() = runBlocking<Unit> {
    val writing = CountDownLatch(1)
    val finishWrite = CountDownLatch(1)
    val slowSecrets = object : SecretStore by secrets {
      override fun write(value: String): Boolean {
        writing.countDown()
        finishWrite.await(20, TimeUnit.SECONDS)
        return secrets.write(value)
      }
    }
    runtime.shutdown()
    runtime = SyncProviderRuntime.bootstrap(context, slowSecrets)
    val login = async(Dispatchers.IO) {
      runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    }
    assertThat(writing.await(20, TimeUnit.SECONDS)).isTrue()

    val logout = async(Dispatchers.IO) { runtime.configure(TestData.syncOptions(defaultHeaders = null)) }
    delay(100L)

    // Had the logout taken effect in memory, its write would race this one for the disk.
    assertThat(runtime.currentOptions().defaultHeaders).containsKey("Authorization")
    finishWrite.countDown()
    login.await()
    logout.await()

    assertThat(runtime.currentOptions().defaultHeaders).isNull()
    assertThat(secrets.stored).isNull()
  }

  @Test
  fun `configuring without headers deletes them, which is the logout path`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    assertThat(secrets.stored).isNotNull()

    runtime.configure(TestData.syncOptions(defaultHeaders = null))
    assertThat(secrets.stored).isNull()

    restartProcess()
    assertThat(runtime.currentOptions().defaultHeaders).isNull()
  }

  @Test
  fun `headers that cannot be recovered dispatch nothing and the worker succeeds`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    insert("auth")
    secrets.failReads = true

    restartProcess()
    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("auth")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row("auth")!!.attempts).isEqualTo(0)
  }

  @Test
  fun `no wake is armed while headers cannot be recovered`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("due", attempts = 1, nextRetryAt = 1L)
    secrets.failReads = true

    restartProcess()
    runtime.armRetryWake()

    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `a worker recovers once the headers can be read again`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    insert("auth")
    server.dispatcher = rejectingWithoutToken()
    secrets.failReads = true
    restartProcess()
    runWorker()
    assertThat(server.requestCount).isEqualTo(0)

    secrets.failReads = false
    runWorker()

    assertThat(row("auth")).isNull()
  }

  @Test
  fun `configureSync lifts the block when headers could not be recovered`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    insert("auth")
    server.dispatcher = rejectingWithoutToken()
    secrets.failReads = true
    restartProcess()

    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    runtime.dispatcher.flush()

    assertThat(row("auth")).isNull()
  }

  /* ------------------------------ the credential hold ------------------------------ */

  /** The wakes armed since the last [clear], each as the instant it fires. */
  private class WakeLog {
    val timer = CopyOnWriteArrayList<Long>()
    val kick = CopyOnWriteArrayList<Long>()

    fun clear() {
      timer.clear()
      kick.clear()
    }
  }

  /**
   * Records every wake armed by a runtime built after this call. Both mechanisms take a
   * delay counted from the call, so the call's time anchors it.
   */
  private fun recordWakes(): WakeLog {
    val log = WakeLog()
    mockkConstructor(RetryTimer::class, BackgroundSyncManager::class)
    recordingWakes = true
    every { anyConstructed<RetryTimer>().arm(any()) } answers {
      log.timer += System.currentTimeMillis() + firstArg<Long>()
      callOriginal()
    }
    every { anyConstructed<BackgroundSyncManager>().scheduleOneTimeFlush(any()) } answers {
      log.kick += System.currentTimeMillis() + firstArg<Long>()
      callOriginal()
    }
    return log
  }

  /** When the armed kick fires: WorkManager counts its delay from when it was enqueued. */
  private fun kickFiresAt(): Long {
    val spec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
      .getWorkSpec(oneTimeWork().single().id.toString())!!
    return spec.lastEnqueueTime + spec.initialDelay
  }

  private suspend fun runRecovery(): ListenableWorker.Result =
    TestListenableWorkerBuilder<RecoveryWorker>(context).build().doWork()

  /**
   * A token restored from disk that the server rejects. Leaves one row held by it,
   * background sync enabled, and a row already due that would otherwise wake the queue
   * at once.
   *
   * @return when the hold ends: the deadline the rejected cycle wrote on its row.
   */
  private suspend fun holdRejectedCredential(): Long {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to "Bearer expired")))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    restartProcess() // The headers are now restored from disk.
    server.dispatcher = rejectingWithoutToken()
    insert("held", createdAt = 1L)

    runtime.dispatcher.flush()

    val holdUntil = row("held")!!.nextRetryAt
    assertThat(holdUntil).isGreaterThan(System.currentTimeMillis())
    insert("due", attempts = 1, nextRetryAt = 1L, createdAt = 2L)
    return holdUntil
  }

  /**
   * No wake armed since [wakes] was last cleared fires before [holdUntil], and neither
   * does the kick WorkManager holds now: no REPLACE brought it forward.
   */
  private fun assertHeldUntil(holdUntil: Long, wakes: WakeLog, timerArmed: Boolean = true) {
    assertWithMessage("timer armed").that(wakes.timer.isNotEmpty()).isEqualTo(timerArmed)
    assertWithMessage("early timer").that(wakes.timer.filter { it < holdUntil }).isEmpty()
    assertWithMessage("kick armed").that(wakes.kick).isNotEmpty()
    assertWithMessage("early kick").that(wakes.kick.filter { it < holdUntil }).isEmpty()
    assertWithMessage("kick in WorkManager").that(kickFiresAt()).isAtLeast(holdUntil)
  }

  @Test
  fun `resumeSync during a credential hold waits for it, though rows are due`() = runBlocking<Unit> {
    goOnline()
    val wakes = recordWakes()
    val holdUntil = holdRejectedCredential()
    insert("fresh", createdAt = 3L) // No cooldown, so resumeSync alone would wake for it.
    attachEngineAndRecover()
    runtime.pause()
    wakes.clear()

    runtime.resume()

    assertHeldUntil(holdUntil, wakes)
    delay(QUIET_MS)
    assertThat(server.requestCount).isEqualTo(1) // Only the request that was rejected.
  }

  @Test
  fun `a JS engine attaching or reloading during a credential hold waits for it`() = runBlocking<Unit> {
    goOnline()
    val wakes = recordWakes()
    val holdUntil = holdRejectedCredential()
    wakes.clear()

    attachEngineAndRecover() // The app starts in the process that met the rejection.
    assertHeldUntil(holdUntil, wakes)
    wakes.clear()
    attachEngineAndRecover() // A reload: the new engine attaches before the old one detaches.
    assertHeldUntil(holdUntil, wakes)

    delay(QUIET_MS)
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `a recovery in the same process during a credential hold waits for it`() = runBlocking<Unit> {
    goOnline()
    val wakes = recordWakes()
    val holdUntil = holdRejectedCredential()
    wakes.clear()

    runRecovery() // No JS engine in this process: the kick alone.
    assertHeldUntil(holdUntil, wakes, timerArmed = false)

    attachEngineAndRecover()
    wakes.clear()
    runRecovery() // Beside a JS engine: the timer too.
    assertHeldUntil(holdUntil, wakes)

    delay(QUIET_MS)
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `every configureSync lifts the credential hold, even with unchanged headers`() = runBlocking<Unit> {
    goOffline() // The wake that follows is armed, but not delivered.
    val wakes = recordWakes()
    val holdUntil = holdRejectedCredential()
    attachEngineAndRecover()
    assertHeldUntil(holdUntil, wakes)
    wakes.clear()

    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to "Bearer expired")))

    assertThat(wakes.timer.minOrNull()).isLessThan(holdUntil)
    assertThat(wakes.kick.minOrNull()).isLessThan(holdUntil)
    assertThat(kickFiresAt()).isLessThan(holdUntil)
  }

  @Test
  fun `during a credential hold no wake path REPLACEs the kick with an earlier one`() = runBlocking<Unit> {
    // Offline, an early timer cannot dispatch, and the hold that a new rejection would set
    // cannot hide an early kick.
    goOffline()
    val holdUntil = holdRejectedCredential()
    assertWithMessage("rejected cycle").that(kickFiresAt()).isAtLeast(holdUntil)

    suspend fun kickHeldAfter(path: String, arm: suspend () -> Unit) {
      arm()
      assertWithMessage(path).that(kickFiresAt()).isAtLeast(holdUntil)
    }
    kickHeldAfter("recovery") { runRecovery() }
    kickHeldAfter("engine attach") { attachEngineAndRecover() }
    kickHeldAfter("reload") { attachEngineAndRecover() }
    kickHeldAfter("resumeSync") {
      runtime.pause()
      runtime.resume()
    }
    kickHeldAfter("re-arm") { runtime.armRetryWake() }
  }

  @Test
  fun `a new process starts without the hold and spends one request to set it again`() = runBlocking<Unit> {
    val holdUntil = holdRejectedCredential()
    insert("also-due", attempts = 1, nextRetryAt = 1L, createdAt = 3L)

    restartProcess()
    runRecovery()

    // The hold is kept in memory only: the new process wakes for the due rows at once,
    // while the held row still waits for its own deadline.
    assertThat(kickFiresAt()).isLessThan(holdUntil)
    assertThat(row("held")!!.nextRetryAt).isEqualTo(holdUntil)

    runWorker()

    // Restored headers send one request first, and its rejection holds the rest again.
    assertThat(server.requestCount).isEqualTo(2)
    assertThat(row("also-due")!!.attempts).isEqualTo(1)
    assertThat(row("also-due")!!.lastErrorCode).isNull()
    assertThat(kickFiresAt()).isAtLeast(row("due")!!.nextRetryAt)
  }

  /* ----------------------------------- queue ---------------------------------- */

  @Test
  fun `queueSize counts what is waiting to be dispatched, cooling items included`() = runBlocking<Unit> {
    insert("new")
    insert("cooling", attempts = 1, nextRetryAt = System.currentTimeMillis() + 60_000L)
    insert("in-flight", status = SyncItemEntity.STATUS_IN_FLIGHT)
    insert("failed", status = SyncItemEntity.STATUS_FAILED)

    assertThat(runtime.queueSize()).isEqualTo(2)
  }

  /* ------------------- network-quality and health-check options ------------------- */

  private fun gateAndHealthCheck(): SyncOptions = TestData.syncOptions(
    minNetworkQuality = NetworkQuality.MODERATE,
    healthCheckUrl = "https://api.example.com/health",
    healthCheckTtlMs = 0.0,
    healthCheckBackoffMs = 15_000.0,
  )

  @Test
  fun `the gate and health-check options are echoed and reach a cold start`() = runBlocking<Unit> {
    val options = gateAndHealthCheck()

    runtime.configure(options)
    assertThat(runtime.currentOptions()).isEqualTo(options)

    restartProcess()

    assertThat(runtime.configSource).isEqualTo(ConfigSource.PERSISTED)
    assertThat(runtime.currentOptions()).isEqualTo(options)
  }

  @Test
  fun `options left unset are not echoed, before or after a restart`() = runBlocking<Unit> {
    val defaults = runtime.currentOptions()
    runtime.configure(gateAndHealthCheck())
    // A later configureSync without them clears them, as for every optional field.
    runtime.configure(TestData.syncOptions())
    val live = runtime.currentOptions()
    restartProcess()
    val persisted = runtime.currentOptions()

    listOf(defaults, live, persisted).forEach { options ->
      assertThat(options.minNetworkQuality).isNull()
      assertThat(options.healthCheckUrl).isNull()
      assertThat(options.healthCheckTtlMs).isNull()
      assertThat(options.healthCheckBackoffMs).isNull()
    }
  }

  @Test
  fun `invalid health-check options are rejected before anything changes`() = runBlocking<Unit> {
    val applied = gateAndHealthCheck()
    runtime.configure(applied)

    val rejections = listOf(
      TestData.syncOptions(healthCheckUrl = "not a url"),
      TestData.syncOptions(healthCheckTtlMs = Double.NaN),
      TestData.syncOptions(healthCheckBackoffMs = -1.0),
    ).map { invalid -> runCatching { runtime.configure(invalid) }.exceptionOrNull() as? SyncException }

    assertThat(rejections.map { it?.code }).containsExactly(
      SyncErrorCode.INVALID_URL,
      SyncErrorCode.INVALID_PAYLOAD,
      SyncErrorCode.INVALID_PAYLOAD,
    ).inOrder()
    assertThat(runtime.currentOptions()).isEqualTo(applied)
    restartProcess()
    assertThat(runtime.currentOptions()).isEqualTo(applied)
  }

  /* --------------------------- network-quality gate --------------------------- */

  private fun capabilities(vararg capabilities: Int): NetworkCapabilities =
    ShadowNetworkCapabilities.newInstance().also { caps ->
      shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      capabilities.forEach { shadowOf(caps).addCapability(it) }
    }

  /** Every event the current runtime emits from now on, until [block] returns. */
  private suspend fun <T> withEvents(block: suspend (List<SyncEvent>) -> T): T {
    val events: MutableList<SyncEvent> = CopyOnWriteArrayList()
    val collecting = CoroutineScope(Dispatchers.Default)
    val subscribed = CountDownLatch(1)
    collecting.launch {
      runtime.emitter.events.onSubscription { subscribed.countDown() }.collect { events += it }
    }
    subscribed.await(5, TimeUnit.SECONDS)
    return try {
      block(events)
    } finally {
      collecting.cancel()
    }
  }

  private suspend fun awaitUntil(condition: suspend () -> Boolean) {
    val met = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
      while (!condition()) delay(10L)
      true
    }
    assertThat(met).isTrue()
  }

  @Test
  fun `a kick the quality gate holds dispatches nothing and comes back 30 s later`() = runBlocking<Unit> {
    // INTERNET only: metered and not validated, so the link signals classify it POOR.
    goOnline()
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    runtime.configure(TestData.syncOptions(minNetworkQuality = NetworkQuality.MODERATE))
    insert("due", attempts = 1, nextRetryAt = 1L)
    restartProcess()
    val startedAt = System.currentTimeMillis()

    val (result, events) = withEvents { events ->
      val result = runWorker()
      // Delivered in order: anything emitted before SYNC_FAILED is visible once it is.
      awaitUntil { events.any { it.type == SyncEventType.SYNC_FAILED } }
      result to events.toList()
    }

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("due")!!.attempts).isEqualTo(1)
    assertThat(events.map { it.type }).containsExactly(SyncEventType.SYNC_FAILED)
    assertThat(events.single().errorCode).isEqualTo(SyncErrorCode.NETWORK_QUALITY_TOO_LOW)
    assertThat(kickFiresAt()).isAtLeast(startedAt + ConnectivityMonitor.QUALITY_CACHE_TTL_MS)
  }

  @Test
  fun `a kick the gate turned back again waits twice as long and carries the streak on`() =
    runBlocking<Unit> {
      goOnline()
      runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
      runtime.configure(TestData.syncOptions(minNetworkQuality = NetworkQuality.MODERATE))
      insert("due", attempts = 1, nextRetryAt = 1L)
      restartProcess()
      val startedAt = System.currentTimeMillis()

      TestListenableWorkerBuilder<SyncWorker>(context)
        .setInputData(Data.Builder().putInt(SyncWorker.KEY_GATE_STREAK, 1).build())
        .build()
        .doWork()

      val spec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
        .getWorkSpec(oneTimeWork().single().id.toString())!!
      assertThat(spec.lastEnqueueTime + spec.initialDelay).isAtLeast(startedAt + 60_000L)
      assertThat(spec.input.getInt(SyncWorker.KEY_GATE_STREAK, 0)).isEqualTo(2)
    }

  @Test
  fun `a periodic run the quality gate holds leaves the kick as it was`() = runBlocking<Unit> {
    goOnline()
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    runtime.configure(TestData.syncOptions(minNetworkQuality = NetworkQuality.MODERATE))
    insert("due", attempts = 1, nextRetryAt = 1L)
    runtime.armRetryWake()
    val kick = oneTimeWork().single().id
    restartProcess()

    val result = runPeriodicWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(oneTimeWork().single().id).isEqualTo(kick)
  }

  @Test
  fun `gate_automaticWake_rerunsWhenQualityReachesMinimum`() = runBlocking<Unit> {
    var healthy = false
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path == "/health" && !healthy ->
          MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        else -> MockResponse().setResponseCode(200)
      }
    }
    val wifi = manager().activeNetwork!!
    val linkCaps = intArrayOf(
      NetworkCapabilities.NET_CAPABILITY_INTERNET,
      NetworkCapabilities.NET_CAPABILITY_VALIDATED,
      NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
    )
    shadowOf(manager()).setNetworkCapabilities(wifi, capabilities(*linkCaps))
    // An IP literal: with one route, a failed HEAD cannot steer the next one to another
    // address the server does not listen on.
    val healthCheckUrl = server.url("/health").newBuilder().host("127.0.0.1").build().toString()
    // EXCELLENT: the link signals alone reach GOOD at most, so only a measurement passes.
    runtime.configure(
      TestData.syncOptions(
        minNetworkQuality = NetworkQuality.EXCELLENT,
        healthCheckUrl = healthCheckUrl,
      ),
    )
    attachEngineAndRecover()
    shadowOf(manager()).networkCallbacks.forEach { it.onAvailable(wifi) }
    // Every HEAD fails, so the measurement classifies the link POOR.
    awaitUntil { runtime.connectivityMonitor.currentState().networkQuality == NetworkQuality.POOR }

    // The timer checks the gate before dispatching: the wake is held, and nothing is emitted.
    val emitted = withEvents { events ->
      insert("due", attempts = 1, nextRetryAt = 1L)
      runtime.armRetryWake()
      delay(QUIET_MS)
      events.toList()
    }
    assertThat(emitted.filter { it.type == SyncEventType.SYNC_FAILED }).isEmpty()
    assertThat(runtime.isRetryTimerArmed).isTrue()
    assertThat(row("due")!!.attempts).isEqualTo(1)

    // A better network becomes the default and is measured at once: the held wake runs.
    healthy = true
    val better = ShadowNetwork.newInstance(202)
    shadowOf(manager()).setNetworkCapabilities(better, capabilities(*linkCaps))
    shadowOf(manager()).networkCallbacks.forEach { it.onAvailable(better) }

    awaitGone("due")
    assertThat(runtime.connectivityMonitor.currentState().networkQuality).isEqualTo(NetworkQuality.EXCELLENT)
  }

  /* ------------------------------- health check ------------------------------- */

  /** `/health` answers 200 while [healthy] holds, else 503; items always answer 200. */
  private fun healthDispatcher(healthy: () -> Boolean): Dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse = when {
      request.path == "/health" -> MockResponse().setResponseCode(if (healthy()) 200 else 503)
      else -> MockResponse().setResponseCode(200)
    }
  }

  /** An IP literal, so a failed request has no other address to fall back to. */
  private fun healthUrl(): String = server.url("/health").newBuilder().host("127.0.0.1").build().toString()

  private val heads = java.util.concurrent.atomic.AtomicInteger(0)

  private fun countingHeads(inner: Dispatcher): Dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
      if (request.method == "HEAD") heads.incrementAndGet()
      return inner.dispatch(request)
    }
  }

  @Test
  fun `a kick the health check skips succeeds and comes back after the backoff`() = runBlocking<Unit> {
    server.dispatcher = countingHeads(healthDispatcher { false })
    goOnline()
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    runtime.configure(TestData.syncOptions(healthCheckUrl = healthUrl(), healthCheckBackoffMs = 120_000.0))
    insert("due", attempts = 1, nextRetryAt = 1L)
    restartProcess()
    val startedAt = System.currentTimeMillis()

    val (result, events) = withEvents { events ->
      val result = runWorker()
      awaitUntil { events.any { it.type == SyncEventType.SYNC_FAILED } }
      result to events.toList()
    }

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(heads.get()).isEqualTo(1)
    assertThat(row("due")!!.attempts).isEqualTo(1)
    assertThat(events.map { it.type }).containsExactly(SyncEventType.SYNC_FAILED)
    assertThat(events.single().errorCode).isEqualTo(SyncErrorCode.HEALTH_CHECK_FAILED)
    assertThat(kickFiresAt()).isAtLeast(startedAt + 120_000L)
  }

  @Test
  fun `health_success_and_configure_clearBackoff, the configureSync half`() = runBlocking<Unit> {
    var healthy = false
    server.dispatcher = countingHeads(healthDispatcher { healthy })
    // Offline as far as the system knows: no quality measurement shares the endpoint, and a
    // manual flush sends anyway.
    goOffline()
    val options = TestData.syncOptions(healthCheckUrl = healthUrl(), healthCheckBackoffMs = 120_000.0)
    runtime.configure(options)
    insert("due")

    runtime.dispatcher.flush()
    runtime.dispatcher.flush()
    assertThat(heads.get()).isEqualTo(1)

    healthy = true
    runtime.configure(options)
    runtime.dispatcher.flush()

    assertThat(heads.get()).isEqualTo(2)
    assertThat(row("due")).isNull()
  }

  @Test
  fun `nothing is emitted or probed while a health-check backoff runs`() = runBlocking<Unit> {
    server.dispatcher = countingHeads(healthDispatcher { false })
    goOnline()
    attachEngineAndRecover()
    runtime.configure(TestData.syncOptions(healthCheckUrl = healthUrl(), healthCheckBackoffMs = 120_000.0))

    val events = withEvents { events ->
      insert("due", attempts = 1, nextRetryAt = 1L)
      runtime.dispatcher.flush()
      // Due rows and every arm path from here on: none may wake before the backoff ends.
      runtime.armRetryWake()
      runtime.resume()
      delay(QUIET_MS * 2)
      events.toList()
    }

    assertThat(events.filter { it.type == SyncEventType.SYNC_FAILED }).hasSize(1)
    assertThat(row("due")!!.attempts).isEqualTo(1)
  }

  @Test
  fun `health_qualitySample2xx_refreshesLastHealthy through the shared probe`() = runBlocking<Unit> {
    server.dispatcher = countingHeads(healthDispatcher { true })
    val wifi = manager().activeNetwork!!
    shadowOf(manager()).setNetworkCapabilities(
      wifi,
      capabilities(
        NetworkCapabilities.NET_CAPABILITY_INTERNET,
        NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
      ),
    )
    runtime.configure(TestData.syncOptions(healthCheckUrl = healthUrl()))
    attachEngineAndRecover()
    shadowOf(manager()).networkCallbacks.forEach { it.onAvailable(wifi) }
    awaitUntil { runtime.connectivityMonitor.currentState().networkQuality == NetworkQuality.EXCELLENT }
    val measured = heads.get()

    insert("due")
    runtime.dispatcher.flush()

    assertThat(heads.get()).isEqualTo(measured)
    assertThat(row("due")).isNull()
  }

  /* ------------------------------- dead letters ------------------------------- */

  @Test
  fun `no item is a dead letter yet, a FAILED row included`() = runBlocking<Unit> {
    insert("failed", status = SyncItemEntity.STATUS_FAILED)

    assertThat(runtime.listDeadLetter()).isEmpty()
  }

  @Test
  fun `replaying or discarding a dead letter rejects and changes nothing`() = runBlocking<Unit> {
    insert("failed", status = SyncItemEntity.STATUS_FAILED)

    val replay = runCatching { runtime.replayDeadLetter("failed") }.exceptionOrNull()
    val discard = runCatching { runtime.discardDeadLetter("failed") }.exceptionOrNull()

    listOf(replay to "replay", discard to "discard").forEach { (error, operation) ->
      assertThat(error).isInstanceOf(SyncException::class.java)
      assertThat((error as SyncException).rawMessage)
        .isEqualTo("Dead-letter $operation is not implemented yet.")
    }
    assertThat(row("failed")?.status).isEqualTo(SyncItemEntity.STATUS_FAILED)
  }

  private companion object {
    const val TOKEN = "Bearer live"
    const val AWAIT_TIMEOUT_MS = 20_000L
    const val FAR_MS = 3_600_000L

    /** Long enough for a wake armed at once to have sent its request. */
    const val QUIET_MS = 250L
  }
}
