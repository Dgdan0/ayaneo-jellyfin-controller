import Foundation

// The download queue, joined by the hub across qBittorrent, Radarr and Sonarr
// (`GET /v1/activity`, #29): Android's `model/Activity`, field for field with
// the hub's Go types. Every field is defaulted, because an installed app
// long outlives the hub it was built against.

/// The *arr queue row behind a transfer.
public struct ArrRef: Decodable, Equatable, Sendable, Hashable {
    /// "sonarr" or "radarr".
    public var service: String
    public var queueId: Int
    public var movieId: Int
    public var seriesId: Int
    public var tmdbId: Int
    public var tvdbId: Int
    public var trackedDownloadState: String
    public var trackedDownloadStatus: String
    /// The *arr's own words for what is wrong, passed through verbatim.
    public var problem: String

    public init(service: String = "", queueId: Int = 0, movieId: Int = 0, seriesId: Int = 0, tmdbId: Int = 0, tvdbId: Int = 0,
                trackedDownloadState: String = "", trackedDownloadStatus: String = "", problem: String = "") {
        self.service = service
        self.queueId = queueId
        self.movieId = movieId
        self.seriesId = seriesId
        self.tmdbId = tmdbId
        self.tvdbId = tvdbId
        self.trackedDownloadState = trackedDownloadState
        self.trackedDownloadStatus = trackedDownloadStatus
        self.problem = problem
    }

    enum CodingKeys: String, CodingKey {
        case service, queueId, movieId, seriesId, tmdbId, tvdbId, trackedDownloadState, trackedDownloadStatus, problem
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(service: c.value(.service, ""), queueId: c.value(.queueId, 0), movieId: c.value(.movieId, 0),
                  seriesId: c.value(.seriesId, 0), tmdbId: c.value(.tmdbId, 0), tvdbId: c.value(.tvdbId, 0),
                  trackedDownloadState: c.value(.trackedDownloadState, ""),
                  trackedDownloadStatus: c.value(.trackedDownloadStatus, ""), problem: c.value(.problem, ""))
    }
}

/// The hub's reading of what a transfer is doing and, when it is stuck, why.
public struct ActivityDiagnosis: Decodable, Equatable, Sendable, Hashable {
    public var code: String
    public var title: String
    public var explanation: String
    public var evidence: [String]
    public var nextStep: String
    public var needsAttention: Bool
    /// The one harmless action the hub offers for it ("start"), or empty.
    public var action: String

    public init(code: String = "", title: String = "", explanation: String = "", evidence: [String] = [],
                nextStep: String = "", needsAttention: Bool = false, action: String = "") {
        self.code = code
        self.title = title
        self.explanation = explanation
        self.evidence = evidence
        self.nextStep = nextStep
        self.needsAttention = needsAttention
        self.action = action
    }

    enum CodingKeys: String, CodingKey { case code, title, explanation, evidence, nextStep, needsAttention, action }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(code: c.value(.code, ""), title: c.value(.title, ""), explanation: c.value(.explanation, ""),
                  evidence: c.value(.evidence, []), nextStep: c.value(.nextStep, ""),
                  needsAttention: c.value(.needsAttention, false), action: c.value(.action, ""))
    }
}

/// One client transfer, with the *arr row it belongs to when the hub joined one.
public struct ActivityItem: Decodable, Equatable, Sendable, Identifiable, Hashable {
    /// "qbit:<hash>", or the *arr's own id for a row with no client item.
    public var id: String
    /// The release name.
    public var title: String
    /// The *arr's title, when the transfer joined a queue row.
    public var mediaTitle: String
    /// One of `Stages`, or a stage this build has never heard of.
    public var stage: String
    /// 0…1.
    public var progress: Double
    public var sizeBytes: Int64
    public var remainingBytes: Int64
    public var speedBps: Int64
    public var uploadBps: Int64
    /// -1 when there is no meaningful estimate, rather than a fake number.
    public var etaSeconds: Int64
    public var seeds: Int
    public var peers: Int
    public var `protocol`: String
    public var client: String
    /// What the client alone says ("stopped" while an import is stuck): Stop or Start follows it.
    public var clientStage: String
    public var clientState: String
    public var priority: Int
    public var diagnosis: ActivityDiagnosis?
    public var category: String
    public var indexer: String
    public var arr: ArrRef?
    public var torrentHash: String
    public var matchConfidence: String
    public var warnings: [String]
    /// How many Sonarr episode rows this one client transfer stands for.
    public var queueItems: Int
    /// Computed by the hub from this token's scopes. Never inferred here.
    public var actions: [String]

    public init(id: String = "", title: String = "", mediaTitle: String = "", stage: String = "", progress: Double = 0,
                sizeBytes: Int64 = 0, remainingBytes: Int64 = 0, speedBps: Int64 = 0, uploadBps: Int64 = 0,
                etaSeconds: Int64 = -1, seeds: Int = 0, peers: Int = 0, protocol: String = "", client: String = "",
                clientStage: String = "", clientState: String = "", priority: Int = 0, diagnosis: ActivityDiagnosis? = nil,
                category: String = "", indexer: String = "", arr: ArrRef? = nil, torrentHash: String = "",
                matchConfidence: String = "none", warnings: [String] = [], queueItems: Int = 0, actions: [String] = []) {
        self.id = id
        self.title = title
        self.mediaTitle = mediaTitle
        self.stage = stage
        self.progress = progress
        self.sizeBytes = sizeBytes
        self.remainingBytes = remainingBytes
        self.speedBps = speedBps
        self.uploadBps = uploadBps
        self.etaSeconds = etaSeconds
        self.seeds = seeds
        self.peers = peers
        self.`protocol` = `protocol`
        self.client = client
        self.clientStage = clientStage
        self.clientState = clientState
        self.priority = priority
        self.diagnosis = diagnosis
        self.category = category
        self.indexer = indexer
        self.arr = arr
        self.torrentHash = torrentHash
        self.matchConfidence = matchConfidence
        self.warnings = warnings
        self.queueItems = queueItems
        self.actions = actions
    }

    enum CodingKeys: String, CodingKey {
        case id, title, mediaTitle, stage, progress, sizeBytes, remainingBytes, speedBps, uploadBps, etaSeconds, seeds, peers
        case `protocol`, client, clientStage, clientState, priority, diagnosis, category, indexer, arr, torrentHash
        case matchConfidence, warnings, queueItems, actions
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), title: c.value(.title, ""), mediaTitle: c.value(.mediaTitle, ""),
                  stage: c.value(.stage, ""), progress: c.value(.progress, 0), sizeBytes: c.value(.sizeBytes, 0),
                  remainingBytes: c.value(.remainingBytes, 0), speedBps: c.value(.speedBps, 0),
                  uploadBps: c.value(.uploadBps, 0), etaSeconds: c.value(.etaSeconds, -1), seeds: c.value(.seeds, 0),
                  peers: c.value(.peers, 0), protocol: c.value(.`protocol`, ""), client: c.value(.client, ""),
                  clientStage: c.value(.clientStage, ""), clientState: c.value(.clientState, ""),
                  priority: c.value(.priority, 0), diagnosis: c.optional(.diagnosis), category: c.value(.category, ""),
                  indexer: c.value(.indexer, ""), arr: c.optional(.arr), torrentHash: c.value(.torrentHash, ""),
                  matchConfidence: c.value(.matchConfidence, "none"), warnings: c.value(.warnings, []),
                  queueItems: c.value(.queueItems, 0), actions: c.value(.actions, []))
    }

    /// The heading: the *arr's title, else the release name as words.
    public var headline: String { mediaTitle.isEmpty ? ReleaseNames.readable(title) : mediaTitle }

    /// The line under it: the release name, empty when it would repeat the heading.
    public var subline: String { mediaTitle.isEmpty ? "" : title }

    public func can(_ action: String) -> Bool { actions.contains(action) }

    /// The hub's diagnosis decides; an older hub's warnings or stage stand in.
    public var isBroken: Bool { diagnosis?.needsAttention ?? (stage == Stages.stuck || !warnings.isEmpty) }

    /// Moving: drives how often the list is asked for. Importing counts, since an
    /// import finishes in seconds and is exactly the change worth catching.
    public var isActive: Bool { stage == Stages.downloading || stage == Stages.importing }
}

/// The hub's stage words. Strings rather than an enum: a stage a newer hub
/// sends has to survive being unknown here.
public enum Stages {
    public static let downloading = "downloading"
    public static let queued = "queued"
    public static let seeding = "seeding"
    public static let importing = "importing"
    public static let stopped = "stopped"
    public static let stuck = "stuck"
    public static let done = "done"

    /// A stage in words; one this build has never heard of is shown as it came.
    public static func label(_ stage: String) -> String {
        switch stage {
        case downloading: "Downloading"
        case queued: "Queued"
        case seeding: "Seeding"
        case importing: "Importing"
        case stopped: "Stopped"
        case stuck: "Stuck"
        case done: "Done"
        default: stage.isEmpty ? "Unknown" : stage
        }
    }
}

public struct ActivitySummary: Decodable, Equatable, Sendable {
    public var downloading: Int
    public var queued: Int
    public var seeding: Int
    public var stuck: Int
    public var downSpeedBytes: Int64
    public var upSpeedBytes: Int64

    public init(downloading: Int = 0, queued: Int = 0, seeding: Int = 0, stuck: Int = 0, downSpeedBytes: Int64 = 0,
                upSpeedBytes: Int64 = 0) {
        self.downloading = downloading
        self.queued = queued
        self.seeding = seeding
        self.stuck = stuck
        self.downSpeedBytes = downSpeedBytes
        self.upSpeedBytes = upSpeedBytes
    }

    enum CodingKeys: String, CodingKey { case downloading, queued, seeding, stuck, downSpeedBytes, upSpeedBytes }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(downloading: c.value(.downloading, 0), queued: c.value(.queued, 0), seeding: c.value(.seeding, 0),
                  stuck: c.value(.stuck, 0), downSpeedBytes: c.value(.downSpeedBytes, 0), upSpeedBytes: c.value(.upSpeedBytes, 0))
    }
}

/// `GET /v1/activity` (`?all=true` keeps finished transfers). A partial answer
/// is the normal shape: the list is useful with any one service answering.
public struct ActivityResponse: Decodable, Equatable, Sendable {
    public var generatedAt: String
    public var summary: ActivitySummary
    public var items: [ActivityItem]
    public var partial: [Partial]

    public init(generatedAt: String = "", summary: ActivitySummary = ActivitySummary(), items: [ActivityItem] = [],
                partial: [Partial] = []) {
        self.generatedAt = generatedAt
        self.summary = summary
        self.items = items
        self.partial = partial
    }

    enum CodingKeys: String, CodingKey { case generatedAt, summary, items, partial }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(generatedAt: c.value(.generatedAt, ""), summary: c.value(.summary, ActivitySummary()),
                  items: c.value(.items, []), partial: c.value(.partial, []))
    }

    public var anyActive: Bool { items.contains(where: \.isActive) }
}

/// What a transfer action answers. Only `ok` is load-bearing.
public struct ActionAck: Decodable, Equatable, Sendable {
    public var ok: Bool
    public var action: String
    public var deletedFiles: Bool
    public var blocklist: Bool
    public var search: Bool
    public var warning: String

    public init(ok: Bool = false, action: String = "", deletedFiles: Bool = false, blocklist: Bool = false,
                search: Bool = false, warning: String = "") {
        self.ok = ok
        self.action = action
        self.deletedFiles = deletedFiles
        self.blocklist = blocklist
        self.search = search
        self.warning = warning
    }

    enum CodingKeys: String, CodingKey { case ok, action, deletedFiles, blocklist, search, warning }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(ok: c.value(.ok, false), action: c.value(.action, ""), deletedFiles: c.value(.deletedFiles, false),
                  blocklist: c.value(.blocklist, false), search: c.value(.search, false), warning: c.value(.warning, ""))
    }
}
