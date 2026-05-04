import CoreData
import Foundation
@testable import SyncProvider

/// Test helper. Constructs a fresh in-memory Core Data stack on every call so
/// XCTest cases stay independent.
enum InMemoryCoreDataStack {
    /// Build the `NSManagedObjectModel` programmatically. We mirror the model
    /// declared in `ios/Database/SyncProvider.xcdatamodeld/.../contents` — the
    /// XML file is not bundled with the unit-test target, so the production
    /// `CoreDataStack.resolveBundle()` path cannot find the `.momd`.
    static func makeModel() -> NSManagedObjectModel {
        let model = NSManagedObjectModel()

        let item = NSEntityDescription()
        item.name = "SyncItemEntity"
        item.managedObjectClassName = "SyncItemEntity"

        item.properties = [
            attribute("attempts", type: .integer32AttributeType, optional: false, defaultValue: 0),
            attribute("body", type: .stringAttributeType, optional: true),
            attribute("contentType", type: .stringAttributeType, optional: true),
            attribute("createdAt", type: .integer64AttributeType, optional: false, defaultValue: 0),
            attribute("headersJSON", type: .binaryDataAttributeType, optional: true),
            attribute("id", type: .stringAttributeType, optional: false),
            attribute("lastAttemptAt", type: .integer64AttributeType, optional: true, usesScalar: false),
            attribute("lastErrorCode", type: .stringAttributeType, optional: true),
            attribute("metadataJSON", type: .binaryDataAttributeType, optional: true),
            attribute("method", type: .stringAttributeType, optional: false, defaultValue: "POST"),
            attribute("priority", type: .stringAttributeType, optional: false, defaultValue: "NORMAL"),
            attribute("status", type: .stringAttributeType, optional: false, defaultValue: "PENDING"),
            attribute("url", type: .stringAttributeType, optional: false)
        ]

        let result = NSEntityDescription()
        result.name = "SyncResultEntity"
        result.managedObjectClassName = "SyncResultEntity"

        result.properties = [
            attribute("errorMessage", type: .stringAttributeType, optional: true),
            attribute("finishedAt", type: .integer64AttributeType, optional: false, defaultValue: 0),
            attribute("id", type: .stringAttributeType, optional: false),
            attribute("itemsAttempted", type: .integer32AttributeType, optional: false, defaultValue: 0),
            attribute("itemsFailed", type: .integer32AttributeType, optional: false, defaultValue: 0),
            attribute("itemsSucceeded", type: .integer32AttributeType, optional: false, defaultValue: 0),
            attribute("startedAt", type: .integer64AttributeType, optional: false, defaultValue: 0)
        ]

        model.entities = [item, result]
        return model
    }

    /// Build a fresh `CoreDataStack` whose container is loaded against an
    /// in-memory store. The returned stack is safe to pass to
    /// `SyncItemDao(stack:)`.
    static func make(file: StaticString = #file, line: UInt = #line) -> CoreDataStack {
        let model = makeModel()
        let container = NSPersistentContainer(name: "SyncProvider", managedObjectModel: model)

        let description = NSPersistentStoreDescription()
        description.type = NSInMemoryStoreType
        description.url = URL(fileURLWithPath: "/dev/null")
        description.shouldAddStoreAsynchronously = false
        container.persistentStoreDescriptions = [description]

        var loadError: Error?
        container.loadPersistentStores { _, error in
            loadError = error
        }
        if let loadError = loadError {
            fatalError("InMemoryCoreDataStack failed to load store: \(loadError) at \(file):\(line)")
        }

        container.viewContext.automaticallyMergesChangesFromParent = true
        container.viewContext.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy

        return CoreDataStack(container: container)
    }

    // MARK: - Internals

    private static func attribute(_ name: String,
                                  type: NSAttributeType,
                                  optional: Bool,
                                  defaultValue: Any? = nil,
                                  usesScalar: Bool = true) -> NSAttributeDescription {
        let attr = NSAttributeDescription()
        attr.name = name
        attr.attributeType = type
        attr.isOptional = optional
        if let defaultValue = defaultValue {
            attr.defaultValue = defaultValue
        }
        // Match the model file: most numeric attrs use scalar getters/setters.
        attr.allowsExternalBinaryDataStorage = false
        attr.isIndexed = false
        if !usesScalar && type == .integer64AttributeType {
            attr.attributeValueClassName = "NSNumber"
        }
        return attr
    }
}
