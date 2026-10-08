import Foundation
import Testing
@testable import HubKit

/// What a downloaded series' page keeps of the series itself: a snapshot of
/// its item and its pictures, kept beside the downloads' own artwork.
struct OfflineSeriesTests {
    private func series(_ id: String = "drake") -> LibraryItem {
        LibraryItem(id: id, type: "series", title: "Drake & Josh", year: 2004, overview: "Two stepbrothers share a house.",
                    originalTitle: "Drake & Josh", premiereDate: "2004-01-11T00:00:00.0000000Z", runtimeSeconds: 1_500,
                    rating: 7.6, officialRating: "TV-G", genres: ["Comedy", "Family"], studios: ["Nickelodeon"],
                    poster: "/v1/img/jf/\(id)/Primary", backdrop: "/v1/img/jf/\(id)/Backdrop")
    }

    @Test func aSnapshotIsTheSeriesAsTheHubSaidItAndDrawsTheSameLines() {
        let snapshot = OfflineSeriesSnapshot(series(), now: 1_000)
        #expect(snapshot.seriesId == "drake" && snapshot.title == "Drake & Josh" && snapshot.savedAt == 1_000)
        #expect(snapshot.item.type == "series")
        // The page's facts line and Details are what the online page's are.
        #expect(DetailLines.facts(snapshot.item) == DetailLines.facts(series()))
        #expect(DetailLines.facts(snapshot.item) == "2004  ·  TV-G  ·  25 min  ·  ★ 7.6  ·  Comedy  ·  Family")
        #expect(DetailLines.details(snapshot.item).map(\.label) == DetailLines.details(series()).map(\.label))
        #expect(snapshot.item.overview == "Two stepbrothers share a house.")
    }

    @Test func aSnapshotIsKeptAndReadBackAfterTheAppStartsAgain() throws {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        #expect(store.series.snapshot("drake") == nil, "nothing is kept until the hub was asked")
        let snapshot = OfflineSeriesSnapshot(series(), now: 5)
        store.series.save(snapshot)
        store.series.writeArtwork(Data([1, 2, 3]), seriesId: "drake", kind: "backdrop")
        #expect(store.series.snapshot("drake") == snapshot)
        #expect(try Data(contentsOf: store.series.artworkFile("drake", kind: "backdrop")) == Data([1, 2, 3]))
        // Beside the artwork, in the store's folder: the next launch finds it.
        let again = OfflineStore(root: root)
        #expect(again.series.snapshot("drake") == snapshot)
        #expect(again.series.folder.deletingLastPathComponent().path == root.path)
        // Another series' name is not this one's, and a ruined file is no snapshot.
        #expect(again.series.snapshot("other") == nil)
        try Data("not json".utf8).write(to: again.series.folder.appendingPathComponent("broken.json"))
        #expect(again.series.snapshot("broken") == nil)
    }

    @Test func aSeriesWithNoSnapshotOrNoPictureIsAskedForAgainOnceNoMore() throws {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        // Downloads from before: no snapshot at all. Each series once, in order.
        #expect(store.series.needing(["a", "b", "a", ""]) == ["a", "b"])
        store.series.save(OfflineSeriesSnapshot(series("a"), now: 1))
        // Its backdrop did not come: it is asked for again.
        #expect(store.series.needing(["a", "b"]) == ["a", "b"])
        store.series.writeArtwork(Data([1]), seriesId: "a", kind: "backdrop")
        #expect(store.series.needing(["a", "b"]) == ["a", "b"], "the poster is still missing")
        store.series.writeArtwork(Data([1]), seriesId: "a", kind: "poster")
        #expect(store.series.needing(["a", "b"]) == ["b"])
        // A series with no pictures of its own has nothing more to wait for.
        store.series.save(OfflineSeriesSnapshot(seriesId: "bare", title: "Bare", savedAt: 2))
        #expect(store.series.needing(["bare"]).isEmpty)
    }

    @Test func theLastEpisodeRemovedTakesTheSnapshotAndItsPicturesWithIt() throws {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        // Two episodes of the series "l" (the test manifests' own), and a film.
        store.enqueue(title: "Lanterns", seriesId: "l", userId: "dan",
                      manifests: [OfflineTests.manifest("b", "b-e1", "e1", 1), OfflineTests.manifest("b", "b-e2", "e2", 2)], now: 0)
        store.series.save(OfflineSeriesSnapshot(seriesId: "l", title: "Lanterns", backdrop: "/x", savedAt: 1))
        store.series.writeArtwork(Data([1]), seriesId: "l", kind: "backdrop")
        store.series.save(OfflineSeriesSnapshot(seriesId: "gone", title: "Gone", savedAt: 1))
        store.remove("b-e1")
        #expect(store.series.snapshot("l") != nil, "an episode is still on the device")
        // What was kept of a series with no episode on the device at all goes with the first removal.
        #expect(store.series.snapshot("gone") == nil)
        store.remove("b-e2")
        #expect(store.series.snapshot("l") == nil)
        #expect(!FileManager.default.fileExists(atPath: store.series.artworkFile("l", kind: "backdrop").path))
    }

    @Test func cancellingABatchTakesTheSeriesToo() throws {
        let root = OfflineTests.folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = OfflineStore(root: root)
        store.enqueue(title: "Lanterns", seriesId: "l", userId: "dan",
                      manifests: [OfflineTests.manifest("b", "b-e1", "e1", 1)], now: 0)
        store.series.save(OfflineSeriesSnapshot(seriesId: "l", title: "Lanterns", savedAt: 1))
        store.cancelBatch("b", removeCompleted: true)
        #expect(store.series.snapshot("l") == nil)
    }

    /// The page's Play, for a film as for a series: where it was left here, else the start.
    @Test func aDownloadedFilmResumesWhereItWasLeftHereElseStartsFromTheStart() {
        let film = OfflineTests.movie("m", "Gran Torino")
        #expect(OfflineCatalog.filmTarget(film, progress: nil).kind == .start)
        let part = OfflineCatalogProgress(positionMillis: 600_000, durationMillis: 1_200_000, updatedAtMillis: 1)
        let resumed = OfflineCatalog.filmTarget(film, progress: part)
        #expect(resumed.kind == .resume && resumed.positionMillis == 600_000 && resumed.row.id == "m")
        // Finished, it starts over; so does a film left in its first moments.
        let done = OfflineCatalogProgress(positionMillis: 1_195_000, durationMillis: 1_200_000, updatedAtMillis: 1)
        #expect(OfflineCatalog.filmTarget(film, progress: done).kind == .start)
        let barely = OfflineCatalogProgress(positionMillis: 2_000, durationMillis: 1_200_000, updatedAtMillis: 1)
        #expect(OfflineCatalog.filmTarget(film, progress: barely).kind == .start)
    }
}
