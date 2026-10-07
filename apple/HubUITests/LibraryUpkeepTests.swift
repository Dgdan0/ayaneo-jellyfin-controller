import XCTest

/// Keeping the library in order (#34), against the demo hub (`-demo`), which
/// answers as the hub does: a search gives each subtitle a ticket that one
/// download spends, a deletion is previewed first and confirmed by a one-use
/// ticket, and nothing real is downloaded, grabbed or deleted.
final class LibraryUpkeepTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// The demo's Bleach (a series) and its second episode: Jellyfin ids are 32 hex characters.
    private let bleach = "000000000000000000000000deb00003"
    private var episode: String { bleach + "-e2" }

    @MainActor
    private func launch(section: String = "library", side: String = "media", open: String = "",
                        title: String = "") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": section, "HUB_SIDE": side, "HUB_OPEN": open, "HUB_TITLE": title]
        app.launch()
        return app
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    @MainActor
    private func button(_ app: XCUIApplication, startingWith words: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", words)).firstMatch
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

    /// The round menu on a title's page, opened to the entry named.
    @MainActor
    private func chooseFromMore(_ app: XCUIApplication, _ entry: String) {
        let more = element(app, "title-more")
        XCTAssertTrue(more.waitForExistence(timeout: 20), "the title has no More menu: \(buttons(app))")
        more.tap()
        let item = app.buttons[entry].firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 5), "the More menu has no \(entry): \(buttons(app))")
        item.tap()
    }

    @MainActor
    private func back(_ app: XCUIApplication) {
        let pill = button(app, startingWith: "Back")
        XCTAssertTrue(pill.waitForExistence(timeout: 5), "no way back: \(buttons(app))")
        pill.tap()
    }

    // MARK: Subtitles

    /// Search, keep to one language, open one, download it, and see it listed
    /// on the page and then in the player's own panel.
    @MainActor
    func testASubtitleIsSearchedFilteredDownloadedAndListedInThePlayer() {
        let app = launch(title: episode)
        chooseFromMore(app, "Find subtitles")

        let library = element(app, "subtitles-library")
        XCTAssertTrue(library.waitForExistence(timeout: 15), "the subtitles page did not open")
        XCTAssertTrue(text(app, containing: "A Second Look").exists || text(app, containing: "Bleach").exists,
                      "the page does not say which title it is for")
        XCTAssertEqual(library.label, "Library · 1 installed subtitle track")
        XCTAssertTrue(text(app, containing: "English · Embedded").exists, "the file's own track is not listed")

        let search = element(app, "subtitles-search")
        XCTAssertTrue(search.waitForExistence(timeout: 5), "no search for a connection that may download")
        search.tap()
        let arabic = button(app, startingWith: "Arabic ·")
        XCTAssertTrue(arabic.waitForExistence(timeout: 20), "the search found nothing: \(buttons(app))")
        XCTAssertTrue(button(app, startingWith: "Hebrew ·").exists && button(app, startingWith: "English ·").exists)

        // One language on its pill hides the others.
        let pill = app.buttons["Arabic"].firstMatch
        XCTAssertTrue(pill.exists, "no pill for Arabic: \(buttons(app))")
        pill.tap()
        XCTAssertTrue(NSPredicate(format: "exists == false").evaluate(with: button(app, startingWith: "Hebrew ·"))
                      || button(app, startingWith: "Hebrew ·").waitForNonExistence(timeout: 5), "the Hebrew results stayed")
        XCTAssertTrue(arabic.exists)

        // Opened, a result says what matched, then offers the download.
        arabic.tap()
        XCTAssertTrue(text(app, containing: "Doesn't match").exists || app.staticTexts["DOESN'T MATCH"].waitForExistence(timeout: 5),
                      "the result did not open")
        let download = element(app, "subtitle-download")
        XCTAssertTrue(download.waitForExistence(timeout: 5))
        download.tap()

        let saved = text(app, containing: "Saved · Jellyfin is refreshing")
        XCTAssertTrue(saved.waitForExistence(timeout: 20), "the download did not report: \(buttons(app))")
        XCTAssertTrue(NSPredicate(format: "label == %@", "Library · 2 installed subtitle tracks").evaluate(with: element(app, "subtitles-library"))
                      || element(app, "subtitles-library").wait(for: \.label, toEqual: "Library · 2 installed subtitle tracks", timeout: 10),
                      "the new track is not among the installed: \(element(app, "subtitles-library").label)")
        XCTAssertTrue(text(app, containing: "Arabic · Podnapisi").waitForExistence(timeout: 5), "the new track is not listed")

        // And the player lists it beside the file's own.
        back(app)
        let play = button(app, startingWith: "Play")
        XCTAssertTrue(play.waitForExistence(timeout: 10), "the episode has no Play: \(buttons(app))")
        play.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForExistence(timeout: 20), "the player did not open")
        app.buttons["Audio & subtitles"].firstMatch.tap()
        let listed = button(app, startingWith: "Arabic")
        XCTAssertTrue(listed.waitForExistence(timeout: 10), "the downloaded subtitle is not in the player: \(buttons(app))")
        app.buttons["Close"].firstMatch.tap()
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 10))
    }

    /// The results are only for the page they came from: the ticket is spent,
    /// so a second search is needed for another subtitle.
    @MainActor
    func testTheResultsGoAfterADownloadAndAnotherSearchBringsNewOnes() {
        let app = launch(title: episode)
        chooseFromMore(app, "Find subtitles")
        element(app, "subtitles-search").tap()
        let hebrew = button(app, startingWith: "Hebrew · 94% match")
        XCTAssertTrue(hebrew.waitForExistence(timeout: 20), "the search found nothing: \(buttons(app))")
        hebrew.tap()
        element(app, "subtitle-download").tap()
        XCTAssertTrue(text(app, containing: "Saved · Jellyfin is refreshing").waitForExistence(timeout: 20))
        XCTAssertFalse(hebrew.exists, "the results stayed after their ticket was spent")
        XCTAssertTrue(text(app, containing: "Hebrew · OpenSubtitles").waitForExistence(timeout: 5))
        // Another search gives tickets that work.
        element(app, "subtitles-search").tap()
        XCTAssertTrue(button(app, startingWith: "Hebrew · 94% match").waitForExistence(timeout: 20))
    }

    // MARK: Finding a release

    /// A Library series searches its seasons and aired episodes for releases
    /// on the same pages the request side uses.
    @MainActor
    func testASeriesFindsReleasesForASeasonAndForOneEpisode() {
        let app = launch(title: bleach)
        chooseFromMore(app, "Find release")

        XCTAssertTrue(app.staticTexts["Find releases"].waitForExistence(timeout: 15), "the release targets did not open")
        let season = button(app, containing: "Search the entire season")
        XCTAssertTrue(season.waitForExistence(timeout: 15), "the whole season is not offered: \(buttons(app))")
        XCTAssertTrue(text(app, containing: "Bleach").exists)
        season.tap()
        XCTAssertTrue(app.staticTexts["Bleach · Season 1"].waitForExistence(timeout: 15), "the season's releases did not open")
        XCTAssertTrue(button(app, containing: "UHD.BluRay").waitForExistence(timeout: 30), "no releases were listed: \(buttons(app))")

        // One aired episode, searched alone.
        back(app)
        let pilot = button(app, containing: "Pilot")
        XCTAssertTrue(pilot.waitForExistence(timeout: 15), "the aired episodes are missing: \(buttons(app))")
        pilot.tap()
        XCTAssertTrue(app.staticTexts["Bleach · S1E1 · Pilot"].waitForExistence(timeout: 15), "the episode's releases did not open")
    }

    /// Held on an episode in the strip, the menu has its own subtitles and its own release search.
    @MainActor
    func testAnEpisodesMenuOffersItsReleaseSearchAndItsSubtitles() {
        let app = launch(title: bleach)
        // The strip's card ("2. A Second Look"), not Home's Continue card for the same episode under the page.
        let card = button(app, containing: "2. A Second Look")
        XCTAssertTrue(card.waitForExistence(timeout: 20), "the episodes did not load: \(buttons(app))")
        card.press(forDuration: 1.2)
        let subtitles = app.buttons["Find subtitles"].firstMatch
        XCTAssertTrue(subtitles.waitForExistence(timeout: 5), "the episode's menu has no subtitles: \(buttons(app))")
        XCTAssertTrue(app.buttons["Find release"].firstMatch.exists, "the episode's menu has no release search")
        app.buttons["Find release"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Bleach · S1E2")).firstMatch
            .waitForExistence(timeout: 15), "the episode's own releases did not open")
    }

    // MARK: Deleting

    /// The preview is read-only, Cancel is first and leaves it, a native
    /// alert asks once more, and a confirmed deletion takes the title out of
    /// the library and Home.
    @MainActor
    func testADeletionIsPreviewedConfirmedInAnAlertAndTheTitleIsGone() {
        let app = launch(open: "Marvel Movies")
        let thor = button(app, containing: "Thor")
        XCTAssertTrue(thor.waitForExistence(timeout: 20), "Marvel Movies has no Thor: \(buttons(app))")
        XCTAssertTrue(app.staticTexts["3 titles"].waitForExistence(timeout: 10))
        thor.tap()
        chooseFromMore(app, "Delete from server")

        // What would go, before anything does.
        XCTAssertTrue(app.staticTexts["Delete from server"].waitForExistence(timeout: 15), "the deletion page did not open")
        XCTAssertTrue(element(app, "removal-summary").waitForExistence(timeout: 15), "no preview: \(buttons(app))")
        XCTAssertTrue(text(app, containing: "1 server file").exists)
        XCTAssertTrue(text(app, containing: "Thor (2011).mkv").exists, "the files are not listed")
        XCTAssertTrue(text(app, containing: "Saved copies on this device remain").exists)
        let cancel = element(app, "removal-cancel")
        let delete = element(app, "removal-delete")
        XCTAssertTrue(cancel.exists && delete.exists)
        XCTAssertLessThan(cancel.frame.minX, delete.frame.minX, "Cancel is not first")

        // Cancel leaves for the title's page; nothing was deleted.
        cancel.tap()
        XCTAssertTrue(element(app, "title-more").waitForExistence(timeout: 10), "Cancel did not come back to the title")
        chooseFromMore(app, "Delete from server")
        XCTAssertTrue(element(app, "removal-delete").waitForExistence(timeout: 15))

        // A native alert, whose Cancel changes nothing either.
        element(app, "removal-delete").tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 5), "no confirmation alert")
        XCTAssertTrue(alert.staticTexts["Permanently delete Thor?"].exists, alert.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " | "))
        XCTAssertTrue(alert.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "This cannot be undone")).firstMatch.exists)
        alert.buttons["Cancel"].tap()
        XCTAssertTrue(alert.waitForNonExistence(timeout: 5))
        XCTAssertTrue(element(app, "removal-delete").exists, "the alert's Cancel left the page")

        // Confirmed, it goes back past the title to the library, which no longer has it.
        element(app, "removal-delete").tap()
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        alert.buttons["Delete server files"].tap()
        XCTAssertTrue(app.staticTexts["2 titles"].waitForExistence(timeout: 20), "the library still counts Thor: \(buttons(app))")
        XCTAssertFalse(button(app, containing: "Thor").exists, "Thor is still in the library")
        XCTAssertTrue(button(app, containing: "Avengers: Endgame").exists, "another title went with it")

        // And Home, where it was among the recently added.
        let home = app.buttons.matching(NSPredicate(format: "label == 'Home' AND NOT (identifier BEGINSWITH 'pane-')")).firstMatch
        XCTAssertTrue(home.waitForExistence(timeout: 5))
        home.tap()
        XCTAssertTrue(element(app, "home-title-latest").waitForExistence(timeout: 15), "Home's rows did not load")
        XCTAssertFalse(button(app, containing: "Thor").exists, "Home still offers the deleted title")
    }

    /// A series is deleted the same way, as a whole, with every episode listed.
    @MainActor
    func testASeriesPreviewListsEveryEpisodeAndCancelKeepsIt() {
        let app = launch(title: bleach)
        chooseFromMore(app, "Delete from server")
        XCTAssertTrue(element(app, "removal-summary").waitForExistence(timeout: 15), "no preview: \(buttons(app))")
        XCTAssertTrue(text(app, containing: "3 server files").exists)
        XCTAssertTrue(text(app, containing: "Bleach · S01E01 · The Beginning.mkv").exists, "the first episode is not listed")
        element(app, "removal-cancel").tap()
        XCTAssertTrue(element(app, "title-more").waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Bleach"].exists, "Cancel lost the series")
    }

    /// A book has a preview and a Cancel (nothing is confirmed here).
    @MainActor
    func testABookIsPreviewedAndItsAlertCancelsWithoutDeleting() {
        let app = launch(section: "home", side: "books", open: "book:rw_demo_darkmatter")
        let more = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "More actions for")).firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 20), "the book has no More menu: \(buttons(app))")
        more.tap()
        let entry = app.buttons["Delete from server"].firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 5), "the book's menu has no Delete from server: \(buttons(app))")
        entry.tap()
        XCTAssertTrue(element(app, "removal-summary").waitForExistence(timeout: 15), "no preview: \(buttons(app))")
        XCTAssertTrue(text(app, containing: "Dark Matter").exists)
        XCTAssertTrue(text(app, containing: "1 server file").exists)
        element(app, "removal-delete").tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        alert.buttons["Cancel"].tap()
        XCTAssertTrue(alert.waitForNonExistence(timeout: 5))
        element(app, "removal-cancel").tap()
        XCTAssertTrue(more.waitForExistence(timeout: 10), "Cancel did not come back to the book")
    }

    @MainActor
    private func button(_ app: XCUIApplication, containing words: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }
}
