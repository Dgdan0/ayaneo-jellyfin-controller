import Foundation

/// A passage found in a book (#37): the chapter it is in, the words round
/// the match, and the place to open (a Readium locator as JSON, its text
/// included, so the page opens on the passage rather than its chapter).
public struct BookSearchHit: Equatable, Sendable, Identifiable {
    public let id: Int
    public let chapter: String
    public let before: String
    public let match: String
    public let after: String
    public let locator: String

    public init(id: Int, chapter: String, before: String, match: String, after: String, locator: String) {
        self.id = id
        self.chapter = chapter
        self.before = before
        self.match = match
        self.after = after
        self.locator = locator
    }

    /// What VoiceOver reads: the chapter, then the passage.
    public var label: String {
        chapter + ", " + (before + match + after).trimmingCharacters(in: .whitespaces)
    }
}

/// Searching a book (#37; Android's `EpubBookSearch` and its sheet): a word
/// or phrase of up to 200 characters, the first 100 passages, 30 seconds at
/// most, and the words each line of the results says.
public enum BookSearch {
    public static let limit = 100
    public static let longestQuery = 200
    public static let seconds = 30

    /// The phrase to look for, trimmed; nil when there is none.
    public static func query(_ text: String) -> String? {
        let phrase = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !phrase.isEmpty else { return nil }
        return String(phrase.prefix(longestQuery))
    }

    public static let enterPhrase = "Enter a word or phrase"
    public static let searching = "Searching…"

    /// Over the results: "12 matches", or that the first 100 are all there is to see.
    public static func summary(_ count: Int, limit: Int = limit) -> String {
        if count == 0 { return "No matches in this edition" }
        if count >= limit { return "First \(limit) matches · narrow your search" }
        return count == 1 ? "1 match" : "\(count) matches"
    }

    /// Why there are no results, and what to do about it.
    public enum Problem: Error, Equatable, Sendable {
        case tooLong, failed, notSearchable

        public var title: String {
            switch self {
            case .tooLong: "Search took too long"
            case .failed: "Could not search this edition"
            case .notSearchable: "This edition cannot be searched"
            }
        }

        public var detail: String {
            switch self {
            case .tooLong: "Try a more specific phrase"
            case .failed: "Choose to try again"
            case .notSearchable: "Its text is not available to search"
            }
        }
    }

    /// A passage's chapter, or "Matching passage" where the book names none.
    public static func chapter(_ title: String?) -> String {
        let name = collapse(title ?? "")
        return name.isEmpty ? "Matching passage" : name
    }

    /// The words round a match, on one line: up to 80 characters before it
    /// and 120 after, cut at a word with "…" where cut.
    public static func snippet(before: String, match: String, after: String) -> (before: String, match: String, after: String) {
        var lead = collapse(before, keepingEdges: true)
        var trail = collapse(after, keepingEdges: true)
        if lead.count > 80 {
            let cut = String(lead.suffix(80))
            let start = cut.firstIndex(of: " ").map { cut.index(after: $0) } ?? cut.startIndex
            lead = "…" + String(cut[start...])
        }
        if trail.count > 120 {
            let cut = String(trail.prefix(120))
            let end = cut.lastIndex(of: " ") ?? cut.endIndex
            trail = String(cut[..<end]) + "…"
        }
        return (lead.hasPrefix(" ") ? String(lead.dropFirst()) : lead, collapse(match), trail)
    }

    /// Runs of spaces, tabs and line breaks as one space; the ends trimmed
    /// unless they are kept, where a space separates the match from its words.
    static func collapse(_ text: String, keepingEdges: Bool = false) -> String {
        let one = text.split(omittingEmptySubsequences: true, whereSeparator: \.isWhitespace).joined(separator: " ")
        guard keepingEdges, !one.isEmpty else { return one }
        let lead = text.first?.isWhitespace == true ? " " : ""
        let trail = text.last?.isWhitespace == true ? " " : ""
        return lead + one + trail
    }
}
