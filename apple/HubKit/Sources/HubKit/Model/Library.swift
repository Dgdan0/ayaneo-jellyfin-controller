import Foundation

// The Home and Library responses, field for field with the hub's Go types
// (`hub/internal/api/home.go`, `library.go`, `media.go`, `users.go`). Every
// field is defaulted, as on Android, so a hub that adds or loosens a field
// breaks neither client.

/// Which title a card is: the hub's `MediaRef`.
public struct MediaRef: Decodable, Equatable, Sendable {
    /// "movie", "series" or, on Home's Continue and Next rows, "episode".
    public var type: String
    public var title: String
    public var year: Int
    public var key: String
    public var tmdb: Int
    /// Hub-relative artwork paths ("/v1/img/jf/…"), never a service address.
    public var poster: String
    public var backdrop: String

    public init(type: String = "", title: String = "", year: Int = 0, key: String = "", tmdb: Int = 0,
                poster: String = "", backdrop: String = "") {
        self.type = type
        self.title = title
        self.year = year
        self.key = key
        self.tmdb = tmdb
        self.poster = poster
        self.backdrop = backdrop
    }

    enum CodingKeys: String, CodingKey { case type, title, year, key, ids, poster, backdrop }
    enum IDKeys: String, CodingKey { case tmdb }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let ids = try? c.nestedContainer(keyedBy: IDKeys.self, forKey: .ids)
        self.init(type: c.value(.type, ""), title: c.value(.title, ""), year: c.value(.year, 0),
                  key: c.value(.key, ""), tmdb: ids?.value(.tmdb, 0) ?? 0,
                  poster: c.value(.poster, ""), backdrop: c.value(.backdrop, ""))
    }
}

/// One card in a Home row or a Library grid: the hub's `SearchHit`.
public struct MediaHit: Decodable, Equatable, Sendable, Identifiable {
    public var media: MediaRef
    /// "S1E2 · The Stake Out" for an episode, the year for anything else.
    public var subtitle: String
    public var overview: String
    public var availability: String
    public var rating: Double
    /// The Jellyfin item, when the title is in the library.
    public var jellyfinItemId: String
    public var played: Bool
    public var favorite: Bool
    public var unplayedCount: Int
    /// 0…1. While a title is in the library this is watch progress; on a
    /// Discover card it is the download's, and -1 when its size is unknown.
    public var progress: Double
    /// What the device may do with it: "play", "detail", "request".
    public var actions: [String]
    /// A request made this session (`RequestedTitles`).
    public var requestId: Int

    public var id: String { jellyfinItemId.isEmpty ? media.key + media.title : jellyfinItemId }

    /// The hub offers Request only for a title not in the library, to a
    /// device allowed to request.
    public var canRequest: Bool { actions.contains("request") }

    public init(media: MediaRef, subtitle: String = "", overview: String = "", availability: String = "",
                rating: Double = 0, jellyfinItemId: String = "", played: Bool = false, favorite: Bool = false,
                unplayedCount: Int = 0, progress: Double = 0, actions: [String] = [], requestId: Int = 0) {
        self.media = media
        self.subtitle = subtitle
        self.overview = overview
        self.availability = availability
        self.rating = rating
        self.jellyfinItemId = jellyfinItemId
        self.played = played
        self.favorite = favorite
        self.unplayedCount = unplayedCount
        self.progress = progress
        self.actions = actions
        self.requestId = requestId
    }

    enum CodingKeys: String, CodingKey {
        case media, subtitle, overview, availability, rating, jellyfinItemId, played, favorite, unplayedCount, progress,
             actions, requestId
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(media: c.value(.media, MediaRef()), subtitle: c.value(.subtitle, ""),
                  overview: c.value(.overview, ""), availability: c.value(.availability, ""),
                  rating: c.value(.rating, 0), jellyfinItemId: c.value(.jellyfinItemId, ""),
                  played: c.value(.played, false), favorite: c.value(.favorite, false),
                  unplayedCount: c.value(.unplayedCount, 0), progress: c.value(.progress, 0),
                  actions: c.value(.actions, []), requestId: c.value(.requestId, 0))
    }
}

/// A Home row: the hub's `DiscoverRow`. Ids are "favourites", "continue",
/// "nextup" and "latest"; an empty row is never sent.
public struct HomeRow: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var items: [MediaHit]

    public init(id: String, title: String, items: [MediaHit]) {
        self.id = id
        self.title = title
        self.items = items
    }

    enum CodingKeys: String, CodingKey { case id, title, items }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), title: c.value(.title, ""), items: c.value(.items, []))
    }
}

/// `GET /v1/home`.
public struct HomeResponse: Decodable, Equatable, Sendable {
    public var rows: [HomeRow]
    public var partial: [Partial]
    public var cache: CacheInfo

    public init(rows: [HomeRow], partial: [Partial] = [], cache: CacheInfo = CacheInfo()) {
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

/// One of Jellyfin's top-level folders.
public struct LibraryFolder: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    /// "movies" or "tvshows", from Jellyfin's CollectionType.
    public var kind: String
    /// The folder's own artwork, or one of its titles chosen for the day.
    public var image: String
    /// "banner" for real folder artwork, "poster" for a title standing in.
    public var imageStyle: String
    /// Up to three of its posters, the day's pick first (#13); empty from an
    /// older hub.
    public var fan: [String]
    /// How many films and series it holds; nil when the hub does not know.
    public var total: Int?

    public init(id: String, name: String, kind: String = "", image: String = "", imageStyle: String = "",
                fan: [String] = [], total: Int? = nil) {
        self.id = id
        self.name = name
        self.kind = kind
        self.image = image
        self.imageStyle = imageStyle
        self.fan = fan
        self.total = total
    }

    enum CodingKeys: String, CodingKey { case id, name, kind, image, imageStyle, fan, total }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), name: c.value(.name, ""), kind: c.value(.kind, ""),
                  image: c.value(.image, ""), imageStyle: c.value(.imageStyle, ""), fan: c.value(.fan, []),
                  total: c.optional(.total))
    }
}

/// `GET /v1/library`: the folders in the profile's order (#15), which the app
/// shows as it comes and never sorts.
public struct LibraryResponse: Decodable, Equatable, Sendable {
    public var views: [LibraryFolder]
    /// "custom" when the profile arranged them, "name" for A to Z.
    public var order: String
    public var partial: [Partial]

    public init(views: [LibraryFolder], order: String = "name", partial: [Partial] = []) {
        self.views = views
        self.order = order
        self.partial = partial
    }

    enum CodingKeys: String, CodingKey { case views, order, partial }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(views: c.value(.views, []), order: c.value(.order, "name"), partial: c.value(.partial, []))
    }
}

/// One of Kavita's or Storyteller's libraries (`ReadingLibrary` in
/// `reading_catalog.go`): "kavita:2", "storyteller:books".
public struct ReadingLibrary: Decodable, Equatable, Sendable, Identifiable, Hashable {
    public var id: String
    /// "kavita" or "storyteller".
    public var source: String
    /// "book", "comic" or "manga".
    public var kind: String
    public var title: String
    public var artwork: String
    /// "poster": the artwork is one of its covers.
    public var artworkStyle: String
    /// What it can do: "browse", "details", "progress", and the sorts it
    /// honours ("sort:title", "sort:author", "sort:last_read", …) (#25).
    public var capabilities: [String]

    public init(id: String, source: String = "", kind: String = "", title: String, artwork: String = "",
                artworkStyle: String = "poster", capabilities: [String] = []) {
        self.id = id
        self.source = source
        self.kind = kind
        self.title = title
        self.artwork = artwork
        self.artworkStyle = artworkStyle
        self.capabilities = capabilities
    }

    enum CodingKeys: String, CodingKey { case id, source, kind, title, artwork, artworkStyle, capabilities }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), source: c.value(.source, ""), kind: c.value(.kind, ""),
                  title: c.value(.title, ""), artwork: c.value(.artwork, ""),
                  artworkStyle: c.value(.artworkStyle, "poster"), capabilities: c.value(.capabilities, []))
    }
}

/// `GET /v1/reading/libraries`, in the profile's order.
public struct ReadingLibrariesResponse: Decodable, Equatable, Sendable {
    public var libraries: [ReadingLibrary]
    public var order: String
    public var partial: [Partial]

    public init(libraries: [ReadingLibrary], order: String = "name", partial: [Partial] = []) {
        self.libraries = libraries
        self.order = order
        self.partial = partial
    }

    enum CodingKeys: String, CodingKey { case libraries, order, partial }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(libraries: c.value(.libraries, []), order: c.value(.order, "name"), partial: c.value(.partial, []))
    }
}

/// Which list of libraries an order is for.
public enum LibrarySide: String, Sendable, CaseIterable {
    case media, books
}

/// `PUT /v1/library/order`'s body.
struct LibraryOrderBody: Encodable {
    let side: String
    let ids: [String]
}

/// What `PUT /v1/library/order` answers: the order now kept. After going back
/// to A to Z it is `ids: []`, not the A to Z list, so the list is read again.
public struct LibraryOrderReply: Decodable, Equatable, Sendable {
    public var side: String
    public var ids: [String]
    public var order: String

    public init(side: String, ids: [String], order: String) {
        self.side = side
        self.ids = ids
        self.order = order
    }

    enum CodingKeys: String, CodingKey { case side, ids, order }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(side: c.value(.side, ""), ids: c.value(.ids, []), order: c.value(.order, "name"))
    }
}

/// One page of a folder, a library search or Favourites: `GET /v1/library/{viewId}/items`.
public struct LibraryPage: Decodable, Equatable, Sendable {
    public var title: String
    public var page: Int
    public var totalPages: Int
    public var total: Int
    public var items: [MediaHit]

    public init(title: String = "", page: Int = 1, totalPages: Int = 1, total: Int = 0, items: [MediaHit] = []) {
        self.title = title
        self.page = page
        self.totalPages = totalPages
        self.total = total
        self.items = items
    }

    enum CodingKeys: String, CodingKey { case title, page, totalPages, total, items }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(title: c.value(.title, ""), page: c.value(.page, 1), totalPages: c.value(.totalPages, 1),
                  total: c.value(.total, 0), items: c.value(.items, []))
    }
}

public struct LibraryPerson: Decodable, Equatable, Sendable, Identifiable {
    public var personId: String
    public var name: String
    public var role: String
    /// "Actor", "Director", "Writer", …
    public var type: String
    public var image: String

    public var id: String { personId.isEmpty ? name + role : personId + role }

    public init(personId: String = "", name: String, role: String = "", type: String = "", image: String = "") {
        self.personId = personId
        self.name = name
        self.role = role
        self.type = type
        self.image = image
    }

    enum CodingKeys: String, CodingKey { case id, name, role, type, image }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(personId: c.value(.id, ""), name: c.value(.name, ""), role: c.value(.role, ""),
                  type: c.value(.type, ""), image: c.value(.image, ""))
    }
}

/// A movie, series, season or episode as Jellyfin knows it: `LibraryItem`.
public struct LibraryItem: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    /// "movie", "series", "season" or "episode" (the hub lowercases Jellyfin's type).
    public var type: String
    public var title: String
    /// "S1E4 · Title" for an episode, as on a Home card.
    public var subtitle: String
    public var seriesTitle: String
    public var seriesId: String
    public var seasonId: String
    public var year: Int
    /// The episode number (or the season's number, for a season).
    public var indexNumber: Int
    public var seasonNumber: Int
    public var overview: String
    public var originalTitle: String
    public var premiereDate: String
    public var runtimeSeconds: Int
    public var rating: Double
    public var criticRating: Double
    public var officialRating: String
    public var genres: [String]
    public var studios: [String]
    public var people: [LibraryPerson]
    public var played: Bool
    public var favorite: Bool
    public var unplayedCount: Int
    public var progress: Double
    public var positionSeconds: Int
    public var poster: String
    public var thumb: String
    public var backdrop: String

    public init(id: String, type: String, title: String, subtitle: String = "", seriesTitle: String = "", seriesId: String = "",
                seasonId: String = "", year: Int = 0, indexNumber: Int = 0, seasonNumber: Int = 0,
                overview: String = "", originalTitle: String = "", premiereDate: String = "",
                runtimeSeconds: Int = 0, rating: Double = 0, criticRating: Double = 0, officialRating: String = "",
                genres: [String] = [], studios: [String] = [], people: [LibraryPerson] = [],
                played: Bool = false, favorite: Bool = false, unplayedCount: Int = 0, progress: Double = 0,
                positionSeconds: Int = 0, poster: String = "", thumb: String = "", backdrop: String = "") {
        self.id = id
        self.type = type
        self.title = title
        self.subtitle = subtitle
        self.seriesTitle = seriesTitle
        self.seriesId = seriesId
        self.seasonId = seasonId
        self.year = year
        self.indexNumber = indexNumber
        self.seasonNumber = seasonNumber
        self.overview = overview
        self.originalTitle = originalTitle
        self.premiereDate = premiereDate
        self.runtimeSeconds = runtimeSeconds
        self.rating = rating
        self.criticRating = criticRating
        self.officialRating = officialRating
        self.genres = genres
        self.studios = studios
        self.people = people
        self.played = played
        self.favorite = favorite
        self.unplayedCount = unplayedCount
        self.progress = progress
        self.positionSeconds = positionSeconds
        self.poster = poster
        self.thumb = thumb
        self.backdrop = backdrop
    }

    enum CodingKeys: String, CodingKey {
        case id, type, title, subtitle, seriesTitle, seriesId, seasonId, year, indexNumber, seasonNumber, overview,
             originalTitle, premiereDate, runtimeSeconds, rating, criticRating, officialRating, genres, studios,
             people, played, favorite, unplayedCount, progress, positionSeconds, poster, thumb, backdrop
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: c.value(.id, ""), type: c.value(.type, ""), title: c.value(.title, ""),
            subtitle: c.value(.subtitle, ""), seriesTitle: c.value(.seriesTitle, ""), seriesId: c.value(.seriesId, ""),
            seasonId: c.value(.seasonId, ""), year: c.value(.year, 0), indexNumber: c.value(.indexNumber, 0),
            seasonNumber: c.value(.seasonNumber, 0), overview: c.value(.overview, ""),
            originalTitle: c.value(.originalTitle, ""), premiereDate: c.value(.premiereDate, ""),
            runtimeSeconds: c.value(.runtimeSeconds, 0), rating: c.value(.rating, 0),
            criticRating: c.value(.criticRating, 0), officialRating: c.value(.officialRating, ""),
            genres: c.value(.genres, []), studios: c.value(.studios, []), people: c.value(.people, []),
            played: c.value(.played, false), favorite: c.value(.favorite, false),
            unplayedCount: c.value(.unplayedCount, 0), progress: c.value(.progress, 0),
            positionSeconds: c.value(.positionSeconds, 0), poster: c.value(.poster, ""),
            thumb: c.value(.thumb, ""), backdrop: c.value(.backdrop, ""))
    }
}

/// `GET /v1/library/items/{itemId}`.
public struct LibraryItemResponse: Decodable, Equatable, Sendable {
    public var item: LibraryItem

    public init(item: LibraryItem) {
        self.item = item
    }

    enum CodingKeys: String, CodingKey { case item }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(item: c.value(.item, LibraryItem(id: "", type: "", title: "")))
    }
}

/// `GET /v1/library/series/{id}/seasons` and `/episodes`: the same shape, a
/// list of items with paging (seasons always come in one page).
public struct LibraryItemList: Decodable, Equatable, Sendable {
    public var page: Int
    public var totalPages: Int
    public var items: [LibraryItem]

    public init(page: Int = 1, totalPages: Int = 1, items: [LibraryItem]) {
        self.page = page
        self.totalPages = totalPages
        self.items = items
    }

    enum CodingKeys: String, CodingKey { case page, totalPages, items }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(page: c.value(.page, 1), totalPages: c.value(.totalPages, 1), items: c.value(.items, []))
    }
}

/// `GET /v1/library/series/{id}/play-target`: the episode a series' Play
/// button starts, and why.
public struct SeriesPlayTarget: Decodable, Equatable, Sendable {
    /// "resume" (an episode is part watched), "next" or "start".
    public var kind: String
    public var item: LibraryItem

    public init(kind: String, item: LibraryItem) {
        self.kind = kind
        self.item = item
    }

    enum CodingKeys: String, CodingKey { case kind, item }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(kind: c.value(.kind, ""), item: c.value(.item, LibraryItem(id: "", type: "", title: "")))
    }
}

/// `POST /v1/library/items/{itemId}/state`: exactly one of the two.
public struct LibraryStateChange: Encodable, Equatable, Sendable {
    public var played: Bool?
    public var favorite: Bool?

    public static func played(_ value: Bool) -> LibraryStateChange { LibraryStateChange(played: value) }
    public static func favorite(_ value: Bool) -> LibraryStateChange { LibraryStateChange(favorite: value) }

    public func body() -> Data {
        (try? JSONEncoder().encode(self)) ?? Data("{}".utf8)
    }
}
