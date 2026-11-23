package com.margelo.nitro.syncprovider.callbacks

import com.margelo.nitro.syncprovider.AfterDispatchContext
import com.margelo.nitro.syncprovider.BeforeDispatchContext
import com.margelo.nitro.syncprovider.HeaderResolverContext
import com.margelo.nitro.syncprovider.RetryDeciderContext

/**
 * The callbacks one hybrid installs.
 *
 * It takes the hybrid's generation when it is created, which evicts every install an older
 * hybrid made (see [CallbackRegistry]). A `set…` replaces this hybrid's install of that kind, and
 * is refused once the hybrid is no longer the current one: torn down, or replaced by a newer
 * one. `clear…` and [isRetryDeciderInstalled] see this hybrid's installs only, and only while it
 * is the current one. Nothing here does I/O, so the bridge calls it on the JS thread.
 */
internal class HybridInstalls(private val callbacks: JsCallbacks) {

  /** This hybrid's generation. */
  val owner: Long = callbacks.attach()

  private val registry: CallbackRegistry get() = callbacks.registry

  /** @return false when this hybrid may no longer install. */
  fun setRetryDecider(decider: (String, RetryDeciderContext) -> Unit): Boolean =
    registry.install(registry.retryDecider, owner, decider)

  fun clearRetryDecider() = registry.clear(registry.retryDecider, owner)

  /** False once this hybrid is torn down, its decider an orphan the engine never asks. */
  fun isRetryDeciderInstalled(): Boolean = registry.isInstalledBy(registry.retryDecider, owner)

  /** @return false when this hybrid may no longer install. */
  fun setHeaderResolver(resolver: (String, HeaderResolverContext) -> Unit): Boolean =
    registry.install(registry.headerResolver, owner, resolver)

  fun clearHeaderResolver() = registry.clear(registry.headerResolver, owner)

  /** @return false when this hybrid may no longer install. */
  fun setBeforeDispatch(hook: (String, BeforeDispatchContext) -> Unit): Boolean =
    registry.install(registry.beforeDispatch, owner, hook)

  fun clearBeforeDispatch() = registry.clear(registry.beforeDispatch, owner)

  /** @return false when this hybrid may no longer install. */
  fun setAfterDispatch(hook: (String, AfterDispatchContext) -> Unit): Boolean =
    registry.install(registry.afterDispatch, owner, hook)

  fun clearAfterDispatch() = registry.clear(registry.afterDispatch, owner)

  /**
   * The hybrid is torn down: it is retired. Its waits end at once, and it installs or clears
   * nothing more. Its installs stay as orphans until a newer hybrid attaches. Each still counts
   * as installed, but a request for one ends at once without calling JS: the retry decider
   * leaves the decision to the policy, the header resolver and `beforeDispatch` keep the item
   * from going out in that cycle, and `afterDispatch` is only logged.
   */
  fun retire() = callbacks.retire(owner)
}
