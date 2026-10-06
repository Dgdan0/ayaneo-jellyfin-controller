import Foundation
import Testing
@testable import HubKit

/// The places this device kept and the hub has not had yet, laid over the
/// hub's answer: Android's `ReadingProgressPresentationTest`, with the
/// audiobook's cases of #30.
struct ReadingProgressPresentationTests {
    private let work = ReadingWork(id: "work", title: "Book",
                                   editions: [ReadingEdition(source: "storyteller", sourceItemId: "edition", kind: "ebook")])
    private let point = ReadingCheckpoint(
        key: ReadingCheckpointKey(scope: "profile", workId: "work", sourceItemId: "edition", kind: "epub"),
        local: ReadingLocation(locator: ["href": .string("chapter.xhtml"), "locations": .object(["totalProgression": .double(0.37)])]),
        updatedAt: 100, pending: true)

    private func near(_ value: Double?, _ expected: Double, within: Double = 1e-9) -> Bool {
        guard let value else { return false }
        return abs(value - expected) < within
    }

    @Test func comingBackToABooksPageShowsTheLocalPlaceBeforeItIsSent() {
        let shown = ReadingProgressPresentation.project(work, pending: [point])
        #expect(near(shown.continueAt?.percentage, 0.37, within: 0.001))
        #expect(shown.continueAt?.sourceItemId == "edition")
    }

    @Test func anotherEditionsPlaceNeverChangesThisBook() {
        var other = point
        other.key = ReadingCheckpointKey(scope: "profile", workId: "work", sourceItemId: "other", kind: "epub")
        #expect(ReadingProgressPresentation.project(work, pending: [other]) == work)
    }

    @Test func aPlaceInAQuestionDoesNotPretendToBeTheAnswer() {
        var asked = point
        asked.conflicted = true
        #expect(ReadingProgressPresentation.project(work, pending: [asked]) == work)
    }

    // A book's page and a comic's page projected as they always were (#30 changed only the audiobook's).
    @Test func aBookPlaceShowsItsTotalProgressionAndAComicPlaceItsPageOverTheIssuesPages() throws {
        let book = try #require(ReadingProgressPresentation.project(work, pending: [point]).progress)
        #expect(near(book.percentage, 0.37, within: 0.001))
        #expect(!book.completed)
        // A book place that names no total still reads as it did: the start.
        var bare = point
        bare.local = ReadingLocation(locator: ["href": .string("chapter.xhtml")])
        #expect(ReadingProgressPresentation.project(work, pending: [bare]).progress?.percentage == 0)
        let issue = ReadingSectionItem(sourceItemId: "issue", workId: "work", title: "#1", kind: "comic", pageCount: 20)
        let run = ReadingWork(id: "work", kind: "comic", sections: [ReadingSection(id: "v", items: [issue])])
        let page = ReadingCheckpoint(key: ReadingCheckpointKey(scope: "profile", workId: "work", sourceItemId: "issue", kind: "pages"),
                                     local: ReadingLocation(pageIndex: 9), updatedAt: 100, pending: true)
        let shown = ReadingProgressPresentation.project(run, pending: [page])
        #expect(shown.progress == ReadingProgress(percentage: 0.5, completed: false, current: 10, total: 20))
        #expect(shown.sections.first?.items.first?.progress == shown.progress)
        #expect(shown.continueAt?.percentage == 0.5)
        #expect(shown.continueAt?.source == "kavita")
        // The last page is finished.
        var last = page
        last.local = ReadingLocation(pageIndex: 19)
        let finished = try #require(ReadingProgressPresentation.project(run, pending: [last]).progress)
        #expect(finished.completed)
        #expect(finished.percentage == 1)
    }

    // An audiobook of three tracks: ten, twenty and five minutes, thirty-five in all (#30).
    private let tracks = [ReadingAudioTrack(index: 0, id: "t_000000000001", title: "Track 01", durationMs: 600_000),
                          ReadingAudioTrack(index: 1, id: "t_000000000002", title: "Track 02", durationMs: 1_200_000),
                          ReadingAudioTrack(index: 2, id: "t_000000000003", title: "Track 03", durationMs: 300_000)]
    private let listening = ReadingWork(
        id: "work", title: "Book",
        editions: [ReadingEdition(source: "storyteller", sourceItemId: "edition", kind: "audiobook")],
        progress: ReadingProgress(percentage: 0.1),
        continueAt: ReadingContinue(workId: "work", source: "storyteller", sourceItemId: "edition", title: "Book", percentage: 0.1))
    /// The first track and five minutes of the second.
    private let partway = 900_000.0 / 2_100_000

    private func heard(_ location: ReadingLocation, at: Int64 = 100) -> ReadingCheckpoint {
        ReadingCheckpoint(key: ReadingCheckpointKey(scope: "profile", workId: "work", sourceItemId: "edition", kind: AudioPlace.kind),
                          local: location, updatedAt: at, pending: true)
    }

    @Test func aPendingAudiobookPlaceShowsHowFarThroughTheBookItIsNotNothing() throws {
        let kept = try #require(AudioPlace.kept(tracks, part: 1, offsetMs: 300_000))
        let shown = ReadingProgressPresentation.project(listening, pending: [heard(kept)])
        #expect(near(shown.progress?.percentage, partway))
        #expect(shown.progress?.completed == false)
        #expect(near(shown.continueAt?.percentage, partway))
        #expect(shown.continueAt?.sourceItemId == "edition")
        // A place does not carry a page: the book's own length is not a count of pages heard.
        #expect(shown.progress?.current == 0)
    }

    @Test func aFinishedAudiobookPlaceShowsTheBookFinished() throws {
        let kept = try #require(AudioPlace.kept(tracks, part: 2, offsetMs: 299_000))
        let shown = ReadingProgressPresentation.project(listening, pending: [heard(kept)])
        #expect(shown.progress?.completed == true)
        #expect(shown.progress?.percentage == 1)
        #expect(shown.continueAt?.percentage == 1)
        // One an older build kept has no fraction beside it, and is finished all the same.
        let finished = AudioPlace("t_000000000003", 300_000, completed: true).location()
        let old = ReadingProgressPresentation.project(listening, pending: [heard(finished)])
        #expect(old.progress?.completed == true)
        #expect(old.progress?.percentage == 1)
    }

    @Test func anAudiobookPlaceFromAnOlderBuildLeavesTheHubsProgressAsItIs() throws {
        let old = heard(AudioPlace("t_000000000002", 61_250).location())
        #expect(ReadingProgressPresentation.project(listening, pending: [old]) == listening)
        // So does one that cannot be told for want of a track's length.
        var unknown = tracks
        unknown[1].durationMs = 0
        let kept = try #require(AudioPlace.kept(unknown, part: 1, offsetMs: 300_000))
        #expect(ReadingProgressPresentation.project(listening, pending: [heard(kept)]) == listening)
    }

    @Test func aSeriesPageShowsTheBooksAudiobookPlaceOnItsRow() throws {
        let row = ReadingSectionItem(sourceItemId: "edition", workId: "work", title: "Book", progress: ReadingProgress(percentage: 0.1))
        var series = listening
        series.entityType = "collection"
        series.editions = []
        series.sections = [ReadingSection(id: "s", items: [row])]
        let kept = try #require(AudioPlace.kept(tracks, part: 1, offsetMs: 300_000))
        let shown = ReadingProgressPresentation.project(series, pending: [heard(kept)])
        #expect(near(shown.sections.first?.items.first?.progress?.percentage, partway))
        #expect(near(shown.continueAt?.percentage, partway))
        // The series keeps its own progress, as it does for a book's page.
        #expect(shown.progress == series.progress)
        // A place from an older build leaves the row as the hub sent it.
        let old = heard(AudioPlace("t_000000000002", 61_250).location())
        #expect(ReadingProgressPresentation.project(series, pending: [old]) == series)
    }

    @Test func ofABooksPlacesTheLatestThatCanBeToldWins() throws {
        let audio = heard(try #require(AudioPlace.kept(tracks, part: 1, offsetMs: 300_000)), at: 200)
        // Heard after it was read: the audiobook's place.
        #expect(near(ReadingProgressPresentation.project(work, pending: [point, audio]).continueAt?.percentage, partway))
        // Read after it was heard: the book's.
        var later = point
        later.updatedAt = 300
        #expect(near(ReadingProgressPresentation.project(work, pending: [later, audio]).continueAt?.percentage, 0.37,
                     within: 0.001))
        // A newer place an older build left says nothing, so it does not hide the book's own.
        let unsaid = heard(AudioPlace("t_000000000002", 61_250).location(), at: 400)
        let shown = ReadingProgressPresentation.project(work, pending: [point, unsaid])
        #expect(near(shown.continueAt?.percentage, 0.37, within: 0.001))
        #expect(near(shown.progress?.percentage, 0.37, within: 0.001))
    }
}
