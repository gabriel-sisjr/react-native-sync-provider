//
//  SyncItemEntity+CoreDataClass.swift
//  SyncProvider
//
//  Explicit `NSManagedObject` subclass for `SyncItemEntity`. The model file
//  declares `codeGenerationType="class"` for IDE convenience, but we ship an
//  explicit definition so the CocoaPods build pipeline does not race against
//  Xcode's runtime codegen.
//

import CoreData
import Foundation

@objc(SyncItemEntity)
final class SyncItemEntity: NSManagedObject {
    @nonobjc class func fetchRequest() -> NSFetchRequest<SyncItemEntity> {
        return NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
    }

    @NSManaged var attempts: Int32
    @NSManaged var body: String?
    @NSManaged var contentType: String?
    @NSManaged var createdAt: Int64
    @NSManaged var headersJSON: Data?
    @NSManaged var id: String?
    @NSManaged var lastAttemptAt: Int64
    @NSManaged var lastErrorCode: String?
    @NSManaged var metadataJSON: Data?
    @NSManaged var method: String?
    @NSManaged var priority: String?
    @NSManaged var status: String?
    @NSManaged var url: String?
}
