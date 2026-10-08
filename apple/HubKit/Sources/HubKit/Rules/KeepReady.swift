import Foundation

// Keep ready (#48): the owner's one-episode buffer. A series with it on
// keeps the next N unwatched episodes on the device, from the one Play starts,
// and the one just finished. The rules, as the owner agreed them:
//
// - N unwatched episodes from the play target are kept, whether the person
//   downloaded some of them by hand or Keep ready did: those count.
// - An episode Keep ready downloaded goes only once the episode after it is
//   finished, so A, B, C: finishing A starts D and A stays; finishing B
//   removes A and starts E. (A finishes, B autoplays, the person falls
//   asleep: A is still there.)
// - Only an episode Keep ready itself downloaded is ever removed; one the
//   person downloaded is theirs.
// - Nothing is removed while something plays; the tidying comes after the
//   player is left, or at launch.
// - "Finished" is the server's watched state, so finishing on another
//   device counts, and an episode marked unwatched again is kept.

public enum KeepReady {
    public static let range = 1...10
    public static let defaultCount = 3

    public static func clamp(_ count: Int) -> Int { min(max(count, range.lowerBound), range.upperBound) }

    /// The series' episodes in the order they are watched, Specials left out:
    /// they are not "the next episode" of anything.
    public static func chain(_ episodes: [DownloadEpisode]) -> [DownloadEpisode] {
        episodes.filter { $0.season > 0 }.sorted { left, right in
            left.season == right.season ? left.index < right.index : left.season < right.season
        }
    }

    /// The first `count` unwatched episodes the PC can make, from the one Play
    /// starts (or the first unwatched, when it is not in the chain).
    public static func window(_ chain: [DownloadEpisode], playTargetId: String, count: Int) -> [DownloadEpisode] {
        let start = chain.firstIndex { $0.id == playTargetId } ?? chain.firstIndex { !$0.played } ?? chain.count
        return Array(chain.dropFirst(start).filter { !$0.played && $0.available }.prefix(max(count, 0)))
    }

    /// What one tidying does: episodes to download, and those to remove.
    public struct Plan: Equatable, Sendable {
        public var download: [DownloadEpisode]
        public var remove: [String]

        public var isEmpty: Bool { download.isEmpty && remove.isEmpty }
    }

    /// `have` is on the device or on its way; `complete` the finished
    /// downloads; `managed` those Keep ready downloaded itself.
    public static func plan(episodes: [DownloadEpisode], playTargetId: String, count: Int, have: (String) -> Bool,
                            complete: Set<String>, managed: Set<String>, playing: Bool) -> Plan {
        let chain = chain(episodes)
        let wanted = window(chain, playTargetId: playTargetId, count: count).filter { !have($0.id) }
        var remove: [String] = []
        if !playing {
            for (index, episode) in chain.enumerated() where managed.contains(episode.id) && complete.contains(episode.id) {
                guard episode.played, index + 1 < chain.count, chain[index + 1].played else { continue }
                remove.append(episode.id)
            }
        }
        return Plan(download: wanted, remove: remove)
    }

    /// The round button's number while Keep ready is on, and the panel's words.
    public static func stepperWords(_ count: Int) -> String { "Keep the next \(count) ready" }
    public static let turnOffWords = "Turn off Keep ready"
}

/// Which series have Keep ready on, for which profile, and the episodes it
/// downloaded itself: one small file in the downloads' folder. Safe from any thread.
public final class KeepReadyStore: @unchecked Sendable {
    struct Entry: Codable, Equatable {
        var count: Int
        var managed: [String]
    }

    private let file: URL
    private let lock = NSLock()
    private var entries: [String: Entry]

    public init(file: URL) {
        self.file = file
        if let data = try? Data(contentsOf: file), let read = try? JSONDecoder().decode([String: Entry].self, from: data) {
            entries = read
        } else {
            entries = [:]
        }
    }

    private static func key(_ userId: String, _ seriesId: String) -> String { userId + "|" + seriesId }

    private func write() {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        try? data.write(to: file, options: .atomic)
    }

    /// The number kept ready, or nil when Keep ready is off.
    public func count(userId: String, seriesId: String) -> Int? {
        lock.lock()
        defer { lock.unlock() }
        return entries[Self.key(userId, seriesId)]?.count
    }

    /// On, or the number changed; what it downloaded already stays its own.
    public func enable(userId: String, seriesId: String, count: Int) {
        lock.lock()
        defer { lock.unlock() }
        var entry = entries[Self.key(userId, seriesId)] ?? Entry(count: count, managed: [])
        entry.count = KeepReady.clamp(count)
        entries[Self.key(userId, seriesId)] = entry
        write()
    }

    /// Off: the files stay, and are the person's now.
    public func disable(userId: String, seriesId: String) {
        lock.lock()
        defer { lock.unlock() }
        entries[Self.key(userId, seriesId)] = nil
        write()
    }

    /// The episodes Keep ready downloaded itself.
    public func managed(userId: String, seriesId: String) -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return Set(entries[Self.key(userId, seriesId)]?.managed ?? [])
    }

    public func mark(userId: String, seriesId: String, ids: [String]) {
        lock.lock()
        defer { lock.unlock() }
        guard var entry = entries[Self.key(userId, seriesId)] else { return }
        for id in ids where !entry.managed.contains(id) { entry.managed.append(id) }
        entries[Self.key(userId, seriesId)] = entry
        write()
    }

    /// These episodes are the person's own now (they asked for them by hand),
    /// or are gone from the device.
    public func unmark(userId: String, ids: [String]) {
        let gone = Set(ids)
        lock.lock()
        defer { lock.unlock() }
        var changed = false
        for (key, var entry) in entries where key.hasPrefix(userId + "|") && entry.managed.contains(where: gone.contains) {
            entry.managed.removeAll(where: gone.contains)
            entries[key] = entry
            changed = true
        }
        if changed { write() }
    }

    /// Takes off the list what is no longer on the device.
    public func prune(userId: String, seriesId: String, stored: Set<String>) {
        lock.lock()
        defer { lock.unlock() }
        guard var entry = entries[Self.key(userId, seriesId)], entry.managed.contains(where: { !stored.contains($0) }) else { return }
        entry.managed.removeAll { !stored.contains($0) }
        entries[Self.key(userId, seriesId)] = entry
        write()
    }

    /// The series this profile keeps ready, with their numbers.
    public func series(userId: String) -> [(seriesId: String, count: Int)] {
        lock.lock()
        defer { lock.unlock() }
        let prefix = userId + "|"
        return entries.compactMap { key, entry in
            key.hasPrefix(prefix) ? (String(key.dropFirst(prefix.count)), entry.count) : nil
        }
        .sorted { $0.seriesId < $1.seriesId }
    }
}
