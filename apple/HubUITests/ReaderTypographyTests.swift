import HubKit
import UIKit
import XCTest

/// The reader's Kindle look (#47), against the demo hub (`-demo`): the page
/// colours as Kindle measured them, the margins and the taps in them, and the
/// corners with the book's title. Reading writes the place to the hub, so
/// these run with `-demo` only, as every UI test does.
final class ReaderTypographyTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        // The simulator keeps the way it was turned; later tests expect it upright.
        XCUIDevice.shared.orientation = .portrait
    }

    private static let recursion = "rw_demo_recursion/demo-rw_demo_recursion"

    @MainActor
    private func launchReading(_ environment: [String: String] = [:], arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        // The corners all on and the page in the book, whatever an earlier run chose.
        app.launchArguments = ["-demo", "-epub.pageInfo.clock", "YES", "-epub.pageInfo.percentage", "YES",
                               "-epub.pageInfo.place", "pageInBook"] + arguments
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_BOOK": Self.recursion, "HUB_BOOK_SCROLL": "0"]
            .merging(environment) { $1 }
        app.launch()
        XCTAssertTrue(page(app).waitForExistence(timeout: 20), "the book did not open")
        return app
    }

    @MainActor
    private func page(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "book-page").firstMatch
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    @MainActor
    private func waitUntil(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return condition()
    }

    /// The colour of a point of the screen, 0 to 255 a channel, read from a screenshot
    /// (taken of the whole screen, at the device's scale).
    @MainActor
    private func colour(_ app: XCUIApplication, atX x: CGFloat, y: CGFloat) -> (red: Int, green: Int, blue: Int)? {
        let image = app.screenshot().image
        guard let cg = image.cgImage else { return nil }
        let scale = image.scale
        let pixel = CGPoint(x: x * scale, y: y * scale)
        guard pixel.x >= 0, pixel.y >= 0, Int(pixel.x) < cg.width, Int(pixel.y) < cg.height,
              let cropped = cg.cropping(to: CGRect(x: Int(pixel.x), y: Int(pixel.y), width: 1, height: 1)) else { return nil }
        var bytes = [UInt8](repeating: 0, count: 4)
        let space = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(data: &bytes, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: space,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.draw(cropped, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        return (Int(bytes[0]), Int(bytes[1]), Int(bytes[2]))
    }

    private func near(_ got: (red: Int, green: Int, blue: Int)?, _ red: Int, _ green: Int, _ blue: Int, within: Int = 6) -> Bool {
        guard let got else { return false }
        return abs(got.red - red) <= within && abs(got.green - green) <= within && abs(got.blue - blue) <= within
    }

    // MARK: Themes

    /// The page's colours are Kindle's, measured (Sepia #FCF0D9, Dark black), Dim is the old grey, and
    /// the margin beside the page is the page's own colour, so the page has no edge.
    @MainActor
    func testThePageColoursAreKindlesAndTheMarginIsThePagesOwn() {
        for (theme, colours) in [("SEPIA", (0xFC, 0xF0, 0xD9)), ("DARK", (0x20, 0x20, 0x20)), ("BLACK", (0, 0, 0))] {
            let app = launchReading(arguments: ["-epub.theme", theme])
            let middle = page(app).frame.midY
            XCTAssertTrue(waitUntil(8) { self.near(self.colour(app, atX: 4, y: middle), colours.0, colours.1, colours.2) },
                          "the margin of \(theme) is \(String(describing: colour(app, atX: 4, y: middle)))")
            app.terminate()
        }
    }

    // MARK: The margins

    /// Kindle's outer margin (24 points on a phone, 90 on an iPad's wide window): the corners' title and
    /// time line up with the text's edge, and a tap or swipe in the margin outside Readium's own view turns
    /// the page. The margin is asked of the rule the layout uses, so the test holds on either device.
    @MainActor
    func testTheCornersLineUpWithTheTextAndTheMarginTurnsThePage() {
        let app = launchReading()
        let title = element(app, "book-corner-title")
        let clock = element(app, "book-corner-clock")
        let place = app.buttons["book-corner-place"]
        XCTAssertTrue(clock.waitForExistence(timeout: 10))
        // A phone with a Dynamic Island or a notch (every iPhone 812 points tall or more) draws no title
        // at the top centre, where the island would cover it (#58); an iPad and the Mac keep it.
        let window = app.windows.firstMatch.frame
        let island = UIDevice.current.userInterfaceIdiom == .phone && max(window.width, window.height) >= 812
        if island {
            XCTAssertFalse(title.exists, "the book's title is behind the island")
        } else {
            XCTAssertTrue(title.waitForExistence(timeout: 10), "the book's title is not at the top")
            XCTAssertEqual(title.label, "Reading Recursion")
        }
        let width = page(app).frame.width
        // Balanced is the default (`pageMargins` 1); an iPad's window under 600 points wide is laid out as a phone's.
        let margin = EpubGeometry.outerMargin(pageMargins: 1, tablet: UIDevice.current.userInterfaceIdiom != .phone,
                                              width: Double(width))
        // The title is in the middle, and the clock ends where the text does: Kindle's margin.
        if !island { XCTAssertEqual(title.frame.midX, width / 2, accuracy: 2, "the title is not centred") }
        XCTAssertEqual(width - clock.frame.maxX, margin, accuracy: 3, "the clock is not on the text's edge (\(margin))")
        XCTAssertEqual(place.frame.minX, margin, accuracy: 3, "the bottom left is not on the text's edge (\(margin))")
        // The strips above and below the text are the layout's own: Kindle's on a phone (#58) and an iPad (#47).
        let strip = PageInfo.strip(compactHeight: false, tablet: UIDevice.current.userInterfaceIdiom != .phone)
        XCTAssertLessThan((island ? clock : title).frame.maxY, strip.top, "the top corner is in the strip above the text")
        // Its tap reaches a little above the words, which are in the strip below the text.
        XCTAssertGreaterThanOrEqual(place.frame.minY, page(app).frame.height - strip.bottom - 8, "the bottom left is in the strip below the text")

        // A tap in the margin turns the page, forward at the right edge and back at the left.
        XCTAssertTrue(waitUntil(10) { place.label.hasPrefix("Page 1 of ") }, "the bottom left says \(place.label)")
        page(app).coordinate(withNormalizedOffset: CGVector(dx: 1 - 5 / width, dy: 0.5)).tap()
        XCTAssertTrue(waitUntil(8) { !place.label.hasPrefix("Page 1 of ") }, "a tap in the right margin did not turn the page: \(place.label)")
        let moved = place.label
        page(app).coordinate(withNormalizedOffset: CGVector(dx: 5 / width, dy: 0.5)).tap()
        XCTAssertTrue(waitUntil(8) { place.label != moved }, "a tap in the left margin did not turn back: \(place.label)")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "kindle-corners"
        shot.lifetime = .keepAlways
        add(shot)
    }

    // MARK: The menu

    /// The font sheet: six typefaces with "Aa" drawn in each, a size slider that steps a tenth, a Spacing
    /// row that opens line spacing and margins and a way back, and brightness fixed at the foot of every page.
    @MainActor
    func testTheFontSheetHasTheTypefacesAStepSliderAndASpacingPage() {
        let app = launchReading(["HUB_BOOK_SHEET": "appearance"],
                                arguments: ["-epub.fontFamily", "literata", "-epub.fontScale", "1.3", "-epub.lineHeight", "1.5",
                                            "-epub.pageMargins", "1"])
        for label in ["Original", "Literata", "Charter", "Georgia", "Iowan", "Atkinson Hyperlegible"] {
            XCTAssertTrue(app.buttons[label].firstMatch.waitForExistence(timeout: 15), "no \(label) tile")
        }
        XCTAssertFalse(app.buttons["Serif"].exists, "Serif, which was Times, is gone")
        XCTAssertTrue(app.buttons["Literata"].firstMatch.isSelected, "Literata is not the default")
        app.buttons["Charter"].firstMatch.tap()
        XCTAssertTrue(waitUntil(5) { app.buttons["Charter"].firstMatch.isSelected && !app.buttons["Literata"].firstMatch.isSelected })
        app.buttons["Literata"].firstMatch.tap()

        // The size: marks a tenth apart, 70% to 200%; the arrows step it a mark as a controller's left and right do.
        let size = app.sliders["book-size"]
        XCTAssertTrue(size.exists, "no size slider")
        XCTAssertEqual(size.value as? String, "130%")
        app.typeKey(.downArrow, modifierFlags: [])
        app.typeKey(.downArrow, modifierFlags: [])
        app.typeKey(.rightArrow, modifierFlags: [])
        XCTAssertTrue(waitUntil(5) { (size.value as? String) == "140%" }, "right did not step the size a mark: \(String(describing: size.value))")
        app.typeKey(.leftArrow, modifierFlags: [])
        app.typeKey(.leftArrow, modifierFlags: [])
        XCTAssertTrue(waitUntil(5) { (size.value as? String) == "120%" }, "left did not step the size back: \(String(describing: size.value))")
        app.typeKey(.rightArrow, modifierFlags: [])
        XCTAssertTrue(waitUntil(5) { (size.value as? String) == "130%" }, "the size says \(String(describing: size.value))")

        // Brightness is at the foot of this page, and of every other.
        XCTAssertTrue(app.sliders["comfort-brightness"].exists, "no brightness at the foot of Font")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "font-sheet"
        shot.lifetime = .keepAlways
        add(shot)

        // Spacing: line spacing and margins, with a way back.
        let spacing = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Spacing'")).firstMatch
        XCTAssertTrue(spacing.exists, "Font has no Spacing")
        XCTAssertTrue(spacing.label.contains("Relaxed") && spacing.label.contains("Balanced"), "Spacing says \(spacing.label)")
        spacing.tap()
        let back = app.buttons["appearance-back"]
        XCTAssertTrue(back.waitForExistence(timeout: 5), "Spacing has no way back")
        for label in ["Tight", "Relaxed", "Open", "Narrow", "Balanced", "Wide"] {
            XCTAssertTrue(app.buttons[label].firstMatch.exists, "Spacing has no \(label)")
        }
        XCTAssertTrue(app.buttons["Relaxed"].firstMatch.isSelected && app.buttons["Balanced"].firstMatch.isSelected)
        XCTAssertTrue(app.sliders["comfort-brightness"].exists, "no brightness at the foot of Spacing")
        app.buttons["Open"].firstMatch.tap()
        XCTAssertTrue(waitUntil(5) { app.buttons["Open"].firstMatch.isSelected }, "Open was not chosen")
        app.buttons["Relaxed"].firstMatch.tap()
        let spacingShot = XCTAttachment(screenshot: app.screenshot())
        spacingShot.name = "spacing-sheet"
        spacingShot.lifetime = .keepAlways
        add(spacingShot)
        back.tap()
        XCTAssertTrue(app.buttons["Themes"].waitForExistence(timeout: 5), "back did not come to Font")
        for tab in ["Layout", "Themes", "Comfort"] {
            app.buttons[tab].firstMatch.tap()
            XCTAssertTrue(app.sliders["comfort-brightness"].waitForExistence(timeout: 5), "no brightness at the foot of \(tab)")
        }
        XCTAssertTrue(app.sliders["comfort-warmth"].exists, "warmth stays in Comfort")
        app.buttons["Font"].firstMatch.tap()
    }
}
