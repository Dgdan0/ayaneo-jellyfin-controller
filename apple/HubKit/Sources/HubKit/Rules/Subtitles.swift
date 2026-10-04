import Foundation

/// One stretch of subtitles, shown over the half-open span [start, end).
public struct SubtitleWindow<Value: Sendable>: Sendable {
    public let startMillis: Int64
    public let endMillis: Int64
    public let values: [Value]

    public init(_ startMillis: Int64, _ endMillis: Int64, _ values: [Value]) {
        self.startMillis = startMillis
        self.endMillis = endMillis
        self.values = values
    }
}

extension SubtitleWindow: Equatable where Value: Equatable {}

/// Subtitles in memory, read at any position with any delay. A port of
/// Android's `SubtitleTimeline`, held to its test cases.
///
/// A positive delay shows a line later: at 12 s with +2 s, the line written
/// for 10 s shows. Reading it is cheap enough to do while a slider moves, so a
/// new delay never reloads the video or the subtitle file.
public struct SubtitleTimeline<Value: Sendable>: Sendable {
    private let windows: [SubtitleWindow<Value>]

    public init(_ windows: [SubtitleWindow<Value>]) {
        self.windows = windows
            .filter { $0.endMillis > $0.startMillis && !$0.values.isEmpty }
            .sorted { $0.startMillis < $1.startMillis }
    }

    public var isEmpty: Bool { windows.isEmpty }

    public func values(at playbackMillis: Int64, offsetMillis: Int64) -> [Value] {
        let source = playbackMillis - offsetMillis
        guard source >= 0 else { return [] }
        var out: [Value] = []
        for window in windows {
            if window.startMillis > source { break }
            if source < window.endMillis { out += window.values }
        }
        return out
    }
}

/// The subtitle files the hub hands over, read into lines and their times.
/// Android has Media3's parsers; this is the same job for SRT, WebVTT and,
/// with its styling dropped, ASS. A block that cannot be read is skipped rather
/// than failing the file, as Android's `TolerantSubtitleDecoderFactory` skips a
/// broken line.
public enum SubtitleParser {
    public enum Format: Equatable, Sendable { case srt, webvtt, ass }

    /// The track's codec decides; an unknown one is read by its contents.
    public static func format(codec: String, text: String) -> Format? {
        switch codec.trimmingCharacters(in: .whitespaces).lowercased() {
        case "srt", "subrip": return .srt
        case "vtt", "webvtt": return .webvtt
        case "ass", "ssa": return .ass
        default:
            let head = text.drop { $0 == "\u{FEFF}" || $0.isWhitespace }.prefix(64)
            if head.hasPrefix("WEBVTT") { return .webvtt }
            if head.hasPrefix("[Script Info]") { return .ass }
            return text.contains("-->") ? .srt : nil
        }
    }

    /// Each window holds one cue's text, its lines joined with "\n".
    public static func parse(_ text: String, codec: String) -> [SubtitleWindow<String>] {
        let normalised = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        let clean = normalised.hasPrefix("\u{FEFF}") ? String(normalised.dropFirst()) : normalised
        switch format(codec: codec, text: clean) {
        case .srt?: return blocks(clean, vtt: false)
        case .webvtt?: return blocks(clean, vtt: true)
        case .ass?: return ass(clean)
        case nil: return []
        }
    }

    /// SRT and WebVTT: blank-line blocks, each a timing line (after an
    /// optional number or name) and its text.
    private static func blocks(_ text: String, vtt: Bool) -> [SubtitleWindow<String>] {
        var out: [SubtitleWindow<String>] = []
        for block in text.components(separatedBy: "\n\n") {
            let lines = block.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
            guard let timingIndex = lines.prefix(2).firstIndex(where: { $0.contains("-->") }),
                  let (start, end) = timing(lines[timingIndex]) else { continue }
            let body = lines[(timingIndex + 1)...]
                .map { cleanLine($0, vtt: vtt) }
                .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            guard !body.isEmpty else { continue }
            out.append(SubtitleWindow(start, end, [body.joined(separator: "\n")]))
        }
        return out
    }

    /// "00:01:02,500 --> 00:01:04,000", or WebVTT's "01:02.500 --> 01:04.000 line:90%".
    static func timing(_ line: String) -> (Int64, Int64)? {
        let halves = line.components(separatedBy: "-->")
        guard halves.count == 2,
              let start = clock(halves[0].trimmingCharacters(in: .whitespaces)),
              let endWord = halves[1].trimmingCharacters(in: .whitespaces).split(separator: " ").first,
              let end = clock(String(endWord)), end > start else { return nil }
        return (start, end)
    }

    /// "01:02:03,456", "01:02:03.456", "02:03.456" or ASS's "1:02:03.45".
    static func clock(_ value: String) -> Int64? {
        let parts = value.replacingOccurrences(of: ",", with: ".").split(separator: ":")
        guard (2...3).contains(parts.count) else { return nil }
        let secondsPart = parts.last!.split(separator: ".", omittingEmptySubsequences: false)
        guard let seconds = Int64(secondsPart[0]), seconds < 60 else { return nil }
        var fraction: Int64 = 0
        if secondsPart.count == 2 {
            let digits = String(secondsPart[1].prefix(3))
            guard let value = Int64(digits) else { return nil }
            fraction = value * Int64(pow(10, Double(3 - digits.count)))
        }
        let numbers = parts.dropLast().compactMap { Int64($0.trimmingCharacters(in: .whitespaces)) }
        guard numbers.count == parts.count - 1 else { return nil }
        let minutes = numbers.last!
        let hours = numbers.count == 2 ? numbers[0] : 0
        guard minutes < 60 || numbers.count == 1 else { return nil }
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000 + fraction
    }

    /// Markup is dropped: `<i>`, `<font …>`, WebVTT's voice and class tags and
    /// timestamps, and `{\an8}` overrides some SRT files carry.
    private static func cleanLine(_ line: String, vtt: Bool) -> String {
        var text = line.replacingOccurrences(of: #"<[^>]*>"#, with: "", options: .regularExpression)
        text = text.replacingOccurrences(of: #"\{\\[^}]*\}"#, with: "", options: .regularExpression)
        if vtt {
            text = text.replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
                .replacingOccurrences(of: "&nbsp;", with: " ").replacingOccurrences(of: "&amp;", with: "&")
        }
        return text
    }

    /// ASS: the `[Events]` lines its `Format:` line names, styling dropped
    /// (APPLE_PLAN.md: "ASS can start as text").
    private static func ass(_ text: String) -> [SubtitleWindow<String>] {
        var fields: [String] = ["Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text"]
        var inEvents = false
        var out: [SubtitleWindow<String>] = []
        for raw in text.split(separator: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("[") {
                inEvents = line.lowercased() == "[events]"
                continue
            }
            guard inEvents else { continue }
            if line.lowercased().hasPrefix("format:") {
                fields = line.dropFirst("format:".count).split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                continue
            }
            guard line.lowercased().hasPrefix("dialogue:"),
                  let startIndex = fields.firstIndex(of: "Start"), let endIndex = fields.firstIndex(of: "End"),
                  let textIndex = fields.firstIndex(of: "Text") else { continue }
            // Only the last field, the words, may hold commas.
            let values = line.dropFirst("dialogue:".count)
                .split(separator: ",", maxSplits: fields.count - 1, omittingEmptySubsequences: false)
                .map { String($0) }
            guard values.count == fields.count,
                  let start = clock(values[startIndex].trimmingCharacters(in: .whitespaces)),
                  let end = clock(values[endIndex].trimmingCharacters(in: .whitespaces)), end > start else { continue }
            var words = values[textIndex]
            // A drawing ({\p1} … {\p0}) is shapes, not words.
            if words.range(of: #"\{[^}]*\\p[1-9]"#, options: .regularExpression) != nil { continue }
            words = words.replacingOccurrences(of: #"\{[^}]*\}"#, with: "", options: .regularExpression)
                .replacingOccurrences(of: "\\N", with: "\n").replacingOccurrences(of: "\\n", with: "\n")
                .replacingOccurrences(of: "\\h", with: " ")
            let lines = words.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            guard !lines.isEmpty else { continue }
            out.append(SubtitleWindow(start, end, [lines.joined(separator: "\n")]))
        }
        return out
    }
}

/// The live subtitle delay: from a minute earlier to a minute later, in tenths
/// of a second. A port of Android's `SubtitleTimingPolicy` (its wide range).
public enum SubtitleTimingPolicy {
    public static let stepMillis: Int64 = 100

    public static func range(wide: Bool) -> Int64 { wide ? 60_000 : 5_000 }

    public static func clamp(_ value: Int64, wide: Bool) -> Int64 {
        min(max(value, -range(wide: wide)), range(wide: wide))
    }

    public static func steps(wide: Bool) -> Int { Int(range(wide: wide) * 2 / stepMillis) }

    public static func progressToOffset(_ progress: Int, wide: Bool) -> Int64 {
        Int64(min(max(progress, 0), steps(wide: wide)) - steps(wide: wide) / 2) * stepMillis
    }

    public static func offsetToProgress(_ value: Int64, wide: Bool) -> Int {
        Int((clamp(value, wide: wide) + range(wide: wide)) / stepMillis)
    }

    /// "+0.8 s · Later", "0.0 s · In sync", "−0.8 s · Earlier".
    public static func label(_ value: Int64) -> String {
        let seconds = String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), Double(abs(value)) / 1_000)
        if value == 0 { return "0.0 s · In sync" }
        return value < 0 ? "\u{2212}\(seconds) s · Earlier" : "+\(seconds) s · Later"
    }

    /// The short form a row shows: "0.0 s", "+1.5 s".
    public static func short(_ value: Int64) -> String {
        let seconds = String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), Double(abs(value)) / 1_000)
        return value == 0 ? "0.0 s" : (value < 0 ? "\u{2212}" : "+") + seconds + " s"
    }
}

/// Which kind of track the Audio & subtitles panel opens on: the one changed
/// last, subtitles at first. A port of Android's `PlayerMenuState`.
public struct PlayerMenuState: Equatable, Sendable {
    public private(set) var trackTab = "subtitles"

    public init() {}

    public mutating func selectTrackTab(_ tab: String) {
        if tab == "audio" || tab == "subtitles" { trackTab = tab }
    }
}
