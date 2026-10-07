import Testing
@testable import HubKit

/// Searching a book (#37): the phrase, the summary over the results, and
/// each passage's words, as the Pocket's sheet says them.
struct BookSearchTests {
    @Test func aPhraseIsTrimmedAndKeptToTwoHundredCharacters() {
        #expect(BookSearch.query("  the river  ") == "the river")
        #expect(BookSearch.query(" \n ") == nil)
        #expect(BookSearch.query(String(repeating: "a", count: 250))?.count == 200)
    }

    @Test func theSummarySaysHowManyAndWhenOnlyTheFirstHundredAreShown() {
        #expect(BookSearch.summary(0) == "No matches in this edition")
        #expect(BookSearch.summary(1) == "1 match")
        #expect(BookSearch.summary(12) == "12 matches")
        #expect(BookSearch.summary(100) == "First 100 matches · narrow your search")
        #expect(BookSearch.Problem.tooLong.title == "Search took too long")
        #expect(BookSearch.Problem.tooLong.detail == "Try a more specific phrase")
        #expect(BookSearch.Problem.failed.title == "Could not search this edition")
    }

    @Test func aPassageNamesItsChapterOrSaysItMatches() {
        #expect(BookSearch.chapter("  Chapter\nTwo ") == "Chapter Two")
        #expect(BookSearch.chapter(nil) == "Matching passage")
        #expect(BookSearch.chapter("   ") == "Matching passage")
    }

    @Test func thePassageIsOneLineCutAtAWord() {
        let short = BookSearch.snippet(before: "It was\n the ", match: "best", after: " of times,\tit was")
        #expect(short.before == "It was the ")
        #expect(short.match == "best")
        #expect(short.after == " of times, it was")

        let long = BookSearch.snippet(before: String(repeating: "word ", count: 30), match: "river",
                                      after: " " + String(repeating: "flows on ", count: 30))
        #expect(long.before.hasPrefix("…word"))
        #expect(long.before.hasSuffix("word "))
        #expect(long.before.count <= 81)
        #expect(long.after.hasPrefix(" flows"))
        #expect(long.after.hasSuffix("on…") || long.after.hasSuffix("flows…"))
        #expect(long.after.count <= 121)

        let hit = BookSearchHit(id: 0, chapter: "One", before: short.before, match: short.match, after: short.after,
                                locator: "{}")
        #expect(hit.label == "One, It was the best of times, it was")
    }
}
