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

    /// A picture of the screen kept with the result, passed or not: Comfort is drawn, so it is seen.
    @MainActor
    private func keep(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    @MainActor
    private func waitForGone(_ element: XCUIElement, _ seconds: TimeInterval = 5) -> Bool {
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: element)
        return XCTWaiter().wait(for: [gone], timeout: seconds) == .completed
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

    // MARK: Comfort

    /// Comfort in the ebook reader's Appearance: brightness and warmth, a
    /// black page, and the screen kept on while narrating; every reader
    /// opens the same way after.
    @MainActor
    func testComfortIsInTheBookReaderAndKeptForTheComicReader() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_BOOK": "rw_demo_recursion/demo-rw_demo_recursion",
                                 "HUB_BOOK_SCROLL": "0", "HUB_BOOK_SHEET": "comfort"]
        app.launch()
        let brightness = app.sliders["comfort-brightness"]
        XCTAssertTrue(brightness.waitForExistence(timeout: 25), "Appearance has no Comfort: \(buttons(app))")
        XCTAssertTrue(app.sliders["comfort-warmth"].exists)
        brightness.adjust(toNormalizedSliderPosition: 0.5)
        XCTAssertTrue(text(app, containing: "%").exists)
        let black = app.buttons["comfort-black"]
        XCTAssertTrue(black.exists, "a book's Comfort has no black page")
        let wasBlack = black.label.contains("On")
        black.tap()
        XCTAssertTrue(app.buttons["comfort-black"].label.contains(wasBlack ? "Off" : "On"), "the black page did not change")
        keep(app, "comfort-dimmed-black-page")
        XCTAssertTrue(app.buttons["comfort-awake"].exists, "no keeping the screen on while narrating")
        // Back as it was, for the next run.
        app.buttons["comfort-black"].tap()
        app.sliders["comfort-brightness"].adjust(toNormalizedSliderPosition: 1)
        app.sliders["comfort-warmth"].adjust(toNormalizedSliderPosition: 0)
        app.terminate()

        // The comic reader's Reading options have the same Comfort, as it was left.
        let comic = XCUIApplication()
        comic.launchArguments = ["-demo"]
        comic.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_READ": "rw_demo_ff/rw_demo_ff-51",
                                   "HUB_READ_SHEET": "display"]
        comic.launch()
        let comicBrightness = comic.sliders["comfort-brightness"]
        for _ in 0..<6 where !(comicBrightness.exists && comicBrightness.isHittable) { comic.swipeUp() }
        XCTAssertTrue(comicBrightness.waitForExistence(timeout: 20), "the comic reader has no Comfort")
        XCTAssertEqual(comicBrightness.value as? String, "100%", "Comfort is not one for every reader")
        XCTAssertFalse(comic.buttons["comfort-black"].exists, "a comic is offered a book's black page")
    }

    /// An audiobook's page has the same Comfort, over the page, with no black
    /// page to offer: The Alloy of Law, a demo tone, put on the player.
    @MainActor
    func testComfortIsOnTheAudiobookPage() {
        let app = launch(open: "book:rw_demo_alloy")
        let entry = app.buttons["book-entry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 15), "the audiobook's page did not open: \(buttons(app))")
        entry.tap()
        let comfort = app.buttons["listen-comfort"]
        XCTAssertTrue(comfort.waitForExistence(timeout: 10), "the audiobook's page has no Comfort: \(buttons(app))")
        let ready = NSPredicate(format: "isEnabled == true")
        wait(for: [expectation(for: ready, evaluatedWith: comfort)], timeout: 15)
        let window = app.windows.firstMatch.frame
        for _ in 0..<4 where comfort.frame.maxX > window.maxX {
            let row = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: window.width * 0.85, dy: comfort.frame.midY))
            row.press(forDuration: 0.05, thenDragTo: row.withOffset(CGVector(dx: -window.width * 0.6, dy: 0)))
        }
        comfort.tap()
        let brightness = app.sliders["comfort-brightness"]
        XCTAssertTrue(brightness.waitForExistence(timeout: 5), "Comfort did not open")
        XCTAssertTrue(app.sliders["comfort-warmth"].exists)
        XCTAssertFalse(app.buttons["comfort-black"].exists, "an audiobook is offered a book's black page")
        app.sliders["comfort-warmth"].adjust(toNormalizedSliderPosition: 0.6)
        keep(app, "comfort-audiobook-warm")
        app.sliders["comfort-warmth"].adjust(toNormalizedSliderPosition: 0)
        XCTAssertEqual(app.sliders["comfort-warmth"].value as? String, "Off")
        app.buttons["Done"].tap()
        XCTAssertTrue(waitForGone(brightness), "Done did not close Comfort")
    }
}
