import CryptoKit
import Foundation

/// A small, bounded disk cache of answers the hub gave, so Home and Discover
/// can open with their last answer while a new one loads (#38; Android's
/// `net/PersistentResponseCache`, held to `PersistentResponseCacheTest`).
///
/// An entry's file is named by the SHA-256 of its key, so the hub's token and
/// the profile that keep one apart are never written in clear. The files live
/// in the app's caches folder, which the system may empty at any time: a read
/// treats a missing, expired or damaged file as nothing kept.
public final class AnswerKeeper: @unchecked Sendable {
    public struct Entry: Equatable, Sendable {
        public let body: Data
        /// How long ago it was kept.
        public let age: TimeInterval
    }

    public static let defaultMaxBytes = 128 << 20
    public static let defaultMaxEntries = 1_024
    public static let defaultMaxEntryBytes = 4 << 20
    /// The longest an answer is shown as the last one: two weeks, as Android keeps Discover's.
    public static let longest: TimeInterval = 14 * 24 * 3_600

    private static let magic = "JH-ANSWER-1"
    private static let ext = "answer"

    private let directory: URL
    private let maxBytes: Int
    private let maxEntries: Int
    private let maxEntryBytes: Int
    private let now: @Sendable () -> Date
    private let lock = NSLock()

    public init(directory: URL, maxBytes: Int = defaultMaxBytes, maxEntries: Int = defaultMaxEntries,
                maxEntryBytes: Int = defaultMaxEntryBytes, now: @escaping @Sendable () -> Date = { Date() }) {
        self.directory = directory
        self.maxBytes = maxBytes
        self.maxEntries = maxEntries
        self.maxEntryBytes = maxEntryBytes
        self.now = now
    }

    /// In the app's caches folder.
    public static func standard(named name: String = "last-answers") -> AnswerKeeper {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return AnswerKeeper(directory: caches.appendingPathComponent(name, isDirectory: true))
    }

    /// One key for one answer of one hub, token and profile. Only its hash
    /// reaches a file name.
    public static func key(path: String, address: String, token: String, user: String) -> String {
        ["answer-v1", path, address, token, user].joined(separator: "\n")
    }

    public func read(_ key: String, maxAge: TimeInterval) -> Entry? {
        guard !key.isEmpty, maxAge >= 0 else { return nil }
        return lock.withLock {
            let file = fileURL(key)
            guard let raw = try? Data(contentsOf: file) else { return nil }
            guard let entry = parse(raw) else {
                try? FileManager.default.removeItem(at: file)
                return nil
            }
            let age = max(0, now().timeIntervalSince(entry.storedAt))
            if age > maxAge {
                try? FileManager.default.removeItem(at: file)
                return nil
            }
            // Used just now: pruning drops the least recently used first.
            try? FileManager.default.setAttributes([.modificationDate: now()], ofItemAtPath: file.path)
            return Entry(body: entry.body, age: age)
        }
    }

    public func put(_ key: String, body: Data) {
        guard !key.isEmpty, !body.isEmpty, body.count <= maxEntryBytes else { return }
        lock.withLock {
            let manager = FileManager.default
            do {
                try manager.createDirectory(at: directory, withIntermediateDirectories: true)
                let header = Data("\(Self.magic)\n\(Int64(now().timeIntervalSince1970 * 1_000))\n".utf8)
                try (header + body).write(to: fileURL(key), options: .atomic)
                try? manager.setAttributes([.modificationDate: now()], ofItemAtPath: fileURL(key).path)
                prune()
            } catch {
                try? manager.removeItem(at: fileURL(key))
            }
        }
    }

    public func remove(_ key: String) {
        guard !key.isEmpty else { return }
        lock.withLock { try? FileManager.default.removeItem(at: fileURL(key)) }
    }

    /// Every answer kept, forgotten: what Settings' clear would do.
    public func removeAll() {
        lock.withLock { try? FileManager.default.removeItem(at: directory) }
    }

    // MARK: Files

    private func fileURL(_ key: String) -> URL {
        let digest = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(digest + "." + Self.ext)
    }

    private func parse(_ raw: Data) -> (storedAt: Date, body: Data)? {
        guard let first = raw.firstIndex(of: 10) else { return nil }
        guard let second = raw[(first + 1)...].firstIndex(of: 10) else { return nil }
        guard String(decoding: raw[..<first], as: UTF8.self) == Self.magic,
              let millis = Int64(String(decoding: raw[(first + 1)..<second], as: UTF8.self)) else { return nil }
        return (Date(timeIntervalSince1970: Double(millis) / 1_000), Data(raw[(second + 1)...]))
    }

    /// The oldest files go first until the folder fits its budget.
    private func prune() {
        let manager = FileManager.default
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        guard let urls = try? manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys) else { return }
        var files = urls.filter { $0.pathExtension == Self.ext }.compactMap { url -> (url: URL, date: Date, size: Int)? in
            guard let values = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
            return (url, values.contentModificationDate ?? .distantPast, values.fileSize ?? 0)
        }.sorted { $0.date < $1.date }
        var bytes = files.reduce(0) { $0 + $1.size }
        while files.count > maxEntries || bytes > maxBytes, !files.isEmpty {
            let oldest = files.removeFirst()
            if (try? manager.removeItem(at: oldest.url)) != nil { bytes -= oldest.size }
        }
    }
}

/// What a screen shows from the last answer, and says about it.
public enum LastAnswer {
    /// "Showing the last answer, from 4 min ago · updating".
    public static func status(ageSeconds: Int) -> StatusMessage {
        StatusMessage("Showing the last answer, from \(StatusText.age(ageSeconds)) · updating")
    }

    /// An answer worth keeping: it has something in it, and nothing was
    /// missing from it (a read with a service out would be kept as the last one).
    public static func worthKeeping(rows: Int, unavailable: Int) -> Bool { rows > 0 && unavailable == 0 }
}

/// A kept answer and how old it is.
public struct KeptAnswer<Value: Sendable>: Sendable {
    public let value: Value
    public let ageSeconds: Int
}

extension HubClient {
    /// Sends `request`, and keeps the body when `keep` says the answer is worth
    /// it, for `lastAnswer` to find at the next launch.
    public func fetchKept<T: Decodable & Sendable>(_ request: HubRequest, as type: T.Type = T.self, keeper: AnswerKeeper,
                                                   keep: @Sendable (T) -> Bool = { _ in true }) async throws(HubFailure) -> T {
        let data = try await self.data(request)
        let value: T
        do {
            value = try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw HubFailure(.badResponse)
        }
        if keep(value) { keeper.put(keeperKey(request, current), body: data) }
        return value
    }

    /// What `fetchKept` kept for this hub, token and profile, if it is not
    /// older than `maxAge` and still reads.
    public func lastAnswer<T: Decodable & Sendable>(_ request: HubRequest, as type: T.Type = T.self, keeper: AnswerKeeper,
                                                    maxAge: TimeInterval = AnswerKeeper.longest) -> KeptAnswer<T>? {
        let key = keeperKey(request, current)
        guard let entry = keeper.read(key, maxAge: maxAge) else { return nil }
        guard let value = try? JSONDecoder().decode(T.self, from: entry.body) else {
            keeper.remove(key)
            return nil
        }
        return KeptAnswer(value: value, ageSeconds: Int(entry.age))
    }

    private func keeperKey(_ request: HubRequest, _ credentials: HubCredentials) -> String {
        AnswerKeeper.key(path: request.path, address: credentials.baseURL, token: credentials.token,
                         user: request.user ?? credentials.userId)
    }
}
