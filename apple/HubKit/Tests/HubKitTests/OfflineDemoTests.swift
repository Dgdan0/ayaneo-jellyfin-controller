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
