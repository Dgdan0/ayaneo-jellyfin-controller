import Foundation

/// The demo hub's ebooks (#25, phase 4): a real EPUB 3 for each demo book,
/// written here, so the ebook reader opens something through the same
/// `…/file` route as a real book without a server. The words are made up
/// (none is from the book whose title it carries) and the same in every run.
///
/// Each book has a page about the edition, eight chapters of 9 to 18 KB
/// (Readium counts a position per 1,024 bytes, so about 120 positions), and
/// a page of notes. Chapter One has a footnote, Two a link on to Five, Three
/// an endnote in the notes, Four a link out of the book, and Eight a second
/// part in the contents: the footnote card, Return to previous place and the
/// link that is not opened can all be tried.
public enum DemoEpub {
    public struct Chapter: Equatable, Sendable {
        public let file: String
        public let title: String
        public let bytes: Int
    }

    /// The book's files in reading order, with their sizes.
    public static func chapters(identifier: String) -> [Chapter] {
        book(title: "", author: "", identifier: identifier).chapters
    }

    /// The EPUB, as a ZIP whose entries are stored, `mimetype` first.
    public static func make(title: String, author: String, identifier: String) -> Data {
        let made = book(title: title, author: author, identifier: identifier)
        var entries: [(name: String, data: Data)] = [
            (name: "mimetype", data: Data("application/epub+zip".utf8)),
            (name: "META-INF/container.xml", data: Data(container.utf8)),
            (name: "OEBPS/content.opf", data: Data(made.opf.utf8)),
            (name: "OEBPS/nav.xhtml", data: Data(made.nav.utf8)),
            (name: "OEBPS/style.css", data: Data(style.utf8)),
        ]
        for file in made.files { entries.append((name: "OEBPS/" + file.name, data: Data(file.xhtml.utf8))) }
        return StoredZip.archive(entries)
    }

    /// Where `fraction` of the book is, as a Readium locator: for the demo's
    /// books that were already being read.
    public static func locator(identifier: String, at fraction: Double) -> [String: Any] {
        let chapters = chapters(identifier: identifier)
        let total = Double(chapters.reduce(0) { $0 + $1.bytes })
        var start = 0.0
        for chapter in chapters {
            let share = Double(chapter.bytes) / total
            if fraction < start + share || chapter == chapters.last {
                let progression = share > 0 ? min(max((fraction - start) / share, 0), 1) : 0
                return ["href": "OEBPS/" + chapter.file, "type": "application/xhtml+xml", "title": chapter.title,
                        "locations": ["progression": progression, "totalProgression": fraction]]
            }
            start += share
        }
        return [:]
    }

    // MARK: The book

    private struct File {
        let name: String
        let title: String
        let xhtml: String
    }

    private struct Made {
        let files: [File]
        let opf: String
        let nav: String
        var chapters: [Chapter] { files.map { Chapter(file: $0.name, title: $0.title, bytes: $0.xhtml.utf8.count) } }
    }

    private static let numbers = ["One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight"]

    private static func book(title: String, author: String, identifier: String) -> Made {
        var random = Random(seed: DemoReading.fnv(identifier))
        var files: [File] = [File(name: "about.xhtml", title: "About this edition", xhtml: page(
            title: "About this edition", body: """
            <section epub:type="frontmatter" id="about">
            <h1>About this edition</h1>
            <p class="lead">This is the demo hub’s stand-in for <em>\(escape(title))</em>\(author.isEmpty ? "" : " by " + escape(author)). \
            Its words are made up for the demo, so the reader has a book to open without a server. Your own copy opens from Storyteller.</p>
            <p>Chapter One has a footnote, Chapter Two a link on to Chapter Five, Chapter Three an endnote, and \
            Chapter Four a link that leads out of the book.</p>
            </section>
            """))]
        for (index, name) in numbers.enumerated() {
            let file = String(format: "chapter-%02ld.xhtml", index + 1)
            let target = 9_000 + Int(random.next() % 9_000)
            var body = "<section epub:type=\"chapter\" id=\"chapter-\(index + 1)\">\n<h1>\(name)</h1>\n"
            switch index {
            case 0:
                body += "<p>The harbour master kept a ledger of every arrival<a epub:type=\"noteref\" href=\"#note-1\" "
                    + "id=\"ref-1\">1</a>, though most of its pages were about the weather.</p>\n"
            case 1:
                body += "<p>The bridge comes later, in <a href=\"chapter-05.xhtml\">the chapter about the bridge</a>, "
                    + "but the river was already there.</p>\n"
            case 2:
                body += "<p>Everyone agreed on the date of the great storm<a epub:type=\"noteref\" "
                    + "href=\"notes.xhtml#note-2\" id=\"ref-2\">2</a>, and on nothing else about it.</p>\n"
            case 3:
                body += "<p>A traveller once left a note pointing to <a href=\"https://example.com/\">a page outside "
                    + "the book</a>, and nobody has followed it since.</p>\n"
            default:
                break
            }
            let half = target / 2
            while body.utf8.count < target {
                if index == 7 && body.utf8.count >= half && !body.contains("id=\"part-2\"") {
                    body += "<h2 id=\"part-2\">Later</h2>\n"
                }
                body += "<p>" + paragraph(&random) + "</p>\n"
            }
            if index == 0 {
                body += "<aside epub:type=\"footnote\" id=\"note-1\"><p>1. The ledger is still kept in the harbour "
                    + "office, and visitors may read it on Thursdays. <a href=\"#ref-1\">↩</a></p></aside>\n"
            }
            body += "</section>"
            files.append(File(name: file, title: name, xhtml: page(title: name, body: body)))
        }
        files.append(File(name: "notes.xhtml", title: "Notes", xhtml: page(title: "Notes", body: """
            <section epub:type="backmatter" id="notes">
            <h1>Notes</h1>
            <aside epub:type="endnote" id="note-2"><p>2. The storm is remembered as the night the lighthouse keeper \
            lost count of the steps, and had to start again from the bottom. <a href="chapter-03.xhtml#ref-2">↩</a></p></aside>
            </section>
            """)))
        let uuid = String(format: "%016llx", DemoReading.fnv("uuid:" + identifier))
        let opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="en">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="book-id">urn:demo:\(uuid)</dc:identifier>
            <dc:title>\(escape(title.isEmpty ? "Demo book" : title))</dc:title>
            <dc:creator>\(escape(author.isEmpty ? "The demo hub" : author))</dc:creator>
            <dc:language>en</dc:language>
            <meta property="dcterms:modified">2026-10-06T00:00:00Z</meta>
            </metadata>
            <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="css" href="style.css" media-type="text/css"/>
            \(files.enumerated().map { "<item id=\"f\($0.offset)\" href=\"\($0.element.name)\" media-type=\"application/xhtml+xml\"/>" }.joined(separator: "\n"))
            </manifest>
            <spine>
            \(files.indices.map { "<itemref idref=\"f\($0)\"/>" }.joined(separator: "\n"))
            </spine>
            </package>
            """
        let entries = files.map { file -> String in
            let link = "<a href=\"\(file.name)\">\(escape(file.title))</a>"
            return file.name == "chapter-08.xhtml"
                ? "<li>\(link)\n<ol><li><a href=\"chapter-08.xhtml#part-2\">Later</a></li></ol></li>"
                : "<li>\(link)</li>"
        }
        let nav = page(title: "Contents", body: """
            <nav epub:type="toc" id="toc">
            <h1>Contents</h1>
            <ol>
            \(entries.joined(separator: "\n"))
            </ol>
            </nav>
            """, stylesheet: false)
        return Made(files: files, opf: opf, nav: nav)
    }

    private static func page(title: String, body: String, stylesheet: Bool = true) -> String {
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en">
        <head>
        <title>\(escape(title))</title>
        \(stylesheet ? "<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/>" : "")
        </head>
        <body>
        \(body)
        </body>
        </html>
        """
    }

    private static func paragraph(_ random: inout Random) -> String {
        var picked: [String] = []
        var last = -1
        for _ in 0..<(4 + Int(random.next() % 4)) {
            var index = Int(random.next() % UInt64(sentences.count))
            if index == last { index = (index + 1) % sentences.count }
            picked.append(sentences[index])
            last = index
        }
        return picked.joined(separator: " ")
    }

    private static func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
    }

    /// The same numbers for the same book in every run.
    private struct Random {
        var state: UInt64

        init(seed: UInt64) { state = seed == 0 ? 0x9E37_79B9_7F4A_7C15 : seed }

        mutating func next() -> UInt64 {
            state ^= state << 13
            state ^= state >> 7
            state ^= state << 17
            return state
        }
    }

    private static let container = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
        </rootfiles>
        </container>
        """

    private static let style = """
        body { margin: 0; line-height: 1.5; }
        h1 { font-size: 1.6em; margin: 1.2em 0 0.8em; text-align: center; }
        h2 { font-size: 1.15em; margin: 1.4em 0 0.6em; }
        p { margin: 0; text-indent: 1.4em; }
        h1 + p, h2 + p, p.lead { text-indent: 0; }
        aside { margin-top: 2em; font-size: 0.9em; }
        aside p { text-indent: 0; }
        """

    /// Made up for the demo, and nobody's book.
    private static let sentences = [
        "The lamps along the quay came on one at a time, as if someone were walking ahead of the dark.",
        "Nobody in the town could remember who had first painted the doors blue, but everyone kept painting them.",
        "She folded the map along its old creases and put it back where she had found it.",
        "The ferry was late again, and the gulls had already given up waiting for it.",
        "Somewhere below the floorboards a clock was ticking that no one had wound in years.",
        "He wrote the date at the top of the page and then sat for a long time without adding anything.",
        "The rain arrived sideways, the way it always did in the last week of the season.",
        "There were forty-one steps up to the lighthouse, and the keeper counted every one of them aloud.",
        "By noon the market had sold out of bread, string and patience.",
        "The letter had been opened once already and sealed again with a little too much care.",
        "From the bridge you could see both rivers, the brown one and the green one, refusing to mix.",
        "Her grandmother had kept bees on the roof, and the roof still hummed in summer.",
        "The library closed at six, which meant the librarian left at seven and the cat left at eight.",
        "Every map of the island showed a different number of hills.",
        "He had a habit of answering questions with the weather, which was usually honest enough.",
        "The tram bell rang twice for a stop and three times for a dog on the line.",
        "They found the boat exactly where the tide had promised to leave it.",
        "In the window of the bakery a sign said back soon, and it had said so since spring.",
        "The wind carried the smell of salt and tar up through the narrow streets.",
        "She kept her questions in a small notebook so that she would not ask them all at once.",
        "On the second night the stars came out early, as though they had somewhere else to be.",
        "The old radio could only find one station, and that station only played the sea.",
        "No one locked their doors, but everyone knocked.",
        "The orchard had been planted in rows that leaned slightly towards the morning.",
        "He carried a key that fitted nothing in the house and refused to throw it away.",
        "By the time the post arrived, the news in it had already been discussed twice at the harbour.",
        "The stairs complained at every step, politely, the way old stairs do.",
        "A single kite hung over the beach all afternoon, held by someone nobody could see.",
        "She learned the names of the boats before she learned the names of the people who sailed them.",
        "The clock tower was eleven minutes fast, and the town had quietly agreed to live by it.",
        "Snow was rare there, so when it came the school closed out of respect.",
        "He drew the harbour every morning, and every morning it came out a little different.",
        "The tea had gone cold while they argued about whether it was too hot.",
        "At the end of the pier there was a bench, and on the bench there was always someone thinking.",
        "The fog came in so slowly that it seemed polite to wait for it to finish.",
        "Her coat had more pockets than she could account for, and some of them held surprises.",
        "Nothing in the shop had a price, which made buying anything a conversation.",
        "The road out of town went uphill both ways, according to everyone who walked it.",
        "They kept a list of things to fix, and the list itself needed fixing.",
        "Through the open window came the sound of someone practising the same tune, a little better each day.",
        "The well in the square had been dry for a century, but people still dropped coins in it.",
        "He measured distances in songs: the bakery was two songs away, the lighthouse seven.",
        "When the bells rang at night, it meant a ship had come home, and nobody minded waking.",
        "The garden wall had a door in it that opened onto more garden.",
        "She read the last page first, to be sure the book deserved her.",
        "Every house on the hill had a different view of the same sea.",
        "The museum’s oldest exhibit was the man who sold the tickets.",
        "By evening the harbour was still, and the boats sat in their reflections like guests at a table.",
    ]
}

/// A ZIP archive with every entry stored as it is: what an EPUB needs, and
/// all a generated one needs.
enum StoredZip {
    static func archive(_ entries: [(name: String, data: Data)]) -> Data {
        var out = Data()
        var central = Data()
        // 2026-10-06, 12:00, in MS-DOS form.
        let time: UInt16 = 12 << 11
        let date: UInt16 = UInt16((2026 - 1980) << 9 | 10 << 5 | 6)
        for entry in entries {
            let name = Data(entry.name.utf8)
            let crc = CRC32.checksum(entry.data)
            let size = UInt32(entry.data.count)
            let offset = UInt32(out.count)
            out.le32(0x0403_4B50)
            out.le16(10)
            out.le16(0)
            out.le16(0)
            out.le16(time)
            out.le16(date)
            out.le32(crc)
            out.le32(size)
            out.le32(size)
            out.le16(UInt16(name.count))
            out.le16(0)
            out.append(name)
            out.append(entry.data)

            central.le32(0x0201_4B50)
            central.le16(20)
            central.le16(10)
            central.le16(0)
            central.le16(0)
            central.le16(time)
            central.le16(date)
            central.le32(crc)
            central.le32(size)
            central.le32(size)
            central.le16(UInt16(name.count))
            central.le16(0)
            central.le16(0)
            central.le16(0)
            central.le16(0)
            central.le32(0)
            central.le32(offset)
            central.append(name)
        }
        let centralOffset = UInt32(out.count)
        out.append(central)
        out.le32(0x0605_4B50)
        out.le16(0)
        out.le16(0)
        out.le16(UInt16(entries.count))
        out.le16(UInt16(entries.count))
        out.le32(UInt32(central.count))
        out.le32(centralOffset)
        out.le16(0)
        return out
    }
}

enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { index in
        var value = UInt32(index)
        for _ in 0..<8 { value = value & 1 != 0 ? 0xEDB8_8320 ^ (value >> 1) : value >> 1 }
        return value
    }

    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data { crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8) }
        return crc ^ 0xFFFF_FFFF
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
