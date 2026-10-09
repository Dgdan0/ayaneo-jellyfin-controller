import Testing
@testable import HubKit

/// The one spelling of a content document's path (#61, #62): the Pocket's
/// `DocumentPathTest` for `of`, `name` and the lenient decoding, so a
/// highlight's `document` is written the same on every app.
struct DocumentPathTests {
    private let mistborn = "Brandon Sanderson - [Mistborn 01] - The Final Empire_split_010.htm"
    private let encoded = "Brandon%20Sanderson%20-%20%5BMistborn%2001%5D%20-%20The%20Final%20Empire_split_010.htm"

    @Test func readiumsEncodedSpellingAndTheBooksRawOneAreTheSameDocument() {
        #expect(DocumentPath.of(encoded) == mistborn)
        #expect(DocumentPath.of(mistborn) == mistborn)
        // Within a folder, with a fragment and a leading slash, as a locator or a link may say it.
        #expect(DocumentPath.of("/OEBPS/Text/\(encoded)#html61-s0") == "OEBPS/Text/\(mistborn)")
    }

    @Test func aFragmentIsNotPartOfTheDocumentAnEscapedHashIsPartOfItsName() {
        #expect(DocumentPath.of("one.xhtml#s12") == "one.xhtml")
        #expect(DocumentPath.of("Chapter%20%231.xhtml#s1") == "Chapter #1.xhtml")
        #expect(DocumentPath.of("one.xhtml#") == "one.xhtml")
    }

    @Test func aLetterWrittenAsOneCharacterOrAsTwoIsOneLetter() {
        let composed = "Caf\u{E9} - Zo\u{EB}.xhtml"
        let decomposed = "Cafe\u{301} - Zoe\u{308}.xhtml"
        #expect(Array(composed.unicodeScalars) != Array(decomposed.unicodeScalars))
        #expect(Array(DocumentPath.name(decomposed).unicodeScalars) == Array(composed.unicodeScalars))
        #expect(Array(DocumentPath.of("Caf%C3%A9%20-%20Zo%C3%AB.xhtml").unicodeScalars) == Array(composed.unicodeScalars))
        #expect(Array(DocumentPath.of("Cafe%CC%81%20-%20Zoe%CC%88.xhtml").unicodeScalars) == Array(composed.unicodeScalars))
        #expect(Array(DocumentPath.of(decomposed).unicodeScalars) == Array(composed.unicodeScalars))
    }

    @Test func aPercentThatIsNotAnEscapeStaysAndSoDoesARunThatIsNotUTF8() {
        #expect(DocumentPath.of("100%.xhtml") == "100%.xhtml")
        #expect(DocumentPath.of("a%zzb.xhtml") == "a%zzb.xhtml")
        #expect(DocumentPath.of("tail%") == "tail%")
        #expect(DocumentPath.of("tail%4") == "tail%4")
        // Latin-1 bytes, not UTF-8: left as written rather than turned into replacement characters.
        #expect(DocumentPath.of("caf%E9.xhtml") == "caf%E9.xhtml")
        #expect(DocumentPath.of("caf%C3%A9%20%E9.xhtml") == "caf\u{E9} %E9.xhtml")
        // A four byte character (an emoji), and lower case hex.
        #expect(DocumentPath.of("one%20%F0%9F%93%96.xhtml") == "one \u{1F4D6}.xhtml")
        #expect(DocumentPath.of("caf%c3%a9.xhtml") == "caf\u{E9}.xhtml")
    }

    @Test func itAgreesWithTheKeyEveryPageHrefIsComparedBy() {
        for href in [encoded, "/OEBPS/Text/\(encoded)#s1", "Caf%C3%A9.xhtml", "one.xhtml#s12"] {
            #expect(DocumentPath.of(href) == BookHref.key(href), "\(href)")
        }
    }
}
