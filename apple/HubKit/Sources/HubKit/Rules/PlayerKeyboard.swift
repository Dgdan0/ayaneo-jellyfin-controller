import Foundation

/// What a key does in the video player (#33), on an iPad's or an iPhone's
/// keyboard and on the Mac, where the Playback menu names the same commands.
public enum PlayerKey: Equatable, Sendable, CaseIterable {
    case playPause, back, forward, volumeUp, volumeDown, mute, subtitles, slower, faster, skip, next, fullScreen
}

/// The player's keys: Space plays and pauses, ← and → jump, ↑ and ↓ set the
/// volume, M mutes, C turns subtitles on and off, [ and ] change the speed,
/// S skips the intro or the credits, N plays the next episode and F fills the
/// screen. Letters either case; anything else is not the player's.
public enum PlayerKeyboard {
    public static func command(_ characters: String) -> PlayerKey? {
        switch characters.lowercased() {
        case " ": .playPause
        case "m": .mute
        case "c": .subtitles
        case "[": .slower
        case "]": .faster
        case "s": .skip
        case "n": .next
        case "f": .fullScreen
        default: nil
        }
    }

    /// The letters `command` reads, for the view that listens for them.
    public static let letters = "mMcCsSnNfF[]"

    /// ↑ and ↓ move the player's own volume a tenth at a time, from 0 to 1.
    public static let volumeStep = 0.1

    public static func volume(_ current: Double, up: Bool) -> Double {
        let next = (current + (up ? volumeStep : -volumeStep)) * 10
        return min(max(next.rounded() / 10, 0), 1)
    }

    /// [ and ]: the next speed down or up of the player's, staying at either end.
    public static func speed(_ current: Float, faster: Bool) -> Float {
        let speeds = PlaybackEnhancements.speeds
        if faster { return speeds.first { $0 > current + 0.001 } ?? speeds.last ?? current }
        return speeds.last { $0 < current - 0.001 } ?? speeds.first ?? current
    }
}

extension PlaybackChoices {
    /// C (#33): subtitles off when they are on; when off, the track turned off
    /// last in this video, else one in the language chosen for the series or
    /// film, else the first that is more than forced signs. Nil when the video
    /// has none.
    public static func toggledSubtitle(_ plan: PlaybackPrepareResponse, last: Int?, language: String) -> Int? {
        if let on = plan.selectedSubtitleIndex, on >= 0 { return -1 }
        let tracks = plan.subtitleTracks
        guard !tracks.isEmpty else { return nil }
        if let last, tracks.contains(where: { $0.index == last }) { return last }
        let full = tracks.filter { !$0.forced }
        if !language.isEmpty, let match = full.first(where: { $0.language.caseInsensitiveCompare(language) == .orderedSame }) {
            return match.index
        }
        return (full.first ?? tracks.first)?.index
    }
}

extension PlayerLabels {
    /// "Speed 1.25×", "Speed Normal".
    public static func speedNotice(_ value: Float) -> String { "Speed " + speed(value) }

    /// "Subtitles off", or the track turned on as its row names it: "Subtitles: English".
    public static func subtitlesNotice(_ track: PlaybackTrack?) -> String {
        guard let track else { return "Subtitles off" }
        return "Subtitles: " + TrackPresentation.of(track).title
    }
}
