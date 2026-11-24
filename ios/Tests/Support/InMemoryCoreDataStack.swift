import CoreData
import Foundation
@testable import SyncProvider

/// Test helper. Constructs a fresh in-memory Core Data stack on every call so
/// XCTest cases stay independent.
///
/// The store is SQLite at `/dev/null`, which SQLite keeps in memory.
/// `NSInMemoryStoreType` is not an option: it crashes on `NSBatchDeleteRequest`,
/// which production uses to clear the queue and the history, and it evaluates
/// predicates in Swift-side code rather than in SQL.
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
            attribute("lastAttemptAt", type: .integer64AttributeType, optional: false, defaultValue: 0),
            attribute("lastErrorCode", type: .stringAttributeType, optional: true),
            attribute("metadataJSON", type: .binaryDataAttributeType, optional: true),
            attribute("method", type: .stringAttributeType, optional: false, defaultValue: "POST"),
            // Added in Core Data model v2 (`SyncProvider 2.xcdatamodel`). Every
            // schema change is made twice: real model + this test model.
            attribute("nextRetryAt", type: .integer64AttributeType, optional: false, defaultValue: 0),
            attribute("priority", type: .stringAttributeType, optional: false, defaultValue: "NORMAL"),
            attribute("status", type: .stringAttributeType, optional: false, defaultValue: "PENDING"),
            attribute("url", type: .stringAttributeType, optional: false)
        ]
        item.uniquenessConstraints = [["id"]]

        // Added in Core Data model v3 (`SyncProvider 3.xcdatamodel`).
        let deadLetter = NSEntityDescription()
        deadLetter.name = "SyncDeadLetterEntity"
        deadLetter.managedObjectClassName = "SyncDeadLetterEntity"
        let deadLetteredAt = attribute("deadLetteredAt", type: .integer64AttributeType, optional: false, defaultValue: 0)
        deadLetter.properties = [
            attribute("attempts", type: .integer32AttributeType, optional: false, defaultValue: 0),
            attribute("body", type: .stringAttributeType, optional: true),
            attribute("contentType", type: .stringAttributeType, optional: true),
            attribute("createdAt", type: .integer64AttributeType, optional: false, defaultValue: 0),
            deadLetteredAt,
            attribute("headersJSON", type: .binaryDataAttributeType, optional: true),
            attribute("id", type: .stringAttributeType, optional: false),
            attribute("lastAttemptAt", type: .integer64AttributeType, optional: false, defaultValue: 0),
            attribute("lastErrorCode", type: .stringAttributeType, optional: false, defaultValue: "MAX_ATTEMPTS_EXCEEDED"),
            attribute("lastErrorMessage", type: .stringAttributeType, optional: true),
            attribute("metadataJSON", type: .binaryDataAttributeType, optional: true),
            attribute("method", type: .stringAttributeType, optional: false, defaultValue: "POST"),
            attribute("priority", type: .stringAttributeType, optional: false, defaultValue: "NORMAL"),
            attribute("url", type: .stringAttributeType, optional: false)
        ]
        deadLetter.uniquenessConstraints = [["id"]]
        deadLetter.indexes = [
            NSFetchIndexDescription(name: "byDeadLetteredAt",
                                    elements: [NSFetchIndexElementDescription(property: deadLetteredAt,
                                                                              collationType: .binary)])
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
        result.uniquenessConstraints = [["id"]]

        model.entities = [item, deadLetter, result]
        return model
    }

    /// Build a fresh `CoreDataStack` whose container is loaded against an
    /// in-memory store. The returned stack is safe to pass to
    /// `SyncItemDao(stack:)`. With `saveFaults`, its saves fail on demand.
    /// With `transactions`, every transaction the stack runs is logged there.
    static func make(saveFaults: SaveFaults? = nil,
                     transactions: TransactionLog? = nil,
                     file: StaticString = #file,
                     line: UInt = #line) -> CoreDataStack {
        let model = makeModel()
        let container: NSPersistentContainer
        if let transactions {
            container = LoggedPersistentContainer(name: "SyncProvider", managedObjectModel: model, log: transactions)
        } else {
            container = NSPersistentContainer(name: "SyncProvider", managedObjectModel: model)
        }

        let description = NSPersistentStoreDescription()
        description.type = NSSQLiteStoreType
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

        guard let saveFaults else { return CoreDataStack(container: container) }
        return CoreDataStack(container: container, beforeSave: { try saveFaults.check() })
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
        if !usesScalar && type == .integer64AttributeType {
            attr.attributeValueClassName = "NSNumber"
        }
        return attr
    }
}

/// Fails a stack's saves on demand, the way a store that cannot write does:
/// the transaction throws and nothing is written. Pass it to
/// ``InMemoryCoreDataStack/make(saveFaults:file:line:)``. A save is counted
/// only when its transaction changed something.
final class SaveFaults: @unchecked Sendable {
    private let lock = NSLock()
    private var toSkip = 0
    private var toFail = 0
    private var failed = 0
    private var interceptSkip = 0
    private var interception: (() throws -> Void)?

    /// Lets the next `skipped` saves through, then fails the `count` after
    /// them. `Int.max` fails every one.
    func fail(_ count: Int, after skipped: Int = 0) {
        lock.withLock {
            toSkip = skipped
            toFail = count
        }
    }

    /// Runs `action` in the save after the next `skipped` ones, once, before
    /// it commits, on the transaction's queue. The save goes on unless
    /// `action` throws. Counted apart from ``fail(_:after:)``.
    func intercept(after skipped: Int = 0, _ action: @escaping () throws -> Void) {
        lock.withLock {
            interceptSkip = skipped
            interception = action
        }
    }

    /// The saves failed so far.
    var failures: Int { lock.withLock { failed } }

    func check() throws {
        let action = lock.withLock { () -> (() throws -> Void)? in
            guard let pending = interception else { return nil }
            if interceptSkip > 0 {
                interceptSkip -= 1
                return nil
            }
            interception = nil
            return pending
        }
        try action?()

        let fails = lock.withLock { () -> Bool in
            if toSkip > 0 {
                toSkip -= 1
                return false
            }
            guard toFail > 0 else { return false }
            if toFail != .max {
                toFail -= 1
            }
            failed += 1
            return true
        }
        if fails {
            throw NSError(domain: NSCocoaErrorDomain,
                          code: NSPersistentStoreSaveError,
                          userInfo: [NSLocalizedDescriptionKey: "A save the test made fail"])
        }
    }
}

/// What a stack's transactions did. Pass it to
/// ``InMemoryCoreDataStack/make(saveFaults:transactions:file:line:)``.
final class TransactionLog: @unchecked Sendable {
    private let lock = NSLock()
    private var started = 0
    private var unsaved: [String: Int64] = [:]

    /// The transactions started since the last ``reset()``: every background
    /// context the stack asked for, a read's or a write's.
    var count: Int {
        lock.withLock { started }
    }

    /// The `nextRetryAt` the last transaction whose save failed tried to give
    /// the queue row `id`, if one did.
    func unsavedNextRetryAt(_ id: String) -> Int64? {
        lock.withLock { unsaved[id] }
    }

    func reset() {
        lock.withLock { started = 0 }
    }

    fileprivate func recordStart() {
        lock.withLock { started += 1 }
    }

    /// Keeps what `context` still holds after its transaction. Runs on the
    /// context's queue.
    fileprivate func recordUnsaved(_ context: NSManagedObjectContext) {
        var deadlines: [String: Int64] = [:]
        for object in context.updatedObjects where object.entity.name == "SyncItemEntity" {
            if let id = object.value(forKey: "id") as? String,
               let nextRetryAt = (object.value(forKey: "nextRetryAt") as? NSNumber)?.int64Value {
                deadlines[id] = nextRetryAt
            }
        }
        lock.withLock { unsaved.merge(deadlines) { $1 } }
    }
}

/// A container that tells a ``TransactionLog`` about every transaction. Its
/// `performBackgroundTask` makes its context through `newBackgroundContext()`,
/// a private-queue context as the system's does, so each transaction is
/// counted once, and it keeps what a transaction whose save failed left in
/// its context.
private final class LoggedPersistentContainer: NSPersistentContainer, @unchecked Sendable {
    private let log: TransactionLog

    init(name: String, managedObjectModel model: NSManagedObjectModel, log: TransactionLog) {
        self.log = log
        super.init(name: name, managedObjectModel: model)
    }

    override func newBackgroundContext() -> NSManagedObjectContext {
        log.recordStart()
        return super.newBackgroundContext()
    }

    override func performBackgroundTask(_ block: @escaping (NSManagedObjectContext) -> Void) {
        let context = newBackgroundContext()
        let log = self.log
        context.perform {
            block(context)
            // A save that failed leaves its changes in the context.
            if context.hasChanges {
                log.recordUnsaved(context)
            }
        }
    }
}
