import Foundation

/// The playback sessions this device has opened and not yet closed. A session
/// is closed by the player's own stop path; one left open by a crash, a forced
/// quit or a relaunch is closed at the next launch instead of waiting for the
/// hub's 30-minute cleanup. The app keeps this as JSON; the rules are here.
///
/// Found on 2026-10-04: a crash left a real session open, and the hub closed it
/// half an hour later, setting the title's `lastPlayedAt` again.
public struct OpenSessions: Codable, Equatable, Sendable {
    public struct Entry: Codable, Equatable, Sendable {
        public let id: String
        /// The profile it plays for: the hub closes a session only for its own
        /// profile (`X-Jellyfin-User`).
        public let user: String
        public let openedAt: Date

        public init(id: String, user: String, openedAt: Date) {
            self.id = id
            self.user = user
            self.openedAt = openedAt
        }
    }

    /// The hub closes a session left alone for 30 minutes, so after two hours
    /// there is nothing left to close.
    public static let staleAfter: TimeInterval = 2 * 60 * 60

    public private(set) var entries: [Entry]

    public init(entries: [Entry] = []) {
        self.entries = entries
    }

    public mutating func opened(_ id: String, user: String, at time: Date) {
        guard !id.isEmpty else { return }
        entries.removeAll { $0.id == id }
        entries.append(Entry(id: id, user: user, openedAt: time))
    }

    public mutating func closed(_ id: String) {
        entries.removeAll { $0.id == id }
    }

    /// What a launch still has to close: every entry from before it that the
    /// hub has not long since closed itself.
    public func leftovers(now: Date) -> [Entry] {
        entries.filter { now.timeIntervalSince($0.openedAt) < Self.staleAfter }
    }

    /// Without the entries the hub has long since closed itself.
    public mutating func prune(now: Date) {
        entries = leftovers(now: now)
    }
}

public enum PlaybackLeftovers {
    /// Closes each session left open, for its own profile. Returns the ids that
    /// need no more closing: closed now, or already gone from the hub (404). A
    /// session the hub could not be reached about stays for the next launch.
    public static func close(_ entries: [OpenSessions.Entry], hub: HubClient) async -> [String] {
        var done: [String] = []
        for entry in entries {
            do throws(HubFailure) {
                try await hub.send(HubEndpoints.closePlayback(sessionId: entry.id, user: entry.user))
                done.append(entry.id)
            } catch {
                if error.kind == .notFound { done.append(entry.id) }
            }
        }
        return done
    }
}
