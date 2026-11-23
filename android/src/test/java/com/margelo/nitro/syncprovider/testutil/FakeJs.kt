package com.margelo.nitro.syncprovider.testutil

import com.margelo.nitro.syncprovider.BeforeDispatchAction
import com.margelo.nitro.syncprovider.BeforeDispatchResult
import com.margelo.nitro.syncprovider.CallbackAnswer
import com.margelo.nitro.syncprovider.CallbackAnswerStatus
import com.margelo.nitro.syncprovider.RetryAction
import com.margelo.nitro.syncprovider.RetryDecision
import com.margelo.nitro.syncprovider.callbacks.JsCallbackGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Stands in for JS in tests of what calls it: functions to install that record each call and
 * answer through [gateway] the way the test says, at once, later from another thread, or never.
 * [scope] is where the later answers run; the test cancels it when it ends.
 */
internal class FakeJs(private val gateway: JsCallbackGateway, private val scope: CoroutineScope) {

  /** A JS function to install: it records each context, then runs [onCall] with its request id. */
  fun <C> function(onCall: FakeJs.(requestId: String, context: C) -> Unit = { _, _ -> }): FakeFunction<C> =
    FakeFunction(this, onCall)

  /** Answer [requestId] now, on the calling thread. */
  fun answer(requestId: String, answer: CallbackAnswer) {
    gateway.answer(requestId, answer)
  }

  /** Answer [requestId] [ms] from now, from another thread. */
  fun answerLater(requestId: String, ms: Long, answer: CallbackAnswer) {
    scope.launch {
      delay(ms)
      gateway.answer(requestId, answer)
    }
  }

  /** Run [block] on another thread, as JS runs, without blocking the caller. */
  fun later(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

  companion object {
    fun decision(action: RetryAction, delayMs: Double? = null): CallbackAnswer =
      CallbackAnswer(CallbackAnswerStatus.RESOLVED, RetryDecision(action, delayMs), null, null, null)

    fun headers(headers: Map<String, String>): CallbackAnswer =
      CallbackAnswer(CallbackAnswerStatus.RESOLVED, null, headers, null, null)

    fun before(action: BeforeDispatchAction): CallbackAnswer =
      CallbackAnswer(CallbackAnswerStatus.RESOLVED, null, null, BeforeDispatchResult(action), null)

    /** RESOLVED with no payload: what `afterDispatch` answers. */
    fun resolved(): CallbackAnswer = CallbackAnswer(CallbackAnswerStatus.RESOLVED, null, null, null, null)

    fun failed(message: String?): CallbackAnswer =
      CallbackAnswer(CallbackAnswerStatus.FAILED, null, null, null, message)
  }
}

/** A JS function [FakeJs] made: what it was called with, in order. */
internal class FakeFunction<C>(
  private val js: FakeJs,
  private val onCall: FakeJs.(requestId: String, context: C) -> Unit,
) : (String, C) -> Unit {

  private val recorded: MutableList<C> = Collections.synchronizedList(mutableListOf())

  val calls: List<C> get() = synchronized(recorded) { recorded.toList() }

  override fun invoke(requestId: String, context: C) {
    recorded += context
    js.onCall(requestId, context)
  }
}
