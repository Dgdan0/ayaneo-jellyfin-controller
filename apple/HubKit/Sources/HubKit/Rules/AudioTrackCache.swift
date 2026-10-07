import CryptoKit
import Foundation

/// The streamed audiobook tracks kept on this device (#37; Android's
/// `AudioStreams`): each track whole, under its key (`AudiobookStream.cacheKey`:
/// the book, the track and its validator, so a changed file is a new key),
/// 512 MB at most, the least recently played let go first
/// (`EpubPackageCachePolicy.evict`, which the EPUBs use too). A track is
/// played from here once it is kept, so a book plays on through an outage
/// and the next track starts at once. Remove offline copy lets a book's go.
public struct AudioTrackCache: Sendable {
    /// About 18 hours of a 64 kbps book, as on the Pocket.
    public static let budgetBytes: Int64 = 512 << 20

    public let root: URL

    public init(root: URL) {
        self.root = root
    }

    /// Where a track is kept: its book's name first, so a book's tracks are
    /// found together, then its key's digest, then the extension its type
    /// needs for the player to read it.
    public func file(sourceItemId: String, key: String, ext: String) -> URL {
        let digest = SHA256.hash(data: Data(key.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent(Self.bookName(sourceItemId) + digest + "." + ext)
    }

    /// Whether the track is kept, without marking it played.
    public func has(sourceItemId: String, key: String, ext: String) -> Bool {
        Self.size(file(sourceItemId: sourceItemId, key: key, ext: ext)) > 0
    }

    /// The track when it is kept, marked as played now; nil when it is not.
    public func kept(sourceItemId: String, key: String, ext: String, now: Date = Date()) -> URL? {
        let target = file(sourceItemId: sourceItemId, key: key, ext: ext)
        guard Self.size(target) > 0 else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: now], ofItemAtPath: target.path)
        return target
    }

    /// A track's bytes kept whole, written beside and moved into place, then
    /// the cache brought under its budget without the tracks in `keeping`
    /// (the one playing and this one). An empty answer is not kept.
    @discardableResult
    public func store(_ data: Data, sourceItemId: String, key: String, ext: String, keeping: [URL] = [],
                      budgetBytes: Int64 = budgetBytes, now: Date = Date()) throws -> URL {
        guard !data.isEmpty, Int64(data.count) <= budgetBytes else { throw CocoaError(.fileWriteOutOfSpace) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let target = file(sourceItemId: sourceItemId, key: key, ext: ext)
        let temporary = target.appendingPathExtension("part")
        try data.write(to: temporary, options: .atomic)
        if FileManager.default.fileExists(atPath: target.path) {
            _ = try FileManager.default.replaceItemAt(target, withItemAt: temporary)
        } else {
            try FileManager.default.moveItem(at: temporary, to: target)
        }
        try? FileManager.default.setAttributes([.modificationDate: now], ofItemAtPath: target.path)
        prune(budgetBytes: budgetBytes, keeping: keeping + [target])
        return target
    }

    /// How much of these books' tracks this device keeps.
    public func bytes(sourceItemIds: [String]) -> Int64 {
        files(of: sourceItemIds).reduce(0) { $0 + Self.size($1) }
    }

    /// Lets go of these books' tracks (Remove offline copy).
    public func remove(sourceItemIds: [String]) {
        for file in files(of: sourceItemIds) { try? FileManager.default.removeItem(at: file) }
    }

    /// Down to `budgetBytes`, the least recently played first, never a track in `keeping`.
    public func prune(budgetBytes: Int64 = budgetBytes, keeping: [URL] = []) {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let held = Set(keeping.map(\.lastPathComponent))
        let entries = all().map { file -> EpubCacheEntry in
            let values = try? file.resourceValues(forKeys: Set(keys))
            return EpubCacheEntry(id: file.lastPathComponent, sizeBytes: Int64(values?.fileSize ?? 0),
                                  lastAccessMillis: Int64((values?.contentModificationDate ?? .distantPast).timeIntervalSince1970 * 1_000),
                                  active: held.contains(file.lastPathComponent))
        }
        for id in EpubPackageCachePolicy.evict(entries, budgetBytes: budgetBytes) {
            try? FileManager.default.removeItem(at: root.appendingPathComponent(id))
        }
    }

    /// The extension a track's type needs for AVFoundation to read the file:
    /// from its MIME type, else its own name, else MP3.
    public static func fileExtension(mime: String, title: String = "") -> String {
        switch mime.lowercased().split(separator: ";").first.map({ $0.trimmingCharacters(in: .whitespaces) }) ?? "" {
        case "audio/mpeg", "audio/mp3", "audio/mpeg3": return "mp3"
        case "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "audio/x-aac": return "m4a"
        case "audio/x-m4b", "audio/m4b": return "m4b"
        case "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave": return "wav"
        case "audio/flac", "audio/x-flac": return "flac"
        case "audio/aiff", "audio/x-aiff": return "aiff"
        default:
            let own = (title as NSString).pathExtension.lowercased()
            return ["mp3", "m4a", "m4b", "aac", "wav", "flac", "aiff", "caf"].contains(own) ? own : "mp3"
        }
    }

    // MARK: Files

    private func all() -> [URL] {
        ((try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension != "part" }
    }

    private func files(of sourceItemIds: [String]) -> [URL] {
        let prefixes = sourceItemIds.filter { !$0.isEmpty }.map(Self.bookName)
        return all().filter { file in prefixes.contains { file.lastPathComponent.hasPrefix($0) } }
    }

    /// A book's part of a file name: its id made safe for a name, and a
    /// separator no id leaves in it.
    static func bookName(_ sourceItemId: String) -> String {
        String(sourceItemId.map { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" ? $0 : "_" }.prefix(80)) + "~"
    }

    static func size(_ file: URL) -> Int64 {
        (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value ?? 0
    }
}
