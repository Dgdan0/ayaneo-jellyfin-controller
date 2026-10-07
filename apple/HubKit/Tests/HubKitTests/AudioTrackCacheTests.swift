import Foundation
import Testing
@testable import HubKit

/// The streamed tracks kept on the device (#37): whole tracks under their
/// keys, the least recently played first out past the budget, and a book's
/// let go on its own.
struct AudioTrackCacheTests {
    private let cache = AudioTrackCache(root: FileManager.default.temporaryDirectory
        .appendingPathComponent("audio-track-cache-\(UUID().uuidString)", isDirectory: true))

    private func bytes(_ count: Int) -> Data { Data(repeating: 7, count: count) }

    @Test func aTrackKeptIsFoundAgainAndAChangedFileIsANewKey() throws {
        defer { try? FileManager.default.removeItem(at: cache.root) }
        #expect(cache.kept(sourceItemId: "dm", key: "reading-audio:dm:t_1:e1", ext: "mp3") == nil)
        let file = try cache.store(bytes(10), sourceItemId: "dm", key: "reading-audio:dm:t_1:e1", ext: "mp3")
        #expect(file.pathExtension == "mp3")
        #expect(AudioTrackCache(root: cache.root).kept(sourceItemId: "dm", key: "reading-audio:dm:t_1:e1", ext: "mp3") == file)
        // A new validator is a new file: the old one is not played for it.
        #expect(cache.kept(sourceItemId: "dm", key: "reading-audio:dm:t_1:e2", ext: "mp3") == nil)
        #expect(throws: (any Error).self) { try cache.store(Data(), sourceItemId: "dm", key: "k", ext: "mp3") }
    }

    @Test func pastTheBudgetTheLeastRecentlyPlayedGoesFirst() throws {
        defer { try? FileManager.default.removeItem(at: cache.root) }
        let day = Date(timeIntervalSince1970: 1_000_000)
        let first = try cache.store(bytes(40), sourceItemId: "a", key: "1", ext: "mp3", budgetBytes: 100, now: day)
        let second = try cache.store(bytes(40), sourceItemId: "a", key: "2", ext: "mp3", budgetBytes: 100,
                                     now: day.addingTimeInterval(60))
        // The first played again: the second is now the least recently played.
        #expect(cache.kept(sourceItemId: "a", key: "1", ext: "mp3", now: day.addingTimeInterval(120)) == first)
        let third = try cache.store(bytes(40), sourceItemId: "b", key: "3", ext: "mp3", budgetBytes: 100,
                                    now: day.addingTimeInterval(180))
        #expect(FileManager.default.fileExists(atPath: first.path))
        #expect(!FileManager.default.fileExists(atPath: second.path))
        #expect(FileManager.default.fileExists(atPath: third.path))
        // The track playing is kept even when it is the oldest.
        _ = try cache.store(bytes(40), sourceItemId: "b", key: "4", ext: "mp3", keeping: [first], budgetBytes: 100,
                            now: day.addingTimeInterval(240))
        #expect(FileManager.default.fileExists(atPath: first.path))
        #expect(!FileManager.default.fileExists(atPath: third.path))
        // A track bigger than the whole budget is not kept.
        #expect(throws: (any Error).self) {
            try cache.store(bytes(101), sourceItemId: "c", key: "5", ext: "mp3", budgetBytes: 100)
        }
    }

    @Test func aBooksTracksAreCountedAndLetGoOnTheirOwn() throws {
        defer { try? FileManager.default.removeItem(at: cache.root) }
        try cache.store(bytes(10), sourceItemId: "demo-dm", key: "1", ext: "wav")
        try cache.store(bytes(15), sourceItemId: "demo-dm", key: "2", ext: "wav")
        try cache.store(bytes(20), sourceItemId: "demo-alloy", key: "1", ext: "wav")
        // A book whose id starts like another's is not counted with it.
        try cache.store(bytes(5), sourceItemId: "demo-d", key: "1", ext: "wav")
        let dm: Int64 = 25
        #expect(cache.bytes(sourceItemIds: ["demo-dm"]) == dm)
        #expect(cache.bytes(sourceItemIds: ["demo-dm", "demo-alloy"]) == dm + 20)
        cache.remove(sourceItemIds: ["demo-dm"])
        #expect(cache.bytes(sourceItemIds: ["demo-dm"]) == 0)
        let left: Int64 = 20
        #expect(cache.bytes(sourceItemIds: ["demo-alloy"]) == left)
        #expect(cache.bytes(sourceItemIds: ["demo-d"]) == 5)
    }

    @Test func aTracksTypeGivesTheExtensionThePlayerNeeds() {
        #expect(AudioTrackCache.fileExtension(mime: "audio/mpeg") == "mp3")
        #expect(AudioTrackCache.fileExtension(mime: "audio/mp4; codecs=mp4a") == "m4a")
        #expect(AudioTrackCache.fileExtension(mime: "audio/x-m4b") == "m4b")
        #expect(AudioTrackCache.fileExtension(mime: "audio/wav") == "wav")
        #expect(AudioTrackCache.fileExtension(mime: "", title: "01 Opening.M4A") == "m4a")
        #expect(AudioTrackCache.fileExtension(mime: "application/octet-stream", title: "Track 1") == "mp3")
    }
}
