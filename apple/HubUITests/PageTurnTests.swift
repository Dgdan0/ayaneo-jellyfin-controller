import XCTest

/// Quick swipes in the ebook reader (#64): every swipe turns exactly one
/// page, however fast they come, and the page follows the finger the whole
/// time (no page view is ever switched off; a finger can take a page that is
/// still sliding). A burst of swipes is sent to the demo's
/// Recursion, drawn large so its first chapter is many pages long, and the
/// pages it lands on are counted against the swipes sent, by the corner's
/// "Page X of Y in chapter". Nothing reaches a real hub (`-demo`).
///
/// XCUITest waits for the app to settle before each gesture it sends, so its
/// own swipes never come faster than a page turns. The burst is therefore
/// one synthesized event of many swipes, a fixed time apart (`SwipeBurst`),
/// as a hand sends them.
final class PageTurnTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = true
    }

    @MainActor
    private func launchReading() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        // HUB_DEBUG_READALONG: the menu's line, "One · Page 3 of 40 in chapter", where a test can read it.
        app.launchEnvironment = ["HUB_SECTION": "home", "HUB_SIDE": "books", "HUB_BOOK": "rw_demo_recursion/demo-rw_demo_recursion",
                                 "HUB_BOOK_SCROLL": "0", "HUB_BOOK_LOOK_ONCE": "1", "HUB_BOOK_SIZE": "2.6",
                                 "HUB_BOOK_SHEET": "contents", "HUB_DEBUG_READALONG": "1"]
        app.launch()
        let one = app.buttons["One"]
        XCTAssertTrue(one.waitForExistence(timeout: 25), "the contents did not open")
        one.tap()
        XCTAssertTrue(one.waitForNonExistence(timeout: 5), "the contents stayed open")
        // The menu the contents came with goes, and the page is the whole screen.
        let corner = app.buttons["book-corner-place"]
        if !corner.waitForExistence(timeout: 3) {
            page(app).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        XCTAssertTrue(corner.waitForExistence(timeout: 5), "the menu stayed")
        return app
    }

    @MainActor
    private func page(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "book-page").firstMatch
    }

    /// The screen page in the chapter, as the menu's line counts it (the
    /// page view's own pages, not the book's): "Page 12 of 40 in chapter" is (12, 40).
    @MainActor
    private func pageInChapter(_ app: XCUIApplication) -> (page: Int, count: Int)? {
        let line = app.staticTexts["debug-readalong"]
        guard line.waitForExistence(timeout: 5),
              let range = line.label.range(of: #"Page (\d+) of (\d+) in chapter"#, options: .regularExpression) else { return nil }
        let numbers = line.label[range].split(separator: " ").compactMap { Int($0) }
        return numbers.count == 2 ? (numbers[0], numbers[1]) : nil
    }

    /// The page once it has stopped moving: the same number twice, a second apart.
    @MainActor
    private func settledPage(_ app: XCUIApplication) -> Int? {
        var last = pageInChapter(app)?.page
        for _ in 0..<10 {
            RunLoop.current.run(until: Date().addingTimeInterval(1))
            let now = pageInChapter(app)?.page
            if now == last { return now }
            last = now
        }
        return last
    }

    /// How the hand swipes: a long sweep across most of the page, or a short flick.
    private struct Swipe {
        let name: String
        /// The share of the page's width the finger travels.
        let reach: CGFloat
        /// How long the finger is down, in seconds.
        let length: TimeInterval
        /// From one swipe's start to the next's, in seconds.
        let apart: TimeInterval
    }

    private let swipes = [
        Swipe(name: "sweeps a quarter-second apart", reach: 0.6, length: 0.08, apart: 0.25),
        Swipe(name: "flicks a quarter-second apart", reach: 0.2, length: 0.05, apart: 0.25),
        Swipe(name: "flicks a tenth apart", reach: 0.2, length: 0.05, apart: 0.1),
    ]

    /// `turns` swipes on the page, forward right to left: all in one synthesized event.
    @MainActor
    private func burst(_ app: XCUIApplication, _ turns: [Bool], _ swipe: Swipe) throws {
        let frame = page(app).frame
        let y = frame.midY
        let right = CGPoint(x: frame.midX + frame.width * swipe.reach / 2, y: y)
        let left = CGPoint(x: frame.midX - frame.width * swipe.reach / 2, y: y)
        try SwipeBurst.send(turns.map { $0 ? (right, left) : (left, right) }, apart: swipe.apart, length: swipe.length)
    }

    private struct Run {
        let name: String
        let turns: [Bool]
        /// The page in the chapter it starts on, so it stays inside the chapter.
        let from: Int
        var asked: Int { turns.reduce(0) { $0 + ($1 ? 1 : -1) } }
    }

    private let runs = [
        Run(name: "forward", turns: Array(repeating: true, count: 10), from: 2),
        Run(name: "back", turns: Array(repeating: false, count: 10), from: 13),
        Run(name: "mixed", turns: [true, true, false, true, true, true, false, true, false, true], from: 3),
    ]

    /// To `target` in the chapter a swipe at a time, as slowly as a reader turns.
    @MainActor
    private func move(_ app: XCUIApplication, from: Int, to target: Int) -> Int {
        var at = from
        for _ in 0..<30 where at != target {
            if at < target { page(app).swipeLeft() } else { page(app).swipeRight() }
            at = settledPage(app) ?? at
        }
        return at
    }

    /// What the page turner counted (debug builds), from the corner's debug line:
    /// "turns 10 frames 340 locked inner 0 outer 0 · grabs 1 jump 2.0 follow -1.00 over 12 ·
    /// swipe frames 24 stalls 0 backs 0 from 100 to 430 end 430.0". The line is drawn once a
    /// second, so it is read after one has passed.
    private struct Turns: CustomStringConvertible {
        var lockedInner = -1, lockedOuter = -1
        var grabs = 0, jump = 0.0, follow = Double.nan, followed = 0
        var swipeFrames = 0, stalls = -1, backs = -1, end = Double.nan, target = Double.nan
        var text = ""
        var description: String { text }
    }

    @MainActor
    private func turnsLine(_ app: XCUIApplication) -> Turns {
        RunLoop.current.run(until: Date().addingTimeInterval(1.2))
        let label = app.staticTexts["debug-readalong"].label
        var turns = Turns()
        func numbers(_ pattern: String) -> [Double]? {
            guard let range = label.range(of: pattern, options: .regularExpression) else { return nil }
            return label[range].split(separator: " ").compactMap { Double($0) }
        }
        if let range = label.range(of: #"turns \d+ frames.*"#, options: .regularExpression) { turns.text = String(label[range]) }
        if let n = numbers(#"locked inner \d+ outer \d+"#), n.count == 2 { turns.lockedInner = Int(n[0]); turns.lockedOuter = Int(n[1]) }
        if let n = numbers(#"grabs \d+ jump -?[\d.]+ follow -?[\d.]+ over \d+"#), n.count == 4 {
            turns.grabs = Int(n[0]); turns.jump = n[1]; turns.follow = n[2]; turns.followed = Int(n[3])
        } else if let n = numbers(#"grabs \d+ jump -?[\d.]+"#), n.count == 2 {
            turns.grabs = Int(n[0]); turns.jump = n[1]
        }
        if let n = numbers(#"swipe frames \d+ stalls \d+ backs \d+ from -?\d+ to -?\d+ end -?[\d.]+"#), n.count == 6 {
            turns.swipeFrames = Int(n[0]); turns.stalls = Int(n[1]); turns.backs = Int(n[2]); turns.target = n[4]; turns.end = n[5]
        }
        return turns
    }

    /// The chapter's title and the page on it, from the corner's line.
    @MainActor
    private func place(_ app: XCUIApplication) -> (title: String, page: Int, count: Int)? {
        let line = app.staticTexts["debug-readalong"]
        guard line.waitForExistence(timeout: 5),
              let range = line.label.range(of: #"^.*? · Page (\d+) of (\d+) in chapter"#, options: .regularExpression) else { return nil }
        let parts = String(line.label[range]).components(separatedBy: " · ")
        let numbers = parts.last?.split(separator: " ").compactMap { Int($0) } ?? []
        guard parts.count >= 2, numbers.count == 2 else { return nil }
        return (parts[0], numbers[0], numbers[1])
    }

    /// The place once it has stopped moving.
    @MainActor
    private func settledPlace(_ app: XCUIApplication) -> (title: String, page: Int, count: Int)? {
        var last = place(app)
        for _ in 0..<10 {
            RunLoop.current.run(until: Date().addingTimeInterval(1))
            let now = place(app)
            if now?.title == last?.title && now?.page == last?.page { return now }
            last = now
        }
        return last
    }

    /// Ten quick swipes forward, ten back, and ten mixed (seven on, three
    /// back), as sweeps and as flicks, a quarter of a second apart and a
    /// tenth: each burst lands exactly as many pages as it asked for.
    @MainActor
    func testEveryQuickSwipeTurnsExactlyOnePage() throws {
        let app = launchReading()
        guard let opened = settledPage(app), let count = pageInChapter(app)?.count else {
            XCTFail("the corner does not say the page")
            return
        }
        XCTAssertGreaterThanOrEqual(count, 14, "chapter One is not long enough to count in: \(count) pages")
        var results: [String] = ["chapter One: \(count) pages"]
        var at = opened
        for swipe in swipes {
            for run in runs {
                at = move(app, from: at, to: run.from)
                try burst(app, run.turns, swipe)
                let landed = settledPage(app) ?? at
                let turns = turnsLine(app)
                results.append("\(run.name), \(swipe.name): asked \(run.asked), landed \(landed - at); \(turns)")
                XCTAssertEqual(turns.lockedInner, 0, "\(run.name), \(swipe.name): a page view was switched off during the burst: \(turns)")
                XCTAssertEqual(landed - at, run.asked,
                               "\(run.name), \(swipe.name): \(run.turns.count) swipes asked for \(run.asked) pages, the reader moved \(landed - at)")
                at = landed
            }
        }
        let note = XCTAttachment(string: results.joined(separator: "\n"))
        note.name = "page-turns"
        note.lifetime = .keepAlways
        add(note)
        print("page turns: " + results.joined(separator: " | "))
    }

    /// The same bursts across the end of chapter One into the next and back
    /// (a swipe on a part's last page moves Readium's outer paging view):
    /// six on from three pages short of the end, six back again.
    @MainActor
    func testQuickSwipesAcrossTheEndOfAPartLandExactly() throws {
        let app = launchReading()
        guard let opened = settledPlace(app) else {
            XCTFail("the corner does not say the page")
            return
        }
        let first = opened.title
        let count = opened.count
        func absolute(_ place: (title: String, page: Int, count: Int)) -> Int { place.title == first ? place.page : count + place.page }
        var results: [String] = ["chapter \(first): \(count) pages"]
        for swipe in swipes {
            // To three pages short of the end of the chapter, a swipe at a time.
            for _ in 0..<12 {
                guard let here = settledPlace(app) else { break }
                let at = absolute(here)
                results.append("positioning, \(swipe.name): at \(at) (\(here.title) \(here.page) of \(here.count))")
                if at == count - 3 { break }
                let gap = count - 3 - at
                if abs(gap) > 2 {
                    // Far away: a burst of sweeps, which land exactly, gets there in a few seconds.
                    try burst(app, Array(repeating: gap > 0, count: min(abs(gap) - 1, 10)), swipes[0])
                } else if gap > 0 {
                    page(app).swipeLeft()
                } else {
                    page(app).swipeRight()
                }
            }
            guard let start = settledPlace(app) else { XCTFail("no place"); return }
            let from = absolute(start)
            XCTAssertEqual(from, count - 3, "could not get to three pages short of the end: " + results.suffix(6).joined(separator: " | "))
            try burst(app, Array(repeating: true, count: 6), swipe)
            let there = settledPlace(app)
            let on = there.map(absolute) ?? from
            results.append("on, \(swipe.name): asked 6, landed \(on - from) (\(there?.title ?? "?") \(there?.page ?? 0)); \(turnsLine(app))")
            XCTAssertEqual(on - from, 6, "6 swipes on over the end of the part, \(swipe.name): the reader moved \(on - from): " + results.suffix(3).joined(separator: " | "))
            try burst(app, Array(repeating: false, count: 6), swipe)
            let back = settledPlace(app)
            let off = back.map(absolute) ?? on
            let turns = turnsLine(app)
            results.append("back, \(swipe.name): asked -6, landed \(off - on) (\(back?.title ?? "?") \(back?.page ?? 0)); \(turns)")
            XCTAssertEqual(off - on, -6, "6 swipes back over the end of the part, \(swipe.name): the reader moved \(off - on)")
            XCTAssertEqual(turns.lockedInner, 0, "a page view was switched off during the burst: \(turns)")
        }
        let note = XCTAttachment(string: results.joined(separator: "\n"))
        note.name = "page-turns-across-parts"
        note.lifetime = .keepAlways
        add(note)
        print("page turns across parts: " + results.joined(separator: " | "))
    }

    /// A finger that comes down on a page that is still sliding takes it
    /// where it is and drags it with the finger; let go short of a turn, it
    /// goes to the page it was heading to.
    @MainActor
    func testAFingerTakesASlidingPageAndDragsItFromWhereItIs() throws {
        let app = launchReading()
        guard var at = settledPage(app) else { XCTFail("the corner does not say the page"); return }
        at = move(app, from: at, to: 5)
        let frame = page(app).frame
        let y = frame.midY
        let right = CGPoint(x: frame.midX + frame.width * 0.3, y: y)
        let left = CGPoint(x: frame.midX - frame.width * 0.3, y: y)
        // A sweep on, then, as it lets go, a finger comes down on the sliding page and drags it 100 points
        // back over most of a second, slowly, and lifts: not a turn.
        let down = CGPoint(x: frame.midX + 40, y: y + 30)
        // Two events, the second sent as the first ends: its glide (about half a second) is still going.
        try SwipeBurst.send(paths: [SwipeBurst.Path(from: right, to: left, start: 0, length: 0.08)])
        try SwipeBurst.send(paths: [SwipeBurst.Path(from: down, to: CGPoint(x: down.x + 100, y: down.y), start: 0, length: 0.9)])
        let landed = settledPage(app) ?? at
        let turns = turnsLine(app)
        print("page grab: landed \(landed - at); \(turns)")
        let note = XCTAttachment(string: "landed \(landed - at); \(turns)")
        note.name = "page-grab"
        note.lifetime = .keepAlways
        add(note)
        XCTAssertGreaterThanOrEqual(turns.grabs, 1, "the finger did not come down on a sliding page: \(turns)")
        XCTAssertLessThan(turns.jump, 20, "the page jumped when the finger took it: \(turns)")
        XCTAssertGreaterThan(turns.followed, 5, "the page did not follow the drag: \(turns)")
        XCTAssertEqual(turns.follow, -1, accuracy: 0.15, "the page did not move in step with the finger: \(turns)")
        XCTAssertEqual(landed - at, 1, "the swipe's page, and not the page nearest or the one before it: \(turns)")
    }

    /// One swipe's offset, frame by frame from release to rest: it carries
    /// the finger's speed on, and arrives, with no stop and no restart.
    @MainActor
    func testASingleSwipeGlidesWithoutStoppingAndRestarting() throws {
        let app = launchReading()
        guard var at = settledPage(app) else { XCTFail("the corner does not say the page"); return }
        at = move(app, from: at, to: 5)
        var results: [String] = []
        for swipe in swipes.prefix(2) {
            for forward in [true, false] {
                try burst(app, [forward], swipe)
                let landed = settledPage(app) ?? at
                let turns = turnsLine(app)
                results.append("\(forward ? "on" : "back"), \(swipe.name): landed \(landed - at); \(turns)")
                XCTAssertEqual(landed - at, forward ? 1 : -1, "\(swipe.name): \(turns)")
                XCTAssertGreaterThanOrEqual(turns.swipeFrames, 8, "the glide has too few frames to judge: \(turns)")
                XCTAssertEqual(turns.stalls, 0, "the page stopped before it arrived: \(turns)")
                XCTAssertEqual(turns.backs, 0, "the page went the wrong way on its way: \(turns)")
                XCTAssertEqual(turns.end, turns.target, accuracy: 1, "the page did not arrive: \(turns)")
                at = landed
            }
        }
        let note = XCTAttachment(string: results.joined(separator: "\n"))
        note.name = "page-glide"
        note.lifetime = .keepAlways
        add(note)
        print("page glide: " + results.joined(separator: " | "))
    }
}

/// Several swipes sent as one synthesized event, through XCTest's own event
/// synthesis (`XCSynthesizedEventRecord`, `XCPointerEventPath`): XCUITest's
/// public gestures each wait for the app to settle first, so they cannot be
/// quicker than a page turn. Test code only.
enum SwipeBurst {
    struct Unavailable: Error, CustomStringConvertible {
        let description: String
    }

    /// Each swipe from its first point to its second, `length` seconds long,
    /// their starts `apart` seconds from one another.
    static func send(_ swipes: [(CGPoint, CGPoint)], apart: TimeInterval, length: TimeInterval) throws {
        try send(paths: swipes.enumerated().map {
            Path(from: $0.element.0, to: $0.element.1, start: Double($0.offset) * apart, length: length)
        })
    }

    /// A finger's path: from a point to another, `length` seconds long, from `start` seconds on.
    struct Path {
        let from: CGPoint
        let to: CGPoint
        let start: TimeInterval
        let length: TimeInterval
    }

    /// Fingers going down, along and up at their own times, as one event.
    static func send(paths: [Path]) throws {
        guard let pathClass = NSClassFromString("XCPointerEventPath") as? NSObject.Type,
              let recordClass = NSClassFromString("XCSynthesizedEventRecord") as? NSObject.Type else {
            throw Unavailable(description: "XCTest has no event synthesis here")
        }
        typealias PathInit = @convention(c) (AnyObject, Selector, CGPoint, Double) -> AnyObject
        typealias Move = @convention(c) (AnyObject, Selector, CGPoint, Double) -> Void
        typealias Lift = @convention(c) (AnyObject, Selector, Double) -> Void
        typealias RecordInit = @convention(c) (AnyObject, Selector, NSString, Int) -> AnyObject
        typealias Add = @convention(c) (AnyObject, Selector, AnyObject) -> Void
        typealias Synthesize = @convention(c) (AnyObject, Selector, AutoreleasingUnsafeMutablePointer<NSError?>?) -> Bool

        func call<T>(_ object: AnyObject, _ name: String, as type: T.Type) throws -> (Selector, T) {
            let selector = NSSelectorFromString(name)
            guard object.responds(to: selector) else { throw Unavailable(description: "no \(name)") }
            return (selector, unsafeBitCast(object.method(for: selector), to: type))
        }

        let recordAlloc = recordClass.perform(NSSelectorFromString("alloc"))!.takeUnretainedValue()
        let (recordInitSelector, recordInit) = try call(recordAlloc, "initWithName:interfaceOrientation:", as: RecordInit.self)
        let record = recordInit(recordAlloc, recordInitSelector, "swipe burst" as NSString, 1)
        let (addSelector, add) = try call(record, "addPointerEventPath:", as: Add.self)
        for swipe in paths {
            let pathAlloc = pathClass.perform(NSSelectorFromString("alloc"))!.takeUnretainedValue()
            let (pathInitSelector, pathInit) = try call(pathAlloc, "initForTouchAtPoint:offset:", as: PathInit.self)
            let path = pathInit(pathAlloc, pathInitSelector, swipe.from, swipe.start)
            let (moveSelector, move) = try call(path, "moveToPoint:atOffset:", as: Move.self)
            move(path, moveSelector, swipe.to, swipe.start + swipe.length)
            let (liftSelector, lift) = try call(path, "liftUpAtOffset:", as: Lift.self)
            lift(path, liftSelector, swipe.start + swipe.length)
            add(record, addSelector, path)
        }
        let (synthesizeSelector, synthesize) = try call(record, "synthesizeWithError:", as: Synthesize.self)
        var error: NSError?
        guard synthesize(record, synthesizeSelector, &error) else {
            throw Unavailable(description: "the burst was not sent: \(error?.localizedDescription ?? "no reason")")
        }
    }
}
