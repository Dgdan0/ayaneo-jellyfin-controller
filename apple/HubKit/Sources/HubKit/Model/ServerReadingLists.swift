import Foundation

// Kavita's reading lists, as the hub serves them (#37; Android's
// `ServerReadingLists`): `GET /v1/reading/lists` and
// `GET /v1/reading/lists/{id}`. Kept apart from the device's own lists
// (`ReadingList`), which the person edits: these are Kavita's, in Kavita's order.

/// One of Kavita's reading lists.
public struct ServerReadingList: Decodable, Equatable, Sendable, Hashable, Identifiable {
    public var id: Int
    public var title: String
    public var summary: String
    public var itemCount: Int
    public var promoted: Bool

    public init(id: Int, title: String, summary: String = "", itemCount: Int = 0, promoted: Bool = false) {
        self.id = id
        self.title = title
        self.summary = summary
        self.itemCount = itemCount
        self.promoted = promoted
    }

    enum CodingKeys: String, CodingKey { case id, title, summary, itemCount, promoted }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(Int.self, forKey: .id), title: c.value(.title, ""), summary: c.value(.summary, ""),
                  itemCount: c.value(.itemCount, 0), promoted: c.value(.promoted, false))
    }
}

public struct ServerReadingListsResponse: Decodable, Equatable, Sendable {
    public var lists: [ServerReadingList]

    public init(lists: [ServerReadingList] = []) {
        self.lists = lists
    }

    enum CodingKeys: String, CodingKey { case lists }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(lists: c.value(.lists, []))
    }
}

/// An issue in a reading list: its run (`workId`) and the issue to open in it.
public struct ServerReadingListEntry: Decodable, Equatable, Sendable, Hashable, Identifiable {
    public var id: Int
    public var order: Int
    public var workId: String
    public var sourceItemId: String
    public var title: String
    public var seriesTitle: String
    public var volume: String
    public var kind: String
    public var artwork: String
    public var pageCount: Int
    public var progress: ReadingProgress?

    public init(id: Int, order: Int, workId: String, sourceItemId: String, title: String, seriesTitle: String,
                volume: String = "", kind: String = "comic", artwork: String = "", pageCount: Int = 0,
                progress: ReadingProgress? = nil) {
        self.id = id
        self.order = order
        self.workId = workId
        self.sourceItemId = sourceItemId
        self.title = title
        self.seriesTitle = seriesTitle
        self.volume = volume
        self.kind = kind
        self.artwork = artwork
        self.pageCount = pageCount
        self.progress = progress
    }

    enum CodingKeys: String, CodingKey {
        case id, order, workId, sourceItemId, title, seriesTitle, volume, kind, artwork, pageCount, progress
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(Int.self, forKey: .id), order: c.value(.order, 0), workId: c.value(.workId, ""),
                  sourceItemId: c.value(.sourceItemId, ""), title: c.value(.title, ""),
                  seriesTitle: c.value(.seriesTitle, ""), volume: c.value(.volume, ""), kind: c.value(.kind, "comic"),
                  artwork: c.value(.artwork, ""), pageCount: c.value(.pageCount, 0), progress: c.optional(.progress))
    }

    /// The issue as the comic reader opens it, inside its own run.
    public var publication: ReadingSectionItem {
        ReadingSectionItem(sourceItemId: sourceItemId, workId: workId, title: title, kind: kind, artwork: artwork,
                           pageCount: pageCount, progress: progress)
    }
}

public struct ServerReadingListResponse: Decodable, Equatable, Sendable {
    public var list: ServerReadingList
    public var items: [ServerReadingListEntry]

    public init(list: ServerReadingList, items: [ServerReadingListEntry] = []) {
        self.list = list
        self.items = items
    }

    enum CodingKeys: String, CodingKey { case list, items }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(list: try c.decode(ServerReadingList.self, forKey: .list), items: c.value(.items, []))
    }
}

extension HubEndpoints {
    /// Kavita's reading lists.
    public static let serverReadingLists = HubRequest("/v1/reading/lists")

    /// One of Kavita's reading lists and its issues.
    public static func serverReadingList(_ id: Int) -> HubRequest {
        HubRequest("/v1/reading/lists/\(id)")
    }
}
