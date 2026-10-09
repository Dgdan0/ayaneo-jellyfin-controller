import Foundation

/// The demo hub's read-along edition (#25 phase 4, #31): Dark Matter's, made
/// here as Storyteller makes one, and the hub's own reading of it.
///
/// - The edition: a title page and four chapters, each sentence an element
///   with an id, and media overlays (SMIL) saying in which audio file and
///   when each sentence is spoken. Its four audio files are the audiobook's
///   three tracks, the last cut in two, as Dark Matter's second file is.
/// - The hub's reading: each audio file mapped onto its track
///   (`alignment.audio`), and the book's chapters placed where their
///   narration starts (`source` "book"). Two and Three run on from one track
///   into the next.
///
/// `…/file?format=readaloud&audio=omit` sends the edition without its audio,
/// as the hub's slim edition is: every entry as it was, the audio files left
/// out while the package still names them. The narration plays the demo's
/// tones (`DemoAudio`). Nothing real is read.
public enum DemoReadAlong {
    public static let workId = "rw_demo_darkmatter"
    public static let sourceItemId = "demo-dm"

    /// Dark Matter's pack has a word set (#66): its manifest says `wordLevel`
    /// and `granularity=word` is the word edition. HUB_DEMO_SENTENCES=1 makes
    /// it a book without one, which the hub answers with its sentences.
    public static var servesWords: Bool { ProcessInfo.processInfo.environment["HUB_DEMO_SENTENCES"] != "1" }

    /// One of the edition's audio files, and where in which track it begins.
    struct AudioFile {
        let href: String
        let track: Int
        let startMs: Int64
    }

    static let files = [
        AudioFile(href: "OEBPS/Audio/00001-00001.mp3", track: 0, startMs: 0),
        AudioFile(href: "OEBPS/Audio/00002-00001.mp3", track: 1, startMs: 0),
        AudioFile(href: "OEBPS/Audio/00003-00001.mp3", track: 2, startMs: 0),
        AudioFile(href: "OEBPS/Audio/00003-00002.mp3", track: 2, startMs: 30_000),
    ]

    /// Some of a chapter's narration: in which file, from when to when in it.
    struct Stretch {
        let file: Int
        let fromMs: Int64
        let toMs: Int64
    }

    struct Chapter {
        let title: String
        let name: String
        let stretches: [Stretch]
        /// Its text's file: the demo ebook's own (`DemoEpub`), so a place kept in
        /// the ebook opens in the read-along edition, as one book's do. Two's
        /// holds spaces, brackets and an accented letter (#61).
        var file: String { DemoEpub.chapterFile(DemoReadAlong.chapters.firstIndex { $0.name == name } ?? 0, identifier: DemoReadAlong.workId) }

        /// The file as its overlay names it. Two's is percent-encoded, as some
        /// tools write a media overlay; the package itself names it raw, as
        /// Mistborn's does.
        var smilFile: String { name == "two" ? BookHref.spelled(file) : file }
    }

    /// The chapters and where they are spoken. The audiobook's tracks are 90,
    /// 75 and 60 seconds (`DemoReading.audiobooks`); the first six seconds
    /// are the credits, the last two silence.
    static let chapters = [
        Chapter(title: "One", name: "one", stretches: [Stretch(file: 0, fromMs: 6_000, toMs: 52_000)]),
        Chapter(title: "Two", name: "two", stretches: [Stretch(file: 0, fromMs: 52_000, toMs: 90_000),
                                                       Stretch(file: 1, fromMs: 0, toMs: 20_000)]),
        Chapter(title: "Three", name: "three", stretches: [Stretch(file: 1, fromMs: 20_000, toMs: 75_000),
                                                           Stretch(file: 2, fromMs: 0, toMs: 15_000)]),
        Chapter(title: "Four", name: "four", stretches: [Stretch(file: 2, fromMs: 15_000, toMs: 30_000),
                                                         Stretch(file: 3, fromMs: 0, toMs: 28_000)]),
    ]

    /// The longest a sentence takes to say.
    static let sentenceMs: Int64 = 5_000

    /// A sentence: its id in its chapter's page, its words, and where it is spoken.
    struct Sentence {
        let id: String
        let text: String
        let file: Int
        let beginMs: Int64
        let endMs: Int64
    }

    /// A chapter's sentences in the order they are read: each stretch cut
    /// into sentences of five seconds, the last of a stretch shorter.
    static func sentences(_ chapter: Int) -> [Sentence] {
        var out: [Sentence] = []
        for stretch in chapters[chapter].stretches {
            var begin = stretch.fromMs
            while begin < stretch.toMs {
                let end = min(begin + sentenceMs, stretch.toMs)
                let number = out.count + 1
                out.append(Sentence(id: "\(chapters[chapter].name)-s\(number)",
                                    text: words[(chapter * 11 + number) % words.count],
                                    file: stretch.file, beginMs: begin, endMs: end))
                begin = end
            }
        }
        return out
    }

    // MARK: The hub's reading

    /// The manifest's `alignment`: each audio file onto its track.
    static func alignment() -> [String: Any] {
        ["audio": files.map { ["href": $0.href, "track": $0.track, "startMs": $0.startMs] as [String: Any] }]
    }

    /// The manifest's chapters, from the book: each where its first sentence is spoken.
    static func bookChapters() -> [[String: Any]] {
        chapters.indices.compactMap { index -> [String: Any]? in
            guard let first = sentences(index).first else { return nil }
            let file = files[first.file]
            return ["title": chapters[index].title, "startMs": file.startMs + first.beginMs, "track": file.track,
                    "source": ReadingAudioChapter.book]
        }
    }

    // MARK: The route

    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        guard method == "GET", parts.count == 7, parts[0] == "v1", parts[1] == "reading", parts[2] == "works",
              parts[3] == workId, parts[4] == "publications", parts[5] == sourceItemId, parts[6] == "file",
              value("format", in: query) == "readaloud" else { return nil }
        // The whole edition, its audio inside, is not in the demo hub: read along streams.
        guard value("audio", in: query) == "omit" else {
            return DemoTransport.Answer(404, #"{"error":{"code":"not_found","message":"The whole edition is not in the demo hub"}}"#)
        }
        // Word by word when asked (#66), as the hub serves our word overlay; an older app asks for sentences.
        let edition = value("granularity", in: query) == "word" && servesWords ? slimWordEdition() : slimEdition()
        var answer = DemoTransport.Answer(200, data: edition, type: "application/epub+zip")
        answer.headers = ["ETag": DemoTransport.etag(edition)]
        return answer
    }

    /// The edition without its audio, made once a run.
    public static func slimEdition() -> Data { made }

    /// The same, word by word (#66): each word of a sentence an element
    /// `<sentence>-wN`, and its overlay one `<par>` per word inside a
    /// `<seq epub:textref="doc#sentence">` per sentence, as our word packs are.
    public static func slimWordEdition() -> Data { madeWords }

    private static let made: Data = edition(words: false)
    private static let madeWords: Data = edition(words: true)

    private static func edition(words: Bool) -> Data {
        var entries: [(name: String, data: Data)] = [
            ("mimetype", Data("application/epub+zip".utf8)),
            ("META-INF/container.xml", Data(container.utf8)),
            ("OEBPS/content.opf", Data(package().utf8)),
            ("OEBPS/nav.xhtml", Data(nav().utf8)),
            ("OEBPS/Styles/book.css", Data(stylesheet.utf8)),
            ("OEBPS/about.xhtml", Data(titlePage.utf8)),
        ]
        for index in chapters.indices {
            entries.append(("OEBPS/" + chapters[index].file, Data(page(index, words: words).utf8)))
            entries.append(("OEBPS/Overlays/\(chapters[index].name).smil", Data(overlay(index, words: words).utf8)))
        }
        return StoredZip.archive(entries)
    }

    private static let container = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
        """

    private static func package() -> String {
        var items = [#"<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>"#,
                     #"<item id="css" href="Styles/book.css" media-type="text/css"/>"#,
                     #"<item id="title" href="about.xhtml" media-type="application/xhtml+xml"/>"#]
        for chapter in chapters {
            items.append(#"<item id="\#(chapter.name)" href="\#(chapter.file)" media-type="application/xhtml+xml" media-overlay="\#(chapter.name)-overlay"/>"#)
            items.append(#"<item id="\#(chapter.name)-overlay" href="Overlays/\#(chapter.name).smil" media-type="application/smil+xml"/>"#)
        }
        for (index, file) in files.enumerated() {
            // Named, as the slim edition still names them; their bytes are not in it.
            items.append(#"<item id="audio-\#(index + 1)" href="\#(file.href.replacingOccurrences(of: "OEBPS/", with: ""))" media-type="audio/mpeg"/>"#)
        }
        let spine = (["title"] + chapters.map(\.name)).map { #"<itemref idref="\#($0)"/>"# }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">urn:demo:dark-matter-read-along</dc:identifier>
                <dc:title>Dark Matter</dc:title>
                <dc:creator>Blake Crouch</dc:creator>
                <dc:language>en</dc:language>
                <meta property="dcterms:modified">2026-10-07T00:00:00Z</meta>
                <meta property="media:active-class">-epub-media-overlay-active</meta>
              </metadata>
              <manifest>
                \(items.joined(separator: "\n    "))
              </manifest>
              <spine>
                \(spine.joined(separator: "\n    "))
              </spine>
            </package>
            """
    }

    private static func nav() -> String {
        let links = [#"<li><a href="about.xhtml">Dark Matter</a></li>"#]
            + chapters.map { #"<li><a href="\#($0.file)">\#($0.title)</a></li>"# }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
            <head><title>Contents</title></head>
            <body><nav epub:type="toc" id="toc"><h1>Contents</h1><ol>
            \(links.joined(separator: "\n"))
            </ol></nav></body>
            </html>
            """
    }

    private static let stylesheet = """
        body { font-family: serif; line-height: 1.5; }
        h1 { text-align: center; margin: 2em 0 1em; }
        .-epub-media-overlay-active { background-color: rgba(227, 179, 65, 0.28); }
        """

    private static let titlePage = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" lang="en">
        <head><title>Dark Matter</title><link rel="stylesheet" href="Styles/book.css"/></head>
        <body><h1>Dark Matter</h1><p style="text-align: center">Blake Crouch</p>
        <p style="text-align: center">The demo hub's read-along edition</p></body>
        </html>
        """

    private static func page(_ chapter: Int, words: Bool = false) -> String {
        let said = sentences(chapter)
        // Three sentences to a paragraph. As Storyteller writes them, the space
        // between two sentences is inside one of their elements (#56): after
        // the sentence ("The dead are dead. "), or in chapter Two before the
        // next (" Later …"), which the highlight must leave out.
        let before = chapter == 1
        let paragraphs = stride(from: 0, to: said.count, by: 3).map { start in
            let group = Array(said[start..<min(start + 3, said.count)])
            return "<p>" + group.enumerated().map { index, sentence in
                let said = words ? wrapped(sentence) : sentence.text
                let text = before ? (index > 0 ? " " : "") + said : said + (index < group.count - 1 ? " " : "")
                return #"<span id="\#(sentence.id)">\#(text)</span>"#
            }.joined() + "</p>"
        }
        let title = chapters[chapter].title
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
            <head><title>\(title)</title><link rel="stylesheet" href="Styles/book.css"/></head>
            <body><section epub:type="chapter"><h1>\(title)</h1>
            \(paragraphs.joined(separator: "\n"))
            </section></body>
            </html>
            """
    }

    private static func overlay(_ chapter: Int, words: Bool = false) -> String {
        let name = chapters[chapter].name
        let file = chapters[chapter].smilFile
        let pars = sentences(chapter).map { sentence in
            let audio = files[sentence.file].href.replacingOccurrences(of: "OEBPS/", with: "../")
            guard words else {
                return #"<par id="\#(sentence.id)-par"><text src="../\#(file)#\#(sentence.id)"/>"#
                    + #"<audio src="\#(audio)" clipBegin="\#(seconds(sentence.beginMs))" clipEnd="\#(seconds(sentence.endMs))"/></par>"#
            }
            let inner = wordTimes(sentence).enumerated().map { index, time in
                #"<par id="\#(sentence.id)-w\#(index)"><text src="../\#(file)#\#(sentence.id)-w\#(index)"/>"#
                    + #"<audio src="\#(audio)" clipBegin="\#(seconds(time.begin))" clipEnd="\#(seconds(time.end))"/></par>"#
            }
            return #"<seq id="\#(sentence.id)-seq" epub:textref="../\#(file)#\#(sentence.id)">"# + inner.joined() + "</seq>"
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0">
            <body><seq id="\(name)-seq" epub:textref="../\(file)">
            \(pars.joined(separator: "\n"))
            </seq></body>
            </smil>
            """
    }

    /// A sentence's words as our word packs write them: each run of letters,
    /// digits and apostrophes an element `<sentence>-wN` from 0, the
    /// punctuation and the spaces between them outside.
    static func wrapped(_ sentence: Sentence) -> String {
        var out = ""
        var index = 0
        for token in tokens(sentence.text) {
            if token.word {
                out += #"<span id="\#(sentence.id)-w\#(index)">\#(token.text)</span>"#
                index += 1
            } else {
                out += token.text
            }
        }
        return out
    }

    /// When each word of a sentence is spoken: its clip shared out by the words' letters, the last ending with it.
    static func wordTimes(_ sentence: Sentence) -> [(begin: Int64, end: Int64)] {
        let words = tokens(sentence.text).filter(\.word).map(\.text)
        let total = max(1, words.reduce(0) { $0 + $1.count })
        let length = sentence.endMs - sentence.beginMs
        var times: [(begin: Int64, end: Int64)] = []
        var letters = 0
        for (index, word) in words.enumerated() {
            let begin = sentence.beginMs + length * Int64(letters) / Int64(total)
            letters += word.count
            let end = index == words.count - 1 ? sentence.endMs : sentence.beginMs + length * Int64(letters) / Int64(total)
            times.append((begin, max(end, begin + 1)))
        }
        return times
    }

    /// A sentence's text as runs of word and not word.
    private static func tokens(_ text: String) -> [(text: String, word: Bool)] {
        var out: [(text: String, word: Bool)] = []
        for character in text {
            let word = character.isLetter || character.isNumber || character == "'" || character == "\u{2019}"
            if let last = out.last, last.word == word {
                out[out.count - 1].text.append(character)
            } else {
                out.append((String(character), word))
            }
        }
        return out
    }

    /// "52.000s", as Storyteller writes a clip.
    private static func seconds(_ ms: Int64) -> String {
        String(format: "%lld.%03llds", ms / 1_000, ms % 1_000)
    }

    private static func value(_ name: String, in query: String) -> String? {
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2, kv[0] == name { return kv[1].removingPercentEncoding ?? kv[1] }
        }
        return nil
    }

    /// The demo's words: a stand-in for a novel, a sentence at a time.
    private static let words = [
        "The kitchen light was still on when he came up the front steps.",
        // Long enough to run over three lines or more: the sentence the book opens on lit (#52).
        "Somewhere down the street a dog barked twice and then thought better of it, and the quiet that came after "
            + "was so complete that he could hear the lake moving against the pilings a mile away.",
        "He had walked this way home a thousand times and never once looked up.",
        "The lake was flat and black, and the city leaned over it like a reader.",
        "She laughed at something on the radio and turned it down to hear him.",
        "Every choice he had made was a door, and every door was still open somewhere.",
        "The man behind him kept exactly the same distance, block after block.",
        "He counted his breaths the way his father had taught him, slowly, to ten.",
        "The warehouse smelled of rust and river water and old machine oil.",
        "Nothing in the room was his, and yet every object knew his name.",
        "The box was cold to the touch, colder than the night outside it.",
        "He tried to remember the last thing he had said to his son that morning.",
        "A corridor ran away from him in both directions, lit and endless.",
        "Each door looked the same, and each one opened onto a different evening.",
        "She asked him what he was afraid of, and he found he could not answer.",
        "The snow began again, soft and certain, filling his footprints behind him.",
        "He wrote the date on his hand so he would not lose it on the way back.",
        "The world outside the door was almost his, which was worse than not at all.",
        "Somewhere a version of him was setting the table and pouring the wine.",
        "He opened the door and stepped through before he could change his mind.",
        "The air tasted of ash, and the sky was the colour of an old bruise.",
        "Two men in the same coat stood at the end of the platform, waiting.",
        "He knew the house by its porch light, the one he had replaced in June.",
    ]
}
