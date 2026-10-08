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

    /// Seconds into the chapter playing, from the line's "0:12 of 1:30".
    @MainActor
    private func seconds(_ app: XCUIApplication) -> Int {
        let line = app.descendants(matching: .any).matching(identifier: "player-timeline").firstMatch
        let clock = (line.value as? String)?.components(separatedBy: " of ").first ?? ""
        let parts = clock.split(separator: ":").compactMap { Int($0) }
        return parts.count == 2 ? parts[0] * 60 + parts[1] : -1
    }

    /// With the app in the background (Home, as the screen locking does) the
    /// audiobook plays on (#49): the app is not suspended, and back in it the
    /// book is further on.
    @MainActor
    func testAnAudiobookPlaysOnInTheBackground() {
        let app = launch(open: "book:rw_demo_alloy")
        let play = listening(app)
        XCTAssertTrue(waitUntil(10) { seconds(app) >= 1 }, "the line does not move")
        let before = seconds(app)
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 10), "the app did not go to the background: \(app.state.rawValue)")
        RunLoop.current.run(until: Date().addingTimeInterval(12))
        XCTAssertEqual(app.state, .runningBackground, "the app was suspended in the background: the book stopped")
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        XCTAssertTrue(waitUntil(5) { seconds(app) >= before + 10 }, "the book did not play on in the background: \(before)s, now \(seconds(app))s")
        XCTAssertEqual(play.label, "Pause")
        play.tap()
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

    /// The contents list the book's chapters by their titles (#31), from the
    /// manifest's `chapters`: Dark Matter's demo names four across its three tracks.
    @MainActor
    func testPartsListTheBooksChaptersByTitle() {
        // Its own page, as Listen opens it: the book on the player, paused.
        let app = launch(open: "listen:rw_demo_darkmatter|demo-dm")
        let play = app.buttons["listen-play"]
        XCTAssertTrue(play.waitForExistence(timeout: 15), "the audiobook's page did not open: \(buttons(app))")
        XCTAssertTrue(waitUntil(15) { play.isEnabled }, "the audiobook was not put on the player")
        // A book with chapters calls its contents Chapters.
        let parts = app.buttons["listen-contents"]
        XCTAssertTrue(parts.waitForExistence(timeout: 5), "the audiobook's page has no contents: \(buttons(app))")
        XCTAssertEqual(parts.label, "Chapters")
        parts.tap()
        for title in ["One", "Two", "Three", "Four"] {
            let chapter = app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", title, title + " ·"))
                .firstMatch
            XCTAssertTrue(chapter.waitForExistence(timeout: 5), "the contents do not list \(title): \(buttons(app))")
        }
        // Chosen, the chapter plays from its start; the book is stopped after.
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Three'")).firstMatch.tap()
        let stop = app.buttons["listen-stop"]
        XCTAssertTrue(stop.waitForExistence(timeout: 5))
        stop.tap()
    }

    /// An aligned audiobook goes by the book's own chapters (#31): the demo's
    /// Dark Matter, whose chapters run on from one track into the next, opens
    /// at its place ten seconds into Three, in its second track.
    @MainActor
    func testAnAlignedAudiobookGoesByTheBooksChapters() {
        let app = launch(open: "listen:rw_demo_darkmatter|demo-dm")
        let play = app.buttons["listen-play"]
        XCTAssertTrue(play.waitForExistence(timeout: 15), "the audiobook's page did not open: \(buttons(app))")
        XCTAssertTrue(waitUntil(15) { play.isEnabled }, "the audiobook was not put on the player")
        let entry = app.staticTexts["listen-entry"]
        XCTAssertTrue(waitUntil(10) { entry.exists && entry.label == "Three" }, "not in Three: \(entry.label)")
        let left = app.staticTexts["listen-time-left"]
        XCTAssertTrue(left.label.hasPrefix("1 min left in chapter"), "the time left is not the chapter's: \(left.label)")

        // The contents are the book's chapters, by title, the one playing marked.
        let contents = app.buttons["listen-contents"]
        XCTAssertEqual(contents.label, "Chapters")
        contents.tap()
        XCTAssertTrue(app.staticTexts["Dark Matter · 4 chapters"].waitForExistence(timeout: 5), "the contents have no heading")
        for title in ["One · 0:52", "Two · 0:58", "Four · 0:45"] {
            XCTAssertTrue(button(app, containing: title).waitForExistence(timeout: 5), "no \(title) in the contents: \(buttons(app))")
        }
        XCTAssertTrue(button(app, containing: "Three").exists)
        XCTAssertFalse(button(app, containing: "Track 0").exists, "a track is listed among the chapters")
        // A chapter chosen is where the book goes.
        button(app, containing: "One · 0:52").tap()
        XCTAssertTrue(waitUntil(5) { entry.label == "One" }, "choosing One did not go there: \(entry.label)")

        // Next is the next chapter, Three in the next track; back from its start is the one before.
        app.buttons["Next chapter"].tap()
        XCTAssertTrue(waitUntil(5) { entry.label == "Two" }, "Next did not go to Two: \(entry.label)")
        app.buttons["Next chapter"].tap()
        XCTAssertTrue(waitUntil(5) { entry.label == "Three" }, "Next did not go on to Three: \(entry.label)")
        app.buttons["Previous chapter"].tap()
        XCTAssertTrue(waitUntil(5) { entry.label == "Two" }, "Previous did not go back to Two: \(entry.label)")

        // The sleep timer's end is the chapter's.
        button(app, containing: "Sleep").tap()
        let endOfChapter = app.buttons["End of this chapter"]
        XCTAssertTrue(endOfChapter.waitForExistence(timeout: 5), "the sleep timer has no end of chapter: \(buttons(app))")
        endOfChapter.tap()
        XCTAssertTrue(button(app, containing: "Sleep · end of chapter").waitForExistence(timeout: 5),
                      "the sleep timer does not say it ends with the chapter: \(buttons(app))")
        button(app, containing: "Sleep").tap()
        app.buttons["Turn off"].tap()

        // Away from its page, the mini player names the chapter under the book.
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Back to'")).firstMatch.tap()
        let detail = app.staticTexts["mini-detail"]
        XCTAssertTrue(detail.waitForExistence(timeout: 5), "no mini player away from the book's page: \(buttons(app))")
        XCTAssertTrue(detail.label.hasPrefix("Two · "), "the mini player does not name the chapter: \(detail.label)")
        button(app, containing: "Dark Matter, Two, open").tap()
        app.buttons["listen-stop"].tap()
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
