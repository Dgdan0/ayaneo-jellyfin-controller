import XCTest

/// Quick swipes in the ebook reader (#64): every swipe turns exactly one
/// page, however fast they come. A burst of swipes is sent to the demo's
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
                results.append("\(run.name), \(swipe.name): asked \(run.asked), landed \(landed - at)")
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
        for (index, swipe) in swipes.enumerated() {
            let start = Double(index) * apart
            let pathAlloc = pathClass.perform(NSSelectorFromString("alloc"))!.takeUnretainedValue()
            let (pathInitSelector, pathInit) = try call(pathAlloc, "initForTouchAtPoint:offset:", as: PathInit.self)
            let path = pathInit(pathAlloc, pathInitSelector, swipe.0, start)
            let (moveSelector, move) = try call(path, "moveToPoint:atOffset:", as: Move.self)
            move(path, moveSelector, swipe.1, start + length)
            let (liftSelector, lift) = try call(path, "liftUpAtOffset:", as: Lift.self)
            lift(path, liftSelector, start + length)
            add(record, addSelector, path)
        }
        let (synthesizeSelector, synthesize) = try call(record, "synthesizeWithError:", as: Synthesize.self)
        var error: NSError?
        guard synthesize(record, synthesizeSelector, &error) else {
            throw Unavailable(description: "the burst was not sent: \(error?.localizedDescription ?? "no reason")")
        }
    }
}
