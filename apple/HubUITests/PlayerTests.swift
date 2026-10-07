import XCTest

/// Leaving the player is how a hub session ends, so each way out is driven
/// here, against the demo hub (`-demo`: Apple's public test stream, no real
/// hub and no watch history): Back, and the app going to the background. Then
/// its panels, through a turn of the device.
final class PlayerTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        // The simulator keeps the way it was turned; later tests and
        // screenshots expect it upright.
        XCUIDevice.shared.orientation = .portrait
    }

    /// The app with the player open on the demo's Bleach S1E5, its chrome held
    /// up; `width` lays it out in a window that narrow (`HUB_WIDTH`).
    @MainActor
    private func launchPlaying(width: Int? = nil, holdingFeedback: Bool = false, seekSeconds: Int? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        // Settings › Playback's jump for this launch only: an argument, not a saved setting.
        if let seekSeconds { app.launchArguments += ["-playback.seekSeconds", String(seekSeconds)] }
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "media",
                                 "HUB_PLAY": "demo-e5", "HUB_PLAY_CHROME": "pinned"]
        if let width { app.launchEnvironment["HUB_WIDTH"] = String(width) }
        if holdingFeedback { app.launchEnvironment["HUB_PLAY_FEEDBACK"] = "hold" }
        app.launch()
        XCTAssertTrue(app.buttons["Lock controls"].waitForExistence(timeout: 15), "the player did not open")
        // The title comes with the plan, a moment after the controls on a busy Mac.
        XCTAssertTrue(app.staticTexts["Bleach"].waitForExistence(timeout: 10), "the player has no title")
        return app
    }

    @MainActor
    func testBackLeavesThePlayerForThePageUnderIt() {
        let app = launchPlaying()
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5), "Back left the player open")
        // The shell is there again, and VoiceOver can reach it.
        XCTAssertTrue(app.buttons["Home"].waitForExistence(timeout: 5))
    }

    /// The ± buttons go as far as Settings › Playback says, as an audiobook's do.
    @MainActor
    func testTheJumpsGoAsFarAsThePlaybackSetting() {
        let app = launchPlaying(seekSeconds: 30)
        XCTAssertTrue(app.buttons["Forward 30 seconds"].waitForExistence(timeout: 5), "the jump forward is not 30 seconds: "
                      + app.buttons.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.joined(separator: " | "))
        XCTAssertTrue(app.buttons["Back 30 seconds"].exists, "the jump back is not 30 seconds")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5), "Back left the player open")
    }

    /// Without picture in picture (the iPhone simulator has none, and a
    /// paused video never starts it), the background ends playback.
    @MainActor
    func testGoingToTheBackgroundLeavesThePlayer() {
        let app = launchPlaying()
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackgroundSuspended, timeout: 15)
                      || app.state == .runningBackground, "the app did not go to the background")
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        XCTAssertTrue(app.buttons["Home"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Lock controls"].exists, "the player was still open after the background")
    }

    /// In a window as narrow as Slide Over the three panel buttons are one
    /// round menu, and no control is cut off at the window's edge. (The
    /// buttons' row was once counted without its gaps and ran past it.)
    @MainActor
    func testANarrowWindowKeepsEveryControlInsideIt() {
        let app = launchPlaying(width: 375)
        let menu = app.buttons["Audio, chapters and this video"]
        XCTAssertTrue(menu.waitForExistence(timeout: 5), "the panel buttons are not one menu at 375 points")
        for label in ["Back", "AirPlay", "Lock controls", "Audio, chapters and this video"] {
            let button = app.buttons[label].firstMatch
            XCTAssertTrue(button.exists, "\(label) is missing")
            XCTAssertLessThanOrEqual(button.frame.maxX, 375, "\(label) runs past the window's edge")
        }
        menu.tap()
        let chapters = app.buttons["Chapters"]
        XCTAssertTrue(chapters.waitForExistence(timeout: 5), "the list of the three did not open")
        chapters.tap()
        let heading = app.staticTexts.matching(identifier: "panel-heading").firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 5), "Chapters did not open from the menu")
        XCTAssertEqual(heading.label, "Chapters")
    }

    /// A panel opened upright stays open and usable when the device turns,
    /// and the video plays on under it.
    @MainActor
    func testAPanelStaysOpenAndUsableWhenTheDeviceTurns() {
        let app = launchPlaying()
        // Upright on a phone the three pills are round icons, never hidden.
        app.buttons["Audio & subtitles"].firstMatch.tap()
        // By its identifier: turned, a wide phone shows the pill of that name.
        let heading = app.staticTexts.matching(identifier: "panel-heading").firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 5), "the panel did not open")
        XCTAssertEqual(heading.label, "Audio & subtitles")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(heading.waitForExistence(timeout: 5), "the panel closed when the device turned")
        let hebrew = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Hebrew")).firstMatch
        XCTAssertTrue(hebrew.waitForExistence(timeout: 5))
        hebrew.tap()
        XCTAssertTrue(NSPredicate(format: "isSelected == true").evaluate(with: hebrew)
                      || hebrew.wait(for: \.isSelected, toEqual: true, timeout: 5), "the subtitles chosen are not ticked")
        app.buttons["Close"].firstMatch.tap()
        XCTAssertTrue(heading.waitForNonExistence(timeout: 5), "the panel did not close")
        XCTAssertTrue(app.buttons["Pause"].waitForExistence(timeout: 5), "the video stopped when the device turned")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5))
    }

    /// Another language is asked for with the version playing (#24): the demo
    /// hub, like Jellyfin, will not apply a track without it.
    @MainActor
    func testAnotherLanguageIsAskedForWithTheVersionPlaying() {
        let app = launchPlaying()
        app.buttons["Audio & subtitles"].firstMatch.tap()
        let english = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@",
                                                       "English", "Stereo")).firstMatch
        XCTAssertTrue(english.waitForExistence(timeout: 5), "the audio tracks are missing")
        english.tap()
        XCTAssertTrue(english.wait(for: \.isSelected, toEqual: true, timeout: 10),
                      "English was not chosen: the hub refused a track without its version")
        app.buttons["Close"].firstMatch.tap()
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5))
    }

    /// As on the Pocket (#24): a double tap on the right steps on and on the
    /// left back, and an up-or-down drag sets the volume on the right half and
    /// the brightness on the left, each shown as a small bar.
    @MainActor
    func testADoubleTapStepsAndADragSetsTheVolumeAndTheBrightness() {
        let app = launchPlaying(holdingFeedback: true)
        // Above the middle row and below it: the picture with nothing on it.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.3)).doubleTap()
        let on = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "+0:10")).firstMatch
        XCTAssertTrue(on.waitForExistence(timeout: 5), "a double tap on the right did not step on")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.3)).doubleTap()
        let back = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "\u{2212}0:10")).firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5), "a double tap on the left did not step back")

        // Whatever kind of element the bar is exposed as.
        let level = app.descendants(matching: .any).matching(identifier: "player-level").firstMatch
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.35))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.65)))
        XCTAssertTrue(level.waitForExistence(timeout: 5), "a drag down on the right showed no level")
        XCTAssertTrue(level.label.hasPrefix("Volume"), "the right half set \(level.label)")
        XCTAssertNotEqual(level.label, "Volume, 100%", "a drag down did not lower the volume")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.65))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.4)))
        let brightness = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == %@ AND label BEGINSWITH %@", "player-level", "Brightness")).firstMatch
        XCTAssertTrue(brightness.waitForExistence(timeout: 5), "a drag up on the left showed no brightness")

        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5))
    }

    /// As on the Pocket (#24): a drag across the picture scrubs, with the time
    /// it lands on and how far over the timeline, and letting go seeks there;
    /// dragging the timeline itself shows where it lands, without how far.
    @MainActor
    func testADragAcrossThePictureScrubsAndLettingGoSeeks() {
        let app = launchPlaying(holdingFeedback: true)
        let timeline = app.descendants(matching: .any).matching(identifier: "player-timeline").firstMatch
        XCTAssertTrue(timeline.waitForExistence(timeout: 10), "the timeline is missing")
        // The video's length is known once the timeline stops reading "… of 0:00".
        XCTAssertTrue(waitUntil(15) { !Self.value(of: timeline).hasSuffix(" of 0:00") }, "the video's length never came")

        app.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.3))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.3)))
        let preview = app.descendants(matching: .any).matching(identifier: "player-scrub").firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 5), "a drag across the picture showed no preview")
        let words = preview.label
        XCTAssertTrue(words.contains(", +"), "the preview did not say how far the drag went: \(words)")
        let landed = Self.seconds(words.components(separatedBy: ", ").first ?? "")
        XCTAssertGreaterThan(landed, 30, "half the picture's width moved the video only to \(words)")
        // Letting go sought there: the timeline reads it, give or take what has played since.
        XCTAssertTrue(waitUntil(10) {
            let now = Self.seconds(Self.value(of: timeline).components(separatedBy: " of ").first ?? "")
            return now >= landed - 2 && now <= landed + 20
        }, "the video did not move to \(words): the timeline reads \(Self.value(of: timeline))")

        // Along the timeline: only where it lands.
        timeline.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: timeline.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5)))
        XCTAssertTrue(waitUntil(5) { preview.exists && !preview.label.contains(",") },
                      "dragging the timeline showed \(preview.label)")

        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5))
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

    @MainActor
    private static func value(of element: XCUIElement) -> String { element.value as? String ?? "" }

    /// "1:42" or "1:02:03" in seconds; -1 for anything else.
    private static func seconds(_ clock: String) -> Int {
        let parts = clock.trimmingCharacters(in: .whitespaces).split(separator: ":").compactMap { Int($0) }
        guard (2...3).contains(parts.count) else { return -1 }
        return parts.reduce(0) { $0 * 60 + $1 }
    }
}
