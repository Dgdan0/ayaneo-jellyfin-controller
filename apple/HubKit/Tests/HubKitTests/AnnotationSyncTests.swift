import Foundation
import Testing
@testable import HubKit

/// One pass with the hub and the file it keeps (#62): the Pocket's
/// `AnnotationSyncTest`, case for case, then the same against the demo hub,
/// which keeps highlights as the hub does.
struct AnnotationSyncTests {
    private static func note(_ id: String, _ color: String = "yellow", at: Int64 = 100, deleted: Bool = false,
                             synced: Int64 = 0) -> ReadingAnnotation {
        ReadingAnnotation(id: id, color: color, document: "OEBPS/chapter-1.xhtml",
                          quote: AnnotationQuote(before: "a ", highlight: "bird", after: "."), createdAt: at, updatedAt: at,
                          deleted: deleted, syncedAt: synced)
    }

    /// A hub that keeps what it is sent, last write wins on `updatedAt`, and says when it stored each version.
    final class FakeHub: AnnotationRemote, @unchecked Sendable {
        private let lock = NSLock()
        var held: [String: ReadingAnnotation] = [:]
        var order: [String] = []
        var clock: Int64 = 1_000
        var failWith: HubFailure?
        var refuse: Set<String> = []
        var writes: [String] = []
        var listedSince: Int64?? = .none

        func seed(_ annotation: ReadingAnnotation) {
            lock.withLock {
                if held[annotation.id] == nil { order.append(annotation.id) }
                held[annotation.id] = annotation
            }
        }

        private func store(_ incoming: ReadingAnnotation) -> ReadingAnnotationWritten {
            if let current = held[incoming.id], incoming.updatedAt <= current.updatedAt {
                return ReadingAnnotationWritten(workId: "w", annotation: current, applied: false)
            }
            var stored = incoming
            clock += 1
            stored.syncedAt = clock
            if held[incoming.id] == nil { order.append(incoming.id) }
            held[incoming.id] = stored
            return ReadingAnnotationWritten(workId: "w", annotation: stored, applied: true)
        }

        func annotations(workId: String, since: Int64?) async throws(HubFailure) -> ReadingAnnotationsResponse {
            if let failWith { throw failWith }
            return lock.withLock {
                listedSince = .some(since)
                let list = order.compactMap { held[$0] }.filter { since == nil ? !$0.deleted : $0.syncedAt > since! }
                return ReadingAnnotationsResponse(workId: workId, annotations: list, serverTime: clock)
            }
        }

        func save(workId: String, annotation: ReadingAnnotation) async throws(HubFailure) -> ReadingAnnotationWritten {
            if let failWith { throw failWith }
            if refuse.contains(annotation.id) { throw HubFailure(.badResponse, status: 400, code: "invalid_request") }
            return lock.withLock {
                writes.append("put \(annotation.id)")
                return store(annotation)
            }
        }

        func delete(workId: String, id: String, updatedAt: Int64) async throws(HubFailure) -> ReadingAnnotationWritten {
            if let failWith { throw failWith }
            return lock.withLock {
                writes.append("delete \(id)")
                var tomb = held[id] ?? ReadingAnnotation(id: id)
                tomb.deleted = true
                tomb.note = ""
                tomb.updatedAt = updatedAt
                return store(tomb)
            }
        }
    }

    final class BookLedger: AnnotationLedger {
        var book: AnnotationBook

        init(_ book: AnnotationBook = AnnotationBook()) {
            self.book = book
        }

        func pending() -> [ReadingAnnotation] { book.pending() }
        func sent(_ id: String, sent: ReadingAnnotation, held: ReadingAnnotation) { book.sent(id, sent: sent, held: held) }
        func refused(_ id: String) { book.refused(id) }
        func cursor() -> Int64 { book.cursor }
        func merged(_ remote: [ReadingAnnotation]) { book.merge(remote) }
    }

    private func run(_ hub: any AnnotationRemote, _ ledger: BookLedger) async -> AnnotationSync.Result {
        await AnnotationSync(remote: hub).run(workId: "w", ledger: ledger)
    }

    @Test func theOutboxGoesOutOldestFirstAndTheHubsAnswerIsWhatWeKeep() async {
        let hub = FakeHub()
        let ledger = BookLedger()
        ledger.book.put(Self.note("an_b"), now: 300)
        ledger.book.put(Self.note("an_a"), now: 100)
        ledger.book.remove("an_a", now: 400)
        #expect(await run(hub, ledger) == AnnotationSync.Result())
        #expect(hub.writes == ["put an_b", "delete an_a"])
        #expect(ledger.book.pendingIds.isEmpty)
        #expect(ledger.book["an_a"]?.deleted == true)
        #expect(ledger.book["an_b"]?.syncedAt == hub.held["an_b"]?.syncedAt)
    }

    @Test func whatTheOtherDevicesDidComesAfterTheCursorAndTheCursorMovesOn() async {
        let hub = FakeHub()
        hub.seed(Self.note("an_x", "pink", at: 50, synced: 900))
        let ledger = BookLedger()
        _ = await run(hub, ledger)
        #expect(hub.listedSince == .some(nil), "the first read has no cursor")
        #expect(ledger.book.live.map(\.id) == ["an_x"])
        #expect(ledger.book.cursor == 900)
        // Another device deletes it, and a new one appears; this one asks only for what is after what it read.
        hub.seed(Self.note("an_x", at: 500, deleted: true, synced: 1_100))
        hub.seed(Self.note("an_y", at: 40, synced: 1_101))
        _ = await run(hub, ledger)
        #expect(hub.listedSince == .some(900))
        #expect(ledger.book.live.map(\.id) == ["an_y"])
    }

    @Test func anEditMadeOfflineAnHourAgoIsStillSentAndStillReachesTheOtherDevice() async {
        let hub = FakeHub()
        hub.seed(Self.note("an_seed", at: 5_000, synced: 900))
        let phone = BookLedger()
        _ = await run(hub, phone)
        let ipad = BookLedger()
        _ = await run(hub, ipad)
        #expect(ipad.book.cursor == 900)
        // The iPad has read up to 900. The phone, which was offline, now sends an edit it made long before that.
        phone.book.put(Self.note("an_1", at: 10), now: 20)
        #expect(phone.book["an_1"]?.updatedAt == 20)
        _ = await run(hub, phone)
        _ = await run(hub, ipad)
        #expect(Set(ipad.book.live.map(\.id)) == ["an_1", "an_seed"])
    }

    @Test func anEditThatLostAtTheHubTakesWhatWonAndStopsWaiting() async {
        let hub = FakeHub()
        hub.seed(Self.note("an_1", "green", at: 900, synced: 1_001))
        let ledger = BookLedger(AnnotationBook([Self.note("an_1", "yellow", at: 100)]))
        ledger.book.put(Self.note("an_1", "blue"), now: 200)
        _ = await run(hub, ledger)
        #expect(ledger.book["an_1"]?.color == "green")
        #expect(ledger.book.pendingIds.isEmpty)
    }

    @Test func noNetworkLeavesTheOutboxAndAsksForAnotherTry() async {
        let hub = FakeHub()
        hub.failWith = HubFailure(.noNetwork)
        let ledger = BookLedger()
        ledger.book.put(Self.note("an_1"), now: 100)
        #expect(await run(hub, ledger) == AnnotationSync.Result(retry: true, stopped: true))
        #expect(ledger.book.pendingIds == ["an_1"])
        #expect(ledger.book.live.count == 1)
    }

    @Test func aHubThatDoesNotKnowTheRoutesIsLeftAloneUntilTheNextTime() async {
        let hub = FakeHub()
        hub.failWith = HubFailure(.notFound, status: 404)
        let ledger = BookLedger()
        ledger.book.put(Self.note("an_1"), now: 100)
        #expect(await run(hub, ledger) == AnnotationSync.Result(retry: false, stopped: true))
        #expect(ledger.book.pendingIds == ["an_1"])
    }

    @Test func anEditTheHubRefusesIsNotSentAgainAndTheRestGoOn() async {
        let hub = FakeHub()
        hub.refuse = ["an_bad"]
        let ledger = BookLedger()
        ledger.book.put(Self.note("an_bad"), now: 100)
        ledger.book.put(Self.note("an_ok"), now: 200)
        #expect(await run(hub, ledger) == AnnotationSync.Result())
        #expect(ledger.book.pendingIds.isEmpty)
        #expect(Array(hub.held.keys) == ["an_ok"])
        #expect(ledger.book.live.count == 2)
    }

    // MARK: The file

    private func folder() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("annotations-\(UUID().uuidString)", isDirectory: true)
    }

    @Test func theStoreKeepsABookAcrossARestartAndNothingOfAnotherProfile() throws {
        let root = folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = AnnotationStore(root: root)
        var book = AnnotationBook()
        book.put(Self.note("an_1"), now: 100)
        try store.save(scope: "profile-a", workId: "w", book: book)
        var again = try AnnotationStore(root: root).load(scope: "profile-a", workId: "w")
        #expect(again.live.map(\.id) == ["an_1"])
        #expect(again.pendingIds == ["an_1"])
        #expect(try store.load(scope: "profile-b", workId: "w").all.isEmpty)
        #expect(try store.load(scope: "profile-a", workId: "other").all.isEmpty)
        #expect(store.pendingWorks(scope: "profile-a") == ["w"])
        #expect(store.pendingWorks(scope: "profile-b").isEmpty)
        var held = try #require(again["an_1"])
        held.syncedAt = 5
        again.sent("an_1", sent: try #require(again["an_1"]), held: held)
        try store.save(scope: "profile-a", workId: "w", book: again)
        #expect(store.pendingWorks(scope: "profile-a").isEmpty)
        #expect(store.drop(scope: "profile-a", workId: "w"))
        #expect(try store.load(scope: "profile-a", workId: "w").all.isEmpty)
    }

    @Test func aFileThatCannotBeReadIsAnErrorAndIsNeverStartedOverQuietly() throws {
        let root = folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = AnnotationStore(root: root)
        try store.save(scope: "profile-a", workId: "w", book: AnnotationBook([Self.note("an_1")]))
        let file = try #require(try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
            .first { $0.pathExtension == "json" })
        try Data("{not json".utf8).write(to: file)
        #expect(throws: AnnotationStore.Unreadable.self) { try store.load(scope: "profile-a", workId: "w") }
    }

    // MARK: The demo hub, as the hub keeps them

    private func demo() -> HubClient {
        HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                  screens: DemoTransport(), sleep: { _ in })
    }

    @Test func twoDevicesAgreeThroughTheDemoHub() async throws {
        let remote = HubAnnotationRemote(hub: demo())
        let work = "rw_demo_rr6"
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        let phone = BookLedger()
        let made = phone.book.put(ReadingAnnotation(id: AnnotationIds.new(), color: "pink", note: "the eclipse",
                                                    document: "OEBPS/chapter-1.xhtml",
                                                    quote: AnnotationQuote(before: "a ", highlight: "light bringer", after: ".")),
                                  now: now - 1_000)
        #expect(await AnnotationSync(remote: remote).run(workId: work, ledger: phone) == AnnotationSync.Result())
        #expect(phone.book.pendingIds.isEmpty && (phone.book[made.id]?.syncedAt ?? 0) > 0)
        let ipad = BookLedger()
        _ = await AnnotationSync(remote: remote).run(workId: work, ledger: ipad)
        #expect(ipad.book[made.id]?.note == "the eclipse")
        // The iPad recolours it; the phone, a delete it made earlier offline, loses to the newer edit.
        ipad.book.put(try #require(ipad.book[made.id]).recoloured("green"), now: now)
        _ = await AnnotationSync(remote: remote).run(workId: work, ledger: ipad)
        phone.book.remove(made.id, now: now - 500)
        _ = await AnnotationSync(remote: remote).run(workId: work, ledger: phone)
        #expect(phone.book[made.id]?.color == "green" && phone.book[made.id]?.deleted == false)
        // Taken away on the iPad: a tombstone the phone learns of.
        ipad.book.remove(made.id, now: now + 1_000)
        _ = await AnnotationSync(remote: remote).run(workId: work, ledger: ipad)
        _ = await AnnotationSync(remote: remote).run(workId: work, ledger: phone)
        #expect(phone.book.live.allSatisfy { $0.id != made.id })
    }

    @Test func theDemoHubRefusesWhatTheHubRefuses() async throws {
        let hub = demo()
        let work = "rw_demo_rr6"
        func put(_ annotation: ReadingAnnotation) async -> HubFailure? {
            do throws(HubFailure) {
                _ = try await hub.fetch(HubEndpoints.saveReadingAnnotation(workId: work, annotation), as: ReadingAnnotationWritten.self)
                return nil
            } catch { return error }
        }
        let good = ReadingAnnotation(id: AnnotationIds.new(), document: "OEBPS/a.xhtml", quote: AnnotationQuote(highlight: "bird"))
        #expect(await put(good) == nil)
        var purple = good
        purple.color = "purple"
        #expect(await put(purple)?.code == "invalid_request")
        var empty = good
        empty.quote = AnnotationQuote(highlight: "  ")
        #expect(await put(empty)?.code == "invalid_request")
        var outside = good
        outside.document = "../secret.xhtml"
        #expect(await put(outside)?.code == "invalid_request")
        let unknown = ReadingAnnotation(id: AnnotationIds.new(), document: "a.xhtml", quote: AnnotationQuote(highlight: "x"))
        do throws(HubFailure) {
            _ = try await hub.fetch(HubEndpoints.saveReadingAnnotation(workId: "rw_nothing", unknown), as: ReadingAnnotationWritten.self)
            Issue.record("a book the hub does not know took a highlight")
        } catch {
            #expect(error.kind == .notFound)
        }
    }
}

private extension ReadingAnnotation {
    func recoloured(_ color: String) -> ReadingAnnotation {
        var next = self
        next.color = color
        return next
    }
}
