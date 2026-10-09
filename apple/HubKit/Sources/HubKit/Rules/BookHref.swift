import Foundation

/// One spelling for a book's document in every comparison (#61).
///
/// Readium names a document as a URL, percent-encoded
/// (`Brandon%20Sanderson%20-%20%5BMistborn%2001%5D%20-%20The%20Final%20Empire_split_010.htm`).
/// Read along's narration names it by the package's path, percent-decoded
/// (`ReadAlongPackage.resolve`). The two only agree for a plain file name, so
/// a book whose names hold spaces or brackets, as Calibre's and Storyteller's
/// often do, found its narration on no page at all.
///
/// Every comparison is now made on the `key`: the path without its fragment,
/// percent-decoded once, in NFC, with no leading slash. What goes back to
/// Readium (a jump, a highlight, a place kept) is Readium's own spelling,
/// from `BookHrefs`.
public enum BookHref {
    /// The document an href names, as Readium or a kept locator spells it.
    public static func key(_ href: String) -> String {
        let path = BookSections.path(href)
        return normalized(path.removingPercentEncoding ?? path)
    }

    /// A path already decoded (the narration's own), in the key's form.
    /// It is not decoded again, so a name with a "%" in it stays as it is.
    public static func normalized(_ path: String) -> String {
        String(path.precomposedStringWithCanonicalMapping.drop { $0 == "/" })
    }

    /// A decoded path as Readium spells one it is given raw
    /// (`RelativeURL(epubHREF:)`): each character a URL's path cannot hold
    /// is percent-encoded.
    public static func spelled(_ path: String) -> String {
        path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? path
    }
}

/// A book's documents as Readium spells them, in reading order, found by
/// their `BookHref.key`: what a locator handed back to Readium names.
public struct BookHrefs: Equatable, Sendable {
    /// The documents in reading order, as Readium spells them.
    public let readingOrder: [String]
    private let spellings: [String: String]

    /// From the publication's reading order (`Link.href`, as Readium spells it).
    public init(readingOrder: [String]) {
        let paths = readingOrder.map(BookSections.path)
        var spellings: [String: String] = [:]
        for path in paths where spellings[BookHref.key(path)] == nil {
            spellings[BookHref.key(path)] = path
        }
        self.readingOrder = paths
        self.spellings = spellings
    }

    /// Readium's spelling of the document the narration names `path`
    /// (decoded, as a `ReadAlongSegment` holds it). One not in the reading
    /// order is spelled as Readium spells a raw path.
    public func readium(_ path: String) -> String {
        spellings[BookHref.normalized(path)] ?? BookHref.spelled(path)
    }
}
