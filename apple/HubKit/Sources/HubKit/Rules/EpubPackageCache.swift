import CryptoKit
import Foundation

// The EPUBs kept on this device (#25, phase 4): Android's
// `reader/EpubPackageCache.kt`, with its test cases. A book is downloaded
// whole through the hub's `…/file`, then opened from here, so a second
// opening needs no network and Readium reads a file rather than a stream.

public struct EpubCacheEntry: Equatable, Sendable {
    public let id: String
    public let sizeBytes: Int64
    public let lastAccessMillis: Int64
    public let active: Bool

    public init(id: String, sizeBytes: Int64, lastAccessMillis: Int64, active: Bool = false) {
        self.id = id
        self.sizeBytes = sizeBytes
        self.lastAccessMillis = lastAccessMillis
        self.active = active
    }
}

public enum EpubPackageCachePolicy {
    /// What to delete to come under `budgetBytes`: the least recently opened
    /// first, never the book open now.
    public static func evict(_ entries: [EpubCacheEntry], budgetBytes: Int64) -> [String] {
        let budget = max(budgetBytes, 0)
        var used = entries.reduce(Int64(0)) { $0 + max($1.sizeBytes, 0) }
        if used <= budget { return [] }
        var removed: [String] = []
        for entry in entries.filter({ !$0.active }).sorted(by: { $0.lastAccessMillis < $1.lastAccessMillis }) {
            if used <= budget { break }
            removed.append(entry.id)
            used -= max(entry.sizeBytes, 0)
        }
        return removed
    }

    /// Books kept on a device: about twenty novels' worth.
    public static let budgetBytes: Int64 = 512 << 20
}

/// Complete EPUBs are moved into place whole; a partial download is never opened.
public struct EpubPackageCache: Sendable {
    public let root: URL

    public init(root: URL) {
        self.root = root
    }

    /// This hub's and profile's books: a folder of their own under `base`.
    public static func folder(base: URL, address: String, userId: String) -> URL {
        let digest = SHA256.hash(data: Data((address + "\u{0}" + userId).utf8))
        return base.appendingPathComponent(digest.prefix(12).map { String(format: "%02x", $0) }.joined(), isDirectory: true)
    }

    public func completeFile(workId: String, sourceItemId: String) -> URL {
        root.appendingPathComponent(Self.stableName(workId, sourceItemId) + ".epub")
    }

    public func temporaryFile(workId: String, sourceItemId: String) -> URL {
        root.appendingPathComponent(Self.stableName(workId, sourceItemId) + ".part")
    }

    public func isComplete(workId: String, sourceItemId: String) -> Bool {
        let size = (try? FileManager.default.attributesOfItem(atPath: completeFile(workId: workId, sourceItemId: sourceItemId).path)[.size]
                    as? NSNumber)?.int64Value ?? 0
        return size > 0
    }

    /// `writer` fills the temporary file; it is moved into place only when it
    /// is complete, and the temporary file never outlives the call.
    @discardableResult
    public func install(workId: String, sourceItemId: String, writer: (URL) throws -> Void) throws -> URL {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let temporary = temporaryFile(workId: workId, sourceItemId: sourceItemId)
        try? FileManager.default.removeItem(at: temporary)
        defer { try? FileManager.default.removeItem(at: temporary) }
        try writer(temporary)
        return try promote(workId: workId, sourceItemId: sourceItemId)
    }

    /// The temporary file becomes the book, replacing any earlier copy. An
    /// empty file, or one that is not a ZIP, is refused.
    public func promote(workId: String, sourceItemId: String) throws -> URL {
        let temporary = temporaryFile(workId: workId, sourceItemId: sourceItemId)
        let target = completeFile(workId: workId, sourceItemId: sourceItemId)
        guard let handle = try? FileHandle(forReadingFrom: temporary) else { throw CocoaError(.fileReadNoSuchFile) }
        let head = try handle.read(upToCount: 4) ?? Data()
        try handle.close()
        guard head == Data([0x50, 0x4B, 0x03, 0x04]) else { throw CocoaError(.fileReadCorruptFile) }
        if FileManager.default.fileExists(atPath: target.path) {
            _ = try FileManager.default.replaceItemAt(target, withItemAt: temporary)
        } else {
            try FileManager.default.moveItem(at: temporary, to: target)
        }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path)
        return target
    }

    /// The book was opened: it is the last to go.
    public func touch(workId: String, sourceItemId: String) {
        try? FileManager.default.setAttributes([.modificationDate: Date()],
                                               ofItemAtPath: completeFile(workId: workId, sourceItemId: sourceItemId).path)
    }

    public func remove(workId: String, sourceItemId: String) {
        try? FileManager.default.removeItem(at: completeFile(workId: workId, sourceItemId: sourceItemId))
        try? FileManager.default.removeItem(at: temporaryFile(workId: workId, sourceItemId: sourceItemId))
    }

    /// Down to `budgetBytes`, keeping the book `keeping` (the one open).
    public func prune(budgetBytes: Int64 = EpubPackageCachePolicy.budgetBytes, keeping: URL? = nil) {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let files = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: keys)) ?? []
        let entries = files.filter { $0.pathExtension == "epub" }.map { file -> EpubCacheEntry in
            let values = try? file.resourceValues(forKeys: Set(keys))
            return EpubCacheEntry(id: file.lastPathComponent, sizeBytes: Int64(values?.fileSize ?? 0),
                                  lastAccessMillis: Int64((values?.contentModificationDate ?? .distantPast).timeIntervalSince1970 * 1_000),
                                  active: file.lastPathComponent == keeping?.lastPathComponent)
        }
        for id in EpubPackageCachePolicy.evict(entries, budgetBytes: budgetBytes) {
            try? FileManager.default.removeItem(at: root.appendingPathComponent(id))
        }
    }

    static func stableName(_ workId: String, _ sourceItemId: String) -> String {
        String(String((workId + "_" + sourceItemId).map { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" ? $0 : "_" })
            .prefix(160))
    }
}
