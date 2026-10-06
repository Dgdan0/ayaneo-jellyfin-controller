import CryptoKit
import Foundation

// A reading or listening place kept on this device first and sent to the hub
// through a durable outbox (#25). Android's `ReadingCheckpointStore` and
// `ReadingCheckpointSync`, held to their tests: a place another device moved
// is a question, never an overwrite; a write names the place it was based on
// (`expected`), and the hub checks it; nobody's clock decides which is newer.
// The listening place uses it from phase 2, a comic's page from phase 3.

/// Whose place, in what, of which kind: the hub and profile (`scope`), the
/// book, its edition, and "audio", "pages" or "epub".
public struct ReadingCheckpointKey: Codable, Equatable, Hashable, Sendable {
    public let scope: String
    public let workId: String
    public let sourceItemId: String
    public let kind: String

    public init(scope: String, workId: String, sourceItemId: String, kind: String) {
        self.scope = scope
        self.workId = workId
        self.sourceItemId = sourceItemId
        self.kind = kind
    }

    /// The record's file name: none of the parts can reach the file system.
    public var fileName: String { Self.digest([scope, workId, sourceItemId, kind].joined(separator: "\u{0}")) }

    public static func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// Whose places these are: the hub, without the slashes an address may
    /// end in, and the profile. A new token keeps them; another profile
    /// never sees them.
    public static func scope(address: String, userId: String) -> String {
        var trimmed = Substring(address.trimmingCharacters(in: .whitespacesAndNewlines))
        while trimmed.hasSuffix("/") { trimmed = trimmed.dropLast() }
        return digest(String(trimmed) + "\u{0}" + userId)
    }
}

/// A place: a locator (an EPUB's, a listening place's) or a page of a comic,
/// exactly one of the two.
public struct ReadingLocation: Codable, Equatable, Hashable, Sendable {
    public let locator: [String: JSONValue]?
    public let pageIndex: Int?

    public init(locator: [String: JSONValue]) {
        self.locator = locator
        pageIndex = nil
    }

    public init(pageIndex: Int) {
        precondition(pageIndex >= 0, "A page is counted from 0")
        locator = nil
        self.pageIndex = pageIndex
    }

    enum CodingKeys: String, CodingKey { case locator, pageIndex }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let locator = try c.decodeIfPresent([String: JSONValue].self, forKey: .locator)
        let page = try c.decodeIfPresent(Int.self, forKey: .pageIndex)
        guard (locator == nil) != (page == nil), (page ?? 0) >= 0 else {
            throw DecodingError.dataCorrupted(.init(codingPath: c.codingPath, debugDescription: "A place is a locator or a page"))
        }
        self.locator = locator
        pageIndex = page
    }

    /// "Page 12", or a locator's chapter and how far: "Chapter 3 · 41%".
    public func label() -> String {
        if let pageIndex { return "Page \(pageIndex + 1)" }
        let progression = locator?["locations"]?["totalProgression"]?.doubleValue
        let chapter = locator?["title"]?.stringValue ?? locator?["href"]?.stringValue ?? ""
        return ([chapter.isEmpty ? nil : chapter, progression.map { Fmt.readingPercentLabel($0) }] as [String?])
            .compactMap { $0 }.joined(separator: " · ")
    }
}

/// One publication's record, which is also its entry in the outbox: the place
/// here (`local`), the place last read from the hub (`base`, and whether any
/// was read), the hub's latest (`remote`), and whether `local` is still to send.
public struct ReadingCheckpoint: Codable, Equatable, Sendable {
    public var key: ReadingCheckpointKey
    public var local: ReadingLocation?
    public var base: ReadingLocation?
    public var baseKnown: Bool
    public var remote: ReadingLocation?
    public var revision: Int64
    public var updatedAt: Int64
    public var pending: Bool
    public var conflicted: Bool
    /// Both places of every question answered, the last twenty.
    public var savedAlternatives: [ReadingLocation]

    public init(key: ReadingCheckpointKey, local: ReadingLocation? = nil, base: ReadingLocation? = nil, baseKnown: Bool = false,
                remote: ReadingLocation? = nil, revision: Int64 = 0, updatedAt: Int64 = 0, pending: Bool = false,
                conflicted: Bool = false, savedAlternatives: [ReadingLocation] = []) {
        self.key = key
        self.local = local
        self.base = base
        self.baseKnown = baseKnown
        self.remote = remote
        self.revision = revision
        self.updatedAt = updatedAt
        self.pending = pending
        self.conflicted = conflicted
        self.savedAlternatives = savedAlternatives
    }

    enum CodingKeys: String, CodingKey {
        case key, local, base, baseKnown, remote, revision, updatedAt, pending, conflicted, savedAlternatives
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(key: try c.decode(ReadingCheckpointKey.self, forKey: .key),
                  local: try c.decodeIfPresent(ReadingLocation.self, forKey: .local),
                  base: try c.decodeIfPresent(ReadingLocation.self, forKey: .base),
                  baseKnown: try c.decodeIfPresent(Bool.self, forKey: .baseKnown) ?? false,
                  remote: try c.decodeIfPresent(ReadingLocation.self, forKey: .remote),
                  revision: try c.decodeIfPresent(Int64.self, forKey: .revision) ?? 0,
                  updatedAt: try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0,
                  pending: try c.decodeIfPresent(Bool.self, forKey: .pending) ?? false,
                  conflicted: try c.decodeIfPresent(Bool.self, forKey: .conflicted) ?? false,
                  savedAlternatives: try c.decodeIfPresent([ReadingLocation].self, forKey: .savedAlternatives) ?? [])
    }
}

/// What the hub said: its place (nil when it keeps none), or that it could not say.
public enum RemoteReadingPosition: Equatable, Sendable {
    case available(ReadingLocation?)
    case unavailable
}

/// Where to open, and whether to ask which place first.
public struct ReadingResume: Equatable, Sendable {
    public let location: ReadingLocation?
    public let conflict: Bool
    public let unavailable: Bool

    public init(_ location: ReadingLocation?, conflict: Bool = false, unavailable: Bool = false) {
        self.location = location
        self.conflict = conflict
        self.unavailable = unavailable
    }
}

/// One record per publication, written whole and swapped in atomically, so a
/// crash leaves the old record or the new one, never half of either. A record
/// that cannot be read is an error, never permission to start the book again.
public final class ReadingCheckpointStore: @unchecked Sendable {
    private let root: URL
    private let lock = NSLock()

    public init(root: URL) {
        self.root = root
    }

    public func read(_ key: ReadingCheckpointKey) throws -> ReadingCheckpoint? {
        lock.lock()
        defer { lock.unlock() }
        return try load(key)
    }

    /// The records still to send for one hub and profile, oldest first.
    public func pending(scope: String) -> [ReadingCheckpoint] {
        lock.lock()
        defer { lock.unlock() }
        let files = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }
            .compactMap { try? Self.decoder.decode(ReadingCheckpoint.self, from: Data(contentsOf: $0)) }
            .filter { $0.key.scope == scope && $0.pending }
            .sorted { $0.updatedAt < $1.updatedAt }
    }

    /// A place reached here. The same place again writes nothing.
    @discardableResult
    public func save(_ key: ReadingCheckpointKey, _ location: ReadingLocation, now: Int64) throws -> ReadingCheckpoint {
        lock.lock()
        defer { lock.unlock() }
        var next = try load(key) ?? ReadingCheckpoint(key: key)
        if Self.same(key, next.local, location) { return next }
        next.local = location
        next.revision += 1
        next.updatedAt = now
        next.pending = true
        return try write(next)
    }

    /// A place kept before this store knew the book, written once and only
    /// when there is no record: it goes out as it is while the hub has no
    /// place, and is a question when it has one.
    @discardableResult
    public func seed(_ key: ReadingCheckpointKey, _ location: ReadingLocation, now: Int64) throws -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if try load(key) != nil { return false }
        try write(ReadingCheckpoint(key: key, local: location, base: nil, baseKnown: true, revision: 1, updatedAt: now, pending: true))
        return true
    }

    /// The hub's place against this device's: the hub's when nothing here is
    /// waiting; this device's while the hub still holds the place it was based
    /// on; otherwise a question.
    @discardableResult
    public func reconcile(_ key: ReadingCheckpointKey, _ server: RemoteReadingPosition) throws -> ReadingResume {
        lock.lock()
        defer { lock.unlock() }
        let previous = try load(key)
        guard case .available(let remote) = server else {
            return ReadingResume(previous?.local, conflict: previous?.conflicted == true, unavailable: previous?.local == nil)
        }
        guard let previous, previous.pending else {
            var settled = previous ?? ReadingCheckpoint(key: key)
            settled.local = remote
            settled.base = remote
            settled.baseKnown = true
            settled.remote = remote
            settled.pending = false
            settled.conflicted = false
            try write(settled)
            return ReadingResume(remote)
        }
        if Self.same(key, previous.local, remote) {
            try settle(key, revision: previous.revision, sent: remote)
            return ReadingResume(remote)
        }
        let conflict = !previous.baseKnown || !Self.same(key, previous.base, remote)
        var asked = previous
        asked.remote = remote
        asked.conflicted = conflict
        try write(asked)
        return ReadingResume(previous.local, conflict: conflict)
    }

    /// The hub took `sent` for `revision`. An older answer never clears a
    /// newer place, nor revives one a choice discarded.
    public func acknowledge(_ key: ReadingCheckpointKey, revision: Int64, sent: ReadingLocation?) throws {
        lock.lock()
        defer { lock.unlock() }
        try settle(key, revision: revision, sent: sent)
    }

    /// "Continue on this device": this place goes out, based on the hub's.
    @discardableResult
    public func chooseLocal(_ key: ReadingCheckpointKey, now: Int64) throws -> ReadingCheckpoint {
        lock.lock()
        defer { lock.unlock() }
        guard var current = try load(key) else { throw CocoaError(.fileNoSuchFile) }
        current.savedAlternatives = alternatives(current)
        current.base = current.remote
        current.baseKnown = true
        current.conflicted = false
        current.revision += 1
        current.updatedAt = now
        current.pending = !Self.same(key, current.local, current.remote)
        return try write(current)
    }

    /// "Use server position": the hub's place, nothing sent.
    @discardableResult
    public func chooseRemote(_ key: ReadingCheckpointKey) throws -> ReadingCheckpoint {
        lock.lock()
        defer { lock.unlock() }
        guard var current = try load(key) else { throw CocoaError(.fileNoSuchFile) }
        current.savedAlternatives = alternatives(current)
        current.local = current.remote
        current.base = current.remote
        current.baseKnown = true
        current.revision += 1
        current.pending = false
        current.conflicted = false
        return try write(current)
    }

    /// Whether two locations of `key`'s book are the same place. An
    /// audiobook's place is its track and moment: this device keeps how far
    /// through the book it is beside them (#30), which the hub never reads
    /// back, so the whole location would take the hub's own reading of the
    /// place just sent for another device's. Every other kind compares whole.
    static func same(_ key: ReadingCheckpointKey, _ one: ReadingLocation?, _ other: ReadingLocation?) -> Bool {
        key.kind == AudioPlace.kind ? AudioPlace.samePlace(one, other) : one == other
    }

    // MARK: Plumbing (under the lock)

    private func settle(_ key: ReadingCheckpointKey, revision: Int64, sent: ReadingLocation?) throws {
        guard var latest = try load(key) else { return }
        if revision > latest.revision || (revision < latest.revision && !latest.pending) { return }
        latest.base = sent
        latest.baseKnown = true
        latest.remote = sent
        latest.pending = latest.revision != revision
        latest.conflicted = false
        try write(latest)
    }

    private func alternatives(_ value: ReadingCheckpoint) -> [ReadingLocation] {
        var seen = Set<ReadingLocation>()
        let all = (value.savedAlternatives + [value.local, value.remote].compactMap { $0 }).filter { seen.insert($0).inserted }
        return Array(all.suffix(20))
    }

    private func load(_ key: ReadingCheckpointKey) throws -> ReadingCheckpoint? {
        let file = root.appendingPathComponent(key.fileName + ".json")
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        let value = try Self.decoder.decode(ReadingCheckpoint.self, from: Data(contentsOf: file))
        guard value.key == key else { throw CocoaError(.fileReadCorruptFile) }
        return value
    }

    @discardableResult
    private func write(_ value: ReadingCheckpoint) throws -> ReadingCheckpoint {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let target = root.appendingPathComponent(value.key.fileName + ".json")
        let temporary = root.appendingPathComponent(value.key.fileName + ".tmp")
        try Self.encoder.encode(value).write(to: temporary)
        let handle = try FileHandle(forUpdating: temporary)
        try handle.synchronize()
        try handle.close()
        guard rename(temporary.path, target.path) == 0 else { throw CocoaError(.fileWriteUnknown) }
        return value
    }

    private static let encoder = JSONEncoder()
    private static let decoder = JSONDecoder()
}

public enum CheckpointSyncResult: Sendable {
    case synced, retry, conflict
}

/// Sends one record: reads the hub's place first, asks rather than overwrites
/// when it moved, and acknowledges only the revision it sent, so a place
/// reached while the write was in flight still goes. One sync at a time per
/// record is the caller's to keep; places may be saved meanwhile.
public struct ReadingCheckpointSync: Sendable {
    let store: ReadingCheckpointStore
    let fetch: @Sendable (ReadingCheckpointKey) async -> RemoteReadingPosition
    let send: @Sendable (ReadingCheckpoint) async -> Bool

    public init(store: ReadingCheckpointStore, fetch: @escaping @Sendable (ReadingCheckpointKey) async -> RemoteReadingPosition,
                send: @escaping @Sendable (ReadingCheckpoint) async -> Bool) {
        self.store = store
        self.fetch = fetch
        self.send = send
    }

    public func sync(_ key: ReadingCheckpointKey) async throws -> CheckpointSyncResult {
        guard let before = try store.read(key), before.pending else { return .synced }
        if before.conflicted { return .conflict }
        let remote = await fetch(key)
        if remote == .unavailable { return .retry }
        if try store.reconcile(key, remote).conflict { return .conflict }
        guard let sent = try store.read(key), sent.pending else { return .synced }
        guard await send(sent) else { return .retry }
        try store.acknowledge(key, revision: sent.revision, sent: sent.local)
        return try store.read(key)?.pending == true ? .retry : .synced
    }
}

/// The sheet that asks where to go on (Android's `chooseReadingResume`):
/// another device moved the place, or the hub could not be asked. Nil when
/// there is nothing to ask.
public struct ReadingResumePrompt: Equatable, Sendable {
    public struct Choice: Equatable, Sendable, Identifiable {
        /// "local", "server" or "start".
        public let id: String
        public let label: String
        public let detail: String
    }

    public let title: String
    public let message: String
    public let choices: [Choice]

    /// `describe` says where a place is: a page or a chapter for a book, a
    /// part and a time for an audiobook.
    public static func make(_ resume: ReadingResume, checkpoint: ReadingCheckpoint?,
                            describe: (ReadingLocation) -> String) -> ReadingResumePrompt? {
        if resume.conflict {
            return ReadingResumePrompt(
                title: "Choose reading position",
                message: "Another reader moved your position. Both positions will be kept on this device.",
                choices: [Choice(id: "local", label: "Continue on this device", detail: checkpoint?.local.map(describe) ?? ""),
                          Choice(id: "server", label: "Use server position", detail: checkpoint?.remote.map(describe) ?? "Beginning")])
        }
        if resume.unavailable {
            return ReadingResumePrompt(
                title: "Reading position unavailable",
                message: "Go back to retry, or explicitly start here.",
                choices: [Choice(id: "start", label: "Start from the beginning",
                                 detail: "Your server position could not be checked. This choice is saved locally.")])
        }
        return nil
    }
}
