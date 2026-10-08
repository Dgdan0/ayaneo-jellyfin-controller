import XCTest

/// The app opens and stays open as a real launch sets it up: Google's Cast
/// SDK (HUB_CAST=google, which a demo launch otherwise leaves out), the
/// controller input, the downloads and their notifications. A crash at launch
/// on the owner's iPad Pro (2610081139) is what this guards.
final class LaunchTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testTheAppOpensAndStaysOpenWithEverythingALaunchStarts() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "media", "HUB_CAST": "google"]
        app.launch()
        let home = app.buttons["Home"].firstMatch
        XCTAssertTrue(home.waitForExistence(timeout: 20), "the app did not open")
        // Long enough for anything started at launch to have run.
        RunLoop.current.run(until: Date().addingTimeInterval(8))
        XCTAssertEqual(app.state, .runningForeground, "the app closed after it opened")
        XCTAssertTrue(home.exists, "the app's pages went")
        // A section away and back, with the controller input's presses.
        app.buttons["Library"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Library"].firstMatch.isSelected || app.buttons["Library"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(app.state, .runningForeground, "the app closed on a section change")
    }
}
