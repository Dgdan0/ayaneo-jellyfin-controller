import XCTest

/// A keyboard driving the app as a controller does (#46): the arrows move a
/// ring from item to item, a row by position and the page row by row, Return
/// presses the item, and the ring comes back to where it was. Against the
/// demo hub (`-demo`); a debug build's probe `pad-focus` says where the focus
/// is ("ring books-home hero-resume").
///
/// Return is typed as "\n": XCUITest's `.return` never reaches the app's key
/// commands in the simulator, "\n" is the Return key. Escape (Ⓑ) reaches
/// nothing in the simulator however it is typed, so Back is the back button here.
final class PadFocusTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// `pad` is a controller's presses (`HUB_PAD`, one a second from `padDelay` seconds after launch).
    @MainActor
    private func launch(side: String, section: String, pad: String = "", padDelay: Int = 6) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": section, "HUB_SIDE": side]
        if !pad.isEmpty {
            app.launchEnvironment["HUB_PAD"] = pad
            app.launchEnvironment["HUB_PAD_DELAY"] = String(padDelay)
        }
        app.launch()
        XCTAssertTrue(app.staticTexts["pad-focus"].firstMatch.waitForExistence(timeout: 20), "no focus probe")
        return app
    }

    /// An iPad lays Books home's hero out sideways: the cover with Resume and
    /// Details to its right, so Right, not Down, goes from the cover to Resume.
    private var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    /// From the hero's cover (the first press) to Resume reading.
    @MainActor
    private func coverToResume(_ app: XCUIApplication) {
        press(app, isPad ? .rightArrow : .downArrow)
    }

    /// Presses `key` until the focus starts with `prefix`, at most `limit` times.
    @MainActor
    private func press(_ app: XCUIApplication, _ key: XCUIKeyboardKey, until prefix: String, limit: Int) {
        var steps = 0
        while !focus(app).hasPrefix(prefix), steps < limit {
            press(app, key)
            steps += 1
        }
    }

    /// Where the focus is, as the probe says.
    @MainActor
    private func focus(_ app: XCUIApplication) -> String {
        app.staticTexts["pad-focus"].firstMatch.label
    }

    /// Presses `key` and waits a moment for the focus to settle.
    @MainActor
    private func press(_ app: XCUIApplication, _ key: XCUIKeyboardKey, times: Int = 1) {
        for _ in 0..<times {
            app.typeKey(key, modifierFlags: [])
            RunLoop.current.run(until: Date().addingTimeInterval(0.35))
        }
    }

    @MainActor
    private func pressReturn(_ app: XCUIApplication) {
        app.typeKey("\n", modifierFlags: [])
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
    }

    /// Whether `condition` comes true within `seconds`.
    @MainActor
    private func waitUntil(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        return condition()
    }

    /// Waits until the focus satisfies `condition`.
    @MainActor
    private func waitFor(_ app: XCUIApplication, _ seconds: TimeInterval = 8, _ condition: (String) -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if condition(focus(app)) { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        return condition(focus(app))
    }

    /// Books home: the first press shows the ring on the hero's cover, then down
    /// to Resume, across to Details, down to the books also being read, past an
    /// empty Want to Read to the shelves; Return opens a book, and coming back
    /// the ring is where it was.
    @MainActor
    func testBooksHomeGoesRowByRowAndReturnOpensABook() {
        let app = launch(side: "books", section: "home")
        XCTAssertTrue(app.buttons["books-resume"].waitForExistence(timeout: 20), "Books home did not load")
        XCTAssertEqual(focus(app), "none", "focus showed before a key was pressed")
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring books-home hero-cover", "the first press did not show the page's first item")
        coverToResume(app)
        XCTAssertEqual(focus(app), "ring books-home hero-resume")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring books-home hero-details")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring books-home hero-details", "right went past the end of the hero's pair")
        press(app, .downArrow)
        XCTAssertTrue(focus(app).hasPrefix("ring books-home also/"), "down did not reach Also reading: \(focus(app))")
        // Also reading is a grid: two lines of it on a phone, one on an iPad.
        press(app, .downArrow, until: "ring books-home series/", limit: 3)
        XCTAssertTrue(focus(app).hasPrefix("ring books-home series/"), "down did not reach Your series: \(focus(app))")
        // Into the first shelf at the book nearest the series card, which on a
        // wider phone (the Pro Max) is its second; left to its first.
        press(app, .downArrow)
        XCTAssertTrue(focus(app).hasPrefix("ring books-home shelf-comics/"), "down did not reach the first shelf: \(focus(app))")
        var steps = 0
        while focus(app) != "ring books-home shelf-comics/0", steps < 4 {
            press(app, .leftArrow)
            steps += 1
        }
        XCTAssertEqual(focus(app), "ring books-home shelf-comics/0", "left did not reach the first shelf's first book")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring books-home shelf-comics/1", "right did not go along the shelf by position")
        // Past the empty Want to Read, to the next shelf with books.
        press(app, .downArrow)
        XCTAssertTrue(focus(app).hasPrefix("ring books-home shelf-recently-added/"), "an empty shelf stopped the ring: \(focus(app))")
        press(app, .upArrow)
        XCTAssertEqual(focus(app), "ring books-home shelf-comics/1", "up did not come back to the book left")

        // Return opens it; its page takes the ring.
        pressReturn(app)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring book:") }, "Return did not open the book: \(focus(app))")
        // Back (the back button: Escape reaches nothing in the simulator), then a
        // press: the ring is on the book it opened.
        let back = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Back to'")).firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5))
        back.tap()
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("hidden ") || $0 == "none" }, "the tap left the ring: \(focus(app))")
        press(app, .rightArrow)
        XCTAssertTrue(waitFor(app) { $0 == "ring books-home shelf-comics/1" }, "Back did not return to the book: \(focus(app))")
    }

    /// Up from the page's top reaches the bar; down from the bar, and up from
    /// the iPhone's tab bar, return to the page where it was.
    @MainActor
    func testTheBarsAreReachedAndLeftWithTheArrows() {
        let app = launch(side: "books", section: "home")
        XCTAssertTrue(app.buttons["books-resume"].waitForExistence(timeout: 20))
        press(app, .downArrow)
        coverToResume(app)
        XCTAssertEqual(focus(app), "ring books-home hero-resume")
        press(app, .upArrow, until: "ring bar bar-", limit: 3)
        XCTAssertTrue(focus(app).hasPrefix("ring bar bar-"), "up did not reach the top bar: \(focus(app))")
        press(app, .rightArrow)
        XCTAssertTrue(focus(app).hasPrefix("ring bar bar-"), "right left the bar: \(focus(app))")
        press(app, .downArrow)
        // Where the page was left: the cover on a phone (up passes it), Resume on an iPad.
        XCTAssertTrue(["ring books-home hero-cover", "ring books-home hero-resume"].contains(focus(app)),
                      "down from the bar did not return to the page: \(focus(app))")
        // An iPad has no tab bar at the foot: the rest is the iPhone's.
        guard !isPad else { return }
        // To the foot of the page, then the tab bar, and back.
        press(app, .downArrow, times: 12)
        XCTAssertTrue(focus(app).hasPrefix("ring bar tab-"), "down past the page did not reach the tab bar: \(focus(app))")
        press(app, .upArrow)
        XCTAssertEqual(focus(app), "ring books-home new-list", "up from the tab bar did not return to the page")
        // Return on a tab chooses its section.
        press(app, .downArrow)
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring bar tab-discover")
        pressReturn(app)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == 'Discover' AND selected == true")).firstMatch
                        .waitForExistence(timeout: 5), "Return did not choose Discover")
    }

    /// The Books library: the libraries as a grid, one opened with Return, its
    /// views and order controls, then its covers.
    @MainActor
    func testTheBooksLibraryIsDrivenByTheKeys() {
        let app = launch(side: "books", section: "library")
        XCTAssertTrue(app.buttons["reading-lists"].waitForExistence(timeout: 20), "the libraries did not load")
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring books-libraries arrange")
        press(app, .downArrow)
        XCTAssertTrue(focus(app).hasPrefix("ring books-libraries libraries/"), "down did not reach the libraries: \(focus(app))")
        // Into the grid at the library nearest Arrange: on an iPad's wider grid not the first, so left to it.
        press(app, .leftArrow, until: "ring books-libraries libraries/storyteller:books", limit: 4)
        press(app, .upArrow, until: "ring books-libraries libraries/storyteller:books", limit: 2)
        XCTAssertEqual(focus(app), "ring books-libraries libraries/storyteller:books")
        pressReturn(app)
        XCTAssertTrue(waitFor(app) { $0 == "ring library:storyteller:books views/series" },
                      "the library did not open on its views: \(focus(app))")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring library:storyteller:books views/authors")
        pressReturn(app)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label MATCHES '[0-9]+ authors?'")).firstMatch
                        .waitForExistence(timeout: 10), "Return did not show the authors")
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring library:storyteller:books sort-direction", "down did not reach the order")
        press(app, .downArrow)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring library:storyteller:books grid/") },
                      "down did not reach the authors: \(focus(app))")
        press(app, .upArrow, times: 2)
        XCTAssertTrue(focus(app).hasPrefix("ring library:storyteller:books views/"), "up did not come back to the views: \(focus(app))")
    }

    /// The Series view's fans (#54): the keys reach a series' fan, which opens
    /// while the ring is on it and closes again as the ring leaves, and
    /// Return opens the series at the book you are on.
    @MainActor
    func testASeriesFanOpensUnderTheRingAndReturnOpensTheSeries() {
        let app = launch(side: "books", section: "library")
        XCTAssertTrue(app.buttons["reading-lists"].waitForExistence(timeout: 20), "the libraries did not load")
        press(app, .downArrow, times: 2)
        press(app, .leftArrow, until: "ring books-libraries libraries/storyteller:books", limit: 4)
        press(app, .upArrow, until: "ring books-libraries libraries/storyteller:books", limit: 2)
        XCTAssertEqual(focus(app), "ring books-libraries libraries/storyteller:books")
        pressReturn(app)
        XCTAssertTrue(waitFor(app) { $0 == "ring library:storyteller:books views/series" }, "the library did not open: \(focus(app))")
        let fan = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Red Rising, 6 books'")).firstMatch
        XCTAssertTrue(fan.waitForExistence(timeout: 10), "Red Rising is not a fan")
        XCTAssertEqual(fan.value as? String, "fan at rest", "the fan is open before the ring is on it")
        // Down into the grid, then along it to Red Rising.
        press(app, .downArrow, until: "ring library:storyteller:books grid/", limit: 3)
        XCTAssertTrue(focus(app).hasPrefix("ring library:storyteller:books grid/"), "down did not reach the grid: \(focus(app))")
        for key in [XCUIKeyboardKey.rightArrow, .downArrow, .leftArrow, .downArrow, .rightArrow] {
            press(app, key, until: "ring library:storyteller:books grid/rw_demo_redrising", limit: 4)
        }
        XCTAssertEqual(focus(app), "ring library:storyteller:books grid/rw_demo_redrising")
        XCTAssertTrue(waitUntil(5) { (fan.value as? String) == "fan open" }, "the fan did not open under the ring: \(String(describing: fan.value))")
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "series-fan-open"
        shot.lifetime = .keepAlways
        add(shot)
        // The ring moves on: it closes.
        press(app, .leftArrow)
        if focus(app).hasSuffix("grid/rw_demo_redrising") { press(app, .rightArrow) }
        XCTAssertTrue(waitUntil(5) { (fan.value as? String) == "fan at rest" }, "the fan stayed open: \(focus(app))")
        // Back, and Return: the series, its books' row at Light Bringer (#6).
        press(app, .rightArrow, until: "ring library:storyteller:books grid/rw_demo_redrising", limit: 2)
        press(app, .leftArrow, until: "ring library:storyteller:books grid/rw_demo_redrising", limit: 2)
        pressReturn(app)
        let lightBringer = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Light Bringer, #6'")).firstMatch
        XCTAssertTrue(lightBringer.waitForExistence(timeout: 10), "Return did not open Red Rising")
        let window = app.windows.firstMatch.frame
        XCTAssertTrue(waitUntil(5) { lightBringer.frame.minX >= window.minX - 1 && lightBringer.frame.maxX <= window.maxX + 1 },
                      "the row is not at Light Bringer: \(lightBringer.frame)")
    }

    /// Downloads: its three pills in a row, each chosen with Return.
    @MainActor
    func testDownloadsPillsAreChosenWithReturn() {
        let app = launch(side: "books", section: "downloads")
        XCTAssertTrue(app.buttons["downloads-books"].waitForExistence(timeout: 20))
        // This side's pills: the other side's Downloads, kept out of sight, has its own.
        func pill(_ id: String) -> XCUIElement {
            app.buttons.matching(NSPredicate(format: "identifier == %@ AND enabled == true", id)).firstMatch
        }
        press(app, .downArrow)
        XCTAssertEqual(focus(app), "ring downloads-books tabs/books")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring downloads-books tabs/device")
        pressReturn(app)
        XCTAssertTrue(pill("downloads-device").wait(for: \.isSelected, toEqual: true, timeout: 5),
                      "Return did not choose Films and TV")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring downloads-books tabs/queue")
        pressReturn(app)
        XCTAssertTrue(pill("downloads-queue").wait(for: \.isSelected, toEqual: true, timeout: 5),
                      "Return did not choose the queue: \(focus(app))")
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring downloads-books tabs/queue", "right went past the last pill")
        press(app, .leftArrow, times: 2)
        app.typeKey(" ", modifierFlags: [])
        XCTAssertTrue(pill("downloads-books").wait(for: \.isSelected, toEqual: true, timeout: 5), "Space did not choose Books")
    }

    /// The panel a hold menu or ⋯ opens: its heading.
    @MainActor
    private func menuTitle(_ app: XCUIApplication) -> XCUIElement {
        app.staticTexts["pad-menu-title"].firstMatch
    }

    /// Waits until `element` exists with `label`, or no longer exists when `label` is nil.
    @MainActor
    private func waitLabel(_ element: XCUIElement, _ label: String?, _ seconds: TimeInterval = 10) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if let label { if element.exists && element.label == label { return true } } else if !element.exists { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.15))
        }
        return false
    }

    /// Ⓨ on a book opens its hold menu (#46, D): the context menu's choices as
    /// rows the ring walks. Ⓐ on Add to a list opens its lists in place, Ⓑ goes
    /// back out of them, Ⓑ again closes the menu, and the ring is on the book.
    @MainActor
    func testYOpensABooksHoldMenuThatTheRingWalksAndBClosesIt() {
        // To the first book also being read: past Resume on a phone; on an iPad Resume is beside the cover.
        let app = launch(side: "books", section: "home", pad: isPad ? "DOWN,DOWN,Y,A,B,B" : "DOWN,DOWN,DOWN,Y,A,B,B")
        XCTAssertTrue(app.buttons["books-resume"].waitForExistence(timeout: 20), "Books home did not load")
        let title = menuTitle(app)
        XCTAssertTrue(waitLabel(title, "Light Bringer", 15), "Y did not open the book's hold menu: \(focus(app))")
        XCTAssertTrue(app.buttons["pad-choice-add-to-list"].exists, "the hold menu has no Add to a list")
        XCTAssertTrue(focus(app).hasPrefix("ring menu:") && focus(app).hasSuffix(" choices/add-to-list"),
                      "the ring did not start on the menu's first choice: \(focus(app))")
        XCTAssertTrue(waitLabel(title, "Add to a list"), "A did not open Add to a list's lists")
        XCTAssertTrue(app.buttons["pad-choice-new-list"].exists, "Add to a list has no New list")
        XCTAssertTrue(waitLabel(title, "Light Bringer"), "B did not go back out of the lists")
        XCTAssertTrue(waitLabel(title, nil), "B did not close the menu")
        XCTAssertTrue(waitFor(app) { $0 == "ring books-home also/rw_demo_rr6" }, "the ring is not back on the book: \(focus(app))")
    }

    /// Ⓑ closes what a page presents before anything else: an alert stays
    /// over the page, which neither moves nor goes back under it.
    @MainActor
    func testBClosesAnAlertFirst() {
        let app = launch(side: "books", section: "home", pad: "DOWN,B,B", padDelay: 14)
        XCTAssertTrue(app.buttons["books-resume"].waitForExistence(timeout: 20))
        // New list asks its name in an alert.
        app.buttons["books-new-list"].firstMatch.tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 5), "New list did not ask for a name")
        // DOWN does nothing under it; the first B closes it; the second has nothing to close, and Home has nowhere to go back to.
        XCTAssertTrue(alert.waitForNonExistence(timeout: 20), "B did not close the alert")
        XCTAssertEqual(focus(app), "none", "the ring moved under the alert")
        XCTAssertTrue(app.buttons["books-resume"].exists, "B went away from Home")
    }

    /// A confirmation asked while the ring is in use comes as rows the ring
    /// walks, the harmless answer first (a controller cannot answer an alert):
    /// Cancel transfer on the Books side's Activity, kept.
    @MainActor
    func testAConfirmationIsAPanelWhileTheRingIsInUse() {
        let app = launch(side: "books", section: "activity")
        let cancel = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'cancel-'")).firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 20), "the Books side's Activity has no transfer to cancel")
        var steps = 0
        while !focus(app).contains(" cancel-"), steps < 12 {
            press(app, .downArrow)
            if !focus(app).contains(" cancel-") { press(app, .rightArrow) }
            steps += 1
        }
        XCTAssertTrue(focus(app).contains(" cancel-"), "the keys did not reach Cancel transfer: \(focus(app))")
        pressReturn(app)
        XCTAssertTrue(waitLabel(menuTitle(app), "Cancel transfer?"), "Cancel transfer did not ask as the panel")
        XCTAssertFalse(app.alerts.firstMatch.exists, "it asked with an alert as well")
        XCTAssertTrue(waitFor(app) { $0.hasSuffix(" choices/keep") }, "the harmless answer is not first: \(focus(app))")
        pressReturn(app)
        XCTAssertTrue(waitLabel(menuTitle(app), nil), "Keep transfer did not close the panel")
        XCTAssertTrue(cancel.exists, "keeping the transfer cancelled it")
    }

    /// A book's page: down to ⋯, whose Return opens its choices as rows the
    /// ring walks; Finished there opens the Finished panel, which takes the ring.
    @MainActor
    func testABooksMoreOpensItsChoicesAndTheFinishedPanel() {
        let app = launch(side: "books", section: "home")
        XCTAssertTrue(app.buttons["books-resume"].waitForExistence(timeout: 20))
        press(app, .downArrow)
        coverToResume(app)
        press(app, .rightArrow)
        XCTAssertEqual(focus(app), "ring books-home hero-details")
        pressReturn(app)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring book:") }, "Details did not open the book")
        var steps = 0
        while !focus(app).hasSuffix(" more"), steps < 14 {
            if focus(app).hasSuffix(" entry") { press(app, .rightArrow) } else { press(app, .downArrow) }
            steps += 1
        }
        XCTAssertTrue(focus(app).hasSuffix(" more"), "the keys did not reach ⋯: \(focus(app))")
        pressReturn(app)
        XCTAssertTrue(waitLabel(menuTitle(app), "More actions for Dark Matter"),
                      "⋯ did not open its choices: \(focus(app)) · \(menuTitle(app).exists ? menuTitle(app).label : "no menu")")
        XCTAssertTrue(waitFor(app) { $0.hasSuffix(" choices/finished") }, "the ring did not start on Finished: \(focus(app))")
        // Down the choices and back: Start over (#60: Dark Matter has a place), then Want to read; Return on Finished chooses it.
        press(app, .downArrow)
        XCTAssertTrue(focus(app).hasSuffix(" choices/start-over"), "down did not reach Start over: \(focus(app))")
        press(app, .downArrow)
        XCTAssertTrue(focus(app).hasSuffix(" choices/want"), "down did not reach Want to read: \(focus(app))")
        press(app, .upArrow)
        press(app, .upArrow)
        XCTAssertTrue(focus(app).hasSuffix(" choices/finished"), "up did not come back to Finished: \(focus(app))")
        pressReturn(app)
        XCTAssertTrue(app.staticTexts["When did you finish?"].waitForExistence(timeout: 5), "Finished did not open its panel")
        press(app, .downArrow)
        XCTAssertTrue(waitFor(app) { $0.hasPrefix("ring finished ") }, "the panel did not take the ring: \(focus(app))")
        // Under the month and the year, its answers; Cancel is on the left.
        press(app, .downArrow)
        XCTAssertTrue(["ring finished finish", "ring finished cancel"].contains(focus(app)), "down did not reach the answers: \(focus(app))")
        press(app, .leftArrow)
        XCTAssertEqual(focus(app), "ring finished cancel")
        pressReturn(app)
        XCTAssertTrue(app.staticTexts["When did you finish?"].waitForNonExistence(timeout: 5), "Return on Cancel did not close the panel")
        XCTAssertFalse(app.staticTexts["book-you-line"].exists, "Cancel marked the book finished")
    }

    /// Notifications and Activity: down their rows by looking, Return opening a notice.
    @MainActor
    func testNotificationsAndActivityRowsAreWalked() {
        let app = launch(side: "media", section: "notifications")
        XCTAssertTrue(app.descendants(matching: .any)["column-sonarr"].firstMatch.waitForExistence(timeout: 20))
        press(app, .downArrow, times: 2)
        let notice = focus(app)
        XCTAssertTrue(notice.hasPrefix("ring notifications-media notice-"), "down did not reach a notice: \(notice)")
        let id = String(notice.dropFirst("ring notifications-media ".count))
        pressReturn(app)
        let row = app.descendants(matching: .any)[id].firstMatch
        XCTAssertTrue(waitFor(app) { _ in (row.value as? String)?.contains("Expanded") == true }, "Return did not open the notice")
        press(app, .downArrow)
        XCTAssertTrue(focus(app).hasPrefix("ring notifications-media notice-") && focus(app) != notice,
                      "down did not reach the next notice: \(focus(app))")
        app.terminate()

        let activity = launch(side: "media", section: "activity")
        XCTAssertTrue(activity.descendants(matching: .any)["all-transfers"].firstMatch.waitForExistence(timeout: 20))
        press(activity, .downArrow)
        XCTAssertTrue(focus(activity).hasPrefix("ring activity transfer-"), "the first press did not show a transfer: \(focus(activity))")
        // Past the transfers and, on an iPad's wider page, the services beside them.
        press(activity, .downArrow, until: "ring activity speed/", limit: 10)
        XCTAssertTrue(focus(activity).hasPrefix("ring activity speed/"), "down did not reach the speed choice: \(focus(activity))")
        press(activity, .rightArrow)
        XCTAssertTrue(focus(activity).hasPrefix("ring activity speed/"), "right left the speed choice: \(focus(activity))")
    }

    // MARK: Controls a finger still works (#46)

    /// The Books library's Sort by is a `Menu` the ring can focus: a tap
    /// still opens it, and a field chosen there sorts the books.
    @MainActor
    func testTheSortMenuStillOpensOnATap() {
        let app = launch(side: "books", section: "library")
        let tile = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Books & Audiobooks")).firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 20), "the reading libraries did not load")
        tile.tap()
        let views = app.otherElements["library-views"]
        XCTAssertTrue(views.waitForExistence(timeout: 10), "the library has no Series, Authors and Books")
        views.buttons["Series"].tap()
        let sort = app.buttons["library-sort-field"].firstMatch
        XCTAssertTrue(sort.waitForExistence(timeout: 10), "no Sort by on Series")
        let before = sort.label
        // Another field than the one sorted by, so the choice shows.
        let field = before.hasPrefix("Title") ? "Series" : "Title"
        sort.tap()
        let choice = app.buttons.matching(NSPredicate(format: "label == %@", field)).firstMatch
        XCTAssertTrue(choice.waitForExistence(timeout: 5), "a tap did not open Sort by (\(before))")
        choice.tap()
        XCTAssertTrue(sort.wait(for: \.label, toEqual: field + " ▾", timeout: 5), "the field chosen did not sort: \(sort.label)")
        // The order chosen stays from one visit to the next: as it was, for the next run.
        sort.tap()
        let back = app.buttons.matching(NSPredicate(format: "label == %@", String(before.dropLast(2)))).firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5), "Sort by did not open again")
        back.tap()
        XCTAssertTrue(sort.wait(for: \.label, toEqual: before, timeout: 5), "the first field did not come back: \(sort.label)")
    }

    /// Downloads' Wait for Wi-Fi is a `Toggle` the ring can focus: a tap on
    /// its switch still turns it off, and again on.
    @MainActor
    func testTheWifiToggleStillSwitchesOnATap() {
        let app = launch(side: "books", section: "downloads")
        // This side's: the other side's Downloads, kept out of sight, has its own.
        let queue = app.buttons.matching(NSPredicate(format: "identifier == %@ AND enabled == true", "downloads-queue")).firstMatch
        XCTAssertTrue(queue.waitForExistence(timeout: 20), "no queue pill")
        queue.tap()
        let toggles = app.switches.matching(identifier: "offline-wifi-only")
        XCTAssertTrue(toggles.firstMatch.waitForExistence(timeout: 10), "no Wait for Wi-Fi in the queue")
        // The one on screen: hittable is not a key a query can match on.
        let toggle = toggles.allElementsBoundByIndex.first { $0.isHittable } ?? toggles.firstMatch
        let before = toggle.value as? String ?? ""
        let after = before == "1" ? "0" : "1"
        // On the switch itself, at the trailing end, where a finger turns it.
        let knob = toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5))
        knob.tap()
        XCTAssertTrue(waitFor(app, 5) { _ in (toggle.value as? String) == after }, "a tap did not switch Wait for Wi-Fi: \(toggle.value ?? "none")")
        knob.tap()
        XCTAssertTrue(waitFor(app, 5) { _ in (toggle.value as? String) == before }, "a second tap did not switch it back: \(toggle.value ?? "none")")
    }
}
