package com.margelo.nitro.syncprovider.callbacks

import com.margelo.nitro.syncprovider.AfterDispatchContext
import com.margelo.nitro.syncprovider.BeforeDispatchContext
import com.margelo.nitro.syncprovider.HeaderResolverContext
import com.margelo.nitro.syncprovider.RetryDeciderContext

/**
 * The JS functions installed through the bridge: at most one per kind, each tagged with the
 * generation of the hybrid that installed it, its owner.
 *
 * Every hybrid takes a generation when it is created ([attach]), and only the newest one, until it
 * is torn down, may install or clear. That attach evicts every install, since all of them belong
 * to older hybrids: a JS reload never disposes the old hybrid on Android, so the new hybrid's
 * creation is what removes the callbacks of the runtime that is gone.
 *
 * A teardown ([retire]) removes nothing. It retires its owner, whose installs stay as orphans
 * until a newer hybrid attaches. An orphan still counts as installed, so an item that would ask
 * it is never sent with the static headers alone. It is never called either: like an owner a
 * newer attach evicted, a retired one is not live ([isLive]), and a request for it ends at once.
 * The late teardown of an old hybrid changes nothing, since a newer attach evicted its installs.
 *
 * Two live JS runtimes in one process would evict each other's installs: the one whose hybrid
 * was created last owns the callbacks, and the other one's installs are refused.
 *
 * Thread-safe. A change takes a short lock, never held while JS is called or awaited. Reading an
 * install ([Slot.installed]) takes none.
 */
internal class CallbackRegistry {

  private val lock = Any()

  /** The newest generation handed out, and whether its hybrid was retired. Guarded by [lock]. */
  private var newest = 0L
  private var newestRetired = false

  val retryDecider = Slot<(String, RetryDeciderContext) -> Unit>(CallbackKind.RETRY_DECIDER)
  val headerResolver = Slot<(String, HeaderResolverContext) -> Unit>(CallbackKind.HEADER_RESOLVER)
  val beforeDispatch = Slot<(String, BeforeDispatchContext) -> Unit>(CallbackKind.BEFORE_DISPATCH)
  val afterDispatch = Slot<(String, AfterDispatchContext) -> Unit>(CallbackKind.AFTER_DISPATCH)

  private val slots: List<Slot<*>> = listOf(retryDecider, headerResolver, beforeDispatch, afterDispatch)

  /** A hybrid was created. Returns its generation, after evicting every install, orphans too. */
  fun attach(): Long = synchronized(lock) {
    newest += 1
    newestRetired = false
    slots.forEach { it.installed = null }
    newest
  }

  /** Whether [owner] is the current hybrid: the newest one, not retired. */
  fun isLive(owner: Long): Boolean = synchronized(lock) { isCurrent(owner) }

  /**
   * Install [function] in [slot] for [owner], replacing what is there.
   *
   * @return false, with nothing changed, when [owner] is not the current hybrid.
   */
  fun <F : Any> install(slot: Slot<F>, owner: Long, function: F): Boolean = synchronized(lock) {
    if (!isCurrent(owner)) return false
    slot.installed = Installed(owner, function)
    true
  }

  /**
   * Uninstall what [owner] installed in [slot], at once, when [owner] is the current hybrid.
   * Another hybrid's install stays, and so does a retired owner's orphan: only a newer attach
   * evicts it.
   */
  fun clear(slot: Slot<*>, owner: Long) {
    synchronized(lock) {
      if (isCurrent(owner) && slot.installed?.owner == owner) slot.installed = null
    }
  }

  /** Whether [owner] is the current hybrid and has an install in [slot]: never for an orphan. */
  fun isInstalledBy(slot: Slot<*>, owner: Long): Boolean =
    synchronized(lock) { isCurrent(owner) && slot.installed?.owner == owner }

  /**
   * [owner] is torn down: it is retired, and installs or clears nothing more. Its installs stay
   * as orphans, never called, until a newer hybrid attaches.
   */
  fun retire(owner: Long) {
    synchronized(lock) {
      if (owner == newest) newestRetired = true
    }
  }

  /** Remove every install. */
  fun clearAll() {
    synchronized(lock) { slots.forEach { it.installed = null } }
  }

  /** Whether [owner] is the newest generation, not retired. Called under [lock]. */
  private fun isCurrent(owner: Long): Boolean = owner == newest && !newestRetired

  /** The install of one kind. */
  class Slot<F : Any> internal constructor(val kind: CallbackKind) {
    /**
     * What is installed now, or null: an orphan included, which the dispatch counts as installed.
     * Written only by [CallbackRegistry], under its lock.
     */
    @Volatile
    var installed: Installed<F>? = null
      internal set
  }

  /** A JS function and the generation of the hybrid that installed it. */
  class Installed<F : Any>(val owner: Long, val function: F)
}
