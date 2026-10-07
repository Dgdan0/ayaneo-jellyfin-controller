import XCTest

/// The Notifications page, its settings and the server monitor (#36), against
/// the demo hub (`-demo`). Its seen list starts with the older entries seen and
/// the newest of each service unread: five on the Media side (three of
/// Sonarr's, one each of Radarr's and Bazarr's) and one of BookKeeprr's.
final class NotificationsTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// `dwell` keeps rows on screen from being seen on their own, so a test
    /// can read what is unread; the dwell test leaves it alone.
    @MainActor
    private func launch(section: String = "notifications", side: String = "media", open: String = "",
                        dwell: String = "600000") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": section, "HUB_SIDE": side, "HUB_OPEN": open, "HUB_SEEN_DWELL_MS": dwell]
        app.launch()
        return app
    }

    /// Whatever kind of element carries `id`: a combined or ignored group is
    /// not always the same type.
    @MainActor
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// Any element whose label contains `words`.
    @MainActor
    private func text(_ app: XCUIApplication, containing words: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }

    @MainActor
    private func buttons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.map { $0.label }.filter { !$0.isEmpty }.joined(separator: " | ")
    }

    @MainActor
    private func notice(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.buttons["notice-\(id)"] }

    /// The bell: Settings' own pill for this page is also called Notifications.
    @MainActor
    private func bellButton(_ app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == 'Notifications' AND NOT (identifier BEGINSWITH 'pane-')")).firstMatch
    }

    /// The bell's count: its value, "" when there is none.
    @MainActor
    private func bell(_ app: XCUIApplication) -> String { (bellButton(app).value as? String) ?? "" }

    // MARK: The page

    @MainActor
    func testEachServiceHasAColumnWithItsUnreadCountAndTheWarningPinnedFirst() {
        let app = launch()
        XCTAssertTrue(element(app, "column-sonarr").waitForExistence(timeout: 15), "no Sonarr column: \(buttons(app))")
        for service in ["radarr", "bazarr"] {
            XCTAssertTrue(element(app, "column-\(service)").exists, "no \(service) column")
        }
        XCTAssertFalse(element(app, "column-kavita").exists, "the Books side's services are on the Media page")
        // Three of Sonarr's, one of Radarr's, one of Bazarr's; one more waits in Books.
        XCTAssertEqual(element(app, "unread-sonarr").label, "3 unread Sonarr notifications")
        XCTAssertEqual(element(app, "unread-radarr").label, "1 unread Radarr notifications")
        XCTAssertEqual(element(app, "unread-bazarr").label, "1 unread Bazarr notifications")
        XCTAssertEqual(element(app, "notifications-status").label,
                       "5 unread notifications · 1 in Books · Bazarr, Storyteller unavailable")
        XCTAssertEqual(bell(app), "6", "the bell counts every service")
        // The health warning leads its column, in words, and says it needs attention now.
        let warning = notice(app, "sonarr:health:indexer")
        XCTAssertTrue(warning.exists)
        XCTAssertTrue(warning.label.contains("Indexer Long Term Status Check"), warning.label)
        XCTAssertTrue(warning.label.hasSuffix("Needs attention now"), warning.label)
        let ids = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'notice-sonarr:'")).allElementsBoundByIndex.map(\.identifier)
        XCTAssertEqual(ids.first, "notice-sonarr:health:indexer", "the warning is not first: \(ids)")
        XCTAssertEqual(notice(app, "sonarr:history:9001").value as? String, "Unread")
        XCTAssertEqual(notice(app, "sonarr:history:8990").value as? String, "", "an older entry starts seen")
    }

    @MainActor
    func testTappingARowMarksItSeenAndLowersTheCountsTogether() {
        let app = launch()
        let row = notice(app, "sonarr:history:9001")
        XCTAssertTrue(row.waitForExistence(timeout: 15))
        XCTAssertEqual(element(app, "unread-sonarr").label, "3 unread Sonarr notifications")
        row.tap()
        XCTAssertEqual(row.value as? String, "Expanded", "the row is still unread after a tap")
        XCTAssertEqual(element(app, "unread-sonarr").label, "2 unread Sonarr notifications")
        XCTAssertTrue(element(app, "notifications-status").label.hasPrefix("4 unread notifications · 1 in Books"),
                      element(app, "notifications-status").label)
        XCTAssertEqual(bell(app), "5", "the bell is the same answer")
        // Tapped again, it closes.
        row.tap()
        XCTAssertEqual(row.value as? String, "")
    }

    @MainActor
    func testMarkAllSeenClearsTheDotsTheCountsAndTheBell() {
        let app = launch()
        let all = app.buttons["mark-all-seen"]
        XCTAssertTrue(all.waitForExistence(timeout: 15), "no Mark all seen: \(buttons(app))")
        all.tap()
        XCTAssertEqual(element(app, "notifications-status").label, "All notifications marked as seen")
        XCTAssertFalse(element(app, "unread-sonarr").exists, "a column still has a count")
        XCTAssertEqual(notice(app, "sonarr:history:9001").value as? String, "")
        XCTAssertFalse(all.exists, "Mark all seen stays when nothing is unread")
        XCTAssertEqual(bell(app), "", "the bell still counts after Mark all seen")
        // Books' own entry was marked too: it is every service's.
        app.buttons["Books and comics"].firstMatch.tap()
        XCTAssertTrue(element(app, "column-bookkeeprr").waitForExistence(timeout: 10))
        XCTAssertFalse(element(app, "unread-bookkeeprr").exists)
    }

    @MainActor
    func testAColumnShowsItsNewestAndShowAllOpensTheRest() {
        let app = launch()
        let more = app.buttons["more-sonarr"]
        XCTAssertTrue(more.waitForExistence(timeout: 15), "Sonarr has more than a column shows: \(buttons(app))")
        XCTAssertTrue(more.label.hasPrefix("Show all "), more.label)
        let oldest = notice(app, "sonarr:history:8016")
        XCTAssertFalse(oldest.exists, "the oldest entry is shown before Show all")
        more.tap()
        XCTAssertEqual(app.buttons["more-sonarr"].label, "Show fewer")
        for _ in 0..<12 where !oldest.exists { app.swipeUp() }
        XCTAssertTrue(oldest.exists, "Show all did not show the oldest entry")
    }

    @MainActor
    func testTheBooksSideShowsItsOwnColumnsAndTheOtherSidesCount() {
        let app = launch(side: "books")
        XCTAssertTrue(element(app, "column-bookkeeprr").waitForExistence(timeout: 15), "no BookKeeprr column: \(buttons(app))")
        XCTAssertTrue(element(app, "column-kavita").exists && element(app, "column-storyteller").exists)
        XCTAssertFalse(element(app, "column-sonarr").exists)
        XCTAssertEqual(element(app, "unread-bookkeeprr").label, "1 unread BookKeeprr notifications")
        // Storyteller cannot be reached: its column says so instead of "No recent activity".
        XCTAssertTrue(text(app, containing: "Service unavailable").exists)
        XCTAssertEqual(element(app, "notifications-status").label,
                       "1 unread notification · 5 in Movies and TV · Bazarr, Storyteller unavailable")
    }

    @MainActor
    func testARowOnScreenForAMomentIsSeenWithoutATap() {
        let app = launch(dwell: "1200")
        let row = notice(app, "sonarr:history:9001")
        XCTAssertTrue(row.waitForExistence(timeout: 15))
        expectation(for: NSPredicate(format: "value == ''"), evaluatedWith: row)
        waitForExpectations(timeout: 12)
        XCTAssertEqual(row.value as? String, "")
    }

    // MARK: Settings

    @MainActor
    func testHistoryLengthsAreChosenInSettingsAndTheColumnTakesThem() {
        var app = launch(section: "settings")
        var pane = app.buttons["pane-notifications"]
        XCTAssertTrue(pane.waitForExistence(timeout: 15), "Settings has no Notifications: \(buttons(app))")
        pane.tap()
        let short = app.buttons["limit-sonarr-20"]
        XCTAssertTrue(short.waitForExistence(timeout: 5), "no history length for Sonarr: \(buttons(app))")
        short.tap()
        XCTAssertTrue(text(app, containing: "Sonarr history, 20 entries").waitForExistence(timeout: 5), "the chosen length is not named")
        // The page asks again with it: twenty of the history, and Show all says so.
        bellButton(app).tap()
        let more = app.buttons["more-sonarr"]
        XCTAssertTrue(more.waitForExistence(timeout: 15), "Notifications has no Show all: \(buttons(app))")
        XCTAssertEqual(more.label, "Show all 20")
        // Back to the default, which the other tests and the next run expect.
        app.terminate()
        app = launch(section: "settings")
        pane = app.buttons["pane-notifications"]
        XCTAssertTrue(pane.waitForExistence(timeout: 15))
        pane.tap()
        let restore = app.buttons["limit-sonarr-60"]
        XCTAssertTrue(restore.waitForExistence(timeout: 5))
        restore.tap()
        XCTAssertTrue(text(app, containing: "Sonarr history, 60 entries").waitForExistence(timeout: 5))
    }

    // MARK: The server monitor

    @MainActor
    func testTheMonitorShowsTheFiguresTheDisksWhoIsPlayingAndTheContainers() {
        let app = launch(section: "services", open: "monitor")
        let cpu = element(app, "figure-CPU")
        XCTAssertTrue(cpu.waitForExistence(timeout: 15), "the monitor did not open: \(buttons(app))")
        XCTAssertEqual(cpu.label, "CPU, 15%, Over a short sample")
        XCTAssertEqual(element(app, "figure-Memory").label, "Memory, 15.0 GB, of 32.0 GB")
        XCTAssertEqual(element(app, "figure-Up for").label, "Up for, 4 days 5 hours, Since the PC last started")
        XCTAssertTrue(text(app, containing: "Windows 11 Pro host · checked ").waitForExistence(timeout: 5))
        XCTAssertTrue(text(app, containing: "refreshes every 15 seconds").exists)
        func reveal(_ words: String) -> Bool {
            let found = text(app, containing: words)
            for _ in 0..<8 where !(found.exists && found.isHittable) { app.swipeUp() }
            return found.exists
        }
        XCTAssertTrue(reveal("3 with space the hub can see"))
        // The nearly full disk says so in words as well as colour.
        XCTAssertTrue(reveal("E:, 384 GB free of 7.3 TB, nearly full"))
        XCTAssertTrue(reveal("G:\\ space unavailable"), "the hub's disk warning is not shown")
        XCTAssertTrue(reveal("Living Room TV · Jellyfin Android TV · Direct play"))
        XCTAssertTrue(reveal("Daniel's iPad · JellyHub · Paused"))
        XCTAssertTrue(reveal("2 of 3 running"))
        XCTAssertTrue(reveal("Up 4 days (unhealthy)"))
        XCTAssertTrue(reveal("Exited (0) 2 days ago"))
    }

    @MainActor
    func testTheMonitorAsksAgainOnItsOwn() {
        let app = launch(section: "services", open: "monitor")
        let status = element(app, "monitor-status")
        XCTAssertTrue(status.waitForExistence(timeout: 15))
        let first = status.label
        XCTAssertTrue(first.contains("checked "), first)
        // Every fifteen seconds: the "checked" time moves.
        expectation(for: NSPredicate(format: "label != %@", first), evaluatedWith: status)
        waitForExpectations(timeout: 25)
    }

    @MainActor
    func testTheActivityStorageCardOpensTheMonitor() {
        let app = launch(section: "activity")
        let storage = app.buttons["storage-card"]
        XCTAssertTrue(storage.waitForExistence(timeout: 15), "Activity has no Storage card: \(buttons(app))")
        for _ in 0..<6 where !storage.isHittable { app.swipeUp() }
        storage.tap()
        XCTAssertTrue(element(app, "figure-CPU").waitForExistence(timeout: 15), "Storage did not open the monitor")
    }
}
