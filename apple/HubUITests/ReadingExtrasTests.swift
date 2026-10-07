import XCTest

/// The reading extras (#37) against the demo hub: a book marked read or
/// unread, and the rest as they come. Nothing reaches a real hub: every test
/// runs with `-demo`, and a mark is kept on the device only, forgotten at
/// each demo launch as the demo's places are.
final class ReadingExtrasTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(open: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_OPEN": open]
        app.launch()
        return app
    }

    @MainActor
    private func text(_ app: XCUIApplication, containing words: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }

    @MainActor
    private func buttons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.map { $0.label }.filter { !$0.isEmpty }.joined(separator: " | ")
    }

    // MARK: Read and unread

    /// Light Bringer is half read: marked read it is finished, and unread
    /// straight after gives back its place; marked read again and the page
    /// left, unread starts it again.
    @MainActor
    func testABookMarkedReadAndUnreadKeepsOrStartsAgain() {
        let app = launch(open: "book:rw_demo_rr6")
        let mark = app.buttons["book-read"]
        XCTAssertTrue(mark.waitForExistence(timeout: 15), "the book's page has no read or unread: \(buttons(app))")
        XCTAssertTrue(text(app, containing: "49%").waitForExistence(timeout: 10), "Light Bringer is not half read")

        mark.tap()
        XCTAssertTrue(text(app, containing: "Marked as read").waitForExistence(timeout: 5), "marking it read said nothing")
        XCTAssertTrue(text(app, containing: "Finished").exists, "a book marked read is not finished")
        XCTAssertEqual(mark.label, "Mark Light Bringer unread")

        // Undone at once: its place comes back.
        mark.tap()
        XCTAssertTrue(text(app, containing: "Previous reading position restored").waitForExistence(timeout: 5))
        XCTAssertTrue(text(app, containing: "49%").exists, "undoing the mark did not give back the place")

        // Read, the page left and opened again, then unread: the book starts again.
        mark.tap()
        XCTAssertTrue(text(app, containing: "Marked as read").waitForExistence(timeout: 5))
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Back to'")).firstMatch.tap()
        // Read, it has left Continue reading: back by its series.
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Light Bringer, Red Rising #6'")).firstMatch.exists,
                       "a book marked read is still being read on Books Home")
        let series = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Red Rising, 6 books'")).firstMatch
        XCTAssertTrue(series.waitForExistence(timeout: 10), "Red Rising is not on Books Home: \(buttons(app))")
        series.tap()
        let again = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Light Bringer'")).firstMatch
        XCTAssertTrue(again.waitForExistence(timeout: 10), "Light Bringer is not in its series: \(buttons(app))")
        // Finished now in its series' row, the sixth and last: along the row to it.
        XCTAssertTrue(again.label.hasPrefix("Finished"), "the series does not show it finished: \(again.label)")
        let window = app.windows.firstMatch.frame
        for _ in 0..<4 where !(again.frame.minX >= window.minX && again.frame.maxX <= window.maxX) {
            let row = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: window.width * 0.85, dy: again.frame.midY))
            row.press(forDuration: 0.05, thenDragTo: row.withOffset(CGVector(dx: -window.width * 0.6, dy: 0)))
        }
        again.tap()
        XCTAssertTrue(mark.waitForExistence(timeout: 10))
        XCTAssertEqual(mark.label, "Mark Light Bringer unread", "the mark did not last")
        mark.tap()
        XCTAssertTrue(text(app, containing: "Marked unread").waitForExistence(timeout: 5), "unread after leaving did not start it again")
        XCTAssertFalse(text(app, containing: "49%").exists, "a book marked unread still shows its place")
        // Undone here at once: read again, as it was a moment ago.
        mark.tap()
        XCTAssertTrue(text(app, containing: "Finished").waitForExistence(timeout: 5))
        mark.tap()
    }
}
