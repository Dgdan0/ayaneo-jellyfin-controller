import XCTest

/// Google Cast to the TV (#44) against the demo hub (`-demo`): its Cast
/// button connects to a stand-in "Living room TV", which takes the hub's TV
/// session as a real TV would and plays a clock of its own. The simulator
/// cannot find a real TV; the TV itself is the owner's check.
final class CastTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    private func launchPlaying() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "media", "HUB_PLAY": "demo-e5", "HUB_PLAY_CHROME": "pinned",
                                 "HUB_CAST": "standin"]
        app.launch()
        XCTAssertTrue(app.buttons["Lock controls"].waitForExistence(timeout: 15), "the player did not open")
        return app
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

    /// Audio & subtitles or This video: its own button, or, where the row has
    /// no room for them (an iPhone upright, once a TV is found), the one menu.
    @MainActor
    private func openPanel(_ app: XCUIApplication, _ name: String) {
        let direct = app.buttons[name].firstMatch
        if direct.exists {
            direct.tap()
            return
        }
        let menu = app.buttons["Audio, chapters and this video"].firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 5), "no way to \(name): \(buttons(app))")
        menu.tap()
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5), "the menu has no \(name): \(buttons(app))")
        row.tap()
    }

    @MainActor
    private func keep(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// The Cast button beside AirPlay; the TV chosen, the video moves there
    /// and the player is its remote: play and pause, the TV's subtitles (text
    /// ones only), and This video's Move to this iPhone, which brings it back.
    @MainActor
    func testTheVideoMovesToTheTVAndThePhoneIsItsRemote() {
        let app = launchPlaying()
        let cast = app.buttons["player-cast"]
        XCTAssertTrue(cast.waitForExistence(timeout: 10), "no Cast button: \(buttons(app))")
        XCTAssertEqual(cast.label, "Cast to a TV")
        XCTAssertTrue(app.buttons["AirPlay"].exists, "AirPlay went")
        // Playing here first, so there is a moment to move.
        let play = app.buttons.matching(NSPredicate(format: "label == 'Pause' OR label == 'Play'")).firstMatch
        XCTAssertTrue(waitUntil(20) { play.exists && play.label == "Pause" }, "the video did not play here")
        keep(app, "cast-before")

        cast.tap()
        let casting = app.descendants(matching: .any).matching(identifier: "player-casting").firstMatch
        XCTAssertTrue(casting.waitForExistence(timeout: 15), "the video did not move to the TV: \(buttons(app))")
        XCTAssertTrue(casting.label.contains("Playing on Living room TV"), "the player says \(casting.label)")
        XCTAssertTrue(waitUntil(5) { cast.label == "Casting to Living room TV" }, "the Cast button says \(cast.label)")
        XCTAssertFalse(app.buttons["Picture in picture"].exists, "picture in picture is offered for a TV")
        keep(app, "cast-remote")
        XCUIDevice.shared.orientation = .landscapeLeft
        _ = waitUntil(2) { false }
        XCTAssertTrue(casting.exists, "turned, the player forgot the TV")
        XCUIDevice.shared.orientation = .portrait
        _ = waitUntil(2) { false }

        // The remote: pause and play on the TV.
        XCTAssertTrue(waitUntil(5) { play.label == "Pause" }, "the TV is not playing: \(play.label)")
        play.tap()
        XCTAssertTrue(waitUntil(5) { play.label == "Play" }, "the TV did not pause")
        play.tap()
        XCTAssertTrue(waitUntil(5) { play.label == "Pause" }, "the TV did not play again")

        // The TV's subtitles: Off and the text ones, not the styled ASS.
        openPanel(app, "Audio & subtitles")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'SRT and WebVTT'")).firstMatch
            .waitForExistence(timeout: 5), "the panel does not say what the TV shows")
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Signs'")).firstMatch.exists,
                       "the TV is offered subtitles it cannot show: \(buttons(app))")
        keep(app, "cast-subtitles")
        app.buttons["Close"].firstMatch.tap()

        // This video: back to this iPhone, where the TV was.
        openPanel(app, "This video")
        let here = app.buttons["player-cast-here"]
        XCTAssertTrue(here.waitForExistence(timeout: 5), "This video has no Move to this iPhone: \(buttons(app))")
        XCTAssertTrue(app.buttons["player-cast-stop"].exists, "This video has no Stop on TV")
        keep(app, "cast-this-video")
        here.tap()
        XCTAssertTrue(casting.waitForNonExistence(timeout: 10), "the player still says the TV plays")
        XCTAssertTrue(waitUntil(20) { play.exists && play.label == "Pause" }, "the video did not go on here")
        app.buttons["Back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 5), "the player stayed")
    }

    /// Stop on TV: the TV stops and the player closes.
    @MainActor
    func testStopOnTVClosesThePlayer() {
        let app = launchPlaying()
        let cast = app.buttons["player-cast"]
        XCTAssertTrue(cast.waitForExistence(timeout: 10))
        let play = app.buttons.matching(NSPredicate(format: "label == 'Pause' OR label == 'Play'")).firstMatch
        XCTAssertTrue(waitUntil(20) { play.exists && play.label == "Pause" }, "the video did not play here")
        cast.tap()
        let casting = app.descendants(matching: .any).matching(identifier: "player-casting").firstMatch
        XCTAssertTrue(casting.waitForExistence(timeout: 15), "the video did not move to the TV")
        openPanel(app, "This video")
        let stop = app.buttons["player-cast-stop"]
        XCTAssertTrue(stop.waitForExistence(timeout: 5))
        stop.tap()
        XCTAssertTrue(app.buttons["Lock controls"].waitForNonExistence(timeout: 10), "the player stayed open")
    }
}
