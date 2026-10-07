#if os(iOS)
import HubKit
@preconcurrency import ReadiumNavigator
@preconcurrency import ReadiumShared
import UIKit

/// Read along's sentence as Readium draws it (#16, X7; Android's
/// `highlightNarration`): `ReadAlongGlow`'s soft wash and glow in the Books
/// accent, one box per line of the sentence, instead of Readium's flat box.
/// The book reader opens a book to read along with `templates(tint:)` among
/// its navigator's decoration templates (beside Readium's own, which its
/// search and bookmarks may use), and applies `decorations(_:)` in `group`
/// as the voice moves; nil clears it.
enum ReadAlongHighlight {
    /// The style Readium knows the sentence by, ReadAlongGlow's class.
    static let style = Decoration.Style.Id(rawValue: ReadAlongGlow.className)
    /// The decoration group of the sentence being read.
    static let group: DecorationGroup = "readalong"

    /// The sentence's template, in `tint`.
    static func templates(tint: UIColor) -> [Decoration.Style.Id: HTMLDecorationTemplate] {
        [style: HTMLDecorationTemplate(layout: .boxes, width: .wrap, element: ReadAlongGlow.element(tint: rgb(tint)),
                                       stylesheet: ReadAlongGlow.stylesheet)]
    }

    /// Readium's own templates with the sentence's: what a read-along
    /// navigator is configured with.
    static func allTemplates(tint: UIColor) -> [Decoration.Style.Id: HTMLDecorationTemplate] {
        HTMLDecorationTemplate.defaultTemplates().merging(templates(tint: tint)) { _, sentence in sentence }
    }

    /// The sentence spoken, or none in a pause: what to apply in `group`.
    static func decorations(_ segment: ReadAlongSegment?) -> [Decoration] {
        guard let segment, let locator = locator(segment) else { return [] }
        return [Decoration(id: "narration", locator: locator, style: Decoration.Style(id: style))]
    }

    /// Where the sentence is, as Readium finds it: its file and its element's id.
    static func locator(_ segment: ReadAlongSegment) -> Locator? {
        let fields: [String: Any] = ["href": segment.textHref, "type": "application/xhtml+xml",
                                     "locations": ["fragments": [segment.fragment]]]
        guard let data = try? JSONSerialization.data(withJSONObject: fields), let json = String(data: data, encoding: .utf8) else {
            return nil
        }
        return try? Locator(jsonString: json)
    }

    /// `color` as 0xRRGGBB, its alpha left to the glow.
    static func rgb(_ color: UIColor) -> UInt32 {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        guard color.getRed(&red, green: &green, blue: &blue, alpha: &alpha) else { return 0xE3B341 }
        func channel(_ value: CGFloat) -> UInt32 { UInt32((min(max(value, 0), 1) * 255).rounded()) }
        return channel(red) << 16 | channel(green) << 8 | channel(blue)
    }
}
#endif
