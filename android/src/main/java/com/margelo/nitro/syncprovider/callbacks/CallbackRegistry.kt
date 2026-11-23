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
 * is torn down, may install. That attach evicts every install, since all of them belong to older
 * hybrids: a JS reload never disposes the old hybrid on Android, so the new hybrid's creation is
 * what removes the callbacks of the runtime that is gone. A teardown ([detach]) removes only its
 * own installs, so the late teardown of an old hybrid never touches a newer one's.
 *
 * Two live JS runtimes in one process would evict each other's installs: the one whose hybrid
 * was created last owns the callbacks, and the other one's installs are refused.
 *
 * Thread-safe. A change takes a short lock, never held while JS is called or awaited. Reading an
 * install ([Slot.installed]) takes none.
 */
internal class CallbackRegistry {

  private val lock = Any()

  /** The newest generation handed out, and whether its hybrid was torn down. Guarded by [lock]. */
  private var newest = 0L
  private var newestTornDown = false

  val retryDecider = Slot<(String, RetryDeciderContext) -> Unit>(CallbackKind.RETRY_DECIDER)
  val headerResolver = Slot<(String, HeaderResolverContext) -> Unit>(CallbackKind.HEADER_RESOLVER)
  val beforeDispatch = Slot<(String, BeforeDispatchContext) -> Unit>(CallbackKind.BEFORE_DISPATCH)
  val afterDispatch = Slot<(String, AfterDispatchContext) -> Unit>(CallbackKind.AFTER_DISPATCH)

  private val slots: List<Slot<*>> = listOf(retryDecider, headerResolver, beforeDispatch, afterDispatch)

  /** A hybrid was created. Returns its generation, after evicting every install. */
  fun attach(): Long = synchronized(lock) {
    newest += 1
    newestTornDown = false
    slots.forEach { it.installed = null }
    newest
  }

  /** Whether [owner] is the current hybrid: the newest one, not torn down. */
  fun isLive(owner: Long): Boolean = synchronized(lock) { owner == newest && !newestTornDown }

  /**
   * Install [function] in [slot] for [owner], replacing what is there.
   *
   * @return false, with nothing changed, when [owner] is not the current hybrid.
   */
  fun <F : Any> install(slot: Slot<F>, owner: Long, function: F): Boolean = synchronized(lock) {
    if (owner != newest || newestTornDown) return false
    slot.installed = Installed(owner, function)
    true
  }

  /** Uninstall what [owner] installed in [slot]. Another hybrid's install stays. */
  fun clear(slot: Slot<*>, owner: Long) {
    synchronized(lock) {
      if (slot.installed?.owner == owner) slot.installed = null
    }
  }

  /** [owner] is torn down: its installs are removed, and it installs nothing more. */
  fun detach(owner: Long) {
    synchronized(lock) {
      if (owner == newest) newestTornDown = true
      slots.forEach { if (it.installed?.owner == owner) it.installed = null }
    }
  }

  /** Remove every install. */
  fun clearAll() {
    synchronized(lock) { slots.forEach { it.installed = null } }
  }

  /** The install of one kind. */
  class Slot<F : Any> internal constructor(val kind: CallbackKind) {
    /** What is installed now, or null. Written only by [CallbackRegistry], under its lock. */
    @Volatile
    var installed: Installed<F>? = null
      internal set

    fun isInstalledBy(owner: Long): Boolean = installed?.owner == owner
  }

  /** A JS function and the generation of the hybrid that installed it. */
  class Installed<F : Any>(val owner: Long, val function: F)
}
