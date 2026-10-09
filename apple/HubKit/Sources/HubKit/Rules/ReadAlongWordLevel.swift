import Foundation

// Read along word by word (#66). An edition with our word overlay nests one
// `<par>` per word in a `<seq epub:textref="doc#sentence">` per sentence,
// each word an element `<sentence>-wN` inside the sentence's. The timeline
// keeps its sentences as before, so following the voice, the page, the
// steps and the place kept are unchanged, and saved places stay sentence ids
// that Storyteller, the hub and older apps understand. Beside them it keeps
// each sentence's words, which only the highlight reads: the word spoken in
// a strong wash, and the sentence's words already spoken before it in a
// light one (style A, as Kindle draws it), cleared at each new sentence.

/// A word the narration speaks inside its sentence: its element's id
/// (`<sentence>-wN`) and when in the sentence's audio file it is spoken.
public struct ReadAlongWord: Equatable, Hashable, Sendable {
    public let fragment: String
    public let beginMs: Int64
    public let endMs: Int64

    public init(fragment: String, beginMs: Int64, endMs: Int64) {
        self.fragment = fragment
        self.beginMs = beginMs
        self.endMs = endMs
    }
}

extension ReadAlongTimeline {
    /// Where a sentence's words are kept: its document and its element's id.
    public static func wordKey(_ textHref: String, _ fragment: String) -> String { textHref + "#" + fragment }

    /// Whether the edition times its words.
    public var timesWords: Bool { !words.isEmpty }

    /// The words of the sentence `segment` in the order they are spoken;
    /// none for a sentence-level edition, or a sentence that could not be cut
    /// into words.
    public func words(of segment: ReadAlongSegment) -> [ReadAlongWord] {
        words[Self.wordKey(segment.textHref, segment.fragment)] ?? []
    }

    /// The word of `segment` spoken at `position`: the last one begun, so a
    /// breath between two words keeps the first lit. Nil before its first, or
    /// for a sentence without words.
    public func word(in segment: ReadAlongSegment, at position: ReadAlongPosition) -> Int? {
        let list = words(of: segment)
        guard !list.isEmpty, tracks.indices.contains(position.track) else { return nil }
        let absolute = tracks[position.track].startMs + position.offsetMs
        var low = 0
        var high = list.count - 1
        while low <= high {
            let middle = (low + high) / 2
            if list[middle].beginMs <= absolute { low = middle + 1 } else { high = middle - 1 }
        }
        return high >= 0 ? high : nil
    }
}

/// What the page shows of a moment, style A: the sentence spoken, the word
/// spoken in it, and whether the trail through its words before that one is
/// drawn. Nil between sentences: nothing is lit there.
public struct ReadAlongMark: Equatable, Sendable {
    public let sentence: ReadAlongSegment
    /// The word spoken (its element's id); nil for a sentence-level edition,
    /// a sentence without words, or before the sentence's first word.
    public let word: String?
    /// The sentence's words spoken before `word` are washed: there are some, and the trail is on.
    public let trail: Bool

    public init(sentence: ReadAlongSegment, word: String?, trail: Bool) {
        self.sentence = sentence
        self.word = word
        self.trail = trail
    }

    /// The mark at `position`, the trail drawn at `trailPercent` (0 draws none).
    public static func at(_ position: ReadAlongPosition, in timeline: ReadAlongTimeline, trailPercent: Int) -> ReadAlongMark? {
        guard let sentence = timeline.active(track: position.track, offsetMs: position.offsetMs) else { return nil }
        guard let index = timeline.word(in: sentence, at: position) else {
            return ReadAlongMark(sentence: sentence, word: nil, trail: false)
        }
        return ReadAlongMark(sentence: sentence, word: timeline.words(of: sentence)[index].fragment,
                             trail: index > 0 && trailPercent > 0)
    }
}

/// The read-along highlight's colour and trail, chosen per page theme (#66),
/// and the washes they make. The word is mixed into the page at the theme's
/// strength (light pages 0.62, dark 0.42), the trail at that times the trail
/// percent, each held to 4.5:1 against the ink by stepping down 0.02. A
/// sentence-level book washes its sentence in the same colour, as #52 does.
public struct ReadAlongHighlightStyle: Equatable, Sendable {
    /// The colours offered, in the swatches' order.
    public enum Colour: String, CaseIterable, Sendable {
        case gold, ember, rose, lavender, sky, teal, mint, moon

        public var rgb: UInt32 {
            switch self {
            case .gold: 0xFFF0_C96A
            case .ember: 0xFFDE_8C4C
            case .rose: 0xFFE9_8FA8
            case .lavender: 0xFFB3_9DEB
            case .sky: 0xFF7D_B7E8
            case .teal: 0xFF5C_C2B5
            case .mint: 0xFF9A_D47E
            case .moon: 0xFFBE_C4D6
            }
        }

        public var label: String { rawValue.prefix(1).uppercased() + rawValue.dropFirst() }
    }

    public var colour: Colour
    /// How strong the trail is next to the word, 0 to 100 in steps of 5; 0 draws no trail.
    public var trail: Int

    public init(colour: Colour, trail: Int = ReadAlongHighlightStyle.defaultTrail) {
        self.colour = colour
        self.trail = Self.stepped(trail)
    }

    public static let defaultTrail = 40
    public static let trailStep = 5
    /// The contrast the ink keeps against either wash.
    public static let contrast = 4.5
    public static let holdStep = 0.02
    public static let lightWord = 0.62
    public static let darkWord = 0.42

    /// The themes the setting shows as tabs: the page colours.
    public static let themes: [EpubTheme] = [.light, .sepia, .dark, .black, .blue]

    /// A dark page: Dim, Dark and Blue.
    public static func isDark(_ theme: EpubTheme) -> Bool { theme == .dark || theme == .black || theme == .blue }

    /// Gold on Paper and Sepia, Ember on the dark pages; the trail at 40%.
    public static func standard(for theme: EpubTheme) -> ReadAlongHighlightStyle {
        ReadAlongHighlightStyle(colour: isDark(theme) ? .ember : .gold)
    }

    /// A percent on the slider's steps, 0 to 100.
    public static func stepped(_ percent: Int) -> Int {
        let clamped = min(max(percent, 0), 100)
        return Int((Double(clamped) / Double(trailStep)).rounded()) * trailStep
    }

    /// The word's strength on `theme`'s page.
    public static func wordStrength(_ theme: EpubTheme) -> Double { isDark(theme) ? darkWord : lightWord }

    /// The trail's: the word's times the trail percent.
    public func trailStrength(_ theme: EpubTheme) -> Double { Self.wordStrength(theme) * Double(trail) / 100 }

    /// The word's wash on `page` under `ink`.
    public func word(_ theme: EpubTheme, page: UInt32, ink: UInt32) -> UInt32 {
        Self.hold(colour.rgb, page: page, ink: ink, strength: Self.wordStrength(theme))
    }

    /// The trail's wash; nil at 0%, when none is drawn.
    public func trail(_ theme: EpubTheme, page: UInt32, ink: UInt32) -> UInt32? {
        trail > 0 ? Self.hold(colour.rgb, page: page, ink: ink, strength: trailStrength(theme)) : nil
    }

    /// A sentence-level book's sentence: #52's wash, in this colour.
    public func sentence(page: UInt32, ink: UInt32) -> UInt32 {
        ReadAlongGlow.wash(accent: colour.rgb, page: page, ink: ink)
    }

    /// `colour` mixed into `page` at `strength`, stepped down 0.02 at a time
    /// until `ink` keeps 4.5:1 against it. Opaque.
    public static func hold(_ colour: UInt32, page: UInt32, ink: UInt32, strength: Double) -> UInt32 {
        var share = strength
        while share > 0 {
            let mixed = GlassColors.mix(page | 0xFF00_0000, colour | 0xFF00_0000, share)
            if GlassColors.contrast(ink | 0xFF00_0000, mixed) >= contrast { return mixed }
            share -= holdStep
        }
        return page | 0xFF00_0000
    }
}

/// The highlight chosen for each page theme, kept on the device.
public struct ReadAlongHighlightStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private static let prefix = "readalong.highlight."

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func style(for theme: EpubTheme) -> ReadAlongHighlightStyle {
        let standard = ReadAlongHighlightStyle.standard(for: theme)
        let colour = defaults.string(forKey: Self.prefix + theme.rawValue + ".colour")
            .flatMap(ReadAlongHighlightStyle.Colour.init(rawValue:)) ?? standard.colour
        let trail = (defaults.object(forKey: Self.prefix + theme.rawValue + ".trail") as? NSNumber)?.intValue ?? standard.trail
        return ReadAlongHighlightStyle(colour: colour, trail: trail)
    }

    public func set(_ style: ReadAlongHighlightStyle, for theme: EpubTheme) {
        defaults.set(style.colour.rawValue, forKey: Self.prefix + theme.rawValue + ".colour")
        defaults.set(style.trail, forKey: Self.prefix + theme.rawValue + ".trail")
    }

    /// Use the default: the colour and the trail both.
    public func reset(_ theme: EpubTheme) {
        defaults.removeObject(forKey: Self.prefix + theme.rawValue + ".colour")
        defaults.removeObject(forKey: Self.prefix + theme.rawValue + ".trail")
    }

    public func isStandard(_ theme: EpubTheme) -> Bool { style(for: theme) == .standard(for: theme) }
}
