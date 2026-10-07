import Foundation

// Keeping the library in order from the app (#34): subtitles through Bazarr
// and deleting from the server, field for field with the hub
// (`hub/internal/api/subtitles.go`, `media_removal.go`) and Android's
// `model/Subtitles.kt` and `model/MediaRemoval.kt`. Every field is defaulted,
// as on Android.

/// One subtitle track of a film or episode: an installed one, or one Bazarr
/// once downloaded.
public struct SubtitleRecord: Decodable, Equatable, Sendable, Identifiable {
    public var id: String
    /// "English", "Hebrew".
    public var language: String
    /// "en", "he".
    public var code: String
    public var provider: String
    /// "92%"; empty for an embedded track.
    public var score: String
    public var date: String
    public var description: String
    public var installed: Bool
    public var embedded: Bool
    public var forced: Bool
    public var hi: Bool

    public init(id: String, language: String = "", code: String = "", provider: String = "", score: String = "", date: String = "",
                description: String = "", installed: Bool = false, embedded: Bool = false, forced: Bool = false, hi: Bool = false) {
        self.id = id
        self.language = language
        self.code = code
        self.provider = provider
        self.score = score
        self.date = date
        self.description = description
        self.installed = installed
        self.embedded = embedded
        self.forced = forced
        self.hi = hi
    }

    enum CodingKeys: String, CodingKey {
        case id, language, code, provider, score, date, description, installed, embedded, forced, hi
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: c.value(.id, ""), language: c.value(.language, ""), code: c.value(.code, ""),
                  provider: c.value(.provider, ""), score: c.value(.score, ""), date: c.value(.date, ""),
                  description: c.value(.description, ""), installed: c.value(.installed, false),
                  embedded: c.value(.embedded, false), forced: c.value(.forced, false), hi: c.value(.hi, false))
    }
}

/// `GET /v1/library/items/{id}/subtitles`.
public struct SubtitleState: Decodable, Equatable, Sendable {
    public var records: [SubtitleRecord]
    /// A token with the `control` scope may search and download; a read-only one may only look.
    public var canDownload: Bool
    public var warning: String

    public init(records: [SubtitleRecord] = [], canDownload: Bool = false, warning: String = "") {
        self.records = records
        self.canDownload = canDownload
        self.warning = warning
    }

    enum CodingKeys: String, CodingKey { case records, canDownload, warning }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(records: c.value(.records, []), canDownload: c.value(.canDownload, false), warning: c.value(.warning, ""))
    }
}

/// One subtitle Bazarr found, with the ticket that downloads it (good for
/// fifteen minutes, from this search, for this title).
public struct SubtitleCandidate: Decodable, Equatable, Sendable, Identifiable {
    public var ticket: String
    public var language: String
    public var provider: String
    /// 0 to 100.
    public var score: Double
    public var release: String
    public var matches: [String]
    public var mismatches: [String]
    public var forced: Bool
    public var hi: Bool

    public var id: String { ticket }

    public init(ticket: String, language: String = "", provider: String = "", score: Double = 0, release: String = "",
                matches: [String] = [], mismatches: [String] = [], forced: Bool = false, hi: Bool = false) {
        self.ticket = ticket
        self.language = language
        self.provider = provider
        self.score = score
        self.release = release
        self.matches = matches
        self.mismatches = mismatches
        self.forced = forced
        self.hi = hi
    }

    enum CodingKeys: String, CodingKey { case ticket, language, provider, score, release, matches, mismatches, forced, hi }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(ticket: c.value(.ticket, ""), language: c.value(.language, ""), provider: c.value(.provider, ""),
                  score: c.value(.score, 0), release: c.value(.release, ""), matches: c.value(.matches, []),
                  mismatches: c.value(.mismatches, []), forced: c.value(.forced, false), hi: c.value(.hi, false))
    }
}

/// `POST …/subtitles/search`.
public struct SubtitleSearch: Decodable, Equatable, Sendable {
    public var candidates: [SubtitleCandidate]

    public init(candidates: [SubtitleCandidate] = []) { self.candidates = candidates }

    enum CodingKeys: String, CodingKey { case candidates }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(candidates: c.value(.candidates, []))
    }
}

/// `POST …/subtitles/download`'s body.
public struct SubtitleDownloadBody: Encodable, Equatable, Sendable {
    public var ticket: String

    public init(ticket: String) { self.ticket = ticket }
}

/// What a download answers: it is on Bazarr's side at once, and Jellyfin is
/// asked to look again.
public struct SubtitleDownloadAck: Decodable, Equatable, Sendable {
    public var ok: Bool
    public var warning: String
    public var jellyfinRefreshStarted: Bool

    public init(ok: Bool = false, warning: String = "", jellyfinRefreshStarted: Bool = false) {
        self.ok = ok
        self.warning = warning
        self.jellyfinRefreshStarted = jellyfinRefreshStarted
    }

    enum CodingKeys: String, CodingKey { case ok, warning, jellyfinRefreshStarted }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(ok: c.value(.ok, false), warning: c.value(.warning, ""),
                  jellyfinRefreshStarted: c.value(.jellyfinRefreshStarted, false))
    }
}

// MARK: Deleting from the server

/// What a deletion would take, before anything is deleted: the hub's
/// read-only preview, and the one-use ticket that confirms it.
public struct RemovalPreview: Decodable, Equatable, Sendable {
    public var ticket: String
    public var title: String
    /// The hub's own plain sentence about what goes.
    public var description: String
    public var files: [String]
    public var fileCount: Int

    public init(ticket: String = "", title: String = "", description: String = "", files: [String] = [], fileCount: Int = 0) {
        self.ticket = ticket
        self.title = title
        self.description = description
        self.files = files
        self.fileCount = fileCount
    }

    enum CodingKeys: String, CodingKey { case ticket, title, description, files, fileCount }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(ticket: c.value(.ticket, ""), title: c.value(.title, ""), description: c.value(.description, ""),
                  files: c.value(.files, []), fileCount: c.value(.fileCount, 0))
    }
}

/// `POST /v1/media/removal-preview`'s body: "video" for a film, series,
/// season or episode (a Jellyfin id), "reading" for a book (a work id).
public struct RemovalRequestBody: Encodable, Equatable, Sendable {
    public var kind: String
    public var id: String

    public init(kind: String, id: String) {
        self.kind = kind
        self.id = id
    }
}

/// `POST /v1/media/remove`'s body: the ticket, and an explicit yes.
public struct RemovalConfirmationBody: Encodable, Equatable, Sendable {
    public var ticket: String
    public var confirm: Bool

    public init(ticket: String, confirm: Bool = true) {
        self.ticket = ticket
        self.confirm = confirm
    }
}
