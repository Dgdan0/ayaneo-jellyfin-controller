import Foundation

/// Outline is white words with a black edge and nothing behind them, the way
/// mpv draws subtitles; Box is white on a dark box. Android's `SubtitleStyle`,
/// Outline by default since 2026-10.
public enum SubtitleStyle: String, CaseIterable, Sendable, Codable {
    case outline, box
}

/// Words' height as a share of the picture, and where the bottom line sits.
/// Android's `SubtitleSize`.
public enum SubtitleSize: String, CaseIterable, Sendable, Codable {
    case small, medium, large

    public var textFraction: Double {
        switch self {
        case .small: 0.046
        case .medium: 0.054
        case .large: 0.066
        }
    }

    public var bottomFraction: Double {
        switch self {
        case .small: 0.07
        case .medium: 0.08
        case .large: 0.10
        }
    }
}

/// How subtitles look in every video. `liftWithControls` moves them above the
/// timeline while it shows; off by default, since a line that jumps whenever
/// the screen is touched distracts more than one the controls briefly cover.
public struct SubtitleLook: Equatable, Sendable, Codable {
    public var style: SubtitleStyle
    public var size: SubtitleSize
    public var liftWithControls: Bool

    public init(style: SubtitleStyle = .outline, size: SubtitleSize = .medium, liftWithControls: Bool = false) {
        self.style = style
        self.size = size
        self.liftWithControls = liftWithControls
    }
}

/// Chapters, segments, skipping and where subtitles sit. A port of Android's
/// `playback/PlaybackEnhancements`, held to its test cases.
public enum PlaybackEnhancements {
    public static let defaultSpeed: Float = 1
    public static let speeds: [Float] = [0.5, 0.75, 1, 1.25, 1.5, 2]

    private static let subtitleGap = 0.02
    private static let maxSubtitleLift = 0.6

    /// Where subtitles sit: their bottom edge, as a share of the picture's
    /// height. Lifted, just above the controls while they show; `covered` is
    /// how much of the bottom the controls cover, 0 when hidden.
    public static func subtitlePlacement(_ look: SubtitleLook, covered: Double, height: Double) -> Double {
        look.liftWithControls ? subtitleLift(look.size.bottomFraction, covered: covered, height: height)
            : look.size.bottomFraction
    }

    public static func subtitleLift(_ base: Double, covered: Double, height: Double) -> Double {
        guard covered > 0, height > 0 else { return base }
        return min(max(base, covered / height + subtitleGap), maxSubtitleLift)
    }

    /// In order, inside the video, one per start, each with a name.
    public static func chapters(_ values: [PlaybackChapter], durationMillis: Int64) -> [PlaybackChapter] {
        var seen = Set<Int64>()
        return values
            .filter { $0.positionMillis >= 0 && (durationMillis <= 0 || $0.positionMillis < durationMillis) }
            .sorted { $0.positionMillis < $1.positionMillis }
            .filter { seen.insert($0.positionMillis).inserted }
            .enumerated()
            .map { index, chapter in
                var named = chapter
                if named.name.trimmingCharacters(in: .whitespaces).isEmpty { named.name = "Chapter \(index + 1)" }
                return named
            }
    }

    /// Where to take a chapter's picture: a quarter of the way in, at most
    /// thirty seconds, on the hub's five-second frame grid. Ten seconds in was
    /// still the fade from black on an episode's cold open.
    public static func chapterFrameMillis(startMillis: Int64, endMillis: Int64) -> Int64 {
        let length = endMillis - startMillis > 0 ? endMillis - startMillis : 60_000
        return frameMillis(startMillis + min(30_000, length / 4))
    }

    /// The hub's frame for `positionMillis`: it extracts one every five
    /// seconds, so asking at the start of the five seconds lets every scrub
    /// over them share one picture (Android's `loadExtractedPreview`).
    public static func frameMillis(_ positionMillis: Int64) -> Int64 {
        max(0, positionMillis) / 5_000 * 5_000
    }

    /// The segment that starts with this chapter, within two seconds, if any.
    public static func segmentAt(_ segments: [PlaybackSegment], startMillis: Int64) -> PlaybackSegment? {
        segments.first { abs($0.startMillis - startMillis) <= 2_000 }
    }

    public static func nextChapter(_ chapters: [PlaybackChapter], positionMillis: Int64) -> PlaybackChapter? {
        chapters.first { $0.positionMillis > positionMillis + 750 }
    }

    public static func previousChapter(_ chapters: [PlaybackChapter], positionMillis: Int64) -> PlaybackChapter? {
        chapters.last { $0.positionMillis < positionMillis - 750 }
    }

    /// The segment a Skip button is for now: one that earns a button
    /// (`UpNext.skipLabel`), and not in its last second.
    public static func skipPrompt(_ segments: [PlaybackSegment], positionMillis: Int64) -> PlaybackSegment? {
        segments.first {
            UpNext.skipLabel($0.type) != nil && positionMillis >= $0.startMillis && positionMillis < $0.endMillis - 1_000
        }
    }
}

extension PlayerLabels {
    public static func subtitleStyle(_ value: SubtitleStyle) -> String {
        switch value {
        case .outline: "Outline"
        case .box: "Box"
        }
    }

    public static func subtitleSize(_ value: SubtitleSize) -> String {
        switch value {
        case .small: "Small"
        case .medium: "Medium"
        case .large: "Large"
        }
    }

    /// "Outline · Medium", Android's subtitle look row.
    public static func subtitleLook(_ look: SubtitleLook) -> String {
        "\(subtitleStyle(look.style)) · \(subtitleSize(look.size))"
    }
}
