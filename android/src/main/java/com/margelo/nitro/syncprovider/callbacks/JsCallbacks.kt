package com.margelo.nitro.syncprovider.callbacks

import com.margelo.nitro.syncprovider.RetryDecision
import com.margelo.nitro.syncprovider.RetryDeciderContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * The process-wide callback state: what JS installed ([registry]) and the requests waiting for JS
 * to answer ([gateway]).
 *
 * It belongs to no hybrid and is not part of the runtime instance, so the bridge installs and
 * clears on the JS thread without building a runtime, and an answer still lands after its hybrid
 * was disposed. Evicting an owner, by a newer attach or a teardown, ends every wait it owns at
 * once, the way no answer would end it.
 *
 * The parameters are the [JsCallbackGateway]'s.
 */
internal class JsCallbacks(
  scope: CoroutineScope = JsCallbackGateway.defaultScope(),
  waitContext: CoroutineContext = Dispatchers.Default,
  notifyContext: CoroutineContext = Dispatchers.Main,
  /** Where callback outcomes are logged, the gateway's lines included. */
  val log: CallbackLog = CallbackLog.ANDROID,
) {
  val registry: CallbackRegistry = CallbackRegistry()

  val gateway: JsCallbackGateway =
    JsCallbackGateway(registry::isLive, scope, waitContext, notifyContext, log)

  /** A hybrid was created: its generation. Every older install is evicted, and its waits end. */
  fun attach(): Long = registry.attach().also { gateway.endWaitsOfGoneOwners() }

  /** The hybrid of generation [owner] is torn down: its installs go, and its waits end. */
  fun detach(owner: Long) {
    registry.detach(owner)
    gateway.endWaitsOfGoneOwners()
  }

  /** Forget every install and end every wait: the runtime is shut down. */
  fun reset() {
    registry.clearAll()
    gateway.endAllWaits()
  }

  /**
   * Ask [decider] what to do with the failed attempt [context] describes, for at most [timeoutMs]
   * and no longer than [stop] stays active. Anything but a decision is a
   * [CallbackResult.Skipped], already logged; the retry policy then applies.
   */
  suspend fun decide(
    decider: CallbackRegistry.Installed<(String, RetryDeciderContext) -> Unit>,
    context: RetryDeciderContext,
    timeoutMs: Long,
    stop: Job?,
  ): CallbackResult<RetryDecision> = gateway.request(
    kind = CallbackKind.RETRY_DECIDER,
    owner = decider.owner,
    itemId = context.itemId,
    timeoutMs = timeoutMs,
    stop = stop,
    consequence = "the retry policy applies",
    parse = CallbackAnswers::decision,
  ) { requestId -> decider.function(requestId, context) }
}
