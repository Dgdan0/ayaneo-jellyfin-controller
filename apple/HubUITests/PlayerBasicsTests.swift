import XCTest

/// The player's basics (#33) against the demo hub (`-demo`: Apple's public
/// test stream as Bleach S1E5, an intro from the start to 1:25 and credits
/// from 9:20; nothing real is played or written): aspect, skipping the intro
/// by itself, when the next episode's card comes, and the keyboard.
final class PlayerBasicsTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    /// The player open on the demo's Bleach S1E5, its chrome held up. Settings
    /// › Playback for this launch only, as arguments, never saved.
    @MainActor
    private func launchPlaying(_ settings: [String: String] = [:], environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"] + settings.flatMap { ["-" + $0.key, $0.value] }
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "media", "HUB_PLAY": "demo-e5", "HUB_PLAY_CHROME": "pinned"]
            .merging(environment) { $1 }
        app.launch()
        XCTAssertTrue(app.buttons["Lock controls"].waitForExistence(timeout: 15), "the player did not open")
        XCTAssertTrue(app.staticTexts["Bleach"].waitForExistence(timeout: 10), "the player has no title")
        return app
    }

    @MainActor
    private func keep(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
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

    @MainActor
    private func buttons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.joined(separator: " | ")
    }

    // MARK: Aspect

    /// This video › Aspect: Fit until changed, then Zoom, and This video says so.
    @MainActor
    func testThisVideosAspectFitsUntilZoomIsChosen() {
        let app = launchPlaying()
        app.buttons["This video"].firstMatch.tap()
        let aspect = app.buttons["player-aspect"]
        XCTAssertTrue(aspect.waitForExistence(timeout: 5), "This video has no Aspect: \(buttons(app))")
        XCTAssertTrue(aspect.label.contains("Fit"), "a video does not open at Fit: \(aspect.label)")
        aspect.tap()
        let zoom = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Zoom'")).firstMatch
        XCTAssertTrue(zoom.waitForExistence(timeout: 5), "Aspect offers no Zoom: \(buttons(app))")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Original aspect'")).firstMatch.exists)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Fill'")).firstMatch.exists)
        zoom.tap()
        XCTAssertTrue(aspect.waitForExistence(timeout: 5), "choosing went nowhere")
        XCTAssertTrue(waitUntil(5) { aspect.label.contains("Zoom") }, "This video says \(aspect.label)")
        app.buttons["Close"].firstMatch.tap()
        // Upright, Fit is a band across the middle; Zoom fills the screen's height.
        _ = waitUntil(2) { false }
        keep(app, "player-zoom")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5))
    }
}
