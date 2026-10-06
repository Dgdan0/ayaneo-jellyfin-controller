import CryptoKit
import Foundation

/// A book's bookmarks, kept on this device (#25, phase 4): Android's
/// `reader/EpubBookmarkStore.kt`, with its test cases. They belong to one hub
/// and profile (`scope`) and one edition, as reading places do; a file per
/// edition, named as phase 2's `ReadingCheckpointKey` names its records (the
/// SHA-256 of the scope, the work, the edition and "epub"), so the two can
/// share a key once both are here. A record that cannot be read is an error,
/// never an empty list that the next bookmark would write over.
public struct EpubBookmark: Codable, Equatable, Sendable {
    /// The part and a point in it that survives the book being laid out again (`BookLocator.anchor`).
    public let anchor: String
    /// Readium's locator, as its JSON.
    public let locator: String
    /// "Chapter 3 · 41%".
    public let label: String
}

public struct EpubBookmarks: Sendable {
    public let root: URL
    public let scope: String
    public let workId: String
    public let sourceItemId: String

    public init(root: URL, scope: String, workId: String, sourceItemId: String) {
        self.root = root
        self.scope = scope
        self.workId = workId
        self.sourceItemId = sourceItemId
    }

    /// Whose bookmarks: the hub, without the slashes an address may end in, and the profile.
    public static func scope(address: String, userId: String) -> String {
        var trimmed = Substring(address.trimmingCharacters(in: .whitespacesAndNewlines))
        while trimmed.hasSuffix("/") { trimmed = trimmed.dropLast() }
        return digest(String(trimmed) + "\u{0}" + userId)
    }

    public func list() throws -> [EpubBookmark] {
        guard FileManager.default.fileExists(atPath: file.path) else { return [] }
        return try JSONDecoder().decode([EpubBookmark].self, from: Data(contentsOf: file))
    }

    public func contains(_ locator: String) throws -> Bool {
        guard let anchor = BookLocator.anchor(locator) else { return false }
        return try list().contains { $0.anchor == anchor }
    }

    /// Added, true; there already, removed, false.
    @discardableResult
    public func toggle(_ locator: String) throws -> Bool {
        guard let anchor = BookLocator.anchor(locator) else { throw CocoaError(.coderInvalidValue) }
        let entries = try list()
        if entries.contains(where: { $0.anchor == anchor }) {
            try write(entries.filter { $0.anchor != anchor })
            return false
        }
        let label = BookLocator.label(locator)
        try write(entries + [EpubBookmark(anchor: anchor, locator: locator, label: label.isEmpty ? "Book location" : label)])
        return true
    }

    @discardableResult
    public func remove(anchor: String) throws -> Bool {
        let entries = try list()
        guard entries.contains(where: { $0.anchor == anchor }) else { return false }
        try write(entries.filter { $0.anchor != anchor })
        return true
    }

    var file: URL {
        root.appendingPathComponent(Self.digest([scope, workId, sourceItemId, "epub"].joined(separator: "\u{0}"))
                                    + ".bookmarks.json")
    }

    private func write(_ entries: [EpubBookmark]) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try JSONEncoder().encode(entries).write(to: file, options: .atomic)
    }

    static func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
