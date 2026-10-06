import XCTest

/// Listening (#25 phase 2), against the demo hub (`-demo`): its audiobooks
/// are tones made on the device and its places live for one launch, so
/// nothing real is played, read or written.
final class ListeningTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// `HUB_PLAY_CHROME=pinned` holds a video's controls up, so Back can be pressed.
    @MainActor
    private func launch(open: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_OPEN": open, "HUB_PLAY_CHROME": "pinned"]
        app.launch()
        return app
    }

    @MainActor
    private func button(_ app: XCUIApplication, containing words: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
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

    /// The Alloy of Law's page, Listen, and Play: the book playing on its own page.
    @MainActor
    private func listening(_ app: XCUIApplication) -> XCUIElement {
        let entry = app.buttons["book-entry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 15), "the audiobook's page did not open: \(buttons(app))")
        XCTAssertTrue(entry.label.contains("Listen") || entry.label.contains("Continue"), "the audiobook's button: \(entry.label)")
        entry.tap()
        let play = app.buttons["listen-play"]
        XCTAssertTrue(play.waitForExistence(timeout: 10), "Listen did not open the audiobook")
        XCTAssertTrue(waitUntil(15) { play.isEnabled }, "the audiobook was not put on the player")
        play.tap()
        XCTAssertTrue(waitUntil(10) { play.label == "Pause" }, "the audiobook did not play")
        return play
    }

    @MainActor
    func testAnAudiobookPlaysOnUnderTheMiniPlayerUntilItIsStopped() {
        let app = launch(open: "book:rw_demo_alloy")
        _ = listening(app)
        XCTAssertTrue(app.staticTexts["listen-time-left"].exists, "the time left is not shown")

        // Away from its page it plays on under the mini player.
        app.buttons["Back to The Alloy of Law"].tap()
        let mini = app.buttons["mini-play"]
        XCTAssertTrue(mini.waitForExistence(timeout: 5), "no mini player away from the book's page")
        XCTAssertEqual(mini.label, "Pause")
        mini.tap()
        XCTAssertTrue(waitUntil(5) { mini.label == "Play" }, "the mini player did not pause the book")

        // It brings the book's page back, where it does not show itself.
        button(app, containing: "The Alloy of Law, open").tap()
        let stop = app.buttons["listen-stop"]
        XCTAssertTrue(stop.waitForExistence(timeout: 5), "the mini player did not open the book")
        XCTAssertFalse(app.buttons["mini-play"].exists, "the mini player stayed over the book's own page")

        // Stop takes the book off the player, and the mini player with it.
        stop.tap()
        app.buttons["Back to The Alloy of Law"].tap()
        XCTAssertTrue(app.buttons["book-entry"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["mini-play"].exists, "the mini player stayed after Stop")
    }

    /// One sound at a time: a video opened while the book plays pauses it.
    @MainActor
    func testAVideoPausesTheAudiobook() {
        let app = launch(open: "book:rw_demo_alloy")
        _ = listening(app)

        // Out to the side's root, where Media is, the book playing on under the mini player.
        app.buttons["Back to The Alloy of Law"].tap()
        let root = app.buttons["Back to Home"]
        XCTAssertTrue(root.waitForExistence(timeout: 5), "the book's page has no way back: \(buttons(app))")
        root.tap()
        let media = app.buttons["Movies and TV"]
        XCTAssertTrue(media.waitForExistence(timeout: 5), "no Media side picker: \(buttons(app))")
        media.tap()
        app.buttons["Library"].firstMatch.tap()
        let movies = app.buttons["Movies, Movie library"]
        XCTAssertTrue(movies.waitForExistence(timeout: 10), "the media libraries did not load: \(buttons(app))")
        movies.tap()
        let film = button(app, containing: "Gran Torino")
        XCTAssertTrue(film.waitForExistence(timeout: 10), "Movies did not load: \(buttons(app))")
        film.tap()
        // "Play", or "Resume · 1:02" (not Books Home's "Resume reading", still there out of sight).
        let watch = app.buttons.matching(NSPredicate(format: "(label == 'Play' OR label BEGINSWITH 'Resume ·') AND identifier != 'mini-play'"))
            .firstMatch
        XCTAssertTrue(watch.waitForExistence(timeout: 10), "the film's page has no Play: \(buttons(app))")
        XCTAssertEqual(app.buttons["mini-play"].label, "Pause", "the book stopped before the film began")

        watch.tap()
        // The player covers the pages, and the mini player with them.
        XCTAssertTrue(app.buttons["mini-play"].waitForNonExistence(timeout: 10), "the player did not open over the pages")
        XCTAssertTrue(app.buttons["Lock controls"].waitForExistence(timeout: 15), "the player has no controls")
        app.buttons["Back"].firstMatch.tap()

        let mini = app.buttons["mini-play"]
        XCTAssertTrue(mini.waitForExistence(timeout: 10), "the mini player did not come back after the film")
        XCTAssertEqual(mini.label, "Play", "the audiobook played on under the film")
    }
}
