import Foundation
import HubKit
import Observation

/// Downloads for watching away from the hub (#5): what is kept on this
/// device, the transfers, and the watches made from a download, for the
/// whole app. One per process (`shared`), as the background session it
/// carries must be: the system hands a relaunched app its transfers by the
/// session's name.
///
/// Files live in Application Support, kept out of backups and never cleared
/// by the system (a download is the person's to remove); the demo hub's in a
/// folder of their own, emptied each launch. A download plays from its file
/// first wherever the item is opened, and its watch is kept here and sent
/// with `POST /v1/offline/progress/sync` as the profile that made it, when
/// the hub can be reached: at launch, after playing, and on Downloads.
@MainActor
@Observable
final class OfflineLibrary {
    static let shared = OfflineLibrary(root: OfflineLibrary.defaultRoot)

    /// Bumped on every change, so a page showing downloads reads them again.
    private(set) var revision = 0
    /// The profile whose downloads are listed and played.
    private(set) var userId = ""
    private(set) var isDemo = false

    @ObservationIgnored let store: OfflineStore
    @ObservationIgnored private var engine: OfflineDownloader?
    @ObservationIgnored private var hub: HubClient?
    @ObservationIgnored private var syncing: Task<Void, Never>?
    /// What each download was when last looked at: a change to finished or
    /// failed is a notification (#43). Nil until the first look.
    @ObservationIgnored private var seenStates: [String: OfflineState]?

    init(root: URL) {
        store = OfflineStore(root: root)
    }

    static var defaultRoot: URL {
        let files = FileManager.default
        if ProcessInfo.processInfo.arguments.contains("-demo") {
            let demo = files.temporaryDirectory.appendingPathComponent("demo-offline", isDirectory: true)
            try? files.removeItem(at: demo)
            return demo
        }
        let base = files.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? files.temporaryDirectory
        return base.appendingPathComponent("offline", isDirectory: true)
    }

    var downloader: OfflineDownloader {
        if let engine { return engine }
        let made = OfflineDownloader(store: store) { [weak self] in self?.downloadsChanged() }
        engine = made
        return made
    }

    /// The hub, the token and the profile to download and play for: at
    /// launch, and whenever one of them changes.
    func attach(_ app: AppModel) {
        hub = app.hub
        userId = app.userId
        isDemo = app.isDemo
        guard app.isConfigured else { return }
        downloader.attach(OfflineDownloader.Context(hub: app.hub, baseURL: app.address, token: app.storedToken(),
                                                    userId: app.userId, isDemo: app.isDemo))
        // This profile's downloads as they are now: only what changes after is news.
        seenStates = OfflineAlerts.states(store.rows(userId: userId))
        revision += 1
        syncSoon()
    }

    /// A download moved: the pages read again, and one that finished or
    /// failed says so (#43).
    private func downloadsChanged() {
        revision += 1
        guard !userId.isEmpty || isDemo else { return }
        let rows = store.rows(userId: userId)
        DownloadAlerts.shared.post(OfflineAlerts.changes(before: seenStates, rows: rows), demo: isDemo)
        seenStates = OfflineAlerts.states(rows)
    }

    /// The system woke the app for the background session's events (iOS).
    func backgroundEvents() async {
        await downloader.backgroundEvents()
    }

    // MARK: Reading

    /// This profile's finished downloads.
    var completed: [OfflineRow] {
        _ = revision
        return store.completed(userId: userId)
    }

    /// One poster per film or series, A to Z.
    var titles: [OfflineCatalogEntry] {
        let batches = Dictionary(store.batches(userId: userId).map { ($0.id, $0.title) }) { first, _ in first }
        return OfflineCatalog.titles(completed, batchTitles: batches)
    }

    /// This profile's batches, newest first.
    var batches: [OfflineBatch] {
        _ = revision
        return store.batches(userId: userId)
    }

    /// Downloads still on their way (anything not finished).
    var coming: Int { batches.reduce(0) { $0 + $1.jobs.filter { $0.state != .complete }.count } }

    /// The item's download in any state: whether it is on the device or coming.
    func row(forItem itemId: String) -> OfflineRow? {
        _ = revision
        return store.forItem(itemId, userId: userId)
    }

    /// Watches kept here, by item.
    func progress(_ itemIds: [String]) -> [String: OfflineCatalogProgress] {
        _ = revision
        return store.progress(itemIds: itemIds, userId: userId)
    }

    /// Bytes on the device for this profile's downloads.
    var storedBytes: Int64 {
        _ = revision
        return store.storedBytes(userId: userId)
    }

    var freeBytes: Int64 { OfflineDownloader.freeBytes(store.root) }

    var wifiOnly: Bool {
        get {
            _ = revision
            return downloader.wifiOnly
        }
        set { downloader.wifiOnly = newValue }
    }

    // MARK: Downloading

    /// Asks the hub for these films or episodes as MP4s for this device, as
    /// one batch, and queues them. Nil when they are on their way; the
    /// reason otherwise, in words.
    func download(itemIds: [String], title: String, seriesId: String = "") async -> String? {
        guard let hub, !itemIds.isEmpty else { return "Not connected to the hub" }
        // The first download asks whether the app may say when it is done, never the launch (#43).
        await DownloadAlerts.shared.askOnce(demo: isDemo)
        let now = OfflineDownloader.now()
        let batchKey = OfflineSelection.batchKey(now: now, for: seriesId.isEmpty ? itemIds[0] : seriesId)
        let body = OfflinePrepareBody(batchKey: batchKey, seriesId: seriesId, format: OfflineFormat.apple,
                                      items: itemIds.map { OfflinePrepareItem(clientItemKey: OfflineSelection.itemKey(batchKey: batchKey, itemId: $0),
                                                                              itemId: $0) })
        let data: Data
        do {
            data = try await hub.data(HubEndpoints.prepareOffline(body))
        } catch {
            if error.kind == .forbidden { return OfflineTransfer.noScope }
            return error.message
        }
        let manifests = OfflineManifest.list(from: data)
        guard !manifests.isEmpty else { return "The hub sent nothing to download" }
        // A hub from before the Apple format sends the original, which this device cannot play.
        guard manifests.allSatisfy(\.manifest.isApple) else { return OfflineTransfer.hubTooOld }
        let added = store.enqueue(title: title, seriesId: seriesId, userId: userId, manifests: manifests, now: now)
        revision += 1
        downloader.kick()
        return added == 0 ? "Already downloaded or on its way" : nil
    }

    func pause(_ id: String) { downloader.pause(id) }
    func resume(_ id: String) { downloader.resume(id) }
    func retry(_ id: String) { downloader.retry(id) }
    func remove(_ id: String) { downloader.remove(id) }
    func pauseBatch(_ id: String) { downloader.pauseBatch(id) }
    func resumeBatch(_ id: String) { downloader.resumeBatch(id) }
    func cancelBatch(_ id: String, keepFinished: Bool) { downloader.cancelBatch(id, removeCompleted: !keepFinished) }

    /// Every download of a film or a series' episodes gone from this device.
    func removeTitle(_ entry: OfflineCatalogEntry) {
        for row in entry.rows { downloader.remove(row.id) }
    }

    // MARK: Playing

    /// The item's finished download for this profile, whole, as the plan the
    /// player opens: from where it was left here, else where the server had
    /// it when it was downloaded. Nil when there is none to play.
    func localPlan(itemId: String, mode: PlaybackStartMode, userId: String) -> PlaybackPrepareResponse? {
        guard let row = store.completedForItem(itemId, userId: userId, now: OfflineDownloader.now()) else { return nil }
        let saved = store.progress(itemIds: [itemId], userId: userId)[itemId]
        return OfflinePlayback.plan(row, file: store.mediaFile(row), saved: saved, mode: mode,
                                    siblings: store.completed(userId: userId), subtitles: [:])
    }

    /// The downloaded episode of a series to go on with, by the same rules as playback.
    func playTarget(seriesId: String, userId: String) -> OfflineCatalogPlayTarget? {
        let rows = store.completed(userId: userId).filter { $0.manifest.item.seriesId == seriesId }
        guard !rows.isEmpty else { return nil }
        return OfflineCatalog.playTarget(rows, progress: store.progress(itemIds: rows.map(\.itemId), userId: userId))
    }

    /// A watch made from a download: kept here, and queued for the hub.
    func remember(itemId: String, userId: String, positionMillis: Int64, durationMillis: Int64, completed: Bool) {
        let now = OfflineDownloader.now()
        store.rememberPlayback(userId: userId, itemId: itemId, positionMillis: positionMillis, durationMillis: durationMillis,
                               completed: completed, now: now, eventKey: OfflinePlayback.eventKey(itemId: itemId, now: now))
        revision += 1
    }

    // MARK: Watches made offline

    /// Sends the watches waiting, unless a send is already under way.
    func syncSoon() {
        guard syncing == nil, let hub else { return }
        syncing = Task { [weak self] in
            await self?.sync(hub)
            self?.syncing = nil
        }
    }

    /// Each profile's waiting watches, a hundred at a time, as that profile.
    /// The hub keeps a later watch from elsewhere (`server_newer`), which
    /// this device then takes for its own; a batch the hub cannot read is
    /// dropped rather than sent forever.
    private func sync(_ hub: HubClient) async {
        for user in store.outboxUsers() {
            while true {
                let events = store.outbox(userId: user, limit: 100)
                guard !events.isEmpty else { break }
                let response: OfflineProgressSyncResponse
                do {
                    response = try await hub.fetch(HubEndpoints.syncOfflineProgress(OfflineProgressSyncBody(events: events),
                                                                                    user: user),
                                                   as: OfflineProgressSyncResponse.self)
                } catch {
                    if error.status == 400 { store.removeOutbox(keys: events.map(\.clientEventKey)) }
                    // Not reachable, or refused: they wait for the next time.
                    break
                }
                let durations = Dictionary(events.map { ($0.clientEventKey, $0.durationMillis) }) { first, _ in first }
                for result in response.results where result.status == "server_newer" {
                    store.adoptServerWatch(userId: user, itemId: result.itemId, server: .fromServer(
                        positionMillis: result.serverPositionMillis, durationMillis: durations[result.clientEventKey] ?? 0,
                        played: result.serverPlayed, lastPlayedAt: result.serverLastPlayedAt))
                }
                let answered = response.results.map(\.clientEventKey)
                store.removeOutbox(keys: answered)
                if answered.isEmpty { break }
            }
        }
        revision += 1
    }
}
