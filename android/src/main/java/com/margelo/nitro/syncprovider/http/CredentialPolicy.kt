package com.margelo.nitro.syncprovider.http

/** How far the current default headers can be trusted by a flush cycle. */
internal enum class CredentialPolicy {
  /** Supplied by JS in this process, or there are none: a 401/403 is terminal. */
  TRUSTED,

  /**
   * Restored from disk. The token may have expired and only the app can renew it,
   * so a 401/403 parks the item instead of failing it.
   */
  PERSISTED,

  /** Configured, but they cannot be recovered right now: do not dispatch at all. */
  UNAVAILABLE,
}
