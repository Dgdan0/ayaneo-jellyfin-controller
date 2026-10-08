import HubKit
import Observation
import SwiftUI

/// A series page's downloads (#48): what the hub says each episode would be
/// (its size, whether the PC can make it, watched or not), the taps on a
/// card's corner, the season button, the smart choices and select mode. The
/// page owns one; the rules are HubKit's `SeriesDownloads` and `KeepReady`.
@MainActor
@Observable
final class SeriesDownloadsModel {
    private(set) var seriesId = ""
    private(set) var selection: OfflineSelectionResponse?
    private(set) var episodes: [DownloadEpisode] = []
    /// Asked for, and not yet in the downloads: a clock at once.
    private(set) var requesting: Set<String> = []
    /// What went wrong the last time something was asked for, in words.
    private(set) var problem: String?
    /// The hub's listing is being asked for: the page keeps the season button's row open for it. Never longer.
    private(set) var loading = false

    /// Select mode: tap to tick, then Download. Ticks stay across seasons.
    var selecting = false
    var ticked: Set<String> = []

    @ObservationIgnored private let offline: OfflineLibrary
    /// The requests in the order they were made, so a run of taps is one batch.
    @ObservationIgnored private var chain: Task<Void, Never>?
    /// Stopped before the hub had answered: taken off again the moment they are queued.
    @ObservationIgnored private var stopped: Set<String> = []

    init(offline: OfflineLibrary = .shared) {
        self.offline = offline
    }

    /// The device, for "on this iPad".
    var device: String {
        #if os(iOS)
        SeriesDownloads.deviceWord(idiom: UIDevice.current.userInterfaceIdiom == .pad ? "pad" : "phone")
        #else
        SeriesDownloads.deviceWord(idiom: "mac")
        #endif
    }

    /// The series' name before the hub's listing arrives.
    @ObservationIgnored private var fallbackTitle = ""

    var seriesTitle: String { selection?.series.title ?? fallbackTitle }
    var isLoaded: Bool { selection != nil }

    func setSeries(id: String, title: String) {
        seriesId = id
        fallbackTitle = title
    }

    /// Whether the PC can make an MP4 of it: true until the hub's listing says it cannot.
    func canDownload(_ id: String) -> Bool { episodes.first { $0.id == id }?.available ?? true }

    // MARK: Loading

    /// Every episode, with its size and whether it is watched; then Keep
    /// ready tidied from the same answer, so it never asks the hub twice.
    func load(_ hub: HubClient, seriesId: String) async {
        self.seriesId = seriesId
        loading = true
        defer { loading = false }
        guard let response = try? await hub.fetch(HubEndpoints.offlineSelection(seriesId: seriesId, format: OfflineFormat.apple),
                                                  as: OfflineSelectionResponse.self) else { return }
        selection = response
        episodes = SeriesDownloads.episodes(from: response)
        await offline.keepReadyTick(response)
    }

    // MARK: What is here

    func have(_ id: String) -> Bool { offline.row(forItem: id) != nil || requesting.contains(id) }

    func badge(_ id: String) -> SeriesDownloads.Badge {
        SeriesDownloads.badge(offline.row(forItem: id), requesting: requesting.contains(id))
    }

    func seasonEpisodes(_ seasonId: String) -> [DownloadEpisode] { episodes.filter { $0.seasonId == seasonId } }

    func missing(season seasonId: String) -> [DownloadEpisode] {
        SeriesDownloads.missing(seasonEpisodes(seasonId), have: have)
    }

    /// "Season 2 · 4.9 GB", or "Season 2 on this iPad".
    func seasonButton(_ seasonId: String, name: String) -> String {
        SeriesDownloads.seasonButton(name: name, missing: missing(season: seasonId), device: device)
    }

    func seasonName(_ number: Int) -> String {
        let season = selection?.seasons.first { $0.season.indexNumber == number }?.season
        return season.map { $0.title.isEmpty ? EpisodeLabel.season(number) : $0.title } ?? EpisodeLabel.season(number)
    }

    /// The panel's choices, from where Play starts.
    func choices(keepReadyCount count: Int) -> [SeriesDownloads.Choice] {
        SeriesDownloads.choices(episodes, playTargetId: selection?.playTargetId ?? "", count: count, have: have,
                                seasonName: seasonName)
    }

    // MARK: Asking

    /// Downloads these episodes, in the order they were asked for.
    func start(_ ids: [String]) {
        let ids = ids.filter { !have($0) }
        guard !ids.isEmpty else { return }
        stopped.subtract(ids)
        requesting.formUnion(ids)
        problem = nil
        let title = seriesTitle
        let series = seriesId
        let previous = chain
        chain = Task { [offline] in
            await previous?.value
            let asked = ids.filter { !stopped.contains($0) }
            var said: String?
            if !asked.isEmpty { said = await offline.download(itemIds: asked, title: title, seriesId: series) }
            for id in ids where stopped.remove(id) != nil { offline.stop(itemId: id) }
            requesting.subtract(ids)
            if let said, said != "Already downloaded or on its way" { problem = said }
        }
    }

    /// A tap on a card's corner: a download starts, one on its way is stopped,
    /// one that failed is asked for again. One that is here is left alone.
    func tap(_ id: String) {
        switch badge(id) {
        case .none: start([id])
        case .waiting, .moving:
            // Not queued yet: the request is called off, and goes if it was already on its way.
            if requesting.contains(id) {
                requesting.remove(id)
                stopped.insert(id)
            }
            offline.stop(itemId: id)
        case .failed:
            offline.stop(itemId: id)
            start([id])
        case .downloaded: break
        }
    }

    func downloadSeason(_ seasonId: String) { start(missing(season: seasonId).map(\.id)) }

    func remove(_ id: String) { offline.stop(itemId: id) }

    // MARK: Keep ready

    var keepReadyCount: Int? { offline.keepReadyCount(seriesId) }

    func setKeepReady(_ count: Int) {
        guard let selection else { return }
        Task { [offline] in await offline.setKeepReady(selection, count: count) }
    }

    func turnOffKeepReady() { offline.turnOffKeepReady(seriesId) }

    // MARK: Select mode

    var tickable: Set<String> { Set(SeriesDownloads.tickable(episodes, have: have).map(\.id)) }

    func beginSelecting(ticking id: String? = nil) {
        ticked = []
        selecting = true
        if let id { toggle(id) }
    }

    func cancelSelecting() {
        selecting = false
        ticked = []
    }

    func toggle(_ id: String) { ticked = SeriesDownloads.toggled(ticked, id, tickable: tickable) }

    func seasonAllTicked(_ seasonId: String) -> Bool {
        SeriesDownloads.seasonAllTicked(ticked, season: seasonEpisodes(seasonId), tickable: tickable)
    }

    func toggleSeason(_ seasonId: String) {
        ticked = SeriesDownloads.toggledSeason(ticked, season: seasonEpisodes(seasonId), tickable: tickable)
    }

    /// The pill's ring: how much of the season is ticked, and "4/14".
    func ticks(_ seasonId: String) -> (ticked: Int, of: Int) {
        SeriesDownloads.seasonTicks(ticked, season: seasonEpisodes(seasonId), tickable: tickable)
    }

    /// The ticked episodes in the series' order.
    var tickedEpisodes: [DownloadEpisode] { episodes.filter { ticked.contains($0.id) && tickable.contains($0.id) } }

    /// Download what is ticked and go back to the page with its rings.
    func downloadTicked() {
        let ids = tickedEpisodes.map(\.id)
        cancelSelecting()
        start(ids)
    }
}
