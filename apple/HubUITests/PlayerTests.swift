import XCTest

/// Leaving the player is how a hub session ends, so each way out is driven
/// here, against the demo hub (`-demo`: Apple's public test stream, no real
/// hub and no watch history): Back, and the app going to the background.
final class PlayerTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// The app with the player open on the demo's Bleach S1E5, its chrome held up.
    @MainActor
    private func launchPlaying() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "media",
                                 "HUB_PLAY": "demo-e5", "HUB_PLAY_CHROME": "pinned"]
        app.launch()
        XCTAssertTrue(app.buttons["Lock controls"].waitForExistence(timeout: 15), "the player did not open")
        XCTAssertTrue(app.staticTexts["Bleach"].exists)
        return app
    }

    @MainActor
    func testBackLeavesThePlayerForThePageUnderIt() {
        let app = launchPlaying()
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5), "Back left the player open")
        // The shell is there again, and VoiceOver can reach it.
        XCTAssertTrue(app.buttons["Home"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testGoingToTheBackgroundLeavesThePlayer() {
        let app = launchPlaying()
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackgroundSuspended, timeout: 15)
                      || app.state == .runningBackground, "the app did not go to the background")
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        XCTAssertTrue(app.buttons["Home"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Lock controls"].exists, "the player was still open after the background")
    }
}
