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
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_READ": "rw_demo_ff/rw_demo_ff-51"]
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
        XCTAssertTrue(opened.contains("Issue 51") && opened.contains("Page 2 of 24"), "it opened at \(opened)")
        XCTAssertTrue(app.buttons["Close reader"].waitForNonExistence(timeout: 8), "the controls stayed over the page")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.88, dy: 0.5)).tap()
        XCTAssertTrue(waitUntil(5) { Self.value(of: reader) != opened }, "a tap on the right third did nothing")
        let moved = Self.value(of: reader)
        XCTAssertTrue(moved.contains("Part 2 of") || moved.contains("Page 3 of 24"), "it read on to \(moved)")
        // The left third goes back.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        XCTAssertTrue(waitUntil(5) { Self.value(of: reader) == opened }, "a tap on the left third did not go back")
    }

    /// Pages: every page as a thumbnail; one tapped opens.
    @MainActor
    func testThePagesGridOpensAPage() {
        let app = launchReading(["HUB_READ_CHROME": "pinned"])
        app.buttons["Pages"].firstMatch.tap()
        let tenth = app.buttons["Page 10"].firstMatch
        XCTAssertTrue(tenth.waitForExistence(timeout: 5), "the grid did not open")
        tenth.tap()
        XCTAssertTrue(waitUntil(8) { Self.value(of: self.page(app)).contains("Page 10 of 24") }, "page 10 did not open")
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
}
