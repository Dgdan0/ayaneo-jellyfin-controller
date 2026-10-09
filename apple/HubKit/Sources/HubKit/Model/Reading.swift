import Foundation

// The Books side's responses (#25), field for field with the hub's Go types
// (`reading_catalog.go`, `reading_requests.go`, `reading_audio.go`) and with
// Android's `model/Reading.kt`. Every field is defaulted, as on Android, so a
// hub that adds or loosens a field breaks neither client. `ReadingLibrary`
// and the libraries' list are in `Library.swift`, beside the media side's.

/// What a reading title is, and the filters Books Discover offers.
public enum ReadingType {
    public static let all = "all"
    public static let ebook = "ebook"
    public static let audiobook = "audiobook"
    public static let comic = "comic"
    public static let manga = "manga"
    public static let lightNovel = "light_novel"

    /// Books Discover's capsule, in Android's order.
    public static let filters: [(id: String, label: String)] = [
        (all, "All"), (ebook, "Ebooks"), (audiobook, "Audiobooks"), (comic, "Comics"), (manga, "Manga"),
        (lightNovel, "Light novels"),
    ]

    /// "Ebooks", "Light novels"; a kind the app does not know yet stays readable.
    public static func label(_ wire: String) -> String {
        if let known = filters.first(where: { $0.id == wire }) { return known.label }
        let words = wire.replacingOccurrences(of: "_", with: " ")
        return words.prefix(1).uppercased() + words.dropFirst()
    }

    /// One title's kind: "Ebook", "Audiobook", "Comic", "Manga", "Light novel".
    public static func one(_ wire: String) -> String {
        switch wire {
        case ebook: "Ebook"
        case audiobook: "Audiobook"
        case comic: "Comic"
        case manga: "Manga"
        case lightNovel: "Light novel"
        default: label(wire)
        }
    }

    /// Where BookKeeprr found a title, by name: "Open Library", "AniList".
    public static func source(_ wire: String) -> String {
        switch wire.lowercased() {
        case "openlibrary": "Open Library"
        case "googlebooks": "Google Books"
        case "itunes": "Apple Books"
        case "anilist": "AniList"
        case "mangadex": "MangaDex"
        case "comicvine": "Comic Vine"
        default: label(wire)
        }
    }
}

/// How far through a book, a series or a comic run.
public struct ReadingProgress: Decodable, Equatable, Sendable, Hashable {
    /// 0…1.
    public var percentage: Double
    public var completed: Bool
    /// A comic's page, of `total`.
    public var current: Int
    public var total: Int
    /// When it was read last: ISO 8601, or Storyteller's "2026-09-27 03:16:47"
    /// with no zone (`ReadingShelves.timestamp` reads both).
    public var updatedAt: String

    public init(percentage: Double = 0, completed: Bool = false, current: Int = 0, total: Int = 0, updatedAt: String = "") {
        self.percentage = percentage
        self.completed = completed
        self.current = current
        self.total = total
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey { case percentage, completed, current, total, updatedAt }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(percentage: c.value(.percentage, 0), completed: c.value(.completed, false), current: c.value(.current, 0),
                  total: c.value(.total, 0), updatedAt: c.value(.updatedAt, ""))
    }
}

/// One way a work can be opened: an ebook, an audiobook, a read-along
/// edition, a comic. Storyteller's three editions of a book share its
/// `sourceItemId`.
public struct ReadingEdition: Decodable, Equatable, Sendable, Hashable {
    public var id: String
    public var workId: String
    /// "storyteller" or "kavita".
    public var source: String
    public var sourceItemId: String
    /// "ebook" (Kavita's "book"), "audiobook", "readaloud", "comic", "manga".
    public var kind: String
    public var format: String
    public var narrator: String
    public var pageCount: Int
    public var durationMs: Int64
    /// "available" when it can be opened; "missing", "processing", … otherwise.
    public var availability: String

    public init(id: String = "", workId: String = "", source: String = "", sourceItemId: String = "", kind: String = "book",
                format: String = "", narrator: String = "", pageCount: Int = 0, durationMs: Int64 = 0,
                availability: String = "") {
        self.id = id
        self.workId = workId
        self.source = source
        self.sourceItemId = sourceItemId
        self.kind = kind
        self.format = format
        self.narrator = narrator
        self.pageCount = pageCount
        self.durationMs = durationMs
        self.availability = availability
    }

    enum CodingKeys: String, CodingKey {
        case id, workId, source, sourceItemId, kind, format, narrator, pageCount, durationMs, availability
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), workId: c.value(.workId, ""), source: c.value(.source, ""),
                  sourceItemId: c.value(.sourceItemId, ""), kind: c.value(.kind, "book"), format: c.value(.format, ""),
                  narrator: c.value(.narrator, ""), pageCount: c.value(.pageCount, 0), durationMs: c.value(.durationMs, 0),
                  availability: c.value(.availability, ""))
    }
}

/// A book of a series, or an issue of a comic run, on its parent's page.
public struct ReadingSectionItem: Decodable, Equatable, Sendable, Hashable {
    public var sourceItemId: String
    /// The book's own work; empty for a book the library does not have, and
    /// for a Kavita issue, which opens through its run.
    public var workId: String
    public var title: String
    /// "6", "1.5"; an issue's number.
    public var number: String
    public var kind: String
    public var artwork: String
    public var authors: [String]
    public var pageCount: Int
    public var progress: ReadingProgress?
    public var availability: String
    /// What this book can be opened as: "ebook", "audiobook", "readaloud".
    public var formats: [String]

    public init(sourceItemId: String = "", workId: String = "", title: String = "", number: String = "", kind: String = "book",
                artwork: String = "", authors: [String] = [], pageCount: Int = 0, progress: ReadingProgress? = nil,
                availability: String = "available", formats: [String] = []) {
        self.sourceItemId = sourceItemId
        self.workId = workId
        self.title = title
        self.number = number
        self.kind = kind
        self.artwork = artwork
        self.authors = authors
        self.pageCount = pageCount
        self.progress = progress
        self.availability = availability
        self.formats = formats
    }

    /// In the library, with a page of its own.
    public var isAvailable: Bool {
        availability.caseInsensitiveCompare("available") == .orderedSame && !workId.trimmingCharacters(in: .whitespaces).isEmpty
    }

    enum CodingKeys: String, CodingKey {
        case sourceItemId, workId, title, number, kind, artwork, authors, pageCount, progress, availability, formats
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(sourceItemId: c.value(.sourceItemId, ""), workId: c.value(.workId, ""), title: c.value(.title, ""),
                  number: c.value(.number, ""), kind: c.value(.kind, "book"), artwork: c.value(.artwork, ""),
                  authors: c.value(.authors, []), pageCount: c.value(.pageCount, 0), progress: c.optional(.progress),
                  availability: c.value(.availability, "available"), formats: c.value(.formats, []))
    }
}

/// A part of a work's page: a series' books, a comic run's volume.
public struct ReadingSection: Decodable, Equatable, Sendable, Hashable {
    public var id: String
    public var title: String
    /// A volume's number (1961); 0 when it has none.
    public var number: Double
    public var items: [ReadingSectionItem]

    public init(id: String = "", title: String = "", number: Double = 0, items: [ReadingSectionItem] = []) {
        self.id = id
        self.title = title
        self.number = number
        self.items = items
    }

    enum CodingKeys: String, CodingKey { case id, title, number, items }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), title: c.value(.title, ""), number: c.value(.number, 0), items: c.value(.items, []))
    }
}

/// Where to carry on: a series' book being read, a comic run's issue.
public struct ReadingContinue: Decodable, Equatable, Sendable, Hashable {
    public var workId: String
    public var source: String
    public var sourceItemId: String
    public var title: String
    public var number: String
    public var percentage: Double
    public var artwork: String
    public var kind: String

    public init(workId: String = "", source: String = "", sourceItemId: String = "", title: String = "", number: String = "",
                percentage: Double = 0, artwork: String = "", kind: String = "book") {
        self.workId = workId
        self.source = source
        self.sourceItemId = sourceItemId
        self.title = title
        self.number = number
        self.percentage = percentage
        self.artwork = artwork
        self.kind = kind
    }

    enum CodingKeys: String, CodingKey { case workId, source, sourceItemId, title, number, percentage, artwork, kind }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(workId: c.value(.workId, ""), source: c.value(.source, ""), sourceItemId: c.value(.sourceItemId, ""),
                  title: c.value(.title, ""), number: c.value(.number, ""), percentage: c.value(.percentage, 0),
                  artwork: c.value(.artwork, ""), kind: c.value(.kind, "book"))
    }
}

/// An author page a book links to.
public struct ReadingAuthorRef: Decodable, Equatable, Sendable, Hashable {
    public var id: String
    public var name: String

    public init(id: String = "", name: String = "") {
        self.id = id
        self.name = name
    }

    enum CodingKeys: String, CodingKey { case id, name }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), name: c.value(.name, ""))
    }
}

/// A book, a series ("collection") or a comic run: a library card, and with
/// its sections and editions, `GET /v1/reading/works/{id}`.
public struct ReadingWork: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var libraryId: String
    /// "work" or "collection" (a series).
    public var entityType: String
    /// "book", "audiobook", "comic", "manga".
    public var kind: String
    public var title: String
    public var sortTitle: String
    public var authors: [String]
    public var series: String
    public var seriesIndex: Double
    /// A book's series page, sent with a book's own detail.
    public var seriesId: String
    public var authorRefs: [ReadingAuthorRef]
    public var overview: String
    public var artwork: String
    public var genres: [String]
    public var year: Int
    public var addedAt: String
    public var bookCount: Int
    public var languages: [String]
    public var editions: [ReadingEdition]
    public var progress: ReadingProgress?
    /// What it can be opened as: "ebook", "audiobook", "readaloud", "comic".
    public var availability: [String]
    public var sections: [ReadingSection]
    public var continueAt: ReadingContinue?
    public var partial: [Partial]
    public var cache: CacheInfo
    /// What readers make of it (#39); only on the book's own page, nil when nothing is known.
    public var community: ReadingCommunity?
    /// This profile's rating, finish date, read count, status and shelves (#39);
    /// only on the book's own page, nil when there is nothing to say.
    public var you: ReadingYou?

    public init(id: String = "", libraryId: String = "", entityType: String = "work", kind: String = "book", title: String = "",
                sortTitle: String = "", authors: [String] = [], series: String = "", seriesIndex: Double = 0,
                seriesId: String = "", authorRefs: [ReadingAuthorRef] = [], overview: String = "", artwork: String = "",
                genres: [String] = [], year: Int = 0, addedAt: String = "", bookCount: Int = 0, languages: [String] = [],
                editions: [ReadingEdition] = [], progress: ReadingProgress? = nil, availability: [String] = [],
                sections: [ReadingSection] = [], continueAt: ReadingContinue? = nil, partial: [Partial] = [],
                cache: CacheInfo = CacheInfo(), community: ReadingCommunity? = nil, you: ReadingYou? = nil) {
        self.id = id
        self.libraryId = libraryId
        self.entityType = entityType
        self.kind = kind
        self.title = title
        self.sortTitle = sortTitle
        self.authors = authors
        self.series = series
        self.seriesIndex = seriesIndex
        self.seriesId = seriesId
        self.authorRefs = authorRefs
        self.overview = overview
        self.artwork = artwork
        self.genres = genres
        self.year = year
        self.addedAt = addedAt
        self.bookCount = bookCount
        self.languages = languages
        self.editions = editions
        self.progress = progress
        self.availability = availability
        self.sections = sections
        self.continueAt = continueAt
        self.partial = partial
        self.cache = cache
        self.community = community
        self.you = you
    }

    public var isSeries: Bool { entityType == "collection" }

    /// Its authors, "Pierce Brown, Someone Else".
    public var byline: String { authors.joined(separator: ", ") }

    /// "Red Rising · Pierce Brown", then the year, then the kind.
    public var subtitle: String {
        var parts: [String] = []
        if !series.trimmingCharacters(in: .whitespaces).isEmpty { parts.append(series) }
        if !byline.trimmingCharacters(in: .whitespaces).isEmpty { parts.append(byline) }
        if parts.isEmpty && year > 0 { parts.append(String(year)) }
        if parts.isEmpty { parts.append(ReadingType.label(kind)) }
        return parts.joined(separator: " · ")
    }

    /// "6", "1.5", or empty outside a numbered series.
    public var seriesNumber: String {
        guard !series.trimmingCharacters(in: .whitespaces).isEmpty, seriesIndex > 0 else { return "" }
        if seriesIndex.truncatingRemainder(dividingBy: 1) == 0 { return String(Int64(seriesIndex)) }
        return String(seriesIndex)
    }

    /// Under a book's card: "Red Rising #6" inside a series, otherwise who
    /// wrote it.
    public var cardSubtitle: String {
        if entityType != "collection" && !seriesNumber.isEmpty { return "\(series) #\(seriesNumber)" }
        return byline.trimmingCharacters(in: .whitespaces).isEmpty ? subtitle : byline
    }

    enum CodingKeys: String, CodingKey {
        case id, libraryId, entityType, kind, title, sortTitle, authors, series, seriesIndex, seriesId, authorRefs, overview,
             artwork, genres, year, addedAt, bookCount, languages, editions, progress, availability, sections, partial, cache,
             community, you
        case continueAt = "continue"
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: c.value(.id, ""), libraryId: c.value(.libraryId, ""), entityType: c.value(.entityType, "work"),
            kind: c.value(.kind, "book"), title: c.value(.title, ""), sortTitle: c.value(.sortTitle, ""),
            authors: c.value(.authors, []), series: c.value(.series, ""), seriesIndex: c.value(.seriesIndex, 0),
            seriesId: c.value(.seriesId, ""), authorRefs: c.value(.authorRefs, []), overview: c.value(.overview, ""),
            artwork: c.value(.artwork, ""), genres: c.value(.genres, []), year: c.value(.year, 0),
            addedAt: c.value(.addedAt, ""), bookCount: c.value(.bookCount, 0), languages: c.value(.languages, []),
            editions: c.value(.editions, []), progress: c.optional(.progress), availability: c.value(.availability, []),
            sections: c.value(.sections, []), continueAt: c.optional(.continueAt), partial: c.value(.partial, []),
            cache: c.value(.cache, CacheInfo()), community: c.optional(.community), you: c.optional(.you))
    }
}

/// What readers make of a book (#39): its average rating, 0 to 5, how many
/// rated it when the source says, and where that came from ("hardcover", or
/// "goodreads" when only the profile's export knows it).
public struct ReadingCommunity: Decodable, Equatable, Sendable {
    public var rating: Double
    public var count: Int?
    public var source: String

    public init(rating: Double, count: Int? = nil, source: String = "") {
        self.rating = rating
        self.count = count
        self.source = source
    }

    enum CodingKeys: String, CodingKey { case rating, count, source }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(rating: c.value(.rating, 0), count: c.optional(.count), source: c.value(.source, ""))
    }
}

/// A profile's own reading of a book (#39): from its Goodreads export, or
/// set in an app, which wins and stays won.
public struct ReadingYou: Decodable, Equatable, Sendable {
    /// 1 to 5; nil is not rated.
    public var rating: Int?
    /// "2025-09"; nil is not known.
    public var finished: String?
    /// 1 or more; nil is not known.
    public var readCount: Int?
    /// Goodreads shelves in the export's order, never the three statuses.
    public var shelves: [String]
    /// "read", "to-read" or "currently-reading".
    public var status: String?
    /// "app" once anything was set from an app, else "goodreads".
    public var source: String

    public init(rating: Int? = nil, finished: String? = nil, readCount: Int? = nil, shelves: [String] = [],
                status: String? = nil, source: String = "app") {
        self.rating = rating
        self.finished = finished
        self.readCount = readCount
        self.shelves = shelves
        self.status = status
        self.source = source
    }

    enum CodingKeys: String, CodingKey { case rating, finished, readCount, shelves, status, source }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(rating: c.optional(.rating), finished: c.optional(.finished), readCount: c.optional(.readCount),
                  shelves: c.value(.shelves, []), status: c.optional(.status), source: c.value(.source, ""))
    }
}

/// `PATCH /v1/reading/works/{id}/you`'s answer: what is left to say, or nil.
public struct ReadingYouResponse: Decodable, Equatable, Sendable {
    public var workId: String
    public var you: ReadingYou?

    enum CodingKeys: String, CodingKey { case workId, you }

    public init(workId: String, you: ReadingYou?) {
        self.workId = workId
        self.you = you
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(workId: c.value(.workId, ""), you: c.optional(.you))
    }
}

/// A change to a profile's rating, finish date or read count (#39): a key
/// set sends its value, cleared sends null, kept sends nothing, as the hub
/// reads it.
public struct ReadingYouChange: Equatable, Sendable {
    public enum Value<T: Equatable & Sendable>: Equatable, Sendable {
        case keep, set(T), clear
    }

    public var rating: Value<Int> = .keep
    public var finished: Value<String> = .keep
    public var readCount: Value<Int> = .keep

    public init(rating: Value<Int> = .keep, finished: Value<String> = .keep, readCount: Value<Int> = .keep) {
        self.rating = rating
        self.finished = finished
        self.readCount = readCount
    }

    public var isEmpty: Bool { rating == .keep && finished == .keep && readCount == .keep }

    /// The body: only the keys that change, null for a cleared one.
    public func body() -> Data {
        var fields: [String: Any] = [:]
        func put<T: Equatable & Sendable>(_ key: String, _ value: Value<T>) {
            switch value {
            case .keep: break
            case .set(let v): fields[key] = v
            case .clear: fields[key] = NSNull()
            }
        }
        put("rating", rating)
        put("finished", finished)
        put("readCount", readCount)
        return (try? JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys])) ?? Data("{}".utf8)
    }

    /// `you` as the hub will make it, for the page to show at once.
    public func applied(to you: ReadingYou?) -> ReadingYou? {
        var next = you ?? ReadingYou(source: "app")
        switch rating { case .keep: break; case .set(let v): next.rating = v; case .clear: next.rating = nil }
        switch readCount { case .keep: break; case .set(let v): next.readCount = v; case .clear: next.readCount = nil }
        switch finished {
        case .keep: break
        case .set(let v):
            next.finished = v
            if readCount == .keep && next.readCount == nil { next.readCount = 1 }
            if next.status != "currently-reading" { next.status = "read" }
        case .clear:
            next.finished = nil
            if next.status == "read" { next.status = nil }
        }
        if !isEmpty { next.source = "app" }
        let empty = next.rating == nil && next.finished == nil && next.readCount == nil && next.status == nil && next.shelves.isEmpty
        return empty ? nil : next
    }
}

/// `GET /v1/reading/libraries/{id}/items`: one page of a library, 60 a page.
public struct ReadingLibraryItemsResponse: Decodable, Equatable, Sendable {
    public var libraryId: String
    public var page: Int
    public var pageSize: Int
    public var total: Int
    public var totalPages: Int
    public var hasMore: Bool
    public var items: [ReadingWork]
    public var partial: [Partial]
    public var cache: CacheInfo

    public init(libraryId: String = "", page: Int = 1, pageSize: Int = 60, total: Int = 0, totalPages: Int = 0,
                hasMore: Bool = false, items: [ReadingWork] = [], partial: [Partial] = [], cache: CacheInfo = CacheInfo()) {
        self.libraryId = libraryId
        self.page = page
        self.pageSize = pageSize
        self.total = total
        self.totalPages = totalPages
        self.hasMore = hasMore
        self.items = items
        self.partial = partial
        self.cache = cache
    }

    enum CodingKeys: String, CodingKey { case libraryId, page, pageSize, total, totalPages, hasMore, items, partial, cache }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(libraryId: c.value(.libraryId, ""), page: c.value(.page, 1), pageSize: c.value(.pageSize, 60),
                  total: c.value(.total, 0), totalPages: c.value(.totalPages, 0), hasMore: c.value(.hasMore, false),
                  items: c.value(.items, []), partial: c.value(.partial, []), cache: c.value(.cache, CacheInfo()))
    }
}

/// An author on a library's Authors view, and with `items`, their shelf.
public struct ReadingAuthor: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var artwork: String
    /// The shelf: "2 series", "6 books".
    public var seriesCount: Int
    public var bookCount: Int
    /// What an older hub sends instead of the two counts.
    public var total: Int
    public var page: Int
    public var totalPages: Int
    public var items: [ReadingWork]

    public init(id: String = "", name: String = "", artwork: String = "", seriesCount: Int = 0, bookCount: Int = 0,
                total: Int = 0, page: Int = 1, totalPages: Int = 0, items: [ReadingWork] = []) {
        self.id = id
        self.name = name
        self.artwork = artwork
        self.seriesCount = seriesCount
        self.bookCount = bookCount
        self.total = total
        self.page = page
        self.totalPages = totalPages
        self.items = items
    }

    enum CodingKeys: String, CodingKey { case id, name, artwork, seriesCount, bookCount, total, page, totalPages, items }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), name: c.value(.name, ""), artwork: c.value(.artwork, ""),
                  seriesCount: c.value(.seriesCount, 0), bookCount: c.value(.bookCount, 0), total: c.value(.total, 0),
                  page: c.value(.page, 1), totalPages: c.value(.totalPages, 0), items: c.value(.items, []))
    }
}

/// `GET /v1/reading/libraries/{id}/authors`.
public struct ReadingAuthorsResponse: Decodable, Equatable, Sendable {
    public var authors: [ReadingAuthor]
    public var page: Int
    public var total: Int
    public var totalPages: Int
    public var partial: [Partial]
    public var cache: CacheInfo

    public init(authors: [ReadingAuthor] = [], page: Int = 1, total: Int = 0, totalPages: Int = 0, partial: [Partial] = [],
                cache: CacheInfo = CacheInfo()) {
        self.authors = authors
        self.page = page
        self.total = total
        self.totalPages = totalPages
        self.partial = partial
        self.cache = cache
    }

    enum CodingKeys: String, CodingKey { case authors, page, total, totalPages, partial, cache }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(authors: c.value(.authors, []), page: c.value(.page, 1), total: c.value(.total, 0),
                  totalPages: c.value(.totalPages, 0), partial: c.value(.partial, []), cache: c.value(.cache, CacheInfo()))
    }
}

/// `GET /v1/reading/resolve`: the library's own work for a Discover title.
public struct ReadingResolveResponse: Decodable, Equatable, Sendable {
    public var workId: String
    public var resolved: Bool

    public init(workId: String = "", resolved: Bool = false) {
        self.workId = workId
        self.resolved = resolved
    }

    enum CodingKeys: String, CodingKey { case workId, resolved }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(workId: c.value(.workId, ""), resolved: c.value(.resolved, false))
    }
}

// MARK: Discover and requests (BookKeeprr)

/// A title BookKeeprr knows: a Discover card or a search result.
public struct ReadingItem: Decodable, Equatable, Sendable, Identifiable, Hashable {
    /// "reading:c87a…": what a request names.
    public var key: String
    public var contentType: String
    public var title: String
    public var author: String
    public var year: Int
    public var isbn: String
    public var source: String
    public var sourceId: String
    public var cover: String
    public var description: String
    /// BookKeeprr tracks it, which is not the same as a file being imported.
    public var inLibrary: Bool
    /// "detail", "request".
    public var actions: [String]

    public var id: String { key.isEmpty ? contentType + ":" + title : key }

    public init(key: String = "", contentType: String = "ebook", title: String = "", author: String = "", year: Int = 0,
                isbn: String = "", source: String = "", sourceId: String = "", cover: String = "", description: String = "",
                inLibrary: Bool = false, actions: [String] = []) {
        self.key = key
        self.contentType = contentType
        self.title = title
        self.author = author
        self.year = year
        self.isbn = isbn
        self.source = source
        self.sourceId = sourceId
        self.cover = cover
        self.description = description
        self.inLibrary = inLibrary
        self.actions = actions
    }

    /// "James Clear · 2016", else the kind.
    public var subtitle: String {
        var parts: [String] = []
        if !author.trimmingCharacters(in: .whitespaces).isEmpty { parts.append(author) }
        if year > 0 { parts.append(String(year)) }
        if parts.isEmpty { parts.append(ReadingType.label(contentType)) }
        return parts.joined(separator: " · ")
    }

    public var canRequest: Bool { actions.contains("request") }

    enum CodingKeys: String, CodingKey {
        case key, contentType, title, author, year, isbn, source, sourceId, cover, description, inLibrary, actions
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(key: c.value(.key, ""), contentType: c.value(.contentType, "ebook"), title: c.value(.title, ""),
                  author: c.value(.author, ""), year: c.value(.year, 0), isbn: c.value(.isbn, ""), source: c.value(.source, ""),
                  sourceId: c.value(.sourceId, ""), cover: c.value(.cover, ""), description: c.value(.description, ""),
                  inLibrary: c.value(.inLibrary, false), actions: c.value(.actions, []))
    }
}

/// One of Books Discover's rows.
public struct ReadingDiscoverRow: Decodable, Equatable, Sendable, Identifiable {
    /// The row's id, the same for each kind ("trending"); `rowKey` tells them apart.
    public var id: String
    public var title: String
    public var meta: String
    public var contentType: String
    public var page: Int
    public var hasMore: Bool
    public var items: [ReadingItem]

    public init(id: String = "", title: String = "", meta: String = "", contentType: String = "ebook", page: Int = 1,
                hasMore: Bool = false, items: [ReadingItem] = []) {
        self.id = id
        self.title = title
        self.meta = meta
        self.contentType = contentType
        self.page = page
        self.hasMore = hasMore
        self.items = items
    }

    /// The kind and the row: under All two rows are called "trending".
    public var rowKey: String { contentType + ":" + id }

    enum CodingKeys: String, CodingKey { case id, title, meta, contentType, page, hasMore, items }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), title: c.value(.title, ""), meta: c.value(.meta, ""),
                  contentType: c.value(.contentType, "ebook"), page: c.value(.page, 1), hasMore: c.value(.hasMore, false),
                  items: c.value(.items, []))
    }
}

/// `GET /v1/reading/discover?type=`, and one row's next page.
public struct ReadingDiscoverResponse: Decodable, Equatable, Sendable {
    public var rows: [ReadingDiscoverRow]
    public var partial: [Partial]
    public var cache: CacheInfo

    public init(rows: [ReadingDiscoverRow] = [], partial: [Partial] = [], cache: CacheInfo = CacheInfo()) {
        self.rows = rows
        self.partial = partial
        self.cache = cache
    }

    enum CodingKeys: String, CodingKey { case rows, partial, cache }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(rows: c.value(.rows, []), partial: c.value(.partial, []), cache: c.value(.cache, CacheInfo()))
    }
}

/// `GET /v1/reading/search?q=&type=`: close matches, and broader ones.
public struct ReadingSearchResponse: Decodable, Equatable, Sendable {
    public var query: String
    public var contentType: String
    public var results: [ReadingItem]
    public var broaderResults: [ReadingItem]
    public var partial: [Partial]
    public var cache: CacheInfo

    public init(query: String = "", contentType: String = ReadingType.all, results: [ReadingItem] = [],
                broaderResults: [ReadingItem] = [], partial: [Partial] = [], cache: CacheInfo = CacheInfo()) {
        self.query = query
        self.contentType = contentType
        self.results = results
        self.broaderResults = broaderResults
        self.partial = partial
        self.cache = cache
    }

    enum CodingKeys: String, CodingKey { case query, contentType, results, broaderResults, partial, cache }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(query: c.value(.query, ""), contentType: c.value(.contentType, ReadingType.all),
                  results: c.value(.results, []), broaderResults: c.value(.broaderResults, []),
                  partial: c.value(.partial, []), cache: c.value(.cache, CacheInfo()))
    }
}

/// What a request can ask BookKeeprr for: "This book", "Choose books from series".
public struct ReadingRequestMode: Decodable, Equatable, Sendable, Hashable {
    public var id: String
    public var label: String
    public var requiresTotalBooks: Bool
    public var requiresSeriesPreview: Bool

    public init(id: String = "", label: String = "", requiresTotalBooks: Bool = false, requiresSeriesPreview: Bool = false) {
        self.id = id
        self.label = label
        self.requiresTotalBooks = requiresTotalBooks
        self.requiresSeriesPreview = requiresSeriesPreview
    }

    enum CodingKeys: String, CodingKey { case id, label, requiresTotalBooks, requiresSeriesPreview }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), label: c.value(.label, ""), requiresTotalBooks: c.value(.requiresTotalBooks, false),
                  requiresSeriesPreview: c.value(.requiresSeriesPreview, false))
    }
}

public struct ReadingQualityProfile: Decodable, Equatable, Sendable, Hashable {
    public var id: Int
    public var label: String
    public var isDefault: Bool
    public var preferCompleteBatches: Bool

    public init(id: Int = 0, label: String = "", isDefault: Bool = false, preferCompleteBatches: Bool = false) {
        self.id = id
        self.label = label
        self.isDefault = isDefault
        self.preferCompleteBatches = preferCompleteBatches
    }

    enum CodingKeys: String, CodingKey {
        case id, label, preferCompleteBatches
        case isDefault = "default"
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, 0), label: c.value(.label, ""), isDefault: c.value(.isDefault, false),
                  preferCompleteBatches: c.value(.preferCompleteBatches, false))
    }
}

/// `GET /v1/reading/requests/options?key=`.
public struct ReadingRequestOptions: Decodable, Equatable, Sendable {
    public var key: String
    public var contentType: String
    public var title: String
    public var author: String
    public var modes: [ReadingRequestMode]
    public var qualityProfiles: [ReadingQualityProfile]
    public var monitoring: [String]

    public init(key: String = "", contentType: String = "ebook", title: String = "", author: String = "",
                modes: [ReadingRequestMode] = [], qualityProfiles: [ReadingQualityProfile] = [], monitoring: [String] = []) {
        self.key = key
        self.contentType = contentType
        self.title = title
        self.author = author
        self.modes = modes
        self.qualityProfiles = qualityProfiles
        self.monitoring = monitoring
    }

    /// The profile BookKeeprr marks default, else the first.
    public var defaultProfileIndex: Int { qualityProfiles.firstIndex(where: \.isDefault) ?? 0 }

    enum CodingKeys: String, CodingKey { case key, contentType, title, author, modes, qualityProfiles, monitoring }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(key: c.value(.key, ""), contentType: c.value(.contentType, "ebook"), title: c.value(.title, ""),
                  author: c.value(.author, ""), modes: c.value(.modes, []), qualityProfiles: c.value(.qualityProfiles, []),
                  monitoring: c.value(.monitoring, []))
    }
}

/// `POST /v1/reading/requests`. The hub refuses unknown fields, so only these
/// go, and an empty one is left out as the hub's `omitempty` would.
public struct ReadingCreateRequestBody: Encodable, Equatable, Sendable {
    public var key: String
    public var mode: String
    public var totalBooks: Int
    public var seriesId: String
    public var bookIds: [String]
    public var qualityProfileId: Int
    public var monitoring: String

    public init(key: String, mode: String, totalBooks: Int = 0, seriesId: String = "", bookIds: [String] = [],
                qualityProfileId: Int, monitoring: String = "none") {
        self.key = key
        self.mode = mode
        self.totalBooks = totalBooks
        self.seriesId = seriesId
        self.bookIds = bookIds
        self.qualityProfileId = qualityProfileId
        self.monitoring = monitoring
    }

    enum CodingKeys: String, CodingKey { case key, mode, totalBooks, seriesId, bookIds, qualityProfileId, monitoring }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(key, forKey: .key)
        try c.encode(mode, forKey: .mode)
        if totalBooks > 0 { try c.encode(totalBooks, forKey: .totalBooks) }
        if !seriesId.isEmpty { try c.encode(seriesId, forKey: .seriesId) }
        if !bookIds.isEmpty { try c.encode(bookIds, forKey: .bookIds) }
        try c.encode(qualityProfileId, forKey: .qualityProfileId)
        if !monitoring.isEmpty { try c.encode(monitoring, forKey: .monitoring) }
    }
}

/// A BookKeeprr series a request made, whose releases can be chosen.
public struct ReadingRequestTarget: Codable, Equatable, Sendable, Hashable {
    public var seriesId: Int
    public var title: String

    public init(seriesId: Int = 0, title: String = "") {
        self.seriesId = seriesId
        self.title = title
    }

    enum CodingKeys: String, CodingKey { case seriesId, title }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(seriesId: c.value(.seriesId, 0), title: c.value(.title, ""))
    }
}

/// What `POST /v1/reading/requests` answers.
public struct ReadingRequestResponse: Decodable, Equatable, Sendable {
    public var requestId: String
    public var seriesId: Int
    public var parentSeriesId: Int
    public var requested: Int
    public var alreadyPresent: Int
    public var failed: Int
    public var state: String
    public var message: String
    public var targets: [ReadingRequestTarget]

    public init(requestId: String = "", seriesId: Int = 0, parentSeriesId: Int = 0, requested: Int = 0,
                alreadyPresent: Int = 0, failed: Int = 0, state: String = "", message: String = "",
                targets: [ReadingRequestTarget] = []) {
        self.requestId = requestId
        self.seriesId = seriesId
        self.parentSeriesId = parentSeriesId
        self.requested = requested
        self.alreadyPresent = alreadyPresent
        self.failed = failed
        self.state = state
        self.message = message
        self.targets = targets
    }

    enum CodingKeys: String, CodingKey {
        case requestId, seriesId, parentSeriesId, requested, alreadyPresent, failed, state, message, targets
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(requestId: c.value(.requestId, ""), seriesId: c.value(.seriesId, 0),
                  parentSeriesId: c.value(.parentSeriesId, 0), requested: c.value(.requested, 0),
                  alreadyPresent: c.value(.alreadyPresent, 0), failed: c.value(.failed, 0), state: c.value(.state, ""),
                  message: c.value(.message, ""), targets: c.value(.targets, []))
    }
}

/// One release a BookKeeprr search found. Its indexer's identity stays on the hub.
public struct ReadingRelease: Decodable, Equatable, Sendable, Identifiable, Hashable {
    public var id: String
    public var title: String
    public var indexer: String
    public var sizeBytes: Int64
    public var seeders: Int
    public var leechers: Int
    public var score: Double
    /// "none", "in-library", "downloading".
    public var ownership: String
    public var rejected: Bool
    public var reason: String
    public var freeleech: Bool
    public var format: String
    /// "compatible", "incompatible" or "unknown".
    public var formatStatus: String

    public init(id: String = "", title: String = "", indexer: String = "", sizeBytes: Int64 = 0, seeders: Int = 0,
                leechers: Int = 0, score: Double = 0, ownership: String = "none", rejected: Bool = false, reason: String = "",
                freeleech: Bool = false, format: String = "", formatStatus: String = "unknown") {
        self.id = id
        self.title = title
        self.indexer = indexer
        self.sizeBytes = sizeBytes
        self.seeders = seeders
        self.leechers = leechers
        self.score = score
        self.ownership = ownership
        self.rejected = rejected
        self.reason = reason
        self.freeleech = freeleech
        self.format = format
        self.formatStatus = formatStatus
    }

    /// A release in the wrong format cannot be grabbed for this request.
    public var canGrab: Bool { formatStatus != "incompatible" }

    /// "EPUB · matches request", "PDF · wrong format", "Format unverified".
    public var formatLabel: String {
        switch formatStatus {
        case "compatible": format.isEmpty ? "Format matched" : "\(format) · matches request"
        case "incompatible": format.isEmpty ? "Wrong format" : "\(format) · wrong format"
        default: "Format unverified"
        }
    }

    enum CodingKeys: String, CodingKey {
        case id, title, indexer, sizeBytes, seeders, leechers, score, ownership, rejected, reason, freeleech, format,
             formatStatus
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), title: c.value(.title, ""), indexer: c.value(.indexer, ""),
                  sizeBytes: c.value(.sizeBytes, 0), seeders: c.value(.seeders, 0), leechers: c.value(.leechers, 0),
                  score: c.value(.score, 0), ownership: c.value(.ownership, "none"), rejected: c.value(.rejected, false),
                  reason: c.value(.reason, ""), freeleech: c.value(.freeleech, false), format: c.value(.format, ""),
                  formatStatus: c.value(.formatStatus, "unknown"))
    }
}

/// `GET …/requests/{seriesId}/releases` and `POST …/search`.
public struct ReadingReleasesResponse: Decodable, Equatable, Sendable {
    public var seriesId: Int
    public var releases: [ReadingRelease]
    public var errors: [String]

    public init(seriesId: Int = 0, releases: [ReadingRelease] = [], errors: [String] = []) {
        self.seriesId = seriesId
        self.releases = releases
        self.errors = errors
    }

    enum CodingKeys: String, CodingKey { case seriesId, releases, errors }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(seriesId: c.value(.seriesId, 0), releases: c.value(.releases, []), errors: c.value(.errors, []))
    }
}

/// `POST …/requests/{seriesId}/grab`.
public struct ReadingReleaseGrabBody: Encodable, Equatable, Sendable {
    public var id: String

    public init(id: String) {
        self.id = id
    }
}

/// One book of a series a request can tick.
public struct ReadingSeriesPreviewBook: Decodable, Equatable, Sendable, Identifiable, Hashable {
    public var id: String
    public var title: String
    public var authorId: String
    public var author: String
    public var year: Int
    public var isbn: String
    public var cover: String
    public var position: Int
    public var inLibrary: Bool
    public var selected: Bool

    public init(id: String = "", title: String = "", authorId: String = "", author: String = "", year: Int = 0,
                isbn: String = "", cover: String = "", position: Int = 0, inLibrary: Bool = false, selected: Bool = true) {
        self.id = id
        self.title = title
        self.authorId = authorId
        self.author = author
        self.year = year
        self.isbn = isbn
        self.cover = cover
        self.position = position
        self.inLibrary = inLibrary
        self.selected = selected
    }

    enum CodingKeys: String, CodingKey { case id, title, authorId, author, year, isbn, cover, position, inLibrary, selected }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), title: c.value(.title, ""), authorId: c.value(.authorId, ""),
                  author: c.value(.author, ""), year: c.value(.year, 0), isbn: c.value(.isbn, ""), cover: c.value(.cover, ""),
                  position: c.value(.position, 0), inLibrary: c.value(.inLibrary, false), selected: c.value(.selected, true))
    }
}

/// A verified series a book belongs to, with its books.
public struct ReadingSeriesPreview: Decodable, Equatable, Sendable, Hashable {
    public var key: String
    public var seriesId: String
    public var name: String
    public var description: String
    public var authorId: String
    public var author: String
    public var authorImage: String
    public var ordering: String
    public var books: [ReadingSeriesPreviewBook]

    public init(key: String = "", seriesId: String = "", name: String = "", description: String = "", authorId: String = "",
                author: String = "", authorImage: String = "", ordering: String = "publication",
                books: [ReadingSeriesPreviewBook] = []) {
        self.key = key
        self.seriesId = seriesId
        self.name = name
        self.description = description
        self.authorId = authorId
        self.author = author
        self.authorImage = authorImage
        self.ordering = ordering
        self.books = books
    }

    enum CodingKeys: String, CodingKey { case key, seriesId, name, description, authorId, author, authorImage, ordering, books }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(key: c.value(.key, ""), seriesId: c.value(.seriesId, ""), name: c.value(.name, ""),
                  description: c.value(.description, ""), authorId: c.value(.authorId, ""), author: c.value(.author, ""),
                  authorImage: c.value(.authorImage, ""), ordering: c.value(.ordering, "publication"),
                  books: c.value(.books, []))
    }
}

/// `GET /v1/reading/requests/series-preview?key=`.
public struct ReadingSeriesPreviewResponse: Decodable, Equatable, Sendable {
    public var key: String
    public var scopes: [ReadingSeriesPreview]

    public init(key: String = "", scopes: [ReadingSeriesPreview] = []) {
        self.key = key
        self.scopes = scopes
    }

    enum CodingKeys: String, CodingKey { case key, scopes }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(key: c.value(.key, ""), scopes: c.value(.scopes, []))
    }
}

/// What a BookKeeprr transfer can be asked to do.
public enum ReadingTransferAction: String, Sendable, CaseIterable {
    case retry, cancel
}

/// One BookKeeprr transfer, without its torrent or indexer.
public struct ReadingDownloadItem: Decodable, Equatable, Sendable, Identifiable, Hashable {
    public var id: String
    public var seriesId: Int
    public var contentType: String
    public var title: String
    public var releaseTitle: String
    /// "queued", "downloading", "importing", "completed", "imported", "failed", "retry_pending", "retrying".
    public var status: String
    public var progressPercent: Int
    public var downloadSpeedBytesPerSecond: Int64
    public var etaSeconds: Int64
    public var sizeBytes: Int64
    public var addedAt: String
    public var completedAt: String
    public var importedAt: String
    public var failed: Bool
    public var actions: [String]

    public init(id: String = "", seriesId: Int = 0, contentType: String = "", title: String = "", releaseTitle: String = "",
                status: String = "", progressPercent: Int = 0, downloadSpeedBytesPerSecond: Int64 = 0, etaSeconds: Int64 = 0,
                sizeBytes: Int64 = 0, addedAt: String = "", completedAt: String = "", importedAt: String = "",
                failed: Bool = false, actions: [String] = []) {
        self.id = id
        self.seriesId = seriesId
        self.contentType = contentType
        self.title = title
        self.releaseTitle = releaseTitle
        self.status = status
        self.progressPercent = progressPercent
        self.downloadSpeedBytesPerSecond = downloadSpeedBytesPerSecond
        self.etaSeconds = etaSeconds
        self.sizeBytes = sizeBytes
        self.addedAt = addedAt
        self.completedAt = completedAt
        self.importedAt = importedAt
        self.failed = failed
        self.actions = actions
    }

    /// 0…1.
    public var progress: Double { Double(min(max(progressPercent, 0), 100)) / 100 }

    /// Still on its way: worth asking about again soon.
    public var isActive: Bool { ["queued", "downloading", "importing", "retrying"].contains(status) }

    /// The actions the hub offers that the app knows.
    public var availableActions: [ReadingTransferAction] { actions.compactMap(ReadingTransferAction.init(rawValue:)) }

    enum CodingKeys: String, CodingKey {
        case id, seriesId, contentType, title, releaseTitle, status, progressPercent, downloadSpeedBytesPerSecond,
             etaSeconds, sizeBytes, addedAt, completedAt, importedAt, failed, actions
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), seriesId: c.value(.seriesId, 0), contentType: c.value(.contentType, ""),
                  title: c.value(.title, ""), releaseTitle: c.value(.releaseTitle, ""), status: c.value(.status, ""),
                  progressPercent: c.value(.progressPercent, 0),
                  downloadSpeedBytesPerSecond: c.value(.downloadSpeedBytesPerSecond, 0), etaSeconds: c.value(.etaSeconds, 0),
                  sizeBytes: c.value(.sizeBytes, 0), addedAt: c.value(.addedAt, ""), completedAt: c.value(.completedAt, ""),
                  importedAt: c.value(.importedAt, ""), failed: c.value(.failed, false), actions: c.value(.actions, []))
    }
}

/// `GET /v1/reading/downloads`.
public struct ReadingDownloadsResponse: Decodable, Equatable, Sendable {
    public var items: [ReadingDownloadItem]

    public init(items: [ReadingDownloadItem] = []) {
        self.items = items
    }

    public var anyActive: Bool { items.contains(where: \.isActive) }

    enum CodingKeys: String, CodingKey { case items }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(items: c.value(.items, []))
    }
}

/// What the hub answers to an action it took (retry, cancel, grab).
public struct ReadingActionReply: Decodable, Equatable, Sendable {
    public var ok: Bool
    public var action: String
    public var message: String

    public init(ok: Bool = false, action: String = "", message: String = "") {
        self.ok = ok
        self.action = action
        self.message = message
    }

    enum CodingKeys: String, CodingKey { case ok, action, message }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(ok: c.value(.ok, false), action: c.value(.action, ""), message: c.value(.message, ""))
    }
}

// MARK: Audiobooks (#19)

/// An audiobook as the hub streams it: its tracks in the order to play them,
/// the chapters inside them and, on a book with a read-along edition, which of
/// that edition's audio files is which track. `revision` names this list of
/// files; every track's address carries it, and a stale one is refused (412
/// `audio_changed`), so a rescan cannot play another file under an old number.
public struct ReadingAudioManifest: Decodable, Equatable, Sendable {
    public var workId: String
    public var sourceItemId: String
    public var revision: String
    public var narrator: String
    public var totalMs: Int64
    public var aligned: Bool
    public var tracks: [ReadingAudioTrack]
    public var chapters: [ReadingAudioChapter]
    public var alignment: ReadingAudioAlignment?
    public var alignmentReason: String
    /// The book's read-along pack has a word set the hub serves (#66): the
    /// edition is then asked for with `granularity=word`. Absent from a hub
    /// before #66 and for a book without one, which are read by the sentence.
    public var wordLevel: Bool
    public var cache: CacheInfo

    public init(workId: String = "", sourceItemId: String = "", revision: String = "", narrator: String = "",
                totalMs: Int64 = 0, aligned: Bool = false, tracks: [ReadingAudioTrack] = [],
                chapters: [ReadingAudioChapter] = [], alignment: ReadingAudioAlignment? = nil, alignmentReason: String = "",
                wordLevel: Bool = false, cache: CacheInfo = CacheInfo()) {
        self.workId = workId
        self.sourceItemId = sourceItemId
        self.revision = revision
        self.narrator = narrator
        self.totalMs = totalMs
        self.aligned = aligned
        self.tracks = tracks
        self.chapters = chapters
        self.alignment = alignment
        self.alignmentReason = alignmentReason
        self.wordLevel = wordLevel
        self.cache = cache
    }

    enum CodingKeys: String, CodingKey {
        case workId, sourceItemId, revision, narrator, totalMs, aligned, tracks, chapters, alignment, alignmentReason, wordLevel, cache
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(workId: c.value(.workId, ""), sourceItemId: c.value(.sourceItemId, ""), revision: c.value(.revision, ""),
                  narrator: c.value(.narrator, ""), totalMs: c.value(.totalMs, 0), aligned: c.value(.aligned, false),
                  tracks: c.value(.tracks, []), chapters: c.value(.chapters, []), alignment: c.optional(.alignment),
                  alignmentReason: c.value(.alignmentReason, ""), wordLevel: c.value(.wordLevel, false),
                  cache: c.value(.cache, CacheInfo()))
    }
}

public struct ReadingAudioTrack: Decodable, Equatable, Sendable, Hashable {
    /// The `{n}` of the track's route.
    public var index: Int
    /// Stays the same when the order changes, so a place can name the track.
    public var id: String
    public var title: String
    public var durationMs: Int64
    public var bytes: Int64
    public var mime: String
    /// The strong validator the bytes are served with: a new file is a new tag.
    public var etag: String

    public init(index: Int = 0, id: String = "", title: String = "", durationMs: Int64 = 0, bytes: Int64 = 0,
                mime: String = "", etag: String = "") {
        self.index = index
        self.id = id
        self.title = title
        self.durationMs = durationMs
        self.bytes = bytes
        self.mime = mime
        self.etag = etag
    }

    enum CodingKeys: String, CodingKey { case index, id, title, durationMs, bytes, mime, etag }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(index: c.value(.index, 0), id: c.value(.id, ""), title: c.value(.title, ""),
                  durationMs: c.value(.durationMs, 0), bytes: c.value(.bytes, 0), mime: c.value(.mime, ""),
                  etag: c.value(.etag, ""))
    }
}

/// A chapter of the audiobook: `startMs` counts from the start of `track`.
/// `source` says where it comes from (#31): `marks`, a chapter mark inside a
/// file, or `book`, an entry of the table of contents of the book's
/// read-along edition, placed where its narration starts, so one chapter can
/// run on from one track into the next. A book's chapters have one source; an
/// older hub sends none, and its chapters are marks.
public struct ReadingAudioChapter: Decodable, Equatable, Sendable, Hashable {
    public var title: String
    public var startMs: Int64
    public var track: Int
    public var source: String

    /// A chapter of the book's own contents rather than a mark in a file.
    public var fromBook: Bool { source == Self.book }

    public static let marks = "marks"
    public static let book = "book"

    public init(title: String = "", startMs: Int64 = 0, track: Int = 0, source: String = "") {
        self.title = title
        self.startMs = startMs
        self.track = track
        self.source = source
    }

    enum CodingKeys: String, CodingKey { case title, startMs, track, source }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(title: c.value(.title, ""), startMs: c.value(.startMs, 0), track: c.value(.track, 0),
                  source: c.value(.source, ""))
    }
}

public struct ReadingAudioAlignment: Decodable, Equatable, Sendable {
    public var audio: [ReadingAlignedAudio]

    public init(audio: [ReadingAlignedAudio] = []) {
        self.audio = audio
    }

    enum CodingKeys: String, CodingKey { case audio }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(audio: c.value(.audio, []))
    }
}

/// A read-along edition's audio file (`href`, a path inside the EPUB) and
/// where in which track it begins.
public struct ReadingAlignedAudio: Decodable, Equatable, Sendable, Hashable {
    public var href: String
    public var track: Int
    public var startMs: Int64

    public init(href: String = "", track: Int = 0, startMs: Int64 = 0) {
        self.href = href
        self.track = track
        self.startMs = startMs
    }

    enum CodingKeys: String, CodingKey { case href, track, startMs }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(href: c.value(.href, ""), track: c.value(.track, 0), startMs: c.value(.startMs, 0))
    }
}

/// `GET …/audio/position`: where the book's listener is; nil when none.
public struct ReadingAudioPositionResponse: Decodable, Equatable, Sendable {
    public var workId: String
    public var sourceItemId: String
    public var position: ReadingAudioPosition?

    public init(workId: String = "", sourceItemId: String = "", position: ReadingAudioPosition? = nil) {
        self.workId = workId
        self.sourceItemId = sourceItemId
        self.position = position
    }

    enum CodingKeys: String, CodingKey { case workId, sourceItemId, position }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(workId: c.value(.workId, ""), sourceItemId: c.value(.sourceItemId, ""), position: c.optional(.position))
    }
}

/// A listening place. `exact` is false for a proportion of the whole, a guess
/// from a reader's place in a book without alignment. `timestamp` is the
/// hub's own clock: never compare it with this device's.
public struct ReadingAudioPosition: Decodable, Equatable, Sendable {
    public var trackId: String
    public var track: Int
    public var offsetMs: Int64
    public var globalMs: Int64
    public var completed: Bool
    public var exact: Bool
    /// "audio" or "text": the form the place was written in.
    public var form: String
    public var timestamp: Int64
    public var updatedAt: String
    public var sentence: ReadingAudioSentence?

    public init(trackId: String = "", track: Int = 0, offsetMs: Int64 = 0, globalMs: Int64 = 0, completed: Bool = false,
                exact: Bool = false, form: String = "", timestamp: Int64 = 0, updatedAt: String = "",
                sentence: ReadingAudioSentence? = nil) {
        self.trackId = trackId
        self.track = track
        self.offsetMs = offsetMs
        self.globalMs = globalMs
        self.completed = completed
        self.exact = exact
        self.form = form
        self.timestamp = timestamp
        self.updatedAt = updatedAt
        self.sentence = sentence
    }

    enum CodingKeys: String, CodingKey {
        case trackId, track, offsetMs, globalMs, completed, exact, form, timestamp, updatedAt, sentence
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(trackId: c.value(.trackId, ""), track: c.value(.track, 0), offsetMs: c.value(.offsetMs, 0),
                  globalMs: c.value(.globalMs, 0), completed: c.value(.completed, false), exact: c.value(.exact, false),
                  form: c.value(.form, ""), timestamp: c.value(.timestamp, 0), updatedAt: c.value(.updatedAt, ""),
                  sentence: c.optional(.sentence))
    }
}

public struct ReadingAudioSentence: Decodable, Equatable, Sendable, Hashable {
    public var href: String
    public var fragment: String

    public init(href: String = "", fragment: String = "") {
        self.href = href
        self.fragment = fragment
    }

    enum CodingKeys: String, CodingKey { case href, fragment }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(href: c.value(.href, ""), fragment: c.value(.fragment, ""))
    }
}
