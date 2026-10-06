import Foundation
import Testing
@testable import HubKit

/// A place kept on this device first and sent through the durable outbox
/// (#25 phase 2): Android's `ReadingCheckpointStoreTest`,
/// `ReadingCheckpointSyncTest` and `AudioOutboxTest`.
struct ReadingCheckpointTests {
    private let key = ReadingCheckpointKey(scope: "server-and-profile", workId: "work", sourceItemId: "edition", kind: "pages")
    private func page(_ n: Int) -> ReadingLocation { ReadingLocation(pageIndex: n) }

    /// A folder of its own for each test, in the system's temporary folder.
    private struct Folder {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("checkpoints-" + UUID().uuidString)
        func store() -> ReadingCheckpointStore { ReadingCheckpointStore(root: url) }
    }

    // MARK: The store

    @Test func aSettledPlaceSurvivesTheStoreBeingMadeAgainBeforeAnyNetwork() throws {
        let folder = Folder()
        let original = folder.store()
        try original.reconcile(key, .available(page(2)))
        try original.save(key, page(8), now: 100)
        let reopened = folder.store()
        #expect(try reopened.read(key)?.local == page(8))
        #expect(try reopened.read(key)?.pending == true)
        #expect(try reopened.reconcile(key, .unavailable).location == page(8))
    }

    @Test func aFailedLookupWithoutALocalPlaceIsNotANewBook() throws {
        let folder = Folder()
        let result = try folder.store().reconcile(key, .unavailable)
        #expect(result.unavailable)
        #expect(result.location == nil)
        #expect(try folder.store().read(key) == nil)
    }

    @Test func anUnchangedServerLetsThisDeviceGoOnButAMovedOneAsks() throws {
        let store = Folder().store()
        try store.reconcile(key, .available(page(2)))
        try store.save(key, page(8), now: 100)
        #expect(try !store.reconcile(key, .available(page(2))).conflict)
        #expect(try store.reconcile(key, .available(page(19))).conflict)
        #expect(try store.read(key)?.local == page(8))
        #expect(try store.read(key)?.remote == page(19))
        #expect(try store.read(key)?.pending == true)
    }

    @Test func anOldAcknowledgementCannotDeleteANewerLocalPlace() throws {
        let store = Folder().store()
        try store.reconcile(key, .available(page(2)))
        let sent = try store.save(key, page(8), now: 100)
        try store.save(key, page(9), now: 101)
        try store.acknowledge(key, revision: sent.revision, sent: page(8))
        let latest = try #require(try store.read(key))
        #expect(latest.local == page(9))
        #expect(latest.base == page(8))
        #expect(latest.pending)
        #expect(try !store.reconcile(key, .available(page(8))).conflict)
    }

    @Test func theSamePlaceAgainWritesNothingAndLeavesNothingToSend() throws {
        let store = Folder().store()
        try store.reconcile(key, .available(page(2)))
        let before = try store.read(key)
        let after = try store.save(key, page(2), now: 100)
        #expect(before == after)
        #expect(store.pending(scope: key.scope).isEmpty)
    }

    @Test func profileServerEditionAndFormatKeepTheirPlacesApart() throws {
        let store = Folder().store()
        try store.save(key, page(8), now: 100)
        let others = [ReadingCheckpointKey(scope: "another-profile", workId: "work", sourceItemId: "edition", kind: "pages"),
                      ReadingCheckpointKey(scope: key.scope, workId: "another-work", sourceItemId: "edition", kind: "pages"),
                      ReadingCheckpointKey(scope: key.scope, workId: "work", sourceItemId: "another-edition", kind: "pages"),
                      ReadingCheckpointKey(scope: key.scope, workId: "work", sourceItemId: "edition", kind: "epub")]
        for other in others { #expect(try store.read(other) == nil) }
        #expect(store.pending(scope: "another-profile").isEmpty)
        #expect(store.pending(scope: key.scope).count == 1)
    }

    @Test func answeringTheQuestionKeepsBothPlacesAndRebasesOnTheHubs() throws {
        let store = Folder().store()
        try store.reconcile(key, .available(page(2)))
        try store.save(key, page(8), now: 100)
        try store.reconcile(key, .available(page(19)))
        try store.chooseLocal(key, now: 200)
        let chosen = try #require(try store.read(key))
        #expect(chosen.base == page(19))
        #expect(chosen.local == page(8))
        #expect(Set(chosen.savedAlternatives) == [page(8), page(19)])
        #expect(!chosen.conflicted)
        #expect(chosen.pending)
    }

    @Test func theServersPlaceChosenAndAnAcknowledgementClearOnlyTheirOwnBook() throws {
        let store = Folder().store()
        try store.save(key, page(8), now: 100)
        let other = ReadingCheckpointKey(scope: key.scope, workId: "work", sourceItemId: "other", kind: "pages")
        try store.save(other, page(4), now: 100)
        try store.reconcile(key, .available(page(19)))
        try store.chooseRemote(key)
        #expect(try store.read(key)?.local == page(19))
        #expect(try store.read(key)?.pending == false)
        let sent = try #require(try store.read(other))
        try store.acknowledge(other, revision: sent.revision, sent: page(4))
        #expect(store.pending(scope: key.scope).isEmpty)
    }

    @Test func readingOfflineWithoutABaseCannotOverwriteTheServersProgress() throws {
        let store = Folder().store()
        try store.save(key, page(8), now: 100)
        #expect(try store.reconcile(key, .available(page(19))).conflict)
    }

    @Test func anAcknowledgementAfterTheServersPlaceWasChosenCannotReviveWhatWasLeft() throws {
        let store = Folder().store()
        try store.reconcile(key, .available(page(2)))
        let sent = try store.save(key, page(8), now: 100)
        try store.reconcile(key, .available(page(19)))
        try store.chooseRemote(key)
        try store.acknowledge(key, revision: sent.revision, sent: sent.local)
        let latest = try #require(try store.read(key))
        #expect(latest.local == page(19))
        #expect(latest.base == page(19))
        #expect(!latest.pending)
    }

    @Test func aRecordThatCannotBeReadIsAnErrorNotANewBook() throws {
        let folder = Folder()
        let store = folder.store()
        try store.save(key, page(8), now: 100)
        try Data("not json".utf8).write(to: folder.url.appendingPathComponent(key.fileName + ".json"))
        #expect(throws: (any Error).self) { try store.read(key) }
        #expect(throws: (any Error).self) { try store.save(key, page(9), now: 101) }
    }

    @Test func theSheetAsksWhichPlaceOrToStartWhenTheHubCouldNotBeAsked() throws {
        let store = Folder().store()
        try store.reconcile(key, .available(page(2)))
        try store.save(key, page(8), now: 100)
        let moved = try store.reconcile(key, .available(page(19)))
        let ask = try #require(ReadingResumePrompt.make(moved, checkpoint: try store.read(key)) { $0.label() })
        #expect(ask.title == "Choose reading position")
        #expect(ask.choices.map(\.id) == ["local", "server"])
        #expect(ask.choices.map(\.detail) == ["Page 9", "Page 20"])
        let unreachable = try #require(ReadingResumePrompt.make(ReadingResume(nil, unavailable: true), checkpoint: nil) { $0.label() })
        #expect(unreachable.title == "Reading position unavailable")
        #expect(unreachable.choices.map(\.label) == ["Start from the beginning"])
        #expect(ReadingResumePrompt.make(ReadingResume(page(2)), checkpoint: nil) { $0.label() } == nil)
    }

    @Test func aPlacesScopeIsTheHubAndTheProfileWhateverTheAddressEndsIn() {
        let scope = ReadingCheckpointKey.scope(address: "https://hub.example/", userId: "dgdan")
        #expect(scope == ReadingCheckpointKey.scope(address: "https://hub.example", userId: "dgdan"))
        #expect(scope != ReadingCheckpointKey.scope(address: "https://hub.example", userId: "hadas"))
        #expect(scope != ReadingCheckpointKey.scope(address: "https://other.example", userId: "dgdan"))
        #expect(scope.count == 64)
    }

    // MARK: The sync

    private func pending(_ folder: Folder) throws -> ReadingCheckpointStore {
        let store = folder.store()
        try store.reconcile(key, .available(page(2)))
        try store.save(key, page(8), now: 100)
        return store
    }

    /// Counts what the sync sends, and answers as it is told.
    private final class Writes: @unchecked Sendable {
        var count = 0
        var succeed = true
        var bases: [ReadingLocation?] = []
    }

    @Test func aFailedReadNeverSendsAndKeepsTheWork() async throws {
        let folder = Folder()
        let store = try pending(folder)
        let writes = Writes()
        let sync = ReadingCheckpointSync(store: store, fetch: { _ in .unavailable }, send: { _ in writes.count += 1; return true })
        #expect(try await sync.sync(key) == .retry)
        #expect(writes.count == 0)
        #expect(try store.read(key)?.pending == true)
    }

    @Test func aChangedServerIsAQuestionNotTheLastWriterWinning() async throws {
        let folder = Folder()
        let store = try pending(folder)
        let writes = Writes()
        let sync = ReadingCheckpointSync(store: store, fetch: { _ in .available(ReadingLocation(pageIndex: 17)) },
                                         send: { _ in writes.count += 1; return true })
        #expect(try await sync.sync(key) == .conflict)
        #expect(writes.count == 0)
        #expect(try store.read(key)?.local == page(8))
    }

    @Test func aRefusedWriteStaysToSendAndALaterOneClearsIt() async throws {
        let folder = Folder()
        let store = try pending(folder)
        let writes = Writes()
        writes.succeed = false
        let first = ReadingLocation(pageIndex: 2)
        let sync = ReadingCheckpointSync(store: store, fetch: { _ in .available(first) },
                                         send: { sent in writes.bases.append(sent.base); return writes.succeed })
        #expect(try await sync.sync(key) == .retry)
        writes.succeed = true
        #expect(try await sync.sync(key) == .synced)
        #expect(try store.read(key)?.pending == false)
        #expect(writes.bases.allSatisfy { $0 == first })
    }

    @Test func aPlaceReachedWhileAWriteIsInFlightSurvivesItsAcknowledgement() async throws {
        let folder = Folder()
        let store = try pending(folder)
        let first = ReadingLocation(pageIndex: 2)
        let key = self.key
        let sync = ReadingCheckpointSync(store: store, fetch: { _ in .available(first) }, send: { _ in
            (try? store.save(key, ReadingLocation(pageIndex: 9), now: 101)) != nil
        })
        #expect(try await sync.sync(key) == .retry)
        #expect(try store.read(key)?.local?.pageIndex == 9)
        #expect(try store.read(key)?.base == page(8))
    }

    // MARK: The listening place through the outbox

    private let audioKey = ReadingCheckpointKey(scope: "profile", workId: "rw_1", sourceItemId: "3726292328809367", kind: AudioPlace.kind)
    private let tracks = [ReadingAudioTrack(index: 0, id: "t_aaaaaaaaaaaa", title: "Track 01", durationMs: 600_000),
                          ReadingAudioTrack(index: 1, id: "t_bbbbbbbbbbbb", title: "Track 02", durationMs: 1_200_000)]

    /// A stand-in for the hub that keeps its rules: a write is checked
    /// against `expected` (absent skips, null means nothing is saved, a place
    /// must be the place held now) and stamped with the hub's own clock, which
    /// runs anywhere it likes.
    private final class Hub: @unchecked Sendable {
        let tracks: [ReadingAudioTrack]
        var held: AudioPlace?
        var clock: Int64 = 1_764_000_000_000
        var writes: [JSONValue] = []
        /// What another device does between this one's read and its write.
        var beforeWrite: (() -> Void)?

        init(_ tracks: [ReadingAudioTrack], held: AudioPlace? = nil) {
            self.tracks = tracks
            self.held = held
        }

        func get() -> RemoteReadingPosition {
            clock -= 86_400_000 // the hub's clock means nothing here: it may even run backwards
            guard let place = held else { return .available(nil) }
            let answer = ReadingAudioPosition(trackId: place.trackId, track: tracks.firstIndex { $0.id == place.trackId } ?? 0,
                                              offsetMs: place.offsetMs, completed: place.completed, exact: true, form: "audio",
                                              timestamp: clock)
            return .available(AudioPlace.fromServer(answer)?.location())
        }

        func post(_ body: JSONValue) -> Bool {
            beforeWrite?()
            beforeWrite = nil
            writes.append(body)
            if let expected = body["expected"] {
                let holds: Bool
                if expected == .null {
                    holds = held == nil
                } else {
                    holds = held?.trackId == expected["trackId"]?.stringValue && held?.offsetMs == expected["offsetMs"]?.int64Value
                }
                if !holds { return false } // 409 reading_position_conflict
            }
            if body["completed"]?.boolValue == true, let last = tracks.last {
                held = AudioPlace(last.id, last.durationMs, completed: true)
            } else if let track = body["trackId"]?.stringValue, let offset = body["offsetMs"]?.int64Value {
                held = AudioPlace(track, offset)
            }
            return true
        }
    }

    private func sync(_ store: ReadingCheckpointStore, _ hub: Hub) -> ReadingCheckpointSync {
        ReadingCheckpointSync(store: store, fetch: { _ in hub.get() }, send: { checkpoint in
            guard let body = AudioPlace.body(checkpoint) else { return false }
            return hub.post(body)
        })
    }

    /// A stretch of listening as the player keeps it: the place, and how far
    /// through the book it is beside it (#30).
    private func listen(_ store: ReadingCheckpointStore, part: Int, offsetMs: Int64, completed: Bool = false) throws {
        let kept = try #require(AudioPlace.kept(tracks, part: part, offsetMs: offsetMs, completed: completed))
        try store.save(audioKey, kept, now: 100)
    }

    private func near(_ value: Double?, _ expected: Double) -> Bool {
        guard let value else { return false }
        return abs(value - expected) < 1e-9
    }

    @Test func aPlaceMovesOnFromTheOneReadCheckedAgainstIt() async throws {
        let store = Folder().store()
        let hub = Hub(tracks, held: AudioPlace("t_aaaaaaaaaaaa", 10_000))
        try store.reconcile(audioKey, hub.get())
        try listen(store, part: 0, offsetMs: 50_000)
        #expect(try await sync(store, hub).sync(audioKey) == .synced)
        #expect(hub.held == AudioPlace("t_aaaaaaaaaaaa", 50_000))
        #expect(hub.writes.count == 1)
        #expect(hub.writes.first?["expected"]?["offsetMs"]?.int64Value == 10_000)
        // The next stretch is based on what was written.
        try listen(store, part: 1, offsetMs: 5_000)
        #expect(try await sync(store, hub).sync(audioKey) == .synced)
        #expect(hub.writes.last?["expected"]?["offsetMs"]?.int64Value == 50_000)
        #expect(try store.read(audioKey)?.pending == false)
    }

    @Test func anotherDevicesMoveIsAChoiceNeverOverwritten() async throws {
        let store = Folder().store()
        let hub = Hub(tracks, held: AudioPlace("t_aaaaaaaaaaaa", 10_000))
        try store.reconcile(audioKey, hub.get())
        try listen(store, part: 0, offsetMs: 50_000)
        hub.held = AudioPlace("t_bbbbbbbbbbbb", 700_000)
        #expect(try await sync(store, hub).sync(audioKey) == .conflict)
        #expect(hub.writes.isEmpty, "Nothing is sent over the other place")
        let checkpoint = try #require(try store.read(audioKey))
        #expect(AudioPlace.of(checkpoint.local) == AudioPlace("t_aaaaaaaaaaaa", 50_000))
        #expect(AudioPlace.of(checkpoint.remote) == AudioPlace("t_bbbbbbbbbbbb", 700_000))
        // Listening on keeps the question open.
        try listen(store, part: 0, offsetMs: 60_000)
        #expect(try await sync(store, hub).sync(audioKey) == .conflict)
        // This device's place chosen: it goes out based on the other one.
        try store.chooseLocal(audioKey, now: 200)
        #expect(try await sync(store, hub).sync(audioKey) == .synced)
        #expect(hub.held == AudioPlace("t_aaaaaaaaaaaa", 60_000))
        #expect(hub.writes.last?["expected"]?["offsetMs"]?.int64Value == 700_000)
    }

    @Test func aMoveBetweenTheReadAndTheWriteIsRefusedThenAskedAbout() async throws {
        let store = Folder().store()
        let hub = Hub(tracks, held: AudioPlace("t_aaaaaaaaaaaa", 10_000))
        try store.reconcile(audioKey, hub.get())
        try listen(store, part: 0, offsetMs: 50_000)
        hub.beforeWrite = { hub.held = AudioPlace("t_bbbbbbbbbbbb", 1_000) }
        #expect(try await sync(store, hub).sync(audioKey) == .retry)
        #expect(hub.held == AudioPlace("t_bbbbbbbbbbbb", 1_000))
        #expect(try await sync(store, hub).sync(audioKey) == .conflict)
    }

    @Test func theHubsClockNeverDecidesWhichPlaceIsNewer() async throws {
        let store = Folder().store()
        let hub = Hub(tracks, held: AudioPlace("t_aaaaaaaaaaaa", 10_000))
        try store.reconcile(audioKey, hub.get())
        // Read twice, its clock far apart, at the same place: no question, and nothing to send.
        #expect(try !store.reconcile(audioKey, hub.get()).conflict)
        #expect(try store.read(audioKey)?.pending == false)
        // A local place not yet sent wins over the same old place, whatever the clock says.
        try listen(store, part: 0, offsetMs: 20_000)
        hub.clock = Int64.max / 2
        #expect(try !store.reconcile(audioKey, hub.get()).conflict)
        #expect(try await sync(store, hub).sync(audioKey) == .synced)
        #expect(hub.held == AudioPlace("t_aaaaaaaaaaaa", 20_000))
    }

    @Test func finishingWritesCompletedAndTheHubsReadingOfItIsTheSamePlace() async throws {
        let store = Folder().store()
        let hub = Hub(tracks)
        try store.reconcile(audioKey, hub.get())
        try listen(store, part: 1, offsetMs: 1_199_000)
        let local = try #require(AudioPlace.of(try store.read(audioKey)?.local))
        #expect(local.completed, "The last two seconds are the end")
        #expect(try await sync(store, hub).sync(audioKey) == .synced)
        #expect(hub.writes.first?["completed"]?.boolValue == true)
        // Read back as the hub holds it: the same, so no question and nothing to send.
        #expect(try !store.reconcile(audioKey, hub.get()).conflict)
        #expect(AudioPlace.of(try store.read(audioKey)?.local) == local)
        #expect(try store.read(audioKey)?.pending == false)
    }

    @Test func anEarlierPlaceGoesOutWhenTheHubHasNoneAndIsAskedAboutWhenItHasOne() async throws {
        let store = Folder().store()
        let hub = Hub(tracks)
        #expect(try store.seed(audioKey, AudioPlace("t_bbbbbbbbbbbb", 300_000).location(), now: 50))
        #expect(try !store.seed(audioKey, AudioPlace("t_aaaaaaaaaaaa", 1).location(), now: 51), "Seeded once")
        let resume = try store.reconcile(audioKey, hub.get())
        #expect(!resume.conflict)
        #expect(AudioPlace.of(resume.location) == AudioPlace("t_bbbbbbbbbbbb", 300_000))
        #expect(try await sync(store, hub).sync(audioKey) == .synced)
        #expect(hub.writes.first?["expected"] == JSONValue.null)

        let other = Hub(tracks, held: AudioPlace("t_aaaaaaaaaaaa", 42_000))
        let second = ReadingCheckpointKey(scope: "profile", workId: "rw_2", sourceItemId: "2878103166016296", kind: AudioPlace.kind)
        #expect(try store.seed(second, AudioPlace("t_bbbbbbbbbbbb", 300_000).location(), now: 50))
        let asked = try store.reconcile(second, other.get())
        #expect(asked.conflict, "The hub has a place of its own: the person chooses")
        #expect(other.held == AudioPlace("t_aaaaaaaaaaaa", 42_000))
    }

    // MARK: The fraction kept beside a listening place (#30)

    @Test func aPlaceWaitingToBeSentSaysHowFarThroughTheBookItIsAndThatSurvivesARestart() throws {
        let folder = Folder()
        let store = folder.store()
        let hub = Hub(tracks)
        try store.reconcile(audioKey, hub.get())
        try listen(store, part: 1, offsetMs: 300_000)
        // The whole of the first track and five minutes of the second, of thirty minutes.
        let waiting = try #require(folder.store().pending(scope: audioKey.scope).first)
        #expect(AudioPlace.progressOf(waiting.local) == 0.5)
        #expect(AudioPlace.of(waiting.local) == AudioPlace("t_bbbbbbbbbbbb", 300_000))
        // What will go out says nothing of it.
        let body = try #require(AudioPlace.body(waiting))
        #expect(Set(body.objectValue.map { Array($0.keys) } ?? []) == ["trackId", "offsetMs", "completed", "expected"])
    }

    @Test func theFractionKeptBesideAPlaceNeverMakesTheHubsOwnReadingLookLikeAnotherDevice() async throws {
        let store = Folder().store()
        let hub = Hub(tracks, held: AudioPlace("t_aaaaaaaaaaaa", 10_000))
        try store.reconcile(audioKey, hub.get())
        // Stretch after stretch, each kept with its fraction and read back from the hub without one.
        for offset: Int64 in [50_000, 90_000, 150_000, 400_000] {
            try listen(store, part: 0, offsetMs: offset)
            #expect(try await sync(store, hub).sync(audioKey) == .synced)
            #expect(hub.held == AudioPlace("t_aaaaaaaaaaaa", offset))
            #expect(try store.read(audioKey)?.pending == false)
        }
        // Each was based on the one before.
        let bases = hub.writes.map { $0["expected"]?["offsetMs"]?.int64Value }
        #expect(bases == [10_000, 50_000, 90_000, 150_000])
    }

    @Test func aWriteThatLandedButWasNeverAcknowledgedIsThisDevicesOwnNotAnothers() throws {
        let store = Folder().store()
        let hub = Hub(tracks, held: AudioPlace("t_aaaaaaaaaaaa", 10_000))
        try store.reconcile(audioKey, hub.get())
        try listen(store, part: 0, offsetMs: 50_000)
        // The hub took it and the answer never got back: nothing was acknowledged here.
        let waiting = try #require(try store.read(audioKey))
        let body = try #require(AudioPlace.body(waiting))
        #expect(hub.post(body))
        #expect(try store.read(audioKey)?.pending == true)
        // The next look finds the same place the hub was sent: no question, and nothing left to send.
        #expect(try !store.reconcile(audioKey, hub.get()).conflict)
        #expect(try store.read(audioKey)?.pending == false)
        #expect(hub.writes.count == 1)
    }

    @Test func aPlaceAnOlderBuildLeftWaitingGoesOutAsItDidAndTheNextStretchCarriesOnFromIt() async throws {
        let store = Folder().store()
        let hub = Hub(tracks, held: AudioPlace("t_aaaaaaaaaaaa", 10_000))
        try store.reconcile(audioKey, hub.get())
        // Kept by a build that wrote no fraction: it says nothing of how far through the book it is.
        try store.save(audioKey, AudioPlace("t_aaaaaaaaaaaa", 50_000).location(), now: 100)
        #expect(AudioPlace.progressOf(try store.read(audioKey)?.local) == nil)
        #expect(try await sync(store, hub).sync(audioKey) == .synced)
        #expect(hub.held == AudioPlace("t_aaaaaaaaaaaa", 50_000))
        // This build's next stretch, with a fraction, is based on it and is not a conflict.
        try listen(store, part: 0, offsetMs: 80_000)
        #expect(near(AudioPlace.progressOf(try store.read(audioKey)?.local), 80_000.0 / 1_800_000))
        #expect(try await sync(store, hub).sync(audioKey) == .synced)
        #expect(hub.held == AudioPlace("t_aaaaaaaaaaaa", 80_000))
        #expect(hub.writes.last?["expected"]?["offsetMs"]?.int64Value == 50_000)
    }

    @Test func listeningOnToTheVeryPlaceTheHubHoldsIsNoNewWrite() throws {
        let store = Folder().store()
        let hub = Hub(tracks, held: AudioPlace("t_aaaaaaaaaaaa", 10_000))
        try store.reconcile(audioKey, hub.get())
        let before = try store.read(audioKey)
        // The same place again, kept with a fraction the hub's reading does not have.
        try listen(store, part: 0, offsetMs: 10_000)
        #expect(try store.read(audioKey) == before)
        #expect(store.pending(scope: audioKey.scope).isEmpty)
    }

    @Test func choosingThisDevicesPlaceComparesItWithTheHubsAsPlaces() async throws {
        let store = Folder().store()
        let hub = Hub(tracks, held: AudioPlace("t_aaaaaaaaaaaa", 10_000))
        try store.reconcile(audioKey, hub.get())
        try listen(store, part: 0, offsetMs: 50_000)
        // Another device moves the book on: a question.
        hub.held = AudioPlace("t_aaaaaaaaaaaa", 60_000)
        #expect(try await sync(store, hub).sync(audioKey) == .conflict)
        // Listening on here reaches the very place the other device left.
        try listen(store, part: 0, offsetMs: 60_000)
        // Kept here with its fraction, it is still the hub's place: nothing to send.
        try store.chooseLocal(audioKey, now: 300)
        #expect(try store.read(audioKey)?.pending == false)
        #expect(try await sync(store, hub).sync(audioKey) == .synced)
        #expect(hub.writes.isEmpty)
    }

    // MARK: The demo hub keeps the same rules

    @Test func theDemoHubRefusesAWriteBasedOnAPlaceItNoLongerHolds() async throws {
        let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                            screens: DemoTransport())
        let book = DemoReading.audiobooks[1]
        let read = try await hub.fetch(HubEndpoints.readingAudioPosition(workId: book.workId, sourceItemId: book.sourceItemId),
                                       as: ReadingAudioPositionResponse.self)
        let manifest = try await hub.fetch(HubEndpoints.readingAudioManifest(workId: book.workId, sourceItemId: book.sourceItemId),
                                           as: ReadingAudioManifest.self)
        let base = read.position.flatMap(AudioPlace.fromServer)
        let place = try #require(AudioPlace.canonical(manifest.tracks, part: 0, offsetMs: 12_000))
        try await hub.send(HubEndpoints.saveReadingAudioPosition(
            workId: book.workId, sourceItemId: book.sourceItemId,
            body: AudioPlace.body(place, base: base, baseKnown: true).encoded()))
        // The same write again names a place the hub no longer holds.
        await #expect(throws: HubFailure.self) {
            try await hub.send(HubEndpoints.saveReadingAudioPosition(
                workId: book.workId, sourceItemId: book.sourceItemId,
                body: AudioPlace.body(place, base: base, baseKnown: true).encoded()))
        }
        let after = try await hub.fetch(HubEndpoints.readingAudioPosition(workId: book.workId, sourceItemId: book.sourceItemId),
                                        as: ReadingAudioPositionResponse.self)
        #expect(after.position.flatMap(AudioPlace.fromServer) == place)
    }
}
