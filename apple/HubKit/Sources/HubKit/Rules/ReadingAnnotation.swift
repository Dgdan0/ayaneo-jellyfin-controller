import Foundation

// Highlights and notes (#62), on the Pocket, the iPad and the iPhone alike:
// kept by the hub per profile and per work. The Pocket's `ReadingAnnotation`,
// `AnnotationLimits`, `AnnotationQuotes`, `AnnotationFinder`, `AnnotationIds`
// and `AnnotationBodies`, under the same names.

/// The four colours, in the order the colour row shows them; `rawValue` is
/// what the hub and the other apps keep.
public enum HighlightColor: String, CaseIterable, Sendable {
    case yellow, blue, pink, green

    public static let `default` = HighlightColor.yellow

    public var label: String { rawValue.prefix(1).uppercased() + rawValue.dropFirst() }

    public static func of(_ id: String?) -> HighlightColor? { id.flatMap(HighlightColor.init(rawValue:)) }

    public static func orDefault(_ id: String?) -> HighlightColor { of(id) ?? .default }
}

/// The passage and the words either side of it, as the page's text says them:
/// an anchor that survives another edition of the book (other markup, another
/// file split). The text is searched for, the context choosing between two
/// places that say the same words (`AnnotationFinder`).
public struct AnnotationQuote: Codable, Equatable, Hashable, Sendable {
    public var before: String
    public var highlight: String
    public var after: String

    public init(before: String = "", highlight: String = "", after: String = "") {
        self.before = before
        self.highlight = highlight
        self.after = after
    }

    enum CodingKeys: String, CodingKey { case before, highlight, after }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(before: c.value(.before, ""), highlight: c.value(.highlight, ""), after: c.value(.after, ""))
    }
}

/// One highlight, with its note when it has one: the hub's JSON as it is.
/// `document` is the file in `DocumentPath`'s one spelling and `locator` the
/// writing app's Readium locator, a hint only for the app that wrote it. A
/// `deleted` one is a tombstone, kept so a device that was offline learns of
/// it; it is never shown.
public struct ReadingAnnotation: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var color: String
    public var note: String
    public var document: String
    public var quote: AnnotationQuote
    public var locator: JSONValue?
    public var createdAt: Int64
    public var updatedAt: Int64
    public var deleted: Bool
    /// When the hub stored this version, on its own clock: what "changed
    /// since" counts. Never set by the app.
    public var syncedAt: Int64

    public init(id: String, color: String = HighlightColor.default.rawValue, note: String = "", document: String = "",
                quote: AnnotationQuote = AnnotationQuote(), locator: JSONValue? = nil, createdAt: Int64 = 0,
                updatedAt: Int64 = 0, deleted: Bool = false, syncedAt: Int64 = 0) {
        self.id = id
        self.color = color
        self.note = note
        self.document = document
        self.quote = quote
        self.locator = locator
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.deleted = deleted
        self.syncedAt = syncedAt
    }

    public var hasNote: Bool { !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    public var highlightColor: HighlightColor { HighlightColor.orDefault(color) }

    enum CodingKeys: String, CodingKey { case id, color, note, document, quote, locator, createdAt, updatedAt, deleted, syncedAt }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), color: c.value(.color, HighlightColor.default.rawValue), note: c.value(.note, ""),
                  document: c.value(.document, ""), quote: c.value(.quote, AnnotationQuote()), locator: c.optional(.locator),
                  createdAt: c.value(.createdAt, 0), updatedAt: c.value(.updatedAt, 0), deleted: c.value(.deleted, false),
                  syncedAt: c.value(.syncedAt, 0))
    }
}

/// What the hub keeps of one highlight, in bytes (UTF-8): more is refused, so
/// it is cut here, never sent to be refused for ever.
public enum AnnotationLimits {
    public static let noteBytes = 4000
    public static let passageBytes = 2000

    /// The most of `text` that fits in `bytes`, cut between characters (code points, as the Pocket cuts).
    public static func clip(_ text: String, _ bytes: Int) -> String {
        if text.utf8.count <= bytes { return text }
        var used = 0
        var kept = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            let size = String(scalar).utf8.count
            if used + size > bytes { break }
            used += size
            kept.append(scalar)
        }
        return String(kept)
    }
}

/// Making a quote from what Readium reports of a selection.
public enum AnnotationQuotes {
    /// How much of the neighbouring text is kept either side: enough to choose
    /// between two places that read alike.
    public static let context = 60

    /// A selection as a quote: the passage with its whitespace tidied, and a
    /// little of the words before and after it.
    public static func of(_ before: String?, _ highlight: String, _ after: String?) -> AnnotationQuote {
        AnnotationQuote(before: tail(before ?? "", context),
                        highlight: AnnotationLimits.clip(tidy(highlight), AnnotationLimits.passageBytes),
                        after: head(after ?? "", context))
    }

    /// The last `max` characters of `text`, starting at a word.
    public static func tail(_ text: String, _ max: Int) -> String {
        let tidy = Array(self.tidy(text))
        if tidy.count <= max { return String(tidy) }
        let cut = Array(tidy[(tidy.count - max)...])
        // The cut began inside a word when the character before it is a letter: drop that piece.
        let inside = isWord(tidy[tidy.count - max - 1]) && isWord(cut[0])
        guard inside else { return String(cut) }
        guard let space = cut.firstIndex(of: " ") else { return String(cut) }
        return String(cut[(space + 1)...])
    }

    /// The first `max` characters of `text`, ending at a word.
    public static func head(_ text: String, _ max: Int) -> String {
        let tidy = Array(self.tidy(text))
        if tidy.count <= max { return String(tidy) }
        let cut = Array(tidy[..<max])
        let inside = isWord(tidy[max]) && isWord(cut[max - 1])
        guard inside else { return String(cut) }
        guard let space = cut.lastIndex(of: " ") else { return String(cut) }
        return String(cut[..<space])
    }

    /// Runs of whitespace as one space, trimmed.
    static func tidy(_ text: String) -> String {
        text.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    private static func isWord(_ character: Character) -> Bool { character.isLetter || character.isNumber }
}

/// Finding a quote in a document's text (#62): the search Readium's
/// decoration makes to draw it, here for the list of highlights, which says
/// "Can't find this passage" for one that the book's text no longer holds
/// (another edition, a corrected typo) rather than dropping it.
///
/// Both sides are compared in one normal form (`normalize`): Unicode NFC, a
/// run of whitespace as one space, curly quotes and dashes as their plain
/// forms, an ellipsis as three dots, no soft hyphens or zero-width
/// characters, case ignored. A comparison form only; nothing is written in it.
/// Places are counted in the normal form's Unicode scalars.
public enum AnnotationFinder {
    /// Where the quote is: `start` to `end` in the normalised text, `share` of
    /// the way through it, and how many places say it.
    public struct Found: Equatable, Sendable {
        public let start: Int
        public let end: Int
        public let length: Int
        public let places: Int

        public var share: Double { length <= 0 ? 0 : Double(start) / Double(length) }
    }

    public static func normalize(_ text: String) -> String {
        var normalizer = TextNormalizer()
        normalizer.append(text)
        return normalizer.text
    }

    /// Streams text into the comparison form chunk by chunk, so a document's
    /// text can be built as its nodes are read and the place of each element
    /// in it noted (`length` is where the next character goes). A chunk's
    /// leading space is held until a character follows.
    public struct TextNormalizer: Sendable {
        private var out = String.UnicodeScalarView()
        private var count = 0
        private var space = false

        public init() {}

        public var length: Int { count }
        public var text: String { String(out) }

        public mutating func append(_ text: String) {
            for scalar in text.precomposedStringWithCanonicalMapping.unicodeScalars {
                switch scalar.value {
                case 0x00AD, 0x200B, 0x200C, 0x200D, 0xFEFF:
                    continue
                case 0x2026:
                    if space { put(" ") }
                    space = false
                    put("."); put("."); put(".")
                    continue
                default:
                    break
                }
                if scalar.properties.isWhitespace || scalar.value == 0x00A0 {
                    space = count > 0
                    continue
                }
                if space { put(" ") }
                space = false
                let plain: Unicode.Scalar
                switch scalar.value {
                case 0x2018, 0x2019, 0x201B, 0x02BC: plain = "'"
                case 0x201C, 0x201D, 0x201F: plain = "\""
                case 0x2013, 0x2014, 0x2212: plain = "-"
                default: plain = scalar
                }
                // One scalar for one, as Java's `Character.toLowerCase(char)` does.
                let lower = plain.properties.lowercaseMapping.unicodeScalars
                if lower.count == 1, let only = lower.first { put(only) } else { put(plain) }
            }
        }

        private mutating func put(_ scalar: Unicode.Scalar) {
            out.append(scalar)
            count += 1
        }
    }

    /// Where `quote` is in `text`, or nil when it is not there. The passage
    /// itself must be present; when it is said in more than one place the one
    /// whose neighbours agree best with the quote's wins, and between equals
    /// the one nearest `hint` (how far through the document it was, 0 to 1),
    /// else the first.
    public static func find(_ text: String, _ quote: AnnotationQuote, hint: Double? = nil) -> Found? {
        findIn(normalize(text), quote, hint: hint)
    }

    /// `find` in a document's text already in the comparison form.
    public static func findIn(_ normalized: String, _ quote: AnnotationQuote, hint: Double? = nil) -> Found? {
        let document = Array(normalized.unicodeScalars)
        let passage = Array(normalize(quote.highlight).trimmingCharacters(in: .whitespaces).unicodeScalars)
        guard !passage.isEmpty, !document.isEmpty else { return nil }
        let before = Array(normalize(quote.before).unicodeScalars)
        let after = Array(normalize(quote.after).unicodeScalars)
        var best: Found?
        var bestScore = -1
        var bestDistance = Double.greatestFiniteMagnitude
        var places = 0
        var from = 0
        while let at = index(of: passage, in: document, from: from) {
            places += 1
            let end = at + passage.count
            let score = agreement(document, at, end, before, after)
            let distance = hint.map { abs(Double(at) / Double(document.count) - $0) } ?? Double(at)
            if score > bestScore || (score == bestScore && distance < bestDistance) {
                best = Found(start: at, end: end, length: document.count, places: 0)
                bestScore = score
                bestDistance = distance
            }
            from = at + 1
        }
        guard let best else { return nil }
        return Found(start: best.start, end: best.end, length: best.length, places: places)
    }

    private static func index(of needle: [Unicode.Scalar], in haystack: [Unicode.Scalar], from: Int) -> Int? {
        guard needle.count <= haystack.count, from <= haystack.count - needle.count else { return nil }
        var at = from
        while at <= haystack.count - needle.count {
            if haystack[at] == needle[0] {
                var i = 1
                while i < needle.count && haystack[at + i] == needle[i] { i += 1 }
                if i == needle.count { return at }
            }
            at += 1
        }
        return nil
    }

    /// How many characters of the words just before and just after the
    /// passage the quote's context repeats. The context is kept trimmed, so the
    /// one space between it and the passage is stepped over rather than compared.
    private static func agreement(_ document: [Unicode.Scalar], _ start: Int, _ end: Int,
                                  _ before: [Unicode.Scalar], _ after: [Unicode.Scalar]) -> Int {
        let trimmedBefore = Array(before.reversed().drop { $0 == " " }.reversed())
        let trimmedAfter = Array(after.drop { $0 == " " })
        var score = 0
        let from = start > 0 && document[start - 1] == " " ? start - 1 : start
        var i = 0
        while i < trimmedBefore.count, from - 1 - i >= 0, document[from - 1 - i] == trimmedBefore[trimmedBefore.count - 1 - i] {
            score += 1
            i += 1
        }
        let to = end < document.count && document[end] == " " ? end + 1 : end
        i = 0
        while i < trimmedAfter.count, to + i < document.count, document[to + i] == trimmedAfter[i] {
            score += 1
            i += 1
        }
        return score
    }
}

/// A highlight's id as the hub asks for it: `an_` and 32 lower-case hex digits.
public enum AnnotationIds {
    public static func new() -> String {
        var generator = SystemRandomNumberGenerator()
        return "an_" + (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255, using: &generator)) }.joined()
    }

    public static func valid(_ id: String) -> Bool {
        let hex = id.dropFirst(3)
        return id.hasPrefix("an_") && hex.count == 32 && hex.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
    }
}

/// The body of a write to the hub (#62): only what it accepts (it refuses a
/// field it does not know, so a tombstone's `deleted` and the `syncedAt` it
/// stamped never go back), with the note and the passage cut to what it
/// keeps, and the locator left out when it would not fit (it is a hint only).
public enum AnnotationBodies {
    public static let locatorBytes = 8 * 1024

    public static func of(_ a: ReadingAnnotation) -> Data {
        var fields: [String: JSONValue] = [
            "id": .string(a.id),
            "color": .string(a.color),
            "note": .string(AnnotationLimits.clip(a.note, AnnotationLimits.noteBytes)),
            "document": .string(a.document),
            "quote": .object([
                "before": .string(a.quote.before),
                "highlight": .string(AnnotationLimits.clip(a.quote.highlight, AnnotationLimits.passageBytes)),
                "after": .string(a.quote.after),
            ]),
            "createdAt": .int(a.createdAt),
            "updatedAt": .int(a.updatedAt),
        ]
        if let locator = a.locator, locator.encoded().count <= locatorBytes { fields["locator"] = locator }
        return JSONValue.object(fields).encoded()
    }
}

/// The Highlights tab's filters: all, one colour, or those with a note.
public enum HighlightFilter: String, CaseIterable, Sendable {
    case all, yellow, blue, pink, green, notes

    public var label: String {
        switch self {
        case .all: "All"
        case .notes: "With notes"
        default: rawValue.prefix(1).uppercased() + rawValue.dropFirst()
        }
    }

    public var description: String {
        switch self {
        case .all: "All highlights"
        case .notes: "Highlights with notes"
        default: "\(label) highlights"
        }
    }

    public func apply(_ list: [ReadingAnnotation]) -> [ReadingAnnotation] {
        switch self {
        case .all: list
        case .notes: list.filter(\.hasNote)
        default: list.filter { $0.color == rawValue }
        }
    }
}
