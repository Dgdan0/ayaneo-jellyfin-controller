import Foundation
import HubKit

/// The audiobook's tracks kept on this device (#37; Android's `AudioStreams`
/// and the player's `prefetchNext`): a track plays from here once it is kept,
/// else from the hub while it is fetched whole behind it, and the next track
/// is fetched ahead, so the change of track plays at once and a book plays on
/// through an outage. One track at a time, after the part playing has started,
/// so the stream is never short of the network.
@MainActor
final class ListeningTracks {
    static let shared = ListeningTracks()

    /// A track to keep: its book, its key, the extension its type needs and its route.
    struct Track: Equatable {
        let sourceItemId: String
        let key: String
        let ext: String
        let path: String
    }

    private var pending: [Track] = []
    private var working: Track?
    private var worker: Task<Void, Never>?
    private var onKept: (@MainActor (Track) -> Void)?

    /// This hub's tracks, beside the ebooks: one folder per hub, whichever profile listens.
    static func cache(address: String) -> AudioTrackCache {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("reading-audio-stream", isDirectory: true)
        return AudioTrackCache(root: EpubPackageCache.folder(base: caches, address: address, userId: ""))
    }

    /// Track `index` of `manifest`, as it is kept and asked for.
    static func track(_ index: Int, manifest: ReadingAudioManifest, parts: [AudiobookPart], workId: String,
                      sourceItemId: String) -> Track? {
        guard parts.indices.contains(index), let entry = manifest.tracks.first(where: { $0.index == index }),
              !parts[index].cacheKey.isEmpty else { return nil }
        return Track(sourceItemId: sourceItemId, key: parts[index].cacheKey,
                     ext: AudioTrackCache.fileExtension(mime: entry.mime, title: entry.title),
                     path: HubEndpoints.readingAudioTrack(workId: workId, sourceItemId: sourceItemId, index: index,
                                                          revision: manifest.revision))
    }

    /// The kept file for `track`, marked as played now; nil when it is not kept.
    func kept(_ track: Track, in cache: AudioTrackCache) -> URL? {
        cache.kept(sourceItemId: track.sourceItemId, key: track.key, ext: track.ext)
    }

    /// Fetches whichever of `tracks` is not kept yet, in order, one at a time.
    /// A track already on its way is left to finish; any other fetch stops.
    func keep(_ tracks: [Track], hub: HubClient, cache: AudioTrackCache, playing: URL?,
              kept: @escaping @MainActor (Track) -> Void) {
        onKept = kept
        let missing = tracks.filter { !cache.has(sourceItemId: $0.sourceItemId, key: $0.key, ext: $0.ext) }
        if let working, missing.contains(working) {
            pending = missing.filter { $0 != working }
            return
        }
        worker?.cancel()
        working = nil
        pending = missing
        guard !missing.isEmpty else { return }
        worker = Task { [weak self] in await self?.run(hub: hub, cache: cache, playing: playing) }
    }

    /// Nothing more is fetched (the book left the player).
    func stop() {
        worker?.cancel()
        worker = nil
        working = nil
        pending = []
        onKept = nil
    }

    private func run(hub: HubClient, cache: AudioTrackCache, playing: URL?) async {
        while !Task.isCancelled, !pending.isEmpty {
            let next = pending.removeFirst()
            working = next
            let data: Data
            do {
                data = try await hub.data(HubRequest(next.path, slow: true))
            } catch {
                // Offline, or refused: tried again when the next part starts.
                working = nil
                return
            }
            guard !Task.isCancelled else { break }
            let keeping = playing.map { [$0] } ?? []
            let stored = await Task.detached(priority: .utility) {
                (try? cache.store(data, sourceItemId: next.sourceItemId, key: next.key, ext: next.ext, keeping: keeping)) != nil
            }.value
            #if DEBUG
            NSLog("listen: %@ %@ on the device", stored ? "kept" : "could not keep", next.key)
            #endif
            working = nil
            if stored, !Task.isCancelled { onKept?(next) }
        }
        working = nil
    }
}
