package com.margelo.nitro.syncprovider.callbacks

import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.AfterDispatchContext
import com.margelo.nitro.syncprovider.BeforeDispatchContext
import com.margelo.nitro.syncprovider.CallbackAnswer
import com.margelo.nitro.syncprovider.CallbackAnswerStatus
import com.margelo.nitro.syncprovider.HeaderResolverContext
import com.margelo.nitro.syncprovider.RetryAction
import com.margelo.nitro.syncprovider.RetryDecision
import com.margelo.nitro.syncprovider.RetryDeciderContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.util.Collections

/**
 * Who owns the installed callbacks, and what a hybrid's creation and teardown do to them. Each
 * [HybridInstalls] stands for one hybrid: on Android a JS reload creates a new hybrid and never
 * disposes the old one, and an explicit `dispose()` is a teardown, which retires the hybrid
 * ([HybridInstalls.retire]).
 */
internal class CallbackRegistryTest {

  private val watchers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private val callbacks = JsCallbacks(
    scope = watchers,
    notifyContext = Dispatchers.Default,
    log = { _, message -> lines += message },
  )
  private val registry: CallbackRegistry get() = callbacks.registry

  @After
  fun tearDown() {
    watchers.cancel()
  }

  @Test
  fun `lifecycle_oldHybridTeardown_keepsNewInstall`() {
    val old = HybridInstalls(callbacks)
    assertThat(old.installEveryKind()).isTrue()
    val new = HybridInstalls(callbacks)
    val newDecider = decider()
    assertThat(new.installEveryKind(newDecider)).isTrue()

    // The old hybrid's dispose comes late, after the new one installed.
    old.retire()

    assertThat(owners()).containsExactly(new.owner, new.owner, new.owner, new.owner)
    assertThat(registry.retryDecider.installed?.function).isSameInstanceAs(newDecider)
    assertThat(new.isRetryDeciderInstalled()).isTrue()
  }

  @Test
  fun `lifecycle_newAttach_evictsOlderInstall, every kind of it`() {
    val old = HybridInstalls(callbacks)
    assertThat(old.installEveryKind()).isTrue()

    // A reload: the new runtime's hybrid is created, and the old one is never disposed.
    val new = HybridInstalls(callbacks)

    assertThat(owners()).containsExactly(null, null, null, null)
    assertThat(old.isRetryDeciderInstalled()).isFalse()
    assertThat(new.isRetryDeciderInstalled()).isFalse()
  }

  @Test
  fun `lifecycle_teardown_retiresInstalls_untilNewAttach, every kind kept and never called`() =
    runBlocking<Unit> {
      val hybrid = HybridInstalls(callbacks)
      assertThat(hybrid.installEveryKind()).isTrue()

      hybrid.retire()

      // Still installed, so an item that would ask one is not sent with the static headers alone.
      assertThat(owners()).containsExactly(hybrid.owner, hybrid.owner, hybrid.owner, hybrid.owner)
      assertThat(hybrid.isRetryDeciderInstalled()).isFalse()
      // A request for an orphan ends at once, a notification too, and JS is never called.
      var calls = 0
      val started = System.nanoTime()
      assertThat(ask(hybrid.owner) { calls++ }.await()).isEqualTo(CallbackResult.Skipped(SkipReason.OWNER_GONE))
      callbacks.gateway.notify(CallbackKind.AFTER_DISPATCH, hybrid.owner, ITEM, LONG_TIMEOUT_MS) { calls++ }
      awaitUntil { lines.size == 2 && callbacks.gateway.pendingCount == 0 }
      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(calls).isEqualTo(0)
      assertThat(lines).containsExactly(
        "retryDecider for item $ITEM (retryDecider:1): the JS runtime that installed it is gone; $POLICY",
        "afterDispatch for item $ITEM (afterDispatch:2): the JS runtime that installed it is gone; nothing changes",
      ).inOrder()

      // A newer hybrid's creation evicts the orphans, and it has installed nothing yet.
      HybridInstalls(callbacks)

      assertThat(owners()).containsExactly(null, null, null, null)
    }

  @Test
  fun `a torn-down hybrid's clear changes nothing, only a newer attach evicts its orphans`() {
    val hybrid = HybridInstalls(callbacks)
    assertThat(hybrid.installEveryKind()).isTrue()
    hybrid.retire()

    hybrid.clearRetryDecider()
    hybrid.clearHeaderResolver()
    hybrid.clearBeforeDispatch()
    hybrid.clearAfterDispatch()

    assertThat(owners()).containsExactly(hybrid.owner, hybrid.owner, hybrid.owner, hybrid.owner)
    assertThat(hybrid.isRetryDeciderInstalled()).isFalse()
  }

  @Test
  fun `lifecycle_staleOwnerInstall_rejected, once a newer hybrid attached or after a teardown`() {
    val old = HybridInstalls(callbacks)
    val new = HybridInstalls(callbacks)
    assertThat(new.installEveryKind()).isTrue()

    assertThat(old.setRetryDecider(decider())).isFalse()
    assertThat(old.setHeaderResolver { _: String, _: HeaderResolverContext -> }).isFalse()
    assertThat(old.setBeforeDispatch { _: String, _: BeforeDispatchContext -> }).isFalse()
    assertThat(old.setAfterDispatch { _: String, _: AfterDispatchContext -> }).isFalse()
    assertThat(owners()).containsExactly(new.owner, new.owner, new.owner, new.owner)

    val kept = registry.retryDecider.installed?.function
    new.retire()

    assertThat(new.installEveryKind()).isFalse()
    // Refused, so nothing changed: its installs stay, orphans until a newer hybrid attaches.
    assertThat(owners()).containsExactly(new.owner, new.owner, new.owner, new.owner)
    assertThat(registry.retryDecider.installed?.function).isSameInstanceAs(kept)
  }

  @Test
  fun `lifecycle_eviction_endsOwnersWaits when a newer hybrid attaches`() = runBlocking<Unit> {
    val old = HybridInstalls(callbacks)
    val called = CompletableDeferred<Unit>()
    val waiting = ask(old.owner) { called.complete(Unit) }
    called.await()
    val started = System.nanoTime()

    HybridInstalls(callbacks)

    assertThat(waiting.await()).isEqualTo(CallbackResult.Skipped(SkipReason.OWNER_GONE))
    assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
    assertThat(lines.single())
      .isEqualTo("retryDecider for item $ITEM (retryDecider:1): the JS runtime that installed it is gone; $POLICY")
    assertThat(callbacks.gateway.pendingCount).isEqualTo(0)
  }

  @Test
  fun `lifecycle_eviction_endsOwnersWaits at a teardown, a notification's too`() = runBlocking<Unit> {
    val hybrid = HybridInstalls(callbacks)
    val asked = CompletableDeferred<Unit>()
    val notified = CompletableDeferred<Unit>()
    val waiting = ask(hybrid.owner) { asked.complete(Unit) }
    asked.await()
    callbacks.gateway.notify(CallbackKind.AFTER_DISPATCH, hybrid.owner, ITEM, LONG_TIMEOUT_MS) {
      notified.complete(Unit)
    }
    notified.await()
    val started = System.nanoTime()

    hybrid.retire()

    assertThat(waiting.await()).isEqualTo(CallbackResult.Skipped(SkipReason.OWNER_GONE))
    awaitUntil { lines.size == 2 && callbacks.gateway.pendingCount == 0 }
    assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
    assertThat(lines).contains(
      "afterDispatch for item $ITEM (afterDispatch:2): the JS runtime that installed it is gone; nothing changes",
    )
  }

  @Test
  fun `a request for an evicted owner ends at once, and JS is never called`() = runBlocking<Unit> {
    val old = HybridInstalls(callbacks)
    HybridInstalls(callbacks)
    var calls = 0

    val result = ask(old.owner) { calls++ }.await()

    assertThat(result).isEqualTo(CallbackResult.Skipped(SkipReason.OWNER_GONE))
    assertThat(calls).isEqualTo(0)
  }

  @Test
  fun `a waiting request of the current hybrid outlives another one's eviction`() = runBlocking<Unit> {
    val old = HybridInstalls(callbacks)
    old.retire()
    val current = HybridInstalls(callbacks)
    var requestId = ""
    val waiting = ask(current.owner) { requestId = it }
    awaitUntil { requestId.isNotEmpty() }

    // A late teardown of the old hybrid, then the current one's answer.
    old.retire()
    callbacks.gateway.answer(requestId, decision())

    assertThat(waiting.await()).isInstanceOf(CallbackResult.Answered::class.java)
  }

  @Test
  fun `a repeated set replaces, and clear is the only uninstall`() {
    val hybrid = HybridInstalls(callbacks)
    val first = decider()
    val second = decider()

    assertThat(hybrid.setRetryDecider(first)).isTrue()
    assertThat(hybrid.setRetryDecider(second)).isTrue()
    assertThat(registry.retryDecider.installed?.function).isSameInstanceAs(second)
    assertThat(hybrid.isRetryDeciderInstalled()).isTrue()

    hybrid.clearRetryDecider()
    assertThat(registry.retryDecider.installed).isNull()
    assertThat(hybrid.isRetryDeciderInstalled()).isFalse()

    assertThat(hybrid.installEveryKind()).isTrue()
    hybrid.clearHeaderResolver()
    hybrid.clearBeforeDispatch()
    hybrid.clearAfterDispatch()
    assertThat(owners()).containsExactly(hybrid.owner, null, null, null).inOrder()
  }

  @Test
  fun `clear and isRetryDeciderInstalled see only their own hybrid's installs`() {
    val old = HybridInstalls(callbacks)
    val new = HybridInstalls(callbacks)
    assertThat(new.installEveryKind()).isTrue()

    old.clearRetryDecider()
    old.clearHeaderResolver()
    old.clearBeforeDispatch()
    old.clearAfterDispatch()

    assertThat(owners()).containsExactly(new.owner, new.owner, new.owner, new.owner)
    assertThat(old.isRetryDeciderInstalled()).isFalse()
    assertThat(new.isRetryDeciderInstalled()).isTrue()
  }

  @Test
  fun `a reset forgets every install and ends every wait`() = runBlocking<Unit> {
    val hybrid = HybridInstalls(callbacks)
    assertThat(hybrid.installEveryKind()).isTrue()
    val called = CompletableDeferred<Unit>()
    val waiting = ask(hybrid.owner) { called.complete(Unit) }
    called.await()

    callbacks.reset()

    assertThat(owners()).containsExactly(null, null, null, null)
    assertThat(waiting.await()).isEqualTo(CallbackResult.Skipped(SkipReason.OWNER_GONE))
  }

  /* --------------------------------- helpers --------------------------------- */

  private fun decider(): (String, RetryDeciderContext) -> Unit = { _, _ -> }

  private fun HybridInstalls.installEveryKind(retryDecider: (String, RetryDeciderContext) -> Unit = decider()): Boolean =
    listOf(
      setRetryDecider(retryDecider),
      setHeaderResolver { _: String, _: HeaderResolverContext -> },
      setBeforeDispatch { _: String, _: BeforeDispatchContext -> },
      setAfterDispatch { _: String, _: AfterDispatchContext -> },
    ).all { it }

  /** The owner of each kind's install, in kind order, or null when none. */
  private fun owners(): List<Long?> = listOf(
    registry.retryDecider.installed?.owner,
    registry.headerResolver.installed?.owner,
    registry.beforeDispatch.installed?.owner,
    registry.afterDispatch.installed?.owner,
  )

  /** A decider request on [owner]'s behalf, waiting in the background. */
  private fun CoroutineScope.ask(
    owner: Long,
    fakeJs: (requestId: String) -> Unit,
  ): Deferred<CallbackResult<RetryDecision>> = async(Dispatchers.Default) {
    callbacks.gateway.request(
      CallbackKind.RETRY_DECIDER, owner, ITEM, LONG_TIMEOUT_MS, null, POLICY, CallbackAnswers::decision, fakeJs,
    )
  }

  private fun decision() = CallbackAnswer(
    CallbackAnswerStatus.RESOLVED,
    RetryDecision(RetryAction.RETRY, null),
    null,
    null,
    null,
  )

  private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L

  private suspend fun awaitUntil(condition: () -> Boolean) {
    withTimeout(AT_ONCE_MS) {
      while (!condition()) delay(10L)
    }
  }

  private companion object {
    const val ITEM = "01ITEM"
    const val POLICY = "the retry policy applies"
    const val LONG_TIMEOUT_MS = 30_000L
    const val AT_ONCE_MS = 5_000L
  }
}
