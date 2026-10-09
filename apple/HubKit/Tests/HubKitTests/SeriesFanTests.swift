import Foundation
import Testing
@testable import HubKit

/// The Series view as fans (#54): which five of a series' books stand in the
/// fan, which is lit, how each leans and darkens, what the caption and the bar
/// under it say, and where a tap goes. The owner's rule, as the Pocket's
/// `SeriesFanTest` checks it.
struct SeriesFanTests {
    private func book(_ n: Int, _ state: String = "", owned: Bool = true, kind: String = "book") -> ReadingSeriesBook {
        ReadingSeriesBook(number: String(n), title: "Book \(n)", cover: "/c/\(n)", kind: kind, owned: owned, released: true, state: state)
    }

    /// `count` books, `on` the one you are on (1-based, 0 for none), the ones before it read.
    private func series(_ count: Int, on: Int = 0, finished: Bool = false, missing: Set<Int> = [], audio: Set<Int> = []) -> ReadingWork {
        ReadingWork(id: "s", entityType: "collection", title: "Series", seriesBooks: (1...count).map { n in
            let state = finished && !missing.contains(n) ? "read" : n == on ? "on" : on > 0 && n < on && !missing.contains(n) ? "read" : ""
            return book(n, state, owned: !missing.contains(n), kind: audio.contains(n) ? "audiobook" : "book")
        })
    }

    private func plan(_ series: ReadingWork, _ maxSlots: Int = SeriesFan.slots) throws -> SeriesFan.Plan {
        try #require(SeriesFan.plan(series, maxSlots: maxSlots))
    }

    private func numbers(_ plan: SeriesFan.Plan) -> String { plan.slots.map(\.book.number).joined(separator: " ") }
    private func lit(_ plan: SeriesFan.Plan) -> Int? { plan.slots.firstIndex(where: \.lit) }
    private func parts(_ plan: SeriesFan.Plan) -> [SeriesFan.Part] {
        if case .segments(let parts) = plan.bar { return parts }
        return []
    }

    @Test func theBookYouAreOnTakesTheMiddleSlotWhenItHasTwoBooksEachSide() throws {
        let plan = try plan(series(10, on: 5))
        #expect(numbers(plan) == "3 4 5 6 7")
        #expect(lit(plan) == 2)
        #expect(plan.slots.filter(\.lit).count == 1)
    }

    @Test func nearTheStartTheFanKeepsItsShapeAndYourBookIsLitInItsOwnSlot() throws {
        let first = try plan(series(10, on: 1))
        #expect(numbers(first) == "1 2 3 4 5" && lit(first) == 0)
        let second = try plan(series(10, on: 2))
        #expect(numbers(second) == "1 2 3 4 5" && lit(second) == 1)
        // Its angle is its slot's, the same as when nothing is lit.
        #expect(try plan(series(10)).slots[1].angle == second.slots[1].angle)
    }

    @Test func nearTheEndItShowsTheLastFive() throws {
        let last = try plan(series(10, on: 10))
        #expect(numbers(last) == "6 7 8 9 10" && lit(last) == 4)
        let before = try plan(series(10, on: 9))
        #expect(numbers(before) == "6 7 8 9 10" && lit(before) == 3)
        // Three from the end has two after it: the middle again.
        let third = try plan(series(10, on: 7))
        #expect(numbers(third) == "5 6 7 8 9" && lit(third) == 2)
    }

    @Test func aSeriesShorterThanFiveHasThatManySlotsTheAnglesStillSymmetrical() throws {
        for count in 1...4 {
            let plan = try plan(series(count, on: 1))
            #expect(plan.slots.count == count)
            #expect(abs(plan.slots.map(\.angle).reduce(0, +)) < 0.001)
            #expect(abs(plan.slots.map(\.offset).reduce(0, +)) < 0.001)
        }
        let three = try plan(series(3, on: 3))
        #expect(numbers(three) == "1 2 3" && lit(three) == 2)
    }

    @Test func fiveSlotsLeanSymmetricallyAboutTheMiddleAndSpreadByTheirPlace() throws {
        let slots = try plan(series(8, on: 4)).slots
        #expect(slots.map(\.angle) == [-16, -8, 0, 8, 16])
        #expect(slots.map(\.offset) == [-2, -1, 0, 1, 2])
    }

    @Test func theLitBookIsOnTopAndTheOthersAreLayeredAndDarkenedByDistance() throws {
        let slots = try plan(series(10, on: 5)).slots
        let lit = slots[2]
        #expect(lit.lit && lit.front && lit.shade == 0)
        #expect(lit.layer == slots.map(\.layer).max())
        // The nearer to it, the higher and the lighter, either side.
        #expect(slots[1].layer > slots[0].layer && slots[3].layer > slots[4].layer)
        #expect(slots[1].shade > 0 && slots[0].shade > slots[1].shade && slots[4].shade > slots[3].shade)
        #expect(slots[1].shade == slots[3].shade)
        #expect(slots.allSatisfy { $0.shade <= SeriesFan.maxShade })
        // One layer for each slot: none shares.
        #expect(Set(slots.map(\.layer)).count == 5)
        // Lit in a slot that is not the middle: distance is from that slot.
        let near = try plan(series(10, on: 2)).slots
        #expect(near[1].shade == 0 && near[4].shade > near[2].shade)
    }

    @Test func aSeriesNotStartedLightsNothingAndShowsBooksOneToFiveWithAnUnreadBar() throws {
        let plan = try plan(series(8))
        #expect(numbers(plan) == "1 2 3 4 5")
        #expect(!plan.slots.contains(where: \.lit) && !plan.started && !plan.finished)
        // The first book is in front, and the others are layered from it.
        #expect(plan.slots[0].front && plan.slots[0].layer == plan.slots.map(\.layer).max())
        #expect(plan.caption == "8 books")
        #expect(parts(plan) == Array(repeating: .toRead, count: 8))
    }

    @Test func aFinishedSeriesLightsNothingItsBarAllReadAndTheFrontBookTicked() throws {
        let plan = try plan(series(8, finished: true))
        #expect(plan.finished && !plan.slots.contains(where: \.lit))
        #expect(numbers(plan) == "1 2 3 4 5")
        #expect(parts(plan) == Array(repeating: .read, count: 8))
        #expect(plan.caption == "8 books · finished")
        #expect(plan.slots[0].front)
    }

    @Test func theCaptionSaysHowManyBooksAndWhichYouAreOn() throws {
        #expect(try plan(series(6, on: 1)).caption == "6 books · on #1")
        #expect(try plan(series(10, on: 7)).caption == "10 books · on #7")
        #expect(try plan(series(1)).caption == "1 book")
        #expect(try plan(series(1, on: 1)).caption == "1 book · on #1")
        // A novella you are on is named by its own number.
        let novella = ReadingWork(entityType: "collection", seriesBooks: [book(1, "read"),
                                                                          ReadingSeriesBook(number: "1.5", title: "Novella", owned: true, state: "on"),
                                                                          book(2)])
        #expect(try plan(novella).caption == "3 books · on #1.5")
        // A book with no place: no number to say.
        let unnumbered = ReadingWork(entityType: "collection", seriesBooks: [ReadingSeriesBook(title: "A", owned: true, state: "on"),
                                                                             ReadingSeriesBook(title: "B", owned: true)])
        #expect(try plan(unnumbered).caption == "2 books")
    }

    @Test func theBarHasAPartForEachBookAndOutlinesTheOnesYouDoNotHave() throws {
        let plan = try plan(series(6, on: 3, missing: [5, 6]))
        #expect(parts(plan) == [.read, .read, .on, .toRead, .missing, .missing])
        // Missing books count in the caption and in the bar.
        #expect(plan.caption == "6 books · on #3")
    }

    @Test func pastTwentyFiveBooksTheBarIsOneLineWithAMark() throws {
        guard case .continuous(let read, let mark) = try plan(series(26, on: 13)).bar else {
            Issue.record("26 books are not one line")
            return
        }
        #expect(abs(read - 12.0 / 26) < 0.001)
        #expect(abs((mark ?? 0) - 12.5 / 26) < 0.001)
        if case .continuous = try plan(series(25, on: 13)).bar { Issue.record("25 books are one line") }
        #expect(try plan(series(30)).bar == .continuous(read: 0, mark: nil))
        #expect(try plan(series(30, finished: true)).bar == .continuous(read: 1, mark: nil))
    }

    @Test func booksYouDoNotHaveAreInTheFanDimmed() throws {
        let plan = try plan(series(6, on: 1, missing: [2, 3, 4]))
        #expect(numbers(plan) == "1 2 3 4 5")
        #expect(plan.slots.map(\.dimmed) == [false, true, true, true, false])
    }

    @Test func everySlotIsTallAndAnAudiobookOnlyBookIsSquare() throws {
        #expect(try plan(series(6, on: 1, audio: [2])).slots.map(\.square) == [false, true, false, false, false])
    }

    @Test func aTapOpensTheSeriesAtTheLitBookOrTheRequestPageOfAMissingFrontBook() throws {
        #expect(try plan(series(10, on: 5)).target == .openSeries(number: "5"))
        // Nothing lit: the series, from its start.
        #expect(try plan(series(10)).target == .openSeries(number: nil))
        #expect(try plan(series(10, finished: true)).target == .openSeries(number: nil))
        // The front book is one you do not have: its request page.
        guard case .request(let missing) = try plan(series(10, missing: [1])).target else {
            Issue.record("a missing front book does not ask for itself")
            return
        }
        #expect(missing.title == "Book 1")
    }

    @Test func aSeriesThatCarriesNoBooksHasNoFan() {
        #expect(SeriesFan.plan(ReadingWork(entityType: "collection")) == nil)
        #expect(!SeriesFan.hasFan(ReadingWork(entityType: "collection")))
        #expect(SeriesFan.hasFan(series(2)))
        var work = series(2)
        work.entityType = "work"
        #expect(!SeriesFan.hasFan(work))
    }

    @Test func theFanOpensWithFocusAsFarAsItsCardHasRoomAndNoFurther() throws {
        let plan = try plan(series(8))
        #expect(SeriesFan.opening(focused: false, plan: plan, cover: 52, halfWidth: 1000) == 1)
        // With all the room in the world it opens as far as it ever does.
        #expect(abs(SeriesFan.opening(focused: true, plan: plan, cover: 52, halfWidth: 1000) - SeriesFan.maxOpening) < 0.001)
        let room = 195.0 / 2 + SeriesFan.spreadMargin
        let opened = SeriesFan.opening(focused: true, plan: plan, cover: 52, halfWidth: room)
        #expect((1.12...SeriesFan.maxOpening).contains(opened), "it opens, visibly: \(opened)")
        #expect(SeriesFan.reach(plan, cover: 52, opening: opened) <= room)
        #expect(SeriesFan.reach(plan, cover: 52, opening: opened + 0.01) > room)
        // A fan already wider than its room does not open at all.
        #expect(SeriesFan.opening(focused: true, plan: plan, cover: 52, halfWidth: 80) == 1)
    }

    @Test func howFarAFanReachesIsMeasuredAtTheCornersOfItsLeaningCovers() throws {
        // The outer slot of five 56pt covers: two steps out, and the top corner leaning 16 degrees about its foot.
        #expect(abs(SeriesFan.reach(try plan(series(8)), cover: 56, opening: 1) - 97.1) < 0.1)
        #expect(SeriesFan.reach(try plan(series(8)), cover: 56, opening: 1.1) > 97.1)
        // The lit book is bigger, and at an outer slot it reaches further.
        #expect(abs(SeriesFan.reach(try plan(series(8, on: 1)), cover: 56, opening: 1) - 102.1) < 0.1)
        // A square audiobook is not as tall, so its corner leans less far.
        #expect(abs(SeriesFan.reach(try plan(series(8, audio: [1, 5])), cover: 56, opening: 1) - 89.4) < 0.1)
        // A lit book in the middle does not reach past the outer ones.
        #expect(abs(SeriesFan.reach(try plan(series(10, on: 5)), cover: 56, opening: 1) - 97.1) < 0.1)
        #expect(SeriesFan.reach(try plan(series(2)), cover: 56, opening: 1) < 60)
    }

    @Test func theBoxHasRoomOverTheLitBookLeaningAtTheEndOfASeries() {
        #expect(abs(SeriesFan.leanRise(cover: 56) - 5) < 0.3)
        #expect(SeriesFan.leanRise(cover: 100) > SeriesFan.leanRise(cover: 56))
        #expect((2.0...5.0).contains(SeriesFan.leanRise(cover: 56, slots: 3)))
        #expect(SeriesFan.leanRise(cover: 56, slots: 1) == 0)
    }

    @Test func aFanOfOneBookStandsUprightInTheMiddle() throws {
        let plan = try plan(series(1))
        #expect(plan.slots.count == 1 && plan.slots[0].angle == 0 && plan.slots[0].offset == 0)
    }

    @Test func theSmallerFansTakeThreeSlotsTheBookYouAreOnInTheMiddle() throws {
        #expect(numbers(try plan(series(10, on: 5), SeriesFan.smallSlots)) == "4 5 6")
        #expect(lit(try plan(series(10, on: 5), 3)) == 1)
        #expect(try plan(series(10, on: 5), 3).slots.map(\.angle) == [-8, 0, 8])
        // Near the start or the end it keeps its shape: the first or last three, the book lit in its own slot.
        let first = try plan(series(10, on: 1), 3)
        let last = try plan(series(10, on: 10), 3)
        #expect(numbers(first) == "1 2 3" && lit(first) == 0)
        #expect(numbers(last) == "8 9 10" && lit(last) == 2)
        // Not started, or finished: books 1 to 3, the first in front on the left.
        let none = try plan(series(10), 3)
        #expect(numbers(none) == "1 2 3" && none.slots[0].front)
        #expect(numbers(try plan(series(10, finished: true), 3)) == "1 2 3")
        #expect(try plan(series(2, on: 2), 3).slots.count == 2)
        #expect(numbers(try plan(series(10, on: 4), 3)) == "3 4 5", "the same window as the five-slot fan's, narrowed")
    }

    @Test func theFirstBookIsInFrontOnTheLeftAndTheFanRunsRight() throws {
        let slots = try plan(series(8)).slots
        #expect(slots[0].front && slots[0].angle < 0 && slots[0].offset < 0)
        #expect(slots[slots.count - 1].angle > 0 && slots[slots.count - 1].offset > 0)
        #expect(slots.map(\.index) == slots.map(\.index).sorted())
        let three = try plan(series(8), 3).slots
        #expect(three[0].offset < 0 && three[0].front && three[2].offset > 0)
    }

    @Test func howManySlotsFitFollowsTheCoverSizeAndTheRoom() throws {
        #expect(SeriesFan.slotsFor(cover: 56, available: 130 + 2 * 4) == SeriesFan.smallSlots)
        #expect(SeriesFan.slotsFor(cover: 52, available: 195) == 5)
        #expect(SeriesFan.slotsFor(cover: 64, available: 130 + 2 * 4) == 1)
        // A fan is as wide as the lit book of its outer slot reaches, which is what the plan says.
        #expect(abs(SeriesFan.fanWidth(cover: 56, slots: 3) - 2 * SeriesFan.reach(try plan(series(10, on: 1), 3), cover: 56, opening: 1)) < 0.01)
        #expect(abs(SeriesFan.fanWidth(cover: 52, slots: 5) - 2 * SeriesFan.reach(try plan(series(10, on: 1)), cover: 52, opening: 1)) < 0.01)
    }

    @Test func theBooksOfASeriesPageAreItsSectionsTheOneYouAreOnLitAndThoseFinishedRead() throws {
        func item(_ n: Int, _ percentage: Double? = nil, done: Bool = false, available: Bool = true,
                  formats: [String] = ["ebook"]) -> ReadingSectionItem {
            ReadingSectionItem(workId: available ? "w\(n)" : "", title: "Book \(n)", number: "\(n)", artwork: "/a/\(n)",
                               progress: percentage.map { ReadingProgress(percentage: $0, completed: done) },
                               availability: available ? "available" : "missing", formats: formats)
        }
        let page = ReadingWork(entityType: "collection", title: "Series", artwork: "/a/series",
                               sections: [ReadingSection(items: [item(1, 1, done: true), item(2, 1, done: true), item(3, 0.4), item(4),
                                                                 item(5, available: false), item(6, formats: ["audiobook"])])],
                               continueAt: ReadingContinue(number: "3"))
        let books = SeriesFan.books(of: page)
        #expect(books.map(\.state) == ["read", "read", "on", "", "", ""])
        #expect(books.map(\.owned) == [true, true, true, true, false, true])
        #expect(books.map(\.kind) == ["book", "book", "book", "book", "book", "audiobook"])
        #expect(books[2].cover == "/a/3")
        let plan = try plan(page, 3)
        #expect(numbers(plan) == "2 3 4" && plan.caption == "6 books · on #3")
        // Sections win over the hub's own list; without sections the list is the books.
        var listed = page
        listed.seriesBooks = [book(1)]
        #expect(SeriesFan.books(of: listed).count == 6)
        #expect(SeriesFan.books(of: ReadingWork(entityType: "collection", seriesBooks: [book(1)])).count == 1)
        // A series with nothing but its own cover is that one cover, upright.
        let lone = try self.plan(ReadingWork(entityType: "collection", title: "Alone", artwork: "/a/alone"))
        #expect(lone.slots.count == 1 && lone.slots[0].book.cover == "/a/alone")
    }

    @Test func withNothingStartedOrAllFinishedAPagesSeriesHasNoLitBook() throws {
        func item(_ n: Int, _ done: Bool) -> ReadingSectionItem {
            ReadingSectionItem(workId: "w\(n)", number: "\(n)", progress: done ? ReadingProgress(percentage: 1, completed: true) : nil,
                               availability: "available")
        }
        let none = ReadingWork(entityType: "collection", sections: [ReadingSection(items: (1...4).map { item($0, false) })])
        let unread = try plan(none, 3)
        #expect(!unread.slots.contains(where: \.lit) && !unread.started)
        let all = ReadingWork(entityType: "collection", sections: [ReadingSection(items: (1...4).map { item($0, true) })])
        let read = try plan(all, 3)
        #expect(read.finished && !read.slots.contains(where: \.lit))
    }

    @Test func theRoomAFanNeedsFollowsItsCoversAndItsSlots() {
        #expect(abs(SeriesFan.width(cover: 76, slots: 5) - (76 + 4 * 76 * SeriesFan.stepFraction)) < 0.01)
        #expect(SeriesFan.width(cover: 76, slots: 1) == 76)
        #expect(SeriesFan.coverHeight(76, square: false) == 114 && SeriesFan.coverHeight(76, square: true) == 76)
    }
}

/// The demo hub's Series view lists each series with its books as the hub
/// does (#54), so the app's fans stand on what a real listing carries.
struct SeriesFanDemoTests {
    @Test func theDemoListingCarriesEachSeriesBooks() async throws {
        let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                            screens: DemoTransport(), sleep: { _ in })
        let listing = try await hub.fetch(HubEndpoints.readingLibraryItems(libraryId: "storyteller:books"),
                                          as: ReadingLibraryItemsResponse.self)
        let series = Dictionary(uniqueKeysWithValues: listing.items.filter(SeriesFan.hasFan).map { ($0.id, $0) })
        // Red Rising: on Light Bringer (#6), Dark Age (#5) not in the library; the last five, #6 lit at the right.
        let redRising = try #require(series["rw_demo_redrising"].flatMap { SeriesFan.plan($0) })
        #expect(redRising.slots.map(\.book.number) == ["2", "3", "4", "5", "6"])
        #expect(redRising.slots.firstIndex(where: \.lit) == 4 && redRising.slots[3].dimmed)
        #expect(redRising.caption == "6 books · on #6" && redRising.target == .openSeries(number: "6"))
        // Mistborn: on #1, and The Alloy of Law an audiobook only, square in its slot.
        let mistborn = try #require(series["rw_demo_mistborn"].flatMap { SeriesFan.plan($0) })
        #expect(mistborn.slots.map(\.square) == [false, false, false, true] && mistborn.slots[0].lit)
        // Licanius: not started, with the two books Hardcover knows and the library does not have.
        let licanius = try #require(series["rw_demo_licanius"].flatMap { SeriesFan.plan($0) })
        #expect(licanius.caption == "3 books" && licanius.slots.map(\.dimmed) == [false, true, true])
        #expect(licanius.bar == .segments([.toRead, .missing, .missing]))
        // A book on its own is a cover, not a fan.
        #expect(listing.items.contains { $0.id == "rw_demo_darkmatter" && !SeriesFan.hasFan($0) })
    }
}
