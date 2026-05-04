//
//  SyncResultEntity+CoreDataClass.swift
//  SyncProvider
//
//  Explicit `NSManagedObject` subclass for `SyncResultEntity`.
//

import CoreData
import Foundation

@objc(SyncResultEntity)
public final class SyncResultEntity: NSManagedObject {
    @nonobjc public class func fetchRequest() -> NSFetchRequest<SyncResultEntity> {
        return NSFetchRequest<SyncResultEntity>(entityName: "SyncResultEntity")
    }

    @NSManaged public var errorMessage: String?
    @NSManaged public var finishedAt: Int64
    @NSManaged public var id: String?
    @NSManaged public var itemsAttempted: Int32
    @NSManaged public var itemsFailed: Int32
    @NSManaged public var itemsSucceeded: Int32
    @NSManaged public var startedAt: Int64
}
