import Foundation
import Testing
@testable import HubKit

/// The dictionary card's lookups (#62): the Pocket's `PhraseLookupTest` and
/// `ReadAlongSelectionTest`'s terms, case for case, so a word or a phrase
/// finds the same entry on both; then the real Open English WordNet file the
/// Pocket ships, where this checkout has it.
struct DictionaryTests {
    /// A dictionary of a few entries; every question asked of it is recorded.
    final class Book: @unchecked Sendable {
        let known: Set<String>
        private let lock = NSLock()
        private var questions: [[String]] = []

        init(_ entries: String...) {
            known = Set(entries)
        }

        var asked: [[String]] { lock.withLock { questions } }

        func lookup(_ requested: String, _ terms: [String]) -> DictionaryEntry {
            lock.withLock { questions.append(terms) }
            let hit = terms.first(where: known.contains)
            return DictionaryEntry(requested: requested, headword: hit ?? "",
                                   definitions: hit.map { [DictionaryDefinition(partOfSpeech: "noun", text: "the meaning of \($0)")] } ?? [])
        }
    }

    private func answer(_ book: Book, _ selection: String) async -> PhraseLookup.Answer {
        await PhraseLookup.answer(selection) { requested, terms in book.lookup(requested, terms) }
    }

    @Test func theWordsOfASelectionHaveNoPunctuationRoundThem() {
        #expect(PhraseLookup.words("take off") == ["take", "off"])
        #expect(PhraseLookup.words("\u{201C}Don\u{2019}t go,\u{201D}") == ["Don\u{2019}t", "go"])
        #expect(PhraseLookup.words(" \u{2014} well-known bird. ") == ["well-known", "bird"])
        #expect(PhraseLookup.words("שלום, עולם") == ["שלום", "עולם"])
        #expect(PhraseLookup.words("\u{2026} \u{201D}").isEmpty)
    }

    @Test func oneWordIsLookedUpByItsBaseFormsAndOpensTheCardAtOnce() async {
        let book = Book("bell")
        let got = await answer(book, "bells")
        #expect(got.found && got.shown == "bell" && !got.isPhrase && got.opensCard && got.note == nil)
        #expect(book.asked == [["bells", "bell"]])
    }

    @Test func oneWordWithNoEntryStillOpensTheCardAndSaysSo() async {
        let got = await answer(Book(), "Maren")
        #expect(!got.found && got.opensCard)
        #expect(got.note == "No entry for \u{201C}Maren\u{201D}.")
    }

    @Test func aPhraseIsTriedAsOneEntryFirstWithTheFirstWordsBaseForms() async {
        let book = Book("pull off")
        let got = await answer(book, "pulled off")
        #expect(got.found && got.shown == "pull off" && got.isPhrase)
        #expect(got.opensCard, "a phrase the dictionary knows opens the card")
        #expect(got.note == nil)
        #expect(book.asked.count == 1)
        #expect(book.asked.first?.first == "pulled off")
        #expect(book.asked.first?.contains("pull off") == true)
    }

    @Test func thePhraseKeepsItsLaterWordsAsWrittenAndLowerCased() async {
        #expect(await answer(Book("in spite of"), "In spite of").shown == "in spite of")
        #expect(PhraseLookup.phraseTerms(["In", "spite", "of"]) == ["in spite of"])
        #expect(PhraseLookup.phraseTerms(["takes", "off"]).contains("take off"))
        #expect(PhraseLookup.phraseTerms(["lantern"]).isEmpty)
    }

    @Test func aPhraseWithNoEntryShowsTheFirstWordThatHasOneAndNeverSilently() async {
        let got = await answer(Book("harbor", "bell"), "harbor bells rang")
        #expect(got.found && got.shown == "harbor")
        #expect(got.missed == "harbor bells rang")
        #expect(got.note == "No entry for \u{201C}harbor bells rang\u{201D}. Showing \u{201C}harbor\u{201D}.")
        #expect(!got.opensCard, "the bar offers Look up instead of opening the card")
        // Not "the" or "rang": the first word that has an entry.
        #expect(await answer(Book("bell"), "the bells rang").shown == "bell")
    }

    @Test func aPhraseNoWordOfWhichIsInTheDictionaryIsSaidToHaveNoEntry() async {
        let got = await answer(Book(), "Maren Keld")
        #expect(!got.found && !got.opensCard)
        #expect(got.note == "No entry for \u{201C}Maren Keld\u{201D}.")
        #expect(got.shown == "Maren Keld")
    }

    @Test func nothingButPunctuationIsNoEntryEither() async {
        let got = await answer(Book(), "\u{2026}")
        #expect(!got.found && got.note != nil)
    }

    @Test func dictionaryCandidatesHandleCommonInflectionsAndPunctuation() {
        #expect(DictionaryTerms.candidates("\u{201C}Stories,\u{201D}") == ["stories", "story"])
        #expect(DictionaryTerms.candidates("running") == ["running", "runn", "run"])
        #expect(DictionaryTerms.candidates("walked") == ["walked", "walk", "walke"])
        #expect(DictionaryTerms.candidates("!?").isEmpty)
        #expect(DictionaryTerms.candidates("baking") == ["baking", "bak", "bake"])
        #expect(DictionaryTerms.candidates("boxes") == ["boxes", "box", "boxe"])
        #expect(DictionaryTerms.candidates("Don\u{2019}t") == ["don't"])
    }

    // MARK: The real dictionary

    /// The Pocket's own file, where this checkout has it (the build copy on
    /// the Mac carries it for the app's bundle); nothing where it does not.
    /// It has no "bells" and no "pulled off": the base forms find "bell" and "pull off".
    static let wordnet: URL? = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("app/src/main/assets/dictionary/en-wordnet-2025.db")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }()

    @Test(.enabled(if: DictionaryTests.wordnet != nil)) func theRealDictionaryAnswersAWordAPhraseAndNothing() async throws {
        let dictionary = OfflineEnglishDictionary(url: try #require(Self.wordnet))
        #expect(dictionary.isReady)
        let bells = dictionary.lookup("bells")
        #expect(bells.headword == "bell")
        #expect(!bells.definitions.isEmpty && bells.definitions.count <= OfflineEnglishDictionary.senses)
        #expect(bells.definitions.allSatisfy { !$0.partOfSpeech.isEmpty && !$0.text.isEmpty })
        let phrase = await PhraseLookup.answer("pulled off") { dictionary.lookupTerms($0, $1) }
        #expect(phrase.found && phrase.shown == "pull off" && phrase.opensCard, "\(phrase)")
        let nothing = await PhraseLookup.answer("Zqxv") { dictionary.lookupTerms($0, $1) }
        #expect(!nothing.found && nothing.note == "No entry for \u{201C}Zqxv\u{201D}.")
    }

    @Test func aFileThatIsNotTheDictionaryAnswersNothingAndDoesNotCrash() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("not-wordnet-\(UUID().uuidString).db")
        try Data("not a database".utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let dictionary = OfflineEnglishDictionary(url: url)
        #expect(!dictionary.isReady)
        #expect(dictionary.lookup("bell").definitions.isEmpty)
    }
}
