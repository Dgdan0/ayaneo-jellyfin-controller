import Foundation

/// The page each entry of a book's Contents starts on (#55), in the count the
/// reader's "Page X of Y" corner keeps (`PageInfo.pageInBook`): the book's own
/// page count from the hub when it has one, else Readium's positions.
public enum ContentsPages {
    /// The page an entry starts on: `share` of the way through its file
    /// (`href`, a fragment ignored), 0 for an entry that opens the file. Nil
    /// for a file not in the reading order, or a book that has not been
    /// measured: a number never guessed.
    public static func page(sections: BookSections, bookPages: Int, href: String, share: Double) -> Int? {
        guard sections.index(of: href) != nil else { return nil }
        if bookPages > 0 {
            guard let start = sections.progress(href: href, progression: share, totalProgression: nil) else { return nil }
            return ReadingBookFacts.page(of: start, pages: bookPages)
        }
        return sections.positionInBook(href: href, progression: share).flatMap(PageInfo.counted)?.page
    }

    /// How far through a file's text the element `anchor` starts: the
    /// letters of the body before its tag, of all the body's letters (not
    /// spaces, so a line's end counts for nothing). Where an entry that points
    /// into the middle of a file (`chapter.xhtml#part2`) starts, so the
    /// entries of one file do not all show the same page. Nil when the file
    /// has no element of that id.
    public static func share(html: String, anchor: String) -> Double? {
        shares(html: html, anchors: [anchor])[anchor]
    }

    /// `share` for several anchors of one file, its text read once. An
    /// anchor the file has no element for is left out.
    public static func shares(html: String, anchors: [String]) -> [String: Double] {
        var found: [String: Double] = [:]
        let body = Self.body(html)
        let total = letters(body)
        for anchor in Set(anchors) {
            guard !anchor.isEmpty else { found[anchor] = 0; continue }
            let escaped = NSRegularExpression.escapedPattern(for: anchor)
            guard total > 0,
                  let at = body.range(of: #"\sid\s*=\s*["']"# + escaped + #"["']"#, options: .regularExpression),
                  let tag = body[..<at.lowerBound].lastIndex(of: "<") else { continue }
            found[anchor] = min(max(Double(letters(body[..<tag])) / Double(total), 0), 1)
        }
        return found
    }

    /// The document's body, or all of it when it has none.
    private static func body(_ html: String) -> Substring {
        guard let open = html.range(of: #"<body[^>]*>"#, options: [.regularExpression, .caseInsensitive]) else { return html[...] }
        let close = html.range(of: "</body>", options: [.caseInsensitive, .backwards], range: open.upperBound..<html.endIndex)
        return html[open.upperBound..<(close?.lowerBound ?? html.endIndex)]
    }

    /// The letters of some HTML's text: no tags, no script or style, an entity one letter, no spaces.
    private static func letters(_ html: Substring) -> Int {
        var text = String(html)
        text = text.replacingOccurrences(of: #"<(script|style)\b[^>]*>[\s\S]*?</\1>"#, with: "",
                                         options: [.regularExpression, .caseInsensitive])
        text = text.replacingOccurrences(of: #"<[^>]*>"#, with: "", options: .regularExpression)
        text = text.replacingOccurrences(of: #"&#?[A-Za-z0-9]+;"#, with: "x", options: .regularExpression)
        return text.unicodeScalars.filter { !$0.properties.isWhitespace }.count
    }
}
