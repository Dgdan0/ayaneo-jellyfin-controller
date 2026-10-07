import Foundation
import Testing
@testable import HubKit

/// Apple downloads (#5, option 1): the MP4 the hub makes on the PC, its
/// manifest, its status, the device's steps at each answer and the words
/// for them, held to the contract in the issue.
struct OfflineAppleTests {
    // MARK: The contract's shapes

    /// The manifest in #5's contract, item and source tracks abbreviated as there.
    static let manifestJSON = #"""
    {"grantId":"9d1f6c0e5b7a4f3c8e2d1a0b9c8d7e6f","batchKey":"batch-1","clientItemKey":"episode-1","expiresAt":1794000000000,
     "item":{"id":"cccccccccccccccccccccccccccccccc","type":"episode","title":"Arrival"},
     "source":{"id":"a62d4bee14ba9965a8900624cd288e12","name":"Original 1080p","container":"mkv",
               "mimeType":"video/x-matroska","sizeBytes":8123456789,"bitrate":8000000,"tracks":[]},
     "mediaUrl":"/v1/offline/grants/9d1f6c0e5b7a4f3c8e2d1a0b9c8d7e6f/media","subtitles":[],"format":"apple",
     "apple":{"container":"mp4","mimeType":"video/mp4","estimatedSizeBytes":7900000000,
       "statusUrl":"/v1/offline/grants/9d1f6c0e5b7a4f3c8e2d1a0b9c8d7e6f/status",
       "video":{"sourceIndex":0,"codec":"hevc","outputCodec":"hevc","tag":"hvc1","converted":false,"width":1920,"height":1080},
       "audio":[
         {"sourceIndex":1,"language":"eng","label":"English - E-AC3 - 5.1 - Default","codec":"eac3","outputCodec":"eac3",
          "channels":6,"outputChannels":6,"converted":false,"default":true},
         {"sourceIndex":2,"language":"rus","label":"Russian - DTS - 5.1","codec":"dts","outputCodec":"aac",
          "channels":6,"outputChannels":6,"converted":true,"reason":"unsupported_codec","default":false}],
       "subtitles":[
         {"sourceIndex":3,"language":"eng","label":"English - SubRip","codec":"subrip","outputCodec":"mov_text",
          "external":false,"forced":false,"hearingImpaired":false,"available":true},
         {"sourceIndex":4,"language":"heb","label":"Hebrew - SubRip","codec":"subrip","outputCodec":"mov_text",
          "external":true,"forced":false,"hearingImpaired":false,"available":true},
         {"sourceIndex":5,"language":"fra","label":"French - PGSSUB","codec":"hdmv_pgs_subtitle",
          "external":false,"forced":false,"hearingImpaired":false,"available":false,"reason":"picture_subtitle"}]}}
    """#

    @Test func theAppleManifestReadsAsTheContractWritesIt() throws {
        let manifest = try JSONDecoder().decode(OfflineManifest.self, from: Data(Self.manifestJSON.utf8))
        #expect(manifest.isApple && manifest.format == "apple")
        let apple = try #require(manifest.apple)
        #expect(apple.estimatedSizeBytes == 7_900_000_000 && manifest.expectedBytes == 7_900_000_000)
        #expect(manifest.container == "mp4" && apple.mimeType == "video/mp4")
        #expect(apple.statusUrl == "/v1/offline/grants/9d1f6c0e5b7a4f3c8e2d1a0b9c8d7e6f/status")
        #expect(apple.video.tag == "hvc1" && !apple.video.converted && apple.video.height == 1080)
        // The MP4's order: the second audio option is the Russian track, now AAC.
        #expect(apple.audio.map(\.language) == ["eng", "rus"])
        #expect(apple.audio[0].isDefault && !apple.audio[1].isDefault)
        #expect(apple.audio[1].converted && apple.audio[1].outputCodec == "aac" && apple.audio[1].reason == "unsupported_codec")
        // The French picture subtitle is not in it; the two text ones are, in order.
        #expect(apple.keptSubtitles.map(\.sourceIndex) == [3, 4])
        #expect(apple.subtitles[2].reason == "picture_subtitle")
        // The original is still described as it is.
        #expect(manifest.source.container == "mkv" && manifest.source.sizeBytes == 8_123_456_789)
        #expect(manifest.subtitles.isEmpty)
    }

    @Test func aHubOlderThanTheAppleFormatAnswersTheOriginalWhichIsNotOne() throws {
        let original = #"{"grantId":"g","clientItemKey":"k","item":{"id":"i","type":"movie","title":"T"},"source":{"container":"mkv","sizeBytes":10},"mediaUrl":"/m","subtitles":[]}"#
        let manifest = try JSONDecoder().decode(OfflineManifest.self, from: Data(original.utf8))
        #expect(!manifest.isApple && manifest.apple == nil)
        #expect(manifest.container == "mkv" && manifest.expectedBytes == 10)
        // "apple" without the block it promises is not one either.
        let half = #"{"grantId":"g","format":"apple","source":{"container":"mkv"}}"#
        #expect(try !JSONDecoder().decode(OfflineManifest.self, from: Data(half.utf8)).isApple)
    }

    @Test func theStatusReadsEachStateAndEachSaysWhatToDoNext() throws {
        func status(_ json: String) throws -> OfflineGrantStatus {
            try JSONDecoder().decode(OfflineGrantStatus.self, from: Data(json.utf8))
        }
        let preparing = try status(#"{"grantId":"g","format":"apple","state":"preparing","percent":41,"queuePosition":0,"estimatedSizeBytes":7900000000}"#)
        #expect(preparing.state == .preparing && preparing.percent == 41)
        #expect(OfflineTransfer.step(preparing) == .wait(percent: 41, queuePosition: 0, afterMillis: 2_000))
        let ready = try status(#"{"grantId":"g","format":"apple","state":"ready","percent":100,"queuePosition":0,"estimatedSizeBytes":7900000000,"sizeBytes":7712345678,"etag":"\"5c1f0f3e9a7b4d2c8e6a1b3d5f7092ab\""}"#)
        #expect(OfflineTransfer.step(ready) == .download(sizeBytes: 7_712_345_678, etag: "\"5c1f0f3e9a7b4d2c8e6a1b3d5f7092ab\""))
        let failed = try status(#"{"grantId":"g","format":"apple","state":"failed","percent":41,"queuePosition":0,"estimatedSizeBytes":7900000000,"error":{"code":"source_missing","message":"The file for this item is not on the media PC.","retryable":false}}"#)
        #expect(failed.error?.code == "source_missing")
        #expect(OfflineTransfer.step(failed) == .failed(message: "The file for this item is not on the media PC.", retryable: false))
        // In line: asked again less often; a state this app does not know waits too.
        let queued = try status(#"{"grantId":"g","format":"apple","state":"queued","percent":0,"queuePosition":2}"#)
        #expect(OfflineTransfer.step(queued) == .wait(percent: 0, queuePosition: 2, afterMillis: 5_000))
        #expect(OfflineTransfer.step(try status(#"{"state":"paused"}"#)) == .wait(percent: 0, queuePosition: 0, afterMillis: 5_000))
        // 100 is only ready's; a failure without words has the app's own.
        #expect(OfflineTransfer.step(OfflineGrantStatus(state: .preparing, percent: 100))
                == .wait(percent: 99, queuePosition: 0, afterMillis: 2_000))
        #expect(OfflineTransfer.step(OfflineGrantStatus(state: .failed))
                == .failed(message: "The PC could not prepare this download", retryable: false))
    }

    @Test func theBodyAsksForTheFormatOnlyWhenItHasOne() throws {
        let apple = String(decoding: HubEndpoints.json(OfflinePrepareBody(
            batchKey: "b", format: "apple", items: [OfflinePrepareItem(clientItemKey: "k", itemId: "i")])), as: UTF8.self)
        #expect(apple == #"{"batchKey":"b","format":"apple","items":[{"clientItemKey":"k","itemId":"i"}]}"#)
        let original = String(decoding: HubEndpoints.json(OfflinePrepareBody(
            batchKey: "b", items: [OfflinePrepareItem(clientItemKey: "k", itemId: "i")])), as: UTF8.self)
        #expect(!original.contains("format"))
    }

    @Test func theRoutesForAnAppleDownloadAreTheContracts() {
        #expect(HubEndpoints.offlineStatus(grantId: "g1").path == "/v1/offline/grants/g1/status")
        #expect(HubEndpoints.offlineStatus(grantId: "g1").method == .get)
        let release = HubEndpoints.releaseOffline(grantId: "g1")
        #expect(release.path == "/v1/offline/grants/g1/media" && release.method == .delete)
        let retry = HubEndpoints.retryOffline(grantId: "g1")
        #expect(retry.path == "/v1/offline/grants/g1/retry" && retry.method == .post)
        #expect(HubEndpoints.offlineSelection(seriesId: "s1", format: "apple").path == "/v1/offline/series/s1/selection?format=apple")
        #expect(HubEndpoints.offlineSelection(seriesId: "s1").path == "/v1/offline/series/s1/selection")
        let sync = HubEndpoints.syncOfflineProgress(OfflineProgressSyncBody(events: []), user: "u1")
        #expect(sync.user == "u1" && sync.method == .post)
    }

    @Test func theSelectionCarriesTheMP4sEstimateAndWhatIsLost() throws {
        let json = #"{"item":{"id":"e1","type":"episode","title":"Pilot"},"sources":[],"estimatedSizeBytes":900,"available":true,"apple":{"estimatedSizeBytes":900,"videoConverted":true,"audioConverted":1,"omittedSubtitles":2}}"#
        let item = try JSONDecoder().decode(OfflineSelectionItem.self, from: Data(json.utf8))
        #expect(item.apple == OfflineAppleSummary(estimatedSizeBytes: 900, videoConverted: true, audioConverted: 1,
                                                  omittedSubtitles: 2))
        #expect(OfflineAppleNotes.selection([item.apple!, OfflineAppleSummary(omittedSubtitles: 1)])
                == "1 takes longer to prepare · 3 subtitle tracks left out")
        #expect(OfflineAppleNotes.selection([OfflineAppleSummary(), OfflineAppleSummary()]) == nil)
        #expect(OfflineAppleNotes.selection([OfflineAppleSummary(videoConverted: true), OfflineAppleSummary(videoConverted: true)])
                == "2 take longer to prepare")
    }

    // MARK: What a failed request means

    @Test func aFailedRequestForTheFileSaysWhatToDo() {
        func recovery(_ status: Int, _ code: String = "", retryAfter: Int64? = nil, message: String = "words") -> OfflineTransfer.Recovery {
            OfflineTransfer.recovery(HubFailure(.of(status: status), message: message, status: status, code: code,
                                                retryAfterSeconds: retryAfter))
        }
        #expect(recovery(409, "offline_preparing", retryAfter: 3) == .prepareAgain(afterMillis: 3_000))
        #expect(recovery(409, "offline_preparing") == .prepareAgain(afterMillis: 5_000))
        #expect(recovery(409, "offline_failed", message: "The PC could not make it.") == .failed("The PC could not make it."))
        #expect(recovery(409, "source_changed") == .failed(OfflineTransfer.sourceChanged))
        #expect(recovery(410, "grant_expired") == .renew)
        // A rejected token or a ban holds every download, never counted as the download's failure.
        #expect(recovery(401) == .credentials(CredentialGate.Block.rejected.message))
        #expect(recovery(429, retryAfter: 900) == .credentials(FailureKind.banned.message))
        // The client's gate refusing to send says so in its own words.
        let gate = CredentialGate.Block.banned(untilMillis: 600_000, nowMillis: 0)
        #expect(OfflineTransfer.recovery(HubFailure(gate.kind, message: gate.message)) == .credentials(gate.message))
        #expect(OfflineTransfer.recovery(HubFailure(.unauthorized, message: CredentialGate.Block.rejected.message))
                == .credentials(CredentialGate.Block.rejected.message))
        #expect(recovery(429, retryAfter: 2) == .later("words"))
        #expect(recovery(404) == .failed(OfflineTransfer.gone))
        #expect(recovery(403) == .failed(OfflineTransfer.noScope))
        #expect(recovery(503, message: "Jellyfin is down") == .later("Jellyfin is down"))
        #expect(OfflineTransfer.recovery(HubFailure(.noNetwork)) == .later(FailureKind.noNetwork.message))
        #expect(recovery(400, message: "bad range") == .failed("bad range"))
    }

    // MARK: On the device

    @Test func anAppleDownloadWaitsOnThePCThenTakesItsExactSizeAndAnotherFileStartsOver() throws {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        let manifest = try JSONDecoder().decode(OfflineManifest.self, from: Data(Self.manifestJSON.utf8))
        store.enqueue(title: "Arrival", seriesId: "", userId: "u", manifests: [(manifest, Data(Self.manifestJSON.utf8))], now: 1)
        var row = try #require(store.row("episode-1"))
        // Named for the MP4, sized by its estimate until the PC says.
        #expect(row.fileName == "episode-1.mp4" && row.totalBytes == 7_900_000_000 && row.isApple)
        #expect(OfflineQueueLabels.figures(row) == "About \(Fmt.bytes(7_900_000_000))")
        store.setPreparing(row.id, percent: 40, queuePosition: 0, now: 2)
        row = try #require(store.row("episode-1"))
        #expect(row.state == .preparing && row.hubPercent == 40)
        #expect(OfflineQueueLabels.figures(row) == "Preparing on the PC · 40% · About \(Fmt.bytes(7_900_000_000))")
        #expect(OfflineQueueLabels.fraction(row) == 0.4)
        #expect(OfflineQueueLabels.chip(row.state).word == "Preparing")
        // Ready: the exact size, and the ETag that names the file.
        #expect(!store.setReady(row.id, sizeBytes: 7_712_345_678, etag: "\"a\"", now: 3))
        row = try #require(store.row("episode-1"))
        #expect(row.totalBytes == 7_712_345_678 && row.etag == "\"a\"" && row.hubPercent == 100)
        store.updateProgress(row.id, bytes: 1_000, now: 4, persist: true)
        try Data("resume".utf8).write(to: store.resumeFile(row))
        // The same file again goes on; a rebuilt one starts over, its resume data gone.
        #expect(!store.setReady(row.id, sizeBytes: 7_712_345_678, etag: "\"a\"", now: 5))
        #expect(store.row("episode-1")?.bytesDownloaded == 1_000)
        #expect(store.setReady(row.id, sizeBytes: 7_700_000_000, etag: "\"b\"", now: 6))
        row = try #require(store.row("episode-1"))
        #expect(row.bytesDownloaded == 0 && row.totalBytes == 7_700_000_000 && row.etag == "\"b\"")
        #expect(!FileManager.default.fileExists(atPath: store.resumeFile(row).path))
        // A renewed manifest keeps the exact size it was told.
        store.updateManifest(row.id, manifest: manifest, json: Data(Self.manifestJSON.utf8), now: 7)
        #expect(store.row("episode-1")?.totalBytes == 7_700_000_000)
    }

    @Test func aDownloadLeftWaitingOnThePCQueuesAgainAfterARestart() throws {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        let manifest = try JSONDecoder().decode(OfflineManifest.self, from: Data(Self.manifestJSON.utf8))
        store.enqueue(title: "Arrival", seriesId: "", userId: "u", manifests: [(manifest, Data(Self.manifestJSON.utf8))], now: 1)
        store.setPreparing("episode-1", percent: 10, queuePosition: 0, now: 2)
        // Read back as the app starts again: nothing is polling it any more.
        let again = OfflineStore(root: root)
        #expect(again.row("episode-1")?.state == .preparing)
        again.requeueInterrupted(keeping: [], now: 3)
        #expect(again.row("episode-1")?.state == .queued)
        // The one being polled is left alone.
        again.setPreparing("episode-1", percent: 20, queuePosition: 0, now: 4)
        again.requeueInterrupted(keeping: ["episode-1"], now: 5)
        #expect(again.row("episode-1")?.state == .preparing)
        // A paused batch pauses one waiting on the PC too.
        again.setBatchPaused("batch-1", true, now: 6)
        #expect(again.row("episode-1")?.state == .paused)
    }

    @Test func aDownloadHeldBackWaitsWithoutCountingAFailureUntilWokenOrDue() throws {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        store.enqueue(title: "Lanterns", seriesId: "l", userId: "u",
                      manifests: [OfflineTests.manifest("b", "k1", "e1", 1), OfflineTests.manifest("b", "k2", "e2", 2)], now: 1)
        store.wait("k1", reason: OfflineTransfer.waitingForWiFi, until: 100, now: 2)
        let held = try #require(store.row("k1"))
        #expect(held.state == .waiting && held.error == "Waiting for Wi-Fi" && held.attempts == 0)
        // Until it is due, the next one goes first.
        #expect(store.nextQueued(userId: "u", now: 50)?.id == "k2")
        #expect(store.nextRetryAt(userId: "u", now: 50) == 100)
        // Wi-Fi back: it goes again now, ahead in its order.
        store.wakeWaiting(userId: "u", now: 60)
        #expect(store.nextQueued(userId: "u", now: 60)?.id == "k1")
        // Watches to send, by profile, the oldest first.
        store.rememberPlayback(userId: "b", itemId: "e1", positionMillis: 1, durationMillis: 10, completed: false, now: 5,
                               eventKey: "e1-5")
        store.rememberPlayback(userId: "a", itemId: "e2", positionMillis: 1, durationMillis: 10, completed: false, now: 6,
                               eventKey: "e2-6")
        store.rememberPlayback(userId: "b", itemId: "e2", positionMillis: 2, durationMillis: 10, completed: false, now: 7,
                               eventKey: "e2-7")
        #expect(store.outboxUsers() == ["b", "a"])
    }

    @Test func itsPlaceInThePCsLineReadsAsAPlace() {
        var row = OfflineTests.movie("m", "Arrival", state: .preparing, done: 0, total: 100)
        row.hubQueuePosition = 1
        #expect(OfflineQueueLabels.onThePC(row) == "Next on the PC")
        for (position, words) in [(2, "2nd"), (3, "3rd"), (4, "4th"), (11, "11th"), (12, "12th"), (13, "13th"), (21, "21st"),
                                  (22, "22nd"), (23, "23rd"), (101, "101st"), (111, "111th")] {
            row.hubQueuePosition = position
            #expect(OfflineQueueLabels.onThePC(row) == "\(words) in line on the PC")
        }
        row.hubQueuePosition = 0
        row.hubPercent = 73
        #expect(OfflineQueueLabels.onThePC(row) == "Preparing on the PC · 73%")
    }

    @Test func thePersonIsToldWhatWillNotComeAcross() throws {
        let manifest = try JSONDecoder().decode(OfflineManifest.self, from: Data(Self.manifestJSON.utf8))
        let apple = try #require(manifest.apple)
        #expect(OfflineAppleNotes.leftOut(apple) == "French subtitles are left out: they are pictures, which this device cannot show.")
        #expect(OfflineAppleNotes.slower(apple) == nil)
        var two = apple
        two.subtitles.append(OfflineAppleSubtitle(sourceIndex: 6, language: "deu", available: false, reason: "picture_subtitle"))
        two.video.converted = true
        #expect(OfflineAppleNotes.leftOut(two) == "French and German subtitles are left out: they are pictures, which this device cannot show.")
        #expect(OfflineAppleNotes.lines(two).first == "Takes longer to prepare: the picture is converted.")
        // A format it cannot read, in no language anyone named.
        let odd = OfflineApple(subtitles: [OfflineAppleSubtitle(language: "und", available: false, reason: "unsupported_format")])
        #expect(OfflineAppleNotes.leftOut(odd) == "Some subtitles are left out.")
        #expect(OfflineAppleNotes.leftOut(OfflineApple(subtitles: apple.keptSubtitles)) == nil)
        #expect(OfflineSelection.confirmDetail(bytes: 4_000, free: 9_000, source: nil, format: "apple")
                .hasSuffix("An MP4 the PC makes for this device"))
    }

    @Test func anAppleDownloadPlaysTheMP4sOwnTracksInItsOrderWithSubtitlesOff() throws {
        let manifest = try JSONDecoder().decode(OfflineManifest.self, from: Data(Self.manifestJSON.utf8))
        let row = OfflineRow(id: "episode-1", batchId: "batch-1", userId: "u", manifest: manifest, state: .complete,
                             bytesDownloaded: 7_712_345_678, totalBytes: 7_712_345_678, fileName: "episode-1.mp4")
        let file = URL(fileURLWithPath: "/tmp/episode-1.mp4")
        let plan = OfflinePlayback.plan(row, file: file, saved: nil, mode: .resume, siblings: [], subtitles: [:])
        // Each track by the source's own index, so a choice remembered while streaming still fits.
        #expect(plan.audioTracks.map(\.index) == [1, 2])
        #expect(plan.audioTracks.map(\.codec) == ["eac3", "aac"])
        #expect(plan.selectedAudioIndex == 1)
        #expect(plan.subtitleTracks.map(\.index) == [3, 4])
        #expect(plan.subtitleTracks.allSatisfy { !$0.external && $0.codec == "mov_text" })
        #expect(plan.selectedSubtitleIndex == nil)
        #expect(plan.mimeType == "video/mp4" && plan.height == 1080)
        #expect(plan.sources.first?.container == "mp4" && plan.sources.first?.sizeBytes == 7_712_345_678)
        // The nth option of its kind in the file.
        #expect(OfflinePlayback.optionPosition(plan.audioTracks, index: 2) == 1)
        #expect(OfflinePlayback.optionPosition(plan.subtitleTracks, index: 4) == 1)
        #expect(OfflinePlayback.optionPosition(plan.subtitleTracks, index: -1) == nil)
        #expect(OfflinePlayback.optionPosition(plan.subtitleTracks, index: 5) == nil)
    }
}
