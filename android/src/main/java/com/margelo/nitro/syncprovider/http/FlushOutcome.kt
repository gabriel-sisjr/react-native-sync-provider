package com.margelo.nitro.syncprovider.http

import com.margelo.nitro.syncprovider.SyncResult

/** What one [SyncDispatcher.flushWithOutcome] call did. */
internal sealed interface FlushOutcome {
  /** What `flush()` resolves with. */
  val result: SyncResult

  /** A cycle ran; [result] is its outcome. */
  data class Ran(override val result: SyncResult) : FlushOutcome

  /** No cycle ran, and [result] is the empty sentinel. */
  data class Skipped(val reason: FlushSkip) : FlushOutcome {
    override val result: SyncResult get() = SyncDispatcher.emptyResult()
  }

  /** The network-quality gate held this flush back. */
  val isHeldByQualityGate: Boolean
    get() = this is Skipped && reason == FlushSkip.NETWORK_QUALITY_TOO_LOW
}

/** Why a flush ran no cycle, in the order [SyncDispatcher.flushWithOutcome] checks. */
internal enum class FlushSkip {
  PAUSED,
  CREDENTIALS_UNAVAILABLE,
  NETWORK_QUALITY_TOO_LOW,
}
