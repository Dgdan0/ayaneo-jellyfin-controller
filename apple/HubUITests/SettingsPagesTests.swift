import XCTest

/// Settings' Appearance, Subtitles, Controller test and Fonts and licences, and
/// the last answer Discover opens with (#38), against the demo hub (`-demo`).
/// The demo keeps accents for the run only, and its controller says the same
/// thing every time.
final class SettingsPagesTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(section: String = "settings", environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": section, "HUB_SIDE": "media"].merging(environment) { _, new in new }
        app.launch()
        return app
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    @MainActor
    private func text(_ app: XCUIApplication, containing words: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }

    @MainActor
    private func open(_ pane: String, in app: XCUIApplication) {
        let button = app.buttons["pane-\(pane)"]
        XCTAssertTrue(button.waitForExistence(timeout: 15), "Settings has no \(pane): \(app.buttons.allElementsBoundByIndex.map(\.label))")
        button.tap()
    }

    // MARK: Appearance

    @MainActor
    func testEachSideChoosesItsAccentAndKeepsItAcrossPanes() {
        let app = launch()
        // Appearance is the first place Settings opens on.
        let books = element(app, "accent-name-books")
        XCTAssertTrue(books.waitForExistence(timeout: 15), "Appearance did not open first")
        XCTAssertEqual(element(app, "accent-name-media").label, "Teal")
        XCTAssertEqual(books.label, "Gold", "Books are gold until chosen otherwise")
        XCTAssertTrue(app.buttons["swatch-media-teal"].isSelected && app.buttons["swatch-books-gold"].isSelected)

        app.buttons["swatch-media-rose"].tap()
        XCTAssertEqual(element(app, "accent-name-media").label, "Rose")
        XCTAssertTrue(app.buttons["swatch-media-rose"].isSelected && !app.buttons["swatch-media-teal"].isSelected)
        XCTAssertEqual(element(app, "accent-name-books").label, "Gold", "the other side is left alone")
        app.buttons["swatch-books-sage"].tap()
        XCTAssertEqual(element(app, "accent-name-books").label, "Sage")

        // Another pane and back: still chosen.
        open("subtitles", in: app)
        XCTAssertTrue(element(app, "subtitle-preview").waitForExistence(timeout: 5))
        open("appearance", in: app)
        XCTAssertEqual(element(app, "accent-name-media").label, "Rose")
        XCTAssertEqual(element(app, "accent-name-books").label, "Sage")
    }

    // MARK: Subtitles

    @MainActor
    func testTheSubtitleLookIsTriedOnAPictureAndKept() {
        let app = launch()
        open("subtitles", in: app)
        let preview = element(app, "subtitle-preview")
        XCTAssertTrue(preview.waitForExistence(timeout: 10), "the preview is missing")
        XCTAssertEqual(preview.label, "Preview: I've always been able to see ghosts.")

        // A known start, whatever an earlier run left.
        app.buttons["style-outline"].tap()
        app.buttons["size-medium"].tap()
        XCTAssertTrue(element(app, "subtitle-lift").exists, "the lift switch is missing")
        XCTAssertTrue(text(app, containing: "White text with a black edge").exists)

        app.buttons["style-box"].tap()
        XCTAssertTrue(text(app, containing: "White text on a dark box").waitForExistence(timeout: 5), "the hint did not follow the style")
        XCTAssertTrue(app.buttons["style-box"].isSelected && !app.buttons["style-outline"].isSelected)
        app.buttons["size-large"].tap()
        XCTAssertTrue(app.buttons["size-large"].isSelected)

        // The preview's own controls toggle.
        XCTAssertEqual(app.buttons["preview-controls"].label, "Hide controls")
        app.buttons["preview-controls"].tap()
        XCTAssertEqual(app.buttons["preview-controls"].label, "Show controls")

        // Another launch finds the look as it was left, as the player would.
        app.terminate()
        let again = launch()
        open("subtitles", in: again)
        XCTAssertTrue(again.buttons["style-box"].waitForExistence(timeout: 10))
        XCTAssertTrue(again.buttons["style-box"].isSelected, "the style was not kept")
        XCTAssertTrue(again.buttons["size-large"].isSelected, "the size was not kept")

        // And back to the defaults, which the player's tests expect.
        again.buttons["style-outline"].tap()
        again.buttons["size-medium"].tap()
        XCTAssertTrue(again.buttons["style-outline"].isSelected && again.buttons["size-medium"].isSelected)
    }

    // MARK: Licences

    @MainActor
    func testEveryLicenceOpensItsFullTextOnAPageOfItsOwn() {
        let app = launch()
        open("licences", in: app)
        let figtree = app.buttons["licence-figtree"]
        XCTAssertTrue(figtree.waitForExistence(timeout: 10), "Fonts and licences is empty")
        XCTAssertTrue(figtree.label.contains("Figtree") && figtree.label.contains("SIL Open Font License"), figtree.label)
        figtree.tap()
        // The text of the file itself, from the app's own resources. A text that can be selected
        // is read by its value or its label, whichever the system gives it.
        func words() -> String {
            let found = app.descendants(matching: .any).matching(identifier: "licence-text").firstMatch
            return (found.value as? String).flatMap { $0.isEmpty ? nil : $0 } ?? found.label
        }
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "licence-text").firstMatch.waitForExistence(timeout: 10),
                      "the licence did not open")
        XCTAssertTrue(words().contains("This Font Software is licensed under the SIL Open Font License"), String(words().prefix(120)))
        // A page of its own: Back, named for where it goes, returns to the list.
        let back = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Back to'")).firstMatch
        XCTAssertTrue(back.exists, "the licence page has no Back")
        back.tap()
        XCTAssertTrue(figtree.waitForExistence(timeout: 10), "Back did not return to the list")

        // The software ones are on the page too, below the fonts.
        let readium = app.buttons["licence-readium"]
        for _ in 0..<6 where !(readium.exists && readium.isHittable) { app.swipeUp() }
        XCTAssertTrue(readium.exists, "the software is not listed")
        readium.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "licence-text").firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(words().contains("Redistribution and use in source and binary forms"), "not the BSD text: \(words().prefix(120))")
    }

    // MARK: The controller test

    @MainActor
    func testTheControllerTestShowsEveryControlAndWhatWasPressed() {
        let app = launch()
        open("controller", in: app)
        XCTAssertEqual(element(app, "controller-header").label, "1 controller connected")
        XCTAssertTrue(text(app, containing: "Demo Controller · Extended gamepad").waitForExistence(timeout: 10))
        // The demo controller holds A, has pulled R2 62% and leans the left stick.
        XCTAssertTrue(element(app, "control-a").label.hasPrefix("A, down"), element(app, "control-a").label)
        XCTAssertEqual(element(app, "control-b").label, "B, up")
        XCTAssertEqual(element(app, "control-r2").value as? String, "62%")
        XCTAssertEqual(element(app, "stick-left").label, "Left stick, x +0.43  y −0.20")
        XCTAssertEqual(element(app, "stick-right").label, "Right stick, Centred")
        // A trigger pulled past half is a press: R2 first, then A.
        XCTAssertEqual(element(app, "controller-last").label, "Last: R2, A")
        XCTAssertTrue(text(app, containing: "of 16 controls tried").exists, "the count of controls tried is missing")
        element(app, "controller-reset").tap()
        XCTAssertTrue(element(app, "controller-last").waitForExistence(timeout: 5))
    }

    // MARK: The last answer

    @MainActor
    func testDiscoverOpensWithItsLastAnswerWhileTheNewOneLoads() {
        // First launch: Discover loads and its answer is kept.
        let first = launch(section: "discover", environment: ["HUB_FORGET_ANSWERS": "1"])
        XCTAssertTrue(first.buttons["Dune: Part Two"].firstMatch.waitForExistence(timeout: 15), "Discover's rows did not load")
        XCTAssertFalse(text(first, containing: "Showing the last answer").exists, "nothing was kept yet")
        first.terminate()

        // Second launch: the hub takes six seconds, and the rows are there at once.
        let second = launch(section: "discover", environment: ["HUB_DEMO_DELAY_MS": "6000"])
        XCTAssertTrue(second.buttons["Dune: Part Two"].firstMatch.waitForExistence(timeout: 4), "the last answer was not shown while waiting")
        let line = text(second, containing: "Showing the last answer, from ")
        XCTAssertTrue(line.waitForExistence(timeout: 2), "the last answer is not said to be one")
        XCTAssertTrue(line.label.hasSuffix("· updating"), line.label)
        // The new answer lands and the words go. A token not yet accepted sends
        // one request at a time, six seconds each here, and the shell's own
        // reads at launch (the bell's notifications, the profiles) go first.
        XCTAssertTrue(line.waitForNonExistence(timeout: 50), "the line stayed after the new answer")
        XCTAssertTrue(second.buttons["Dune: Part Two"].firstMatch.exists)
    }
}
