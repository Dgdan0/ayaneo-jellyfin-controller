import Foundation
import Testing
@testable import HubKit

/// A kept EPUB checked with the hub as it opens (#41): the rule, the ETag kept
/// beside the book and let go of with it, and the opening itself against a
/// scripted hub, an unreachable one and the demo hub.
struct EpubFreshnessTests {
    private let token = String(repeating: "t", count: 43)
    private let request = HubEndpoints.readingEpubFile(workId: "rw_book", sourceItemId: "12")

    // MARK: The rule

    @Test func eachKeptCopyAsksWhatItNeeds() {
        #expect(EpubFreshness.ask(complete: false, etag: nil, force: false) == .download)
        #expect(EpubFreshness.ask(complete: false, etag: "\"v1\"", force: false) == .download)
        #expect(EpubFreshness.ask(complete: true, etag: "\"v1\"", force: true) == .download)
        #expect(EpubFreshness.ask(complete: true, etag: "\"v1\"", force: false) == .check(etag: "\"v1\""))
        // Kept before ETags were: once more. Sent without one: nothing to ask.
        #expect(EpubFreshness.ask(complete: true, etag: nil, force: false) == .refetch)
        #expect(EpubFreshness.ask(complete: true, etag: "", force: false) == .keep)
        #expect(!EpubFreshness.quick(.download))
        #expect(EpubFreshness.quick(.check(etag: "\"v1\"")) && EpubFreshness.quick(.refetch))
    }

    @Test func theHubsAnswerDecidesWhatOpens() {
        let check = EpubFreshness.Ask.check(etag: "\"v1\"")
        #expect(EpubFreshness.outcome(check, .unchanged) == .openKept)
        #expect(EpubFreshness.outcome(check, .file(etag: "\"v2\"")) == .replace(etag: "\"v2\""))
        #expect(EpubFreshness.outcome(check, .failed) == .openKept)
        #expect(EpubFreshness.outcome(.refetch, .file(etag: nil)) == .replace(etag: ""))
        #expect(EpubFreshness.outcome(.refetch, .failed) == .openKept)
        #expect(EpubFreshness.outcome(.download, .file(etag: "\"v1\"")) == .replace(etag: "\"v1\""))
        #expect(EpubFreshness.outcome(.download, .failed) == .fail)
        #expect(EpubFreshness.outcome(.keep, .unchanged) == .openKept)
    }

    // MARK: The ETag beside the book

    @Test func theEtagIsKeptWithTheBookAndGoesWithIt() throws {
        let cache = EpubPackageCache(root: Self.folder())
        defer { try? FileManager.default.removeItem(at: cache.root) }
        try cache.install(workId: "rw_book", sourceItemId: "12", etag: "\"v1\"") { try Self.book("one").write(to: $0) }
        #expect(cache.etag(workId: "rw_book", sourceItemId: "12") == "\"v1\"")
        // A copy installed without one (as before #41) keeps none, not the old one.
        try cache.install(workId: "rw_book", sourceItemId: "12") { try Self.book("two").write(to: $0) }
        #expect(cache.etag(workId: "rw_book", sourceItemId: "12") == nil)
        try cache.install(workId: "rw_book", sourceItemId: "12", etag: "") { try Self.book("three").write(to: $0) }
        #expect(cache.etag(workId: "rw_book", sourceItemId: "12") == "")
        // A refused download leaves the book and its ETag as they were.
        try cache.install(workId: "rw_book", sourceItemId: "12", etag: "\"v3\"") { try Self.book("three").write(to: $0) }
        #expect(throws: (any Error).self) {
            try cache.install(workId: "rw_book", sourceItemId: "12", etag: "\"v4\"") { try Data("<html>".utf8).write(to: $0) }
        }
        #expect(cache.etag(workId: "rw_book", sourceItemId: "12") == "\"v3\"")
        // Removed (Remove offline copy, a book that would not open): the ETag goes too.
        cache.remove(workId: "rw_book", sourceItemId: "12")
        #expect(cache.etag(workId: "rw_book", sourceItemId: "12") == nil)
        #expect(!cache.isComplete(workId: "rw_book", sourceItemId: "12"))
    }

    @Test func pruningLetsGoOfTheEtagOfEachBookItRemoves() throws {
        let cache = EpubPackageCache(root: Self.folder())
        defer { try? FileManager.default.removeItem(at: cache.root) }
        let old = try cache.install(workId: "rw_old", sourceItemId: "1", etag: "\"old\"") { try Self.book("old").write(to: $0) }
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 1_000)], ofItemAtPath: old.path)
        let open = try cache.install(workId: "rw_open", sourceItemId: "2", etag: "\"open\"") { try Self.book("open").write(to: $0) }
        cache.prune(budgetBytes: 1, keeping: open)
        #expect(!cache.isComplete(workId: "rw_old", sourceItemId: "1"))
        #expect(cache.etag(workId: "rw_old", sourceItemId: "1") == nil)
        #expect(cache.etag(workId: "rw_open", sourceItemId: "2") == "\"open\"")
    }

    // MARK: Opening, against a scripted hub

    @Test func aKeptBookIsAskedWhetherItChangedAndReplacedOnlyWhenItHas() async throws {
        let cache = EpubPackageCache(root: Self.folder())
        defer { try? FileManager.default.removeItem(at: cache.root) }
        let transport = ScriptedTransport([
            .init(status: 200, body: "PK\u{03}\u{04}one", headers: ["ETag": "\"v1\""]),
            .init(status: 304, body: "", headers: ["ETag": "\"v1\""]),
            .init(status: 200, body: "PK\u{03}\u{04}two", headers: ["ETag": "\"v2\""]),
            .init(status: 304, body: ""),
        ])
        let hub = client(transport)
        // Nothing kept: downloaded, with the hub's patience, and its ETag kept.
        let first = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: hub)
        #expect(try String(decoding: Data(contentsOf: first), as: UTF8.self).hasSuffix("one"))
        #expect(cache.etag(workId: "rw_book", sourceItemId: "12") == "\"v1\"")
        // Kept: asked with its ETag, quickly; 304 opens it as it is.
        _ = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: hub)
        #expect(try String(decoding: Data(contentsOf: first), as: UTF8.self).hasSuffix("one"))
        // The hub's copy changed: the new one replaces it, with its ETag.
        let third = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: hub)
        #expect(try String(decoding: Data(contentsOf: third), as: UTF8.self).hasSuffix("two"))
        #expect(cache.etag(workId: "rw_book", sourceItemId: "12") == "\"v2\"")
        _ = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: hub)
        let sent = await transport.sent
        #expect(sent.map { $0.value(forHTTPHeaderField: "If-None-Match") } == [nil, "\"v1\"", "\"v1\"", "\"v2\""])
        let timeouts = sent.map(\.timeoutInterval)
        #expect(timeouts.first != HubClient.quickCheckSeconds)
        #expect(Array(timeouts.dropFirst()) == [HubClient.quickCheckSeconds, HubClient.quickCheckSeconds, HubClient.quickCheckSeconds])
        #expect(Set(sent.compactMap { $0.url?.path }) == ["/v1/reading/works/rw_book/publications/12/file"])
    }

    @Test func aCopyKeptBeforeEtagsIsFetchedOnceAndThenChecked() async throws {
        let cache = EpubPackageCache(root: Self.folder())
        defer { try? FileManager.default.removeItem(at: cache.root) }
        try cache.install(workId: "rw_book", sourceItemId: "12") { try Self.book("old").write(to: $0) }
        let transport = ScriptedTransport([
            .init(status: 200, body: "PK\u{03}\u{04}copy", headers: ["ETag": "\"c1\""]),
            .init(status: 304, body: ""),
        ])
        let hub = client(transport)
        let file = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: hub)
        #expect(try String(decoding: Data(contentsOf: file), as: UTF8.self).hasSuffix("copy"))
        _ = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: hub)
        #expect(await transport.sent.map { $0.value(forHTTPHeaderField: "If-None-Match") } == [nil, "\"c1\""])
    }

    @Test func aHubThatSendsNoEtagIsNotAskedAgain() async throws {
        let cache = EpubPackageCache(root: Self.folder())
        defer { try? FileManager.default.removeItem(at: cache.root) }
        let transport = ScriptedTransport([.init(status: 200, body: "PK\u{03}\u{04}bare")])
        let hub = client(transport)
        _ = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: hub)
        #expect(cache.etag(workId: "rw_book", sourceItemId: "12") == "")
        _ = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: hub)
        #expect(await transport.sent.count == 1)
    }

    @Test func anOutageOrARefusalOpensWhatIsKeptAtOnce() async throws {
        let cache = EpubPackageCache(root: Self.folder())
        defer { try? FileManager.default.removeItem(at: cache.root) }
        try cache.install(workId: "rw_book", sourceItemId: "12", etag: "\"v1\"") { try Self.book("kept").write(to: $0) }
        // No network: one try, no retries, and the kept book.
        let offline = UnreachableTransport()
        let file = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: client(offline))
        #expect(try String(decoding: Data(contentsOf: file), as: UTF8.self).hasSuffix("kept"))
        #expect(await offline.calls == 1)
        // The hub down, or answering with something that is no book: the same.
        let down = ScriptedTransport([.init(status: 503, body: #"{"error":{"code":"upstream_down","message":"down"}}"#)])
        _ = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: client(down))
        #expect(await down.sent.count == 1)
        let wrong = ScriptedTransport([.init(status: 200, body: "<html>proxy</html>", headers: ["ETag": "\"w\""])])
        let kept = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: client(wrong))
        #expect(try String(decoding: Data(contentsOf: kept), as: UTF8.self).hasSuffix("kept"))
        #expect(cache.etag(workId: "rw_book", sourceItemId: "12") == "\"v1\"")
    }

    @Test func withNothingKeptAFailureIsSaidAndForceDownloadsAgain() async throws {
        let cache = EpubPackageCache(root: Self.folder())
        defer { try? FileManager.default.removeItem(at: cache.root) }
        await #expect(throws: HubFailure.self) {
            _ = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: client(UnreachableTransport()))
        }
        try cache.install(workId: "rw_book", sourceItemId: "12", etag: "\"v1\"") { try Self.book("kept").write(to: $0) }
        let transport = ScriptedTransport([.init(status: 200, body: "PK\u{03}\u{04}again", headers: ["ETag": "\"v1\""])])
        let file = try await cache.open(workId: "rw_book", sourceItemId: "12", request: request, hub: client(transport), force: true)
        #expect(try String(decoding: Data(contentsOf: file), as: UTF8.self).hasSuffix("again"))
        #expect(await transport.sent.first?.value(forHTTPHeaderField: "If-None-Match") == nil)
    }

    // MARK: The demo hub

    @Test func theDemoHubAnswers304ToTheEtagItSentAndAnOutageOpensTheKeptBook() async throws {
        let cache = EpubPackageCache(root: Self.folder())
        defer { try? FileManager.default.removeItem(at: cache.root) }
        let demo = RecordingDemo()
        let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                            screens: demo, sleep: { _ in })
        let book = HubEndpoints.readingEpubFile(workId: "rw_demo_rr6", sourceItemId: "rr6")
        let file = try await cache.open(workId: "rw_demo_rr6", sourceItemId: "rr6", request: book, hub: hub)
        let etag = try #require(cache.etag(workId: "rw_demo_rr6", sourceItemId: "rr6"))
        #expect(etag.hasPrefix("\"") && etag.count > 2)
        _ = try await cache.open(workId: "rw_demo_rr6", sourceItemId: "rr6", request: book, hub: hub)
        #expect(await demo.statuses == [200, 304])
        // The read-along edition is kept and checked the same way.
        let slim = HubEndpoints.readingEpubFile(workId: DemoReadAlong.workId, sourceItemId: DemoReadAlong.sourceItemId,
                                                format: "readaloud", omitAudio: true)
        let aligned = EpubPackageCache(root: cache.root.appendingPathComponent("aligned-slim", isDirectory: true))
        _ = try await aligned.open(workId: DemoReadAlong.workId, sourceItemId: DemoReadAlong.sourceItemId, request: slim, hub: hub)
        _ = try await aligned.open(workId: DemoReadAlong.workId, sourceItemId: DemoReadAlong.sourceItemId, request: slim, hub: hub)
        #expect(await demo.statuses == [200, 304, 200, 304])
        // Offline: the book kept opens, as it was.
        await demo.goOffline()
        let offline = try await cache.open(workId: "rw_demo_rr6", sourceItemId: "rr6", request: book, hub: hub)
        #expect(offline == file)
        #expect(try Data(contentsOf: offline).prefix(4) == Data([0x50, 0x4B, 0x03, 0x04]))
    }

    // MARK: Plumbing

    private func client(_ transport: any HubTransport) -> HubClient {
        HubClient(credentials: HubCredentials(baseURL: "https://hub.example", token: token), screens: transport,
                  now: { 0 }, sleep: { _ in })
    }

    private static func book(_ words: String) -> Data {
        Data([0x50, 0x4B, 0x03, 0x04] + Array(words.utf8))
    }

    private static func folder() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("epub-freshness-\(UUID().uuidString)", isDirectory: true)
    }
}

/// No network at all: every request fails at once, counted.
private actor UnreachableTransport: HubTransport {
    private(set) var calls = 0

    nonisolated func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        await count()
        throw URLError(.notConnectedToInternet)
    }

    private func count() { calls += 1 }
}

/// The demo hub, recording each answer's status, and able to lose the network.
private actor RecordingDemo: HubTransport {
    private(set) var statuses: [Int] = []
    private var offline = false

    func goOffline() { offline = true }

    nonisolated func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        guard await !isOffline() else { throw URLError(.notConnectedToInternet) }
        let (data, response) = try await DemoTransport().send(request)
        await record(response.statusCode)
        return (data, response)
    }

    private func isOffline() -> Bool { offline }
    private func record(_ status: Int) { statuses.append(status) }
}
