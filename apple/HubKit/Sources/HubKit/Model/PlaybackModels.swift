import Foundation

// The playback endpoints' bodies and answers, field for field with the hub's
// Go types (`hub/internal/api/playback.go`, `playback_cast.go`) and Android's
// `model/Playback.kt`. Every answer field is defaulted, as everywhere in
// HubKit, so a hub that adds or loosens a field breaks neither client.

/// Where a title starts. "resume" is the hub's to judge: it starts at the saved
/// position, or at 0:00 when there is none worth keeping; "restart" is 0:00.
public enum PlaybackStartMode: String, Sendable, Codable {
    case resume, restart
}

/// The movie or episode a session plays, and its neighbours.
public struct PlaybackItem: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    /// "movie" or "episode".
    public var type: String
    public var title: String
    public var seriesTitle: String
    public var seriesId: String
    public var seasonId: String
    public var seasonNumber: Int
    public var episodeNumber: Int

    public init(id: String = "", type: String = "", title: String = "", seriesTitle: String = "",
                seriesId: String = "", seasonId: String = "", seasonNumber: Int = 0, episodeNumber: Int = 0) {
        self.id = id
        self.type = type
        self.title = title
        self.seriesTitle = seriesTitle
        self.seriesId = seriesId
        self.seasonId = seasonId
        self.seasonNumber = seasonNumber
        self.episodeNumber = episodeNumber
    }

    /// "Bleach · S1E5 · Beat the Invisible Enemy!", Android's `displayTitle`.
    public var displayTitle: String {
        if !seriesTitle.isEmpty && seasonNumber > 0 && episodeNumber > 0 {
            return "\(seriesTitle) · S\(seasonNumber)E\(episodeNumber) · \(title)"
        }
        return seriesTitle.isEmpty ? title : "\(seriesTitle) · \(title)"
    }

    enum CodingKeys: String, CodingKey {
        case id, type, title, seriesTitle, seriesId, seasonId, seasonNumber, episodeNumber
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), type: c.value(.type, ""), title: c.value(.title, ""),
                  seriesTitle: c.value(.seriesTitle, ""), seriesId: c.value(.seriesId, ""),
                  seasonId: c.value(.seasonId, ""), seasonNumber: c.value(.seasonNumber, 0),
                  episodeNumber: c.value(.episodeNumber, 0))
    }
}

/// One media version of the item (a 4K and a 1080p file, say).
public struct PlaybackSource: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var container: String
    public var sizeBytes: Int64
    public var bitrate: Int

    public init(id: String = "", name: String = "", container: String = "", sizeBytes: Int64 = 0, bitrate: Int = 0) {
        self.id = id
        self.name = name
        self.container = container
        self.sizeBytes = sizeBytes
        self.bitrate = bitrate
    }

    enum CodingKeys: String, CodingKey { case id, name, container, sizeBytes, bitrate }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), name: c.value(.name, ""), container: c.value(.container, ""),
                  sizeBytes: c.value(.sizeBytes, 0), bitrate: c.value(.bitrate, 0))
    }
}

/// An audio or subtitle stream. A text subtitle comes as its own file
/// (`externalUrl`), which the app draws itself.
public struct PlaybackTrack: Decodable, Equatable, Sendable {
    /// Jellyfin's stream index; -1 when the hub sent none.
    public var index: Int
    public var type: String
    public var label: String
    public var language: String
    public var codec: String
    public var channels: Int
    public var channelLayout: String
    public var isDefault: Bool
    public var forced: Bool
    public var hearingImpaired: Bool
    public var external: Bool
    public var externalUrl: String
    /// A download's track: which of the MP4's own subtitle options it is
    /// (#45); nil for one the app draws, or a stream's. Never from the hub.
    public var fileOption: Int?

    public init(index: Int = -1, type: String = "", label: String = "", language: String = "", codec: String = "",
                channels: Int = 0, channelLayout: String = "", isDefault: Bool = false, forced: Bool = false,
                hearingImpaired: Bool = false, external: Bool = false, externalUrl: String = "", fileOption: Int? = nil) {
        self.index = index
        self.type = type
        self.label = label
        self.language = language
        self.codec = codec
        self.channels = channels
        self.channelLayout = channelLayout
        self.isDefault = isDefault
        self.forced = forced
        self.hearingImpaired = hearingImpaired
        self.external = external
        self.externalUrl = externalUrl
        self.fileOption = fileOption
    }

    enum CodingKeys: String, CodingKey {
        case index, type, label, language, codec, channels, channelLayout
        case isDefault = "default"
        case forced, hearingImpaired, external, externalUrl
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(index: c.value(.index, -1), type: c.value(.type, ""), label: c.value(.label, ""),
                  language: c.value(.language, ""), codec: c.value(.codec, ""), channels: c.value(.channels, 0),
                  channelLayout: c.value(.channelLayout, ""), isDefault: c.value(.isDefault, false),
                  forced: c.value(.forced, false), hearingImpaired: c.value(.hearingImpaired, false),
                  external: c.value(.external, false), externalUrl: c.value(.externalUrl, ""))
    }
}

public struct PlaybackChapter: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var positionMillis: Int64

    public init(id: String = "", name: String = "", positionMillis: Int64 = 0) {
        self.id = id
        self.name = name
        self.positionMillis = positionMillis
    }

    enum CodingKeys: String, CodingKey { case id, name, positionMillis }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), name: c.value(.name, ""), positionMillis: c.value(.positionMillis, 0))
    }
}

/// An intro, recap, credits or other stretch Jellyfin (or the hub, from whole
/// chapter names) knows about.
public struct PlaybackSegment: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    /// "Intro", "Outro" (the credits), "Recap", "Preview", "Commercial".
    public var type: String
    public var startMillis: Int64
    public var endMillis: Int64

    public init(id: String = "", type: String = "", startMillis: Int64 = 0, endMillis: Int64 = 0) {
        self.id = id
        self.type = type
        self.startMillis = startMillis
        self.endMillis = endMillis
    }

    enum CodingKeys: String, CodingKey { case id, type, startMillis, endMillis }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), type: c.value(.type, ""), startMillis: c.value(.startMillis, 0),
                  endMillis: c.value(.endMillis, 0))
    }
}

public struct PlaybackTrickplay: Decodable, Equatable, Sendable {
    public var tileUrl: String
    public var width: Int
    public var height: Int
    public var tileWidth: Int
    public var tileHeight: Int
    public var thumbnailCount: Int
    public var intervalMillis: Int64

    public init(tileUrl: String = "", width: Int = 0, height: Int = 0, tileWidth: Int = 0, tileHeight: Int = 0,
                thumbnailCount: Int = 0, intervalMillis: Int64 = 0) {
        self.tileUrl = tileUrl
        self.width = width
        self.height = height
        self.tileWidth = tileWidth
        self.tileHeight = tileHeight
        self.thumbnailCount = thumbnailCount
        self.intervalMillis = intervalMillis
    }

    enum CodingKeys: String, CodingKey { case tileUrl, width, height, tileWidth, tileHeight, thumbnailCount, intervalMillis }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(tileUrl: c.value(.tileUrl, ""), width: c.value(.width, 0), height: c.value(.height, 0),
                  tileWidth: c.value(.tileWidth, 0), tileHeight: c.value(.tileHeight, 0),
                  thumbnailCount: c.value(.thumbnailCount, 0), intervalMillis: c.value(.intervalMillis, 0))
    }
}

/// `POST /v1/playback/items/{itemId}/prepare` (and `…/select`): the session,
/// what will play and how, and everything the player shows about it.
public struct PlaybackPrepareResponse: Decodable, Equatable, Sendable {
    public var sessionId: String
    public var item: PlaybackItem
    /// Where playback starts, already judged by the hub.
    public var positionMillis: Int64
    public var durationMillis: Int64
    /// Hub-relative, and needs the bearer token; AVPlayer is given a grant's
    /// address instead (`PlaybackGrant`).
    public var mediaUrl: String
    public var mimeType: String
    /// "DirectPlay", "DirectStream" or "Transcode".
    public var playMethod: String
    public var transcodeReason: String
    public var bitrate: Int
    public var width: Int
    public var height: Int
    public var frameRate: Double
    public var hdr: String
    public var videoCodec: String
    public var audioCodec: String
    public var sources: [PlaybackSource]
    public var audioTracks: [PlaybackTrack]
    public var subtitleTracks: [PlaybackTrack]
    public var selectedMediaSourceId: String
    public var selectedAudioIndex: Int?
    public var selectedSubtitleIndex: Int?
    public var previousItem: PlaybackItem?
    public var nextItem: PlaybackItem?
    public var trickplay: PlaybackTrickplay?
    public var previewUrl: String
    public var chapters: [PlaybackChapter]
    public var segments: [PlaybackSegment]

    public init(sessionId: String = "", item: PlaybackItem = PlaybackItem(), positionMillis: Int64 = 0,
                durationMillis: Int64 = 0, mediaUrl: String = "", mimeType: String = "video/*", playMethod: String = "",
                transcodeReason: String = "", bitrate: Int = 0, width: Int = 0, height: Int = 0, frameRate: Double = 0,
                hdr: String = "", videoCodec: String = "", audioCodec: String = "", sources: [PlaybackSource] = [],
                audioTracks: [PlaybackTrack] = [], subtitleTracks: [PlaybackTrack] = [], selectedMediaSourceId: String = "",
                selectedAudioIndex: Int? = nil, selectedSubtitleIndex: Int? = nil, previousItem: PlaybackItem? = nil,
                nextItem: PlaybackItem? = nil, trickplay: PlaybackTrickplay? = nil, previewUrl: String = "",
                chapters: [PlaybackChapter] = [], segments: [PlaybackSegment] = []) {
        self.sessionId = sessionId
        self.item = item
        self.positionMillis = positionMillis
        self.durationMillis = durationMillis
        self.mediaUrl = mediaUrl
        self.mimeType = mimeType
        self.playMethod = playMethod
        self.transcodeReason = transcodeReason
        self.bitrate = bitrate
        self.width = width
        self.height = height
        self.frameRate = frameRate
        self.hdr = hdr
        self.videoCodec = videoCodec
        self.audioCodec = audioCodec
        self.sources = sources
        self.audioTracks = audioTracks
        self.subtitleTracks = subtitleTracks
        self.selectedMediaSourceId = selectedMediaSourceId
        self.selectedAudioIndex = selectedAudioIndex
        self.selectedSubtitleIndex = selectedSubtitleIndex
        self.previousItem = previousItem
        self.nextItem = nextItem
        self.trickplay = trickplay
        self.previewUrl = previewUrl
        self.chapters = chapters
        self.segments = segments
    }

    enum CodingKeys: String, CodingKey {
        case sessionId, item, positionMillis, durationMillis, mediaUrl, mimeType, playMethod, transcodeReason
        case bitrate, width, height, frameRate, hdr, videoCodec, audioCodec, sources, audioTracks, subtitleTracks
        case selectedMediaSourceId, selectedAudioIndex, selectedSubtitleIndex, previousItem, nextItem, trickplay
        case previewUrl, chapters, segments
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            sessionId: c.value(.sessionId, ""), item: c.value(.item, PlaybackItem()),
            positionMillis: c.value(.positionMillis, 0), durationMillis: c.value(.durationMillis, 0),
            mediaUrl: c.value(.mediaUrl, ""), mimeType: c.value(.mimeType, "video/*"),
            playMethod: c.value(.playMethod, ""), transcodeReason: c.value(.transcodeReason, ""),
            bitrate: c.value(.bitrate, 0), width: c.value(.width, 0), height: c.value(.height, 0),
            frameRate: c.value(.frameRate, 0), hdr: c.value(.hdr, ""), videoCodec: c.value(.videoCodec, ""),
            audioCodec: c.value(.audioCodec, ""), sources: c.value(.sources, []), audioTracks: c.value(.audioTracks, []),
            subtitleTracks: c.value(.subtitleTracks, []), selectedMediaSourceId: c.value(.selectedMediaSourceId, ""),
            selectedAudioIndex: c.optional(.selectedAudioIndex), selectedSubtitleIndex: c.optional(.selectedSubtitleIndex),
            previousItem: c.optional(.previousItem), nextItem: c.optional(.nextItem), trickplay: c.optional(.trickplay),
            previewUrl: c.value(.previewUrl, ""), chapters: c.value(.chapters, []), segments: c.value(.segments, []))
    }
}

/// `POST /v1/playback/sessions/{id}/cast-grant`: addresses for the session's
/// stream that carry no credential, valid until the session closes.
///
/// AVPlayer fetches its own media (and every HLS segment) and has no supported
/// way to add the bearer token to those requests. A request without one
/// counts towards the hub's ban on the device's address, so the player is
/// given these, the ones a TV is given, instead. AirPlay needs the same.
public struct PlaybackGrant: Decodable, Equatable, Sendable {
    /// Hub-relative ("/v1/cast/{grant}/hls/…"), or a whole address.
    public var mediaUrl: String
    public var mimeType: String
    public var subtitleUrls: [String: String]
    public var expiresAt: String

    public init(mediaUrl: String = "", mimeType: String = "", subtitleUrls: [String: String] = [:], expiresAt: String = "") {
        self.mediaUrl = mediaUrl
        self.mimeType = mimeType
        self.subtitleUrls = subtitleUrls
        self.expiresAt = expiresAt
    }

    enum CodingKeys: String, CodingKey { case mediaUrl, mimeType, subtitleUrls, expiresAt }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(mediaUrl: c.value(.mediaUrl, ""), mimeType: c.value(.mimeType, ""),
                  subtitleUrls: c.value(.subtitleUrls, [:]), expiresAt: c.value(.expiresAt, ""))
    }

    /// The address the player opens: a hub path joined to the hub's address,
    /// a whole address as it is.
    public func address(base: String) -> String {
        let lower = mediaUrl.lowercased()
        if lower.hasPrefix("https://") || lower.hasPrefix("http://") { return mediaUrl }
        return HubEndpoints.join(base, mediaUrl)
    }
}

/// Who is playing, for Jellyfin's dashboard and its transcode bookkeeping.
public struct PlaybackDevice: Encodable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var version: String

    public init(id: String, name: String, version: String) {
        self.id = id
        self.name = name
        self.version = version
    }
}

/// What the player can open. `containers` and `hlsSegments` are the two
/// fields AVPlayer needs (#2); a client that sends neither gets Media3's.
public struct PlaybackCapabilities: Encodable, Equatable, Sendable {
    public var width: Int
    public var height: Int
    public var maxAudioChannels: Int
    public var videoCodecs: [String]
    public var audioCodecs: [String]
    public var hdrTypes: [String]
    public var containers: [String]
    public var hlsSegments: String

    public init(width: Int, height: Int, maxAudioChannels: Int, videoCodecs: [String], audioCodecs: [String],
                hdrTypes: [String] = [], containers: [String] = [], hlsSegments: String = "") {
        self.width = width
        self.height = height
        self.maxAudioChannels = maxAudioChannels
        self.videoCodecs = videoCodecs
        self.audioCodecs = audioCodecs
        self.hdrTypes = hdrTypes
        self.containers = containers
        self.hlsSegments = hlsSegments
    }
}

public struct PlaybackPrepareBody: Encodable, Equatable, Sendable {
    public var startMode: PlaybackStartMode
    public var positionMillis: Int64
    public var mediaSourceId: String?
    public var audioStreamIndex: Int?
    public var subtitleStreamIndex: Int?
    public var maxBitrate: Int
    public var forceTranscode: Bool
    public var device: PlaybackDevice
    public var capabilities: PlaybackCapabilities

    public init(startMode: PlaybackStartMode, positionMillis: Int64 = 0, mediaSourceId: String? = nil,
                audioStreamIndex: Int? = nil, subtitleStreamIndex: Int? = nil, maxBitrate: Int = 0,
                forceTranscode: Bool = false, device: PlaybackDevice, capabilities: PlaybackCapabilities) {
        self.startMode = startMode
        self.positionMillis = positionMillis
        self.mediaSourceId = mediaSourceId
        self.audioStreamIndex = audioStreamIndex
        self.subtitleStreamIndex = subtitleStreamIndex
        self.maxBitrate = maxBitrate
        self.forceTranscode = forceTranscode
        self.device = device
        self.capabilities = capabilities
    }

    enum CodingKeys: String, CodingKey {
        case startMode, positionMillis, mediaSourceId, audioStreamIndex, subtitleStreamIndex, maxBitrate
        case forceTranscode, device, capabilities
    }

    /// The defaults stay out of the body, as the hub's own `omitempty` writes it.
    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(startMode, forKey: .startMode)
        if positionMillis > 0 { try c.encode(positionMillis, forKey: .positionMillis) }
        try c.encodeIfPresent(mediaSourceId, forKey: .mediaSourceId)
        try c.encodeIfPresent(audioStreamIndex, forKey: .audioStreamIndex)
        try c.encodeIfPresent(subtitleStreamIndex, forKey: .subtitleStreamIndex)
        if maxBitrate > 0 { try c.encode(maxBitrate, forKey: .maxBitrate) }
        if forceTranscode { try c.encode(true, forKey: .forceTranscode) }
        try c.encode(device, forKey: .device)
        try c.encode(capabilities, forKey: .capabilities)
    }
}

/// `POST /v1/playback/sessions/{id}/select`: another version, track or
/// quality for the same session, from `positionMillis`. Only what changes is sent.
public struct PlaybackSelectBody: Encodable, Equatable, Sendable {
    public var positionMillis: Int64
    public var mediaSourceId: String?
    public var audioStreamIndex: Int?
    public var subtitleStreamIndex: Int?
    public var maxBitrate: Int?
    public var forceTranscode: Bool?

    public init(positionMillis: Int64, mediaSourceId: String? = nil, audioStreamIndex: Int? = nil,
                subtitleStreamIndex: Int? = nil, maxBitrate: Int? = nil, forceTranscode: Bool? = nil) {
        self.positionMillis = positionMillis
        self.mediaSourceId = mediaSourceId
        self.audioStreamIndex = audioStreamIndex
        self.subtitleStreamIndex = subtitleStreamIndex
        self.maxBitrate = maxBitrate
        self.forceTranscode = forceTranscode
    }
}

/// `POST /v1/playback/sessions/{id}/events`. The hub ignores a sequence it has
/// already seen, so a retried event cannot be applied twice.
public struct PlaybackEventBody: Encodable, Equatable, Sendable {
    /// "started", "progress", "paused", "unpaused", "seek" or "stopped".
    public var type: String
    public var sequence: Int64
    public var positionMillis: Int64
    public var paused: Bool
    public var muted: Bool
    public var volume: Int

    public init(type: String, sequence: Int64, positionMillis: Int64, paused: Bool = false, muted: Bool = false,
                volume: Int = 100) {
        self.type = type
        self.sequence = sequence
        self.positionMillis = positionMillis
        self.paused = paused
        self.muted = muted
        self.volume = volume
    }
}
