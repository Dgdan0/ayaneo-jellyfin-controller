import Foundation
import HubKit

/// What the player keeps on this device: each profile's audio, subtitles and
/// subtitle delay for a series or a film (Android's `PlaybackPreferences`),
/// and how subtitles look in every video (Android's `SubtitleSettings`). In
/// the app's defaults, as its other settings are.
enum PlaybackMemory {
    private static let lookKey = "playback.subtitleLook"

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
}
