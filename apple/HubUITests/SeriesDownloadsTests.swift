import XCTest

/// A series' downloads on its own page (#48), against the demo hub (`-demo`):
/// a tap on a card's corner, the season button, the storage bar that rises
/// and fades, the smart choices behind the round button, Keep ready, and
/// select mode. Slow Horses has Season 1 of three and Season 2 of four; the
/// demo makes each MP4 in a few seconds. Nothing real is asked for.
final class SeriesDownloadsTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        // The simulator keeps the way it was turned; later tests expect it upright.
        XCUIDevice.shared.orientation = .portrait
    }

    private let series = "000000000000000000000000deb00012"
    /// What the page calls this device: "on this iPad" on an iPad.
    private var device: String { UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone" }

    @MainActor
    private func launch(download: [String] = [], pinnedBar: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "library", "HUB_SIDE": "media", "HUB_TITLE": series]
        if pinnedBar { app.launchEnvironment["HUB_BAR_PINNED"] = "1" }
        if !download.isEmpty { app.launchEnvironment["HUB_DOWNLOAD"] = download.map { "\(series)-\($0)" }.joined(separator: ",") }
        app.launch()
        // The page settles once the hub's listing is in (the season button takes its place); then the
        // cards are lifted clear of the bar at the foot of the screen, which would be pressed through them.
        XCTAssertTrue(element(app, "download-season").waitForExistence(timeout: 60), "the page did not settle")
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
            .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)))
        RunLoop.current.run(until: Date().addingTimeInterval(0.6))
        return app
    }

    /// The episodes on the device, as the ends of their ids ("e1", "s2e2"): a list the page keeps for the tests,
    /// since the strip builds only the cards at the screen.
    @MainActor
    private func onDevice(_ app: XCUIApplication) -> Set<String> {
        let label = element(app, "downloaded-episodes").label
        let inner = label.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        return Set(inner.split(separator: ",").map(String.init))
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// An episode card's corner: "Download", "Waiting…", "Downloading…", "Downloaded".
    @MainActor
    private func corner(_ app: XCUIApplication, _ episode: String) -> XCUIElement {
        element(app, "download-\(series)-\(episode)")
    }

    @MainActor
    private func card(_ app: XCUIApplication, _ episode: String) -> XCUIElement {
        element(app, "episode-\(series)-\(episode)")
    }

    @MainActor
    private func buttons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.map { $0.label }.filter { !$0.isEmpty }.joined(separator: " | ")
    }

    @MainActor
    private func waitUntil(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return condition()
    }

    /// Swipes the strip along until `target` can be pressed: only a card or two is on a phone's screen.
    @MainActor
    private func scroll(_ app: XCUIApplication, to target: XCUIElement) {
        let strip = card(app, "e1").frame.midY
        var tries = 0
        while !target.isHittable && tries < 8 {
            let window = app.windows.firstMatch
            let height = window.frame.height
            let from = window.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: strip / height))
            let to = window.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: strip / height))
            from.press(forDuration: 0.05, thenDragTo: to)
            tries += 1
        }
    }

    /// A tap on an episode's corner, once more if the first was lost (never twice: a second tap stops it).
    @MainActor
    private func startDownload(_ app: XCUIApplication, _ episode: String) {
        let target = corner(app, episode)
        XCTAssertTrue(target.waitForExistence(timeout: 25), "no corner for \(episode): \(buttons(app))")
        target.tap()
        if !waitUntil(4, { target.label != "Download" }) { target.tap() }
        XCTAssertTrue(waitUntil(5) { target.label != "Download" }, "\(episode) did not start: \(target.label)")
    }

    /// What an episode's corner says, scrolling the strip to it: the strip builds only the cards at the screen.
    @MainActor
    private func cornerLabel(_ app: XCUIApplication, _ episode: String) -> String {
        let target = corner(app, episode)
        if target.exists { return target.label }
        let window = app.windows.firstMatch
        let anyCard = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "episode-\(series)")).firstMatch
        guard anyCard.exists else { return "" }
        let y = anyCard.frame.midY / window.frame.height
        for direction in [(0.85, 0.15, 4), (0.15, 0.85, 8)] {
            for _ in 0..<direction.2 {
                window.coordinate(withNormalizedOffset: CGVector(dx: direction.0, dy: y))
                    .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: direction.1, dy: y)))
                RunLoop.current.run(until: Date().addingTimeInterval(0.4))
                if target.exists { return target.label }
            }
        }
        return ""
    }

    /// Presses and holds `target` until `menuItem` shows, once more if the first hold was taken for a tap.
    @MainActor
    private func hold(_ target: XCUIElement, until menuItem: XCUIElement) {
        // A card under the bar at the foot of the screen is pressed through to the bar: scroll it clear first.
        let window = XCUIApplication().windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
            .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35)))
        RunLoop.current.run(until: Date().addingTimeInterval(0.6))
        target.press(forDuration: 1.2)
        if !menuItem.waitForExistence(timeout: 4) {
            // A hold taken for a tap plays the card: out of the player, and once more.
            let app = XCUIApplication()
            if app.buttons["Lock controls"].exists {
                app.buttons["Back"].firstMatch.tap()
                _ = app.buttons["Lock controls"].waitForNonExistence(timeout: 10)
            }
            target.press(forDuration: 1.8)
        }
    }

    @MainActor
    private func pill(_ app: XCUIApplication, _ season: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", season)).firstMatch
    }

    @MainActor
    private func openPanel(_ app: XCUIApplication) {
        let round = element(app, "title-download")
        XCTAssertTrue(round.waitForExistence(timeout: 25), "the series page has no download button: \(buttons(app))")
        round.tap()
        XCTAssertTrue(element(app, "download-panel").waitForExistence(timeout: 10), "the choices did not open")
        // Their sizes come with the hub's listing: until then the panel only says it is asking.
        let choose = element(app, "choose-episodes")
        XCTAssertTrue(waitUntil(60) { choose.exists && choose.isEnabled }, "the hub's listing did not arrive: \(buttons(app))")
    }

    // MARK: Quick taps

    /// A tap on a card's corner downloads at once, with no question; the bar
    /// rises with what is coming, the corner goes to a tick, and the bar fades
    /// a few seconds after the last one arrives.
    @MainActor
    func testATapOnACardsCornerDownloadsAtOnceAndTheBarRisesThenFades() {
        let app = launch()
        let first = corner(app, "e1")
        XCTAssertTrue(first.waitForExistence(timeout: 25), "the first card has no download corner: \(buttons(app))")
        XCTAssertEqual(first.label, "Download")
        XCTAssertFalse(element(app, "storage-bar-line").exists, "no bar before a download")
        first.tap()
        XCTAssertFalse(app.alerts.firstMatch.waitForExistence(timeout: 1.5), "a tap in the corner asks nothing")
        let line = element(app, "storage-bar-line")
        XCTAssertTrue(line.waitForExistence(timeout: 10), "the bar did not rise")
        XCTAssertTrue(waitUntil(5) { line.label.contains("1 coming") }, "the bar says \(line.label)")
        XCTAssertNotEqual(first.label, "Download", "the corner shows it is on its way")
        XCTAssertTrue(waitUntil(60) { first.label == "Downloaded" }, "the episode did not arrive: \(first.label)")
        XCTAssertTrue(waitUntil(3) { !line.exists || line.label == "1 on this \(device)" }, "the bar says \(line.label)")
        // It fades about three seconds after the last one.
        XCTAssertTrue(line.waitForNonExistence(timeout: 12), "the bar stayed up")
    }

    /// The ring stops what it is doing: tapped while it waits or moves, the
    /// corner goes back to an arrow and nothing is left on the device.
    @MainActor
    func testTappingTheRingStopsTheDownload() {
        let app = launch()
        let first = corner(app, "e1")
        XCTAssertTrue(first.waitForExistence(timeout: 25))
        first.tap()
        XCTAssertTrue(waitUntil(5) { first.label != "Download" }, "it did not start: \(first.label)")
        first.tap()
        XCTAssertTrue(waitUntil(10) { first.label == "Download" }, "the tap did not stop it: \(first.label)")
    }

    /// "Season 1 · 658 KB" gets every episode not here; then it says it is all on this iPhone.
    @MainActor
    func testTheSeasonButtonDownloadsTheRestAndThenSaysItIsOnThisDevice() {
        let app = launch()
        let season = element(app, "download-season")
        XCTAssertTrue(season.waitForExistence(timeout: 25), "no season button: \(buttons(app))")
        XCTAssertTrue(season.label.hasPrefix("Season 1 · "), season.label)
        season.tap()
        XCTAssertTrue(waitUntil(10) { season.label == "Season 1 on this \(device)" }, "the button says \(season.label)")
        for episode in ["e1", "e2", "e3"] {
            XCTAssertTrue(waitUntil(90) { self.cornerLabel(app, episode) == "Downloaded" },
                          "\(episode) did not arrive: \(cornerLabel(app, episode))")
        }
        // The other season still has its own to get.
        pill(app, "Season 2").tap()
        XCTAssertTrue(waitUntil(10) { season.label.hasPrefix("Season 2 · ") }, "Season 2's button says \(season.label)")
    }

    /// A season's pill, held, still offers Download season as a shortcut.
    @MainActor
    func testHoldingASeasonsPillStillOffersDownloadSeason() {
        let app = launch()
        let second = pill(app, "Season 2")
        XCTAssertTrue(second.waitForExistence(timeout: 25))
        XCTAssertTrue(element(app, "download-season").waitForExistence(timeout: 45), "the hub's listing did not arrive")
        let download = app.buttons["Download season"].firstMatch
        hold(second, until: download)
        XCTAssertTrue(download.waitForExistence(timeout: 5), "the pill's menu has no Download season: \(buttons(app))")
        download.tap()
        XCTAssertFalse(app.alerts.firstMatch.waitForExistence(timeout: 1), "the shortcut asks nothing")
        second.tap()
        XCTAssertTrue(waitUntil(10) { self.element(app, "download-season").label == "Season 2 on this \(device)" },
                      "Season 2 was not asked for: \(element(app, "download-season").label)")
    }

    // MARK: The choices

    /// The round button's panel: five choices with their counts and sizes, the
    /// bar previewing the chosen one, and a Download that asks for it.
    @MainActor
    func testTheRoundButtonOffersTheChoicesWithTheirSizesAndDownloadsTheChosen() {
        let app = launch()
        openPanel(app)
        for id in ["choice-keep-ready", "choice-rest-of-season", "choice-everything-unwatched", "choice-whole-series",
                   "choose-episodes"] {
            XCTAssertTrue(element(app, id).waitForExistence(timeout: 10), "no \(id): \(buttons(app))")
        }
        XCTAssertTrue(element(app, "choice-keep-ready").label.contains("Keep the next 3 ready"))
        XCTAssertTrue(element(app, "choice-keep-ready").label.contains("3 episodes ·"), element(app, "choice-keep-ready").label)
        XCTAssertTrue(element(app, "choice-rest-of-season").label.contains("Rest of Season 1"))
        XCTAssertTrue(element(app, "choice-whole-series").label.contains("7 episodes ·"), element(app, "choice-whole-series").label)
        // The preview follows the choice.
        let preview = element(app, "download-preview")
        element(app, "choice-whole-series").tap()
        XCTAssertTrue(waitUntil(5) { preview.label.hasPrefix("Adds ") }, "the bar says \(preview.label)")
        let whole = preview.label
        element(app, "choice-rest-of-season").tap()
        XCTAssertTrue(waitUntil(5) { preview.label != whole }, "the preview did not change: \(preview.label)")
        // Download the rest of Season 1: the panel closes, the bar rises.
        element(app, "download-panel-go").tap()
        XCTAssertTrue(element(app, "download-panel").waitForNonExistence(timeout: 10), "the panel stayed")
        XCTAssertTrue(element(app, "storage-bar-line").waitForExistence(timeout: 10), "the bar did not rise")
        XCTAssertTrue(waitUntil(90) { self.cornerLabel(app, "e3") == "Downloaded" }, "Season 1 did not arrive")
    }

    /// Keep ready: the stepper sets the number, the round button carries it,
    /// the panel offers to turn it off, and the files stay when it is.
    @MainActor
    func testKeepReadyKeepsTheNumberOnTheButtonAndTurnsOffKeepingTheFiles() {
        let app = launch()
        openPanel(app)
        element(app, "keep-ready-minus").tap()
        XCTAssertTrue(element(app, "choice-keep-ready").label.contains("Keep the next 2 ready"), element(app, "choice-keep-ready").label)
        element(app, "download-panel-go").tap()
        XCTAssertTrue(element(app, "download-panel").waitForNonExistence(timeout: 10))
        let badge = element(app, "keep-ready-badge")
        XCTAssertTrue(badge.waitForExistence(timeout: 10), "the round button does not carry the number")
        XCTAssertEqual(badge.label, "2")
        // The next two, and no more.
        XCTAssertTrue(waitUntil(90) { self.cornerLabel(app, "e1") == "Downloaded" && self.cornerLabel(app, "e2") == "Downloaded" },
                      "the next two did not arrive")
        XCTAssertEqual(cornerLabel(app, "e3"), "Download", "a third was not asked for")

        openPanel(app)
        XCTAssertTrue(element(app, "choice-keep-ready").label.contains("On · keeping 2 ready"), element(app, "choice-keep-ready").label)
        let off = element(app, "keep-ready-off")
        XCTAssertTrue(off.waitForExistence(timeout: 5), "no way to turn it off")
        XCTAssertEqual(off.label, "Turn off Keep ready")
        off.tap()
        XCTAssertTrue(badge.waitForNonExistence(timeout: 10), "the number stayed on the button")
        XCTAssertEqual(cornerLabel(app, "e1"), "Downloaded", "the files stay")
        XCTAssertEqual(cornerLabel(app, "e2"), "Downloaded")
    }

    /// Finishing episodes moves the window along: with Keep ready on 2, watching
    /// the first starts the third and keeps the first; watching the second
    /// removes the first and starts the fourth (the owner's rules).
    @MainActor
    func testKeepReadyStartsTheNextAndRemovesAnEpisodeOnlyOnceTheNextIsFinished() {
        let app = launch()
        openPanel(app)
        element(app, "keep-ready-minus").tap()
        element(app, "download-panel-go").tap()
        XCTAssertTrue(waitUntil(90) { self.onDevice(app) == ["e1", "e2"] }, "the next two did not arrive: \(onDevice(app))")

        // The first is finished, on its own page; the series page reads again.
        XCTAssertTrue(element(app, "storage-bar-line").waitForNonExistence(timeout: 20), "the bar stayed up")
        markWatched(app, episode: "e1", card: "1. The Beginning")
        pullToRefresh(app)
        XCTAssertTrue(waitUntil(90) { self.onDevice(app) == ["e1", "e2", "e3"] },
                      "the third did not start, and the first stays: \(onDevice(app))")

        // The second is finished: the first goes, the fourth starts.
        XCTAssertTrue(element(app, "storage-bar-line").waitForNonExistence(timeout: 20), "the bar stayed up")
        markWatched(app, episode: "e2", card: "2. A Second Look")
        pullToRefresh(app)
        XCTAssertTrue(waitUntil(90) { self.onDevice(app) == ["e2", "e3", "s2e1"] },
                      "the first was not removed or the next did not start (the one just finished stays): \(onDevice(app))")
    }

    @MainActor
    private func markWatched(_ app: XCUIApplication, episode: String, card title: String) {
        let card = self.card(app, episode)
        // The page was pulled to its top: the strip is below the fold, and builds its cards when it comes into view.
        let window = app.windows.firstMatch
        for _ in 0..<6 where !card.exists {
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
                .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)))
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        XCTAssertTrue(card.waitForExistence(timeout: 60), "no card \(title): \(buttons(app))")
        let details = app.buttons["Episode details"].firstMatch
        hold(card, until: details)
        XCTAssertTrue(details.waitForExistence(timeout: 5), "the card's menu has no Episode details: \(buttons(app))")
        details.tap()
        let watched = app.buttons["Mark watched"].firstMatch
        XCTAssertTrue(watched.waitForExistence(timeout: 15), "the episode's page has no Mark watched: \(buttons(app))")
        watched.tap()
        XCTAssertTrue(app.buttons["Mark unwatched"].firstMatch.waitForExistence(timeout: 10), "it was not marked")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Back to'")).firstMatch.tap()
        XCTAssertTrue(element(app, "downloaded-episodes").waitForExistence(timeout: 15), "the series page did not come back")
    }

    /// Back to the top of the page, then pulled down: the page reads the hub again.
    @MainActor
    private func pullToRefresh(_ app: XCUIApplication) {
        let window = app.windows.firstMatch
        func drag(_ from: CGFloat, _ to: CGFloat) {
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from))
                .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to)))
        }
        for _ in 0..<3 { drag(0.25, 0.9) }
        drag(0.3, 0.8)
        RunLoop.current.run(until: Date().addingTimeInterval(1))
    }

    // MARK: The bar and the cards

    /// While the bar is up, the cards can be scrolled clear above it: the scroll content is padded by
    /// its height, so it never has to cover an episode's title. Upright, and turned on its side.
    @MainActor
    func testTheBarNeverCoversTheEpisodeTitlesOnceTheCardsAreScrolledClear() {
        for orientation in [UIDeviceOrientation.portrait, .landscapeLeft] {
            XCUIDevice.shared.orientation = orientation
            Thread.sleep(forTimeInterval: 1.5)
            let app = launch(pinnedBar: true)
            let bar = element(app, "storage-bar")
            XCTAssertTrue(bar.waitForExistence(timeout: 30), "the bar did not rise (\(orientation.rawValue))")
            // All the way down the page.
            let window = app.windows.firstMatch
            for _ in 0..<6 {
                window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8))
                    .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2)))
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
            XCTAssertTrue(bar.exists, "the bar was gone before it was measured (\(orientation.rawValue))")
            let first = card(app, "e1")
            XCTAssertTrue(first.exists, "no first card (\(orientation.rawValue)): \(buttons(app))")
            XCTAssertLessThanOrEqual(first.frame.maxY, bar.frame.minY + 1,
                                     "the bar covers the card's title (\(orientation.rawValue)): card \(first.frame), bar \(bar.frame)")
            app.terminate()
        }
    }

    // MARK: Select mode

    /// Choose episodes in the panel: the top is Cancel, how many and Select
    /// season, ticks stay across seasons, what is here cannot be ticked, and
    /// Download goes back to the page with rings.
    @MainActor
    func testSelectModeTicksAcrossSeasonsAndDownloadsWhatIsTicked() {
        let app = launch()
        // One is on its way already: it cannot be ticked.
        startDownload(app, "e1")

        openPanel(app)
        element(app, "choose-episodes").tap()
        let count = element(app, "select-count")
        XCTAssertTrue(count.waitForExistence(timeout: 10), "select mode did not start: \(buttons(app))")
        XCTAssertEqual(count.label, "0 selected")
        XCTAssertTrue(element(app, "select-cancel").exists && element(app, "select-season").exists)
        XCTAssertTrue(element(app, "select-total").exists, "the bottom bar has no total")

        card(app, "e1").tap()
        XCTAssertEqual(count.label, "0 selected", "an episode already coming cannot be ticked")

        // Select season ticks the rest of Season 1; ticks survive the next season.
        element(app, "select-season").tap()
        XCTAssertTrue(waitUntil(5) { count.label == "2 selected" }, "Select season ticked \(count.label)")
        XCTAssertEqual(element(app, "select-season").label, "Unselect season")
        XCTAssertTrue(pill(app, "Season 1 · 2/2").exists, "the pill does not count its ticks: \(buttons(app))")
        pill(app, "Season 2").tap()
        XCTAssertTrue(card(app, "s2e1").waitForExistence(timeout: 10))
        card(app, "s2e1").tap()
        XCTAssertTrue(waitUntil(5) { count.label == "3 selected" }, "\(count.label)")
        XCTAssertEqual(element(app, "select-season").label, "Select season")
        XCTAssertTrue(pill(app, "Season 2 · 1/4").waitForExistence(timeout: 5), "Season 2 does not count: \(buttons(app))")
        pill(app, "Season 1").tap()
        XCTAssertTrue(waitUntil(5) { self.element(app, "select-season").label == "Unselect season" }, "the ticks were lost")

        element(app, "select-download").tap()
        XCTAssertTrue(count.waitForNonExistence(timeout: 10), "select mode stayed")
        for episode in ["e2", "e3"] {
            XCTAssertNotEqual(cornerLabel(app, episode), "Download", "\(episode) was not asked for")
        }
        pill(app, "Season 2").tap()
        XCTAssertTrue(waitUntil(10) { self.cornerLabel(app, "s2e1") != "Download" }, "the Season 2 episode was not asked for")
        XCTAssertEqual(cornerLabel(app, "s2e2"), "Download", "one that was not ticked is left alone")
    }

    /// A card held offers Select episodes and starts with that card ticked; Cancel leaves with nothing asked.
    @MainActor
    func testHoldingACardStartsSelectModeWithItTickedAndCancelAsksForNothing() {
        let app = launch()
        let first = card(app, "e2")
        XCTAssertTrue(first.waitForExistence(timeout: 25))
        XCTAssertTrue(element(app, "download-season").waitForExistence(timeout: 45), "the hub's listing did not arrive")
        let select = app.buttons["Select episodes"].firstMatch
        hold(first, until: select)
        XCTAssertTrue(select.waitForExistence(timeout: 5), "the card's menu has no Select episodes: \(buttons(app))")
        // The menu has the card's download, too.
        XCTAssertTrue(app.buttons["Download"].firstMatch.exists, "no Download in the card's menu: \(buttons(app))")
        select.tap()
        let count = element(app, "select-count")
        XCTAssertTrue(count.waitForExistence(timeout: 10))
        XCTAssertEqual(count.label, "1 selected")
        element(app, "select-cancel").tap()
        XCTAssertTrue(count.waitForNonExistence(timeout: 10), "Cancel stayed in select mode")
        XCTAssertEqual(cornerLabel(app, "e2"), "Download", "Cancel asked for nothing")
        XCTAssertFalse(element(app, "storage-bar-line").exists)
    }
}
