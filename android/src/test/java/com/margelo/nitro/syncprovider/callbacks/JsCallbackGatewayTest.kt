package com.margelo.nitro.syncprovider.callbacks

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.margelo.nitro.syncprovider.BeforeDispatchAction
import com.margelo.nitro.syncprovider.BeforeDispatchResult
import com.margelo.nitro.syncprovider.CallbackAnswer
import com.margelo.nitro.syncprovider.CallbackAnswerStatus
import com.margelo.nitro.syncprovider.RetryAction
import com.margelo.nitro.syncprovider.RetryDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import org.junit.After
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The gateway against fake JS: functions that take their request id and answer through
 * [JsCallbackGateway.answer], at once, later from another thread, or never. Time is real, as in
 * production, so every wait here is short, and "at once" means well inside a long timeout.
 */
internal class JsCallbackGatewayTest {

  /** Where fake JS answers later, off the waiting thread. */
  private val js = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** The gateway's own scope, where notifications wait. */
  private val watchers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** Every line the gateway logged: priority and message. */
  private val lines: MutableList<Pair<Int, String>> = Collections.synchronizedList(mutableListOf())

  private val gateway = JsCallbackGateway(
    scope = watchers,
    waitContext = Dispatchers.Default,
    notifyContext = Dispatchers.Default,
    log = { priority, message -> lines += priority to message },
  )

  @After
  fun tearDown() {
    js.cancel()
    watchers.cancel()
  }

  /* -------------------------------- requests -------------------------------- */

  @Test
  fun `gateway_answerBeforeTimeout_resolves`() = runBlocking<Unit> {
    val started = System.nanoTime()

    val result = askDecider { id -> later(50L, id, decision(RetryAction.DROP)) }

    assertThat(result).isEqualTo(CallbackResult.Answered(RetryDecision(RetryAction.DROP, null)))
    assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
    assertThat(gateway.pendingCount).isEqualTo(0)
    assertThat(lines).isEmpty()
  }

  @Test
  fun `gateway_wrongKindPayload_isFailed, whichever kind asked`() = runBlocking<Unit> {
    val started = System.nanoTime()

    val decider = askDecider { id -> gateway.answer(id, resolved(headers = mapOf("X-Kind" to "1"))) }
    val resolver = askResolver { id -> gateway.answer(id, decision(RetryAction.RETRY)) }
    val before = askBefore { id -> gateway.answer(id, resolved(headers = emptyMap())) }
    val bare = askResolver { id -> gateway.answer(id, resolved()) }

    assertThat(listOf(decider, resolver, before, bare))
      .containsExactly(
        CallbackResult.Skipped(SkipReason.INVALID_ANSWER),
        CallbackResult.Skipped(SkipReason.INVALID_ANSWER),
        CallbackResult.Skipped(SkipReason.INVALID_ANSWER),
        CallbackResult.Skipped(SkipReason.INVALID_ANSWER),
      )
    // A wrong answer ends the wait at once: no timeout is waited out.
    assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
    assertThat(lines.map { it.second })
      .containsExactly(
        "retryDecider for item $ITEM (retryDecider:1): the answer carried no usable decision; $POLICY",
        "headerResolver for item $ITEM (headerResolver:2): the answer carried no usable headers; $DEFER",
        "beforeDispatch for item $ITEM (beforeDispatch:3): the answer carried no usable beforeDispatch; $DEFER",
        "headerResolver for item $ITEM (headerResolver:4): the answer carried no usable headers; $DEFER",
      )
      .inOrder()
  }

  @Test
  fun `each kind takes the field it asked for, and ignores the others`() = runBlocking<Unit> {
    val decider = askDecider { id ->
      gateway.answer(id, resolved(decision = RetryDecision(RetryAction.RETRY, 250.0), headers = mapOf("A" to "b")))
    }
    val noHeaders = askResolver { id -> gateway.answer(id, resolved(headers = emptyMap())) }
    val headers = askResolver { id -> gateway.answer(id, resolved(headers = mapOf("Authorization" to "Bearer t"))) }
    val skip = askBefore { id -> gateway.answer(id, before(BeforeDispatchAction.SKIP)) }

    assertThat(decider).isEqualTo(CallbackResult.Answered(RetryDecision(RetryAction.RETRY, 250.0)))
    assertThat(noHeaders).isEqualTo(CallbackResult.Answered(emptyMap<String, String>()))
    assertThat(headers).isEqualTo(CallbackResult.Answered(mapOf("Authorization" to "Bearer t")))
    assertThat(skip).isEqualTo(CallbackResult.Answered(BeforeDispatchAction.SKIP))
    assertThat(lines).isEmpty()
  }

  @Test
  fun `gateway_invokeThrows_isFailed, at once and without keeping the request`() = runBlocking<Unit> {
    val started = System.nanoTime()

    val result = askDecider { throw IllegalStateException("the JS function was deleted") }

    assertThat(result).isEqualTo(CallbackResult.Skipped(SkipReason.INVOKE_FAILED))
    assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
    assertThat(gateway.pendingCount).isEqualTo(0)
    assertThat(lines.single().second).isEqualTo(
      "retryDecider for item $ITEM (retryDecider:1): the call failed: " +
        "IllegalStateException: the JS function was deleted; $POLICY",
    )
  }

  @Test
  fun `gateway_secondAnswer_noop, and so is an answer after the request ended`() = runBlocking<Unit> {
    var requestId = ""

    // Answered while JS is being called: the request is registered before the call.
    val result = askDecider { id ->
      requestId = id
      gateway.answer(id, decision(RetryAction.DROP))
      gateway.answer(id, decision(RetryAction.DEAD_LETTER))
    }
    gateway.answer(requestId, decision(RetryAction.RETRY))

    assertThat(result).isEqualTo(CallbackResult.Answered(RetryDecision(RetryAction.DROP, null)))
    assertThat(gateway.pendingCount).isEqualTo(0)
    assertThat(lines).isEmpty()
  }

  @Test
  fun `gateway_noAnswer_timesOut, after its timeout and not before`() = runBlocking<Unit> {
    val started = System.nanoTime()

    val result = askDecider(timeoutMs = 150L) { }

    assertThat(result).isEqualTo(CallbackResult.Skipped(SkipReason.TIMED_OUT))
    assertThat(elapsedMs(started)).isAtLeast(150L)
    assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
    assertThat(gateway.pendingCount).isEqualTo(0)
    assertThat(lines).containsExactly(
      Log.WARN to "retryDecider for item $ITEM (retryDecider:1): no answer within 150 ms; $POLICY",
    )
  }

  @Test
  fun `gateway_cancellation_endsWait at once, and the answer that follows does nothing`() =
    runBlocking<Unit> {
      val stop = Job()
      js.launch {
        delay(50L)
        stop.cancel()
      }
      val started = System.nanoTime()

      val result = askDecider(stop = stop) { id -> later(400L, id, decision(RetryAction.DROP)) }

      assertThat(result).isEqualTo(CallbackResult.Skipped(SkipReason.STOPPED))
      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(lines).containsExactly(
        Log.INFO to "retryDecider for item $ITEM (retryDecider:1): the cycle stopped; $POLICY",
      )
      delay(600L)
      assertThat(gateway.pendingCount).isEqualTo(0)
      assertThat(lines).hasSize(1)
    }

  /**
   * The shape the dispatcher uses: the wait runs under `NonCancellable`, and the stop is a child
   * of the caller's job, made before it. Cancelling the caller still ends the wait at once.
   */
  @Test
  fun `gateway_cancellation_endsWait when the caller is cancelled, though it waits under NonCancellable`() =
    runBlocking<Unit> {
      val called = CompletableDeferred<Unit>()
      var result: CallbackResult<RetryDecision>? = null
      val started = System.nanoTime()
      val caller = launch(Dispatchers.Default) {
        val stop = Job(coroutineContext[Job])
        try {
          result = withContext(NonCancellable) {
            askDecider(stop = stop) { id ->
              called.complete(Unit)
              later(LONG_TIMEOUT_MS / 2, id, decision(RetryAction.DROP))
            }
          }
        } finally {
          stop.complete()
        }
      }

      called.await()
      caller.cancel()
      caller.join()

      assertThat(result).isEqualTo(CallbackResult.Skipped(SkipReason.STOPPED))
      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(caller.isCancelled).isTrue()
      assertThat(gateway.pendingCount).isEqualTo(0)
    }

  @Test
  fun `gateway_cancellation_endsWait before the call, and JS is never asked`() = runBlocking<Unit> {
    val stop = Job().apply { cancel() }
    var calls = 0

    val result = askDecider(stop = stop) { calls++ }

    assertThat(result).isEqualTo(CallbackResult.Skipped(SkipReason.STOPPED))
    assertThat(calls).isEqualTo(0)
    assertThat(gateway.pendingCount).isEqualTo(0)
  }

  /** As on iOS: a runtime that went away is not a failure of the callback, as a stop is not. */
  @Test
  fun `an owner gone ends a wait at once and logs at INFO, a notification's too`() = runBlocking<Unit> {
    val live = AtomicBoolean(true)
    val owned = JsCallbackGateway(
      isOwnerLive = { live.get() },
      scope = watchers,
      waitContext = Dispatchers.Default,
      notifyContext = Dispatchers.Default,
      log = { priority, message -> lines += priority to message },
    )
    val asked = CompletableDeferred<Unit>()
    val waiting = async {
      owned.request(
        CallbackKind.HEADER_RESOLVER, OWNER, ITEM, LONG_TIMEOUT_MS, null, DEFER, CallbackAnswers::headers,
      ) { asked.complete(Unit) }
    }
    asked.await()
    val started = System.nanoTime()

    live.set(false)
    owned.endWaitsOfGoneOwners()

    assertThat(waiting.await()).isEqualTo(CallbackResult.Skipped(SkipReason.OWNER_GONE))
    assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
    // An owner already gone is not called, and the line says why.
    val notified = AtomicBoolean(false)
    owned.notify(CallbackKind.AFTER_DISPATCH, OWNER, ITEM, LONG_TIMEOUT_MS) { notified.set(true) }
    awaitUntil { lines.size == 2 && owned.pendingCount == 0 }
    assertThat(notified.get()).isFalse()
    assertThat(lines).containsExactly(
      Log.INFO to "headerResolver for item $ITEM (headerResolver:1): $GONE; $DEFER",
      Log.INFO to "afterDispatch for item $ITEM (afterDispatch:2): $GONE; nothing changes",
    ).inOrder()
  }

  @Test
  fun `a FAILED answer ends the wait at once, and the log keeps 256 characters of its message`() =
    runBlocking<Unit> {
      val message = "x".repeat(JsCallbackGateway.MAX_LOGGED_MESSAGE) + "TAIL"
      val started = System.nanoTime()

      val result = askDecider { id -> later(20L, id, failed(message)) }
      val empty = askDecider { id -> gateway.answer(id, failed(null)) }

      assertThat(result).isEqualTo(CallbackResult.Skipped(SkipReason.ANSWER_FAILED))
      assertThat(empty).isEqualTo(CallbackResult.Skipped(SkipReason.ANSWER_FAILED))
      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(lines.map { it.second }).containsExactly(
        "retryDecider for item $ITEM (retryDecider:1): answered FAILED: ${"x".repeat(256)}; $POLICY",
        "retryDecider for item $ITEM (retryDecider:2): answered FAILED: (no message); $POLICY",
      ).inOrder()
    }

  @Test
  fun `a late answer changes nothing`() = runBlocking<Unit> {
    val result = askDecider(timeoutMs = 100L) { id -> later(300L, id, decision(RetryAction.DEAD_LETTER)) }
    delay(500L)

    assertThat(result).isEqualTo(CallbackResult.Skipped(SkipReason.TIMED_OUT))
    assertThat(gateway.pendingCount).isEqualTo(0)
    assertThat(lines).hasSize(1)
  }

  @Test
  fun `an unknown request id does nothing, and a request waiting keeps its own answer`() =
    runBlocking<Unit> {
      val result = askDecider { id ->
        gateway.answer("retryDecider:999999", decision(RetryAction.DEAD_LETTER))
        gateway.answer("headerResolver:1", resolved(headers = emptyMap()))
        later(50L, id, decision(RetryAction.RETRY))
      }

      assertThat(result).isEqualTo(CallbackResult.Answered(RetryDecision(RetryAction.RETRY, null)))
      assertThat(lines).isEmpty()
    }

  @Test
  fun `requests are told apart by id, so concurrent ones each get their own answer`() =
    runBlocking<Unit> {
      // Answered in the reverse order of the requests.
      val asks = (1..20).map { n ->
        async(Dispatchers.Default) {
          askResolver { id -> later((20 - n) * 5L, id, resolved(headers = mapOf("X-N" to "$n"))) }
        }
      }

      val results = asks.map { it.await() }

      assertThat(results).isEqualTo(
        (1..20).map { CallbackResult.Answered(mapOf("X-N" to "$it")) },
      )
      assertThat(gateway.pendingCount).isEqualTo(0)
    }

  /* ----------------------------- header validation ----------------------------- */

  @Test
  fun `one resolver header OkHttp would refuse fails the whole answer`() = runBlocking<Unit> {
    val refused = listOf(
      mapOf("" to "value"),
      mapOf("X Space" to "value"),
      mapOf("X-Ok" to "line\nbreak"),
      mapOf("X-Ok" to "carriage\rreturn"),
      mapOf("X-Ok" to "café"),
      mapOf("Ünicode" to "value"),
      mapOf("Authorization" to "Bearer fine", "X-Bad" to "nul\u0000"),
    )

    val results = refused.map { headers -> askResolver { id -> gateway.answer(id, resolved(headers = headers)) } }
    val accepted = askResolver { id ->
      gateway.answer(id, resolved(headers = mapOf("Authorization" to "Bearer t", "X-Tab" to "a\tb", "X-Empty" to "")))
    }

    results.forEach { assertThat(it).isEqualTo(CallbackResult.Skipped(SkipReason.INVALID_ANSWER)) }
    assertThat(accepted).isEqualTo(
      CallbackResult.Answered(mapOf("Authorization" to "Bearer t", "X-Tab" to "a\tb", "X-Empty" to "")),
    )
    // The header values never reach the log.
    assertThat(lines.joinToString { it.second }).doesNotContain("Bearer")
  }

  @Test
  fun `the header rule is the one OkHttp applies`() {
    val names = listOf(
      "X-Ok", "Authorization", "x:colon", "~!", "", " ", "X Space", "Tab\tName", "Ünicode",
      "del\u007f", "new\nline",
    )
    val values = listOf(
      "plain", "", "a\tb", "  spaced  ", "line\nbreak", "cr\rx", "café", "del\u007f", "nul\u0000",
    )

    names.forEach { name ->
      val okhttp = runCatching { Headers.Builder().add(name, "v") }.isSuccess
      assertWithMessage("name '%s'", name).that(CallbackAnswers.isHeaderName(name)).isEqualTo(okhttp)
    }
    values.forEach { value ->
      val okhttp = runCatching { Headers.Builder().add("X-Value", value) }.isSuccess
      assertWithMessage("value '%s'", value).that(CallbackAnswers.isHeaderValue(value)).isEqualTo(okhttp)
    }
  }

  /* ------------------------------ notifications ------------------------------ */

  @Test
  fun `a notification never waits, and logs an answer of FAILED`() = runBlocking<Unit> {
    val release = CompletableDeferred<Unit>()

    gateway.notify(CallbackKind.AFTER_DISPATCH, OWNER, ITEM, LONG_TIMEOUT_MS) { id ->
      js.launch {
        release.await()
        gateway.answer(id, failed("the hook threw"))
      }
    }

    // notify has returned, and JS has not answered: nothing waited for it.
    assertThat(gateway.pendingCount).isEqualTo(1)
    assertThat(lines).isEmpty()
    release.complete(Unit)
    awaitUntil { lines.isNotEmpty() && gateway.pendingCount == 0 }
    assertThat(lines).containsExactly(
      Log.WARN to "afterDispatch for item $ITEM (afterDispatch:1): answered FAILED: the hook threw; nothing changes",
    )
  }

  @Test
  fun `a notification logs no answer within its timeout, then forgets it`() = runBlocking<Unit> {
    gateway.notify(CallbackKind.AFTER_DISPATCH, OWNER, ITEM, 100L) { }

    awaitUntil { lines.isNotEmpty() && gateway.pendingCount == 0 }
    assertThat(lines.single().second).isEqualTo(
      "afterDispatch for item $ITEM (afterDispatch:1): no answer within 100 ms; nothing changes",
    )
  }

  @Test
  fun `a notification whose call throws logs it and keeps nothing`() = runBlocking<Unit> {
    gateway.notify(CallbackKind.AFTER_DISPATCH, OWNER, ITEM, LONG_TIMEOUT_MS) {
      throw IllegalStateException("runtime gone")
    }

    awaitUntil { lines.isNotEmpty() && gateway.pendingCount == 0 }
    assertThat(lines.single().second).isEqualTo(
      "afterDispatch for item $ITEM (afterDispatch:1): the call failed: " +
        "IllegalStateException: runtime gone; nothing changes",
    )
  }

  @Test
  fun `a notification answered RESOLVED needs no payload and logs nothing`() = runBlocking<Unit> {
    var called = false
    gateway.notify(CallbackKind.AFTER_DISPATCH, OWNER, ITEM, LONG_TIMEOUT_MS) { id ->
      called = true
      later(20L, id, resolved())
    }

    awaitUntil { called && gateway.pendingCount == 0 }
    assertThat(lines).isEmpty()
  }

  /* --------------------------------- helpers --------------------------------- */

  /** Ask the decider; [fakeJs] is what the JS function does with its request id. */
  private suspend fun askDecider(
    timeoutMs: Long = LONG_TIMEOUT_MS,
    stop: Job? = null,
    fakeJs: (requestId: String) -> Unit,
  ): CallbackResult<RetryDecision> = gateway.request(
    CallbackKind.RETRY_DECIDER, OWNER, ITEM, timeoutMs, stop, POLICY, CallbackAnswers::decision, fakeJs,
  )

  private suspend fun askResolver(fakeJs: (requestId: String) -> Unit): CallbackResult<Map<String, String>> =
    gateway.request(
      CallbackKind.HEADER_RESOLVER, OWNER, ITEM, LONG_TIMEOUT_MS, null, DEFER, CallbackAnswers::headers, fakeJs,
    )

  private suspend fun askBefore(fakeJs: (requestId: String) -> Unit): CallbackResult<BeforeDispatchAction> =
    gateway.request(
      CallbackKind.BEFORE_DISPATCH, OWNER, ITEM, LONG_TIMEOUT_MS, null, DEFER, CallbackAnswers::beforeDispatch,
      fakeJs,
    )

  /** Fake JS answering [id] [ms] from now, from another thread. */
  private fun later(ms: Long, id: String, answer: CallbackAnswer) {
    js.launch {
      delay(ms)
      gateway.answer(id, answer)
    }
  }

  private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L

  private suspend fun awaitUntil(condition: () -> Boolean) {
    withTimeout(AT_ONCE_MS) {
      while (!condition()) delay(10L)
    }
  }

  private companion object {
    const val OWNER = 1L
    const val ITEM = "01ITEM"
    const val POLICY = "the retry policy applies"
    const val DEFER = "the item is not sent and waits"
    const val GONE = "the JS runtime that installed it is gone"

    /** A timeout no test waits out. */
    const val LONG_TIMEOUT_MS = 30_000L

    /** Well inside [LONG_TIMEOUT_MS]: what "at once" allows on a loaded machine. */
    const val AT_ONCE_MS = 5_000L

    fun resolved(
      decision: RetryDecision? = null,
      headers: Map<String, String>? = null,
      beforeDispatch: BeforeDispatchResult? = null,
    ) = CallbackAnswer(CallbackAnswerStatus.RESOLVED, decision, headers, beforeDispatch, null)

    fun decision(action: RetryAction, delayMs: Double? = null) =
      resolved(decision = RetryDecision(action, delayMs))

    fun before(action: BeforeDispatchAction) = resolved(beforeDispatch = BeforeDispatchResult(action))

    fun failed(message: String?) = CallbackAnswer(CallbackAnswerStatus.FAILED, null, null, null, message)
  }
}
