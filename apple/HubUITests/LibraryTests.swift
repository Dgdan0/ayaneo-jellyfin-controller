import XCTest

/// A library's own search (#14), against the demo hub (`-demo`), which
/// answers as the hub does: a search keeps to the library it is given.
final class LibraryTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(opening library: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "library", "HUB_SIDE": "media", "HUB_OPEN": library]
        app.launch()
        return app
    }

    private func poster(_ app: XCUIApplication, _ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
    }

    /// Every button's label on one line, for a failure's message.
    @MainActor
    private func buttons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.map { $0.label }.filter { !$0.isEmpty }.joined(separator: " | ")
    }

    @MainActor
    func testALibrarysOwnSearchLooksOnlyInsideIt() {
        let app = launch(opening: "Anime")
        let search = app.buttons["Search Anime"]
        XCTAssertTrue(search.waitForExistence(timeout: 15), "Anime's page has no search")
        XCTAssertTrue(app.staticTexts["5 titles"].waitForExistence(timeout: 10), "Anime's titles did not load")
        search.tap()

        let field = app.textFields["Search Anime"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "the search did not open")
        field.tap()
        field.typeText("the")
        XCTAssertTrue(app.staticTexts["2 matches in Anime"].waitForExistence(timeout: 10), "the search did not keep to Anime")
        XCTAssertTrue(poster(app, "Avatar: The Last Airbender").exists, buttons(app))
        XCTAssertTrue(poster(app, "Code Geass").exists, buttons(app))
        XCTAssertFalse(poster(app, "The Dark Knight").exists, "a film from another library came back")

        // Pressed again, the search closes and the library's titles come back.
        search.tap()
        XCTAssertTrue(app.staticTexts["5 titles"].waitForExistence(timeout: 10), "closing the search kept the matches")

        // The Library page's own search still looks through everything.
        app.buttons["Back to Library"].tap()
        let root = app.textFields["Search your Jellyfin library"]
        XCTAssertTrue(root.waitForExistence(timeout: 10))
        root.tap()
        root.typeText("the")
        XCTAssertTrue(app.staticTexts["6 matches"].waitForExistence(timeout: 10), "the Library page's search was kept to one library")
        XCTAssertTrue(poster(app, "The Dark Knight").exists)
    }
}
