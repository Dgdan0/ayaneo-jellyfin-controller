import XCTest

/// The comic reader against the demo hub (#25, phase 3): `HUB_READ` opens it
/// at launch on the Books demo's Fantastic Four, issue 51, whose place is its
/// second page. Reading writes the place to the hub, so these run with `-demo`
/// only, as every UI test does.
final class ComicReaderTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launchReading(_ environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        // Thirds, the reader's own way, unless a test reads the whole page: a run's way is kept.
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_READ": "rw_demo_ff/rw_demo_ff-51",
                                 "HUB_READ_FIT": "thirds"]
            .merging(environment) { $1 }
        app.launch()
        XCTAssertTrue(page(app).waitForExistence(timeout: 15), "the reader did not open")
        XCTAssertTrue(waitUntil(15) { Self.value(of: self.page(app)).contains("of 24") }, "the issue's pages did not come")
        return app
    }

    @MainActor
    private func page(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "comic-page").firstMatch
    }

    /// The controls come up as it opens and go by themselves; a tap on the
    /// right third then reads on: a step down the page, or the next page
    /// where the whole page fits.
    @MainActor
    func testItOpensAtItsPlaceAndATapOnTheRightThirdReadsOn() {
        let app = launchReading()
        let reader = page(app)
        XCTAssertEqual(reader.label, "Fantastic Four")
        let opened = Self.value(of: reader)
        // Page 2, or the spread it stands in (2 and 3) on a wide window held sideways.
        XCTAssertTrue(opened.contains("Issue 51") && [2, 3].contains(Self.pageNumber(opened)), "it opened at \(opened)")
        XCTAssertTrue(app.buttons["Close reader"].waitForNonExistence(timeout: 8), "the controls stayed over the page")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.88, dy: 0.5)).tap()
        XCTAssertTrue(waitUntil(5) { Self.value(of: reader) != opened }, "a tap on the right third did nothing")
        let moved = Self.value(of: reader)
        XCTAssertTrue(moved.contains("Part 2 of") || Self.pageNumber(moved) > Self.pageNumber(opened), "it read on to \(moved)")
        // The left third goes back.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        XCTAssertTrue(waitUntil(5) { Self.value(of: reader) == opened }, "a tap on the left third did not go back")
    }

    /// A keyboard reads on and back (`ReaderKeyboard`): Space a step on, as Ⓐ
    /// does, and ← a step back at the page's fit. (XCUITest's Delete reaches no
    /// view without text input, so Ⓑ's key is not tried here.)
    @MainActor
    func testTheKeyboardReadsOnAndBack() {
        let app = launchReading()
        let reader = page(app)
        XCTAssertTrue(app.buttons["Close reader"].waitForNonExistence(timeout: 8), "the controls stayed over the page")
        let opened = Self.value(of: reader)
        app.typeKey(" ", modifierFlags: [])
        XCTAssertTrue(waitUntil(5) { Self.value(of: reader) != opened }, "Space did not read on")
        app.typeKey(.leftArrow, modifierFlags: [])
        XCTAssertTrue(waitUntil(5) { Self.value(of: reader) == opened }, "← did not go back: \(Self.value(of: reader))")
    }

    /// A drag from the page's outer edge curls it over like paper to the next
    /// page, and one from the other edge back (#32). The middle of the page
    /// keeps its own drag, so only the curl can turn from an edge.
    @MainActor
    func testADragFromTheEdgeCurlsThePageOnAndBack() {
        let app = launchReading(["HUB_READ_FIT": "whole"])
        let reader = page(app)
        XCTAssertTrue(app.buttons["Close reader"].waitForNonExistence(timeout: 8), "the controls stayed over the page")
        let opened = Self.value(of: reader)
        let openedPage = Self.pageNumber(opened)
        XCTAssertTrue([2, 3].contains(openedPage), "it opened at \(opened)")
        let edge = app.coordinate(withNormalizedOffset: CGVector(dx: 0.985, dy: 0.55))
        edge.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)),
                   withVelocity: .default, thenHoldForDuration: 0.2)
        XCTAssertTrue(waitUntil(5) { Self.pageNumber(Self.value(of: reader)) > openedPage },
                      "the curl did not turn on: \(Self.value(of: reader))")
        // The page beyond is decoded ahead before the edge is the curl's again.
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        let back = app.coordinate(withNormalizedOffset: CGVector(dx: 0.015, dy: 0.55))
        back.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)),
                   withVelocity: .default, thenHoldForDuration: 0.2)
        XCTAssertTrue(waitUntil(5) { Self.pageNumber(Self.value(of: reader)) == openedPage },
                      "the curl did not turn back: \(Self.value(of: reader))")
    }

    /// The keyboard plays the same curl: Space reads down the page in thirds
    /// and, from the last third, turns it over as the edge's curl does; the
    /// page is the reader's again once it has turned.
    @MainActor
    func testTheKeyboardTurnsThePageWithTheCurl() {
        let app = launchReading()
        let reader = page(app)
        XCTAssertTrue(app.buttons["Close reader"].waitForNonExistence(timeout: 8), "the controls stayed over the page")
        let openedPage = Self.pageNumber(Self.value(of: reader))
        let turned = { Self.pageNumber(Self.value(of: reader)) > openedPage }
        for _ in 0..<6 where !turned() {
            app.typeKey(" ", modifierFlags: [])
            // Long enough for a curl to finish and the page to come back.
            _ = waitUntil(2) { turned() }
        }
        XCTAssertTrue(turned(), "Space did not turn on: \(Self.value(of: reader))")
        // A tap in the middle still brings the controls: the page is the reader's again.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons["Close reader"].waitForExistence(timeout: 5), "the page did not come back after the curl")
    }

    @MainActor
    func testThePagesGridOpensAPage() {
        let app = launchReading(["HUB_READ_CHROME": "pinned"])
        app.buttons["Pages"].firstMatch.tap()
        let tenth = app.buttons["Page 10"].firstMatch
        XCTAssertTrue(tenth.waitForExistence(timeout: 5), "the grid did not open")
        tenth.tap()
        // Page 10, or the spread it stands in.
        XCTAssertTrue(waitUntil(8) { (10...11).contains(Self.pageNumber(Self.value(of: self.page(app)))) },
                      "page 10 did not open: \(Self.value(of: page(app)))")
        XCTAssertFalse(app.buttons["Page 10"].exists, "the grid stayed open")
    }

    /// The last page read on to its end: the card names the issue and the
    /// next one, and Continue opens it.
    @MainActor
    func testTheEndOfAnIssueNamesTheNextAndGoesOnToIt() {
        let app = launchReading(["HUB_READ_SHEET": "end"])
        let heading = app.staticTexts.matching(identifier: "comic-end-heading").firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 10), "no end card")
        XCTAssertEqual(heading.label, "End of Fantastic Four #51")
        XCTAssertTrue(app.staticTexts["Next: #52"].exists, "the next issue is not named")
        app.buttons["Continue"].tap()
        XCTAssertTrue(waitUntil(10) { Self.value(of: self.page(app)).contains("Issue 52") }, "Continue did not open #52")
        XCTAssertTrue(Self.value(of: page(app)).contains("Page 1 of 24"))
    }

    /// Close leaves the reader for the app under it.
    @MainActor
    func testCloseLeavesTheReader() {
        let app = launchReading(["HUB_READ_CHROME": "pinned"])
        app.buttons["Close reader"].tap()
        XCTAssertTrue(page(app).waitForNonExistence(timeout: 5), "the reader stayed open")
    }

    /// From a run's page, as the Books side opens it (`ReaderHost`): the
    /// issue being read opens, taps read on, the middle brings the controls
    /// back, and Close comes back to the run's page.
    @MainActor
    func testARunsPageOpensItsIssueAndCloseComesBackToThePage() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_OPEN": "book:rw_demo_ff"]
        app.launch()
        let entry = app.buttons["book-entry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 15), "the comic's page did not open")
        entry.tap()
        let reader = page(app)
        XCTAssertTrue(reader.waitForExistence(timeout: 15), "the issue did not open in the reader")
        XCTAssertTrue(waitUntil(15) { Self.value(of: reader).contains("Issue 51") }, "the reader did not open at issue 51")
        XCTAssertTrue(app.buttons["Close reader"].waitForNonExistence(timeout: 8), "the controls stayed over the page")
        let opened = Self.value(of: reader)
        for _ in 0..<3 {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.88, dy: 0.5)).tap()
            RunLoop.current.run(until: Date().addingTimeInterval(0.6))
        }
        XCTAssertTrue(waitUntil(5) { Self.value(of: reader) != opened }, "taps on the right third did not read on")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let close = app.buttons["Close reader"]
        XCTAssertTrue(close.waitForExistence(timeout: 5), "the middle did not bring the controls back")
        close.tap()
        XCTAssertTrue(reader.waitForNonExistence(timeout: 5), "the reader stayed open")
        XCTAssertTrue(entry.waitForExistence(timeout: 10), "closing the reader did not come back to the run's page")
    }

    /// Asks `condition` every quarter of a second until it holds or `seconds` pass.
    @MainActor
    private func waitUntil(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return condition()
    }

    @MainActor
    private static func value(of element: XCUIElement) -> String { element.value as? String ?? "" }

    /// The page in "Issue 51, Page 3 of 24": a spread's last page, as the reader names it; 0 when none.
    private static func pageNumber(_ value: String) -> Int {
        guard let range = value.range(of: #"Page \d+ of"#, options: .regularExpression) else { return 0 }
        return Int(value[range].dropFirst(5).dropLast(3)) ?? 0
    }
}
