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
        ReadingManifestCache(root: manifestsRoot)
    }

    /// Where they are, for Start over (#60), which forgets their pages from any thread.
    nonisolated static var manifestsRoot: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("reading-manifests", isDirectory: true)
    }

    /// Whose page lists these are.
    static func scope(app: AppModel) -> String {
        ReadingCheckpointKey.scope(address: app.address, userId: app.userId)
    }

    /// Which books this hub's profile keeps here (#43): the Books side's Downloads lists them.
    static func shelf(app: AppModel) -> ReadingKeptShelf {
        shelf(address: app.address, userId: app.userId)
    }

    nonisolated static func shelf(address: String, userId: String) -> ReadingKeptShelf {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("reading-kept", isDirectory: true)
        return ReadingKeptShelf(file: EpubPackageCache.folder(base: base, address: address, userId: userId)
            .appendingPathComponent("books.json"))
    }

    /// A book a reader keeps on this device: an ebook or read-along edition
    /// opened, an audiobook put on the player, a comic's issue opened.
    nonisolated static func kept(address: String, userId: String, workId: String, title: String, artwork: String,
                                 kind: String, sourceItemId: String) {
        shelf(address: address, userId: userId).record(workId: workId, title: title, artwork: artwork, kind: kind,
                                                       sourceItemId: sourceItemId,
                                                       now: Int64(Date().timeIntervalSince1970 * 1_000))
    }

    /// How much of `work` this device keeps.
    static func bytes(_ work: ReadingWork, app: AppModel) async -> Int64 {
        await bytes(workId: work.id, sourceItemIds: sourceItemIds(work), app: app)
    }

    /// How much of a work's editions and issues this device keeps.
    static func bytes(workId: String, sourceItemIds ids: [String], app: AppModel) async -> Int64 {
        var total = ListeningTracks.cache(address: app.address).bytes(sourceItemIds: ids)
        for id in ids {
            total += size(ebooks(app: app).completeFile(workId: workId, sourceItemId: id))
            total += size(readAlong(app: app).completeFile(workId: workId, sourceItemId: id))
        }
        return total + (await app.hub.keptImageBytes(pagePaths(workId: workId, ids, app: app)))
    }

    /// Lets go of everything this device keeps of `work`.
    static func remove(_ work: ReadingWork, app: AppModel) async {
        await remove(workId: work.id, sourceItemIds: Array(Set(sourceItemIds(work) + keptIds(work.id, app: app))), app: app)
    }

    /// Lets go of a work's editions and issues on this device, and of its place on the shelf.
    static func remove(workId: String, sourceItemIds ids: [String], app: AppModel) async {
        ListeningTracks.cache(address: app.address).remove(sourceItemIds: ids)
        for id in ids {
            ebooks(app: app).remove(workId: workId, sourceItemId: id)
            readAlong(app: app).remove(workId: workId, sourceItemId: id)
        }
        await app.hub.forgetImages(pagePaths(workId: workId, ids, app: app))
        for key in pageKeys(workId: workId, ids, app: app) { manifests(app: app).remove(key) }
        shelf(app: app).remove(workId: workId)
    }

    /// The editions and issues the shelf says were kept of `workId`.
    private static func keptIds(_ workId: String, app: AppModel) -> [String] {
        shelf(app: app).list().first { $0.workId == workId }?.sourceItemIds ?? []
    }

    /// The work's editions and, for a comic run, its issues.
    static func sourceItemIds(_ work: ReadingWork) -> [String] {
        var seen = Set<String>()
        return (work.editions.map(\.sourceItemId) + work.sections.flatMap(\.items).map(\.sourceItemId))
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    private static func pageKeys(workId: String, _ ids: [String], app: AppModel) -> [ReadingCheckpointKey] {
        let scope = scope(app: app)
        return ids.map { ReadingCheckpointKey(scope: scope, workId: workId, sourceItemId: $0, kind: "pages") }
    }

    /// Every page of the issues this device kept a page list for: the pages it may hold.
    private static func pagePaths(workId: String, _ ids: [String], app: AppModel) -> [String] {
        let kept = manifests(app: app)
        return pageKeys(workId: workId, ids, app: app).flatMap { key -> [String] in
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
