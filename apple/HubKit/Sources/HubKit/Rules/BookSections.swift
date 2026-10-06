import Foundation

/// A book as Readium measures it (#25, phase 4): its parts in reading order
/// (one per file of the EPUB) and how many positions each holds, from the
/// publication's positions, for where the page is, the time left, the slider
/// and the chapter keys. Android keeps this arithmetic in `EpubReaderScreen`
/// (`bookProgress`, `seekBook`, `updatePosition`, `changeChapter`); here it
/// is pure, so it is tested.
public struct BookSections: Equatable, Sendable {
    public struct Section: Equatable, Sendable {
        /// The part's file, as Readium names it ("OEBPS/chapter-04.xhtml").
        public let href: String
        /// Where the part starts in the whole book, 0 to 1.
        public let start: Double
        /// How many positions it holds.
        public let size: Int
    }

    public let sections: [Section]

    public init(sections: [Section]) {
        self.sections = sections
    }

    /// From Readium's positions, in reading order: each one's part and where it is in the whole book.
    public init(positions: [(href: String, totalProgression: Double?)]) {
        var sections: [Section] = []
        var sizes: [String: Int] = [:]
        for position in positions {
            let href = Self.path(position.href)
            if sizes[href] == nil {
                sections.append(Section(href: href, start: position.totalProgression ?? 0, size: 0))
            }
            sizes[href, default: 0] += 1
        }
        self.sections = sections.map { Section(href: $0.href, start: $0.start, size: sizes[$0.href] ?? 0) }
    }

    public var isEmpty: Bool { sections.isEmpty }
    public var sizes: [Int] { sections.map(\.size) }

    /// The part holding `href`, whatever fragment it carries.
    public func index(of href: String) -> Int? {
        let path = Self.path(href)
        return sections.firstIndex { $0.href == path }
    }

    /// How far through the whole book: the part's place, and the way through
    /// it. Readium's positions can be sparse in a short part, so this reads
    /// more smoothly than its own total progression; that is the answer
    /// when the part is not known.
    public func progress(href: String, progression: Double, totalProgression: Double?) -> Double? {
        guard let index = index(of: href) else { return totalProgression }
        let start = sections[index].start
        let end = index + 1 < sections.count ? sections[index + 1].start : 1
        return min(max(start + (end - start) * TimeLeft.clamped(progression), 0), 1)
    }

    /// Where `fraction` of the book is: the part, and how far through it.
    public func seek(_ fraction: Double) -> (href: String, progression: Double)? {
        guard !sections.isEmpty else { return nil }
        let index = max(sections.lastIndex { $0.start <= fraction } ?? 0, 0)
        let start = sections[index].start
        let end = index + 1 < sections.count ? sections[index + 1].start : 1
        let local = end > start ? min(max((fraction - start) / (end - start), 0), 1) : 0
        return (sections[index].href, local)
    }

    /// Where in the whole book, in positions: what the pace measures.
    public func position(href: String, progression: Double) -> Double? {
        index(of: href).flatMap { TimeLeft.position(sizes, section: $0, progression: progression) }
    }

    /// What is left at `minutesPerPosition`.
    public func timeLeft(href: String, progression: Double, minutesPerPosition: Double) -> TimeLeft? {
        index(of: href).flatMap {
            TimeLeft.ofPositions(sizes, section: $0, progression: progression, minutesPerPosition: minutesPerPosition)
        }
    }

    /// The part `delta` away in the reading order, or nil past either end.
    public static func chapter(from index: Int, delta: Int, count: Int) -> Int? {
        let next = index + delta
        return next >= 0 && next < count ? next : nil
    }

    /// The menu's line: "Four · Page 3 of 12 in chapter · 49% of book";
    /// a part with no title of its own is "Reading".
    public static func line(title: String?, page: (index: Int, count: Int)?, progress: Double?) -> String {
        var parts: [String] = []
        let name = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        parts.append(name.isEmpty ? "Reading" : name)
        if let page, page.count > 1 {
            parts.append("Page \(min(max(page.index, 0), page.count - 1) + 1) of \(page.count) in chapter")
        }
        if let progress { parts.append("\(Fmt.readingPercentLabel(progress)) of book") }
        return parts.joined(separator: " · ")
    }

    /// The slider while it is dragged: "Go to 43%".
    public static func browseLine(_ fraction: Double) -> String {
        "Go to \(Int((min(max(fraction, 0), 1) * 100).rounded()))%"
    }

    /// A place's file without its fragment.
    public static func path(_ href: String) -> String {
        String(href.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
    }
}

/// A book's place as the bookmarks and the place keeper see it: Readium's
/// locator, as its JSON. Only its part, its fragment and how far through it
/// are read here; the rest is kept as it came.
public enum BookLocator {
    /// "Chapter 3 · 41%": the part's title, else its file, and how far through the book.
    public static func label(_ json: String) -> String {
        guard let object = object(json) else { return "" }
        let locations = object["locations"] as? [String: Any]
        let total = (locations?["totalProgression"] as? NSNumber)?.doubleValue
        let title = (object["title"] as? String) ?? ""
        let chapter = title.isEmpty ? (object["href"] as? String ?? "") : title
        return ([chapter.isEmpty ? nil : chapter, total.map { Fmt.readingPercentLabel($0) }] as [String?])
            .compactMap { $0 }.joined(separator: " · ")
    }

    /// The part, and a point in it that survives the book being laid out
    /// again (a font size, a window): its fragment if it has one, else how
    /// far through the part, to a ten-thousandth. Android's bookmark anchor.
    public static func anchor(_ json: String) -> String? {
        guard let object = object(json), let href = object["href"] as? String, !href.isEmpty else { return nil }
        let locations = object["locations"] as? [String: Any]
        if let fragments = locations?["fragments"] as? [String], !fragments.isEmpty {
            return href + "#" + "[" + fragments.map { "\"\($0)\"" }.joined(separator: ",") + "]"
        }
        if let progression = (locations?["progression"] as? NSNumber)?.doubleValue {
            return href + "#" + String(Int(progression * 10_000))
        }
        return href + "#start"
    }

    public static func href(_ json: String) -> String? { object(json)?["href"] as? String }

    public static func progression(_ json: String) -> Double? {
        ((object(json)?["locations"] as? [String: Any])?["progression"] as? NSNumber)?.doubleValue
    }

    /// The same place: equal once read, as the hub compares them (`sameReadingLocator`); nil is no place.
    public static func same(_ left: String?, _ right: String?) -> Bool {
        let a = left.flatMap(value)
        let b = right.flatMap(value)
        switch (a, b) {
        case (nil, nil): return true
        case (let a?, let b?): return (a as? NSObject)?.isEqual(b) ?? false
        default: return false
        }
    }

    /// A locator as one line of JSON with its keys in order, so the same place reads the same.
    public static func canonical(_ value: Any) -> String? {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .withoutEscapingSlashes])
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// A locator the hub would take: an object with a part and its locations (`validReadiumLocator`).
    public static func valid(_ json: String) -> Bool {
        guard json.utf8.count <= 48 << 10, let object = object(json),
              let href = object["href"] as? String, !href.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        return object["locations"] is [String: Any]
    }

    static func object(_ json: String) -> [String: Any]? { value(json) as? [String: Any] }

    static func value(_ json: String) -> Any? {
        guard let data = json.data(using: .utf8) else { return nil }
        let parsed = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        return parsed is NSNull ? nil : parsed
    }
}
