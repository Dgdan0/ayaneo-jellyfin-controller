import XCTest

/// The book page (#39, the owner's layout "1") against the demo hub, which
/// gives The Alloy of Law a pretend Goodreads export and Light Bringer only
/// readers' ratings; what is set here lasts for the launch, as the demo's
/// places do. Nothing reaches a real hub.
final class BookPageTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(open: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_OPEN": open]
        app.launch()
        XCTAssertTrue(app.buttons["book-entry"].waitForExistence(timeout: 15), "the book's page did not open: \(buttons(app))")
        return app
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    @MainActor
    private func text(_ app: XCUIApplication, containing words: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }

    @MainActor
    private func buttons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.joined(separator: " | ")
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

    @MainActor
    private func keep(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// ⋯, then one of its items.
    @MainActor
    private func more(_ app: XCUIApplication, _ title: String, _ item: String) {
        app.buttons["More actions for \(title)"].tap()
        let choice = app.buttons[item]
        XCTAssertTrue(choice.waitForExistence(timeout: 5), "⋯ has no \(item): \(buttons(app))")
        choice.tap()
    }

    /// ⋯ › Reading status › `status` (#63): the one row, its four choices under it.
    @MainActor
    private func status(_ app: XCUIApplication, _ title: String, _ status: String) {
        app.buttons["More actions for \(title)"].tap()
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Reading status'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5), "⋯ has no Reading status: \(buttons(app))")
        row.tap()
        let choice = app.buttons[status]
        XCTAssertTrue(choice.waitForExistence(timeout: 5), "Reading status has no \(status): \(buttons(app))")
        choice.tap()
    }

    /// "Oct 2026": this month as the page says it.
    private var thisMonth: String {
        let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        let parts = Calendar.current.dateComponents([.year, .month], from: Date())
        return "\(months[(parts.month ?? 1) - 1]) \(parts.year ?? 0)"
    }

    /// The Alloy of Law from the pretend export: what readers make of it, its
    /// genres, and under its cover your four stars, two reads and shelves; its
    /// one format gold, the others grey, and the missing ebook to find.
    @MainActor
    func testABookSaysWhatIsAboutYouUnderItsCoverAndWhatYouCanDo() {
        let app = launch(open: "book:rw_demo_alloy")
        XCTAssertTrue(element(app, "book-facts").label.contains("4.2 from readers"), element(app, "book-facts").label)
        XCTAssertTrue(element(app, "book-genres").label.contains("Fantasy · Steampunk · Magic systems"))
        XCTAssertEqual(element(app, "book-stars").label, "Your rating, 4 of 5")
        XCTAssertEqual(element(app, "book-you-line").label, "Finished Sep 2025 · 2nd time")
        XCTAssertTrue(element(app, "book-shelves").label.contains("cosmere · favorites"))
        XCTAssertEqual(app.buttons["book-format-audiobook"].label, "Audiobook, available")
        XCTAssertEqual(app.buttons["book-format-ebook"].label, "Ebook, not available")
        // Ebook, Audiobook, Read along, in that order (#49).
        let order = ["ebook", "audiobook", "readaloud"].map { app.buttons["book-format-\($0)"].frame.minX }
        XCTAssertEqual(order, order.sorted(), "the formats are not Ebook, Audiobook, Read along: \(order)")
        keep(app, "book-page-alloy")
        app.buttons["book-format-ebook"].tap()
        XCTAssertTrue(app.buttons["find-this-book"].waitForExistence(timeout: 10), "a missing ebook offers nothing to find")
        XCTAssertTrue(text(app, containing: "without an ebook").exists)
    }

    /// A star rates the book on the hub and shows at once; the same star
    /// again takes the rating away.
    @MainActor
    func testAStarRatesAndTheSameStarTakesItAway() {
        let app = launch(open: "book:rw_demo_rr6")
        let stars = element(app, "book-stars")
        XCTAssertEqual(stars.label, "Not rated")
        XCTAssertTrue(element(app, "book-facts").label.contains("4.5 from readers"), element(app, "book-facts").label)
        app.buttons["book-star-4"].tap()
        XCTAssertTrue(waitUntil(5) { stars.label == "Your rating, 4 of 5" }, "the star did not rate: \(stars.label)")
        app.buttons["book-star-2"].tap()
        XCTAssertTrue(waitUntil(5) { stars.label == "Your rating, 2 of 5" }, "another star did not change it: \(stars.label)")
        app.buttons["book-star-2"].tap()
        XCTAssertTrue(waitUntil(5) { stars.label == "Not rated" }, "the same star did not take it away: \(stars.label)")
    }

    /// ⋯ › Reading status › Finished asks when, this month to begin with;
    /// marked, the cover says so and asks for a rating, and the book is read;
    /// Reading straight after gives its place back (#63: a finish of this
    /// visit is taken back whole). Another month can be chosen.
    @MainActor
    func testFinishedAsksWhenAndUndoGivesThePlaceBack() {
        let app = launch(open: "book:rw_demo_rr6")
        let entry = app.buttons["book-entry"]
        XCTAssertTrue(waitUntil(10) { entry.label.contains("49%") }, "Light Bringer is not half read: \(entry.label)")
        XCTAssertTrue(entry.label.hasPrefix("Resume"), entry.label)

        status(app, "Light Bringer", "Finished")
        let confirm = app.buttons["finish-confirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "When did you finish? did not open")
        XCTAssertTrue(app.navigationBars["When did you finish?"].exists || text(app, containing: "When did you finish?").exists)
        keep(app, "finished-panel")
        confirm.tap()
        let line = element(app, "book-you-line")
        XCTAssertTrue(waitUntil(5) { line.exists && line.label == "Finished \(self.thisMonth) · rate it?" },
                      "the cover does not say it was finished: \(line.label)")
        XCTAssertTrue(text(app, containing: "Marked finished · \(thisMonth)").exists)
        XCTAssertFalse(entry.label.contains("49%"), "a finished book still resumes: \(entry.label)")
        keep(app, "finished")

        status(app, "Light Bringer", "Reading")
        XCTAssertTrue(text(app, containing: "Reading status · Reading").waitForExistence(timeout: 5))
        XCTAssertTrue(waitUntil(5) { entry.label.contains("49%") }, "Reading did not give back the place: \(entry.label)")
        XCTAssertTrue(waitUntil(5) { !line.exists }, "the finish stayed after Reading")

        // Another month: January of last year.
        status(app, "Light Bringer", "Finished")
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        let year = Calendar.current.component(.year, from: Date()) - 1
        element(app, "finish-year").tap()
        let lastYear = app.buttons["\(year)"]
        XCTAssertTrue(lastYear.waitForExistence(timeout: 5), "the year has no \(year): \(buttons(app))")
        lastYear.tap()
        element(app, "finish-month").tap()
        let january = app.buttons["January"]
        XCTAssertTrue(january.waitForExistence(timeout: 5), "the month has no January: \(buttons(app))")
        january.tap()
        confirm.tap()
        XCTAssertTrue(waitUntil(5) { line.exists && line.label == "Finished Jan \(year) · rate it?" },
                      "another month was not kept: \(line.label)")
        status(app, "Light Bringer", "Reading")
        XCTAssertTrue(waitUntil(5) { entry.label.contains("49%") })
    }

    /// The reading status (#63): Not reading takes a book off Continue
    /// reading and keeps its place; opening it again makes it Reading; the row
    /// says the status it holds, and the ✓ goes on a finished cover.
    @MainActor
    func testNotReadingTakesABookOffContinueReadingAndKeepsItsPlace() {
        let app = launch(open: "book:rw_demo_rr6")
        let entry = app.buttons["book-entry"]
        XCTAssertTrue(waitUntil(10) { entry.label.contains("49%") }, "Light Bringer is not half read: \(entry.label)")
        app.buttons["More actions for Light Bringer"].tap()
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Reading status'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5), "⋯ has no Reading status: \(buttons(app))")
        XCTAssertTrue(row.label.hasPrefix("Reading status · Reading"), "a book half read is not Reading: \(row.label)")
        row.tap()
        for choice in ["Want to read", "Reading", "Finished", "Not reading"] {
            XCTAssertTrue(app.buttons[choice].waitForExistence(timeout: 5), "Reading status has no \(choice): \(buttons(app))")
        }
        app.buttons["Not reading"].tap()
        XCTAssertTrue(text(app, containing: "Reading status · Not reading").waitForExistence(timeout: 5), "Not reading said nothing")
        XCTAssertTrue(entry.label.contains("49%"), "Not reading lost the place: \(entry.label)")
        keep(app, "not-reading")
        // Off Continue reading: Books Home's row no longer has it.
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Back to'")).firstMatch.tap()
        let card = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Light Bringer, Red Rising #6'")).firstMatch
        XCTAssertTrue(app.buttons["books-resume"].waitForExistence(timeout: 10), "Books Home did not come back: \(buttons(app))")
        XCTAssertFalse(waitUntil(4) { card.exists }, "a book put down is still in Continue reading")
    }

    /// Finished by status puts the ✓ on its cover wherever it is listed, and
    /// says the month under it (#63).
    @MainActor
    func testFinishedPutsTheTickOnTheCoverAndChangingTheDateKeepsIt() {
        let app = launch(open: "book:rw_demo_rr6")
        let entry = app.buttons["book-entry"]
        XCTAssertTrue(waitUntil(10) { entry.label.contains("49%") }, "Light Bringer is not half read: \(entry.label)")
        status(app, "Light Bringer", "Finished")
        let confirm = app.buttons["finish-confirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "When did you finish? did not open")
        confirm.tap()
        let line = element(app, "book-you-line")
        XCTAssertTrue(waitUntil(5) { line.exists && line.label.hasPrefix("Finished \(self.thisMonth)") }, "not finished: \(line.label)")
        // The row now holds Finished, with the month to put right.
        app.buttons["More actions for Light Bringer"].tap()
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Reading status'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        XCTAssertTrue(row.label.hasPrefix("Reading status · Finished"), row.label)
        row.tap()
        app.buttons["Finished"].tap()
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "Finished again did not ask the month, to put the date right")
        confirm.tap()
        XCTAssertTrue(waitUntil(5) { line.exists && line.label.hasPrefix("Finished") }, "the finish went: \(line.label)")
        // Its series lists it finished, the tick on its cover.
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Back to'")).firstMatch.tap()
        let series = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Red Rising, 6 books'")).firstMatch
        XCTAssertTrue(series.waitForExistence(timeout: 10), "Red Rising is not on Books Home: \(buttons(app))")
        series.tap()
        let book = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Light Bringer'")).firstMatch
        XCTAssertTrue(book.waitForExistence(timeout: 10), "Light Bringer is not in its series: \(buttons(app))")
        XCTAssertTrue(book.label.contains("Completed") || book.label.hasPrefix("Finished"), "not finished in its series: \(book.label)")
    }
}
