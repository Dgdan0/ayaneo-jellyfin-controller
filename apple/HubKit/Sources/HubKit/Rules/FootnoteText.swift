import Foundation

/// A footnote's words for its card (#25, phase 4): Android's
/// `reader/FootnoteText.kt` (#18, E5), with its test cases. Readium hands
/// over the note's element as HTML when a note reference is followed.
/// Paragraphs and line breaks stay; the link back to the text, an arrow such
/// as ↩, goes, since the card is closed rather than followed.
public enum FootnoteText {
    // Patterns, compiled for each note: a note opens a tap at a time.
    private static let backlink = #"<a\b[^>]*>\s*(?:↩︎?|↑|⤴|⏎|\^|&#8617;|&#x21a9;|&#8593;|&#x2191;)\s*</a>"#
    private static let lineBreak = #"<br\s*/?>"#
    private static let blockEnd = #"</(p|div|li|blockquote|h[1-6]|aside|section|tr)\s*>"#
    private static let tag = #"<[^>]+>"#
    private static let entity = #"&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);"#
    private static let named = ["amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ",
                                "mdash": "—", "ndash": "–", "hellip": "…", "lsquo": "‘", "rsquo": "’", "ldquo": "“",
                                "rdquo": "”"]
    private static let loneArrow = "[ \\t]*[↩⤴]\u{FE0E}?[ \\t]*(?=\\n|$)"
    private static let spaces = "[ \\t\u{00A0}]+"
    private static let gaps = #"\n{3,}"#

    public static func plain(_ html: String) -> String {
        var text = replace(regex(backlink, caseInsensitive: true), in: html, with: "")
        text = replace(regex(lineBreak, caseInsensitive: true), in: text, with: "\n")
        text = replace(regex(blockEnd, caseInsensitive: true), in: text, with: "\n\n")
        text = replace(regex(tag), in: text, with: "")
        // One pass, so "&amp;lt;" reads "&lt;" rather than "<".
        text = decodeEntities(text)
        // An arrow written as a character rather than a link, left at the end of a line.
        text = replace(regex(loneArrow), in: text, with: "")
        let runs = regex(spaces)
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
            .map { replace(runs, in: $0, with: " ").trimmingCharacters(in: .whitespaces) }
        return replace(regex(gaps), in: lines.joined(separator: "\n"), with: "\n\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func decodeEntities(_ text: String) -> String {
        let source = text as NSString
        var out = ""
        var last = 0
        for match in regex(entity).matches(in: text, range: NSRange(location: 0, length: source.length)) {
            out += source.substring(with: NSRange(location: last, length: match.range.location - last))
            let name = source.substring(with: match.range(at: 1))
            let decoded: String? = if name.hasPrefix("#x") || name.hasPrefix("#X") {
                Int(name.dropFirst(2), radix: 16).flatMap(character)
            } else if name.hasPrefix("#") {
                Int(name.dropFirst()).flatMap(character)
            } else {
                named[name]
            }
            out += decoded ?? source.substring(with: match.range)
            last = match.range.location + match.range.length
        }
        out += source.substring(from: last)
        return out
    }

    private static func character(_ code: Int) -> String? {
        guard (1...0x10FFFF).contains(code), let scalar = Unicode.Scalar(UInt32(code)) else { return nil }
        return String(Character(scalar))
    }

    private static func regex(_ pattern: String, caseInsensitive: Bool = false) -> NSRegularExpression {
        // The patterns are fixed above, so a failure here is a typo in this file.
        try! NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : [])
    }

    private static func replace(_ expression: NSRegularExpression, in text: String, with template: String) -> String {
        expression.stringByReplacingMatches(in: text, range: NSRange(location: 0, length: (text as NSString).length),
                                            withTemplate: NSRegularExpression.escapedTemplate(for: template))
    }
}
