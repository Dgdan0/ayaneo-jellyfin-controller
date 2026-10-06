import AVFoundation
import Foundation
import Testing
@testable import HubKit

/// The demo hub's offline downloads (#5): a series' selection, grants, the
/// file (a real MP4 AVPlayer opens), renewal and the watches sent back,
/// answered through the same routes as the hub's.
struct OfflineDemoTests {
    private let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                                screens: DemoTransport(), sleep: { _ in })
    /// Attack on Titan in the demo library.
    private let series = String(format: "%032lx", 0xdeb0_0000 + 1)
    /// Inception.
    private let movie = String(format: "%032lx", 0xdeb0_0000 + 14)

    @Test func theDemoVideoIsAnMP4AVPlayerOpens() async throws {
        let data = try #require(await DemoVideo.data())
        #expect(String(decoding: data[4..<8], as: UTF8.self) == "ftyp")
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("demo-video-test-\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: file) }
        try data.write(to: file)
        let asset = AVURLAsset(url: file)
        let seconds = try await asset.load(.duration).seconds
        #expect(abs(seconds - Double(DemoVideo.seconds)) < 0.5)
        #expect(try await asset.loadTracks(withMediaType: .video).count == 1)
        // Written once per run.
        #expect(await DemoVideo.data() == data)
    }

    @Test func aSeriesSelectionListsItsEpisodesWithTheirSize() async throws {
        let selection = try await hub.fetch(HubEndpoints.offlineSelection(seriesId: series), as: OfflineSelectionResponse.self)
        #expect(selection.series.title == "Attack on Titan")
        let episodes = try #require(selection.seasons.first).episodes
        #expect(episodes.count == 3)
        #expect(episodes.allSatisfy { $0.available && $0.estimatedSizeBytes > 0 })
        #expect(selection.playTargetId == episodes.first?.item.id)
        #expect(episodes.first?.sources.first?.container == "mp4")
    }

    @Test func grantsNameTheFileItsSizeItsLibraryAndASubtitleBesideIt() async throws {
        let batch = "offline-1-demo"
        let body = OfflinePrepareBody(batchKey: batch, seriesId: series, items: [
            OfflinePrepareItem(clientItemKey: batch + "-e1", itemId: series + "-e1"),
            OfflinePrepareItem(clientItemKey: batch + "-e2", itemId: series + "-e2"),
        ])
        let data = try await hub.data(HubEndpoints.prepareOffline(body))
        let manifests = OfflineManifest.list(from: data).map(\.manifest)
        #expect(manifests.map(\.clientItemKey) == [batch + "-e1", batch + "-e2"])
        let video = try #require(await DemoVideo.data())
        let first = try #require(manifests.first)
        #expect(first.source.sizeBytes == Int64(video.count))
        #expect(first.library == "Anime")
        #expect(first.item.seriesTitle == "Attack on Titan")
        // The file through its grant, and the subtitle file beside it.
        let file = try await hub.data(HubRequest(first.mediaUrl))
        #expect(file == video)
        let subtitle = try await hub.data(HubRequest(try #require(first.subtitles.first).url))
        #expect(String(decoding: subtitle, as: UTF8.self).contains("-->"))
        // Asked again, the same grants; renewed, the same routes, later.
        let again = OfflineManifest.list(from: try await hub.data(HubEndpoints.prepareOffline(body))).map(\.manifest)
        #expect(again.map(\.grantId) == manifests.map(\.grantId))
        let renewed = try await hub.fetch(HubEndpoints.renewOffline(grantId: first.grantId), as: OfflineManifest.self)
        #expect(renewed.grantId == first.grantId && renewed.mediaUrl == first.mediaUrl)
        #expect(renewed.expiresAt >= first.expiresAt)
    }

    @Test func onlyFilmsAndEpisodesOfTheSeriesAreGranted() async throws {
        do throws(HubFailure) {
            _ = try await hub.data(HubEndpoints.prepareOffline(OfflinePrepareBody(batchKey: "b", seriesId: series, items: [
                OfflinePrepareItem(clientItemKey: "b-m", itemId: movie),
            ])))
            Issue.record("A film was granted as an episode of a series")
        } catch {
            #expect(error.status == 400)
        }
        let film = OfflineManifest.list(from: try await hub.data(HubEndpoints.prepareOffline(OfflinePrepareBody(
            batchKey: "film", items: [OfflinePrepareItem(clientItemKey: "film-m", itemId: movie)]))))
        #expect(film.first?.manifest.item.title == "Inception")
        #expect(film.first?.manifest.library == "Movies")
    }

    // MARK: Apple downloads on the pretend PC

    /// Faster than the app's demo, so the tests do not wait long.
    private static func quickPace() {
        DemoOffline.pace.withLock { $0 = DemoOffline.Pace(queuedMillis: 300, preparingMillis: 600, convertingMillis: 900) }
    }

    private func prepareApple(_ itemId: String) async throws -> OfflineManifest {
        let batch = "apple-" + UUID().uuidString.prefix(8)
        let body = OfflinePrepareBody(batchKey: batch, format: OfflineFormat.apple,
                                      items: [OfflinePrepareItem(clientItemKey: batch + "-k", itemId: itemId)])
        return try #require(OfflineManifest.list(from: try await hub.data(HubEndpoints.prepareOffline(body))).first?.manifest)
    }

    /// Asks for the status until `stop` says so, at most ten seconds, keeping each state seen in turn.
    private func follow(_ grantId: String, until stop: (OfflineGrantStatus) -> Bool) async throws
        -> (last: OfflineGrantStatus, seen: [OfflineGrantStatus.State]) {
        var seen: [OfflineGrantStatus.State] = []
        let deadline = Date().addingTimeInterval(10)
        while true {
            let status = try await hub.fetch(HubEndpoints.offlineStatus(grantId: grantId), as: OfflineGrantStatus.self)
            if seen.last != status.state { seen.append(status.state) }
            if stop(status) || Date() > deadline { return (status, seen) }
        }
    }

    @Test func anAppleGrantWaitsOnThePCThenServesItsMP4UnderItsETag() async throws {
        Self.quickPace()
        let manifest = try await prepareApple(DemoOffline.inception)
        #expect(manifest.isApple && manifest.subtitles.isEmpty && manifest.container == "mp4")
        // Inception's French picture subtitle is left out, and the person is told.
        #expect(OfflineAppleNotes.leftOut(try #require(manifest.apple))?.hasPrefix("French subtitles") == true)
        // Before it is ready the file is refused, with the PC's reason.
        do throws(HubFailure) {
            _ = try await hub.data(HubEndpoints.offlineMedia(manifest.mediaUrl))
            Issue.record("the MP4 was served before the PC had made it")
        } catch {
            #expect(error.status == 409 && error.code == "offline_preparing" && error.retryable == true)
            #expect(["queued", "preparing"].contains(error.reason))
        }
        let (ready, seen) = try await follow(manifest.grantId) { $0.state == .ready }
        #expect(ready.state == .ready && !ready.etag.isEmpty && ready.percent == 100)
        // Only ever forward: in line, made, ready.
        let order: [OfflineGrantStatus.State] = [.queued, .preparing, .ready]
        #expect(seen == order.filter(seen.contains))
        // The file, whole, under the ETag the status named.
        let request = URLRequest(url: try #require(URL(string: DemoTransport.address + manifest.mediaUrl)))
        let (data, response) = try await DemoTransport().send(request)
        #expect(response.statusCode == 200 && response.value(forHTTPHeaderField: "ETag") == ready.etag)
        #expect(Int64(data.count) == ready.sizeBytes)
        // Let go once kept: asked for again, it is made again, under another ETag.
        try await hub.send(HubEndpoints.releaseOffline(grantId: manifest.grantId))
        let again = try await follow(manifest.grantId) { $0.state == .ready }.last
        #expect(again.state == .ready && again.etag != ready.etag)
    }

    @Test func duneFailsOnThePCUntilItIsRetried() async throws {
        Self.quickPace()
        let manifest = try await prepareApple(DemoOffline.dune)
        let failed = try await follow(manifest.grantId) { $0.state == .failed || $0.state == .ready }.last
        #expect(failed.state == .failed && failed.error?.code == "ffmpeg_failed" && failed.error?.retryable == true)
        #expect(OfflineTransfer.step(failed) == .failed(message: "The PC could not make this download's MP4.", retryable: true))
        do throws(HubFailure) {
            _ = try await hub.data(HubEndpoints.offlineMedia(manifest.mediaUrl))
            Issue.record("a failed MP4 was served")
        } catch {
            #expect(error.code == "offline_failed" && error.reason == "ffmpeg_failed")
        }
        let retried = try await hub.fetch(HubEndpoints.retryOffline(grantId: manifest.grantId), as: OfflineGrantStatus.self)
        #expect(retried.state == .queued || retried.state == .preparing)
        #expect(try await follow(manifest.grantId) { $0.state != .queued && $0.state != .preparing }.last.state == .ready)
    }

    @Test func theMatrixIsConvertedAndTakesLonger() async throws {
        Self.quickPace()
        let manifest = try await prepareApple(DemoOffline.matrix)
        let apple = try #require(manifest.apple)
        #expect(apple.video.converted && apple.video.codec == "mpeg4" && apple.video.outputCodec == "h264")
        #expect(OfflineAppleNotes.slower(apple) != nil)
    }

    @Test func anAppleSelectionCarriesTheMP4sEstimates() async throws {
        let selection = try await hub.fetch(HubEndpoints.offlineSelection(seriesId: series, format: OfflineFormat.apple),
                                            as: OfflineSelectionResponse.self)
        let first = try #require(selection.seasons.first?.episodes.first)
        let video = try #require(await DemoVideo.data())
        #expect(first.apple?.estimatedSizeBytes == first.estimatedSizeBytes)
        #expect(first.estimatedSizeBytes > Int64(video.count))
        #expect(selection.estimatedSizeBytes == first.estimatedSizeBytes * Int64(selection.episodeCount))
    }

    @Test func watchesSentBackAreAppliedOnceThenDuplicates() async throws {
        let event = OfflineProgressEvent(clientEventKey: "demo-watch-\(UUID().uuidString.prefix(8))", itemId: movie,
                                         positionMillis: 60_000, durationMillis: 600_000, occurredAt: 1_790_000_000_000)
        let first = try await hub.fetch(HubEndpoints.syncOfflineProgress(OfflineProgressSyncBody(events: [event])),
                                        as: OfflineProgressSyncResponse.self)
        #expect(first.results.map(\.status) == ["applied"])
        let again = try await hub.fetch(HubEndpoints.syncOfflineProgress(OfflineProgressSyncBody(events: [event])),
                                        as: OfflineProgressSyncResponse.self)
        #expect(again.results.map(\.status) == ["duplicate"])
    }
}
