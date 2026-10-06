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

/// The two shapes a download comes in: the original file (Android's), or an
/// MP4 the hub repackages for AVPlayer (#5, option 1), which is all this app asks for.
public enum OfflineFormat {
    public static let original = "original"
    public static let apple = "apple"
}

/// An episode in the picker: whether it can be downloaded, and about how big it is.
public struct OfflineSelectionItem: Decodable, Equatable, Sendable {
    public var item: LibraryItem
    public var sources: [OfflineSource]
    /// With `?format=apple`, the MP4's estimate.
    public var estimatedSizeBytes: Int64
    /// With `?format=apple`, false for an item that cannot become an MP4.
    public var available: Bool
    /// With `?format=apple`: what will not come across as it is.
    public var apple: OfflineAppleSummary?

    public init(item: LibraryItem, sources: [OfflineSource] = [], estimatedSizeBytes: Int64 = 0, available: Bool = false,
                apple: OfflineAppleSummary? = nil) {
        self.item = item
        self.sources = sources
        self.estimatedSizeBytes = estimatedSizeBytes
        self.available = available
        self.apple = apple
    }

    enum CodingKeys: String, CodingKey { case item, sources, estimatedSizeBytes, available, apple }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(item: c.value(.item, LibraryItem(id: "", type: "episode", title: "")), sources: c.value(.sources, []),
                  estimatedSizeBytes: c.value(.estimatedSizeBytes, 0), available: c.value(.available, false),
                  apple: c.optional(.apple))
    }
}

/// What the picker is told of an episode's MP4 before anything is prepared:
/// its size, and what will not come across as it is.
public struct OfflineAppleSummary: Decodable, Equatable, Sendable {
    public var estimatedSizeBytes: Int64
    /// The picture is converted to H.264, so the item takes longer to prepare.
    public var videoConverted: Bool
    /// How many audio tracks become AAC.
    public var audioConverted: Int
    /// How many subtitle tracks are left out (picture subtitles).
    public var omittedSubtitles: Int

    public init(estimatedSizeBytes: Int64 = 0, videoConverted: Bool = false, audioConverted: Int = 0, omittedSubtitles: Int = 0) {
        self.estimatedSizeBytes = estimatedSizeBytes
        self.videoConverted = videoConverted
        self.audioConverted = audioConverted
        self.omittedSubtitles = omittedSubtitles
    }

    enum CodingKeys: String, CodingKey { case estimatedSizeBytes, videoConverted, audioConverted, omittedSubtitles }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(estimatedSizeBytes: c.value(.estimatedSizeBytes, 0), videoConverted: c.value(.videoConverted, false),
                  audioConverted: c.value(.audioConverted, 0), omittedSubtitles: c.value(.omittedSubtitles, 0))
    }
}

/// The picture of an Apple download's MP4.
public struct OfflineAppleVideo: Decodable, Equatable, Sendable {
    /// The track's `index` in the source (Jellyfin's stream index).
    public var sourceIndex: Int
    public var codec: String
    public var outputCodec: String
    /// "avc1" or "hvc1".
    public var tag: String
    /// Re-encoded to H.264: the item takes longer to prepare.
    public var converted: Bool
    /// "unsupported_codec" or "unsupported_profile" when converted.
    public var reason: String
    public var width: Int
    public var height: Int

    public init(sourceIndex: Int = -1, codec: String = "", outputCodec: String = "", tag: String = "", converted: Bool = false,
                reason: String = "", width: Int = 0, height: Int = 0) {
        self.sourceIndex = sourceIndex
        self.codec = codec
        self.outputCodec = outputCodec
        self.tag = tag
        self.converted = converted
        self.reason = reason
        self.width = width
        self.height = height
    }

    enum CodingKeys: String, CodingKey { case sourceIndex, codec, outputCodec, tag, converted, reason, width, height }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(sourceIndex: c.value(.sourceIndex, -1), codec: c.value(.codec, ""), outputCodec: c.value(.outputCodec, ""),
                  tag: c.value(.tag, ""), converted: c.value(.converted, false), reason: c.value(.reason, ""),
                  width: c.value(.width, 0), height: c.value(.height, 0))
    }
}

/// An audio track of the MP4, in the MP4's order: the nth option AVPlayer
/// lists is the nth of these.
public struct OfflineAppleAudio: Decodable, Equatable, Sendable {
    public var sourceIndex: Int
    /// ISO 639-2/T, as written into the MP4 ("eng"; "und" when unknown).
    public var language: String
    public var label: String
    public var codec: String
    public var outputCodec: String
    public var channels: Int
    public var outputChannels: Int
    /// AAC in the MP4 (`outputCodec`).
    public var converted: Bool
    public var reason: String
    public var isDefault: Bool

    public init(sourceIndex: Int = -1, language: String = "", label: String = "", codec: String = "", outputCodec: String = "",
                channels: Int = 0, outputChannels: Int = 0, converted: Bool = false, reason: String = "", isDefault: Bool = false) {
        self.sourceIndex = sourceIndex
        self.language = language
        self.label = label
        self.codec = codec
        self.outputCodec = outputCodec
        self.channels = channels
        self.outputChannels = outputChannels
        self.converted = converted
        self.reason = reason
        self.isDefault = isDefault
    }

    enum CodingKeys: String, CodingKey {
        case sourceIndex, language, label, codec, outputCodec, channels, outputChannels, converted, reason
        case isDefault = "default"
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(sourceIndex: c.value(.sourceIndex, -1), language: c.value(.language, ""), label: c.value(.label, ""),
                  codec: c.value(.codec, ""), outputCodec: c.value(.outputCodec, ""), channels: c.value(.channels, 0),
                  outputChannels: c.value(.outputChannels, 0), converted: c.value(.converted, false),
                  reason: c.value(.reason, ""), isDefault: c.value(.isDefault, false))
    }
}

/// A subtitle track of the source and whether it is in the MP4. The ones
/// in it, in this order, are the MP4's subtitle options; none is on.
public struct OfflineAppleSubtitle: Decodable, Equatable, Sendable {
    public var sourceIndex: Int
    public var language: String
    public var label: String
    public var codec: String
    public var outputCodec: String
    public var external: Bool
    public var forced: Bool
    public var hearingImpaired: Bool
    /// In the MP4; when false, `reason` says why not ("picture_subtitle", "unsupported_format").
    public var available: Bool
    public var reason: String

    public init(sourceIndex: Int = -1, language: String = "", label: String = "", codec: String = "", outputCodec: String = "",
                external: Bool = false, forced: Bool = false, hearingImpaired: Bool = false, available: Bool = false,
                reason: String = "") {
        self.sourceIndex = sourceIndex
        self.language = language
        self.label = label
        self.codec = codec
        self.outputCodec = outputCodec
        self.external = external
        self.forced = forced
        self.hearingImpaired = hearingImpaired
        self.available = available
        self.reason = reason
    }

    enum CodingKeys: String, CodingKey {
        case sourceIndex, language, label, codec, outputCodec, external, forced, hearingImpaired, available, reason
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(sourceIndex: c.value(.sourceIndex, -1), language: c.value(.language, ""), label: c.value(.label, ""),
                  codec: c.value(.codec, ""), outputCodec: c.value(.outputCodec, ""), external: c.value(.external, false),
                  forced: c.value(.forced, false), hearingImpaired: c.value(.hearingImpaired, false),
                  available: c.value(.available, false), reason: c.value(.reason, ""))
    }
}

/// What an Apple download's MP4 will hold, told before any work starts and
/// the same for the life of the grant, so it can be shown offline.
public struct OfflineApple: Decodable, Equatable, Sendable {
    public var container: String
    public var mimeType: String
    /// For the free-space check: within about 10% when the picture is copied.
    /// The exact size comes from the status once the file is ready.
    public var estimatedSizeBytes: Int64
    public var statusUrl: String
    public var video: OfflineAppleVideo
    public var audio: [OfflineAppleAudio]
    public var subtitles: [OfflineAppleSubtitle]

    public init(container: String = "mp4", mimeType: String = "video/mp4", estimatedSizeBytes: Int64 = 0, statusUrl: String = "",
                video: OfflineAppleVideo = OfflineAppleVideo(), audio: [OfflineAppleAudio] = [],
                subtitles: [OfflineAppleSubtitle] = []) {
        self.container = container
        self.mimeType = mimeType
        self.estimatedSizeBytes = estimatedSizeBytes
        self.statusUrl = statusUrl
        self.video = video
        self.audio = audio
        self.subtitles = subtitles
    }

    enum CodingKeys: String, CodingKey { case container, mimeType, estimatedSizeBytes, statusUrl, video, audio, subtitles }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(container: c.value(.container, "mp4"), mimeType: c.value(.mimeType, "video/mp4"),
                  estimatedSizeBytes: c.value(.estimatedSizeBytes, 0), statusUrl: c.value(.statusUrl, ""),
                  video: c.value(.video, OfflineAppleVideo()), audio: c.value(.audio, []), subtitles: c.value(.subtitles, []))
    }

    /// The subtitle tracks in the MP4, in its order.
    public var keptSubtitles: [OfflineAppleSubtitle] { subtitles.filter(\.available) }
}

/// `GET /v1/offline/grants/{grantId}/status`: where one grant's file stands.
public struct OfflineGrantStatus: Decodable, Equatable, Sendable {
    public enum State: String, Sendable {
        /// Waiting its turn, or for room in the PC's cache.
        case queued
        case preparing
        case ready
        case failed
        case unknown
    }

    public var grantId: String
    public var format: String
    public var state: State
    /// 0 to 100; 99 while the hub finishes the file, 100 when ready.
    public var percent: Int
    /// 1 for the next to run, 2 for the one after; 0 when running, ready or failed.
    public var queuePosition: Int
    public var estimatedSizeBytes: Int64
    /// The exact size, once ready.
    public var sizeBytes: Int64
    /// Strong, naming this exact file, once ready.
    public var etag: String
    public var error: OfflineStatusError?

    public init(grantId: String = "", format: String = "", state: State = .unknown, percent: Int = 0, queuePosition: Int = 0,
                estimatedSizeBytes: Int64 = 0, sizeBytes: Int64 = 0, etag: String = "", error: OfflineStatusError? = nil) {
        self.grantId = grantId
        self.format = format
        self.state = state
        self.percent = percent
        self.queuePosition = queuePosition
        self.estimatedSizeBytes = estimatedSizeBytes
        self.sizeBytes = sizeBytes
        self.etag = etag
        self.error = error
    }

    enum CodingKeys: String, CodingKey {
        case grantId, format, state, percent, queuePosition, estimatedSizeBytes, sizeBytes, etag, error
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(grantId: c.value(.grantId, ""), format: c.value(.format, ""),
                  state: State(rawValue: c.value(.state, "")) ?? .unknown, percent: c.value(.percent, 0),
                  queuePosition: c.value(.queuePosition, 0), estimatedSizeBytes: c.value(.estimatedSizeBytes, 0),
                  sizeBytes: c.value(.sizeBytes, 0), etag: c.value(.etag, ""), error: c.optional(.error))
    }
}

/// Why an MP4 could not be made: `source_missing`, `source_changed` (prepare
/// again), `no_ffmpeg`, `disk_full`, `ffmpeg_failed`, `subtitle_unavailable`
/// or `internal`, and whether a retry can help.
public struct OfflineStatusError: Decodable, Equatable, Sendable {
    public var code: String
    public var message: String
    public var retryable: Bool

    public init(code: String = "", message: String = "", retryable: Bool = false) {
        self.code = code
        self.message = message
        self.retryable = retryable
    }

    enum CodingKeys: String, CodingKey { case code, message, retryable }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(code: c.value(.code, ""), message: c.value(.message, ""), retryable: c.value(.retryable, false))
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
    /// "apple" for an MP4 AVPlayer plays; empty is the original file.
    public var format: String
    public var items: [OfflinePrepareItem]

    public init(batchKey: String, seriesId: String = "", format: String = "", items: [OfflinePrepareItem]) {
        self.batchKey = batchKey
        self.seriesId = seriesId
        self.format = format
        self.items = items
    }

    enum CodingKeys: String, CodingKey { case batchKey, seriesId, format, items }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(batchKey, forKey: .batchKey)
        if !seriesId.isEmpty { try c.encode(seriesId, forKey: .seriesId) }
        if !format.isEmpty { try c.encode(format, forKey: .format) }
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
    /// The original file, even for an Apple download (its container, size and every track).
    public var source: OfflineSource
    public var mediaUrl: String
    /// Subtitle files beside an original; empty for an Apple download, whose
    /// text subtitles are inside the MP4.
    public var subtitles: [OfflineSubtitle]
    /// "apple", or empty for the original. A hub older than the Apple format
    /// ignores the request for one and answers the original, which AVPlayer
    /// cannot play, so this is checked (`isApple`).
    public var format: String
    /// What the MP4 will hold, for an Apple download.
    public var apple: OfflineApple?

    public init(grantId: String = "", batchKey: String = "", clientItemKey: String = "", expiresAt: Int64 = 0,
                item: LibraryItem = LibraryItem(id: "", type: "", title: ""), library: String = "", lastPlayedAt: Int64 = 0,
                source: OfflineSource = OfflineSource(), mediaUrl: String = "", subtitles: [OfflineSubtitle] = [],
                format: String = "", apple: OfflineApple? = nil) {
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
        self.format = format
        self.apple = apple
    }

    /// An MP4 the hub repackages for this device.
    public var isApple: Bool { format == OfflineFormat.apple && apple != nil }

    /// The download's size as far as it is known before it is ready: the
    /// MP4's estimate, or the original's size.
    public var expectedBytes: Int64 { apple.map(\.estimatedSizeBytes) ?? source.sizeBytes }

    /// The file's container on this device ("mp4"): AVPlayer goes by a local file's extension.
    public var container: String {
        let name = (apple?.container ?? source.container).lowercased()
        return name.isEmpty ? "media" : name
    }

    enum CodingKeys: String, CodingKey {
        case grantId, batchKey, clientItemKey, expiresAt, item, source, mediaUrl, subtitles, format, apple
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
                  subtitles: c.value(.subtitles, []), format: c.value(.format, ""), apple: c.optional(.apple))
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
