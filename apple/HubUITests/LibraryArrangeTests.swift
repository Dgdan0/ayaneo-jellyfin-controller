import XCTest

/// Arranging libraries (#15), against the demo hub (`-demo`), whose order a
/// save changes as the real hub's does: Arrange, a library dragged onto
/// another takes its place, Done, and the page reads the order back.
final class LibraryArrangeTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testADraggedLibraryTakesAnothersPlaceAndStaysThere() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "library", "HUB_SIDE": "media"]
        app.launch()

        let arrange = app.buttons["arrange-libraries"]
        XCTAssertTrue(arrange.waitForExistence(timeout: 15), "the Library page has no Arrange")
        // A to Z until something is arranged.
        XCTAssertEqual(tileNames(app).first, "Anime")
        arrange.tap()

        // The second tile onto the first: both on screen, even on a phone,
        // where the tiles stack one under another.
        let second = app.buttons["Marvel Movies, Movie library"]
        let first = app.buttons["Anime, TV library"]
        XCTAssertTrue(second.waitForExistence(timeout: 5))
        // Held until the system lifts it, moved slowly, and held over the other
        // tile, so the drop has time to see it arrive.
        second.press(forDuration: 1.2, thenDragTo: first, withVelocity: .slow, thenHoldForDuration: 1.0)
        XCTAssertTrue(waitForFirst("Marvel Movies", in: app),
                      "the dragged library did not take the first place: \(tileNames(app))")

        arrange.tap() // Done
        // Read back from the demo hub, which kept the order.
        XCTAssertTrue(waitForFirst("Marvel Movies", in: app), "the order did not stay after Done: \(tileNames(app))")
    }

    /// The libraries in the order on screen, from one snapshot: reading each
    /// button in turn raced the tiles sliding into their new places.
    @MainActor
    private func tileNames(_ app: XCUIApplication) -> [String] {
        guard let snapshot = try? app.snapshot() else { return [] }
        var names: [String] = []
        func walk(_ node: any XCUIElementSnapshot) {
            let label = node.label
            if node.elementType == .button, label.hasSuffix(" library") || label.hasSuffix(", Library") {
                names.append(label.components(separatedBy: ", ").first ?? label)
            }
            node.children.forEach(walk)
        }
        walk(snapshot)
        return names
    }

    @MainActor
    private func waitForFirst(_ name: String, in app: XCUIApplication) -> Bool {
        let deadline = Date().addingTimeInterval(8)
        while Date() < deadline {
            if tileNames(app).first == name { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return false
    }
}
