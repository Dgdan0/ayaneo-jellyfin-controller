import XCTest

/// The Books side (#25), against the demo hub (`-demo`), which keeps the
/// hub's rules for requests, grabs and transfers. Nothing real is read,
/// requested or written: the demo's books and transfers live in the app.
final class BooksTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(_ section: String, open: String = "", sheet: String = "") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": section, "HUB_SIDE": "books", "HUB_OPEN": open, "HUB_SHEET": sheet]
        app.launch()
        return app
    }

    @MainActor
    private func button(_ app: XCUIApplication, containing words: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }

    @MainActor
    private func text(_ app: XCUIApplication, containing words: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }

    /// Every button's label on one line, for a failure's message.
    @MainActor
    private func buttons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.map { $0.label }.filter { !$0.isEmpty }.joined(separator: " | ")
    }

    /// Whether `condition` comes true within `seconds`, asking four times a second.
    @MainActor
    private func waitUntil(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return condition()
    }

    /// Scrolls the page until `element` is on screen, or gives up.
    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, tries: Int = 6) -> Bool {
        for _ in 0..<tries where !(element.exists && element.isHittable) {
            app.swipeUp()
        }
        return element.exists && element.isHittable
    }

    // MARK: Home

    @MainActor
    func testResumeReadingOpensTheBookAndItsReaderAndCloseComesBackToIt() {
        let app = launch("home")
        let resume = app.buttons["books-resume"]
        XCTAssertTrue(resume.waitForExistence(timeout: 15), "Books Home has no Resume reading: \(buttons(app))")
        XCTAssertTrue(text(app, containing: "Dark Matter").exists)
        resume.tap()
        // The ebook reader opens on the book (phase 4); the middle of the page
        // brings its menu, whose Close comes back.
        let page = app.descendants(matching: .any).matching(identifier: "book-page").firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 20), "Resume reading did not open the reader")
        let close = app.buttons["book-close"]
        XCTAssertTrue(waitUntil(15) {
            if !close.exists { page.tap() }
            return close.waitForExistence(timeout: 2)
        }, "the reader's menu did not come")
        close.tap()
        XCTAssertTrue(app.buttons["book-entry"].waitForExistence(timeout: 10), "closing the reader did not come back to the book")
        XCTAssertTrue(text(app, containing: "Blake Crouch").exists)
    }

    // MARK: Library

    @MainActor
    func testALibraryShowsItsSeriesItsAuthorsAndEveryBook() {
        let app = launch("library")
        let tile = button(app, containing: "Books & Audiobooks")
        XCTAssertTrue(tile.waitForExistence(timeout: 15), "the reading libraries did not load: \(buttons(app))")
        tile.tap()
        // The view chosen stays from one visit to the next, so a run cut short
        // can leave Authors or Books: Series first, whatever was left.
        let views = app.otherElements["library-views"]
        XCTAssertTrue(views.waitForExistence(timeout: 10), "the library has no Series, Authors and Books")
        views.buttons["Series"].tap()
        XCTAssertTrue(button(app, containing: "Red Rising").waitForExistence(timeout: 10), "the series did not load: \(buttons(app))")

        views.buttons["Authors"].tap()
        let pierce = button(app, containing: "Pierce Brown")
        XCTAssertTrue(pierce.waitForExistence(timeout: 10), "the authors did not load: \(buttons(app))")
        pierce.tap()
        XCTAssertTrue(text(app, containing: "AUTHOR").waitForExistence(timeout: 10), "the author's page did not open")
        XCTAssertTrue(button(app, containing: "Light Bringer").waitForExistence(timeout: 10), "the author's books are missing")

        app.buttons["Back to Books & Audiobooks"].tap()
        XCTAssertTrue(views.waitForExistence(timeout: 5))
        views.buttons["Books"].tap()
        XCTAssertTrue(button(app, containing: "Light Bringer").waitForExistence(timeout: 10), "every book on its own did not load")
        // The view chosen stays for the next visit: Series again for the next run.
        views.buttons["Series"].tap()
    }

    // MARK: A book

    @MainActor
    func testWantToReadPutsABookOnHomesRowAndTakesItOff() {
        let app = launch("home", open: "book:rw_demo_recursion")
        let want = app.buttons["book-want"]
        XCTAssertTrue(want.waitForExistence(timeout: 15), "the book page did not open: \(buttons(app))")
        // From whatever an earlier run left: on, then checked on Home.
        if want.label == "Remove from Want to Read" { want.tap() }
        XCTAssertEqual(want.label, "Add to Want to Read")
        want.tap()
        XCTAssertTrue(text(app, containing: "Added to Want to Read").waitForExistence(timeout: 5))
        XCTAssertEqual(want.label, "Remove from Want to Read")

        app.buttons["Home"].firstMatch.tap()
        app.buttons["Home"].firstMatch.tap()
        let row = text(app, containing: "Want to Read")
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        let card = button(app, containing: "Recursion")
        XCTAssertTrue(reveal(card, in: app), "Recursion is not on Want to Read: \(buttons(app))")
        card.tap()
        let again = app.buttons["book-want"]
        XCTAssertTrue(again.waitForExistence(timeout: 10))
        again.tap()
        XCTAssertEqual(again.label, "Add to Want to Read")
    }

    @MainActor
    func testAComicRunOpensItsIssueInTheReaderPlace() {
        let app = launch("home", open: "book:rw_demo_ff")
        let entry = app.buttons["book-entry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 15), "the comic's page did not open: \(buttons(app))")
        XCTAssertTrue(text(app, containing: "On issue 51").exists, "the comic's page does not say which issue is being read")
        entry.tap()
        // Phase 3's comic reader opens at the issue, or, until it is in ReaderHost, the place
        // that names the issue and where its page is kept.
        let page = app.descendants(matching: .any).matching(identifier: "comic-page").firstMatch
        let standIn = app.buttons["reader-close"]
        XCTAssertTrue(waitUntil(10) { page.exists || standIn.exists }, "the issue did not open the reader")
        if standIn.exists {
            XCTAssertTrue(text(app, containing: "Your place is kept on Kavita: Issue 51").exists)
            standIn.tap()
            XCTAssertTrue(entry.waitForExistence(timeout: 10), "closing the reader did not come back to the comic")
        } else {
            XCTAssertTrue(waitUntil(15) { (page.value as? String ?? "").contains("Issue 51") }, "the reader did not open at issue 51")
        }
    }

    // MARK: Requests

    @MainActor
    func testARequestReachesTheReleasesAndAGrabShowsInActivity() {
        let app = launch("discover", open: "book-request")
        let request = app.buttons["book-request"]
        XCTAssertTrue(request.waitForExistence(timeout: 15), "the title to request did not open: \(buttons(app))")
        request.tap()
        // A first request asks what to download; one made in an earlier run goes straight on.
        let choose = button(app, containing: "Choose release")
        let releases = button(app, containing: "Demo Book (2018) EPUB")
        if choose.waitForExistence(timeout: 5) && !releases.exists { choose.tap() }
        XCTAssertTrue(releases.waitForExistence(timeout: 15), "the release picker did not open: \(buttons(app))")
        releases.tap()
        let download = app.buttons["Download this release"]
        XCTAssertTrue(download.waitForExistence(timeout: 5), "the release was not confirmed first")
        download.tap()
        XCTAssertTrue(text(app, containing: "Download queued").waitForExistence(timeout: 10), "the grab was not sent")

        app.buttons["Activity"].firstMatch.tap()
        XCTAssertTrue(text(app, containing: "Requested book").waitForExistence(timeout: 15), "the grab is not in Activity")
    }

    // MARK: Activity

    @MainActor
    func testAFailedTransferRetriesAndOneIsCancelledOnlyAfterAsking() {
        let app = launch("activity")
        let retry = app.buttons["retry-rt_demo_xmen"]
        XCTAssertTrue(retry.waitForExistence(timeout: 15), "the failed transfer has no Retry: \(buttons(app))")
        retry.tap()
        XCTAssertTrue(text(app, containing: "Retrying Uncanny X-Men").waitForExistence(timeout: 10), "the retry was not sent")

        let cancel = app.buttons["cancel-rt_demo_will"]
        XCTAssertTrue(reveal(cancel, in: app), "the moving transfer has no Cancel: \(buttons(app))")
        cancel.tap()
        // It asks first, and the transfer stays until the answer is Cancel transfer. (iOS shows
        // the harmless answer, Keep transfer, as a tap outside the question.)
        let confirm = app.buttons["confirm-cancel"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "cancelling did not ask first: \(buttons(app))")
        XCTAssertTrue(app.staticTexts["Cancel transfer?"].exists)
        XCTAssertTrue(text(app, containing: "The Will of the Many").exists)
        confirm.tap()
        XCTAssertTrue(text(app, containing: "Cancelled The Will of the Many").waitForExistence(timeout: 10), "the cancel was not sent")
    }
}
