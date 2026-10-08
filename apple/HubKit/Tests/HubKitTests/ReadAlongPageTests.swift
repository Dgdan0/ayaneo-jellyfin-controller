import Foundation
import Testing
@testable import HubKit

/// The voice and the page moving each other (#49): a page's first and last
/// words placed inside their sentences' clips, when the voice turns the page,
/// and what a page turned by hand does to the voice.
struct ReadAlongPageTests {
    // Two files: chapter one from 5 s into the first, chapter two the second.
    private let timeline = ReadAlongTimeline(tracks: [
        ReadAlongTrack(audioHref: "one.mp4", segments: [
            ReadAlongSegment(textHref: "one.xhtml", fragment: "s0", audioHref: "one.mp4", beginMs: 5_000, endMs: 6_000),
            ReadAlongSegment(textHref: "one.xhtml", fragment: "s1", audioHref: "one.mp4", beginMs: 6_000, endMs: 10_000),
            ReadAlongSegment(textHref: "one.xhtml", fragment: "s2", audioHref: "one.mp4", beginMs: 10_000, endMs: 12_000),
        ]),
        ReadAlongTrack(audioHref: "two.mp4", segments: [
            ReadAlongSegment(textHref: "two.xhtml", fragment: "t0", audioHref: "two.mp4", beginMs: 0, endMs: 3_000),
            ReadAlongSegment(textHref: "two.xhtml", fragment: "t1", audioHref: "two.mp4", beginMs: 3_000, endMs: 4_000),
        ]),
    ])

    /// Four words of four letters: the words begin at 0, 5, 10 and 15, and half the letters are before the third.
    private let words = "aaaa bbbb cccc dddd"

    private func at(_ track: Int, _ offsetMs: Int64) -> ReadAlongPosition { ReadAlongPosition(track: track, offsetMs: offsetMs) }

    private func caret(_ fragment: String, _ offset: Int, _ text: String? = nil) -> ReadAlongCaret {
        ReadAlongCaret(fragment: fragment, text: text ?? words, offset: offset)
    }

    // MARK: Words

    @Test func aCharacterIsHeardFromTheStartOfItsWord() {
        #expect(ReadAlongWords.wordStart(words, at: 0) == 0)
        #expect(ReadAlongWords.wordStart(words, at: 7) == 5)
        #expect(ReadAlongWords.wordStart(words, at: 18) == 15)
        // A word broken over the page ("exam-" | "ple") is heard from its start.
        #expect(ReadAlongWords.wordStart("an exam\u{AD}ple", at: 9) == 3)
    }

    @Test func aSpaceIsHeardAsTheWordAfterItAndNothingAfterTheLast() {
        #expect(ReadAlongWords.wordStart(words, at: 4) == 5)
        #expect(ReadAlongWords.wordStart("aaaa   \n  bbbb", at: 5) == 10)
        #expect(ReadAlongWords.wordStart("a\u{00A0}b", at: 1) == 2, "a no-break space is a space")
        #expect(ReadAlongWords.wordStart("aaaa  ", at: 4) == nil)
        #expect(ReadAlongWords.wordStart(words, at: 19) == nil, "the end of the sentence has no word")
        #expect(ReadAlongWords.wordStart(words, at: -1) == nil)
    }

    @Test func theShareCountsLettersNotSpaces() {
        #expect(ReadAlongWords.share(words, before: 0) == 0)
        #expect(ReadAlongWords.share(words, before: 10) == 0.5)
        #expect(ReadAlongWords.share(words, before: 19) == 1)
        // A line's end and its spaces take the voice no time.
        #expect(ReadAlongWords.share("aa      \n    bb", before: 13) == 0.5)
        // A character outside the basic plane is two of the page's units and two letters.
        #expect(ReadAlongWords.share("\u{1F600} ab", before: 3) == 0.5)
        #expect(ReadAlongWords.share("   ", before: 2) == 0, "no letters, no share")
        #expect(ReadAlongWords.share(words, before: 99) == 1)
    }

    // MARK: A page in the narration

    @Test func aPageBeginningInsideASentenceIsPlacedInsideItsClip() throws {
        // The page begins at "cccc" of s1, which is said from 6 to 10 s.
        let span = try #require(timeline.span(href: "one.xhtml", edges: ReadAlongPageEdges(first: caret("s1", 10),
                                                                                           last: caret("s1", 19))))
        #expect(span.start == at(0, 3_000), "half of s1's letters are before cccc: 6 s + 0.5 × 4 s, 3 s into the stretch at 5 s")
        // A page's sentences are its own file's.
        #expect(timeline.span(href: "two.xhtml", edges: ReadAlongPageEdges(first: caret("s1", 0), last: caret("s2", 3))) == nil)
        #expect(timeline.span(href: "one.xhtml", edges: ReadAlongPageEdges(first: caret("s1", 0), last: caret("t0", 3))) == nil)
    }

    @Test func thePageTurnsWhenTheVoiceReachesTheNextPagesFirstWord() throws {
        let edges = ReadAlongPageEdges(first: caret("s1", 10), last: caret("s2", 9))
        let span = try #require(timeline.span(href: "one.xhtml", edges: edges))
        #expect(span.start == at(0, 3_000))
        // After "bbbb", the next page begins at "cccc": half of s2 (10 to 12 s), 11 s, 6 s into the stretch.
        #expect(span.end == at(0, 6_000))
        #expect(span.firstSentence == at(0, 1_000))
        #expect(span.lastSentence == at(0, 5_000))
        #expect(span.nextSentenceEnd == at(0, 7_000), "the next page shows the voice until s2 ends")
    }

    @Test func aWordBrokenOverThePageTurnsItWhenTheVoiceBeginsTheWord() throws {
        // The last character on the page is inside "cccc".
        let span = try #require(timeline.span(href: "one.xhtml", edges: ReadAlongPageEdges(first: caret("s0", 0),
                                                                                           last: caret("s1", 12))))
        #expect(span.start == at(0, 0))
        #expect(span.end == at(0, 3_000))
    }

    @Test func aPageEndingWithItsSentenceTurnsWhenTheNextSentenceBeginsInTheNextFile() throws {
        let span = try #require(timeline.span(href: "one.xhtml", edges: ReadAlongPageEdges(first: caret("s1", 0),
                                                                                           last: caret("s2", 19))))
        #expect(span.start == at(0, 1_000))
        #expect(span.end == at(1, 0), "t0 begins the next page")
        #expect(span.nextSentenceEnd == at(1, 3_000))
        // The narration's last sentence on the page: nothing to turn to.
        let last = try #require(timeline.span(href: "two.xhtml", edges: ReadAlongPageEdges(first: caret("t0", 0),
                                                                                           last: caret("t1", 19))))
        #expect(last.end == nil && last.nextSentenceEnd == nil)
        #expect(last.shows(at(1, 3_900)) && last.shows(at(1, 9_000)))
    }

    @Test func thePageScriptsAnswerIsReadAndAnOffsetItCouldNotPlaceIsTheSentencesEdge() throws {
        let answer = #"{"first":{"id":"s1","text":"aaaa bbbb","offset":5},"last":{"id":"s2","text":"cc dd","offset":-1}}"#
        let edges = try #require(ReadAlongPageEdges.parse(answer))
        #expect(edges.first == ReadAlongCaret(fragment: "s1", text: "aaaa bbbb", offset: 5))
        #expect(edges.last == ReadAlongCaret(fragment: "s2", text: "cc dd", offset: 5))
        let unplaced = try #require(ReadAlongPageEdges.parse(#"{"first":{"id":"s1","text":"ab","offset":-1},"last":{"id":"s1","text":"ab","offset":9}}"#))
        #expect(unplaced.first.offset == 0 && unplaced.last.offset == 2)
        #expect(ReadAlongPageEdges.parse(nil) == nil)
        #expect(ReadAlongPageEdges.parse("null") == nil, "nothing narrated on the page")
        #expect(ReadAlongPageEdges.parse(#"{"first":{"id":""}}"#) == nil)
        #expect(ReadAlongPageEdges.parse(42) == nil)
    }

    @Test func thePageScriptTakesItsIdsAsJsonAndReportsOnlyOffsets() {
        let script = ReadAlongPageScript.edges(["one-s1", #"a"b"#])
        #expect(script.contains(#"var ids=["one-s1","a\"b"]"#))
        #expect(script.contains("caretRangeFromPoint") == false, "the page is measured by its characters' boxes")
        #expect(script.contains("getClientRects") && script.contains("JSON.stringify"))
        #expect(ReadAlongPageScript.edges([]).contains("var ids=[]"))
    }

    // MARK: The voice turns the page

    private var page: ReadAlongPageSpan {
        // From halfway through s1 to halfway through t0.
        ReadAlongPageSpan(start: at(0, 3_000), end: at(1, 1_500), firstSentence: at(0, 1_000), lastSentence: at(1, 0),
                          nextSentenceEnd: at(1, 3_000))
    }

    @Test func positionsAreInTheOrderTheyAreHeard() {
        #expect(at(0, 9_000) < at(1, 0))
        #expect(at(1, 10) > at(1, 9))
        #expect([at(1, 0), at(0, 5), at(0, 1)].sorted() == [at(0, 1), at(0, 5), at(1, 0)])
    }

    @Test func thePageStaysWhileItShowsTheVoice() {
        #expect(ReadAlongPageFollow.move(at(0, 3_000), on: page) == .stay)
        #expect(ReadAlongPageFollow.move(at(1, 1_499), on: page) == .stay)
    }

    @Test func theVoiceCrossingThePagesEndTurnsItEvenInsideASentence() {
        #expect(ReadAlongPageFollow.move(at(1, 1_500), on: page) == .forward)
        #expect(ReadAlongPageFollow.move(at(1, 2_999), on: page) == .forward)
        // Past the sentence the next page begins with: the page goes to the voice's sentence.
        #expect(ReadAlongPageFollow.move(at(1, 3_000), on: page) == .go)
    }

    @Test func theVoiceBeforeThePageIsAPageBackOnlyInsideItsFirstSentence() {
        #expect(ReadAlongPageFollow.move(at(0, 2_999), on: page) == .backward)
        #expect(ReadAlongPageFollow.move(at(0, 1_000), on: page) == .backward)
        #expect(ReadAlongPageFollow.move(at(0, 999), on: page) == .go)
        #expect(ReadAlongPageFollow.move(at(0, 0), on: nil) == .go, "a page without narration goes to the voice")
    }

    // MARK: The hand turns the page

    @Test func aPageTurnedByHandThatShowsTheVoiceChangesNothing() {
        #expect(ReadAlongPageFollow.handMoved(at(0, 4_000), speaking: at(0, 1_000), on: page) == .stay(turnAt: at(1, 1_500)))
    }

    @Test func turnedALittleEarlyOntoThePageTheSentenceGoesOnToNothingRestarts() {
        // The voice is still in s1's words on the page before.
        #expect(ReadAlongPageFollow.handMoved(at(0, 2_000), speaking: at(0, 1_000), on: page) == .stay(turnAt: at(1, 1_500)))
    }

    @Test func turnedBackOntoASentenceTheVoiceHasReadPastThePageWaitsForTheNextSentence() {
        #expect(ReadAlongPageFollow.handMoved(at(1, 2_000), speaking: at(1, 0), on: page) == .stay(turnAt: nil))
    }

    @Test func anyOtherPageTakesTheVoiceToItsFirstWord() {
        #expect(ReadAlongPageFollow.handMoved(at(0, 500), speaking: at(0, 0), on: page) == .seek(at(0, 3_000)))
        #expect(ReadAlongPageFollow.handMoved(at(1, 3_500), speaking: at(1, 3_000), on: page) == .seek(at(0, 3_000)))
        // In a pause between two sentences.
        #expect(ReadAlongPageFollow.handMoved(at(1, 9_000), speaking: nil, on: page) == .seek(at(0, 3_000)))
        #expect(ReadAlongPageFollow.handMoved(at(0, 0), speaking: at(0, 0), on: nil) == .unnarrated)
    }

    // MARK: The sentence for the voice

    @Test func theSentenceThePageGoesToIsTheOneSpokenOrTheNextToBe() throws {
        #expect(timeline.sentence(atOrAfter: at(0, 500))?.fragment == "s0")
        // After s2, in the silence at the end of the first file: t0 is next.
        #expect(timeline.sentence(atOrAfter: at(0, 7_500))?.fragment == "t0")
        #expect(timeline.sentence(atOrAfter: at(1, 9_000))?.fragment == "t1", "past the end: the last")
        let t0 = try #require(timeline.tracks[1].segments.first)
        #expect(timeline.begin(of: t0) == at(1, 0))
        #expect(timeline.begin(of: ReadAlongSegment(textHref: "x", fragment: "y", audioHref: "", beginMs: 0, endMs: 1)) == nil)
    }

    // MARK: The demo's edition

    @Test func theDemosSentencesArePlacedInsideTheirFiveSecondClips() throws {
        let demo = try ReadAlongPackage.read(DemoReadAlong.slimEdition(), requireAudio: false)
        let sentences = DemoReadAlong.sentences(0)
        let second = sentences[1]
        // A page from the middle of the second sentence to the middle of the third.
        let text = second.text
        let word = text.utf16.count / 2
        let third = sentences[2].text
        let edges = ReadAlongPageEdges(first: ReadAlongCaret(fragment: second.id, text: text, offset: word),
                                       last: ReadAlongCaret(fragment: sentences[2].id, text: third, offset: third.utf16.count / 2))
        let span = try #require(demo.span(href: "OEBPS/chapter-01.xhtml", edges: edges))
        // Inside the clips: the second sentence is 11 to 16 s into the file, the third 16 to 21 s, and the stretch starts at 6 s.
        #expect(span.start > at(0, 5_000) && span.start < at(0, 10_000), "\(span.start)")
        let end = try #require(span.end)
        #expect(end > at(0, 10_000) && end < at(0, 15_000), "\(end)")
        #expect(span.firstSentence == at(0, 5_000) && span.lastSentence == at(0, 10_000))
    }
}
