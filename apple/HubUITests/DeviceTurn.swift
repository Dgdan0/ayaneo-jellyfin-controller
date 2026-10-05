import XCTest

/// Turns the simulator itself, landscape or upright (`scripts/mac.sh turn`),
/// for screenshots of every screen both ways up (APPLE_PLAN.md). An iPad app
/// that shares the screen with others cannot turn itself, so the device is
/// turned instead; it stays turned when the test ends.
final class DeviceTurn: XCTestCase {
    @MainActor
    func testTurn() {
        let wanted: UIDeviceOrientation = ProcessInfo.processInfo.environment["HUB_TURN"] == "landscape"
            ? .landscapeLeft : .portrait
        let before = XCUIDevice.shared.orientation
        // A new test session can believe the device is upright when it is not,
        // and then asking for upright does nothing (seen with Xcode 27: iPads
        // left sideways after `turn portrait`). Going the other way first
        // makes the last step a real turn, and the waits let each one finish
        // before the session ends.
        XCUIDevice.shared.orientation = wanted == .portrait ? .landscapeLeft : .portrait
        Thread.sleep(forTimeInterval: 1)
        XCUIDevice.shared.orientation = wanted
        Thread.sleep(forTimeInterval: 2)
        XCTAssertEqual(XCUIDevice.shared.orientation.rawValue, wanted.rawValue, "the device was \(before.rawValue)")
    }
}
