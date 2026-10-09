import XCTest

/// What a book's cover says of its formats and the Series view's fans (#54),
/// against the demo hub (`-demo`): Dark Matter and The Final Empire are read
/// along, The Well of Ascension is an ebook and an audiobook, Recursion an
/// ebook, The Alloy of Law an audiobook; Red Rising is on #6 without #5,
/// Mistborn on #1, Licanius not started with two books it does not have.
final class BookCoverTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(_ section: String = "library") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": section, "HUB_SIDE": "books"]
        app.launch()
        return app
    }

    @MainActor
    private func button(_ app: XCUIApplication, containing words: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }

    @MainActor
    private func buttons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.joined(separator: " | ")
    }

    @MainActor
    private func waitUntil(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return condition()
    }

    @MainActor
    private func keep(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// The reading library, in `view` (Series, Authors or Books).
    @MainActor
    private func library(_ app: XCUIApplication, _ view: String) -> XCUIElement {
        let tile = button(app, containing: "Books & Audiobooks")
        XCTAssertTrue(tile.waitForExistence(timeout: 15), "the reading libraries did not load: \(buttons(app))")
        tile.tap()
        let views = app.otherElements["library-views"]
        XCTAssertTrue(views.waitForExistence(timeout: 10), "the library has no Series, Authors and Books")
        views.buttons[view].tap()
        return views
    }

    /// Scrolled until `element` is on screen clear of the tab bar at the foot, or not.
    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        func shown() -> Bool {
            guard element.exists, element.isHittable else { return false }
            let window = app.windows.firstMatch.frame
            return element.frame.maxY < window.maxY - window.height * 0.12 && element.frame.minY > window.minY + 60
        }
        for _ in 0..<6 where !shown() {
            if element.exists && element.frame.minY <= app.windows.firstMatch.frame.minY + 60 { app.swipeDown() } else { app.swipeUp() }
        }
        return shown()
    }

    // MARK: A cover

    /// Every book on its own: a read-along book's cover carries the book with
    /// sound, an ebook with its audiobook the headphones, an ebook nothing,
    /// and an audiobook is a square sitting at the foot of a tall cover's
    /// place, so its card is as tall as the others and the titles line up.
    @MainActor
    func testACoverSaysItsFormats() {
        let app = launch()
        let views = library(app, "Books")
        let darkMatter = button(app, containing: "Dark Matter")
        XCTAssertTrue(darkMatter.waitForExistence(timeout: 10), "every book on its own did not load: \(buttons(app))")
        keep("covers-formats")
        XCTAssertTrue(darkMatter.label.contains("read along"), "Dark Matter's cover does not say read along: \(darkMatter.label)")
        XCTAssertTrue(button(app, containing: "The Final Empire").label.contains("read along"))
        let well = button(app, containing: "The Well of Ascension")
        XCTAssertTrue(well.waitForExistence(timeout: 5) && well.label.contains("ebook and audiobook"),
                      "The Well of Ascension's cover does not say ebook and audiobook: \(well.label)")
        let recursion = button(app, containing: "Recursion")
        XCTAssertFalse(recursion.label.contains("read along") || recursion.label.contains("audiobook"),
                       "an ebook's cover says more: \(recursion.label)")
        // A square at the foot of a tall cover's place: the card is the same height, and its foot level.
        let alloy = button(app, containing: "The Alloy of Law")
        XCTAssertTrue(reveal(alloy, in: app), "The Alloy of Law is not in the grid: \(buttons(app))")
        XCTAssertEqual(alloy.frame.height, recursion.frame.height, accuracy: 1,
                       "an audiobook's card is not as tall as an ebook's: \(alloy.frame) and \(recursion.frame)")
        keep("covers-square")
        // Series again for the next run.
        views.buttons["Series"].tap()
    }

    // MARK: The Series view

    /// Each series is a fan of its books with "6 books · on #6" and its bar;
    /// a tap opens the series with its books' row at the book you are on.
    @MainActor
    func testTheSeriesViewFansEachSeriesAndOpensItAtTheBookYouAreOn() {
        let app = launch()
        _ = library(app, "Series")
        let redRising = button(app, containing: "Red Rising, 6 books · on #6")
        XCTAssertTrue(redRising.waitForExistence(timeout: 10), "Red Rising is not a fan on #6: \(buttons(app))")
        XCTAssertTrue(button(app, containing: "Mistborn Original Trilogy, 4 books · on #1").exists, "Mistborn: \(buttons(app))")
        XCTAssertTrue(button(app, containing: "The Licanius Trilogy, 3 books").exists, "Licanius: \(buttons(app))")
        // A book on its own stays a cover.
        XCTAssertTrue(button(app, containing: "Dark Matter").exists)
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        keep("series-fans")

        redRising.tap()
        // Light Bringer's card in the books' row, not the Continue reading card above it.
        let lightBringer = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Light Bringer, #6'")).firstMatch
        XCTAssertTrue(lightBringer.waitForExistence(timeout: 10), "Red Rising's page did not open: \(buttons(app))")
        // The books' row is at Light Bringer, #6, the book you are on: on screen, where it starts at #1 otherwise.
        let window = app.windows.firstMatch.frame
        XCTAssertTrue(waitUntil(5) { lightBringer.frame.minX >= window.minX - 1 && lightBringer.frame.maxX <= window.maxX + 1 },
                      "the row is not at Light Bringer: \(lightBringer.frame) in \(window)")
        keep("series-opened-at-the-book")
    }

    /// Books Home's series and a series page's top fan three of its books
    /// about the one you are on, as the Series view does.
    @MainActor
    func testHomeAndTheSeriesPageFanTheSameWay() {
        let app = launch("home")
        let series = button(app, containing: "Red Rising")
        XCTAssertTrue(series.waitForExistence(timeout: 15), "Books Home did not load: \(buttons(app))")
        XCTAssertTrue(reveal(button(app, containing: "Red Rising, 6 books · on #6"), in: app), "no Red Rising in Your series: \(buttons(app))")
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        keep("home-series-fans")
        button(app, containing: "Red Rising, 6 books · on #6").tap()
        XCTAssertTrue(button(app, containing: "Light Bringer").waitForExistence(timeout: 10), "Red Rising's page did not open")
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        keep("series-page-fan")
    }
}
