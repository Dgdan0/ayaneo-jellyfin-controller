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

    // MARK: Reading lists

    /// Books › Library › Reading lists, then one of Kavita's lists.
    @MainActor
    private func openReadingList(_ environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "library", "HUB_SIDE": "books"].merging(environment) { $1 }
        app.launch()
        let lists = app.buttons["reading-lists"]
        XCTAssertTrue(lists.waitForExistence(timeout: 15), "Books' library has no reading lists: \(buttons(app))")
        for _ in 0..<4 where !lists.isHittable { app.swipeUp() }
        keep(app, "reading-lists-tile")
        lists.tap()
        let first = app.buttons["reading-list-1"]
        XCTAssertTrue(first.waitForExistence(timeout: 10), "Kavita's lists did not come: \(buttons(app))")
        XCTAssertTrue(text(app, containing: "2 Kavita lists · title order").exists)
        XCTAssertTrue(first.label.contains("Marvel's first year") && first.label.contains("4 issues"), first.label)
        first.tap()
        XCTAssertTrue(app.buttons["reading-list-entry-0"].waitForExistence(timeout: 10), "the list's issues did not come")
        keep(app, "reading-list")
        return app
    }

    @MainActor
    private func endHeading(_ app: XCUIApplication) -> XCUIElement {
        app.staticTexts.matching(identifier: "comic-end-heading").firstMatch
    }

    /// Marvel's first year, in Kavita's order across two runs: an issue
    /// opened from it goes on to the list's next issue, whichever run it is
    /// in, until the list ends; what was read shows on the list after.
    @MainActor
    func testAReadingListIsReadInItsOrderAcrossRuns() {
        // HUB_READ_SHEET=end reads each issue to its end as it opens.
        let app = openReadingList(["HUB_READ_SHEET": "end"])
        XCTAssertTrue(app.buttons["reading-list-entry-0"].label.hasPrefix("1. Fantastic Four · Issue #1"))
        let second = app.buttons["reading-list-entry-1"]
        XCTAssertTrue(second.label.hasPrefix("2. Amazing Adult Fantasy · Issue #7"), second.label)
        second.tap()

        let heading = endHeading(app)
        XCTAssertTrue(heading.waitForExistence(timeout: 20), "the issue did not open from the list")
        XCTAssertEqual(heading.label, "End of Amazing Adult Fantasy #7")
        XCTAssertTrue(app.staticTexts["Next: Fantastic Four #2"].exists, "the list's next issue, in another run, is not named")
        app.buttons["Continue"].tap()

        XCTAssertTrue(waitUntilLabel(heading, "End of Fantastic Four #2"), "Continue did not open the list's next issue")
        XCTAssertTrue(app.staticTexts["Next: #3"].exists, "the list's next issue is not named")
        app.buttons["Continue"].tap()

        XCTAssertTrue(waitUntilLabel(heading, "End of Fantastic Four #3"), "Continue did not go on along the list")
        XCTAssertTrue(app.staticTexts["End of the reading list"].exists, "the list's end is not said")
        XCTAssertFalse(app.buttons["Continue"].exists, "Continue past the list's end")
        keep(app, "reading-list-end")
        app.buttons["Leave"].tap()

        // Back on the list, the issues read to their end are read.
        let third = app.buttons["reading-list-entry-2"]
        XCTAssertTrue(third.waitForExistence(timeout: 10))
        XCTAssertTrue(waitUntil(10) { third.label.contains("Read") }, "the list does not show the issue read: \(third.label)")
        XCTAssertTrue(app.buttons["reading-list-entry-1"].label.contains("Read"))
        XCTAssertFalse(app.buttons["reading-list-entry-0"].label.contains("Read"))
    }

    /// The list's first issue, turned back from its first page: the list starts there.
    @MainActor
    func testAReadingListStartsWithItsFirstIssue() {
        let app = openReadingList()
        app.buttons["reading-list-entry-0"].tap()
        let page = app.descendants(matching: .any).matching(identifier: "comic-page").firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 15), "the reader did not open")
        XCTAssertTrue(waitUntil(15) { (page.value as? String ?? "").contains("Page 1 of 36") }, "the issue did not open at its start")
        XCTAssertEqual(page.label, "Fantastic Four")
        XCTAssertTrue(app.buttons["Close reader"].waitForNonExistence(timeout: 8), "the controls stayed over the page")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        XCTAssertTrue(text(app, containing: "Start of reading list").waitForExistence(timeout: 5), "going back said nothing")
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
    private func waitUntilLabel(_ element: XCUIElement, _ label: String) -> Bool {
        waitUntil(20) { element.exists && element.label == label }
    }

    // MARK: Search and Look Up

    /// Recursion in the demo, paged, at its first page, with a sheet open.
    @MainActor
    private func openBook(_ environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_BOOK": "rw_demo_recursion/demo-rw_demo_recursion",
                                 "HUB_BOOK_SCROLL": "0"].merging(environment) { $1 }
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "book-page").firstMatch.waitForExistence(timeout: 20),
                      "the book did not open")
        return app
    }

    /// A word typed and searched for: the passages with it, under their
    /// chapters; one chosen opens its page, with the way back in the menu,
    /// and the search keeps what it found for the next time.
    @MainActor
    func testABookIsSearchedAndAPassageOpens() {
        let app = openBook(["HUB_BOOK_SHEET": "search"])
        let field = app.textFields["book-search-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 15), "the reader has no search")
        field.tap()
        field.typeText("harbour")
        app.buttons["book-search-go"].tap()
        let summary = app.staticTexts.matching(identifier: "book-search-summary").firstMatch
        XCTAssertTrue(summary.waitForExistence(timeout: 30), "the search found nothing to say")
        XCTAssertTrue(summary.label.lowercased().contains("match"), summary.label)
        XCTAssertFalse(summary.label.hasPrefix("No matches"), "harbour is in the book: \(summary.label)")
        let first = app.buttons["book-search-hit-0"]
        XCTAssertTrue(first.exists)
        XCTAssertTrue(first.label.lowercased().contains("harbour"), "the passage does not show the word: \(first.label)")
        keep(app, "book-search-results")
        let chapter = first.label.components(separatedBy: ",").first ?? ""
        first.tap()

        // Its page, the sheet gone, and the way back to where the search began.
        XCTAssertTrue(waitForGone(field, 8), "the passage did not open")
        let back = app.buttons.matching(NSPredicate(format: "identifier IN %@", ["book-returnPlace", "book-return"])).firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 8), "the menu has no way back: \(buttons(app))")
        let position = app.staticTexts["book-position"]
        XCTAssertTrue(waitUntil(8) { position.label.contains(chapter) }, "it did not open \(chapter): \(position.label)")
        keep(app, "book-search-opened")

        // The search again: what it found is still there.
        app.buttons["book-search"].tap()
        XCTAssertTrue(summary.waitForExistence(timeout: 5), "the search forgot what it found")
        XCTAssertTrue(app.buttons["book-search-hit-0"].exists)
    }

    /// A word held on the page offers the system's Look Up, and not the
    /// reader's own Delete key as an action on the words.
    @MainActor
    func testAWordOnThePageCanBeLookedUp() {
        let app = openBook()
        let page = app.descendants(matching: .any).matching(identifier: "book-page").firstMatch
        // Readium lays the page out after it appears.
        _ = waitUntil(2) { false }
        // The opening paragraph, a little way down the page.
        let word = page.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.22))
        var lookUp = app.menuItems["Look Up"]
        for _ in 0..<3 where !lookUp.exists {
            word.press(forDuration: 1.1)
            lookUp = app.menuItems["Look Up"].exists ? app.menuItems["Look Up"] : app.buttons["Look Up"]
            _ = lookUp.waitForExistence(timeout: 3)
        }
        keep(app, "book-look-up-menu")
        XCTAssertTrue(lookUp.exists, "a word held offers no Look Up: \(app.menuItems.allElementsBoundByIndex.map(\.label))")
        XCTAssertFalse(app.menuItems["Delete"].exists || app.buttons["Delete"].exists, "a book's words offer Delete")
        lookUp.tap()
        keep(app, "book-look-up")
    }

    // MARK: On the device

    /// More actions › Remove offline copy on a book's page: what it says, as
    /// the notice or the question.
    @MainActor
    private func askToRemove(_ app: XCUIApplication, title: String) -> String {
        let more = app.buttons["More actions for \(title)"]
        XCTAssertTrue(more.waitForExistence(timeout: 10), "the book's page has no more actions: \(buttons(app))")
        more.tap()
        let remove = app.buttons["Remove offline copy"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5), "More actions has no Remove offline copy")
        remove.tap()
        let alert = app.alerts["Remove offline copy?"]
        if alert.waitForExistence(timeout: 3) {
            return alert.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " ")
        }
        return text(app, containing: "offline copy").label
    }

    /// An audiobook put on the player keeps its tracks on the device, the
    /// next fetched ahead; Remove offline copy says how much and lets them go.
    @MainActor
    func testAnAudiobooksTracksAreKeptAndItsOfflineCopyRemoved() {
        let app = launch(open: "book:rw_demo_alloy")
        let entry = app.buttons["book-entry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 15), "the audiobook's page did not open: \(buttons(app))")
        entry.tap()
        let play = app.buttons["listen-play"]
        XCTAssertTrue(play.waitForExistence(timeout: 10), "Listen did not open the audiobook")
        wait(for: [expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: play)], timeout: 15)
        // Both tracks fetched, the one on the player first: a moment in the demo.
        _ = waitUntil(6) { false }
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Back to'")).firstMatch.tap()

        var said = ""
        XCTAssertTrue(waitUntil(20) {
            said = self.askToRemove(app, title: "The Alloy of Law")
            if said.contains("No offline copy") { _ = self.waitUntil(2) { false } }
            return said.contains("on this device.")
        }, "the tracks were not kept: \(said)")
        XCTAssertTrue(said.contains("The Alloy of Law · 2.") && said.contains("MB"), "the question says \(said)")
        keep(app, "remove-offline-copy")
        app.alerts.buttons["Remove from this device"].tap()
        XCTAssertTrue(text(app, containing: "Offline copy removed").waitForExistence(timeout: 5), "nothing said it was removed")
        // Gone: nothing left to remove.
        XCTAssertTrue(askToRemove(app, title: "The Alloy of Law").contains("No offline copy is kept"))
    }

    /// Fantastic Four #51 read, then the reading servers gone: the issue
    /// opens again from its page list and the pages kept on the device, and
    /// says so.
    @MainActor
    func testAComicReopensInAnOutageFromWhatTheDeviceKept() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_OPEN": "book:rw_demo_ff",
                                 "HUB_READ": "rw_demo_ff/rw_demo_ff-51", "HUB_READ_CHROME": "pinned",
                                 "HUB_DEMO_OUTAGE": "after-close"]
        app.launch()
        let page = app.descendants(matching: .any).matching(identifier: "comic-page").firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 20), "the reader did not open")
        XCTAssertTrue(waitUntil(15) { (page.value as? String ?? "").contains("Page 2 of 24") }, "issue 51 did not open at its place")
        _ = waitUntil(2) { false }
        app.buttons["Close reader"].tap()
        XCTAssertTrue(waitForGone(page, 5), "the reader stayed open")

        // The reading servers are gone now; the run's page is still there.
        let entry = app.buttons["book-entry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 10), "Fantastic Four's page is not under the reader: \(buttons(app))")
        entry.tap()
        XCTAssertTrue(page.waitForExistence(timeout: 20), "the reader did not open again")
        XCTAssertTrue(waitUntil(30) { (page.value as? String ?? "").contains("Page 2 of 24") },
                      "the issue did not reopen from what was kept: \(page.value as? String ?? "")")
        XCTAssertTrue(text(app, containing: "Using cached pages").waitForExistence(timeout: 5), "the outage was not said")
        XCTAssertFalse(text(app, containing: "could not be loaded").exists, "the kept page did not show")
        keep(app, "comic-outage")
    }
}
