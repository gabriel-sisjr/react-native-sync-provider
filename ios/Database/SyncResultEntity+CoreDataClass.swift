//
//  SyncResultEntity+CoreDataClass.swift
//  SyncProvider
//
//  Explicit `NSManagedObject` subclass for `SyncResultEntity`.
//

import CoreData
import Foundation

@objc(SyncResultEntity)
final class SyncResultEntity: NSManagedObject {
    @nonobjc class func fetchRequest() -> NSFetchRequest<SyncResultEntity> {
        return NSFetchRequest<SyncResultEntity>(entityName: "SyncResultEntity")
    }

    @NSManaged var errorMessage: String?
    @NSManaged var finishedAt: Int64
    @NSManaged var id: String?
    @NSManaged var itemsAttempted: Int32
    @NSManaged var itemsFailed: Int32
    @NSManaged var itemsSucceeded: Int32
    @NSManaged var startedAt: Int64
}
