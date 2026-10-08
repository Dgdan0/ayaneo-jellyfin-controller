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
    /// The demo's Dune fails on the pretend PC until it is retried.
    private let dune = "000000000000000000000000deb0000d"

    @MainActor
    private func launch(title: String, environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        // The player's controls stay up, so its Back is there to press.
        app.launchEnvironment = ["HUB_SECTION": "library", "HUB_SIDE": "media", "HUB_TITLE": title, "HUB_PLAY_CHROME": "pinned"]
            .merging(environment) { _, given in given }
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
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(lock.waitForNonExistence(timeout: 10), "Back left the player open")

        // Removed: asked first, then gone.
        app.buttons["Remove"].firstMatch.tap()
        let ask = app.alerts.firstMatch
        XCTAssertTrue(ask.waitForExistence(timeout: 5), "removing did not ask first")
        ask.buttons["Remove"].tap()
        XCTAssertTrue(text(app, containing: "Not on this device").waitForExistence(timeout: 5), "the film stayed on the device")
    }

    /// Subtitles kept beside a download (#45): the demo hub's English and
    /// Hebrew sidecars come with the film as WebVTT, though its MP4 has none,
    /// are choices in Audio & subtitles, and the Hebrew is drawn by the app
    /// from its file (HUB_PLAY_SUBTITLE=heb turns it on as the film opens;
    /// HUB_PLAY_CUES=read lets the test read the line drawn).
    @MainActor
    func testADownloadsSubtitlesComeBesideItAndAreDrawnFromTheirFiles() {
        let app = launch(title: inception, environment: ["HUB_PLAY_SUBTITLE": "heb", "HUB_PLAY_CUES": "read"])
        let download = element(app, "title-download")
        XCTAssertTrue(download.waitForExistence(timeout: 20), "the film's page has no Download: \(buttons(app))")
        download.tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 5), "Download did not ask first")
        alert.buttons["Download"].tap()
        XCTAssertTrue(waitUntil(30) { download.label == "Downloaded" }, "the film did not arrive: \(download.label)")

        // Opening Downloads brings them up to date too.
        openDownloads(app)
        element(app, "downloads-device").tap()
        let poster = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Inception'")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 10), "Inception is not on the device: \(buttons(app))")
        poster.tap()
        let play = element(app, "offline-play")
        XCTAssertTrue(play.waitForExistence(timeout: 10), "the downloaded film's page did not open")
        play.tap()

        // The Hebrew line, drawn from the kept WebVTT, as it is written. Matched
        // in one query: a cue comes and goes, so its label is not read apart.
        let cue = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier == 'subtitle-cue' AND (label CONTAINS %@ OR label CONTAINS %@)",
            "כתובית שנשמרה ליד ההורדה", "היא מוצגת גם בלי רשת")).firstMatch
        XCTAssertTrue(cue.waitForExistence(timeout: 10), "the kept Hebrew was not drawn")
        // Paused, so the line holds still while the panel is used: a cue that
        // changes between two of XCTest's looks at the screen fails a tap.
        app.buttons["Pause"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Play"].firstMatch.waitForExistence(timeout: 5), "the film did not pause")

        // Both are choices, though the MP4 holds no subtitles; the Hebrew is ticked.
        app.buttons["Audio & subtitles"].firstMatch.tap()
        let heading = app.staticTexts.matching(identifier: "panel-heading").firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 5), "Audio & subtitles did not open")
        let hebrew = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Hebrew")).firstMatch
        let english = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "English")).firstMatch
        XCTAssertTrue(hebrew.waitForExistence(timeout: 5), "the kept Hebrew is not a choice: \(buttons(app))")
        XCTAssertTrue(english.exists, "the kept English is not a choice: \(buttons(app))")
        XCTAssertTrue(hebrew.isSelected || hebrew.wait(for: \.isSelected, toEqual: true, timeout: 5), "the Hebrew is not ticked")
        english.tap()
        XCTAssertTrue(english.wait(for: \.isSelected, toEqual: true, timeout: 5), "the English could not be chosen")
        app.buttons["Close"].firstMatch.tap()
        XCTAssertTrue(heading.waitForNonExistence(timeout: 5), "the panel did not close")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 10), "Back left the player open")

        // Removed with the film.
        app.buttons["Remove"].firstMatch.tap()
        let ask = app.alerts.firstMatch
        XCTAssertTrue(ask.waitForExistence(timeout: 5), "removing did not ask first")
        ask.buttons["Remove"].tap()
        XCTAssertTrue(text(app, containing: "Not on this device").waitForExistence(timeout: 5), "the film stayed on the device")
    }

    /// A downloaded series' own page has the library page's shape: its name, the series' own
    /// facts and overview (kept beside its artwork when its first episode was queued), Play
    /// naming the episode, Remove and more as round buttons, the season's pill and a strip of
    /// episode cards that say what is on the device. HUB_DOWNLOAD queues the three episodes as
    /// one batch at launch; the page is opened from Downloads, as a person does.
    @MainActor
    func testADownloadedSeriesHasTheLibraryPagesShapeWithItsSeasonsAndEpisodeCards() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "downloads", "HUB_SIDE": "media",
                                 "HUB_DOWNLOAD": "\(bleach)-e1,\(bleach)-e2,\(bleach)-e3"]
        app.launch()
        let device = element(app, "downloads-device")
        XCTAssertTrue(device.waitForExistence(timeout: 15), "the Downloads page has no On this device: \(buttons(app))")
        device.tap()
        let poster = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Bleach'")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 40), "Bleach did not arrive on the device: \(buttons(app))")
        poster.tap()

        // Play names the episode it starts; Remove and more are round buttons beside it.
        let play = element(app, "offline-play")
        XCTAssertTrue(play.waitForExistence(timeout: 10), "the downloaded series' page did not open: \(buttons(app))")
        XCTAssertEqual(play.label, "Play S1E1")
        XCTAssertTrue(app.buttons["Remove"].firstMatch.exists, "no Remove beside Play: \(buttons(app))")
        XCTAssertTrue(app.buttons["More actions"].firstMatch.exists, "no more beside Play: \(buttons(app))")

        // The three episodes have all come, so the page says so, and the series' own words are there.
        XCTAssertTrue(text(app, containing: "On this device · 3 episodes").waitForExistence(timeout: 40),
                      "the page does not say its three episodes are on the device")
        XCTAssertTrue(text(app, containing: "as the demo hub tells it").waitForExistence(timeout: 15),
                      "the series' own overview was not kept beside its artwork")

        // The season's pill, and the episode cards: UP NEXT on the first, what is on the device under each.
        let season = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Season 1'")).firstMatch
        XCTAssertTrue(season.waitForExistence(timeout: 10), "the page has no season pill: \(buttons(app))")
        XCTAssertTrue(season.label.contains("3 episodes"), "the season does not count its episodes: \(season.label)")
        let first = element(app, "offline-episode-\(bleach)-e1")
        let second = element(app, "offline-episode-\(bleach)-e2")
        XCTAssertTrue(first.waitForExistence(timeout: 10), "no card for the first episode: \(buttons(app))")
        XCTAssertTrue(second.exists, "no card for the second episode: \(buttons(app))")
        XCTAssertTrue(first.label.contains("The Beginning"), first.label)
        XCTAssertTrue(first.label.localizedCaseInsensitiveContains("up next"), "the first episode is not up next: \(first.label)")
        XCTAssertTrue(second.label.contains("A Second Look"), second.label)
        XCTAssertFalse(second.label.localizedCaseInsensitiveContains("up next"), "the second is up next too: \(second.label)")
        XCTAssertNotNil(first.label.range(of: #"\d\s?[KMG]?B"#, options: .regularExpression),
                        "the card does not say its size on the device: \(first.label)")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "offline-series"
        shot.lifetime = .keepAlways
        add(shot)
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

    /// A download the PC could not prepare (#43): its notification says so
    /// and opens the queue, which says why, and Retry makes it again there.
    @MainActor
    func testAFailedDownloadSaysWhyAndRetryBringsIt() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "library", "HUB_SIDE": "media", "HUB_TITLE": dune, "HUB_ALERTS": "1"]
        app.launch()
        let download = element(app, "title-download")
        XCTAssertTrue(download.waitForExistence(timeout: 20), "the film's page has no Download: \(buttons(app))")
        download.tap()
        let ask = app.alerts.firstMatch
        XCTAssertTrue(ask.waitForExistence(timeout: 5), "Download did not ask first")
        ask.buttons["Download"].tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.alerts.buttons["Allow"]
        if allow.waitForExistence(timeout: 8) { allow.tap() }
        let banner = springboard.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "could not make this download")).firstMatch
        XCTAssertTrue(banner.waitForExistence(timeout: 60), "no notification said the download failed")
        banner.tap()

        let retry = app.buttons["Retry"].firstMatch
        XCTAssertTrue(retry.waitForExistence(timeout: 10), "the notification did not open the queue with Retry: \(buttons(app))")
        XCTAssertTrue(text(app, containing: "could not make this download's MP4").exists, "the queue does not say why it failed")
        retry.tap()
        element(app, "downloads-device").tap()
        let poster = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Dune'")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 40), "Dune did not come after Retry: \(buttons(app))")
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
