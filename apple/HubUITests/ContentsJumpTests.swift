import XCTest

/// A Contents jump into another file (the Pocket's #59): "Later" points half
/// way into chapter Eight. A jump to it from inside Eight lands where the
/// element is; from another file, Readium's own jump has landed elsewhere on
/// the Pocket. Every jump into another file must land where the jump inside
/// the file does, and a whole chapter on its first page, from either side.
/// Recursion in the demo; nothing reaches a real hub (`-demo`).
final class ContentsJumpTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launchReading() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_BOOK": "rw_demo_recursion/demo-rw_demo_recursion",
                                 "HUB_BOOK_SCROLL": "0", "HUB_BOOK_CHROME": "pinned"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "book-page").firstMatch.waitForExistence(timeout: 20),
                      "the book did not open")
        return app
    }

    @MainActor
    private func waitUntil(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return condition()
    }

    /// "Eight · Page 4 of 7 in chapter": the menu's position line, to "in chapter".
    @MainActor
    private func place(_ app: XCUIApplication) -> String {
        let label = app.staticTexts["book-position"].label
        return label.range(of: #"^.*? in chapter"#, options: .regularExpression).map { String(label[$0]) } ?? label
    }

    /// Contents, the row `title`, and the place once it has stopped moving for a second. `chapter` is
    /// what the line names the place by: a chapter, or the contents' entry inside it ("Later").
    @MainActor
    private func jump(_ app: XCUIApplication, to title: String, chapter: String...) -> String {
        let contents = app.buttons["Contents"].firstMatch
        XCTAssertTrue(contents.waitForExistence(timeout: 10), "no Contents in the menu")
        contents.tap()
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "no \(title) in the contents")
        row.tap()
        XCTAssertTrue(row.waitForNonExistence(timeout: 10), "the contents stayed open")
        XCTAssertTrue(waitUntil(15) { chapter.contains { place(app).hasPrefix($0 + " · ") } },
                      "\(title) did not go to \(chapter): \(place(app))")
        var last = place(app)
        var still = Date()
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline && Date().timeIntervalSince(still) < 1.2 {
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
            let now = place(app)
            if now != last {
                last = now
                still = Date()
            }
        }
        return last
    }

    @MainActor
    func testAJumpIntoAnotherFileLandsWhereAJumpInsideItDoes() {
        let app = launchReading()
        // The place, from inside the file: Eight from its start, then Later in it.
        _ = jump(app, to: "Eight", chapter: "Eight")
        let reference = jump(app, to: "Later", chapter: "Eight", "Later")
        XCTAssertFalse(reference.contains("Page 1 of"), "Later is on Eight's first page: \(reference)")
        // From files before it and after it, and from Eight's own start.
        var landings: [String: String] = [:]
        for (index, from) in [("One", "One"), ("About this edition", "About this edition"), ("Notes", "Notes"),
                              ("Five", "Five"), ("One", "One"), ("Notes", "Notes")].enumerated() {
            _ = jump(app, to: from.0, chapter: from.1)
            landings["\(index) from \(from.0)"] = jump(app, to: "Later", chapter: "Eight", "Later")
        }
        let wrong = landings.filter { $0.value != reference }
        let note = XCTAttachment(string: "reference: \(reference)\n" + landings.sorted { $0.key < $1.key }
            .map { "\($0.key): \($0.value)" }.joined(separator: "\n"))
        note.name = "contents-jumps"
        note.lifetime = .keepAlways
        add(note)
        XCTAssertTrue(wrong.isEmpty, "a jump into Eight did not land on Later's page (\(reference)): \(wrong.sorted { $0.key < $1.key })")
    }

    /// A whole chapter, from before it and after it, opens on its first page.
    @MainActor
    func testAWholeChapterOpensOnItsFirstPageFromEitherSide() {
        let app = launchReading()
        var landings: [String] = []
        for (from, to) in [("Later", "One"), ("Notes", "One"), ("One", "Five"), ("Later", "Five"), ("Notes", "Eight"), ("One", "Eight")] {
            _ = from == "Later" ? jump(app, to: from, chapter: "Eight", "Later") : jump(app, to: from, chapter: from)
            landings.append(jump(app, to: to, chapter: to))
        }
        XCTAssertTrue(landings.allSatisfy { $0.contains("Page 1 of") }, "a chapter did not open on its first page: \(landings)")
    }
}
