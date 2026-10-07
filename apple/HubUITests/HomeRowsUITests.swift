import XCTest

/// Home's rows (#35), against the demo hub (`-demo`): Coming up from the
/// calendar, a row of a library's newest titles, and Settings › Home turning
/// rows off and putting them in another order. The demo keeps the layout for
/// the run only, so each launch starts from Android's default.
final class HomeRowsUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(section: String = "home", environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": section, "HUB_SIDE": "media"].merging(environment) { _, new in new }
        app.launch()
        return app
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// A row's title as Home shows it, by the row's id: Settings' own lines of
    /// the same names stay in the tree behind Home, out of reach, and are not rows.
    @MainActor
    private func row(_ app: XCUIApplication, _ title: String) -> XCUIElement {
        let ids = ["Continue watching": "continue", "Next up": "nextup", "Recently added": "latest", "Favourites": "favourites",
                   "Coming up": "upcoming", "From Anime": "library:a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"]
        return element(app, "home-title-\(ids[title] ?? title)")
    }

    /// Scrolls Home until `element` is on screen, or gives up.
    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, tries: Int = 8) -> Bool {
        for _ in 0..<tries where !(element.exists && element.isHittable) { app.swipeUp() }
        return element.exists
    }

    /// The Home tab: Settings' own pill for this page is also called Home.
    @MainActor
    private func homeTab(_ app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == 'Home' AND NOT (identifier BEGINSWITH 'pane-')")).firstMatch
    }

    // MARK: Coming up

    @MainActor
    func testComingUpIsTheLastRowAndItsCardsOpenTheirRequestSide() {
        let app = launch()
        XCTAssertTrue(row(app, "Continue watching").waitForExistence(timeout: 15), "Home's rows did not load")
        XCTAssertTrue(row(app, "Next up").exists && row(app, "Recently added").exists)
        XCTAssertFalse(row(app, "Favourites").exists, "an empty row is left out")
        let coming = row(app, "Coming up")
        XCTAssertTrue(reveal(coming, in: app), "Coming up is missing")
        let latest = row(app, "Recently added")
        if latest.exists { XCTAssertGreaterThan(coming.frame.minY, latest.frame.minY, "Coming up is not after Recently added") }
        // Four titles in the next two weeks, each with its day in the corner.
        let today = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Today'")).firstMatch
        XCTAssertTrue(today.waitForExistence(timeout: 5), "a card does not say it is today")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Tomorrow'")).firstMatch.exists)
        today.tap()
        // Not in the library: its page is the request side.
        XCTAssertTrue(app.staticTexts["NOT IN YOUR LIBRARY"].waitForExistence(timeout: 10), "the request side did not open")
    }

    @MainActor
    func testComingUpsHeroOffersDetailsAndNoPlay() {
        let app = launch(environment: ["HUB_HERO": "upcoming"])
        let eyebrow = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'COMING UP'")).firstMatch
        XCTAssertTrue(eyebrow.waitForExistence(timeout: 15), "the hero did not show Coming up's first card")
        XCTAssertTrue(eyebrow.label.contains("TODAY"), eyebrow.label)
        XCTAssertTrue(app.buttons["Details"].firstMatch.exists, "Coming up's hero has no Details")
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play' OR label BEGINSWITH 'Resume'")).firstMatch.exists,
                       "a title not in the library cannot be played")
        app.buttons["Details"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["NOT IN YOUR LIBRARY"].waitForExistence(timeout: 10))
    }

    // MARK: Settings > Home

    @MainActor
    func testSettingsHidesARowAndHomeLeavesItOut() {
        let app = launch(section: "settings")
        let pane = app.buttons["pane-home"]
        XCTAssertTrue(pane.waitForExistence(timeout: 15))
        pane.tap()
        let toggle = element(app, "home-row-nextup")
        XCTAssertTrue(toggle.waitForExistence(timeout: 10), "Settings has no Next up")
        XCTAssertEqual(toggle.value as? String, "1")
        toggle.tap()
        XCTAssertEqual(toggle.value as? String, "0", "Next up was not turned off")

        homeTab(app).tap()
        XCTAssertTrue(row(app, "Continue watching").waitForExistence(timeout: 15), "Home did not load")
        XCTAssertFalse(row(app, "Next up").exists, "a row turned off is still on Home")
        XCTAssertTrue(row(app, "Recently added").exists)
    }

    @MainActor
    func testSettingsMovesARowAndHomeFollowsTheNewOrder() {
        let app = launch(section: "settings")
        app.buttons["pane-home"].tap()
        let up = element(app, "home-up-latest")
        XCTAssertTrue(up.waitForExistence(timeout: 10))
        XCTAssertFalse(element(app, "home-up-continue").isEnabled, "the first row can move no higher")
        up.tap()
        up.tap()
        XCTAssertFalse(element(app, "home-up-latest").isEnabled, "Recently added is first now")

        homeTab(app).tap()
        let latest = row(app, "Recently added")
        XCTAssertTrue(latest.waitForExistence(timeout: 15))
        XCTAssertTrue(row(app, "Continue watching").exists)
        XCTAssertLessThan(latest.frame.minY, row(app, "Continue watching").frame.minY, "Recently added is not above Continue watching")
    }

    @MainActor
    func testALibrarysRowIsAddedOffAndTurnedOnForItsNewestTitles() {
        let app = launch(section: "settings")
        app.buttons["pane-home"].tap()
        let anime = element(app, "home-row-library:a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1")
        XCTAssertTrue(anime.waitForExistence(timeout: 10), "Settings does not offer Anime's row")
        XCTAssertEqual(anime.value as? String, "0", "a library's row starts off")
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'From Anime'")).firstMatch.exists)
        anime.tap()
        XCTAssertEqual(anime.value as? String, "1")

        homeTab(app).tap()
        let from = row(app, "From Anime")
        XCTAssertTrue(reveal(from, in: app), "Home has no From Anime row: \(app.staticTexts.allElementsBoundByIndex.prefix(20).map(\.label))")
        // It comes after the built-in rows Home shows.
        let continuing = row(app, "Continue watching")
        if continuing.exists { XCTAssertGreaterThan(from.frame.minY, continuing.frame.minY) }
    }

    // MARK: The last answer (#38)

    @MainActor
    func testHomeOpensWithItsLastAnswerWhileTheNewOneLoads() {
        // First launch: Home loads and its answer is kept.
        let first = launch(environment: ["HUB_FORGET_ANSWERS": "1"])
        XCTAssertTrue(row(first, "Continue watching").waitForExistence(timeout: 15), "Home's rows did not load")
        first.terminate()

        // Second launch: the hub takes six seconds, and the rows are there at once.
        let second = launch(environment: ["HUB_DEMO_DELAY_MS": "6000"])
        XCTAssertTrue(row(second, "Continue watching").waitForExistence(timeout: 4), "the last answer was not shown while waiting")
        let line = second.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Showing the last answer, from '")).firstMatch
        XCTAssertTrue(line.waitForExistence(timeout: 2), "the last answer is not said to be one")
        XCTAssertTrue(line.waitForNonExistence(timeout: 25), "the line stayed after the new answer")
        XCTAssertTrue(row(second, "Continue watching").exists)
    }
}
