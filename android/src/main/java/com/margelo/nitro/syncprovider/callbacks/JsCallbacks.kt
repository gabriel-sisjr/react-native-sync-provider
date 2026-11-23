package com.margelo.nitro.syncprovider.callbacks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
  log: CallbackLog = CallbackLog.ANDROID,
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
}
