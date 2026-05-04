//
//  ULID.swift
//  SyncProvider
//
//  Lightweight, dependency-free ULID generator. Produces 26-character
//  Crockford base32 strings that are lexicographically sortable by
//  generation time.
//
//  Spec: https://github.com/ulid/spec
//

import Foundation
import Security

/// Pure-Swift ULID generator.
///
/// Monotonicity is guaranteed across calls within the same millisecond on the
/// same device — when the generator is asked for two ULIDs in the same
/// millisecond, the second one increments the random component of the first
/// rather than reseeding.
enum ULID {
    private static let crockfordAlphabet: [Character] = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    /// Serialization queue protecting `lastTimestampMs` and `lastRandom`.
    private static let queue = DispatchQueue(label: "com.gabriel-sisjr.syncprovider.ulid")
    private static var lastTimestampMs: UInt64 = 0
    private static var lastRandom: [UInt8] = [UInt8](repeating: 0, count: 10)

    /// Generate a new ULID using the current wall clock.
    static func generate() -> String {
        return queue.sync {
            let nowMs = UInt64(Date().timeIntervalSince1970 * 1000)
            let timestampMs = max(nowMs, lastTimestampMs)

            var random = [UInt8](repeating: 0, count: 10)
            if timestampMs == lastTimestampMs {
                // Same ms — increment the previous random bytes for monotonicity.
                random = lastRandom
                incrementRandom(&random)
            } else {
                let status = SecRandomCopyBytes(kSecRandomDefault, random.count, &random)
                if status != errSecSuccess {
                    // Fallback: fill from arc4random_buf — never expected to fail.
                    arc4random_buf(&random, random.count)
                }
            }

            lastTimestampMs = timestampMs
            lastRandom = random

            return encode(timestampMs: timestampMs, random: random)
        }
    }

    // MARK: - Internals

    private static func incrementRandom(_ bytes: inout [UInt8]) {
        for i in (0..<bytes.count).reversed() {
            if bytes[i] == 0xFF {
                bytes[i] = 0
            } else {
                bytes[i] &+= 1
                return
            }
        }
        // All bytes overflowed — reseed.
        var fresh = [UInt8](repeating: 0, count: bytes.count)
        let status = SecRandomCopyBytes(kSecRandomDefault, fresh.count, &fresh)
        if status != errSecSuccess {
            arc4random_buf(&fresh, fresh.count)
        }
        bytes = fresh
    }

    private static func encode(timestampMs: UInt64, random: [UInt8]) -> String {
        // 10 chars (50 bits) for timestamp + 16 chars (80 bits) for random = 26 chars total.
        var output = [Character]()
        output.reserveCapacity(26)

        // Timestamp: encode 48 bits as 10 chars of base32 (5 bits per char).
        // The leading 2 bits of the first char are always 0 since 48 bits fit in 10*5=50 bits.
        var ts = timestampMs & 0x0000_FFFF_FFFF_FFFF
        var tsChars = [Character](repeating: "0", count: 10)
        for i in (0..<10).reversed() {
            tsChars[i] = crockfordAlphabet[Int(ts & 0x1F)]
            ts >>= 5
        }
        output.append(contentsOf: tsChars)

        // Random: 80 bits as 16 chars (5 bits per char).
        // Pack the 10 random bytes into a 80-bit big-endian buffer, then chunk by 5 bits.
        var bits: UInt64 = 0
        var bitsHigh: UInt64 = 0

        // Build a 128-bit conceptual buffer where the top 80 bits are the random bytes.
        // We'll iterate the 10 bytes and pull 5-bit groups.
        // Simpler: emit 16 chars by reading bit positions [0..80).
        var randomChars = [Character](repeating: "0", count: 16)
        for i in 0..<16 {
            let bitOffset = i * 5
            let value = bitSlice(of: random, startBit: bitOffset, length: 5)
            randomChars[i] = crockfordAlphabet[Int(value & 0x1F)]
        }
        output.append(contentsOf: randomChars)

        // Silence unused warnings from the placeholder bits buffer above.
        _ = bits
        _ = bitsHigh

        return String(output)
    }

    /// Read `length` bits from a big-endian byte buffer starting at `startBit`.
    private static func bitSlice(of bytes: [UInt8], startBit: Int, length: Int) -> UInt64 {
        precondition(length > 0 && length <= 32, "bitSlice length out of range")
        var result: UInt64 = 0
        for i in 0..<length {
            let bitIndex = startBit + i
            let byteIndex = bitIndex / 8
            let bitInByte = 7 - (bitIndex % 8)
            guard byteIndex < bytes.count else { break }
            let bit = (bytes[byteIndex] >> UInt8(bitInByte)) & 0x01
            result = (result << 1) | UInt64(bit)
        }
        return result
    }
}
