//
//  BackgroundSyncOptionsStore.swift
//  SyncProvider
//
//  Keeps `BackgroundSyncOptions` across relaunches, so a handler launched by
//  the OS with no JS can honor `foregroundOnly` and continue the BGTask chain.
//

import Foundation

/// UserDefaults persistence for ``BackgroundSyncOptionsValue``.
///
/// The record keeps `minimumIntervalMs` as the call gave it, NaN and
/// ±Infinity included, and the number is converted where it is read. JSON has
/// no number for those three, so they are written as the strings
/// ``SyncConfigStore`` writes. Without that the encoder refused such options,
/// the save was skipped, and a cold BGTask launch read the options from
/// before the call. A record without such a string reads the same as before.
struct BackgroundSyncOptionsStore: @unchecked Sendable {
    private enum Key {
        static let options = "background.options.v1"
        static let enabled = "background.enabled"
    }

    private static let positiveInfinity = "Infinity"
    private static let negativeInfinity = "-Infinity"
    private static let notANumber = "NaN"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .syncProvider) {
        self.defaults = defaults
    }

    private static func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.nonConformingFloatEncodingStrategy = .convertToString(positiveInfinity: positiveInfinity,
                                                                      negativeInfinity: negativeInfinity,
                                                                      nan: notANumber)
        return encoder
    }

    private static func makeDecoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.nonConformingFloatDecodingStrategy = .convertFromString(positiveInfinity: positiveInfinity,
                                                                        negativeInfinity: negativeInfinity,
                                                                        nan: notANumber)
        return decoder
    }

    func load() -> (options: BackgroundSyncOptionsValue?, enabled: Bool) {
        let options = defaults.data(forKey: Key.options).flatMap {
            try? Self.makeDecoder().decode(BackgroundSyncOptionsValue.self, from: $0)
        }
        return (options, defaults.bool(forKey: Key.enabled))
    }

    func save(options: BackgroundSyncOptionsValue?, enabled: Bool) {
        if let options {
            do {
                defaults.set(try Self.makeEncoder().encode(options), forKey: Key.options)
            } catch {
                SyncLogger.error("Could not save the background-sync options: \(error.localizedDescription)",
                                 category: "background")
            }
        }
        defaults.set(enabled, forKey: Key.enabled)
    }
}
