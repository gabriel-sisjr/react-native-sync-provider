package com.margelo.nitro.syncprovider.error

import com.margelo.nitro.syncprovider.SyncErrorCode

/**
 * Native-side counterpart of the JS `SyncError`.
 *
 * Every error that escapes a `Promise<T>` rejection from the SyncProvider
 * native module SHOULD be wrapped in a [SyncException]. The JS facade reads
 * the `code` from `error.message` (Nitro encodes the throwable's message into
 * the rejection) and re-instantiates a `SyncError` instance.
 *
 * The serialized payload is: `"<code>: <message>"`. This convention matches
 * the iOS implementation so JS-side parsing is platform-agnostic.
 */
internal class SyncException(
  val code: SyncErrorCode,
  message: String,
  cause: Throwable? = null,
) : RuntimeException("${code.name}: $message", cause) {
  /** Bare message without the `<code>: ` prefix, useful for logging. */
  val rawMessage: String = message
}
