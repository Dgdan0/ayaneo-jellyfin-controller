import Foundation
import Testing
@testable import HubKit

/// Kindle's corners while reading (#42): their words, the tap's order, the
/// choices kept on the device, the clock as the device writes the time, and
/// the page in the book from Readium's positions.
struct PageInfoTests {
    /// Light Bringer 49% through its 735 pages, in a chapter from 48% to 56% of the book.
    private let reading = PageInfo.Reading(bookPages: 735, progress: 0.4946, chapter: 0.48...0.56,
                                           positionInBook: (1_650, 3_400), positionInChapter: (40, 180),
                                           timeLeft: TimeLeft(chapterMs: 12 * 60_000, bookMs: 250 * 60_000))

    @Test func eachWayOfSayingWhereYouAreHasItsWords() {
        // The hub's pages, rounded as the book's page rounds them ("49% · page 363 of 735").
        #expect(PageInfo.label(.pageInBook, reading) == "Page 363 of 735")
        #expect(ReadingBookFacts.progress(ReadingWork(id: "w", kind: "book",
                                                      editions: [ReadingEdition(sourceItemId: "e", kind: "ebook", pageCount: 735)],
                                                      progress: ReadingProgress(percentage: 0.4946)))
                == "49% · page 363 of 735")
        // The chapter's share of them: pages 352 to 410, the 12th of 59.
        #expect(PageInfo.label(.pageInChapter, reading) == "Page 12 of 59 in chapter")
        #expect(PageInfo.label(.chapterTimeLeft, reading) == "12 min left in chapter")
        #expect(PageInfo.label(.bookTimeLeft, reading) == "4h 10m left in book")
        #expect(PageInfo.label(.none, reading) == nil)
        #expect(PageInfoPlace.allCases.map(\.title)
                == ["Page in book", "Page in chapter", "Time left in chapter", "Time left in book", "None"])
        // Under a minute is a minute, as the menu's line says it.
        #expect(TimeLeft(chapterMs: 5_000, bookMs: 30_000).chapterLabel() == "1 min left in chapter")
        #expect(TimeLeft(chapterMs: 12 * 60_000, bookMs: 250 * 60_000).label() == "12 min left in chapter · 4h 10m in book")
        // Nothing to say yet: no pages, no positions, no pace.
        #expect(PageInfo.label(.pageInBook, PageInfo.Reading()) == nil)
        #expect(PageInfo.label(.pageInChapter, PageInfo.Reading(bookPages: 735, progress: 0.4)) == nil)
        #expect(PageInfo.label(.chapterTimeLeft, PageInfo.Reading(bookPages: 735, progress: 0.4)) == nil)
    }

    @Test func pagesAreTheHubsAndPositionsOnlyWhenItHasNoCount() {
        // A Game of Thrones: 765 pages, never Readium's 3,400 positions.
        let thrones = PageInfo.Reading(bookPages: 765, progress: 0.4412, chapter: 0.43...0.45,
                                       positionInBook: (1_500, 3_400), positionInChapter: (5, 60))
        #expect(PageInfo.label(.pageInBook, thrones) == "Page 337 of 765")
        // No count from the hub: the positions, counted from one.
        let unknown = PageInfo.Reading(progress: 0.4412, chapter: 0.43...0.45,
                                       positionInBook: (1_500, 3_400), positionInChapter: (5, 60))
        #expect(PageInfo.label(.pageInBook, unknown) == "Page 1501 of 3400")
        #expect(PageInfo.label(.pageInChapter, unknown) == "Page 6 of 60 in chapter")
        // The first page, and the last chapter running to the book's end.
        #expect(PageInfo.label(.pageInBook, PageInfo.Reading(bookPages: 765, progress: 0)) == "Page 1 of 765")
        let last = PageInfo.Reading(bookPages: 765, progress: 1, chapter: 0.98...1)
        #expect(PageInfo.label(.pageInBook, last) == "Page 765 of 765")
        #expect(PageInfo.label(.pageInChapter, last) == "Page 17 of 17 in chapter")
        // A chapter shorter than a page is still a page.
        #expect(PageInfo.label(.pageInChapter, PageInfo.Reading(bookPages: 100, progress: 0.501, chapter: 0.5...0.505))
                == "Page 1 of 1 in chapter")
        // The edition being read counts, else the longest text edition; an audiobook's never.
        let work = ReadingWork(id: "w", kind: "book", editions: [
            ReadingEdition(sourceItemId: "audio", kind: "audiobook", pageCount: 9_999),
            ReadingEdition(sourceItemId: "big", kind: "ebook", pageCount: 800),
            ReadingEdition(sourceItemId: "small", kind: "ebook", pageCount: 765),
        ])
        #expect(PageInfo.bookPages(work, sourceItemId: "small") == 765)
        #expect(PageInfo.bookPages(work, sourceItemId: "other") == 800)
        #expect(PageInfo.bookPages(ReadingWork(id: "w", kind: "book"), sourceItemId: "x") == 0)
    }

    @Test func thePercentageIsTheResumeButtonsAndEachCornerCanBeTurnedOff() {
        let all = PageInfo.corners(PageInfoPreferences(), reading)
        #expect(all == PageInfoCorners(place: "Page 363 of 735", percent: "49%"))
        #expect(Fmt.readingPercentLabel(0.4946) == "49%")
        let quiet = PageInfo.corners(PageInfoPreferences(clock: false, place: .none, percentage: false), reading)
        #expect(quiet == PageInfoCorners())
        #expect(PageInfo.corners(PageInfoPreferences(place: .bookTimeLeft), PageInfo.Reading(progress: nil)).percent == nil)
    }

    @Test func aTapShowsTheNextWayPastAnyThatCannotBeSaid() {
        #expect(PageInfoPlace.cycle == [.pageInBook, .pageInChapter, .chapterTimeLeft, .bookTimeLeft])
        var value = PageInfoPreferences(place: .pageInBook)
        var seen: [PageInfoPlace] = []
        for _ in 0..<4 {
            value = PageInfo.next(value, reading)
            seen.append(value.place)
        }
        #expect(seen == [.pageInChapter, .chapterTimeLeft, .bookTimeLeft, .pageInBook])
        // A chapter whose pages cannot be said yet: the tap passes it.
        let early = PageInfo.Reading(bookPages: 735, progress: 0.1, timeLeft: TimeLeft(chapterMs: 60_000, bookMs: 600_000))
        #expect(PageInfo.next(PageInfoPreferences(place: .pageInBook), early).place == .chapterTimeLeft)
        // Nothing else can be said: it stays.
        #expect(PageInfo.next(PageInfoPreferences(place: .pageInBook), PageInfo.Reading(bookPages: 3, progress: 0.5)).place
                == .pageInBook)
        // None is chosen in Appearance and stays, whatever the tap or L3; the cycle itself starts again from it.
        #expect(PageInfo.next(PageInfoPreferences(place: .none), reading).place == .none)
        #expect(PageInfoPlace.none.next { _ in true } == .pageInBook)
        // The other corners are not the tap's to change.
        let tapped = PageInfo.next(PageInfoPreferences(clock: false, place: .bookTimeLeft, percentage: false), reading)
        #expect(tapped == PageInfoPreferences(clock: false, place: .pageInBook, percentage: false))
    }

    @Test func theChoicesAreKeptOnTheDevice() throws {
        let suite = "page-info-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        #expect(PageInfoStore.load(defaults) == PageInfoPreferences(clock: true, place: .pageInBook, percentage: true))
        let chosen = PageInfoPreferences(clock: false, place: .none, percentage: false)
        PageInfoStore.save(chosen, to: defaults)
        #expect(PageInfoStore.load(defaults) == chosen)
        PageInfoStore.save(PageInfoPreferences(place: .chapterTimeLeft), to: defaults)
        #expect(PageInfoStore.load(defaults).place == .chapterTimeLeft)
        // Beside the reader's look, under its own names.
        #expect(defaults.object(forKey: "epub.pageInfo.clock") != nil)
        defaults.set("location", forKey: "epub.pageInfo.place")
        #expect(PageInfoStore.load(defaults).place == .pageInBook)
    }

    @Test func theClockFollowsTheDevicesTwelveOrTwentyFourHours() throws {
        let utc = try #require(TimeZone(identifier: "UTC"))
        // 2026-10-08 21:41 UTC.
        let evening = Date(timeIntervalSince1970: 1_791_495_660)
        let twelve = PageInfo.clock(evening, locale: Locale(identifier: "en_US"), timeZone: utc)
        #expect(twelve.hasPrefix("9:41") && twelve.hasSuffix("PM"))
        #expect(PageInfo.clock(evening, locale: Locale(identifier: "en_GB"), timeZone: utc) == "21:41")
        // A device set to 24 hours whatever its region (`hourCycle`).
        var components = Locale.Components(identifier: "en_US")
        components.hourCycle = .zeroToTwentyThree
        #expect(PageInfo.clock(evening, locale: Locale(components: components), timeZone: utc) == "21:41")
    }

    @Test func thePositionsAndTheChaptersSpanComeFromReadiumsParts() {
        let sections = BookSections(sections: [.init(href: "a.xhtml", start: 0, size: 10),
                                               .init(href: "b.xhtml", start: 0.25, size: 30)])
        func inBook(_ href: String, _ progression: Double) -> [Int]? {
            sections.positionInBook(href: href, progression: progression).map { [$0.index, $0.count] }
        }
        func inChapter(_ href: String, _ progression: Double) -> [Int]? {
            sections.positionInChapter(href: href, progression: progression).map { [$0.index, $0.count] }
        }
        #expect(inBook("a.xhtml", 0) == [0, 40])
        #expect(inBook("b.xhtml#x", 0.5) == [25, 40])
        #expect(inBook("b.xhtml", 1) == [39, 40])
        #expect(inChapter("b.xhtml", 0.5) == [15, 30])
        #expect(inChapter("b.xhtml", 1) == [29, 30])
        #expect(sections.positionInBook(href: "c.xhtml", progression: 0.5) == nil)
        #expect(BookSections(sections: []).positionInBook(href: "a.xhtml", progression: 0) == nil)
        #expect(sections.span(href: "a.xhtml#n1") == 0...0.25)
        #expect(sections.span(href: "b.xhtml") == 0.25...1)
        #expect(sections.span(href: "c.xhtml") == nil)
    }

    @Test func thePagesInkIsReadFromItsHex() {
        #expect(EpubPagePalette.argb("#3E3526") == 0xFF3E_3526)
        #expect(EpubPagePalette.argb(EpubPagePalette.hex(0xFFDF_DFD8)) == 0xFFDF_DFD8)
        #expect(EpubPagePalette.argb("#12345") == nil && EpubPagePalette.argb("#GGGGGG") == nil)
        let compact = PageInfo.strip(compactHeight: true), regular = PageInfo.strip(compactHeight: false)
        #expect([compact.top, compact.bottom, regular.top, regular.bottom] == [34, 34, 62, 62])
        // An iPad's page starts lower and ends higher, as Kindle's does (#47).
        let pad = PageInfo.strip(compactHeight: false, tablet: true)
        #expect([pad.top, pad.bottom] == [84, 96])
        #expect(PageInfo.strip(compactHeight: true, tablet: true) == compact)
        let baselines = PageInfo.baselines(tablet: true, strip: pad)
        #expect([baselines.top, baselines.bottom] == [50, 61])
        // Too short for the deep strips, an iPad's corners sit in the middle of the shallower ones.
        let short = PageInfo.baselines(tablet: true, strip: compact)
        #expect(short.top > 17 && short.top < 24 && short.top == short.bottom)
        let phone = PageInfo.baselines(tablet: false, strip: regular)
        #expect(phone.top > 31 && phone.top < 40 && phone.top == phone.bottom, "in the middle of the strip, a little below it")
        #expect(PageInfo.cornerSize == 13)
        #expect(PageInfo.title("  Light   Bringer\n") == "Light Bringer")
    }
}
