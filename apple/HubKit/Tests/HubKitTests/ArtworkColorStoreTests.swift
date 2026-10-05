import Foundation
import Testing
@testable import HubKit

/// A clock the tests move by hand.
final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: TimeInterval

    init(_ start: TimeInterval = 1_000) {
        value = start
    }

    var now: TimeInterval { lock.withLock { value } }

    func advance(_ seconds: TimeInterval) {
        lock.withLock { value += seconds }
    }
}

/// The hub's `/v1/img/colors`, scripted: records every request and answers
/// with `answer(sources, callNumber)`.
actor ColorsHub {
    typealias Answer = @Sendable (_ sources: [String], _ call: Int) throws -> ArtworkColorsResponse

    private(set) var asked: [[String]] = []
    private let answer: Answer

    init(_ answer: @escaping Answer) {
        self.answer = answer
    }

    func fetch(_ sources: [String]) throws -> ArtworkColorsResponse {
        asked.append(sources)
        return try answer(sources, asked.count)
    }

    /// Answers every source with `palette`.
    static func knowing(_ palette: ArtworkPalette) -> ColorsHub {
        ColorsHub { sources, _ in colours(sources, palette) }
    }

    static func colours(_ sources: [String], _ palette: ArtworkPalette) -> ArtworkColorsResponse {
        let hex = palette.hexes
        let set = ArtworkColorSet(dominant: hex[0], dark: hex[1], vivid: hex[2], light: hex[3])
        return ArtworkColorsResponse(colors: Dictionary(uniqueKeysWithValues: sources.map { ($0, set) }))
    }
}

/// The colours store: batching, the asking rules in use, and the file. The
/// rules themselves are pinned in `GlassTests`.
struct ArtworkColorStoreTests {
    let gold = ArtworkPalette(dominant: 0xFFD0_B366, dark: 0xFF1D_1500, vivid: 0xFFD0_B366, light: 0xFFF2_E4BF)

    func store(_ hub: ColorsHub, clock: TestClock = TestClock(), file: URL? = nil) -> ArtworkColorStore {
        ArtworkColorStore(file: file, automatic: false, now: { clock.now }, fetch: { try await hub.fetch($0) })
    }

    @Test func asksMadeTogetherGoToTheHubAsOneRequest() async {
        let hub = ColorsHub.knowing(gold)
        let colors = store(hub)
        await colors.request(["/v1/img/a"])
        await colors.request(["/v1/img/b", "/v1/img/a", ""])
        await colors.flush()
        #expect(await hub.asked == [["/v1/img/a", "/v1/img/b"]])
        #expect(await colors.palette("/v1/img/b") == gold)
        #expect(await colors.palette("/v1/img/unknown") == nil)
    }

    @Test func aRequestCarriesAtMostSixty() async {
        let hub = ColorsHub.knowing(gold)
        let colors = store(hub)
        let sources = (1...75).map { "/v1/img/p\($0)" }
        await colors.request(sources)
        await colors.flush()
        let asked = await hub.asked
        #expect(asked.map(\.count) == [60, 15])
        #expect(asked.flatMap { $0 } == sources)
    }

    @Test func knownAndMissingArtworkIsNeverAskedForAgain() async {
        let hub = ColorsHub { sources, _ in
            var response = ColorsHub.colours(sources.filter { $0 == "/v1/img/a" }, gold)
            response.missing = sources.filter { $0 != "/v1/img/a" }
            return response
        }
        let colors = store(hub)
        await colors.request(["/v1/img/a", "/v1/img/gone"])
        await colors.flush()
        await colors.request(["/v1/img/a", "/v1/img/gone"])
        await colors.flush()
        #expect(await hub.asked.count == 1)
        #expect(await colors.palette("/v1/img/a") == gold)
        #expect(await colors.palette("/v1/img/gone") == nil)
    }

    @Test func pendingArtworkIsAskedForAgainOnceItsWaitIsOver() async {
        let clock = TestClock()
        let hub = ColorsHub { sources, call in
            call == 1 ? ArtworkColorsResponse(pending: sources) : ColorsHub.colours(sources, gold)
        }
        let colors = store(hub, clock: clock)
        await colors.request(["/v1/img/slow"])
        await colors.flush()
        clock.advance(2.9)
        await colors.flush()
        #expect(await hub.asked.count == 1)
        clock.advance(0.1)
        await colors.flush()
        #expect(await hub.asked == [["/v1/img/slow"], ["/v1/img/slow"]])
        #expect(await colors.palette("/v1/img/slow") == gold)
    }

    @Test func aFailedRequestIsAskedAgainLater() async {
        struct Offline: Error {}
        let clock = TestClock()
        let hub = ColorsHub { sources, call in
            if call == 1 { throw Offline() }
            return ColorsHub.colours(sources, gold)
        }
        let colors = store(hub, clock: clock)
        await colors.request(["/v1/img/a"])
        await colors.flush()
        #expect(await colors.palette("/v1/img/a") == nil)
        clock.advance(3)
        await colors.flush()
        #expect(await hub.asked.count == 2)
        #expect(await colors.palette("/v1/img/a") == gold)
    }

    @Test func newColoursArriveOnTheUpdates() async {
        let colors = store(ColorsHub.knowing(gold))
        var updates = colors.updates.makeAsyncIterator()
        await colors.request(["/v1/img/a"])
        await colors.flush()
        #expect(await updates.next() == ["/v1/img/a": gold])
    }

    @Test func coloursOutliveARestartInAndroidsFileShapeByPicture() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("hubkit-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = folder.appendingPathComponent(ArtworkColorStore.fileName)

        let first = store(ColorsHub.knowing(gold), file: file)
        await first.request(["/v1/img/jf/abc/Backdrop?tag=t&w=1280"])
        await first.flush()
        await first.save()
        #expect(try String(contentsOf: file, encoding: .utf8)
            == ##"[["jf/abc/Backdrop/t","#d0b366","#1d1500","#d0b366","#f2e4bf"]]"##)

        let hub = ColorsHub.knowing(gold)
        let second = store(hub, file: file)
        #expect(await second.restore() == ["jf/abc/Backdrop/t": gold])
        // The same picture at another width is known already.
        await second.request(["/v1/img/jf/abc/Backdrop?tag=t&w=360"])
        await second.flush()
        #expect(await hub.asked.isEmpty)
        #expect(await second.palette("/v1/img/jf/abc/Backdrop?tag=t") == gold)
    }

    @Test func aFileFromBeforeKeysByPictureStillCounts() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("hubkit-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: folder) }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent(ArtworkColorStore.fileName)
        try Data(##"[["/v1/img/tmdb/w342/x.jpg","#d0b366","#1d1500","#d0b366","#f2e4bf"]]"##.utf8).write(to: file)

        let hub = ColorsHub.knowing(gold)
        let colors = store(hub, file: file)
        #expect(await colors.restore() == ["tmdb/x.jpg": gold])
        await colors.request(["/v1/img/tmdb/w780/x.jpg"])
        await colors.flush()
        #expect(await hub.asked.isEmpty)
    }

    @Test func onePictureAtTwoWidthsIsAskedForOnceAndArrivesByPicture() async {
        let hub = ColorsHub.knowing(gold)
        let colors = store(hub)
        var updates = colors.updates.makeAsyncIterator()
        await colors.request(["/v1/img/tmdb/w342/x.jpg", "/v1/img/tmdb/w1280/x.jpg"])
        await colors.request(["/v1/img/jf/i/Primary?tag=t&w=360", "/v1/img/jf/i/Primary?w=540&tag=t"])
        await colors.flush()
        #expect(await hub.asked == [["/v1/img/tmdb/w342/x.jpg", "/v1/img/jf/i/Primary?tag=t&w=360"]])
        #expect(await updates.next() == ["tmdb/x.jpg": gold, "jf/i/Primary/t": gold])
        #expect(await colors.palette("/v1/img/tmdb/w1280/x.jpg") == gold)
        #expect(await colors.palette("/v1/img/jf/i/Primary?w=540&tag=t") == gold)
        // Another tag is another picture.
        #expect(await colors.palette("/v1/img/jf/i/Primary?tag=u&w=360") == nil)
    }

    @Test func aDamagedFileCostsOneRoundOfAskingAgain() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("hubkit-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: folder) }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent(ArtworkColorStore.fileName)
        try Data("[[\"/v1/img/a\",\"not a colour\"]".utf8).write(to: file)

        let hub = ColorsHub.knowing(gold)
        let colors = store(hub, file: file)
        #expect(await colors.restore().isEmpty)
        await colors.request(["/v1/img/a"])
        await colors.flush()
        #expect(await hub.asked == [["/v1/img/a"]])
    }

    @Test func onItsOwnItWaitsAMomentAndSendsTheBatch() async throws {
        let hub = ColorsHub.knowing(gold)
        let colors = ArtworkColorStore(file: nil, fetch: { try await hub.fetch($0) })
        await colors.request(["/v1/img/a"])
        await colors.request(["/v1/img/b"])
        var waited = 0
        while await hub.asked.isEmpty, waited < 200 {
            try await Task.sleep(for: .milliseconds(10))
            waited += 1
        }
        try await Task.sleep(for: .milliseconds(100))
        #expect(await hub.asked == [["/v1/img/a", "/v1/img/b"]])
        #expect(await colors.palette("/v1/img/a") == gold)
    }
}
