import Foundation
import Testing
@testable import HubKit

/// The books kept on this device, as the Books side's Downloads lists them (#43).
struct ReadingKeptShelfTests {
    @Test func aBookKeptTwiceIsOneEntryWithBothOfWhatWasKeptNewestFirst() {
        var shelf: [ReadingKeptBook] = []
        shelf = ReadingKeptBook.record(shelf, workId: "w1", title: "Dark Matter", artwork: "/a1", kind: "ebook",
                                       sourceItemId: "e1", now: 10)
        shelf = ReadingKeptBook.record(shelf, workId: "w2", title: "Recursion", artwork: "/a2", kind: "audiobook",
                                       sourceItemId: "a2", now: 20)
        shelf = ReadingKeptBook.record(shelf, workId: "w1", title: "Dark Matter", artwork: "", kind: "audiobook",
                                       sourceItemId: "a1", now: 30)
        #expect(shelf.map(\.workId) == ["w1", "w2"])
        #expect(shelf[0].kinds == ["ebook", "audiobook"])
        #expect(shelf[0].sourceItemIds == ["e1", "a1"])
        #expect(shelf[0].artwork == "/a1", "an empty artwork keeps the one known")
        #expect(shelf[0].formats == "Ebook · Audiobook")
        #expect(shelf[1].isAudiobookOnly)
        // Nothing to keep without a work or an edition.
        #expect(ReadingKeptBook.record(shelf, workId: "", title: "x", artwork: "", kind: "ebook", sourceItemId: "e", now: 40) == shelf)
    }

    @Test func aComicRunCountsItsIssues() {
        var shelf: [ReadingKeptBook] = []
        for (index, issue) in ["i51", "i52", "i51"].enumerated() {
            shelf = ReadingKeptBook.record(shelf, workId: "ff", title: "Fantastic Four", artwork: "/ff", kind: "comic",
                                           sourceItemId: issue, now: Int64(index))
        }
        #expect(shelf.count == 1)
        #expect(shelf[0].formats == "Comic · 2 issues")
        #expect(ReadingKeptBook(workId: "m", title: "Chainsaw Man", kinds: ["manga"], sourceItemIds: ["c1"]).formats == "Manga")
        #expect(ReadingKeptBook(workId: "r", title: "Dark Matter", kinds: ["readalong"]).formats == "Read along")
    }

    @Test func theShelfIsKeptInAFileAndForgetsAWork() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("kept-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let shelf = ReadingKeptShelf(file: folder.appendingPathComponent("books.json"))
        #expect(shelf.list().isEmpty)
        shelf.record(workId: "w1", title: "Dark Matter", artwork: "/a", kind: "ebook", sourceItemId: "e1", now: 1)
        shelf.record(workId: "w2", title: "Recursion", artwork: "/b", kind: "ebook", sourceItemId: "e2", now: 2)
        let again = ReadingKeptShelf(file: shelf.file)
        #expect(again.list().map(\.workId) == ["w2", "w1"])
        again.remove(workId: "w2")
        #expect(ReadingKeptShelf(file: shelf.file).list().map(\.workId) == ["w1"])
    }
}
