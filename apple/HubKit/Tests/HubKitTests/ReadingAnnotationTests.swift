import Foundation
import Testing
@testable import HubKit

/// Highlights and notes (#62): the Pocket's `ReadingAnnotationTest`, case for
/// case, then the body a write sends and the limits it is cut to.
struct ReadingAnnotationTests {
    @Test func theFourColoursAreTheHubsAndAnUnknownOneIsYellow() {
        #expect(HighlightColor.allCases.map(\.rawValue) == ["yellow", "blue", "pink", "green"])
        #expect(HighlightColor.of("pink") == .pink)
        #expect(HighlightColor.of("purple") == nil)
        #expect(HighlightColor.orDefault("purple") == .yellow)
        #expect(HighlightColor.orDefault(nil) == .yellow)
    }

    @Test func anAnnotationReadsTheHubsJsonAndIgnoresWhatItDoesNotUnderstand() throws {
        let text = """
            {"id":"an_0123456789abcdef0123456789abcdef","color":"blue","note":"who lit it?","document":"OEBPS/chapter-1.xhtml",
             "quote":{"before":"the old light, like a ","highlight":"bird","after":". She had laughed"},
             "locator":{"href":"OEBPS/chapter-1.xhtml","locations":{"progression":0.4}},
             "createdAt":10,"updatedAt":20,"syncedAt":30,"somethingNew":true}
            """
        let annotation = try JSONDecoder().decode(ReadingAnnotation.self, from: Data(text.utf8))
        #expect(annotation.quote.highlight == "bird")
        #expect(annotation.highlightColor == .blue)
        #expect(annotation.hasNote)
        #expect(annotation.syncedAt == 30)
        #expect(!annotation.deleted)
        #expect(annotation.locator?["href"]?.stringValue == "OEBPS/chapter-1.xhtml")
        let again = try JSONDecoder().decode(ReadingAnnotation.self, from: JSONEncoder().encode(annotation))
        #expect(again == annotation)
    }

    @Test func aQuoteKeepsTheWordsAroundTheSelectionAndNoMoreThanAFewOfThem() throws {
        let before = String(repeating: "x", count: 300) + " She did not stop to look at them. In spite of the cold, she pulled off her "
        let quote = AnnotationQuotes.of(before, "  gloves \n and set ",
                                        " her palm against the lighthouse door. The iron was warm. Somewhere above her the lantern burned, still, and brightly")
        #expect(quote.highlight == "gloves and set")
        #expect(quote.before.count <= AnnotationQuotes.context)
        #expect(quote.after.count <= AnnotationQuotes.context)
        #expect(quote.before.hasSuffix("pulled off her"), "the context ends at the selection: \(quote.before)")
        #expect(quote.after.hasPrefix("her palm against"))
        // It starts at a word, not in the middle of one.
        #expect(!quote.before.hasPrefix("xx"))
        #expect(try #require(quote.before.first).isLetter)
    }

    @Test func contextShorterThanTheLimitIsKeptWhole() {
        let quote = AnnotationQuotes.of("the old light, like a ", "bird", ".\u{201D} She had laughed")
        #expect(quote.before == "the old light, like a")
        #expect(quote.after == ".\u{201D} She had laughed")
        #expect(AnnotationQuotes.of(nil, "", nil) == AnnotationQuote())
    }

    @Test func aPassageIsFoundWhateverTheQuotesAndSpacingOfTheEdition() throws {
        let text = "\u{201C}They said it would take off on its own,\u{201D}\n   her brother had told her, \u{201C}the old light, like a bird.\u{201D}"
        let quote = AnnotationQuote(before: "it would", highlight: "take off on its OWN,\u{201D} her brother", after: "had told her")
        let found = try #require(AnnotationFinder.find(text, quote))
        #expect(found.start >= 0 && found.start < found.end)
        #expect(found.places == 1)
        #expect(found.end - found.start == AnnotationFinder.normalize("take off on its own,\u{201D} her brother").unicodeScalars.count)
    }

    @Test func curlyAndStraightApostrophesAndTheEllipsisAreTheSamePassage() {
        #expect(AnnotationFinder.find("Don\u{2019}t go\u{2026} now", AnnotationQuote(highlight: "don't go... now")) != nil)
        #expect(AnnotationFinder.find("Don't go... now", AnnotationQuote(highlight: "Don\u{2019}t go\u{2026} now")) != nil)
    }

    @Test func softHyphensZeroWidthCharactersAndADecomposedLetterAreNotInTheWay() {
        #expect(AnnotationFinder.find("a light\u{00AD}house\u{200B} door", AnnotationQuote(highlight: "lighthouse door")) != nil)
        #expect(AnnotationFinder.find("the caf\u{0065}\u{0301} was shut", AnnotationQuote(highlight: "café was")) != nil)
    }

    @Test func aPassageThatIsNotInTheTextIsNotFound() {
        #expect(AnnotationFinder.find("She did not stop to look at them.", AnnotationQuote(highlight: "she did stop")) == nil)
        #expect(AnnotationFinder.find("", AnnotationQuote(highlight: "anything")) == nil)
        #expect(AnnotationFinder.find("some text", AnnotationQuote(highlight: "   ")) == nil)
    }

    @Test func whenTwoPlacesSayTheSameWordsTheOneWithTheRightNeighboursWins() throws {
        let text = "He saw the bird fly. Later, the old light, like a bird. She laughed at the bird too."
        let second = try #require(AnnotationFinder.find(text, AnnotationQuote(before: "the old light, like a ", highlight: "bird", after: ". She laughed")))
        let first = try #require(AnnotationFinder.find(text, AnnotationQuote(before: "He saw the ", highlight: "bird", after: " fly.")))
        let third = try #require(AnnotationFinder.find(text, AnnotationQuote(before: "laughed at the ", highlight: "bird", after: " too.")))
        #expect(second.places == 3)
        #expect(first.start < second.start && second.start < third.start)
        let normal = AnnotationFinder.normalize(text)
        #expect(first.start == normal.unicodeScalars.distance(from: normal.unicodeScalars.startIndex,
                                                              to: try #require(normal.range(of: "bird fly")).lowerBound))
    }

    @Test func withNoContextToChooseByTheHintOrElseTheFirstPlaceDecides() throws {
        let text = "bird. " + String(repeating: "x ", count: 50) + "bird. " + String(repeating: "y ", count: 50) + "bird."
        #expect(try #require(AnnotationFinder.find(text, AnnotationQuote(highlight: "bird"))).start == 0)
        let near = try #require(AnnotationFinder.find(text, AnnotationQuote(highlight: "bird"), hint: 0.5))
        #expect(near.share > 0.3 && near.share < 0.7, "nearest the middle: \(near.share)")
        #expect(try #require(AnnotationFinder.find(text, AnnotationQuote(highlight: "bird"), hint: 1))).share > 0.9)
    }

    @Test func howFarThroughTheDocumentAPassageIsGivesItsPageNumberToTheList() throws {
        let text = String(repeating: "a", count: 500) + " the lantern was burning " + String(repeating: "b", count: 500)
        let found = try #require(AnnotationFinder.find(text, AnnotationQuote(highlight: "the lantern was burning")))
        #expect(abs(found.share - 0.5) < 0.02)
    }

    @Test func aStreamedDocumentIsTheSameAsOneNormalisedWhole() {
        var stream = AnnotationFinder.TextNormalizer()
        for chunk in ["  The \u{201C}old", "\n light\u{201D} ", " like a\u{00A0}", "BIRD\u{2026}"] { stream.append(chunk) }
        #expect(stream.text == AnnotationFinder.normalize("  The \u{201C}old\n light\u{201D}  like a\u{00A0}BIRD\u{2026}"))
        #expect(stream.text == "the \"old light\" like a bird...")
        #expect(stream.length == stream.text.unicodeScalars.count)
    }

    // MARK: What a write sends, and the limits

    private func body(_ annotation: ReadingAnnotation) throws -> [String: Any] {
        try #require(try JSONSerialization.jsonObject(with: AnnotationBodies.of(annotation)) as? [String: Any])
    }

    private func note(_ id: String = "an_1") -> ReadingAnnotation {
        ReadingAnnotation(id: id, document: "OEBPS/chapter-1.xhtml", quote: AnnotationQuote(before: "a ", highlight: "bird", after: "."),
                          createdAt: 100, updatedAt: 100)
    }

    @Test func theBodyHasOnlyWhatTheHubAcceptsAndNeverMoreThanItKeeps() throws {
        var full = note()
        full.note = String(repeating: "x", count: 5000)
        full.deleted = true
        full.syncedAt = 77
        full.locator = .object(["href": .string("OEBPS/chapter-1.xhtml")])
        let sent = try body(full)
        #expect(Set(sent.keys) == ["id", "color", "note", "document", "quote", "locator", "createdAt", "updatedAt"])
        #expect((sent["note"] as? String)?.count == AnnotationLimits.noteBytes)
        #expect(try body(note())["locator"] == nil)
        var huge = note()
        huge.locator = .object(["blob": .string(String(repeating: "y", count: 9000))])
        #expect(try body(huge)["locator"] == nil, "a locator over 8 KB is left out, a hint only")
    }

    @Test func aNoteInAnotherAlphabetIsCutByBytesBetweenCharacters() {
        let hebrew = String(repeating: "שלום", count: 1500)
        let clipped = AnnotationLimits.clip(hebrew, AnnotationLimits.noteBytes)
        #expect(clipped.utf8.count <= AnnotationLimits.noteBytes)
        #expect(hebrew.hasPrefix(clipped))
        #expect(AnnotationLimits.clip("", 10) == "")
        let emoji = String(repeating: "a\u{1F600}", count: 10)
        #expect(AnnotationLimits.clip(emoji, 7) == "a\u{1F600}a")
    }

    @Test func anIdIsAnAndThirtyTwoHexDigitsAndANewOneIsValid() {
        #expect(AnnotationIds.valid("an_0123456789abcdef0123456789abcdef"))
        #expect(!AnnotationIds.valid("an_0123456789ABCDEF0123456789abcdef"))
        #expect(!AnnotationIds.valid("an_123"))
        #expect(!AnnotationIds.valid("xx_0123456789abcdef0123456789abcdef"))
        let made = AnnotationIds.new()
        #expect(AnnotationIds.valid(made) && made != AnnotationIds.new())
    }

    @Test func theFiltersAreAllAColourAndWithNotes() {
        var noted = note("an_2")
        noted.note = "who lit it?"
        noted.color = "pink"
        let list = [note("an_1"), noted]
        #expect(HighlightFilter.allCases.map(\.label) == ["All", "Yellow", "Blue", "Pink", "Green", "With notes"])
        #expect(HighlightFilter.all.apply(list).count == 2)
        #expect(HighlightFilter.pink.apply(list).map(\.id) == ["an_2"])
        #expect(HighlightFilter.notes.apply(list).map(\.id) == ["an_2"])
        #expect(HighlightFilter.blue.apply(list).isEmpty)
    }
}
