import CoreGraphics
import Foundation
import ImageIO
import Synchronization

/// The demo hub's Books (`-demo`, #25): Storyteller's books and Kavita's
/// comics as the live stack holds them (Red Rising read at its sixth book,
/// Dark Matter with all three editions, Fantastic Four at issue 51), BookKeeprr's
/// Discover rows, requests and transfers, and two audiobooks with a listening
/// place that refuses a stale `expected` with 409 as the hub does. Covers are
/// drawn here. Nothing real is read or written: the app plays generated tones
/// for the demo's tracks (`DemoAudio`).
public enum DemoReading {
    /// The demo's audiobooks: the work, its edition and its tracks' lengths.
    public struct Audiobook: Sendable {
        public let workId: String
        public let sourceItemId: String
        public let title: String
        public let narrator: String
        public let tracksMs: [Int64]
        /// Chapter marks inside the tracks.
        public let chapters: [(title: String, startMs: Int64, track: Int)]
        /// Aligned with its read-along edition (`DemoReadAlong`, #31): the
        /// hub maps the edition's audio onto the tracks, and the chapters
        /// are the book's own, placed by their narration.
        public var aligned = false
    }

    public static let audiobooks = [
        Audiobook(workId: "rw_demo_darkmatter", sourceItemId: "demo-dm", title: "Dark Matter", narrator: "Jon Lindstrom",
                  tracksMs: [90_000, 75_000, 60_000], chapters: [], aligned: true),
        Audiobook(workId: "rw_demo_alloy", sourceItemId: "demo-alloy", title: "The Alloy of Law", narrator: "Michael Kramer",
                  tracksMs: [80_000, 80_000], chapters: []),
    ]

    /// The revision every demo track is served under.
    public static let revision = "demo00000001"

    /// A demo track's id: `t_` and twelve hex digits, as the hub's.
    public static func trackId(_ sourceItemId: String, _ index: Int) -> String {
        "t_" + String(format: "%012llx", fnv(sourceItemId + "#\(index)") & 0xFFFF_FFFF_FFFF)
    }

    /// FNV-1a: the same number for the same words in every run, which
    /// `hashValue` is not.
    static func fnv(_ text: String) -> UInt64 {
        var hash: UInt64 = 1_469_598_103_934_665_603
        for byte in text.utf8 { hash = (hash ^ UInt64(byte)) &* 1_099_511_628_211 }
        return hash
    }

    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        // Any reading picture: a book's cover, an issue's (`kavita-chapter`).
        if parts.count >= 4, parts[0] == "v1", parts[1] == "img", parts[2] == "reading", let name = parts.last {
            return DemoTransport.Answer(200, data: cover(name), type: "image/jpeg")
        }
        guard parts.count >= 3, parts[0] == "v1", parts[1] == "reading" else { return nil }
        switch (method, parts.count, parts[2]) {
        case ("GET", 5, "libraries") where parts[4] == "items":
            return libraryItems(parts[3], query: query)
        case ("GET", 5, "libraries") where parts[4] == "authors":
            return authors(parts[3], query: query)
        case ("GET", 4, "works"):
            return work(parts[3])
        case ("PATCH", 5, "works") where parts[4] == "you":
            return DemoBookPage.patch(workId: parts[3], body: body, known: findWork(parts[3]) != nil)
        case (_, _, "works") where parts.count >= 7 && parts[4] == "publications" && parts[6] == "audio":
            return audio(method: method, parts: parts, body: body)
        case ("GET", 3, "resolve"):
            let sourceId = value("sourceId", in: query)
            let found = discoverItems.first { $0.sourceId == sourceId }.flatMap { item in works.first { $0.title == item.title } }
            return json(["workId": found?.id ?? "", "resolved": found != nil])
        case ("GET", 3, "discover"):
            return discover(type: value("type", in: query))
        case ("GET", 4, "discover"):
            let type = value("type", in: query)
            let page = Int(value("page", in: query)) ?? 1
            guard let row = rows(type: type).first(where: { $0.id == parts[3] }) else { return failure(404, "not_found", "No such row") }
            // A second page of every row, then the end.
            return json(["rows": [rowFields(row, page: page, items: page > 2 ? [] : Array(row.items.reversed()))], "partial": [],
                         "cache": [:]])
        case ("GET", 3, "search"):
            return search(value("q", in: query), type: value("type", in: query))
        case ("GET", 4, "requests") where parts[3] == "options":
            return requestOptions(key: value("key", in: query))
        case ("GET", 4, "requests") where parts[3] == "series-preview":
            return seriesPreview(key: value("key", in: query))
        case ("POST", 3, "requests"):
            return createRequest(body)
        case ("GET", 5, "requests") where parts[4] == "releases":
            return releases(seriesId: Int(parts[3]) ?? 0)
        case ("POST", 5, "requests") where parts[4] == "search":
            return releases(seriesId: Int(parts[3]) ?? 0)
        case ("POST", 5, "requests") where parts[4] == "grab":
            return grab(seriesId: Int(parts[3]) ?? 0, body: body)
        case ("GET", 3, "downloads"):
            let list = transfers.withLock { all -> [Transfer] in
                // A download that moves on each read, as a real one would.
                for index in all.indices where all[index].status == "downloading" {
                    all[index].progressPercent = min(99, all[index].progressPercent + 7)
                }
                return all
            }
            return json(["items": list.map(transferFields)])
        case ("POST", 5, "downloads") where parts[4] == "retry":
            return retry(parts[3])
        case ("DELETE", 4, "downloads"):
            let gone = transfers.withLock { all in
                let before = all.count
                all.removeAll { $0.id == parts[3] && $0.actions.contains("cancel") }
                return all.count < before
            }
            return gone ? json(["ok": true, "action": "cancel_transfer"]) : failure(404, "not_found", "No such transfer")
        default:
            return nil
        }
    }

    // MARK: The libraries

    /// The libraries as `/v1/reading/libraries` lists them (in `DemoMedia`, which keeps their order).
    static let libraries: [(id: String, source: String, kind: String, title: String, artwork: String, capabilities: [String])] = [
        ("storyteller:books", "storyteller", "book", "Books & Audiobooks", art("rr6"),
         ["browse", "details", "ebook", "audiobook", "readaloud", "progress", "sort:title", "sort:series", "sort:author",
          "sort:added", "sort:last_read"]),
        ("kavita:3", "kavita", "manga", "Manga", art("csm"),
         ["browse", "details", "progress", "sort:title", "sort:series", "sort:added", "sort:last_read"]),
        ("kavita:2", "kavita", "comic", "My Marvelous Year", art("ff"),
         ["browse", "details", "progress", "sort:title", "sort:series", "sort:added", "sort:last_read"]),
    ]

    static func art(_ id: String) -> String { "/v1/img/reading/demo/" + id }

    /// A book of a series, as a series' page lists it.
    struct Book {
        let workId: String
        let sourceItemId: String
        let title: String
        let number: String
        let kind: String
        let pages: Int
        let progress: Double
        let updatedAt: String
        let formats: [String]
        var available = true
    }

    /// A work: a book, a series or a comic run.
    struct Work {
        let id: String
        let libraryId: String
        let entityType: String
        let kind: String
        let title: String
        let authors: [(id: String, name: String)]
        let year: Int
        let addedAt: String
        let overview: String
        let artwork: String
        var series = ""
        var seriesIndex = 0.0
        var seriesId = ""
        var books: [Book] = []
        var volumes: [(title: String, number: Int, issues: [(number: Int, pages: Int)])] = []
        var editions: [(kind: String, format: String, pages: Int, durationMs: Int64, narrator: String)] = []
        var progress = 0.0
        var updatedAt = ""
        var continueNumber = ""
    }

    private static let pierce = [(id: "ra_demo_pierce", name: "Pierce Brown")]
    private static let blake = [(id: "ra_demo_blake", name: "Blake Crouch")]
    private static let brandon = [(id: "ra_demo_brandon", name: "Brandon Sanderson")]
    private static let james = [(id: "ra_demo_james", name: "James Islington")]

    private static let redRisingBooks = [
        Book(workId: "rw_demo_rr1", sourceItemId: "rr1", title: "Red Rising", number: "1", kind: "book", pages: 367, progress: 0.0954,
             updatedAt: "2026-09-21 16:09:43", formats: ["ebook"]),
        Book(workId: "rw_demo_rr2", sourceItemId: "rr2", title: "Golden Son", number: "2", kind: "book", pages: 414, progress: 0.0193,
             updatedAt: "2026-09-22 02:17:48", formats: ["ebook"]),
        Book(workId: "rw_demo_rr3", sourceItemId: "rr3", title: "Morning Star", number: "3", kind: "book", pages: 514, progress: 0,
             updatedAt: "", formats: ["ebook"]),
        Book(workId: "rw_demo_rr4", sourceItemId: "rr4", title: "Iron Gold", number: "4", kind: "book", pages: 605, progress: 0,
             updatedAt: "", formats: ["ebook"]),
        Book(workId: "", sourceItemId: "", title: "Dark Age", number: "5", kind: "book", pages: 823, progress: 0, updatedAt: "",
             formats: [], available: false),
        Book(workId: "rw_demo_rr6", sourceItemId: "rr6", title: "Light Bringer", number: "6", kind: "book", pages: 735,
             progress: 0.4946, updatedAt: "2026-10-04 21:16:47", formats: ["ebook"]),
    ]

    private static let mistbornBooks = [
        Book(workId: "rw_demo_mb1", sourceItemId: "mb1", title: "The Final Empire", number: "1", kind: "book", pages: 541,
             progress: 0.0034, updatedAt: "2026-10-03 13:07:02", formats: ["ebook", "audiobook", "readaloud"]),
        Book(workId: "rw_demo_mb2", sourceItemId: "mb2", title: "The Well of Ascension", number: "2", kind: "book", pages: 590,
             progress: 0, updatedAt: "", formats: ["ebook"]),
        Book(workId: "rw_demo_mb3", sourceItemId: "mb3", title: "The Hero of Ages", number: "3", kind: "book", pages: 572,
             progress: 0, updatedAt: "", formats: ["ebook"]),
        Book(workId: "rw_demo_alloy", sourceItemId: "demo-alloy", title: "The Alloy of Law", number: "4", kind: "audiobook",
             pages: 0, progress: 0, updatedAt: "", formats: ["audiobook"]),
    ]

    static let works: [Work] = [
        Work(id: "rw_demo_redrising", libraryId: "storyteller:books", entityType: "collection", kind: "book", title: "Red Rising",
             authors: pierce, year: 2014, addedAt: "2026-09-21 05:57:26",
             overview: "Darrow is a Red, a miner beneath the surface of Mars, until the day he learns that the surface has long been livable.",
             artwork: art("rr1"), books: redRisingBooks, progress: 0.1015, updatedAt: "2026-10-04 21:16:47", continueNumber: "6"),
        Work(id: "rw_demo_mistborn", libraryId: "storyteller:books", entityType: "collection", kind: "book",
             title: "Mistborn Original Trilogy", authors: brandon, year: 2006, addedAt: "2026-10-05 11:13:11",
             overview: "For a thousand years the ash fell and no flowers bloomed.", artwork: art("mb1"), books: mistbornBooks,
             progress: 0.0034, updatedAt: "2026-10-03 13:07:02", continueNumber: "1"),
        Work(id: "rw_demo_licanius", libraryId: "storyteller:books", entityType: "collection", kind: "book",
             title: "The Licanius Trilogy", authors: james, year: 2014, addedAt: "2026-09-21 03:51:13",
             overview: "It has been twenty years since the end of the war.", artwork: art("lic"),
             books: [Book(workId: "rw_demo_lic1", sourceItemId: "lic1", title: "The Shadow of What Was Lost", number: "1",
                          kind: "book", pages: 698, progress: 0, updatedAt: "", formats: ["ebook"])]),
        Work(id: "rw_demo_darkmatter", libraryId: "storyteller:books", entityType: "work", kind: "book", title: "Dark Matter",
             authors: blake, year: 2016, addedAt: "2026-09-23 05:31:11",
             overview: "A physicist is knocked out on his way home and wakes in a life he never chose.", artwork: art("dm"),
             editions: [("ebook", "epub", 246, 0, ""), ("audiobook", "audio", 0, 225_000, "Jon Lindstrom"),
                        ("readaloud", "epub-media-overlay", 0, 0, "Jon Lindstrom")],
             progress: 0.0325, updatedAt: "2026-10-05 12:17:12"),
        Work(id: "rw_demo_recursion", libraryId: "storyteller:books", entityType: "work", kind: "book", title: "Recursion",
             authors: blake, year: 2019, addedAt: "2026-09-21 16:16:29",
             overview: "Memory makes reality. That is what the people with False Memory Syndrome come to learn.", artwork: art("rec"),
             editions: [("ebook", "epub", 326, 0, "")]),
        Work(id: "rw_demo_alloy", libraryId: "storyteller:books", entityType: "work", kind: "audiobook", title: "The Alloy of Law",
             authors: brandon, year: 2011, addedAt: "2026-10-05 11:13:10",
             overview: "Three hundred years after the events of the Mistborn trilogy, Scadrial is on the verge of modernity.",
             artwork: art("alloy"), series: "Mistborn Original Trilogy", seriesIndex: 4, seriesId: "rw_demo_mistborn",
             editions: [("audiobook", "audio", 0, 160_000, "Michael Kramer")]),
        Work(id: "rw_demo_ff", libraryId: "kavita:2", entityType: "work", kind: "comic", title: "Fantastic Four", authors: [],
             year: 1961, addedAt: "2026-09-26 10:00:00", overview: "", artwork: art("ff"),
             volumes: [("Volume 1961", 1961, (1...12).map { ($0, 36) }), ("Volume 1998", 1998, (51...56).map { ($0, 24) })],
             editions: [("comic", "archive", 576, 0, "")], progress: 0.0023, updatedAt: "2026-10-01 20:00:00", continueNumber: "51"),
        Work(id: "rw_demo_aaf", libraryId: "kavita:2", entityType: "work", kind: "comic", title: "Amazing Adult Fantasy", authors: [],
             year: 1961, addedAt: "2026-09-26 10:00:00", overview: "", artwork: art("aaf"),
             volumes: [("Volume 1961", 1961, [(7, 36)])], editions: [("comic", "archive", 36, 0, "")], progress: 0.1111,
             updatedAt: "2026-09-30 21:00:00", continueNumber: "7"),
        Work(id: "rw_demo_csm", libraryId: "kavita:3", entityType: "work", kind: "manga", title: "Chainsaw Man", authors: [],
             year: 2018, addedAt: "2026-09-27 10:00:00", overview: "Denji is a teenage boy living with a Chainsaw Devil named Pochita.",
             artwork: art("csm"), volumes: [("Volume 1", 1, (1...8).map { ($0, 22) })], editions: [("manga", "archive", 176, 0, "")]),
    ]

    private static func libraryItems(_ libraryId: String, query: String) -> DemoTransport.Answer {
        let view = value("view", in: query)
        let sort = value("sort", in: query)
        let descending = value("direction", in: query) == "desc"
        var list: [Work]
        if view == "works" {
            // Every book on its own: a series opened up into its books.
            list = works.filter { $0.libraryId == libraryId }.flatMap { work -> [Work] in
                guard work.entityType == "collection" else { return [work] }
                return work.books.filter(\.available).compactMap { book in works.first { $0.id == book.workId } ?? bookWork(book, of: work) }
            }
            var seen = Set<String>()
            list = list.filter { seen.insert($0.id).inserted }
        } else {
            // As series: a book of a series is the series' card.
            list = works.filter { $0.libraryId == libraryId && !($0.entityType == "work" && !$0.seriesId.isEmpty) }
        }
        switch sort {
        case "last_read": list.sort { ReadingShelves.timestamp($0.updatedAt) > ReadingShelves.timestamp($1.updatedAt) }
        case "added": list.sort { ReadingShelves.timestamp($0.addedAt) > ReadingShelves.timestamp($1.addedAt) }
        case "author": list.sort { ($0.authors.first?.name ?? "") < ($1.authors.first?.name ?? "") }
        default: list.sort { $0.title.lowercased() < $1.title.lowercased() }
        }
        if descending && sort != "last_read" && sort != "added" { list.reverse() }
        if !descending && (sort == "last_read" || sort == "added") { list.reverse() }
        return json(["libraryId": libraryId, "page": 1, "pageSize": 60, "total": list.count, "totalPages": 1, "hasMore": false,
                     "items": list.map { summaryFields($0) }, "partial": [], "cache": ["hit": true, "ageSeconds": 3]])
    }

    /// A book of a series that has no page of its own in the demo.
    private static func bookWork(_ book: Book, of series: Work) -> Work {
        Work(id: book.workId, libraryId: series.libraryId, entityType: "work", kind: book.kind, title: book.title,
             authors: series.authors, year: series.year, addedAt: series.addedAt, overview: series.overview,
             artwork: art(book.sourceItemId), series: series.title, seriesIndex: Double(book.number) ?? 0, seriesId: series.id,
             editions: book.formats.map { ($0, $0 == "ebook" ? "epub" : "audio", $0 == "ebook" ? book.pages : 0, 0, "") },
             progress: book.progress, updatedAt: book.updatedAt)
    }

    private static func findWork(_ id: String) -> Work? {
        if let work = works.first(where: { $0.id == id }) { return work }
        for series in works where series.entityType == "collection" {
            if let book = series.books.first(where: { $0.workId == id && $0.available }) { return bookWork(book, of: series) }
        }
        return nil
    }

    private static func work(_ id: String) -> DemoTransport.Answer {
        guard let work = findWork(id) else { return failure(404, "not_found", "No such book") }
        // The book page's community, you and genres (#39): on this route only.
        return json(DemoBookPage.decorate(detailFields(work), workId: work.id))
    }

    private static func progressFields(_ percentage: Double, updatedAt: String, completed: Bool = false) -> [String: Any] {
        var fields: [String: Any] = ["percentage": percentage, "completed": completed]
        if !updatedAt.isEmpty { fields["updatedAt"] = updatedAt }
        return fields
    }

    private static func availability(_ work: Work) -> [String] {
        if work.entityType == "collection" {
            let formats = Set(work.books.flatMap(\.formats))
            return ReadingBookFacts.allFormats.filter(formats.contains)
        }
        if work.kind == "comic" || work.kind == "manga" { return [work.kind] }
        return ReadingBookFacts.allFormats.filter { kind in work.editions.contains { $0.kind == kind } }
    }

    private static func summaryFields(_ work: Work) -> [String: Any] {
        var fields: [String: Any] = [
            "id": work.id, "libraryId": work.libraryId, "entityType": work.entityType, "kind": work.kind, "title": work.title,
            "sortTitle": work.title, "authors": work.authors.map(\.name), "overview": work.overview, "artwork": work.artwork,
            "genres": [], "year": work.year, "addedAt": work.addedAt, "languages": [], "editions": [],
            "availability": availability(work),
            "bookCount": work.entityType == "collection" ? work.books.count : 1,
        ]
        if !work.series.isEmpty {
            fields["series"] = work.series
            fields["seriesIndex"] = work.seriesIndex
        }
        if work.progress > 0 { fields["progress"] = progressFields(work.progress, updatedAt: work.updatedAt) }
        return fields
    }

    private static func detailFields(_ work: Work) -> [String: Any] {
        var fields = summaryFields(work)
        fields["authorRefs"] = work.authors.map { ["id": $0.id, "name": $0.name] }
        if !work.seriesId.isEmpty { fields["seriesId"] = work.seriesId }
        let sourceItemId = audiobooks.first { $0.workId == work.id }?.sourceItemId ?? "demo-" + work.id
        fields["editions"] = work.editions.enumerated().map { index, edition -> [String: Any] in
            var out: [String: Any] = ["id": "re_demo_\(work.id)_\(index)", "workId": work.id,
                                      "source": work.kind == "comic" || work.kind == "manga" ? "kavita" : "storyteller",
                                      "sourceItemId": sourceItemId, "kind": edition.kind, "format": edition.format,
                                      "availability": "available"]
            if edition.pages > 0 { out["pageCount"] = edition.pages }
            if edition.durationMs > 0 { out["durationMs"] = edition.durationMs }
            if !edition.narrator.isEmpty { out["narrator"] = edition.narrator }
            return out
        }
        if work.entityType == "collection" {
            fields["sections"] = [["id": "demo-series:\(work.id)", "title": "Books", "items": work.books.map { book -> [String: Any] in
                var item: [String: Any] = ["sourceItemId": book.sourceItemId, "workId": book.workId, "title": book.title,
                                           "number": book.number, "kind": book.kind, "artwork": art(book.sourceItemId.isEmpty ? "missing" : book.sourceItemId),
                                           "authors": work.authors.map(\.name), "availability": book.available ? "available" : "missing",
                                           "formats": book.formats]
                if book.pages > 0 { item["pageCount"] = book.pages }
                if book.progress > 0 { item["progress"] = progressFields(book.progress, updatedAt: book.updatedAt) }
                return item
            }]]
            if let on = work.books.first(where: { $0.number == work.continueNumber && $0.available }) {
                fields["continue"] = ["workId": on.workId, "source": "storyteller", "sourceItemId": on.sourceItemId, "title": on.title,
                                      "number": on.number, "percentage": on.progress, "artwork": art(on.sourceItemId), "kind": on.kind]
            }
        } else if !work.volumes.isEmpty {
            fields["sections"] = work.volumes.map { volume -> [String: Any] in
                ["id": "demo-volume:\(work.id):\(volume.number)", "title": volume.title, "number": volume.number,
                 "items": volume.issues.map { issue -> [String: Any] in
                     let reading = "\(issue.number)" == work.continueNumber
                     var item: [String: Any] = ["sourceItemId": "\(work.id)-\(issue.number)", "title": "\(issue.number)",
                                                "number": "\(issue.number)", "kind": work.kind,
                                                "artwork": HubEndpoints.kavitaChapterCover("\(work.id)-\(issue.number)"),
                                                "pageCount": issue.pages, "availability": ""]
                     item["progress"] = reading ? ["percentage": 0.0417, "completed": false, "current": 1, "total": issue.pages]
                         : ["percentage": 0, "completed": false, "total": issue.pages]
                     return item
                 }]
            }
            if !work.continueNumber.isEmpty {
                fields["continue"] = ["source": "kavita", "sourceItemId": "\(work.id)-\(work.continueNumber)",
                                      "title": work.continueNumber, "number": work.continueNumber, "percentage": work.progress,
                                      "kind": work.kind]
            }
        }
        fields["cache"] = ["hit": false, "ageSeconds": 0]
        return fields
    }

    private static func authors(_ libraryId: String, query: String) -> DemoTransport.Answer {
        let wanted = value("authorId", in: query)
        let shelf = works.filter { $0.libraryId == libraryId && !($0.entityType == "work" && !$0.seriesId.isEmpty) }
        var people: [(id: String, name: String)] = []
        for work in shelf { for author in work.authors where !people.contains(where: { $0.id == author.id }) { people.append(author) } }
        people.sort { $0.name < $1.name }
        if value("direction", in: query) == "desc" { people.reverse() }
        let rows = people.filter { wanted.isEmpty || $0.id == wanted }.map { person -> [String: Any] in
            let theirs = shelf.filter { $0.authors.contains { $0.id == person.id } }
            let series = theirs.filter { $0.entityType == "collection" }
            let books = series.reduce(0) { $0 + $1.books.count } + theirs.filter { $0.entityType != "collection" }.count
            var fields: [String: Any] = ["id": person.id, "name": person.name, "artwork": "", "seriesCount": series.count,
                                         "bookCount": books, "total": theirs.count, "page": 1, "totalPages": 1]
            if !wanted.isEmpty { fields["items"] = theirs.map { detailFields($0) } }
            return fields
        }
        return json(["authors": rows, "page": 1, "total": rows.count, "totalPages": 1, "partial": [], "cache": ["hit": true]])
    }

    // MARK: Discover, search and requests

    struct Item {
        let key: String
        let contentType: String
        let title: String
        let author: String
        let year: Int
        let sourceId: String
        var inLibrary = false
    }

    static let discoverItems: [Item] = [
        Item(key: "reading:demo-atomic", contentType: "ebook", title: "Atomic Habits", author: "James Clear", year: 2018, sourceId: "OL1W"),
        Item(key: "reading:demo-verity", contentType: "ebook", title: "Verity", author: "Colleen Hoover", year: 2018, sourceId: "OL2W"),
        Item(key: "reading:demo-psych", contentType: "ebook", title: "The Psychology of Money", author: "Morgan Housel", year: 2020,
             sourceId: "OL3W"),
        Item(key: "reading:demo-darkmatter", contentType: "ebook", title: "Dark Matter", author: "Blake Crouch", year: 2016,
             sourceId: "OL4W", inLibrary: true),
        Item(key: "reading:demo-will", contentType: "ebook", title: "The Will of the Many", author: "James Islington", year: 2023,
             sourceId: "OL5W"),
        Item(key: "reading:demo-dcc", contentType: "audiobook", title: "Dungeon Crawler Carl", author: "Matt Dinniman", year: 2020,
             sourceId: "itunes:1"),
        Item(key: "reading:demo-1984", contentType: "audiobook", title: "1984", author: "George Orwell", year: 1949, sourceId: "itunes:2"),
        Item(key: "reading:demo-mistborn-audio", contentType: "audiobook", title: "Mistborn", author: "Brandon Sanderson", year: 2008,
             sourceId: "itunes:3", inLibrary: true),
        Item(key: "reading:demo-onepiece", contentType: "manga", title: "One Piece", author: "", year: 1997, sourceId: "al:1"),
        Item(key: "reading:demo-berserk", contentType: "manga", title: "Berserk", author: "", year: 1989, sourceId: "al:2"),
        Item(key: "reading:demo-frieren", contentType: "manga", title: "Frieren", author: "", year: 2020, sourceId: "al:3"),
        Item(key: "reading:demo-86", contentType: "light_novel", title: "86—EIGHTY-SIX", author: "Asato Asato", year: 2017, sourceId: "al:4"),
        Item(key: "reading:demo-apothecary", contentType: "light_novel", title: "The Apothecary Diaries", author: "Natsu Hyuuga",
             year: 2014, sourceId: "al:5"),
    ]

    struct Row {
        let id: String
        let title: String
        let contentType: String
        let items: [Item]
    }

    private static func rows(type: String) -> [Row] {
        func of(_ kind: String) -> [Item] { discoverItems.filter { $0.contentType == kind } }
        let all = [
            Row(id: "ebook-trending", title: "Trending now", contentType: "ebook", items: of("ebook")),
            Row(id: "audio-itunes-top", title: "Popular audiobooks", contentType: "audiobook", items: of("audiobook")),
            Row(id: "audio-librivox", title: "Free audiobooks", contentType: "audiobook", items: []),
            Row(id: "popular", title: "Popular", contentType: "manga", items: of("manga")),
            Row(id: "novel-trending", title: "Trending now", contentType: "light_novel", items: of("light_novel")),
        ]
        return type.isEmpty || type == ReadingType.all ? all : all.filter { $0.contentType == type }
    }

    private static func itemFields(_ item: Item) -> [String: Any] {
        let requested = requests.withLock { $0.contains(item.key) }
        return ["key": item.key, "contentType": item.contentType, "title": item.title, "author": item.author, "year": item.year,
                "source": item.contentType == "audiobook" ? "itunes" : "openlibrary", "sourceId": item.sourceId,
                "cover": art((item.contentType == "audiobook" ? "sq-" : "") + item.key.replacingOccurrences(of: "reading:", with: "")),
                "description": "\(item.title), as the demo hub tells it.", "inLibrary": item.inLibrary || requested,
                "actions": item.inLibrary || requested ? ["detail"] : ["detail", "request"]]
    }

    private static func rowFields(_ row: Row, page: Int = 1, items: [Item]? = nil) -> [String: Any] {
        ["id": row.id, "title": row.title, "meta": "Demo", "contentType": row.contentType, "page": page, "hasMore": page < 2,
         "items": (items ?? row.items).map(itemFields)]
    }

    private static func discover(type: String) -> DemoTransport.Answer {
        json(["rows": rows(type: type).map { rowFields($0) }, "partial": [], "cache": ["hit": true, "ageSeconds": 30]])
    }

    private static func search(_ query: String, type: String) -> DemoTransport.Answer {
        let words = query.lowercased()
        let kind = type.isEmpty ? ReadingType.all : type
        let pool = discoverItems.filter { kind == ReadingType.all || $0.contentType == kind }
        let close = pool.filter { $0.title.lowercased().contains(words) }
        let broader = pool.filter { !$0.title.lowercased().contains(words) && $0.author.lowercased().contains(words) }
        return json(["query": query, "contentType": kind, "results": close.map(itemFields), "broaderResults": broader.map(itemFields),
                     "partial": [], "cache": ["hit": false]])
    }

    private static func requestOptions(key: String) -> DemoTransport.Answer {
        guard let item = discoverItems.first(where: { $0.key == key }) else { return failure(404, "not_found", "No such title") }
        var modes: [[String: Any]] = [["id": "single", "label": "This " + (item.contentType == "audiobook" ? "audiobook" : "book")]]
        if item.contentType == "ebook" {
            modes.append(["id": "series", "label": "Choose books from series", "requiresSeriesPreview": true])
        }
        return json(["key": item.key, "contentType": item.contentType, "title": item.title, "author": item.author, "modes": modes,
                     "qualityProfiles": [["id": 1, "label": "Any"], ["id": 7, "label": "English EPUB", "default": true,
                                                                   "preferCompleteBatches": true]],
                     "monitoring": ["all", "none"]])
    }

    private static func seriesPreview(key: String) -> DemoTransport.Answer {
        guard let item = discoverItems.first(where: { $0.key == key }) else { return failure(404, "not_found", "No such title") }
        let books: [[String: Any]] = [
            ["id": "OL-demo-1", "title": item.title, "author": item.author, "year": item.year, "position": 1, "inLibrary": false,
             "selected": true, "cover": art("s1")],
            ["id": "OL-demo-2", "title": item.title + ": Book Two", "author": item.author, "year": item.year + 2, "position": 2,
             "inLibrary": false, "selected": true, "cover": art("s2")],
            ["id": "OL-demo-3", "title": item.title + ": Book Three", "author": item.author, "year": item.year + 4, "position": 3,
             "inLibrary": true, "selected": false, "cover": art("s3")],
        ]
        return json(["key": key, "scopes": [["key": key, "seriesId": "OL-demo-series", "name": item.title + " series",
                                            "author": item.author, "ordering": "publication", "books": books]]])
    }

    private static let requests = Mutex<Set<String>>([])

    private static func createRequest(_ body: Data?) -> DemoTransport.Answer {
        guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let key = fields["key"] as? String, let mode = fields["mode"] as? String, fields["qualityProfileId"] is Int,
              let item = discoverItems.first(where: { $0.key == key }) else {
            return failure(400, "invalid_request", "invalid reading request body")
        }
        let allowed: Set<String> = ["key", "mode", "totalBooks", "bookIds", "seriesId", "qualityProfileId", "monitoring"]
        guard Set(fields.keys).isSubset(of: allowed) else { return failure(400, "invalid_request", "invalid reading request body") }
        if mode == "series", (fields["bookIds"] as? [String] ?? []).isEmpty {
            return failure(400, "invalid_request", "choose at least one book")
        }
        requests.withLock { _ = $0.insert(key) }
        let seriesId = 900 + (discoverItems.firstIndex { $0.key == key } ?? 0)
        return json(["requestId": "rq_demo_\(seriesId)", "seriesId": seriesId, "state": "searching",
                     "message": "BookKeeprr is searching for \(item.title)",
                     "targets": [["seriesId": seriesId, "title": item.title]]])
    }

    private static func releases(seriesId: Int) -> DemoTransport.Answer {
        guard seriesId > 0 else { return failure(404, "not_found", "No such request") }
        let releases: [[String: Any]] = [
            ["id": "rel1", "title": "Demo Book (2018) EPUB", "indexer": "Demo Indexer", "sizeBytes": 4_300_000, "seeders": 42,
             "leechers": 3, "ownership": "none", "rejected": false, "freeleech": true, "format": "EPUB", "formatStatus": "compatible"],
            ["id": "rel2", "title": "Demo Book (2018) AZW3", "indexer": "Demo Indexer", "sizeBytes": 3_100_000, "seeders": 9,
             "ownership": "none", "rejected": true, "reason": "Not in the quality profile", "format": "AZW3",
             "formatStatus": "compatible"],
            ["id": "rel3", "title": "Demo Book (2018) PDF", "indexer": "Other Indexer", "sizeBytes": 12_000_000, "seeders": 4,
             "ownership": "none", "rejected": true, "reason": "PDF is not an ebook format this request takes", "format": "PDF",
             "formatStatus": "incompatible"],
        ]
        return json(["seriesId": seriesId, "releases": releases, "errors": []])
    }

    private static func grab(seriesId: Int, body: Data?) -> DemoTransport.Answer {
        guard seriesId > 0, let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let id = fields["id"] as? String, ["rel1", "rel2"].contains(id) else {
            return failure(400, "invalid_request", "That release cannot be grabbed")
        }
        transfers.withLock { all in
            all.insert(Transfer(id: "rt_demo_\(seriesId)", seriesId: seriesId, contentType: "ebook", title: "Requested book",
                                releaseTitle: "Demo Book (2018) EPUB", status: "downloading", progressPercent: 5, sizeBytes: 4_300_000,
                                speed: 900_000, eta: 30, failed: false, actions: ["cancel"]), at: 0)
        }
        return json(["ok": true, "action": "grab_release"])
    }

    struct Transfer: Sendable {
        let id: String
        let seriesId: Int
        let contentType: String
        let title: String
        let releaseTitle: String
        var status: String
        var progressPercent: Int
        let sizeBytes: Int64
        var speed: Int64
        var eta: Int64
        var failed: Bool
        var actions: [String]
    }

    private static let transfers = Mutex<[Transfer]>([
        Transfer(id: "rt_demo_xmen", seriesId: 28, contentType: "comic", title: "Uncanny X-Men",
                 releaseTitle: "Uncanny X-Men (001 - 544 & Annuals)", status: "failed", progressPercent: 0, sizeBytes: 0, speed: 0, eta: 0,
                 failed: true, actions: ["retry", "cancel"]),
        Transfer(id: "rt_demo_will", seriesId: 31, contentType: "ebook", title: "The Will of the Many",
                 releaseTitle: "The Will of the Many by James Islington EPUB", status: "downloading", progressPercent: 42,
                 sizeBytes: 5_200_000, speed: 640_000, eta: 6, failed: false, actions: ["cancel"]),
        Transfer(id: "rt_demo_hunger", seriesId: 19, contentType: "ebook", title: "The Hunger Games",
                 releaseTitle: "The Hunger Games Trilogy by Suzanne Collins EPUB", status: "imported", progressPercent: 100,
                 sizeBytes: 2_900_000, speed: 0, eta: 0, failed: false, actions: []),
        Transfer(id: "rt_demo_mistborn", seriesId: 15, contentType: "audiobook", title: "Mistborn",
                 releaseTitle: "Brandon Sanderson - Mistborn Series [Books 1-4] MacMillan Audio", status: "imported",
                 progressPercent: 100, sizeBytes: 2_660_058_388, speed: 0, eta: 0, failed: false, actions: []),
    ])

    private static func transferFields(_ transfer: Transfer) -> [String: Any] {
        var fields: [String: Any] = ["id": transfer.id, "seriesId": transfer.seriesId, "contentType": transfer.contentType,
                                     "title": transfer.title, "releaseTitle": transfer.releaseTitle, "status": transfer.status,
                                     "progressPercent": transfer.progressPercent, "failed": transfer.failed, "actions": transfer.actions,
                                     "addedAt": "2026-10-05T09:00:00Z"]
        if transfer.sizeBytes > 0 { fields["sizeBytes"] = transfer.sizeBytes }
        if transfer.speed > 0 && transfer.status == "downloading" { fields["downloadSpeedBytesPerSecond"] = transfer.speed }
        if transfer.eta > 0 && transfer.status == "downloading" { fields["etaSeconds"] = transfer.eta }
        return fields
    }

    private static func retry(_ id: String) -> DemoTransport.Answer {
        let done = transfers.withLock { all -> Bool in
            guard let index = all.firstIndex(where: { $0.id == id && $0.actions.contains("retry") }) else { return false }
            all[index].status = "retrying"
            all[index].failed = false
            all[index].actions = ["cancel"]
            return true
        }
        return done ? json(["ok": true, "action": "retry_transfer"]) : failure(404, "not_found", "No such transfer")
    }

    // MARK: Audiobooks

    /// The place each demo audiobook holds, as Storyteller would: Dark Matter
    /// starts part-way into its second track, The Alloy of Law has none.
    private static let places = Mutex<[String: (track: Int, offsetMs: Int64, completed: Bool)]>(
        ["demo-dm": (1, 30_000, false)])

    private static func audio(method: String, parts: [String], body: Data?) -> DemoTransport.Answer {
        guard let book = audiobooks.first(where: { $0.workId == parts[3] && $0.sourceItemId == parts[5] }) else {
            return failure(404, "not_found", "No such audiobook")
        }
        switch (method, parts.count) {
        case ("GET", 7):
            return json(manifestFields(book))
        case ("GET", 8) where parts[7] == "position":
            return json(positionFields(book))
        case ("POST", 8) where parts[7] == "position":
            return savePosition(book, body: body)
        case ("GET", 9) where parts[7] == "tracks":
            // A track's bytes, which the app keeps on the device (#37): its tone.
            guard let index = Int(parts[8]), book.tracksMs.indices.contains(index) else {
                return failure(404, "not_found", "No such track")
            }
            return DemoTransport.Answer(200, data: DemoAudio.wav(milliseconds: book.tracksMs[index],
                                                                 frequency: DemoAudio.tone(index)), type: "audio/wav")
        default:
            return failure(404, "not_found", "No such route in the demo hub")
        }
    }

    static func manifestFields(_ book: Audiobook) -> [String: Any] {
        var fields = plainManifestFields(book)
        if book.aligned {
            fields["aligned"] = true
            fields["alignment"] = DemoReadAlong.alignment()
            fields["chapters"] = DemoReadAlong.bookChapters()
            // Its pack has a word set (#66), as the hub says it.
            if DemoReadAlong.servesWords { fields["wordLevel"] = true }
        }
        return fields
    }

    private static func plainManifestFields(_ book: Audiobook) -> [String: Any] {
        ["workId": book.workId, "sourceItemId": book.sourceItemId, "revision": revision, "narrator": book.narrator,
         "totalMs": book.tracksMs.reduce(0, +), "aligned": false,
         "tracks": book.tracksMs.enumerated().map { index, length in
             ["index": index, "id": trackId(book.sourceItemId, index), "title": String(format: "Track %02d/%02d", index + 1, book.tracksMs.count),
              "durationMs": length, "bytes": DemoAudio.byteCount(milliseconds: length), "mime": "audio/wav",
              "etag": "\"demo\(index)\""] as [String: Any]
         },
         "chapters": book.chapters.map { ["title": $0.title, "startMs": $0.startMs, "track": $0.track,
                                          "source": ReadingAudioChapter.marks] as [String: Any] },
         "cache": ["hit": false, "ageSeconds": 0]]
    }

    private static func positionFields(_ book: Audiobook) -> [String: Any] {
        guard let place = places.withLock({ $0[book.sourceItemId] }) else {
            return ["workId": book.workId, "sourceItemId": book.sourceItemId, "position": NSNull()]
        }
        let before = book.tracksMs.prefix(place.track).reduce(0, +)
        return ["workId": book.workId, "sourceItemId": book.sourceItemId,
                "position": ["trackId": trackId(book.sourceItemId, place.track), "track": place.track, "offsetMs": place.offsetMs,
                             "globalMs": before + place.offsetMs, "completed": place.completed, "exact": true, "form": "audio",
                             "timestamp": 1_791_000_000_000] as [String: Any]]
    }

    /// The hub's rules: a known track and an offset within it; `expected`
    /// absent skips the check, null means nothing is saved, a place must be
    /// the place held now (409 `reading_position_conflict` otherwise).
    private static func savePosition(_ book: Audiobook, body: Data?) -> DemoTransport.Answer {
        guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              Set(fields.keys).isSubset(of: ["trackId", "offsetMs", "completed", "timestamp", "expected"]),
              let trackId = fields["trackId"] as? String, let offset = (fields["offsetMs"] as? NSNumber)?.int64Value, offset >= 0,
              let track = book.tracksMs.indices.first(where: { self.trackId(book.sourceItemId, $0) == trackId }),
              offset <= book.tracksMs[track] + 1_000 else {
            return failure(400, "invalid_request", "invalid audiobook reading position")
        }
        let completed = (fields["completed"] as? Bool) ?? false
        return places.withLock { all -> DemoTransport.Answer in
            let held = all[book.sourceItemId]
            if let expected = fields["expected"] {
                let holds: Bool
                if expected is NSNull {
                    holds = held == nil
                } else if let want = expected as? [String: Any], let wantTrack = want["trackId"] as? String,
                          let wantOffset = (want["offsetMs"] as? NSNumber)?.int64Value, let held {
                    let heldTrack = self.trackId(book.sourceItemId, held.track)
                    let heldOffset = held.completed ? book.tracksMs[held.track] : held.offsetMs
                    holds = heldTrack == wantTrack && heldOffset == wantOffset
                } else {
                    holds = false
                }
                if !holds {
                    return failure(409, "reading_position_conflict",
                                   "Reading progress changed on another device. Choose which position to continue from.")
                }
            }
            if completed {
                let last = book.tracksMs.count - 1
                all[book.sourceItemId] = (last, book.tracksMs[last], true)
            } else {
                all[book.sourceItemId] = (track, min(offset, book.tracksMs[track]), false)
            }
            return json(["ok": true, "action": "save_audio_position", "timestamp": 1_791_000_000_000])
        }
    }

    /// Moves a demo book's place as another device would, for a UI test of
    /// the choice it leads to.
    public static func moveElsewhere(sourceItemId: String, track: Int, offsetMs: Int64) {
        places.withLock { $0[sourceItemId] = (track, offsetMs, false) }
    }

    // MARK: Pictures

    /// A cover drawn from its name: a colour of its own, a lighter band where
    /// a title would be. "sq-" covers are square, as an audiobook's are.
    static func cover(_ name: String) -> Data {
        let square = name.hasPrefix("sq-") || name == "alloy" || name == "demo-alloy"
        let width = 240, height = square ? 240 : 360
        let space = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return Data() }
        let hue = Double(fnv(name) % 360) / 360
        let top = color(hue: hue, saturation: 0.5, brightness: 0.62)
        let bottom = color(hue: (hue + 0.07).truncatingRemainder(dividingBy: 1), saturation: 0.7, brightness: 0.22)
        if let gradient = CGGradient(colorsSpace: space, colors: [top, bottom] as CFArray, locations: [0, 1]) {
            context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: height), end: .zero, options: [])
        }
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.18))
        context.fill(CGRect(x: 18, y: height * 2 / 3 - 18, width: width - 36, height: 26))
        context.fill(CGRect(x: 40, y: height * 2 / 3 - 52, width: width - 80, height: 14))
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.12))
        context.fillEllipse(in: CGRect(x: width / 2 - 40, y: height / 4 - 40, width: 80, height: 80))
        guard let image = context.makeImage() else { return Data() }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { return Data() }
        CGImageDestinationAddImage(destination, image, nil)
        CGImageDestinationFinalize(destination)
        return data as Data
    }

    private static func color(hue: Double, saturation: Double, brightness: Double) -> CGColor {
        let sector = hue * 6, index = Int(sector) % 6, fraction = sector - Double(Int(sector))
        let p = brightness * (1 - saturation), q = brightness * (1 - saturation * fraction)
        let t = brightness * (1 - saturation * (1 - fraction))
        let (red, green, blue) = switch index {
        case 0: (brightness, t, p)
        case 1: (q, brightness, p)
        case 2: (p, brightness, t)
        case 3: (p, q, brightness)
        case 4: (t, p, brightness)
        default: (brightness, p, q)
        }
        return CGColor(red: red, green: green, blue: blue, alpha: 1)
    }

    // MARK: Plumbing

    private static func json(_ object: [String: Any]) -> DemoTransport.Answer {
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8)
        return DemoTransport.Answer(200, data: data, type: "application/json")
    }

    private static func failure(_ status: Int, _ code: String, _ message: String) -> DemoTransport.Answer {
        DemoTransport.Answer(status, #"{"error":{"code":"\#(code)","message":"\#(message)"}}"#)
    }

    private static func value(_ name: String, in query: String) -> String {
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2, kv[0] == name { return kv[1].removingPercentEncoding ?? kv[1] }
        }
        return ""
    }
}

/// The demo's audiobook tracks: a quiet tone a few seconds long per track,
/// written by the app to a file it plays (AVPlayer cannot fetch from the demo
/// hub, which answers only the app's own requests), and served by the demo
/// hub to the app's track cache (#37). A WAV, so it needs no encoder: 8 kHz,
/// 16-bit mono, a soft tone that changes with the track, so a change of
/// track can be heard.
public enum DemoAudio {
    public static let sampleRate = 8_000

    /// Track `index`'s tone, in hertz.
    public static func tone(_ index: Int) -> Double { 220 + 55 * Double(index % 4) }

    public static func byteCount(milliseconds: Int64) -> Int64 {
        44 + Int64(sampleRate) * 2 * max(0, milliseconds) / 1_000
    }

    /// A WAV file of `milliseconds` of a tone at `frequency` hertz.
    public static func wav(milliseconds: Int64, frequency: Double) -> Data {
        let samples = Int(Int64(sampleRate) * max(0, milliseconds) / 1_000)
        var data = Data(capacity: 44 + samples * 2)
        func append32(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        func append16(_ value: UInt16) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        data.append(contentsOf: Array("RIFF".utf8))
        append32(UInt32(36 + samples * 2))
        data.append(contentsOf: Array("WAVEfmt ".utf8))
        append32(16)
        append16(1)
        append16(1)
        append32(UInt32(sampleRate))
        append32(UInt32(sampleRate * 2))
        append16(2)
        append16(16)
        data.append(contentsOf: Array("data".utf8))
        append32(UInt32(samples * 2))
        var pcm = [Int16](repeating: 0, count: samples)
        for index in 0..<samples {
            // A tone that swells and fades once a second, quiet enough to leave on.
            let time = Double(index) / Double(sampleRate)
            let envelope = 0.5 - 0.5 * cos(2 * .pi * time)
            pcm[index] = Int16(1_800 * envelope * sin(2 * .pi * frequency * time))
        }
        // Apple's processors are little-endian, as a WAV's samples are.
        pcm.withUnsafeBytes { data.append(contentsOf: $0) }
        return data
    }
}
