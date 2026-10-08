import XCTest

/// Downloads for watching away from the hub (#5), against the demo hub
/// (`-demo`), which answers as the hub does: each download waits a moment on
/// the pretend PC while its MP4 is made, then comes as a real MP4 into a
/// folder of the demo's own, emptied at each launch. Nothing real is asked for.
final class DownloadsTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// The demo's Inception (a film with a French picture subtitle that is
    /// left out) and Bleach (a series): Jellyfin ids are 32 hex characters.
    private let inception = "000000000000000000000000deb0000e"
    private let bleach = "000000000000000000000000deb00003"

    @MainActor
    private func launch(title: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "library", "HUB_SIDE": "media", "HUB_TITLE": title]
        app.launch()
        return app
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
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

    /// The Downloads tab, from the bar.
    @MainActor
    private func openDownloads(_ app: XCUIApplication) {
        let tab = app.buttons.matching(NSPredicate(format: "label == 'Downloads'")).firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 5), "no Downloads tab: \(buttons(app))")
        tab.tap()
    }

    /// A film: its Download button asks once, the queue shows the PC making
    /// its MP4 and what is left out, then it is on this device, opens on its
    /// own page and plays from the file; removed, it is gone.
    @MainActor
    func testAFilmComesToTheDeviceAndPlaysFromIt() {
        let app = launch(title: inception)
        let download = element(app, "title-download")
        XCTAssertTrue(download.waitForExistence(timeout: 20), "the film's page has no Download: \(buttons(app))")
        XCTAssertEqual(download.label, "Download")
        download.tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 5), "Download did not ask first")
        XCTAssertTrue(alert.label.contains("Download Inception?"), alert.label)
        alert.buttons["Download"].tap()
        // On its way: the button's ring, then the queue.
        XCTAssertTrue(waitUntil(10) { download.label.hasPrefix("Downloading") || download.label == "Downloaded" },
                      "the button did not show the download: \(download.label)")

        openDownloads(app)
        let queue = element(app, "downloads-queue")
        XCTAssertTrue(queue.waitForExistence(timeout: 5), "the Downloads page has no queue")
        queue.tap()
        XCTAssertTrue(text(app, containing: "French subtitles are left out").waitForExistence(timeout: 10)
                      || text(app, containing: "Downloaded").exists, "the queue does not say what is left out")
        let device = element(app, "downloads-device")
        device.tap()
        let poster = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Inception'")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 30), "Inception did not arrive on the device: \(buttons(app))")
        poster.tap()

        let play = element(app, "offline-play")
        XCTAssertTrue(play.waitForExistence(timeout: 10), "the downloaded film's page did not open")
        XCTAssertTrue(text(app, containing: "On this device").exists)
        play.tap()
        let lock = app.buttons["Lock controls"]
        XCTAssertTrue(lock.waitForExistence(timeout: 15), "the download did not play")
        // The controls go by themselves (and the finished download's notification may have
        // held the test a moment): a tap on the picture brings them back.
        let back = app.buttons["Back"].firstMatch
        if !back.waitForExistence(timeout: 2) { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        XCTAssertTrue(back.waitForExistence(timeout: 5), "the player's controls did not come back")
        back.tap()
        XCTAssertTrue(lock.waitForNonExistence(timeout: 10), "Back left the player open")

        // Removed: asked first, then gone.
        app.buttons["Remove"].firstMatch.tap()
        let ask = app.alerts.firstMatch
        XCTAssertTrue(ask.waitForExistence(timeout: 5), "removing did not ask first")
        ask.buttons["Remove"].tap()
        XCTAssertTrue(text(app, containing: "Not on this device").waitForExistence(timeout: 5), "the film stayed on the device")
    }

    /// A series: Download opens its episodes, Next 3 ticks three from where
    /// Play would start, and one question queues them as one batch.
    @MainActor
    func testASeriesEpisodesArePickedAndQueuedAsOne() {
        let app = launch(title: bleach)
        let download = element(app, "title-download")
        XCTAssertTrue(download.waitForExistence(timeout: 20), "the series' page has no Download: \(buttons(app))")
        download.tap()
        let next = element(app, "pick-next-3")
        XCTAssertTrue(next.waitForExistence(timeout: 15), "the episodes to download did not open: \(buttons(app))")
        next.tap()
        let counter = element(app, "pick-counter")
        XCTAssertTrue(waitUntil(5) { counter.label.hasPrefix("3 selected") }, "Next 3 did not tick three: \(counter.label)")
        element(app, "pick-download").tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 5), "Download did not ask first")
        XCTAssertTrue(alert.label.contains("Download 3 episodes?"), alert.label)
        alert.buttons["Download"].tap()
        XCTAssertTrue(text(app, containing: "On their way").waitForExistence(timeout: 10), "the episodes were not queued")
        // What is coming cannot be ticked again.
        XCTAssertTrue(text(app, containing: "On its way").exists || text(app, containing: "On this device").exists)

        openDownloads(app)
        element(app, "downloads-queue").tap()
        XCTAssertTrue(text(app, containing: "Bleach").waitForExistence(timeout: 5), "the queue has no Bleach batch")
        let words = app.staticTexts.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.joined(separator: " | ")
        XCTAssertTrue(text(app, containing: "/3 complete").exists, "the batch does not count its three: \(words)")
    }

    /// A download that finishes says so in a notification (#43). The demo
    /// asks for permission only with HUB_ALERTS=1, at the first download.
    @MainActor
    func testAFinishedDownloadSaysSoInANotification() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "library", "HUB_SIDE": "media", "HUB_TITLE": inception, "HUB_ALERTS": "1"]
        app.launch()
        let download = element(app, "title-download")
        XCTAssertTrue(download.waitForExistence(timeout: 20), "the film's page has no Download: \(buttons(app))")
        download.tap()
        let ask = app.alerts.firstMatch
        XCTAssertTrue(ask.waitForExistence(timeout: 5), "Download did not ask first")
        ask.buttons["Download"].tap()
        // The question about notifications, the first time only.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.alerts.buttons["Allow"]
        if allow.waitForExistence(timeout: 8) { allow.tap() }
        let banner = springboard.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "Inception is ready to watch offline")).firstMatch
        XCTAssertTrue(banner.waitForExistence(timeout: 60), "no notification said the download finished")
    }

    /// Download season (#43): a season's menu opens the picker with that
    /// season's episodes ticked, ready for one question.
    @MainActor
    func testDownloadSeasonTicksTheSeasonsEpisodes() {
        let app = launch(title: bleach)
        let season = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Season 1")).firstMatch
        XCTAssertTrue(season.waitForExistence(timeout: 20), "the series has no season: \(buttons(app))")
        season.press(forDuration: 1.2)
        let download = app.buttons["Download season"].firstMatch
        XCTAssertTrue(download.waitForExistence(timeout: 5), "the season's menu has no Download season: \(buttons(app))")
        download.tap()
        let counter = element(app, "pick-counter")
        XCTAssertTrue(counter.waitForExistence(timeout: 15), "the picker did not open")
        XCTAssertTrue(waitUntil(10) { counter.label.hasPrefix("3 selected") }, "the season's episodes are not ticked: \(counter.label)")
    }

    /// The Books side's Downloads (#43): a book opened is kept on the device
    /// and listed there with what is kept; Remove offline copy asks, then it goes.
    @MainActor
    func testABookOpenedIsKeptAndListedOnTheBooksDownloads() {
        // Open Recursion once: its EPUB is kept on the device.
        let reading = XCUIApplication()
        reading.launchArguments = ["-demo"]
        reading.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books",
                                     "HUB_BOOK": "rw_demo_recursion/demo-rw_demo_recursion", "HUB_BOOK_SCROLL": "0"]
        reading.launch()
        let page = reading.descendants(matching: .any).matching(identifier: "book-page").firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 20), "the book did not open")
        reading.terminate()

        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "downloads", "HUB_SIDE": "books"]
        app.launch()
        XCTAssertTrue(element(app, "downloads-books").waitForExistence(timeout: 15), "the Books side's Downloads has no Books")
        XCTAssertTrue(element(app, "downloads-device").exists, "the films and series are not beside the books")
        let kept = element(app, "kept-book-rw_demo_recursion")
        XCTAssertTrue(kept.waitForExistence(timeout: 15), "Recursion is not listed as kept: \(buttons(app))")
        XCTAssertTrue(kept.label.contains("Ebook"), "the kept book does not say what is kept: \(kept.label)")
        kept.press(forDuration: 1.2)
        let remove = app.buttons["Remove offline copy"].firstMatch
        XCTAssertTrue(remove.waitForExistence(timeout: 5), "holding the book offers no Remove offline copy")
        remove.tap()
        let ask = app.alerts.firstMatch
        XCTAssertTrue(ask.waitForExistence(timeout: 5), "removing did not ask first")
        ask.buttons["Remove from this device"].tap()
        XCTAssertTrue(kept.waitForNonExistence(timeout: 10), "Recursion stayed after its offline copy was removed")
    }

    /// Asks `condition` every quarter of a second until it holds or `seconds` pass.
    @MainActor
    private func waitUntil(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return condition()
    }
}
