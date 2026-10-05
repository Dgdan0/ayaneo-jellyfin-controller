import Foundation
import Testing
@testable import HubKit

/// The demo hub's library answers as the hub does (#9, #14), so the UI tests
/// that drive it prove what the app sends.
struct DemoLibraryTests {
    private let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                                screens: DemoTransport(), sleep: { _ in })

    private var anime: String { DemoMedia.folders[0].id }

    @Test func aLibrarysOwnSearchKeepsToItAndTheRootsLooksEverywhere() async throws {
        let inside = try await hub.fetch(HubEndpoints.librarySearch("the", viewId: anime), as: LibraryPage.self)
        #expect(inside.items.map(\.media.title) == ["Avatar: The Last Airbender", "Code Geass: Lelouch of the Rebellion"])
        let everywhere = try await hub.fetch(HubEndpoints.librarySearch("the"), as: LibraryPage.self)
        #expect(everywhere.total == 6)
        #expect(everywhere.items.contains { $0.media.title == "The Dark Knight" })
    }

    @Test func aMalformedLibraryIsRefusedBeforeAnySearch() async {
        await #expect(throws: HubFailure.self) {
            _ = try await hub.fetch(HubEndpoints.librarySearch("the", viewId: "anime"), as: LibraryPage.self)
        }
    }

    @Test func eachLibraryListsItsOwnTitles() async throws {
        let page = try await hub.fetch(HubEndpoints.libraryItems(viewId: anime), as: LibraryPage.self)
        #expect(page.total == 5)
        #expect(page.items.allSatisfy { $0.media.type == "series" && $0.jellyfinItemId.count == 32 })
    }

    @Test func watchedAndFavouriteChangeOneAtATimeAndStay() async throws {
        let matrix = try #require(DemoLibrary.titles.first { $0.title == "The Matrix" })
        let watched = try await hub.fetch(HubEndpoints.libraryState(itemId: matrix.id, body: LibraryStateChange.played(true).body()),
                                          as: LibraryItemResponse.self)
        #expect(watched.item.played && !watched.item.favorite)
        let starred = try await hub.fetch(HubEndpoints.libraryState(itemId: matrix.id, body: LibraryStateChange.favorite(true).body()),
                                          as: LibraryItemResponse.self)
        #expect(starred.item.played && starred.item.favorite)
        let read = try await hub.fetch(HubEndpoints.libraryItem(matrix.id), as: LibraryItemResponse.self)
        #expect(read.item.played && read.item.favorite)
        let favourites = try await hub.fetch(HubEndpoints.libraryFavorites(), as: LibraryPage.self)
        #expect(favourites.items.contains { $0.jellyfinItemId == matrix.id })

        // The hub's rule: exactly one of the two, as a true or a false.
        for body in [#"{"played":true,"favorite":true}"#, #"{"played":1}"#, "{}"] {
            await #expect(throws: HubFailure.self) {
                _ = try await hub.fetch(HubEndpoints.libraryState(itemId: matrix.id, body: Data(body.utf8)),
                                        as: LibraryItemResponse.self)
            }
        }

        // Put back, as a test against the real hub must.
        _ = try await hub.fetch(HubEndpoints.libraryState(itemId: matrix.id, body: LibraryStateChange.played(false).body()),
                                as: LibraryItemResponse.self)
        let back = try await hub.fetch(HubEndpoints.libraryState(itemId: matrix.id, body: LibraryStateChange.favorite(false).body()),
                                       as: LibraryItemResponse.self)
        #expect(!back.item.played && !back.item.favorite)
    }

    @Test func aSeriesHasASeasonOfEpisodesAndAnEpisodeToStart() async throws {
        let bleach = try #require(DemoLibrary.titles.first { $0.title == "Bleach" })
        let seasons = try await hub.fetch(HubEndpoints.librarySeasons(seriesId: bleach.id), as: LibraryItemList.self)
        let season = try #require(seasons.items.first)
        let episodes = try await hub.fetch(HubEndpoints.libraryEpisodes(seriesId: bleach.id, seasonId: season.id),
                                           as: LibraryItemList.self)
        #expect(episodes.items.map(\.indexNumber) == [1, 2, 3])
        let target = try await hub.fetch(HubEndpoints.seriesPlayTarget(seriesId: bleach.id), as: SeriesPlayTarget.self)
        #expect(target.item.id == episodes.items[0].id)
        let episode = try await hub.fetch(HubEndpoints.libraryItem(target.item.id), as: LibraryItemResponse.self)
        #expect(episode.item.seriesTitle == "Bleach" && episode.item.type == "episode")
    }
}
