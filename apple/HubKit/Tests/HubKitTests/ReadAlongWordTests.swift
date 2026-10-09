import Foundation
import Testing
@testable import HubKit

/// Read along word by word (#66): the nested overlay, style A's bookkeeping,
/// and the highlight's colours. Android's `ReadAlongPackageTest` (nested
/// seq), `ReadAlongLocationTest` (a word saves its sentence) and the pure
/// highlight's tests.
struct ReadAlongWordTests {
    // MARK: The nested overlay

    private var sentenceLevel: ReadAlongTimeline { try! ReadAlongPackage.read(DemoReadAlong.slimEdition(), requireAudio: false) }
    private var wordLevel: ReadAlongTimeline { try! ReadAlongPackage.read(DemoReadAlong.slimWordEdition(), requireAudio: false) }

    @Test func aNestedSeqGivesEachSentenceItsWordsAndKeepsTheSentences() throws {
        let sentences = sentenceLevel
        let words = wordLevel
        #expect(!sentences.timesWords && words.timesWords)
        // The sentences are the sentence edition's, clip for clip: following, steps and places are unchanged.
        #expect(words.tracks.map(\.segments) == sentences.tracks.map(\.segments))
        let first = try #require(words.tracks.first?.segments.first)
        let said = DemoReadAlong.sentences(0)[0]
        #expect(first.fragment == said.id)
        let times = DemoReadAlong.wordTimes(said)
        #expect(words.words(of: first).map(\.fragment) == times.indices.map { "\(said.id)-w\($0)" })
        #expect(words.words(of: first).map(\.beginMs) == times.map(\.begin))
        #expect(words.words(of: first).last?.endMs == first.endMs, "the last word ends with its sentence")
        // Every word of every sentence is its sentence's (`sentenceFragment`), and its element exists in the page.
        let zip = try ZipArchive(DemoReadAlong.slimWordEdition())
        for track in words.tracks {
            for sentence in track.segments {
                let page = String(decoding: try zip.read(sentence.textHref, limit: 1 << 22), as: UTF8.self)
                for word in words.words(of: sentence) {
                    #expect(word.fragment.hasPrefix(sentence.fragment + "-w"), "\(word.fragment) is not \(sentence.fragment)'s")
                    #expect(page.contains(#"id="\#(word.fragment)""#), "no element \(word.fragment)")
                }
            }
        }
    }

    /// The hub's contract (#66, `claude/readalong-words`): the audio manifest
    /// says `wordLevel` when the book's pack has a word set (absent from an
    /// older hub, and false then), and only then is the edition asked for with
    /// `granularity=word`. The demo hub answers as the hub does.
    @Test func theWordSetIsAskedForWhereTheManifestSaysWordLevel() async throws {
        let older = try JSONDecoder().decode(ReadingAudioManifest.self, from: Data(#"{"workId":"w","aligned":true}"#.utf8))
        let words = try JSONDecoder().decode(ReadingAudioManifest.self,
                                             from: Data(#"{"workId":"w","aligned":true,"wordLevel":true}"#.utf8))
        #expect(!older.wordLevel && words.wordLevel)
        let slim = "/file?format=readaloud&audio=omit"
        #expect(HubEndpoints.readingEpubFile(workId: "w", sourceItemId: "s", format: "readaloud", omitAudio: true).path.hasSuffix(slim))
        #expect(HubEndpoints.readingEpubFile(workId: "w", sourceItemId: "s", format: "readaloud", omitAudio: true, granularity: "word")
            .path.hasSuffix(slim + "&granularity=word"))
        let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                            screens: DemoTransport(), sleep: { _ in })
        let (work, source) = (DemoReadAlong.workId, DemoReadAlong.sourceItemId)
        let manifest = try await hub.fetch(HubEndpoints.readingAudioManifest(workId: work, sourceItemId: source),
                                           as: ReadingAudioManifest.self)
        #expect(manifest.aligned && manifest.wordLevel)
        let wordEdition = try await hub.data(HubEndpoints.readingEpubFile(workId: work, sourceItemId: source, format: "readaloud",
                                                                          omitAudio: true, granularity: "word"))
        let sentenceEdition = try await hub.data(HubEndpoints.readingEpubFile(workId: work, sourceItemId: source, format: "readaloud",
                                                                              omitAudio: true))
        #expect(try ReadAlongPackage.read(wordEdition, requireAudio: false).timesWords)
        #expect(try !ReadAlongPackage.read(sentenceEdition, requireAudio: false).timesWords)
    }

    @Test func aSentenceThatCouldNotBeCutIntoWordsStaysOneSentence() throws {
        let smil = """
            <?xml version="1.0" encoding="UTF-8"?>
            <smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0"><body>
            <seq id="c" epub:textref="../c.xhtml">
            <seq id="s0-seq" epub:textref="../c.xhtml#s0">
            <par id="s0-w0"><text src="../c.xhtml#s0-w0"/><audio src="../a.mp3" clipBegin="0s" clipEnd="0.4s"/></par>
            <par id="s0-w1"><text src="../c.xhtml#s0-w1"/><audio src="../a.mp3" clipBegin="0.45s" clipEnd="1s"/></par>
            </seq>
            <par id="s1"><text src="../c.xhtml#s1"/><audio src="../a.mp3" clipBegin="1.2s" clipEnd="3s"/></par>
            <seq id="s2-seq" epub:textref="../c.xhtml#s2">
            <par id="s2-w0"><text src="../c.xhtml#s2-w0"/><audio src="../a.mp3" clipBegin="3s" clipEnd="3.5s"/></par>
            </seq></seq></body></smil>
            """
        let edition = Self.edition(smil: smil, page: #"<p><span id="s0"><span id="s0-w0">One</span> <span id="s0-w1">two</span>.</span> "#
            + #"<span id="s1">Three!</span> <span id="s2"><span id="s2-w0">Four</span>.</span></p>"#)
        let timeline = try ReadAlongPackage.read(edition, requireAudio: false)
        let segments = timeline.tracks.flatMap(\.segments)
        #expect(segments.map(\.fragment) == ["s0", "s1", "s2"])
        #expect(segments.map(\.beginMs) == [0, 1_200, 3_000] && segments.map(\.endMs) == [1_000, 3_000, 3_500])
        #expect(timeline.words(of: segments[0]).map(\.fragment) == ["s0-w0", "s0-w1"])
        #expect(timeline.words(of: segments[1]).isEmpty, "s1 stays a sentence")
        #expect(timeline.words(of: segments[2]).map(\.fragment) == ["s2-w0"])
    }

    @Test func aWordEditionMayHoldMoreParsThanASentenceOne() {
        #expect(ReadAlongPackage.wordLimit >= 1_000_000 && ReadAlongPackage.segmentLimit == 200_000)
    }

    /// A one-document edition, its overlay `smil` and its page's paragraph `page`.
    static func edition(smil: String, page: String) -> Data {
        StoredZip.archive([
            ("mimetype", Data("application/epub+zip".utf8)),
            ("META-INF/container.xml", Data("""
                <?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/p.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
                """.utf8)),
            ("OEBPS/p.opf", Data("""
                <?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest>
                <item id="c" href="c.xhtml" media-type="application/xhtml+xml" media-overlay="o"/>
                <item id="o" href="o/c.smil" media-type="application/smil+xml"/></manifest><spine><itemref idref="c"/></spine></package>
                """.utf8)),
            ("OEBPS/c.xhtml", Data(#"<html xmlns="http://www.w3.org/1999/xhtml"><body>\#(page)</body></html>"#.utf8)),
            ("OEBPS/o/c.smil", Data(smil.utf8)),
        ])
    }

    // MARK: Style A

    private let timeline = ReadAlongTimeline(tracks: [ReadAlongTrack(audioHref: "a.mp3", segments: [
        ReadAlongSegment(textHref: "c.xhtml", fragment: "s0", audioHref: "a.mp3", beginMs: 1_000, endMs: 3_000),
        ReadAlongSegment(textHref: "c.xhtml", fragment: "s1", audioHref: "a.mp3", beginMs: 3_500, endMs: 5_000),
    ])], words: [
        "c.xhtml#s0": [ReadAlongWord(fragment: "s0-w0", beginMs: 1_000, endMs: 1_400),
                       ReadAlongWord(fragment: "s0-w1", beginMs: 1_500, endMs: 2_000),
                       ReadAlongWord(fragment: "s0-w2", beginMs: 2_200, endMs: 3_000)],
        "c.xhtml#s1": [ReadAlongWord(fragment: "s1-w0", beginMs: 3_500, endMs: 4_000),
                       ReadAlongWord(fragment: "s1-w1", beginMs: 4_000, endMs: 5_000)],
    ])

    private func at(_ ms: Int64, trail: Int = 40) -> ReadAlongMark? {
        ReadAlongMark.at(ReadAlongPosition(track: 0, offsetMs: ms - 1_000), in: timeline, trailPercent: trail)
    }

    @Test func theTrailGrowsThroughTheSentenceAndANewSentenceClearsIt() {
        #expect(at(1_100) == ReadAlongMark(sentence: timeline.tracks[0].segments[0], word: "s0-w0", trail: false), "the first word: no trail")
        #expect(at(1_600)?.word == "s0-w1" && at(1_600)?.trail == true, "the second: a trail before it")
        #expect(at(2_100)?.word == "s0-w1", "a breath between two words keeps the first lit")
        #expect(at(2_900)?.word == "s0-w2" && at(2_900)?.trail == true)
        #expect(at(3_200) == nil, "nothing between sentences")
        #expect(at(3_600)?.word == "s1-w0" && at(3_600)?.trail == false, "a new sentence clears the trail")
        #expect(at(4_500)?.word == "s1-w1" && at(4_500)?.trail == true)
    }

    @Test func aSeekLandsOnItsWordAndATrailAtNoneDrawsNothing() {
        // Stateless: wherever the voice is put, the mark is that moment's.
        for (ms, word) in [(2_950 as Int64, "s0-w2"), (1_000, "s0-w0"), (4_999, "s1-w1")] {
            #expect(at(ms)?.word == word, "\(ms)")
        }
        #expect(at(2_500, trail: 0)?.trail == false, "0% draws no trail")
        #expect(at(2_500, trail: 5)?.trail == true)
    }

    @Test func aSentenceLevelEditionMarksItsSentenceAlone() {
        let sentences = ReadAlongTimeline(tracks: timeline.tracks)
        let mark = ReadAlongMark.at(ReadAlongPosition(track: 0, offsetMs: 900), in: sentences, trailPercent: 40)
        #expect(mark == ReadAlongMark(sentence: sentences.tracks[0].segments[0], word: nil, trail: false))
    }

    @Test func aPlaceSavedMidWordIsItsSentence() {
        let page = #"{"href":"c.xhtml","type":"application/xhtml+xml","locations":{"progression":0.1}}"#
        let saved = ReadAlongLocation.save(page, timeline, point: ReadAlongPosition(track: 0, offsetMs: 1_100), completed: false)
        let object = (try? JSONSerialization.jsonObject(with: Data(saved.utf8))) as? [String: Any]
        #expect((object?["locations"] as? [String: Any])?["fragments"] as? [String] == ["s0"], "a sentence id, never a word's")
        #expect(ReadAlongLocation.resume(saved, timeline) == ReadAlongPosition(track: 0, offsetMs: 0))
    }

    // MARK: The colours

    private func palette(_ theme: EpubTheme) -> (page: UInt32, ink: UInt32) {
        let palette = EpubPagePalette.of(theme)!
        return (palette.page, palette.ink)
    }

    @Test func everyColourOnEveryPageKeepsTheInkReadableAtEveryTrail() {
        for theme in ReadAlongHighlightStyle.themes {
            let (page, ink) = palette(theme)
            for colour in ReadAlongHighlightStyle.Colour.allCases {
                for trail in [0, 40, 100] {
                    let style = ReadAlongHighlightStyle(colour: colour, trail: trail)
                    let word = style.word(theme, page: page, ink: ink)
                    #expect(GlassColors.contrast(ink | 0xFF00_0000, word) >= 4.5, "\(colour) word on \(theme)")
                    #expect(word != page | 0xFF00_0000, "\(colour) on \(theme): the word must show")
                    let trailWash = style.trail(theme, page: page, ink: ink)
                    if trail == 0 {
                        #expect(trailWash == nil, "no trail at 0%")
                    } else {
                        let washed = try! #require(trailWash)
                        #expect(GlassColors.contrast(ink | 0xFF00_0000, washed) >= 4.5, "\(colour) trail \(trail) on \(theme)")
                    }
                    if trail == 100 { #expect(trailWash == word, "at 100% the trail is as strong as the word") }
                    #expect(GlassColors.contrast(ink | 0xFF00_0000, style.sentence(page: page, ink: ink)) >= 4.5)
                }
            }
        }
    }

    @Test func theStrengthsAreTheApprovedOnes() {
        let gold = ReadAlongHighlightStyle(colour: .gold)
        #expect(ReadAlongHighlightStyle.wordStrength(.light) == 0.62 && ReadAlongHighlightStyle.wordStrength(.sepia) == 0.62)
        #expect(ReadAlongHighlightStyle.wordStrength(.dark) == 0.42 && ReadAlongHighlightStyle.wordStrength(.black) == 0.42)
        #expect(abs(gold.trailStrength(.light) - 0.248) < 1e-9, "40% of 0.62")
        #expect(abs(gold.trailStrength(.black) - 0.168) < 1e-9, "40% of 0.42")
        #expect(ReadAlongHighlightStyle(colour: .gold, trail: 0).trailStrength(.light) == 0)
        #expect(ReadAlongHighlightStyle(colour: .gold, trail: 100).trailStrength(.light) == 0.62)
        // The mix held to 4.5:1 steps down 0.02 at a time.
        let (page, ink) = palette(.light)
        let held = ReadAlongHighlightStyle.hold(0xFFF0_C96A, page: page, ink: ink, strength: 0.62)
        var share = 0.62
        while GlassColors.contrast(ink | 0xFF00_0000, GlassColors.mix(page | 0xFF00_0000, 0xFFF0_C96A, share)) < 4.5 { share -= 0.02 }
        #expect(held == GlassColors.mix(page | 0xFF00_0000, 0xFFF0_C96A, share))
        // The colours, as approved.
        #expect(ReadAlongHighlightStyle.Colour.allCases.map(\.rgb) == [0xFFF0_C96A, 0xFFDE_8C4C, 0xFFE9_8FA8, 0xFFB3_9DEB,
                                                                     0xFF7D_B7E8, 0xFF5C_C2B5, 0xFF9A_D47E, 0xFFBE_C4D6])
    }

    @Test func eachPageHasItsDefaultAndKeepsItsOwnChoice() throws {
        #expect(ReadAlongHighlightStyle.standard(for: .light) == ReadAlongHighlightStyle(colour: .gold, trail: 40))
        #expect(ReadAlongHighlightStyle.standard(for: .sepia).colour == .gold)
        #expect(ReadAlongHighlightStyle.standard(for: .dark).colour == .ember && ReadAlongHighlightStyle.standard(for: .black).colour == .ember)
        #expect(ReadAlongHighlightStyle.stepped(42) == 40 && ReadAlongHighlightStyle.stepped(103) == 100 && ReadAlongHighlightStyle.stepped(-3) == 0)
        let defaults = try #require(UserDefaults(suiteName: "highlight-\(UUID().uuidString)"))
        let store = ReadAlongHighlightStore(defaults: defaults)
        store.set(ReadAlongHighlightStyle(colour: .teal, trail: 65), for: .sepia)
        #expect(store.style(for: .sepia) == ReadAlongHighlightStyle(colour: .teal, trail: 65))
        #expect(store.style(for: .light) == .standard(for: .light), "another page keeps its own")
        #expect(!store.isStandard(.sepia))
        store.reset(.sepia)
        #expect(store.style(for: .sepia) == .standard(for: .sepia) && store.isStandard(.sepia), "Use the default resets both")
    }

    // MARK: The real thing

    /// The Final Empire (Dramatized) read word by word, from a private copy
    /// of the hub's word edition (never committed: copyrighted), where the
    /// Mac keeps it; nothing where it does not. Every par resolves, each
    /// word belongs to its sentence, and each word's element exists.
    @Test(.enabled(if: Self.privateWords != nil)) func everyWordOfTheFinalEmpireIsReadAlong() throws {
        let url = try #require(Self.privateWords)
        let data = try Data(contentsOf: url)
        let timeline = try ReadAlongPackage.read(data, requireAudio: false)
        let zip = try ZipArchive(data)
        var sentences = 0
        var words = 0
        var pages: [String: Set<String>] = [:]
        for track in timeline.tracks {
            for sentence in track.segments {
                sentences += 1
                let list = timeline.words(of: sentence)
                words += list.count
                if pages[sentence.textHref] == nil {
                    let page = String(decoding: try zip.read(sentence.textHref, limit: 1 << 23), as: UTF8.self)
                    let ids = page.components(separatedBy: #"id=""#).dropFirst().compactMap { $0.split(separator: "\"").first.map(String.init) }
                    pages[sentence.textHref] = Set(ids)
                }
                let ids = pages[sentence.textHref] ?? []
                #expect(ids.contains(sentence.fragment), "no sentence \(sentence.fragment)")
                for word in list {
                    #expect(word.fragment.hasPrefix(sentence.fragment + "-w"))
                    #expect(ids.contains(word.fragment), "no word \(word.fragment)")
                }
            }
        }
        #expect(timeline.timesWords && sentences > 20_000 && words > 150_000, "\(sentences) sentences, \(words) words")
        print("The Final Empire (Dramatized), word by word: \(sentences) sentences, \(words) words")
    }

    private static var privateWords: URL? {
        let folder = ProcessInfo.processInfo.environment["HUB_PRIVATE_EPUBS"].map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Builds/jellyhub-books/private-fixtures")
        let file = folder.appendingPathComponent("mistborn-dramatized-words.epub")
        return FileManager.default.fileExists(atPath: file.path) ? file : nil
    }
}
