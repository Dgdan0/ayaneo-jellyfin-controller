import XCTest

/// "Still watching?" (#48), against the demo hub's Bleach S1E5 (`-demo`): the
/// player opens 14 seconds from its end, so the up-next card comes at once and
/// its bar fills in eight. Debug launches take `HUB_AUTOPLAY_RUN`, how many
/// episodes have already started by themselves; at three the next one asks.
final class StillWatchingTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(run: Int, environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        // The chrome held up, so Back and the titles are there to read; the question puts it away.
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "media", "HUB_PLAY": "demo-e5", "HUB_PLAY_CHROME": "pinned",
                                 "HUB_PLAY_FROM_END": "14", "HUB_AUTOPLAY_RUN": String(run)]
            .merging(environment) { $1 }
        app.launch()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'S1E5'")).firstMatch
                          .waitForExistence(timeout: 20), "the player did not open on S1E5")
        return app
    }

    @MainActor
    private func episode(_ app: XCUIApplication, _ code: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", code)).firstMatch
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

    /// Leaves the player through Back, so no session is left open.
    @MainActor
    private func leave(_ app: XCUIApplication) {
        let back = app.buttons["Back"].firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5), "no Back")
        back.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 10), "the player stayed open")
    }

    /// After three episodes that started by themselves, the fourth asks
    /// instead of starting; Keep watching starts it.
    @MainActor
    func testTheFourthAutoplayAsksAndKeepWatchingStartsIt() {
        let app = launch(run: 3)
        let question = app.staticTexts["still-watching"]
        XCTAssertTrue(question.waitForExistence(timeout: 40), "the bar filled and nothing asked")
        XCTAssertEqual(question.label, "Still watching?")
        let line = app.staticTexts["still-watching-line"].label
        XCTAssertTrue(line.hasPrefix("Paused after 3 episodes in a row.") && line.contains("Next: S1E6"), line)
        XCTAssertTrue(app.buttons["still-watching-stop"].exists, "no Stop")
        XCTAssertFalse(app.buttons["Watch credits"].exists, "the up-next card stayed under the question")
        XCTAssertFalse(episode(app, "S1E6").exists, "the next episode started without an answer")
        app.buttons["still-watching-keep"].tap()
        XCTAssertTrue(question.waitForNonExistence(timeout: 5), "Keep watching left the question up")
        XCTAssertTrue(episode(app, "S1E6").waitForExistence(timeout: 20), "Keep watching did not start S1E6")
        leave(app)
    }

    /// Two have started by themselves: the third still does, without asking.
    @MainActor
    func testTheThirdAutoplayStillStartsByItself() {
        let app = launch(run: 2)
        XCTAssertTrue(episode(app, "S1E6").waitForExistence(timeout: 40), "the third autoplay did not start S1E6")
        XCTAssertFalse(app.staticTexts["still-watching"].exists, "it asked before three had started by themselves")
        leave(app)
    }

    /// A controller: Ⓐ is Keep watching, the default.
    @MainActor
    func testAControllersAKeepsWatching() {
        // Ⓐ 32 seconds in: the question has long been asked.
        let app = launch(run: 3, environment: ["HUB_PAD": "A", "HUB_PAD_DELAY": "32"])
        let question = app.staticTexts["still-watching"]
        XCTAssertTrue(question.waitForExistence(timeout: 30), "the bar filled and nothing asked")
        XCTAssertTrue(question.waitForNonExistence(timeout: 20), "Ⓐ did not answer the question")
        XCTAssertTrue(episode(app, "S1E6").waitForExistence(timeout: 20), "Ⓐ did not start S1E6")
        leave(app)
    }

    /// A controller: Ⓑ stops, and the player closes.
    @MainActor
    func testAControllersBStops() {
        let app = launch(run: 3, environment: ["HUB_PAD": "B", "HUB_PAD_DELAY": "32"])
        let question = app.staticTexts["still-watching"]
        XCTAssertTrue(question.waitForExistence(timeout: 30), "the bar filled and nothing asked")
        XCTAssertTrue(question.waitForNonExistence(timeout: 20), "Ⓑ did not answer the question")
        // Its chrome is held up: had the player stayed open, its controls would be back.
        RunLoop.current.run(until: Date().addingTimeInterval(2))
        XCTAssertFalse(app.buttons["Lock controls"].exists, "Ⓑ did not close the player")
        XCTAssertFalse(episode(app, "S1E6").exists, "Ⓑ started the next episode")
    }
}
