import Foundation
import Testing
@testable import HubKit

/// The reader's Kindle geometry and typefaces (#47, READER_TYPOGRAPHY_PLAN.md).
struct EpubGeometryTests {
    /// Kindle's margins: 90 points outside and a 48 point gap on an iPad (A3's check on the iPad mini).
    @Test func anIpadKeepsKindlesNinetyPointsAndFortyEightBetweenColumns() {
        let gutter = EpubGeometry.gutter(tablet: true)
        #expect(gutter == 24, "half the gap")
        #expect(EpubGeometry.outerMargin(pageMargins: 1, tablet: true, width: 744) == 90)
        let inset = EpubGeometry.inset(pageMargins: 1, tablet: true, width: 744, gutter: gutter)
        #expect(inset == 66)
        // Portrait: 744 - 180 = 564 points of text, as on Kindle.
        #expect(744 - 2 * (inset + gutter) == 564)
        // Landscape: (1133 - 132) / 2 - 48 = 452.5 points a column, and the gap between them is Readium's two gutters.
        let page = 1133 - 2 * inset
        #expect((page - 4 * gutter) / 2 == 452.5)
        #expect(2 * gutter == 48)
    }

    @Test func aPhoneKeepsTwentyFourAndTheOtherChoicesAreNarrowerAndWider() {
        #expect(EpubGeometry.gutter(tablet: false) == 12)
        #expect(EpubGeometry.outerMargin(pageMargins: 1, tablet: false, width: 393) == 24)
        #expect(EpubGeometry.inset(pageMargins: 1, tablet: false, width: 393, gutter: 12) == 12)
        let tablet = EpubAppearance.margins.map { EpubGeometry.outerMargin(pageMargins: $0.amount, tablet: true, width: 834) }
        #expect(tablet == [48, 90, 120])
        let phone = EpubAppearance.margins.map { EpubGeometry.outerMargin(pageMargins: $0.amount, tablet: false, width: 393) }
        #expect(phone == [16, 24, 36])
        // Narrow on a phone is under the gutter: the page is not inset at all, never by less than nothing.
        #expect(EpubGeometry.inset(pageMargins: 0.5, tablet: false, width: 393, gutter: 12) == 4)
        #expect(EpubGeometry.inset(pageMargins: 0.5, tablet: false, width: 393, gutter: 24) == 0)
    }

    @Test func aValueBetweenTwoChoicesLiesBetweenTheirMarginsAndOneOutsideThemStopsAtTheEnds() {
        let between = EpubGeometry.outerMargin(pageMargins: 0.75, tablet: true, width: 1024)
        #expect(between > 48 && between < 90)
        #expect(EpubGeometry.outerMargin(pageMargins: 0.1, tablet: true, width: 1024) == 48)
        #expect(EpubGeometry.outerMargin(pageMargins: 2, tablet: true, width: 1024) == 120)
        #expect(EpubGeometry.outerMargin(pageMargins: 1.7, tablet: true, width: 1024) == 120)
    }

    /// An iPad window too narrow for Kindle's margins (Slide Over) is laid out as a phone's.
    @Test func aNarrowWindowOnAnIpadHasAPhonesMargins() {
        #expect(EpubGeometry.outerMargin(pageMargins: 1, tablet: true, width: 375) == 24)
        #expect(EpubGeometry.outerMargin(pageMargins: 1, tablet: true, width: EpubGeometry.tabletMinWidth) == 90)
        // With the iPad's gutter in force the text is where the phone's margin puts it, no further in.
        #expect(EpubGeometry.inset(pageMargins: 1, tablet: true, width: 375, gutter: 24) == 0)
    }

    @Test func theTypefacesAreTheOwnersSixAndLiterataIsTheDefault() {
        #expect(EpubTypefaces.all.map(\.label) == ["Original", "Literata", "Charter", "Georgia", "Iowan", "Atkinson Hyperlegible"])
        #expect(EpubTypefaces.standard == "literata" && EpubReaderPreferences().fontFamily == "literata")
        #expect(Set(EpubTypefaces.all.map(\.id)).count == EpubTypefaces.all.count)
        #expect(EpubTypefaces.family("literata") == "Literata")
        #expect(EpubTypefaces.family("iowan") == "Iowan Old Style")
        #expect(EpubTypefaces.family("atkinson") == "Atkinson Hyperlegible Next")
        #expect(EpubTypefaces.family("publisher") == nil && EpubTypefaces.family("nonsense") == nil)
        // Kept from before, Serif still draws as the generic family; it is not in the menu.
        #expect(EpubTypefaces.family("serif") == "serif" && EpubTypefaces.typeface("serif") == nil)
        #expect(EpubTypefaces.label("serif") == "Serif" && EpubTypefaces.label("literata") == "Literata")
        #expect(EpubTypefaces.all.filter { $0.source == .bundled }.map(\.family) == ["Literata", "Atkinson Hyperlegible Next"])
        #expect(EpubTypefaces.all.filter { $0.source == .system }.map(\.id) == ["charter", "georgia", "iowan"])
        #expect(EpubTypefaces.typeface("literata")?.detail == "Default" && EpubTypefaces.typeface("publisher")?.detail == "The book's own")
        // Each bundled family is a file in the app; the sample is its PostScript name.
        #expect(EpubTypefaces.bundledFiles.map(\.family) == ["Literata", "Atkinson Hyperlegible Next"])
        #expect(EpubTypefaces.all.dropFirst().allSatisfy { $0.sample != nil } && EpubTypefaces.all.first?.sample == nil)
    }

    /// The font files ship in the app and the project says so, so a face the menu offers draws.
    @Test func everyBundledFontIsAFileOfTheAppAndNamedInTheProject() throws {
        let hub = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Hub")
        let project = try String(contentsOf: hub.deletingLastPathComponent().appendingPathComponent("project.yml"), encoding: .utf8)
        for entry in EpubTypefaces.bundledFiles {
            for name in [entry.roman, entry.italic].compactMap({ $0 }) {
                let file = hub.appendingPathComponent("Resources/Fonts/\(name).ttf")
                #expect(FileManager.default.fileExists(atPath: file.path), "\(name).ttf is not in Hub/Resources/Fonts")
                #expect(project.contains("- \(name).ttf"), "\(name).ttf is not in the project's UIAppFonts")
            }
        }
    }
}
