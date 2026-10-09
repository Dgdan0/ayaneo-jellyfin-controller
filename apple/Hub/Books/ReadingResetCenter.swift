import Foundation
import HubKit

/// Start over (#60) on this device: the one place that applies the hub's
/// stamps (Android's `ReadingProgress.noticeReset`).
///
/// Every read of a book's place goes through it: the work's own page, the
/// ebook and listening positions, and a comic's page list. A stamp this
/// device has not applied drops what it kept of the book:
/// - its checkpoints of every kind, and so the outbox (`ReadingResets`);
/// - a comic's place on the device and its kept page lists;
/// - a finish marked here and the lists' progress, which each window's
///   `BooksModel` forgets when it hears `.readingStartedOver`.
///
/// The writes carry the stamp applied (`seen`).
enum ReadingResetCenter {
    /// The stamps applied. The demo hub forgets its start-overs when the app
    /// starts again, as it forgets every place, so the demo's are in memory.
    static let resets = ReadingResets(
        ledger: ProcessInfo.processInfo.arguments.contains("-demo") ? MemoryResetLedger() : DefaultsResetLedger(),
        store: ListeningStore.shared)

    /// The hub says `workId` was started over at `resetAt`, for the hub and
    /// profile `scope`: applied once a stamp. True when it was news here.
    @discardableResult
    static func notice(scope: String, workId: String, resetAt: Int64) -> Bool {
        resets.apply(scope: scope, workId: workId, resetAt: resetAt) { alsoDrop(scope: scope, workId: workId) }
    }

    /// The stamp of `workId` this device has applied, for a write.
    static func seen(scope: String, workId: String) -> Int64 {
        resets.seen(scope: scope, workId: workId)
    }

    /// What `ReadingResets` does not keep itself.
    static func alsoDrop(scope: String, workId: String) {
        UserDefaults.standard.removeObject(forKey: ComicReaderSettings.placeKey(workId: workId))
        ReadingManifestCache(root: ReadingOffline.manifestsRoot).dropPlace(workId: workId)
        NotificationCenter.default.post(name: .readingStartedOver, object: nil,
                                        userInfo: [ReadingResetCenter.scopeKey: scope, ReadingResetCenter.workKey: workId])
    }

    static let scopeKey = "scope"
    static let workKey = "workId"
}

extension Notification.Name {
    /// A book was started over and this device applied it (#60): its marks go.
    static let readingStartedOver = Notification.Name("readingStartedOver")
}
