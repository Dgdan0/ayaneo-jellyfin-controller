import Foundation
import Testing
@testable import HubKit

/// Offline downloads (#5): the hub's answers and routes, then Android's
/// `OfflineCatalogTest`, `OfflineQueueLabelsTest` and the retry policy, then
/// the store on disk, the picker's choices and the plan a download plays by.
struct OfflineTests {
    // MARK: The hub's answers

    @Test func theSelectionReadsAsTheHubWritesIt() throws {
        let json = #"""
        {"series":{"id":"s1","type":"series","title":"Lanterns"},"episodeCount":2,"estimatedSizeBytes":3000,
         "playTargetId":"e2",
         "seasons":[{"season":{"id":"season-1","type":"season","title":"Season 1","indexNumber":1},
           "episodes":[
             {"item":{"id":"e1","type":"episode","title":"One","seasonNumber":1,"indexNumber":1,"played":true},
              "sources":[{"id":"src1","name":"1080p","container":"mkv","mimeType":"video/x-matroska","sizeBytes":1000,
                "tracks":[{"index":1,"type":"Audio","label":"English","default":true},{"index":2,"type":"Subtitle","codec":"srt","external":true}]}],
              "estimatedSizeBytes":1000,"available":true},
             {"item":{"id":"e2","type":"episode","title":"Two","seasonNumber":1,"indexNumber":2},
              "sources":[],"estimatedSizeBytes":2000,"available":true},
             {"item":{"id":"e3","type":"episode","title":"Three","seasonNumber":1,"indexNumber":3},"available":false}]}]}
        """#
        let selection = try JSONDecoder().decode(OfflineSelectionResponse.self, from: Data(json.utf8))
        #expect(selection.series.title == "Lanterns")
        #expect(selection.playTargetId == "e2")
        let episodes = try #require(selection.seasons.first).episodes
        #expect(episodes.map(\.item.id) == ["e1", "e2", "e3"])
        #expect(episodes[0].sources.first?.tracks.map(\.type) == ["Audio", "Subtitle"])
        #expect(episodes[0].sources.first?.tracks.first?.isDefault == true)
        #expect(episodes[2].available == false && episodes[2].estimatedSizeBytes == 0)
    }

    @Test func aPrepareAnswerGivesEachManifestWithItsOwnJSON() throws {
        let json = #"""
        {"batchKey":"offline-1-s1","items":[
          {"grantId":"g1","batchKey":"offline-1-s1","clientItemKey":"offline-1-s1-e1","expiresAt":1790000000000,
           "item":{"id":"e1","type":"episode","title":"One","seriesId":"s1","seriesTitle":"Lanterns",
                   "library":{"id":"lib","name":"Anime"},"lastPlayedAt":1780000000000},
           "source":{"id":"src1","container":"mkv","sizeBytes":1000},
           "mediaUrl":"/v1/offline/grants/g1/media",
           "subtitles":[{"track":{"index":2,"type":"Subtitle","codec":"srt","external":true},"url":"/v1/offline/grants/g1/subtitles/2"}],
           "format":"a field a later hub adds"}]}
        """#
        let listed = OfflineManifest.list(from: Data(json.utf8))
        #expect(listed.count == 1)
        let manifest = try #require(listed.first).manifest
        #expect(manifest.grantId == "g1" && manifest.clientItemKey == "offline-1-s1-e1")
        #expect(manifest.library == "Anime")
        #expect(manifest.lastPlayedAt == 1_780_000_000_000)
        #expect(manifest.mediaUrl == "/v1/offline/grants/g1/media")
        #expect(manifest.subtitles.first?.url == "/v1/offline/grants/g1/subtitles/2")
        // Kept whole: a field this app does not read survives in the stored JSON.
        let kept = try #require(listed.first).json
        #expect(String(decoding: kept, as: UTF8.self).contains("a later hub adds"))
        #expect(OfflineManifest.list(from: Data("[]".utf8)).isEmpty)
    }

    @Test func theBodiesSendOnlyWhatTheyHave() throws {
        let body = OfflinePrepareBody(batchKey: "b", items: [OfflinePrepareItem(clientItemKey: "k", itemId: "i")])
        let sent = try #require(try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as? [String: Any])
        #expect(sent["seriesId"] == nil)
        #expect((sent["items"] as? [[String: Any]])?.first?["mediaSourceId"] == nil)
        let event = OfflineProgressEvent(clientEventKey: "k", itemId: "i", positionMillis: 5, durationMillis: 10, occurredAt: 7)
        let encoded = try JSONEncoder().encode(event)
        #expect(!String(decoding: encoded, as: UTF8.self).contains("completed"))
        #expect(try JSONDecoder().decode(OfflineProgressEvent.self, from: encoded) == event)
        let synced = try JSONDecoder().decode(OfflineProgressSyncResponse.self, from: Data(#"""
            {"results":[{"clientEventKey":"k","itemId":"i","status":"server_newer","serverPositionMillis":60000,
              "serverPlayed":false,"serverLastPlayedAt":99}]}
            """#.utf8))
        #expect(synced.results.first?.status == "server_newer" && synced.results.first?.serverLastPlayedAt == 99)
    }

    @Test func theRoutesAreTheHubs() {
        #expect(HubEndpoints.offlineSelection(seriesId: "abc").path == "/v1/offline/series/abc/selection")
        let prepare = HubEndpoints.prepareOffline(OfflinePrepareBody(batchKey: "b", items: []))
        #expect(prepare.path == "/v1/offline/prepare" && prepare.method == .post && prepare.slow)
        #expect(HubEndpoints.renewOffline(grantId: "g 1").path == "/v1/offline/grants/g%201/renew")
        #expect(HubEndpoints.syncOfflineProgress(OfflineProgressSyncBody(events: [])).path == "/v1/offline/progress/sync")
    }

    // MARK: The catalog (OfflineCatalogTest)

    @Test func titlesGroupEpisodesBySeriesAndSortTitlesAndEpisodes() throws {
        let result = OfflineCatalog.titles([
            Self.episode("a2", "attack", "Attack on Titan", "season-1", 1, 2),
            Self.movie("m1", "Zodiac"),
            Self.episode("a1", "attack", "Attack on Titan", "season-1", 1, 1),
        ])
        #expect(result.map(\.title) == ["Attack on Titan", "Zodiac"])
        #expect(result.first?.isSeries == true)
        #expect(result.first?.rows.map(\.manifest.item.indexNumber) == [1, 2])
        #expect(result.last?.isSeries == false)
        #expect(result.last?.key == "m1")
    }

    @Test func downloadsGroupByTheLibraryTheirManifestOrALaterLookupNamesUnknownLast() {
        var marvel = Self.movie("m1", "Iron Man 3")
        marvel.manifest.library = "Marvel Movies"
        let entries = OfflineCatalog.titles([marvel, Self.episode("b1", "bleach", "Bleach", "season-1", 1, 1),
                                             Self.movie("m2", "Zodiac"), Self.episode("a1", "attack", "Attack on Titan", "season-1", 1, 1)],
                                            libraryNames: ["bleach": "Anime", "attack": "Anime"])
        let groups = OfflineCatalog.byLibrary(entries)
        #expect(groups.map(\.library) == ["Anime", "Marvel Movies", "Movies"])
        #expect(groups.first?.entries.map(\.title) == ["Attack on Titan", "Bleach"])
        #expect(groups.last?.entries.map(\.title) == ["Zodiac"])
    }

    @Test func seasonsHoldOnlyTheStoredRowsOfTheSeries() {
        let result = OfflineCatalog.seasons(seriesId: "lanterns", [
            Self.episode("s1e4", "lanterns", "Lanterns", "season-1", 1, 4),
            Self.episode("special", "lanterns", "Lanterns", "specials", 0, 1),
            Self.episode("other", "other-series", "Other", "season-3", 3, 1),
        ])
        #expect(result.map(\.number) == [0, 1])
        #expect(result.flatMap(\.rows).map(\.id) == ["special", "s1e4"])
        #expect(result.first?.title == "Specials")
    }

    @Test func theLocalTargetResumesTheNewestPartialEpisodeThenGoesPastFinishedOnes() {
        let first = Self.episode("s1e1", "lanterns", "Lanterns", "season-1", 1, 1)
        let second = Self.episode("s1e2", "lanterns", "Lanterns", "season-1", 1, 2)
        let third = Self.episode("s1e3", "lanterns", "Lanterns", "season-1", 1, 3)
        let resume = OfflineCatalog.playTarget([first, second, third], progress: [
            "s1e1": OfflineCatalogProgress(positionMillis: 1_190_000, durationMillis: 1_200_000, updatedAtMillis: 1),
            "s1e2": OfflineCatalogProgress(positionMillis: 300_000, durationMillis: 1_200_000, updatedAtMillis: 2),
        ])
        #expect(resume?.row.id == "s1e2")
        #expect(resume?.kind == .resume)
        let next = OfflineCatalog.playTarget([first, second, third], progress: [
            "s1e1": OfflineCatalogProgress(positionMillis: 1_190_000, durationMillis: 1_200_000, updatedAtMillis: 1),
        ])
        #expect(next?.row.id == "s1e2")
        #expect(next?.kind == .next)
        #expect(OfflineCatalog.playTarget([first], progress: [:])?.kind == .start)
    }

    @Test func aLaterWatchOnTheServerReplacesAnOlderOneFromThisDevice() {
        // Downloaded, watched to 20 minutes offline, then carried on to 60 on the TV.
        let local = OfflineCatalogProgress(positionMillis: 1_200_000, durationMillis: 7_200_000, updatedAtMillis: 1_000)
        let server = OfflineCatalogProgress.fromServer(positionMillis: 3_600_000, durationMillis: 7_200_000, played: false,
                                                       lastPlayedAt: 2_000)
        #expect(OfflineCatalogProgress.newer(local, server).resumePosition == 3_600_000)
    }

    @Test func thisDeviceKeepsItsOwnWatchWhenItIsTheLaterOne() {
        let local = OfflineCatalogProgress(positionMillis: 1_200_000, durationMillis: 7_200_000, updatedAtMillis: 3_000)
        let server = OfflineCatalogProgress.fromServer(positionMillis: 3_600_000, durationMillis: 7_200_000, played: false,
                                                       lastPlayedAt: 2_000)
        #expect(OfflineCatalogProgress.newer(local, server) == local)
        // An older hub sends no date, which never wins.
        let undated = OfflineCatalogProgress(positionMillis: 3_600_000, durationMillis: 7_200_000, updatedAtMillis: 0)
        #expect(OfflineCatalogProgress.newer(local, undated) == local)
        #expect(OfflineCatalogProgress.newer(local, nil) == local)
    }

    @Test func aServerWatchThatFinishedTheItemStartsItOver() {
        let server = OfflineCatalogProgress.fromServer(positionMillis: 0, durationMillis: 7_200_000, played: true, lastPlayedAt: 2_000)
        #expect(server.isComplete)
        #expect(server.resumePosition == 0)
    }

    // MARK: The manager's words (OfflineQueueLabelsTest) and retries

    @Test func eachStateIsAChipOnlyTroubleAndAFinishedFileInColour() {
        func chip(_ state: OfflineState) -> String {
            let chip = OfflineQueueLabels.chip(state)
            return "\(chip.word) \(chip.tone)"
        }
        #expect(chip(.downloading) == "Downloading quiet")
        #expect(chip(.queued) == "Queued quiet")
        #expect(chip(.paused) == "Paused quiet")
        #expect(chip(.waiting) == "Waiting waiting")
        #expect(chip(.failed) == "Failed bad")
        #expect(chip(.complete) == "Downloaded good")
    }

    @Test func theFiguresSayHowMuchHasArrivedAndHowFastOnlyWhileItMoves() {
        var moving = Self.movie("m", "Zodiac", state: .downloading, done: 50 << 20, total: 200 << 20)
        moving.speedBytesPerSecond = 5 << 20
        #expect(OfflineQueueLabels.figures(moving)
                == "\(Fmt.bytes(50 << 20)) of \(Fmt.bytes(200 << 20)) · \(Fmt.speed(5 << 20)) · \(Fmt.eta(30)) left")
        var paused = moving
        paused.state = .paused
        #expect(OfflineQueueLabels.figures(paused) == "\(Fmt.bytes(50 << 20)) of \(Fmt.bytes(200 << 20))")
    }

    @Test func nothingArrivedYetOrAllOfItIsTheSizeAlone() {
        #expect(OfflineQueueLabels.figures(Self.movie("m", "Zodiac", state: .queued, done: 0, total: 200 << 20)) == Fmt.bytes(200 << 20))
        #expect(OfflineQueueLabels.figures(Self.movie("m", "Zodiac", state: .complete, done: 200 << 20, total: 200 << 20))
                == Fmt.bytes(200 << 20))
    }

    @Test func retriesWaitShortFirstAndBoundedLater() {
        #expect(OfflineRetryPolicy.delayMillis(1) == 5_000)
        #expect(OfflineRetryPolicy.delayMillis(3) == 45_000)
        #expect(OfflineRetryPolicy.delayMillis(50) == 300_000)
    }

    // MARK: The store on disk

    @Test func aBatchQueuesInItsOrderOnceAndDownloadsTheOldestBatchFirst() throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        let added = store.enqueue(title: "Lanterns", seriesId: "lanterns", userId: "dan",
                                  manifests: [Self.manifest("b1", "b1-e2", "e2", 2), Self.manifest("b1", "b1-e1", "e1", 1)],
                                  now: 10)
        #expect(added == 2)
        // The same items again, under new keys, add nothing.
        #expect(store.enqueue(title: "Lanterns", seriesId: "lanterns", userId: "dan",
                              manifests: [Self.manifest("b9", "b9-e1", "e1", 1)], now: 11) == 0)
        #expect(store.batches(userId: "dan").map(\.id) == ["b1"])
        store.enqueue(title: "Zodiac", seriesId: "", userId: "dan", manifests: [Self.manifest("b2", "b2-m", "m", 0)], now: 20)
        // Batch order, then each batch's own order (as sent, not by episode).
        #expect(store.nextQueued(userId: "dan", now: 30)?.id == "b1-e2")
        #expect(store.nextQueued(userId: "someone-else", now: 30) == nil)
        store.setBatchPaused("b1", true, now: 31)
        #expect(store.nextQueued(userId: "dan", now: 32)?.id == "b2-m")
        #expect(store.row("b1-e2")?.state == .paused)
        store.setBatchPaused("b1", false, now: 33)
        #expect(store.row("b1-e2")?.state == .queued)
        #expect(store.row("b1-e1")?.fileName == "b1-e1.mkv")
    }

    @Test func aFailureWaitsThenNeedsThePersonAndRetryStartsTheCountAgain() throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        store.enqueue(title: "Zodiac", seriesId: "", userId: "dan", manifests: [Self.manifest("b", "b-m", "m", 0)], now: 0)
        store.recordFailure("b-m", message: "The network was lost", maxRetries: 1, now: 1_000)
        #expect(store.row("b-m")?.state == .waiting)
        #expect(store.row("b-m")?.retryAt == 6_000)
        #expect(store.nextQueued(userId: "dan", now: 5_999) == nil)
        #expect(store.nextRetryAt(userId: "dan", now: 2_000) == 6_000)
        #expect(store.nextQueued(userId: "dan", now: 6_000)?.id == "b-m")
        store.recordFailure("b-m", message: "Again", maxRetries: 1, now: 7_000)
        #expect(store.row("b-m")?.state == .failed && store.row("b-m")?.error == "Again")
        store.retry("b-m", now: 8_000)
        #expect(store.row("b-m")?.state == .queued && store.row("b-m")?.attempts == 0)
    }

    @Test func aFinishedFileMustBeExactlyItsSizeAndAMissingOneIsNeverPlayed() throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        store.enqueue(title: "Zodiac", seriesId: "", userId: "dan", manifests: [Self.manifest("b", "b-m", "m", 0, size: 4)], now: 0)
        let row = try #require(store.row("b-m"))
        try Data([1, 2, 3]).write(to: store.mediaFile(row))
        store.finish("b-m", now: 1)
        #expect(store.row("b-m")?.state == .failed)
        try Data([1, 2, 3, 4]).write(to: store.mediaFile(row))
        store.retry("b-m", now: 2)
        store.finish("b-m", now: 3)
        #expect(store.row("b-m")?.state == .complete)
        #expect(store.completedForItem("m", userId: "dan", now: 4)?.id == "b-m")
        #expect(store.storedBytes(userId: "dan") == 4)
        try FileManager.default.removeItem(at: store.mediaFile(row))
        #expect(store.completedForItem("m", userId: "dan", now: 5) == nil)
        #expect(store.row("b-m")?.state == .failed)
    }

    @Test func cancellingABatchCanKeepItsFinishedEpisodesAndRemovingDeletesTheFiles() throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        store.enqueue(title: "Lanterns", seriesId: "l", userId: "dan",
                      manifests: [Self.manifest("b", "b-e1", "e1", 1, size: 2), Self.manifest("b", "b-e2", "e2", 2, size: 2)],
                      now: 0)
        let first = try #require(store.row("b-e1"))
        try Data([1, 2]).write(to: store.mediaFile(first))
        try Data("subtitle".utf8).write(to: store.subtitleFile(first, track: PlaybackTrack(index: 3, codec: "srt")))
        store.finish("b-e1", now: 1)
        store.cancelBatch("b", removeCompleted: false)
        #expect(store.row("b-e2") == nil)
        #expect(store.row("b-e1")?.state == .complete)
        #expect(store.batches(userId: "dan").first?.jobs.count == 1)
        store.remove("b-e1")
        #expect(store.row("b-e1") == nil)
        #expect(store.batches(userId: "dan").isEmpty)
        #expect(!FileManager.default.fileExists(atPath: store.mediaFile(first).path))
        #expect(!FileManager.default.fileExists(atPath: store.subtitleFile(first, track: PlaybackTrack(index: 3, codec: "srt")).path))
    }

    @Test func everythingIsReadBackAfterTheAppStartsAgainAndAnInterruptedTransferQueuesAgain() throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        do {
            let store = OfflineStore(root: root)
            store.enqueue(title: "Lanterns", seriesId: "l", userId: "dan",
                          manifests: [Self.manifest("b", "b-e1", "e1", 1), Self.manifest("b", "b-e2", "e2", 2)], now: 0)
            store.updateProgress("b-e1", bytes: 400, now: 1, persist: true)
            store.updateProgress("b-e2", bytes: 9, now: 1, persist: false)
            store.rememberPlayback(userId: "dan", itemId: "e1", positionMillis: 60_000, durationMillis: 1_200_000,
                                   completed: false, now: 5, eventKey: "k1")
        }
        let again = OfflineStore(root: root)
        #expect(again.row("b-e1")?.bytesDownloaded == 400)
        #expect(again.row("b-e1")?.state == .downloading)
        #expect(again.row("b-e1")?.manifest.item.id == "e1")
        // Bytes not written down are not kept: the transfer tells again.
        #expect(again.row("b-e2")?.bytesDownloaded == 0)
        #expect(again.outbox(userId: "dan").map(\.clientEventKey) == ["k1"])
        again.requeueInterrupted(keeping: [], now: 6)
        #expect(again.row("b-e1")?.state == .queued)
    }

    @Test func watchesAreKeptPerProfileOneWaitingPerItemAndTheServersLaterOneIsTaken() throws {
        let root = Self.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        store.rememberPlayback(userId: "dan", itemId: "e1", positionMillis: 60_000, durationMillis: 1_200_000, completed: false,
                               now: 10, eventKey: "k1")
        store.rememberPlayback(userId: "dan", itemId: "e1", positionMillis: 90_000, durationMillis: 1_200_000, completed: false,
                               now: 20, eventKey: "k2")
        store.rememberPlayback(userId: "hadas", itemId: "e1", positionMillis: 5_000, durationMillis: 1_200_000, completed: false,
                               now: 30, eventKey: "k3")
        #expect(store.outbox(userId: "dan").map(\.clientEventKey) == ["k2"])
        #expect(store.progress(itemIds: ["e1"], userId: "dan")["e1"]?.positionMillis == 90_000)
        #expect(store.progress(itemIds: ["e1"], userId: "hadas")["e1"]?.positionMillis == 5_000)
        store.removeOutbox(keys: ["k2"])
        #expect(store.outbox(userId: "dan").isEmpty)
        // A later watch elsewhere replaces this device's; an earlier one does not.
        store.adoptServerWatch(userId: "dan", itemId: "e1",
                               server: OfflineCatalogProgress(positionMillis: 600_000, durationMillis: 1_200_000, updatedAtMillis: 15))
        #expect(store.progress(itemIds: ["e1"], userId: "dan")["e1"]?.positionMillis == 90_000)
        store.adoptServerWatch(userId: "dan", itemId: "e1",
                               server: OfflineCatalogProgress(positionMillis: 600_000, durationMillis: 1_200_000, updatedAtMillis: 25))
        #expect(store.progress(itemIds: ["e1"], userId: "dan")["e1"]?.positionMillis == 600_000)
        #expect(store.outbox(userId: "dan").isEmpty)
    }

    // MARK: The picker

    @Test func nextEpisodesStartWherePlayWouldAndSkipWatchedOnes() {
        let items = [Self.selection("e1", played: true), Self.selection("e2"), Self.selection("e3", played: true),
                     Self.selection("e4"), Self.selection("e5")]
        #expect(OfflineSelection.next(2, among: items, playTargetId: "e2").map(\.id) == ["e2", "e4"])
        // Without a target, from the first unwatched.
        #expect(OfflineSelection.next(3, among: items, playTargetId: "").map(\.id) == ["e2", "e4", "e5"])
        // A target already watched (a rewatch) is still where it starts.
        #expect(OfflineSelection.next(1, among: items, playTargetId: "e3").map(\.id) == ["e3"])
    }

    @Test func thePickerCountsItsChoiceAndAsksInWords() {
        let items = [Self.selection("e1", size: 1_000), Self.selection("e2", size: 3_000)]
        #expect(OfflineSelection.counter(["e1", "e2"], among: items) == "2 selected · \(Fmt.bytes(4_000))")
        #expect(OfflineSelection.confirmTitle(1) == "Download 1 episode?")
        #expect(OfflineSelection.confirmTitle(3) == "Download 3 episodes?")
        #expect(OfflineSelection.confirmDetail(bytes: 4_000, free: 9_000, source: OfflineSource(container: "mkv")).hasSuffix("· MKV"))
        let selectable = OfflineSelection.selectable([OfflineSelectionSeason(season: LibraryItem(id: "s", type: "season", title: ""),
                                                                             episodes: items)]) { $0 == "e1" }
        #expect(selectable.map(\.item.id) == ["e2"])
    }

    @Test func keysAreInTheHubsAlphabet() throws {
        let batch = OfflineSelection.batchKey(now: 1_790_000_000_000, for: "8f2c-41ab/x")
        #expect(batch == "offline-1790000000000-8f2c41ab")
        let item = OfflineSelection.itemKey(batchKey: batch, itemId: "0123456789abcdef0123")
        #expect(item == batch + "-0123456789ab")
        let allowed = try Regex("^[A-Za-z0-9._:-]{1,120}$")
        #expect(item.wholeMatch(of: allowed) != nil)
        #expect(OfflinePlayback.eventKey(itemId: "0123456789abcdef", now: 5).wholeMatch(of: allowed) != nil)
    }

    // MARK: Playing a download

    @Test func aDownloadPlaysFromItsFileWhereItWasLeftWithItsNeighbours() throws {
        var first = Self.episode("s1e1", "lanterns", "Lanterns", "season-1", 1, 1)
        var second = Self.episode("s1e2", "lanterns", "Lanterns", "season-1", 1, 2)
        second.manifest.item.runtimeSeconds = 1_200
        second.manifest.item.positionSeconds = 120
        second.manifest.source = OfflineSource(id: "src", container: "mp4", mimeType: "video/mp4", sizeBytes: 100, tracks: [
            PlaybackTrack(index: 1, type: "Audio", label: "English"),
            PlaybackTrack(index: 2, type: "Audio", label: "Japanese", isDefault: true),
            PlaybackTrack(index: 3, type: "Subtitle", label: "English", codec: "mov_text"),
        ])
        second.manifest.subtitles = [OfflineSubtitle(track: PlaybackTrack(index: 4, type: "Subtitle", codec: "srt"), url: "/s/4"),
                                     OfflineSubtitle(track: PlaybackTrack(index: 5, type: "Subtitle", codec: "srt"), url: "/s/5")]
        let third = Self.episode("s1e3", "lanterns", "Lanterns", "season-1", 1, 3)
        first.manifest.item.runtimeSeconds = 1_200
        let file = URL(fileURLWithPath: "/tmp/s1e2.mp4")
        let local = URL(fileURLWithPath: "/tmp/s1e2-4.srt")
        // Never played here: where the server had it when it was downloaded.
        let plan = OfflinePlayback.plan(second, file: file, saved: nil, mode: .resume, siblings: [third, first, second],
                                        subtitles: [4: local])
        #expect(plan.sessionId == "offline:s1e2" && OfflinePlayback.isOffline(plan.sessionId))
        #expect(plan.mediaUrl == file.absoluteString)
        #expect(plan.positionMillis == 120_000)
        #expect(plan.durationMillis == 1_200_000)
        #expect(plan.audioTracks.map(\.index) == [1, 2])
        #expect(plan.selectedAudioIndex == 2)
        // Embedded subtitles, and the files beside it that arrived.
        #expect(plan.subtitleTracks.map(\.index) == [3, 4])
        #expect(plan.subtitleTracks.last?.externalUrl == local.absoluteString)
        #expect(plan.previousItem?.id == "s1e1" && plan.nextItem?.id == "s1e3")
        // A watch here wins, and Start over is the beginning.
        let saved = OfflineCatalogProgress(positionMillis: 600_000, durationMillis: 1_200_000, updatedAtMillis: 1)
        #expect(OfflinePlayback.plan(second, file: file, saved: saved, mode: .resume, siblings: [], subtitles: [:]).positionMillis
                == 600_000)
        #expect(OfflinePlayback.plan(second, file: file, saved: saved, mode: .restart, siblings: [], subtitles: [:]).positionMillis == 0)
    }

    // MARK: Plumbing

    static func folder() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("offline-tests-\(UUID().uuidString)", isDirectory: true)
    }

    static func row(_ id: String, _ item: LibraryItem, state: OfflineState = .complete, done: Int64 = 100,
                    total: Int64 = 100) -> OfflineRow {
        OfflineRow(id: id, batchId: "batch-\(id)", userId: "user",
                   manifest: OfflineManifest(clientItemKey: id, item: item, source: OfflineSource(sizeBytes: total)),
                   state: state, bytesDownloaded: done, totalBytes: total, fileName: id + ".mkv", updatedAt: 1)
    }

    static func episode(_ id: String, _ seriesId: String, _ seriesTitle: String, _ seasonId: String, _ season: Int,
                        _ number: Int) -> OfflineRow {
        row(id, LibraryItem(id: id, type: "episode", title: "Episode \(number)", seriesTitle: seriesTitle, seriesId: seriesId,
                            seasonId: seasonId, indexNumber: number, seasonNumber: season))
    }

    static func movie(_ id: String, _ title: String, state: OfflineState = .complete, done: Int64 = 100,
                      total: Int64 = 100) -> OfflineRow {
        row(id, LibraryItem(id: id, type: "movie", title: title), state: state, done: done, total: total)
    }

    static func manifest(_ batch: String, _ key: String, _ itemId: String, _ number: Int,
                         size: Int64 = 1_000) -> (manifest: OfflineManifest, json: Data) {
        let manifest = OfflineManifest(grantId: "g-" + key, batchKey: batch, clientItemKey: key,
                                       item: LibraryItem(id: itemId, type: number == 0 ? "movie" : "episode", title: itemId,
                                                         seriesId: number == 0 ? "" : "l", indexNumber: number,
                                                         seasonNumber: number == 0 ? 0 : 1),
                                       source: OfflineSource(id: "src", container: "mkv", sizeBytes: size),
                                       mediaUrl: "/v1/offline/grants/g-\(key)/media")
        let json = #"{"grantId":"g-\#(key)","batchKey":"\#(batch)","clientItemKey":"\#(key)","item":{"id":"\#(itemId)","type":"\#(number == 0 ? "movie" : "episode")","title":"\#(itemId)","seriesId":"\#(number == 0 ? "" : "l")","indexNumber":\#(number),"seasonNumber":\#(number == 0 ? 0 : 1)},"source":{"id":"src","container":"mkv","sizeBytes":\#(size)},"mediaUrl":"/v1/offline/grants/g-\#(key)/media"}"#
        return (manifest, Data(json.utf8))
    }

    static func selection(_ id: String, played: Bool = false, size: Int64 = 1_000) -> OfflineSelectionItem {
        OfflineSelectionItem(item: LibraryItem(id: id, type: "episode", title: id, played: played), estimatedSizeBytes: size,
                             available: true)
    }
}
