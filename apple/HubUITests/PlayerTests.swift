import XCTest

/// Leaving the player is how a hub session ends, so each way out is driven
/// here, against the demo hub (`-demo`: Apple's public test stream, no real
/// hub and no watch history): Back, and the app going to the background. Then
/// its panels, through a turn of the device.
final class PlayerTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        // The simulator keeps the way it was turned; later tests and
        // screenshots expect it upright.
        XCUIDevice.shared.orientation = .portrait
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

    /// Without picture in picture (the iPhone simulator has none, and a
    /// paused video never starts it), the background ends playback.
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

    /// A panel opened upright stays open and usable when the device turns,
    /// and the video plays on under it.
    @MainActor
    func testAPanelStaysOpenAndUsableWhenTheDeviceTurns() {
        let app = launchPlaying()
        // Upright on a phone the three pills are round icons, never hidden.
        app.buttons["Audio & subtitles"].firstMatch.tap()
        // By its identifier: turned, a wide phone shows the pill of that name.
        let heading = app.staticTexts.matching(identifier: "panel-heading").firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 5), "the panel did not open")
        XCTAssertEqual(heading.label, "Audio & subtitles")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(heading.waitForExistence(timeout: 5), "the panel closed when the device turned")
        let hebrew = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Hebrew")).firstMatch
        XCTAssertTrue(hebrew.waitForExistence(timeout: 5))
        hebrew.tap()
        XCTAssertTrue(NSPredicate(format: "isSelected == true").evaluate(with: hebrew)
                      || hebrew.wait(for: \.isSelected, toEqual: true, timeout: 5), "the subtitles chosen are not ticked")
        app.buttons["Close"].firstMatch.tap()
        XCTAssertTrue(heading.waitForNonExistence(timeout: 5), "the panel did not close")
        XCTAssertTrue(app.buttons["Pause"].waitForExistence(timeout: 5), "the video stopped when the device turned")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5))
    }
}
