package com.margelo.nitro.syncprovider.error

import com.margelo.nitro.syncprovider.SyncErrorCode

/**
 * Native-side counterpart of the JS `SyncError`.
 *
 * Every error that escapes a `Promise<T>` rejection from the SyncProvider
 * native module SHOULD be wrapped in a [SyncException]. Its message is
 * `"<code>: <message>"`, and Nitro carries that message into the rejection.
 *
 * JS does not read the code back: `wrapNativeError` throws a `SyncError` with
 * the failing call's fallback code and keeps this text as its message.
 */
internal class SyncException(
  val code: SyncErrorCode,
  message: String,
  cause: Throwable? = null,
) : RuntimeException("${code.name}: $message", cause) {
  /** Bare message without the `<code>: ` prefix, useful for logging. */
  val rawMessage: String = message
}
