import Foundation

// The typefaces a book can be read in (#47): the owner's decision after the
// Kindle comparison (READER_TYPOGRAPHY_PLAN.md). Literata is the default on
// every device, bundled so the iPad, the iPhone and the Pocket draw the same
// page; Atkinson Hyperlegible Next is bundled too; Charter, Georgia and Iowan
// Old Style are Apple's own and cost nothing; Original leaves the book's own
// face. "Serif", which was Times, is gone.

/// One choice in the menu's row of typefaces.
public struct EpubTypeface: Equatable, Sendable, Identifiable {
    public enum Source: Sendable {
        /// The book's own face and styling.
        case original
        /// A font file shipped inside the app, declared to Readium.
        case bundled
        /// A font the device has, named to Readium.
        case system
    }

    /// What is kept on the device.
    public let id: String
    /// What the tile says under its "Aa".
    public let label: String
    /// The CSS family name Readium is given; nil for the book's own.
    public let family: String?
    public let source: Source
    /// The font's PostScript name, for the tile's "Aa" drawn in the real face; nil draws the system serif.
    public let sample: String?

    /// What the menu says about it beside its name: "Default", or what the book keeps.
    public var detail: String {
        switch id {
        case EpubTypefaces.standard: "Default"
        case EpubTypefaces.original: "The book's own"
        default: ""
        }
    }
}

public enum EpubTypefaces {
    /// Literata, on a new device and after Reset text style.
    public static let standard = "literata"
    /// The book's own face and styling, kept as "publisher" since the first reader.
    public static let original = "publisher"

    public static let all: [EpubTypeface] = [
        EpubTypeface(id: original, label: "Original", family: nil, source: .original, sample: nil),
        EpubTypeface(id: "literata", label: "Literata", family: "Literata", source: .bundled, sample: "Literata-Regular"),
        EpubTypeface(id: "charter", label: "Charter", family: "Charter", source: .system, sample: "Charter-Roman"),
        EpubTypeface(id: "georgia", label: "Georgia", family: "Georgia", source: .system, sample: "Georgia"),
        EpubTypeface(id: "iowan", label: "Iowan", family: "Iowan Old Style", source: .system, sample: "IowanOldStyle-Roman"),
        EpubTypeface(id: "atkinson", label: "Atkinson Hyperlegible", family: "Atkinson Hyperlegible Next", source: .bundled,
                     sample: "AtkinsonHyperlegibleNext-Regular"),
    ]

    public static func typeface(_ id: String) -> EpubTypeface? { all.first { $0.id == id } }

    /// The CSS family Readium draws `id` in; nil for the book's own face.
    /// A generic family kept from before (`serif`, `sans-serif`) still draws as itself.
    public static func family(_ id: String) -> String? {
        if let known = typeface(id) { return known.family }
        return ["serif", "sans-serif", "monospace"].contains(id) ? id : nil
    }

    /// Its name in words, for a line that says what a look is.
    public static func label(_ id: String) -> String {
        switch id {
        case "serif": "Serif"
        case "sans-serif": "Sans"
        default: typeface(id)?.label ?? "Original"
        }
    }

    /// The bundled font files, by the name they have in the app, for Readium to serve:
    /// each family's roman and italic where there is an italic.
    public static let bundledFiles: [(family: String, roman: String, italic: String?)] = [
        ("Literata", "Literata", "Literata-Italic"),
        ("Atkinson Hyperlegible Next", "AtkinsonHyperlegibleNext", nil),
    ]
}
