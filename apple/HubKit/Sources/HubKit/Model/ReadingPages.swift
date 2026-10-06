import Foundation

// The page reader's answers (#25, phase 3): a comic issue or a manga volume as
// the hub's `GET /v1/reading/works/{workId}/publications/{sourceItemId}`
// gives it, field for field with the hub's `ReadingPublicationManifest` and
// Android's `model/Reading.kt`. Every field is defaulted, as on Android.

/// One page of a publication, as Kavita measured it; nothing for a page it
/// did not, which counts as an upright one until it decodes.
public struct ReadingPublicationPage: Decodable, Equatable, Sendable, Hashable {
    public var index: Int
    public var width: Int
    public var height: Int
    public var isWide: Bool

    public init(index: Int = 0, width: Int = 0, height: Int = 0, isWide: Bool = false) {
        self.index = index
        self.width = width
        self.height = height
        self.isWide = isWide
    }

    enum CodingKeys: String, CodingKey { case index, width, height, isWide }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(index: c.value(.index, 0), width: c.value(.width, 0), height: c.value(.height, 0),
                  isWide: c.value(.isWide, false))
    }

    /// The page's size for the spread planner: wide by the hub's word or its shape.
    public var dimension: PageDimension {
        PageDimension(index: index, width: width, height: height, isWide: isWide || width > height)
    }
}

/// An issue or a volume to read: its pages, where the person is in it (Kavita's
/// own progress, read fresh), which way it reads, and the issues either side.
public struct ReadingPublicationManifest: Decodable, Equatable, Sendable {
    public var workId: String
    public var source: String
    public var sourceItemId: String
    /// "comic" or "manga".
    public var kind: String
    public var title: String
    public var seriesTitle: String
    public var number: String
    public var pageCount: Int
    /// The page the person is on, from 0.
    public var currentPage: Int
    /// "ltr", or "rtl" for manga.
    public var direction: String
    public var pages: [ReadingPublicationPage]
    public var previousSourceItemId: String
    public var nextSourceItemId: String

    public init(workId: String = "", source: String = "", sourceItemId: String = "", kind: String = "comic",
                title: String = "", seriesTitle: String = "", number: String = "", pageCount: Int = 0,
                currentPage: Int = 0, direction: String = "ltr", pages: [ReadingPublicationPage] = [],
                previousSourceItemId: String = "", nextSourceItemId: String = "") {
        self.workId = workId
        self.source = source
        self.sourceItemId = sourceItemId
        self.kind = kind
        self.title = title
        self.seriesTitle = seriesTitle
        self.number = number
        self.pageCount = pageCount
        self.currentPage = currentPage
        self.direction = direction
        self.pages = pages
        self.previousSourceItemId = previousSourceItemId
        self.nextSourceItemId = nextSourceItemId
    }

    enum CodingKeys: String, CodingKey {
        case workId, source, sourceItemId, kind, title, seriesTitle, number, pageCount, currentPage, direction, pages,
             previousSourceItemId, nextSourceItemId
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(workId: c.value(.workId, ""), source: c.value(.source, ""), sourceItemId: c.value(.sourceItemId, ""),
                  kind: c.value(.kind, "comic"), title: c.value(.title, ""), seriesTitle: c.value(.seriesTitle, ""),
                  number: c.value(.number, ""), pageCount: c.value(.pageCount, 0), currentPage: c.value(.currentPage, 0),
                  direction: c.value(.direction, "ltr"), pages: c.value(.pages, []),
                  previousSourceItemId: c.value(.previousSourceItemId, ""),
                  nextSourceItemId: c.value(.nextSourceItemId, ""))
    }

    /// Which way it reads: the series' own choice (`ComicView.direction`)
    /// over the library's.
    public func pageDirection(chosen: String?) -> PageDirection {
        (chosen ?? direction) == "rtl" ? .rtl : .ltr
    }

    /// The page it opens on, within the issue.
    public var startPage: Int { pageCount > 0 ? min(max(currentPage, 0), pageCount - 1) : 0 }

    /// Each page's size as the manifest has it, for the spread planner.
    public var dimensions: [PageDimension] { pages.map(\.dimension) }
}

/// `POST …/publications/{id}/progress`: the page now, and the page the hub
/// last said, so a page read on another device since is not overwritten (the
/// hub answers 409 `reading_position_conflict`).
public struct ReadingPublicationProgressBody: Encodable, Equatable, Sendable {
    public var pageIndex: Int
    public var expectedPage: Int?

    public init(pageIndex: Int, expectedPage: Int? = nil) {
        self.pageIndex = pageIndex
        self.expectedPage = expectedPage
    }
}
