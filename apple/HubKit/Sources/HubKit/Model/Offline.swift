import Foundation

// Offline downloads (#5): the hub's `/v1/offline/*` answers, as
// `hub/internal/api/offline.go` writes them and Android's `model/Offline.kt`
// reads them. Every field defaults, as everywhere in HubKit.

/// One downloadable version of a film or an episode: its container, size and tracks.
public struct OfflineSource: Decodable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var container: String
    public var mimeType: String
    public var sizeBytes: Int64
    public var bitrate: Int
    public var tracks: [PlaybackTrack]

    public init(id: String = "", name: String = "", container: String = "", mimeType: String = "video/*",
                sizeBytes: Int64 = 0, bitrate: Int = 0, tracks: [PlaybackTrack] = []) {
        self.id = id
        self.name = name
        self.container = container
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.bitrate = bitrate
        self.tracks = tracks
    }

    enum CodingKeys: String, CodingKey { case id, name, container, mimeType, sizeBytes, bitrate, tracks }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), name: c.value(.name, ""), container: c.value(.container, ""),
                  mimeType: c.value(.mimeType, "video/*"), sizeBytes: c.value(.sizeBytes, 0), bitrate: c.value(.bitrate, 0),
                  tracks: c.value(.tracks, []))
    }
}

/// An episode in the picker: whether it can be downloaded, and about how big it is.
public struct OfflineSelectionItem: Decodable, Equatable, Sendable {
    public var item: LibraryItem
    public var sources: [OfflineSource]
    public var estimatedSizeBytes: Int64
    public var available: Bool

    public init(item: LibraryItem, sources: [OfflineSource] = [], estimatedSizeBytes: Int64 = 0, available: Bool = false) {
        self.item = item
        self.sources = sources
        self.estimatedSizeBytes = estimatedSizeBytes
        self.available = available
    }

    enum CodingKeys: String, CodingKey { case item, sources, estimatedSizeBytes, available }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(item: c.value(.item, LibraryItem(id: "", type: "episode", title: "")), sources: c.value(.sources, []),
                  estimatedSizeBytes: c.value(.estimatedSizeBytes, 0), available: c.value(.available, false))
    }
}

public struct OfflineSelectionSeason: Decodable, Equatable, Sendable {
    public var season: LibraryItem
    public var episodes: [OfflineSelectionItem]

    public init(season: LibraryItem, episodes: [OfflineSelectionItem] = []) {
        self.season = season
        self.episodes = episodes
    }

    enum CodingKeys: String, CodingKey { case season, episodes }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(season: c.value(.season, LibraryItem(id: "", type: "season", title: "")), episodes: c.value(.episodes, []))
    }
}

/// `GET /v1/offline/series/{seriesId}/selection`: every season and episode,
/// for the profile watching, with what can be downloaded and its size.
public struct OfflineSelectionResponse: Decodable, Equatable, Sendable {
    public var series: LibraryItem
    public var seasons: [OfflineSelectionSeason]
    public var episodeCount: Int
    public var estimatedSizeBytes: Int64
    /// The episode Play would start: where "next episodes" begin.
    public var playTargetId: String

    public init(series: LibraryItem, seasons: [OfflineSelectionSeason] = [], episodeCount: Int = 0,
                estimatedSizeBytes: Int64 = 0, playTargetId: String = "") {
        self.series = series
        self.seasons = seasons
        self.episodeCount = episodeCount
        self.estimatedSizeBytes = estimatedSizeBytes
        self.playTargetId = playTargetId
    }

    enum CodingKeys: String, CodingKey { case series, seasons, episodeCount, estimatedSizeBytes, playTargetId }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(series: c.value(.series, LibraryItem(id: "", type: "series", title: "")), seasons: c.value(.seasons, []),
                  episodeCount: c.value(.episodeCount, 0), estimatedSizeBytes: c.value(.estimatedSizeBytes, 0),
                  playTargetId: c.value(.playTargetId, ""))
    }
}

/// `POST /v1/offline/prepare`: the items to keep, each under a key of this
/// device's own, so asking twice gives the same grants.
public struct OfflinePrepareBody: Encodable, Equatable, Sendable {
    public var batchKey: String
    public var seriesId: String
    public var items: [OfflinePrepareItem]

    public init(batchKey: String, seriesId: String = "", items: [OfflinePrepareItem]) {
        self.batchKey = batchKey
        self.seriesId = seriesId
        self.items = items
    }

    enum CodingKeys: String, CodingKey { case batchKey, seriesId, items }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(batchKey, forKey: .batchKey)
        if !seriesId.isEmpty { try c.encode(seriesId, forKey: .seriesId) }
        try c.encode(items, forKey: .items)
    }
}

public struct OfflinePrepareItem: Encodable, Equatable, Sendable {
    public var clientItemKey: String
    public var itemId: String
    public var mediaSourceId: String

    public init(clientItemKey: String, itemId: String, mediaSourceId: String = "") {
        self.clientItemKey = clientItemKey
        self.itemId = itemId
        self.mediaSourceId = mediaSourceId
    }

    enum CodingKeys: String, CodingKey { case clientItemKey, itemId, mediaSourceId }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(clientItemKey, forKey: .clientItemKey)
        try c.encode(itemId, forKey: .itemId)
        if !mediaSourceId.isEmpty { try c.encode(mediaSourceId, forKey: .mediaSourceId) }
    }
}

/// A subtitle file beside the video, and the grant's route to it.
public struct OfflineSubtitle: Decodable, Equatable, Sendable {
    public var track: PlaybackTrack
    public var url: String

    public init(track: PlaybackTrack = PlaybackTrack(), url: String = "") {
        self.track = track
        self.url = url
    }

    enum CodingKeys: String, CodingKey { case track, url }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(track: c.value(.track, PlaybackTrack()), url: c.value(.url, ""))
    }
}

/// What one grant lets this device download: the item, the exact version,
/// its tracks, and the routes to the file and its subtitles. Kept on the
/// device as the hub wrote it (`OfflineRow.manifestJSON`), so a field a later
/// hub adds survives until a later app reads it.
public struct OfflineManifest: Decodable, Equatable, Sendable {
    public var grantId: String
    public var batchKey: String
    public var clientItemKey: String
    /// Unix milliseconds: after it, the routes answer 410 until the grant is renewed.
    public var expiresAt: Int64
    public var item: LibraryItem
    /// The Jellyfin library the item is in, for Downloads' grouping; empty from an older hub.
    public var library: String
    /// When the server last saw it played, Unix milliseconds, 0 when unknown.
    public var lastPlayedAt: Int64
    public var source: OfflineSource
    public var mediaUrl: String
    public var subtitles: [OfflineSubtitle]

    public init(grantId: String = "", batchKey: String = "", clientItemKey: String = "", expiresAt: Int64 = 0,
                item: LibraryItem = LibraryItem(id: "", type: "", title: ""), library: String = "", lastPlayedAt: Int64 = 0,
                source: OfflineSource = OfflineSource(), mediaUrl: String = "", subtitles: [OfflineSubtitle] = []) {
        self.grantId = grantId
        self.batchKey = batchKey
        self.clientItemKey = clientItemKey
        self.expiresAt = expiresAt
        self.item = item
        self.library = library
        self.lastPlayedAt = lastPlayedAt
        self.source = source
        self.mediaUrl = mediaUrl
        self.subtitles = subtitles
    }

    enum CodingKeys: String, CodingKey {
        case grantId, batchKey, clientItemKey, expiresAt, item, source, mediaUrl, subtitles
    }

    /// The parts of the item `LibraryItem` does not keep.
    private struct ItemExtras: Decodable {
        struct Library: Decodable {
            var name: String?
        }

        var library: Library?
        var lastPlayedAt: Int64?
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let extras: ItemExtras? = c.optional(.item)
        self.init(grantId: c.value(.grantId, ""), batchKey: c.value(.batchKey, ""), clientItemKey: c.value(.clientItemKey, ""),
                  expiresAt: c.value(.expiresAt, 0), item: c.value(.item, LibraryItem(id: "", type: "", title: "")),
                  library: extras?.library?.name ?? "", lastPlayedAt: extras?.lastPlayedAt ?? 0,
                  source: c.value(.source, OfflineSource()), mediaUrl: c.value(.mediaUrl, ""),
                  subtitles: c.value(.subtitles, []))
    }

    /// Each manifest in a prepare answer, with its JSON as the hub wrote it.
    public static func list(from data: Data) -> [(manifest: OfflineManifest, json: Data)] {
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let items = object["items"] as? [Any] else { return [] }
        return items.compactMap { item in
            guard let json = try? JSONSerialization.data(withJSONObject: item),
                  let manifest = try? JSONDecoder().decode(OfflineManifest.self, from: json) else { return nil }
            return (manifest, json)
        }
    }
}

/// A watch made on this device, sent when the hub can be reached.
public struct OfflineProgressEvent: Codable, Equatable, Sendable {
    public var clientEventKey: String
    public var itemId: String
    public var positionMillis: Int64
    public var durationMillis: Int64
    public var completed: Bool
    /// When it was watched, Unix milliseconds: the hub keeps a later watch from elsewhere.
    public var occurredAt: Int64

    public init(clientEventKey: String, itemId: String, positionMillis: Int64, durationMillis: Int64,
                completed: Bool = false, occurredAt: Int64) {
        self.clientEventKey = clientEventKey
        self.itemId = itemId
        self.positionMillis = positionMillis
        self.durationMillis = durationMillis
        self.completed = completed
        self.occurredAt = occurredAt
    }

    enum CodingKeys: String, CodingKey { case clientEventKey, itemId, positionMillis, durationMillis, completed, occurredAt }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(clientEventKey: c.value(.clientEventKey, ""), itemId: c.value(.itemId, ""),
                  positionMillis: c.value(.positionMillis, 0), durationMillis: c.value(.durationMillis, 0),
                  completed: c.value(.completed, false), occurredAt: c.value(.occurredAt, 0))
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(clientEventKey, forKey: .clientEventKey)
        try c.encode(itemId, forKey: .itemId)
        try c.encode(positionMillis, forKey: .positionMillis)
        try c.encode(durationMillis, forKey: .durationMillis)
        if completed { try c.encode(true, forKey: .completed) }
        try c.encode(occurredAt, forKey: .occurredAt)
    }
}

/// `POST /v1/offline/progress/sync`: up to a hundred watches at once.
public struct OfflineProgressSyncBody: Encodable, Equatable, Sendable {
    public var events: [OfflineProgressEvent]

    public init(events: [OfflineProgressEvent]) {
        self.events = events
    }
}

/// What the hub did with one watch: "applied", "server_newer" (a later
/// watch elsewhere, which this device then takes) or "duplicate".
public struct OfflineProgressResult: Decodable, Equatable, Sendable {
    public var clientEventKey: String
    public var itemId: String
    public var status: String
    public var serverPositionMillis: Int64
    public var serverPlayed: Bool
    public var serverLastPlayedAt: Int64

    public init(clientEventKey: String = "", itemId: String = "", status: String = "", serverPositionMillis: Int64 = 0,
                serverPlayed: Bool = false, serverLastPlayedAt: Int64 = 0) {
        self.clientEventKey = clientEventKey
        self.itemId = itemId
        self.status = status
        self.serverPositionMillis = serverPositionMillis
        self.serverPlayed = serverPlayed
        self.serverLastPlayedAt = serverLastPlayedAt
    }

    enum CodingKeys: String, CodingKey {
        case clientEventKey, itemId, status, serverPositionMillis, serverPlayed, serverLastPlayedAt
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(clientEventKey: c.value(.clientEventKey, ""), itemId: c.value(.itemId, ""), status: c.value(.status, ""),
                  serverPositionMillis: c.value(.serverPositionMillis, 0), serverPlayed: c.value(.serverPlayed, false),
                  serverLastPlayedAt: c.value(.serverLastPlayedAt, 0))
    }
}

public struct OfflineProgressSyncResponse: Decodable, Equatable, Sendable {
    public var results: [OfflineProgressResult]

    public init(results: [OfflineProgressResult] = []) {
        self.results = results
    }

    enum CodingKeys: String, CodingKey { case results }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(results: c.value(.results, []))
    }
}
