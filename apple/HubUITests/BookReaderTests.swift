import XCTest

/// The ebook reader against the demo hub (#25, phase 4): `HUB_BOOK` opens it
/// at launch on one of the Books demo's books, made-up words in a real EPUB
/// that the demo hub serves (`DemoEpub`): a page about the edition, eight
/// chapters, a footnote in One and a link on to Five in Two. Reading writes
/// the place to the hub, so these run with `-demo` only, as every UI test does.
final class BookReaderTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// Recursion, not started: it opens at its first page.
    private static let recursion = "rw_demo_recursion/demo-rw_demo_recursion"

    @MainActor
    private func launchReading(_ book: String = recursion, _ environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_BOOK": book]
            .merging(environment) { $1 }
        app.launch()
        XCTAssertTrue(page(app).waitForExistence(timeout: 20), "the book did not open")
        return app
    }

    @MainActor
    private func page(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "book-page").firstMatch
    }

    /// Light Bringer is half read in the Books demo: it opens there, and the
    /// menu says where that is and how long is left.
    @MainActor
    func testItOpensAtItsPlaceAndTheMenuSaysHowLongIsLeft() {
        let app = launchReading("rw_demo_rr6/rr6", ["HUB_BOOK_CHROME": "pinned"])
        let position = app.staticTexts["book-position"]
        XCTAssertTrue(position.waitForExistence(timeout: 15), "the menu did not come")
        XCTAssertTrue(waitUntil(10) { position.label.contains("% of book") }, "the menu says \(position.label)")
        let percent = Int(position.label.components(separatedBy: "% of book").first?
            .components(separatedBy: " ").last ?? "") ?? -1
        XCTAssertTrue((40...60).contains(percent), "it opened at \(percent)% rather than about half way")
        let heading = app.descendants(matching: .any).matching(identifier: "book-heading").firstMatch
        XCTAssertTrue(heading.label.contains("Light Bringer"), "the menu's title reads \(heading.label)")
        XCTAssertTrue(heading.label.contains("left in chapter"), "the menu does not say how long is left: \(heading.label)")
    }

    /// The contents go to a chapter, and the menu then offers the way back.
    @MainActor
    func testTheContentsGoToAChapterAndReturnComesBack() {
        let app = launchReading(Self.recursion, ["HUB_BOOK_CHROME": "pinned", "HUB_BOOK_SHEET": "contents"])
        let five = app.buttons["Five"]
        XCTAssertTrue(five.waitForExistence(timeout: 15), "the contents did not open")
        five.tap()
        let position = app.staticTexts["book-position"]
        XCTAssertTrue(waitUntil(10) { position.label.hasPrefix("Five") }, "the contents did not go to Five: \(position.label)")
        let back = app.buttons["Return to previous place"]
        XCTAssertTrue(back.waitForExistence(timeout: 5), "no way back after a jump")
        back.tap()
        XCTAssertTrue(waitUntil(10) { position.label.hasPrefix("About this edition") }, "it did not come back: \(position.label)")
        XCTAssertFalse(back.exists, "the way back stayed after it was taken")
    }

    /// A note's number opens the note as a card over the page; Close puts
    /// the card away and the page is where it was.
    @MainActor
    func testANoteOpensOverThePageAndThePageStays() {
        let app = launchReading(Self.recursion, ["HUB_BOOK_SHEET": "contents"])
        let one = app.buttons["One"]
        XCTAssertTrue(one.waitForExistence(timeout: 15), "the contents did not open")
        one.tap()
        let reference = app.links["1"]
        XCTAssertTrue(reference.waitForExistence(timeout: 10), "chapter One's note is not on its first page")
        reference.tap()
        let note = app.staticTexts["book-footnote"]
        XCTAssertTrue(note.waitForExistence(timeout: 5), "the note did not open as a card")
        XCTAssertTrue(note.label.contains("harbour office"), "the card reads \(note.label)")
        app.buttons["Close"].firstMatch.tap()
        XCTAssertTrue(note.waitForNonExistence(timeout: 5), "the card stayed")
        XCTAssertTrue(reference.exists, "the page moved away from the note's number")
    }

    /// Appearance shows each choice as it is made, and keeps it.
    @MainActor
    func testAppearanceChangesThePageAtOnce() {
        let app = launchReading(Self.recursion, ["HUB_BOOK_SHEET": "appearance"])
        let themes = app.buttons["Themes"]
        XCTAssertTrue(themes.waitForExistence(timeout: 15), "Appearance did not open")
        themes.tap()
        let night = app.buttons["Night"]
        XCTAssertTrue(night.waitForExistence(timeout: 5))
        night.tap()
        XCTAssertTrue(waitUntil(5) { night.isSelected }, "Night was not chosen")
        // As every other test reads it.
        app.buttons["Sepia"].tap()
        XCTAssertTrue(waitUntil(5) { app.buttons["Sepia"].isSelected })
    }

    /// Keys lists what each key does in a book; Ⓑ on the open menu leaves it.
    @MainActor
    func testKeysListTheBooksKeysAndCloseLeaves() {
        let app = launchReading(Self.recursion, ["HUB_BOOK_CHROME": "pinned", "HUB_BOOK_SHEET": "keys"])
        XCTAssertTrue(app.staticTexts["Leave the book"].waitForExistence(timeout: 15), "Keys does not say what B does")
        XCTAssertTrue(app.staticTexts["Next chapter"].exists, "Keys does not name R2")
        app.buttons["Close"].firstMatch.tap()
        let close = app.buttons["book-close"]
        XCTAssertTrue(close.waitForExistence(timeout: 5), "the menu is not there under the sheet")
        close.tap()
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
}
