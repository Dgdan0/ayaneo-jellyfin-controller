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

    /// Kindle's 24 points on a phone: the corners' title and time line up with the text's edge, and a
    /// tap or swipe in the margin outside Readium's own view turns the page.
    @MainActor
    func testTheCornersLineUpWithTheTextAndTheMarginTurnsThePage() {
        let app = launchReading()
        let title = element(app, "book-corner-title")
        let clock = element(app, "book-corner-clock")
        let place = app.buttons["book-corner-place"]
        XCTAssertTrue(title.waitForExistence(timeout: 10), "the book's title is not at the top")
        XCTAssertEqual(title.label, "Reading Recursion")
        XCTAssertTrue(clock.waitForExistence(timeout: 5))
        let width = page(app).frame.width
        // The title is in the middle, and the clock ends where the text does: Kindle's margin, 24 points on a phone.
        XCTAssertEqual(title.frame.midX, width / 2, accuracy: 2, "the title is not centred")
        XCTAssertEqual(width - clock.frame.maxX, 24, accuracy: 3, "the clock is not on the text's edge")
        XCTAssertEqual(place.frame.minX, 24, accuracy: 3, "the bottom left is not on the text's edge")
        XCTAssertLessThan(title.frame.maxY, 62, "the title is in the strip above the text")
        XCTAssertGreaterThan(place.frame.minY, page(app).frame.height - 62, "the bottom left is in the strip below the text")

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
}
