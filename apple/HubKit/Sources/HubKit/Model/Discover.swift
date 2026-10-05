import Foundation

// The request side's responses, field for field with the hub's Go types
// (`discover.go`, `search.go`, `detail.go`, `person.go`, `options.go`,
// `requests.go`, `releases.go`, `calendar.go`). Every field is defaulted, so
// a hub that adds or loosens one breaks nothing.

/// A Discover row: one of Jellyseerr's feeds, page by page. Ids are
/// "trending", "movies", "tv" and "upcoming"; the hub sends their titles.
public struct DiscoverRow: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var page: Int
    public var totalPages: Int
    public var items: [MediaHit]

    public init(id: String, title: String, page: Int = 1, totalPages: Int = 1, items: [MediaHit]) {
        self.id = id
        self.title = title
        self.page = page
        self.totalPages = totalPages
        self.items = items
    }

    enum CodingKeys: String, CodingKey { case id, title, page, totalPages, items }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), title: c.value(.title, ""), page: c.value(.page, 1),
                  totalPages: c.value(.totalPages, 1), items: c.value(.items, []))
    }
}

/// `GET /v1/discover` (every row's first page) and `/v1/discover/{row}?page=`
/// (one row). A row with nothing in it is left out.
public struct DiscoverResponse: Decodable, Equatable, Sendable {
    public var rows: [DiscoverRow]
    public var partial: [Partial]
    public var cache: CacheInfo

    public init(rows: [DiscoverRow], partial: [Partial] = [], cache: CacheInfo = CacheInfo()) {
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

/// `GET /v1/search?q=`: films and series, the closest titles first.
public struct SearchResponse: Decodable, Equatable, Sendable {
    public var query: String
    public var page: Int
    public var totalPages: Int
    public var totalResults: Int
    public var results: [MediaHit]
    public var cache: CacheInfo

    public init(query: String = "", page: Int = 1, totalPages: Int = 1, totalResults: Int = 0, results: [MediaHit] = [],
                cache: CacheInfo = CacheInfo()) {
        self.query = query
        self.page = page
        self.totalPages = totalPages
        self.totalResults = totalResults
        self.results = results
        self.cache = cache
    }

    enum CodingKeys: String, CodingKey { case query, page, totalPages, totalResults, results, cache }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(query: c.value(.query, ""), page: c.value(.page, 1), totalPages: c.value(.totalPages, 1),
                  totalResults: c.value(.totalResults, 0), results: c.value(.results, []),
                  cache: c.value(.cache, CacheInfo()))
    }
}

/// A season as TMDB lists it: in a title's detail, its request options and
/// its release search.
public struct SeasonOption: Decodable, Hashable, Sendable, Identifiable {
    public var number: Int
    public var name: String
    public var episodeCount: Int
    public var year: Int
    public var image: String

    public var id: Int { number }

    public init(number: Int, name: String = "", episodeCount: Int = 0, year: Int = 0, image: String = "") {
        self.number = number
        self.name = name
        self.episodeCount = episodeCount
        self.year = year
        self.image = image
    }

    enum CodingKeys: String, CodingKey { case number, name, episodeCount, year, image }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(number: c.value(.number, 0), name: c.value(.name, ""), episodeCount: c.value(.episodeCount, 0),
                  year: c.value(.year, 0), image: c.value(.image, ""))
    }
}

/// One of a title's cast, in billing order; the id opens their filmography.
public struct CastMember: Decodable, Equatable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    public var character: String
    public var profile: String

    public init(id: Int, name: String, character: String = "", profile: String = "") {
        self.id = id
        self.name = name
        self.character = character
        self.profile = profile
    }

    enum CodingKeys: String, CodingKey { case id, name, character, profile }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, 0), name: c.value(.name, ""), character: c.value(.character, ""),
                  profile: c.value(.profile, ""))
    }
}

/// One step on a title's way into the library: request, grab, download,
/// import, library.
public struct PipelineStage: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    /// The full name ("Find a release"); `short` is what a chip shows ("Grab").
    public var label: String
    public var short: String
    /// "done", "active", "pending", "stuck", "failed" or "unknown".
    public var state: String
    public var detail: String
    public var source: String
    /// 0…1 while it runs; -1 when the size is not known.
    public var progress: Double

    public init(id: String, label: String = "", short: String = "", state: String = "pending", detail: String = "",
                source: String = "", progress: Double = 0) {
        self.id = id
        self.label = label
        self.short = short
        self.state = state
        self.detail = detail
        self.source = source
        self.progress = progress
    }

    enum CodingKeys: String, CodingKey { case id, label, short, state, detail, source, progress }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), label: c.value(.label, ""), short: c.value(.short, ""),
                  state: c.value(.state, "pending"), detail: c.value(.detail, ""), source: c.value(.source, ""),
                  progress: c.value(.progress, 0))
    }
}

public struct Pipeline: Decodable, Equatable, Sendable {
    /// The one line the hub writes about where the title is ("Looking for a release").
    public var summary: String
    public var stages: [PipelineStage]

    public init(summary: String = "", stages: [PipelineStage] = []) {
        self.summary = summary
        self.stages = stages
    }

    enum CodingKeys: String, CodingKey { case summary, stages }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(summary: c.value(.summary, ""), stages: c.value(.stages, []))
    }
}

/// `GET /v1/media/{key}`: a title as Jellyseerr and TMDB know it, with the
/// pipeline the hub builds from Jellyseerr and the live transfers.
public struct MediaDetail: Decodable, Equatable, Sendable {
    public var media: MediaRef
    public var overview: String
    public var runtimeMinutes: Int
    public var genres: [String]
    public var rating: Double
    /// How many seasons and episodes, for a series.
    public var seasons: Int
    public var episodes: Int
    public var seasonList: [SeasonOption]
    public var trailerUrl: String
    public var trailerKey: String
    public var availability: String
    public var jellyfinItemId: String
    public var cast: [CastMember]
    public var pipeline: Pipeline
    public var actions: [String]
    public var partial: [Partial]
    public var cache: CacheInfo

    public init(media: MediaRef, overview: String = "", runtimeMinutes: Int = 0, genres: [String] = [], rating: Double = 0,
                seasons: Int = 0, episodes: Int = 0, seasonList: [SeasonOption] = [], trailerUrl: String = "",
                trailerKey: String = "", availability: String = "not_in_library", jellyfinItemId: String = "",
                cast: [CastMember] = [], pipeline: Pipeline = Pipeline(), actions: [String] = [],
                partial: [Partial] = [], cache: CacheInfo = CacheInfo()) {
        self.media = media
        self.overview = overview
        self.runtimeMinutes = runtimeMinutes
        self.genres = genres
        self.rating = rating
        self.seasons = seasons
        self.episodes = episodes
        self.seasonList = seasonList
        self.trailerUrl = trailerUrl
        self.trailerKey = trailerKey
        self.availability = availability
        self.jellyfinItemId = jellyfinItemId
        self.cast = cast
        self.pipeline = pipeline
        self.actions = actions
        self.partial = partial
        self.cache = cache
    }

    /// The hub offers Request only to a title not in the library, and only to
    /// a device allowed to request.
    public var canRequest: Bool { actions.contains("request") }
    public var isSeries: Bool { media.type == "series" }

    enum CodingKeys: String, CodingKey {
        case media, overview, runtimeMinutes, genres, rating, seasons, episodes, seasonList, trailerUrl, trailerKey,
             availability, jellyfinItemId, cast, pipeline, actions, partial, cache
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(media: c.value(.media, MediaRef()), overview: c.value(.overview, ""),
                  runtimeMinutes: c.value(.runtimeMinutes, 0), genres: c.value(.genres, []), rating: c.value(.rating, 0),
                  seasons: c.value(.seasons, 0), episodes: c.value(.episodes, 0), seasonList: c.value(.seasonList, []),
                  trailerUrl: c.value(.trailerUrl, ""), trailerKey: c.value(.trailerKey, ""),
                  availability: c.value(.availability, "not_in_library"), jellyfinItemId: c.value(.jellyfinItemId, ""),
                  cast: c.value(.cast, []), pipeline: c.value(.pipeline, Pipeline()), actions: c.value(.actions, []),
                  partial: c.value(.partial, []), cache: c.value(.cache, CacheInfo()))
    }
}

/// `GET /v1/person/{id}`: a performer and the films and series they acted in.
public struct PersonResponse: Decodable, Equatable, Sendable {
    public var id: Int
    public var name: String
    public var profile: String
    public var biography: String
    /// "Acting", "Directing", …
    public var knownFor: String
    public var credits: [MediaHit]
    /// "release" (newest first) or "popularity".
    public var sortedBy: String
    public var cache: CacheInfo

    public init(id: Int, name: String, profile: String = "", biography: String = "", knownFor: String = "",
                credits: [MediaHit] = [], sortedBy: String = "release", cache: CacheInfo = CacheInfo()) {
        self.id = id
        self.name = name
        self.profile = profile
        self.biography = biography
        self.knownFor = knownFor
        self.credits = credits
        self.sortedBy = sortedBy
        self.cache = cache
    }

    enum CodingKeys: String, CodingKey { case id, name, profile, biography, knownFor, credits, sortedBy, cache }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, 0), name: c.value(.name, ""), profile: c.value(.profile, ""),
                  biography: c.value(.biography, ""), knownFor: c.value(.knownFor, ""), credits: c.value(.credits, []),
                  sortedBy: c.value(.sortedBy, "release"), cache: c.value(.cache, CacheInfo()))
    }
}

/// A quality profile (or a tag) the request form offers.
public struct RequestOption: Decodable, Equatable, Sendable, Identifiable {
    public var id: Int
    public var label: String
    public var isDefault: Bool

    public init(id: Int, label: String, isDefault: Bool = false) {
        self.id = id
        self.label = label
        self.isDefault = isDefault
    }

    enum CodingKeys: String, CodingKey { case id, label, isDefault = "default" }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, 0), label: c.value(.label, ""), isDefault: c.value(.isDefault, false))
    }
}

/// A folder the title can go into; `path` is what a request sends.
public struct RootFolderOption: Decodable, Equatable, Sendable, Identifiable {
    public var id: Int
    public var path: String
    /// The path's last two parts ("Daniel\Movies").
    public var label: String
    public var freeSpaceBytes: Int64
    public var isDefault: Bool

    public init(id: Int, path: String, label: String = "", freeSpaceBytes: Int64 = 0, isDefault: Bool = false) {
        self.id = id
        self.path = path
        self.label = label
        self.freeSpaceBytes = freeSpaceBytes
        self.isDefault = isDefault
    }

    enum CodingKeys: String, CodingKey { case id, path, label, freeSpaceBytes, isDefault = "default" }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, 0), path: c.value(.path, ""), label: c.value(.label, ""),
                  freeSpaceBytes: c.value(.freeSpaceBytes, 0), isDefault: c.value(.isDefault, false))
    }
}

/// `GET /v1/requests/options?key=`: what a request for this title can choose,
/// from the Radarr or Sonarr server Jellyseerr sends it to.
public struct RequestOptions: Decodable, Equatable, Sendable {
    public var key: String
    public var type: String
    public var title: String
    /// "radarr" or "sonarr".
    public var service: String
    /// Jellyseerr's id for the server; 0 is a real id.
    public var serverId: Int
    public var serverName: String
    public var profiles: [RequestOption]
    public var rootFolders: [RootFolderOption]
    public var seasons: [SeasonOption]
    public var partial: [Partial]

    public init(key: String, type: String = "", title: String = "", service: String = "", serverId: Int = 0,
                serverName: String = "", profiles: [RequestOption] = [], rootFolders: [RootFolderOption] = [],
                seasons: [SeasonOption] = [], partial: [Partial] = []) {
        self.key = key
        self.type = type
        self.title = title
        self.service = service
        self.serverId = serverId
        self.serverName = serverName
        self.profiles = profiles
        self.rootFolders = rootFolders
        self.seasons = seasons
        self.partial = partial
    }

    enum CodingKeys: String, CodingKey { case key, type, title, service, serverId, serverName, profiles, rootFolders, seasons, partial }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(key: c.value(.key, ""), type: c.value(.type, ""), title: c.value(.title, ""),
                  service: c.value(.service, ""), serverId: c.value(.serverId, 0), serverName: c.value(.serverName, ""),
                  profiles: c.value(.profiles, []), rootFolders: c.value(.rootFolders, []),
                  seasons: c.value(.seasons, []), partial: c.value(.partial, []))
    }
}

/// `POST /v1/requests`' body. `seasons` is "all" or the season numbers, and
/// left out for a film.
public struct CreateRequestBody: Encodable, Equatable, Sendable {
    public enum Seasons: Equatable, Sendable {
        case all
        case numbers([Int])
    }

    public var key: String
    public var seasons: Seasons?
    public var profileId: Int?
    public var rootFolder: String?
    public var serverId: Int?
    public var is4k = false

    public init(key: String, seasons: Seasons? = nil, profileId: Int? = nil, rootFolder: String? = nil,
                serverId: Int? = nil) {
        self.key = key
        self.seasons = seasons
        self.profileId = profileId
        self.rootFolder = rootFolder
        self.serverId = serverId
    }

    enum CodingKeys: String, CodingKey { case key, seasons, profileId, rootFolder, serverId, is4k }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(key, forKey: .key)
        switch seasons {
        case .all?: try c.encode("all", forKey: .seasons)
        case .numbers(let numbers)?: try c.encode(numbers, forKey: .seasons)
        case nil: break
        }
        try c.encodeIfPresent(profileId, forKey: .profileId)
        try c.encodeIfPresent(rootFolder, forKey: .rootFolder)
        try c.encodeIfPresent(serverId, forKey: .serverId)
        try c.encode(is4k, forKey: .is4k)
    }
}

/// What `POST /v1/requests` answers: the request's state, the hub's sentence
/// for it, and where the title now is.
public struct CreateRequestReply: Decodable, Equatable, Sendable {
    public var requestId: Int
    /// "pending", "approved", "declined", "failed", "completed" or "unknown".
    public var state: String
    public var message: String
    public var availability: String

    public init(requestId: Int = 0, state: String = "", message: String = "", availability: String = "") {
        self.requestId = requestId
        self.state = state
        self.message = message
        self.availability = availability
    }

    enum CodingKeys: String, CodingKey { case requestId, state, message, availability }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(requestId: c.value(.requestId, 0), state: c.value(.state, ""), message: c.value(.message, ""),
                  availability: c.value(.availability, ""))
    }
}

/// An aired episode a release search can be for.
public struct ReleaseEpisodeTarget: Decodable, Equatable, Sendable, Identifiable {
    public var season: Int
    public var episode: Int
    public var title: String
    public var overview: String
    /// "YYYY-MM-DD" as Sonarr has it.
    public var airDate: String
    public var runtimeMinutes: Int
    public var image: String
    public var hasFile: Bool
    public var monitored: Bool

    public var id: String { "\(season):\(episode)" }

    public init(season: Int, episode: Int, title: String = "", overview: String = "", airDate: String = "",
                runtimeMinutes: Int = 0, image: String = "", hasFile: Bool = false, monitored: Bool = true) {
        self.season = season
        self.episode = episode
        self.title = title
        self.overview = overview
        self.airDate = airDate
        self.runtimeMinutes = runtimeMinutes
        self.image = image
        self.hasFile = hasFile
        self.monitored = monitored
    }

    enum CodingKeys: String, CodingKey { case season, episode, title, overview, airDate, runtimeMinutes, image, hasFile, monitored }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(season: c.value(.season, 0), episode: c.value(.episode, 0), title: c.value(.title, ""),
                  overview: c.value(.overview, ""), airDate: c.value(.airDate, ""),
                  runtimeMinutes: c.value(.runtimeMinutes, 0), image: c.value(.image, ""),
                  hasFile: c.value(.hasFile, false), monitored: c.value(.monitored, true))
    }
}

/// `GET /v1/media/{key}/release-targets?season=`: a season's aired episodes.
public struct ReleaseTargetsResponse: Decodable, Equatable, Sendable {
    public var key: String
    public var title: String
    public var season: Int
    public var seasonTitle: String
    public var seasonImage: String
    public var episodes: [ReleaseEpisodeTarget]
    public var partial: [Partial]
    public var cache: CacheInfo

    public init(key: String = "", title: String = "", season: Int = 0, seasonTitle: String = "", seasonImage: String = "",
                episodes: [ReleaseEpisodeTarget] = [], partial: [Partial] = [], cache: CacheInfo = CacheInfo()) {
        self.key = key
        self.title = title
        self.season = season
        self.seasonTitle = seasonTitle
        self.seasonImage = seasonImage
        self.episodes = episodes
        self.partial = partial
        self.cache = cache
    }

    enum CodingKeys: String, CodingKey { case key, title, season, seasonTitle, seasonImage, episodes, partial, cache }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(key: c.value(.key, ""), title: c.value(.title, ""), season: c.value(.season, 0),
                  seasonTitle: c.value(.seasonTitle, ""), seasonImage: c.value(.seasonImage, ""),
                  episodes: c.value(.episodes, []), partial: c.value(.partial, []), cache: c.value(.cache, CacheInfo()))
    }
}

/// One release an interactive search found. `id` is the hub's opaque grab id;
/// the indexer's own address never reaches the app.
public struct Release: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var indexer: String
    public var quality: String
    public var protocolName: String
    public var sizeBytes: Int64
    public var seeders: Int
    public var leechers: Int
    public var ageDays: Int
    public var releaseGroup: String
    public var languages: [String]
    public var freeleech: Bool
    public var score: Int
    /// Radarr or Sonarr would not pick it; it can still be grabbed, as an override.
    public var rejected: Bool
    /// Their sentences, verbatim.
    public var rejections: [String]
    /// Holds more than the season or episode searched for: it cannot be grabbed.
    public var scopeBlocked: Bool

    public init(id: String, title: String, indexer: String = "", quality: String = "", protocolName: String = "",
                sizeBytes: Int64 = 0, seeders: Int = 0, leechers: Int = 0, ageDays: Int = 0, releaseGroup: String = "",
                languages: [String] = [], freeleech: Bool = false, score: Int = 0, rejected: Bool = false,
                rejections: [String] = [], scopeBlocked: Bool = false) {
        self.id = id
        self.title = title
        self.indexer = indexer
        self.quality = quality
        self.protocolName = protocolName
        self.sizeBytes = sizeBytes
        self.seeders = seeders
        self.leechers = leechers
        self.ageDays = ageDays
        self.releaseGroup = releaseGroup
        self.languages = languages
        self.freeleech = freeleech
        self.score = score
        self.rejected = rejected
        self.rejections = rejections
        self.scopeBlocked = scopeBlocked
    }

    enum CodingKeys: String, CodingKey {
        case id, title, indexer, quality, protocolName = "protocol", sizeBytes, seeders, leechers, ageDays, releaseGroup,
             languages, freeleech, score, rejected, rejections, scopeBlocked
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), title: c.value(.title, ""), indexer: c.value(.indexer, ""),
                  quality: c.value(.quality, ""), protocolName: c.value(.protocolName, ""),
                  sizeBytes: c.value(.sizeBytes, 0), seeders: c.value(.seeders, 0), leechers: c.value(.leechers, 0),
                  ageDays: c.value(.ageDays, 0), releaseGroup: c.value(.releaseGroup, ""), languages: c.value(.languages, []),
                  freeleech: c.value(.freeleech, false), score: c.value(.score, 0), rejected: c.value(.rejected, false),
                  rejections: c.value(.rejections, []), scopeBlocked: c.value(.scopeBlocked, false))
    }
}

/// `GET /v1/media/{key}/releases`: the search's releases, those Radarr or
/// Sonarr would take first, then the rest in their order.
public struct ReleasesResponse: Decodable, Equatable, Sendable {
    public var key: String
    public var title: String
    public var service: String
    public var season: Int
    public var episode: Int
    public var releases: [Release]
    /// Neither rejected nor outside the scope.
    public var accepted: Int
    public var cache: CacheInfo

    public init(key: String = "", title: String = "", service: String = "", season: Int = 0, episode: Int = 0,
                releases: [Release] = [], accepted: Int = 0, cache: CacheInfo = CacheInfo()) {
        self.key = key
        self.title = title
        self.service = service
        self.season = season
        self.episode = episode
        self.releases = releases
        self.accepted = accepted
        self.cache = cache
    }

    enum CodingKeys: String, CodingKey { case key, title, service, season, episode, releases, accepted, cache }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(key: c.value(.key, ""), title: c.value(.title, ""), service: c.value(.service, ""),
                  season: c.value(.season, 0), episode: c.value(.episode, 0), releases: c.value(.releases, []),
                  accepted: c.value(.accepted, 0), cache: c.value(.cache, CacheInfo()))
    }
}

/// `POST /v1/media/{key}/grab`'s body: the release, and the season and episode
/// of the search that found it.
public struct GrabBody: Encodable, Equatable, Sendable {
    public var releaseId: String
    public var season: Int?
    public var episode: Int?

    public init(releaseId: String, season: Int? = nil, episode: Int? = nil) {
        self.releaseId = releaseId
        self.season = season
        self.episode = episode
    }
}

/// What a grab answers: the release as the download client took it.
public struct GrabReply: Decodable, Equatable, Sendable {
    public var title: String
    public var quality: String
    public var service: String

    public init(title: String = "", quality: String = "", service: String = "") {
        self.title = title
        self.quality = quality
        self.service = service
    }

    enum CodingKeys: String, CodingKey { case title, quality, service }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(title: c.value(.title, ""), quality: c.value(.quality, ""), service: c.value(.service, ""))
    }
}

/// One release on the calendar: an episode, or a film's cinema, digital or
/// physical release.
public struct CalendarItem: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    /// "sonarr" or "radarr".
    public var service: String
    /// Its `key` opens the title; empty when the *arr has no TMDB id. Its
    /// poster is the *arr's.
    public var media: MediaRef
    /// The day, in the timezone asked for ("2026-10-02").
    public var date: String
    /// The moment it airs, in UTC; empty when only the day is known.
    public var at: String
    /// "Episode", "Digital release", "Physical release" or "In cinemas".
    public var releaseType: String
    public var season: Int
    public var episode: Int
    public var episodeTitle: String
    public var overview: String
    public var hasFile: Bool

    public init(id: String, service: String = "", media: MediaRef = MediaRef(), date: String, at: String = "",
                releaseType: String = "", season: Int = 0, episode: Int = 0, episodeTitle: String = "",
                overview: String = "", hasFile: Bool = false) {
        self.id = id
        self.service = service
        self.media = media
        self.date = date
        self.at = at
        self.releaseType = releaseType
        self.season = season
        self.episode = episode
        self.episodeTitle = episodeTitle
        self.overview = overview
        self.hasFile = hasFile
    }

    enum CodingKeys: String, CodingKey { case id, service, media, date, at, releaseType, season, episode, episodeTitle, overview, hasFile }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), service: c.value(.service, ""), media: c.value(.media, MediaRef()),
                  date: c.value(.date, ""), at: c.value(.at, ""), releaseType: c.value(.releaseType, ""),
                  season: c.value(.season, 0), episode: c.value(.episode, 0), episodeTitle: c.value(.episodeTitle, ""),
                  overview: c.value(.overview, ""), hasFile: c.value(.hasFile, false))
    }
}

/// `GET /v1/calendar?start=&end=&timezone=`: monitored releases, by day.
public struct CalendarResponse: Decodable, Equatable, Sendable {
    public var start: String
    public var end: String
    public var items: [CalendarItem]
    public var partial: [Partial]

    public init(start: String = "", end: String = "", items: [CalendarItem] = [], partial: [Partial] = []) {
        self.start = start
        self.end = end
        self.items = items
        self.partial = partial
    }

    enum CodingKeys: String, CodingKey { case start, end, items, partial }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(start: c.value(.start, ""), end: c.value(.end, ""), items: c.value(.items, []),
                  partial: c.value(.partial, []))
    }
}
