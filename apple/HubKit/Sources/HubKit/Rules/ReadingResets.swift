import Foundation

/// Start over (#60) as this device keeps it (Android's `ReadingResets`): one
/// stamp per hub, profile and book, the last start over it has applied.
///
/// The hub says, with every read of a book's place (`resetAt`, on the work's
/// own page, both position routes and a comic's page list), when the book was
/// last started over. A device that has not applied that stamp drops what it
/// kept of the place:
/// - its checkpoints, every kind, and with them the outbox;
/// - whatever `alsoDrop` adds (a comic's place, a kept page list, a finish
///   marked here).
///
/// Every write then carries the stamp it has applied (`resetSeen`). A write
/// made from a place since started over is refused as `reading_position_reset`,
/// and the app reads the book again instead of retrying.
public final class ReadingResets: Sendable {
    /// The hub's code for a write made from a book since started over.
    public static let code = "reading_position_reset"

    private let ledger: any ReadingResetLedger
    private let store: ReadingCheckpointStore
    private let lock = NSLock()

    public init(ledger: any ReadingResetLedger, store: ReadingCheckpointStore) {
        self.ledger = ledger
        self.store = store
    }

    /// The start over of `workId` this device has applied, 0 for none.
    public func seen(scope: String, workId: String) -> Int64 {
        ledger.get(Self.key(scope: scope, workId: workId))
    }

    /// The hub says `workId` was started over at `resetAt`. When that is news
    /// here, every place this device kept of the book goes, `alsoDrop` forgets
    /// the rest, and the stamp is kept, last, so an interrupted drop is done
    /// again. Done once a stamp: a place read after it stays. True when it was news.
    @discardableResult
    public func apply(scope: String, workId: String, resetAt: Int64, alsoDrop: () -> Void = {}) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard resetAt > 0, resetAt > seen(scope: scope, workId: workId) else { return false }
        store.dropWork(scope: scope, workId: workId)
        alsoDrop()
        ledger.put(Self.key(scope: scope, workId: workId), resetAt)
        return true
    }

    static func key(scope: String, workId: String) -> String {
        ReadingCheckpointKey.digest("reset\u{0}" + scope + "\u{0}" + workId)
    }
}

/// Where the stamps are kept.
public protocol ReadingResetLedger: Sendable {
    func get(_ key: String) -> Int64
    func put(_ key: String, _ value: Int64)
}

/// In the app's defaults, beside the books' other marks.
public final class DefaultsResetLedger: ReadingResetLedger, @unchecked Sendable {
    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func get(_ key: String) -> Int64 {
        (defaults.object(forKey: "reading.reset." + key) as? NSNumber)?.int64Value ?? 0
    }

    public func put(_ key: String, _ value: Int64) {
        defaults.set(NSNumber(value: value), forKey: "reading.reset." + key)
    }
}

/// In memory: the demo hub's, which forgets its start-overs, as it forgets
/// every place, when the app starts again.
public final class MemoryResetLedger: ReadingResetLedger, @unchecked Sendable {
    private var stamps: [String: Int64] = [:]
    private let lock = NSLock()

    public init() {}

    public func get(_ key: String) -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        return stamps[key] ?? 0
    }

    public func put(_ key: String, _ value: Int64) {
        lock.lock()
        defer { lock.unlock() }
        stamps[key] = value
    }
}
