import XCTest

/// Every screen works both ways up (APPLE_PLAN.md), and turning the device
/// keeps your place: the section, the page pushed in it, and Back to the page
/// under it. Against the demo hub, so nothing real changes.
final class TurnTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testTurningKeepsThePageYouWereOn() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "services", "HUB_SIDE": "media"]
        app.launch()

        let monitor = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Server monitor'")).firstMatch
        XCTAssertTrue(monitor.waitForExistence(timeout: 15))
        monitor.tap()
        let back = app.buttons["Back to Services"]
        XCTAssertTrue(back.waitForExistence(timeout: 5), "the Server monitor page did not open")

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(back.waitForExistence(timeout: 5), "turning sideways left the page")
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(back.waitForExistence(timeout: 5), "turning back upright left the page")

        back.tap()
        XCTAssertTrue(monitor.waitForExistence(timeout: 5), "Back did not return to Services")
    }
}
