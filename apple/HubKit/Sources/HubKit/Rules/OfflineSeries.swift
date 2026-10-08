import Foundation

// What a downloaded series' page says of the series itself. An episode's
// manifest carries the episode: its still, its number, its own words. The
// series' facts (its year, rating, genres) and its overview come from the
// series' item, which the hub is asked for once, when the first episode is
// queued, and kept beside the artwork, so the page reads like the online one
// with no hub. A download made before this has none until the hub next
// answers; the page is right without it, with the title and the episodes.

/// The series' own item, as little of it as the page shows.
public struct OfflineSeriesSnapshot: Codable, Equatable, Sendable {
    public var seriesId: String
    public var title: String
    public var originalTitle: String
    public var overview: String
    public var premiereDate: String
    public var officialRating: String
    public var year: Int
    public var runtimeSeconds: Int
    public var rating: Double
    public var genres: [String]
    public var studios: [String]
    /// The hub's paths of its pictures, so one that did not come is asked for again.
    public var poster: String
    public var backdrop: String
    /// When it was read from the hub, Unix milliseconds.
    public var savedAt: Int64

    public init(seriesId: String, title: String = "", originalTitle: String = "", overview: String = "",
                premiereDate: String = "", officialRating: String = "", year: Int = 0, runtimeSeconds: Int = 0,
                rating: Double = 0, genres: [String] = [], studios: [String] = [], poster: String = "",
                backdrop: String = "", savedAt: Int64 = 0) {
        self.seriesId = seriesId
        self.title = title
        self.originalTitle = originalTitle
        self.overview = overview
        self.premiereDate = premiereDate
        self.officialRating = officialRating
        self.year = year
        self.runtimeSeconds = runtimeSeconds
        self.rating = rating
        self.genres = genres
        self.studios = studios
        self.poster = poster
        self.backdrop = backdrop
        self.savedAt = savedAt
    }

    /// What the hub said of the series.
    public init(_ item: LibraryItem, now: Int64) {
        self.init(seriesId: item.id, title: item.title, originalTitle: item.originalTitle, overview: item.overview,
                  premiereDate: item.premiereDate, officialRating: item.officialRating, year: item.year,
                  runtimeSeconds: item.runtimeSeconds, rating: item.rating, genres: item.genres, studios: item.studios,
                  poster: item.poster, backdrop: item.backdrop, savedAt: now)
    }

    /// The series as an item, for the lines a title's page draws of one
    /// (`DetailLines.facts`, its details).
    public var item: LibraryItem {
        LibraryItem(id: seriesId, type: "series", title: title, year: year, overview: overview, originalTitle: originalTitle,
                    premiereDate: premiereDate, runtimeSeconds: runtimeSeconds, rating: rating,
                    officialRating: officialRating, genres: genres, studios: studios, poster: poster, backdrop: backdrop)
    }
}

/// The snapshots and the series' pictures, on disk beside the downloads'
/// artwork (`OfflineStore.series`): a small file a series, and the pictures
/// it was asked for with it. Safe from any thread.
public final class OfflineSeriesStore: @unchecked Sendable {
    public let folder: URL
    private let lock = NSLock()

    public init(folder: URL) {
        self.folder = folder
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    /// "poster" or "backdrop".
    public func artworkFile(_ seriesId: String, kind: String) -> URL {
        folder.appendingPathComponent("\(Self.safe(seriesId))-\(Self.safe(kind)).img")
    }

    private func snapshotFile(_ seriesId: String) -> URL {
        folder.appendingPathComponent("\(Self.safe(seriesId)).json")
    }

    /// What was kept of the series; nil when the hub was never asked, or the file cannot be read.
    public func snapshot(_ seriesId: String) -> OfflineSeriesSnapshot? {
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? Data(contentsOf: snapshotFile(seriesId)),
              let value = try? JSONDecoder().decode(OfflineSeriesSnapshot.self, from: data),
              value.seriesId == seriesId else { return nil }
        return value
    }

    /// Written whole, or not at all: a series is never left half kept.
    public func save(_ snapshot: OfflineSeriesSnapshot) {
        guard !snapshot.seriesId.isEmpty, let data = try? JSONEncoder().encode(snapshot) else { return }
        lock.lock()
        defer { lock.unlock() }
        try? data.write(to: snapshotFile(snapshot.seriesId), options: .atomic)
    }

    public func writeArtwork(_ data: Data, seriesId: String, kind: String) {
        lock.lock()
        defer { lock.unlock() }
        try? data.write(to: artworkFile(seriesId, kind: kind), options: .atomic)
    }

    /// Which of these series the hub should be asked about: those with no
    /// snapshot, and those whose picture did not come.
    public func needing(_ seriesIds: [String]) -> [String] {
        var seen = Set<String>()
        return seriesIds.filter { id in
            guard !id.isEmpty, seen.insert(id).inserted else { return false }
            guard let kept = snapshot(id) else { return true }
            let manager = FileManager.default
            return (!kept.backdrop.isEmpty && !manager.fileExists(atPath: artworkFile(id, kind: "backdrop").path))
                || (!kept.poster.isEmpty && !manager.fileExists(atPath: artworkFile(id, kind: "poster").path))
        }
    }

    /// Everything kept of the series gone, but for those in `keeping`: what is
    /// left when the last of a series' episodes is removed from the device.
    public func prune(keeping: Set<String>) {
        lock.lock()
        defer { lock.unlock() }
        let manager = FileManager.default
        for file in (try? manager.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? [] where file.pathExtension == "json" {
            guard let data = try? Data(contentsOf: file), let kept = try? JSONDecoder().decode(OfflineSeriesSnapshot.self, from: data),
                  !keeping.contains(kept.seriesId) else { continue }
            try? manager.removeItem(at: file)
            for kind in ["poster", "backdrop"] { try? manager.removeItem(at: artworkFile(kept.seriesId, kind: kind)) }
        }
    }

    private static func safe(_ value: String) -> String {
        String(value.map { $0.isLetter || $0.isNumber || $0 == "." || $0 == "_" || $0 == "-" ? $0 : "_" })
    }
}
