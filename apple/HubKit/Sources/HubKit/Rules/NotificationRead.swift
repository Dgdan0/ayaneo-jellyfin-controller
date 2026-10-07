import Foundation

/// What this device has seen of the services' notifications (#36). A port of
/// Android's `state/NotificationReadState` and `settings/NotificationReadStore`,
/// held to the same test cases: the page's unread dots and the bell's count
/// are one answer, so they cannot disagree.
public struct NotificationReadSnapshot: Codable, Equatable, Sendable {
    /// Services whose existing history has been taken as seen.
    public var initializedServices: Set<String>
    public var seenIds: Set<String>
    /// The oldest entry each service has shown so far, to tell backfill from news.
    public var oldestTimes: [String: String]

    public init(initializedServices: Set<String> = [], seenIds: Set<String> = [], oldestTimes: [String: String] = [:]) {
        self.initializedServices = initializedServices
        self.seenIds = seenIds
        self.oldestTimes = oldestTimes
    }
}

public struct NotificationReadResult: Equatable, Sendable {
    public var snapshot: NotificationReadSnapshot
    public var unreadIds: Set<String>
}

/// Pure unread-state transitions; `NotificationReadStore` keeps them.
public enum NotificationReadReducer {
    public static let maxSeenIds = 1_000

    public static func observe(_ snapshot: NotificationReadSnapshot, sections: [NotificationSection]) -> NotificationReadResult {
        var initialized = snapshot.initializedServices
        var seen = snapshot.seenIds
        var oldest = snapshot.oldestTimes

        for section in sections {
            let times = section.items.map(\.occurredAt).filter { !$0.isEmpty }
            let previousOldest = oldest[section.service]
            if !initialized.contains(section.service) && (!section.items.isEmpty || section.state == "up") {
                // Installing an update must not turn the existing history into a full inbox.
                seen.formUnion(section.items.map(\.id))
                initialized.insert(section.service)
            } else if let previousOldest {
                // Raising a history limit exposes older entries; those are backfill, not new.
                for item in section.items where !item.occurredAt.isEmpty && item.occurredAt <= previousOldest {
                    seen.insert(item.id)
                }
            }
            if let observed = times.min() {
                oldest[section.service] = min(previousOldest ?? observed, observed)
            }
        }

        let current = Set(sections.flatMap(\.items).map(\.id))
        return NotificationReadResult(
            snapshot: NotificationReadSnapshot(initializedServices: initialized, seenIds: bounded(seen, currentIds: current),
                                               oldestTimes: oldest),
            unreadIds: current.subtracting(seen))
    }

    /// Caps the stored seen list, never forgetting an id that is still on
    /// screen. Keeping the alphabetically last thousand once dropped something
    /// visible, which came back unread on the next read, and the count flipped
    /// between two answers.
    public static func bounded(_ seen: Set<String>, currentIds: Set<String>, max limit: Int = maxSeenIds) -> Set<String> {
        if seen.count <= limit { return seen }
        let visible = seen.intersection(currentIds)
        let room = Swift.max(limit - visible.count, 0)
        return visible.union(seen.subtracting(visible).sorted().suffix(room))
    }

    public static func markSeen(_ snapshot: NotificationReadSnapshot, id: String) -> NotificationReadSnapshot {
        var next = snapshot
        next.seenIds.insert(id)
        return next
    }

    public static func markAllSeen(_ snapshot: NotificationReadSnapshot, ids: some Collection<String>) -> NotificationReadSnapshot {
        var next = snapshot
        next.seenIds.formUnion(ids)
        return next
    }
}

/// The seen list on this device, one per hub. The snapshot lives in memory
/// and is written through after every change.
public final class NotificationReadStore {
    private var snapshot: NotificationReadSnapshot
    private let save: (NotificationReadSnapshot) -> Void

    public init(snapshot: NotificationReadSnapshot = NotificationReadSnapshot(),
                save: @escaping (NotificationReadSnapshot) -> Void = { _ in }) {
        self.snapshot = snapshot
        self.save = save
    }

    /// Kept in the app's defaults, one entry per hub address (Android keys its
    /// preferences by a hash of the address).
    public static func defaults(_ defaults: UserDefaults = .standard, hub: String) -> NotificationReadStore {
        let key = "notifications.read." + hub
        let stored = defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(NotificationReadSnapshot.self, from: $0) }
        return NotificationReadStore(snapshot: stored ?? NotificationReadSnapshot()) { snapshot in
            if let data = try? JSONEncoder().encode(snapshot) { defaults.set(data, forKey: key) }
        }
    }

    public var seen: Set<String> { snapshot.seenIds }

    /// A read from the hub: baselines a service seen for the first time, takes
    /// backfill as seen, and answers what is unread.
    @discardableResult
    public func observe(_ sections: [NotificationSection]) -> Set<String> {
        let result = NotificationReadReducer.observe(snapshot, sections: sections)
        change(result.snapshot)
        return result.unreadIds
    }

    public func markSeen(_ id: String) {
        guard !id.isEmpty else { return }
        change(NotificationReadReducer.markSeen(snapshot, id: id))
    }

    public func markAllSeen(_ ids: some Collection<String>) {
        change(NotificationReadReducer.markAllSeen(snapshot, ids: ids))
    }

    private func change(_ next: NotificationReadSnapshot) {
        guard next != snapshot else { return }
        snapshot = next
        save(next)
    }
}
