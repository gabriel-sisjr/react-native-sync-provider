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
public final class SyncItemEntity: NSManagedObject {
    @nonobjc public class func fetchRequest() -> NSFetchRequest<SyncItemEntity> {
        return NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
    }

    @NSManaged public var attempts: Int32
    @NSManaged public var body: String?
    @NSManaged public var contentType: String?
    @NSManaged public var createdAt: Int64
    @NSManaged public var headersJSON: Data?
    @NSManaged public var id: String?
    @NSManaged public var lastAttemptAt: Int64
    @NSManaged public var lastErrorCode: String?
    @NSManaged public var metadataJSON: Data?
    @NSManaged public var method: String?
    @NSManaged public var priority: String?
    @NSManaged public var status: String?
    @NSManaged public var url: String?
}
