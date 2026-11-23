package com.margelo.nitro.syncprovider.callbacks

import com.margelo.nitro.syncprovider.BeforeDispatchAction
import com.margelo.nitro.syncprovider.CallbackAnswer
import com.margelo.nitro.syncprovider.RetryDecision

/**
 * What a RESOLVED answer of each kind must carry for native to use it. An answer without it is
 * treated as FAILED, and the caller's fallback applies. Fields for other kinds are ignored.
 * An unknown enum value never reaches here: the bridge rejects it at the JS call site, and the
 * facade then answers FAILED.
 */
internal object CallbackAnswers {

  /**
   * The retry decider's verdict. Its `delayMs` is not judged here: only RETRY reads it, and a
   * missing or unusable one means the policy's delay, not a failed answer.
   */
  fun decision(answer: CallbackAnswer): RetryDecision? = answer.decision

  /** The header resolver's headers, possibly none. One header OkHttp would refuse fails them all. */
  fun headers(answer: CallbackAnswer): Map<String, String>? = answer.headers?.takeIf(::areSendable)

  /** What `beforeDispatch` decided. */
  fun beforeDispatch(answer: CallbackAnswer): BeforeDispatchAction? = answer.beforeDispatch?.action

  /** Whether OkHttp sends every one of [headers] as it is. */
  fun areSendable(headers: Map<String, String>): Boolean =
    headers.all { (name, value) -> isHeaderName(name) && isHeaderValue(value) }

  /** OkHttp's rule for a header name: not empty, and only `!` to `~` (0x21-0x7E). */
  fun isHeaderName(name: String): Boolean = name.isNotEmpty() && name.all { it in '!'..'~' }

  /** OkHttp's rule for a header value: only TAB and 0x20-0x7E. */
  fun isHeaderValue(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }
}
