import XCTest

/// The Media side's Activity tab (#29), against the demo hub (`-demo`), which
/// keeps the hub's rules for transfers and limits in the app itself: nothing
/// real is stopped, deleted, removed or slowed.
final class ActivityTabTests: XCTestCase {
    /// The demo's transfers by their ids (`DemoActivity`).
    private let severance = "qbit:" + String(repeating: "a1", count: 20)
    private let expanse = "qbit:" + String(repeating: "07", count: 20)
    private let lastSeen = "qbit:" + String(repeating: "d4", count: 20)

    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(open: String = "") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "activity", "HUB_SIDE": "media", "HUB_OPEN": open]
        app.launch()
        return app
    }

    @MainActor
    private func text(_ app: XCUIApplication, containing words: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }

    /// Every button's label on one line, for a failure's message.
    @MainActor
    private func buttons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.map { $0.label }.filter { !$0.isEmpty }.joined(separator: " | ")
    }

    /// Scrolls the page until `element` is on screen, or gives up.
    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, tries: Int = 6) -> Bool {
        // On screen and clear of the tab bar at the foot, where a tap lands on the bar:
        // the iPhone 17's screen is shorter than the Pro Max's.
        func shown() -> Bool {
            guard element.exists, element.isHittable else { return false }
            let window = app.windows.firstMatch.frame
            return element.frame.maxY < window.maxY - window.height * 0.15 && element.frame.minY > window.minY + 60
        }
        for _ in 0..<tries where !shown() {
            if element.exists && element.frame.minY <= app.windows.firstMatch.frame.minY + 60 {
                app.swipeDown()
            } else {
                app.swipeUp()
            }
        }
        return shown()
    }

    // MARK: The dashboard

    @MainActor
    func testTheDashboardSaysWhatNeedsAttentionAndAnAddressItCannotOpen() {
        let app = launch()
        XCTAssertTrue(text(app, containing: "things need attention").waitForExistence(timeout: 15),
                      "the headline did not load: \(buttons(app))")
        XCTAssertTrue(text(app, containing: "E: is nearly full").exists)
        XCTAssertTrue(text(app, containing: "Storyteller needs setting up").exists)
        XCTAssertTrue(app.buttons["why-\(lastSeen)"].exists, "a broken transfer has no Why is this stuck?")
        // Kavita has no address this device can open: the setting to fix is named instead.
        let kavita = app.buttons["service-kavita"]
        XCTAssertTrue(reveal(kavita, in: app), "Kavita is not among the services: \(buttons(app))")
        kavita.tap()
        XCTAssertTrue(text(app, containing: "set services.kavita.web_url").waitForExistence(timeout: 5))
    }

    @MainActor
    func testWhyIsThisStuckOpensTheTransfersOnItsExplanation() {
        let app = launch()
        let why = app.buttons["why-\(lastSeen)"]
        XCTAssertTrue(why.waitForExistence(timeout: 15), "nothing needs attention: \(buttons(app))")
        XCTAssertTrue(reveal(why, in: app))
        why.tap()
        XCTAssertTrue(text(app, containing: "Why isn't it working?").waitForExistence(timeout: 10), "the explanation did not open")
        XCTAssertTrue(text(app, containing: "Import needs attention").exists)
        XCTAssertTrue(text(app, containing: "Found executable file").exists, "the service's own words are missing")
    }

    // MARK: The transfers

    @MainActor
    func testATransferStopsAndStartsOnThePress() {
        let app = launch(open: "transfers")
        let stop = app.buttons["stop-\(severance)"]
        XCTAssertTrue(stop.waitForExistence(timeout: 15), "Severance cannot be stopped: \(buttons(app))")
        XCTAssertTrue(reveal(stop, in: app))
        stop.tap()
        // The transfer's own state, not the notice, which is gone a few seconds after the answer.
        let start = app.buttons["start-\(severance)"]
        XCTAssertTrue(start.waitForExistence(timeout: 15), "the stop was not sent: a stopped transfer offers no Start")
        XCTAssertFalse(app.buttons["stop-\(severance)"].exists, "the stopped transfer still offers Stop")
        XCTAssertTrue(reveal(start, in: app))
        start.tap()
        XCTAssertTrue(app.buttons["stop-\(severance)"].waitForExistence(timeout: 15), "the start was not sent: it offers no Stop")
        XCTAssertFalse(start.exists, "the started transfer still offers Start")
    }

    @MainActor
    func testDeletingFilesAsksFirstAndCancelLeavesTheTransfer() {
        let app = launch(open: "transfers")
        let more = app.buttons["more-\(expanse)"]
        XCTAssertTrue(more.waitForExistence(timeout: 15), "The Expanse has no More: \(buttons(app))")
        XCTAssertTrue(reveal(more, in: app))
        more.tap()
        let delete = app.buttons["Remove and delete files"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5), "the menu has no Remove and delete files: \(buttons(app))")
        delete.tap()
        // It asks first, with the harmless answer in the cancel role (iOS 26 shows it last).
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 5), "deleting the files did not ask first")
        let answers = alert.buttons.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }
        XCTAssertEqual(Set(answers), ["Cancel", "Remove and delete files"], "the question offers more than its two answers: \(answers)")
        alert.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(app.buttons["more-\(expanse)"].exists, "Cancel removed the transfer")

        app.buttons["more-\(expanse)"].tap()
        app.buttons["Remove and delete files"].tap()
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5))
        app.alerts.firstMatch.buttons["Remove and delete files"].firstMatch.tap()
        XCTAssertTrue(text(app, containing: "Deleted The Expanse").waitForExistence(timeout: 10), "the delete was not sent")
        XCTAssertTrue(app.buttons["more-\(expanse)"].waitForNonExistence(timeout: 10), "the deleted transfer is still listed")
    }

    // MARK: Speed limits

    @MainActor
    func testQuietIsChosenAndItsLimitsEdited() {
        let app = launch(open: "speed")
        let quiet = app.otherElements["limits-mode"].buttons["Quiet"]
        XCTAssertTrue(quiet.waitForExistence(timeout: 15), "the limits did not load: \(buttons(app))")
        quiet.tap()
        XCTAssertTrue(text(app, containing: "Speed limits saved").waitForExistence(timeout: 10), "Quiet was not saved")

        let edit = app.buttons["edit-alternative"]
        XCTAssertTrue(reveal(edit, in: app), "Quiet's limits cannot be edited: \(buttons(app))")
        edit.tap()
        let download = app.textFields["limit-download"]
        XCTAssertTrue(download.waitForExistence(timeout: 5), "the editor did not open")
        download.tap()
        download.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 8) + "100")
        app.buttons["apply-limits"].tap()
        XCTAssertTrue(text(app, containing: "100 KiB/s").waitForExistence(timeout: 10), "the new cap is not shown")
    }
}
