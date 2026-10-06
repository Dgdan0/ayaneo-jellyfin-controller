import Foundation
import HubKit
import Network

/// The transfers behind Downloads (#5; Android's `OfflineDownloadService`):
/// one download at a time, in the queue's order, the oldest batch first.
///
/// A real hub's files come through a background `URLSession`, so a download
/// goes on with the app in the background or quit by the system, and a pause
/// or a dropped connection keeps resume data to go on from where it was. A
/// grant past its date (410) is renewed and the download carries on; a file
/// that changed on the server (409 `source_changed`) is left for the person.
/// The demo hub cannot be reached by URLSession, so its file comes through
/// `HubClient` and is written out a piece at a time, so the bar moves.
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

    private let store: OfflineStore
    private let changed: () -> Void
    private var context: Context?
    private let relay: TransferRelay
    private var session: URLSession?
    /// The download moving now, and its task when it comes through URLSession.
    private var activeId: String?
    private var activeTask: URLSessionDownloadTask?
    private var demoTransfer: Task<Void, Never>?
    /// Asked to stop for a pause or a removal, so its cancellation is not a failure.
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
            let wifi = update.status == .satisfied && !update.isExpensive
            Task { @MainActor in self?.networkChanged(wifi: wifi) }
        }
        path.start(queue: DispatchQueue(label: "offline.path"))
    }

    var wifiOnly: Bool {
        get { UserDefaults.standard.object(forKey: "offline.wifiOnly") as? Bool ?? true }
        set {
            UserDefaults.standard.set(newValue, forKey: "offline.wifiOnly")
            kick()
        }
    }

    /// The hub, the token and the profile to download for; downloads already
    /// under way in the background are found again.
    func attach(_ next: Context) {
        let profileChanged = context?.userId != next.userId
        context = next
        if session == nil && !next.isDemo {
            let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
            configuration.isDiscretionary = false
            #if os(iOS)
            configuration.sessionSendsLaunchEvents = true
            #endif
            configuration.httpMaximumConnectionsPerHost = 1
            let queue = OperationQueue()
            queue.maxConcurrentOperationCount = 1
            session = URLSession(configuration: configuration, delegate: relay, delegateQueue: queue)
        }
        if profileChanged { stopActive(requeue: true) }
        guard let session else {
            store.requeueInterrupted(keeping: [], now: Self.now())
            kick()
            return
        }
        session.getAllTasks { tasks in
            let running = tasks.compactMap { $0 as? URLSessionDownloadTask }.filter { $0.state == .running }
            let ids = Set(running.compactMap(\.taskDescription))
            Task { @MainActor [weak self] in
                guard let self else { return }
                if self.activeTask == nil, let task = running.first, let id = task.taskDescription {
                    self.activeId = id
                    self.activeTask = task
                }
                self.store.requeueInterrupted(keeping: ids, now: Self.now())
                self.changed()
                self.kick()
            }
        }
    }

    // MARK: The queue

    /// Starts the next download when none is moving.
    func kick() {
        guard let context, activeId == nil else { return }
        let now = Self.now()
        guard let row = store.nextQueued(userId: context.userId, now: now) else {
            scheduleWake(at: store.nextRetryAt(userId: context.userId, now: now))
            return
        }
        if let reason = blocked(row, context: context) {
            store.setState(row.id, .waiting, error: reason, now: now)
            changed()
            scheduleWake(at: now + 30_000)
            return
        }
        activeId = row.id
        samples = []
        store.updateProgress(row.id, bytes: row.bytesDownloaded, now: now, persist: true)
        changed()
        if context.isDemo {
            demoTransfer = Task { await demoDownload(row, context: context) }
        } else {
            start(row, context: context)
        }
    }

    /// Why a download cannot start now, in words for its row.
    private func blocked(_ row: OfflineRow, context: Context) -> String? {
        if !context.isDemo && wifiOnly && !onWiFi { return "Waiting for Wi-Fi" }
        let remaining = max(row.totalBytes - row.bytesDownloaded, 0)
        if Self.freeBytes(store.root) < remaining + Self.reserveBytes { return "Not enough free space" }
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

    private func networkChanged(wifi: Bool) {
        guard wifi != onWiFi else { return }
        onWiFi = wifi
        changed()
        if wifi { kick() }
    }

    // MARK: Through URLSession

    private func start(_ row: OfflineRow, context: Context) {
        guard let session, let url = URL(string: HubEndpoints.join(context.baseURL, row.manifest.mediaUrl)) else {
            fail(row.id, "The hub's address for this download cannot be used")
            return
        }
        let task: URLSessionDownloadTask
        if let resume = try? Data(contentsOf: store.resumeFile(row)) {
            task = session.downloadTask(withResumeData: resume)
            try? FileManager.default.removeItem(at: store.resumeFile(row))
        } else {
            var request = URLRequest(url: url)
            request.setValue("Bearer " + context.token, forHTTPHeaderField: "Authorization")
            if !context.userId.isEmpty { request.setValue(context.userId, forHTTPHeaderField: HubClient.userHeader) }
            request.allowsExpensiveNetworkAccess = !wifiOnly
            request.allowsConstrainedNetworkAccess = !wifiOnly
            task = session.downloadTask(with: request)
        }
        task.taskDescription = row.id
        task.countOfBytesClientExpectsToReceive = max(row.totalBytes - row.bytesDownloaded, 0)
        activeTask = task
        task.resume()
    }

    /// From the relay, as bytes arrive.
    fileprivate func wrote(_ id: String, bytes: Int64) {
        guard id == activeId, let row = store.row(id) else { return }
        let now = Date()
        samples.append((now, bytes))
        samples.removeAll { now.timeIntervalSince($0.at) > 10 }
        let first = samples.first ?? (now, bytes)
        let seconds = max(now.timeIntervalSince(first.at), 0.001)
        let speed = samples.count > 1 ? Int64(Double(bytes - first.bytes) / seconds) : 0
        // Written to disk now and then: the transfer reports far more often.
        let persist = now.timeIntervalSince(lastWritten) > 5
        if persist { lastWritten = now }
        store.updateProgress(id, bytes: min(bytes, row.totalBytes), speed: speed, now: Self.now(), persist: persist)
        changed()
    }

    /// From the relay: the task ended. `moved` is whether the file was put in place.
    fileprivate func ended(_ id: String, status: Int, moved: Bool, failure: String?) {
        let wasActive = id == activeId
        if wasActive {
            activeId = nil
            activeTask = nil
        }
        let now = Self.now()
        if stopping.remove(id) != nil {
            changed()
            kick()
            return
        }
        if status == 410 {
            Task { await renew(id) }
            return
        }
        if moved {
            store.finish(id, now: now)
            if let row = store.row(id), row.state == .complete {
                try? FileManager.default.removeItem(at: store.resumeFile(row))
                Task { await fetchExtras(row) }
            }
        } else {
            fail(id, failure ?? "The download stopped")
        }
        changed()
        kick()
    }

    /// An expired grant made good, and the download queued again from where it was.
    private func renew(_ id: String) async {
        guard let context, let row = store.row(id) else { return }
        do throws(HubFailure) {
            let data = try await context.hub.data(HubEndpoints.renewOffline(grantId: row.manifest.grantId))
            let manifest = try JSONDecoder().decode(OfflineManifest.self, from: data)
            store.updateManifest(id, manifest: manifest, json: data, now: Self.now())
            store.setState(id, .queued, now: Self.now())
        } catch {
            if error.status == 409 {
                store.recordFailure(id, message: "The file changed on the server: remove this download and add it again",
                                    maxRetries: 0, now: Self.now())
            } else {
                store.recordFailure(id, message: error.message, maxRetries: Self.maxRetries, now: Self.now())
            }
        } catch {
            store.recordFailure(id, message: "The hub's renewal could not be read", maxRetries: Self.maxRetries, now: Self.now())
        }
        changed()
        kick()
    }

    private func fail(_ id: String, _ message: String) {
        if id == activeId {
            activeId = nil
            activeTask = nil
        }
        store.recordFailure(id, message: message, maxRetries: Self.maxRetries, now: Self.now())
        changed()
    }

    // MARK: The demo hub

    private func demoDownload(_ row: OfflineRow, context: Context) async {
        let id = row.id
        do throws(HubFailure) {
            let data = try await context.hub.data(HubRequest(row.manifest.mediaUrl))
            let file = store.mediaFile(row)
            try? FileManager.default.removeItem(at: file)
            FileManager.default.createFile(atPath: file.path, contents: nil)
            let handle = try? FileHandle(forWritingTo: file)
            let pieces = 16
            let piece = max(data.count / pieces, 1)
            var written = 0
            while written < data.count {
                guard !Task.isCancelled, activeId == id else {
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
                wrote(id, bytes: Int64(written))
                try? await Task.sleep(for: .milliseconds(180))
            }
            try? handle?.close()
            ended(id, status: 200, moved: true, failure: nil)
        } catch {
            guard activeId == id else { return }
            ended(id, status: error.status ?? 0, moved: false, failure: error.message)
        }
    }

    // MARK: Beside the file

    /// The subtitle files and the pictures, so the download reads and plays
    /// with no hub. A missing one leaves the video whole.
    private func fetchExtras(_ row: OfflineRow) async {
        guard let context else { return }
        for subtitle in row.manifest.subtitles where !subtitle.url.isEmpty {
            let target = store.subtitleFile(row, track: subtitle.track)
            if let data = try? await context.hub.data(HubRequest(subtitle.url)) {
                try? data.write(to: target, options: .atomic)
            }
        }
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

    /// Paused: a moving download stops where it is, its resume data kept.
    func pause(_ id: String) {
        store.setItemPaused(id, true, now: Self.now())
        stop(id)
        changed()
        kick()
    }

    func resume(_ id: String) {
        store.setItemPaused(id, false, now: Self.now())
        changed()
        kick()
    }

    func retry(_ id: String) {
        store.retry(id, now: Self.now())
        changed()
        kick()
    }

    func pauseBatch(_ batchId: String) {
        store.setBatchPaused(batchId, true, now: Self.now())
        if let activeId, store.row(activeId)?.batchId == batchId { stop(activeId) }
        changed()
        kick()
    }

    func resumeBatch(_ batchId: String) {
        store.setBatchPaused(batchId, false, now: Self.now())
        changed()
        kick()
    }

    /// Gone from the device, its transfer stopped first.
    func remove(_ id: String) {
        stop(id, keepResume: false)
        store.remove(id)
        changed()
        kick()
    }

    func cancelBatch(_ batchId: String, removeCompleted: Bool) {
        if let activeId, store.row(activeId)?.batchId == batchId { stop(activeId, keepResume: false) }
        store.cancelBatch(batchId, removeCompleted: removeCompleted)
        changed()
        kick()
    }

    /// The moving transfer for `id` stopped, keeping resume data unless it is going away.
    private func stop(_ id: String, keepResume: Bool = true) {
        guard id == activeId else { return }
        stopping.insert(id)
        if let task = activeTask {
            let file = store.row(id).map(store.resumeFile)
            task.cancel { data in
                if keepResume, let data, let file { try? data.write(to: file, options: .atomic) }
            }
        } else {
            demoTransfer?.cancel()
            demoTransfer = nil
            activeId = nil
            stopping.remove(id)
        }
    }

    private func stopActive(requeue: Bool) {
        guard let activeId else { return }
        let id = activeId
        stop(id)
        if requeue { store.setState(id, .queued, now: Self.now()) }
    }

    // MARK: Background events (iOS)

    /// The system woke the app for this session's events: until they are all in.
    func backgroundEvents() async {
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

/// URLSession's delegate: on the session's own queue, it puts a finished file
/// in place before the system deletes it, then tells the downloader on the
/// main actor.
private final class TransferRelay: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let store: OfflineStore
    weak var owner: OfflineDownloader?
    /// Per task: the status it ended with and whether its file was moved.
    private var outcomes: [Int: (status: Int, moved: Bool, failure: String?)] = [:]

    init(store: OfflineStore) {
        self.store = store
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription else { return }
        Task { @MainActor [weak owner] in owner?.wrote(id, bytes: totalBytesWritten) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription, let row = store.row(id) else { return }
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(status) else {
            // The hub's own words, from the little error body it sent.
            let body = try? Data(contentsOf: location)
            let message = body.flatMap { HubClient.hubMessage($0) } ?? "The hub answered \(status)"
            outcomes[downloadTask.taskIdentifier] = (status, false, message)
            return
        }
        let target = store.mediaFile(row)
        try? FileManager.default.removeItem(at: target)
        do {
            try FileManager.default.moveItem(at: location, to: target)
            outcomes[downloadTask.taskIdentifier] = (status, true, nil)
        } catch {
            outcomes[downloadTask.taskIdentifier] = (status, false, "The download could not be kept on this device")
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        guard let id = task.taskDescription else { return }
        let outcome = outcomes.removeValue(forKey: task.taskIdentifier)
        var failure = outcome?.failure
        if let error = error as NSError? {
            if let resume = error.userInfo[NSURLSessionDownloadTaskResumeData] as? Data, let row = store.row(id) {
                try? resume.write(to: store.resumeFile(row), options: .atomic)
            }
            failure = error.code == NSURLErrorCancelled ? "Stopped" : error.localizedDescription
        }
        let status = outcome?.status ?? 0
        let moved = outcome?.moved ?? false
        let reason = failure
        Task { @MainActor [weak owner] in owner?.ended(id, status: status, moved: moved, failure: reason) }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor [weak owner] in owner?.finishedBackgroundEvents() }
    }
}
