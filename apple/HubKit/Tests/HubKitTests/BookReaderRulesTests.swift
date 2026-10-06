import Foundation
import Testing
@testable import HubKit

/// The ebook reader's rules (#25, phase 4), with Android's test cases:
/// `ReadingPaceTest`, `BookScrollTest`, `FootnoteTextTest`,
/// `ReaderPagePreviewTest` and `EpubReaderStateTest`, then the arithmetic
/// Android keeps in its screen (`BookSections`).
struct BookReaderRulesTests {
    // MARK: The pace and the time left (ReadingPaceTest)

    @Test func beforeAnyReadingThePaceIsThePrior() {
        #expect(abs(ReadingPace().minutesPerPosition() - ReadingPace.defaultMinutesPerPosition) < 1e-9)
        #expect(abs(ReadingPace().minutesPerPosition(prior: 2.5) - 2.5) < 1e-9)
    }

    @Test func pagesReadAtASteadyPaceCalibrateIt() {
        var pace = ReadingPace()
        // A page of half a position every 40 s: 1.333 minutes a position.
        for index in 0..<60 { pace = pace.observe(from: Double(index) * 0.5, to: Double(index + 1) * 0.5, elapsedMs: 40_000) }
        #expect(abs(pace.positions - 30) < 1e-9)
        #expect(abs(pace.minutes - 40) < 1e-9)
        let minutes = pace.minutesPerPosition()
        #expect((1.33...1.39).contains(minutes), "\(minutes) leans to 1.333 from 1.6")
    }

    @Test func jumpsPausesMovesBackAndFlickingThroughCountForNothing() {
        let pace = ReadingPace(positions: 10, minutes: 15)
        #expect(pace.observe(from: 10, to: 40, elapsedMs: 60_000) == pace)
        #expect(pace.observe(from: 10, to: 10.5, elapsedMs: 20 * 60_000) == pace)
        #expect(pace.observe(from: 10, to: 9.5, elapsedMs: 30_000) == pace)
        #expect(pace.observe(from: 10, to: 11, elapsedMs: 2_000) == pace)
        // Three positions in twenty seconds is about 3,600 words a minute.
        #expect(pace.observe(from: 10, to: 13, elapsedMs: 20_000) == pace)
    }

    @Test func onlyTheRecentReadingCounts() {
        var pace = ReadingPace(positions: ReadingPace.windowPositions, minutes: ReadingPace.windowPositions * 3)
        // Four windows' worth at a minute a position, after reading at three.
        for _ in 0..<1_000 { pace = pace.observe(from: 0, to: 0.5, elapsedMs: 30_000) }
        #expect(abs(pace.positions - ReadingPace.windowPositions) < 1e-6)
        #expect(pace.minutesPerPosition() < 1.1, "\(pace.minutesPerPosition()) follows the new pace of 1.0")
    }

    @Test func itKeepsAsWordsAndReadsBack() {
        let pace = ReadingPace(positions: 12.5, minutes: 20.25)
        #expect(ReadingPace.decode(pace.encode()) == pace)
        #expect(pace.encode() == "12.5000|20.2500")
        #expect(ReadingPace.decode(nil) == ReadingPace())
        #expect(ReadingPace.decode("nonsense") == ReadingPace())
        #expect(ReadingPace.decode("-1|3") == ReadingPace())
        #expect(ReadingPace(positions: 1, minutes: 1) + ReadingPace(positions: 2, minutes: 3) == ReadingPace(positions: 3, minutes: 4))
    }

    @Test func theTrackerMeasuresFromWhereTheReadingWasAndStartsAgainAfterAPauseOrAJump() throws {
        var tracker = ReadingPace.Tracker()
        let first = tracker.at(10, nowMs: 0)
        #expect(first == nil)
        // Scrolled a little: too soon to judge, the start is kept.
        let soon = tracker.at(10.1, nowMs: 1_000)
        #expect(soon == nil)
        let measured = tracker.at(10.6, nowMs: 45_000)
        let reading = try #require(measured)
        #expect(reading == ReadingPace.Reading(from: 10, to: 10.6, elapsedMs: 45_000))
        let learnt = ReadingPace().observe(reading)
        #expect(abs(learnt.positions - 0.6) < 1e-9)
        #expect(abs(learnt.minutes - 0.75) < 1e-9)
        // A jump through the contents: measured again from there.
        let jumped = tracker.at(50, nowMs: 60_000)
        #expect(jumped == nil)
        // A pause: from where the book was picked up again.
        let paused = tracker.at(50.5, nowMs: 60_000 + ReadingPace.maxStepMs + 1)
        #expect(paused == nil)
        let resumed = tracker.at(51, nowMs: 60_000 + ReadingPace.maxStepMs + 31_000)
        #expect(resumed == ReadingPace.Reading(from: 50.5, to: 51, elapsedMs: 30_999))
        // Back a page: nothing, and measured from there.
        let back = tracker.at(50.8, nowMs: 60_000 + ReadingPace.maxStepMs + 41_000)
        #expect(back == nil)
        tracker.restart()
        let again = tracker.at(51, nowMs: 0)
        #expect(again == nil)
    }

    @Test func timeLeftFromThePositionsInTheChapterAndInTheBook() throws {
        // Four parts of 10, 20, 30 and 40 positions; halfway through the second, at 1.5 minutes a position.
        let left = try #require(TimeLeft.ofPositions([10, 20, 30, 40], section: 1, progression: 0.5, minutesPerPosition: 1.5))
        #expect(left.chapterMs == 15 * 60_000)
        #expect(left.bookMs == 120 * 60_000)
        #expect(left.label() == "15 min left in chapter · 2h 0m in book")
        #expect(TimeLeft.ofPositions([], section: 0, progression: 0, minutesPerPosition: 1.5) == nil)
        #expect(abs((TimeLeft.position([10, 20, 30, 40], section: 1, progression: 0.75) ?? 0) - 25) < 1e-9)
    }

    @Test func underAMinuteStillReadsAsAMinute() {
        #expect(TimeLeft(chapterMs: 5_000, bookMs: 30_000).label() == "1 min left in chapter · 1 min in book")
        #expect(TimeLeft(chapterMs: 12 * 60_000, bookMs: 250 * 60_000).label() == "12 min left in chapter · 4h 10m in book")
    }

    @Test func thePaceIsKeptPerEditionAndOverEveryBook() throws {
        let defaults = try #require(UserDefaults(suiteName: "book-pace-\(UUID().uuidString)"))
        let store = ReadingPaceStore(defaults: defaults)
        let key = ReadingPaceStore.key(workId: "rw_book", sourceItemId: "12")
        #expect(key == "rw_book:12")
        #expect(abs(store.prior() - ReadingPace.defaultMinutesPerPosition) < 1e-9)
        let reading = ReadingPace.Reading(from: 10, to: 10.5, elapsedMs: 30_000)
        let learnt = store.record(key, book: store.book(key), reading: reading)
        #expect(learnt == ReadingPace(positions: 0.5, minutes: 0.5))
        #expect(store.book(key) == learnt)
        #expect(store.book("rw_other:1") == ReadingPace())
        // Every book leans on the reading too: a minute a position, pulled toward it from 1.6.
        #expect(store.prior() < ReadingPace.defaultMinutesPerPosition)
        // A jump is not reading, and keeps nothing.
        let unchanged = store.record(key, book: learnt, reading: ReadingPace.Reading(from: 10, to: 40, elapsedMs: 60_000))
        #expect(unchanged == learnt)
    }

    // MARK: Scrolling (BookScrollTest)

    @Test func aGentlePushStillMovesAPointAtATimeAsTheFractionsAddUp() {
        var scroll = BookScroll(edgePx: 300)
        // 0.4 of a point a frame used to round down to nothing, every frame.
        var moves: [BookScroll.Move] = []
        for _ in 0..<10 { moves.append(scroll.glide(0.4, canDown: true, canUp: true)) }
        #expect(Self.moved(moves) == 4)
        #expect(moves.first == .stay)
    }

    @Test func upCarriesItsOwnFractions() {
        var scroll = BookScroll(edgePx: 300)
        var moves: [BookScroll.Move] = []
        for _ in 0..<5 { moves.append(scroll.glide(-0.5, canDown: true, canUp: true)) }
        #expect(Self.moved(moves) == -2)
    }

    /// How far a run of moves scrolled, in points.
    private static func moved(_ moves: [BookScroll.Move]) -> Int {
        var total = 0
        for move in moves {
            if case .by(let px) = move { total += px }
        }
        return total
    }

    @Test func theStickTipsIntoTheNextPartOnlyAfterPushingOnPastTheEnd() {
        var scroll = BookScroll(edgePx: 100)
        let first = scroll.glide(40, canDown: false, canUp: true)
        let second = scroll.glide(40, canDown: false, canUp: true)
        let third = scroll.glide(40, canDown: false, canUp: true)
        // Then it starts again from nothing.
        let fourth = scroll.glide(40, canDown: false, canUp: true)
        #expect([first, second, third, fourth] == [.stay, .stay, .nextPart, .stay])
    }

    @Test func aPushThatTurnsBackOrScrollsAgainStartsTheEdgeOver() {
        var scroll = BookScroll(edgePx: 100)
        _ = scroll.glide(80, canDown: false, canUp: true)
        let back1 = scroll.glide(-30, canDown: false, canUp: false)
        let back2 = scroll.glide(-60, canDown: false, canUp: false)
        let back3 = scroll.glide(-20, canDown: false, canUp: false)
        #expect([back1, back2, back3] == [.stay, .stay, .previousPart])
        _ = scroll.glide(80, canDown: false, canUp: true)
        let free = scroll.glide(5, canDown: true, canUp: true)
        let blocked = scroll.glide(80, canDown: false, canUp: true)
        #expect(free == .by(5))
        #expect(blocked == .stay)
    }

    @Test func theDpadScrollsAtOnceAndGoesOnToTheNextPartAtTheEnd() {
        var scroll = BookScroll(edgePx: 100)
        let down = scroll.step(342, canDown: true, canUp: true)
        let next = scroll.step(342, canDown: false, canUp: true)
        let previous = scroll.step(-342, canDown: true, canUp: false)
        let still = scroll.step(0, canDown: true, canUp: true)
        #expect([down, next, previous, still] == [.by(342), .nextPart, .previousPart, .stay])
    }

    // MARK: Footnotes (FootnoteTextTest)

    @Test func paragraphsStayAndTheLinkBackGoes() {
        let html = #"<p>The observatory was built in 1891 &amp; rebuilt twice.</p><p>See also the <em>Almanac</em>. <a href="one.xhtml#ref1">↩</a></p>"#
        #expect(FootnoteText.plain(html) == "The observatory was built in 1891 & rebuilt twice.\n\nSee also the Almanac.")
    }

    @Test func entitiesDecodeOnceAndBreaksBecomeLines() {
        #expect(FootnoteText.plain("a &amp;lt; b<br/>c &mdash; d &#8220;e&#x201D;") == "a &lt; b\nc — d “e”")
    }

    @Test func theNotesOwnNumberIsKeptArrowsWrittenOutAreDropped() {
        #expect(FootnoteText.plain(##"<a href="#r1">1.</a> A lantern of the old kind. &#8617;"##) == "1. A lantern of the old kind.")
        #expect(FootnoteText.plain(##"  Only   words.  <a href="#back">&#x21a9;</a> "##) == "Only words.")
    }

    @Test func emptyNotesStayEmpty() {
        #expect(FootnoteText.plain("<p> </p>") == "")
    }

    // MARK: The page making room (ReaderPagePreviewTest)

    @Test func readingWithoutMenusUsesTheCompleteViewport() {
        #expect(ReaderPagePreview.fit(width: 1920, height: 1080) == .identity)
    }

    @Test func theMenuFitsThePageBetweenItsBarsWithoutChangingItsShape() {
        let result = ReaderPagePreview.fit(width: 1920, height: 1080, top: 130, bottom: 130, margin: 24)
        #expect(abs(result.scale - 772.0 / 1080.0) < 0.0001)
        #expect(abs(result.y - 154) < 0.001)
        #expect(result.x >= 24)
        #expect(result.y + 1080 * result.scale <= 926.001)
    }

    @Test func aSheetLeavesTheWholePageVisibleBesideIt() {
        for (width, height, right) in [(1920.0, 1080.0, 750.0), (1080, 1920, 650), (640, 360, 320)] {
            let result = ReaderPagePreview.fit(width: width, height: height, right: right, margin: 12)
            #expect(result.scale > 0 && result.scale < 1)
            #expect(result.x >= 12)
            #expect(result.y >= 12)
            #expect(result.x + width * result.scale <= width - right - 12 + 0.01)
            #expect(result.y + height * result.scale <= height - 12 + 0.01)
        }
    }

    @Test func closingAgainAndAgainRestoresTheSameFullSizePage() {
        for _ in 0..<10 {
            _ = ReaderPagePreview.fit(width: 1920, height: 1080, right: 750, margin: 24)
            #expect(ReaderPagePreview.fit(width: 1920, height: 1080) == .identity)
        }
    }

    @Test func unmeasuredViewsAndVerySmallWindowsAreSafe() {
        #expect(ReaderPagePreview.fit(width: 0, height: 0, top: 60) == .identity)
        let result = ReaderPagePreview.fit(width: 80, height: 40, top: 60, bottom: 60, right: 320, margin: 12)
        #expect(result.scale.isFinite && result.scale > 0)
        #expect(result.x >= 0 && result.y >= 0)
        #expect(result.x + 80 * result.scale <= 80)
        #expect(result.y + 40 * result.scale <= 40)
    }

    // MARK: Appearance (EpubReaderStateTest)

    @Test func onePageExitsScrollingAndSpreadsAndAnExplicitLayoutReplacesIt() {
        let single = EpubLayoutPolicy.selectOnePage(EpubReaderPreferences(columns: .two, scroll: true), true)
        #expect(!single.scroll)
        #expect(single.columns == .one)
        #expect(EpubLayoutPolicy.columnCount(single, viewportWidth: 1200, publicationAllowsSpreads: true) == 1)
        #expect(!EpubLayoutPolicy.selectColumns(single, .two).onePagePerScreen)
        #expect(!EpubLayoutPolicy.selectScroll(single, true).onePagePerScreen)
        let disabled = EpubLayoutPolicy.selectOnePage(single, false)
        #expect(!disabled.onePagePerScreen)
        #expect(disabled.columns == .one)
    }

    @Test func aMiddleTapShowsTheMenuAndAnyTapOnThePageClosesIt() {
        #expect(EpubChromePolicy.handlesTap(0.5, controlsVisible: false))
        #expect(!EpubChromePolicy.handlesTap(0.1, controlsVisible: false))
        #expect(!EpubChromePolicy.handlesTap(0.9, controlsVisible: false))
        #expect(EpubChromePolicy.handlesTap(0.1, controlsVisible: true))
    }

    @Test func twoColumnsOnlyForWidePaginatedBooks() {
        let auto = EpubReaderPreferences(columns: .auto, scroll: false)
        #expect(EpubLayoutPolicy.columnCount(auto, viewportWidth: 980, publicationAllowsSpreads: true) == 2)
        #expect(EpubLayoutPolicy.columnCount(auto, viewportWidth: 680, publicationAllowsSpreads: true) == 1)
        var scrolling = auto
        scrolling.scroll = true
        #expect(EpubLayoutPolicy.columnCount(scrolling, viewportWidth: 1200, publicationAllowsSpreads: true) == 1)
        #expect(EpubLayoutPolicy.columnCount(auto, viewportWidth: 1200, publicationAllowsSpreads: false) == 1)
        var one = auto
        one.columns = .one
        #expect(EpubLayoutPolicy.columnCount(one, viewportWidth: 1200, publicationAllowsSpreads: true) == 1)
        var two = auto
        two.columns = .two
        #expect(EpubLayoutPolicy.columnCount(two, viewportWidth: 800, publicationAllowsSpreads: true) == 2)
    }

    @Test func choosingTwoColumnsEndsScrollingAndScrollingEndsTwoColumns() {
        let scrolling = EpubReaderPreferences(columns: .one, scroll: true)
        let two = EpubLayoutPolicy.selectColumns(scrolling, .two)
        #expect(!two.scroll)
        #expect(EpubLayoutPolicy.columnCount(two, viewportWidth: 800, publicationAllowsSpreads: true) == 2)
        let continuous = EpubLayoutPolicy.selectScroll(two, true)
        #expect(continuous.scroll)
        #expect(continuous.columns == .auto)
    }

    @Test func appearanceChangesAreKeptAtOnceAndClosingKeepsThem() {
        let initial = EpubReaderPreferences(theme: .sepia, fontScale: 1)
        var state = EpubPreferenceState(initial)
        var changed = initial
        changed.theme = .dark
        changed.fontScale = 1.2
        state.preview(changed)
        #expect(state.visible.theme == .dark)
        let committed = state.commit()
        #expect(committed)
        #expect(state.saved.theme == .dark)
        #expect(state.saved.fontScale == 1.2)
        #expect(state.dirty)
        state.markPersisted()
        state.cancel()
        #expect(state.saved == state.visible)
        let again = state.commit()
        #expect(!again)
    }

    @Test func theAppearanceSheetsChoicesSetWhatAndroidsSet() {
        let start = EpubReaderPreferences()
        #expect(!EpubAppearance.typeface(start, "serif").publisherStyles)
        #expect(EpubAppearance.typeface(start, "publisher").publisherStyles)
        #expect(EpubAppearance.fontSize(start, steps: 2).fontScale == 1.2)
        #expect(EpubAppearance.fontSize(start, steps: -9).fontScale == 0.7)
        #expect(EpubAppearance.fontSize(start, steps: 30).fontScale == 2)
        #expect(EpubAppearance.fontSizeLabel(1.2) == "120%")
        let spaced = EpubAppearance.lineSpacing(start, 1.5)
        #expect(spaced.lineHeight == 1.5 && !spaced.publisherStyles)
        let justified = EpubAppearance.justified(start)
        #expect(justified.textAlignment == "justify" && !justified.publisherStyles)
        #expect(EpubAppearance.justified(justified).textAlignment == "start")
        #expect(EpubAppearance.same(1.7, 1.7000001))
    }

    @Test func theBookIsDrawnWithThePagesColoursAndTheRightColumns() {
        var value = EpubReaderPreferences(theme: .blue, fontFamily: "sans-serif", fontScale: 1.3, columns: .two,
                                          textAlignment: "justify")
        let blue = EpubRendering(value, systemDark: false)
        #expect(blue.theme == "dark")
        #expect(blue.background == "#1D303D")
        #expect(blue.text == "#DCE6E8")
        #expect(blue.fontFamily == "sans-serif")
        #expect(blue.columns == .two)
        #expect(blue.textAlign == "justify")
        value.onePagePerScreen = true
        value.scroll = true
        let onePage = EpubRendering(value, systemDark: false)
        #expect(onePage.columns == .one && !onePage.scroll)
        // The publisher's face is no face of ours; system colours follow the device.
        let system = EpubRendering(EpubReaderPreferences(theme: .system), systemDark: true)
        #expect(system.fontFamily == nil)
        #expect(system.theme == "dark" && system.background == "#202020")
        #expect(EpubRendering(EpubReaderPreferences(theme: .system), systemDark: false).background == "#FBFAF6")
        #expect(EpubRendering(EpubReaderPreferences(), systemDark: false).theme == "sepia")
    }

    @Test func theAppearanceIsKeptForEveryBookAndReadBackWithinItsLimits() throws {
        let defaults = try #require(UserDefaults(suiteName: "book-appearance-\(UUID().uuidString)"))
        #expect(EpubAppearanceStore.load(defaults) == EpubReaderPreferences())
        let chosen = EpubReaderPreferences(theme: .dark, fontFamily: "serif", fontScale: 1.4, lineHeight: 1.5,
                                           pageMargins: 1.7, columns: .one, scroll: true, publisherStyles: false,
                                           textAlignment: "justify")
        EpubAppearanceStore.save(chosen, to: defaults)
        #expect(EpubAppearanceStore.load(defaults) == chosen)
        defaults.set(9.0, forKey: "epub.fontScale")
        defaults.set("NOPE", forKey: "epub.theme")
        defaults.set(true, forKey: "epub.onePagePerScreen")
        let clamped = EpubAppearanceStore.load(defaults)
        #expect(clamped.fontScale == 2)
        #expect(clamped.theme == .sepia)
        // One page per screen read back as it was chosen: one column, no scrolling.
        #expect(clamped.columns == .one && !clamped.scroll)
    }

    // MARK: The book's parts (BookSections)

    private let book = BookSections(positions: [
        ("OEBPS/about.xhtml", 0), ("OEBPS/one.xhtml", 0.1), ("OEBPS/one.xhtml", 0.2), ("OEBPS/one.xhtml", 0.3),
        ("OEBPS/two.xhtml", 0.4), ("OEBPS/two.xhtml", 0.5), ("OEBPS/two.xhtml", 0.6), ("OEBPS/two.xhtml", 0.7),
        ("OEBPS/three.xhtml", 0.8), ("OEBPS/three.xhtml", 0.9),
    ])

    @Test func thePartsAndTheirSizesComeFromThePositions() {
        #expect(book.sections.map(\.href) == ["OEBPS/about.xhtml", "OEBPS/one.xhtml", "OEBPS/two.xhtml", "OEBPS/three.xhtml"])
        #expect(book.sizes == [1, 3, 4, 2])
        #expect(book.index(of: "OEBPS/two.xhtml#part-2") == 2)
        #expect(book.index(of: "OEBPS/missing.xhtml") == nil)
    }

    @Test func howFarThroughTheBookReadsSmoothlyWithinAPart() throws {
        // Halfway through the second part: between its start (0.4) and the next's (0.8).
        let halfway = try #require(book.progress(href: "OEBPS/two.xhtml", progression: 0.5, totalProgression: 0.55))
        #expect(abs(halfway - 0.6) < 1e-9)
        // The last part runs to the end of the book.
        #expect(abs((book.progress(href: "OEBPS/three.xhtml", progression: 1, totalProgression: nil) ?? 0) - 1) < 1e-9)
        // A part not known: Readium's own answer.
        #expect(book.progress(href: "OEBPS/x.xhtml", progression: 0.5, totalProgression: 0.42) == 0.42)
    }

    @Test func theSliderFindsThePartAndThePlaceInIt() throws {
        let place = try #require(book.seek(0.6))
        #expect(place.href == "OEBPS/two.xhtml")
        #expect(abs(place.progression - 0.5) < 1e-9)
        #expect(book.seek(0)?.href == "OEBPS/about.xhtml")
        #expect(book.seek(1)?.href == "OEBPS/three.xhtml")
        #expect(abs((book.seek(1)?.progression ?? 0) - 1) < 1e-9)
        #expect(BookSections(positions: []).seek(0.5) == nil)
    }

    @Test func thePlaceInPositionsAndTheTimeLeftComeFromTheParts() throws {
        #expect(abs((book.position(href: "OEBPS/two.xhtml", progression: 0.5) ?? 0) - 6) < 1e-9)
        let left = try #require(book.timeLeft(href: "OEBPS/two.xhtml", progression: 0.5, minutesPerPosition: 2))
        #expect(left.chapterMs == 4 * 60_000)
        #expect(left.bookMs == 8 * 60_000)
    }

    @Test func theMenusLineAndTheChapterKeys() {
        #expect(BookSections.line(title: "Four", page: (2, 12), progress: 0.4946) == "Four · Page 3 of 12 in chapter · 49% of book")
        #expect(BookSections.line(title: " ", page: (0, 1), progress: nil) == "Reading")
        #expect(BookSections.browseLine(0.432) == "Go to 43%")
        #expect(BookSections.chapter(from: 2, delta: 1, count: 4) == 3)
        #expect(BookSections.chapter(from: 3, delta: 1, count: 4) == nil)
        #expect(BookSections.chapter(from: 0, delta: -1, count: 4) == nil)
    }
}
