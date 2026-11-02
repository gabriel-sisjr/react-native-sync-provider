//
//  SecretStore.swift
//  SyncProvider
//
//  Storage for `defaultHeaders`, which usually carry a bearer token and must
//  not reach UserDefaults. Behind a protocol because hostless XCTest has no
//  Keychain: tests use an in-memory fake.
//

import Foundation
import Security

/// Outcome of reading the stored headers.
enum SecretReadResult: Sendable, Equatable {
    case found([String: String])
    /// Nothing is stored.
    case notFound
    /// Something may be stored but it could not be read: the device has not
    /// been unlocked since boot, or the data is unusable.
    case unavailable(OSStatus)
}

/// Encrypted storage for the default headers.
protocol SecretStore: Sendable {
    func readDefaultHeaders() -> SecretReadResult
    func writeDefaultHeaders(_ headers: [String: String]) throws
    /// Removes the stored headers. Succeeds when nothing is stored.
    func deleteDefaultHeaders() throws
}

/// A Keychain operation failed. Carries the status only, never the data.
struct SecretStoreError: LocalizedError, Equatable {
    let status: OSStatus

    var errorDescription: String? {
        "Keychain operation failed with status \(status)"
    }
}

/// Keychain-backed ``SecretStore``.
///
/// The item is readable after the first unlock since boot, so a BGTask can use
/// it while the device is locked. `ThisDeviceOnly` keeps it out of backups and
/// off other devices.
struct KeychainSecretStore: SecretStore {
    private let service: String
    private let account: String

    init(service: String = "com.gabriel-sisjr.syncprovider", account: String = "defaultHeaders") {
        self.service = service
        self.account = account
    }

    private var itemQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any
        ]
    }

    func readDefaultHeaders() -> SecretReadResult {
        var query = itemQuery
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data,
                  let headers = try? JSONDecoder().decode([String: String].self, from: data) else {
                return .unavailable(errSecDecode)
            }
            return .found(headers)
        case errSecItemNotFound:
            return .notFound
        default:
            return .unavailable(status)
        }
    }

    func writeDefaultHeaders(_ headers: [String: String]) throws {
        let data = try JSONEncoder().encode(headers)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]

        let updateStatus = SecItemUpdate(itemQuery as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess {
            return
        }
        guard updateStatus == errSecItemNotFound else {
            throw SecretStoreError(status: updateStatus)
        }

        let addStatus = SecItemAdd(itemQuery.merging(attributes) { _, new in new } as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw SecretStoreError(status: addStatus)
        }
    }

    func deleteDefaultHeaders() throws {
        let status = SecItemDelete(itemQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw SecretStoreError(status: status)
        }
    }
}
