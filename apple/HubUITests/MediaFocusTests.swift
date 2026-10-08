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
}
