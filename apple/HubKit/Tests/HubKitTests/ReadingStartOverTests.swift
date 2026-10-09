import Foundation
import Testing
@testable import HubKit

/// Start over (#60): the drop rule a device applies once per stamp, what it
/// says, and the fields the hub sends and is sent. Android's
/// `ReadingResetsTest` and `ReadingStartOverTest`.
struct ReadingStartOverTests {
    private func store() -> ReadingCheckpointStore {
        ReadingCheckpointStore(root: FileManager.default.temporaryDirectory
            .appendingPathComponent("start-over-\(UUID().uuidString)", isDirectory: true))
    }

    private func key(_ scope: String, _ work: String, _ source: String, _ kind: String) -> ReadingCheckpointKey {
        ReadingCheckpointKey(scope: scope, workId: work, sourceItemId: source, kind: kind)
    }

    // MARK: The drop rule

    @Test func aStampNotYetAppliedDropsEveryPlaceOfThatBookOnlyOnce() throws {
        let store = store()
        let ebook = key("me", "dune", "1", "epub")
        let audio = key("me", "dune", "2", AudioPlace.kind)
        let other = key("me", "alloy", "1", "epub")
        let elsewhere = key("someone", "dune", "1", "epub")
        for each in [ebook, audio, other, elsewhere] {
            try store.save(each, ReadingLocation(pageIndex: 3), now: 1)
        }
        let resets = ReadingResets(ledger: MemoryResetLedger(), store: store)
        var dropped = 0
        #expect(resets.apply(scope: "me", workId: "dune", resetAt: 500) { dropped += 1 })
        #expect(try store.read(ebook) == nil && store.read(audio) == nil, "every kind of the book goes, the outbox with it")
        #expect(try store.read(other) != nil, "another book stays")
        #expect(try store.read(elsewhere) != nil, "another profile's place stays")
        #expect(store.pending(scope: "me").map(\.key) == [other])
        #expect(dropped == 1 && resets.seen(scope: "me", workId: "dune") == 500)

        // Read after it, a place stays: the same stamp is no news.
        try store.save(ebook, ReadingLocation(pageIndex: 1), now: 2)
        #expect(!resets.apply(scope: "me", workId: "dune", resetAt: 500) { dropped += 1 })
        #expect(!resets.apply(scope: "me", workId: "dune", resetAt: 400) { dropped += 1 }, "nor an older one")
        #expect(!resets.apply(scope: "me", workId: "dune", resetAt: 0) { dropped += 1 }, "nor none")
        #expect(try store.read(ebook)?.local?.pageIndex == 1 && dropped == 1)
        // A newer one is news again.
        #expect(resets.apply(scope: "me", workId: "dune", resetAt: 900))
        #expect(try store.read(ebook) == nil)
        #expect(resets.seen(scope: "someone", workId: "dune") == 0, "each profile applies its own")
    }

    @Test func theStampsAreKeptInTheDefaults() throws {
        let defaults = try #require(UserDefaults(suiteName: "start-over-\(UUID().uuidString)"))
        let one = ReadingResets(ledger: DefaultsResetLedger(defaults: defaults), store: store())
        one.apply(scope: "me", workId: "dune", resetAt: 1_791_000_000_123)
        let again = ReadingResets(ledger: DefaultsResetLedger(defaults: defaults), store: store())
        #expect(again.seen(scope: "me", workId: "dune") == 1_791_000_000_123)
    }

    @Test func aDeviceKnowsWhetherItKeepsAPlace() throws {
        let store = store()
        #expect(!store.hasPlace(scope: "me", workId: "dune"))
        try store.save(key("me", "dune", "1", "epub"), ReadingLocation(pageIndex: 2), now: 1)
        #expect(store.hasPlace(scope: "me", workId: "dune"))
        #expect(!store.hasPlace(scope: "someone", workId: "dune"))
    }

    @Test func aKeptPageListOpensAtItsFirstPageOnceStartedOver() throws {
        let cache = ReadingManifestCache(root: FileManager.default.temporaryDirectory
            .appendingPathComponent("manifests-\(UUID().uuidString)", isDirectory: true))
        let ff = key("me", "rw_ff", "ff-51", "pages")
        let other = key("me", "rw_aaf", "aaf-7", "pages")
        try cache.save(ff, answer: Data(#"{"workId":"rw_ff","sourceItemId":"ff-51","pageCount":24,"currentPage":9}"#.utf8))
        try cache.save(other, answer: Data(#"{"workId":"rw_aaf","sourceItemId":"aaf-7","pageCount":36,"currentPage":4}"#.utf8))
        cache.dropPlace(workId: "rw_ff")
        #expect(cache.read(ff)?.currentPage == 0)
        #expect(cache.read(ff)?.pageCount == 24, "the rest of the answer stays")
        #expect(cache.read(other)?.currentPage == 4)
    }

    // MARK: What it says

    private let dune = ReadingWork(id: "dune", title: "Dune", availability: ["ebook", "audiobook", "readaloud"])

    @Test func itIsOfferedWithAPlaceOrAFinish() {
        #expect(!ReadingStartOver.offered(hasPlace: false, finished: false))
        #expect(ReadingStartOver.offered(hasPlace: true, finished: false))
        #expect(ReadingStartOver.offered(hasPlace: false, finished: true))
        #expect(!ReadingStartOver.hasPlace(dune, kept: false))
        #expect(ReadingStartOver.hasPlace(dune, kept: true), "a place kept here, even unsent")
        var started = dune
        started.progress = ReadingProgress(percentage: 0.4, completed: false)
        #expect(ReadingStartOver.hasPlace(started, kept: false))
        started.progress = ReadingProgress(percentage: 0, completed: true)
        #expect(ReadingStartOver.hasPlace(started, kept: false))
        var run = ReadingWork(id: "ff", kind: "comic", title: "Fantastic Four")
        run.continueAt = ReadingContinue(workId: "ff", sourceItemId: "ff-51", percentage: 0)
        #expect(ReadingStartOver.hasPlace(run, kept: false), "a comic run being read")
    }

    @Test func itAsksInTheWordsOfTheBooksFormatsTheHarmlessAnswerFirst() {
        #expect(ReadingStartOver.confirmTitle(dune) == "Start Dune over?")
        #expect(ReadingStartOver.confirmDetail(dune)
            == "Your place in the ebook, audiobook and read along is forgotten on every device. Your rating, notes and bookmarks stay.")
        #expect(ReadingStartOver.confirmDetail(ReadingWork(title: "Recursion", availability: ["ebook"]))
            == "Your place in the ebook is forgotten on every device. Your rating, notes and bookmarks stay.")
        #expect(ReadingStartOver.confirmDetail(ReadingWork(kind: "comic", title: "FF"))
            == "Your place is forgotten and every issue is unread again, on every device. Your lists stay.")
        #expect(ReadingStartOver.keep == "Keep my place" && ReadingStartOver.action == "Start over")
        #expect(ReadingStartOver.failed("The hub is down") == "Start over could not be done · The hub is down")
    }

    // MARK: On the wire

    @Test func everyReadOfAPlaceSaysWhenTheBookWasStartedOver() throws {
        let work = try JSONDecoder().decode(ReadingWork.self, from: Data(#"{"id":"dune","title":"Dune","resetAt":1791000000123}"#.utf8))
        #expect(work.resetAt == 1_791_000_000_123)
        #expect(try JSONDecoder().decode(ReadingWork.self, from: Data(#"{"id":"dune"}"#.utf8)).resetAt == 0, "an older hub")
        #expect(EpubPosition.decode(Data(#"{"locator":null,"resetAt":42}"#.utf8))?.resetAt == 42)
        let audio = try JSONDecoder().decode(ReadingAudioPositionResponse.self, from: Data(#"{"position":null,"resetAt":43}"#.utf8))
        #expect(audio.resetAt == 43)
        let pages = try JSONDecoder().decode(ReadingPublicationManifest.self, from: Data(#"{"pageCount":3,"resetAt":44}"#.utf8))
        #expect(pages.resetAt == 44)
        let answer = try JSONDecoder().decode(ReadingStartOverResponse.self,
                                              from: Data(#"{"ok":true,"action":"start_over","workId":"dune","resetAt":45,"you":null}"#.utf8))
        #expect(answer.resetAt == 45 && answer.you == nil)
        #expect(HubEndpoints.readingStartOver("rw dune").path == "/v1/reading/works/rw%20dune/start-over")
    }

    @Test func everyWriteSaysTheStartOverItApplied() throws {
        func object(_ data: Data) -> [String: Any] { (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:] }
        let locator = #"{"href":"a.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}"#
        #expect(object(EpubPositionBody(locator: locator, timestamp: 1, expectedLocator: nil, resetSeen: 77).encoded())["resetSeen"]
            as? Int64 == 77)
        #expect(object(EpubPositionBody(locator: locator, timestamp: 1, expectedLocator: nil).encoded())["resetSeen"] == nil,
                "none applied yet sends nothing")
        let pages = try JSONEncoder().encode(ReadingPublicationProgressBody(pageIndex: 3, expectedPage: 2, resetSeen: 78))
        #expect(object(pages)["resetSeen"] as? Int64 == 78)
        let checkpoint = ReadingCheckpoint(key: key("me", "dune", "2", AudioPlace.kind),
                                           local: AudioPlace("t1", 5_000).location(), baseKnown: false)
        guard case .object(let fields)? = AudioPlace.body(checkpoint, resetSeen: 79) else {
            Issue.record("no listening write")
            return
        }
        #expect(fields["resetSeen"] == .int(79))
    }

    // MARK: The demo hub

    @Test func theDemoHubStartsABookOverAndRefusesAWriteFromBefore() throws {
        // Red Rising, which no other test reads: the demo hub is one for every test of the run.
        let workId = "rw_demo_rr1"
        func call(_ method: String, _ path: String, _ body: [String: Any]? = nil) -> (Int, [String: Any]) {
            let data = body.flatMap { try? JSONSerialization.data(withJSONObject: $0) }
            let answer = DemoStartOver.answer(method: method, path: path, body: data)
                ?? DemoBooks.answer(method: method, path: path, query: "", body: data)
                ?? DemoTransport.Answer(404, "{}")
            return (answer.status, (try? JSONSerialization.jsonObject(with: answer.body)) as? [String: Any] ?? [:])
        }
        let book = try #require(DemoBooks.book(workId))
        let source = try #require(book.sourceItemIds.sorted().first)
        let position = "/v1/reading/works/\(workId)/publications/\(source)/position"
        let (status, started) = call("POST", "/v1/reading/works/\(workId)/start-over")
        #expect(status == 200)
        let at = try #require((started["resetAt"] as? NSNumber)?.int64Value)
        let (_, read) = call("GET", position)
        #expect(read["locator"] is NSNull, "no place after it")
        #expect((read["resetAt"] as? NSNumber)?.int64Value == at)
        let locator: [String: Any] = ["href": "OEBPS/chapter-01.xhtml", "type": "application/xhtml+xml", "locations": ["progression": 0.2]]
        let (refused, error) = call("POST", position, ["locator": locator, "checkBase": true, "expectedLocator": NSNull(),
                                                       "resetSeen": at - 1])
        #expect(refused == 409 && (error["error"] as? [String: Any])?["code"] as? String == ReadingResets.code)
        let (saved, _) = call("POST", position, ["locator": locator, "checkBase": true, "expectedLocator": NSNull(), "resetSeen": at])
        #expect(saved == 200, "a write from after it is kept")
    }
}
