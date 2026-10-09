import Foundation
import Testing
@testable import HubKit

/// A book's reading status (#63): the Pocket's `ReadingStatusTest`, case for
/// case — the four words, how they are worked out, what each does and what it
/// writes — then the shelves and the projection that read through it.
struct ReadingStatusTests {
    private func progress(_ percent: Double, completed: Bool = false) -> ReadingProgress {
        ReadingProgress(percentage: percent, completed: completed)
    }

    // MARK: The words

    @Test func thereAreFourStatusesInThePickersOrder() {
        #expect(ReadingStatus.choices == ["want", "reading", "finished", "not-reading"])
        #expect(ReadingStatus.choices.map(ReadingStatus.label) == ["Want to read", "Reading", "Finished", "Not reading"])
        #expect(ReadingStatus.label("") == "" && ReadingStatus.label("abandoned") == "")
    }

    @Test func eachStatusSaysWhatItDoesInThePicker() {
        #expect(ReadingStatus.detail("want") == "Keep it on your Want to read list")
        #expect(ReadingStatus.detail("reading") == "Show it in Continue reading")
        #expect(ReadingStatus.detail("finished") == "Say when; puts the ✓ on its cover")
        #expect(ReadingStatus.detail("not-reading") == "Take it off Continue reading and Home; your place stays")
    }

    // MARK: The one status of a book

    @Test func theHubsWordIsTheStatusWhateverThePageCouldWorkOut() {
        #expect(ReadingStatus.of(ReadingWork(progress: progress(0.4), status: "not-reading")) == "not-reading")
        #expect(ReadingStatus.of(ReadingWork(progress: progress(1, completed: true), status: "reading")) == "reading")
        #expect(ReadingStatus.of(ReadingWork(status: "finished")) == "finished")
        #expect(ReadingStatus.of(ReadingSectionItem(status: "finished")) == "finished")
    }

    @Test func withoutTheHubsWordTheStatusIsWorkedOutTheWayTheHubDoesIt() {
        func status(_ you: ReadingYou? = nil, _ progress: ReadingProgress? = nil) -> String {
            ReadingStatus.of(ReadingWork(progress: progress, you: you))
        }
        #expect(status() == "")
        #expect(status(ReadingYou(rating: 4)) == "")
        #expect(status(nil, progress(0.2)) == "reading")
        #expect(status(nil, progress(1, completed: true)) == "finished")
        #expect(status(ReadingYou(finished: "2026-09")) == "finished")
        #expect(status(ReadingYou(status: "read")) == "finished")
        #expect(status(ReadingYou(status: "to-read")) == "want")
        #expect(status(ReadingYou(status: "currently-reading")) == "reading")
        // Finished outranks a place begun (a second time through); to-read does not outrank a place at the end.
        #expect(status(ReadingYou(finished: "2026-09"), progress(0.03)) == "finished")
        #expect(status(ReadingYou(status: "to-read"), progress(1, completed: true)) == "finished")
        #expect(status(ReadingYou(status: "to-read"), progress(0.04)) == "want")
        // What the person chose outranks all of it.
        #expect(status(ReadingYou(chosen: "not-reading"), progress(0.4)) == "not-reading")
        #expect(status(ReadingYou(finished: "2026-09", chosen: "reading")) == "reading")
        #expect(status(ReadingYou(status: "read", chosen: "want")) == "want")
        // Wanted and then opened is being read, as the Want to Read list always had it.
        #expect(status(ReadingYou(chosen: "want"), progress(0.2)) == "reading")
        #expect(status(ReadingYou(chosen: "want"), progress(1, completed: true)) == "want")
        #expect(status(ReadingYou(chosen: "not-reading"), progress(0.2)) == "not-reading")
        // A word nobody can choose is not one.
        #expect(status(ReadingYou(status: "to-read", chosen: "abandoned")) == "want")
    }

    @Test func theTickIsForEveryFinishedBookHoweverItCameToBeFinished() {
        #expect(ReadingStatus.isFinished(ReadingWork(status: "finished")))
        #expect(ReadingStatus.isFinished(ReadingWork(you: ReadingYou(finished: "2024-01", status: "read"))), "an import that says read")
        #expect(ReadingStatus.isFinished(ReadingWork(progress: progress(1, completed: true))), "read to the end, from a hub that sends no status")
        #expect(!ReadingStatus.isFinished(ReadingWork()))
        #expect(!ReadingStatus.isFinished(ReadingWork(progress: progress(1, completed: true), status: "reading")))
        #expect(!ReadingStatus.isFinished(ReadingWork(progress: progress(1, completed: true), status: "not-reading")),
                "put down is not finished, whatever the place says")
        #expect(ReadingStatus.isFinished(ReadingSectionItem(status: "finished")))
        #expect(ReadingStatus.isFinished(ReadingSectionItem(progress: progress(1, completed: true))))
        #expect(!ReadingStatus.isFinished(ReadingSectionItem(progress: progress(0.5))))
    }

    @Test func aBookIsInContinueReadingWhenItHasAPlaceAndWasNotPutDownOrFinished() {
        func continues(_ status: String, _ p: ReadingProgress?) -> Bool {
            ReadingStatus.continues(ReadingWork(progress: p, status: status))
        }
        #expect(continues("", progress(0.4)))
        #expect(continues("reading", progress(0.4)))
        #expect(continues("reading", progress(1, completed: true)), "reading it again, from the end")
        #expect(!continues("not-reading", progress(0.4)), "put down, place kept")
        #expect(!continues("finished", progress(0.4)), "marked finished by hand with a place short of the end")
        #expect(!continues("", progress(1, completed: true)), "read to the end")
        #expect(!continues("want", progress(1, completed: true)), "wanted again after finishing")
        #expect(!continues("reading", progress(0)), "no place to continue from")
        #expect(!continues("reading", nil), "no place at all")
    }

    // MARK: The menu row

    @Test func theMenuRowNamesTheStatusItHolds() {
        #expect(ReadingStatus.rowLabel("") == "Reading status")
        #expect(ReadingStatus.rowLabel("want") == "Reading status · Want to read")
        #expect(ReadingStatus.rowLabel("reading") == "Reading status · Reading")
        #expect(ReadingStatus.rowLabel("finished") == "Reading status · Finished")
        #expect(ReadingStatus.rowLabel("not-reading") == "Reading status · Not reading")
    }

    @Test func underTheRowWhatThatStatusMeansForThisBook() {
        #expect(ReadingStatus.rowDetail("", nil) == "Want to read, reading, finished or not reading")
        #expect(ReadingStatus.rowDetail("want", nil) == "On your Want to read list")
        #expect(ReadingStatus.rowDetail("reading", nil) == "In Continue reading")
        #expect(ReadingStatus.rowDetail("not-reading", nil) == "Off Continue reading and Home; your place stays")
        #expect(ReadingStatus.rowDetail("finished", ReadingYou(finished: "2025-09")) == "Finished Sep 2025 · change the date")
        #expect(ReadingStatus.rowDetail("finished", nil) == "Finished · say when")
        #expect(ReadingStatus.rowDetail("finished", ReadingYou(status: "read")) == "Finished · say when")
    }

    // MARK: Choosing

    @Test func choosingTheStatusABookHasChangesNothingExceptFinishedWhichCanChangeTheDate() {
        #expect(ReadingStatus.action("reading", current: "reading") == .nothing)
        #expect(ReadingStatus.action("want", current: "want") == .nothing)
        #expect(ReadingStatus.action("reading", current: "not-reading") == .set)
        #expect(ReadingStatus.action("not-reading", current: "") == .set)
        // Finished asks the month, finished already or not: that is how a date is put right.
        #expect(ReadingStatus.action("finished", current: "reading") == .askMonth)
        #expect(ReadingStatus.action("finished", current: "finished") == .askMonth)
    }

    @Test func wantToReadIsTheListSoChoosingItAddsTheBookAndAnythingElseTakesItOff() {
        #expect(ReadingStatus.effects("want", wasFinished: false).wantList)
        for other in ["reading", "finished", "not-reading"] {
            #expect(!ReadingStatus.effects(other, wasFinished: false).wantList, "\(other)")
        }
    }

    @Test func aBookIsMarkedReadHereWhenItBecomesFinishedAndUnmarkedWhenItStopsBeingFinished() {
        #expect(ReadingStatus.effects("finished", wasFinished: false).localRead == .mark)
        #expect(ReadingStatus.effects("finished", wasFinished: true).localRead == .keep)
        #expect(ReadingStatus.effects("reading", wasFinished: true).localRead == .unmark)
        #expect(ReadingStatus.effects("not-reading", wasFinished: true).localRead == .unmark)
        #expect(ReadingStatus.effects("want", wasFinished: true).localRead == .unmark)
        #expect(ReadingStatus.effects("reading", wasFinished: false).localRead == .keep)
    }

    @Test func thePageShowsTheStatusAtOnceAsTheHubWillSettleIt() {
        #expect(ReadingStatus.settle("reading", progress(0.5)) == "reading")
        #expect(ReadingStatus.settle("not-reading", progress(0.5)) == "not-reading")
        #expect(ReadingStatus.settle("want", nil) == "want")
        #expect(ReadingStatus.settle("want", progress(0)) == "want")
        // Wanted with a place begun, short of the end, is being read.
        #expect(ReadingStatus.settle("want", progress(0.2)) == "reading")
        #expect(ReadingStatus.settle("want", progress(1, completed: true)) == "want")
    }

    // MARK: What is written

    private func body(_ change: ReadingYouChange) -> String { String(decoding: change.body(), as: UTF8.self) }

    @Test func aStatusAloneIsOneKey() {
        #expect(body(BookPage.choosing("want")) == #"{"status":"want"}"#)
        #expect(body(BookPage.choosing("not-reading")) == #"{"status":"not-reading"}"#)
        #expect(!BookPage.choosing("reading").isEmpty)
    }

    @Test func aFinishSaysTheStatusTooSoAClientThatReadsStatusesGetsTheWordAndOneThatDoesNotGetsTheMonth() throws {
        let october = try #require(BookPage.FinishDate("2026-10"))
        #expect(body(BookPage.finishing(nil, on: october)) == #"{"finished":"2026-10","status":"finished"}"#)
        #expect(body(BookPage.finishing(ReadingYou(finished: "2025-09", readCount: 1), on: october))
                == #"{"finished":"2026-10","readCount":2,"status":"finished"}"#)
    }

    @Test func leavingAFinishMarkedInThisVisitPutsThePageBackAndSaysTheNewStatus() {
        let before = ReadingYou(finished: "2025-09", readCount: 1)
        #expect(body(BookPage.choosing("reading", undo: BookPage.undoing(before)))
                == #"{"finished":"2025-09","readCount":1,"status":"reading"}"#)
        // Where there was nothing to put back, the status alone.
        #expect(body(BookPage.choosing("not-reading")) == #"{"status":"not-reading"}"#)
        // Where the page had no finish at all, taking the finish away clears the month and the count.
        #expect(body(BookPage.choosing("want", undo: BookPage.undoing(nil))) == #"{"finished":null,"readCount":null,"status":"want"}"#)
    }

    @Test func theStatusKeyIsSentClearedOrLeftOutLikeTheOthers() {
        #expect(body(ReadingYouChange(status: .clear)) == #"{"status":null}"#)
        #expect(body(ReadingYouChange()) == "{}")
        #expect(ReadingYouChange().isEmpty && !ReadingYouChange(status: .clear).isEmpty)
        // Shown at once as the hub will make it: the choice is the person's.
        #expect(ReadingYouChange(status: .set("not-reading")).applied(to: nil)?.chosen == "not-reading")
        #expect(ReadingYouChange(status: .clear).applied(to: ReadingYou(chosen: "want")) == nil)
    }

    // MARK: Under the cover

    @Test func theFinishUnderTheCoverIsForABookThatIsFinishedTheCountOfReadingsForABookReadBefore() throws {
        let read = ReadingYou(rating: 4, finished: "2025-09", readCount: 2, shelves: ["cosmere"])
        #expect(ReadingStatus.youForLine(ReadingWork(status: "finished", you: read)) == read)
        // Read again: the month it was finished is history, "2nd time" is not.
        let again = try #require(ReadingStatus.youForLine(ReadingWork(status: "reading", you: read)))
        #expect(again.finished == nil && again.readCount == 2 && again.rating == 4)
        #expect(BookPage.youLine(again) == "2nd time")
        #expect(BookPage.youLine(read) == "Finished Sep 2025 · 2nd time")
        // An import that says read no longer says it for a book put down.
        #expect(ReadingStatus.youForLine(ReadingWork(status: "not-reading", you: ReadingYou(status: "read")))?.status == nil)
        #expect(ReadingStatus.youForLine(ReadingWork()) == nil)
    }

    // MARK: From the hub

    @Test func aWorkABookOfASeriesAndAnAnswerCarryTheStatus() throws {
        let work = try JSONDecoder().decode(ReadingWork.self, from: Data("""
            {"id":"rw_1","status":"not-reading","you":{"chosen":"not-reading","shelves":[],"source":"app"},
             "sections":[{"id":"s","items":[{"workId":"rw_2","status":"finished"},{"workId":"rw_3"}]}]}
            """.utf8))
        #expect(work.status == "not-reading" && work.you?.chosen == "not-reading")
        #expect(work.sections.first?.items.map(\.status) == ["finished", ""])
        let answer = try JSONDecoder().decode(ReadingYouResponse.self, from: Data("""
            {"workId":"rw_1","you":{"chosen":"want","shelves":[],"source":"app"},"status":"want"}
            """.utf8))
        #expect(answer.status == "want" && answer.you?.chosen == "want")
        // A hub from before this knows no status at all.
        let old = try JSONDecoder().decode(ReadingWork.self, from: Data(#"{"id":"rw_1","sections":[{"id":"s","items":[{"workId":"rw_2"}]}]}"#.utf8))
        #expect(old.status == "" && old.sections.first?.items.first?.status == "")
    }

    // MARK: The shelves and this device's marks read through it

    @Test func continueReadingLeavesOutABookPutDownAndKeepsOneChosenAsReading() {
        let works = [
            ReadingWork(id: "a", progress: progress(0.4)),
            ReadingWork(id: "b", progress: progress(0.4), status: "not-reading"),
            ReadingWork(id: "c", progress: progress(1, completed: true), status: "reading"),
            ReadingWork(id: "d", progress: progress(0.4), status: "finished"),
        ]
        #expect(ReadingShelves.current(works).map(\.id).sorted() == ["a", "c"])
    }

    @Test func wantToReadLeavesOutABookTheHubSaysIsReadingFinishedOrPutDown() {
        var state = ReadingListsState()
        for id in ["w", "r", "f", "n"] { state = state.add(ReadingListsState.wantToReadId, ReadingListEntry(workId: id, title: id)) }
        let resolved = ["r": ReadingWork(id: "r", status: "reading"), "f": ReadingWork(id: "f", status: "finished"),
                        "n": ReadingWork(id: "n", status: "not-reading"), "w": ReadingWork(id: "w", status: "want")]
        let row = ReadingShelves.rows(current: [], state: state, resolved: resolved).first { $0.id == ReadingListsState.wantToReadId }
        #expect(row?.items.map(\.id) == ["w"])
    }

    @Test func aSeriesNextBookIsNotOnePutDownAndAFinishByStatusCounts() {
        let series = ReadingWork(id: "s", entityType: "collection", title: "Red Rising", sections: [ReadingSection(id: "b", items: [
            ReadingSectionItem(workId: "b1", number: "1", status: "finished"),
            ReadingSectionItem(workId: "b2", number: "2", status: "not-reading"),
            ReadingSectionItem(workId: "b3", number: "3"),
        ])])
        #expect(ReadingShelves.nextInSeries([series]).map(\.id) == ["b3"])
        #expect(ReadingShelves.yourSeries([series]).map(\.id) == ["s"], "a book finished by status is one begun")
        let done = ReadingWork(id: "d", entityType: "collection", sections: [ReadingSection(id: "b", items: [
            ReadingSectionItem(workId: "x", status: "finished"), ReadingSectionItem(workId: "y", progress: progress(1, completed: true)),
        ])])
        #expect(ReadingShelves.yourSeries([done]).isEmpty, "every book finished")
    }

    @Test func aBookMarkedReadHereIsFinishedSoTheHubsOlderWordDoesNotTakeTheTick() {
        let state = ReadingCompletionState().markRead("rw_1", at: 1_000)
        let work = state.project(ReadingWork(id: "rw_1", progress: progress(0.3), status: "reading"))
        #expect(work.status == "finished" && ReadingStatus.isFinished(work))
        let item = state.project(ReadingSectionItem(workId: "rw_1", status: "want"))
        #expect(ReadingStatus.isFinished(item))
    }
}
