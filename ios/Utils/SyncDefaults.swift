//
//  SyncDefaults.swift
//  SyncProvider
//
//  The library's own UserDefaults domain, kept apart from the host app's keys.
//  Declared in `PrivacyInfo.xcprivacy` under `CA92.1`.
//

import Foundation

extension UserDefaults {
    /// Non-secret state that must survive a relaunch. Nothing written here may
    /// contain a credential: see `SyncConfigStore` for where those live.
    static let syncProvider = UserDefaults(suiteName: "com.gabriel-sisjr.syncprovider") ?? .standard
}
