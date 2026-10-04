import XCTest

/// Turns the simulator itself, landscape or upright (`scripts/mac.sh turn`),
/// for screenshots of every screen both ways up (APPLE_PLAN.md). An iPad app
/// that shares the screen with others cannot turn itself, so the device is
/// turned instead; it stays turned when the test ends.
final class DeviceTurn: XCTestCase {
    @MainActor
    func testTurn() {
        let wanted = ProcessInfo.processInfo.environment["HUB_TURN"] ?? "portrait"
        XCUIDevice.shared.orientation = wanted == "landscape" ? .landscapeLeft : .portrait
    }
}
