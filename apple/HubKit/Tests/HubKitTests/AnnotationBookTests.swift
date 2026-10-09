import Foundation
import Testing
@testable import HubKit

/// What a device keeps of a book's highlights (#62): the Pocket's
/// `AnnotationBookTest`, case for case.
struct AnnotationBookTests {
    private func note(_ id: String, _ color: String = "yellow", text: String = "", at: Int64 = 100, quote: String = "bird",
                      synced: Int64 = 0) -> ReadingAnnotation {
        ReadingAnnotation(id: id, color: color, note: text, document: "OEBPS/chapter-1.xhtml",
                          quote: AnnotationQuote(before: "a ", highlight: quote, after: "."), createdAt: at, updatedAt: at,
                          syncedAt: synced)
    }

    @Test func aHighlightMadeHereIsShownAtOnceAndWaitsToBeSent() {
        var book = AnnotationBook()
        let kept = book.put(note("an_1", at: 0), now: 5_000)
        #expect(kept.updatedAt == 5_000 && kept.createdAt == 5_000)
        #expect(book.live.map(\.id) == ["an_1"])
        #expect(book.pending().map(\.id) == ["an_1"])
    }

    @Test func anEditIsStampedAfterWhatItEditsEvenWhenTheClockHasNotMovedOn() {
        var book = AnnotationBook([note("an_1", at: 9_000)])
        let edited = book.put(note("an_1", "blue", at: 0), now: 4_000)
        #expect(edited.updatedAt == 9_001 && edited.createdAt == 9_000)
    }

    @Test func aRemovedHighlightIsATombstoneWithNoNoteAndIsNotShown() throws {
        var book = AnnotationBook([note("an_1", text: "a thought", at: 100)])
        let tomb = try #require(book.remove("an_1", now: 500))
        #expect(tomb.deleted && tomb.note == "" && tomb.quote.highlight == "bird")
        #expect(book.live.isEmpty)
        #expect(book.pendingIds == ["an_1"])
        #expect(book.remove("an_unknown", now: 600) == nil)
    }

    @Test func theHubsNewerVersionReplacesOursAndAnOlderOneDoesNot() {
        var book = AnnotationBook([note("an_1", "yellow", at: 100, synced: 1)])
        #expect(book.merge([note("an_1", "pink", at: 200, synced: 2)]))
        #expect(book["an_1"]?.color == "pink")
        #expect(!book.merge([note("an_1", "green", at: 150, synced: 3)]))
        #expect(book["an_1"]?.color == "pink")
        #expect(book.cursor == 3)
    }

    @Test func anEditWaitingToBeSentBeatsAnOlderVersionFromTheHubAndLosesToANewerOne() {
        var book = AnnotationBook([note("an_1", "yellow", at: 100)])
        book.put(note("an_1", "blue"), now: 300)
        book.merge([note("an_1", "pink", at: 200, synced: 5)])
        #expect(book["an_1"]?.color == "blue")
        #expect(book.pendingIds == ["an_1"])
        // Another device edited it later than we did: ours would lose at the hub, so it is dropped.
        book.merge([note("an_1", "green", at: 400, synced: 6)])
        #expect(book["an_1"]?.color == "green")
        #expect(book.pendingIds.isEmpty)
    }

    @Test func theHubsVersionWinsATieSoTwoDevicesAgree() {
        var book = AnnotationBook([note("an_1", "yellow", at: 100)])
        book.put(note("an_1", "blue"), now: 200)
        book.merge([note("an_1", "pink", at: 200, synced: 4)])
        #expect(book["an_1"]?.color == "pink")
        #expect(book.pendingIds.isEmpty)
    }

    @Test func aTombstoneFromTheHubRemovesWhatWeShowAndAnUndoNewerThanItBringsItBack() {
        var book = AnnotationBook([note("an_1", at: 100)])
        var tomb = note("an_1", at: 300, synced: 7)
        tomb.deleted = true
        #expect(book.merge([tomb]))
        #expect(book.live.isEmpty)
        // Not redrawn for a tombstone it already has.
        #expect(!book.merge([tomb]))
        let back = book.put(note("an_1", "pink"), now: 250)
        #expect(!back.deleted && back.updatedAt == 301)
        #expect(book.live.map(\.id) == ["an_1"])
    }

    @Test func whatIsOnlyANewStampOfTheSameThingDoesNotCallForARedraw() {
        var book = AnnotationBook([note("an_1", at: 100, synced: 1)])
        #expect(!book.merge([note("an_1", at: 100, synced: 9)]))
        #expect(book.cursor == 9)
    }

    @Test func whenTheHubTookAnEditItIsNoLongerWaitingUnlessItWasEditedAgainMeanwhile() {
        var book = AnnotationBook()
        let first = book.put(note("an_1", "yellow"), now: 100)
        var stored = first
        stored.syncedAt = 50
        // Edited again while the first was on its way.
        let second = book.put(note("an_1", "blue"), now: 200)
        book.sent("an_1", sent: first, held: stored)
        #expect(book.pendingIds == ["an_1"])
        #expect(book["an_1"]?.color == "blue")
        var secondStored = second
        secondStored.syncedAt = 60
        book.sent("an_1", sent: second, held: secondStored)
        #expect(book.pendingIds.isEmpty)
        #expect(book.cursor == 60)
    }

    @Test func whenTheHubKeptSomethingNewerThatIsWhatWeKeep() {
        var book = AnnotationBook()
        let sent = book.put(note("an_1", "yellow"), now: 100)
        book.sent("an_1", sent: sent, held: note("an_1", "green", at: 900, synced: 70))
        #expect(book["an_1"]?.color == "green")
        #expect(book.pendingIds.isEmpty)
    }

    @Test func anEditTheHubRefusesForeverStopsWaitingButStaysHere() {
        var book = AnnotationBook()
        book.put(note("an_1"), now: 100)
        book.refused("an_1")
        #expect(book.pendingIds.isEmpty)
        #expect(book.live.count == 1)
    }

    @Test func theOutboxIsSentOldestFirstSoADeleteAndItsUndoKeepTheirOrder() {
        var book = AnnotationBook()
        book.put(note("an_b"), now: 300)
        book.put(note("an_a"), now: 100)
        book.remove("an_a", now: 400)
        #expect(book.pending().map(\.id) == ["an_b", "an_a"])
    }

    @Test func aSnapshotComesBackAsTheSameBook() throws {
        var book = AnnotationBook([note("an_1", at: 100, synced: 5)], cursor: 5)
        book.put(note("an_2"), now: 200)
        let again = try JSONDecoder().decode(AnnotationBook.Snapshot.self, from: JSONEncoder().encode(book.snapshot())).book()
        #expect(again.all == book.all)
        #expect(again.pendingIds == book.pendingIds)
        #expect(again.cursor == 5)
    }

    @Test func aWaitingIdWithNoHighlightBehindItIsNotKept() {
        let book = AnnotationBook([note("an_1")], pending: ["an_1", "an_gone"])
        #expect(book.pendingIds == ["an_1"])
    }

    @Test func theLiveOnesAreInTheOrderTheyWereMade() {
        let book = AnnotationBook([note("an_b", at: 300), note("an_a", at: 100), note("an_c", at: 300)])
        #expect(book.live.map(\.id) == ["an_a", "an_b", "an_c"])
    }
}
