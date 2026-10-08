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

    /// Every text on screen on one line, for a failure's message (the reader's notices among them).
    @MainActor
    private func texts(_ app: XCUIApplication) -> String {
        app.staticTexts.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.joined(separator: " | ")
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

    // MARK: The voice and the page (#49)

    /// The demo's chapters are a page or two at the usual size: drawn larger
    /// (`HUB_BOOK_SIZE`, for this launch only), they run over several pages,
    /// and sentences break across them.
    private static let largeType = ["HUB_BOOK_LOOK_ONCE": "1", "HUB_DEBUG_READALONG": "1"]
    /// The sizes tried, smallest first, until the reader counts enough pages.
    private static let sizes = ["2.2", "3.0", "4.0", "5.0"]

    /// The read-along book drawn large enough that the reader counts at least
    /// `pages` pages in its chapter. How large is the layout's own answer, not
    /// a device's: a phone reaches it at the first size, an iPad's two columns
    /// (a 12.9-inch held upright is wide enough for them) only at a larger one.
    @MainActor
    private func launchOverPages(_ pages: Int, _ environment: [String: String] = [:]) -> XCUIApplication {
        launchOverPages(pages, environment, then: { _ in })
    }

    /// The same, from `sizes`, `turn` done to each launch before its pages are counted (turning the device sideways).
    @MainActor
    private func launchOverPages(_ pages: Int, _ environment: [String: String], sizes: [String] = ReadAlongTests.sizes,
                                 then turn: (XCUIApplication) throws -> Void) rethrows -> XCUIApplication {
        for size in sizes {
            let app = launchReadingAlong(Self.largeType.merging(environment) { $1 }.merging(["HUB_BOOK_SIZE": size]) { $1 })
            try turn(app)
            if waitUntil(15, { page(app).count >= pages }) { return app }
            if size == sizes.last {
                XCTFail("even at \(size) the chapter is not \(pages) pages: \(debug(app).label)")
                return app
            }
            app.terminate()
        }
        preconditionFailure("no size to try")
    }

    /// The position line: "One · Page 2 of 3 in chapter · 18% of book".
    @MainActor
    private func place(_ app: XCUIApplication) -> String {
        let label = debug(app).label
        return label.range(of: #"^.*?% of book"#, options: .regularExpression).map { String(label[$0]) } ?? label
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    /// What the voice and the page last did to each other, after the
    /// reader's position line (`HUB_DEBUG_READALONG`).
    @MainActor
    private func debug(_ app: XCUIApplication) -> XCUIElement {
        app.staticTexts["debug-readalong"]
    }

    /// "Page 2 of 5 in chapter" from the position line: the page and how many.
    @MainActor
    private func page(_ app: XCUIApplication) -> (index: Int, count: Int) {
        let label = debug(app).label
        guard let range = label.range(of: #"Page (\d+) of (\d+)"#, options: .regularExpression) else { return (1, 1) }
        let numbers = label[range].split(separator: " ").compactMap { Int($0) }
        return numbers.count == 2 ? (numbers[0], numbers[1]) : (1, 1)
    }

    /// How far into its sentence the voice was when it last turned the page, in ms; nil before a turn.
    @MainActor
    private func turnedInside(_ app: XCUIApplication) -> Int? {
        let label = debug(app).label
        guard let range = label.range(of: #"turned in \S+ \+(-?\d+)ms"#, options: .regularExpression) else { return nil }
        return Int(label[range].split(separator: "+").last?.dropLast(2) ?? "")
    }

    /// The screen as it is: the app's own screenshot came out sideways and cut in half once the simulator turned.
    @MainActor
    private func keep(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Plays from the page, then takes the menu away: the page alone, as it is read.
    @MainActor
    private func playWithTheMenuAway(_ app: XCUIApplication) -> XCUIElement {
        let play = app.buttons["readalong-play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20), "the dock is not the lower bar: \(buttons(app))")
        play.tap()
        XCTAssertTrue(waitUntil(15) { play.label == "Pause narration" }, "the narration did not play")
        let page = app.descendants(matching: .any).matching(identifier: "book-page").firstMatch
        page.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(waitUntil(5) { !play.exists }, "the menu did not go away")
        return play
    }

    /// While the voice reads with the menu away, the corners stay as they are
    /// when reading alone: the pill that took the bottom right is gone and the
    /// percentage is back. A tap on the page still brings the dock.
    @MainActor
    func testTheCornersStayWhileTheNarrationPlays() {
        let app = launchReadingAlong()
        _ = playWithTheMenuAway(app)
        let percent = app.descendants(matching: .any).matching(identifier: "book-corner-percent").firstMatch
        XCTAssertTrue(percent.waitForExistence(timeout: 5), "no percentage at the bottom right while the voice reads")
        XCTAssertFalse(app.buttons["readalong-pill"].exists, "the narration's pill is still there")
        keep(app, "readalong-corner-playing")
        let page = app.descendants(matching: .any).matching(identifier: "book-page").firstMatch
        page.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let play = app.buttons["readalong-play"]
        XCTAssertTrue(play.waitForExistence(timeout: 5), "a tap did not bring the dock back")
        XCTAssertEqual(play.label, "Pause narration")
        play.tap()
    }

    /// The voice turns the page when it reaches the next page's first word,
    /// inside the sentence the page break cuts, and the sentence goes on
    /// glowing on the new page.
    @MainActor
    func testTheVoiceTurnsThePageInsideASentence() {
        let app = launchOverPages(3)
        _ = playWithTheMenuAway(app)
        // Some page breaks fall between sentences: the voice reads on to one inside a sentence.
        XCTAssertTrue(waitUntil(60) { (turnedInside(app) ?? 0) > 400 }, "the voice turned no page inside a sentence: \(debug(app).label)")
        RunLoop.current.run(until: Date().addingTimeInterval(0.6))
        keep(app, "readalong-turned-mid-sentence")
    }

    /// A page turned by hand while the voice reads takes the voice to the new
    /// page's first word, playing on; the dock says the page follows the voice.
    @MainActor
    func testAPageTurnedByHandTakesTheVoiceToItsFirstWord() {
        let app = launchOverPages(3)
        let play = playWithTheMenuAway(app)
        // Just after the voice has turned a page its next turn is a page away: the hand turns
        // then, not in the moment the voice does (a turn the voice is making takes no other).
        XCTAssertTrue(waitUntil(40) { debug(app).label.contains("turned in") }, "the voice turned no page: \(debug(app).label)")
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        let before = place(app)
        app.typeKey(XCUIKeyboardKey.rightArrow, modifierFlags: [])
        XCTAssertTrue(waitUntil(10) { debug(app).label.contains("moved to") }, "the voice did not go to the page: \(debug(app).label)")
        // On a page or into the next chapter, where the voice turned to its last page.
        XCTAssertNotEqual(place(app), before, "the page did not turn: \(debug(app).label)")
        // Moved on, not back: from where it was to the next page's first word.
        let moves = debug(app).label.components(separatedBy: "moved to ").last?.components(separatedBy: " from ") ?? []
        let positions = moves.map { $0.split(separator: ":").compactMap { Int($0.prefix { $0.isNumber || $0 == "-" }) } }
        XCTAssertEqual(positions.count, 2, debug(app).label)
        if positions.count == 2, positions[0].count == 2, positions[1].count == 2 {
            XCTAssertTrue(positions[0][0] > positions[1][0] || positions[0][1] > positions[1][1] + 1_000,
                          "the voice did not move on to the page: \(debug(app).label)")
        }
        // The voice reads on there, and the page is the narration's: no "Reading".
        let page = app.descendants(matching: .any).matching(identifier: "book-page").firstMatch
        page.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(play.label, "Pause narration", "turning the page stopped the voice")
        XCTAssertTrue(text(app, containing: "Read along · Following").exists, "the page is not following the voice")
        XCTAssertFalse(text(app, containing: "Read along · Reading").exists)
        play.tap()
    }

    /// Turned sideways, two columns asked for: the voice still turns the page
    /// inside a sentence. On an iPad the page is the two-column spread, which
    /// the page's script measures as one page; an iPhone held sideways is too
    /// narrow for Readium's two columns, so there it is one wide column. A
    /// simulator that will not turn (the 12.9-inch iPad often will not from a
    /// test) skips it, saying so: an iPad held upright that wide already lays
    /// out two columns, which the other tests read along in.
    @MainActor
    func testTurnedSidewaysTheVoiceStillTurnsThePage() throws {
        // Sideways a phone's page is short: from a smaller size, at which its breaks fall inside sentences
        // (at 2.2 they fell between paragraphs, which a page break keeps whole when it can).
        // Three pages or more, as the others: with two, a chapter's one break can fall between paragraphs.
        let app = try launchOverPages(3, ["HUB_BOOK_COLUMNS": "TWO"], sizes: ["1.8"] + Self.sizes) { app in
            // Turned once the app is up, and measured only once its window is wide.
            let window = app.windows.firstMatch
            for orientation in [UIDeviceOrientation.landscapeLeft, .landscapeRight]
            where !(window.frame.width > window.frame.height) {
                XCUIDevice.shared.orientation = orientation
                _ = waitUntil(10) { window.frame.width > window.frame.height }
            }
            guard window.frame.width > window.frame.height else {
                throw XCTSkip("the simulator did not turn the app sideways (its window stayed \(window.frame)), so there is no sideways page to read along in here")
            }
            RunLoop.current.run(until: Date().addingTimeInterval(2))
        }
        keep(app, "readalong-sideways")
        _ = playWithTheMenuAway(app)
        XCTAssertTrue(waitUntil(75) { (turnedInside(app) ?? 0) > 400 }, "the voice turned no page inside a sentence: \(debug(app).label)")
        RunLoop.current.run(until: Date().addingTimeInterval(0.6))
        keep(app, "readalong-sideways-turned")
    }

    /// A sentence over several lines is one even tint with one glow (#52):
    /// where two lines' boxes meet, the colour is not laid twice. Drawn at 130%
    /// and 200%, at each line spacing, for the eye (the attachments
    /// "readalong-lines-<size>-<spacing>"); the book opens on its first
    /// sentence lit, which runs over three lines and more. On an iPad the page
    /// is two columns.
    @MainActor
    func testASentenceOverSeveralLinesIsOneEvenTint() {
        let columns = UIDevice.current.userInterfaceIdiom == .pad ? "TWO" : "AUTO"
        for size in ["1.3", "2.0"] {
            for spacing in ["1.3", "1.5", "1.8"] {
                let app = launchReadingAlong(Self.largeType.merging(["HUB_BOOK_SIZE": size, "HUB_BOOK_SPACING": spacing,
                                                                     "HUB_BOOK_COLUMNS": columns, "HUB_BOOK_CHROME": ""]) { $1 })
                XCTAssertTrue(waitUntil(15) { debug(app).label.contains("% of book") }, "the page did not say where it is: \(debug(app).label)")
                // The look is set a moment after the book opens, and the sentence lit again on the new layout.
                RunLoop.current.run(until: Date().addingTimeInterval(3))
                keep(app, "readalong-lines-\(size)-\(spacing)")
                app.terminate()
            }
        }
        // An iPad held sideways: two columns, the sentence over lines in the first.
        guard UIDevice.current.userInterfaceIdiom == .pad else { return }
        XCUIDevice.shared.orientation = .landscapeLeft
        for size in ["1.3", "2.0"] {
            let app = launchReadingAlong(Self.largeType.merging(["HUB_BOOK_SIZE": size, "HUB_BOOK_SPACING": "1.5",
                                                                 "HUB_BOOK_COLUMNS": "TWO", "HUB_BOOK_CHROME": ""]) { $1 })
            let window = app.windows.firstMatch
            guard waitUntil(10, { window.frame.width > window.frame.height }) else {
                XCTContext.runActivity(named: "the simulator did not turn the app sideways: no two-column picture") { _ in }
                return
            }
            XCTAssertTrue(waitUntil(15) { debug(app).label.contains("% of book") }, "the page did not say where it is: \(debug(app).label)")
            RunLoop.current.run(until: Date().addingTimeInterval(3))
            keep(app, "readalong-lines-columns-\(size)")
            app.terminate()
        }
    }

    /// The sentence lit keeps its words' ink and tints no other sentence's
    /// words (#52, the owner's notes from the Pocket): the tint is behind the
    /// words, one line tall per line. A sentence that starts and ends mid-line
    /// ("He had walked this way home…", the second of the chapter's first
    /// paragraph), paused there, in Paper, Sepia, Dim and Dark, at 130% and
    /// 200%, at spacing 1.3 and 1.8: the attachments
    /// "readalong-ink-<theme>-<size>-<spacing>", which the ink and the
    /// neighbours' words are measured on. On an iPad, two columns.
    @MainActor
    func testTheSentenceLitKeepsItsInkAndTintsNoOtherWords() {
        // An iPad also held sideways, where its page is two columns.
        let ways = UIDevice.current.userInterfaceIdiom == .pad ? ["", "columns-"] : [""]
        for way in ways {
            if !way.isEmpty { XCUIDevice.shared.orientation = .landscapeLeft }
            for theme in ["LIGHT", "SEPIA", "DARK", "BLACK"] {
                for size in ["1.3", "2.0"] {
                    for spacing in ["1.3", "1.8"] {
                        let app = launchReadingAlong(Self.largeType.merging(
                            ["HUB_BOOK_THEME": theme, "HUB_BOOK_SIZE": size, "HUB_BOOK_SPACING": spacing,
                             "HUB_BOOK_COLUMNS": way.isEmpty ? "AUTO" : "TWO", "HUB_BOOK_CHROME": "",
                             "HUB_READALONG_SENTENCE": "one-s2"]) { $1 })
                        XCTAssertTrue(waitUntil(30) { debug(app).label.contains("% of book") }, "the page did not say where it is: \(debug(app).label)")
                        // The look is set a moment after the book opens, and the sentence lit again on the new layout.
                        RunLoop.current.run(until: Date().addingTimeInterval(3))
                        keep(app, "readalong-ink-\(way)\(theme)-\(size)-\(spacing)")
                        app.terminate()
                    }
                }
            }
        }
    }

    /// The spaces round a sentence are not lit with it (#56): Storyteller's
    /// element holds the space after a sentence ("The dead are dead. "), or
    /// before it (" Later …", the demo's chapter Two), and the wash ends at its
    /// full stop and starts at its first letter. The attachments
    /// "readalong-trim-after", "-before" and "-before-mid-line", which are measured.
    @MainActor
    func testTheSpacesRoundASentenceAreNotLit() {
        // Two's second sentence starts a line; its third starts after "evening." on the same line.
        for (name, sentence) in [("after", "one-s2"), ("before", "two-s2"), ("before-mid-line", "two-s3")] {
            let app = launchReadingAlong(Self.largeType.merging(
                ["HUB_BOOK_THEME": "SEPIA", "HUB_BOOK_SIZE": "1.3", "HUB_BOOK_SPACING": "1.5", "HUB_BOOK_CHROME": "",
                 "HUB_READALONG_SENTENCE": sentence]) { $1 })
            XCTAssertTrue(waitUntil(30) { debug(app).label.contains("% of book") }, "the page did not say where it is: \(debug(app).label)")
            RunLoop.current.run(until: Date().addingTimeInterval(3))
            keep(app, "readalong-trim-\(name)")
            app.terminate()
        }
    }

    /// With the app in the background (Home, as the screen locking does) the
    /// voice reads on: the app is not suspended, the narration has the lock
    /// screen, and back in the app the voice is further on and the page with it.
    @MainActor
    func testTheNarrationPlaysOnInTheBackgroundAndThePageCatchesUp() {
        let app = launchOverPages(3)
        let play = app.buttons["readalong-play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        play.tap()
        XCTAssertTrue(waitUntil(15) { play.label == "Pause narration" },
                      "the narration did not play: \(debug(app).label); \(texts(app))")
        XCTAssertTrue(waitUntil(10) { debug(app).label.hasSuffix("narration · Dark Matter") },
                      "the lock screen is not the narration's: \(debug(app).label)")
        let time = app.staticTexts["readalong-time"]
        let before = seconds(time)
        let pageBefore = page(app).index

        XCUIDevice.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 10), "the app did not go to the background: \(app.state.rawValue)")
        RunLoop.current.run(until: Date().addingTimeInterval(24))
        // A quiet app is suspended within seconds; one playing sound is not.
        XCTAssertEqual(app.state, .runningBackground, "the app was suspended in the background: the voice stopped")

        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        XCTAssertTrue(waitUntil(5) { seconds(time) >= before + 22 }, "the voice did not read on in the background: \(before)s, now \(time.label)")
        XCTAssertEqual(play.label, "Pause narration")
        // A chapter of 46 seconds over three pages or more: 24 seconds is past the first page, the
        // shortest for its heading. The page has caught up with the voice.
        XCTAssertTrue(waitUntil(8) { page(app).index > pageBefore || debug(app).label.contains("Two") },
                      "the page did not catch up with the voice: \(debug(app).label)")
        play.tap()
    }
}
