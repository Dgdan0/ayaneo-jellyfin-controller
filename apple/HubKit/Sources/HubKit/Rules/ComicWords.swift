import Foundation

// The comic reader's words (#16): its heading and the line under it, and the
// card at the end of an issue. Android's `ReaderTitleFormatter` and
// `EndOfIssue` (`reader/PagedImageState.kt`), with their test cases.

public enum ReaderTitleFormatter {
    public static func format(_ seriesTitle: String, _ publicationTitle: String, fallback: String) -> String {
        let series = seriesTitle.trimmingCharacters(in: .whitespaces)
        let publication = publicationTitle.trimmingCharacters(in: .whitespaces)
        if series.isEmpty && publication.isEmpty { return fallback.trimmingCharacters(in: .whitespaces) }
        if series.isEmpty { return publication }
        if publication.isEmpty || series.caseInsensitiveCompare(publication) == .orderedSame { return series }
        return series + " · " + publication
    }

    /// The reader's heading: the series, or the publication when it has none.
    public static func heading(_ seriesTitle: String, _ publicationTitle: String, fallback: String) -> String {
        for candidate in [seriesTitle, publicationTitle, fallback] {
            let trimmed = candidate.trimmingCharacters(in: .whitespaces)
            if !trimmed.isEmpty { return trimmed }
        }
        return ""
    }

    /// Under the heading, which issue this is: Kavita names a comic's chapters
    /// "Chapter 51", but a comic has issues. A manga keeps chapters. Empty when
    /// the publication is the series itself.
    public static func issue(kind: String, seriesTitle: String, publicationTitle: String, number: String) -> String {
        let title = publicationTitle.trimmingCharacters(in: .whitespaces)
        var n = number.trimmingCharacters(in: .whitespaces)
        if n.isEmpty { n = chapterNumber(title) ?? "" }
        let generic = title.isEmpty || title == n || chapterNumber(title) != nil
        if title.caseInsensitiveCompare(seriesTitle.trimmingCharacters(in: .whitespaces)) == .orderedSame && n.isEmpty {
            return ""
        }
        if !generic { return title }
        if n.isEmpty { return "" }
        return kind == "manga" ? "Chapter \(n)" : "Issue \(n)"
    }

    /// "Issue 51 · Page 2 of 24", and where on the page when it is read in
    /// steps: "Part 2 of 3" (`part` of `parts`, from 1; none for a page read whole).
    public static func subtitle(_ issue: String, page: Int, pageCount: Int, part: Int? = nil, parts: Int = 0) -> String {
        var pieces: [String] = []
        if !issue.isEmpty { pieces.append(issue) }
        if pageCount > 0 { pieces.append("Page \(page) of \(pageCount)") }
        if let part, parts > 1 { pieces.append(self.part(part, of: parts)) }
        return pieces.joined(separator: " · ")
    }

    /// "Part 2 of 3": a step down a page read in thirds.
    public static func part(_ part: Int, of parts: Int) -> String { "Part \(part) of \(parts)" }

    /// "Chapter 51" read as 51, the whole title or nothing.
    private static func chapterNumber(_ title: String) -> String? {
        guard let match = title.wholeMatch(of: /chapter\s*(\S+)/.ignoresCase()) else { return nil }
        return String(match.1)
    }
}

/// Where the controller's cursor starts when the controls open: on the last
/// one, the next page.
public enum ReaderControlFocusPolicy {
    public static func initialIndex(controlCount: Int) -> Int? { controlCount > 0 ? controlCount - 1 : nil }
}

/// The card at the end of an issue (#16, C6): "End of Fantastic Four #51" and
/// what A goes on to, "Next: #52". Words only, so tested.
public enum EndOfIssue {
    /// "Fantastic Four #51"; a named issue keeps its name ("Fantastic Four · Annual 1965").
    public static func name(series: String, title: String, number: String) -> String {
        let run = series.trimmingCharacters(in: .whitespaces)
        let label = title.trimmingCharacters(in: .whitespaces)
        // The number: given, or read from "Chapter 51", "Issue 51" or a bare "51".
        var n = number.trimmingCharacters(in: .whitespaces)
        if n.isEmpty {
            if let numbered = issueNumber(label) {
                n = numbered
            } else if label.wholeMatch(of: /#?[0-9]+(?:\.[0-9]+)?[a-zA-Z]?/) != nil {
                n = label.hasPrefix("#") ? String(label.dropFirst()) : label
            }
        }
        let bare = label.hasPrefix("#") ? String(label.dropFirst()) : label
        let generic = label.isEmpty || bare == n || issueNumber(label) != nil
            || label.caseInsensitiveCompare(run) == .orderedSame
        if !generic { return [run, label].filter { !$0.isEmpty }.joined(separator: " · ") }
        if !n.isEmpty { return [run, "#" + n].filter { !$0.isEmpty }.joined(separator: " ") }
        return run.isEmpty ? "this issue" : run
    }

    public static func heading(series: String, title: String, number: String) -> String {
        "End of " + name(series: series, title: title, number: number)
    }

    /// What comes next: the same run's next issue by its number alone
    /// ("Next: #52"), another series' by its name, or why there is none.
    public static func next(currentSeries: String, nextSeries: String?, nextTitle: String, nextNumber: String,
                            readingList: Bool) -> String {
        guard let nextSeries else { return readingList ? "End of the reading list" : "That was the last issue" }
        let sameRun = nextSeries.trimmingCharacters(in: .whitespaces)
            .caseInsensitiveCompare(currentSeries.trimmingCharacters(in: .whitespaces)) == .orderedSame
        let label = name(series: sameRun ? "" : nextSeries, title: nextTitle, number: nextNumber)
        return "Next: " + (label.isEmpty ? "the next issue" : label)
    }

    /// "Chapter 51", "Issue #51": the number, when the title is only that.
    private static func issueNumber(_ title: String) -> String? {
        guard let match = title.wholeMatch(of: /(?:chapter|issue)\s*#?(\S+)/.ignoresCase()) else { return nil }
        return String(match.1)
    }
}
