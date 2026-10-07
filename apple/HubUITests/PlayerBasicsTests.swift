import XCTest

/// The player's basics (#33) against the demo hub (`-demo`: Apple's public
/// test stream as Bleach S1E5, an intro from the start to 1:25 and credits
/// from 9:20; nothing real is played or written): aspect, skipping the intro
/// by itself, when the next episode's card comes, and the keyboard.
final class PlayerBasicsTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    /// The player open on the demo's Bleach S1E5, its chrome held up. Settings
    /// › Playback for this launch only, as arguments, never saved.
    @MainActor
    private func launchPlaying(_ settings: [String: String] = [:], environment: [String: String] = [:]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"] + settings.flatMap { ["-" + $0.key, $0.value] }
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "media", "HUB_PLAY": "demo-e5", "HUB_PLAY_CHROME": "pinned"]
            .merging(environment) { $1 }
        app.launch()
        XCTAssertTrue(app.buttons["Lock controls"].waitForExistence(timeout: 15), "the player did not open")
        XCTAssertTrue(app.staticTexts["Bleach"].waitForExistence(timeout: 10), "the player has no title")
        return app
    }

    @MainActor
    private func keep(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
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

    @MainActor
    private func buttons(_ app: XCUIApplication) -> String {
        app.buttons.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.joined(separator: " | ")
    }

    // MARK: Aspect

    /// This video › Aspect: Fit until changed, then Zoom, and This video says so.
    @MainActor
    func testThisVideosAspectFitsUntilZoomIsChosen() {
        let app = launchPlaying()
        app.buttons["This video"].firstMatch.tap()
        let aspect = app.buttons["player-aspect"]
        XCTAssertTrue(aspect.waitForExistence(timeout: 5), "This video has no Aspect: \(buttons(app))")
        XCTAssertTrue(aspect.label.contains("Fit"), "a video does not open at Fit: \(aspect.label)")
        aspect.tap()
        let zoom = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Zoom'")).firstMatch
        XCTAssertTrue(zoom.waitForExistence(timeout: 5), "Aspect offers no Zoom: \(buttons(app))")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Original aspect'")).firstMatch.exists)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Fill'")).firstMatch.exists)
        zoom.tap()
        XCTAssertTrue(aspect.waitForExistence(timeout: 5), "choosing went nowhere")
        XCTAssertTrue(waitUntil(5) { aspect.label.contains("Zoom") }, "This video says \(aspect.label)")
        app.buttons["Close"].firstMatch.tap()
        // Upright, Fit is a band across the middle; Zoom fills the screen's height.
        _ = waitUntil(2) { false }
        keep(app, "player-zoom")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5))
    }

    // MARK: Skip intro and the next episode's card

    /// Settings › Playback › Skip intros automatically: the intro the demo
    /// episode opens on skips itself and says so, with no button left for it.
    @MainActor
    func testAnIntroSkipsItselfWhenSettingsSaySo() {
        let app = launchPlaying(["playback.autoSkipIntro": "YES"])
        let skipped = app.staticTexts["Skipped intro"]
        XCTAssertTrue(skipped.waitForExistence(timeout: 20), "the intro did not skip itself: \(buttons(app))")
        XCTAssertFalse(app.buttons["Skip intro"].exists, "the intro still has its button")
        keep(app, "intro-skipped")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5))
    }

    /// Without the setting, the intro waits for its button, as before.
    @MainActor
    func testAnIntroWaitsForItsButtonUntilAsked() {
        let app = launchPlaying(["playback.autoSkipIntro": "NO"])
        XCTAssertTrue(app.buttons["Skip intro"].waitForExistence(timeout: 20), "the intro has no Skip button")
        XCTAssertFalse(app.staticTexts["Skipped intro"].exists)
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5))
    }

    /// Settings › Playback › Next episode: Never brings no card before the
    /// end; 20 s before the end brings it then.
    @MainActor
    func testTheNextEpisodesCardComesWhenSettingsSay() {
        let never = launchPlaying(["playback.nextTiming": "never"], environment: ["HUB_PLAY_FROM_END": "40"])
        XCTAssertFalse(waitUntil(12) { never.buttons["Watch credits"].exists }, "the card came though Settings say never")
        never.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(never.buttons["Lock controls"].waitForNonExistence(timeout: 5))
        never.terminate()

        let beforeEnd = launchPlaying(["playback.nextTiming": "beforeEnd"], environment: ["HUB_PLAY_FROM_END": "30"])
        XCTAssertTrue(beforeEnd.buttons["Watch credits"].waitForExistence(timeout: 25), "the card did not come 20 s before the end")
        beforeEnd.buttons["Watch credits"].tap()
        XCTAssertTrue(beforeEnd.buttons["Watch credits"].waitForNonExistence(timeout: 5), "Watch credits kept the card")
        beforeEnd.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(beforeEnd.buttons["Lock controls"].waitForNonExistence(timeout: 5))
    }

    /// Settings › Playback keeps both choices from one launch to the next;
    /// put back as they were after.
    @MainActor
    func testSettingsPlaybackKeepsTheNextEpisodeAndTheIntros() {
        func open() -> XCUIApplication {
            let app = XCUIApplication()
            app.launchArguments = ["-demo"]
            app.launchEnvironment = ["HUB_SECTION": "settings", "HUB_SIDE": "media"]
            app.launch()
            let playback = app.buttons.matching(NSPredicate(format: "label == 'Playback'")).firstMatch
            XCTAssertTrue(playback.waitForExistence(timeout: 15), "Settings has no Playback: \(buttons(app))")
            playback.tap()
            XCTAssertTrue(app.buttons["next-never"].waitForExistence(timeout: 5), "Playback has no next-episode choice")
            return app
        }
        var app = open()
        XCTAssertTrue(app.buttons["next-credits"].isSelected, "the next episode's card does not wait for the credits until chosen")
        app.buttons["next-never"].tap()
        let toggle = app.switches["auto-skip-intro"]
        XCTAssertTrue(toggle.exists, "Playback has no Skip intros automatically")
        XCTAssertEqual(toggle.value as? String, "0", "intros skip themselves until chosen")
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).tap()
        XCTAssertTrue(waitUntil(3) { toggle.value as? String == "1" }, "the switch did not turn on")
        app.terminate()

        app = open()
        XCTAssertTrue(app.buttons["next-never"].isSelected, "Never was not kept")
        XCTAssertEqual(app.switches["auto-skip-intro"].value as? String, "1", "Skip intros automatically was not kept")
        app.buttons["next-credits"].tap()
        let again = app.switches["auto-skip-intro"]
        again.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).tap()
        XCTAssertTrue(waitUntil(3) { again.value as? String == "0" }, "the switch did not turn off")
    }

    // MARK: Now Playing (the lock screen, Control Center, AirPods, the media keys)

    /// The video has the lock screen while it is open, under its episode's
    /// code and name, and nobody has it once it closes. Read from debug builds'
    /// summary (`HUB_DEBUG_NOW_PLAYING`): a UI test cannot see the lock screen.
    @MainActor
    func testTheLockScreenIsTheVideosWhileItIsOpen() {
        let app = launchPlaying(environment: ["HUB_DEBUG_NOW_PLAYING": "1"])
        let summary = app.staticTexts["debug-now-playing"]
        XCTAssertTrue(summary.waitForExistence(timeout: 10), "no Now Playing summary")
        XCTAssertTrue(waitUntil(10) { summary.label == "video · S1E5 · Beat the Invisible Enemy!" },
                      "the lock screen shows \(summary.label)")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(waitUntil(5) { summary.label == "none" }, "the lock screen kept the video: \(summary.label)")
    }

    /// An audiobook put on the player has the lock screen, under its title,
    /// until it leaves the player.
    @MainActor
    func testTheLockScreenIsTheAudiobooksWhileItIsOnThePlayer() {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_OPEN": "book:rw_demo_alloy",
                                 "HUB_DEBUG_NOW_PLAYING": "1"]
        app.launch()
        let entry = app.buttons["book-entry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 15), "the audiobook's page did not open: \(buttons(app))")
        let summary = app.staticTexts["debug-now-playing"]
        XCTAssertEqual(summary.label, "none")
        entry.tap()
        XCTAssertTrue(waitUntil(15) { summary.label == "audiobook · The Alloy of Law" }, "the lock screen shows \(summary.label)")
        let stop = app.buttons["listen-stop"]
        for _ in 0..<4 where !stop.isHittable {
            let window = app.windows.firstMatch.frame
            let row = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: window.width * 0.85, dy: stop.frame.midY))
            row.press(forDuration: 0.05, thenDragTo: row.withOffset(CGVector(dx: -window.width * 0.6, dy: 0)))
        }
        stop.tap()
        XCTAssertTrue(waitUntil(5) { summary.label == "none" }, "the lock screen kept the audiobook: \(summary.label)")
    }

    // MARK: The keyboard

    @MainActor
    private func text(_ app: XCUIApplication, beginning words: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", words)).firstMatch
    }

    /// M mutes and turns the sound on again, ] and [ change the speed, C
    /// turns subtitles on and off, each saying so; S skips the intro the
    /// episode opens on, and N plays the next episode.
    @MainActor
    func testThePlayersLetterKeys() {
        let app = launchPlaying()
        XCTAssertTrue(app.buttons["Skip intro"].waitForExistence(timeout: 20), "the intro has no Skip button")
        app.typeKey("m", modifierFlags: [])
        XCTAssertTrue(app.staticTexts["Muted"].waitForExistence(timeout: 5), "M did not mute")
        app.typeKey("m", modifierFlags: [])
        XCTAssertTrue(app.staticTexts["Sound on"].waitForExistence(timeout: 5), "M did not turn the sound on again")
        app.typeKey("]", modifierFlags: [])
        XCTAssertTrue(app.staticTexts["Speed 1.25×"].waitForExistence(timeout: 5), "] did not go faster")
        app.typeKey("[", modifierFlags: [])
        XCTAssertTrue(app.staticTexts["Speed Normal"].waitForExistence(timeout: 5), "[ did not go slower")

        // Subtitles may start on, as this profile last chose for the series: C turns them over, twice.
        app.typeKey("c", modifierFlags: [])
        let off = app.staticTexts["Subtitles off"]
        let on = text(app, beginning: "Subtitles: ")
        XCTAssertTrue(waitUntil(5) { off.exists || on.exists }, "C said nothing")
        let turnedOff = off.exists
        // The hub's answer first, and the word gone, then the other way.
        XCTAssertTrue(waitUntil(10) { !app.staticTexts["Changing playback…"].exists })
        _ = waitUntil(3.5) { false }
        app.typeKey("c", modifierFlags: [])
        XCTAssertTrue((turnedOff ? on : off).waitForExistence(timeout: 5),
                      "C did not turn subtitles \(turnedOff ? "on" : "off") again")
        XCTAssertTrue(waitUntil(10) { !app.staticTexts["Changing playback…"].exists })

        app.typeKey("s", modifierFlags: [])
        XCTAssertTrue(app.buttons["Skip intro"].waitForNonExistence(timeout: 8), "S did not skip the intro")
        app.typeKey("n", modifierFlags: [])
        XCTAssertTrue(text(app, beginning: "S1E6").waitForExistence(timeout: 20), "N did not play the next episode")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5))
    }

    /// ↓ and ↑ set the player's volume a tenth at a time, the level showing.
    @MainActor
    func testTheArrowsSetTheVolume() {
        let app = launchPlaying(environment: ["HUB_PLAY_FEEDBACK": "hold"])
        let level = app.descendants(matching: .any).matching(identifier: "player-level").firstMatch
        app.typeKey(.downArrow, modifierFlags: [])
        XCTAssertTrue(level.waitForExistence(timeout: 5), "↓ showed no level")
        XCTAssertEqual(level.label, "Volume, 90%")
        app.typeKey(.downArrow, modifierFlags: [])
        XCTAssertTrue(waitUntil(3) { level.label == "Volume, 80%" }, "↓ again said \(level.label)")
        app.typeKey(.upArrow, modifierFlags: [])
        XCTAssertTrue(waitUntil(3) { level.label == "Volume, 90%" }, "↑ said \(level.label)")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5))
    }
}
