//
//  HeaderMerge.swift
//  SyncProvider
//
//  How the header layers of one request combine, for the request itself and
//  for what `beforeDispatch` is shown of it.
//

import Foundation

/// Merges the header layers of one request: `defaultHeaders`, then the header
/// resolver's, then the item's own. A later layer wins. Names compare
/// case-insensitively, as HTTP and `URLRequest` compare them, and a name keeps
/// the spelling of the layer that won.
///
/// The `Content-Type` that an item's `contentType` supplies when no layer sets
/// one is not part of the merge: the request adds it, and `beforeDispatch`
/// sees it as its own `contentType` field. An explicit `Content-Type` in a
/// layer is merged like any other header.
enum HeaderMerge {
    /// `layers`, lowest precedence first. A `nil` layer adds nothing. Within one
    /// layer, names that differ only by case are applied in sorted order, so
    /// the result never depends on dictionary order.
    static func merge(_ layers: [String: String]?...) -> [String: String] {
        var merged: [String: String] = [:]
        // A lowercased name, to the spelling it has in `merged`.
        var spellings: [String: String] = [:]
        for layer in layers {
            guard let layer else { continue }
            for (name, value) in layer.sorted(by: { $0.key < $1.key }) {
                let folded = name.lowercased()
                if let previous = spellings[folded] {
                    merged.removeValue(forKey: previous)
                }
                merged[name] = value
                spellings[folded] = name
            }
        }
        return merged
    }
}
