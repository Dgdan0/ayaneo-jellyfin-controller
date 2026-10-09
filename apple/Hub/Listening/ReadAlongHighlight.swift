#if os(iOS)
import HubKit
@preconcurrency import ReadiumNavigator
@preconcurrency import ReadiumShared
import UIKit

/// Read along's sentence as Readium draws it (#16, X7, #52; Android's
/// `highlightNarration`): `ReadAlongGlow`'s opaque wash of the Books accent
/// under the words, each row of the sentence its own line's box, fitted by
/// the page script the navigator runs (`BookNavigator.fitNarration`).
/// The book reader opens a book to read along with `templates(tint:)` among
/// its navigator's decoration templates (beside Readium's own, which its
/// search and bookmarks may use), and applies `decorations(_:)` in `group`
/// as the voice moves; nil clears it.
enum ReadAlongHighlight {
    /// The style Readium knows the sentence by, ReadAlongGlow's class: the
    /// whole sentence in a sentence-level edition, the trail through it in a
    /// word-level one (#66).
    static let style = Decoration.Style.Id(rawValue: ReadAlongGlow.className)
    /// The word spoken's (#66).
    static let wordStyle = Decoration.Style.Id(rawValue: ReadAlongGlow.wordClassName)
    /// The decoration group of the sentence being read, and of the word.
    static let group: DecorationGroup = "readalong"
    static let wordGroup: DecorationGroup = "readalong-word"

    /// The sentence's template and the word's, each washed by the page script.
    static func templates(tint: UInt32) -> [Decoration.Style.Id: HTMLDecorationTemplate] {
        [style: HTMLDecorationTemplate(layout: .boxes, width: .wrap, element: ReadAlongGlow.element(tint: tint),
                                       stylesheet: ReadAlongGlow.stylesheet(tint: tint)),
         wordStyle: HTMLDecorationTemplate(layout: .boxes, width: .wrap, element: ReadAlongGlow.wordElement(),
                                           stylesheet: ReadAlongGlow.wordStylesheet())]
    }

    /// The word spoken, `fragment` in the sentence's document (#66).
    static func word(_ fragment: String, of segment: ReadAlongSegment, hrefs: BookHrefs) -> [Decoration] {
        let fields: [String: Any] = ["href": hrefs.readium(segment.textHref), "type": "application/xhtml+xml",
                                     "locations": ["fragments": [fragment]]]
        guard let data = try? JSONSerialization.data(withJSONObject: fields), let json = String(data: data, encoding: .utf8),
              let locator = try? Locator(jsonString: json) else { return [] }
        return [Decoration(id: "word", locator: locator, style: Decoration.Style(id: wordStyle))]
    }

    /// Readium's own templates with the sentence's: what a read-along
    /// navigator is configured with.
    static func allTemplates(tint: UInt32) -> [Decoration.Style.Id: HTMLDecorationTemplate] {
        HTMLDecorationTemplate.defaultTemplates().merging(templates(tint: tint)) { _, sentence in sentence }
    }

    /// The sentence spoken, or none in a pause: what to apply in `group`.
    /// Its file as Readium spells it (`hrefs`, #61).
    static func decorations(_ segment: ReadAlongSegment?, hrefs: BookHrefs) -> [Decoration] {
        guard let segment, let locator = locator(segment, hrefs: hrefs) else { return [] }
        return [Decoration(id: "narration", locator: locator, style: Decoration.Style(id: style))]
    }

    /// Where the sentence is, as Readium finds it: its file, in Readium's own
    /// spelling, and its element's id.
    static func locator(_ segment: ReadAlongSegment, hrefs: BookHrefs) -> Locator? {
        let fields: [String: Any] = ["href": hrefs.readium(segment.textHref), "type": "application/xhtml+xml",
                                     "locations": ["fragments": [segment.fragment]]]
        guard let data = try? JSONSerialization.data(withJSONObject: fields), let json = String(data: data, encoding: .utf8) else {
            return nil
        }
        return try? Locator(jsonString: json)
    }
}
#endif
