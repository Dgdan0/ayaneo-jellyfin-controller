import Foundation
import Testing
@testable import HubKit

/// The ebook reader's routes and its place (#25, phase 4): the paths, the
/// position as the hub writes it and the body it takes, the demo hub's EPUBs
/// and places, the keepers, the books kept on the device and the bookmarks.
/// Each test that writes a demo place uses a book of its own, since tests run
/// side by side.
struct BookRoutesTests {
    private let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                                screens: DemoTransport(), sleep: { _ in })

    // MARK: Routes

    @Test func theBookAndItsPlaceAreAskedForByTheirIds() {
        let file = HubEndpoints.readingEpubFile(workId: "rw_9f2", sourceItemId: "12")
        #expect(file.path == "/v1/reading/works/rw_9f2/publications/12/file")
        #expect(file.method == .get && file.slow)
        #expect(HubEndpoints.readingEpubFile(workId: "rw_9f2", sourceItemId: "12", format: "readaloud", omitAudio: true).path
                == "/v1/reading/works/rw_9f2/publications/12/file?format=readaloud&audio=omit")
        #expect(HubEndpoints.readingEpubPosition(workId: "rw 9", sourceItemId: "a/b").path
                == "/v1/reading/works/rw%209/publications/a%2Fb/position")
    }

    @Test func thePlaceIsSentWithThePlaceTheHubHad() throws {
        let body = EpubPositionBody(locator: #"{"href":"c4.xhtml","locations":{"progression":0.4}}"#, timestamp: 1_700_000_000_000,
                                    expectedLocator: #"{"href":"c3.xhtml","locations":{"progression":1}}"#)
        let save = HubEndpoints.saveReadingEpubPosition(workId: "rw_book", sourceItemId: "12", body)
        #expect(save.path == "/v1/reading/works/rw_book/publications/12/position")
        #expect(save.method == .post && !save.idempotent)
        let sent = try #require(try save.body.flatMap { try JSONSerialization.jsonObject(with: $0) } as? NSDictionary)
        let expected = try JSONSerialization.jsonObject(with: Data(#"""
            {"checkBase":true,"expectedLocator":{"href":"c3.xhtml","locations":{"progression":1}},
             "locator":{"href":"c4.xhtml","locations":{"progression":0.4}},"timestamp":1700000000000}
            """#.utf8)) as? NSDictionary
        #expect(sent == expected)
        // A book with no place yet: the hub is told it expects none.
        let first = EpubPositionBody(locator: #"{"href":"c1.xhtml","locations":{}}"#, timestamp: 0, expectedLocator: nil)
        #expect(String(decoding: first.encoded(), as: UTF8.self) == #"{"checkBase":true,"expectedLocator":null,"locator":{"href":"c1.xhtml","locations":{}}}"#)
    }

    @Test func aReadiumLocatorComesBackWithEverythingItCarried() throws {
        // Android's EpubReaderStateTest: optional text and location fields round-trip unchanged.
        let answer = #"""
        {"workId":"rw_book","sourceItemId":"12","timestamp":1700000000000,
         "locator":{"href":"chapter-4.xhtml","type":"application/xhtml+xml","title":"Chapter 4",
            "locations":{"fragments":["paragraph-3"],"progression":0.4,"totalProgression":0.32,"position":44},
            "text":{"before":"red ","highlight":"rising","after":" dawn"},
            "displayInfo":{"resourceScreenIndex":2}}}
        """#
        let position = try #require(EpubPosition.decode(Data(answer.utf8)))
        #expect(position.timestamp == 1_700_000_000_000)
        let locator = try #require(position.locator)
        #expect(BookLocator.href(locator) == "chapter-4.xhtml")
        let sent = EpubPositionBody(locator: locator, timestamp: position.timestamp, expectedLocator: locator).encoded()
        let fields = try #require(try JSONSerialization.jsonObject(with: sent) as? [String: Any])
        let original = try #require(try JSONSerialization.jsonObject(with: Data(answer.utf8)) as? [String: Any])
        #expect((fields["locator"] as? NSDictionary) == (original["locator"] as? NSDictionary))
        // No place yet is nil, not an empty one.
        #expect(EpubPosition.decode(Data(#"{"workId":"w","sourceItemId":"1","locator":null}"#.utf8))?.locator == nil)
        #expect(EpubPosition.decode(Data("[]".utf8)) == nil)
    }

    @Test func aPlaceIsNamedComparedAndCheckedAsTheHubDoes() {
        let locator = #"{"href":"OEBPS/c3.xhtml","title":"Chapter 3","locations":{"progression":0.25,"totalProgression":0.41}}"#
        #expect(BookLocator.label(locator) == "Chapter 3 · 41%")
        #expect(BookLocator.label(#"{"href":"c.xhtml","locations":{}}"#) == "c.xhtml")
        #expect(BookLocator.anchor(locator) == "OEBPS/c3.xhtml#2500")
        #expect(BookLocator.anchor(#"{"href":"c.xhtml","locations":{"fragments":["p-3"],"progression":0.5}}"#) == #"c.xhtml#["p-3"]"#)
        #expect(BookLocator.anchor(#"{"href":"c.xhtml","locations":{}}"#) == "c.xhtml#start")
        #expect(BookLocator.same(locator, #"{"locations":{"totalProgression":0.41,"progression":0.25},"title":"Chapter 3","href":"OEBPS/c3.xhtml"}"#))
        #expect(!BookLocator.same(locator, nil))
        #expect(BookLocator.same(nil, "null"))
        #expect(BookLocator.valid(locator))
        #expect(!BookLocator.valid(#"{"href":" ","locations":{}}"#))
        #expect(!BookLocator.valid(#"{"href":"c.xhtml"}"#))
    }

    // MARK: The demo's EPUB

    @Test func theDemoBookIsAStoredZipWithItsMimetypeFirst() throws {
        let epub = DemoEpub.make(title: "Recursion", author: "Blake Crouch", identifier: "rw_demo_recursion")
        #expect(epub == DemoEpub.make(title: "Recursion", author: "Blake Crouch", identifier: "rw_demo_recursion"))
        // The first entry is `mimetype`, stored, with no extra field: what a reader checks first.
        #expect(Array(epub.prefix(4)) == [0x50, 0x4B, 0x03, 0x04])
        #expect(String(decoding: epub[30..<38], as: UTF8.self) == "mimetype")
        #expect(String(decoding: epub[38..<58], as: UTF8.self) == "application/epub+zip")
        let entries = try Self.entries(epub)
        #expect(entries.map(\.name).prefix(5) == ["mimetype", "META-INF/container.xml", "OEBPS/content.opf", "OEBPS/nav.xhtml",
                                                  "OEBPS/style.css"])
        for entry in entries { #expect(CRC32.checksum(entry.data) == entry.crc, "\(entry.name)") }
        let files = Dictionary(uniqueKeysWithValues: entries.map { ($0.name, String(decoding: $0.data, as: UTF8.self)) })
        #expect(files["META-INF/container.xml"]?.contains(#"full-path="OEBPS/content.opf""#) == true)
        #expect(files["OEBPS/content.opf"]?.contains("<dc:title>Recursion</dc:title>") == true)
        #expect(files["OEBPS/content.opf"]?.contains(#"properties="nav""#) == true)
        #expect(files["OEBPS/nav.xhtml"]?.contains(#"<a href="chapter-08.xhtml#part-2">Later</a>"#) == true)
        // The footnote, the endnote, the link on and the link out.
        let one = try #require(files["OEBPS/chapter-01.xhtml"])
        #expect(one.contains(##"<a epub:type="noteref" href="#note-1" id="ref-1">1</a>"##))
        #expect(one.contains(#"<aside epub:type="footnote" id="note-1">"#))
        #expect(files["OEBPS/chapter-02.xhtml"]?.contains(#"href="chapter-05.xhtml""#) == true)
        #expect(files["OEBPS/chapter-03.xhtml"]?.contains(#"href="notes.xhtml#note-2""#) == true)
        #expect(files["OEBPS/notes.xhtml"]?.contains(#"id="note-2""#) == true)
        #expect(files["OEBPS/chapter-04.xhtml"]?.contains(#"href="https://example.com/""#) == true)
        // Eight chapters of 9 to 18 KB, about a hundred and twenty positions in all.
        let chapters = DemoEpub.chapters(identifier: "rw_demo_recursion").filter { $0.file.hasPrefix("chapter-") }
        #expect(chapters.count == 8)
        #expect(chapters.allSatisfy { (9_000...19_500).contains($0.bytes) })
    }

    @Test func aBookAlreadyBeingReadOpensWhereTheBooksDemoSaysItIs() throws {
        let locator = DemoEpub.locator(identifier: "rw_demo_rr6", at: 0.4946)
        let href = try #require(locator["href"] as? String)
        #expect(href.hasPrefix("OEBPS/chapter-"))
        let locations = try #require(locator["locations"] as? [String: Any])
        #expect(locations["totalProgression"] as? Double == 0.4946)
        let progression = try #require(locations["progression"] as? Double)
        #expect((0...1).contains(progression))
    }

    // MARK: The demo hub's routes

    @Test func theDemoHubSendsTheBookAndKeepsItsPlaceAsTheHubDoes() async throws {
        let workId = "rw_demo_darkmatter", sourceItemId = "demo-dm"
        let epub = try await hub.data(HubEndpoints.readingEpubFile(workId: workId, sourceItemId: sourceItemId))
        #expect(Array(epub.prefix(4)) == [0x50, 0x4B, 0x03, 0x04])
        // Dark Matter is 3% read in the Books demo, so it has a place already.
        let before = try #require(EpubPosition.decode(try await hub.data(
            HubEndpoints.readingEpubPosition(workId: workId, sourceItemId: sourceItemId))))
        let start = try #require(before.locator)
        #expect(BookLocator.href(start)?.hasPrefix("OEBPS/") == true)
        let next = #"{"href":"OEBPS/chapter-02.xhtml","locations":{"progression":0.5,"totalProgression":0.2},"type":"application/xhtml+xml"}"#
        try await hub.send(HubEndpoints.saveReadingEpubPosition(workId: workId, sourceItemId: sourceItemId,
                                                                EpubPositionBody(locator: next, timestamp: 1, expectedLocator: start)))
        let after = EpubPosition.decode(try await hub.data(HubEndpoints.readingEpubPosition(workId: workId, sourceItemId: sourceItemId)))
        #expect(BookLocator.same(after?.locator, next))
        // Based on the place it had before: another device's would be lost, so it is refused.
        do throws(HubFailure) {
            try await hub.send(HubEndpoints.saveReadingEpubPosition(workId: workId, sourceItemId: sourceItemId,
                                                                    EpubPositionBody(locator: start, timestamp: 2, expectedLocator: start)))
            Issue.record("A stale place was taken")
        } catch {
            #expect(error.status == 409)
        }
        // A publication of another book, and an edition the demo has no ebook of.
        do throws(HubFailure) {
            _ = try await hub.data(HubEndpoints.readingEpubFile(workId: workId, sourceItemId: "someone-else"))
            Issue.record("Another book's publication was sent")
        } catch {
            #expect(error.kind == .notFound)
        }
        do throws(HubFailure) {
            _ = try await hub.data(HubEndpoints.readingEpubFile(workId: "rw_demo_alloy", sourceItemId: "demo-alloy"))
            Issue.record("An audiobook was sent as an EPUB")
        } catch {
            #expect(error.kind == .notFound)
        }
    }

    @Test func aSeriesBookOpensUnderTheIdItsSeriesListsItBy() async throws {
        let epub = try await hub.data(HubEndpoints.readingEpubFile(workId: "rw_demo_rr2", sourceItemId: "rr2"))
        #expect(Array(epub.prefix(2)) == [0x50, 0x4B])
        let edition = try await hub.data(HubEndpoints.readingEpubFile(workId: "rw_demo_rr2", sourceItemId: "demo-rw_demo_rr2"))
        #expect(edition == epub)
    }

    // MARK: The keeper, through the reading outbox

    private func makeKeeper(_ workId: String, _ sourceItemId: String, store: ReadingCheckpointStore) -> CheckpointBookPlaces {
        CheckpointBookPlaces(hub: hub, store: store,
                             key: CheckpointBookPlaces.key(address: DemoTransport.address, userId: "", workId: workId,
                                                           sourceItemId: sourceItemId),
                             now: { 1_000 })
    }

    @Test func theKeeperOpensAtTheHubsPlaceAndSendsThePlaceReachedWithTheOneItHad() async throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let workId = "rw_demo_recursion", sourceItemId = "demo-rw_demo_recursion"
        let keeper = makeKeeper(workId, sourceItemId, store: ReadingCheckpointStore(root: root))
        #expect(await keeper.opening() == .at(nil))
        let place = #"{"href":"OEBPS/chapter-01.xhtml","locations":{"progression":0.3,"totalProgression":0.05},"type":"application/xhtml+xml"}"#
        await keeper.reached(place)
        // Kept here until the reading pauses, then sent.
        #expect(DemoBooks.place(workId: workId, sourceItemId: sourceItemId) == nil)
        await keeper.flush()
        #expect(BookLocator.same(DemoBooks.place(workId: workId, sourceItemId: sourceItemId), place))
        // The next goes on from that one: the hub had it, so it is no question.
        let later = #"{"href":"OEBPS/chapter-02.xhtml","locations":{"progression":0.1,"totalProgression":0.12},"type":"application/xhtml+xml"}"#
        await keeper.reached(later)
        await keeper.flush()
        #expect(BookLocator.same(DemoBooks.place(workId: workId, sourceItemId: sourceItemId), later))
        #expect(await keeper.conflicted() == false)
    }

    @Test func aPlaceAnotherDeviceMovedIsNeverOverwrittenAndOpensAsAQuestion() async throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let workId = "rw_demo_rr6", sourceItemId = "rr6"
        let store = ReadingCheckpointStore(root: root)
        let keeper = makeKeeper(workId, sourceItemId, store: store)
        guard case .at(let start?) = await keeper.opening() else {
            Issue.record("Light Bringer is half read in the Books demo")
            return
        }
        let elsewhere = #"{"href":"OEBPS/chapter-07.xhtml","locations":{"progression":0.9,"totalProgression":0.83},"type":"application/xhtml+xml"}"#
        DemoBooks.moveElsewhere(workId: workId, sourceItemId: sourceItemId, locator: elsewhere)
        let mine = #"{"href":"OEBPS/chapter-05.xhtml","locations":{"progression":0.2,"totalProgression":0.55},"type":"application/xhtml+xml"}"#
        await keeper.reached(mine)
        await keeper.flush()
        #expect(await keeper.conflicted())
        #expect(BookLocator.same(DemoBooks.place(workId: workId, sourceItemId: sourceItemId), elsewhere))
        #expect(!BookLocator.same(start, elsewhere))
        // Opened again: both places offered, and the hub's taken when chosen.
        let again = makeKeeper(workId, sourceItemId, store: store)
        guard case .question(let prompt) = await again.opening() else {
            Issue.record("A place another device moved opened without asking")
            return
        }
        #expect(prompt.choices.map(\.id) == ["local", "server"])
        let server = await again.answer("server")
        guard case .at(let chosen?) = server else {
            Issue.record("Use server position opened nowhere")
            return
        }
        #expect(BookLocator.same(chosen, elsewhere))
    }

    @Test func aHubThatCannotBeAskedIsAQuestionAndTheBeginningWritesNothing() async throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let keeper = makeKeeper("rw_demo_nothing", "x", store: ReadingCheckpointStore(root: root))
        guard case .question(let prompt) = await keeper.opening() else {
            Issue.record("A place that could not be checked opened without asking")
            return
        }
        #expect(prompt.choices.map(\.id) == ["start"])
        #expect(await keeper.answer("start") == .at(nil))
    }

    // MARK: Books kept on the device (EpubPackageCacheTest)

    @Test func aFailedReplacementKeepsTheLastCompleteBookAndRemovesThePartialFile() throws {
        let cache = EpubPackageCache(root: Self.folder())
        defer { try? FileManager.default.removeItem(at: cache.root) }
        let original = try cache.install(workId: "rw_book", sourceItemId: "12") {
            try Data([0x50, 0x4B, 0x03, 0x04] + Array("complete-edition".utf8)).write(to: $0)
        }
        #expect(throws: (any Error).self) {
            try cache.install(workId: "rw_book", sourceItemId: "12") { url in
                try Data("partial".utf8).write(to: url)
                throw URLError(.networkConnectionLost)
            }
        }
        #expect(try Data(contentsOf: original).suffix(16) == Data("complete-edition".utf8))
        #expect(cache.isComplete(workId: "rw_book", sourceItemId: "12"))
        #expect(!FileManager.default.fileExists(atPath: cache.temporaryFile(workId: "rw_book", sourceItemId: "12").path))
    }

    @Test func anEmptyDownloadOrOneThatIsNoZipIsNeverABook() throws {
        let cache = EpubPackageCache(root: Self.folder())
        defer { try? FileManager.default.removeItem(at: cache.root) }
        #expect(throws: (any Error).self) {
            try cache.install(workId: "rw_book", sourceItemId: "12") { _ in }
        }
        #expect(throws: (any Error).self) {
            try cache.install(workId: "rw_book", sourceItemId: "12") { try Data("<html>error</html>".utf8).write(to: $0) }
        }
        #expect(!cache.isComplete(workId: "rw_book", sourceItemId: "12"))
    }

    @Test func pruningRemovesTheLeastRecentlyOpenedFirstAndNeverTheOpenBook() {
        let entries = [EpubCacheEntry(id: "active", sizeBytes: 80, lastAccessMillis: 1, active: true),
                       EpubCacheEntry(id: "old", sizeBytes: 40, lastAccessMillis: 2),
                       EpubCacheEntry(id: "new", sizeBytes: 50, lastAccessMillis: 3)]
        #expect(EpubPackageCachePolicy.evict(entries, budgetBytes: 80) == ["old", "new"])
        #expect(EpubPackageCachePolicy.evict(entries, budgetBytes: 500).isEmpty)
        #expect(EpubPackageCache.stableName("rw book", "a/b") == "rw_book_a_b")
    }

    // MARK: Bookmarks (EpubBookmarkStoreTest)

    private static func locator(_ progression: Double, total: Double? = nil) -> String {
        #"{"href":"chapter.xhtml","locations":{"progression":\#(progression),"totalProgression":\#(total ?? progression)},"title":"Chapter one"}"#
    }

    @Test func aBookmarkSurvivesReopeningAndRepaginationWithoutDuplicates() throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        func store() -> EpubBookmarks { EpubBookmarks(root: root, scope: "account/profile", workId: "book", sourceItemId: "edition") }
        #expect(try store().toggle(Self.locator(0.25, total: 0.2)))
        #expect(try store().list().count == 1)
        #expect(try store().contains(Self.locator(0.25, total: 0.21)))
        #expect(try !store().toggle(Self.locator(0.25, total: 0.21)))
        #expect(try store().list().isEmpty)
        #expect(try store().toggle(Self.locator(0.25)))
        #expect(try store().list().map(\.locator) == [Self.locator(0.25)])
        #expect(try store().list().first?.label == "Chapter one · 25%")
    }

    @Test func eachHubBookAndEditionHasItsOwnAndADeletionLasts() throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = EpubBookmarks(root: root, scope: "account/profile", workId: "book", sourceItemId: "edition")
        try store.toggle(Self.locator(0.25))
        for other in [EpubBookmarks(root: root, scope: "other/profile", workId: "book", sourceItemId: "edition"),
                      EpubBookmarks(root: root, scope: "account/profile", workId: "other", sourceItemId: "edition"),
                      EpubBookmarks(root: root, scope: "account/profile", workId: "book", sourceItemId: "another-edition")] {
            #expect(try other.list().isEmpty)
        }
        let anchor = try #require(try store.list().first?.anchor)
        #expect(try store.remove(anchor: anchor))
        #expect(try EpubBookmarks(root: root, scope: "account/profile", workId: "book", sourceItemId: "edition").list().isEmpty)
        #expect(EpubBookmarks.scope(address: "https://hub.example/", userId: "u")
                == EpubBookmarks.scope(address: "https://hub.example", userId: "u"))
    }

    @Test func aBrokenRecordFailsRatherThanQuietlyReplacingTheBookmarks() throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = EpubBookmarks(root: root, scope: "account/profile", workId: "book", sourceItemId: "edition")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data("broken".utf8).write(to: store.file)
        #expect(throws: (any Error).self) { try store.toggle(Self.locator(0.25)) }
    }

    // MARK: Plumbing

    private static func folder() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("book-tests-\(UUID().uuidString)", isDirectory: true)
    }

    private struct Entry {
        let name: String
        let crc: UInt32
        let data: Data
    }

    /// The archive's entries, read from its central directory as a reader does.
    private static func entries(_ zip: Data) throws -> [Entry] {
        let bytes = [UInt8](zip)
        func u16(_ at: Int) -> Int { Int(bytes[at]) | Int(bytes[at + 1]) << 8 }
        func u32(_ at: Int) -> Int { u16(at) | u16(at + 2) << 16 }
        let end = try #require((0...(bytes.count - 22)).reversed().first { u32($0) == 0x0605_4B50 })
        var at = u32(end + 16)
        var out: [Entry] = []
        for _ in 0..<u16(end + 10) {
            #expect(u32(at) == 0x0201_4B50)
            #expect(u16(at + 10) == 0, "stored")
            let crc = UInt32(u32(at + 16)), size = u32(at + 20), nameLength = u16(at + 28)
            let extra = u16(at + 30), comment = u16(at + 32), local = u32(at + 42)
            let name = String(decoding: bytes[(at + 46)..<(at + 46 + nameLength)], as: UTF8.self)
            let dataAt = local + 30 + u16(local + 26) + u16(local + 28)
            out.append(Entry(name: name, crc: crc, data: Data(bytes[dataAt..<(dataAt + size)])))
            at += 46 + nameLength + extra + comment
        }
        return out
    }
}
