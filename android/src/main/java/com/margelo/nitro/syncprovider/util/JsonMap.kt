package com.margelo.nitro.syncprovider.util

import org.json.JSONObject

/**
 * Lightweight `Map<String, String>` ↔ JSON encoder built on `org.json` to
 * avoid pulling in `kotlinx-serialization` for what is essentially a tiny
 * persistence concern (HTTP headers and metadata blobs).
 */
internal object JsonMap {
  fun encode(map: Map<String, String>?): String? {
    if (map.isNullOrEmpty()) return null
    val obj = JSONObject()
    for ((k, v) in map) obj.put(k, v)
    return obj.toString()
  }

  fun decode(raw: String?): Map<String, String>? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
      val obj = JSONObject(raw)
      val out = LinkedHashMap<String, String>(obj.length())
      val keys = obj.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        out[key] = obj.optString(key, "")
      }
      out
    }.getOrNull()
  }
}
