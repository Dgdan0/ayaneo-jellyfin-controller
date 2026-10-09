import Foundation

/// What this device keeps of one profile's highlights of one book (#62; the
/// Pocket's `AnnotationBook`): every one the hub has told it of, the edits it
/// has not yet been able to send (the outbox), and how far through the hub's
/// changes it has read. `AnnotationStore` puts it on disk and `AnnotationSync`
/// talks to the hub.
///
/// The rule is the hub's, last write wins on `updatedAt`: an edit made here is
/// stamped by this device's clock, and a version the hub sends replaces ours
/// when it is as new or newer. A delete is an edit that leaves a tombstone, so
/// a device that was offline learns of it and an Undo (a newer edit of the
/// same id) can bring it back.
public struct AnnotationBook: Sendable {
    private var byId: [String: ReadingAnnotation] = [:]
    /// In the order the ids were first made or edited here.
    private var order: [String] = []
    private var outbox: [String] = []
    /// The greatest `syncedAt` of the hub's this device has read: the next fetch asks for what is after it.
    public private(set) var cursor: Int64

    public init(_ annotations: [ReadingAnnotation] = [], pending: [String] = [], cursor: Int64 = 0) {
        for annotation in annotations { keep(annotation) }
        outbox = pending.filter { byId[$0] != nil }.reduce(into: []) { if !$0.contains($1) { $0.append($1) } }
        self.cursor = cursor
    }

    private mutating func keep(_ annotation: ReadingAnnotation) {
        if byId[annotation.id] == nil { order.append(annotation.id) }
        byId[annotation.id] = annotation
    }

    private mutating func queue(_ id: String) {
        if !outbox.contains(id) { outbox.append(id) }
    }

    /// The highlights to show, in the order they were made.
    public var live: [ReadingAnnotation] {
        order.compactMap { byId[$0] }.filter { !$0.deleted }
            .sorted { $0.createdAt != $1.createdAt ? $0.createdAt < $1.createdAt : $0.id < $1.id }
    }

    /// Everything kept, tombstones too.
    public var all: [ReadingAnnotation] { order.compactMap { byId[$0] } }

    public var pendingIds: [String] { outbox }

    public var isDirty: Bool { !outbox.isEmpty }

    public subscript(id: String) -> ReadingAnnotation? { byId[id] }

    /// The edits waiting to be sent, oldest first, so a delete and the Undo
    /// after it arrive in the order they were made.
    public func pending() -> [ReadingAnnotation] {
        outbox.compactMap { byId[$0] }.enumerated()
            .sorted { $0.element.updatedAt != $1.element.updatedAt ? $0.element.updatedAt < $1.element.updatedAt : $0.offset < $1.offset }
            .map(\.element)
    }

    /// A highlight made or changed here, stamped `now`, or one millisecond
    /// after the version it replaces when this device's clock has not moved
    /// past it, so it always wins over what it edits. Returns what is kept.
    @discardableResult
    public mutating func put(_ annotation: ReadingAnnotation, now: Int64) -> ReadingAnnotation {
        let held = byId[annotation.id]
        let stamp = max(now, (held?.updatedAt ?? 0) + 1)
        var kept = annotation
        kept.createdAt = held?.createdAt ?? (annotation.createdAt > 0 ? annotation.createdAt : stamp)
        kept.updatedAt = stamp
        kept.deleted = false
        kept.syncedAt = held?.syncedAt ?? 0
        keep(kept)
        queue(kept.id)
        return kept
    }

    /// A highlight removed here: a tombstone that keeps the anchor and drops
    /// the note. Nil for one this book never held.
    @discardableResult
    public mutating func remove(_ id: String, now: Int64) -> ReadingAnnotation? {
        guard var held = byId[id] else { return nil }
        if held.deleted { return held }
        held.note = ""
        held.updatedAt = max(now, held.updatedAt + 1)
        held.deleted = true
        keep(held)
        queue(id)
        return held
    }

    /// The hub took `sent` (or answered with `held`, what it keeps under that
    /// id when it kept something newer): the edit is no longer waiting, unless
    /// this device has edited it again since, and what the hub holds is
    /// adopted when it is not the version that was sent.
    public mutating func sent(_ id: String, sent: ReadingAnnotation, held: ReadingAnnotation) {
        if let local = byId[id] {
            if !(local.updatedAt > sent.updatedAt && local.updatedAt > held.updatedAt) {
                keep(held)
                outbox.removeAll { $0 == id }
            }
        } else {
            keep(held)
        }
        cursor = max(cursor, held.syncedAt)
    }

    /// The hub will not take this edit and never will: it is no longer waiting.
    public mutating func refused(_ id: String) { outbox.removeAll { $0 == id } }

    /// What the hub sent. A version replaces ours when it is as new or newer
    /// (the hub's wins a tie, so two devices that wrote the same moment agree);
    /// an older one changes nothing. An edit still waiting that the hub's
    /// version replaces leaves the outbox: it would lose at the hub. Returns
    /// whether anything shown changed.
    @discardableResult
    public mutating func merge(_ remote: [ReadingAnnotation]) -> Bool {
        var changed = false
        for incoming in remote {
            cursor = max(cursor, incoming.syncedAt)
            let local = byId[incoming.id]
            if let local, incoming.updatedAt < local.updatedAt { continue }
            if local.map({ !Self.sameShown($0, incoming) }) ?? true { changed = true }
            keep(incoming)
            outbox.removeAll { $0 == incoming.id }
        }
        return changed
    }

    /// What the person sees of a version: a change to nothing but its stamps does not redraw the page.
    private static func sameShown(_ a: ReadingAnnotation, _ b: ReadingAnnotation) -> Bool {
        a.deleted == b.deleted && a.color == b.color && a.note == b.note && a.document == b.document && a.quote == b.quote
    }

    public struct Snapshot: Codable, Equatable, Sendable {
        public var annotations: [ReadingAnnotation]
        public var pending: [String]
        public var cursor: Int64

        public init(annotations: [ReadingAnnotation] = [], pending: [String] = [], cursor: Int64 = 0) {
            self.annotations = annotations
            self.pending = pending
            self.cursor = cursor
        }

        public func book() -> AnnotationBook { AnnotationBook(annotations, pending: pending, cursor: cursor) }
    }

    public func snapshot() -> Snapshot { Snapshot(annotations: all, pending: outbox, cursor: cursor) }
}

/// One atomically replaced file per profile and book (#62; the Pocket's
/// `AnnotationStore`): the book's highlights as this device knows them, the
/// outbox in it, so an edit made offline survives the app being closed and
/// goes out when the hub next answers. `scope` is the profile, hub and token
/// (`ReadingCheckpointKey.scope`): another has its own files and never sees
/// these. A file that cannot be read is an error, never permission to start a
/// new one over it.
public final class AnnotationStore: @unchecked Sendable {
    private let root: URL
    private let lock = NSLock()

    public struct Unreadable: Error, Equatable {}

    private struct Stored: Codable {
        let scope: String
        let workId: String
        let book: AnnotationBook.Snapshot
    }

    public init(root: URL) {
        self.root = root
    }

    private func file(_ scope: String, _ workId: String) -> URL {
        root.appendingPathComponent(ReadingCheckpointKey.digest(scope + "\u{0}" + workId) + ".json")
    }

    public func load(scope: String, workId: String) throws -> AnnotationBook {
        try lock.withLock {
            let url = file(scope, workId)
            guard FileManager.default.fileExists(atPath: url.path) else { return AnnotationBook() }
            guard let stored = try? JSONDecoder().decode(Stored.self, from: Data(contentsOf: url)),
                  stored.scope == scope, stored.workId == workId else { throw Unreadable() }
            return stored.book.book()
        }
    }

    public func save(scope: String, workId: String, book: AnnotationBook) throws {
        try lock.withLock {
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(Stored(scope: scope, workId: workId, book: book.snapshot()))
            try data.write(to: file(scope, workId), options: .atomic)
        }
    }

    /// The books under `scope` with edits waiting to be sent.
    public func pendingWorks(scope: String) -> [String] {
        lock.withLock {
            let files = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
            return files.filter { $0.pathExtension == "json" }
                .compactMap { try? JSONDecoder().decode(Stored.self, from: Data(contentsOf: $0)) }
                .filter { $0.scope == scope && !$0.book.pending.isEmpty }
                .map(\.workId)
                .sorted()
        }
    }

    /// Forgets a book's highlights on this device, the outbox with them.
    @discardableResult
    public func drop(scope: String, workId: String) -> Bool {
        lock.withLock { (try? FileManager.default.removeItem(at: file(scope, workId))) != nil }
    }
}

/// The hub's highlight routes, as the sync needs them: a seam so a test can stand in for the hub.
public protocol AnnotationRemote: Sendable {
    func annotations(workId: String, since: Int64?) async throws(HubFailure) -> ReadingAnnotationsResponse
    func save(workId: String, annotation: ReadingAnnotation) async throws(HubFailure) -> ReadingAnnotationWritten
    func delete(workId: String, id: String, updatedAt: Int64) async throws(HubFailure) -> ReadingAnnotationWritten
}

/// The hub itself, through the client.
public struct HubAnnotationRemote: AnnotationRemote {
    let hub: HubClient

    public init(hub: HubClient) {
        self.hub = hub
    }

    public func annotations(workId: String, since: Int64?) async throws(HubFailure) -> ReadingAnnotationsResponse {
        try await hub.fetch(HubEndpoints.readingAnnotations(workId: workId, since: since), as: ReadingAnnotationsResponse.self)
    }

    public func save(workId: String, annotation: ReadingAnnotation) async throws(HubFailure) -> ReadingAnnotationWritten {
        try await hub.fetch(HubEndpoints.saveReadingAnnotation(workId: workId, annotation), as: ReadingAnnotationWritten.self)
    }

    public func delete(workId: String, id: String, updatedAt: Int64) async throws(HubFailure) -> ReadingAnnotationWritten {
        try await hub.fetch(HubEndpoints.deleteReadingAnnotation(workId: workId, id: id, updatedAt: updatedAt),
                            as: ReadingAnnotationWritten.self)
    }
}

/// What a sync needs of the book it works on: the app's shelf under a lock,
/// a plain `AnnotationBook` in a test.
public protocol AnnotationLedger: AnyObject {
    func pending() -> [ReadingAnnotation]
    func sent(_ id: String, sent: ReadingAnnotation, held: ReadingAnnotation)
    func refused(_ id: String)
    func cursor() -> Int64
    func merged(_ remote: [ReadingAnnotation])
}

/// One pass with the hub (#62; the Pocket's `AnnotationSync`), the outbox first
/// and then what the other devices did: each waiting edit goes out oldest
/// first, and the hub's answer, what it holds under that id, is adopted; then
/// the changes after the cursor are read and merged.
public struct AnnotationSync: Sendable {
    public struct Result: Equatable, Sendable {
        /// A failure that may pass (no network, the hub busy): try again later.
        public var retry: Bool
        /// The hub could not be asked at all or said no for good: nothing more this pass.
        public var stopped: Bool

        public init(retry: Bool = false, stopped: Bool = false) {
            self.retry = retry
            self.stopped = stopped
        }
    }

    let remote: any AnnotationRemote

    public init(remote: any AnnotationRemote) {
        self.remote = remote
    }

    public func run(workId: String, ledger: AnnotationLedger) async -> Result {
        for edit in ledger.pending() {
            do throws(HubFailure) {
                let answer: ReadingAnnotationWritten
                if edit.deleted {
                    answer = try await remote.delete(workId: workId, id: edit.id, updatedAt: edit.updatedAt)
                } else {
                    answer = try await remote.save(workId: workId, annotation: edit)
                }
                ledger.sent(edit.id, sent: edit, held: answer.annotation)
            } catch {
                switch Self.judge(error) {
                case .refused: ledger.refused(edit.id)
                case .later: return Result(retry: true, stopped: true)
                case .never: return Result(stopped: true)
                }
            }
        }
        // Everything after the cursor, tombstones too; the first read has no cursor and gets what is there.
        let since = ledger.cursor() > 0 ? ledger.cursor() : nil
        do throws(HubFailure) {
            let list = try await remote.annotations(workId: workId, since: since)
            ledger.merged(list.annotations)
            return Result()
        } catch {
            return Self.judge(error) == .later ? Result(retry: true, stopped: true) : Result(stopped: true)
        }
    }

    enum Verdict { case later, refused, never }

    /// The hub's refusals for good (it is not a highlight, or the book holds the most it can keep).
    static let refusals: Set<String> = ["invalid_request", "annotation_limit"]

    static func judge(_ failure: HubFailure) -> Verdict {
        if failure.kind == .badResponse && refusals.contains(failure.code) { return .refused }
        if failure.kind.isRetryable { return .later }
        // An old hub without the routes, a token that cannot: not retried until the app is next opened.
        return .never
    }
}
