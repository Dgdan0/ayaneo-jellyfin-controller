import Foundation

/// The player's wording. A port of Android's `playback/PlayerLabels`, held to
/// its test cases. Decimals use a full stop whatever the region, as `Fmt`'s
/// do: "23.98 fps" must not become "23,98 fps" because of a device setting.
public enum PlayerLabels {
    private static let posix = Locale(identifier: "en_US_POSIX")

    /// The player's title: the series, or the film.
    public static func title(_ item: PlaybackItem) -> String {
        item.seriesTitle.isEmpty ? item.title : item.seriesTitle
    }

    /// Under it: "S1E1 · Somewhere Not Here" for an episode, nothing for a
    /// film; " · Offline" when it plays from the device.
    public static func subtitle(_ item: PlaybackItem, offline: Bool = false) -> String {
        [item.seriesTitle.isEmpty ? nil : EpisodeLabel.of(season: item.seasonNumber, episode: item.episodeNumber,
                                                           title: item.title),
         offline ? "Offline" : nil]
            .compactMap { $0 }
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .joined(separator: " · ")
    }

    /// Under the timeline's start: "5:34 · Part A". A chapter called only
    /// "Chapter 2" says nothing the timeline does not, so it is left out.
    public static func positionLine(positionMillis: Int64, chapterName: String?) -> String {
        let name = chapterName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let generic = name.range(of: #"^chapter\s*\d+$"#, options: [.regularExpression, .caseInsensitive]) != nil
        return ([Fmt.clock(positionMillis)] + (name.isEmpty || generic ? [] : [name])).joined(separator: " · ")
    }

    /// The chapter playing at `positionMillis`: the last to have started.
    public static func chapterName(at positionMillis: Int64, in chapters: [PlaybackChapter]) -> String? {
        chapters.last { $0.positionMillis <= positionMillis }?.name
    }

    /// Under the timeline's end: the time left, "−22:53".
    public static func remainingLine(positionMillis: Int64, durationMillis: Int64) -> String {
        durationMillis <= 0 ? "" : "\u{2212}" + Fmt.clock(max(0, durationMillis - positionMillis))
    }

    public static func signedTime(_ deltaMillis: Int64) -> String {
        (deltaMillis < 0 ? "\u{2212}" : "+") + Fmt.clock(abs(deltaMillis))
    }

    public static func subtitleOffset(_ offsetMillis: Int64) -> String {
        if offsetMillis == 0 { return "No offset" }
        let seconds = String(format: "%.1f", locale: posix, Double(abs(offsetMillis)) / 1_000)
        return offsetMillis < 0 ? "\(seconds) seconds earlier" : "\(seconds) seconds later"
    }

    public static func speed(_ value: Float) -> String {
        value == 1 ? "Normal" : "\(value)×"
    }

    /// Jellyfin's play method in words: "Direct play", "Direct stream", "Converting".
    public static func playMethod(_ value: String) -> String {
        switch value.trimmingCharacters(in: .whitespaces).lowercased() {
        case "directplay": "Direct play"
        case "directstream": "Direct stream"
        case "transcode": "Converting"
        case "": "Playback"
        default: value
        }
    }

    /// What is playing and how, for the playback panel.
    public static func diagnostic(_ value: PlaybackPrepareResponse) -> String {
        var parts = [playMethod(value.playMethod)]
        if value.width > 0 { parts.append("\(value.width)×\(value.height)") }
        if !value.videoCodec.isEmpty { parts.append(value.videoCodec.uppercased()) }
        if !value.audioCodec.isEmpty { parts.append(value.audioCodec.uppercased()) }
        if value.frameRate > 0 { parts.append(String(format: "%.2f fps", locale: posix, value.frameRate)) }
        if value.bitrate > 0 { parts.append(Fmt.mbps(Int64(value.bitrate))) }
        if !value.hdr.isEmpty { parts.append(value.hdr) }
        if !value.transcodeReason.isEmpty { parts.append(value.transcodeReason) }
        return parts.joined(separator: " · ")
    }

    public static func sourceDetail(container: String, bitrate: Int) -> String {
        [container.uppercased(), bitrate > 0 ? Fmt.mbps(Int64(bitrate)) : ""]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// A media version with its name first, for a single line.
    public static func source(name: String, container: String, bitrate: Int) -> String {
        [name, sourceDetail(container: container, bitrate: bitrate)].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// This video's Quality row: "Original · 1080p", "10 Mbps · 720p", or the downloaded file.
    public static func qualityValue(_ qualityLabel: String, height: Int, offline: Bool) -> String {
        if offline { return "Original · downloaded" }
        return height > 0 ? "\(qualityLabel) · \(height)p" : qualityLabel
    }

    /// Under a chapter's name: where it starts, how long it runs, and what it
    /// is when a skip segment starts with it ("3:58 · 2 min · Intro").
    public static func chapterDetail(startMillis: Int64, endMillis: Int64, kind: String?) -> String {
        [Fmt.clock(startMillis), endMillis - startMillis > 0 ? chapterLength(endMillis - startMillis) : nil, kind]
            .compactMap { $0 }.joined(separator: " · ")
    }

    /// "45 s" under a minute, else whole minutes: "2 min".
    public static func chapterLength(_ millis: Int64) -> String {
        let seconds = max(1, millis / 1_000)
        return seconds < 60 ? "\(seconds) s" : "\((seconds + 30) / 60) min"
    }

    /// A segment's kind in a chapter's line; credits rather than Jellyfin's "Outro".
    public static func segmentKind(_ type: String) -> String? {
        switch type.trimmingCharacters(in: .whitespaces).lowercased() {
        case "intro": "Intro"
        case "outro": "Credits"
        case "recap": "Recap"
        case "preview": "Preview"
        case "commercial": "Ad"
        default: nil
        }
    }

    /// How the stream will be delivered: method, resolution, bitrate.
    public static func quality(_ value: PlaybackPrepareResponse) -> String {
        var line = value.playMethod.isEmpty ? "Original" : playMethod(value.playMethod)
        if value.width > 0 && value.height > 0 { line += " · \(value.width)×\(value.height)" }
        if value.bitrate > 0 { line += " · " + Fmt.mbps(Int64(value.bitrate)) }
        return line
    }
}

/// Settings › Playback › Show the next episode, Android's `NextEpisodeTiming`.
public enum NextEpisodeTiming: String, CaseIterable, Sendable {
    case credits, beforeEnd, never

    public var label: String {
        switch self {
        case .credits: "When credits start"
        case .beforeEnd: "20 s before the end"
        case .never: "Never"
        }
    }
}

/// When the up-next card appears near the end of an episode, and which
/// segments get a Skip button. A port of Android's `playback/UpNext`.
///
/// The card's bar fills over `countdownMillis` and the next episode starts
/// when it is full: Play now starts it at once, Watch credits puts it away
/// until the video ends.
public enum UpNext {
    public static let leadMillis: Int64 = 20_000
    public static let countdownMillis: Int64 = 8_000

    /// Where the card appears, or nil for never. "When credits start" uses the
    /// credits segment when there is one in the second half of the video (an
    /// opening "Ending" theme in the first half is not the credits), and 20
    /// seconds before the end otherwise.
    public static func cardAt(_ timing: NextEpisodeTiming, segments: [PlaybackSegment], durationMillis: Int64) -> Int64? {
        if durationMillis <= leadMillis * 2 { return nil }
        let beforeEnd = durationMillis - leadMillis
        switch timing {
        case .never: return nil
        case .beforeEnd: return beforeEnd
        case .credits:
            let credits = segments.first {
                $0.type.caseInsensitiveCompare("Outro") == .orderedSame && $0.startMillis >= durationMillis / 2
            }
            return credits.map { min($0.startMillis, beforeEnd) } ?? beforeEnd
        }
    }

    public static func showsCard(positionMillis: Int64, cardAt: Int64?, durationMillis: Int64) -> Bool {
        guard let cardAt else { return false }
        return positionMillis >= cardAt && positionMillis < durationMillis
    }

    /// The button a segment earns, or nil: credits are the up-next card's, not a Skip.
    public static func skipLabel(_ type: String) -> String? {
        switch type.trimmingCharacters(in: .whitespaces).lowercased() {
        case "intro": "Skip intro"
        case "recap": "Skip recap"
        case "preview": "Skip preview"
        case "commercial": "Skip ad"
        default: nil
        }
    }

    /// Skipping these happens by itself when Settings › Playback says so.
    public static func skipsAutomatically(_ type: String) -> Bool {
        type.caseInsensitiveCompare("Intro") == .orderedSame || type.caseInsensitiveCompare("Recap") == .orderedSame
    }
}

/// Seeking, the end of a video and the quality caps. A port of Android's
/// `playback/PlaybackRules`.
public enum PlaybackRules {
    public struct Quality: Equatable, Sendable {
        public let label: String
        /// Bits per second; 0 is the original.
        public let bitrate: Int
    }

    public static let qualities = [
        Quality(label: "Original", bitrate: 0),
        Quality(label: "40 Mbps", bitrate: 40_000_000),
        Quality(label: "20 Mbps", bitrate: 20_000_000),
        Quality(label: "10 Mbps", bitrate: 10_000_000),
        Quality(label: "5 Mbps", bitrate: 5_000_000),
        Quality(label: "2 Mbps", bitrate: 2_000_000),
    ]

    /// A change to what plays (a track, the quality, a conversion, a version)
    /// as the hub's select takes it, naming the version playing unless the
    /// change is another version: Android's `PlaybackRules.selection` (#24).
    /// Jellyfin applies an audio or subtitle stream index only with the media
    /// source it belongs to; without one it converted the default track again,
    /// so a language chosen in the player never reached the sound (Bleach S1E6
    /// on Apple, 2026-10-05).
    public static func selection(_ plan: PlaybackPrepareResponse, positionMillis: Int64? = nil,
                                 mediaSourceId: String? = nil, audioStreamIndex: Int? = nil,
                                 subtitleStreamIndex: Int? = nil, maxBitrate: Int? = nil,
                                 forceTranscode: Bool? = nil) -> PlaybackSelectBody {
        PlaybackSelectBody(positionMillis: positionMillis ?? plan.positionMillis,
                           mediaSourceId: mediaSourceId ?? (plan.selectedMediaSourceId.isEmpty ? nil : plan.selectedMediaSourceId),
                           audioStreamIndex: audioStreamIndex, subtitleStreamIndex: subtitleStreamIndex,
                           maxBitrate: maxBitrate, forceTranscode: forceTranscode)
    }

    /// A held seek goes further the longer it is held.
    public static func seekStep(repeatCount: Int) -> Int64 {
        repeatCount >= 12 ? 60_000 : repeatCount >= 5 ? 30_000 : 10_000
    }

    public static func clampSeek(_ position: Int64, durationMillis: Int64) -> Int64 {
        min(max(position, 0), max(durationMillis, 0))
    }

    /// A player teardown must never be confused with naturally reaching the end.
    public static func reachedNaturalEnd(positionMillis: Int64, durationMillis: Int64) -> Bool {
        durationMillis > 0 && positionMillis >= durationMillis - 1_000
    }

    /// A full-width swipe scans a useful window without making short swipes too coarse.
    public static func scrubTarget(startMillis: Int64, dragFraction: Double, durationMillis: Int64) -> Int64 {
        if durationMillis <= 0 { return 0 }
        let window = min(durationMillis, min(max(durationMillis / 3, 120_000), 1_200_000))
        let delta = Int64((min(max(dragFraction, -1), 1) * Double(window)).rounded())
        return clampSeek(startMillis + delta, durationMillis: durationMillis)
    }

    public struct TrickplayFrame: Equatable, Sendable {
        public let thumbnailIndex: Int
        public let tileIndex: Int
        public let column: Int
        public let row: Int
    }

    public static func trickplayFrame(positionMillis: Int64, info: PlaybackTrickplay) -> TrickplayFrame? {
        guard info.intervalMillis > 0, info.thumbnailCount > 0, info.tileWidth > 0, info.tileHeight > 0 else { return nil }
        let thumbnail = Int(min(max(max(positionMillis, 0) / info.intervalMillis, 0), Int64(info.thumbnailCount - 1)))
        let perTile = info.tileWidth * info.tileHeight
        let offset = thumbnail % perTile
        return TrickplayFrame(thumbnailIndex: thumbnail, tileIndex: thumbnail / perTile,
                              column: offset % info.tileWidth, row: offset / info.tileWidth)
    }
}

/// The player's gestures on the picture (#24), as the Pocket's: a double tap
/// on either half seeks back or forward by the step, and an up-or-down drag
/// sets the brightness on the left half and the player's volume on the right,
/// each shown as a small bar.
public enum PlayerGestures {
    public enum Side: Equatable, Sendable { case left, right }

    /// The Pocket's default step. The Apple app has no Settings › Playback
    /// yet, so this is the step.
    public static let seekStepMillis: Int64 = 10_000

    public static func side(x: Double, width: Double) -> Side { x < width / 2 ? .left : .right }

    /// What a double tap at `x` seeks by: back on the left half, on on the right.
    public static func doubleTapSeek(x: Double, width: Double, step: Int64 = seekStepMillis) -> Int64 {
        side(x: x, width: width) == .left ? -step : step
    }

    /// An up-or-down drag: well past a tap's wobble and clearly more vertical
    /// than across.
    public static func isVertical(dx: Double, dy: Double) -> Bool {
        abs(dy) >= 12 && abs(dy) > abs(dx) * 1.5
    }

    /// A level from 0 to 1 after a drag of `dy` points (down is positive)
    /// that began at `start`: up raises it, and a drag over 60% of the
    /// picture's height takes it from nothing to all. Brightness keeps a
    /// little light (`floor`), as the Pocket's does at 2%.
    public static func level(start: Double, dy: Double, height: Double, floor: Double = 0) -> Double {
        guard height > 0 else { return start }
        return min(1, max(floor, start - dy / (height * 0.6)))
    }

    public static let brightnessFloor = 0.02

    /// "60%".
    public static func percent(_ level: Double) -> String { "\(Int((min(max(level, 0), 1) * 100).rounded()))%" }

    /// The double tap's words: "−0:10 · 12:34", where it lands.
    public static func seekFeedback(deltaMillis: Int64, targetMillis: Int64) -> String {
        PlayerLabels.signedTime(deltaMillis) + "  ·  " + Fmt.clock(targetMillis)
    }
}

/// What the player tells the hub about a session, and when: Android
/// `PlaybackService`'s listener rules, as values. The player hands it what
/// happened; what comes back is sent, in order, as it is.
///
/// "started" the first time the video plays, "unpaused" every time after;
/// "paused" only once started and before the end; "progress" every ten
/// seconds of playing, counted from the start; a seek the person made; and
/// "stopped" exactly once, at the duration when the video reached its end (the
/// hub saves that as watched) or where it was left.
public struct PlaybackReporter: Equatable, Sendable {
    public static let progressEveryMillis: Int64 = 10_000

    public private(set) var sequence: Int64 = 0
    public private(set) var started = false
    /// The hub has been told the session stopped.
    public private(set) var stopped = false
    private var lastProgressAt: Int64 = 0

    public init() {}

    public mutating func playingChanged(_ playing: Bool, positionMillis: Int64, nowMillis: Int64,
                                        muted: Bool = false) -> PlaybackEventBody? {
        if playing {
            let type = started && !stopped ? "unpaused" : "started"
            if !started { lastProgressAt = nowMillis }
            started = true
            stopped = false
            return event(type, positionMillis, paused: false, muted: muted)
        }
        guard started, !stopped else { return nil }
        return event("paused", positionMillis, paused: true, muted: muted)
    }

    /// Called while time passes; a progress report every ten seconds of play.
    public mutating func tick(playing: Bool, positionMillis: Int64, nowMillis: Int64, muted: Bool = false) -> PlaybackEventBody? {
        guard playing, started, !stopped, nowMillis - lastProgressAt >= Self.progressEveryMillis else { return nil }
        lastProgressAt = nowMillis
        return event("progress", positionMillis, paused: false, muted: muted)
    }

    /// A seek the person made. Where playback starts is not one: the first
    /// report already says it.
    public mutating func seeked(positionMillis: Int64, paused: Bool, muted: Bool = false) -> PlaybackEventBody? {
        guard started, !stopped else { return nil }
        return event("seek", positionMillis, paused: paused, muted: muted)
    }

    /// The video played to its end: stopped at the duration.
    public mutating func reachedEnd(durationMillis: Int64, muted: Bool = false) -> PlaybackEventBody? {
        guard !stopped else { return nil }
        stopped = true
        return event("stopped", durationMillis, paused: false, muted: muted)
    }

    /// The person left: stopped where it was, unless it never started or the
    /// end already said so.
    public mutating func stop(positionMillis: Int64, muted: Bool = false) -> PlaybackEventBody? {
        guard started, !stopped else { return nil }
        stopped = true
        return event("stopped", positionMillis, paused: true, muted: muted)
    }

    private mutating func event(_ type: String, _ position: Int64, paused: Bool, muted: Bool) -> PlaybackEventBody {
        sequence += 1
        return PlaybackEventBody(type: type, sequence: sequence, positionMillis: max(0, position), paused: paused,
                                 muted: muted, volume: 100)
    }
}

/// What AVPlayer can open (APPLE_PLAN.md, "Hub changes", 1; #2): MP4, M4V and
/// MOV as files, anything else as HLS in fMP4 segments, which is the only way
/// AVPlayer plays HEVC over HLS. AAC, AC-3 and E-AC-3 decode natively, so
/// Jellyfin copies them rather than converting.
public enum PlaybackProfile {
    public static let containers = ["mp4", "m4v", "mov"]
    public static let hlsSegments = "fmp4"
    public static let audioCodecs = ["aac", "ac3", "eac3", "alac", "flac", "mp3"]
    /// AVPlayer mixes 5.1 down for two speakers and passes it to a receiver
    /// that has more, so a 5.1 track is copied rather than converted.
    public static let maxAudioChannels = 6

    /// HEVC only where the device decodes it in hardware.
    public static func videoCodecs(hevc: Bool) -> [String] {
        hevc ? ["h264", "hevc"] : ["h264"]
    }

    public static func capabilities(width: Int, height: Int, hevc: Bool, hdrTypes: [String] = []) -> PlaybackCapabilities {
        // The hub refuses a screen larger than 8K.
        PlaybackCapabilities(width: min(max(width, 0), 7_680), height: min(max(height, 0), 4_320),
                             maxAudioChannels: maxAudioChannels, videoCodecs: videoCodecs(hevc: hevc),
                             audioCodecs: audioCodecs, hdrTypes: hdrTypes, containers: containers,
                             hlsSegments: hlsSegments)
    }

    /// "JellyHub for iPad", the name Jellyfin's dashboard shows.
    public static func deviceName(_ kind: String) -> String { "JellyHub for " + kind }
}
