import XCTest

/// A game controller outside the readers, against the demo hub. A test has no
/// controller, so `HUB_PAD` presses its buttons by name, one a second,
/// `HUB_PAD_DELAY` seconds after launch (Debug builds); they reach the app
/// where a controller's do (`PadRouter.dispatch`).
final class ControllerTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private let inception = "000000000000000000000000deb0000e"

    @MainActor
    private func launch(_ environment: [String: String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SIDE": "media"].merging(environment) { $1 }
        app.launch()
        return app
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// The section tab of that name is the one shown.
    @MainActor
    private func shows(_ app: XCUIApplication, _ section: String) -> Bool {
        waitUntil(15) { app.buttons[section].firstMatch.isSelected }
    }

    /// R1 twice from Home is Library; L1 from Home goes round to Activity.
    @MainActor
    func testTheShouldersGoRoundTheSections() {
        var app = launch(["HUB_SECTION": "home", "HUB_PAD": "R1,R1"])
        XCTAssertTrue(shows(app, "Library"), "R1 twice from Home did not reach Library")
        app.terminate()
        app = launch(["HUB_SECTION": "home", "HUB_PAD": "L1"])
        XCTAssertTrue(shows(app, "Activity"), "L1 from Home did not go round to Activity")
    }

    /// Ⓑ goes back a page, and closes the profile picker.
    @MainActor
    func testBGoesBackAPageAndClosesTheProfilePicker() {
        var app = launch(["HUB_SECTION": "library", "HUB_TITLE": inception, "HUB_PAD": "B", "HUB_PAD_DELAY": "6"])
        let download = element(app, "title-download")
        XCTAssertTrue(download.waitForExistence(timeout: 15), "the film's page did not open")
        XCTAssertTrue(download.waitForNonExistence(timeout: 10), "Ⓑ did not go back from the film's page")
        XCTAssertTrue(shows(app, "Library"), "Ⓑ left Library")
        app.terminate()

        app = launch(["HUB_SECTION": "home", "HUB_SHEET": "profiles", "HUB_PAD": "B", "HUB_PAD_DELAY": "6"])
        // A profile's tile, the same in the iPad's picker and the phone's sheet.
        let picker = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Adirimo")).firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 15), "the profile picker did not open")
        XCTAssertTrue(picker.waitForNonExistence(timeout: 10), "Ⓑ did not close the profile picker")
    }

    /// In the player: Ⓐ pauses, Ⓨ opens Audio & subtitles, Ⓑ closes it and then leaves.
    @MainActor
    func testThePlayerTakesTheController() {
        let playing = ["HUB_SECTION": "home", "HUB_PLAY": "demo-e5", "HUB_PLAY_CHROME": "pinned", "HUB_PAD_DELAY": "7"]
        var app = launch(playing.merging(["HUB_PAD": "A,Y"]) { $1 })
        XCTAssertTrue(app.buttons["Pause"].waitForExistence(timeout: 15), "the video did not play")
        let heading = app.staticTexts.matching(identifier: "panel-heading").firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 15), "Ⓨ did not open a panel")
        XCTAssertEqual(heading.label, "Audio & subtitles")
        XCTAssertTrue(app.buttons["Play"].exists, "Ⓐ did not pause the video")
        app.buttons["Close"].firstMatch.tap()
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 10), "Back left the player open")
        app.terminate()

        app = launch(playing.merging(["HUB_PAD": "Y,B,B"]) { $1 })
        let lock = app.buttons["Lock controls"]
        XCTAssertTrue(lock.waitForExistence(timeout: 15), "the player did not open")
        let panel = app.staticTexts.matching(identifier: "panel-heading").firstMatch
        XCTAssertTrue(panel.waitForExistence(timeout: 15), "Ⓨ did not open a panel")
        XCTAssertTrue(panel.waitForNonExistence(timeout: 5), "Ⓑ did not close the panel")
        XCTAssertTrue(lock.waitForNonExistence(timeout: 5), "Ⓑ again did not leave the player")
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
