import XCTest

/// Read along word by word (#66) against the demo hub's Dark Matter, whose
/// word edition times each word: the word being read in a strong wash and the
/// trail through its sentence before it (style A), the highlight chosen per
/// page colour, a book without a word pack, the place kept, and how smoothly
/// the page follows a minute of words. Nothing reaches a real hub (`-demo`).
final class ReadAlongWordUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(_ environment: [String: String] = [:], arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"] + arguments
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_BOOK": "rw_demo_darkmatter/demo-dm",
                                 "HUB_BOOK_READALONG": "1", "HUB_BOOK_SCROLL": "0", "HUB_BOOK_CHROME": "",
                                 "HUB_BOOK_LOOK_ONCE": "1", "HUB_DEBUG_READALONG": "1",
                                 // The highlight is kept on the device: each test starts from the defaults.
                                 "HUB_HIGHLIGHT_RESET": "1"]
            .merging(environment) { $1 }
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "book-page").firstMatch.waitForExistence(timeout: 30),
                      "the read-along edition did not open")
        return app
    }

    @MainActor
    private func debug(_ app: XCUIApplication) -> String {
        app.staticTexts["debug-readalong"].label
    }

    /// The washes drawn now: "trail F2DCB0 word EFC981" as kinds and colours.
    @MainActor
    private func washes(_ app: XCUIApplication) -> [String: UInt32] {
        let line = debug(app)
        guard let range = line.range(of: "washes ") else { return [:] }
        let words = line[range.upperBound...].split(separator: " ")
        var out: [String: UInt32] = [:]
        var index = 0
        while index + 1 < words.count, ["trail", "word", "sentence"].contains(String(words[index])) {
            out[String(words[index])] = UInt32(words[index + 1], radix: 16)
            index += 2
        }
        return out
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

    @MainActor
    private func keep(_ name: String) -> Shot {
        let screenshot = XCUIScreen.main.screenshot()
        let shot = XCTAttachment(screenshot: screenshot)
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
        return Shot(screenshot.image)
    }

    // MARK: The word and its trail

    /// On Paper, Sepia, Dim and Dark: a moment late in Dark Matter's long
    /// first sentence, its trail over two lines and more. Each wash is
    /// there; the trail runs from line to line with no gap (its rows joined);
    /// the word is one line high; and its words keep their ink over it.
    @MainActor
    func testTheWordAndItsTrailAreWashedOnEveryPage() {
        for theme in ["LIGHT", "SEPIA", "DARK", "BLACK"] {
            let app = launch(["HUB_BOOK_THEME": theme, "HUB_READALONG_SENTENCE": "one-s1", "HUB_READALONG_WORD": "30"])
            XCTAssertTrue(waitUntil(30) { washes(app)["word"] != nil && washes(app)["trail"] != nil },
                          "\(theme): the word and its trail are not washed: \(debug(app))")
            RunLoop.current.run(until: Date().addingTimeInterval(2))
            let drawn = washes(app)
            let shot = keep("readalong-word-\(theme)")
            let word = shot.find(drawn["word"]!)
            let trail = shot.find(drawn["trail"]!)
            XCTAssertGreaterThan(word.count, 80, "\(theme): the word's wash is not on the page")
            XCTAssertGreaterThan(trail.count, word.count * 2, "\(theme): the trail is not on the page")
            let wordBox = Shot.bounds(word)
            let trailBox = Shot.bounds(trail)
            // One line high, the word; the trail over more than one line.
            XCTAssertLessThan(wordBox.height, 60 * shot.scale, "\(theme): the word is not one line: \(wordBox)")
            XCTAssertGreaterThan(trailBox.height, wordBox.height * 1.8, "\(theme): the trail does not run over a line break: \(trailBox)")
            // Joined from line to line: every row from the trail's top to its bottom has some of it.
            let rows = Set(trail.map(\.y))
            let gaps = stride(from: trailBox.minY, through: trailBox.maxY, by: 2).filter { !rows.contains($0) }
            XCTAssertTrue(gaps.isEmpty, "\(theme): the trail has a gap between its lines at \(gaps.prefix(5))")
            // The word's letters keep their ink over its wash.
            XCTAssertGreaterThan(shot.ink(in: wordBox, against: drawn["word"]!), 20, "\(theme): no ink on the word's wash")
            app.terminate()
        }
    }

    // MARK: The setting

    /// Appearance › Themes › Read-along highlight: the page colours as tabs,
    /// a sentence in the highlight, eight colours with the page's default
    /// marked, the trail, and Use the default. A choice shows at once on the
    /// page, is kept for its own page colour only, and is still there after
    /// the app starts again.
    @MainActor
    func testTheHighlightIsChosenPerPageColourAndKept() {
        let app = launch(["HUB_BOOK_THEME": "SEPIA", "HUB_READALONG_SENTENCE": "one-s1", "HUB_READALONG_WORD": "30",
                          "HUB_BOOK_SHEET": "highlight"])
        let sepia = app.buttons["highlight-theme-SEPIA"]
        XCTAssertTrue(sepia.waitForExistence(timeout: 20), "the highlight's page did not open")
        for theme in ["LIGHT", "SEPIA", "DARK", "BLACK", "BLUE"] {
            XCTAssertTrue(app.buttons["highlight-theme-\(theme)"].exists, "no tab for \(theme)")
        }
        XCTAssertTrue(sepia.isSelected, "the page shown's colour is not the one open")
        for colour in ["gold", "ember", "rose", "lavender", "sky", "teal", "mint", "moon"] {
            XCTAssertTrue(app.buttons["highlight-colour-\(colour)"].exists, "no \(colour)")
        }
        XCTAssertEqual(app.buttons["highlight-colour-gold"].label, "Gold, default", "Sepia's default is not Gold")
        XCTAssertTrue(app.buttons["highlight-colour-gold"].isSelected)
        let trail = app.sliders["highlight-trail"]
        XCTAssertEqual(trail.value as? String, "40%")
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "highlight-preview").firstMatch.exists, "no sentence to see it in")
        let before = washes(app)["word"]

        app.buttons["highlight-colour-teal"].tap()
        XCTAssertTrue(waitUntil(5) { app.buttons["highlight-colour-teal"].isSelected }, "Teal was not chosen")
        trail.adjust(toNormalizedSliderPosition: 0.7)
        XCTAssertTrue(waitUntil(5) { (trail.value as? String) != "40%" }, "the trail did not move: \(String(describing: trail.value))")
        let chosen = trail.value as? String
        XCTAssertTrue(waitUntil(5) { washes(app)["word"] != before }, "the page did not take the colour: \(debug(app))")
        _ = keep("highlight-setting")
        // Dim keeps its own.
        app.buttons["highlight-theme-DARK"].tap()
        XCTAssertTrue(waitUntil(5) { app.buttons["highlight-colour-ember"].isSelected }, "Dim did not keep Ember")
        XCTAssertEqual(app.buttons["highlight-colour-ember"].label, "Ember, default")
        XCTAssertEqual(trail.value as? String, "40%")
        app.terminate()

        // Kept: the app started again, Sepia's highlight is as chosen.
        let again = launch(["HUB_BOOK_THEME": "SEPIA", "HUB_BOOK_SHEET": "highlight", "HUB_HIGHLIGHT_RESET": "0"])
        XCTAssertTrue(again.buttons["highlight-colour-teal"].waitForExistence(timeout: 20))
        XCTAssertTrue(again.buttons["highlight-colour-teal"].isSelected, "Teal was not kept")
        XCTAssertEqual(again.sliders["highlight-trail"].value as? String, chosen, "the trail was not kept")
        // Use the default: Gold, and 40%.
        let reset = again.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Use the default'")).firstMatch
        XCTAssertTrue(reset.exists, "no Use the default: \(again.buttons.allElementsBoundByIndex.map(\.label))")
        reset.tap()
        XCTAssertTrue(waitUntil(5) { again.buttons["highlight-colour-gold"].isSelected }, "Use the default did not bring Gold back")
        XCTAssertEqual(again.sliders["highlight-trail"].value as? String, "40%")
    }

    // MARK: A book without a word pack

    /// The hub has no word pack for the book (HUB_DEMO_SENTENCES): the
    /// sentence is washed as before, in the colour chosen for the page.
    @MainActor
    func testABookWithoutAWordPackWashesItsSentenceInTheChosenColour() {
        let app = launch(["HUB_BOOK_THEME": "SEPIA", "HUB_READALONG_SENTENCE": "one-s2", "HUB_DEMO_SENTENCES": "1",
                          "HUB_BOOK_SHEET": "highlight"])
        XCTAssertTrue(waitUntil(30) { washes(app)["sentence"] != nil }, "the sentence is not washed: \(debug(app))")
        XCTAssertNil(washes(app)["word"], "a word is washed in a book without a word pack")
        let gold = washes(app)["sentence"]
        let teal = app.buttons["highlight-colour-teal"]
        XCTAssertTrue(teal.waitForExistence(timeout: 10))
        teal.tap()
        XCTAssertTrue(waitUntil(5) { washes(app)["sentence"] != gold && washes(app)["sentence"] != nil },
                      "the sentence did not take the colour: \(debug(app))")
        app.buttons["appearance-back"].tap()
        let close = app.buttons["Close"].firstMatch
        if close.waitForExistence(timeout: 3) { close.tap() }
        RunLoop.current.run(until: Date().addingTimeInterval(2))
        let shot = keep("readalong-sentence-only-teal")
        XCTAssertGreaterThan(shot.find(washes(app)["sentence"]!).count, 200, "the sentence's wash is not on the page")
    }

    // MARK: Playing

    /// Played, the word moves on several times a second, the trail with it,
    /// and the place kept names the sentence, never a word.
    @MainActor
    func testWhileItPlaysTheWordMovesAndThePlaceIsASentence() {
        let app = launch(["HUB_BOOK_CHROME": "pinned"])
        let play = app.buttons["readalong-play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        play.tap()
        XCTAssertTrue(waitUntil(15) { play.label == "Pause narration" }, "the narration did not play")
        var seen = Set<UInt32>()
        var words = Set<String>()
        let deadline = Date().addingTimeInterval(8)
        while Date() < deadline {
            if let word = washes(app)["word"] { seen.insert(word) }
            if let count = debug(app).range(of: #"words (\d+)"#, options: .regularExpression) { words.insert(String(debug(app)[count])) }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        XCTAssertGreaterThanOrEqual(words.count, 4, "the word did not move on: \(debug(app))")
        play.tap()
        XCTAssertTrue(waitUntil(5) { play.label == "Play narration" })
        XCTAssertTrue(waitUntil(15) { debug(app).contains("place one-s") || debug(app).contains("place two-s") },
                      "no place kept: \(debug(app))")
        let place = debug(app).range(of: #"place [^ ]+"#, options: .regularExpression).map { String(debug(app)[$0]) } ?? ""
        XCTAssertFalse(place.contains("-w"), "the place kept is a word: \(place)")
    }

    // MARK: Smoothness

    /// A minute of words (three to six a second in the demo), and the same
    /// minute read by the sentence (a book without a word pack) beside it:
    /// the frames the screen drew, the hitches among them (a frame half as
    /// late again as its interval), and how long the page took to fit each
    /// word, kept as "word-smoothness". The minute also turns pages and opens
    /// the next chapter, whose hitches both share: the words must add none
    /// worth the name (under 5 ms a second more, Apple's "good"), and fit in
    /// well under a word's time.
    @MainActor
    func testAMinuteOfWordsStaysSmooth() {
        func minute(_ environment: [String: String]) -> (line: String, words: Double, hitch: Double, fit: Double) {
            let app = launch(["HUB_BOOK_CHROME": "pinned", "HUB_DEBUG_FRAMES": "1"].merging(environment) { $1 })
            let play = app.buttons["readalong-play"]
            XCTAssertTrue(play.waitForExistence(timeout: 20))
            play.tap()
            XCTAssertTrue(waitUntil(15) { play.label == "Pause narration" }, "the narration did not play")
            // The menu away: the page alone, as it is read.
            app.descendants(matching: .any).matching(identifier: "book-page").firstMatch
                .coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            RunLoop.current.run(until: Date().addingTimeInterval(60))
            let line = debug(app)
            app.terminate()
            func number(_ pattern: String) -> Double {
                guard let range = line.range(of: pattern, options: .regularExpression) else { return .nan }
                return Double(line[range].split(separator: " ").last?.replacingOccurrences(of: "ms/s", with: "")
                    .replacingOccurrences(of: "ms", with: "") ?? "") ?? .nan
            }
            return (line, number(#"words \d+"#), number(#"hitch [0-9.]+ms/s"#), number(#"fit avg [0-9.]+ms"#))
        }
        let sentences = minute(["HUB_DEMO_SENTENCES": "1"])
        let words = minute([:])
        let note = XCTAttachment(string: "words: \(words.line)\nsentences: \(sentences.line)")
        note.name = "word-smoothness"
        note.lifetime = .keepAlways
        add(note)
        print("word smoothness: words \(words.line) | sentences \(sentences.line)")
        XCTAssertGreaterThan(words.words, 120, "fewer words than a minute holds: \(words.line)")
        XCTAssertLessThan(words.hitch, sentences.hitch + 5, "the words hitched: \(words.line); by the sentence: \(sentences.line)")
        XCTAssertLessThan(words.fit, 50, "a word took too long to fit: \(words.line)")
    }
}

/// A screenshot's pixels.
struct Shot {
    let width: Int
    let height: Int
    let scale: CGFloat
    private let pixels: [UInt8]

    init(_ image: UIImage) {
        let cg = image.cgImage!
        width = cg.width
        height = cg.height
        scale = image.scale
        var data = [UInt8](repeating: 0, count: width * height * 4)
        let context = CGContext(data: &data, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        pixels = data
    }

    func rgb(_ x: Int, _ y: Int) -> (Int, Int, Int) {
        let index = (y * width + x) * 4
        return (Int(pixels[index]), Int(pixels[index + 1]), Int(pixels[index + 2]))
    }

    /// Every other pixel of `colour` (0xRRGGBB), within 3 a channel.
    func find(_ colour: UInt32) -> [(x: Int, y: Int)] {
        let r = Int((colour >> 16) & 0xFF), g = Int((colour >> 8) & 0xFF), b = Int(colour & 0xFF)
        var out: [(x: Int, y: Int)] = []
        for y in stride(from: 0, to: height, by: 2) {
            for x in stride(from: 0, to: width, by: 2) {
                let p = rgb(x, y)
                if abs(p.0 - r) <= 3 && abs(p.1 - g) <= 3 && abs(p.2 - b) <= 3 { out.append((x, y)) }
            }
        }
        return out
    }

    static func bounds(_ points: [(x: Int, y: Int)]) -> (minX: Int, minY: Int, maxX: Int, maxY: Int, height: CGFloat) {
        let xs = points.map(\.x), ys = points.map(\.y)
        let minY = ys.min() ?? 0, maxY = ys.max() ?? 0
        return (xs.min() ?? 0, minY, xs.max() ?? 0, maxY, CGFloat(maxY - minY))
    }

    /// The pixels in `box` far from `wash`: the letters drawn over it.
    func ink(in box: (minX: Int, minY: Int, maxX: Int, maxY: Int, height: CGFloat), against wash: UInt32) -> Int {
        let lum = { (r: Int, g: Int, b: Int) in 0.299 * Double(r) + 0.587 * Double(g) + 0.114 * Double(b) }
        let washLum = lum(Int((wash >> 16) & 0xFF), Int((wash >> 8) & 0xFF), Int(wash & 0xFF))
        var count = 0
        for y in stride(from: box.minY, through: box.maxY, by: 1) {
            for x in stride(from: box.minX, through: box.maxX, by: 1) where x < width && y < height {
                let p = rgb(x, y)
                if abs(lum(p.0, p.1, p.2) - washLum) > 60 { count += 1 }
            }
        }
        return count
    }
}
