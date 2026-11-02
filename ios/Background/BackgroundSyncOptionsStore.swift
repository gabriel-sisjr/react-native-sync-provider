//
//  BackgroundSyncOptionsStore.swift
//  SyncProvider
//
//  Keeps `BackgroundSyncOptions` across relaunches, so a handler launched by
//  the OS with no JS can honor `foregroundOnly` and continue the BGTask chain.
//

import Foundation

/// UserDefaults persistence for ``BackgroundSyncOptionsValue``.
struct BackgroundSyncOptionsStore: @unchecked Sendable {
    private enum Key {
        static let options = "background.options.v1"
        static let enabled = "background.enabled"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .syncProvider) {
        self.defaults = defaults
    }

    func load() -> (options: BackgroundSyncOptionsValue?, enabled: Bool) {
        let options = defaults.data(forKey: Key.options).flatMap {
            try? JSONDecoder().decode(BackgroundSyncOptionsValue.self, from: $0)
        }
        return (options, defaults.bool(forKey: Key.enabled))
    }

    func save(options: BackgroundSyncOptionsValue?, enabled: Bool) {
        if let options, let data = try? JSONEncoder().encode(options) {
            defaults.set(data, forKey: Key.options)
        }
        defaults.set(enabled, forKey: Key.enabled)
    }
}
