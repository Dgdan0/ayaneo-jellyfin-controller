import Foundation

// Where an ebook opens and where it was left (#25, phase 4). The reader
// speaks only to a `BookPlaceKeeper`, and `CheckpointBookPlaces` keeps a
// book's place through the reading outbox phase 2 brought
// (`ReadingCheckpointStore`, `ReadingCheckpointSync`), as the listening place
// goes: kept on this device first, sent with the place it was based on, and a
// place another device moved since is a question, never an overwrite.

/// Where a book opens.
public enum BookOpening: Equatable, Sendable {
    /// At this Readium locator (its JSON), or at the beginning (nil).
    case at(String?)
    /// Another device moved the place, or the hub could not be asked: the
    /// reader asks which, and hands the answer to `answer(_:)`.
    case question(ReadingResumePrompt)
    /// The place could not be read on this device. The reader says so.
    case unavailable(String)
}

/// What the ebook reader asks of whoever keeps its place.
public protocol BookPlaceKeeper: Sendable {
    /// Where to open.
    func opening() async -> BookOpening
    /// The answer to `opening()`'s question: a choice's id ("local", "server" or "start").
    func answer(_ choice: String) async -> BookOpening
    /// A place on the page (a Readium locator as JSON): kept, and sent when it can be.
    func reached(_ locator: String) async
    /// Leaving the book or the app, or a pause in the reading: send what is waiting now.
    func flush() async
    /// Another device moved the place while this one read: nothing more is sent.
    func conflicted() async -> Bool
}

/// A book's place through the reading outbox, under `kind` "epub": the
/// hub's place read through `…/position`, sent back with `checkBase` and the
/// place this device last read (`expectedLocator`).
public actor CheckpointBookPlaces: BookPlaceKeeper {
    public static let kind = "epub"

    private let hub: HubClient
    private let store: ReadingCheckpointStore
    private let key: ReadingCheckpointKey
    private let now: @Sendable () -> Int64
    private var refused = false

    public init(hub: HubClient, store: ReadingCheckpointStore, key: ReadingCheckpointKey,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }) {
        self.hub = hub
        self.store = store
        self.key = key
        self.now = now
    }

    /// The key for a book's EPUB place, for one hub and profile.
    public static func key(address: String, userId: String, workId: String, sourceItemId: String) -> ReadingCheckpointKey {
        ReadingCheckpointKey(scope: ReadingCheckpointKey.scope(address: address, userId: userId), workId: workId,
                             sourceItemId: sourceItemId, kind: kind)
    }

    public func opening() async -> BookOpening {
        let remote = await Self.remote(hub, key)
        let resume: ReadingResume
        do {
            resume = try store.reconcile(key, remote)
        } catch {
            return .unavailable("Your place in this book could not be read on this device. It has been kept.")
        }
        if let prompt = ReadingResumePrompt.make(resume, checkpoint: try? store.read(key),
                                                 describe: { Self.json($0.locator).map(BookLocator.label) ?? $0.label() }) {
            return .question(prompt)
        }
        return .at(Self.json(resume.location?.locator))
    }

    public func answer(_ choice: String) async -> BookOpening {
        switch choice {
        case "local":
            guard let chosen = try? store.chooseLocal(key, now: now()) else { return .at(nil) }
            return .at(Self.json(chosen.local?.locator))
        case "server":
            guard let chosen = try? store.chooseRemote(key) else { return .at(nil) }
            return .at(Self.json(chosen.local?.locator))
        default:
            // From the beginning, which writes nothing until the reading moves.
            return .at(nil)
        }
    }

    public func reached(_ locator: String) async {
        guard BookLocator.valid(locator), let location = Self.location(locator) else { return }
        _ = try? store.save(key, location, now: now())
    }

    public func flush() async {
        guard !refused else { return }
        let hub = hub
        let sync = ReadingCheckpointSync(store: store, fetch: { key in await Self.remote(hub, key) }, send: { checkpoint in
            guard let locator = Self.json(checkpoint.local?.locator) else { return false }
            let body = EpubPositionBody(locator: locator, timestamp: Int64(Date().timeIntervalSince1970 * 1_000),
                                        checkBase: checkpoint.baseKnown, expectedLocator: Self.json(checkpoint.base?.locator))
            do throws(HubFailure) {
                try await hub.send(HubEndpoints.saveReadingEpubPosition(workId: checkpoint.key.workId,
                                                                        sourceItemId: checkpoint.key.sourceItemId, body))
                return true
            } catch {
                return false
            }
        })
        if (try? await sync.sync(key)) == .conflict { refused = true }
    }

    public func conflicted() async -> Bool { refused }

    // MARK: Plumbing

    /// The hub's place for the book, or that it could not be asked.
    static func remote(_ hub: HubClient, _ key: ReadingCheckpointKey) async -> RemoteReadingPosition {
        do throws(HubFailure) {
            let data = try await hub.data(HubEndpoints.readingEpubPosition(workId: key.workId, sourceItemId: key.sourceItemId))
            guard let position = EpubPosition.decode(data) else { return .unavailable }
            return .available(position.locator.flatMap(location))
        } catch {
            return .unavailable
        }
    }

    /// A locator's JSON as the outbox keeps it.
    static func location(_ json: String) -> ReadingLocation? {
        guard let fields = try? JSONDecoder().decode([String: JSONValue].self, from: Data(json.utf8)) else { return nil }
        return ReadingLocation(locator: fields)
    }

    /// The outbox's locator as JSON, keys in order.
    static func json(_ locator: [String: JSONValue]?) -> String? {
        guard let locator else { return nil }
        let data = JSONValue.object(locator).encoded()
        guard let value = try? JSONSerialization.jsonObject(with: data) else { return nil }
        return BookLocator.canonical(value)
    }
}
