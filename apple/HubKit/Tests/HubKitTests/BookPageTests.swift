import Foundation
import Testing
@testable import HubKit

/// The book page (#39): the hub's new fields read, the PATCH written as the
/// contract says, and the page's words.
struct BookPageTests {
    private func work(_ json: String) throws -> ReadingWork {
        try JSONDecoder().decode(ReadingWork.self, from: Data(json.utf8))
    }

    @Test func communityYouAndGenresAreReadAsTheHubSendsThem() throws {
        let read = try work(#"""
            {"id":"rw_1","kind":"book","title":"The Final Empire","genres":["Fantasy","Epic fantasy","Magic systems"],
             "community":{"rating":4.45,"count":1204331,"source":"hardcover"},
             "you":{"rating":5,"finished":"2025-09","readCount":2,"shelves":["cosmere","favorites"],"status":"read","source":"goodreads"}}
            """#)
        #expect(read.community == ReadingCommunity(rating: 4.45, count: 1_204_331, source: "hardcover"))
        #expect(read.you == ReadingYou(rating: 5, finished: "2025-09", readCount: 2, shelves: ["cosmere", "favorites"],
                                       status: "read", source: "goodreads"))
        // An older hub, or nothing to say: neither is there.
        let bare = try work(#"{"id":"rw_2","title":"Recursion"}"#)
        #expect(bare.community == nil && bare.you == nil && bare.genres.isEmpty)
        // Goodreads' average alone has no count.
        let averaged = try work(#"{"id":"rw_3","community":{"rating":4.2,"source":"goodreads"},"you":{"shelves":[]}}"#)
        #expect(averaged.community?.count == nil)
        #expect(averaged.you?.rating == nil && averaged.you?.shelves == [])
    }

    @Test func aChangeSendsWhatChangesAndNullForWhatIsCleared() throws {
        func fields(_ change: ReadingYouChange) throws -> [String: Any] {
            try #require(JSONSerialization.jsonObject(with: change.body()) as? [String: Any])
        }
        let rate = try fields(ReadingYouChange(rating: .set(4)))
        #expect(rate.count == 1 && rate["rating"] as? Int == 4)
        let undo = try fields(ReadingYouChange(finished: .clear, readCount: .clear))
        #expect(undo.keys.sorted() == ["finished", "readCount"])
        #expect(undo["finished"] is NSNull && undo["readCount"] is NSNull)
        let finish = try fields(ReadingYouChange(finished: .set("2026-10")))
        #expect(finish["finished"] as? String == "2026-10" && finish["readCount"] == nil)
        #expect(ReadingYouChange().isEmpty)
        #expect(HubEndpoints.readingYou("rw_1", ReadingYouChange(rating: .set(3))).method == .patch)
        #expect(HubEndpoints.readingYou("rw_1", ReadingYouChange(rating: .set(3))).path == "/v1/reading/works/rw_1/you")
    }

    @Test func aChangeShowsAtOnceAsTheHubWillMakeIt() {
        // A first finish counts once and reads as read.
        let finished = ReadingYouChange(finished: .set("2026-10")).applied(to: nil)
        #expect(finished == ReadingYou(finished: "2026-10", readCount: 1, status: "read", source: "app"))
        // A read in progress stays in progress.
        let reading = ReadingYou(shelves: ["sci-fi"], status: "currently-reading", source: "goodreads")
        #expect(ReadingYouChange(finished: .set("2026-10")).applied(to: reading)?.status == "currently-reading")
        // Cleared to nothing is nothing to say.
        let rated = ReadingYou(rating: 4, source: "app")
        #expect(ReadingYouChange(rating: .clear).applied(to: rated) == nil)
        // Shelves stay: the app cannot write them.
        let shelved = ReadingYou(rating: 3, shelves: ["favorites"], source: "goodreads")
        #expect(ReadingYouChange(rating: .clear).applied(to: shelved)?.shelves == ["favorites"])
    }

    @Test func theFactsLineSaysYearPagesLengthAndReaders() {
        var book = ReadingWork(id: "rw_1", title: "The Final Empire", year: 2006,
                               editions: [ReadingEdition(kind: "ebook", pageCount: 541),
                                          ReadingEdition(kind: "audiobook", durationMs: 88_740_000)])
        #expect(BookPage.facts(book) == "2006 · 541 pages · 24h 39m")
        book.community = ReadingCommunity(rating: 4.45, count: 10, source: "hardcover")
        #expect(BookPage.facts(book) == "2006 · 541 pages · 24h 39m · 4.5 from readers")
        #expect(BookPage.community(ReadingCommunity(rating: 0, source: "hardcover")) == nil)
        book.genres = ["Fantasy", " Epic fantasy ", ""]
        #expect(BookPage.genres(book) == "Fantasy · Epic fantasy")
    }

    @Test func theFormatsAreAudiobookEbookAndReadAlongReadyOrNot() {
        let book = ReadingWork(id: "rw_1", kind: "book", title: "Dark Matter",
                               editions: [ReadingEdition(sourceItemId: "dm", kind: "ebook", availability: "available"),
                                          ReadingEdition(sourceItemId: "dm", kind: "readaloud", availability: "aligning")])
        let formats = BookPage.formats(book)
        #expect(formats.map(\.kind) == ["audiobook", "ebook", "readaloud"])
        #expect(formats.map(\.opens) == [false, true, false])
        #expect(BookPage.unavailable(formats[2]) == "Read along is still being aligned")
        #expect(BookPage.unavailable(formats[0]) == "This book has no audiobook")
        #expect(BookPage.formats(ReadingWork(id: "s", entityType: "collection")).isEmpty)
        #expect(BookPage.formats(ReadingWork(id: "c", kind: "comic")).isEmpty)
    }

    @Test func resumeSaysWhereYouAre() {
        var book = ReadingWork(id: "rw_1", progress: ReadingProgress(percentage: 0.32))
        #expect(BookPage.resume(book, chapter: "Chapter 14") == "Resume · Chapter 14 · 32%")
        #expect(BookPage.resume(book, chapter: " ") == "Resume · 32%")
        book.progress = ReadingProgress(percentage: 1, completed: true)
        #expect(BookPage.resume(book, chapter: "Epilogue") == nil)
        book.progress = nil
        #expect(BookPage.resume(book, chapter: nil) == nil)
    }

    @Test func underTheCoverYourFinishTimesAndShelves() {
        let you = ReadingYou(rating: 5, finished: "2025-09", readCount: 2, shelves: ["cosmere", "favorites"], source: "goodreads")
        #expect(BookPage.youLine(you) == "Finished Sep 2025 · 2nd time")
        #expect(BookPage.youLine(ReadingYou(finished: "2026-10", readCount: 1)) == "Finished Oct 2026 · rate it?")
        #expect(BookPage.youLine(ReadingYou(readCount: 3)) == "3rd time")
        #expect(BookPage.youLine(ReadingYou(shelves: ["x"])) == nil)
        #expect(BookPage.youLine(nil) == nil)
        #expect(BookPage.shelves(you) == "cosmere · favorites")
        #expect(BookPage.shelves(ReadingYou()) == nil)
        #expect([1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 101].map { BookPage.timesLabel($0) ?? "-" }
                == ["-", "2nd time", "3rd time", "4th time", "11th time", "12th time", "13th time", "21st time", "22nd time",
                    "23rd time", "101st time"])
        #expect(BookPage.finishedLabel("2025-13") == nil)
        #expect(BookPage.ratingLabel(4) == "Your rating, 4 of 5")
        #expect(BookPage.ratingLabel(nil) == "Not rated")
    }

    @Test func aStarRatesAndTheSameStarAgainTakesTheRatingAway() {
        #expect(BookPage.rating(tapping: 4, current: nil) == .set(4))
        #expect(BookPage.rating(tapping: 2, current: 4) == .set(2))
        #expect(BookPage.rating(tapping: 4, current: 4) == .clear)
    }

    @Test func finishingCountsAReadAndUndoingPutsItBack() {
        let now = BookPage.FinishDate(month: 10, year: 2026)
        let first = BookPage.finishing(nil, on: now)
        #expect(first == ReadingYouChange(finished: .set("2026-10")))
        let before = ReadingYou(finished: "2025-09", readCount: 2, source: "goodreads")
        #expect(BookPage.finishing(before, on: now) == ReadingYouChange(finished: .set("2026-10"), readCount: .set(3)))
        #expect(BookPage.undoing(before) == ReadingYouChange(finished: .set("2025-09"), readCount: .set(2)))
        #expect(BookPage.undoing(nil) == ReadingYouChange(finished: .clear, readCount: .clear))
    }

    @Test func thePanelOffersMonthsUpToThisOneAndYearsBackTo1900() throws {
        let now = BookPage.FinishDate(month: 10, year: 2026)
        let years = BookPage.FinishDate.years(now: now)
        #expect(years.first == 2026 && years.last == 1900)
        #expect(BookPage.FinishDate.months(in: 2026, now: now) == Array(1...10))
        #expect(BookPage.FinishDate.months(in: 2025, now: now) == Array(1...12))
        #expect(BookPage.FinishDate(month: 12, year: 2026).clamped(to: now) == now)
        #expect(BookPage.FinishDate(month: 3, year: 2026).clamped(to: now).value == "2026-03")
        #expect(try #require(BookPage.FinishDate("1999-07")).value == "1999-07")
        #expect(BookPage.FinishDate("99-07") == nil)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let date = Date(timeIntervalSince1970: 1_791_417_600) // 2026-10-08
        #expect(BookPage.FinishDate.current(date, calendar: calendar) == now)
        #expect(BookPage.months.count == 12)
    }

    @Test func theDemoHubAnswersAsTheContractSays() async throws {
        let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                            screens: DemoTransport(), sleep: { _ in })
        let alloy = try await hub.fetch(HubEndpoints.readingWork("rw_demo_alloy"), as: ReadingWork.self)
        #expect(alloy.community?.source == "goodreads" && alloy.community?.count == nil)
        #expect(alloy.you?.shelves == ["cosmere", "favorites"] && alloy.you?.readCount == 2)
        #expect(alloy.genres.count == 3)
        // Light Bringer has nothing to say of you until it is rated; put back after.
        let before = try await hub.fetch(HubEndpoints.readingWork("rw_demo_rr6"), as: ReadingWork.self)
        #expect(before.you == nil && before.community?.count == 98_211)
        let rated = try await hub.fetch(HubEndpoints.readingYou("rw_demo_rr6", ReadingYouChange(rating: .set(4))),
                                        as: ReadingYouResponse.self)
        #expect(rated.you?.rating == 4 && rated.you?.source == "app")
        let finished = try await hub.fetch(HubEndpoints.readingYou("rw_demo_rr6", ReadingYouChange(finished: .set("2026-01"))),
                                           as: ReadingYouResponse.self)
        #expect(finished.you?.readCount == 1 && finished.you?.status == "read")
        let page = try await hub.fetch(HubEndpoints.readingWork("rw_demo_rr6"), as: ReadingWork.self)
        #expect(page.you?.finished == "2026-01")
        let cleared = try await hub.fetch(HubEndpoints.readingYou("rw_demo_rr6", ReadingYouChange(rating: .clear, finished: .clear,
                                                                                                  readCount: .clear)),
                                          as: ReadingYouResponse.self)
        #expect(cleared.you == nil)
        // Refused: a month to come, a number as words, a key it does not take, a book it does not know.
        let refusals = [HubRequest("/v1/reading/works/rw_demo_rr6/you", method: .patch, body: Data(#"{"finished":"2999-01"}"#.utf8)),
                        HubRequest("/v1/reading/works/rw_demo_rr6/you", method: .patch, body: Data(#"{"rating":"4"}"#.utf8)),
                        HubRequest("/v1/reading/works/rw_demo_rr6/you", method: .patch, body: Data(#"{"shelves":["x"]}"#.utf8)),
                        HubRequest("/v1/reading/works/rw_demo_rr6/you", method: .patch, body: Data("{}".utf8))]
        for request in refusals {
            await #expect(throws: HubFailure.self) { try await hub.send(request) }
        }
        await #expect(throws: HubFailure.self) {
            try await hub.send(HubEndpoints.readingYou("rw_nope", ReadingYouChange(rating: .set(2))))
        }
    }
}
