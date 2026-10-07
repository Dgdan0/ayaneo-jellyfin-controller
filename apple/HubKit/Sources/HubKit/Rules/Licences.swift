import Foundation

/// The licences the app ships with, for Settings › Fonts and licences (#38;
/// Android's "Fonts and licences" row). Each text is a file in
/// `apple/Hub/Resources/Licenses`, and `THIRD_PARTY_SOFTWARE.md` says what each
/// is for. A test holds this list and that folder to each other, so a licence
/// added to the app cannot be left out of the page.
public struct LicenceEntry: Equatable, Sendable, Identifiable {
    public enum Kind: Sendable { case font, software }

    public let id: String
    public let name: String
    /// What the app uses it for.
    public let role: String
    /// Who holds the copyright and which licence it is, on one line.
    public let terms: String
    /// The file's name without ".txt".
    public let file: String
    public let kind: Kind

    /// "Body text · SIL Open Font License 1.1".
    public var line: String { role + " · " + terms }
}

public enum Licences {
    public static let all: [LicenceEntry] = [
        LicenceEntry(id: "figtree", name: "Figtree", role: "Body text",
                     terms: "© The Figtree Project Authors · SIL Open Font License 1.1", file: "Figtree-OFL-1.1", kind: .font),
        LicenceEntry(id: "bricolage", name: "Bricolage Grotesque", role: "Titles",
                     terms: "© The Bricolage Grotesque Project Authors · SIL Open Font License 1.1",
                     file: "BricolageGrotesque-OFL-1.1", kind: .font),
        LicenceEntry(id: "readium", name: "Readium Swift toolkit", role: "Opens ebooks on iPad and iPhone",
                     terms: "© 2017 Readium · BSD 3-Clause", file: "Readium-swift-toolkit-BSD-3-Clause", kind: .software),
        LicenceEntry(id: "cryptoswift", name: "CryptoSwift", role: "Readium: unscrambling an ebook's embedded fonts",
                     terms: "© Marcin Krzyzanowski · zlib-style", file: "CryptoSwift-License", kind: .software),
        LicenceEntry(id: "zip", name: "Zip", role: "Readium: reading archives (includes minizip, zlib licence)",
                     terms: "© 2015 Roy Marmelstein · MIT", file: "Zip-MIT", kind: .software),
        LicenceEntry(id: "differencekit", name: "DifferenceKit", role: "Readium: changes to highlights",
                     terms: "Apache License 2.0", file: "DifferenceKit-Apache-2.0", kind: .software),
        LicenceEntry(id: "fuzi", name: "Fuzi", role: "Readium: reading an ebook's XML",
                     terms: "MIT", file: "Fuzi-MIT", kind: .software),
        LicenceEntry(id: "zipfoundation", name: "ZIPFoundation", role: "Readium: reading an ebook's ZIP",
                     terms: "© 2017-2024 Thomas Zoechling · MIT", file: "ZIPFoundation-MIT", kind: .software),
        LicenceEntry(id: "swiftsoup", name: "SwiftSoup", role: "Reading a footnote's HTML",
                     terms: "MIT", file: "SwiftSoup-MIT", kind: .software),
    ]

    public static var fonts: [LicenceEntry] { all.filter { $0.kind == .font } }
    public static var software: [LicenceEntry] { all.filter { $0.kind == .software } }

    /// What the page says above the list.
    public static let note = "Their full texts ship inside the app."
}
