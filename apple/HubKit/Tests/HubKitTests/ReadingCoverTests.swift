import Foundation
import Testing
@testable import HubKit

/// What a book's cover says of its formats (#54), as the Pocket's
/// `ReadingCoverTest`: tall for an ebook, square for an audiobook, and for a
/// book that is both a small round mark, headphones until it is aligned for
/// read along and a book with sound after.
struct ReadingCoverTests {
    private func shape(_ kind: String, _ formats: String...) -> ReadingBookFacts.CoverShape {
        ReadingBookFacts.coverShape(kind: kind, formats: formats)
    }

    private func mark(_ kind: String, _ formats: String...) -> ReadingBookFacts.CoverMark {
        ReadingBookFacts.formatMark(kind: kind, formats: formats)
    }

    @Test func anEbookIsTheTallCoverUnmarked() {
        #expect(shape("book", "ebook") == .tall && mark("book", "ebook") == .none)
    }

    @Test func anAudiobookIsASquareCoverUnmarked() {
        #expect(shape("audiobook", "audiobook") == .square && mark("audiobook", "audiobook") == .none)
        // What a book can be opened as decides, not what the hub called it.
        #expect(shape("book", "audiobook") == .square)
    }

    @Test func anEbookWithAnAudiobookIsTallWithHeadphonesUntilItIsAligned() {
        #expect(shape("book", "ebook", "audiobook") == .tall)
        #expect(mark("book", "ebook", "audiobook") == .headphones && mark("book", "audiobook", "ebook") == .headphones)
    }

    @Test func onceAlignedForReadAlongTheMarkIsTheBookWithSound() {
        #expect(shape("book", "ebook", "audiobook", "readaloud") == .tall)
        #expect(mark("book", "ebook", "audiobook", "readaloud") == .readAlong)
        // A read-along edition is both an ebook and an audiobook, whatever else is listed with it.
        #expect(shape("book", "readaloud") == .tall && mark("book", "readaloud") == .readAlong)
        #expect(mark("book", "audiobook", "readaloud") == .readAlong)
    }

    @Test func aComicOrMangaKeepsItsTallCoverAndItsPillWithNoMark() {
        for kind in ["comic", "manga"] {
            #expect(shape(kind, kind) == .tall && mark(kind, kind) == .none)
            #expect(shape(kind, "audiobook") == .tall && mark(kind, "ebook", "audiobook") == .none)
        }
    }

    @Test func aHubThatSaysNothingOfFormatsLeavesTheKindToDecide() {
        #expect(shape("audiobook") == .square && mark("audiobook") == .none)
        #expect(shape("book") == .tall && mark("book") == .none)
        #expect(shape("", "something-new") == .tall && mark("", "something-new") == .none)
    }

    @Test func aWorkReadsItsFormatsFromTheHubsListElseFromItsEditions() {
        var both = ReadingWork(kind: "book", availability: ["ebook", "audiobook"])
        #expect(ReadingBookFacts.formatMark(both) == .headphones)
        both.availability = ["ebook", "audiobook", "readaloud"]
        #expect(ReadingBookFacts.formatMark(both) == .readAlong)
        let editions = ReadingWork(kind: "book", editions: [ReadingEdition(kind: "book", availability: "available"),
                                                            ReadingEdition(kind: "audiobook", availability: "available")])
        #expect(ReadingBookFacts.coverShape(editions) == .tall && ReadingBookFacts.formatMark(editions) == .headphones)
        #expect(ReadingBookFacts.coverShape(ReadingWork(kind: "audiobook", availability: ["audiobook"])) == .square)
        // An edition that is not here (still being aligned, missing) is not a format of the book.
        let aligning = ReadingWork(kind: "book", editions: [ReadingEdition(kind: "book", availability: "available"),
                                                            ReadingEdition(kind: "audiobook", availability: "available"),
                                                            ReadingEdition(kind: "readaloud", availability: "queued")])
        #expect(ReadingBookFacts.formatMark(aligning) == .headphones)
        // A series is many books: no one format speaks for it.
        #expect(ReadingBookFacts.formatMark(ReadingWork(entityType: "collection", availability: ["ebook", "audiobook"])) == .none)
    }

    @Test func aBookInASeriesRowIsDecidedTheSameWay() {
        #expect(ReadingBookFacts.coverShape(ReadingSectionItem(kind: "audiobook", formats: ["audiobook"])) == .square)
        #expect(ReadingBookFacts.formatMark(ReadingSectionItem(kind: "book", formats: ["ebook", "audiobook", "readaloud"])) == .readAlong)
        #expect(ReadingBookFacts.formatMark(ReadingSectionItem(kind: "book", formats: ["ebook"])) == .none)
    }

    @Test func aSquareCoverSitsLowerInTheTallOnesPlaceSoCaptionsLineUp() {
        #expect(ReadingBookFacts.coverLift(.tall, width: 100) == 0)
        #expect(ReadingBookFacts.coverLift(.square, width: 100) == 50)
        #expect(ReadingBookFacts.tallHeight(100) == 150)
    }

    @Test func theMarkSaysWhatItIsToVoiceOver() {
        #expect(ReadingBookFacts.CoverMark.readAlong.words == "read along")
        #expect(ReadingBookFacts.CoverMark.headphones.words == "ebook and audiobook")
        #expect(ReadingBookFacts.CoverMark.none.words == nil)
    }

    /// The hub's `seriesBooks` on a series in a library's listing (#54):
    /// absent from an older hub, and every field with its default.
    @Test func aSeriesInTheListingCarriesItsBooks() throws {
        let books = [#"{"number":"1","title":"The Hunger Games","cover":"/v1/img/reading/storyteller/7","kind":"book","owned":true,"released":true,"state":"read"}"#,
                     #"{"number":"2","title":"Catching Fire","kind":"audiobook","owned":true,"released":true,"state":"on"}"#,
                     #"{"number":"4","title":"The Ballad of Songbirds and Snakes","owned":false,"released":true}"#]
        let json = #"{"id":"c1","entityType":"collection","title":"The Hunger Games","seriesBooks":["#
            + books.joined(separator: ",") + "]}"
        let series = try JSONDecoder().decode(ReadingWork.self, from: Data(json.utf8))
        #expect(series.seriesBooks.map(\.number) == ["1", "2", "4"])
        #expect(series.seriesBooks.map(\.state) == ["read", "on", ""])
        #expect(series.seriesBooks.map(\.owned) == [true, true, false])
        #expect(series.seriesBooks[1].kind == "audiobook" && series.seriesBooks[2].kind == "book" && series.seriesBooks[2].cover.isEmpty)
        #expect(SeriesFan.hasFan(series))
        let older = try JSONDecoder().decode(ReadingWork.self, from: Data(#"{"id":"c1","entityType":"collection"}"#.utf8))
        #expect(older.seriesBooks.isEmpty && !SeriesFan.hasFan(older))
    }
}
