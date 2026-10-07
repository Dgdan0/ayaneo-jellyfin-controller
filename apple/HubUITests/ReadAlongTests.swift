import XCTest

/// Read along against the demo hub (#25 phase 4, #16, #19): Dark Matter's
/// read-along edition (`DemoReadAlong`), its words and media overlays, read
/// along with its narration streamed from the demo's audiobook tracks, which
/// are tones made on the device. The place it keeps goes to the demo hub
/// only, as every UI test runs with `-demo`.
final class ReadAlongTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launchReadingAlong(_ environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_BOOK": "rw_demo_darkmatter/demo-dm",
                                 "HUB_BOOK_READALONG": "1", "HUB_BOOK_SCROLL": "0", "HUB_BOOK_CHROME": "pinned"]
            .merging(environment) { $1 }
        app.launch()
        let page = app.descendants(matching: .any).matching(identifier: "book-page").firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 30), "the read-along edition did not open")
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

    /// Seconds into the stretch playing, from the dock's "0:12 of 1:24 · part 1 of 4".
    @MainActor
    private func seconds(_ time: XCUIElement) -> Int {
        let clock = time.label.components(separatedBy: " of ").first ?? ""
        let parts = clock.split(separator: ":").compactMap { Int($0) }
        return parts.count == 2 ? parts[0] * 60 + parts[1] : -1
    }

    /// The dock is the menu's lower bar; Play reads along from the page, the
    /// voice moves on, the jumps jump, and Pause pauses.
    @MainActor
    func testTheNarrationPlaysFromThePageWithTheDockAsTheLowerBar() {
        let app = launchReadingAlong()
        let play = app.buttons["readalong-play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20), "the dock is not the lower bar: \(buttons(app))")
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "book-slider").firstMatch.exists,
                       "the book's own lower bar is there too")
        XCTAssertEqual(play.label, "Play narration")
        let time = app.staticTexts["readalong-time"]
        XCTAssertTrue(time.label.hasSuffix("part 1 of 4"), "the dock does not say where the narration is: \(time.label)")

        // The place kept was the page, not a sentence: Play starts from the page's first narrated sentence.
        play.tap()
        XCTAssertTrue(waitUntil(15) { play.label == "Pause narration" }, "the narration did not play")
        XCTAssertTrue(text(app, containing: "Read along · Following").waitForExistence(timeout: 5),
                      "the dock does not say the page follows the voice")
        let started = seconds(time)
        XCTAssertTrue(waitUntil(15) { seconds(time) >= started + 2 }, "the voice did not move on: \(time.label)")

        // +10 jumps ten seconds on; Pause stops it there.
        let before = seconds(time)
        app.buttons["readalong-forward"].tap()
        XCTAssertTrue(waitUntil(5) { seconds(time) >= before + 9 }, "+10 did not jump: \(before) to \(time.label)")
        play.tap()
        XCTAssertTrue(waitUntil(5) { play.label == "Play narration" }, "the narration did not pause")
        let paused = seconds(time)
        RunLoop.current.run(until: Date().addingTimeInterval(2))
        XCTAssertLessThanOrEqual(seconds(time), paused + 1, "it went on after Pause: \(time.label)")
    }

    /// A page turned by hand while the voice reads: the page stops following
    /// it ("Reading"), the keys still reach the page, and Return to narrated
    /// sentence follows it again. With the menu away, a pill says it plays.
    @MainActor
    func testAPageTurnedByHandStopsFollowingUntilTheNarrationIsFollowedAgain() {
        let app = launchReadingAlong()
        let play = app.buttons["readalong-play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20), "the dock is not the lower bar: \(buttons(app))")
        play.tap()
        XCTAssertTrue(waitUntil(15) { play.label == "Pause narration" }, "the narration did not play")
        XCTAssertTrue(text(app, containing: "Read along · Following").waitForExistence(timeout: 5))

        // A tap in the page's middle takes the menu away: the pill says the voice plays and the page follows it.
        let page = app.descendants(matching: .any).matching(identifier: "book-page").firstMatch
        page.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let pill = app.buttons["readalong-pill"]
        XCTAssertTrue(pill.waitForExistence(timeout: 5), "no pill while the narration plays with the menu away: \(buttons(app))")
        XCTAssertTrue(pill.label.contains("Following"), "the pill reads \(pill.label)")

        // The keyboard's right arrow turns the page while the voice reads on: the page reads on its own.
        app.typeKey(XCUIKeyboardKey.rightArrow, modifierFlags: [])
        XCTAssertTrue(waitUntil(10) { pill.exists && pill.label.contains("Reading") },
                      "a page turned by hand still follows the voice: \(pill.label)")

        // The pill brings the menu back, and Return to narrated sentence follows the voice again.
        pill.tap()
        XCTAssertTrue(play.waitForExistence(timeout: 5), "the pill did not bring the menu back")
        XCTAssertEqual(play.label, "Pause narration", "turning the page stopped the voice")
        XCTAssertTrue(text(app, containing: "Read along · Reading").exists)
        app.buttons["readalong-follow"].tap()
        XCTAssertTrue(text(app, containing: "Read along · Following").waitForExistence(timeout: 10),
                      "Return to narrated sentence did not follow the voice again")
        play.tap()
        XCTAssertTrue(waitUntil(5) { play.label == "Play narration" }, "the narration did not pause")
    }
}
