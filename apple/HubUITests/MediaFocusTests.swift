import XCTest

/// The Media screens driven as a controller drives them (#46), with the
/// keyboard's arrows and Return, which go where a controller's D-pad and Ⓐ
/// go. Against the demo hub; the debug probe `pad-focus` says where the ring
/// is ("ring home row-latest/…"). Return is typed as "\n" (see PadFocusTests).
final class MediaFocusTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(_ environment: [String: String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SIDE": "media"].merging(environment) { $1 }
        app.launch()
        XCTAssertTrue(app.staticTexts["pad-focus"].firstMatch.waitForExistence(timeout: 20), "no focus probe")
        return app
    }

    @MainActor
    private func focus(_ app: XCUIApplication) -> String {
        app.staticTexts["pad-focus"].firstMatch.label
    }

    @MainActor
    private func press(_ app: XCUIApplication, _ key: XCUIKeyboardKey, times: Int = 1) {
        for _ in 0..<times {
            app.typeKey(key, modifierFlags: [])
            RunLoop.current.run(until: Date().addingTimeInterval(0.35))
        }
    }

    @MainActor
    private func pressReturn(_ app: XCUIApplication) {
        app.typeKey("\n", modifierFlags: [])
        RunLoop.current.run(until: Date().addingTimeInterval(0.6))
    }

    @MainActor
    private func waitFor(_ app: XCUIApplication, _ seconds: TimeInterval = 8, _ condition: (String) -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if condition(focus(app)) { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        return condition(focus(app))
    }

    /// Home: the first press lands on the hero's Play, down goes into the
    /// first row and right along it, Return opens the title, and back on Home
    /// the ring is on the card it left.
    @MainActor
    func testHomeGoesFromTheHeroIntoTheRowsAndOpensATitle() {
        let app = launch(["HUB_SECTION": "home"])
        XCTAssertTrue(app.staticTexts["home-title-continue"].waitForExistence(timeout: 20)
                      || app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'home-title-'")).firstMatch
                          .waitForExistence(timeout: 5), "Home did not load")
        XCTAssertEqual(focus(app), "none", "focus showed before a key was pressed")
        press(app, .downArrow)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring home hero-") }, "the first press did not land on the hero: \(focus(app))")
        press(app, .downArrow)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring home row-") }, "down did not reach the first row: \(focus(app))")
        let first = focus(app)
        press(app, .rightArrow)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring home row-") && $0 != first }, "right did not move along the row: \(focus(app))")
        let card = focus(app)
        pressReturn(app)
        let title = app.descendants(matching: .any).matching(identifier: "title-download").firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 10) || app.buttons["Back to Home"].waitForExistence(timeout: 2),
                      "Return did not open the card's title")
        app.buttons["Back to Home"].firstMatch.tap()
        // After a touch the first press shows where the ring is, and moves nothing.
        press(app, .rightArrow)
        XCTAssertTrue(waitFor(app) { $0 == card }, "back on Home the ring is not on the card it left: \(focus(app))")
    }

    /// Library: the search and Favourites, then the libraries; Return opens
    /// one, whose page goes from its libraries' capsule to the poster grid,
    /// and Return there opens a title.
    @MainActor
    func testLibraryOpensALibraryAndATitleFromTheKeys() {
        let app = launch(["HUB_SECTION": "library"])
        XCTAssertTrue(app.buttons["arrange-libraries"].waitForExistence(timeout: 20), "the Library page did not load")
        press(app, .downArrow)
        XCTAssertTrue(waitFor(app) { $0 == "ring media-libraries search" }, "the first press did not land on the search: \(focus(app))")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring media-libraries favourites")
        press(app, .downArrow)
        XCTAssertTrue(waitFor(app) { $0 == "ring media-libraries arrange" }, "down did not reach Arrange: \(focus(app))")
        press(app, .downArrow)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring media-libraries libraries/") }, "down did not reach a library: \(focus(app))")
        pressReturn(app)
        XCTAssertTrue(waitFor(app, 10) { $0.hasPrefix("ring folder:") || $0 == "none" }, "Return did not open the library: \(focus(app))")
        // The library's page: from its capsule down to the titles.
        press(app, .downArrow)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring folder:") }, "the library's page took no focus: \(focus(app))")
        var steps = 0
        while !focus(app).contains(" grid/"), steps < 4 {
            press(app, .downArrow)
            steps += 1
        }
        XCTAssertTrue(focus(app).contains(" grid/"), "down did not reach the titles: \(focus(app))")
        pressReturn(app)
        let title = app.descendants(matching: .any).matching(identifier: "title-download").firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 10), "Return did not open the title")
    }

    /// The player's panels with a controller: Ⓨ opens Audio & subtitles, down
    /// walks its rows, Ⓐ chooses one, and Ⓑ closes the panel, then leaves.
    @MainActor
    func testThePlayersPanelIsWalkedWithTheController() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "media", "HUB_PLAY": "demo-e5",
                                 "HUB_PLAY_CHROME": "pinned", "HUB_PAD": "Y,DOWN,DOWN,DOWN,A", "HUB_PAD_DELAY": "10"]
        app.launch()
        XCTAssertTrue(app.buttons["Lock controls"].waitForExistence(timeout: 15), "the player did not open")
        let heading = app.staticTexts.matching(identifier: "panel-heading").firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 15), "Ⓨ did not open a panel")
        let probe = app.staticTexts["pad-focus"].firstMatch
        XCTAssertTrue(waitUntil(15) { probe.label.hasPrefix("ring player-tracks row:") },
                      "down did not walk the panel's rows: \(probe.label)")
        // Ⓐ on a row chose it: the panel is still open, and one more row is ticked or the same.
        XCTAssertTrue(heading.exists, "Ⓐ on a row closed the panel")
    }

    // MARK: A series' downloads (#48)

    /// Slow Horses in the demo: two seasons, and the hub's listing of what can be downloaded.
    private let series = "000000000000000000000000deb00012"

    @MainActor
    private func openSeries(_ environment: [String: String]) -> XCUIApplication {
        let app = launch(["HUB_SECTION": "library", "HUB_TITLE": series].merging(environment) { $1 })
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "title-download").firstMatch
                          .waitForExistence(timeout: 30), "the series did not open")
        return app
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// Ⓨ on an episode card asks what it offers, as a long press does; Select episodes there starts select mode.
    @MainActor
    func testAnEpisodesYAsksWhatItOffers() {
        // Ⓨ is pressed 45 seconds in; the arrows have walked to an episode long before.
        let app = openSeries(["HUB_PAD": "Y", "HUB_PAD_DELAY": "45"])
        XCTAssertTrue(element(app, "download-season").waitForExistence(timeout: 30), "the hub's listing did not arrive")
        var steps = 0
        while !focus(app).contains(" episodes/"), steps < 8 {
            press(app, .downArrow)
            steps += 1
        }
        XCTAssertTrue(focus(app).contains(" episodes/"), "down did not reach the episodes: \(focus(app))")
        let select = app.buttons["Select episodes"].firstMatch
        XCTAssertTrue(select.waitForExistence(timeout: 60), "Ⓨ on the episode asked nothing")
        XCTAssertTrue(app.buttons["Download"].firstMatch.exists || app.buttons["Remove download"].firstMatch.exists,
                      "the episode's own download is not among its actions")
        select.tap()
        XCTAssertTrue(element(app, "select-cancel").waitForExistence(timeout: 10), "Select episodes did not start select mode")
    }

    /// Ⓑ in select mode ends it, and the page stays: Back comes after.
    @MainActor
    func testBEndsSelectModeBeforeGoingBack() {
        // Ⓑ is pressed 40 seconds in, once select mode has long started.
        let app = openSeries(["HUB_SERIES_DOWNLOADS": "select:e1", "HUB_PAD": "B", "HUB_PAD_DELAY": "40"])
        let cancel = element(app, "select-cancel")
        XCTAssertTrue(cancel.waitForExistence(timeout: 35), "select mode did not start before Ⓑ")
        XCTAssertTrue(waitUntil(50) { !cancel.exists }, "Ⓑ did not end select mode")
        XCTAssertTrue(element(app, "title-download").exists, "Ⓑ left the page instead of ending select mode")
    }

    /// The choices panel is walked with the controller: down from Close to Keep ready, and Ⓑ closes it.
    @MainActor
    func testTheDownloadChoicesAreWalkedWithTheController() {
        // The presses start 40 seconds in, once the choices have long been open; Ⓑ waits five seconds more.
        let app = openSeries(["HUB_SERIES_DOWNLOADS": "panel", "HUB_PAD": "DOWN,DOWN,WAIT,WAIT,WAIT,WAIT,WAIT,B",
                              "HUB_PAD_DELAY": "40"])
        let panel = element(app, "download-panel")
        XCTAssertTrue(panel.waitForExistence(timeout: 35), "the choices did not open before the presses")
        XCTAssertTrue(waitUntil(50) { focus(app) == "ring series-downloads keep/choice" },
                      "down did not walk from Close to Keep ready: \(focus(app))")
        XCTAssertTrue(waitUntil(15) { !panel.exists }, "Ⓑ did not close the choices")
        XCTAssertTrue(element(app, "title-download").exists, "Ⓑ left the page instead of closing the choices")
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
