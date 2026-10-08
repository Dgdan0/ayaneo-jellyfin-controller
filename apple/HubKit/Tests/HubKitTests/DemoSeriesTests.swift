import Foundation
import Testing
@testable import HubKit

/// The demo's two-season series (#48): Slow Horses has Season 1 of three and
/// Season 2 of four, so a series page has a second season to tick and download,
/// and its episodes remember being watched, which moves where Play starts.
struct DemoSeriesTests {
    private let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                                screens: DemoTransport(), sleep: { _ in })
    private let series = String(format: "%032lx", 0xdeb0_0000 + 18)

    @Test func theSeriesHasTwoSeasonsAndEachListsItsOwnEpisodes() async throws {
        let seasons = try await hub.fetch(HubEndpoints.librarySeasons(seriesId: series), as: LibraryItemList.self).items
        #expect(seasons.map(\.title) == ["Season 1", "Season 2"])
        let second = try #require(seasons.last)
        let episodes = try await hub.fetch(HubEndpoints.libraryEpisodes(seriesId: series, seasonId: second.id, page: 1),
                                           as: LibraryItemList.self).items
        #expect(episodes.map(\.indexNumber) == [1, 2, 3, 4])
        #expect(episodes.allSatisfy { $0.seasonNumber == 2 && $0.seasonId == second.id })
        #expect(episodes.first?.id == series + "-s2e1" && episodes.first?.title == "Back in Business")
        // A season of its own, never the other's.
        let first = try await hub.fetch(HubEndpoints.libraryEpisodes(seriesId: series, seasonId: seasons[0].id, page: 1),
                                        as: LibraryItemList.self).items
        #expect(first.map(\.id) == [series + "-e1", series + "-e2", series + "-e3"])
    }

    @Test func aLaterSeasonsEpisodeIsAPageOfItsOwn() async throws {
        let episode = try await hub.fetch(HubEndpoints.libraryItem(series + "-s2e3"), as: LibraryItemResponse.self).item
        #expect(episode.type == "episode" && episode.seasonNumber == 2 && episode.indexNumber == 3)
        #expect(episode.title == "Hot Desk")
    }

    @Test func theSelectionListsEverySeasonWithSizes() async throws {
        let selection = try await hub.fetch(HubEndpoints.offlineSelection(seriesId: series, format: "apple"),
                                            as: OfflineSelectionResponse.self)
        #expect(selection.seasons.map { $0.episodes.count } == [3, 4])
        #expect(selection.episodeCount == 7)
        let episodes = SeriesDownloads.episodes(from: selection)
        #expect(episodes.count == 7 && episodes.allSatisfy { $0.available && $0.bytes > 0 })
        #expect(episodes.map(\.season) == [1, 1, 1, 2, 2, 2, 2])
    }

    @Test func watchingAnEpisodeMovesWhereThePlayStartsAndKeepReadyCanSeeIt() async throws {
        let first = series + "-e1"
        let body = Data(#"{"played":true}"#.utf8)
        _ = try await hub.fetch(HubEndpoints.libraryState(itemId: first, body: body), as: LibraryItemResponse.self)
        defer { Task { _ = try? await hub.fetch(HubEndpoints.libraryState(itemId: first, body: Data(#"{"played":false}"#.utf8)),
                                                 as: LibraryItemResponse.self) } }
        let selection = try await hub.fetch(HubEndpoints.offlineSelection(seriesId: series, format: "apple"),
                                            as: OfflineSelectionResponse.self)
        #expect(selection.playTargetId == series + "-e2", "Play starts at the first episode not watched")
        let episodes = SeriesDownloads.episodes(from: selection)
        #expect(episodes.first?.played == true && episodes.dropFirst().allSatisfy { !$0.played })
        let target = try await hub.fetch(HubEndpoints.seriesPlayTarget(seriesId: series), as: SeriesPlayTarget.self)
        #expect(target.item.id == series + "-e2")
    }
}
