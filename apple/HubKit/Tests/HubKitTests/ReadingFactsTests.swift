import Foundation
import Testing
@testable import HubKit

/// Every line a book says (#25): Android's `ReadingBookFactsTest`, the series
/// row's and the author's labels, and `Fmt.readingPercent`.
struct ReadingFactsTests {
    private let pierce = [ReadingAuthorRef(id: "ra_1", name: "Pierce Brown")]

    @Test func readingPercentIsRoundedDownAndNeverZeroOnceStarted() {
        #expect(Fmt.readingPercentLabel(0.016) == "1%")
        #expect(Fmt.readingPercent(0.4999) == 49)
        #expect(Fmt.readingPercent(0.0004) == 1)
        #expect(Fmt.readingPercent(0.9999) == 99)
        #expect(Fmt.readingPercent(1.0) == 99)
        #expect(Fmt.readingPercent(0.3, completed: true) == 100)
        #expect(Fmt.readingPercent(0) == 0)
        #expect(Fmt.readingPercent(.nan) == 0)
    }

    @Test func aBookSaysWhereItSitsInItsSeriesItsYearAndPages() {
        let lightBringer = ReadingWork(title: "Light Bringer", authors: ["Pierce Brown"], series: "Red Rising", seriesIndex: 6,
                                       authorRefs: pierce, year: 2023, editions: [ReadingEdition(kind: "ebook", pageCount: 735)])
        #expect(ReadingBookFacts.line(lightBringer, progress: "49% read") == "Book 6 of Red Rising · 2023 · 735 pages · 49% read")
    }

    @Test func anAudiobookSaysHowLongItIsAndWhoReadsIt() {
        let well = ReadingWork(title: "Well of Ascension", series: "Mistborn", seriesIndex: 2,
                               authorRefs: [ReadingAuthorRef(id: "ra_2", name: "Brandon Sanderson")],
                               editions: [ReadingEdition(kind: "audiobook", narrator: "Michael Kramer",
                                                         durationMs: 29 * 3_600_000 + 50 * 60_000)])
        #expect(ReadingBookFacts.line(well, progress: nil) == "Book 2 of Mistborn · 29h 50m · read by Michael Kramer")
    }

    @Test func anAudiobookWhileYouListenSaysWhatItIsAndWhoReadsIt() {
        let well = ReadingWork(title: "Well of Ascension", series: "Mistborn", seriesIndex: 2,
                               authorRefs: [ReadingAuthorRef(id: "ra_2", name: "Brandon Sanderson")])
        #expect(ReadingBookFacts.listeningEyebrow(well) == "Audiobook · Book 2 · Mistborn")
        #expect(ReadingBookFacts.listeningLine(well, narrator: " Michael Kramer ") == "Brandon Sanderson · read by Michael Kramer")
        #expect(ReadingBookFacts.listeningEyebrow(nil) == "Audiobook")
        #expect(ReadingBookFacts.listeningEyebrow(ReadingWork(title: "Dark Matter")) == "Audiobook")
        #expect(ReadingBookFacts.listeningLine(nil, narrator: "Jon Lindstrom") == "read by Jon Lindstrom")
        #expect(ReadingBookFacts.listeningLine(ReadingWork(authors: ["Blake Crouch"]), narrator: "") == "Blake Crouch")
    }

    @Test func withoutAuthorLinksTheAuthorIsNamedInTheLine() {
        let darkMatter = ReadingWork(title: "Dark Matter", authors: ["Blake Crouch"], year: 2016)
        #expect(ReadingBookFacts.line(darkMatter, progress: nil) == "Blake Crouch · 2016")
        #expect(ReadingBookFacts.place(darkMatter) == nil)
        #expect(ReadingBookFacts.place(ReadingWork(series: "Saga")) == "Saga")
        #expect(ReadingBookFacts.place(ReadingWork(series: "Saga", seriesIndex: 1.5)) == "Book 1.5 of Saga")
    }

    @Test func progressNamesThePageWhenTheLengthIsKnown() {
        let book = ReadingWork(title: "Light Bringer", editions: [ReadingEdition(kind: "ebook", pageCount: 735)],
                               progress: ReadingProgress(percentage: 0.4945))
        #expect(ReadingBookFacts.progress(book) == "49% · page 363 of 735")
        var plain = book
        plain.editions = []
        #expect(ReadingBookFacts.progress(plain) == "49% read")
        var done = book
        done.progress = ReadingProgress(completed: true)
        #expect(ReadingBookFacts.progress(done) == "Finished")
        var none = book
        none.progress = nil
        #expect(ReadingBookFacts.progress(none) == nil)
    }

    @Test func aSeriesPageSaysWhichBookAndHowManyAreFinished() {
        func book(_ n: Int, _ pct: Double?, done: Bool = false) -> ReadingSectionItem {
            ReadingSectionItem(workId: "b\(n)", number: "\(n)",
                               progress: pct.map { ReadingProgress(percentage: $0, completed: done) })
        }
        let series = ReadingWork(entityType: "collection", bookCount: 6,
                                 sections: [ReadingSection(items: [book(1, 1, done: true), book(2, 0.3), book(3, nil)])],
                                 continueAt: ReadingContinue(number: "2"))
        #expect(ReadingBookFacts.seriesProgress(series) == "On #2 · 1 of 3 finished")
        var unread = series
        unread.continueAt = nil
        unread.sections = [ReadingSection(items: [book(1, nil)])]
        #expect(ReadingBookFacts.seriesProgress(unread) == nil)
        // The real Red Rising: on the sixth, none finished.
        let redRising = try? ReadingVectors.decode(ReadingWork.self, ReadingVectors.redRising)
        #expect(redRising.flatMap(ReadingBookFacts.seriesProgress) == "On #6 · 0 of 6 finished")
    }

    @Test func aComicSaysItsKindOnTheCoverAndHowFarInUnderIt() {
        #expect(ReadingBookFacts.kindTag("comic") == "Comic")
        #expect(ReadingBookFacts.kindTag("manga") == "Manga")
        #expect(ReadingBookFacts.kindTag("book") == nil)
        #expect(ReadingBookFacts.comicLine(nil) == "Not started")
        // One page of a 4,437-page run is still started.
        #expect(ReadingBookFacts.comicLine(ReadingProgress(percentage: 0.000225)) == "1% read")
        #expect(ReadingBookFacts.comicLine(ReadingProgress(percentage: 0.347)) == "34% read")
        #expect(ReadingBookFacts.comicLine(ReadingProgress(percentage: 1, completed: true)) == "Finished")
    }

    @Test func aComicRunIsCountedInIssuesAndEachIssueIsNamed() {
        let issue = ReadingSectionItem(sourceItemId: "8338", title: "7", number: "7", kind: "comic", pageCount: 36)
        var eight = issue
        eight.number = "8"
        eight.title = "8"
        let run = ReadingWork(id: "w", kind: "comic", year: 1961, sections: [ReadingSection(items: [issue, eight])])
        #expect(ReadingBookFacts.length(run) == ["2 issues"])
        #expect(ReadingBookFacts.issueTitle(issue, kind: "comic") == "Issue 7")
        #expect(ReadingBookFacts.issueTitle(issue, kind: "manga") == "Chapter 7")
        var annual = issue
        annual.title = "Annual 1965"
        #expect(ReadingBookFacts.issueTitle(annual, kind: "comic") == "Annual 1965")
        #expect(ReadingBookFacts.issueLine(issue) == "36 pages · Not started")
    }

    @Test func aPagesEyebrowSaysWhatItIsAndWhereItBelongs() {
        let lightBringer = ReadingWork(title: "Light Bringer", series: "Red Rising", seriesIndex: 6)
        #expect(ReadingBookFacts.eyebrow(lightBringer) == "Book 6 · Red Rising")
        var unnumbered = lightBringer
        unnumbered.seriesIndex = 0
        #expect(ReadingBookFacts.eyebrow(unnumbered) == "Red Rising")
        #expect(ReadingBookFacts.eyebrow(ReadingWork(entityType: "collection", title: "Red Rising", authors: ["Pierce Brown"]))
                == "Series · Pierce Brown")
        #expect(ReadingBookFacts.eyebrow(ReadingWork(entityType: "collection")) == "Series")
        #expect(ReadingBookFacts.eyebrow(ReadingWork(kind: "comic"), library: "My Marvelous Year") == "Comic · My Marvelous Year")
        #expect(ReadingBookFacts.eyebrow(ReadingWork(kind: "manga")) == "Manga")
        #expect(ReadingBookFacts.eyebrow(ReadingWork(title: "Dark Matter")) == "Book")
        #expect(ReadingBookFacts.eyebrow(ReadingWork(kind: "audiobook")) == "Audiobook")
    }

    @Test func aBookAlsoBeingReadSaysWhoseItIsAndHowFarIn() {
        let darkMatter = ReadingWork(title: "Dark Matter", authors: ["Blake Crouch"], progress: ReadingProgress(percentage: 0.0325))
        #expect(ReadingBookFacts.miniLine(darkMatter) == "Blake Crouch · 3%")
        // Opened is never 0%.
        #expect(ReadingBookFacts.miniLine(ReadingWork(series: "Mistborn", seriesIndex: 1, progress: ReadingProgress(percentage: 0.004)))
                == "Mistborn #1 · 1%")
        var done = darkMatter
        done.progress = ReadingProgress(percentage: 1, completed: true)
        #expect(ReadingBookFacts.miniLine(done) == "Blake Crouch · Finished")
        var none = darkMatter
        none.progress = nil
        #expect(ReadingBookFacts.miniLine(none) == "Blake Crouch")
    }

    @Test func aSeriesContinueCardSaysWhichBookHowFarAndWhichPage() {
        let point = ReadingContinue(title: "Light Bringer", number: "6", percentage: 0.4945)
        #expect(ReadingBookFacts.continueLine(point, kind: "book", pages: 735) == "Book 6 · 49% · page 363 of 735")
        #expect(ReadingBookFacts.continueLine(point, kind: "book", pages: 0) == "Book 6 · 49%")
        var issue = point
        issue.number = "51"
        issue.percentage = 0.002
        #expect(ReadingBookFacts.continueLine(issue, kind: "comic", pages: 0) == "Issue 51 · 1%")
        var first = point
        first.number = "1"
        first.percentage = 0
        #expect(ReadingBookFacts.continueLine(first, kind: "book", pages: 300) == "Book 1")
    }

    @Test func aSeriesLineCountsItsBooksAndTheMissingOnes() {
        let series = ReadingWork(entityType: "collection", genres: ["Science fiction"], bookCount: 6,
                                 sections: [ReadingSection(items: [ReadingSectionItem(workId: "b1"),
                                                                   ReadingSectionItem(workId: "", availability: "missing")])])
        #expect(ReadingBookFacts.seriesLine(series) == "6 books · 1 missing · Science fiction")
        var unlinked = series
        unlinked.authors = ["Pierce Brown"]
        unlinked.genres = []
        unlinked.bookCount = 1
        #expect(ReadingBookFacts.seriesLine(unlinked) == "Pierce Brown · 1 book · 1 missing")
        unlinked.authorRefs = pierce
        #expect(ReadingBookFacts.seriesLine(unlinked) == "1 book · 1 missing")
    }

    @Test func aSeriesOrRunNamesTheBookIssueOrChapterToContinue() {
        let point = ReadingContinue(title: "Light Bringer", number: "6")
        #expect(ReadingBookFacts.continueLabel(point, kind: "book") == "Continue · Book 6")
        #expect(ReadingBookFacts.continueLabel(point, kind: "comic") == "Continue · Issue 6")
        #expect(ReadingBookFacts.continueLabel(point, kind: "manga") == "Continue · Chapter 6")
        #expect(ReadingBookFacts.continueLabel(ReadingContinue(title: "Light Bringer"), kind: "book") == "Continue reading")
    }

    @Test func formatsComeInOneOrderWhateverOrderTheHubSends() {
        #expect(ReadingBookFacts.formats(ReadingWork(availability: ["readaloud", "ebook", "audiobook"]))
                == ["ebook", "audiobook", "readaloud"])
        #expect(ReadingBookFacts.formats(ReadingWork(availability: ["audiobook"])) == ["audiobook"])
        // A book's own page carries editions rather than the list.
        #expect(ReadingBookFacts.formats(ReadingWork(editions: [
            ReadingEdition(kind: "book", availability: "available"),
            ReadingEdition(kind: "audiobook", availability: "available"),
            ReadingEdition(kind: "readaloud", availability: "missing"),
        ])) == ["ebook", "audiobook"])
    }

    @Test func aSeriesRowSaysTheNumberHowFarAndTheUnusualFormat() {
        #expect(SeriesBookLabels.formats(["ebook"]) == nil)
        #expect(SeriesBookLabels.formats(["ebook", "audiobook"]) == "Ebook + audio")
        #expect(SeriesBookLabels.formats(["audiobook"]) == "Audio")
        #expect(SeriesBookLabels.formats(["ebook", "audiobook", "readaloud"]) == "Read along")
        #expect(SeriesBookLabels.subtitle(number: "2", available: true, progress: ReadingProgress(percentage: 0.4),
                                          formats: ["audiobook"]) == "#2 · 40% · Audio")
        #expect(SeriesBookLabels.subtitle(number: "3", available: false, progress: nil, formats: ["ebook", "audiobook"])
                == "#3 · Missing")
        #expect(SeriesBookLabels.subtitle(number: "", available: true, progress: ReadingProgress(completed: true), formats: [])
                == "Completed")
    }

    @Test func anAuthorsShelfAndHowFarThroughItYouAre() {
        #expect(AuthorLabels.shelf(seriesCount: 2, bookCount: 6, total: 0) == "2 series · 6 books")
        #expect(AuthorLabels.shelf(seriesCount: 0, bookCount: 0, total: 2) == "2 books")
        #expect(AuthorLabels.shelf(seriesCount: 1, bookCount: 0, total: 0) == "1 series")
        #expect(AuthorLabels.shelf(seriesCount: 0, bookCount: 1, total: 0) == "1 book")
        #expect(AuthorLabels.reading([ReadingProgress(percentage: 0.3), ReadingProgress(percentage: 1, completed: true), nil])
                == "1 in progress · 1 finished")
        #expect(AuthorLabels.reading([ReadingProgress(completed: true), ReadingProgress(completed: true)]) == "All 2 finished")
        #expect(AuthorLabels.reading([nil, ReadingProgress()]) == nil)
    }

    @Test func aCardSubtitleNamesTheSeriesNumberElseTheAuthor() {
        #expect(ReadingWork(title: "Dark Matter", authors: ["Blake Crouch"]).cardSubtitle == "Blake Crouch")
        #expect(ReadingWork(title: "Novella", series: "Saga", seriesIndex: 1.5).cardSubtitle == "Saga #1.5")
        #expect(ReadingWork(entityType: "collection", title: "Saga", series: "Saga", seriesIndex: 1).cardSubtitle == "Saga")
    }
}
