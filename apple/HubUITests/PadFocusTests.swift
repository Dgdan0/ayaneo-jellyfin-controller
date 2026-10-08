import XCTest

/// A keyboard driving the app as a controller does (#46): the arrows move a
/// ring from item to item, a row by position and the page row by row, Return
/// presses the item, and the ring comes back to where it was. Against the
/// demo hub (`-demo`); a debug build's probe `pad-focus` says where the focus
/// is ("ring books-home hero-resume").
///
/// Return is typed as "\n": XCUITest's `.return` never reaches the app's key
/// commands in the simulator, "\n" is the Return key. Escape (Ⓑ) reaches
/// nothing in the simulator however it is typed, so Back is the back button here.
final class PadFocusTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(side: String, section: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": section, "HUB_SIDE": side]
        app.launch()
        XCTAssertTrue(app.staticTexts["pad-focus"].firstMatch.waitForExistence(timeout: 20), "no focus probe")
        return app
    }

    /// Where the focus is, as the probe says.
    @MainActor
    private func focus(_ app: XCUIApplication) -> String {
        app.staticTexts["pad-focus"].firstMatch.label
    }

    /// Presses `key` and waits a moment for the focus to settle.
    @MainActor
    private func press(_ app: XCUIApplication, _ key: XCUIKeyboardKey, times: Int = 1) {
        for _ in 0..<times {
            app.typeKey(key, modifierFlags: [])
            RunLoop.current.run(until: Date().addingTimeInterval(0.35))
        }
    }

    @MainActor
    private func pressReturn(_ app: XCUIApplication) {
        app.typeKey("\n", modifierFlags: [])
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
    }

    /// Waits until the focus satisfies `condition`.
    @MainActor
    private func waitFor(_ app: XCUIApplication, _ seconds: TimeInterval = 8, _ condition: (String) -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if condition(focus(app)) { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        return condition(focus(app))
    }

    /// Books home: the first press shows the ring on the hero's cover, then down
    /// to Resume, across to Details, down to the books also being read, past an
    /// empty Want to Read to the shelves; Return opens a book, and coming back
    /// the ring is where it was.
    @MainActor
    func testBooksHomeGoesRowByRowAndReturnOpensABook() {
        let app = launch(side: "books", section: "home")
        XCTAssertTrue(app.buttons["books-resume"].waitForExistence(timeout: 20), "Books home did not load")
        XCTAssertEqual(focus(app), "none", "focus showed before a key was pressed")
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring books-home hero-cover", "the first press did not show the page's first item")
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring books-home hero-resume")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring books-home hero-details")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring books-home hero-details", "right went past the end of the hero's pair")
        press(app, .downArrow)
        XCTAssertTrue(focus(app).hasPrefix("ring books-home also/"), "down did not reach Also reading: \(focus(app))")
        press(app, .downArrow, times: 2)
        XCTAssertTrue(focus(app).hasPrefix("ring books-home series/"), "down did not reach Your series: \(focus(app))")
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring books-home shelf-comics/0", "down did not reach the first shelf's first book")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring books-home shelf-comics/1", "right did not go along the shelf by position")
        // Past the empty Want to Read, to the next shelf with books.
        press(app, .downArrow)
        XCTAssertTrue(focus(app).hasPrefix("ring books-home shelf-recently-added/"), "an empty shelf stopped the ring: \(focus(app))")
        press(app, .upArrow)
        XCTAssertEqual(focus(app), "ring books-home shelf-comics/1", "up did not come back to the book left")

        // Return opens it; its page takes the ring.
        pressReturn(app)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring book:") }, "Return did not open the book: \(focus(app))")
        // Back (the back button: Escape reaches nothing in the simulator), then a
        // press: the ring is on the book it opened.
        let back = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Back to'")).firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5))
        back.tap()
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("hidden ") || $0 == "none" }, "the tap left the ring: \(focus(app))")
        press(app, .rightArrow)
        XCTAssertTrue(waitFor(app) { $0 == "ring books-home shelf-comics/1" }, "Back did not return to the book: \(focus(app))")
    }

    /// Up from the page's top reaches the bar; down from the bar, and up from
    /// the iPhone's tab bar, return to the page where it was.
    @MainActor
    func testTheBarsAreReachedAndLeftWithTheArrows() {
        let app = launch(side: "books", section: "home")
        XCTAssertTrue(app.buttons["books-resume"].waitForExistence(timeout: 20))
        press(app, .downArrow, times: 2)
        XCTAssertEqual(focus(app), "ring books-home hero-resume")
        press(app, .upArrow, times: 2)
        XCTAssertTrue(focus(app).hasPrefix("ring bar bar-"), "up did not reach the top bar: \(focus(app))")
        press(app, .rightArrow)
        XCTAssertTrue(focus(app).hasPrefix("ring bar bar-"), "right left the bar: \(focus(app))")
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring books-home hero-cover", "down from the bar did not return to the page")
        // To the foot of the page, then the tab bar, and back.
        press(app, .downArrow, times: 12)
        XCTAssertTrue(focus(app).hasPrefix("ring bar tab-"), "down past the page did not reach the tab bar: \(focus(app))")
        press(app, .upArrow)
        XCTAssertEqual(focus(app), "ring books-home new-list", "up from the tab bar did not return to the page")
        // Return on a tab chooses its section.
        press(app, .downArrow)
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring bar tab-discover")
        pressReturn(app)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == 'Discover' AND selected == true")).firstMatch
                        .waitForExistence(timeout: 5), "Return did not choose Discover")
    }

    /// The Books library: the libraries as a grid, one opened with Return, its
    /// views and order controls, then its covers.
    @MainActor
    func testTheBooksLibraryIsDrivenByTheKeys() {
        let app = launch(side: "books", section: "library")
        XCTAssertTrue(app.buttons["reading-lists"].waitForExistence(timeout: 20), "the libraries did not load")
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring books-libraries arrange")
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring books-libraries libraries/storyteller:books")
        pressReturn(app)
        XCTAssertTrue(waitFor(app) { $0 == "ring library:storyteller:books views/series" },
                      "the library did not open on its views: \(focus(app))")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring library:storyteller:books views/authors")
        pressReturn(app)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label MATCHES '[0-9]+ authors?'")).firstMatch
                        .waitForExistence(timeout: 10), "Return did not show the authors")
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring library:storyteller:books sort-direction", "down did not reach the order")
        press(app, .downArrow)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring library:storyteller:books grid/") },
                      "down did not reach the authors: \(focus(app))")
        press(app, .upArrow, times: 2)
        XCTAssertTrue(focus(app).hasPrefix("ring library:storyteller:books views/"), "up did not come back to the views: \(focus(app))")
    }

    /// Downloads: its three pills in a row, each chosen with Return.
    @MainActor
    func testDownloadsPillsAreChosenWithReturn() {
        let app = launch(side: "books", section: "downloads")
        XCTAssertTrue(app.buttons["downloads-books"].waitForExistence(timeout: 20))
        // This side's pills: the other side's Downloads, kept out of sight, has its own.
        func pill(_ id: String) -> XCUIElement {
            app.buttons.matching(NSPredicate(format: "identifier == %@ AND enabled == true", id)).firstMatch
        }
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring downloads-books tabs/books")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring downloads-books tabs/device")
        pressReturn(app)
        XCTAssertTrue(pill("downloads-device").wait(for: \.isSelected, toEqual: true, timeout: 5),
                      "Return did not choose Films and TV")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring downloads-books tabs/queue")
        pressReturn(app)
        XCTAssertTrue(pill("downloads-queue").wait(for: \.isSelected, toEqual: true, timeout: 5),
                      "Return did not choose the queue: \(focus(app))")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring downloads-books tabs/queue", "right went past the last pill")
        press(app, .leftArrow, times: 2)
        app.typeKey(" ", modifierFlags: [])
        XCTAssertTrue(pill("downloads-books").wait(for: \.isSelected, toEqual: true, timeout: 5), "Space did not choose Books")
    }

    /// A book's page: down to ⋯, whose Return opens its choices as a dialog;
    /// Finished there opens the Finished panel, which takes the ring.
    @MainActor
    func testABooksMoreOpensItsChoicesAndTheFinishedPanel() {
        let app = launch(side: "books", section: "home")
        XCTAssertTrue(app.buttons["books-resume"].waitForExistence(timeout: 20))
        press(app, .downArrow, times: 2)
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring books-home hero-details")
        pressReturn(app)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring book:") }, "Details did not open the book")
        var steps = 0
        while !focus(app).hasSuffix(" more"), steps < 14 {
            if focus(app).hasSuffix(" entry") { press(app, .rightArrow) } else { press(app, .downArrow) }
            steps += 1
        }
        XCTAssertTrue(focus(app).hasSuffix(" more"), "the keys did not reach ⋯: \(focus(app))")
        pressReturn(app)
        let finished = app.buttons["Finished"].firstMatch
        XCTAssertTrue(finished.waitForExistence(timeout: 5), "⋯ did not open its choices")
        finished.tap()
        XCTAssertTrue(app.staticTexts["When did you finish?"].waitForExistence(timeout: 5), "Finished did not open its panel")
        press(app, .downArrow)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring finished ") }, "the panel did not take the ring: \(focus(app))")
        // Under the month and the year, its answers; Cancel is on the left.
        press(app, .downArrow)
        XCTAssertTrue(["ring finished finish", "ring finished cancel"].contains(focus(app)), "down did not reach the answers: \(focus(app))")
        press(app, .leftArrow)
        XCTAssertEqual(focus(app), "ring finished cancel")
        pressReturn(app)
        XCTAssertTrue(app.staticTexts["When did you finish?"].waitForNonExistence(timeout: 5), "Return on Cancel did not close the panel")
        XCTAssertFalse(app.staticTexts["book-you-line"].exists, "Cancel marked the book finished")
    }

    /// Notifications and Activity: down their rows by looking, Return opening a notice.
    @MainActor
    func testNotificationsAndActivityRowsAreWalked() {
        let app = launch(side: "media", section: "notifications")
        XCTAssertTrue(app.descendants(matching: .any)["column-sonarr"].firstMatch.waitForExistence(timeout: 20))
        press(app, .downArrow, times: 2)
        let notice = focus(app)
        XCTAssertTrue(notice.hasPrefix("ring notifications-media notice-"), "down did not reach a notice: \(notice)")
        let id = String(notice.dropFirst("ring notifications-media ".count))
        pressReturn(app)
        let row = app.descendants(matching: .any)[id].firstMatch
        XCTAssertTrue(waitFor(app) { _ in (row.value as? String)?.contains("Expanded") == true }, "Return did not open the notice")
        press(app, .downArrow)
        XCTAssertTrue(focus(app).hasPrefix("ring notifications-media notice-") && focus(app) != notice,
                      "down did not reach the next notice: \(focus(app))")
        app.terminate()

        let activity = launch(side: "media", section: "activity")
        XCTAssertTrue(activity.descendants(matching: .any)["all-transfers"].firstMatch.waitForExistence(timeout: 20))
        press(activity, .downArrow)
        XCTAssertTrue(focus(activity).hasPrefix("ring activity transfer-"), "the first press did not show a transfer: \(focus(activity))")
        press(activity, .downArrow, times: 3)
        XCTAssertTrue(focus(activity).hasPrefix("ring activity speed/"), "down did not reach the speed choice: \(focus(activity))")
        press(activity, .rightArrow)
        XCTAssertTrue(focus(activity).hasPrefix("ring activity speed/"), "right left the speed choice: \(focus(activity))")
    }
}
