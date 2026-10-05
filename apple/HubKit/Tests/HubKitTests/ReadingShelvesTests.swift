import Foundation
import Testing
@testable import HubKit

/// Books Home's rows and the person's lists (#25): Android's `ReadingShelvesTest`.
struct ReadingShelvesTests {
    private func work(_ id: String, _ percent: Double = 0, _ updated: String = "") -> ReadingWork {
        ReadingWork(id: id, title: id, artwork: "/\(id)",
                    progress: ReadingProgress(percentage: percent, completed: percent >= 1, updatedAt: updated))
    }

    private func series(_ id: String, _ books: ReadingSectionItem...) -> ReadingWork {
        ReadingWork(id: id, entityType: "collection", title: id, sections: [ReadingSection(items: books)])
    }

    private func book(_ id: String, _ number: String, _ percent: Double = 0, done: Bool = false, updated: String = "",
                      availability: String = "available") -> ReadingSectionItem {
        ReadingSectionItem(workId: availability == "available" ? id : "", title: id, number: number,
                           progress: percent > 0 || done ? ReadingProgress(percentage: percent, completed: done, updatedAt: updated) : nil,
                           availability: availability)
    }

    @Test func startingOrFinishingRemovesTheWantEntryForGoodButKeepsNamedLists() {
        for progress in [0.001, 0.5, 1.0] {
            let entry = ReadingListEntry(workId: "book", title: "Book")
            let before = ReadingListsState(wantToRead: [entry], lists: [ReadingList(id: "list", name: "List", items: [entry])])
            let after = ReadingListsState.decode(before.recordProgress("book", percentage: progress).encoded())
            #expect(after.wantToRead.isEmpty)
            #expect(after.lists.first?.items.first?.lastProgress == progress)
            #expect(after.recordProgress("book", percentage: 0).wantToRead.isEmpty)
        }
    }

    @Test func anUnopenedBookStaysWantedAndUnknownProgressDoesNotRemoveIt() {
        let before = ReadingListsState(wantToRead: [ReadingListEntry(workId: "book", title: "Book")])
        for progress in [0, Double.nan, .infinity, -1] {
            #expect(before.recordProgress("book", percentage: progress).wantToRead.map(\.workId) == ["book"])
        }
    }

    @Test func wantedEntriesHideStartedBooksFromTheHubTheDeviceAndTheCurrentShelf() {
        let state = ReadingListsState(wantToRead: [
            ReadingListEntry(workId: "remote", title: "Remote"), ReadingListEntry(workId: "local", title: "Local", lastProgress: 0.2),
            ReadingListEntry(workId: "current", title: "Current"), ReadingListEntry(workId: "completed", title: "Completed"),
            ReadingListEntry(workId: "new", title: "New"),
        ])
        var completed = work("completed")
        completed.progress = ReadingProgress(percentage: 0, completed: true)
        let resolved = ["remote": work("remote", 0.1), "completed": completed]
        let row = ReadingShelves.rows(current: [work("current", 0.3)], state: state, resolved: resolved)
            .first { $0.id == ReadingListsState.wantToReadId }
        #expect(row?.items.map(\.id) == ["new"])
    }

    @Test func aStartedOrFinishedBookCannotBeWantedButCanGoOnANamedList() {
        for started in [work("book", 0.2), work("book", 1)] {
            let entry = ReadingListEntry.from(started)
            #expect(ReadingListsState().add(ReadingListsState.wantToReadId, entry).wantToRead.isEmpty)
            #expect(ReadingListsState().create("List", id: "list").add("list", entry).lists.first?.items.count == 1)
        }
    }

    @Test func theFirstReadRecordedPromotesItsListWithoutASecondUpdate() {
        let state = ReadingListsState(lists: [
            ReadingList(id: "first", name: "First", items: [ReadingListEntry(workId: "new", title: "New")], updatedAt: 1),
            ReadingList(id: "older", name: "Older",
                        items: [ReadingListEntry(workId: "old", title: "Old", lastProgress: 0.2, lastReadAt: 100)], updatedAt: 2),
        ])
        let updated = state.recordProgress("new", percentage: 0.1, at: 200)
        #expect(updated.lists.first?.items.first?.lastReadAt == 200)
        #expect(ReadingShelves.rows(current: [], state: updated, resolved: [:]).dropFirst().map(\.id) == ["first", "older"])
    }

    @Test func anUndatedFirstSightingDoesNotPretendTheBookWasReadJustNow() {
        let state = ReadingListsState(lists: [ReadingList(id: "old", name: "Old", items: [ReadingListEntry(workId: "book", title: "Book")],
                                                          updatedAt: 1)])
        #expect(state.recordProgress("book", percentage: 0.3, at: 0).lists.first?.items.first?.lastReadAt == 0)
    }

    @Test func currentReadsAreSingleBooksNewestFirst() {
        let collection = ReadingWork(id: "series", entityType: "collection", title: "Series", sections: [ReadingSection(items: [
            ReadingSectionItem(workId: "book1", title: "First", progress: ReadingProgress(percentage: 1, completed: true)),
            ReadingSectionItem(workId: "book2", title: "Second", progress: ReadingProgress(percentage: 0.4, updatedAt: "2026-09-22T12:00:00Z")),
        ])])
        let current = ReadingShelves.current([work("older", 0.3, "2026-09-21T12:00:00Z"), collection])
        #expect(current.map(\.id) == ["book2", "older"])
        #expect(!current.contains { $0.isSeries })
    }

    @Test func aListKeepsItsOrderAndOpensAtTheNextUnread() {
        let entries = (1...6).map { ReadingListEntry(workId: "book\($0)", title: "Book \($0)") }
        let list = ReadingList(id: "list", name: "Comics", items: entries, updatedAt: 7)
        let resolved = Dictionary(uniqueKeysWithValues: (1...6).map { ("book\($0)", work("book\($0)", $0 <= 2 ? 1 : 0)) })
        let row = ReadingShelves.listRow(list, resolved: resolved)
        #expect(row.items.map(\.id) == (1...6).map { "book\($0)" })
        #expect(row.nextIndex == 2 && row.readCount == 2)
        #expect(row.items.prefix(2).allSatisfy { $0.progress?.completed == true })
    }

    @Test func listsSortByReadingAndWantToReadHidesFinishedBooks() {
        let old = ReadingList(id: "old", name: "Old", items: [ReadingListEntry(workId: "a", title: "A")])
        let recent = ReadingList(id: "recent", name: "Recent", items: [ReadingListEntry(workId: "b", title: "B")])
        let state = ReadingListsState(wantToRead: [ReadingListEntry(workId: "a", title: "A"), ReadingListEntry(workId: "c", title: "C")],
                                      lists: [old, recent])
        let resolved = ["a": work("a", 1, "2026-09-20T12:00:00Z"), "b": work("b", 0.5, "2026-09-22T12:00:00Z"), "c": work("c")]
        let rows = ReadingShelves.rows(current: [], state: state, resolved: resolved)
        #expect(rows.map(\.id) == ["want-to-read", "recent", "old"])
        #expect(rows.first?.items.map(\.id) == ["c"])
    }

    @Test func editsAreKeptAndAWorkIsOnAListOnce() {
        let first = ReadingListsState().create("Sci-fi", id: "one", at: 10)
            .add("one", ReadingListEntry(workId: "red", title: "Red Rising"), at: 11)
            .add("one", ReadingListEntry(workId: "red", title: "Red Rising"), at: 12)
            .add("want-to-read", ReadingListEntry(workId: "mist", title: "Mistborn"), at: 13)
        let decoded = ReadingListsState.decode(first.encoded())
        #expect(decoded.lists.first?.items.map(\.workId) == ["red"])
        #expect(decoded.wantToRead.map(\.workId) == ["mist"])
        #expect(decoded.move("one", workId: "red", by: 1, at: 14).lists.first?.items.map(\.workId) == ["red"])
        #expect(decoded.remove("one", workId: "red", at: 15).lists.first?.items.isEmpty == true)
        #expect(decoded.rename("one", to: "  Space  ").lists.first?.name == "Space")
        #expect(decoded.delete("one").lists.isEmpty)
        #expect(ReadingListsState.decode(Data("not json".utf8)) == ReadingListsState())
    }

    @Test func undatedKavitaReadsKeepTheServersLastReadOrder() {
        #expect(ReadingShelves.current([work("most-recent", 0.3), work("older", 0.2)]).map(\.id) == ["most-recent", "older"])
    }

    @Test func readingOnThisDeviceMakesAListTheMostRecent() {
        let state = ReadingListsState(lists: [
            ReadingList(id: "older", name: "Older", items: [ReadingListEntry(workId: "a", title: "A", lastProgress: 0.2, lastReadAt: 100)],
                        updatedAt: 100),
            ReadingList(id: "newer", name: "Newer", items: [ReadingListEntry(workId: "b", title: "B", lastProgress: 0.2, lastReadAt: 200)],
                        updatedAt: 200),
        ])
        let updated = state.recordProgress("a", percentage: 0.3, at: 300)
        #expect(ReadingShelves.rows(current: [], state: updated, resolved: [:]).dropFirst().map(\.id) == ["older", "newer"])
        #expect(updated.lists.first?.items.first?.lastReadAt == 300)
        #expect(updated == updated.recordProgress("a", percentage: 0.3, at: 400))
    }

    @Test func aFinishedBookStaysFinishedWhenTheHubCannotBeReached() {
        let list = ReadingList(id: "comics", name: "Comics", items: [
            ReadingListEntry(workId: "read", title: "Read", lastProgress: 1), ReadingListEntry(workId: "next", title: "Next", lastProgress: 0.2),
        ])
        let row = ReadingShelves.listRow(list, resolved: [:])
        #expect(row.readCount == 1 && row.nextIndex == 1)
        #expect(row.items.first?.progress?.completed == true)
    }

    @Test func renamingAListDoesNotPutItAboveOneReadMoreRecently() {
        let recentlyRead = ReadingList(id: "recent", name: "Recent", items: [ReadingListEntry(workId: "a", title: "A", lastReadAt: 300)],
                                       updatedAt: 10)
        let renamed = ReadingList(id: "renamed", name: "Renamed", items: [ReadingListEntry(workId: "b", title: "B", lastReadAt: 100)],
                                  updatedAt: 500)
        let rows = ReadingShelves.rows(current: [], state: ReadingListsState(lists: [renamed, recentlyRead]), resolved: [:])
        #expect(rows.dropFirst().map(\.id) == ["recent", "renamed"])
    }

    @Test func currentlyReadingShowsOneCardPerSeriesAndKeepsComicsApart() {
        let redRising = series("Red Rising",
                               book("Red Rising", "1", 0.09, updated: "2026-09-21T16:00:00Z"),
                               book("Golden Son", "2", 0.02, updated: "2026-09-22T02:00:00Z"),
                               book("Light Bringer", "6", 0.49, updated: "2026-09-27T03:00:00Z"))
        var comic = work("Fantastic Four", 0.2, "2026-09-28T10:00:00Z")
        comic.kind = "comic"
        let rows = ReadingShelves.rows(current: [redRising, comic, work("Dark Matter", 0.03, "2026-09-25T03:00:00Z")],
                                       state: ReadingListsState(), resolved: [:])
        let reading = rows.first { $0.id == ReadingShelves.currentlyReading }
        #expect(reading?.items.map(\.id) == ["Light Bringer", "Dark Matter"])
        #expect(reading?.items.first?.cardSubtitle == "Red Rising #6")
        #expect(rows.first { $0.id == ReadingShelves.comics }?.items.map(\.id) == ["Fantastic Four"])
        #expect(rows.first { $0.id == ReadingShelves.comics }?.title == "Comics")
    }

    @Test func nextInSeriesIsTheFirstUnreadBookAfterTheLastFinished() {
        let finishedOne = series("Mistborn", book("Final Empire", "1", done: true, updated: "2026-09-20T10:00:00Z"),
                                 book("Well of Ascension", "2"), book("Hero of Ages", "3"))
        let stillReading = series("Red Rising", book("Red Rising", "1", done: true), book("Golden Son", "2", 0.4))
        let nothingFinished = series("Licanius", book("Shadow", "1"))
        let nextMissing = series("Stormlight", book("Way of Kings", "1", done: true), book("Words", "2", availability: "missing"),
                                 book("Oathbringer", "3"))
        let next = ReadingShelves.nextInSeries([finishedOne, stillReading, nothingFinished, nextMissing])
        #expect(next.map(\.id) == ["Well of Ascension", "Oathbringer"])
        #expect(next.first?.cardSubtitle == "Mistborn #2")
    }

    @Test func builtInRowsComeFirstAndRecentlyAddedLast() {
        let state = ReadingListsState(lists: [ReadingList(id: "mine", name: "Mine", items: [ReadingListEntry(workId: "x", title: "X")])])
        let rows = ReadingShelves.rows(current: [work("reading", 0.3)], state: state, resolved: [:], next: [work("next")],
                                       recent: [work("new")])
        #expect(rows.map(\.id) == [ReadingShelves.currentlyReading, ReadingShelves.nextInSeriesId, ReadingListsState.wantToReadId,
                                   "mine", ReadingShelves.recentlyAdded])
        #expect(rows.filter { !$0.isOwnList }.allSatisfy { $0.id != "mine" })
    }

    @Test func storytellersTimesWithASpaceAndNoZoneAreRead() {
        #expect(ReadingShelves.timestamp("2026-09-27 03:16:47") == 1_790_479_007_000)
        #expect(ReadingShelves.timestamp("2026-09-27T03:16:47Z") == 1_790_479_007_000)
        #expect(ReadingShelves.timestamp("2026-09-27T16:17:09.082Z") == 1_790_525_829_082)
        #expect(ReadingShelves.timestamp("1790479007") == 1_790_479_007_000)
        #expect(ReadingShelves.timestamp("1790479007000") == 1_790_479_007_000)
        #expect(ReadingShelves.timestamp("") == 0 && ReadingShelves.timestamp(nil) == 0 && ReadingShelves.timestamp("soon") == 0)
        let redRising = series("Red Rising", book("Red Rising", "1", 0.09, updated: "2026-09-21 16:09:43"),
                               book("Light Bringer", "6", 0.49, updated: "2026-09-27 03:16:47"))
        #expect(ReadingShelves.rows(current: [redRising], state: ReadingListsState(), resolved: [:]).first?.items.map(\.id)
                == ["Light Bringer"])
    }

    @Test func aNewEmptyListFollowsListsThatHaveBeenRead() {
        let active = ReadingList(id: "active", name: "Active", items: [ReadingListEntry(workId: "a", title: "A", lastReadAt: 100)],
                                 updatedAt: 10)
        let empty = ReadingList(id: "empty", name: "Empty", updatedAt: 500)
        #expect(ReadingShelves.rows(current: [], state: ReadingListsState(lists: [empty, active]), resolved: [:]).dropFirst().map(\.id)
                == ["active", "empty"])
    }

    @Test func yourSeriesFansTheBookYouAreOnInFrontAndSaysWhereYouAre() {
        func item(_ n: Int, _ pct: Double?, done: Bool = false, at: String = "") -> ReadingSectionItem {
            ReadingSectionItem(workId: "b\(n)", title: "Book \(n)", number: "\(n)", artwork: "/art/\(n)",
                               progress: pct.map { ReadingProgress(percentage: $0, completed: done, updatedAt: at) })
        }
        let redRising = ReadingWork(id: "rr", entityType: "collection", title: "Red Rising", bookCount: 6,
                                    sections: [ReadingSection(items: [item(1, 0.1, at: "2026-09-21 16:09:43"), item(2, 0.02), item(3, nil),
                                                                      item(4, nil), item(5, nil), item(6, 0.49, at: "2026-09-27 03:16:47")])],
                                    continueAt: ReadingContinue(number: "6", artwork: "/art/6"))
        let mistborn = ReadingWork(id: "mb", entityType: "collection", title: "Mistborn", bookCount: 3,
                                   sections: [ReadingSection(items: [item(1, 0.02, at: "2026-09-21 13:48:57"), item(2, nil), item(3, nil)])])
        let unread = ReadingWork(id: "lt", entityType: "collection", title: "Licanius", bookCount: 1,
                                 sections: [ReadingSection(items: [item(1, nil)])])
        let finished = ReadingWork(id: "f", entityType: "collection", title: "Done", sections: [ReadingSection(items: [item(1, 1, done: true)])])
        let shelf = ReadingShelves.yourSeries([mistborn, unread, redRising, finished])
        #expect(shelf.map(\.title) == ["Red Rising", "Mistborn"])
        #expect(shelf.first?.line == "6 books · on #6")
        // Up to four: the book you are on, then the first books in order.
        #expect(shelf.first?.covers == ["/art/6", "/art/1", "/art/2", "/art/3"])
        #expect(shelf.last?.line == "3 books · on #1")
    }

    @Test func theRealRedRisingIsReadAtItsSixthBook() throws {
        let redRising = try ReadingVectors.decode(ReadingWork.self, ReadingVectors.redRising)
        #expect(ReadingShelves.onNumber(redRising) == "6")
        #expect(ReadingShelves.current([redRising]).map(\.title) == ["Light Bringer", "Golden Son", "Red Rising"])
        #expect(ReadingShelves.fanCovers(redRising).first == "/v1/img/reading/storyteller/148412911207129")
        #expect(ReadingShelves.yourSeries([redRising]).first?.line == "6 books · on #6")
    }
}
