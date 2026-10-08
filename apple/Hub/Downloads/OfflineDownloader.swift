import Foundation
import HubKit
import Network

/// The transfers behind Downloads (#5; Android's `OfflineDownloadService`):
/// one download at a time, in the queue's order, the oldest batch first.
///
/// Every download here is an MP4 the hub makes of the original on the PC
/// (option 1). The downloader asks the PC how far it is (`…/status`, every
/// two seconds while it runs, five while it waits its turn) and shows it;
/// once it is ready it downloads exactly that file, then lets the PC's copy
/// go. Each step is `OfflineTransfer`'s, so what it does at each answer is
/// tested in HubKit.
///
/// A real hub's file comes through a background `URLSession`, so a download
/// goes on with the app in the background or quit by the system, and a pause
/// or a dropped connection keeps resume data to go on from where it was (the
/// session sends `Range` and `If-Range` with the file's ETag). A grant past
/// its date (410) is renewed and the download carries on; a refused token or
/// a ban holds every download, and the client's gate is told, so nothing is
/// sent into a ban. The demo hub cannot be reached by URLSession, so its file
/// comes through `HubClient` and is written out a piece at a time.
@MainActor
final class OfflineDownloader {
    /// The background session's name, per app: the Mac's debug build has an id of its own.
    nonisolated static let sessionIdentifier = (Bundle.main.bundleIdentifier ?? "com.dgdan.jellyhub") + ".offline"

    /// What the transfers need from the app.
    struct Context {
        let hub: HubClient
        let baseURL: String
        let token: String
        let userId: String
        let isDemo: Bool
    }

    /// Failures in a row before a download needs the person (Android's default).
    static let maxRetries = 5
    /// Free space kept clear of downloads.
    static let reserveBytes: Int64 = 512 << 20
    /// How long a download held back (no Wi-Fi, no room, a refused token) waits before it is looked at again.
    static let holdMillis: Int64 = 30_000
    private static let wifiOnlyKey = "offline.wifiOnly"

    /// A download that has just arrived whole: its subtitles are fetched
    /// beside it while the hub is at hand (#45).
    var finished: ((OfflineRow) -> Void)?

    private let store: OfflineStore
    private let changed: () -> Void
    private var context: Context?
    private let relay: TransferRelay
    private var session: URLSession?
    /// The download being carried now: waiting on the PC, or moving.
    private var activeId: String?
    /// Its task, when its bytes come through the background session.
    private var activeTask: URLSessionDownloadTask?
    /// Asking the PC how far it is, renewing, or the demo hub's transfer.
    private var work: Task<Void, Never>?
    /// Stopped by the person (a pause or a removal), so its end is not a failure.
    private var stopping: Set<String> = []
    private var wake: Task<Void, Never>?
    private var lastWritten = Date.distantPast
    private var samples: [(at: Date, bytes: Int64)] = []
    private let path = NWPathMonitor()
    private(set) var onWiFi = true
    private var backgroundEventsDone: [CheckedContinuation<Void, Never>] = []

    init(store: OfflineStore, changed: @escaping () -> Void) {
        self.store = store
        self.changed = changed
        relay = TransferRelay(store: store)
        relay.owner = self
        path.pathUpdateHandler = { [weak self] update in
            // A phone's mobile data and a hotspot are expensive; Wi-Fi and a cable are not.
            let free = update.status == .satisfied && !update.isExpensive
            Task { @MainActor in self?.networkChanged(free: free) }
        }
        path.start(queue: DispatchQueue(label: "offline.path"))
    }

    /// Downloads wait for Wi-Fi (or a cable) unless the person allows mobile data and hotspots.
    var wifiOnly: Bool {
        get { UserDefaults.standard.object(forKey: Self.wifiOnlyKey) as? Bool ?? true }
        set {
            UserDefaults.standard.set(newValue, forKey: Self.wifiOnlyKey)
            if let context { store.wakeWaiting(userId: context.userId, now: Self.now()) }
            if newValue, !onWiFi, let id = activeId, activeTask != nil {
                // Off Wi-Fi now: the moving file stops where it is and waits.
                stop(id)
                store.wait(id, reason: OfflineTransfer.waitingForWiFi, until: Self.now() + Self.holdMillis, now: Self.now())
            }
            changed()
            kick()
        }
    }

    /// The hub, the token and the profile to download for. Transfers a
    /// previous launch left running in the background are found again.
    func attach(_ next: Context) {
        let profileChanged = context?.userId != next.userId
        let tokenChanged = context?.token != next.token
        context = next
        if !next.isDemo { ensureSession() }
        if profileChanged {
            // Each profile's downloads are its own: another's queue waits.
            stopActive(requeue: true)
        }
        if tokenChanged { store.wakeWaiting(userId: next.userId, now: Self.now()) }
        guard let session else {
            store.requeueInterrupted(keeping: activeId.map { [$0] } ?? [], now: Self.now())
            changed()
            kick()
            return
        }
        session.getAllTasks { [weak self] tasks in
            let running = tasks.compactMap { $0 as? URLSessionDownloadTask }.filter { $0.state == .running }
            let ids = Set(running.compactMap(\.taskDescription))
            Task { @MainActor in
                guard let self else { return }
                if self.activeId == nil, let task = running.first, let id = task.taskDescription,
                   self.store.row(id)?.userId == next.userId {
                    self.activeId = id
                    self.activeTask = task
                }
                self.store.requeueInterrupted(keeping: ids.union(self.activeId.map { [$0] } ?? []), now: Self.now())
                self.changed()
                self.kick()
            }
        }
    }

    /// The background session, made once: the system hands a relaunched app
    /// the events of the transfers it carried, once a session of that name exists.
    func ensureSession() {
        guard session == nil else { return }
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        configuration.isDiscretionary = false
        #if os(iOS)
        configuration.sessionSendsLaunchEvents = true
        #endif
        configuration.httpMaximumConnectionsPerHost = 1
        configuration.urlCache = nil
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        session = URLSession(configuration: configuration, delegate: relay, delegateQueue: queue)
    }

    // MARK: The queue

    /// Starts the next download when none is being carried.
    func kick() {
        guard let context, activeId == nil else { return }
        let now = Self.now()
        guard let row = store.nextQueued(userId: context.userId, now: now) else {
            scheduleWake(at: store.nextRetryAt(userId: context.userId, now: now))
            return
        }
        if let reason = held(row, context: context) {
            store.wait(row.id, reason: reason, until: now + Self.holdMillis, now: now)
            changed()
            // Another download may not be held back by the same thing.
            kick()
            return
        }
        activeId = row.id
        samples = []
        if row.isApple {
            store.setPreparing(row.id, percent: row.hubPercent, queuePosition: row.hubQueuePosition, now: now)
            changed()
            work = Task { [weak self] in await self?.prepare(row.id, context: context) }
        } else {
            begin(row.id, context: context)
        }
    }

    /// Why a download cannot start now, in words for its row.
    private func held(_ row: OfflineRow, context: Context) -> String? {
        if !context.isDemo && wifiOnly && !onWiFi { return OfflineTransfer.waitingForWiFi }
        let remaining = max(row.totalBytes - row.bytesDownloaded, 0)
        if Self.freeBytes(store.root) < remaining + Self.reserveBytes { return OfflineTransfer.waitingForRoom }
        return nil
    }

    private func scheduleWake(at time: Int64?) {
        wake?.cancel()
        guard let time else { return }
        let delay = max(time - Self.now(), 1_000)
        wake = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(delay))
            guard !Task.isCancelled else { return }
            self?.kick()
        }
    }

    private func networkChanged(free: Bool) {
        guard free != onWiFi else { return }
        onWiFi = free
        if free {
            if let context { store.wakeWaiting(userId: context.userId, now: Self.now()) }
        } else if wifiOnly, let id = activeId, activeTask != nil {
            // Wi-Fi gone mid-file: it stops where it is, its resume data kept.
            stop(id)
            store.wait(id, reason: OfflineTransfer.waitingForWiFi, until: Self.now() + Self.holdMillis, now: Self.now())
        }
        changed()
        kick()
    }

    // MARK: Waiting on the PC

    /// Asks the PC how far the MP4 is until it is ready, then downloads it.
    private func prepare(_ id: String, context: Context) async {
        while !Task.isCancelled, activeId == id {
            guard let row = store.row(id) else {
                release(id)
                return
            }
            let status: OfflineGrantStatus
            do {
                status = try await context.hub.fetch(HubEndpoints.offlineStatus(grantId: row.manifest.grantId),
                                                     as: OfflineGrantStatus.self)
            } catch {
                guard !Task.isCancelled, activeId == id, error.kind != .cancelled else { return }
                recover(id, error, context: context)
                return
            }
            guard !Task.isCancelled, activeId == id else { return }
            switch OfflineTransfer.step(status) {
            case .wait(let percent, let queuePosition, let afterMillis):
                store.setPreparing(id, percent: percent, queuePosition: queuePosition, now: Self.now())
                changed()
                try? await Task.sleep(for: .milliseconds(afterMillis))
            case .download(let sizeBytes, let etag):
                store.setReady(id, sizeBytes: sizeBytes, etag: etag, now: Self.now())
                if let ready = store.row(id), let reason = held(ready, context: context) {
                    // Its exact size is more than there is room for.
                    store.wait(id, reason: reason, until: Self.now() + Self.holdMillis, now: Self.now())
                    release(id)
                    return
                }
                begin(id, context: context)
                return
            case .failed(let message, _):
                store.setState(id, .failed, error: message, now: Self.now())
                release(id)
                return
            }
        }
    }

    // MARK: The file

    /// The file's bytes, from where they were.
    private func begin(_ id: String, context: Context) {
        guard let row = store.row(id) else {
            release(id)
            return
        }
        activeId = id
        store.updateProgress(id, bytes: row.bytesDownloaded, now: Self.now(), persist: true)
        changed()
        if context.isDemo {
            work = Task { [weak self] in await self?.demoDownload(id, context: context) }
            return
        }
        ensureSession()
        guard let session, let url = URL(string: HubEndpoints.join(context.baseURL, row.manifest.mediaUrl)) else {
            store.setState(id, .failed, error: "The hub's address for this download cannot be used", now: Self.now())
            release(id)
            return
        }
        let task: URLSessionDownloadTask
        let resumeFile = store.resumeFile(row)
        if let resume = try? Data(contentsOf: resumeFile) {
            // The session sends Range and If-Range with the ETag it saw; a
            // rebuilt file comes back whole, and its ETag says so (`complete`).
            task = session.downloadTask(withResumeData: resume)
            try? FileManager.default.removeItem(at: resumeFile)
        } else {
            var request = URLRequest(url: url)
            request.setValue("Bearer " + context.token, forHTTPHeaderField: "Authorization")
            if !context.userId.isEmpty { request.setValue(context.userId, forHTTPHeaderField: HubClient.userHeader) }
            request.allowsExpensiveNetworkAccess = !wifiOnly
            request.allowsConstrainedNetworkAccess = !wifiOnly
            task = session.downloadTask(with: request)
        }
        task.taskDescription = id
        task.countOfBytesClientExpectsToReceive = max(row.totalBytes - row.bytesDownloaded, 0)
        activeTask = task
        task.resume()
    }

    /// From the relay, as bytes arrive.
    fileprivate func wrote(_ id: String, taskId: Int, bytes: Int64) {
        guard id == activeId, activeTask?.taskIdentifier == taskId || activeTask == nil, let row = store.row(id) else { return }
        progressed(row, bytes: bytes)
    }

    private func progressed(_ row: OfflineRow, bytes: Int64) {
        let now = Date()
        samples.append((now, bytes))
        samples.removeAll { now.timeIntervalSince($0.at) > 10 }
        let first = samples.first ?? (now, bytes)
        let seconds = max(now.timeIntervalSince(first.at), 0.001)
        let speed = samples.count > 1 ? Int64(Double(bytes - first.bytes) / seconds) : 0
        // Written to disk now and then: the transfer reports far more often.
        let persist = now.timeIntervalSince(lastWritten) > 5
        if persist { lastWritten = now }
        store.updateProgress(row.id, bytes: min(bytes, row.totalBytes), speed: speed, now: Self.now(), persist: persist)
        changed()
    }

    /// From the relay: a task ended, its file put in place or not.
    fileprivate func ended(_ id: String, _ outcome: TransferOutcome) {
        let isActive = id == activeId && activeTask?.taskIdentifier == outcome.taskId
        if outcome.moved {
            // Whole, whichever launch started it: it is kept.
            if id == activeId {
                activeId = nil
                activeTask = nil
                work?.cancel()
            }
            complete(id, outcome)
            return
        }
        // A task from before that no longer carries the download: its resume data is kept, nothing more.
        guard isActive || (activeId != id && store.row(id)?.state == .downloading) else { return }
        if isActive {
            activeId = nil
            activeTask = nil
        }
        let now = Self.now()
        if stopping.remove(id) != nil {
            changed()
            kick()
            return
        }
        guard let context else {
            store.setState(id, .queued, now: now)
            changed()
            return
        }
        let failure = outcome.failure ?? HubFailure(.unknown, message: "The download stopped")
        if failure.kind == .cancelled {
            // Stopped by the system (the app was quit): it goes on later from its resume data.
            store.setState(id, .queued, now: now)
            changed()
            kick()
            return
        }
        if failure.status == 401, outcome.token != context.token {
            // Resumed with a token the person has since changed: again from the start, with the new one.
            if let row = store.row(id) { try? FileManager.default.removeItem(at: store.resumeFile(row)) }
            store.setState(id, .queued, now: now)
            changed()
            kick()
            return
        }
        if let status = failure.status, !outcome.token.isEmpty, outcome.token == context.token {
            // What the hub said about this token holds for every request the app makes.
            let hub = context.hub
            let token = outcome.token
            let wait = failure.retryAfterSeconds
            Task { await hub.observe(token: token, status: status, retryAfterSeconds: wait) }
        }
        recover(id, failure, context: context)
    }

    /// The file arrived: kept when it is the file the PC named and exactly
    /// its size, then the PC's copy is let go.
    private func complete(_ id: String, _ outcome: TransferOutcome) {
        let now = Self.now()
        guard let row = store.row(id) else {
            kick()
            return
        }
        if row.isApple, !row.etag.isEmpty, !outcome.etag.isEmpty, outcome.etag != row.etag {
            // The PC made the file again since its ETag was read: ask it again.
            try? FileManager.default.removeItem(at: store.mediaFile(row))
            store.setState(id, .queued, now: now)
            changed()
            kick()
            return
        }
        guard store.isStored(row) else {
            try? FileManager.default.removeItem(at: store.mediaFile(row))
            store.recordFailure(id, message: "The download did not arrive whole", maxRetries: Self.maxRetries, now: now)
            changed()
            kick()
            return
        }
        store.finish(id, now: now)
        try? FileManager.default.removeItem(at: store.resumeFile(row))
        if row.isApple, let hub = context?.hub {
            let grantId = row.manifest.grantId
            Task { try? await hub.send(HubEndpoints.releaseOffline(grantId: grantId)) }
        }
        if let done = store.row(id) { finished?(done) }
        Task { await fetchArtwork(row) }
        changed()
        kick()
    }

    /// What a failure means for the download (`OfflineTransfer.recovery`).
    private func recover(_ id: String, _ failure: HubFailure, context: Context) {
        let now = Self.now()
        switch OfflineTransfer.recovery(failure) {
        case .prepareAgain(let afterMillis):
            // Not ready after all: back to waiting on the PC.
            activeId = id
            store.setPreparing(id, percent: 0, queuePosition: 0, now: now)
            changed()
            work = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(afterMillis))
                guard !Task.isCancelled else { return }
                await self?.prepare(id, context: context)
            }
            return
        case .renew:
            activeId = id
            work = Task { [weak self] in await self?.renew(id, context: context) }
            return
        case .credentials(let message):
            store.wait(id, reason: message, until: now + 2 * Self.holdMillis, now: now)
        case .failed(let message):
            store.setState(id, .failed, error: message, now: now)
        case .later(let message):
            store.recordFailure(id, message: message, maxRetries: Self.maxRetries, now: now)
        }
        release(id)
    }

    /// An expired grant made good, and the download carried on from where it was.
    private func renew(_ id: String, context: Context) async {
        guard let row = store.row(id) else {
            release(id)
            return
        }
        let renewed: Data
        do {
            renewed = try await context.hub.data(HubEndpoints.renewOffline(grantId: row.manifest.grantId))
        } catch {
            guard activeId == id, error.kind != .cancelled else { return }
            if case .renew = OfflineTransfer.recovery(error) {
                store.recordFailure(id, message: error.message, maxRetries: Self.maxRetries, now: Self.now())
                release(id)
            } else {
                recover(id, error, context: context)
            }
            return
        }
        guard activeId == id else { return }
        guard let manifest = try? JSONDecoder().decode(OfflineManifest.self, from: renewed) else {
            store.recordFailure(id, message: "The hub's renewal could not be read", maxRetries: Self.maxRetries, now: Self.now())
            release(id)
            return
        }
        store.updateManifest(id, manifest: manifest, json: renewed, now: Self.now())
        if manifest.isApple {
            await prepare(id, context: context)
        } else {
            begin(id, context: context)
        }
    }

    /// The download is no longer carried: the next one may go.
    private func release(_ id: String) {
        if activeId == id {
            activeId = nil
            activeTask = nil
        }
        changed()
        kick()
    }

    // MARK: The demo hub

    /// The demo hub's file through `HubClient`, written out a piece at a time so the bar moves.
    private func demoDownload(_ id: String, context: Context) async {
        guard let row = store.row(id) else { return }
        let data: Data
        do {
            data = try await context.hub.data(HubEndpoints.offlineMedia(row.manifest.mediaUrl))
        } catch {
            guard activeId == id, error.kind != .cancelled else { return }
            activeId = nil
            recover(id, error, context: context)
            return
        }
        let file = store.mediaFile(row)
        try? FileManager.default.removeItem(at: file)
        FileManager.default.createFile(atPath: file.path, contents: nil)
        let handle = try? FileHandle(forWritingTo: file)
        let pieces = 16
        let piece = max(data.count / pieces, 1)
        var written = 0
        while written < data.count {
            guard !Task.isCancelled, activeId == id, let current = store.row(id) else {
                try? handle?.close()
                if stopping.remove(id) != nil {
                    changed()
                    kick()
                }
                return
            }
            let end = min(written + piece, data.count)
            try? handle?.write(contentsOf: data[written..<end])
            written = end
            progressed(current, bytes: Int64(written))
            try? await Task.sleep(for: .milliseconds(180))
        }
        try? handle?.close()
        guard activeId == id else { return }
        activeId = nil
        complete(id, TransferOutcome(taskId: -1, status: 200, moved: true, failure: nil, etag: "", token: ""))
    }

    // MARK: Beside the file

    /// The pictures, so the download reads with no hub; a missing one leaves the video whole.
    private func fetchArtwork(_ row: OfflineRow) async {
        guard let context else { return }
        let item = row.manifest.item
        for (kind, picture) in [("poster", item.poster), ("thumb", item.thumb), ("backdrop", item.backdrop)] where !picture.isEmpty {
            let target = store.artworkFile(row, kind: kind)
            if FileManager.default.fileExists(atPath: target.path) { continue }
            if let data = try? await context.hub.image(HubEndpoints.sized(picture, width: kind == "backdrop" ? 1280 : 480)) {
                try? data.write(to: target, options: .atomic)
            }
        }
        changed()
    }

    // MARK: The person's choices

    /// Paused: a moving download stops where it is, its resume data kept;
    /// one waiting on the PC stops being asked about.
    func pause(_ id: String) {
        stop(id)
        store.setItemPaused(id, true, now: Self.now())
        changed()
        kick()
    }

    func resume(_ id: String) {
        store.setItemPaused(id, false, now: Self.now())
        changed()
        kick()
    }

    /// Again, the count of failures starting over; a failure on the PC is
    /// made again there first.
    func retry(_ id: String) {
        guard let row = store.row(id) else { return }
        if row.isApple, let hub = context?.hub {
            let grantId = row.manifest.grantId
            Task { [weak self] in
                _ = try? await hub.fetch(HubEndpoints.retryOffline(grantId: grantId), as: OfflineGrantStatus.self)
                self?.store.retry(id, now: Self.now())
                self?.changed()
                self?.kick()
            }
            return
        }
        store.retry(id, now: Self.now())
        changed()
        kick()
    }

    func pauseBatch(_ batchId: String) {
        if let activeId, store.row(activeId)?.batchId == batchId { stop(activeId) }
        store.setBatchPaused(batchId, true, now: Self.now())
        changed()
        kick()
    }

    func resumeBatch(_ batchId: String) {
        store.setBatchPaused(batchId, false, now: Self.now())
        changed()
        kick()
    }

    /// Gone from the device, its transfer stopped first, and an MP4 still on
    /// the PC let go.
    func remove(_ id: String) {
        guard let row = store.row(id) else { return }
        stop(id, keepResume: false)
        letGo([row])
        store.remove(id)
        changed()
        kick()
    }

    func cancelBatch(_ batchId: String, removeCompleted: Bool) {
        if let activeId, store.row(activeId)?.batchId == batchId { stop(activeId, keepResume: false) }
        let rows = (context.map { store.batches(userId: $0.userId) } ?? []).first { $0.id == batchId }?.jobs ?? []
        letGo(rows.filter { $0.state != .complete })
        store.cancelBatch(batchId, removeCompleted: removeCompleted)
        changed()
        kick()
    }

    /// The PC's MP4 for downloads that will not finish: its job stops and its file is freed.
    private func letGo(_ rows: [OfflineRow]) {
        guard let hub = context?.hub else { return }
        let grants = rows.filter { $0.isApple && $0.state != .complete }.map(\.manifest.grantId)
        guard !grants.isEmpty else { return }
        Task {
            for grant in grants { try? await hub.send(HubEndpoints.releaseOffline(grantId: grant)) }
        }
    }

    /// The download carried now stops, keeping resume data unless it is going away.
    private func stop(_ id: String, keepResume: Bool = true) {
        guard id == activeId else { return }
        if let task = activeTask {
            stopping.insert(id)
            let file = store.row(id).map(store.resumeFile)
            task.cancel { data in
                if keepResume, let data, let file { try? data.write(to: file, options: .atomic) }
            }
        } else {
            // Waiting on the PC, renewing, or the demo's transfer: nothing to keep.
            work?.cancel()
            work = nil
            activeId = nil
        }
    }

    private func stopActive(requeue: Bool) {
        guard let id = activeId else { return }
        stop(id)
        if requeue { store.setState(id, .queued, now: Self.now()) }
    }

    // MARK: Background events (iOS)

    /// The system woke the app for this session's events: until they are all in.
    func backgroundEvents() async {
        ensureSession()
        await withCheckedContinuation { backgroundEventsDone.append($0) }
    }

    fileprivate func finishedBackgroundEvents() {
        let waiting = backgroundEventsDone
        backgroundEventsDone = []
        for continuation in waiting { continuation.resume() }
    }

    // MARK: Plumbing

    nonisolated static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    nonisolated static func freeBytes(_ folder: URL) -> Int64 {
        let values = try? folder.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? Int64.max
    }
}

/// How a background task ended: its file put in place or not, and what the
/// hub said, with the ETag of what arrived and the token it carried.
struct TransferOutcome: Sendable {
    let taskId: Int
    var status: Int
    var moved: Bool
    var failure: HubFailure?
    var etag: String
    var token: String
}

/// URLSession's delegate: on the session's own queue, it puts a finished file
/// in place before the system deletes it, reads an error body the hub sent,
/// then tells the downloader on the main actor.
private final class TransferRelay: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let store: OfflineStore
    /// Set once, before the session exists.
    weak var owner: OfflineDownloader?
    /// Per task, on the session's serial queue: how it ended, until it completes.
    private var outcomes: [Int: TransferOutcome] = [:]

    init(store: OfflineStore) {
        self.store = store
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription else { return }
        let taskId = downloadTask.taskIdentifier
        Task { @MainActor [weak owner] in owner?.wrote(id, taskId: taskId, bytes: totalBytesWritten) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription else { return }
        let http = downloadTask.response as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        var outcome = TransferOutcome(taskId: downloadTask.taskIdentifier, status: status, moved: false, failure: nil,
                                      etag: http?.value(forHTTPHeaderField: "ETag") ?? "", token: Self.token(of: downloadTask))
        guard (200...299).contains(status) else {
            // The hub's own words, from the little error body it sent.
            let body = (try? Data(contentsOf: location)) ?? Data()
            outcome.failure = HubFailure.answer(status: status, body: body,
                                                retryAfter: http?.value(forHTTPHeaderField: "Retry-After"))
            outcomes[downloadTask.taskIdentifier] = outcome
            return
        }
        // Removed while it moved: the file is dropped with the temporary one.
        guard let row = store.row(id) else { return }
        let target = store.mediaFile(row)
        try? FileManager.default.removeItem(at: target)
        do {
            try FileManager.default.moveItem(at: location, to: target)
            outcome.moved = true
        } catch {
            outcome.failure = HubFailure(.unknown, message: "The download could not be kept on this device")
        }
        outcomes[downloadTask.taskIdentifier] = outcome
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        guard let id = task.taskDescription else { return }
        let http = task.response as? HTTPURLResponse
        var outcome = outcomes.removeValue(forKey: task.taskIdentifier)
            ?? TransferOutcome(taskId: task.taskIdentifier, status: http?.statusCode ?? 0, moved: false, failure: nil,
                               etag: http?.value(forHTTPHeaderField: "ETag") ?? "", token: Self.token(of: task))
        if let error = error as NSError? {
            if let resume = error.userInfo[NSURLSessionDownloadTaskResumeData] as? Data, let row = store.row(id) {
                try? resume.write(to: store.resumeFile(row), options: .atomic)
            }
            outcome.moved = false
            let kind = FailureKind.of(error: error)
            outcome.failure = kind == .cancelled ? HubFailure(.cancelled) : HubFailure(kind)
        }
        let ended = outcome
        Task { @MainActor [weak owner] in owner?.ended(id, ended) }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor [weak owner] in owner?.finishedBackgroundEvents() }
    }

    /// The bearer token the task's request carried.
    private static func token(of task: URLSessionTask) -> String {
        let header = task.originalRequest?.value(forHTTPHeaderField: "Authorization") ?? ""
        return header.hasPrefix("Bearer ") ? String(header.dropFirst("Bearer ".count)) : ""
    }
}
