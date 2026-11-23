package com.margelo.nitro.syncprovider.callbacks

import com.margelo.nitro.syncprovider.AfterDispatchContext
import com.margelo.nitro.syncprovider.BeforeDispatchAction
import com.margelo.nitro.syncprovider.BeforeDispatchContext
import com.margelo.nitro.syncprovider.HeaderResolverContext
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

  /**
   * Ask [resolver] for the headers of the attempt [context] describes, for at most [timeoutMs]
   * and no longer than [stop] stays active. Anything but headers OkHttp sends as they are, none
   * included, is a [CallbackResult.Skipped], already logged: the item is then not sent.
   */
  suspend fun resolveHeaders(
    resolver: CallbackRegistry.Installed<(String, HeaderResolverContext) -> Unit>,
    context: HeaderResolverContext,
    timeoutMs: Long,
    stop: Job?,
  ): CallbackResult<Map<String, String>> = gateway.request(
    kind = CallbackKind.HEADER_RESOLVER,
    owner = resolver.owner,
    itemId = context.itemId,
    timeoutMs = timeoutMs,
    stop = stop,
    consequence = NOT_SENT,
    parse = CallbackAnswers::headers,
  ) { requestId -> resolver.function(requestId, context) }

  /**
   * Ask [hook] whether the request [context] describes may go out, for at most [timeoutMs] and no
   * longer than [stop] stays active. Anything but PROCEED or SKIP is a [CallbackResult.Skipped],
   * already logged: the item is then not sent.
   */
  suspend fun beforeDispatch(
    hook: CallbackRegistry.Installed<(String, BeforeDispatchContext) -> Unit>,
    context: BeforeDispatchContext,
    timeoutMs: Long,
    stop: Job?,
  ): CallbackResult<BeforeDispatchAction> = gateway.request(
    kind = CallbackKind.BEFORE_DISPATCH,
    owner = hook.owner,
    itemId = context.itemId,
    timeoutMs = timeoutMs,
    stop = stop,
    consequence = NOT_SENT,
    parse = CallbackAnswers::beforeDispatch,
  ) { requestId -> hook.function(requestId, context) }

  /**
   * Tell [hook] how the request [context] describes ended, and return at once: nothing waits for
   * it. An answer of FAILED, or none within [timeoutMs], is only logged.
   */
  fun afterDispatch(
    hook: CallbackRegistry.Installed<(String, AfterDispatchContext) -> Unit>,
    context: AfterDispatchContext,
    timeoutMs: Long,
  ) {
    gateway.notify(
      kind = CallbackKind.AFTER_DISPATCH,
      owner = hook.owner,
      itemId = context.itemId,
      timeoutMs = timeoutMs,
    ) { requestId -> hook.function(requestId, context) }
  }

  private companion object {
    /** What follows an interceptor that gave nothing to use, a stop included. */
    const val NOT_SENT = "the item is not sent in this cycle"
  }
}
