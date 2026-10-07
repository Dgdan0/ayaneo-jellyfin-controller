import Foundation
import HubKit

/// What Settings › Playback keeps for video (#33; Android's `PlaybackSettings`):
/// whether intros skip themselves, and when the next episode's card comes.
/// The jump's length, which the audiobook shares, is `ListeningSettings.seekSeconds`.
enum PlaybackSettings {
    private static let autoSkipKey = "playback.autoSkipIntro"
    private static let nextTimingKey = "playback.nextTiming"

    /// Off until chosen: a Skip intro button comes instead.
    static var autoSkipIntro: Bool {
        get { UserDefaults.standard.bool(forKey: autoSkipKey) }
        set { UserDefaults.standard.set(newValue, forKey: autoSkipKey) }
    }

    /// When the credits start, until chosen.
    static var nextTiming: NextEpisodeTiming {
        get { UserDefaults.standard.string(forKey: nextTimingKey).flatMap(NextEpisodeTiming.init(rawValue:)) ?? .credits }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: nextTimingKey) }
    }
}
