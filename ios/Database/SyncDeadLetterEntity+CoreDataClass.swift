//
//  SyncDeadLetterEntity+CoreDataClass.swift
//  SyncProvider
//
//  Explicit `NSManagedObject` subclass for `SyncDeadLetterEntity`: an item that
//  failed for good, moved out of the queue with its request and its failure.
//

import CoreData
import Foundation

@objc(SyncDeadLetterEntity)
final class SyncDeadLetterEntity: NSManagedObject {
    @nonobjc class func fetchRequest() -> NSFetchRequest<SyncDeadLetterEntity> {
        return NSFetchRequest<SyncDeadLetterEntity>(entityName: "SyncDeadLetterEntity")
    }

    @NSManaged var attempts: Int32
    @NSManaged var body: String?
    @NSManaged var contentType: String?
    @NSManaged var createdAt: Int64
    @NSManaged var deadLetteredAt: Int64
    @NSManaged var headersJSON: Data?
    @NSManaged var id: String?
    @NSManaged var lastAttemptAt: Int64
    @NSManaged var lastErrorCode: String?
    @NSManaged var lastErrorMessage: String?
    @NSManaged var metadataJSON: Data?
    @NSManaged var method: String?
    @NSManaged var priority: String?
    @NSManaged var url: String?
}
