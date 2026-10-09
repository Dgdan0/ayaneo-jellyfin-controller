import Compression
import Foundation
import Testing
@testable import HubKit

/// Read along's rules (#25 phase 4, Android's #16 and #19): the timeline and
/// its sentence steps, what the dock and the page say, the place as a text
/// locator, and the session that keeps it while a book opens. Android's
/// `ReadAlongFollowTest`, `ReadAlongDockTest`, `ReadAlongLocationTest` and
/// `ReadAlongSessionTest`, case for case.
struct ReadAlongTests {
    // Two audio files: the first narrates chapter one from 5 s in, the second chapter two.
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

    private func at(_ track: Int, _ offsetMs: Int64) -> ReadAlongPosition { ReadAlongPosition(track: track, offsetMs: offsetMs) }

    // MARK: The sentence steps

    @Test func r1GoesToTheNextSentenceAcrossFiles() {
        // In s1 (6.5 s into the file, 1.5 s into the stretch).
        #expect(timeline.step(at(0, 1_500), delta: 1) == at(0, 5_000))
        // From the last sentence of the first file to the first of the second.
        #expect(timeline.step(at(0, 6_000), delta: 1) == at(1, 0))
        #expect(timeline.step(at(1, 3_500), delta: 1) == nil)
    }

    @Test func l1GoesBackASentenceOrToThisOnesStartWhenWellIntoIt() {
        // Half a second into s1: s0.
        #expect(timeline.step(at(0, 1_500), delta: -1) == at(0, 0))
        // Three seconds into s1: s1's own start.
        #expect(timeline.step(at(0, 4_000), delta: -1) == at(0, 1_000))
        // From the second file's first sentence back to the first file's last.
        #expect(timeline.step(at(1, 500), delta: -1) == at(0, 5_000))
        #expect(timeline.step(at(0, 200), delta: -1) == nil)
        #expect(ReadAlongTimeline.restartMs == 1_500)
    }

    @Test func whichPagesAreNarratedAndWhatThePageSays() {
        #expect(timeline.narrates("two.xhtml"))
        #expect(!timeline.narrates("front.xhtml"))
        #expect(ReadAlongFollow.label(following: true, narrated: true) == "Following")
        #expect(ReadAlongFollow.label(following: false, narrated: true) == "Reading")
        #expect(ReadAlongFollow.label(following: true, narrated: false) == "Alignment unavailable")
        #expect(ReadAlongDockText.heading(follow: "Following") == "Read along · Following")
        #expect(ReadAlongDockText.heading(follow: "") == "Read along")
    }

    // MARK: The sentence playing

    @Test func theSentenceSpokenIsNeverTheWrongOneInAPause() {
        let paused = ReadAlongTimeline(tracks: [ReadAlongTrack(audioHref: "a.mp3", segments: [
            ReadAlongSegment(textHref: "c.xhtml", fragment: "s1", audioHref: "a.mp3", beginMs: 1_000, endMs: 2_000),
            ReadAlongSegment(textHref: "c.xhtml", fragment: "s2", audioHref: "a.mp3", beginMs: 3_000, endMs: 4_000),
        ])])
        #expect(paused.active(track: 0, offsetMs: 0)?.fragment == "s1")
        #expect(paused.active(track: 0, offsetMs: 1_500) == nil, "a pause lights no sentence")
        #expect(paused.active(track: 0, offsetMs: 2_500)?.fragment == "s2")
        #expect(paused.active(track: 0, offsetMs: 3_000) == nil)
        #expect(paused.active(track: 1, offsetMs: 0) == nil)
        #expect(paused.find(href: "c.xhtml", fragment: "s2") == at(0, 2_000))
        #expect(paused.find(href: "c.xhtml", fragment: "s9") == nil)
    }

    @Test func aWordTimelineIsSearchedNotScanned() {
        // A hundred thousand words, each a millisecond, a millisecond apart.
        let words = (0..<100_000).map { index in
            ReadAlongSegment(textHref: "chapter.xhtml", fragment: "word-\(index)", audioHref: "audio.mp3",
                             beginMs: Int64(index) * 2, endMs: Int64(index) * 2 + 1)
        }
        let book = ReadAlongTimeline(tracks: [ReadAlongTrack(audioHref: "audio.mp3", segments: words)])
        let clock = ContinuousClock()
        let took = clock.measure {
            for _ in 0..<10_000 { _ = book.active(track: 0, offsetMs: 170_000) }
        }
        #expect(book.active(track: 0, offsetMs: 170_000)?.fragment == "word-85000")
        #expect(book.active(track: 0, offsetMs: 170_001) == nil)
        #expect(took < .seconds(1), "ten thousand lookups took \(took)")
    }

    // MARK: The dock

    private func parts(_ seconds: Int64...) -> ReadAlongTimeline {
        ReadAlongTimeline(tracks: seconds.enumerated().map { index, length in
            ReadAlongTrack(audioHref: "part\(index).mp4", segments: [
                ReadAlongSegment(textHref: "one.xhtml", fragment: "s\(index)", audioHref: "part\(index).mp4", beginMs: 0,
                                 endMs: length * 1_000),
            ])
        })
    }

    @Test func theTimeInThePartPlayingAndWhichPartWhenThereAreSeveral() {
        #expect(ReadAlongDockText.time(at(0, 253_000), parts(1_625)) == "4:13 of 27:05")
        #expect(ReadAlongDockText.time(at(1, 10_000), parts(30, 60, 90)) == "0:10 of 1:00 · part 2 of 3")
        // Past the end of a part never reads past its length.
        #expect(ReadAlongDockText.time(at(1, 99_000), parts(30, 60, 90)) == "1:00 of 1:00 · part 2 of 3")
        #expect(ReadAlongDockText.time(at(4, 0), parts(30)) == "0:00 of 0:00")
    }

    @Test func theLineShowsHowFarThroughThePart() {
        #expect(abs(ReadAlongDockText.fraction(at(0, 30_000), parts(60)) - 0.5) < 0.001)
        #expect(abs(ReadAlongDockText.fraction(at(0, 90_000), parts(60)) - 1) < 0.001)
        #expect(ReadAlongDockText.fraction(at(3, 0), parts(60)) == 0)
    }

    /// #52, the owner's notes and the Pocket's design (0.4.23): an opaque
    /// wash under the words, each row its own line box, no glow.
    @Test func theWashIsTheAccentInThePageAsFarAsTheInkKeepsItsContrast() {
        let gold: UInt32 = 0xE3B341
        // Paper and Sepia: 45% of the way to the gold, the ink still well clear.
        for theme in [EpubTheme.light, .sepia] {
            let (page, ink) = EpubPagePalette.of(theme)!
            let wash = ReadAlongGlow.wash(accent: gold, page: page, ink: ink)
            #expect(wash == GlassColors.mix(page, gold | 0xFF00_0000, 0.45), "\(theme)")
            #expect(GlassColors.contrast(ink, wash) >= 4.5, "\(theme)")
        }
        // Dim, Dark and Blue: less, as far as the light ink keeps 4.5:1.
        for theme in [EpubTheme.dark, .black, .blue] {
            let (page, ink) = EpubPagePalette.of(theme)!
            let wash = ReadAlongGlow.wash(accent: gold, page: page, ink: ink)
            #expect(GlassColors.contrast(ink, wash) >= 4.5, "\(theme)")
            #expect(wash != page, "\(theme) shows no wash")
            #expect(GlassColors.contrast(ink, GlassColors.mix(page, gold | 0xFF00_0000, 0.45)) < 4.5
                    || wash == GlassColors.mix(page, gold | 0xFF00_0000, 0.45), "\(theme)")
        }
        #expect(GlassColors.alpha(ReadAlongGlow.wash(accent: gold, page: 0xFF000000, ink: 0xFFAFAFAF)) == 0xFF, "opaque")
        // A page where no share keeps the contrast: held at its weakest, lowered 0.02 at a time while over 0.06 (#66).
        var weakest = 0.45
        while weakest > 0.06 { weakest -= 0.02 }
        #expect(ReadAlongGlow.wash(accent: 0xFFFFFF, page: 0xFF808080, ink: 0xFF909090)
                == GlassColors.mix(0xFF808080, 0xFFFFFFFF, weakest))
    }

    @Test func theBoxesAreUnderTheWordsInTheWashAndNothingElse() {
        let gold: UInt32 = 0xE3B341
        #expect(ReadAlongGlow.element(tint: gold) == #"<div class="pocket-narration"></div>"#)
        let sheet = ReadAlongGlow.stylesheet(tint: gold)
        #expect(sheet == #"div[data-style="pocket-narration"] > div.pocket-narration { z-index: -2 !important; "#
                + "background-color: var(--pocket-narration-wash, transparent) !important; }")
        // The word spoken's a layer above it (#66).
        #expect(ReadAlongGlow.wordStylesheet() == #"div[data-style="pocket-word"] > div.pocket-word { z-index: -1 !important; "#
                + "background-color: var(--pocket-word-wash, transparent) !important; }")
        // No translucent layer over the text, no group opacity, no glow, no ring.
        for word in ["opacity", "rgba", "box-shadow", "filter", "border:", "blend"] { #expect(!sheet.contains(word), "\(word)") }
    }

    @Test func theFittingScriptSetsTheWashAndFitsEachRowToItsLine() {
        let script = ReadAlongPageScript.fitNarration(wash: 0xFFF5E0B4)
        #expect(script.contains("v:'--pocket-narration-wash',c:'rgb(245, 224, 180)'"))
        // A row is as tall as its words; the gap to the next row is a join as wide as the two rows share.
        #expect(script.contains("b.style.top=r.t+'px';b.style.height=(r.b-r.t)+'px';"))
        #expect(script.contains("jl=Math.max(r.l,down.l),jr=Math.min(r.r,down.r);"))
        // A row with another sentence's words above or below stops short of their line's words.
        #expect(script.contains("r.t=(tl||tr)?Math.max(r.t+a,r.c-p+r.h/2+1):r.t;r.b=(bl||br)?Math.min(r.b-a,r.c+p-r.h/2-1):r.b;"))
        #expect(script.contains("var a=0.15*(parseFloat(getComputedStyle(item).fontSize)||16);"))
        // Its own joins do not set it off again.
        #expect(script.contains("if(!(node.dataset&&node.dataset.join)){fit();return;}"))
        // The wash is set as each style's own variable, from the fits kept in the page (#66).
        #expect(script.contains("{s:\"pocket-narration\",v:'--pocket-narration-wash',c:'rgb(245, 224, 180)'"))
        #expect(script.contains("fits.forEach(function(f){document.documentElement.style.setProperty(f.v,f.c);});"))
        // It stays in the page and fits again when Readium lays the boxes out again.
        #expect(script.contains("new MutationObserver") && script.contains("window.__pocketNarration=fit;"))
        // Corners round only on the outside of the shape.
        #expect(script.contains("var tl=!up||up.l>r.l+1,tr=!up||up.r<r.r-1,bl=!down||down.l>r.l+1,br=!down||down.r<r.r-1;"))
    }

    /// #56: Storyteller's element for a sentence holds the space after it, and
    /// can hold one before it; the wash covers the sentence's words alone.
    @Test func eachRowIsTrimmedToTheSentencesWordsWithoutTheSpacesAround() {
        let script = ReadAlongPageScript.fitNarration(wash: 0xFFF5E0B4, fragment: #"one-s2"x"#)
        // The sentence's element, named safely, kept for the fits Readium's relayouts set off.
        #expect(script.contains(#"id:"one-s2\"x",until:"""#) && script.contains("window.__pocketFits=fits;"))
        // A Range from its first to its last character that is not a space, its text walked in order.
        #expect(script.contains("createTreeWalker(el,NodeFilter.SHOW_TEXT,null)"))
        #expect(script.contains(#"if(/\S/.test(s.charAt(i))){if(!a)a=[n,i];z=[n,i+1];}"#))
        #expect(script.contains("g.setStart(a[0],a[1]);if(stop)g.setEndBefore(stop);else g.setEnd(z[0],z[1]);"))
        // A row takes that Range's extent on its line; a row of only the space gets no box.
        #expect(script.contains("if(!on.length){r.boxes.forEach(function(b){b.style.display='none';});return false;}"))
        #expect(script.contains("r.l=Math.min.apply(null,on.map(function(q){return q.l;}));r.r=Math.max.apply(null,on.map(function(q){return q.r;}));"))
        // The air beyond the words stays, applied after the trim.
        let trim = try! #require(script.range(of: "if(own){"))
        let air = try! #require(script.range(of: "rows.forEach(function(r){r.l-=2;r.r+=2;});"))
        #expect(trim.lowerBound < air.lowerBound)
        // With no element named, Readium's own extents stand.
        #expect(ReadAlongPageScript.fitNarration(wash: 0xFFF5E0B4).contains(#"id:"",until:"""#))
    }

    // MARK: The place

    private let voice = ReadAlongTimeline(tracks: [ReadAlongTrack(audioHref: "voice.mp3", segments: [
        ReadAlongSegment(textHref: "book/ch1.xhtml", fragment: "s1", audioHref: "voice.mp3", beginMs: 1_000, endMs: 3_000),
        ReadAlongSegment(textHref: "book/ch1.xhtml", fragment: "s2", audioHref: "voice.mp3", beginMs: 3_000, endMs: 5_000),
    ])])
    private let locator = ##"{"href":"old.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.2,"cssSelector":"#old"},"text":{"highlight":"old sentence"}}"##

    private func object(_ json: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
    }

    /// #19: the place is the sentence, a text locator any reader understands; the hub maps it to the audio.
    @Test func theSentenceIsThePlaceWithoutStaleSelectorsOrAPrivateOffset() {
        let saved = ReadAlongLocation.save(locator, voice, point: at(0, 2_500), completed: false)
        let fields = object(saved)
        #expect(fields["href"] as? String == "book/ch1.xhtml")
        let locations = fields["locations"] as? [String: Any] ?? [:]
        #expect(locations["fragments"] as? [String] == ["s2"])
        #expect(locations["cssSelector"] == nil)
        #expect(fields["text"] == nil)
        #expect(locations["pocketdsAudio"] == nil, "No private offset is written")
        #expect(fields["type"] as? String == "application/xhtml+xml", "the rest of the locator is kept")
        // It resumes at the sentence's start.
        #expect(ReadAlongLocation.resume(saved, voice) == at(0, 2_000))
    }

    @Test func anOldPrivateOffsetIsDroppedAndNotTrusted() {
        let old = #"{"href":"book/ch1.xhtml","locations":{"fragments":["s2"],"pocketdsAudio":{"track":0,"offsetMs":3900}}}"#
        #expect(ReadAlongLocation.resume(old, voice) == at(0, 2_000), "The sentence decides")
        let saved = ReadAlongLocation.save(old, voice, point: at(0, 500), completed: false)
        #expect((object(saved)["locations"] as? [String: Any])?["pocketdsAudio"] == nil)
        // Half a second into the narration is the first sentence.
        #expect((object(saved)["locations"] as? [String: Any])?["fragments"] as? [String] == ["s1"])
    }

    @Test func completionAndTextOnlyFallbackAreExplicit() {
        let saved = ReadAlongLocation.save(locator, voice, point: at(0, 4_000), completed: true)
        #expect((object(saved)["locations"] as? [String: Any])?["totalProgression"] as? Double == 1.0)
        let textOnly = #"{"href":"book/ch1.xhtml","locations":{"fragments":["s2"]}}"#
        #expect(ReadAlongLocation.resume(textOnly, voice) == at(0, 2_000))
        #expect(ReadAlongLocation.resume(locator, voice) == nil)
        // A point the narration does not hold leaves the place as it was.
        #expect(ReadAlongLocation.save(locator, voice, point: at(3, 0), completed: false) == locator)
    }

    // MARK: The page

    @Test func listeningFromThisPageLooksForItsNarratedSentencesInOrderAndOnce() {
        #expect(timeline.fragments(in: "one.xhtml") == ["s0", "s1", "s2"])
        #expect(timeline.fragments(in: "two.xhtml") == ["t0", "t1"])
        #expect(timeline.fragments(in: "front.xhtml").isEmpty)
    }

    @Test func thePagesScriptsTakeTheIdsAsJsonSoNoneBreaksOut() {
        let visible = ReadAlongPageScript.visible("s1")
        #expect(visible.contains(#"document.getElementById("s1")"#))
        #expect(visible.hasPrefix("(function(){") && visible.hasSuffix("})()"))
        // A quote or a backslash in an id stays inside its string.
        #expect(ReadAlongPageScript.visible(#"a"b\c"#).contains(#"getElementById("a\"b\\c")"#))
        let first = ReadAlongPageScript.firstVisible(["s1", "s2"])
        #expect(first.contains(#"var ids=["s1","s2"];"#))
        #expect(first.contains("return null;"))
        #expect(ReadAlongPageScript.firstVisible([]).contains("var ids=[];"))
    }

    @Test func readingAlongTheTimeLeftIsWhatTheNarrationHasLeftToSayAtItsSpeed() throws {
        func seg(_ href: String, _ begin: Int64, _ end: Int64) -> ReadAlongSegment {
            ReadAlongSegment(textHref: href, fragment: "s\(begin)", audioHref: "a.mp3", beginMs: begin, endMs: end)
        }
        let book = ReadAlongTimeline(tracks: [
            ReadAlongTrack(audioHref: "a.mp3", segments: [seg("one.xhtml", 0, 60_000), seg("one.xhtml", 60_000, 120_000),
                                                         seg("two.xhtml", 120_000, 300_000)]),
            ReadAlongTrack(audioHref: "b.mp3", segments: [ReadAlongSegment(textHref: "two.xhtml", fragment: "t", audioHref: "b.mp3",
                                                                           beginMs: 0, endMs: 600_000)]),
        ])
        // 30 s into the first sentence of chapter one, at normal speed.
        let left = try #require(TimeLeft.ofNarration(book, at(0, 30_000), speed: 1))
        #expect(left.chapterMs == 90_000)
        #expect(left.bookMs == 870_000)
        // Twice as fast, half the time.
        #expect(TimeLeft.ofNarration(book, at(0, 30_000), speed: 2)?.chapterMs == 45_000)
        // In chapter two, which runs on into the second part.
        #expect(TimeLeft.ofNarration(book, at(0, 120_000), speed: 1)?.chapterMs == 780_000)
        #expect(TimeLeft.ofNarration(book, at(5, 0), speed: 1) == nil)
    }

    // MARK: The session

    @Test func reopeningKeepsTheNarrationsPlaceAcrossReadiumsFirstPageCallbacks() {
        var state = ReadAlongSession()
        state.beginOpen()
        #expect(!state.canSavePage())
        state.ready(at(0, 10_000))
        #expect(state.canSavePage())
        #expect(state.pointForSave(nil) == at(0, 10_000))
        state.record(at(0, 20_000))
        #expect(state.pointForSave(nil) == at(0, 20_000))
        state.switchToText()
        #expect(state.pointForSave(nil) == nil)
    }

    @Test func aNewOpenWithoutANarrationPlaceClearsTheOldOne() {
        var state = ReadAlongSession()
        state.ready(at(3, 42_000))
        state.beginOpen()
        #expect(!state.canSavePage())
        state.ready(nil)
        #expect(state.pointForSave(nil) == nil)
        #expect(state.canSavePage())
    }

    @Test func aPlayerGoingAwayCannotReplaceTheNarrationsPlaceWithAPageOfText() {
        var state = ReadAlongSession()
        state.ready(nil)
        state.record(at(0, 10_000))
        #expect(state.canSavePage(narrationAvailable: true))
        #expect(!state.canSavePage(narrationAvailable: false))
        #expect(state.pointForSave(nil) == at(0, 10_000))
        state.switchToText()
        #expect(state.canSavePage(narrationAvailable: false))
    }
}

/// Read along streamed (#19): which edition to open, and where each stretch
/// of narration is heard. Android's `ReadAlongStreamTest`.
struct ReadAlongStreamTests {
    private let tracks = [
        ReadingAudioTrack(index: 0, id: "t_aaaaaaaaaaaa", title: "Track 01", durationMs: 4_610_652, bytes: 36_942_522,
                          mime: "audio/mpeg", etag: "\"e1\""),
        ReadingAudioTrack(index: 1, id: "t_bbbbbbbbbbbb", title: "Track 02", durationMs: 7_200_000, bytes: 57_600_000,
                          mime: "audio/mpeg", etag: "\"e2\""),
    ]

    // Dark Matter's shape: the edition's files named in its own order, the second track cut in two chunks.
    private var manifest: ReadingAudioManifest {
        ReadingAudioManifest(revision: "05c8b6c63e2b", aligned: true, tracks: tracks,
                             alignment: ReadingAudioAlignment(audio: [
                                 ReadingAlignedAudio(href: "OEBPS/Audio/00001-00001.mp3", track: 1, startMs: 0),
                                 ReadingAlignedAudio(href: "OEBPS/Audio/00001-00002.mp3", track: 1, startMs: 3_600_000),
                                 ReadingAlignedAudio(href: "OEBPS/Audio/00002-00001.mp3", track: 0, startMs: 0)]))
    }

    private func url(_ index: Int) -> String { "https://hub/audio/tracks/\(index)?rev=05c8b6c63e2b" }

    private func stretch(_ href: String) -> ReadAlongTrack {
        ReadAlongTrack(audioHref: href, segments: [
            ReadAlongSegment(textHref: "OEBPS/text/one.xhtml", fragment: "s1", audioHref: href, beginMs: 2_000, endMs: 5_000)])
    }

    @Test func eachStretchIsHeardFromTheTrackItsFileWasMappedToWhereThatFileBegins() throws {
        let timeline = ReadAlongTimeline(tracks: [stretch("OEBPS/Audio/00001-00001.mp3"), stretch("OEBPS/Audio/00001-00002.mp3"),
                                                  stretch("/OEBPS/Audio/00002-00001.mp3")])
        let sources = try ReadAlongStream.sources(timeline, manifest: manifest, sourceItemId: "3726292328809367", url: url)
        #expect(sources.map(\.url) == [url(1), url(1), url(0)])
        // The second chunk begins an hour into the track: its sentences are clipped from there.
        #expect(sources.map(\.startMs) == [0, 3_600_000, 0])
        // The bytes are the audiobook's own, kept under the same key as when it is listened to.
        #expect(sources[0].cacheKey == AudiobookStream.cacheKey(sourceItemId: "3726292328809367", track: tracks[1]))
        // Each is heard from its first sentence to its last one's end.
        let clip = sources[1].clip(timeline.tracks[1])
        #expect(clip.fromMs == 3_602_000 && clip.toMs == 3_605_000)
    }

    private func sentence(_ href: String, _ id: String, _ begin: Int64, _ end: Int64) -> ReadAlongSegment {
        ReadAlongSegment(textHref: "OEBPS/text/one.xhtml", fragment: id, audioHref: href, beginMs: begin, endMs: end)
    }

    /// A sentence past its file's audio skips itself, not the edition: a file's
    /// audio is its window of the track, up to the next file mapped there or
    /// the track's end. One that runs past the window ends there, so the voice
    /// never reads on into the next file's words.
    @Test func aSentencePastItsFilesAudioIsSkippedAndOneRunningPastItEndsThere() throws {
        let first = "OEBPS/Audio/00001-00001.mp3", second = "OEBPS/Audio/00001-00002.mp3", third = "/OEBPS/Audio/00002-00001.mp3"
        let timeline = ReadAlongTimeline(tracks: [
            // The first file's window is the first hour of track 1, up to where the second file begins.
            ReadAlongTrack(audioHref: first, segments: [sentence(first, "a", 2_000, 5_000), sentence(first, "b", 3_599_000, 3_601_000),
                                                        sentence(first, "c", 3_600_000, 3_602_000), sentence(first, "d", 3_700_000, 3_701_000)]),
            // The second file's window is the rest of track 1: an hour.
            ReadAlongTrack(audioHref: second, segments: [sentence(second, "e", 1_000, 2_000)]),
            // Track 0 is 4,610,652 ms long: nothing of this stretch is in it.
            ReadAlongTrack(audioHref: third, segments: [sentence(third, "f", 4_610_652, 4_612_000), sentence(third, "g", 4_700_000, 4_701_000)]),
        ])
        let fitted = try #require(ReadAlongStream.fitted(timeline, manifest: manifest))
        #expect(fitted.tracks.map(\.audioHref) == [first, second], "a stretch left with no sentence goes")
        #expect(fitted.tracks[0].segments.map(\.fragment) == ["a", "b"])
        #expect(fitted.tracks[0].segments[1].endMs == 3_600_000, "it ends where the next file's audio begins")
        #expect(fitted.tracks[1] == timeline.tracks[1])
        // The fitted timeline still maps, each stretch onto its own file.
        let sources = try ReadAlongStream.sources(fitted, manifest: manifest, sourceItemId: "3726292328809367", url: url)
        #expect(sources.map(\.startMs) == [0, 3_600_000])
    }

    @Test func aFileWhoseAudioCannotBeMeasuredIsLeftAsItIs() {
        // Unmapped (`sources` refuses it), or on a track of unknown length with no file after it.
        let unmapped = ReadAlongTimeline(tracks: [ReadAlongTrack(audioHref: "OEBPS/Audio/00009-00001.mp3",
                                                                 segments: [sentence("OEBPS/Audio/00009-00001.mp3", "x", 9_000_000, 9_001_000)])])
        #expect(ReadAlongStream.fitted(unmapped, manifest: manifest) == unmapped)
        var unknown = manifest
        unknown.tracks[0].durationMs = 0
        let third = "OEBPS/Audio/00002-00001.mp3"
        let late = ReadAlongTimeline(tracks: [ReadAlongTrack(audioHref: third, segments: [sentence(third, "y", 9_000_000, 9_001_000)])])
        #expect(ReadAlongStream.fitted(late, manifest: unknown) == late)
    }

    @Test func anEditionWithNothingLeftToPlayHasNoNarration() {
        let third = "OEBPS/Audio/00002-00001.mp3"
        let past = ReadAlongTimeline(tracks: [ReadAlongTrack(audioHref: third, segments: [sentence(third, "z", 5_000_000, 5_001_000)])])
        #expect(ReadAlongStream.fitted(past, manifest: manifest) == nil)
    }

    @Test func theDemosEditionFitsItsTracksAsItIs() throws {
        let timeline = try ReadAlongPackage.read(DemoReadAlong.slimEdition(), requireAudio: false)
        let data = try JSONSerialization.data(withJSONObject: DemoReading.manifestFields(DemoReading.audiobooks[0]))
        let manifest = try JSONDecoder().decode(ReadingAudioManifest.self, from: data)
        #expect(ReadAlongStream.fitted(timeline, manifest: manifest) == timeline)
    }

    @Test func aFileTheHubDidNotMapIsNoNarrationAtAllNeverAnotherFiles() {
        let timeline = ReadAlongTimeline(tracks: [stretch("OEBPS/Audio/00009-00001.mp3")])
        #expect(throws: ReadAlongError.self) {
            try ReadAlongStream.sources(timeline, manifest: manifest, sourceItemId: "3726292328809367", url: url)
        }
        var nowhere = manifest
        nowhere.alignment = ReadingAudioAlignment(audio: [ReadingAlignedAudio(href: "OEBPS/Audio/00009-00001.mp3", track: 7)])
        #expect(throws: ReadAlongError.self) {
            try ReadAlongStream.sources(timeline, manifest: nowhere, sourceItemId: "3726292328809367", url: url)
        }
    }

    @Test func theStreamWhenTheHubMappedTheAudioTheWholeEditionWhenItCannotWhatIsHereWhenItCannotBeAsked() {
        #expect(ReadAlongStream.plan(.success(manifest)) == .stream(manifest))
        // An edition the hub could not map (lengths that do not match, Mistborn's while it was building).
        var unmapped = manifest
        unmapped.aligned = false
        unmapped.alignment = nil
        unmapped.alignmentReason = "lengths_do_not_match"
        #expect(ReadAlongStream.plan(.success(unmapped)) == .whole)
        var empty = manifest
        empty.alignment = ReadingAudioAlignment(audio: [])
        #expect(ReadAlongStream.plan(.success(empty)) == .whole)
        #expect(ReadAlongStream.plan(.failure(HubFailure(.badResponse, code: "audio_not_streamable", reason: "unmapped_root")))
                == .whole)
        #expect(ReadAlongStream.plan(.failure(HubFailure(.notFound))) == .whole)
        let offline = HubFailure(.noNetwork)
        #expect(ReadAlongStream.plan(.failure(offline)) == .unreachable(offline))
    }
}

/// How the narration is played (#19): stretches of one source heard one
/// after another are one run, played without a stop; a jump crosses stretches.
struct NarrationRunsTests {
    private func stretch(_ href: String, _ beginMs: Int64, _ endMs: Int64) -> ReadAlongTrack {
        ReadAlongTrack(audioHref: href, segments: [
            ReadAlongSegment(textHref: "c.xhtml", fragment: href, audioHref: href, beginMs: beginMs, endMs: endMs)])
    }

    @Test func stretchesOfOneSourceHeardOneAfterAnotherAreOneRun() {
        let timeline = ReadAlongTimeline(tracks: [stretch("a.mp3", 1_000, 5_000), stretch("b.mp3", 0, 4_000),
                                                  stretch("c.mp3", 0, 3_000), stretch("d.mp3", 0, 2_000),
                                                  stretch("e.mp3", 0, 2_000)])
        // a and b half a second apart in one track; c ten seconds on; d back before them; e another track.
        let sources = [NarrationSource(url: "t0"), NarrationSource(url: "t0", startMs: 5_500),
                       NarrationSource(url: "t0", startMs: 20_000), NarrationSource(url: "t0", startMs: 1_000),
                       NarrationSource(url: "t1")]
        let runs = NarrationRuns(timeline, sources: sources)
        #expect(runs.runs.map(\.stretches) == [0..<2, 2..<3, 3..<4, 4..<5])
        #expect(runs.runs.map(\.fromMs) == [1_000, 20_000, 1_000, 0])
        #expect(runs.runs.map(\.toMs) == [9_500, 23_000, 3_000, 2_000])
        #expect(runs.run(of: 1) == 0 && runs.run(of: 4) == 3 && runs.run(of: 9) == nil)

        // A place and the moment of its source, both ways.
        let heard = runs.time(of: ReadAlongPosition(track: 1, offsetMs: 500))
        #expect(heard?.run == 0 && heard?.ms == 6_000)
        #expect(runs.position(run: 0, ms: 6_000) == ReadAlongPosition(track: 1, offsetMs: 500))
        // In the pause between a and b, the end of a; before a, its start.
        #expect(runs.position(run: 0, ms: 5_200) == ReadAlongPosition(track: 0, offsetMs: 4_000))
        #expect(runs.position(run: 0, ms: 500) == ReadAlongPosition(track: 0, offsetMs: 0))
        // Past a stretch's end is its end.
        let end = runs.time(of: ReadAlongPosition(track: 2, offsetMs: 99_000))
        #expect(end?.run == 1 && end?.ms == 23_000)
    }

    @Test func theDemosEditionPlaysAsThreeRunsOneATrack() throws {
        let timeline = try ReadAlongPackage.read(DemoReadAlong.slimEdition(), requireAudio: false)
        let data = try JSONSerialization.data(withJSONObject: DemoReading.manifestFields(DemoReading.audiobooks[0]))
        let manifest = try JSONDecoder().decode(ReadingAudioManifest.self, from: data)
        let sources = try ReadAlongStream.sources(timeline, manifest: manifest, sourceItemId: DemoReadAlong.sourceItemId) { "track-\($0)" }
        let runs = NarrationRuns(timeline, sources: sources)
        // The third track's two files are heard as one.
        #expect(runs.runs.map(\.url) == ["track-0", "track-1", "track-2"])
        #expect(runs.runs.map(\.stretches) == [0..<1, 1..<2, 2..<4])
        #expect(runs.runs.map(\.fromMs) == [6_000, 0, 0])
        #expect(runs.runs.map(\.toMs) == [90_000, 75_000, 58_000])
        #expect(runs.position(run: 2, ms: 35_000) == ReadAlongPosition(track: 3, offsetMs: 5_000))
    }

    @Test func aJumpCrossesStretchesAndStopsAtEitherEnd() {
        let timeline = ReadAlongTimeline(tracks: [stretch("a.mp3", 0, 10_000), stretch("b.mp3", 0, 20_000)])
        #expect(timeline.jump(ReadAlongPosition(track: 0, offsetMs: 5_000), by: 10_000) == ReadAlongPosition(track: 1, offsetMs: 5_000))
        #expect(timeline.jump(ReadAlongPosition(track: 1, offsetMs: 2_000), by: -5_000) == ReadAlongPosition(track: 0, offsetMs: 7_000))
        #expect(timeline.jump(ReadAlongPosition(track: 1, offsetMs: 15_000), by: 60_000) == ReadAlongPosition(track: 1, offsetMs: 19_999))
        #expect(timeline.jump(ReadAlongPosition(track: 0, offsetMs: 1_000), by: -5_000) == ReadAlongPosition(track: 0, offsetMs: 0))
    }
}

/// An edition's media overlays read into the timeline (#16, #19), from EPUBs
/// made here, stored and deflated, and the demo hub's own. Android's
/// `ReadAlongPackageTest`.
struct ReadAlongPackageTests {
    private static let container = "<container><rootfiles><rootfile full-path='EPUB/package.opf'/></rootfiles></container>"
    private static let package = "<package><manifest><item id='c' href='chapter.xhtml' media-overlay='s'/><item id='s' href='overlays/one.smil'/></manifest><spine><itemref idref='c'/></spine></package>"

    /// A one-chapter edition: two sentences, the first clipped `begin` to `end`.
    private func book(audio: String = "../audio/voice.mp3", begin: String = "1s", end: String = "2s", doctype: String = "",
                      withAudio: Bool = true, withText: Bool = true, deflated: Bool = false) -> Data {
        var entries: [(name: String, data: Data)] = [
            ("META-INF/container.xml", Data(Self.container.utf8)),
            ("EPUB/package.opf", Data(Self.package.utf8)),
            ("EPUB/chapter.xhtml", Data("<html><body><p id='sentence1'>A test.</p><p id='sentence2'>Another.</p></body></html>".utf8)),
            ("EPUB/overlays/one.smil", Data("\(doctype)<smil><body><seq><par><text src='../chapter.xhtml#sentence1'/><audio src='\(audio)' clipBegin='\(begin)' clipEnd='\(end)'/></par><par><text src='../chapter.xhtml#sentence2'/><audio src='\(audio)' clipBegin='3s' clipEnd='4s'/></par></seq></body></smil>".utf8)),
            ("EPUB/audio/voice.mp3", Data("test audio".utf8)),
        ]
        if !withAudio { entries.removeAll { $0.name == "EPUB/audio/voice.mp3" } }
        if !withText { entries.removeAll { $0.name == "EPUB/chapter.xhtml" } }
        return deflated ? Self.deflatedZip(entries) : StoredZip.archive(entries)
    }

    @Test func spineOrderAndRelativeReferencesResolveToArchiveResources() throws {
        let timeline = try ReadAlongPackage.read(book())
        #expect(timeline.tracks.count == 1)
        #expect(timeline.tracks[0].segments[0].textHref == "EPUB/chapter.xhtml")
        #expect(timeline.tracks[0].segments[0].fragment == "sentence1")
        #expect(timeline.tracks[0].audioHref == "EPUB/audio/voice.mp3")
        #expect(timeline.tracks[0].startMs == 1_000)
        #expect(timeline.tracks[0].durationMs == 3_000)
        #expect(timeline.active(track: 0, offsetMs: 0)?.fragment == "sentence1")
        #expect(timeline.active(track: 0, offsetMs: 1_500) == nil, "a narration gap never lights the wrong sentence")
        #expect(timeline.active(track: 0, offsetMs: 2_500)?.fragment == "sentence2")
        #expect(timeline.active(track: 0, offsetMs: 3_000) == nil)
    }

    @Test func aDeflatedEditionReadsTheSame() throws {
        // The hub's slim edition copies each entry as it was, and most are deflated.
        #expect(try ReadAlongPackage.read(book(deflated: true)) == ReadAlongPackage.read(book()))
    }

    @Test func textSeekAndResumeUseTheExactFragmentAndStretchOffset() throws {
        let timeline = try ReadAlongPackage.read(book())
        #expect(timeline.find(href: "EPUB/chapter.xhtml", fragment: "sentence2") == ReadAlongPosition(track: 0, offsetMs: 2_000))
        #expect(timeline.find(href: "missing.xhtml", fragment: "sentence1") == nil)
        #expect(timeline.find(href: "EPUB/chapter.xhtml", fragment: "missing") == nil)
    }

    @Test func nestedWordOverlaysRemainIndividuallySeekable() throws {
        let edition = StoredZip.archive([
            ("META-INF/container.xml", Data(Self.container.utf8)),
            ("EPUB/package.opf", Data(Self.package.utf8)),
            ("EPUB/chapter.xhtml", Data("<html><body><p><span id='s1'><span id='s1-w0'>Hello</span> <span id='s1-w1'>world</span></span></p></body></html>".utf8)),
            ("EPUB/overlays/one.smil", Data("<smil><body><seq><seq id='s1'><par><text src='../chapter.xhtml#s1-w0'/><audio src='../audio/voice.mp3' clipBegin='0.1s' clipEnd='0.4s'/></par><par><text src='../chapter.xhtml#s1-w1'/><audio src='../audio/voice.mp3' clipBegin='0.5s' clipEnd='0.9s'/></par></seq></seq></body></smil>".utf8)),
            ("EPUB/audio/voice.mp3", Data("test audio".utf8)),
        ])
        let timeline = try ReadAlongPackage.read(edition)
        #expect(timeline.tracks.count == 1 && timeline.tracks[0].segments.count == 2)
        #expect(timeline.active(track: 0, offsetMs: 0)?.fragment == "s1-w0")
        #expect(timeline.active(track: 0, offsetMs: 350) == nil)
        #expect(timeline.active(track: 0, offsetMs: 450)?.fragment == "s1-w1")
        #expect(timeline.find(href: "EPUB/chapter.xhtml", fragment: "s1-w1") == ReadAlongPosition(track: 0, offsetMs: 400))
    }

    @Test func aWordOfNoLengthFromTheAlignerDoesNotDiscardTheBook() throws {
        let timeline = try ReadAlongPackage.read(book(begin: "1s", end: "1s"))
        #expect(timeline.tracks.count == 1)
        #expect(timeline.tracks[0].segments.map(\.fragment) == ["sentence2"])
    }

    /// A clip that ends before it begins (an older aligner's; the hub mends
    /// those it serves, but an edition kept from before has them) skips its
    /// one sentence; the edition reads on. Only a book of nothing but such
    /// clips has no narration.
    @Test func aClipEndingBeforeItBeginsSkipsItsSentenceNotTheEdition() throws {
        let timeline = try ReadAlongPackage.read(book(begin: "2s", end: "1s"))
        #expect(timeline.tracks.count == 1)
        #expect(timeline.tracks[0].segments.map(\.fragment) == ["sentence2"])
        #expect(timeline.tracks[0].startMs == 3_000 && timeline.tracks[0].durationMs == 1_000)
    }

    @Test func clockSyntaxTakesHoursMinutesMillisecondsAndNpt() throws {
        #expect(try ReadAlongPackage.clock("01:02:03.250") == 3_723_250)
        #expect(try ReadAlongPackage.clock("npt=1.25s") == 1_250)
        #expect(try ReadAlongPackage.clock("1250ms") == 1_250)
        #expect(try ReadAlongPackage.clock("2min") == 120_000)
        #expect(try ReadAlongPackage.clock("1h") == 3_600_000)
        #expect(try ReadAlongPackage.clock("52.000s") == 52_000)
        #expect(throws: ReadAlongError.self) { try ReadAlongPackage.clock("") }
        #expect(throws: ReadAlongError.self) { try ReadAlongPackage.clock("1:2:3:4") }
    }

    @Test func unsafeResourcesMissingFilesAndInvalidTimingAreRefused() {
        for audio in ["https://evil/audio.mp3", "../../../outside.mp3", "../audio/missing.mp3", "file:///secret.mp3",
                      "/EPUB/audio/voice.mp3", "../audio/voice.mp3?x=1", "..\\audio\\voice.mp3"] {
            #expect(throws: ReadAlongError.self, "\(audio)") { try ReadAlongPackage.read(book(audio: audio)) }
        }
        for (begin, end) in [("-1s", "2s"), ("NaN", "2s")] {
            #expect(throws: ReadAlongError.self, "\(begin) to \(end)") { try ReadAlongPackage.read(book(begin: begin, end: end)) }
        }
        #expect(throws: ReadAlongError.self) {
            try ReadAlongPackage.read(book(doctype: "<!DOCTYPE smil [<!ENTITY x SYSTEM 'file:///secret'>]>"))
        }
        #expect(throws: ReadAlongError.self) { try ReadAlongPackage.read(Data("not a zip".utf8)) }
    }

    /// #19: the hub's slim edition keeps its SMIL but not its audio, which streams from the tracks.
    @Test func theEditionWithoutItsAudioReadsWhenAskedToAndOnlyThen() throws {
        let slim = book(withAudio: false)
        #expect(throws: ReadAlongError.self, "The whole edition must hold its audio") { try ReadAlongPackage.read(slim) }
        let timeline = try ReadAlongPackage.read(slim, requireAudio: false)
        #expect(timeline.tracks.count == 1 && timeline.tracks[0].audioHref == "EPUB/audio/voice.mp3")
        #expect(timeline.tracks[0].segments.map(\.fragment) == ["sentence1", "sentence2"])
        // The words must still be there.
        #expect(throws: ReadAlongError.self) {
            try ReadAlongPackage.read(book(withAudio: false, withText: false), requireAudio: false)
        }
    }

    /// A reference resolved, as "path#fragment".
    private func resolved(_ base: String, _ relative: String) throws -> String {
        let found = try ReadAlongPackage.resolve(base, relative)
        return found.path + "#" + found.fragment
    }

    @Test func resolvingStaysInsideThePackage() throws {
        #expect(try resolved("", "OEBPS/content.opf") == "OEBPS/content.opf#")
        #expect(try resolved("OEBPS/Overlays/one.smil", "../Text/one.xhtml#one-s1") == "OEBPS/Text/one.xhtml#one-s1")
        #expect(try resolved("OEBPS/Text/one.xhtml", "#note%201") == "OEBPS/Text/one.xhtml#note 1")
        #expect(try resolved("OEBPS/content.opf", "Text/my%20file.xhtml") == "OEBPS/Text/my file.xhtml#")
        #expect(try resolved("OEBPS/content.opf", "./Text/./a.xhtml") == "OEBPS/Text/a.xhtml#")
        #expect(throws: ReadAlongError.self) { try ReadAlongPackage.resolve("a.opf", "../b.xhtml") }
        #expect(throws: ReadAlongError.self) { try ReadAlongPackage.resolve("OEBPS/a.opf", "//host/b.xhtml") }
        #expect(throws: ReadAlongError.self) { try ReadAlongPackage.resolve("OEBPS/a.opf", "  ") }
    }

    // MARK: The demo hub's edition

    @Test func theDemosSlimEditionIsDarkMattersNarrationInFourStretchesOnThreeTracks() throws {
        let timeline = try ReadAlongPackage.read(DemoReadAlong.slimEdition(), requireAudio: false)
        #expect(timeline.tracks.map(\.audioHref) == DemoReadAlong.files.map(\.href))
        #expect(timeline.tracks.map(\.startMs) == [6_000, 0, 0, 0])
        #expect(timeline.tracks.map(\.durationMs) == [84_000, 75_000, 30_000, 28_000])
        // Two's file holds spaces, brackets and an accented letter (#61): found as Readium spells it.
        let twoFile = BookHref.spelled("OEBPS/" + DemoEpub.chapterFile(1, identifier: DemoReadAlong.workId))
        #expect(timeline.narrates(twoFile))
        #expect(!timeline.narrates("OEBPS/about.xhtml"), "the title page has no narration")
        // Two runs on from the first file into the second.
        let two = try #require(timeline.find(href: twoFile, fragment: "two-s1"))
        #expect(two == ReadAlongPosition(track: 0, offsetMs: 46_000))
        #expect(timeline.find(href: twoFile, fragment: "two-s9") == ReadAlongPosition(track: 1, offsetMs: 0))

        // The demo hub's manifest maps each file onto a track: the last two share the third, half a minute apart.
        let data = try JSONSerialization.data(withJSONObject: DemoReading.manifestFields(DemoReading.audiobooks[0]))
        let manifest = try JSONDecoder().decode(ReadingAudioManifest.self, from: data)
        guard case .stream(let mapped) = ReadAlongStream.plan(.success(manifest)) else {
            Issue.record("the demo's Dark Matter does not stream")
            return
        }
        let sources = try ReadAlongStream.sources(timeline, manifest: mapped, sourceItemId: DemoReadAlong.sourceItemId) { "track-\($0)" }
        #expect(sources.map(\.url) == ["track-0", "track-1", "track-2", "track-2"])
        #expect(sources.map(\.startMs) == [0, 0, 0, 30_000])
        // The last stretch is heard from half a minute into the third track to two seconds before its end.
        let last = sources[3].clip(timeline.tracks[3])
        #expect(last.fromMs == 30_000 && last.toMs == 58_000)
    }

    // MARK: A deflated archive

    /// A ZIP whose entries are deflated, as most EPUBs' are.
    static func deflatedZip(_ entries: [(name: String, data: Data)]) -> Data {
        var out = Data()
        var central = Data()
        for entry in entries {
            let name = Data(entry.name.utf8)
            let raw = [UInt8](entry.data)
            var packed = [UInt8](repeating: 0, count: raw.count + 64)
            let count = packed.withUnsafeMutableBufferPointer { target in
                raw.withUnsafeBufferPointer { source in
                    compression_encode_buffer(target.baseAddress!, target.count, source.baseAddress!, source.count, nil,
                                              COMPRESSION_ZLIB)
                }
            }
            packed.removeLast(packed.count - count)
            let crc = ZipArchive.crc32(raw)
            let offset = UInt32(out.count)
            out.le32(0x0403_4B50)
            out.le16(20); out.le16(0); out.le16(8); out.le16(0); out.le16(0)
            out.le32(crc); out.le32(UInt32(packed.count)); out.le32(UInt32(raw.count))
            out.le16(UInt16(name.count)); out.le16(0)
            out.append(name)
            out.append(contentsOf: packed)
            central.le32(0x0201_4B50)
            central.le16(20); central.le16(20); central.le16(0); central.le16(8); central.le16(0); central.le16(0)
            central.le32(crc); central.le32(UInt32(packed.count)); central.le32(UInt32(raw.count))
            central.le16(UInt16(name.count)); central.le16(0); central.le16(0); central.le16(0); central.le16(0)
            central.le32(0); central.le32(offset)
            central.append(name)
        }
        let start = UInt32(out.count)
        out.append(central)
        out.le32(0x0605_4B50)
        out.le16(0); out.le16(0)
        out.le16(UInt16(entries.count)); out.le16(UInt16(entries.count))
        out.le32(UInt32(central.count)); out.le32(start)
        out.le16(0)
        return out
    }
}

private extension Data {
    mutating func le16(_ value: UInt16) {
        append(UInt8(value & 0xFF))
        append(UInt8(value >> 8))
    }

    mutating func le32(_ value: UInt32) {
        for shift in stride(from: UInt32(0), to: 32, by: 8) { append(UInt8((value >> shift) & 0xFF)) }
    }
}
