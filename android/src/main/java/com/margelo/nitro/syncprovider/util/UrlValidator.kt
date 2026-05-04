package com.margelo.nitro.syncprovider.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object UrlValidator {
  /**
   * Validates that [url] is a non-blank absolute http(s) URL. Uses OkHttp's
   * parser so we don't drift from the parser used by the dispatcher.
   */
  fun isValid(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.scheme == "http" || parsed.scheme == "https"
  }
}
