import Foundation
import HubKit

/// What the player keeps on this device: each profile's audio, subtitles and
/// subtitle delay for a series or a film (Android's `PlaybackPreferences`),
/// how subtitles look in every video (Android's `SubtitleSettings`), and the
/// hub sessions it has opened and not yet closed (`OpenSessions`). In the
/// app's defaults, as its other settings are.
enum PlaybackMemory {
    private static let lookKey = "playback.subtitleLook"
    private static let sessionsKey = "playback.openSessions"
    /// The open sessions are written from the main actor and from the outbox's
    /// own task, which closes them while the quitting main thread waits.
    private static let sessionsLock = NSLock()
    /// Whether this launch has closed what the last one left open.
    @MainActor private static var leftoversClosed = false

    static func selection(user: String, scope: String) -> PlaybackSelection {
        guard !scope.isEmpty, let data = UserDefaults.standard.data(forKey: key(user: user, scope: scope)),
              let value = try? JSONDecoder().decode(PlaybackSelection.self, from: data) else { return PlaybackSelection() }
        return value
    }

    static func save(_ selection: PlaybackSelection, user: String, scope: String) {
        guard !scope.isEmpty, let data = try? JSONEncoder().encode(selection) else { return }
        UserDefaults.standard.set(data, forKey: key(user: user, scope: scope))
    }

    static func look() -> SubtitleLook {
        guard let data = UserDefaults.standard.data(forKey: lookKey),
              let value = try? JSONDecoder().decode(SubtitleLook.self, from: data) else { return SubtitleLook() }
        return value
    }

    static func save(_ look: SubtitleLook) {
        guard let data = try? JSONEncoder().encode(look) else { return }
        UserDefaults.standard.set(data, forKey: lookKey)
    }

    /// Per profile, so another person's choice for the same series is theirs.
    private static func key(user: String, scope: String) -> String {
        "playback.selection.\(user.isEmpty ? "default" : user).\(scope)"
    }

    // MARK: Sessions left open

    static func sessionOpened(_ id: String, user: String) {
        updateSessions { $0.opened(id, user: user, at: Date()) }
    }

    static func sessionClosed(_ id: String) {
        updateSessions { $0.closed(id) }
    }

    /// Once a launch, before anything plays: closes each session an earlier
    /// launch left open (a crash, a forced quit, a relaunch from a script)
    /// through the hub's `DELETE`, which saves it as the hub's own 30-minute
    /// cleanup would, only sooner. Sessions this launch opens meanwhile are not
    /// among them: the list is read first.
    @MainActor
    static func closeLeftovers(hub: HubClient) async {
        guard !leftoversClosed else { return }
        leftoversClosed = true
        let now = Date()
        let leftovers = readSessions().leftovers(now: now)
        updateSessions { $0.prune(now: now) }
        guard !leftovers.isEmpty else { return }
        let done = await PlaybackLeftovers.close(leftovers, hub: hub)
        #if DEBUG
        NSLog("playback: closed %d of %d sessions left open", done.count, leftovers.count)
        #endif
        updateSessions { book in done.forEach { book.closed($0) } }
    }

    private static func readSessions() -> OpenSessions {
        sessionsLock.withLock { decodeSessions() }
    }

    private static func updateSessions(_ change: (inout OpenSessions) -> Void) {
        sessionsLock.withLock {
            var book = decodeSessions()
            change(&book)
            if let data = try? JSONEncoder().encode(book) { UserDefaults.standard.set(data, forKey: sessionsKey) }
        }
    }

    private static func decodeSessions() -> OpenSessions {
        guard let data = UserDefaults.standard.data(forKey: sessionsKey),
              let book = try? JSONDecoder().decode(OpenSessions.self, from: data) else { return OpenSessions() }
        return book
    }
}
