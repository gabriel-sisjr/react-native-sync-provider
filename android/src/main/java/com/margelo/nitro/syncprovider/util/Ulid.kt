package com.margelo.nitro.syncprovider.util

import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Crockford-base32 ULID generator with monotonic ordering within a single
 * timestamp millisecond.
 *
 * A ULID is 26 chars: 10 chars of timestamp (48 bits, ms since epoch) +
 * 16 chars of randomness (80 bits). Within the same millisecond the random
 * suffix is incremented (rather than re-rolled) to preserve insertion order.
 *
 * Why we don't depend on a third-party ULID lib: keeps the artifact small,
 * removes a transitive dep, and the algorithm is trivial to inline.
 */
internal object Ulid {
  private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
  private const val TIMESTAMP_LEN = 10
  private const val RANDOM_LEN = 16

  private val random = SecureRandom()
  private val lock = ReentrantLock()

  private var lastTimestamp: Long = -1L
  private val lastRandom = ByteArray(10) // 80 bits

  /**
   * Generate a new ULID. Thread-safe.
   *
   * @param timestampMs epoch milliseconds; defaults to `System.currentTimeMillis()`.
   *                    Tests can pass a fixed value.
   * @return a 26-character Crockford-base32 ULID string.
   */
  fun generate(timestampMs: Long = System.currentTimeMillis()): String = lock.withLock {
    val ts = timestampMs.coerceAtLeast(0L)
    val randomBytes = if (ts == lastTimestamp) {
      incrementRandom(lastRandom)
      lastRandom
    } else {
      random.nextBytes(lastRandom)
      lastTimestamp = ts
      lastRandom
    }
    buildString(TIMESTAMP_LEN + RANDOM_LEN) {
      encodeTimestamp(ts, this)
      encodeRandom(randomBytes, this)
    }
  }

  private fun encodeTimestamp(ts: Long, out: StringBuilder) {
    var v = ts
    val chars = CharArray(TIMESTAMP_LEN)
    for (i in TIMESTAMP_LEN - 1 downTo 0) {
      chars[i] = ALPHABET[(v and 0x1FL).toInt()]
      v = v ushr 5
    }
    out.append(chars)
  }

  private fun encodeRandom(bytes: ByteArray, out: StringBuilder) {
    // 10 bytes (80 bits) → 16 base32 chars (5 bits each).
    val bits = LongArray(2)
    bits[0] = ((bytes[0].toLong() and 0xFF) shl 32) or
      ((bytes[1].toLong() and 0xFF) shl 24) or
      ((bytes[2].toLong() and 0xFF) shl 16) or
      ((bytes[3].toLong() and 0xFF) shl 8) or
      (bytes[4].toLong() and 0xFF)
    bits[1] = ((bytes[5].toLong() and 0xFF) shl 32) or
      ((bytes[6].toLong() and 0xFF) shl 24) or
      ((bytes[7].toLong() and 0xFF) shl 16) or
      ((bytes[8].toLong() and 0xFF) shl 8) or
      (bytes[9].toLong() and 0xFF)
    for (half in 0..1) {
      var v = bits[half]
      val chars = CharArray(8)
      for (i in 7 downTo 0) {
        chars[i] = ALPHABET[(v and 0x1FL).toInt()]
        v = v ushr 5
      }
      out.append(chars)
    }
  }

  private fun incrementRandom(bytes: ByteArray) {
    for (i in bytes.indices.reversed()) {
      val updated = ((bytes[i].toInt() and 0xFF) + 1) and 0xFF
      bytes[i] = updated.toByte()
      if (updated != 0) return
    }
    // Overflow (extremely unlikely): re-randomize and bump timestamp by 1ms.
    random.nextBytes(bytes)
    lastTimestamp += 1L
  }
}
