import XCTest

/// The shell hides the system's navigation bar, which also turns off UIKit's
/// swipe from the left edge to go back; `SwipeBack` puts it back. This drives
/// that swipe against the demo hub, so nothing real changes.
final class SwipeBackTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testASwipeFromTheLeftEdgeGoesBack() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "services", "HUB_SIDE": "media"]
        app.launch()

        // Services' Server monitor card pushes a page.
        let monitor = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Server monitor'")).firstMatch
        XCTAssertTrue(monitor.waitForExistence(timeout: 15))
        monitor.tap()
        let back = app.buttons["Back to Services"]
        XCTAssertTrue(back.waitForExistence(timeout: 5))

        // From the very edge of the screen, most of the way across.
        let edge = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.5))
        edge.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)))

        XCTAssertTrue(back.waitForNonExistence(timeout: 5), "the swipe left the pushed page on screen")
        XCTAssertTrue(monitor.waitForExistence(timeout: 5))
    }
}
