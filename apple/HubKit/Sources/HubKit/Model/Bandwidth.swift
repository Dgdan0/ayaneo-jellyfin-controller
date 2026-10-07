import Foundation

/// qBittorrent's two sets of limits (`GET /v1/downloads/bandwidth`, #29):
/// the normal ones and the alternative ones Activity calls Quiet, which is in
/// use, and what this token may change. Bytes per second; 0 is no limit.
public struct BandwidthState: Decodable, Equatable, Sendable {
    /// "normal" or "alternative".
    public var mode: String
    public var downloadBps: Int64
    public var uploadBps: Int64
    public var alternativeDownloadBps: Int64
    public var alternativeUploadBps: Int64
    public var queueingEnabled: Bool
    public var schedulerEnabled: Bool
    /// qBittorrent 5 and later switch between the two sets on request.
    public var modeSwitchSupported: Bool
    /// The token has the `control` scope.
    public var canControl: Bool

    public init(mode: String = "normal", downloadBps: Int64 = 0, uploadBps: Int64 = 0, alternativeDownloadBps: Int64 = 0,
                alternativeUploadBps: Int64 = 0, queueingEnabled: Bool = false, schedulerEnabled: Bool = false,
                modeSwitchSupported: Bool = false, canControl: Bool = false) {
        self.mode = mode
        self.downloadBps = downloadBps
        self.uploadBps = uploadBps
        self.alternativeDownloadBps = alternativeDownloadBps
        self.alternativeUploadBps = alternativeUploadBps
        self.queueingEnabled = queueingEnabled
        self.schedulerEnabled = schedulerEnabled
        self.modeSwitchSupported = modeSwitchSupported
        self.canControl = canControl
    }

    enum CodingKeys: String, CodingKey {
        case mode, downloadBps, uploadBps, alternativeDownloadBps, alternativeUploadBps, queueingEnabled, schedulerEnabled
        case modeSwitchSupported, canControl
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(mode: c.value(.mode, "normal"), downloadBps: c.value(.downloadBps, 0), uploadBps: c.value(.uploadBps, 0),
                  alternativeDownloadBps: c.value(.alternativeDownloadBps, 0),
                  alternativeUploadBps: c.value(.alternativeUploadBps, 0), queueingEnabled: c.value(.queueingEnabled, false),
                  schedulerEnabled: c.value(.schedulerEnabled, false), modeSwitchSupported: c.value(.modeSwitchSupported, false),
                  canControl: c.value(.canControl, false))
    }

    /// Quiet, qBittorrent's alternative limits, is in use.
    public var quiet: Bool { mode == "alternative" }
}

/// A change to the limits (`POST /v1/downloads/bandwidth`): the set in use,
/// or one set's caps, or both. The hub refuses a field it does not know, so
/// only what is set is written.
public struct BandwidthChange: Encodable, Equatable, Sendable {
    /// "normal" or "alternative"; nil leaves the set in use alone.
    public var mode: String?
    /// Which set `downloadBps` and `uploadBps` are for: "normal" or "alternative".
    public var limitsFor: String?
    public var downloadBps: Int64?
    public var uploadBps: Int64?

    public init(mode: String? = nil, limitsFor: String? = nil, downloadBps: Int64? = nil, uploadBps: Int64? = nil) {
        self.mode = mode
        self.limitsFor = limitsFor
        self.downloadBps = downloadBps
        self.uploadBps = uploadBps
    }

    enum CodingKeys: String, CodingKey { case mode, limitsFor, downloadBps, uploadBps }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encodeIfPresent(mode, forKey: .mode)
        try c.encodeIfPresent(limitsFor, forKey: .limitsFor)
        try c.encodeIfPresent(downloadBps, forKey: .downloadBps)
        try c.encodeIfPresent(uploadBps, forKey: .uploadBps)
    }

    /// The JSON body.
    public func encoded() -> Data { (try? JSONEncoder().encode(self)) ?? Data("{}".utf8) }
}
