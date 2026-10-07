import XCTest

/// A title's watched and favourite (#9), against the demo hub (`-demo`),
/// which answers as the hub does: a change must be exactly one of the two, as
/// a true or a false, and it stays. Nothing real changes; toggling a real
/// profile's state is left to the owner, on a device.
final class TitleStateTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func poster(_ app: XCUIApplication, _ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
    }

    /// Every button's label on one line, for a failure's message.
    @MainActor
    private func buttons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.map { $0.label }.filter { !$0.isEmpty }.joined(separator: " | ")
    }

    private func waitUntilEnabled(_ element: XCUIElement, timeout: TimeInterval = 5) -> Bool {
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: element)
        return XCTWaiter().wait(for: [enabled], timeout: timeout) == .completed
    }

    /// A cast portrait the hub names on TMDB opens their films and series
    /// (#27); one it cannot name opens nothing.
    @MainActor
    func testACastPortraitOpensTheFilmography() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "library", "HUB_SIDE": "media", "HUB_OPEN": "Movies"]
        app.launch()
        let card = poster(app, "Gran Torino")
        XCTAssertTrue(card.waitForExistence(timeout: 15), "Movies did not load")
        card.tap()
        let cast = app.buttons["Cast"]
        XCTAssertTrue(cast.waitForExistence(timeout: 10), "the title has no Cast tab: " + buttons(app))
        cast.tap()
        let named = poster(app, "Rebecca Ferguson")
        XCTAssertTrue(named.waitForExistence(timeout: 5), "the named portrait is not a button: " + buttons(app))
        XCTAssertFalse(poster(app, "A Face in the Crowd").exists, "a portrait with no TMDB id offers an action")
        named.tap()
        XCTAssertTrue(app.buttons["Back to Gran Torino"].waitForExistence(timeout: 10), "the filmography did not open")
        XCTAssertTrue(app.staticTexts["Rebecca Ferguson"].waitForExistence(timeout: 10), "the filmography is not theirs")
    }

    @MainActor
    func testWatchedAndFavouriteChangeAndStay() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "library", "HUB_SIDE": "media", "HUB_OPEN": "Movies"]
        app.launch()
        let card = poster(app, "Gran Torino")
        XCTAssertTrue(card.waitForExistence(timeout: 15), "Movies did not load")
        card.tap()

        let watched = app.buttons["Mark watched"]
        XCTAssertTrue(watched.waitForExistence(timeout: 10), "the title page did not open")
        watched.tap()
        let unwatch = app.buttons["Mark unwatched"]
        XCTAssertTrue(unwatch.waitForExistence(timeout: 5), "marking it watched did not show")
        // A change the hub refused would put the page back once it answered.
        let favourite = app.buttons["Favourite"]
        XCTAssertTrue(waitUntilEnabled(favourite), "the save did not finish")
        XCTAssertTrue(unwatch.exists, "the hub refused marking it watched")
        favourite.tap()
        let unstar = app.buttons["Remove from favourites"]
        XCTAssertTrue(unstar.waitForExistence(timeout: 5), "starring it did not show")
        XCTAssertTrue(waitUntilEnabled(unstar), "the save did not finish")

        // Back to the library and in again: both were kept.
        app.buttons["Back to Movies"].tap()
        // Back on Movies once its own back pill shows, not mid-way.
        XCTAssertTrue(app.buttons["Back to Library"].waitForExistence(timeout: 10), "Back did not return to Movies")
        XCTAssertTrue(card.waitForExistence(timeout: 10))
        card.tap()
        XCTAssertTrue(app.buttons["Mark unwatched"].waitForExistence(timeout: 10), "watched was not kept: " + buttons(app))
        XCTAssertTrue(app.buttons["Remove from favourites"].exists, "the favourite was not kept")

        // Put back.
        app.buttons["Mark unwatched"].tap()
        XCTAssertTrue(app.buttons["Mark watched"].waitForExistence(timeout: 5))
        XCTAssertTrue(waitUntilEnabled(app.buttons["Remove from favourites"]))
        app.buttons["Remove from favourites"].tap()
        XCTAssertTrue(app.buttons["Favourite"].waitForExistence(timeout: 5))
    }
}
