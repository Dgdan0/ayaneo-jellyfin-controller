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
    /// The style Readium knows the sentence by, ReadAlongGlow's class.
    static let style = Decoration.Style.Id(rawValue: ReadAlongGlow.className)
    /// The decoration group of the sentence being read.
    static let group: DecorationGroup = "readalong"

    /// The sentence's template, in `tint` (an accent's colour, 0xAARRGGBB).
    static func templates(tint: UInt32) -> [Decoration.Style.Id: HTMLDecorationTemplate] {
        [style: HTMLDecorationTemplate(layout: .boxes, width: .wrap, element: ReadAlongGlow.element(tint: tint),
                                       stylesheet: ReadAlongGlow.stylesheet(tint: tint))]
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
