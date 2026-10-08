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
    private func launchReading(_ book: String = recursion, _ environment: [String: String] = [:],
                               arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"] + arguments
        // Pages, not continuous scrolling, whatever an earlier test left chosen.
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_BOOK": book, "HUB_BOOK_SCROLL": "0"]
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
        // Once the contents have slid away and the page has settled on One: a
        // tap while the sheet still slides closes it instead (the whole suite,
        // run on a loaded Mac, 2026-10-07).
        XCTAssertTrue(one.waitForNonExistence(timeout: 5), "the contents stayed open")
        let reference = app.links["1"]
        XCTAssertTrue(reference.waitForExistence(timeout: 10), "chapter One's note is not on its first page")
        XCTAssertTrue(waitUntil(5) { reference.isHittable }, "the note's number cannot be tapped")
        RunLoop.current.run(until: Date().addingTimeInterval(0.6))
        reference.tap()
        let note = app.staticTexts["book-footnote"]
        // On a loaded Mac the iPad Pro's two columns can still be settling, and
        // the tap is lost (the class's suite, 2026-10-07 and 08; alone it passed):
        // once more, then it must open.
        if !note.waitForExistence(timeout: 5), reference.isHittable { reference.tap() }
        XCTAssertTrue(note.waitForExistence(timeout: 5), "the note did not open as a card: frame \(reference.frame) in \(app.windows.firstMatch.frame); "
                      + app.staticTexts.allElementsBoundByIndex.prefix(12).map(\.label).joined(separator: " | "))
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
        // Night is Dim now, and a true-black Dark beside it (#47).
        let dim = app.buttons["Dim"]
        XCTAssertTrue(dim.waitForExistence(timeout: 5))
        dim.tap()
        XCTAssertTrue(waitUntil(5) { dim.isSelected }, "Dim was not chosen")
        let dark = app.buttons["Dark"]
        dark.tap()
        XCTAssertTrue(waitUntil(5) { dark.isSelected && !dim.isSelected }, "Dark was not chosen")
        // As every other test reads it.
        app.buttons["Sepia"].tap()
        XCTAssertTrue(waitUntil(5) { app.buttons["Sepia"].isSelected })
    }

    /// Appearance walked with the keys, as a controller walks it (#25): right
    /// on the tabs goes to Layout, down to the columns, right and Return
    /// choose Two pages; down to Automatic columns and Return put it back.
    @MainActor
    func testAppearanceIsWalkedWithTheKeys() {
        let app = launchReading(Self.recursion, ["HUB_BOOK_SHEET": "appearance"])
        let layout = app.buttons["Layout"]
        XCTAssertTrue(layout.waitForExistence(timeout: 15), "Appearance did not open")
        app.typeKey(.rightArrow, modifierFlags: [])
        XCTAssertTrue(waitUntil(5) { layout.isSelected }, "right on the tabs did not go to Layout")
        app.typeKey(.downArrow, modifierFlags: [])
        app.typeKey(.rightArrow, modifierFlags: [])
        app.typeKey(" ", modifierFlags: [])
        let two = app.buttons["Two pages"]
        XCTAssertTrue(waitUntil(5) { two.isSelected }, "Return did not choose Two pages")
        // Back to automatic columns, as the other tests read the book.
        app.typeKey(.leftArrow, modifierFlags: [])
        for _ in 0..<3 { app.typeKey(.downArrow, modifierFlags: []) }
        app.typeKey(" ", modifierFlags: [])
        let automatic = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Automatic columns")).firstMatch
        XCTAssertTrue(waitUntil(5) { automatic.isSelected },
                      "Return did not choose Automatic columns")
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

    /// The keyboard turns pages while the screen holds the keys, and still
    /// once a tap on the page has given them to Readium's web view: both
    /// hear a key, and the reader acts on it once.
    @MainActor
    func testKeysTurnThePageWhetherTheScreenOrThePageHoldsThem() {
        let app = launchReading()
        // Recursion opens at its first page; three pages on, then the menu.
        turnPages(app, 3)
        let afterKeys = place(app)
        XCTAssertFalse(afterKeys.hasPrefix("About this edition · Page 1 of"), "the keys did not turn the page: \(afterKeys)")
        // A tap in the page's middle puts the menu away, and the page now has the keys.
        page(app).tap()
        XCTAssertTrue(app.staticTexts["book-position"].waitForNonExistence(timeout: 5), "the tap left the menu up")
        turnPages(app, 3)
        let afterTap = place(app)
        XCTAssertNotEqual(afterTap, afterKeys, "the keys did nothing once the page had been tapped")
        XCTAssertGreaterThanOrEqual(percent(afterTap), percent(afterKeys), "the keys went back: \(afterKeys) to \(afterTap)")
    }

    /// Continuous scrolling, chosen in Appearance: the page scrolls, and the
    /// menu says how far through without page numbers.
    @MainActor
    func testContinuousScrollingScrollsAndCountsNoPages() {
        let app = launchReading(Self.recursion, ["HUB_BOOK_SHEET": "appearance"])
        let layout = app.buttons["Layout"]
        XCTAssertTrue(layout.waitForExistence(timeout: 15), "Appearance did not open")
        layout.tap()
        let scrolling = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Continuous scrolling'")).firstMatch
        XCTAssertTrue(scrolling.waitForExistence(timeout: 5), "Layout has no continuous scrolling: "
                      + app.buttons.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.joined(separator: " | "))
        scrolling.tap()
        XCTAssertTrue(waitUntil(5) { scrolling.label.hasSuffix("On") }, "scrolling did not turn on: \(scrolling.label)")
        app.buttons["Close"].firstMatch.tap()
        XCTAssertTrue(layout.waitForNonExistence(timeout: 5), "Appearance stayed open")
        // The menu, under the sheet, says where the book is; while it scrolls, ↑ and ↓ scroll.
        let before = shownPlace(app)
        XCTAssertFalse(before.contains("Page "), "scrolling still counts pages: \(before)")
        page(app).tap()
        XCTAssertTrue(app.staticTexts["book-position"].waitForNonExistence(timeout: 5), "the tap left the menu up")
        for _ in 0..<4 { page(app).swipeUp() }
        page(app).tap()
        let after = shownPlace(app)
        XCTAssertGreaterThan(percent(after), percent(before), "the page did not scroll on: \(before) to \(after)")
    }

    /// Where the book is, from the menu on screen.
    @MainActor
    private func shownPlace(_ app: XCUIApplication) -> String {
        let position = app.staticTexts["book-position"]
        XCTAssertTrue(position.waitForExistence(timeout: 5), "the menu is not up")
        XCTAssertTrue(waitUntil(5) { position.label.contains("% of book") }, "the menu says \(position.label)")
        return position.label
    }

    /// Right arrow `count` times, a moment apart, as a person reads.
    @MainActor
    private func turnPages(_ app: XCUIApplication, _ count: Int) {
        for _ in 0..<count {
            app.typeKey(.rightArrow, modifierFlags: [])
            RunLoop.current.run(until: Date().addingTimeInterval(0.6))
        }
    }

    /// A book read once is kept on the device, its ETag beside it (#41):
    /// with the reading servers gone once the reader closes, the check with
    /// the hub fails at once and Light Bringer opens again from the copy
    /// kept, half way through, without a word of failure.
    @MainActor
    func testABookKeptOnTheDeviceOpensAgainInAnOutage() {
        // Under the edition id its page opens it by, so Resume finds the copy this opening keeps.
        let app = launchReading("rw_demo_rr6/demo-rw_demo_rr6", ["HUB_OPEN": "book:rw_demo_rr6", "HUB_BOOK_CHROME": "pinned",
                                                                 "HUB_DEMO_OUTAGE": "after-close"])
        let position = app.staticTexts["book-position"]
        XCTAssertTrue(position.waitForExistence(timeout: 15), "the menu did not come")
        XCTAssertTrue(waitUntil(10) { position.label.contains("% of book") }, "the menu says \(position.label)")
        app.buttons["book-close"].tap()
        XCTAssertTrue(page(app).waitForNonExistence(timeout: 5), "the reader stayed open")

        // The reading servers are gone now; the book's page is still there.
        let entry = app.buttons["book-entry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 10), "Light Bringer's page is not under the reader")
        entry.tap()
        // Kept, it opens rather than downloads.
        let downloading = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Downloading'")).firstMatch
        XCTAssertFalse(downloading.waitForExistence(timeout: 2), "the kept copy was not found: \(downloading.label)")
        // The hub's place cannot be asked either: this device's is offered when it asks.
        let local = app.buttons["book-choice-local"]
        XCTAssertTrue(waitUntil(20) { self.page(app).exists || local.exists }, "the kept book did not open")
        if local.exists { local.tap() }
        XCTAssertTrue(page(app).waitForExistence(timeout: 15), "the kept book did not open")
        XCTAssertFalse(app.staticTexts["book-status-heading"].exists, "a failure was said: \(app.staticTexts["book-status-heading"].label)")
        // Half way through, as it was (the menu pinned again, or opened by ↑).
        if !position.waitForExistence(timeout: 4) { app.typeKey(.upArrow, modifierFlags: []) }
        XCTAssertTrue(position.waitForExistence(timeout: 5), "the menu did not come")
        XCTAssertTrue(waitUntil(10) { position.label.contains("% of book") }, "the menu says \(position.label)")
        XCTAssertTrue((40...60).contains(percent(position.label)), "it opened at \(position.label)")
    }

    /// Kindle's corners (#42): while reading, the time, where you are and
    /// how far through; a tap on the bottom left shows the next way of
    /// saying where you are, round to the first; the menu puts them away.
    @MainActor
    func testTheCornersShowWhileReadingAndATapShowsTheNextPlace() {
        // Whatever an earlier run chose, every corner on and the page in the book first.
        let app = launchReading(Self.recursion, arguments: ["-epub.pageInfo.clock", "YES", "-epub.pageInfo.percentage", "YES",
                                                            "-epub.pageInfo.place", "pageInBook"])
        let clock = app.descendants(matching: .any).matching(identifier: "book-corner-clock").firstMatch
        let place = app.buttons["book-corner-place"]
        let percent = app.descendants(matching: .any).matching(identifier: "book-corner-percent").firstMatch
        XCTAssertTrue(clock.waitForExistence(timeout: 10), "no clock while reading")
        XCTAssertTrue(place.waitForExistence(timeout: 5), "nothing says where the page is")
        XCTAssertTrue(waitUntil(10) { place.label.hasPrefix("Page 1 of ") && !place.label.contains("chapter") },
                      "the bottom left says \(place.label)")
        XCTAssertTrue(percent.exists && percent.label.hasPrefix("0%"), "the bottom right says \(percent.label)")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "corners"
        shot.lifetime = .keepAlways
        add(shot)

        // A tap at a time: the page in the chapter, the time left in it and in the book, then round again.
        for ending in ["in chapter", "left in chapter", "left in book"] {
            place.tap()
            XCTAssertTrue(waitUntil(5) { place.label.hasSuffix(ending) }, "after a tap the bottom left says \(place.label)")
        }
        XCTAssertTrue(place.label.hasPrefix("Page") == false)
        place.tap()
        XCTAssertTrue(waitUntil(5) { place.label.hasPrefix("Page 1 of ") && !place.label.contains("chapter") },
                      "the tap did not come round: \(place.label)")

        // The menu up, the corners away; the menu down, back.
        app.typeKey(.upArrow, modifierFlags: [])
        XCTAssertTrue(app.staticTexts["book-position"].waitForExistence(timeout: 5), "the menu did not come")
        XCTAssertTrue(clock.waitForNonExistence(timeout: 5), "the clock stayed with the menu up")
        XCTAssertFalse(place.exists || percent.exists, "a corner stayed with the menu up")
        page(app).tap()
        XCTAssertTrue(clock.waitForExistence(timeout: 5), "the corners did not come back with the menu down")
        XCTAssertTrue(place.exists && percent.exists)
    }

    /// Appearance › Layout › Page info turns each corner off: the clock,
    /// then the bottom left (None); the percentage stays.
    @MainActor
    func testPageInfoInAppearanceTurnsTheCornersOff() {
        let app = launchReading(Self.recursion, ["HUB_BOOK_SHEET": "layout"],
                                arguments: ["-epub.pageInfo.clock", "YES", "-epub.pageInfo.percentage", "YES",
                                            "-epub.pageInfo.place", "pageInBook"])
        let clockRow = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Clock'")).firstMatch
        XCTAssertTrue(clockRow.waitForExistence(timeout: 15), "Layout has no Page info")
        XCTAssertTrue(clockRow.label.hasSuffix("On"), "the clock row says \(clockRow.label)")
        // Far down the sheet: the tap scrolls to it first.
        clockRow.tap()
        XCTAssertTrue(waitUntil(5) { clockRow.label.hasSuffix("Off") }, "the clock row says \(clockRow.label)")
        let none = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'None'")).firstMatch
        none.tap()
        XCTAssertTrue(waitUntil(5) { none.isSelected }, "None was not chosen")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "page-info"
        shot.lifetime = .keepAlways
        add(shot)
        // Back to the page: the percentage alone.
        app.buttons["Close"].firstMatch.tap()
        let percent = app.descendants(matching: .any).matching(identifier: "book-corner-percent").firstMatch
        if !percent.waitForExistence(timeout: 3) { page(app).tap() }
        XCTAssertTrue(percent.waitForExistence(timeout: 5), "the percentage went too")
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "book-corner-clock").firstMatch.exists,
                       "the clock stayed")
        XCTAssertFalse(app.buttons["book-corner-place"].exists, "the bottom left stayed")
    }

    /// Reset text style (#42): a look kept from before (the book's styling,
    /// left-aligned, no hyphenation, tight lines) gets the reader's own
    /// typography in one press.
    @MainActor
    func testResetTextStyleBringsJustifiedHyphenatedText() {
        let app = launchReading(Self.recursion, ["HUB_BOOK_SHEET": "layout"],
                                arguments: ["-epub.publisherStyles", "YES", "-epub.textAlignment", "start",
                                            "-epub.hyphens", "NO", "-epub.lineHeight", "1.1"])
        let justified = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Justified text'")).firstMatch
        let hyphenation = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Hyphenation'")).firstMatch
        let publisher = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Publisher styling'")).firstMatch
        XCTAssertTrue(justified.waitForExistence(timeout: 15), "Layout has no Justified text")
        XCTAssertTrue(justified.label.hasSuffix("Off") && hyphenation.label.hasSuffix("Off") && publisher.label.hasSuffix("On"),
                      "the kept look reads \(justified.label) / \(hyphenation.label) / \(publisher.label)")
        let reset = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Reset text style'")).firstMatch
        XCTAssertTrue(reset.exists, "Layout has no Reset text style")
        XCTAssertTrue(reset.label.contains("Literata, justified, hyphenated, 1.5 spacing"), "Reset text style says \(reset.label)")
        reset.tap()
        XCTAssertTrue(waitUntil(5) { justified.label.hasSuffix("On") }, "Justified text says \(justified.label)")
        XCTAssertTrue(hyphenation.label.hasSuffix("On"), "Hyphenation says \(hyphenation.label)")
        XCTAssertTrue(publisher.label.hasSuffix("Off"), "Publisher styling says \(publisher.label)")
        XCTAssertTrue(app.buttons["Relaxed"].firstMatch.isSelected, "the line spacing is not Relaxed (1.5)")
    }

    /// Where the book is, from its menu, which ↑ opens (as Ⓑ and Delete do).
    @MainActor
    private func place(_ app: XCUIApplication) -> String {
        app.typeKey(.upArrow, modifierFlags: [])
        let position = app.staticTexts["book-position"]
        XCTAssertTrue(position.waitForExistence(timeout: 5), "↑ did not open the menu")
        XCTAssertTrue(waitUntil(5) { position.label.contains("% of book") }, "the menu says \(position.label)")
        return position.label
    }

    /// "One · Page 2 of 3 in chapter · 4% of book" is 4.
    private func percent(_ line: String) -> Int {
        Int(line.components(separatedBy: "% of book").first?.components(separatedBy: " ").last ?? "") ?? -1
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
