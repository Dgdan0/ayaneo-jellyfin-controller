import Foundation
import HubKit

/// What the Books side keeps on this device, where, and Remove offline copy
/// (#37; Android's `removeOfflineReading`): a book's EPUBs and read-along
/// editions, its audiobook's tracks, and its comics' page lists and the pages
/// read. Server files, bookmarks and reading progress are never touched.
@MainActor
enum ReadingOffline {
    private static var caches: URL { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0] }

    /// This hub's and profile's EPUBs.
    static func ebooks(app: AppModel) -> EpubPackageCache {
        EpubPackageCache(root: EpubPackageCache.folder(base: caches.appendingPathComponent("reading-epub", isDirectory: true),
                                                       address: app.address, userId: app.userId))
    }

    /// The read-along editions, without their audio, beside the ebooks (`aligned-slim`, as on the Pocket).
    static func readAlong(app: AppModel) -> EpubPackageCache {
        EpubPackageCache(root: ebooks(app: app).root.appendingPathComponent("aligned-slim", isDirectory: true))
    }

    /// The comics' page lists, kept to reopen an issue in an outage; by account inside (`ReadingCheckpointKey`).
    static func manifests(app: AppModel) -> ReadingManifestCache {
        ReadingManifestCache(root: caches.appendingPathComponent("reading-manifests", isDirectory: true))
    }

    /// Whose page lists these are.
    static func scope(app: AppModel) -> String {
        ReadingCheckpointKey.scope(address: app.address, userId: app.userId)
    }

    /// How much of `work` this device keeps.
    static func bytes(_ work: ReadingWork, app: AppModel) async -> Int64 {
        let ids = sourceItemIds(work)
        var total = ListeningTracks.cache(address: app.address).bytes(sourceItemIds: ids)
        for id in ids {
            total += size(ebooks(app: app).completeFile(workId: work.id, sourceItemId: id))
            total += size(readAlong(app: app).completeFile(workId: work.id, sourceItemId: id))
        }
        return total + (await app.hub.keptImageBytes(pagePaths(work, app: app)))
    }

    /// Lets go of everything this device keeps of `work`.
    static func remove(_ work: ReadingWork, app: AppModel) async {
        let ids = sourceItemIds(work)
        ListeningTracks.cache(address: app.address).remove(sourceItemIds: ids)
        for id in ids {
            ebooks(app: app).remove(workId: work.id, sourceItemId: id)
            readAlong(app: app).remove(workId: work.id, sourceItemId: id)
        }
        await app.hub.forgetImages(pagePaths(work, app: app))
        for key in pageKeys(work, app: app) { manifests(app: app).remove(key) }
    }

    /// The work's editions and, for a comic run, its issues.
    static func sourceItemIds(_ work: ReadingWork) -> [String] {
        var seen = Set<String>()
        return (work.editions.map(\.sourceItemId) + work.sections.flatMap(\.items).map(\.sourceItemId))
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    private static func pageKeys(_ work: ReadingWork, app: AppModel) -> [ReadingCheckpointKey] {
        let scope = scope(app: app)
        return sourceItemIds(work).map { ReadingCheckpointKey(scope: scope, workId: work.id, sourceItemId: $0, kind: "pages") }
    }

    /// Every page of the issues this device kept a page list for: the pages it may hold.
    private static func pagePaths(_ work: ReadingWork, app: AppModel) -> [String] {
        let kept = manifests(app: app)
        return pageKeys(work, app: app).flatMap { key -> [String] in
            guard let manifest = kept.read(key) else { return [] }
            return (0..<manifest.pageCount).map {
                HubEndpoints.readingPublicationPage(workId: key.workId, sourceItemId: key.sourceItemId, page: $0)
            }
        }
    }

    private static func size(_ file: URL) -> Int64 {
        (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value ?? 0
    }
}
