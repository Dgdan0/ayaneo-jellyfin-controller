import Foundation

/// The one way the app gets an artwork's Glass colours (GLASS_PLAN.md, #10). A
/// port of Android's `ui/glass/ArtworkColors`.
///
/// Screens `request` the artwork they show. Asks made within a few frames of
/// each other go to the hub as one request of at most sixty. What to ask, and
/// when to ask again, is `ArtworkColorBook`'s rule: pending after 3, 10 and
/// 30 seconds, missing never. Colours are kept per picture, whatever width it
/// is asked at (`ArtworkColorKey`, the hub's own key), and in a file, so after
/// a restart a page is in colour before the network answers.
public actor ArtworkColorStore {
    public typealias Fetch = @Sendable ([String]) async throws -> ArtworkColorsResponse

    /// Long enough for a row of cards appearing in one frame to share a request.
    public static let batchDelay: Duration = .milliseconds(40)
    /// New colours are written this long after they arrive, so a screenful of
    /// artwork costs one write rather than one per answer.
    public static let saveDelay: Duration = .seconds(5)
    public static let keepOnDevice = 2000
    /// The name Android gives its file too.
    public static let fileName = "artwork-colors.json"

    /// Each answer's new colours by picture (`ArtworkColorKey`), for the
    /// app's copy that views read.
    public nonisolated let updates: AsyncStream<[String: ArtworkPalette]>

    private var book: ArtworkColorBook
    private var queued: [String] = []
    /// The pictures queued (`ArtworkColorKey`), so two widths wait as one.
    private var queuedSet: Set<String> = []
    private let fetch: Fetch
    private let file: URL?
    private let automatic: Bool
    private let now: @Sendable () -> TimeInterval
    private let continuation: AsyncStream<[String: ArtworkPalette]>.Continuation
    private var sending: Task<Void, Never>?
    private var retrying: Task<Void, Never>?
    private var retryAt: TimeInterval?
    private var saving: Task<Void, Never>?

    /// - Parameters:
    ///   - file: where known colours are kept; nil keeps them in memory only.
    ///   - automatic: false leaves sending to `flush()` and writing to `save()`,
    ///     so tests can step through what the timers would do.
    public init(file: URL?, automatic: Bool = true, capacity: Int = keepOnDevice,
                now: @escaping @Sendable () -> TimeInterval = { Date().timeIntervalSince1970 },
                fetch: @escaping Fetch) {
        self.file = file
        self.automatic = automatic
        self.now = now
        self.fetch = fetch
        book = ArtworkColorBook(capacity: capacity)
        (updates, continuation) = AsyncStream.makeStream(of: [String: ArtworkPalette].self)
    }

    /// Known colours for `src`, or nil. Never asks.
    public func palette(_ src: String) -> ArtworkPalette? {
        src.isEmpty ? nil : book.palette(src)
    }

    /// Asks for the colours of `sources` that are not known yet, without
    /// waiting for them. Each answer arrives on `updates`.
    public func request(_ sources: [String]) {
        var added = false
        for src in sources where !src.isEmpty && !queuedSet.contains(ArtworkColorKey.of(src)) {
            if book.knows(src) || book.isMissing(src) { continue }
            queued.append(src)
            queuedSet.insert(ArtworkColorKey.of(src))
            added = true
        }
        if added { schedule(after: Self.batchDelay) }
    }

    /// Sends everything waiting, sixty to a request, together with anything
    /// whose wait after a pending answer is over. What the timers run.
    public func flush() async {
        for src in book.due(now: now()) where !queuedSet.contains(ArtworkColorKey.of(src)) {
            queued.append(src)
            queuedSet.insert(ArtworkColorKey.of(src))
        }
        while !queued.isEmpty {
            let asked = book.toAsk(queued, now: now())
            let leaving = Set(asked.map(ArtworkColorKey.of))
            queued.removeAll { leaving.contains(ArtworkColorKey.of($0)) || book.knows($0) || book.isMissing($0) }
            queuedSet = Set(queued.map(ArtworkColorKey.of))
            if asked.isEmpty { break }
            await send(asked)
        }
    }

    /// Reads the colours an earlier run kept, and returns everything now known,
    /// by picture. A damaged file costs one round of asking again, never a crash.
    @discardableResult
    public func restore() -> [String: ArtworkPalette] {
        if let file, let data = try? Data(contentsOf: file),
           let rows = try? JSONDecoder().decode([[String]].self, from: data) {
            book.restore(rows.compactMap { row -> (String, ArtworkPalette)? in
                guard row.count == 5,
                      let palette = ArtworkPalette(ArtworkColorSet(dominant: row[1], dark: row[2], vivid: row[3], light: row[4]))
                else { return nil }
                return (row[0], palette)
            })
        }
        return Dictionary(book.snapshot, uniquingKeysWith: { _, latest in latest })
    }

    /// Writes the known colours, least recently used first, in Android's shape
    /// with the picture's key first: `[[key, dominant, dark, vivid, light], …]`.
    public func save() {
        guard let file else { return }
        let rows = book.snapshot.suffix(Self.keepOnDevice).map { [$0.0] + $0.1.hexes }
        let encoder = JSONEncoder()
        encoder.outputFormatting = .withoutEscapingSlashes
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try encoder.encode(rows).write(to: file, options: .atomic)
        } catch {
            // Not kept: the next launch asks the hub again, which answers from its own file.
        }
    }

    private func send(_ asked: [String]) async {
        do {
            let response = try await fetch(asked)
            var colors: [String: ArtworkPalette] = [:]
            for (src, set) in response.colors {
                if let palette = ArtworkPalette(set) { colors[src] = palette }
            }
            let got = book.answered(asked, colors: colors, missing: response.missing, now: now())
            if !got.isEmpty {
                continuation.yield(Dictionary(got.compactMap { src in colors[src].map { (ArtworkColorKey.of(src), $0) } },
                                              uniquingKeysWith: { _, latest in latest }))
                scheduleSave()
            }
        } catch {
            // The hub may not have the endpoint yet (#10 before its deploy), or the
            // network dropped: the book waits and asks again, then gives up.
            book.failed(asked, now: now())
        }
    }

    private func schedule(after delay: Duration) {
        guard automatic, sending == nil else { return }
        sending = Task { [self] in
            try? await Task.sleep(for: delay)
            await flush()
            sending = nil
            planRetry()
        }
    }

    private func planRetry() {
        guard automatic, let dueAt = book.nextDueAt else { return }
        if let retryAt, retryAt <= dueAt, retrying != nil { return }
        retrying?.cancel()
        retryAt = dueAt
        let wait = max(0, dueAt - now())
        retrying = Task { [self] in
            try? await Task.sleep(for: .seconds(wait))
            guard !Task.isCancelled else { return }
            retrying = nil
            retryAt = nil
            schedule(after: .zero)
        }
    }

    private func scheduleSave() {
        guard automatic, file != nil, saving == nil else { return }
        saving = Task { [self] in
            try? await Task.sleep(for: Self.saveDelay)
            saving = nil
            save()
        }
    }
}
