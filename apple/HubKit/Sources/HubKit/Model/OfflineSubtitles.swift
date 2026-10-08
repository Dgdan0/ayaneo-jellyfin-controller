import Foundation

// An Apple download's subtitles kept beside its MP4 (#45): the hub's list of
// the grant's text subtitles as they are now (`GET …/subtitle-tracks`), each
// one fetched as WebVTT, and what this device keeps of them.

/// `GET /v1/offline/grants/{grantId}/subtitle-tracks`.
public struct OfflineSubtitleList: Decodable, Equatable, Sendable {
    public var grantId: String
    public var format: String
    /// The grant's text subtitles as they are now: the whole truth at that moment.
    public var tracks: [OfflineSubtitleTrack]
    /// What is not offered as WebVTT; an `unreadable` one keeps its key.
    public var omitted: [OfflineSubtitleOmitted]

    public init(grantId: String = "", format: String = "apple", tracks: [OfflineSubtitleTrack] = [],
                omitted: [OfflineSubtitleOmitted] = []) {
        self.grantId = grantId
        self.format = format
        self.tracks = tracks
        self.omitted = omitted
    }

    enum CodingKeys: String, CodingKey { case grantId, format, tracks, omitted }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(grantId: c.value(.grantId, ""), format: c.value(.format, ""), tracks: c.value(.tracks, []),
                  omitted: c.value(.omitted, []))
    }
}

/// One text subtitle of the grant's source, fetchable as WebVTT.
public struct OfflineSubtitleTrack: Decodable, Equatable, Sendable {
    /// What the file is kept under: `emb-<index inside the file>` or
    /// `ext-<language>[-forced][-sdh][-n]`. Never `sourceIndex`, which moves.
    public var key: String
    /// Jellyfin's index now; it moves when a sidecar is added or removed.
    public var sourceIndex: Int
    /// ISO 639-2/T ("eng", "heb"; "und" when unknown).
    public var language: String
    public var title: String
    public var label: String
    public var codec: String
    public var external: Bool
    public var isDefault: Bool
    public var forced: Bool
    public var hearingImpaired: Bool
    /// Written right to left (Hebrew, Arabic…).
    public var rtl: Bool
    /// Compared for equality: a different one means fetch it again.
    public var signature: String
    /// The WebVTT's route, relative to the hub.
    public var url: String
    /// Where the same track sits among the MP4's subtitle options; nil for one that came later.
    public var mp4Index: Int?

    public init(key: String, sourceIndex: Int = 0, language: String = "und", title: String = "", label: String = "",
                codec: String = "subrip", external: Bool = true, isDefault: Bool = false, forced: Bool = false,
                hearingImpaired: Bool = false, rtl: Bool = false, signature: String = "", url: String = "",
                mp4Index: Int? = nil) {
        self.key = key
        self.sourceIndex = sourceIndex
        self.language = language
        self.title = title
        self.label = label
        self.codec = codec
        self.external = external
        self.isDefault = isDefault
        self.forced = forced
        self.hearingImpaired = hearingImpaired
        self.rtl = rtl
        self.signature = signature
        self.url = url
        self.mp4Index = mp4Index
    }

    enum CodingKeys: String, CodingKey {
        case key, sourceIndex, language, title, label, codec, external
        case isDefault = "default"
        case forced, hearingImpaired, rtl, signature, url, mp4Index
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(key: c.value(.key, ""), sourceIndex: c.value(.sourceIndex, 0), language: c.value(.language, "und"),
                  title: c.value(.title, ""), label: c.value(.label, ""), codec: c.value(.codec, ""),
                  external: c.value(.external, false), isDefault: c.value(.isDefault, false), forced: c.value(.forced, false),
                  hearingImpaired: c.value(.hearingImpaired, false), rtl: c.value(.rtl, false),
                  signature: c.value(.signature, ""), url: c.value(.url, ""), mp4Index: c.optional(.mp4Index))
    }
}

/// A subtitle the hub does not offer as WebVTT, and why: `picture_subtitle`,
/// `unsupported_format`, `unreadable` (for now: it keeps its key) or `too_many`.
public struct OfflineSubtitleOmitted: Decodable, Equatable, Sendable {
    public var key: String
    public var sourceIndex: Int
    public var language: String
    public var label: String
    public var reason: String

    public init(key: String = "", sourceIndex: Int = 0, language: String = "", label: String = "", reason: String = "") {
        self.key = key
        self.sourceIndex = sourceIndex
        self.language = language
        self.label = label
        self.reason = reason
    }

    enum CodingKeys: String, CodingKey { case key, sourceIndex, language, label, reason }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(key: c.value(.key, ""), sourceIndex: c.value(.sourceIndex, 0), language: c.value(.language, ""),
                  label: c.value(.label, ""), reason: c.value(.reason, ""))
    }
}

/// One subtitle this device keeps beside a download, as WebVTT.
public struct OfflineKeptSubtitle: Codable, Equatable, Sendable {
    public var key: String
    public var language: String
    public var title: String
    public var label: String
    public var isDefault: Bool
    public var forced: Bool
    public var hearingImpaired: Bool
    public var rtl: Bool
    public var mp4Index: Int?
    /// The ETag the file came with, as received: its signature.
    public var etag: String

    public init(key: String, language: String = "und", title: String = "", label: String = "", isDefault: Bool = false,
                forced: Bool = false, hearingImpaired: Bool = false, rtl: Bool = false, mp4Index: Int? = nil,
                etag: String = "") {
        self.key = key
        self.language = language
        self.title = title
        self.label = label
        self.isDefault = isDefault
        self.forced = forced
        self.hearingImpaired = hearingImpaired
        self.rtl = rtl
        self.mp4Index = mp4Index
        self.etag = etag
    }

    /// The listed track's facts with the ETag kept.
    public init(_ track: OfflineSubtitleTrack, etag: String) {
        self.init(key: track.key, language: track.language, title: track.title, label: track.label,
                  isDefault: track.isDefault, forced: track.forced, hearingImpaired: track.hearingImpaired,
                  rtl: track.rtl, mp4Index: track.mp4Index, etag: etag)
    }
}

/// What a download keeps of its subtitles, and when the hub was last asked.
public struct OfflineKeptSubtitles: Codable, Equatable, Sendable {
    public var tracks: [OfflineKeptSubtitle]
    /// Unix milliseconds of the last answer that settled anything (a list, or
    /// a hub that cannot refresh this one); 0 never.
    public var checkedAt: Int64

    public init(tracks: [OfflineKeptSubtitle] = [], checkedAt: Int64 = 0) {
        self.tracks = tracks
        self.checkedAt = checkedAt
    }
}
