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
    private func launch(title: String, environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "library", "HUB_SIDE": "media", "HUB_TITLE": title]
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
