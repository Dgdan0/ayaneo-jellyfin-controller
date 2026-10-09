import Foundation

/// A comic's or a manga's page list as the hub last gave it (#37; Android's
/// `ReadingManifestCache`), kept so the issue reopens in an outage with the
/// pages this device still has. Kept by account, book and edition
/// (`ReadingCheckpointKey`), so it never opens for another profile or
/// another issue. It keeps the hub's answer as it came; it does not keep the
/// pages, which the artwork cache holds as they were read.
public struct ReadingManifestCache: Sendable {
    public let root: URL

    public init(root: URL) {
        self.root = root
    }

    /// The kept page list, if it is this key's.
    public func read(_ key: ReadingCheckpointKey) -> ReadingPublicationManifest? {
        guard let data = try? Data(contentsOf: file(key)),
              let manifest = try? JSONDecoder().decode(ReadingPublicationManifest.self, from: data),
              manifest.workId == key.workId, manifest.sourceItemId == key.sourceItemId else { return nil }
        return manifest
    }

    /// The hub's answer kept for `key`, written beside and moved into place;
    /// an answer for another book or edition is refused.
    public func save(_ key: ReadingCheckpointKey, answer: Data) throws {
        guard let manifest = try? JSONDecoder().decode(ReadingPublicationManifest.self, from: answer),
              manifest.workId == key.workId, manifest.sourceItemId == key.sourceItemId else {
            throw CocoaError(.fileWriteInvalidFileName)
        }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try answer.write(to: file(key), options: .atomic)
    }

    public func remove(_ key: ReadingCheckpointKey) {
        try? FileManager.default.removeItem(at: file(key))
    }

    /// The book `workId` was started over (#60): every page list kept of it
    /// opens at its first page again. The rest of each answer stays as it came.
    public func dropPlace(workId: String) {
        let files = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        for file in files where file.pathExtension == "json" {
            guard let data = try? Data(contentsOf: file),
                  var object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  object["workId"] as? String == workId,
                  (object["currentPage"] as? NSNumber)?.intValue ?? 0 != 0 else { continue }
            object["currentPage"] = 0
            if let changed = try? JSONSerialization.data(withJSONObject: object) { try? changed.write(to: file, options: .atomic) }
        }
    }

    func file(_ key: ReadingCheckpointKey) -> URL {
        root.appendingPathComponent(key.fileName + ".json")
    }
}
