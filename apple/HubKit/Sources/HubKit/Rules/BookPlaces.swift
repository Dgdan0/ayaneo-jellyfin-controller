import Foundation

// Where an ebook opens and where it was left (#25, phase 4). The reader
// speaks only to a `BookPlaceKeeper`. Phase 2's reading outbox
// (`ReadingCheckpointStore` and `ReadingCheckpointSync`: the place kept on
// this device first, sent with the place it was based on, a place another
// device moved asked about, never overwritten) fills it for a real hub once
// it reaches this branch. Until then a real book's place is read and never
// written (`ReadOnlyBookPlaces`), and only the demo hub's books keep theirs
// (`DemoBookPlaces`).

/// Where a book opens.
public enum BookOpening: Equatable, Sendable {
    /// At this Readium locator (its JSON), or at the beginning (nil).
    case at(String?)
    /// The place could not be read. The reader says so and offers the beginning or another try.
    case unavailable(String)
}

/// What the ebook reader asks of whoever keeps its place.
public protocol BookPlaceKeeper: Sendable {
    /// Where to open.
    func opening() async -> BookOpening
    /// A place on the page (a Readium locator as JSON): kept, and sent when it can be.
    func reached(_ locator: String) async
    /// Leaving the book or the app, or a pause in the reading: send what is waiting now.
    func flush() async
    /// Another device moved the place while this one read: nothing more is sent.
    func conflicted() async -> Bool
}

/// A real hub's book until the outbox arrives: it opens where the hub says,
/// and its place is never written.
public actor ReadOnlyBookPlaces: BookPlaceKeeper {
    private let hub: HubClient
    private let workId: String
    private let sourceItemId: String

    public init(hub: HubClient, workId: String, sourceItemId: String) {
        self.hub = hub
        self.workId = workId
        self.sourceItemId = sourceItemId
    }

    public func opening() async -> BookOpening {
        await Self.read(hub: hub, workId: workId, sourceItemId: sourceItemId).opening
    }

    public func reached(_ locator: String) async {}
    public func flush() async {}
    public func conflicted() async -> Bool { false }

    /// The hub's place, as an opening, and the locator itself.
    static func read(hub: HubClient, workId: String, sourceItemId: String) async -> (opening: BookOpening, locator: String?, read: Bool) {
        do throws(HubFailure) {
            let data = try await hub.data(HubEndpoints.readingEpubPosition(workId: workId, sourceItemId: sourceItemId))
            guard let position = EpubPosition.decode(data) else {
                return (.unavailable(FailureKind.badResponse.message), nil, false)
            }
            return (.at(position.locator), position.locator, true)
        } catch {
            return (.unavailable(error.message), nil, false)
        }
    }
}

/// The demo hub's books (`-demo`): the place goes to the demo hub through the
/// same route a real book's will take, with the place it was based on, one
/// send at a time; a 409 (another device moved it, `DemoBooks.moveElsewhere`)
/// stops the sending. It refuses any other hub, so it can never write a real
/// book's place.
public actor DemoBookPlaces: BookPlaceKeeper {
    private let hub: HubClient
    private let workId: String
    private let sourceItemId: String
    private let now: @Sendable () -> Int64
    /// The hub's place when this device last read or wrote it: what `expectedLocator` names.
    private var base: String?
    private var baseKnown = false
    /// The newest place reached that the hub has not taken.
    private var wanted: String?
    private var sending = false
    private var refused = false

    public init(hub: HubClient, workId: String, sourceItemId: String,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }) {
        self.hub = hub
        self.workId = workId
        self.sourceItemId = sourceItemId
        self.now = now
    }

    public func opening() async -> BookOpening {
        guard await isDemo() else { return .unavailable("This book's place is kept only on the demo hub") }
        let read = await ReadOnlyBookPlaces.read(hub: hub, workId: workId, sourceItemId: sourceItemId)
        if read.read {
            base = read.locator
            baseKnown = true
        }
        return read.opening
    }

    public func reached(_ locator: String) async {
        guard BookLocator.valid(locator), !BookLocator.same(locator, wanted ?? base) else { return }
        wanted = locator
    }

    public func flush() async {
        guard !sending, !refused, baseKnown, let place = wanted, await isDemo() else { return }
        sending = true
        defer { sending = false }
        let body = EpubPositionBody(locator: place, timestamp: now(), expectedLocator: base)
        do throws(HubFailure) {
            try await hub.send(HubEndpoints.saveReadingEpubPosition(workId: workId, sourceItemId: sourceItemId, body))
            base = place
            if BookLocator.same(wanted, place) { wanted = nil }
        } catch {
            // Another device moved the place: never overwritten. Anything else waits for the next flush.
            if error.status == 409 { refused = true }
        }
    }

    public func conflicted() async -> Bool { refused }

    private func isDemo() async -> Bool {
        await hub.current.baseURL == DemoTransport.address
    }
}
