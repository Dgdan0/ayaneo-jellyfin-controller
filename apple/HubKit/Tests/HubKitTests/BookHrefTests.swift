import Foundation
import Testing
@testable import HubKit

/// One spelling for a book's documents (#61): Readium names them percent-encoded, the narration decoded.
struct BookHrefTests {
    // As Calibre and Storyteller name Mistborn's documents, and an accented letter besides.
    private let raw = "OEBPS/Brandon Sanderson - [Mistborn 01] - Matière_split_010.htm"
    private let readium = "OEBPS/Brandon%20Sanderson%20-%20%5BMistborn%2001%5D%20-%20Mati%C3%A8re_split_010.htm"
    // The same name with its "è" decomposed (e and a combining grave), as a file system can write it.
    private let decomposed = "OEBPS/Brandon%20Sanderson%20-%20%5BMistborn%2001%5D%20-%20Matie%CC%80re_split_010.htm"

    @Test func everySpellingOfADocumentHasOneKey() {
        #expect(BookHref.key(readium) == raw)
        #expect(BookHref.key(readium + "#html61-s3") == raw, "without its fragment")
        #expect(BookHref.key(decomposed) == raw, "in NFC")
        #expect(BookHref.key("/" + readium) == raw, "without a leading slash")
        #expect(BookHref.key(raw) == raw, "a raw path is its own key")
        #expect(BookHref.normalized("OEBPS/100% sure.xhtml") == "OEBPS/100% sure.xhtml", "a decoded path is not decoded again")
    }

    @Test func aRawPathIsSpelledAsReadiumSpellsIt() {
        #expect(BookHref.spelled(raw) == readium)
        #expect(BookHref.spelled("OEBPS/chapter-01.xhtml") == "OEBPS/chapter-01.xhtml")
    }

    @Test func whatGoesBackToReadiumIsReadiumsOwnSpelling() {
        let hrefs = BookHrefs(readingOrder: ["OEBPS/title.xhtml", readium, "OEBPS/chapter-02.xhtml"])
        #expect(hrefs.readium(raw) == readium)
        #expect(hrefs.readium("OEBPS/chapter-02.xhtml") == "OEBPS/chapter-02.xhtml")
        #expect(hrefs.readium("OEBPS/not in the book.xhtml") == "OEBPS/not%20in%20the%20book.xhtml", "else as Readium spells a raw path")
        #expect(hrefs.readingOrder == ["OEBPS/title.xhtml", readium, "OEBPS/chapter-02.xhtml"])
    }

    // MARK: The narration and the page

    /// A title page, a narrated part with the awkward name, a part's opening page, a plain narrated part, an afterword.
    private var order: [String] {
        ["OEBPS/title.xhtml", readium, "OEBPS/part-two.xhtml", "OEBPS/chapter-02.xhtml", "OEBPS/afterword.xhtml"]
    }

    private var timeline: ReadAlongTimeline {
        ReadAlongTimeline(tracks: [
            ReadAlongTrack(audioHref: "a.mp4", segments: [
                ReadAlongSegment(textHref: raw, fragment: "s1", audioHref: "a.mp4", beginMs: 0, endMs: 2_000),
                ReadAlongSegment(textHref: raw, fragment: "s2", audioHref: "a.mp4", beginMs: 2_000, endMs: 4_000),
            ]),
            ReadAlongTrack(audioHref: "b.mp4", segments: [
                ReadAlongSegment(textHref: "OEBPS/chapter-02.xhtml", fragment: "t1", audioHref: "b.mp4", beginMs: 0, endMs: 3_000),
                ReadAlongSegment(textHref: "OEBPS/chapter-02.xhtml", fragment: "t2", audioHref: "b.mp4", beginMs: 3_000, endMs: 5_000),
            ]),
        ])
    }

    @Test func aPageSpelledAsReadiumSpellsItFindsItsNarration() throws {
        for page in [readium, decomposed, readium + "#s2"] {
            #expect(timeline.narrates(page), "\(page)")
            #expect(timeline.fragments(in: page) == ["s1", "s2"], "\(page)")
            #expect(timeline.sentence(after: page)?.fragment == "t1", "\(page)")
            #expect(timeline.find(href: page, fragment: "s2") == ReadAlongPosition(track: 0, offsetMs: 2_000), "\(page)")
        }
        let edges = ReadAlongPageEdges(first: ReadAlongCaret(fragment: "s1", text: "One two.", offset: 0),
                                       last: ReadAlongCaret(fragment: "s2", text: "Three four.", offset: 11))
        let span = try #require(timeline.span(href: readium, edges: edges))
        #expect(span.firstSentence == ReadAlongPosition(track: 0, offsetMs: 0))
        // A place kept with Readium's spelling resumes at its sentence.
        let place = #"{"href":"\#(readium)","type":"application/xhtml+xml","locations":{"fragments":["s2"]}}"#
        #expect(ReadAlongLocation.resume(place, timeline) == ReadAlongPosition(track: 0, offsetMs: 2_000))
    }

    @Test func playOnAPageWithNoNarrationStartsAtTheNearest() {
        // The title page: the first sentence after it, in the awkwardly named part.
        #expect(timeline.nearest(to: "OEBPS/title.xhtml", readingOrder: order)?.fragment == "s1")
        // Between the two narrated parts: the next one's first.
        #expect(timeline.nearest(to: "OEBPS/part-two.xhtml#top", readingOrder: order)?.fragment == "t1")
        // After the last: the last sentence before it.
        #expect(timeline.nearest(to: "OEBPS/afterword.xhtml", readingOrder: order)?.fragment == "t2")
        // A narrated part: its own first, however it is spelled.
        #expect(timeline.nearest(to: decomposed, readingOrder: order)?.fragment == "s1")
        // A page not in the reading order: the narration's first.
        #expect(timeline.nearest(to: "OEBPS/elsewhere.xhtml", readingOrder: order)?.fragment == "s1")
        #expect(ReadAlongTimeline(tracks: []).nearest(to: "OEBPS/title.xhtml", readingOrder: order) == nil)
    }

    @Test func thePlaceKeptNamesItsPartAsReadiumDoes() throws {
        let hrefs = BookHrefs(readingOrder: order)
        func href(_ json: String) -> String? {
            ((try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any])?["href"] as? String
        }
        // The page is the sentence's part: its own spelling stays.
        let page = #"{"href":"\#(readium)","type":"application/xhtml+xml","locations":{"progression":0.2}}"#
        let here = ReadAlongLocation.save(page, timeline, point: ReadAlongPosition(track: 0, offsetMs: 2_500), completed: false,
                                          hrefs: hrefs)
        #expect(href(here) == readium)
        // The voice has gone on into another part: that part, as Readium spells it.
        let title = #"{"href":"OEBPS/title.xhtml","type":"application/xhtml+xml","locations":{"progression":0}}"#
        let there = ReadAlongLocation.save(title, timeline, point: ReadAlongPosition(track: 0, offsetMs: 500), completed: false,
                                           hrefs: hrefs)
        #expect(href(there) == readium)
        #expect(ReadAlongLocation.resume(there, timeline) == ReadAlongPosition(track: 0, offsetMs: 0))
    }

    // MARK: Editions

    /// The demo's Dark Matter names chapter Two with spaces, brackets and an
    /// accented letter, raw in its package and percent-encoded in its overlay.
    @Test func theDemosAwkwardlyNamedChapterIsNarrated() throws {
        let demo = try ReadAlongPackage.read(DemoReadAlong.slimEdition(), requireAudio: false)
        let file = "OEBPS/" + DemoEpub.chapterFile(1, identifier: DemoReadAlong.workId)
        #expect(file.contains(" - [") && file.contains("è"))
        let ids = DemoReadAlong.sentences(1).map(\.id)
        #expect(demo.fragments(in: BookHref.spelled(file)) == ids)
        #expect(demo.tracks.flatMap(\.segments).contains { $0.textHref == file })
    }

    /// Mistborn's two read-along editions (#61), where the owner found no
    /// narration on any page: every document with a media overlay finds its
    /// sentences by its href as Readium spells it, and every other one the
    /// nearest narration. The books are copyrighted, so they are never in the
    /// repository: slim copies (no audio) are read from `HUB_PRIVATE_EPUBS`,
    /// else ~/Builds/jellyhub-books/private-fixtures, and the test does
    /// nothing where they are not.
    @Test(.enabled(if: !Self.privateEditions.isEmpty)) func everyMistbornDocumentFindsItsNarration() throws {
        for url in Self.privateEditions {
            let data = try Data(contentsOf: url)
            let timeline = try ReadAlongPackage.read(data, requireAudio: false)
            let zip = try ZipArchive(data)
            let container = try ReadAlongPackage.xml(zip, "META-INF/container.xml")
            let opfPath = try ReadAlongPackage.resolve("", container.named("rootfile").first?.attributes["full-path"] ?? "").path
            let opf = try ReadAlongPackage.xml(zip, opfPath)
            var items: [String: XMLElements.Element] = [:]
            for item in opf.named("item") { items[item.attributes["id"] ?? ""] = item }
            var order: [String] = []
            var overlaid: [String] = []
            for ref in opf.named("itemref") {
                guard let item = items[ref.attributes["idref"] ?? ""], let href = item.attributes["href"] else { continue }
                // As Readium spells it: the package's path, percent-encoded.
                let spelled = BookHref.spelled(try ReadAlongPackage.resolve(opfPath, href).path)
                order.append(spelled)
                if !(item.attributes["media-overlay"] ?? "").isEmpty { overlaid.append(spelled) }
            }
            let sentences = timeline.tracks.reduce(0) { $0 + $1.segments.count }
            // The word edition has each sentence once (#66): 19,760 of them, against its sentence set's 21,867.
            #expect(!overlaid.isEmpty && sentences > 19_000, "\(url.lastPathComponent): \(overlaid.count) overlays, \(sentences) sentences")
            for page in order {
                if overlaid.contains(page) {
                    #expect(!timeline.fragments(in: page).isEmpty, "\(url.lastPathComponent): no sentences for \(page)")
                } else {
                    #expect(timeline.nearest(to: page, readingOrder: order) != nil, "\(url.lastPathComponent): nothing near \(page)")
                }
            }
            print("\(url.lastPathComponent): \(order.count) documents, \(overlaid.count) narrated, \(sentences) sentences")
        }
    }

    private static var privateEditions: [URL] {
        let folder = ProcessInfo.processInfo.environment["HUB_PRIVATE_EPUBS"].map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Builds/jellyhub-books/private-fixtures")
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        return names.filter { $0.lowercased().hasPrefix("mistborn") && $0.hasSuffix(".epub") }.sorted()
            .map { folder.appendingPathComponent($0) }
    }
}
