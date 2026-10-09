import Foundation

/// What a book's cover says of its formats (#54), wherever a book is shown on
/// its own: the Books grid, Home's rows, search, an author's page and a
/// series page's row (not a library tile with its random picture).
public extension ReadingBookFacts {
    enum CoverShape: Equatable, Sendable {
        /// The 2:3 cover of a book.
        case tall
        /// An audiobook's square.
        case square
    }

    /// The small round mark in the top-left corner of a tall cover of a book
    /// that is more than its ebook: a book with sound once an edition is
    /// aligned for read along, headphones while it is an ebook and an
    /// audiobook. An icon, never words: the corner nothing else of a cover uses.
    enum CoverMark: Equatable, Sendable {
        case none, readAlong, headphones

        /// What it adds to the book's description for VoiceOver; nil for none.
        public var words: String? {
            switch self {
            case .none: nil
            case .readAlong: "read along"
            case .headphones: "ebook and audiobook"
            }
        }
    }

    /// Ebook only is tall, audiobook only a square; both are tall with a mark
    /// (`formatMark`), and a read-along edition is both at once. A comic or
    /// manga is its kind pill's business and always tall. With nothing known
    /// of the formats (an older hub) the kind decides: an audiobook is square.
    static func coverShape(kind: String, formats: [String]) -> CoverShape {
        if kindTag(kind) != nil { return .tall }
        let audio = formats.contains("audiobook") || formats.contains("readaloud")
        let text = formats.contains("ebook") || formats.contains("readaloud")
        if audio && !text { return .square }
        if text { return .tall }
        return kind == "audiobook" ? .square : .tall
    }

    static func formatMark(kind: String, formats: [String]) -> CoverMark {
        if kindTag(kind) != nil { return .none }
        let audio = formats.contains("audiobook") || formats.contains("readaloud")
        let text = formats.contains("ebook") || formats.contains("readaloud")
        guard audio && text else { return .none }
        return formats.contains("readaloud") ? .readAlong : .headphones
    }

    static func coverShape(_ work: ReadingWork) -> CoverShape { coverShape(kind: work.kind, formats: formats(work)) }
    static func formatMark(_ work: ReadingWork) -> CoverMark {
        // A series is many books: no one format speaks for it.
        work.isSeries ? .none : formatMark(kind: work.kind, formats: formats(work))
    }
    static func coverShape(_ item: ReadingSectionItem) -> CoverShape { coverShape(kind: item.kind, formats: item.formats) }
    static func formatMark(_ item: ReadingSectionItem) -> CoverMark { formatMark(kind: item.kind, formats: item.formats) }

    /// A tall cover `width` across is this high.
    static func tallHeight(_ width: Double) -> Double { width * 1.5 }

    /// How far down a cover sits in a tall cover's place, so the covers of a
    /// row or a grid line up along their feet and their captions on one line:
    /// a square one half its width lower, a tall one not at all.
    static func coverLift(_ shape: CoverShape, width: Double) -> Double {
        shape == .square ? tallHeight(width) - width : 0
    }
}
