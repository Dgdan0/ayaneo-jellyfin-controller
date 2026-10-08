import Foundation

// What is downloaded, what is still coming, and the watches made offline
// (#5): Android's `offline/OfflineRepository.kt`, on files rather than SQLite.
// A file per download, so the one in progress rewrites only its own; the
// batches, the watches and the sync outbox in a small file each. Everything
// lives under one folder in Application Support, kept out of backups, never
// emptied by the system: a download is the person's to remove.

public enum OfflineState: String, Codable, CaseIterable, Sendable {
    case queued, downloading, paused, waiting, failed, complete
    /// An Apple download's MP4 is being made on the PC (or waits its turn
    /// there): `OfflineRow.hubPercent` and `hubQueuePosition` say how far.
    case preparing
}

/// One film or episode being kept on this device: a grant's manifest, where
/// its transfer is, and its file.
public struct OfflineRow: Codable, Equatable, Sendable, Identifiable {
    /// This device's key for it (`clientItemKey`).
    public let id: String
    public var batchId: String
    /// The Jellyfin profile it was downloaded for.
    public var userId: String
    public var itemId: String
    public var sourceId: String
    /// The manifest as the hub wrote it, kept whole.
    public var manifestJSON: Data
    /// `manifestJSON`, read.
    public var manifest: OfflineManifest
    public var state: OfflineState
    public var bytesDownloaded: Int64
    public var totalBytes: Int64
    /// The file's name in the media folder. No path is kept: an app's
    /// container moves when the app is updated.
    public var fileName: String
    public var error: String
    public var attempts: Int
    public var speedBytesPerSecond: Int64
    public var sortOrder: Int
    public var updatedAt: Int64
    public var retryAt: Int64
    /// How far the PC is with an Apple download's MP4, 0 to 100.
    public var hubPercent: Int
    /// Its place in the PC's line: 1 next, 0 when running or done.
    public var hubQueuePosition: Int
    /// The ETag of the PC's MP4 being downloaded: a different one is a
    /// different file, which starts over.
    public var etag: String

    public init(id: String, batchId: String, userId: String, manifest: OfflineManifest, manifestJSON: Data = Data(),
                state: OfflineState = .queued, bytesDownloaded: Int64 = 0, totalBytes: Int64? = nil, fileName: String = "",
                error: String = "", attempts: Int = 0, speedBytesPerSecond: Int64 = 0, sortOrder: Int = 0,
                updatedAt: Int64 = 0, retryAt: Int64 = 0, hubPercent: Int = 0, hubQueuePosition: Int = 0, etag: String = "") {
        self.id = id
        self.batchId = batchId
        self.userId = userId
        itemId = manifest.item.id
        sourceId = manifest.source.id
        self.manifestJSON = manifestJSON
        self.manifest = manifest
        self.state = state
        self.bytesDownloaded = bytesDownloaded
        self.totalBytes = totalBytes ?? manifest.expectedBytes
        self.fileName = fileName
        self.error = error
        self.attempts = attempts
        self.speedBytesPerSecond = speedBytesPerSecond
        self.sortOrder = sortOrder
        self.updatedAt = updatedAt
        self.retryAt = retryAt
        self.hubPercent = hubPercent
        self.hubQueuePosition = hubQueuePosition
        self.etag = etag
    }

    /// An MP4 the hub repackages, which waits on the PC before it downloads.
    public var isApple: Bool { manifest.isApple }

    /// How much has arrived, 0 to 1.
    public var progress: Double {
        totalBytes <= 0 ? 0 : min(max(Double(bytesDownloaded) / Double(totalBytes), 0), 1)
    }

    enum CodingKeys: String, CodingKey {
        case id, batchId, userId, itemId, sourceId, manifestJSON, state, bytesDownloaded, totalBytes, fileName, error
        case attempts, speedBytesPerSecond, sortOrder, updatedAt, retryAt, hubPercent, hubQueuePosition, etag
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        batchId = c.value(.batchId, "")
        userId = c.value(.userId, "")
        itemId = c.value(.itemId, "")
        sourceId = c.value(.sourceId, "")
        manifestJSON = c.value(.manifestJSON, Data())
        manifest = (try? JSONDecoder().decode(OfflineManifest.self, from: manifestJSON)) ?? OfflineManifest()
        state = c.value(.state, .failed)
        bytesDownloaded = c.value(.bytesDownloaded, 0)
        totalBytes = c.value(.totalBytes, 0)
        fileName = c.value(.fileName, "")
        error = c.value(.error, "")
        attempts = c.value(.attempts, 0)
        speedBytesPerSecond = c.value(.speedBytesPerSecond, 0)
        sortOrder = c.value(.sortOrder, 0)
        updatedAt = c.value(.updatedAt, 0)
        retryAt = c.value(.retryAt, 0)
        hubPercent = c.value(.hubPercent, 0)
        hubQueuePosition = c.value(.hubQueuePosition, 0)
        etag = c.value(.etag, "")
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(batchId, forKey: .batchId)
        try c.encode(userId, forKey: .userId)
        try c.encode(itemId, forKey: .itemId)
        try c.encode(sourceId, forKey: .sourceId)
        try c.encode(manifestJSON, forKey: .manifestJSON)
        try c.encode(state, forKey: .state)
        try c.encode(bytesDownloaded, forKey: .bytesDownloaded)
        try c.encode(totalBytes, forKey: .totalBytes)
        try c.encode(fileName, forKey: .fileName)
        try c.encode(error, forKey: .error)
        try c.encode(attempts, forKey: .attempts)
        try c.encode(speedBytesPerSecond, forKey: .speedBytesPerSecond)
        try c.encode(sortOrder, forKey: .sortOrder)
        try c.encode(updatedAt, forKey: .updatedAt)
        try c.encode(retryAt, forKey: .retryAt)
        try c.encode(hubPercent, forKey: .hubPercent)
        try c.encode(hubQueuePosition, forKey: .hubQueuePosition)
        try c.encode(etag, forKey: .etag)
    }
}

/// A group of downloads asked for together: a film, an episode, or a series' choice.
public struct OfflineBatchRecord: Codable, Equatable, Sendable {
    public let id: String
    public var title: String
    public var seriesId: String
    public var userId: String
    public var paused: Bool
    public var createdAt: Int64
}

public struct OfflineBatch: Equatable, Sendable, Identifiable {
    public let id: String
    public let title: String
    public let seriesId: String
    public let paused: Bool
    public let createdAt: Int64
    /// In the order they download.
    public let jobs: [OfflineRow]

    public var completeCount: Int { jobs.filter { $0.state == .complete }.count }
    public var totalBytes: Int64 { jobs.reduce(0) { $0 + $1.totalBytes } }
    public var downloadedBytes: Int64 { jobs.reduce(0) { $0 + min($1.bytesDownloaded, $1.totalBytes) } }
}

/// A watch kept on this device, per profile and item.
struct OfflineProgressRecord: Codable, Equatable, Sendable {
    let userId: String
    let itemId: String
    var positionMillis: Int64
    var durationMillis: Int64
    var updatedAt: Int64
}

/// A watch waiting to be sent: one per profile and item, the newest.
struct OfflineOutboxEntry: Codable, Equatable, Sendable {
    let userId: String
    let itemId: String
    let event: OfflineProgressEvent
    let createdAt: Int64
}

/// The download catalog, its queue, and the watches made offline, on disk.
/// Every method is safe from any thread; each change is written at once,
/// apart from the bytes of a transfer in progress, which the downloader
/// writes as it sees fit (`updateProgress(…, persist:)`).
public final class OfflineStore: @unchecked Sendable {
    public let root: URL
    private let lock = NSLock()
    private var batchRecords: [String: OfflineBatchRecord] = [:]
    private var rowsById: [String: OfflineRow] = [:]
    private var watches: [OfflineProgressRecord] = []
    private var pending: [OfflineOutboxEntry] = []

    public init(root: URL) {
        self.root = root
        let manager = FileManager.default
        for folder in [root, mediaFolder, subtitleFolder, artworkFolder, resumeFolder, rowFolder] {
            try? manager.createDirectory(at: folder, withIntermediateDirectories: true)
        }
        // Downloads are the person's: never in a backup, never cleared by the system.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var excluded = root
        try? excluded.setResourceValues(values)
        let decoder = JSONDecoder()
        if let data = try? Data(contentsOf: stateFolder.appendingPathComponent("batches.json")),
           let list = try? decoder.decode([OfflineBatchRecord].self, from: data) {
            batchRecords = Dictionary(list.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        }
        let files = (try? manager.contentsOfDirectory(at: rowFolder, includingPropertiesForKeys: nil)) ?? []
        for file in files where file.pathExtension == "json" {
            if let data = try? Data(contentsOf: file), let row = try? decoder.decode(OfflineRow.self, from: data) {
                rowsById[row.id] = row
            }
        }
        if let data = try? Data(contentsOf: stateFolder.appendingPathComponent("progress.json")) {
            watches = (try? decoder.decode([OfflineProgressRecord].self, from: data)) ?? []
        }
        if let data = try? Data(contentsOf: stateFolder.appendingPathComponent("outbox.json")) {
            pending = (try? decoder.decode([OfflineOutboxEntry].self, from: data)) ?? []
        }
    }

    // MARK: Folders and files

    public var mediaFolder: URL { root.appendingPathComponent("media", isDirectory: true) }
    public var subtitleFolder: URL { root.appendingPathComponent("subtitles", isDirectory: true) }
    public var artworkFolder: URL { root.appendingPathComponent("artwork", isDirectory: true) }
    /// URLSession's resume data for a paused or interrupted transfer.
    public var resumeFolder: URL { root.appendingPathComponent("resume", isDirectory: true) }
    var stateFolder: URL { root.appendingPathComponent("state", isDirectory: true) }
    var rowFolder: URL { stateFolder.appendingPathComponent("rows", isDirectory: true) }

    public func mediaFile(_ row: OfflineRow) -> URL { mediaFolder.appendingPathComponent(row.fileName) }

    public func subtitleFile(_ row: OfflineRow, track: PlaybackTrack) -> URL {
        subtitleFolder.appendingPathComponent("\(Self.safe(row.id))-\(track.index).\(Self.subtitleExtension(track.codec))")
    }

    // MARK: Subtitles kept beside an Apple download (#45)

    /// Where `key`'s WebVTT is kept: under the download's name, so removing it removes them.
    public func keptSubtitleFile(_ row: OfflineRow, key: String) -> URL {
        subtitleFolder.appendingPathComponent("\(Self.safe(row.id))-kept-\(Self.safe(key)).vtt")
    }

    private func keptSubtitlesFile(_ row: OfflineRow) -> URL {
        subtitleFolder.appendingPathComponent("\(Self.safe(row.id))-kept.json")
    }

    /// What the download keeps of its subtitles; nil when the hub was never asked.
    public func keptSubtitles(_ row: OfflineRow) -> OfflineKeptSubtitles? {
        guard let data = try? Data(contentsOf: keptSubtitlesFile(row)) else { return nil }
        return try? JSONDecoder().decode(OfflineKeptSubtitles.self, from: data)
    }

    /// Written only while the download is here: one removed during a refresh
    /// is left with nothing behind it.
    public func saveKeptSubtitles(_ row: OfflineRow, _ value: OfflineKeptSubtitles) {
        lock.lock()
        defer { lock.unlock() }
        guard rowsById[row.id] != nil else { return }
        save(value, to: keptSubtitlesFile(row))
    }

    public func hasKeptSubtitle(_ row: OfflineRow, key: String) -> Bool {
        FileManager.default.fileExists(atPath: keptSubtitleFile(row, key: key).path)
    }

    /// `data` as `key`'s file: written to a temporary name, then put in place
    /// whole; nothing while the download is not here.
    public func writeKeptSubtitle(_ row: OfflineRow, key: String, data: Data) throws {
        lock.lock()
        defer { lock.unlock() }
        guard rowsById[row.id] != nil else { return }
        let target = keptSubtitleFile(row, key: key)
        let temporary = target.appendingPathExtension("part")
        try? FileManager.default.removeItem(at: temporary)
        try data.write(to: temporary)
        if FileManager.default.fileExists(atPath: target.path) {
            _ = try FileManager.default.replaceItemAt(target, withItemAt: temporary)
        } else {
            try FileManager.default.moveItem(at: temporary, to: target)
        }
    }

    public func removeKeptSubtitle(_ row: OfflineRow, key: String) {
        try? FileManager.default.removeItem(at: keptSubtitleFile(row, key: key))
    }

    /// The kept subtitles whose files are there, with their files, in order.
    public func keptSubtitleFiles(_ row: OfflineRow) -> [(track: OfflineKeptSubtitle, file: URL)] {
        (keptSubtitles(row)?.tracks ?? []).compactMap { track in
            let file = keptSubtitleFile(row, key: track.key)
            return FileManager.default.fileExists(atPath: file.path) ? (track, file) : nil
        }
    }

    /// "poster", "thumb" or "backdrop".
    public func artworkFile(_ row: OfflineRow, kind: String) -> URL {
        artworkFolder.appendingPathComponent("\(Self.safe(row.id))-\(Self.safe(kind)).img")
    }

    public func resumeFile(_ row: OfflineRow) -> URL { resumeFolder.appendingPathComponent(Self.safe(row.id) + ".resume") }

    /// A complete download is a file of exactly its expected size.
    public func isStored(_ row: OfflineRow) -> Bool {
        row.totalBytes > 0 && Self.size(of: mediaFile(row)) == row.totalBytes
    }

    // MARK: Adding

    /// Each manifest as a queued download in one batch (the first's
    /// `batchKey`), skipping one this profile already has for the same item
    /// and version. How many were added.
    @discardableResult
    public func enqueue(title: String, seriesId: String, userId: String, manifests: [(manifest: OfflineManifest, json: Data)],
                        now: Int64) -> Int {
        guard let first = manifests.first else { return 0 }
        lock.lock()
        defer { lock.unlock() }
        let batchId = first.manifest.batchKey.isEmpty ? "batch-\(now)" : first.manifest.batchKey
        if batchRecords[batchId] == nil {
            batchRecords[batchId] = OfflineBatchRecord(id: batchId, title: title, seriesId: seriesId, userId: userId,
                                                       paused: false, createdAt: now)
            writeBatches()
        }
        var added = 0
        for (index, entry) in manifests.enumerated() {
            let manifest = entry.manifest
            let id = manifest.clientItemKey.isEmpty ? "\(batchId)-\(manifest.item.id)" : manifest.clientItemKey
            let duplicate = rowsById[id] != nil || rowsById.values.contains {
                $0.userId == userId && $0.itemId == manifest.item.id && $0.sourceId == manifest.source.id
            }
            if duplicate { continue }
            let row = OfflineRow(id: id, batchId: batchId, userId: userId, manifest: manifest, manifestJSON: entry.json,
                                 fileName: Self.safe(id) + "." + Self.safe(manifest.container), sortOrder: index, updatedAt: now)
            rowsById[id] = row
            write(row)
            added += 1
        }
        if added == 0 { removeEmptyBatch(batchId) }
        return added
    }

    // MARK: Reading

    public func row(_ id: String) -> OfflineRow? {
        lock.lock()
        defer { lock.unlock() }
        return rowsById[id]
    }

    /// This profile's batches, newest first, each's downloads in their order.
    public func batches(userId: String) -> [OfflineBatch] {
        lock.lock()
        defer { lock.unlock() }
        return batchRecords.values.filter { $0.userId == userId }.sorted { $0.createdAt > $1.createdAt }.map(batch)
    }

    public func rows(userId: String) -> [OfflineRow] {
        lock.lock()
        defer { lock.unlock() }
        return rowsById.values.filter { $0.userId == userId }.sorted { $0.updatedAt > $1.updatedAt }
    }

    /// This profile's finished downloads, the newest first.
    public func completed(userId: String) -> [OfflineRow] {
        rows(userId: userId).filter { $0.state == .complete }
    }

    /// The item's download in any state: whether it is on the device or coming.
    public func forItem(_ itemId: String, userId: String) -> OfflineRow? {
        rows(userId: userId).first { $0.itemId == itemId }
    }

    /// The item's finished download, if its file is whole; a file that is not
    /// is marked failed rather than played.
    public func completedForItem(_ itemId: String, userId: String, now: Int64) -> OfflineRow? {
        guard let row = completed(userId: userId).first(where: { $0.itemId == itemId }) else { return nil }
        if isStored(row) { return row }
        setState(row.id, .failed, error: "The downloaded file is missing or incomplete", now: now)
        return nil
    }

    /// The next download to start: queued, or waiting and due again, in an
    /// unpaused batch; the oldest batch first, in its order.
    public func nextQueued(userId: String, now: Int64) -> OfflineRow? {
        lock.lock()
        defer { lock.unlock() }
        return rowsById.values.filter { row in
            guard row.userId == userId, let batch = batchRecords[row.batchId], !batch.paused else { return false }
            return row.state == .queued || (row.state == .waiting && row.retryAt <= now)
        }.sorted { left, right in
            let a = batchRecords[left.batchId]?.createdAt ?? 0
            let b = batchRecords[right.batchId]?.createdAt ?? 0
            return a == b ? left.sortOrder < right.sortOrder : a < b
        }.first
    }

    /// When the soonest waiting download is due, if any is.
    public func nextRetryAt(userId: String, now: Int64) -> Int64? {
        lock.lock()
        defer { lock.unlock() }
        return rowsById.values.filter { row in
            guard row.userId == userId, row.state == .waiting, row.retryAt > now,
                  let batch = batchRecords[row.batchId] else { return false }
            return !batch.paused
        }.map(\.retryAt).min()
    }

    /// Bytes on the device for this profile's finished downloads.
    public func storedBytes(userId: String) -> Int64 {
        completed(userId: userId).reduce(0) { $0 + $1.totalBytes }
    }

    // MARK: A transfer

    /// The bytes arrived so far. Written to disk only when `persist` says so:
    /// a transfer reports twice a second.
    public func updateProgress(_ id: String, bytes: Int64, state: OfflineState = .downloading, speed: Int64 = 0,
                               now: Int64, persist: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard var row = rowsById[id] else { return }
        row.bytesDownloaded = bytes
        row.state = state
        row.error = ""
        row.speedBytesPerSecond = max(speed, 0)
        row.retryAt = 0
        row.updatedAt = now
        rowsById[id] = row
        if persist { write(row) }
    }

    /// A renewed grant's manifest, in place of the expired one. An Apple
    /// download keeps the exact size it was told once its MP4 was ready.
    public func updateManifest(_ id: String, manifest: OfflineManifest, json: Data, now: Int64) {
        lock.lock()
        defer { lock.unlock() }
        guard var row = rowsById[id] else { return }
        row.manifest = manifest
        row.manifestJSON = json
        if !(row.isApple && !row.etag.isEmpty) { row.totalBytes = manifest.expectedBytes }
        row.updatedAt = now
        rowsById[id] = row
        write(row)
    }

    /// An Apple download's MP4 on its way on the PC: how far, or its place in
    /// line. Written to disk when the state changes, not with each percent.
    public func setPreparing(_ id: String, percent: Int, queuePosition: Int, now: Int64) {
        lock.lock()
        defer { lock.unlock() }
        guard var row = rowsById[id] else { return }
        let persist = row.state != .preparing || !row.error.isEmpty
        row.state = .preparing
        row.hubPercent = min(max(percent, 0), 100)
        row.hubQueuePosition = max(queuePosition, 0)
        row.error = ""
        row.speedBytesPerSecond = 0
        row.retryAt = 0
        row.updatedAt = now
        rowsById[id] = row
        if persist { write(row) }
    }

    /// The PC's MP4 is ready: its exact size, and the ETag that names it. A
    /// different file from the one partly downloaded starts over, its bytes
    /// and resume data gone. True when it starts over.
    @discardableResult
    public func setReady(_ id: String, sizeBytes: Int64, etag: String, now: Int64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard var row = rowsById[id] else { return false }
        let startOver = !row.etag.isEmpty && row.etag != etag
        if startOver {
            row.bytesDownloaded = 0
            try? FileManager.default.removeItem(at: resumeFile(row))
        }
        row.etag = etag
        if sizeBytes > 0 { row.totalBytes = sizeBytes }
        row.hubPercent = 100
        row.hubQueuePosition = 0
        row.updatedAt = now
        rowsById[id] = row
        write(row)
        return startOver
    }

    /// A state and its reason. The same state again changes nothing, so a
    /// download waiting for Wi-Fi is not rewritten each time it is checked.
    public func setState(_ id: String, _ state: OfflineState, error: String = "", now: Int64) {
        lock.lock()
        defer { lock.unlock() }
        guard var row = rowsById[id] else { return }
        let reason = String(error.prefix(300))
        if row.state == state && row.error == reason && (state == .downloading || row.speedBytesPerSecond == 0) { return }
        row.state = state
        row.error = reason
        row.updatedAt = now
        if state != .downloading { row.speedBytesPerSecond = 0 }
        if state != .waiting { row.retryAt = 0 }
        rowsById[id] = row
        write(row)
    }

    /// The transfer ended: complete when the file is exactly its size, else failed.
    public func finish(_ id: String, now: Int64) {
        lock.lock()
        guard var row = rowsById[id] else {
            lock.unlock()
            return
        }
        guard isStored(row) else {
            lock.unlock()
            setState(id, .failed, error: "The downloaded file did not match its expected size", now: now)
            return
        }
        row.state = .complete
        row.bytesDownloaded = row.totalBytes
        row.speedBytesPerSecond = 0
        row.error = ""
        row.retryAt = 0
        row.updatedAt = now
        rowsById[id] = row
        write(row)
        lock.unlock()
    }

    /// A failure: tried again after a pause that grows (`OfflineRetryPolicy`),
    /// then left for the person once `maxRetries` have failed.
    public func recordFailure(_ id: String, message: String, maxRetries: Int, now: Int64) {
        lock.lock()
        defer { lock.unlock() }
        guard var row = rowsById[id] else { return }
        row.attempts += 1
        let failed = row.attempts > maxRetries
        row.state = failed ? .failed : .waiting
        row.speedBytesPerSecond = 0
        row.retryAt = failed ? 0 : now + OfflineRetryPolicy.delayMillis(row.attempts)
        row.error = String(message.prefix(300))
        row.updatedAt = now
        rowsById[id] = row
        write(row)
    }

    /// Held back for a reason that is not the download's fault (no Wi-Fi, no
    /// room, a refused token) until `until`, without counting a failure.
    public func wait(_ id: String, reason: String, until: Int64, now: Int64) {
        lock.lock()
        defer { lock.unlock() }
        guard var row = rowsById[id] else { return }
        let reason = String(reason.prefix(300))
        let persist = row.state != .waiting || row.error != reason
        row.state = .waiting
        row.error = reason
        row.retryAt = until
        row.speedBytesPerSecond = 0
        row.updatedAt = now
        rowsById[id] = row
        if persist { write(row) }
    }

    /// Whatever held the waiting downloads back has changed (Wi-Fi is back, a
    /// new token): they may go now.
    public func wakeWaiting(userId: String, now: Int64) {
        lock.lock()
        defer { lock.unlock() }
        for var row in rowsById.values where row.userId == userId && row.state == .waiting && row.retryAt > now {
            row.retryAt = now
            rowsById[row.id] = row
            write(row)
        }
    }

    /// Downloads still marked as moving, or waiting on the PC, that nothing is
    /// carrying (the app was stopped with one under way) go back to the head
    /// of the queue.
    public func requeueInterrupted(keeping active: Set<String>, now: Int64) {
        lock.lock()
        let stranded = rowsById.values.filter {
            ($0.state == .downloading || $0.state == .preparing) && !active.contains($0.id)
        }.map(\.id)
        lock.unlock()
        for id in stranded { setState(id, .queued, now: now) }
    }

    // MARK: The person's choices

    /// A batch paused stops its queued and waiting downloads; resumed, its paused ones queue again.
    public func setBatchPaused(_ batchId: String, _ paused: Bool, now: Int64) {
        lock.lock()
        defer { lock.unlock() }
        guard var batch = batchRecords[batchId] else { return }
        batch.paused = paused
        batchRecords[batchId] = batch
        writeBatches()
        for var row in rowsById.values where row.batchId == batchId {
            if paused, row.state == .queued || row.state == .waiting || row.state == .downloading || row.state == .preparing {
                row.state = .paused
            } else if !paused, row.state == .paused {
                row.state = .queued
            } else {
                continue
            }
            row.retryAt = 0
            row.speedBytesPerSecond = 0
            row.updatedAt = now
            rowsById[row.id] = row
            write(row)
        }
    }

    public func setItemPaused(_ id: String, _ paused: Bool, now: Int64) {
        if paused {
            setState(id, .paused, now: now)
            return
        }
        lock.lock()
        defer { lock.unlock() }
        guard var row = rowsById[id] else { return }
        row.state = .queued
        row.retryAt = 0
        row.error = ""
        row.speedBytesPerSecond = 0
        row.updatedAt = now
        rowsById[id] = row
        write(row)
    }

    /// Retry now, the count of failures starting again.
    public func retry(_ id: String, now: Int64) {
        lock.lock()
        defer { lock.unlock() }
        guard var row = rowsById[id] else { return }
        row.state = .queued
        row.attempts = 0
        row.error = ""
        row.speedBytesPerSecond = 0
        row.retryAt = 0
        row.updatedAt = now
        rowsById[id] = row
        write(row)
    }

    /// A download and its files gone from this device. Never the server's media.
    public func remove(_ id: String) {
        lock.lock()
        defer { lock.unlock() }
        guard let row = rowsById[id] else { return }
        delete(row)
        removeEmptyBatch(row.batchId)
    }

    public func removeBatch(_ batchId: String) {
        cancelBatch(batchId, removeCompleted: true)
    }

    /// The batch's unfinished downloads gone, and its finished ones too when `removeCompleted`.
    public func cancelBatch(_ batchId: String, removeCompleted: Bool) {
        lock.lock()
        defer { lock.unlock() }
        for row in rowsById.values where row.batchId == batchId && (removeCompleted || row.state != .complete) {
            delete(row)
        }
        removeEmptyBatch(batchId)
    }

    // MARK: Watches made offline

    /// Where the person is, kept for this profile, and queued for the hub:
    /// one waiting watch per item, the newest.
    public func rememberPlayback(userId: String, itemId: String, positionMillis: Int64, durationMillis: Int64,
                                 completed: Bool, now: Int64, eventKey: String) {
        lock.lock()
        defer { lock.unlock() }
        let record = OfflineProgressRecord(userId: userId, itemId: itemId, positionMillis: max(0, positionMillis),
                                           durationMillis: max(0, durationMillis), updatedAt: now)
        watches.removeAll { $0.userId == userId && $0.itemId == itemId }
        watches.append(record)
        writeWatches()
        guard durationMillis > 0 else { return }
        let event = OfflineProgressEvent(clientEventKey: eventKey, itemId: itemId, positionMillis: max(0, positionMillis),
                                         durationMillis: durationMillis, completed: completed, occurredAt: now)
        pending.removeAll { $0.userId == userId && $0.itemId == itemId }
        pending.append(OfflineOutboxEntry(userId: userId, itemId: itemId, event: event, createdAt: now))
        writeOutbox()
    }

    /// This profile's watches kept here for these items.
    public func progress(itemIds: [String], userId: String) -> [String: OfflineCatalogProgress] {
        lock.lock()
        defer { lock.unlock() }
        let wanted = Set(itemIds)
        var out: [String: OfflineCatalogProgress] = [:]
        for record in watches where record.userId == userId && wanted.contains(record.itemId) {
            out[record.itemId] = OfflineCatalogProgress(positionMillis: record.positionMillis,
                                                        durationMillis: record.durationMillis,
                                                        updatedAtMillis: record.updatedAt)
        }
        return out
    }

    /// The watches waiting to be sent for this profile, the oldest first.
    public func outbox(userId: String, limit: Int = 50) -> [OfflineProgressEvent] {
        lock.lock()
        defer { lock.unlock() }
        return Array(pending.filter { $0.userId == userId }.sorted { $0.createdAt < $1.createdAt }.prefix(limit).map(\.event))
    }

    /// The profiles with watches waiting to be sent, each sent as itself.
    public func outboxUsers() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        var users: [String] = []
        for entry in pending.sorted(by: { $0.createdAt < $1.createdAt }) where !users.contains(entry.userId) {
            users.append(entry.userId)
        }
        return users
    }

    /// The hub took these (or had a later watch): no longer waiting.
    public func removeOutbox(keys: [String]) {
        lock.lock()
        defer { lock.unlock() }
        let gone = Set(keys)
        let before = pending.count
        pending.removeAll { gone.contains($0.event.clientEventKey) }
        if pending.count != before { writeOutbox() }
    }

    /// The server's later watch in place of this device's own, not queued:
    /// the server already has it. Every offline page then agrees with what
    /// playback will do.
    public func adoptServerWatch(userId: String, itemId: String, server: OfflineCatalogProgress) {
        lock.lock()
        defer { lock.unlock() }
        let current = watches.first { $0.userId == userId && $0.itemId == itemId }.map {
            OfflineCatalogProgress(positionMillis: $0.positionMillis, durationMillis: $0.durationMillis, updatedAtMillis: $0.updatedAt)
        }
        if server.updatedAtMillis <= 0 { return }
        if let current, OfflineCatalogProgress.newer(current, server) == current { return }
        watches.removeAll { $0.userId == userId && $0.itemId == itemId }
        watches.append(OfflineProgressRecord(userId: userId, itemId: itemId, positionMillis: server.positionMillis,
                                             durationMillis: server.durationMillis, updatedAt: server.updatedAtMillis))
        writeWatches()
    }

    // MARK: Plumbing (under the lock)

    private func batch(_ record: OfflineBatchRecord) -> OfflineBatch {
        OfflineBatch(id: record.id, title: record.title, seriesId: record.seriesId, paused: record.paused,
                     createdAt: record.createdAt,
                     jobs: rowsById.values.filter { $0.batchId == record.id }.sorted { $0.sortOrder < $1.sortOrder })
    }

    private func delete(_ row: OfflineRow) {
        let manager = FileManager.default
        try? manager.removeItem(at: mediaFile(row))
        try? manager.removeItem(at: resumeFile(row))
        let prefix = Self.safe(row.id) + "-"
        for folder in [subtitleFolder, artworkFolder] {
            for file in (try? manager.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
            where file.lastPathComponent.hasPrefix(prefix) {
                try? manager.removeItem(at: file)
            }
        }
        try? manager.removeItem(at: rowFile(row.id))
        rowsById[row.id] = nil
    }

    private func removeEmptyBatch(_ batchId: String) {
        if !rowsById.values.contains(where: { $0.batchId == batchId }), batchRecords[batchId] != nil {
            batchRecords[batchId] = nil
            writeBatches()
        }
    }

    private func rowFile(_ id: String) -> URL { rowFolder.appendingPathComponent(Self.safe(id) + ".json") }

    private func write(_ row: OfflineRow) { save(row, to: rowFile(row.id)) }
    private func writeBatches() { save(Array(batchRecords.values), to: stateFolder.appendingPathComponent("batches.json")) }
    private func writeWatches() { save(watches, to: stateFolder.appendingPathComponent("progress.json")) }
    private func writeOutbox() { save(pending, to: stateFolder.appendingPathComponent("outbox.json")) }

    private func save<T: Encodable>(_ value: T, to file: URL) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: file, options: .atomic)
    }

    // MARK: Names

    /// A name safe in a file name.
    static func safe(_ value: String) -> String {
        String(value.map { $0.isLetter || $0.isNumber || $0 == "." || $0 == "_" || $0 == "-" ? $0 : "_" })
    }

    static func subtitleExtension(_ codec: String) -> String {
        switch codec.lowercased() {
        case "srt", "subrip": "srt"
        case "ass", "ssa": "ass"
        default: "vtt"
        }
    }

    static func size(of file: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: file.path)[.size]) as? NSNumber)?.int64Value ?? -1
    }
}

/// Transient failures wait before the next try, so one interrupted download
/// does not hold up the queue: 5 s, 15 s, 45 s, 2 min, then every 5 min
/// (Android's `OfflineRetryPolicy`).
public enum OfflineRetryPolicy {
    private static let delays: [Int64] = [5_000, 15_000, 45_000, 120_000, 300_000]

    public static func delayMillis(_ failedAttempts: Int) -> Int64 {
        delays[min(max(failedAttempts - 1, 0), delays.count - 1)]
    }
}
