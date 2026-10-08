import Foundation
import Testing
@testable import HubKit

/// An Apple download's subtitles kept beside its MP4 as WebVTT and refreshed
/// from the hub (#45): the list as the contract writes it, what is fetched and
/// let go, what an error means, the player's choices, and the whole round
/// against the demo hub's two routes.
struct OfflineSubtitleSyncTests {
    /// The contract's example (#45): a French and a Hebrew sidecar, the
    /// file's own English (stream 2 inside it, Jellyfin's 4) and a French
    /// picture subtitle left out.
    static let listJSON = #"""
    {"grantId":"9d1f6c0e5b7a4f3c8e2d1a0b9c8d7e6f","format":"apple","tracks":[
      {"key":"ext-fra","sourceIndex":0,"language":"fra","title":"","label":"French - SubRip - External","codec":"subrip",
       "external":true,"default":false,"forced":false,"hearingImpaired":false,"rtl":false,
       "signature":"07be5d2c91a4e3f60b8c7d1e2f3a4b5c",
       "url":"/v1/offline/grants/9d1f6c0e5b7a4f3c8e2d1a0b9c8d7e6f/subtitle-tracks/ext-fra"},
      {"key":"ext-heb","sourceIndex":1,"language":"heb","title":"","label":"Hebrew - SubRip - External","codec":"subrip",
       "external":true,"default":false,"forced":false,"hearingImpaired":false,"rtl":true,
       "signature":"a91d03c47be25f6810d4c3b2a1908e7f",
       "url":"/v1/offline/grants/9d1f6c0e5b7a4f3c8e2d1a0b9c8d7e6f/subtitle-tracks/ext-heb","mp4Index":0},
      {"key":"emb-2","sourceIndex":4,"language":"eng","title":"","label":"English - SubRip","codec":"subrip",
       "external":false,"default":false,"forced":false,"hearingImpaired":false,"rtl":false,
       "signature":"3f1c9a6e0b2d47a8c5e7f1a2b3c4d5e6",
       "url":"/v1/offline/grants/9d1f6c0e5b7a4f3c8e2d1a0b9c8d7e6f/subtitle-tracks/emb-2","mp4Index":1}],
     "omitted":[{"sourceIndex":5,"language":"fra","label":"French - PGSSUB","codec":"hdmv_pgs_subtitle","external":false,
                 "reason":"picture_subtitle"}]}
    """#

    private static func list() throws -> OfflineSubtitleList {
        try JSONDecoder().decode(OfflineSubtitleList.self, from: Data(listJSON.utf8))
    }

    /// Each listed track kept as fetched, its ETag the quoted signature.
    private static func kept(_ list: OfflineSubtitleList) -> [OfflineKeptSubtitle] {
        list.tracks.map { OfflineKeptSubtitle($0, etag: "\"\($0.signature)\"") }
    }

    // MARK: The list

    @Test func theListReadsAsTheContractWritesIt() throws {
        let list = try Self.list()
        #expect(list.grantId == "9d1f6c0e5b7a4f3c8e2d1a0b9c8d7e6f" && list.format == "apple")
        #expect(list.tracks.map(\.key) == ["ext-fra", "ext-heb", "emb-2"])
        #expect(list.tracks.map(\.mp4Index) == [nil, 0, 1])
        #expect(list.tracks.map(\.rtl) == [false, true, false])
        #expect(list.tracks.map(\.external) == [true, true, false])
        let english = list.tracks[2]
        #expect(english.sourceIndex == 4 && english.language == "eng" && english.label == "English - SubRip")
        #expect(english.signature == "3f1c9a6e0b2d47a8c5e7f1a2b3c4d5e6" && english.url.hasSuffix("/subtitle-tracks/emb-2"))
        // A picture subtitle has no key: nothing to keep for it.
        #expect(list.omitted.map(\.key) == [""])
        #expect(list.omitted.first?.reason == "picture_subtitle" && list.omitted.first?.language == "fra")
        // A kept track remembers the facts the player needs, and the ETag it came with.
        let hebrew = OfflineKeptSubtitle(list.tracks[1], etag: "\"a91d03c47be25f6810d4c3b2a1908e7f\"")
        #expect(hebrew.rtl && hebrew.mp4Index == 0 && hebrew.language == "heb" && hebrew.label == "Hebrew - SubRip - External")
        let stored = try JSONDecoder().decode(OfflineKeptSubtitles.self,
                                              from: JSONEncoder().encode(OfflineKeptSubtitles(tracks: [hebrew], checkedAt: 7)))
        #expect(stored == OfflineKeptSubtitles(tracks: [hebrew], checkedAt: 7))
    }

    // MARK: What is fetched and let go

    @Test func onlyANewOrChangedTrackIsFetched() throws {
        let list = try Self.list()
        // Nothing kept yet: every track.
        #expect(OfflineSubtitleSync.plan(kept: [], list: list, hasFile: { _ in false }).fetch.map(\.key)
                == ["ext-fra", "ext-heb", "emb-2"])
        // Kept as the ETags came: nothing to do.
        let kept = Self.kept(list)
        let settled = OfflineSubtitleSync.plan(kept: kept, list: list, hasFile: { _ in true })
        #expect(settled.fetch.isEmpty && settled.remove.isEmpty)
        // Bazarr replaced the Hebrew in place: the same key, another signature, only it.
        var replaced = list
        replaced.tracks[1].signature = "b00000000000000000000000000000b0"
        #expect(OfflineSubtitleSync.plan(kept: kept, list: replaced, hasFile: { _ in true }).fetch.map(\.key) == ["ext-heb"])
        // A kept file lost from the disk is fetched again.
        #expect(OfflineSubtitleSync.plan(kept: kept, list: list, hasFile: { $0 != "emb-2" }).fetch.map(\.key) == ["emb-2"])
    }

    @Test func aTrackGoneFromTheListIsLetGoAndOneUnreadableForNowIsKept() throws {
        let list = try Self.list()
        let kept = Self.kept(list)
        // The French sidecar removed and a German one added: Jellyfin's number
        // moves for every track, the keys do not.
        var moved = list
        moved.tracks.removeFirst()
        for position in moved.tracks.indices { moved.tracks[position].sourceIndex -= 1 }
        moved.tracks.append(OfflineSubtitleTrack(key: "ext-deu", sourceIndex: 1, language: "deu", signature: "d", url: "/d"))
        let plan = OfflineSubtitleSync.plan(kept: kept, list: moved, hasFile: { _ in true })
        #expect(plan.fetch.map(\.key) == ["ext-deu"])
        #expect(plan.remove == ["ext-fra"])
        // The Hebrew cannot be had just now: it keeps its key, and its file.
        var unreadable = list
        let hebrew = unreadable.tracks.remove(at: 1)
        unreadable.omitted.append(OfflineSubtitleOmitted(key: hebrew.key, sourceIndex: hebrew.sourceIndex, language: "heb",
                                                         label: hebrew.label, reason: "unreadable"))
        let waiting = OfflineSubtitleSync.plan(kept: kept, list: unreadable, hasFile: { _ in true })
        #expect(waiting.fetch.isEmpty && waiting.remove.isEmpty)
        let merged = OfflineSubtitleSync.merged(kept: kept, list: unreadable, fetched: [:], hasFile: { _ in true })
        #expect(merged.map(\.key) == ["ext-fra", "emb-2", "ext-heb"])
        #expect(merged.last == kept[1])
    }

    @Test func whatIsKeptTakesTheETagReceivedAndTheListsNewFacts() {
        let list = OfflineSubtitleList(tracks: [
            OfflineSubtitleTrack(key: "ext-eng", language: "eng", label: "English", signature: "b"),
            OfflineSubtitleTrack(key: "ext-heb", language: "heb", label: "Hebrew (Bazarr)", rtl: true, signature: "h", mp4Index: 0),
            OfflineSubtitleTrack(key: "ext-deu", language: "deu", signature: "d"),
        ])
        let kept = [OfflineKeptSubtitle(key: "ext-eng", language: "eng", label: "English", etag: "\"a\""),
                    OfflineKeptSubtitle(key: "ext-heb", language: "heb", label: "Hebrew", etag: "\"h\"")]
        // English came back newer than the list said; German failed to come.
        let merged = OfflineSubtitleSync.merged(kept: kept, list: list, fetched: ["ext-eng": "\"c\""],
                                                hasFile: { $0 != "ext-deu" })
        #expect(merged.map(\.key) == ["ext-eng", "ext-heb"])
        #expect(merged.map(\.etag) == ["\"c\"", "\"h\""])
        #expect(merged[1].label == "Hebrew (Bazarr)" && merged[1].rtl && merged[1].mp4Index == 0)
        // So the next list fetches the English again (its ETag is not the list's), and the German.
        #expect(OfflineSubtitleSync.plan(kept: merged, list: list, hasFile: { $0 != "ext-deu" }).fetch.map(\.key)
                == ["ext-eng", "ext-deu"])
        // A failed refetch keeps the file and the ETag it has.
        #expect(OfflineSubtitleSync.merged(kept: kept, list: list, fetched: [:], hasFile: { _ in true }).map(\.etag)
                == ["\"a\"", "\"h\""])
    }

    @Test func anErrorKeepsEverythingAndAnExpiredGrantIsRenewed() {
        #expect(OfflineSubtitleSync.failure(status: 410) == .expired)
        for status in [403, 404, 409, 422] {
            #expect(OfflineSubtitleSync.failure(status: status) == .settled)
        }
        let away: [Int?] = [nil, 429, 500, 502, 503]
        for status in away {
            #expect(OfflineSubtitleSync.failure(status: status) == .unreachable)
        }
    }

    @Test func aDownloadIsAskedAboutAgainAfterAWhileAndETagsReadAsSignatures() {
        #expect(OfflineSubtitleSync.due(nil, now: 5))
        #expect(OfflineSubtitleSync.due(OfflineKeptSubtitles(), now: 5))
        let asked = OfflineKeptSubtitles(checkedAt: 1_000)
        #expect(!OfflineSubtitleSync.due(asked, now: 1_000 + OfflineSubtitleSync.recheckMillis - 1))
        #expect(OfflineSubtitleSync.due(asked, now: 1_000 + OfflineSubtitleSync.recheckMillis))
        // A clock set back asks at once.
        #expect(OfflineSubtitleSync.due(asked, now: 999))
        #expect(OfflineSubtitleSync.signature("\"a91d\"") == "a91d")
        #expect(OfflineSubtitleSync.signature("W/\"a91d\"") == "a91d")
        #expect(OfflineSubtitleSync.signature("a91d") == "a91d")
    }

    // MARK: Playing

    @Test func keptFilesAreDrawnByTheAppAndTheMP4sOwnOptionsStayForTheRest() throws {
        let manifest = try JSONDecoder().decode(OfflineManifest.self, from: Data(OfflineAppleTests.manifestJSON.utf8))
        let row = OfflineRow(id: "episode-1", batchId: "batch-1", userId: "u", manifest: manifest, state: .complete,
                             bytesDownloaded: 7_712_345_678, totalBytes: 7_712_345_678, fileName: "episode-1.mp4")
        let folder = URL(fileURLWithPath: "/tmp/subtitles", isDirectory: true)
        let hebrewFile = folder.appendingPathComponent("episode-1-kept-ext-heb.vtt")
        // The Hebrew is the MP4's second option (sourceIndex 4); German came after the download.
        let kept: [(track: OfflineKeptSubtitle, file: URL)] = [
            (OfflineKeptSubtitle(key: "ext-heb", language: "heb", label: "Hebrew - SubRip - External", rtl: true, mp4Index: 1,
                                 etag: "\"h\""), hebrewFile),
            (OfflineKeptSubtitle(key: "ext-deu", language: "deu", label: "German - SubRip - External", etag: "\"d\""),
             folder.appendingPathComponent("episode-1-kept-ext-deu.vtt")),
        ]
        var plan = OfflinePlayback.plan(row, file: URL(fileURLWithPath: "/tmp/episode-1.mp4"), saved: nil, mode: .resume,
                                        siblings: [], subtitles: [:], kept: kept)
        // The Hebrew keeps the index a choice made while streaming remembers; German is a new choice.
        #expect(plan.subtitleTracks.map(\.index) == [4, OfflineSubtitleSync.keptIndexBase + 1, 3])
        #expect(plan.subtitleTracks.map(\.language) == ["heb", "deu", "eng"])
        #expect(plan.subtitleTracks.map(\.external) == [true, true, false])
        #expect(plan.subtitleTracks.map(\.fileOption) == [nil, nil, 0])
        #expect(plan.subtitleTracks[0].externalUrl == hebrewFile.absoluteString && plan.subtitleTracks[0].codec == "webvtt")
        #expect(plan.subtitleTracks[2].codec == "mov_text" && plan.subtitleTracks[2].externalUrl.isEmpty)
        // A kept file is the app's to draw, with none of the MP4's chosen; the
        // English is still the MP4's first option.
        #expect(OfflinePlayback.optionPosition(plan.subtitleTracks, index: 4) == nil)
        #expect(OfflinePlayback.optionPosition(plan.subtitleTracks, index: OfflineSubtitleSync.keptIndexBase + 1) == nil)
        #expect(OfflinePlayback.optionPosition(plan.subtitleTracks, index: 3) == 0)
        plan.selectedSubtitleIndex = 4
        #expect(PlaybackChoices.drawnSubtitle(plan)?.externalUrl == hebrewFile.absoluteString)
        plan.selectedSubtitleIndex = 3
        #expect(PlaybackChoices.drawnSubtitle(plan) == nil)
        // With nothing kept, the MP4's own options as before.
        let bare = OfflinePlayback.plan(row, file: URL(fileURLWithPath: "/tmp/episode-1.mp4"), saved: nil, mode: .resume,
                                        siblings: [], subtitles: [:])
        #expect(bare.subtitleTracks.map(\.index) == [3, 4])
        #expect(bare.subtitleTracks.map(\.fileOption) == [0, 1])
    }

    // MARK: On the device

    @Test func theFilesAreKeptBesideTheDownloadAndGoWithIt() throws {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        let json = Data(OfflineAppleTests.manifestJSON.utf8)
        let manifest = try JSONDecoder().decode(OfflineManifest.self, from: json)
        store.enqueue(title: "Arrival", seriesId: "", userId: "u", manifests: [(manifest, json)], now: 1)
        let row = try #require(store.row("episode-1"))
        #expect(store.keptSubtitles(row) == nil && store.keptSubtitleFiles(row).isEmpty)
        // Written whole, then replaced whole; nothing half-written left beside it.
        try store.writeKeptSubtitle(row, key: "ext-heb", data: Data("WEBVTT\n\nfirst".utf8))
        try store.writeKeptSubtitle(row, key: "ext-heb", data: Data("WEBVTT\n\nsecond".utf8))
        let file = store.keptSubtitleFile(row, key: "ext-heb")
        #expect(String(decoding: try Data(contentsOf: file), as: UTF8.self) == "WEBVTT\n\nsecond")
        #expect(file.deletingLastPathComponent() == store.subtitleFolder)
        let names = try FileManager.default.contentsOfDirectory(atPath: store.subtitleFolder.path)
        #expect(!names.contains { $0.hasSuffix(".part") })
        // Only the kept entries whose files are there are played.
        let hebrew = OfflineKeptSubtitle(key: "ext-heb", language: "heb", rtl: true, etag: "\"h\"")
        let english = OfflineKeptSubtitle(key: "emb-2", language: "eng", etag: "\"e\"")
        store.saveKeptSubtitles(row, OfflineKeptSubtitles(tracks: [hebrew, english], checkedAt: 5))
        #expect(store.keptSubtitles(row) == OfflineKeptSubtitles(tracks: [hebrew, english], checkedAt: 5))
        #expect(store.keptSubtitleFiles(row).map(\.track.key) == ["ext-heb"])
        store.removeKeptSubtitle(row, key: "ext-heb")
        #expect(!store.hasKeptSubtitle(row, key: "ext-heb"))
        // Remove download removes them too.
        try store.writeKeptSubtitle(row, key: "emb-2", data: Data("WEBVTT\n".utf8))
        store.remove(row.id)
        #expect(!FileManager.default.fileExists(atPath: store.keptSubtitleFile(row, key: "emb-2").path))
        #expect(store.keptSubtitles(row) == nil)
        #expect(try FileManager.default.contentsOfDirectory(atPath: store.subtitleFolder.path).isEmpty)
    }

    // MARK: Against the demo hub

    private let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                                screens: DemoTransport(), sleep: { _ in })
    /// Inception in the demo library.
    private let movie = String(format: "%032lx", 0xdeb0_0000 + 14)

    /// A downloaded Apple grant of Inception, its own for this test, kept in
    /// `store` (or only described, without one).
    private func download(into store: OfflineStore? = nil) async throws -> OfflineRow {
        let key = "subs-" + UUID().uuidString.lowercased()
        let body = OfflinePrepareBody(batchKey: key, format: OfflineFormat.apple,
                                      items: [OfflinePrepareItem(clientItemKey: key, itemId: movie)])
        let entry = try #require(OfflineManifest.list(from: try await hub.data(HubEndpoints.prepareOffline(body))).first)
        #expect(entry.manifest.isApple)
        guard let store else {
            return OfflineRow(id: key, batchId: key, userId: "u", manifest: entry.manifest, state: .complete,
                              bytesDownloaded: 1, totalBytes: 1, fileName: key + ".mp4")
        }
        store.enqueue(title: "Inception", seriesId: "", userId: "u", manifests: [entry], now: 1)
        return try #require(store.row(key))
    }

    private static func text(_ store: OfflineStore, _ row: OfflineRow, _ key: String) -> String? {
        (try? Data(contentsOf: store.keptSubtitleFile(row, key: key))).map { String(decoding: $0, as: UTF8.self) }
    }

    @Test func theDemoHubListsAnAppleGrantsSubtitlesAndServesEachAsWebVTT() async throws {
        let row = try await download()
        let grantId = row.manifest.grantId
        let list = try await hub.fetch(HubEndpoints.offlineSubtitleTracks(grantId: grantId, user: "u"), as: OfflineSubtitleList.self)
        #expect(list.grantId == grantId && list.format == "apple")
        #expect(list.tracks.map(\.key) == ["ext-eng", "ext-heb"])
        #expect(list.tracks.map(\.rtl) == [false, true])
        #expect(list.tracks.allSatisfy { $0.mp4Index == nil && $0.signature.count == 32 })
        #expect(list.omitted.map(\.reason) == ["picture_subtitle"])
        // The WebVTT with its signature as a strong ETag; asked with it, 304.
        let hebrew = list.tracks[1]
        guard case .file(let data, let etag) = try await hub.file(HubEndpoints.offlineSubtitleTrack(hebrew.url, user: "u")) else {
            Issue.record("The Hebrew track did not come")
            return
        }
        #expect(String(decoding: data, as: UTF8.self).hasPrefix("WEBVTT"))
        #expect(String(decoding: data, as: UTF8.self).contains("כתובית שנשמרה ליד ההורדה"))
        #expect(etag == "\"\(hebrew.signature)\"")
        #expect(try await hub.file(HubEndpoints.offlineSubtitleTrack(hebrew.url, user: "u"), ifNoneMatch: etag) == .unchanged)
        // A key not listed is not there.
        do throws(HubFailure) {
            _ = try await hub.data(HubEndpoints.offlineSubtitleTrack(hebrew.url.replacingOccurrences(of: "ext-heb", with: "ext-deu"),
                                                                     user: "u"))
            Issue.record("A key not listed was served")
        } catch {
            #expect(error.status == 404)
        }
    }

    @Test func aRefreshKeepsTheFilesInStepWithTheHub() async throws {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        let row = try await download(into: store)
        let grantId = row.manifest.grantId
        let start: Int64 = 1_000_000
        let later = OfflineSubtitleSync.recheckMillis

        // First: both fetched, each kept with the ETag it came with.
        #expect(await OfflineSubtitleSync.refresh(row, store: store, hub: hub, now: start) == .changed)
        let first = try #require(store.keptSubtitles(row))
        #expect(first.checkedAt == start)
        #expect(first.tracks.map(\.key) == ["ext-eng", "ext-heb"])
        #expect(first.tracks.map(\.rtl) == [false, true])
        #expect(first.tracks.allSatisfy { $0.etag.hasPrefix("\"") && $0.etag.count == 34 })
        #expect(Self.text(store, row, "ext-eng")?.contains("It plays with no network.") == true)
        #expect(store.keptSubtitleFiles(row).count == 2)

        // Asked about recently: not asked again, before playing either.
        #expect(await OfflineSubtitleSync.refresh(row, store: store, hub: hub, now: start + 1) == .unchanged)
        #expect(!OfflineSubtitleSync.due(first, now: start + OfflineSubtitleSync.playRecheckMillis - 1,
                                          freshMillis: OfflineSubtitleSync.playRecheckMillis))
        #expect(OfflineSubtitleSync.due(first, now: start + OfflineSubtitleSync.playRecheckMillis,
                                         freshMillis: OfflineSubtitleSync.playRecheckMillis))
        // A while later, nothing changed: nothing fetched.
        #expect(await OfflineSubtitleSync.refresh(row, store: store, hub: hub, now: start + later) == .unchanged)
        #expect(store.keptSubtitles(row)?.tracks == first.tracks)
        #expect(store.keptSubtitles(row)?.checkedAt == start + later)

        // Bazarr replaces the English in place and adds German: those two come, the Hebrew stays.
        var subtitles = DemoOffline.standardSubtitles
        subtitles[0].text = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nA better English line.\n"
        subtitles.append(DemoOffline.Subtitle(key: "ext-deu", language: "deu", label: "German - SubRip - External",
                                              text: "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nEin deutscher Untertitel.\n"))
        DemoOffline.setSubtitles(grantId, subtitles)
        #expect(await OfflineSubtitleSync.refresh(row, store: store, hub: hub, now: start + 2 * later) == .changed)
        let second = try #require(store.keptSubtitles(row))
        #expect(second.tracks.map(\.key) == ["ext-eng", "ext-heb", "ext-deu"])
        #expect(second.tracks[0].etag != first.tracks[0].etag && second.tracks[1].etag == first.tracks[1].etag)
        #expect(Self.text(store, row, "ext-eng")?.contains("A better English line.") == true)
        #expect(Self.text(store, row, "ext-deu")?.contains("Ein deutscher Untertitel.") == true)

        // The English and German taken away: their files go, the Hebrew's stays.
        DemoOffline.setSubtitles(grantId, [DemoOffline.standardSubtitles[1]])
        #expect(await OfflineSubtitleSync.refresh(row, store: store, hub: hub, now: start + 3 * later) == .changed)
        #expect(store.keptSubtitles(row)?.tracks.map(\.key) == ["ext-heb"])
        #expect(!store.hasKeptSubtitle(row, key: "ext-eng") && !store.hasKeptSubtitle(row, key: "ext-deu"))
        #expect(store.hasKeptSubtitle(row, key: "ext-heb"))

        // The grant expired while the Hebrew was replaced: renewed, then asked again.
        var hebrew = DemoOffline.standardSubtitles[1]
        hebrew.text = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nשורה חדשה\n"
        DemoOffline.setSubtitles(grantId, [hebrew])
        DemoOffline.expire(grantId)
        #expect(await OfflineSubtitleSync.refresh(row, store: store, hub: hub, now: start + 4 * later) == .changed)
        #expect(Self.text(store, row, "ext-heb")?.contains("שורה חדשה") == true)
        #expect(store.keptSubtitles(row)?.checkedAt == start + 4 * later)
        // The renewed manifest is kept: the same grant, its expiry no earlier.
        let renewed = try #require(store.row(row.id)).manifest
        #expect(renewed.grantId == grantId && renewed.expiresAt >= row.manifest.expiresAt)
    }

    @Test func aDownloadRemovedLeavesNoSubtitleBehind() async throws {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        let row = try await download(into: store)
        store.remove(row.id)
        // A refresh that ends after the download went writes nothing.
        #expect(await OfflineSubtitleSync.refresh(row, store: store, hub: hub, now: 1_000_000) == .unchanged)
        #expect(store.keptSubtitles(row) == nil)
        try store.writeKeptSubtitle(row, key: "ext-heb", data: Data("WEBVTT".utf8))
        #expect(!store.hasKeptSubtitle(row, key: "ext-heb"))
        #expect(try FileManager.default.contentsOfDirectory(atPath: store.subtitleFolder.path).isEmpty)
    }

    @Test func anErrorAnswerKeepsEveryFile() async throws {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        let row = try await download(into: store)
        let start: Int64 = 1_000_000
        #expect(await OfflineSubtitleSync.refresh(row, store: store, hub: hub, now: start) == .changed)
        let kept = try #require(store.keptSubtitles(row))
        // No such grant on this hub (404, as a hub older than these routes answers): all kept.
        var stranger = row
        stranger.manifest.grantId = "demo-grant-apple-nobody"
        let later = start + OfflineSubtitleSync.recheckMillis
        #expect(await OfflineSubtitleSync.refresh(stranger, store: store, hub: hub, now: later) == .unchanged)
        #expect(store.keptSubtitles(row)?.tracks == kept.tracks)
        #expect(store.keptSubtitles(row)?.checkedAt == later)
        #expect(store.keptSubtitleFiles(row).count == 2)
        // An original download is never asked about.
        var original = row
        original.manifest.format = OfflineFormat.original
        original.manifest.apple = nil
        #expect(await OfflineSubtitleSync.refresh(original, store: store, hub: hub, now: later * 2) == .unchanged)
        #expect(store.keptSubtitles(row)?.checkedAt == later)
    }
}
