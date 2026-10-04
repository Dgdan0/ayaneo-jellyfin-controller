import Foundation

/// An audio or subtitle track as a row says it: the language in words, then
/// what tells it apart ("5.1 surround · AAC · Default"). A port of Android's
/// `TrackPresentation`, held to its test cases.
public struct TrackPresentation: Equatable, Sendable {
    public let title: String
    public let detail: String

    private static let english = Locale(identifier: "en_US_POSIX")

    public static func of(_ track: PlaybackTrack) -> TrackPresentation {
        let code = track.language.trimmingCharacters(in: .whitespaces).lowercased()
        let language: String = switch code {
        case "eng": "en"
        case "heb": "he"
        case "jpn": "ja"
        case "spa": "es"
        case "fre", "fra": "fr"
        case "ger", "deu": "de"
        case "ara": "ar"
        case "rus": "ru"
        case "chi", "zho": "zh"
        case "und", "": ""
        default: code
        }
        let name = language.isEmpty ? "" : (english.localizedString(forLanguageCode: language) ?? "")
        let title = !name.isEmpty ? name : (!track.label.isEmpty ? track.label : "Track \(track.index + 1)")
        var detail: [String] = []
        if track.forced { detail.append("Forced") }
        if track.hearingImpaired { detail.append("SDH") }
        if track.channels > 0 {
            let layout = switch track.channels {
            case 1: "Mono"
            case 2: "Stereo"
            case 6: "5.1 surround"
            case 8: "7.1 surround"
            default: "\(track.channels) channels"
            }
            detail.append(layout)
        }
        if !track.codec.trimmingCharacters(in: .whitespaces).isEmpty { detail.append(track.codec.uppercased()) }
        if track.isDefault { detail.append("Default") }
        if track.external { detail.append("External") }
        // A commentary or an alternate mix must stay distinguishable from the main track.
        if !name.isEmpty && !track.label.isEmpty {
            let generated = Set([name, code, language, track.codec, "Default", "External", "Forced", "SDH",
                                 "Hearing impaired", "Mono", "Stereo", "Surround", "pocketds", "1.0", "2.0", "5.1",
                                 "7.1", "\(track.channels) channels"].map { $0.lowercased() })
            // Split as Android does, on a dash, dot or bar with spaces either side.
            let parts = track.label
                .replacingOccurrences(of: #"\s+[-·|]\s+"#, with: "\u{1F}", options: .regularExpression)
                .components(separatedBy: "\u{1F}")
                .map { $0.trimmingCharacters(in: .whitespaces) }
            detail += parts.filter { !$0.isEmpty && !generated.contains($0.lowercased()) }
        }
        var seen = Set<String>()
        return TrackPresentation(title: title, detail: detail.filter { seen.insert($0).inserted }.joined(separator: " · "))
    }
}

/// What a person last chose for a series or a film, on this device and
/// profile: the audio's language, subtitles on or off and their language, and
/// the subtitle delay. Stream numbers change from file to file, so they are
/// not kept. Android's `PlaybackPreferences`.
public struct PlaybackSelection: Equatable, Sendable, Codable {
    public var audioLanguage: String
    /// nil until the person has chosen.
    public var subtitlesEnabled: Bool?
    public var subtitleLanguage: String
    public var subtitleOffsetMillis: Int64

    public init(audioLanguage: String = "", subtitlesEnabled: Bool? = nil, subtitleLanguage: String = "",
                subtitleOffsetMillis: Int64 = 0) {
        self.audioLanguage = audioLanguage
        self.subtitlesEnabled = subtitlesEnabled
        self.subtitleLanguage = subtitleLanguage
        self.subtitleOffsetMillis = subtitleOffsetMillis
    }
}

/// How a remembered choice meets a new session. Android's `PlaybackPreferences`
/// and `PlayerScreen.applyRememberedSelection`.
public enum PlaybackChoices {
    public static let maxSubtitleOffsetMillis: Int64 = 60_000

    /// Episodes of one series share a choice; a film keeps its own.
    public static func scope(_ item: PlaybackItem) -> String {
        item.seriesId.isEmpty ? item.id : item.seriesId
    }

    public static func preferredAudio(_ plan: PlaybackPrepareResponse, _ selection: PlaybackSelection) -> PlaybackTrack? {
        guard !selection.audioLanguage.isEmpty else { return nil }
        return plan.audioTracks.first { $0.language.caseInsensitiveCompare(selection.audioLanguage) == .orderedSame }
    }

    public static func preferredSubtitle(_ plan: PlaybackPrepareResponse, _ selection: PlaybackSelection) -> PlaybackTrack? {
        guard selection.subtitlesEnabled == true else { return nil }
        return plan.subtitleTracks.first {
            !selection.subtitleLanguage.isEmpty
                && $0.language.caseInsensitiveCompare(selection.subtitleLanguage) == .orderedSame
        } ?? plan.subtitleTracks.first { $0.isDefault }
    }

    /// The audio and subtitle streams to ask the hub for, or nil when the plan
    /// already plays what was chosen. Subtitles off is -1.
    public static func wanted(_ plan: PlaybackPrepareResponse,
                              _ selection: PlaybackSelection) -> (audio: Int?, subtitle: Int?)? {
        let subtitle: Int? = switch selection.subtitlesEnabled {
        case false?: -1
        case true?: preferredSubtitle(plan, selection)?.index
        case nil: plan.selectedSubtitleIndex
        }
        let audio = preferredAudio(plan, selection)?.index ?? plan.selectedAudioIndex
        if audio == plan.selectedAudioIndex && subtitle == plan.selectedSubtitleIndex { return nil }
        return (audio, subtitle)
    }

    /// The choice after a session plays these tracks; the delay is kept.
    public static func remembering(_ plan: PlaybackPrepareResponse, in selection: PlaybackSelection) -> PlaybackSelection {
        let audio = plan.audioTracks.first { $0.index == plan.selectedAudioIndex }
        let subtitle = plan.subtitleTracks.first { $0.index == plan.selectedSubtitleIndex }
        var next = selection
        next.audioLanguage = audio?.language ?? ""
        next.subtitlesEnabled = subtitle != nil
        next.subtitleLanguage = subtitle?.language ?? ""
        return next
    }

    /// The track the app draws itself: a text track the hub serves as a file.
    /// A picture track is burned into the video by the hub instead.
    public static func drawnSubtitle(_ plan: PlaybackPrepareResponse) -> PlaybackTrack? {
        guard let track = plan.subtitleTracks.first(where: { $0.index == plan.selectedSubtitleIndex }),
              track.external, !track.externalUrl.isEmpty else { return nil }
        return track
    }

    /// After a change, whether the player can keep what it is playing. A file
    /// played as it is keeps one address whichever version is chosen
    /// (`/v1/playback/sessions/{id}/stream`), so the version and the method
    /// must match as well; a conversion's address names its tracks and bitrate.
    public static func sameStream(_ before: PlaybackPrepareResponse, _ after: PlaybackPrepareResponse) -> Bool {
        !after.mediaUrl.isEmpty && before.mediaUrl == after.mediaUrl
            && before.selectedMediaSourceId == after.selectedMediaSourceId
            && before.playMethod.caseInsensitiveCompare(after.playMethod) == .orderedSame
    }
}
