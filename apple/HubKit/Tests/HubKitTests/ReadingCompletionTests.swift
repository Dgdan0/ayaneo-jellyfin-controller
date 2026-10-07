import Foundation
import Testing
@testable import HubKit

/// A book marked read or unread by hand (#37): Android's
/// `ReadingCompletionStateTest`, case for case.
struct ReadingCompletionTests {
    private let book = ReadingWork(id: "red-rising", title: "Red Rising", progress: ReadingProgress(percentage: 0.5, completed: false),
                                   continueAt: ReadingContinue(workId: "red-rising", sourceItemId: "12", percentage: 0.5))

    @Test func readRemovesTheContinuationAndCompletesTheBook() {
        let projected = ReadingCompletionState().markRead(book.id).project(book)
        #expect(projected.progress?.completed == true)
        #expect(projected.progress?.percentage == 1)
        #expect(projected.continueAt == nil)
        #expect(ReadingShelves.current([projected]).isEmpty, "a finished book is not being read")
    }

    @Test func unmarkingBeforeLeavingThePageRestoresTheExactProgress() {
        var session = ReadingCompletionSession()
        let read = session.markRead(ReadingCompletionState(), book.id)
        #expect(session.unmark(read, book.id).project(book) == book)
    }

    @Test func undoRestoresAnEarlierMarkAndItsTimeWithoutChangingOtherBooks() {
        let original = ReadingCompletionState().reset(book.id, at: 123).markRead("other", at: 22)
        var session = ReadingCompletionSession()
        #expect(session.unmark(session.markRead(original, book.id), book.id) == original)
    }

    @Test func unmarkingAfterLeavingThePageStartsTheBookAgain() {
        var session = ReadingCompletionSession()
        let read = session.markRead(ReadingCompletionState(), book.id)
        session.leave()
        let reset = session.unmark(read, book.id)
        #expect(reset.project(book).progress?.percentage == 0)
        #expect(reset.project(book).continueAt == nil)
        #expect(reset.startsAtBeginning(book.id))
        #expect(reset.ebookResume(book.id, ReadingResume(ReadingLocation(pageIndex: 8))).location == nil)
        #expect(reset.pageResume(book.id, 8) == 0)
        #expect(ReadingCompletionState.decode(reset.encoded()) == reset)
        #expect(reset.notice(book.id) == "Marked unread · next read starts at the beginning")
    }

    @Test func aSeriesMarksOnlyTheBookChosenNeverItsOthers() {
        let series = ReadingWork(id: "series", entityType: "collection", sections: [
            ReadingSection(items: [ReadingSectionItem(sourceItemId: "12", workId: book.id, title: book.title, progress: book.progress),
                                   ReadingSectionItem(sourceItemId: "13", workId: "next", title: "Next")])])
        let projected = ReadingCompletionState().markRead(book.id).project(series)
        #expect(projected.sections[0].items[0].progress?.completed == true)
        #expect(projected.sections[0].items[1].progress == nil)
    }

    @Test func aReaderSavingAPlaceForgetsTheMarkAndWhatItSays() {
        let read = ReadingCompletionState().markRead(book.id)
        #expect(read.notice(book.id) == "Marked as read")
        #expect(read.ebookResume(book.id, ReadingResume(ReadingLocation(pageIndex: 3))).location?.pageIndex == 3,
                "a book marked read opens where it was")
        let cleared = read.clear(book.id)
        #expect(cleared.project(book) == book)
        #expect(cleared.notice(book.id) == "Previous reading position restored")
        #expect(ReadingCompletionState.decode(Data("not json".utf8)) == ReadingCompletionState())
    }
}
