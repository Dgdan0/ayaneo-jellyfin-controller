import XCTest

/// Discover, a title's way in, the request form and Upcoming (#17), against
/// the demo hub (`-demo`), so nothing is requested anywhere: the form is
/// opened and closed.
final class DiscoverTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "discover", "HUB_SIDE": "media"]
        app.launch()
        return app
    }

    @MainActor
    func testATitleYouDoNotHaveOpensOnItsPipelineAndTheFormClosesUnsent() {
        let app = launch()
        let card = app.buttons["Dune: Part Two"].firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 15), "Discover's rows did not load")
        card.tap()

        XCTAssertTrue(app.staticTexts["NOT IN YOUR LIBRARY"].waitForExistence(timeout: 10), "the title page did not open")
        XCTAssertTrue(app.otherElements["Request, not yet"].exists || app.staticTexts["Request"].exists,
                      "the pipeline is missing")
        XCTAssertTrue(app.buttons["Find release"].exists)
        let request = app.buttons["Request"].firstMatch
        XCTAssertTrue(request.exists, "Request is missing on a title not in the library")
        request.tap()

        XCTAssertTrue(app.staticTexts["Request Dune: Part Two"].waitForExistence(timeout: 10), "the form did not open")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Quality profile")).firstMatch
            .waitForExistence(timeout: 5), "the form has no quality profile")
        app.buttons["Close"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Request Dune: Part Two"].waitForNonExistence(timeout: 5), "the form did not close")
        XCTAssertTrue(app.buttons["Request"].firstMatch.exists, "closing the form requested the title")
    }

    @MainActor
    func testUpcomingShowsTheWeeksReleasesByDay() {
        let app = launch()
        let upcoming = app.buttons["Upcoming"].firstMatch
        XCTAssertTrue(upcoming.waitForExistence(timeout: 15))
        upcoming.tap()
        let week = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "This week")).firstMatch
        XCTAssertTrue(week.waitForExistence(timeout: 10), "the weeks are missing")
        let release = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Last Seen")).firstMatch
        XCTAssertTrue(release.waitForExistence(timeout: 10), "the week's releases did not load")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Season 1 · 2 episodes")).firstMatch.exists,
                      "a season's episodes on one day are not one row")
    }
}
