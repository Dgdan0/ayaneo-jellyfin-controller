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

/// Whether a kept EPUB is still the hub's (#41; Android's `EpubPackageCache`
/// does the same). The hub serves each ebook as its reading copy, with a
/// strong ETag, and builds it again when its rules change (font sizes that
/// follow the text size, two pages from 30em): a copy kept for ever would
/// keep the old file. So a kept copy is checked with `If-None-Match` as it
/// opens, quickly, and an outage opens it as it is.
public enum EpubFreshness {
    /// What opening a book asks the hub.
    public enum Ask: Equatable, Sendable {
        /// Nothing kept, or a fresh download asked for: the whole file.
        case download
        /// A copy kept with its ETag: whether it changed (`If-None-Match`).
        case check(etag: String)
        /// A copy kept before ETags were kept: the file once more.
        case refetch
        /// A copy the hub sent without an ETag: nothing to ask, it opens as it is.
        case keep
    }

    /// `etag` is what is kept beside the copy: nil when nothing is (a copy
    /// kept before #41), empty when the hub sent none.
    public static func ask(complete: Bool, etag: String?, force: Bool) -> Ask {
        guard complete, !force else { return .download }
        guard let etag else { return .refetch }
        return etag.isEmpty ? .keep : .check(etag: etag)
    }

    /// A copy kept opens without the hub: its check has a short timeout and no retries.
    public static func quick(_ ask: Ask) -> Bool { ask != .download }

    /// What the hub answered.
    public enum Answer: Equatable, Sendable {
        /// 304.
        case unchanged
        /// The file, and its ETag when the hub sent one.
        case file(etag: String?)
        /// No answer: no network, a timeout, or a refusal.
        case failed
    }

    public enum Outcome: Equatable, Sendable {
        /// The copy kept opens.
        case openKept
        /// The file sent replaces it, its ETag kept beside it (empty when it had none).
        case replace(etag: String)
        /// Nothing to open: the failure is said.
        case fail
    }

    public static func outcome(_ ask: Ask, _ answer: Answer) -> Outcome {
        switch (ask, answer) {
        case (_, .file(let etag)): .replace(etag: etag ?? "")
        // Nothing kept and nothing sent.
        case (.download, _): .fail
        // 304, or the hub out of reach: what is kept is what there is.
        case (.check, _), (.refetch, _), (.keep, _): .openKept
        }
    }
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

    /// The ETag the hub sent with the copy, beside it (#41).
    public func etagFile(workId: String, sourceItemId: String) -> URL {
        root.appendingPathComponent(Self.stableName(workId, sourceItemId) + ".etag")
    }

    /// The kept copy's ETag: nil when none is kept (a copy from before #41),
    /// empty when the hub sent none.
    public func etag(workId: String, sourceItemId: String) -> String? {
        guard let data = try? Data(contentsOf: etagFile(workId: workId, sourceItemId: sourceItemId)) else { return nil }
        return String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public func isComplete(workId: String, sourceItemId: String) -> Bool {
        let size = (try? FileManager.default.attributesOfItem(atPath: completeFile(workId: workId, sourceItemId: sourceItemId).path)[.size]
                    as? NSNumber)?.int64Value ?? 0
        return size > 0
    }

    /// `writer` fills the temporary file; it is moved into place only when it
    /// is complete, and the temporary file never outlives the call. `etag` is
    /// kept beside the book (see `promote`).
    @discardableResult
    public func install(workId: String, sourceItemId: String, etag: String? = nil,
                        writer: (URL) throws -> Void) throws -> URL {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let temporary = temporaryFile(workId: workId, sourceItemId: sourceItemId)
        try? FileManager.default.removeItem(at: temporary)
        defer { try? FileManager.default.removeItem(at: temporary) }
        try writer(temporary)
        return try promote(workId: workId, sourceItemId: sourceItemId, etag: etag)
    }

    /// The temporary file becomes the book, replacing any earlier copy. An
    /// empty file, or one that is not a ZIP, is refused. The earlier copy's
    /// ETag goes first, and `etag` (empty when the hub sent none) is kept
    /// beside the new one; were the app to stop between the two, the book
    /// would only be fetched once more.
    public func promote(workId: String, sourceItemId: String, etag: String? = nil) throws -> URL {
        let temporary = temporaryFile(workId: workId, sourceItemId: sourceItemId)
        let target = completeFile(workId: workId, sourceItemId: sourceItemId)
        let validator = etagFile(workId: workId, sourceItemId: sourceItemId)
        guard let handle = try? FileHandle(forReadingFrom: temporary) else { throw CocoaError(.fileReadNoSuchFile) }
        let head = try handle.read(upToCount: 4) ?? Data()
        try handle.close()
        guard head == Data([0x50, 0x4B, 0x03, 0x04]) else { throw CocoaError(.fileReadCorruptFile) }
        try? FileManager.default.removeItem(at: validator)
        if FileManager.default.fileExists(atPath: target.path) {
            _ = try FileManager.default.replaceItemAt(target, withItemAt: temporary)
        } else {
            try FileManager.default.moveItem(at: temporary, to: target)
        }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path)
        if let etag { try? Data(etag.utf8).write(to: validator, options: .atomic) }
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
        try? FileManager.default.removeItem(at: etagFile(workId: workId, sourceItemId: sourceItemId))
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
            let book = root.appendingPathComponent(id)
            try? FileManager.default.removeItem(at: book)
            try? FileManager.default.removeItem(at: book.deletingPathExtension().appendingPathExtension("etag"))
        }
    }

    /// The book to open (#41): a kept copy checked with the hub
    /// (`EpubFreshness`), replaced when the hub's has changed and opened as
    /// it is in an outage; with nothing kept, `request` downloads it. `force`
    /// drops the copy first (Try again on a book that would not open).
    /// Throws the hub's failure when there is nothing to open, or the error
    /// that kept a download from becoming the book.
    public func open(workId: String, sourceItemId: String, request: HubRequest, hub: HubClient,
                     force: Bool = false) async throws -> URL {
        if force { remove(workId: workId, sourceItemId: sourceItemId) }
        let ask = EpubFreshness.ask(complete: isComplete(workId: workId, sourceItemId: sourceItemId),
                                    etag: etag(workId: workId, sourceItemId: sourceItemId), force: force)
        var sent = Data()
        var failure = HubFailure(.badResponse)
        let answer: EpubFreshness.Answer
        if ask == .keep {
            answer = .unchanged
        } else {
            let kept: String? = if case .check(let etag) = ask { etag } else { nil }
            do throws(HubFailure) {
                switch try await hub.file(request, ifNoneMatch: kept, quick: EpubFreshness.quick(ask)) {
                case .unchanged:
                    answer = .unchanged
                case .file(let data, let etag):
                    sent = data
                    answer = .file(etag: etag)
                }
            } catch {
                if error.kind == .cancelled { throw error }
                failure = error
                answer = .failed
            }
        }
        switch EpubFreshness.outcome(ask, answer) {
        case .openKept:
            touch(workId: workId, sourceItemId: sourceItemId)
            return completeFile(workId: workId, sourceItemId: sourceItemId)
        case .replace(let etag):
            do {
                let file = try install(workId: workId, sourceItemId: sourceItemId, etag: etag) { try sent.write(to: $0) }
                prune(keeping: file)
                return file
            } catch {
                // What came is no book, or there is no room for it: a copy kept still opens.
                guard ask != .download else { throw error }
                touch(workId: workId, sourceItemId: sourceItemId)
                return completeFile(workId: workId, sourceItemId: sourceItemId)
            }
        case .fail:
            throw failure
        }
    }

    static func stableName(_ workId: String, _ sourceItemId: String) -> String {
        String(String((workId + "_" + sourceItemId).map { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" ? $0 : "_" })
            .prefix(160))
    }
}
