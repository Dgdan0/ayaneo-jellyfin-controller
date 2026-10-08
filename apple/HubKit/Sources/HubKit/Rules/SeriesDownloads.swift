import Foundation

// A series' downloads on its own page (#48): what a card's corner says, what
// "Season 2 · 4.9 GB" gets, the smart choices of the round button, and the
// ticks of select mode. Pure: the page hands in the episodes the hub listed
// (with their sizes and the profile's watched state), what the device has,
// and gets back words and ids.

/// One episode as the downloads see it: where it falls in the series, whether
/// it is watched, whether the PC can make an MP4 of it, and how big that is.
public struct DownloadEpisode: Equatable, Sendable, Identifiable {
    public var id: String
    public var seasonId: String
    /// 0 is Specials.
    public var season: Int
    public var index: Int
    public var played: Bool
    public var available: Bool
    public var bytes: Int64

    public init(id: String, seasonId: String = "", season: Int = 1, index: Int = 1, played: Bool = false,
                available: Bool = true, bytes: Int64 = 0) {
        self.id = id
        self.seasonId = seasonId
        self.season = season
        self.index = index
        self.played = played
        self.available = available
        self.bytes = bytes
    }
}

public enum SeriesDownloads {
    /// Every episode the hub listed, in the series' order: Specials last, so
    /// "the next episode" never starts in them.
    public static func episodes(from selection: OfflineSelectionResponse) -> [DownloadEpisode] {
        selection.seasons.flatMap { season in
            season.episodes.map { entry -> DownloadEpisode in
                let item = entry.item
                return DownloadEpisode(id: item.id, seasonId: item.seasonId.isEmpty ? season.season.id : item.seasonId,
                                       season: item.seasonNumber, index: item.indexNumber, played: item.played,
                                       available: entry.available, bytes: entry.estimatedSizeBytes)
            }
        }
        .sorted { left, right in
            let a = left.season == 0 ? Int.max : left.season
            let b = right.season == 0 ? Int.max : right.season
            return a == b ? left.index < right.index : a < b
        }
    }

    /// Those that can be downloaded and are not on the device or on their way.
    public static func missing(_ episodes: [DownloadEpisode], have: (String) -> Bool) -> [DownloadEpisode] {
        episodes.filter { $0.available && !have($0.id) }
    }

    public static func size(_ episodes: [DownloadEpisode]) -> Int64 {
        episodes.reduce(0) { $0 + $1.bytes }
    }

    /// "3 episodes · 1.2 GB", "1 episode · 600 MB".
    public static func countLine(_ episodes: [DownloadEpisode]) -> String {
        "\(episodes.count) episode\(episodes.count == 1 ? "" : "s") · \(Fmt.bytes(size(episodes)))"
    }

    // MARK: A season's button

    /// What the button after the season pills says: "Season 2 · 4.9 GB" for
    /// what is not here yet, "Season 2 on this iPad" when nothing is left to get.
    public static func seasonButton(name: String, missing: [DownloadEpisode], device: String) -> String {
        missing.isEmpty ? "\(name) on this \(device)" : "\(name) · \(Fmt.bytes(size(missing)))"
    }

    // MARK: The corner of a card

    /// What an episode card's corner shows.
    public enum Badge: Equatable, Sendable {
        /// Not here: an arrow, a tap downloads.
        case none
        /// Asked for and waiting its turn, or the PC is making its MP4: a clock.
        case waiting
        /// Bytes arriving: a ring, 0 to 1. A tap stops it.
        case moving(Double)
        case downloaded
        case failed

        /// What VoiceOver says, and the tests read.
        public var label: String {
            switch self {
            case .none: "Download"
            case .waiting: "Waiting to download. Stop"
            case .moving(let fraction): "Downloading \(Int((min(max(fraction, 0), 1) * 100).rounded())) percent. Stop"
            case .downloaded: "Downloaded"
            case .failed: "Download failed. Try again"
            }
        }

        /// Whether it is on the way, so a tap stops it.
        public var isComing: Bool {
            switch self {
            case .waiting, .moving: true
            default: false
            }
        }
    }

    /// The corner for an episode's download in any state, or none.
    public static func badge(_ row: OfflineRow?, requesting: Bool = false) -> Badge {
        guard let row else { return requesting ? .waiting : .none }
        switch row.state {
        case .complete: return .downloaded
        case .failed: return .failed
        case .downloading: return .moving(max(OfflineQueueLabels.fraction(row), 0.03))
        case .queued, .waiting, .paused, .preparing: return .waiting
        }
    }

    // MARK: The smart choices

    public enum ChoiceKind: Equatable, Sendable {
        case keepReady(Int)
        case restOfSeason
        case everythingUnwatched
        case wholeSeries
    }

    /// One row of the panel: its words, what it would add and how big that is.
    public struct Choice: Equatable, Sendable, Identifiable {
        public var kind: ChoiceKind
        public var title: String
        public var episodes: [DownloadEpisode]

        public var id: String {
            switch kind {
            case .keepReady: "keep-ready"
            case .restOfSeason: "rest-of-season"
            case .everythingUnwatched: "everything-unwatched"
            case .wholeSeries: "whole-series"
            }
        }

        public var ids: [String] { episodes.map(\.id) }
        public var bytes: Int64 { SeriesDownloads.size(episodes) }
        public var isEmpty: Bool { episodes.isEmpty }

        /// "3 episodes · 1.2 GB", or "Nothing left to get".
        public var line: String { episodes.isEmpty ? "Nothing left to get" : SeriesDownloads.countLine(episodes) }
    }

    /// The panel's choices (not "Choose episodes", which opens select mode).
    /// `target` is the episode Play starts; `count` the Keep ready number.
    public static func choices(_ episodes: [DownloadEpisode], playTargetId: String, count: Int,
                               have: (String) -> Bool, seasonName: (Int) -> String) -> [Choice] {
        let chain = KeepReady.chain(episodes)
        let window = KeepReady.window(chain, playTargetId: playTargetId, count: count)
        let target = chain.first { $0.id == playTargetId } ?? chain.first { !$0.played }
        let seasonNumber = target?.season ?? chain.first?.season ?? 1
        let rest = chain.drop { $0.id != target?.id }.filter { $0.season == seasonNumber }
        return [
            Choice(kind: .keepReady(count), title: "Keep the next \(count) ready",
                   episodes: missing(window, have: have)),
            Choice(kind: .restOfSeason, title: "Rest of \(seasonName(seasonNumber))",
                   episodes: missing(Array(rest), have: have)),
            Choice(kind: .everythingUnwatched, title: "Everything unwatched",
                   episodes: missing(episodes.filter { !$0.played }, have: have)),
            Choice(kind: .wholeSeries, title: "Whole series", episodes: missing(episodes, have: have)),
        ]
    }

    // MARK: Select mode

    /// Episodes that can be ticked: not here, not coming, and the PC can make them.
    public static func tickable(_ episodes: [DownloadEpisode], have: (String) -> Bool) -> [DownloadEpisode] {
        missing(episodes, have: have)
    }

    /// Ticks after a tap: an episode that is here, coming or cannot come is never ticked.
    public static func toggled(_ ticked: Set<String>, _ id: String, tickable: Set<String>) -> Set<String> {
        guard tickable.contains(id) else { return ticked }
        var out = ticked
        if out.contains(id) { out.remove(id) } else { out.insert(id) }
        return out
    }

    /// "Select season" ticks every tickable episode of it; when all are
    /// ticked already the button says Unselect season and takes them off.
    public static func seasonAllTicked(_ ticked: Set<String>, season: [DownloadEpisode], tickable: Set<String>) -> Bool {
        let own = season.map(\.id).filter(tickable.contains)
        return !own.isEmpty && own.allSatisfy(ticked.contains)
    }

    public static func toggledSeason(_ ticked: Set<String>, season: [DownloadEpisode], tickable: Set<String>) -> Set<String> {
        let own = Set(season.map(\.id).filter(tickable.contains))
        return seasonAllTicked(ticked, season: season, tickable: tickable) ? ticked.subtracting(own) : ticked.union(own)
    }

    /// The pill's "4/14": ticked of those that can be ticked in the season.
    public static func seasonTicks(_ ticked: Set<String>, season: [DownloadEpisode], tickable: Set<String>)
        -> (ticked: Int, of: Int) {
        let own = season.map(\.id).filter(tickable.contains)
        return (own.filter(ticked.contains).count, own.count)
    }

    /// "10 selected" over the top.
    public static func selectedWords(_ count: Int) -> String { "\(count) selected" }

    /// The bottom bar's total: "10 episodes · 4.9 GB", or nothing chosen yet.
    public static func total(_ ticked: Set<String>, among episodes: [DownloadEpisode]) -> String {
        let chosen = episodes.filter { ticked.contains($0.id) }
        return chosen.isEmpty ? "Nothing selected" : countLine(chosen)
    }

    /// The device: "iPad", "iPhone" or "Mac".
    public static func deviceWord(idiom: String) -> String {
        switch idiom.lowercased() {
        case "pad", "ipad": "iPad"
        case "phone", "iphone": "iPhone"
        case "mac": "Mac"
        default: "device"
        }
    }
}
