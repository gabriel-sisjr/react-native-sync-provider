package com.margelo.nitro.syncprovider.events

import android.util.Log
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncEvent
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.error.SyncException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Multiplexed event emitter that fans out [SyncEvent] payloads to every
 * subscriber registered via the Nitro `addListener` / `removeListener` pair.
 *
 * Subscribers are keyed by `(channel, subscriptionId)`. Currently only the
 * `"sync-event"` channel is accepted; any other name throws
 * [SyncErrorCode.INVALID_PAYLOAD].
 *
 * Callbacks are dispatched on the **Main** dispatcher to mirror React
 * Native's expectation that JS thread interactions happen there. The native
 * call site can `emit(...)` from any thread.
 */
internal class SyncEventEmitter {

  private val mutex = Mutex()
  private val listeners: MutableMap<String, MutableMap<String, (SyncEvent) -> Unit>> = mutableMapOf()

  private val _events = MutableSharedFlow<SyncEvent>(
    replay = 0,
    extraBufferCapacity = 64,
    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
  )
  val events: SharedFlow<SyncEvent> = _events.asSharedFlow()

  /**
   * Register a callback for a given channel.
   *
   * @return a subscription id that must be passed to [remove].
   * @throws SyncException with [SyncErrorCode.INVALID_PAYLOAD] when [channel]
   *   is unknown.
   */
  suspend fun add(channel: String, callback: (SyncEvent) -> Unit): String {
    requireKnownChannel(channel)
    val id = UUID.randomUUID().toString()
    mutex.withLock {
      listeners.getOrPut(channel) { mutableMapOf() }[id] = callback
    }
    return id
  }

  /**
   * Cancel a previously registered subscription. No-op if [subscriptionId]
   * does not exist.
   */
  suspend fun remove(channel: String, subscriptionId: String) {
    requireKnownChannel(channel)
    mutex.withLock {
      listeners[channel]?.remove(subscriptionId)
    }
  }

  /**
   * Fan out [event] to every registered listener and to the internal
   * [SharedFlow]. Errors thrown by individual callbacks are swallowed and
   * logged so one misbehaving listener cannot break the whole pipeline.
   */
  suspend fun emit(event: SyncEvent) {
    _events.tryEmit(event)
    val snapshot: List<(SyncEvent) -> Unit> = mutex.withLock {
      listeners[CHANNEL_SYNC_EVENT]?.values?.toList().orEmpty()
    }
    if (snapshot.isEmpty()) return
    withContext(Dispatchers.Main) {
      for (cb in snapshot) {
        try {
          cb(event)
        } catch (t: Throwable) {
          Log.e(TAG, "listener threw for ${event.type.name}", t)
        }
      }
    }
  }

  /** Remove every subscription. Used on module teardown. */
  suspend fun clear() {
    mutex.withLock { listeners.clear() }
  }

  private fun requireKnownChannel(channel: String) {
    if (channel != CHANNEL_SYNC_EVENT) {
      throw SyncException(
        SyncErrorCode.INVALID_PAYLOAD,
        "Unknown event channel: '$channel'. Only '$CHANNEL_SYNC_EVENT' is supported.",
      )
    }
  }

  companion object {
    private const val TAG = "SyncEventEmitter"
    private const val CHANNEL_SYNC_EVENT = "sync-event"

    /**
     * Convenience builder for a [SyncEvent] with the timestamp populated to
     * `System.currentTimeMillis()` and only the type set. Other fields can
     * be supplied via the named parameters.
     */
    @Suppress("LongParameterList")
    fun event(
      type: SyncEventType,
      itemId: String? = null,
      progress: Double? = null,
      errorCode: SyncErrorCode? = null,
      statusCode: Int? = null,
      attempt: Int? = null,
      connectionStatus: com.margelo.nitro.syncprovider.ConnectionStatus? = null,
      metadata: Map<String, String>? = null,
    ): SyncEvent = SyncEvent(
      type = type,
      timestamp = System.currentTimeMillis().toDouble(),
      itemId = itemId,
      progress = progress,
      errorCode = errorCode,
      statusCode = statusCode?.toDouble(),
      attempt = attempt?.toDouble(),
      connectionStatus = connectionStatus,
      metadata = metadata,
    )
  }
}
